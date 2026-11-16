package dev.soprano.game.transport;

import dev.soprano.game.Navigation;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Keeps the player's head in open air over a level, connected surface. */
public final class SwimRoute implements TransportRoute {
    private static final double ARRIVAL_RADIUS = .38;
    private static final int STALL_TICKS = 80;
    private static final Input FLOAT = new Input(false, false, false, false, true, false, false);
    private static final Input SWIM = new Input(true, false, false, false, true, false, false);

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Goal goal;
    private final Navigation.Options options;
    private final double halfWidth;
    private final double height;
    private IncrementalSearch search;
    private List<Vec3> waypoints = List.of();
    private Status status = Status.PLANNING;
    private String detail;
    private Node start;
    private Vec2 direction;
    private int waterY;
    private int index;
    private int stalled;
    private int replans;
    private double bestDistance;

    public SwimRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.options = options;
        halfWidth = player.getBbWidth() * .5 + .035;
        height = player.getDimensions(net.minecraft.world.entity.Pose.STANDING).height();
        if (player.isInWater()) startSearch();
        else fail("Enter open water before starting a surface swim.");
    }

    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }
    @Override public Vec2 walkDirection() { return direction; }

    @Override
    public void advance(int expansions, long budgetNanos) {
        if (status != Status.PLANNING) return;
        var result = search.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) {
            detail = "Checking breathable surface water (" + result.visited() + " positions).";
            return;
        }
        if (result.status() != IncrementalSearch.Status.FOUND) {
            fail("No complete surface swim to the destination through loaded water. " + result.reason());
            return;
        }
        Vec3 first = center(start);
        if (!clearSegment(player.getX(), player.getZ(), first.x, first.z, false)) {
            recover("The player drifted away from the checked starting lane.");
            return;
        }
        var route = new ArrayList<Vec3>(result.path().size() + 1);
        route.add(first);
        for (var step : result.path()) route.add(center(step.to()));
        waypoints = List.copyOf(route);
        index = 0;
        resetProgress();
        status = Status.MOVING;
        detail = "Surface swim ready.";
    }

    @Override
    public Input input() {
        direction = null;
        if (status != Status.MOVING) return Input.EMPTY;
        Vec3 pos = player.position();
        if (player.isInLava() || pos.y > waterY + 1.55 || pos.y < waterY - 1.8
            || !clearSegment(pos.x, pos.z, pos.x, pos.z, false)) {
            fail("The player left breathable surface water. Take control.");
            return Input.EMPTY;
        }
        Vec3 motion = player.getDeltaMovement();
        Vec3 target = waypoints.get(index);
        double distance = horizontalDistance(pos, target);
        if (distance <= ARRIVAL_RADIUS) {
            if (index == waypoints.size() - 1) {
                if (motion.horizontalDistance() <= .075 && !player.isEyeInFluid(FluidTags.WATER)) {
                    status = Status.ARRIVED;
                    detail = "Arrived at the water surface.";
                }
                return FLOAT;
            }
            target = waypoints.get(++index);
            distance = horizontalDistance(pos, target);
            resetProgress();
        }
        if (player.isEyeInFluid(FluidTags.WATER) || pos.y < waterY + .04) {
            var body = player.getBoundingBox().expandTowards(0, Math.max(0, waterY + .50 - pos.y), 0);
            if (!level.noBlockCollision(player, body)) fail("There is an obstruction above the swimmer. Take control.");
            return status == Status.MOVING ? FLOAT : Input.EMPTY;
        }
        if (distance + .02 < bestDistance) {
            bestDistance = distance;
            stalled = 0;
        } else if (++stalled >= STALL_TICKS) {
            recover("Swimming progress stalled.");
            return FLOAT;
        }
        if (distance > 2 || !clearSegment(pos.x, pos.z, target.x, target.z, false)) {
            recover("The checked surface route changed.");
            return FLOAT;
        }
        double dx = (target.x - pos.x) / distance, dz = (target.z - pos.z) / distance;
        double driftX = pos.x + motion.x * 3 + dx * .10, driftZ = pos.z + motion.z * 3 + dz * .10;
        if (!clearSegment(pos.x, pos.z, driftX, driftZ, true)) return FLOAT;
        double yaw = Math.toRadians(player.getYRot());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        direction = new Vec2((float) (dx * cos + dz * sin), (float) (dz * cos - dx * sin));
        detail = "Swimming across the surface (" + (index + 1) + "/" + waypoints.size() + ").";
        return SWIM;
    }

    private void startSearch() {
        Vec3 pos = player.position();
        int x = Mth.floor(pos.x), z = Mth.floor(pos.z);
        if (!Node.validColumn(x) || !Node.validColumn(z) || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            fail("Start a surface swim in loaded water.");
            return;
        }
        int feetY = Mth.floor(pos.y);
        for (int y = feetY - 1; y <= feetY + 2; y++) {
            if (!isOpenSurface(new BlockPos(x, y, z))) continue;
            waterY = y;
            if (startSearchAt(x, z, pos)) return;
        }
        fail("Start within reach of a clear, calm water surface.");
    }

    private boolean startSearchAt(int x, int z, Vec3 from) {
        start = new Node(x, 0, z);
        if (!clearSegment(from.x, from.z, start.centerX(), start.centerZ(), false)) return false;
        if (goal.y16() != null && Math.abs(goal.y16() / 16.0 - (waterY + .5)) > .95) {
            fail("Pick a destination at this water surface's elevation.");
            return true;
        }
        var original = options.search();
        var config = new IncrementalSearch.Config(original.diagonals(), false, 0,
            original.horizontalRange(), original.maxVisited(), false, original.searchMode());
        search = new IncrementalSearch(new SurfaceTerrain(), start, new Goal(goal.x(), 0, goal.z(), goal.radius()), config);
        waypoints = List.of();
        status = Status.PLANNING;
        detail = "Checking the surface route.";
        return true;
    }

    private boolean clearSegment(double x, double z, double toX, double toZ, boolean entities) {
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(toX - x, toZ - z) / .24));
        double fromX = x, fromZ = z;
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        double feet = waterY + .08, top = waterY + 1.28 + height;
        var footprint = new RouteExclusions.Footprint(halfWidth * 2, top - feet);
        if (exclusions.blockingSegment(new RouteExclusions.Point(x, feet, z),
            new RouteExclusions.Point(toX, feet, toZ), footprint).isPresent()) return false;
        for (int sample = 1; sample <= samples; sample++) {
            double fraction = sample / (double) samples;
            double nextX = x + (toX - x) * fraction, nextZ = z + (toZ - z) * fraction;
            var sweep = new AABB(Math.min(fromX, nextX) - halfWidth, feet, Math.min(fromZ, nextZ) - halfWidth,
                Math.max(fromX, nextX) + halfWidth, top, Math.max(fromZ, nextZ) + halfWidth);
            if (!clearWater(sweep, entities)) return false;
            fromX = nextX;
            fromZ = nextZ;
        }
        return true;
    }

    private boolean clearWater(AABB sweep, boolean entities) {
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(sweep.minX, sweep.minZ) || !border.isWithinBounds(sweep.maxX, sweep.maxZ)) return false;
        BlockPos.MutableBlockPos water = new BlockPos.MutableBlockPos();
        for (int x = Mth.floor(sweep.minX); x <= Mth.floor(sweep.maxX); x++) {
            for (int z = Mth.floor(sweep.minZ); z <= Mth.floor(sweep.maxZ); z++) {
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) return false;
                if (!isOpenSurface(water.set(x, waterY, z))) return false;
            }
        }
        return entities ? level.noCollision(player, sweep) : level.noBlockCollision(player, sweep);
    }

    private boolean isOpenSurface(BlockPos pos) {
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && fluid.isSource()
            && !level.getBlockState(pos).is(Blocks.BUBBLE_COLUMN)
            && level.getFluidState(pos.above()).isEmpty();
    }

    private void recover(String reason) {
        if (options.autoReplan() && replans++ < options.replanLimit()) startSearch();
        else fail(reason);
    }

    private void resetProgress() {
        bestDistance = Double.POSITIVE_INFINITY;
        stalled = 0;
    }

    private void fail(String reason) {
        search = null;
        status = Status.FAILED;
        detail = reason;
    }

    private Vec3 center(Node node) {
        return new Vec3(node.centerX(), waterY + .9, node.centerZ());
    }

    private static double horizontalDistance(Vec3 from, Vec3 to) {
        return Math.hypot(from.x - to.x, from.z - to.z);
    }

    private final class SurfaceTerrain implements Terrain {
        @Override
        public List<Node> floors(int x, int z, int minY16, int maxY16) {
            if (minY16 > 0 || maxY16 < 0 || !clearSegment(x + .5, z + .5, x + .5, z + .5, false)) return List.of();
            return List.of(new Node(x, 0, z));
        }

        @Override
        public boolean canTraverse(Node from, Node to, Move move) {
            return move == Move.WALK && clearSegment(from.centerX(), from.centerZ(), to.centerX(), to.centerZ(), false);
        }
    }
}
