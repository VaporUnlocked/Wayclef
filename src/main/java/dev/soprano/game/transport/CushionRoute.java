package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.path.Goal;
import dev.soprano.path.RouteExclusions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Plans the fewest confirmed right-click transfers between loaded cushions. */
public final class CushionRoute implements TransportRoute {
    private static final int CONFIRM_TICKS = 40;
    private static final double EDGE_INSET = .025;

    private record Step(Cushion seat, Step previous) { }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Goal goal;
    private final Navigation.Options options;
    private final MinecraftTerrain terrain;
    private final Vec3 start;
    private final ArrayDeque<Step> open = new ArrayDeque<>();
    private final Set<Integer> visited = new HashSet<>();
    private List<Cushion> route = List.of();
    private List<Vec3> waypoints = List.of();
    private Cushion current, pending;
    private int next, ticks, sentAt, attempts, replans;
    private Status status = Status.PLANNING;
    private String detail = "Checking loaded cushions.";

    public CushionRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.options = options;
        terrain = new MinecraftTerrain(level, player, options.avoidMobs());
        current = player.getVehicle() instanceof Cushion cushion ? cushion : null;
        start = current == null ? player.position() : current.position();
        String issue = readiness(player);
        if (issue != null) fail(issue);
        else if (goal.sightY() != null || goal.skyExposed()) fail("Pick the coordinates of a destination cushion.");
        else startSearch();
    }

    public static String readiness(LocalPlayer player) {
        if (player.getVehicle() instanceof Cushion) return null;
        return !player.isPassenger() && player.onGround() && !player.isInLiquid() && !player.isFallFlying()
            ? null : "Start on a cushion or on dry ground within reach of one.";
    }

    public static boolean free(Cushion seat) { return seat.isAlive() && !seat.isRemoved() && !seat.isVehicle(); }

    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }
    @Override public Input input() { return Input.EMPTY; }
    @Override public boolean allowsDismounted() { return true; }

    @Override
    public void advance(int expansions, long budgetNanos) {
        ticks++;
        if (status == Status.MOVING) {
            transfer();
            return;
        }
        if (status != Status.PLANNING) return;
        if (player.getVehicle() != current || current == null && readiness(player) != null) {
            fail("The player's starting seat or footing changed.");
            return;
        }
        long deadline = System.nanoTime() + budgetNanos;
        RouteExclusions exclusions = Navigation.activeExclusions(options.avoidMobs());
        double reach = player.entityInteractionRange();
        terrain.beginSearchSlice();
        try {
            for (int i = 0; i < expansions && (i == 0 || System.nanoTime() < deadline); i++) {
                Step step = open.pollFirst();
                if (step == null) {
                    fail("No reachable chain of loaded cushions leads to the destination.");
                    return;
                }
                if (step.seat() != null && matches(step.seat())) {
                    setRoute(step);
                    return;
                }
                // A passenger update can arrive before the local rider is positioned on the seat.
                enqueue(step, eyeAt(step.seat()), reach, exclusions);
            }
        } finally {
            terrain.endSearchSlice();
        }
        detail = "Checking loaded cushions (" + visited.size() + " reached).";
    }

    private void enqueue(Step from, Vec3 eye, double reach, RouteExclusions exclusions) {
        AABB vicinity = new AABB(eye, eye).inflate(reach);
        var candidates = new ArrayList<>(level.getEntitiesOfClass(Cushion.class, vicinity, seat -> free(seat)
            && !visited.contains(seat.getId()) && inSearchRange(seat)));
        candidates.sort(Comparator.comparingDouble(seat -> seat.position().distanceToSqr(goal.x() + .5, seat.getY(), goal.z() + .5)));
        int limit = Math.min(options.search().maxVisited(), 4096);
        for (Cushion seat : candidates) {
            if (visited.size() >= limit) return;
            if (usable(seat, exclusions) && hit(level, player, eye, seat) != null) {
                visited.add(seat.getId());
                open.addLast(new Step(seat, from));
            }
        }
    }

    private boolean inSearchRange(Cushion seat) {
        int range = options.search().horizontalRange();
        return Math.abs(seat.getX() - start.x) <= range && Math.abs(seat.getZ() - start.z) <= range;
    }

    private boolean matches(Cushion seat) {
        Vec3 at = seat.position();
        BlockPos block = BlockPos.containing(at);
        if (Math.abs((long) block.getX() - goal.x()) > goal.radius() || Math.abs((long) block.getZ() - goal.z()) > goal.radius()
            || goal.y16() != null && Math.abs(at.y - goal.y16() / 16.0) >= 1
            || goal.heightBand() != null && (at.y * 16 < goal.heightBand().minY16() || at.y * 16 > goal.heightBand().maxY16())) {
            return false;
        }
        var bounds = goal.bounds();
        return bounds == null || at.x >= bounds.minX() && at.x < bounds.maxX()
            && at.y >= bounds.minY() && at.y < bounds.maxY() && at.z >= bounds.minZ() && at.z < bounds.maxZ();
    }

    private void setRoute(Step destination) {
        var chain = new ArrayDeque<Cushion>();
        for (Step at = destination; at.previous() != null; at = at.previous()) chain.addFirst(at.seat());
        route = List.copyOf(chain);
        var path = new ArrayList<Vec3>();
        path.add(player.position());
        route.forEach(seat -> path.add(seat.position()));
        waypoints = List.copyOf(path);
        next = 0;
        if (route.isEmpty()) arrive();
        else {
            status = Status.MOVING;
            detail = "Verified " + route.size() + (route.size() == 1 ? " cushion hop." : " cushion hops.");
        }
    }

    private void transfer() {
        if (pending != null) {
            if (player.getVehicle() == pending) {
                current = pending;
                pending = null;
                attempts = 0;
                if (++next == route.size()) {
                    arrive();
                    return;
                }
            } else {
                if (ticks - sentAt < CONFIRM_TICKS) return;
                pending = null;
                if (++attempts >= 2) {
                    fail("The server did not confirm the next cushion transfer.");
                    return;
                }
            }
        }
        if (player.getVehicle() != current || readiness(player) != null || player.isShiftKeyDown()) {
            fail("The player left the cushion route.");
            return;
        }
        if (player.isUsingItem() || Minecraft.getInstance().gui.screen() != null) return;
        Cushion target = route.get(next);
        EntityHitResult aim = current == null ? seatHit(level, player, target) : hit(level, player, eyeAt(current), target);
        if (!usable(target, Navigation.activeExclusions(options.avoidMobs())) || aim == null) {
            if (options.autoReplan() && replans++ < options.replanLimit()) startSearch();
            else fail("The next cushion is occupied, obstructed, or beyond reach.");
            return;
        }
        var mode = Minecraft.getInstance().gameMode;
        if (mode == null || !mode.interact(player, target, aim, InteractionHand.MAIN_HAND).consumesAction()) {
            fail("The game rejected the next cushion click.");
            return;
        }
        pending = target;
        sentAt = ticks;
        detail = "Cushion hop " + (next + 1) + " of " + route.size() + ".";
    }

    private void startSearch() {
        open.clear();
        visited.clear();
        attempts = 0;
        if (current != null) visited.add(current.getId());
        open.add(new Step(current, null));
        status = Status.PLANNING;
        detail = "Checking loaded cushions.";
    }

    private boolean usable(Cushion seat, RouteExclusions exclusions) {
        if (!free(seat) || seat.level() != level) return false;
        var cell = seat.blockPosition();
        // The vanilla seated pose puts the player's feet below the cushion's supporting surface.
        // Check the space above that surface while using the rider's real eye position to reach the next seat.
        Vec3 at = seat.position();
        var body = player.getDimensions(Pose.STANDING);
        return !level.isOutsideBuildHeight(cell) && level.getWorldBorder().isWithinBounds(cell)
            && level.getFluidState(cell).isEmpty()
            && level.getChunkSource().hasChunk(cell.getX() >> 4, cell.getZ() >> 4)
            && exclusions.blockingAt(new RouteExclusions.Point(at.x, at.y, at.z),
                new RouteExclusions.Footprint(body.width(), body.height())).isEmpty()
            && terrain.clearBodyAt(at.x, at.y, at.z);
    }

    private Vec3 eyeAt(Cushion seat) {
        if (seat == null) return player.getEyePosition();
        return seat.getPassengerRidingPosition(player).subtract(player.getVehicleAttachmentPoint(seat))
            .add(0, player.getEyeHeight(Pose.STANDING), 0);
    }

    public static EntityHitResult seatHit(ClientLevel level, LocalPlayer player, Cushion seat) {
        if (!player.isWithinEntityInteractionRange(seat, 0)) return null;
        return hit(level, player, player.getEyePosition(), seat);
    }

    private static EntityHitResult hit(ClientLevel level, LocalPlayer player, Vec3 eye, Cushion seat) {
        AABB bounds = seat.getBoundingBox();
        Vec3 closest = new Vec3(
            inset(eye.x, bounds.minX, bounds.maxX),
            inset(eye.y, bounds.minY, bounds.maxY),
            inset(eye.z, bounds.minZ, bounds.maxZ));
        double reach = player.entityInteractionRange();
        for (var point : List.of(closest, bounds.getCenter())) {
            if (eye.distanceToSqr(point) > reach * reach) continue;
            var result = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (result.getType() == HitResult.Type.MISS) return new EntityHitResult(seat, point);
        }
        return null;
    }

    private static double inset(double value, double minimum, double maximum) {
        double margin = Math.min(EDGE_INSET, (maximum - minimum) * .25);
        return Math.clamp(value, minimum + margin, maximum - margin);
    }

    private void arrive() {
        status = Status.ARRIVED;
        detail = "Arrived at the destination cushion.";
    }

    private void fail(String reason) {
        status = Status.FAILED;
        detail = reason;
    }
}
