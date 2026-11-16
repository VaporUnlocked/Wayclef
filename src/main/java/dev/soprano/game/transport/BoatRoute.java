package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.game.PathFollower;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plans a level source-water or ice route and steers a vanilla boat with movement input. */
public final class BoatRoute implements TransportRoute {
    private static final int SHORE_RADIUS = 2;
    private static final int LOOKAHEAD = 4;
    private static final int ICE_LOOKAHEAD = 18;
    private static final int STALL_TICKS = 100;
    private static final double ARRIVAL_DISTANCE = .44;
    private static final double SETTLED_SPEED = .045;
    private static final double ICE_SETTLED_SPEED = .018;
    private static final double BLUE_ICE_DRAG = .989;
    private static final double FORWARD_PADDLE = .04;
    private static final double BACKWARD_PADDLE = .005;
    private static final double ICE_CRUISE_LIMIT = .68;
    private static final int ICE_STEERING_HORIZON = 35;
    private static final double HULL_MARGIN = .06;
    private static final int SHORE_CONNECTION_RANGE = 24;
    private static final Input DISMOUNT = new Input(false, false, false, false, false, true, false);

    private enum DockPhase { NONE, CHECK_REMOTE, CHECK_SETTLED, DISMOUNTING, WALKING }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Goal requestedGoal;
    private final Navigation.Options options;
    private final AbstractBoat boat;
    private final MinecraftTerrain walkingTerrain;
    private final BoatShore shore;
    private final Set<Node> rejectedDocks = new HashSet<>();

    private IncrementalSearch search;
    private Node laneStart;
    private List<Vec3> waypoints = List.of();
    private Status status = Status.PLANNING;
    private String detail = "Looking for a navigable boat surface.";
    private int surfaceBlockY;
    private boolean onIce;
    private double laneOffsetX;
    private double laneOffsetZ;
    private double surface;
    private double hullBottom;
    private double hullTop;
    private double hullRadius;
    private int point;
    private int stallTicks;
    private int replans;
    private int partialSegments;
    private boolean partial;
    private boolean reversePaddle;
    private String iceStopReason;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private float previousYaw;
    private boolean joinShore;
    private Node dock;
    private DockPhase dockPhase = DockPhase.NONE;
    private BoatShore.Exit exit;
    private IncrementalSearch groundSearch;
    private PathFollower walker;
    private Node walkingStart;
    private List<Vec3> shoreWaypoints = List.of();
    private int dismountTicks;

    public BoatRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.requestedGoal = goal;
        this.options = options;
        this.boat = player.getControlledVehicle() instanceof AbstractBoat current ? current : null;
        walkingTerrain = new MinecraftTerrain(level, player, options.avoidMobs());
        shore = boat == null ? null : new BoatShore(level, player, boat, walkingTerrain, goal);
        if (boat == null) {
            fail("Take control of a boat to use boat routing.");
            return;
        }
        startSearch();
    }

    @Override
    public void advance(int expansions, long budgetNanos) {
        if (status == Status.ARRIVED || status == Status.FAILED) return;
        if (dockPhase == DockPhase.DISMOUNTING && !player.isPassenger()) {
            beginWalking();
            return;
        }
        if (dockPhase == DockPhase.WALKING) {
            if (player.isPassenger() || player.isInLiquid()) fail("The shoreline connection needs dry, unmounted movement.");
            else if (groundSearch != null) checkWalking(expansions, budgetNanos);
            return;
        }
        if (!controlsBoat()) {
            fail("You are no longer controlling the same boat.");
            return;
        }
        if (groundSearch != null) {
            checkWalking(expansions, budgetNanos);
            return;
        }
        if (status != Status.PLANNING) return;
        var result = search.advance(expansions, budgetNanos);
        switch (result.status()) {
            case SEARCHING -> detail = "Checking " + surfaceName() + " and hull clearance (" + result.visited() + " positions).";
            case FOUND, PARTIAL -> {
                double x = boat.getX(), z = boat.getZ();
                Vec3 start = laneCenter(laneStart);
                if (distance(x, z, start) > LOOKAHEAD || !clearSegment(x, z, start.x, start.z, false)) {
                    recover("The boat drifted away from the planned lane.");
                    return;
                }
                partial = result.status() == IncrementalSearch.Status.PARTIAL;
                if (partial && ++partialSegments > options.partialSegmentLimit()) {
                    fail("Boat route reached the partial route limit.");
                    return;
                }
                var route = new ArrayList<Vec3>(result.path().size() + 2);
                route.add(new Vec3(x, surface, z));
                route.add(start);
                for (var step : result.path()) route.add(laneCenter(step.to()));
                waypoints = List.copyOf(route);
                if (!partial && joinShore) {
                    Vec3 last = waypoints.getLast();
                    dock = new Node(Mth.floor(last.x), 0, Mth.floor(last.z));
                    exit = shore.at(last.x, last.z, false);
                    if (exit == null) rejectDock("The proposed shoreline no longer has safe footing.");
                    else planWalking(exit.start(), DockPhase.CHECK_REMOTE);
                    return;
                }
                startMoving();
            }
            case FAILED -> fail("No connected boat route over loaded " + surfaceName() + ". " + result.reason());
        }
    }

    private void startMoving() {
        point = 1;
        previousYaw = boat.getYRot();
        resetProgress();
        status = Status.MOVING;
        detail = partial ? "Boating to the edge of the confirmed route." : "Boat route over " + surfaceName() + " ready.";
    }

    @Override
    public Input input() {
        if (status != Status.MOVING) return Input.EMPTY;
        if (dockPhase == DockPhase.WALKING) return walkShore();
        if (!controlsBoat()) return Input.EMPTY;
        if (dockPhase == DockPhase.DISMOUNTING) return dismount();

        double x = boat.getX(), z = boat.getZ();
        Vec3 motion = boat.getDeltaMovement();
        double speed = motion.horizontalDistance();
        double settled = settledSpeed();
        float yaw = boat.getYRot();
        float turnRate = Mth.wrapDegrees(yaw - previousYaw);
        previousYaw = yaw;
        if (iceStopReason != null) {
            if (speed > ICE_SETTLED_SPEED)
                return steerOnIce(x, z, yaw, turnRate, motion, speed, new Vec3(x, surface, z));
            recover(iceStopReason);
            return Input.EMPTY;
        }

        int last = waypoints.size() - 1;
        while (point < last && distance(x, z, waypoints.get(point)) <= ARRIVAL_DISTANCE
            && (!turnAt(point) || speed <= settled)) {
            point++;
            resetProgress();
        }
        if (point == last && distance(x, z, waypoints.get(last)) <= ARRIVAL_DISTANCE && speed <= settled) {
            if (partial) {
                startSearch();
            } else if (joinShore) {
                exit = shore.at(x, z, true);
                if (exit == null) rejectDock("The settled boat has no safe standing exit.");
                else planWalking(exit.start(), DockPhase.CHECK_SETTLED);
            } else {
                status = Status.ARRIVED;
                detail = "Arrived by boat.";
            }
            return Input.EMPTY;
        }

        Vec3 next = waypoints.get(point);
        double nextDistance = distance(x, z, next);
        if (nextDistance + .05 < bestDistance) {
            bestDistance = nextDistance;
            stallTicks = 0;
        } else if (++stallTicks >= STALL_TICKS) {
            return stopAndRecover("The boat stopped making progress.", x, z, yaw, turnRate, motion, speed);
        }

        if (nextDistance > LOOKAHEAD || !clearSegment(x, z, next.x, next.z, false)) {
            return stopAndRecover("The next part of the boat route is blocked.", x, z, yaw, turnRate, motion, speed);
        }

        Vec3 target = next;
        Vec3 direction = next.subtract(waypoints.get(point - 1));
        double offLane = Math.abs((x - next.x) * direction.z - (z - next.z) * direction.x);
        if (!turnAt(point) && offLane <= direction.horizontalDistance() * .20) {
            int lookahead = onIce ? ICE_LOOKAHEAD : LOOKAHEAD;
            for (int index = point + 1; index <= Math.min(last, point + lookahead - 1); index++) {
                Vec3 farther = waypoints.get(index);
                if (!farther.subtract(target).equals(direction)
                    || !clearSegment(onIce ? target.x : x, onIce ? target.z : z, farther.x, farther.z, false)) break;
                target = farther;
            }
        }
        if (onIce) return steerOnIce(x, z, yaw, turnRate, motion, speed, target);
        return steer(x, z, yaw, turnRate, motion, speed, target);
    }

    @Override
    public Status status() { return status; }

    @Override
    public Vec2 walkDirection() {
        return status == Status.MOVING && dockPhase == DockPhase.WALKING && walker != null ? walker.walkDirection() : null;
    }

    @Override
    public String detail() { return detail; }

    @Override
    public List<Vec3> waypoints() {
        if (shoreWaypoints.isEmpty()) return waypoints;
        List<Vec3> combined = new ArrayList<>(waypoints);
        combined.addAll(shoreWaypoints);
        return List.copyOf(combined);
    }

    @Override
    public boolean allowsDismounted() { return dockPhase == DockPhase.DISMOUNTING || dockPhase == DockPhase.WALKING; }

    private boolean turnAt(int index) {
        if (index == 1 || index == waypoints.size() - 1) return true;
        Vec3 previous = waypoints.get(index - 1), next = waypoints.get(index), after = waypoints.get(index + 1);
        return next.x - previous.x != after.x - next.x || next.z - previous.z != after.z - next.z;
    }

    private boolean controlsBoat() {
        return boat != null && boat.isAlive() && player.isAlive()
            && player.level() == level && player.getControlledVehicle() == boat;
    }

    private void startSearch() {
        if (!findSurface()) {
            fail("Bring the boat onto level source water or an ice lane wide enough for the hull.");
            return;
        }
        if (onIce && boat.getDeltaMovement().horizontalDistance() > ICE_SETTLED_SPEED) {
            fail("Brake and let the boat settle before starting an ice route.");
            return;
        }
        iceStopReason = null;
        boolean differentLevel = requestedGoal.y16() != null && Math.abs(requestedGoal.y16() / 16.0 - surface) > 1.75;
        boolean surfaceTarget = clearAt(requestedGoal.x() + laneOffsetX, requestedGoal.z() + laneOffsetZ, false);
        joinShore = options.transportMode() == TransportMode.AUTO && (differentLevel || !surfaceTarget || requestedGoal.sightY() != null);
        if (differentLevel && !joinShore) {
            fail("The selected destination is at a different elevation from this boat surface.");
            return;
        }
        laneStart = findStart();
        if (laneStart == null) {
            fail("No clear " + surfaceName() + " lane leads away from the boat.");
            return;
        }

        int radius = requestedGoal.radius();
        if (joinShore) radius = Math.max(radius, SHORE_CONNECTION_RANGE);
        else if (radius == 0 && !surfaceTarget) radius = SHORE_RADIUS;
        var laneGoal = new Goal(requestedGoal.x(), 0, requestedGoal.z(), radius);
        var settings = options.search();
        var config = new IncrementalSearch.Config(settings.diagonals(), false, 0, settings.horizontalRange(),
            settings.maxVisited(), settings.allowPartial(), settings.searchMode());
        search = new IncrementalSearch(new LaneTerrain(), laneStart, laneGoal, config);
        waypoints = List.of();
        shoreWaypoints = List.of();
        groundSearch = null;
        dockPhase = DockPhase.NONE;
        status = Status.PLANNING;
        detail = radius > requestedGoal.radius() ? "Looking for a safe place to dismount." : "Checking the " + surfaceName() + " route.";
    }

    private boolean findSurface() {
        int x = Mth.floor(boat.getX()), z = Mth.floor(boat.getZ());
        if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return false;
        double bottom = boat.getBoundingBox().minY;
        hullRadius = boat.getBbWidth() * .5 + HULL_MARGIN;
        hullTop = Math.max(boat.getBoundingBox().maxY, player.getBoundingBox().maxY) + .15;
        onIce = false;
        laneOffsetX = 0;
        laneOffsetZ = 0;
        int top = Mth.floor(boat.getY() + boat.getBbHeight() + .25);
        for (int y = top; y >= top - 2; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (!sourceSurface(pos, fluid)) continue;
            double height = y + fluid.getHeight(level, pos);
            if (bottom > height + .25 || height - bottom > boat.getBbHeight() + .15) continue;
            surfaceBlockY = y;
            surface = height;
            hullBottom = Math.min(bottom - .05, height - .28);
            return true;
        }
        int iceTop = Mth.floor(bottom + .25);
        if (Math.abs(bottom - iceTop) > .25 || !level.getBlockState(new BlockPos(x, iceTop - 1, z)).is(BlockTags.ICE)) return false;
        onIce = true;
        // Keep the route lattice aligned with the boat's lane, including ice that is centered between blocks.
        laneOffsetX = nearestHalfOffset(boat.getX());
        laneOffsetZ = nearestHalfOffset(boat.getZ());
        surfaceBlockY = iceTop - 1;
        surface = iceTop;
        hullBottom = iceTop + .001;
        hullTop = Math.max(hullTop, iceTop + player.getBbHeight() + .15);
        return clearAt(boat.getX(), boat.getZ(), false);
    }

    private Node findStart() {
        double x = boat.getX(), z = boat.getZ();
        int centerX = (int) Math.round(x - laneOffsetX), centerZ = (int) Math.round(z - laneOffsetZ);
        double nearest = Double.POSITIVE_INFINITY;
        Node start = null;
        for (int gx = centerX - 1; gx <= centerX + 1; gx++) {
            if (!Node.validColumn(gx)) continue;
            for (int gz = centerZ - 1; gz <= centerZ + 1; gz++) {
                if (!Node.validColumn(gz)) continue;
                double laneX = gx + laneOffsetX, laneZ = gz + laneOffsetZ;
                double distance = Math.hypot(x - laneX, z - laneZ);
                if (distance >= nearest || !clearSegment(x, z, laneX, laneZ, false)) continue;
                nearest = distance;
                start = new Node(gx, 0, gz);
            }
        }
        return start;
    }

    private boolean clearSegment(double x, double z, double toX, double toZ, boolean entities) {
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        if (exclusions.blockingSegment(new RouteExclusions.Point(x, hullBottom, z),
            new RouteExclusions.Point(toX, hullBottom, toZ),
            new RouteExclusions.Footprint(hullRadius * 2, hullTop - hullBottom)).isPresent()) return false;
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(toX - x, toZ - z) / .32));
        double fromX = x, fromZ = z;
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = sample / (double) samples;
            double nextX = x + (toX - x) * fraction, nextZ = z + (toZ - z) * fraction;
            if (!clearSection(fromX, fromZ, nextX, nextZ, entities)) return false;
            fromX = nextX;
            fromZ = nextZ;
        }
        return true;
    }

    private boolean clearAt(double x, double z, boolean entities) {
        return clearSegment(x, z, x, z, entities);
    }

    private boolean clearSection(double fromX, double fromZ, double x, double z, boolean entities) {
        double minX = Math.min(fromX, x) - hullRadius, maxX = Math.max(fromX, x) + hullRadius;
        double minZ = Math.min(fromZ, z) - hullRadius, maxZ = Math.max(fromZ, z) + hullRadius;
        int left = Mth.floor(minX) >> 4, right = Mth.floor(maxX) >> 4;
        int back = Mth.floor(minZ) >> 4, front = Mth.floor(maxZ) >> 4;
        var chunks = level.getChunkSource();
        if (!chunks.hasChunk(left, back) || !chunks.hasChunk(left, front)
            || !chunks.hasChunk(right, back) || !chunks.hasChunk(right, front)) return false;
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(minX, minZ) || !border.isWithinBounds(maxX, maxZ)) return false;
        if (onIce) {
            if (!iceSupports(minX, minZ, maxX, maxZ)) return false;
        } else {
            BlockPos pos = BlockPos.containing(x, surfaceBlockY, z);
            if (!sourceSurface(pos, level.getFluidState(pos))) return false;
        }
        AABB hull = new AABB(minX, hullBottom, minZ, maxX, hullTop, maxZ);
        return entities ? level.noCollision(boat, hull) : level.noBlockCollision(boat, hull);
    }

    private boolean iceSupports(double minX, double minZ, double maxX, double maxZ) {
        BlockPos.MutableBlockPos support = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(minX); x <= Mth.floor(maxX); x++) {
            for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ); z++) {
                support.set(x, surfaceBlockY, z);
                if (!level.getBlockState(support).is(BlockTags.ICE) || !level.getFluidState(support.above()).isEmpty()) return false;
            }
        }
        return true;
    }

    private String surfaceName() {
        return onIce ? "ice" : "source water";
    }

    private double settledSpeed() {
        return onIce ? ICE_SETTLED_SPEED : SETTLED_SPEED;
    }

    private boolean sourceSurface(BlockPos pos, FluidState fluid) {
        return fluid.is(FluidTags.WATER) && fluid.isSource()
            && !level.getFluidState(pos.above()).is(FluidTags.WATER)
            && !level.getBlockState(pos).is(Blocks.BUBBLE_COLUMN);
    }

    private Input steer(double x, double z, float yaw, float turnRate, Vec3 motion, double speed, Vec3 target) {
        double distance = distance(x, z, target);
        float wanted = (float) Math.toDegrees(Math.atan2(x - target.x, target.z - z));
        float angle = Mth.wrapDegrees(wanted - yaw);
        float predictedAngle = Mth.wrapDegrees(angle - turnRate * 3);
        double forwardX = -Math.sin(Math.toRadians(yaw)), forwardZ = Math.cos(Math.toRadians(yaw));
        double forwardSpeed = forwardX * motion.x + forwardZ * motion.z;
        double cruiseSpeed = Math.clamp((distance - .12) * .12, SETTLED_SPEED, .29);

        // Coast prediction includes lateral drift, which can persist even when the hull faces its waypoint.
        boolean canCoast = clearSegment(x, z, x + motion.x * 4, z + motion.z * 4, true);
        boolean canThrust = canCoast && clearSegment(x, z,
            x + motion.x * 3 + forwardX * .28, z + motion.z * 3 + forwardZ * .28, true);
        boolean canReverse = clearSegment(x, z, x - forwardX * .14, z - forwardZ * .14, true);
        boolean brake = canReverse && forwardSpeed > .025
            && (!canCoast || speed > cruiseSpeed || distance <= ARRIVAL_DISTANCE || Math.abs(angle) > 65);
        boolean thrust = !brake && canThrust && distance > ARRIVAL_DISTANCE
            && Math.abs(angle) < 17 && Math.abs(predictedAngle) < 18 && speed < cruiseSpeed;
        boolean turn = canCoast && distance > ARRIVAL_DISTANCE && Math.abs(predictedAngle) > 4;

        // Turning alone applies a small forward paddle stroke. Alternate reverse strokes to turn in a tight lane.
        reversePaddle = !reversePaddle;
        boolean reverse = brake || (turn && !thrust && speed <= .10 && reversePaddle && canReverse);
        return new Input(thrust, reverse, turn && predictedAngle < 0, turn && predictedAngle > 0, false, false, false);
    }

    private Input steerOnIce(double x, double z, float yaw, float turnRate, Vec3 motion, double speed, Vec3 target) {
        double distance = distance(x, z, target);
        float wanted = heading(target.x - x - motion.x * 3, target.z - z - motion.z * 3);
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians), forwardZ = Math.cos(radians);
        double forwardSpeed = forwardX * motion.x + forwardZ * motion.z;
        Vec3 stopping = brakingDisplacement(motion.x, motion.z, forwardX, forwardZ);
        boolean canStop = clearSegment(x, z, x + stopping.x, z + stopping.z, true);
        float motionHeading = heading(motion.x, motion.z);
        boolean brake = speed > ICE_SETTLED_SPEED && (!canStop || distance <= ARRIVAL_DISTANCE
            || stopping.horizontalDistance() + speed + .13 >= distance
            || speed > .07 && Math.abs(Mth.wrapDegrees(wanted - motionHeading)) > 47);
        if (brake && speed > .035) wanted = motionHeading;

        float angle = Mth.wrapDegrees(wanted - yaw);
        // Blue ice preserves the boat's angular momentum. Counter-steer before reaching the new heading.
        float predictedAngle = Mth.wrapDegrees(angle - turnRate * (1.4f + Math.abs(turnRate) * .5f));
        boolean turn = Math.abs(predictedAngle) > 2.5f || Math.abs(turnRate) > 1.2f;
        boolean reverse = brake && forwardSpeed > ICE_SETTLED_SPEED && Math.abs(angle) < 30;
        boolean thrust = false;
        if (!brake && distance > ARRIVAL_DISTANCE && Math.abs(angle) < 5 && Math.abs(turnRate) < 1.5f && speed < ICE_CRUISE_LIMIT) {
            double nextX = motion.x * BLUE_ICE_DRAG + forwardX * FORWARD_PADDLE;
            double nextZ = motion.z * BLUE_ICE_DRAG + forwardZ * FORWARD_PADDLE;
            Vec3 projected = brakingDisplacement(nextX, nextZ, forwardX, forwardZ).add(nextX, 0, nextZ);
            thrust = projected.horizontalDistance() + .05 < distance
                && clearSegment(x, z, x + projected.x, z + projected.z, true);
        }
        // A turn includes a small forward stroke. Alternate with reverse to avoid acceleration while turning.
        reversePaddle = !reversePaddle;
        if (turn && !thrust && !reverse && reversePaddle) reverse = true;
        return new Input(thrust, reverse, turn && predictedAngle < 0, turn && predictedAngle > 0, false, false, false);
    }

    private static float heading(double x, double z) {
        return (float) Math.toDegrees(Math.atan2(-x, z));
    }

    private static Vec3 brakingDisplacement(double x, double z, double forwardX, double forwardZ) {
        double forward = Math.max(0, x * forwardX + z * forwardZ);
        double lateralX = x - forward * forwardX, lateralZ = z - forward * forwardZ;
        double brakingDistance = 0;
        while (forward > ICE_SETTLED_SPEED) {
            forward = forward * BLUE_ICE_DRAG - BACKWARD_PADDLE;
            brakingDistance += forward;
        }
        // Reserve room to align the hull with sideways motion before that part of the slide can be braked.
        return new Vec3(brakingDistance * forwardX + lateralX * ICE_STEERING_HORIZON, 0,
            brakingDistance * forwardZ + lateralZ * ICE_STEERING_HORIZON);
    }

    private Input stopAndRecover(String reason, double x, double z, float yaw, float turnRate, Vec3 motion, double speed) {
        if (onIce && speed > ICE_SETTLED_SPEED) {
            iceStopReason = reason;
            detail = "Braking the boat on ice.";
            return steerOnIce(x, z, yaw, turnRate, motion, speed, new Vec3(x, surface, z));
        }
        recover(reason);
        return Input.EMPTY;
    }

    private void recover(String reason) {
        if (!options.autoReplan() || replans++ >= options.replanLimit()) {
            fail(reason);
            return;
        }
        startSearch();
    }

    private void resetProgress() {
        bestDistance = Double.POSITIVE_INFINITY;
        stallTicks = 0;
    }

    private void rejectDock(String reason) {
        if (dock != null) rejectedDocks.add(dock);
        if (rejectedDocks.size() >= 6) fail(reason);
        else startSearch();
    }

    private void planWalking(Node from, DockPhase phase) {
        var config = options.search();
        var connection = new IncrementalSearch.Config(config.diagonals(), config.jumping(), config.maxDrop(),
            Math.min(config.horizontalRange(), SHORE_CONNECTION_RANGE + requestedGoal.radius()),
            Math.min(config.maxVisited(), 2_000), false, config.searchMode(), config.gapJumps());
        groundSearch = new IncrementalSearch(walkingTerrain, from, requestedGoal, connection);
        walkingStart = from;
        dockPhase = phase;
        status = phase == DockPhase.WALKING ? Status.MOVING : Status.PLANNING;
        detail = "Checking a walking route from the shoreline.";
    }

    private void checkWalking(int expansions, long budgetNanos) {
        var result = groundSearch.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        groundSearch = null;
        if (result.status() != IncrementalSearch.Status.FOUND) {
            if (dockPhase == DockPhase.WALKING) fail("Dismounted safely. " + result.reason());
            else rejectDock("No walking route leads from the selected shoreline.");
            return;
        }
        List<Vec3> next = new ArrayList<>();
        next.add(exit.feet());
        for (Step step : result.path()) next.add(new Vec3(step.to().centerX(), step.to().y(), step.to().centerZ()));
        shoreWaypoints = List.copyOf(next);
        switch (dockPhase) {
            case CHECK_REMOTE -> {
                dockPhase = DockPhase.NONE;
                startMoving();
            }
            case CHECK_SETTLED -> {
                if (boat.getDeltaMovement().horizontalDistance() > settledSpeed() || !sameExit(shore.facing(boat.getX(), boat.getZ(), exit.yaw()))) {
                    rejectDock("The boat moved before the shoreline exit was verified.");
                    return;
                }
                dockPhase = DockPhase.DISMOUNTING;
                status = Status.MOVING;
                dismountTicks = 0;
                detail = "Preparing to dismount onto the verified shore.";
            }
            case WALKING -> {
                walker = result.path().isEmpty() ? new PathFollower(walkingTerrain, player, walkingStart, options.steerView())
                    : new PathFollower(walkingTerrain, player, result.path(), options.steerView());
            }
            default -> throw new IllegalStateException("Unexpected shoreline state.");
        }
    }

    private Input dismount() {
        if (++dismountTicks > 20) {
            fail("The boat did not release the rider at the verified shoreline.");
            return Input.EMPTY;
        }
        if (boat.getDeltaMovement().horizontalDistance() > settledSpeed() || !sameExit(shore.facing(boat.getX(), boat.getZ(), exit.yaw()))) {
            fail("The shoreline exit changed before dismounting.");
            return Input.EMPTY;
        }
        float change = Mth.wrapDegrees(exit.yaw() - player.getYRot());
        player.setYRot(player.getYRot() + Math.clamp(change, -30, 30));
        if (dismountTicks == 1 || Math.abs(change) > 1) return Input.EMPTY;
        detail = "Dismounting onto the verified shore.";
        return DISMOUNT;
    }

    private boolean sameExit(BoatShore.Exit latest) {
        return latest != null && latest.start().equals(exit.start()) && latest.feet().distanceToSqr(exit.feet()) <= .16;
    }

    private void beginWalking() {
        Node from = player.onGround() ? walkingTerrain.startAtPlayer() : null;
        if (from != null && player.position().distanceToSqr(exit.feet()) <= 4 && !player.isInWater()) {
            planWalking(from, DockPhase.WALKING);
        } else if (++dismountTicks > 24) {
            fail("The boat released you outside the verified shore. Take control.");
        }
    }

    private Input walkShore() {
        if (walker == null) return Input.EMPTY;
        Input next = walker.tick();
        switch (walker.status()) {
            case ARRIVED -> {
                status = Status.ARRIVED;
                detail = "Reached the destination after leaving the boat.";
            }
            case BLOCKED -> fail("Dismounted safely. " + walker.message());
            case MOVING -> detail = "Walking from the shoreline (" + walker.completedSteps() + "/" + walker.totalSteps() + ").";
        }
        return status == Status.MOVING ? next : Input.EMPTY;
    }

    private void fail(String reason) {
        search = null;
        groundSearch = null;
        status = Status.FAILED;
        detail = reason;
    }

    private static double distance(double x, double z, Vec3 target) {
        return Math.hypot(target.x - x, target.z - z);
    }

    private static double nearestHalfOffset(double coordinate) {
        return Math.floorMod((int) Math.round(coordinate * 2), 2) * .5;
    }

    private Vec3 laneCenter(Node node) {
        return new Vec3(node.x() + laneOffsetX, surface, node.z() + laneOffsetZ);
    }

    private final class LaneTerrain implements Terrain {
        @Override
        public List<Node> floors(int x, int z, int minY16, int maxY16) {
            if (minY16 > 0 || maxY16 < 0 || !clearAt(x + laneOffsetX, z + laneOffsetZ, false)) return List.of();
            return List.of(new Node(x, 0, z));
        }

        @Override
        public boolean canTraverse(Node from, Node to, Move move) {
            return move == Move.WALK && clearSegment(from.x() + laneOffsetX, from.z() + laneOffsetZ,
                to.x() + laneOffsetX, to.z() + laneOffsetZ, false);
        }

        @Override
        public boolean canFinish(Node node) {
            return !joinShore || !rejectedDocks.contains(node) && shore.at(node.x() + laneOffsetX, node.z() + laneOffsetZ, false) != null;
        }
    }
}
