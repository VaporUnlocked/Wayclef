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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Plans for the ridden body and steers with the same inputs as its player. */
public final class MountRoute implements TransportRoute {
    private static final double EPSILON = 1e-5;
    private static final double ANCHOR = .04;
    private static final int STALL_TICKS = 55;
    private static final int WALK_CONNECTION_RANGE = 24;
    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    private static final Input DISMOUNT = new Input(false, false, false, false, false, true, false);

    private final LocalPlayer player;
    private final AbstractHorse mount;
    private final Goal goal;
    private final IncrementalSearch.Config searchConfig;
    private final boolean autoReplan;
    private final int replanLimit;
    private final MountTerrain terrain;
    private final MinecraftTerrain walkingTerrain;
    private final MountDismount dismount;
    private final Navigation.Options options;
    private final Set<Node> rejectedDismounts = new HashSet<>();
    private Status status = Status.PLANNING;
    private String detail = "Planning a mounted route.";
    private Input input = Input.EMPTY;
    private IncrementalSearch search;
    private Node start;
    private List<Vec3> waypoints = List.of();
    private int waypoint;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int stalledTicks;
    private int replans;
    private boolean joinWalking;
    private boolean dismounting;
    private boolean dismounted;
    private int dismountTicks;
    private Node finalMountNode;
    private MountDismount.Exit exit;
    private IncrementalSearch walkingSearch;
    private PathFollower walker;
    private Node walkingStart;

    public static boolean supports(LocalPlayer player) {
        return player.getVehicle() instanceof AbstractHorse horse && horse.getControllingPassenger() == player;
    }

    public MountRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.player = player;
        this.goal = goal;
        this.options = options;
        autoReplan = options.autoReplan();
        replanLimit = options.replanLimit();
        mount = supports(player) ? (AbstractHorse) player.getVehicle() : null;
        walkingTerrain = new MinecraftTerrain(level, player, options.avoidMobs());
        dismount = mount == null ? null : new MountDismount(level, player, mount, walkingTerrain);
        if (mount == null) {
            terrain = null;
            searchConfig = null;
            fail("Mount a horse, donkey, mule or camel you control first.");
            return;
        }
        IncrementalSearch.Config settings = options.search();
        int drop = Math.min(1, settings.maxDrop());
        terrain = new MountTerrain(level, mount, drop, options.avoidMobs());
        // The search's rise edge also represents a vanilla automatic mount step.
        searchConfig = new IncrementalSearch.Config(settings.diagonals(), terrain.stepHeight > 9.0 / 16.0,
            drop, settings.horizontalRange(), settings.maxVisited(), false, settings.searchMode());
        if (!mount.onGround() || mount.isInLiquid() || !canSteer()) {
            fail("Settle the mount on dry ground and stand up before planning.");
            return;
        }
        startSearch();
    }

    @Override
    public void advance(int expansions, long budgetNanos) {
        input = Input.EMPTY;
        if (status == Status.ARRIVED || status == Status.FAILED) return;
        if (dismounting || dismounted) {
            continueDismount(expansions, budgetNanos);
            return;
        }
        if (!canSteer() || mount.isInLiquid()) {
            fail("The mount is no longer controllable on dry land.");
            return;
        }
        if (walkingSearch != null) {
            verifyWalking(expansions, budgetNanos);
            return;
        }
        if (status == Status.PLANNING) {
            var result = search.advance(expansions, budgetNanos);
            detail = "Planning mounted route · " + result.visited() + " nodes checked";
            if (result.status() == IncrementalSearch.Status.SEARCHING) return;
            if (result.status() != IncrementalSearch.Status.FOUND) {
                if (!joinWalking && options.transportMode() == TransportMode.AUTO) {
                    joinWalking = true;
                    startSearch();
                    return;
                }
                fail("No mounted route through loaded terrain. " + result.reason());
                return;
            }
            List<Vec3> points = new ArrayList<>();
            points.add(terrain.anchor(start));
            for (Step step : result.path()) points.add(terrain.anchor(step.to()));
            finalMountNode = result.path().isEmpty() ? start : result.path().getLast().to();
            centerArrival(points);
            waypoints = List.copyOf(points);
            waypoint = 0;
            resetProgress();
            status = Status.MOVING;
        }
        follow();
    }

    @Override public Input input() { return input; }
    @Override public Vec2 walkDirection() {
        return status == Status.MOVING && walker != null ? walker.walkDirection() : null;
    }
    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }
    @Override public boolean allowsDismounted() { return dismounting || dismounted; }
    @Override public void close() { input = Input.EMPTY; }

    private void centerArrival(List<Vec3> points) {
        int last = points.size() - 1;
        Vec3 anchor = points.get(last);
        Vec3 center = new Vec3(Math.floor(anchor.x) + .5, anchor.y, Math.floor(anchor.z) + .5);
        Vec3 before = last == 0 ? mount.position() : points.get(last - 1);
        for (double fraction : new double[] { 1, .5, .2 }) {
            Vec3 point = anchor.lerp(center, fraction);
            if (terrain.clearTransition(before, point, true)) {
                points.set(last, point);
                return;
            }
        }
    }

    private void startSearch() {
        start = terrain.findStart();
        if (start == null) {
            fail("No nearby floor has enough clearance for the mount and rider.");
            return;
        }
        terrain.finish = joinWalking ? this::canDismountNear : node -> true;
        Goal destination = joinWalking ? new Goal(goal.x(), null, goal.z(), Math.max(goal.radius(), WALK_CONNECTION_RANGE)) : goal;
        search = new IncrementalSearch(terrain, start, destination, searchConfig);
        walkingSearch = null;
        status = Status.PLANNING;
        detail = "Planning a mounted route.";
        waypoints = List.of();
    }

    private boolean canSteer() {
        return mount != null && !mount.isRemoved() && mount.isAlive() && mount.level() == terrain.level
            && player.getVehicle() == mount && mount.getControllingPassenger() == player
            && (!(mount instanceof Camel camel) || !camel.isCamelSitting() && !camel.isInPoseTransition());
    }

    private void follow() {
        Vec3 position = mount.position();
        if (!terrain.clearBody(position)) {
            recover("The mount's clearance changed.");
            return;
        }
        Vec3 target = waypoints.get(waypoint);
        boolean stopHere = stopAt(waypoint);
        double speed = mount.getDeltaMovement().horizontalDistance();
        boolean last = waypoint == waypoints.size() - 1;
        if (reached(position, target, stopHere) && (!last || joinWalking || inGoalColumn(position))) {
            if (stopHere && speed > .09) {
                noteProgress(position, target);
                return;
            }
            if (++waypoint == waypoints.size()) {
                if (joinWalking) prepareDismount();
                else {
                    status = Status.ARRIVED;
                    detail = "Arrived on the mount.";
                }
                return;
            }
            resetProgress();
            target = waypoints.get(waypoint);
            stopHere = stopAt(waypoint);
        }
        if (Math.abs(position.y - target.y) > 1.1 || flatDistance(position, target) > 2.1
            || !terrain.clearTransition(position, target, false)) {
            recover("The mount moved off the verified route or the terrain changed.");
            return;
        }
        if (!noteProgress(position, target)) return;
        double dx = target.x - position.x, dz = target.z - position.z;
        if (Math.hypot(dx, dz) < .015) return;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = Mth.wrapDegrees(targetYaw - player.getYRot());
        player.setYRot(player.getYRot() + Mth.clamp(delta, -30, 30));
        if (Math.abs(delta) <= 36 && (!stopHere || speed < .13 || Math.hypot(dx, dz) > speed * 2 + .16)) {
            input = FORWARD;
        }
        detail = "Riding to waypoint " + (waypoint + 1) + " of " + waypoints.size() + ".";
    }

    private boolean stopAt(int index) {
        if (index == 0 || index == waypoints.size() - 1) return true;
        Vec3 before = waypoints.get(index - 1), here = waypoints.get(index), after = waypoints.get(index + 1);
        return Math.abs(here.y - before.y) > EPSILON || Math.abs(after.y - here.y) > EPSILON
            || Math.abs(here.x - before.x - (after.x - here.x)) > EPSILON
            || Math.abs(here.z - before.z - (after.z - here.z)) > EPSILON;
    }

    private boolean reached(Vec3 position, Vec3 target, boolean stopping) {
        return Math.abs(position.y - target.y) < .20 && flatDistance(position, target) < (stopping ? .23 : .40)
            && (!stopping || mount.onGround());
    }

    private boolean inGoalColumn(Vec3 position) {
        return Math.abs((long) Mth.floor(position.x) - goal.x()) <= goal.radius()
            && Math.abs((long) Mth.floor(position.z) - goal.z()) <= goal.radius();
    }

    private boolean noteProgress(Vec3 position, Vec3 target) {
        double distance = position.distanceTo(target);
        if (distance < bestDistance - .035) {
            bestDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks >= STALL_TICKS) {
            recover("The mount stopped making progress.");
            return false;
        }
        return true;
    }

    private void resetProgress() {
        stalledTicks = 0;
        bestDistance = Double.POSITIVE_INFINITY;
    }

    private boolean canDismountNear(Node node) {
        if (rejectedDismounts.contains(node)) return false;
        Vec3 feet = terrain.anchor(node);
        float facingGoal = (float) Math.toDegrees(Math.atan2(feet.x - goal.x() - .5, goal.z() + .5 - feet.z));
        return dismount.at(feet, facingGoal) != null || dismount.at(feet, facingGoal + 90) != null
            || dismount.at(feet, facingGoal + 180) != null || dismount.at(feet, facingGoal - 90) != null;
    }

    private void prepareDismount() {
        exit = dismount.at(mount.position(), mount.getYRot());
        if (exit == null) rejectDismount("The mount has no safe standing exit here.");
        else {
            walkingSearch = new IncrementalSearch(walkingTerrain, exit.start(), goal, walkingConfig());
            detail = "Checking the walking connection before dismounting.";
        }
    }

    private IncrementalSearch.Config walkingConfig() {
        var search = options.search();
        return new IncrementalSearch.Config(search.diagonals(), search.jumping(), search.maxDrop(),
            Math.min(search.horizontalRange(), WALK_CONNECTION_RANGE + goal.radius()), Math.min(search.maxVisited(), 2_000),
            false, search.searchMode(), search.gapJumps());
    }

    private void verifyWalking(int expansions, long budgetNanos) {
        var result = walkingSearch.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        walkingSearch = null;
        if (result.status() != IncrementalSearch.Status.FOUND) {
            if (dismounted) fail("Dismounted safely. " + result.reason());
            else rejectDismount("No walking route leads from the mount's exit.");
            return;
        }
        if (!dismounted) {
            if (!safeDismountNow()) {
                rejectDismount("The mount or the verified exit moved before dismounting.");
                return;
            }
            dismounting = true;
            dismountTicks = 0;
            detail = "Dismounting onto verified footing.";
            return;
        }
        List<Vec3> joined = new ArrayList<>(waypoints);
        joined.add(exit.feet());
        for (Step step : result.path()) joined.add(new Vec3(step.to().centerX(), step.to().y(), step.to().centerZ()));
        waypoints = List.copyOf(joined);
        walker = result.path().isEmpty() ? new PathFollower(walkingTerrain, player, walkingStart, options.steerView())
            : new PathFollower(walkingTerrain, player, result.path(), options.steerView());
    }

    private boolean safeDismountNow() {
        if (!canSteer() || !mount.onGround() || mount.getDeltaMovement().horizontalDistance() > .09) return false;
        var liveExit = dismount.at(mount.position(), mount.getYRot());
        return liveExit != null && liveExit.start().equals(exit.start()) && liveExit.feet().distanceToSqr(exit.feet()) <= .16;
    }

    private void continueDismount(int expansions, long budgetNanos) {
        if (dismounting) {
            if (++dismountTicks > 24) {
                fail("The mount did not release you onto the verified exit.");
            } else if (player.isPassenger()) {
                if (safeDismountNow()) input = DISMOUNT;
                else fail("The mount's exit changed before dismounting.");
            } else {
                Node start = player.onGround() ? walkingTerrain.startAtPlayer() : null;
                if (start != null && player.position().distanceToSqr(exit.feet()) <= 4 && !player.isInWater()) {
                    dismounting = false;
                    dismounted = true;
                    walkingStart = start;
                    walkingSearch = new IncrementalSearch(walkingTerrain, start, goal, walkingConfig());
                }
            }
            return;
        }
        if (player.isPassenger() || player.isInLiquid()) {
            fail("The walking connection needs dry, unmounted movement.");
        } else if (walkingSearch != null) {
            verifyWalking(expansions, budgetNanos);
        } else if (walker != null) {
            input = walker.tick();
            switch (walker.status()) {
                case ARRIVED -> arriveOnFoot();
                case BLOCKED -> fail("Dismounted safely. " + walker.message());
                case MOVING -> detail = "Walking after dismounting (" + walker.completedSteps() + "/" + walker.totalSteps() + ").";
            }
        }
    }

    private void arriveOnFoot() {
        status = Status.ARRIVED;
        detail = "Reached the destination after dismounting.";
        input = Input.EMPTY;
    }

    private void rejectDismount(String reason) {
        rejectedDismounts.add(finalMountNode);
        if (rejectedDismounts.size() >= 6) fail(reason);
        else startSearch();
    }

    private void recover(String reason) {
        if (autoReplan && replans < replanLimit && mount.onGround()) {
            replans++;
            startSearch();
            return;
        }
        fail(reason);
    }

    private void fail(String reason) {
        input = Input.EMPTY;
        search = null;
        status = Status.FAILED;
        detail = reason;
    }

    private static double flatDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static final class MountTerrain implements Terrain {
        private static final double MARGIN = .035;
        private static final double SUPPORT_DEPTH = .075;
        private static final Set<Block> HAZARDS = Set.of(
            Blocks.BLUE_ICE, Blocks.CACTUS, Blocks.CAMPFIRE, Blocks.COBWEB, Blocks.END_GATEWAY,
            Blocks.END_PORTAL, Blocks.FIRE, Blocks.FROSTED_ICE, Blocks.HONEY_BLOCK, Blocks.ICE,
            Blocks.MAGMA_BLOCK, Blocks.MOVING_PISTON, Blocks.NETHER_PORTAL, Blocks.PACKED_ICE,
            Blocks.POINTED_DRIPSTONE, Blocks.POWDER_SNOW, Blocks.SCAFFOLDING, Blocks.SLIME_BLOCK,
            Blocks.SOUL_CAMPFIRE, Blocks.SOUL_FIRE, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE
        );

        private final ClientLevel level;
        private final AbstractHorse mount;
        private final double halfWidth;
        private final double height;
        private final double stepHeight;
        private final int drop;
        private final boolean avoidMobs;
        private final int minY;
        private final int maxY;
        private Predicate<Node> finish = node -> true;

        MountTerrain(ClientLevel level, AbstractHorse mount, int drop, boolean avoidMobs) {
            this.level = level;
            this.mount = mount;
            this.drop = drop;
            this.avoidMobs = avoidMobs;
            double reach = mount.getBbWidth() / 2.0;
            double top = mount.getBoundingBox().maxY;
            for (var rider : mount.getPassengers()) {
                double offset = Math.hypot(rider.getX() - mount.getX(), rider.getZ() - mount.getZ());
                reach = Math.max(reach, offset + rider.getBbWidth() / 2.0);
                top = Math.max(top, rider.getBoundingBox().maxY);
            }
            halfWidth = reach + MARGIN;
            height = top - mount.getY() + MARGIN;
            stepHeight = Math.min(1, mount.maxUpStep());
            minY = level.getMinY();
            maxY = level.getMaxY();
        }

        Vec3 anchor(Node node) {
            // An integer-aligned lane fits a wide mount between two block columns.
            return new Vec3(node.x() + ANCHOR, node.y(), node.z() + ANCHOR);
        }

        Node findStart() {
            Vec3 feet = mount.position();
            int x = Mth.floor(feet.x), z = Mth.floor(feet.z);
            int low = Mth.ceil((feet.y - drop - EPSILON) * 16);
            int high = Mth.floor((feet.y + stepHeight + EPSILON) * 16);
            Node closest = null;
            double best = Double.POSITIVE_INFINITY;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (Node node : floors(x + dx, z + dz, low, high)) {
                        Vec3 target = anchor(node);
                        double distance = feet.distanceToSqr(target);
                        if (distance >= best || flatDistance(feet, target) > 1.35) continue;
                        if (clearTransition(feet, target, true)) {
                            closest = node;
                            best = distance;
                        }
                    }
                }
            }
            return closest;
        }

        @Override
        public List<Node> floors(int x, int z, int minY16, int maxY16) {
            if (!Node.validColumn(x) || !Node.validColumn(z) || minY16 > maxY16
                || !loaded(x - 1, z - 1, x, z)) return List.of();
            Set<Integer> levels = new TreeSet<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = Math.max(minY, Math.floorDiv(minY16, 16) - 2);
                 y <= Math.min(maxY, Math.floorDiv(maxY16, 16)); y++) {
                for (int dz = -1; dz <= 0; dz++) {
                    for (int dx = -1; dx <= 0; dx++) {
                        pos.set(x + dx, y, z + dz);
                        var state = level.getBlockState(pos);
                        if (state.isAir() || HAZARDS.contains(state.getBlock())) continue;
                        for (AABB box : state.getCollisionShape(level, pos).toAabbs()) {
                            double top = (y + box.maxY) * 16;
                            int y16 = (int) Math.round(top);
                            if (y16 >= minY16 && y16 <= maxY16 && y16 >= Node.MIN_Y16 && y16 <= Node.MAX_Y16
                                && Math.abs(top - y16) < EPSILON) levels.add(y16);
                        }
                    }
                }
            }
            List<Node> floors = new ArrayList<>();
            for (int y16 : levels) {
                Node node = new Node(x, y16, z);
                Vec3 point = anchor(node);
                if (clearBody(point) && supported(point)) floors.add(node);
            }
            return floors;
        }

        @Override
        public boolean canTraverse(Node from, Node to, Move move) {
            return clearTransition(anchor(from), anchor(to), true);
        }

        @Override public boolean canFinish(Node node) { return finish.test(node); }

        boolean clearBody(Vec3 feet) {
            return clear(body(feet));
        }

        boolean clearTransition(Vec3 from, Vec3 to, boolean checkFooting) {
            double rise = to.y - from.y;
            if (rise > stepHeight + EPSILON || rise < -drop - EPSILON) return false;
            double top = Math.max(from.y, to.y);
            Vec3 start = new Vec3(from.x, top, from.z), end = new Vec3(to.x, top, to.z);
            if (rise > EPSILON && !clear(body(from).minmax(body(start)))) return false;
            if (rise < -EPSILON && !clear(body(to).minmax(body(end)))) return false;
            int sections = Math.max(1, Mth.ceil(flatDistance(start, end) / .4));
            AABB previous = body(start);
            for (int section = 1; section <= sections; section++) {
                Vec3 point = start.lerp(end, section / (double) sections);
                AABB next = body(point);
                if (!clear(previous.minmax(next))) return false;
                if (checkFooting && Math.abs(rise) < EPSILON && section < sections && !supported(point)) return false;
                previous = next;
            }
            return supported(to);
        }

        private AABB body(Vec3 feet) {
            return new AABB(feet.x - halfWidth, feet.y + EPSILON, feet.z - halfWidth,
                feet.x + halfWidth, feet.y + height, feet.z + halfWidth);
        }

        private boolean supported(Vec3 feet) {
            double reach = halfWidth * .65;
            int corners = 0;
            for (int z = -1; z <= 1; z += 2) {
                for (int x = -1; x <= 1; x += 2) {
                    if (floorAt(feet.x + x * reach, feet.y, feet.z + z * reach)) corners++;
                }
            }
            return corners >= 3 || corners == 2 && floorAt(feet.x, feet.y, feet.z);
        }

        private boolean floorAt(double x, double y, double z) {
            double probe = .045;
            AABB floor = new AABB(x - probe, y - SUPPORT_DEPTH, z - probe, x + probe, y - EPSILON, z + probe);
            return !level.noBlockCollision(mount, floor);
        }

        private boolean clear(AABB box) {
            int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX - EPSILON);
            int z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ - EPSILON);
            int y0 = Mth.floor(box.minY - SUPPORT_DEPTH), y1 = Mth.floor(box.maxY - EPSILON);
            if (y0 < minY || y1 > maxY || !level.getWorldBorder().isWithinBounds(box)
                || !loaded(x0 - 1, z0 - 1, x1 + 1, z1 + 1) || !level.noBlockCollision(mount, box)) return false;
            var exclusions = Navigation.activeExclusions(avoidMobs);
            if (exclusions.blockingAt(new RouteExclusions.Point((box.minX + box.maxX) * .5, box.minY, (box.minZ + box.maxZ) * .5),
                new RouteExclusions.Footprint(Math.max(box.maxX - box.minX, box.maxZ - box.minZ), box.maxY - box.minY)).isPresent()) return false;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        var state = level.getBlockState(pos.set(x, y, z));
                        if (!state.getFluidState().isEmpty() || HAZARDS.contains(state.getBlock())) return false;
                    }
                }
            }
            return true;
        }

        private boolean loaded(int x0, int z0, int x1, int z1) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
                    if (!level.hasChunk(cx, cz)) return false;
                }
            }
            return true;
        }
    }
}
