package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.path.Goal;
import dev.soprano.path.RouteExclusions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Traverses a verified vanilla rail line using ordinary boarding, motion input, and dismount input. */
public final class RailRoute implements TransportRoute {
    private static final int LOOK_AHEAD = 4;
    private static final int BOARD_TIMEOUT = 40;
    private static final int STALL_TIMEOUT = 100;
    private static final Input DISMOUNT = new Input(false, false, false, false, false, true, false);

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Goal goal;
    private final Navigation.Options options;
    private final Minecart cart;
    private final BlockPos start;
    private final ArrayDeque<BlockPos> open = new ArrayDeque<>();
    private final Map<BlockPos, BlockPos> previous = new HashMap<>();
    private Status status = Status.PLANNING;
    private String detail = "Checking loaded rail connections.";
    private List<BlockPos> route = List.of();
    private List<Vec3> waypoints = List.of();
    private boolean boarded;
    private int boardingTicks;
    private int dismountTicks;
    private int groundedExitTicks;
    private int stalledTicks;
    private int cursor;
    private String abortReason;
    private Vec3 previousPosition;
    private Vec3 expectedExit;

    public RailRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.options = options;
        cart = findCart();
        start = cart == null ? null : cart.getCurrentBlockPosOrRailBelow().immutable();
        if (cart == null) {
            fail("Stand within reach of an empty, stopped rideable minecart, or get in one first.");
        } else if (track(start) == null) {
            fail("The rideable minecart must be on a loaded, usable rail.");
        } else {
            boarded = player.getVehicle() == cart;
            open.add(start);
            previous.put(start, null);
        }
    }

    @Override public void advance(int expansions, long budgetNanos) {
        if (status != Status.PLANNING) return;
        if (!cart.isAlive() || cart.level() != level) {
            fail("The selected minecart disappeared.");
            return;
        }
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        long deadline = System.nanoTime() + budgetNanos;
        int limit = options.search().maxVisited();
        for (int count = 0; count < expansions && !open.isEmpty() && (count == 0 || System.nanoTime() < deadline); count++) {
            BlockPos position = open.removeFirst();
            RailShape shape = track(position);
            if (shape == null || !corridorClear(position, shape, exclusions)) continue;
            if (destination(position) && possibleExit(position)) {
                finishPlan(position);
                return;
            }
            for (BlockPos next : RailGeometry.connections(position, shape, this::track)) {
                if (previous.size() >= limit) break;
                if (previous.containsKey(next) || !withinRange(next) || position.equals(start) && !canDepartToward(next)) continue;
                previous.put(next, position);
                open.addLast(next);
            }
        }
        if (open.isEmpty()) fail("No connected loaded rail route with a safe exit reaches the destination.");
        else detail = "Checking loaded rail connections (" + previous.size() + " positions).";
    }

    private void finishPlan(BlockPos destination) {
        List<BlockPos> reverse = new ArrayList<>();
        for (BlockPos point = destination; point != null; point = previous.get(point)) reverse.add(point);
        route = List.copyOf(reverse.reversed());
        List<Vec3> points = new ArrayList<>(route.size());
        for (BlockPos point : route) {
            RailShape shape = track(point);
            if (shape == null) {
                fail("The rail changed before the plan was ready.");
                return;
            }
            var ends = RailGeometry.endpoints(point, shape);
            points.add(ends.getFirst().lerp(ends.getLast(), .5));
        }
        waypoints = List.copyOf(points);
        previous.clear();
        open.clear();
        status = Status.MOVING;
        detail = "Rail route ready (" + route.size() + " rails).";
    }

    @Override public Input input() {
        if (status != Status.MOVING) return Input.EMPTY;
        if (!cart.isAlive() || cart.level() != level) return fail("The selected minecart disappeared.");
        if (dismountTicks > 0) return confirmDismount();
        if (player.getVehicle() != cart) {
            if (boarded || player.isPassenger()) return fail("You are no longer riding the selected minecart.");
            return board();
        }
        boarded = true;
        BlockPos position = cart.getCurrentBlockPosOrRailBelow();
        int last = route.size() - 1;
        int observed = locateRail(position);
        if (observed < 0 || !liveTrackClear(observed)) {
            return abort("The minecart left the confirmed route or the next rail is obstructed.");
        }
        if (observed == last) {
            Vec3 exit = cart.getDismountLocationForPassenger(player);
            if (safeExit(exit)) return dismount(exit, null);
            return fail("The planned stop has no safe dismount from the minecart's current position.");
        }
        if (cursor != observed || previousPosition == null || cart.position().distanceToSqr(previousPosition) >= .0025) {
            stalledTicks = 0;
        } else if (++stalledTicks >= STALL_TIMEOUT) {
            return abort("The minecart stopped. The track needs enough momentum or powered rail.");
        }
        cursor = observed;
        previousPosition = cart.position();
        detail = "Riding rail " + (cursor + 1) + "/" + route.size() + ".";
        if (cart.getDeltaMovement().horizontalDistanceSqr() > .025 * .025) return Input.EMPTY;
        return pushToward(waypoints.get(cursor + 1));
    }

    private Minecart findCart() {
        if (player.getVehicle() instanceof Minecart ridden) return ridden;
        if (player.isPassenger() || !player.onGround() || player.isInLiquid()
            || new MinecraftTerrain(level, player, options.avoidMobs()).startAtPlayer() == null) return null;
        if (Minecraft.getInstance().hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Minecart aimed
            && canBoard(aimed)) return aimed;
        Minecart nearest = null;
        double best = Double.POSITIVE_INFINITY;
        var vicinity = player.getBoundingBox().inflate(Math.min(6, player.entityInteractionRange()));
        for (Minecart candidate : level.getEntitiesOfClass(Minecart.class, vicinity, this::canBoard)) {
            double distance = player.distanceToSqr(candidate);
            if (distance >= best) continue;
            nearest = candidate;
            best = distance;
        }
        return nearest;
    }

    private boolean canBoard(Minecart candidate) {
        return candidate.isAlive() && !candidate.isVehicle() && !player.isPassenger()
            && player.isWithinEntityInteractionRange(candidate, 0) && player.hasLineOfSight(candidate)
            && candidate.getDeltaMovement().horizontalDistanceSqr() <= .04 * .04
            && track(candidate.getCurrentBlockPosOrRailBelow()) != null;
    }

    private Input board() {
        Minecraft mc = Minecraft.getInstance();
        if (boardingTicks == 0 && mc.gui.screen() != null) {
            detail = "Close the current screen to board the confirmed minecart.";
            return Input.EMPTY;
        }
        if (boardingTicks++ >= BOARD_TIMEOUT) return fail("The server did not confirm minecart boarding.");
        if (boardingTicks != 1) return Input.EMPTY;
        if (!canBoard(cart) || !cart.getCurrentBlockPosOrRailBelow().equals(start) || !liveTrackClear(0)
            || player.isShiftKeyDown() || player.isUsingItem()
            || new MinecraftTerrain(level, player, options.avoidMobs()).startAtPlayer() == null) {
            return fail("The minecart moved or its boarding position became unsafe.");
        }
        InteractionHand hand = player.getMainHandItem().isEmpty() ? InteractionHand.MAIN_HAND
            : player.getOffhandItem().isEmpty() ? InteractionHand.OFF_HAND : null;
        if (hand == null) return fail("Free either hand before boarding the minecart.");
        if (mc.gameMode == null || !mc.gameMode.interact(player, cart,
            new EntityHitResult(cart, cart.getBoundingBox().getCenter()), hand).consumesAction()) {
            return fail("The minecart could not be boarded.");
        }
        detail = "Waiting for the server to confirm minecart boarding.";
        return Input.EMPTY;
    }

    private RailShape track(BlockPos position) {
        if (position.getY() < level.getMinY() || position.getY() > level.getMaxY()
            || !level.hasChunk(position.getX() >> 4, position.getZ() >> 4)
            || !level.getWorldBorder().isWithinBounds(position)) return null;
        var state = level.getBlockState(position);
        if (!(state.getBlock() instanceof BaseRailBlock rail) || !state.getFluidState().isEmpty()
            || state.is(Blocks.ACTIVATOR_RAIL) && state.getValue(PoweredRailBlock.POWERED)) return null;
        return state.getValue(rail.getShapeProperty());
    }

    private boolean withinRange(BlockPos position) {
        int range = options.search().horizontalRange();
        return Math.abs(position.getX() - start.getX()) <= range && Math.abs(position.getZ() - start.getZ()) <= range;
    }

    private boolean destination(BlockPos position) {
        return Math.abs(position.getX() - goal.x()) <= goal.radius()
            && Math.abs(position.getZ() - goal.z()) <= goal.radius()
            && (goal.y16() == null || Math.abs(position.getY() * 16 - goal.y16()) <= 20);
    }

    private boolean canDepartToward(BlockPos next) {
        Vec3 velocity = cart.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() <= .04 * .04) return true;
        var offset = next.subtract(start);
        return velocity.x * offset.getX() + velocity.z * offset.getZ() > 0;
    }

    private int locateRail(BlockPos actual) {
        for (int index = cursor; index < route.size() && index <= cursor + LOOK_AHEAD; index++) {
            if (route.get(index).equals(actual)) return index;
        }
        return -1;
    }

    private boolean liveTrackClear(int first) {
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        for (int index = first; index < route.size() && index < first + LOOK_AHEAD; index++) {
            BlockPos position = route.get(index);
            RailShape shape = track(position);
            if (shape == null || !corridorClear(position, shape, exclusions)) return false;
            if (index + 1 < route.size() && !RailGeometry.connections(position, shape, this::track).contains(route.get(index + 1))) {
                return false;
            }
        }
        return true;
    }

    private boolean corridorClear(BlockPos position, RailShape shape, RouteExclusions exclusions) {
        var ends = RailGeometry.endpoints(position, shape);
        Vec3 from = ends.getFirst(), to = ends.getLast();
        double width = Math.max(cart.getBbWidth(), player.getBbWidth()) + .08;
        double height = player.getBbHeight() + .50;
        var body = new RouteExclusions.Footprint(width, height);
        if (exclusions.blockingSegment(point(from), point(to), body).isPresent()) return false;
        double radius = width / 2;
        for (int step = 0; step <= 4; step++) {
            Vec3 sample = from.lerp(to, step / 4.0);
            double bottom = sample.y + (shape.isSlope() ? .45 : .04);
            var box = new AABB(sample.x - radius, bottom, sample.z - radius,
                sample.x + radius, sample.y + height, sample.z + radius);
            if (!clear(box)) return false;
        }
        return true;
    }

    private boolean clear(AABB body) {
        if (!level.getWorldBorder().isWithinBounds(body.minX, body.minZ)
            || !level.getWorldBorder().isWithinBounds(body.maxX, body.maxZ)) return false;
        for (BlockPos position : BlockPos.betweenClosed(BlockPos.containing(body.minX, body.minY, body.minZ),
                BlockPos.containing(body.maxX - 1e-7, body.maxY - 1e-7, body.maxZ - 1e-7))) {
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) return false;
            var state = level.getBlockState(position);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB)) return false;
        }
        return level.noBlockCollision(cart, body)
            && level.getEntitiesOfClass(AbstractMinecart.class, body, other -> other != cart).isEmpty();
    }

    private boolean possibleExit(BlockPos position) {
        // Vanilla chooses a side at runtime. Require at least one loaded adjacent standing surface now.
        var terrain = new MinecraftTerrain(level, player);
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos next = position.relative(direction);
            for (var floor : terrain.floors(next.getX(), next.getZ(), (position.getY() - 1) * 16, (position.getY() + 2) * 16)) {
                if (terrain.canFinish(floor)) return true;
            }
        }
        return false;
    }

    private boolean safeExit(Vec3 exit) {
        if (exit.distanceToSqr(cart.position()) > 9) return false;
        var terrain = new MinecraftTerrain(level, player);
        if (!terrain.clearBodyAt(exit.x, exit.y, exit.z)) return false;
        var body = new RouteExclusions.Footprint(Math.max(.6, player.getBbWidth()), Math.max(1.8, player.getBbHeight()));
        if (Navigation.activeExclusions(options.avoidMobs()).blockingAt(point(exit), body).isPresent()) return false;
        double radius = body.width() / 2;
        var bounds = new AABB(exit.x - radius, exit.y + 1e-7, exit.z - radius,
            exit.x + radius, exit.y + body.height(), exit.z + radius);
        if (!level.noEntityCollision(player, bounds)) return false;
        int y16 = (int) Math.round(exit.y * 16);
        return terrain.floors(Mth.floor(exit.x), Mth.floor(exit.z), y16, y16).stream().anyMatch(terrain::canFinish);
    }

    private Input abort(String reason) {
        Vec3 exit = cart.getDismountLocationForPassenger(player);
        return safeExit(exit) ? dismount(exit, reason) : fail(reason + " No safe dismount was found.");
    }

    private Input dismount(Vec3 exit, String reason) {
        expectedExit = exit;
        abortReason = reason;
        dismountTicks = 1;
        groundedExitTicks = 0;
        detail = "Dismounting onto verified footing.";
        return DISMOUNT;
    }

    private Input confirmDismount() {
        if (++dismountTicks > BOARD_TIMEOUT) return fail("The server did not confirm a safe dismount.");
        if (!player.isPassenger()) {
            if (player.position().distanceToSqr(expectedExit) > 4) return fail("The server changed the expected dismount position.");
            boolean grounded = player.onGround() && new MinecraftTerrain(level, player, options.avoidMobs()).startAtPlayer() != null;
            groundedExitTicks = grounded ? groundedExitTicks + 1 : 0;
            if (groundedExitTicks < 2) return Input.EMPTY;
            if (abortReason != null) return fail(abortReason);
            status = Status.ARRIVED;
            detail = "Arrived by minecart and dismounted.";
            return Input.EMPTY;
        }
        if (player.getVehicle() != cart) return fail("The server changed the active vehicle during dismount.");
        Vec3 exit = cart.getDismountLocationForPassenger(player);
        if (!safeExit(exit) || exit.distanceToSqr(expectedExit) > 2) return fail("The minecart moved away from the verified dismount.");
        return DISMOUNT;
    }

    private Input pushToward(Vec3 target) {
        double x = target.x - cart.getX(), z = target.z - cart.getZ();
        if (options.steerView()) player.setYRot((float) Math.toDegrees(Math.atan2(-x, z)));
        double angle = Math.atan2(x, z) + Math.toRadians(player.getYRot());
        int octant = Math.floorMod((int) Math.round(angle * 4 / Math.PI), 8);
        return new Input(octant == 0 || octant == 1 || octant == 7, octant >= 3 && octant <= 5,
            octant >= 1 && octant <= 3, octant >= 5 && octant <= 7, false, false, false);
    }

    private static RouteExclusions.Point point(Vec3 vector) { return new RouteExclusions.Point(vector.x, vector.y, vector.z); }

    private Input fail(String message) {
        status = Status.FAILED;
        detail = message;
        return Input.EMPTY;
    }

    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }
}
