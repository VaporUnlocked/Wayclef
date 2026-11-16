package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Verifies a loaded source-lava crossing and steers a ridden strider to dry ground. */
public final class StriderRoute implements TransportRoute {
    private static final double EPSILON = 1e-5;
    private static final int STALL_TICKS = 55;
    private static final int LANDING_RADIUS = 3;

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Strider strider;
    private final Goal goal;
    private final Navigation.Options options;
    private LavaTerrain terrain;
    private IncrementalSearch search;
    private Node start;
    private Node landing;
    private List<Vec3> waypoints = List.of();
    private Status status = Status.PLANNING;
    private String detail = "Checking source lava and dry landing space.";
    private int waypoint;
    private int stalledTicks;
    private int replans;
    private double bestDistance = Double.POSITIVE_INFINITY;

    public static boolean riding(LocalPlayer player) { return player != null && player.getVehicle() instanceof Strider; }

    public static String readiness(LocalPlayer player) {
        if (!riding(player)) return "Mount a saddled strider for the lava crossing.";
        if (!player.getMainHandItem().is(Items.WARPED_FUNGUS_ON_A_STICK)
            || player.getOffhandItem().is(Items.WARPED_FUNGUS_ON_A_STICK))
            return "Hold the warped fungus on a stick in the main hand only to steer and stop on shore.";
        if (player.getControlledVehicle() != player.getVehicle()) return "Take control of the strider first.";
        return neutralSlot(player) < 0 ? "Leave a non-fungus hotbar slot available for stopping on shore." : null;
    }

    public StriderRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.options = options;
        this.strider = riding(player) ? (Strider) player.getVehicle() : null;
        String issue = readiness(player);
        if (issue == null) beginSearch();
        else fail(issue);
    }

    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }

    @Override
    public void advance(int expansions, long budgetNanos) {
        if (status != Status.PLANNING) return;
        if (!controlsStrider()) {
            fail("Strider steering was released. Take control of the crossing.");
            return;
        }
        var result = search.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) {
            detail = "Checking source lava and landing clearance (" + result.visited() + " positions).";
            return;
        }
        if (result.status() != IncrementalSearch.Status.FOUND) {
            fail("No complete loaded strider crossing to a clear dry landing was verified.");
            return;
        }
        List<Vec3> route = new ArrayList<>(result.path().size() + 1);
        route.add(terrain.center(start));
        for (var step : result.path()) route.add(terrain.center(step.to()));
        landing = result.path().isEmpty() ? start : result.path().getLast().to();
        waypoints = List.copyOf(route);
        if (!terrain.segment(strider.position(), waypoints.getFirst(), false)
            || horizontalDistance(strider.position(), waypoints.getFirst()) > 1.15) {
            recover("The strider moved away from the crossing start during planning.");
            return;
        }
        waypoint = 0;
        resetProgress();
        status = Status.MOVING;
        detail = "Verified strider crossing to dry ground.";
    }

    @Override
    public Input input() {
        if (status != Status.MOVING) return Input.EMPTY;
        if (!controlsStrider()) {
            fail("Strider steering was released. Take control of the crossing.");
            return Input.EMPTY;
        }
        terrain.beginSearchSlice();
        try {
            steer();
        } finally {
            terrain.endSearchSlice();
        }
        // Vanilla walks the strider forward while the rider holds the steering item.
        return Input.EMPTY;
    }

    private void steer() {
        Vec3 position = strider.position();
        if (!terrain.nearSurface(position) || !terrain.canFinish(landing)) {
            fail("The loaded lava surface or dry landing changed. Take control of the strider.");
            return;
        }
        int last = waypoints.size() - 1;
        while (waypoint < last && horizontalDistance(position, waypoints.get(waypoint)) <= .20) {
            waypoint++;
            resetProgress();
        }
        Vec3 target = waypoints.get(waypoint);
        double distance = horizontalDistance(position, target);
        if (waypoint == last && distance <= .28 && stopOnVerifiedGround()) {
            status = Status.ARRIVED;
            detail = "Strider reached verified dry ground. Dismount manually when ready.";
            return;
        }
        if (distance + .025 < bestDistance) {
            bestDistance = distance;
            stalledTicks = 0;
        } else if (++stalledTicks >= STALL_TICKS) {
            recover("The strider stopped making progress.");
            return;
        }
        Vec3 motion = strider.getDeltaMovement();
        if (distance > 1.65 || motion.horizontalDistance() > .32 || !terrain.segment(position, target, true)) {
            fail("The strider left its verified route. Take control of the crossing.");
            return;
        }
        if (distance < .02) return;
        double x = (target.x - position.x) / distance, z = (target.z - position.z) / distance;
        double forward = Math.min(.22, distance);
        Vec3 projected = position.add(motion.x + x * forward, 0, motion.z + z * forward);
        if (!terrain.segment(position, projected, true)) {
            fail("The next strider movement lost safe clearance. Take control of the crossing.");
            return;
        }
        player.setYRot((float) Math.toDegrees(Math.atan2(-x, z)));
        detail = "Strider crossing " + (waypoint + 1) + "/" + waypoints.size() + ".";
    }

    /** Release forward steering only when current footing and the residual drift are safely ashore. */
    public boolean stopOnVerifiedGround() {
        if (!controlsStrider() || !strider.onGround() || strider.isInLiquid()) return false;
        Vec3 current = strider.position(), motion = strider.getDeltaMovement();
        int neutral = neutralSlot(player);
        Node at = new Node(Mth.floor(current.x), 0, Mth.floor(current.z));
        Vec3 coast = current.add(motion.x * 4, 0, motion.z * 4);
        if (neutral < 0 || Math.abs(current.y - terrain.dryHeight()) > .08 || !terrain.canFinish(at)
            || !terrain.drySegment(current, coast)) return false;
        player.getInventory().setSelectedSlot(neutral);
        return true;
    }

    private boolean controlsStrider() {
        return strider != null && strider.isAlive() && !strider.isRemoved() && strider.level() == level
            && player.getVehicle() == strider && readiness(player) == null;
    }

    private void beginSearch() {
        Vec3 position = strider.position();
        int y = Mth.floor(position.y + .04);
        start = new Node(Mth.floor(position.x), 0, Mth.floor(position.z));
        terrain = terrainAt(y);
        if (!terrain.nearSurface(position)) terrain = terrainAt(y - 1);
        if (!terrain.nearSurface(position) || !terrain.segment(position, terrain.center(start), false)) {
            fail("Start on a loaded level lava surface or a full-block shore within the strider's step height.");
            return;
        }
        Integer y16 = goal.y16();
        if (goal.sightY() != null || y16 != null && Math.abs(y16 / 16.0 - terrain.dryHeight()) > 1.0) {
            fail("Pick a dry shore destination at the crossing's elevation.");
            return;
        }
        int radius = Math.max(goal.radius(), LANDING_RADIUS);
        var destination = new Goal(goal.x(), 0, goal.z(), radius);
        var settings = options.search();
        var config = new IncrementalSearch.Config(false, false, 0, settings.horizontalRange(), settings.maxVisited(),
            false, settings.searchMode());
        search = new IncrementalSearch(terrain, start, destination, config);
        status = Status.PLANNING;
        waypoints = List.of();
        detail = "Checking source lava and dry landing space.";
    }

    private LavaTerrain terrainAt(int baseY) {
        double riderOffset = horizontalDistance(strider.position(), player.position());
        double halfWidth = Math.max(strider.getBbWidth() * .5, riderOffset + player.getBbWidth() * .5) + .01;
        double height = Math.max(strider.getBbHeight(), player.getBoundingBox().maxY - strider.getY()) + .08;
        return new LavaTerrain(level, level::hasChunk, strider, baseY, halfWidth, height, strider.maxUpStep(),
            () -> Navigation.activeExclusions(options.avoidMobs()));
    }

    private void recover(String reason) {
        if (options.autoReplan() && replans++ < options.replanLimit()) beginSearch();
        else fail(reason + " Take control of the crossing.");
    }

    private void resetProgress() {
        bestDistance = Double.POSITIVE_INFINITY;
        stalledTicks = 0;
    }

    private void fail(String message) {
        status = Status.FAILED;
        detail = message;
        search = null;
    }

    private static int neutralSlot(LocalPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        for (int slot = 0; slot < 9; slot++) {
            if (!inventory.getItem(slot).is(Items.WARPED_FUNGUS_ON_A_STICK)) return slot;
        }
        return -1;
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private enum Surface { LAVA, DRY, UNSAFE }

    static final class LavaTerrain implements Terrain {
        @FunctionalInterface
        interface ChunkAvailability { boolean loaded(int x, int z); }

        private final CollisionGetter level;
        private final ChunkAvailability chunks;
        private final Entity strider;
        private final int baseY;
        private final double halfWidth;
        private final double height;
        private final double stepHeight;
        private final Supplier<RouteExclusions> exclusions;
        private RouteExclusions sliceExclusions;

        LavaTerrain(CollisionGetter level, ChunkAvailability chunks, Entity strider, int baseY, double halfWidth,
                    double height, double stepHeight, Supplier<RouteExclusions> exclusions) {
            this.level = level;
            this.chunks = chunks;
            this.strider = strider;
            this.baseY = baseY;
            this.halfWidth = halfWidth;
            this.height = height;
            this.stepHeight = stepHeight;
            this.exclusions = exclusions;
        }

        @Override public void beginSearchSlice() { sliceExclusions = exclusions.get(); }
        @Override public void endSearchSlice() { sliceExclusions = null; }

        double dryHeight() { return baseY + 1.0; }

        Vec3 center(Node node) {
            double y = surface(node.x(), node.z()) == Surface.LAVA ? baseY + .5 : dryHeight();
            return new Vec3(node.centerX(), y, node.centerZ());
        }

        boolean nearSurface(Vec3 feet) {
            Surface on = surface(Mth.floor(feet.x), Mth.floor(feet.z));
            double expected = on == Surface.DRY ? dryHeight() : baseY + .5;
            return on != Surface.UNSAFE && Math.abs(feet.y - expected) <= .55;
        }

        @Override
        public List<Node> floors(int x, int z, int minimum, int maximum) {
            if (minimum > 0 || maximum < 0 || surface(x, z) == Surface.UNSAFE) return List.of();
            Node node = new Node(x, 0, z);
            Vec3 point = center(node);
            return segment(point, point, false) ? List.of(node) : List.of();
        }

        @Override
        public boolean canTraverse(Node from, Node to, Move move) {
            if (move != Move.WALK || Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()) != 1) return false;
            Vec3 first = center(from), second = center(to);
            return Math.abs(first.y - second.y) <= stepHeight + EPSILON && segment(first, second, false);
        }

        @Override
        public boolean canFinish(Node node) {
            for (int z = node.z() - 1; z <= node.z() + 1; z++) {
                for (int x = node.x() - 1; x <= node.x() + 1; x++) {
                    Vec3 point = new Vec3(x + .5, dryHeight(), z + .5);
                    if (surface(x, z) != Surface.DRY || !segment(point, point, false)) return false;
                }
            }
            return true;
        }

        boolean drySegment(Vec3 from, Vec3 to) { return segment(from, to, true, true); }

        boolean segment(Vec3 from, Vec3 to, boolean entities) { return segment(from, to, entities, false); }

        private boolean segment(Vec3 from, Vec3 to, boolean entities, boolean dryOnly) {
            double minX = Math.min(from.x, to.x) - halfWidth, maxX = Math.max(from.x, to.x) + halfWidth;
            double minZ = Math.min(from.z, to.z) - halfWidth, maxZ = Math.max(from.z, to.z) + halfWidth;
            double bodyTop = Math.max(dryHeight(), Math.max(from.y, to.y)) + height;
            if (bodyTop > level.getMaxY() + 1 || !level.getWorldBorder().isWithinBounds(minX, minZ)
                || !level.getWorldBorder().isWithinBounds(maxX, maxZ)) return false;
            for (int x = Mth.floor(minX); x <= Mth.floor(maxX - EPSILON); x++) {
                for (int z = Mth.floor(minZ); z <= Mth.floor(maxZ - EPSILON); z++) {
                    Surface floor = surface(x, z);
                    if (floor == Surface.UNSAFE || dryOnly && floor != Surface.DRY) return false;
                    for (int y = baseY + 1; y <= Mth.floor(bodyTop - EPSILON); y++) {
                        if (MinecraftTerrain.inherentHazard(level.getBlockState(new BlockPos(x, y, z)))) return false;
                    }
                }
            }
            var activeExclusions = sliceExclusions == null ? exclusions.get() : sliceExclusions;
            var footprint = new RouteExclusions.Footprint(halfWidth * 2, bodyTop - baseY);
            var first = new RouteExclusions.Point(from.x, baseY, from.z);
            var last = new RouteExclusions.Point(to.x, baseY, to.z);
            if (activeExclusions.blockingSegment(first, last, footprint).isPresent()) return false;
            AABB hull = new AABB(minX, dryHeight() + EPSILON, minZ, maxX, bodyTop, maxZ);
            return level.noBlockCollision(strider, hull) && (!entities || level.noEntityCollision(strider, hull));
        }

        private Surface surface(int x, int z) {
            if (baseY < level.getMinY() || baseY >= level.getMaxY() || !chunks.loaded(x >> 4, z >> 4)) return Surface.UNSAFE;
            var floor = new BlockPos(x, baseY, z);
            var state = level.getBlockState(floor);
            var fluid = state.getFluidState();
            if (state.is(Blocks.LAVA) && fluid.isSource() && level.getFluidState(floor.above()).isEmpty())
                return Surface.LAVA;
            if (!MinecraftTerrain.inherentHazard(state) && state.isCollisionShapeFullBlock(level, floor)) return Surface.DRY;
            return Surface.UNSAFE;
        }
    }
}
