package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import dev.soprano.path.RoutePolicy;
import dev.soprano.path.Terrain;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Client-thread view of the loaded collision geometry used by planning and movement. */
public final class MinecraftTerrain implements Terrain {
    private record Collision(BlockState state, boolean unsafe, boolean full, List<AABB> boxes) { }
    private record DoorCollision(BlockPos position, BlockState state, List<AABB> boxes) { }
    private enum BubbleFlow {
        UP(16), DOWN(-16);

        final int step;

        BubbleFlow(int step) { this.step = step; }
    }
    private static final Collision AIR = new Collision(null, false, false, List.of());
    private static final AABB UNIT_CUBE = new AABB(0, 0, 0, 1, 1, 1);
    private static final List<AABB> UNIT_BOXES = List.of(UNIT_CUBE);
    private static final int[][] CARDINALS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    private static final double EPSILON = WalkingSweep.EPSILON;
    private static final double FLOOR_REACH = .045;
    private static final double STEP_HEIGHT = WalkingSweep.MAX_STEP;
    private static final double MAX_WALK_RISE = 1.00001;
    private static final double JUMP_APEX = 1.253;
    private static final double GAP_TAKEOFF_OFFSET = .39;
    private static final double MAX_DROP = 3.00001;
    private static final double MIN_FOOT_OVERLAP = .12;
    private static final double FOOTING_EXCLUSION_DEPTH = .015;

    private static final Set<Block> HAZARDS = Set.of(
        Blocks.BUBBLE_COLUMN, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY,
        Blocks.POWDER_SNOW, Blocks.MAGMA_BLOCK, Blocks.FIRE, Blocks.SOUL_FIRE,
        Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.WITHER_ROSE,
        Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB, Blocks.MOVING_PISTON, Blocks.SCAFFOLDING,
        Blocks.POINTED_DRIPSTONE, Blocks.SLIME_BLOCK
    );

    @FunctionalInterface
    interface ChunkAvailability { boolean loaded(int x, int z); }

    private final CollisionGetter level;
    private final ChunkAvailability chunks;
    private final LocalPlayer player;
    private final double halfWidth;
    private final double standingHeight;
    private final RouteExclusions.Footprint footprint;
    private final Supplier<RouteExclusions> exclusionSource;
    private final Set<BlockPos> permittedPortalCells;
    private final RoutePermissions permissions;
    private final RoutePolicy routePolicy;
    private final int minY;
    private final int maxY;
    private final BlockPos.MutableBlockPos queryPosition = new BlockPos.MutableBlockPos();
    private final Long2ObjectOpenHashMap<Collision> collisionCache = new Long2ObjectOpenHashMap<>();
    private boolean cacheActive;
    private RouteExclusions exclusions;
    private String exclusionReason;
    private final WalkingSweep.Geometry walkingGeometry = new WalkingSweep.Geometry() {
        @Override public double support(double x, double z, double bottom, double top) {
            return supportHeight(x, z, bottom, top, EPSILON);
        }

        @Override public boolean clear(double x, double y, double z, double toX, double toY, double toZ) {
            return clearSegment(x, y, z, toX, toY, toZ);
        }
    };

    public MinecraftTerrain(ClientLevel level, LocalPlayer player) {
        this(level, player, false);
    }

    public MinecraftTerrain(ClientLevel level, LocalPlayer player, boolean avoidMobs) {
        this(level, player, avoidMobs, new RoutePermissions(0, 0, false, false), Set.of());
    }

    MinecraftTerrain(ClientLevel level, LocalPlayer player, boolean avoidMobs,
                     RoutePermissions permissions, Set<BlockPos> permittedPortalCells) {
        this(level, player, avoidMobs, permissions, permittedPortalCells, RoutePolicy.UNRESTRICTED);
    }

    MinecraftTerrain(ClientLevel level, LocalPlayer player, boolean avoidMobs,
                     RoutePermissions permissions, Set<BlockPos> permittedPortalCells, RoutePolicy routePolicy) {
        this(level, player, level::hasChunk,
            Math.max(player.getDimensions(Pose.STANDING).width(), player.getBbWidth()) / 2.0,
            Math.max(player.getDimensions(Pose.STANDING).height(), player.getBbHeight()),
            () -> Navigation.activeExclusions(avoidMobs), permissions, permittedPortalCells, routePolicy);
    }

    MinecraftTerrain(CollisionGetter level, LocalPlayer player, ChunkAvailability chunks, double halfWidth, double standingHeight) {
        this(level, player, chunks, halfWidth, standingHeight, () -> null);
    }

    MinecraftTerrain(CollisionGetter level, LocalPlayer player, ChunkAvailability chunks, double halfWidth,
                     double standingHeight, Supplier<RouteExclusions> exclusions) {
        this(level, player, chunks, halfWidth, standingHeight, exclusions, new RoutePermissions(0, 0, false, false), Set.of());
    }

    MinecraftTerrain(CollisionGetter level, LocalPlayer player, ChunkAvailability chunks, double halfWidth,
                     double standingHeight, Supplier<RouteExclusions> exclusions, RoutePermissions permissions,
                     Set<BlockPos> permittedPortalCells) {
        this(level, player, chunks, halfWidth, standingHeight, exclusions, permissions, permittedPortalCells,
            RoutePolicy.UNRESTRICTED);
    }

    MinecraftTerrain(CollisionGetter level, LocalPlayer player, ChunkAvailability chunks, double halfWidth,
                     double standingHeight, Supplier<RouteExclusions> exclusions, RoutePermissions permissions,
                     Set<BlockPos> permittedPortalCells, RoutePolicy routePolicy) {
        this.level = level;
        this.player = player;
        this.chunks = chunks;
        this.halfWidth = halfWidth;
        this.standingHeight = standingHeight;
        this.footprint = new RouteExclusions.Footprint(halfWidth * 2, standingHeight + FOOTING_EXCLUSION_DEPTH);
        this.exclusionSource = exclusions;
        this.permissions = permissions;
        this.routePolicy = routePolicy;
        this.permittedPortalCells = Set.copyOf(permittedPortalCells);
        minY = level.getMinY();
        maxY = level.getMaxY();
    }

    /** Choose a nearby supported center that vanilla walking can reach from the actual feet. */
    public Node startAtPlayer() {
        if (!isPlayerClear()) return null;
        double x = player.getX(), y = player.getY(), z = player.getZ();
        int columnX = cell(x), columnZ = cell(z);
        if (!player.onGround()) {
            if (!player.onClimbable() || !climbAt(columnX, cell(y), columnZ)) return null;
            return new Node(columnX, cell(y) * 16, columnZ);
        }
        int lower = (int) Math.ceil((y - STEP_HEIGHT) * 16.0);
        int upper = (int) Math.floor((y + STEP_HEIGHT) * 16.0);
        Node nearest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int candidateX = columnX + dx, candidateZ = columnZ + dz;
                double horizontal = Math.hypot(candidateX + .5 - x, candidateZ + .5 - z);
                if (horizontal > 1.15 || horizontal >= bestDistance) continue;
                for (Node candidate : floors(candidateX, candidateZ, lower, upper)) {
                    double distance = Math.hypot(horizontal, Math.abs(feetY(candidate) - y));
                    if (distance < bestDistance && level.noBlockCollision(player,
                        sweptBody(centerX(candidate), feetY(candidate), centerZ(candidate), centerX(candidate), feetY(candidate), centerZ(candidate)))
                        && canApproach(candidate)) {
                        nearest = candidate;
                        bestDistance = distance;
                    }
                }
            }
        }
        return nearest;
    }

    @Override
    public List<Node> floors(int x, int z, int minY16, int maxY16) {
        exclusionReason = null;
        double centerX = x + .5, centerZ = z + .5;
        if (minY16 > maxY16 || !areaAvailable(centerX - halfWidth, centerZ - halfWidth,
            centerX + halfWidth, centerZ + halfWidth)) return List.of();
        int bottom = Math.max(minY, Math.floorDiv(minY16, 16) - 1);
        int top = Math.min(maxY, Math.floorDiv(maxY16, 16));
        if (bottom > top) return List.of();

        List<Node> candidates = new ArrayList<>(2);
        for (int y = bottom; y <= top; y++) {
            Collision collision = collision(x, y, z);
            if (collision == AIR || collision.unsafe()) continue;
            int rungHeight = y * 16;
            if (rungHeight >= minY16 && rungHeight <= maxY16 && climbAt(x, y, z)
                && clearBodyAt(centerX, y, centerZ)) {
                addFloor(candidates, new Node(x, rungHeight, z));
            }
            for (AABB shape : collision.boxes()) {
                if (shape.maxY > 1 + EPSILON || !footOverlap(centerX, centerZ, x, z, shape, MIN_FOOT_OVERLAP)) continue;
                double surface = y + shape.maxY;
                int height = (int) Math.round(surface * 16);
                if (height < minY16 || height > maxY16 || Math.abs(height / 16.0 - surface) > EPSILON) continue;
                Node candidate = new Node(x, height, z);
                if (clearBodyAt(centerX, surface, centerZ)) addFloor(candidates, candidate);
            }
        }
        return candidates;
    }

    private static void addFloor(List<Node> floors, Node node) {
        if (!floors.contains(node)) floors.add(node);
    }

    /** Resolves the support below a live target position to a safe route node in its current column. */
    Node followGroundBelow(double x, double y, double z) {
        int columnX = cell(x), columnZ = cell(z);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Node.validColumn(columnX) || !Node.validColumn(columnZ)
            || !areaAvailable(x - halfWidth, z - halfWidth, x + halfWidth, z + halfWidth)) return null;

        double ceiling = Math.min(y + FLOOR_REACH, Node.MAX_Y16 / 16.0);
        int bottom = Math.max(minY, Math.floorDiv(Node.MIN_Y16, 16));
        for (int layer = Math.min(maxY + 1, cell(ceiling)); layer >= bottom; layer--) {
            // A fluid or hazardous block directly beneath the target is not a route to a lower floor.
            if (layer <= maxY && unsafeAt(columnX, layer, columnZ)) return null;
            double surface = supportHeight(x, z, layer, Math.min(layer + 1.0, ceiling), MIN_FOOT_OVERLAP);
            if (!Double.isFinite(surface)) continue;
            if (!clearBodyAt(x, surface, z)) return null;

            int lower = Math.max(Node.MIN_Y16, (int) Math.ceil((surface - STEP_HEIGHT) * 16));
            int upper = Math.min(Node.MAX_Y16, (int) Math.floor((surface + STEP_HEIGHT) * 16));
            Node nearest = null;
            double bestDifference = Double.POSITIVE_INFINITY;
            for (Node candidate : floors(columnX, columnZ, lower, upper)) {
                double difference = Math.abs(candidate.y() - surface);
                if (difference < bestDifference && canApproachFrom(x, surface, z, candidate)) {
                    nearest = candidate;
                    bestDifference = difference;
                }
            }
            return nearest;
        }
        return null;
    }

    @Override public void beginSearchSlice() {
        exclusions = exclusionSource.get();
        cacheActive = true;
    }

    @Override public void endSearchSlice() {
        cacheActive = false;
        collisionCache.clear();
        exclusions = null;
    }

    @Override public boolean supportsExtendedSteps() { return true; }
    @Override public boolean supportsGapJumps() { return true; }
    @Override public boolean supportsClimbing() { return true; }

    @Override public boolean canFinish(Node node) {
        return supportedAt(centerX(node), feetY(node), centerZ(node));
    }

    @Override public boolean isSkyExposed(Node node) {
        return isSkyExposed(node.centerX(), node.y(), node.centerZ());
    }

    boolean isSkyExposed(double x, double y, double z) {
        if (!(level instanceof ClientLevel client) || !client.dimensionType().hasSkyLight()) return false;
        int columnX = cell(x), columnZ = cell(z), aboveFeet = (int) Math.ceil(y);
        if (!chunks.loaded(columnX >> 4, columnZ >> 4)) return false;
        return client.getHeight(Heightmap.Types.MOTION_BLOCKING, columnX, columnZ) <= aboveFeet
            && client.canSeeSky(new BlockPos(columnX, aboveFeet, columnZ));
    }

    @Override public int transitionPenalty(Node from, Node to, Move move) { return routePolicy.penalty(to); }

    boolean selectedBody(Node node) {
        return routePolicy.containsBody(node.centerX(), node.y(), node.centerZ(), halfWidth, standingHeight);
    }

    boolean selectedBlock(BlockPos block) {
        return routePolicy.containsBody(block.getX() + .5, block.getY(), block.getZ() + .5, .5, 1);
    }

    @Override
    public boolean canTraverse(Node from, Node to, Move move) {
        exclusionReason = null;
        if (move == Move.CLIMB) return canClimb(from, to);
        if (move == Move.BUBBLE) return canRideBubble(from, to);
        if (move == Move.JUMP && Step.isGapSpan(from, to)) return canGapJump(from, to);
        int dx = Math.abs(from.x() - to.x()), dz = Math.abs(from.z() - to.z());
        if (dx > 1 || dz > 1 || dx + dz == 0) return false;
        double fromX = centerX(from), fromY = feetY(from), fromZ = centerZ(from);
        double toX = centerX(to), toY = feetY(to), toZ = centerZ(to);
        double rise = toY - fromY;
        if (move == Move.WALK && from.y16() == to.y16() && (from.y16() & 15) == 0
            && fullBlockFooting(from, to)) {
            boolean clear = clearSegment(fromX, fromY, fromZ, toX, toY, toZ);
            // A cardinal swept box is exact. For diagonals the box may include extra corners.
            if (clear || dx + dz == 1) return clear;
        }
        if (!supportedAt(fromX, fromY, fromZ) || !supportedAt(toX, toY, toZ)) return false;

        if (move == Move.WALK) {
            if (dx + dz == 2 && (!supportsEither(fromX, toZ, fromY, toY)
                || !supportsEither(toX, fromZ, fromY, toY))) return false;
            if (permissions.useDoors() && from.y16() != to.y16() && closedTrapdoorOverFooting(from, to)) return false;
            return walkSweep(fromX, fromY, fromZ, toX, toY, toZ);
        }
        if (dx + dz != 1) return false;
        if (move == Move.JUMP && rise > STEP_HEIGHT && rise <= 1 + EPSILON) {
            return aerialSweep(fromX, fromY, fromZ, toX, toY, toZ, fromY + JUMP_APEX);
        }
        if (move == Move.DROP && rise < -STEP_HEIGHT && -rise <= MAX_DROP) {
            return aerialSweep(fromX, fromY, fromZ, toX, toY, toZ, fromY);
        }
        return false;
    }

    private boolean canGapJump(Node from, Node to) {
        if ((from.y16() & 15) != 0 || halfWidth < .27 || halfWidth > .36) return false;
        int y = from.y16() / 16;
        if (y <= minY || y + standingHeight + JUMP_APEX > maxY + 1) return false;
        double x = centerX(from), z = centerZ(from), toX = centerX(to), toZ = centerZ(to);
        AABB arc = sweptBody(x, y, z, toX, y + JUMP_APEX, toZ);
        if (!areaAvailable(arc.minX, arc.minZ, arc.maxX, arc.maxZ)) return false;
        Collision takeoff = collision(from.x(), y - 1, from.z());
        Collision landing = collision(to.x(), y - 1, to.z());
        Collision gap = collision((from.x() + to.x()) / 2, y - 1, (from.z() + to.z()) / 2);
        if (!takeoff.full() || takeoff.unsafe() || !landing.full() || landing.unsafe() || gap != AIR) return false;
        var floor = takeoff.state().getBlock();
        if (Math.abs(floor.getFriction() - .6) > .005 || Math.abs(floor.getJumpFactor() - 1) > .005
            || Math.abs(floor.getSpeedFactor() - 1) > .005) return false;
        if (!clear(arc) || !level.noBlockCollision(player, arc)) return false;
        for (int sample = 0; sample <= 8; sample++) {
            double step = sample / 8.0;
            double atX = x + (toX - x) * step, atZ = z + (toZ - z) * step;
            if (!exclusionClear(atX, y, atZ, atX, y + JUMP_APEX, atZ)) return false;
        }
        Vec3 launch = gapTakeoff(from, to);
        return walkSweep(x, y, z, launch.x, launch.y, launch.z);
    }

    static Vec3 gapTakeoff(Node from, Node to) {
        return new Vec3(centerX(from) + Integer.signum(to.x() - from.x()) * GAP_TAKEOFF_OFFSET,
            feetY(from), centerZ(from) + Integer.signum(to.z() - from.z()) * GAP_TAKEOFF_OFFSET);
    }

    boolean canApproachGapTakeoff(Vec3 feet, Vec3 launch) {
        return feet.distanceToSqr(launch) <= 1.8 * 1.8
            && supportedAt(feet.x, feet.y, feet.z) && supportedAt(launch.x, launch.y, launch.z)
            && walkSweep(feet.x, feet.y, feet.z, launch.x, launch.y, launch.z);
    }

    boolean gapArcClearOfEntities(Node from, Node to) {
        return level.noEntityCollision(player, sweptBody(centerX(from), feetY(from), centerZ(from),
            centerX(to), feetY(to) + JUMP_APEX, centerZ(to)));
    }

    @Override
    public List<Node> climbNeighbors(Node from) {
        boolean fromRung = isClimbPoint(from);
        List<Node> neighbors = null;
        if (fromRung) {
            neighbors = new ArrayList<>(8);
            int lower = from.y16() - 16, upper = from.y16() + 16;
            if (lower >= Node.MIN_Y16) neighbors.addAll(floors(from.x(), from.z(), lower, lower));
            if (upper <= Node.MAX_Y16) neighbors.addAll(floors(from.x(), from.z(), upper, upper));
        }

        int lower = Math.max(Node.MIN_Y16, from.y16() - 16);
        int upper = Math.min(Node.MAX_Y16, from.y16() + 16);
        for (int[] offset : CARDINALS) {
            int x = from.x() + offset[0], z = from.z() + offset[1];
            if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
            if (fromRung) {
                neighbors.addAll(floors(x, z, lower, upper));
                continue;
            }
            for (int y = Math.floorDiv(lower + 15, 16); y <= Math.floorDiv(upper, 16); y++) {
                if (!climbAt(x, y, z)) continue;
                if (neighbors == null) neighbors = new ArrayList<>(4);
                neighbors.add(new Node(x, y * 16, z));
            }
        }
        return neighbors == null ? List.of() : neighbors;
    }

    boolean isClimbPoint(Node node) {
        return (node.y16() & 15) == 0 && climbAt(node.x(), node.y16() / 16, node.z());
    }

    private boolean climbAt(int x, int y, int z) {
        if (y < minY || y > maxY || !chunks.loaded(x >> 4, z >> 4)) return false;
        Collision contact = collision(x, y, z);
        BlockState state = contact.state();
        if (state == null || contact.unsafe()) return false;
        if (state.is(Blocks.LADDER) || state.is(BlockTags.CLIMBABLE)) return true;
        if (!(state.getBlock() instanceof TrapDoorBlock) || y == minY
            || !state.getValue(TrapDoorBlock.OPEN) && (!permissions.useDoors() || !manualDoor(state))) return false;
        BlockState below = collision(x, y - 1, z).state();
        return below != null && below.is(Blocks.LADDER)
            && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }

    private boolean canClimb(Node from, Node to) {
        int distance = Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z());
        int rise = to.y16() - from.y16();
        if (distance > 1 || Math.abs(rise) > 16 || distance == 0 && rise == 0) return false;
        boolean fromRung = isClimbPoint(from), toRung = isClimbPoint(to);
        if (!fromRung && !toRung || !fromRung && !canFinish(from) || !toRung && !canFinish(to)) return false;

        double x = centerX(from), y = feetY(from), z = centerZ(from);
        double toX = centerX(to), toY = feetY(to), toZ = centerZ(to);
        if (distance == 0) {
            if (rise > 0 && !fromRung || rise < 0 && !fromRung && !climbAt(from.x(), cell(y) - 1, from.z()))
                return false;
            if (rise < 0 && permissions.useDoors()) {
                for (DoorCollision door : closedDoors(sweptBody(x, y, z, toX, toY, toZ))) {
                    if (door.state().getBlock() instanceof TrapDoorBlock && supportsTrapdoor(from, door)) return false;
                }
            }
            return clearSegment(x, y, z, toX, toY, toZ);
        }
        if (rise > 0 && !fromRung) {
            // Reach the ladder at the current elevation before asking vanilla to climb.
            return climbAt(to.x(), cell(y), to.z())
                && clearSegment(x, y, z, toX, y, toZ)
                && clearSegment(toX, y, toZ, toX, toY, toZ);
        }
        return aerialSweep(x, y, z, toX, toY, toZ, Math.max(y, toY));
    }

    @Override
    public List<Node> bubbleNeighbors(Node from) {
        if ((from.y16() & 15) != 0) return List.of();
        BubbleFlow flow = bubblePoint(from);
        List<Node> neighbors = null;
        if (flow != null) {
            neighbors = new ArrayList<>(4);
            int nextY = from.y16() + flow.step;
            if (nextY >= Node.MIN_Y16 && nextY <= Node.MAX_Y16) {
                Node next = new Node(from.x(), nextY, from.z());
                if (bubblePoint(next) == flow) neighbors.add(next);
            }
            if (!bubbleEnd(from, flow, false)) return neighbors;
        }
        for (int[] offset : CARDINALS) {
            int x = from.x() + offset[0], z = from.z() + offset[1];
            if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
            Node next = new Node(x, from.y16(), z);
            BubbleFlow nextFlow = bubblePoint(next);
            if (flow == null ? nextFlow != null && bubbleEnd(next, nextFlow, true) : nextFlow == null && canFinish(next)) {
                if (neighbors == null) neighbors = new ArrayList<>(4);
                neighbors.add(next);
            }
        }
        return neighbors == null ? List.of() : neighbors;
    }

    private BubbleFlow bubblePoint(Node node) {
        if ((node.y16() & 15) != 0 || !chunks.loaded(node.x() >> 4, node.z() >> 4)) return null;
        int y = node.y16() / 16;
        if (y < minY || y > maxY + 1) return null;
        if (y <= maxY) {
            Collision cell = collision(node.x(), y, node.z());
            if (cell != AIR) return bubbleFlow(cell.state());
        }
        // Include the air just above the top water block so a bank level with the surface can connect.
        return bubbleAt(node.x(), y - 1, node.z());
    }

    private BubbleFlow bubbleAt(int x, int y, int z) {
        if (y < minY || y > maxY || !chunks.loaded(x >> 4, z >> 4)) return null;
        return bubbleFlow(collision(x, y, z).state());
    }

    private static BubbleFlow bubbleFlow(BlockState state) {
        return state != null && state.is(Blocks.BUBBLE_COLUMN)
            ? state.getValue(BubbleColumnBlock.DRAG_DOWN) ? BubbleFlow.DOWN : BubbleFlow.UP : null;
    }

    private boolean bubbleEnd(Node node, BubbleFlow flow, boolean entry) {
        int y = node.y16() / 16;
        BubbleFlow atFeet = bubbleAt(node.x(), y, node.z());
        BubbleFlow belowFeet = bubbleAt(node.x(), y - 1, node.z());
        boolean bottom = entry == (flow == BubbleFlow.UP);
        if (!bottom) return atFeet == null && belowFeet == flow;
        if (atFeet != flow || y <= minY) return false;
        BlockState base = collision(node.x(), y - 1, node.z()).state();
        return base != null && base.is(flow == BubbleFlow.UP ? Blocks.SOUL_SAND : Blocks.MAGMA_BLOCK);
    }

    private boolean canRideBubble(Node from, Node to) {
        BubbleFlow source = bubblePoint(from), target = bubblePoint(to);
        if (source == null && target == null) return false;
        int distance = Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z());
        BubbleFlow flow = source == null ? target : source;
        Node shaft = source == null ? to : from;
        if (distance == 0) {
            if (source == null || source != target || to.y16() - from.y16() != flow.step
                || bubbleAt(shaft.x(), Math.min(from.y16(), to.y16()) / 16, shaft.z()) != flow) return false;
        } else {
            if (distance != 1 || from.y16() != to.y16() || source != null && target != null) return false;
            boolean entry = source == null;
            Node bank = entry ? from : to;
            if (!bubbleEnd(shaft, flow, entry) || !canFinish(bank)) return false;
        }
        double x = from.centerX(), y = from.y(), z = from.centerZ();
        double toX = to.centerX(), toY = to.y(), toZ = to.centerZ();
        return exclusionClear(x, y, z, toX, toY, toZ)
            && clear(sweptBody(x, y, z, toX, toY, toZ), shaft, flow);
    }

    boolean isBubblePoint(Node node) { return bubblePoint(node) != null; }

    boolean sinkingBubble(Step step) {
        return bubblePoint(step.from()) == BubbleFlow.DOWN || bubblePoint(step.to()) == BubbleFlow.DOWN;
    }

    boolean isPlayerClearOnBubble(Step step) {
        Node shaft = bubblePoint(step.from()) != null ? step.from() : step.to();
        BubbleFlow flow = bubblePoint(shaft);
        double x = player.getX(), y = player.getY(), z = player.getZ();
        return flow != null && exclusionClear(x, y, z, x, y, z)
            && clear(sweptBody(x, y, z, x, y, z), shaft, flow);
    }

    /** Resolve the selected map block against the player's full standing footprint. */
    public Node standingNodeAt(BlockPos position) {
        int y16 = position.getY() * 16;
        Node result = null;
        for (Node node : floors(position.getX(), position.getZ(), y16 - 8, y16 + 16)) {
            if ((result == null || node.y16() > result.y16()) && canFinish(node)) result = node;
        }
        return result;
    }

    private boolean fullBlockFooting(Node from, Node to) {
        if (halfWidth > .5 || halfWidth * 2 < MIN_FOOT_OVERLAP) return false;
        int floorY = Math.floorDiv(from.y16(), 16) - 1;
        if (floorY < minY || floorY > maxY) return false;
        int minX = Math.min(from.x(), to.x()), maxX = Math.max(from.x(), to.x());
        int minZ = Math.min(from.z(), to.z()), maxZ = Math.max(from.z(), to.z());
        if (!areaAvailable(minX + .5 - halfWidth, minZ + .5 - halfWidth,
            maxX + .5 + halfWidth, maxZ + .5 + halfWidth)) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Collision collision = collision(x, floorY, z);
                if (collision.unsafe() || !collision.full()) return false;
            }
        }
        return true;
    }

    /** Check the real approach to a nearby center without rounding the player's feet. */
    public boolean canApproach(Node target) {
        return canApproachFrom(player.getX(), player.getY(), player.getZ(), target);
    }

    public boolean canApproachFrom(double x, double y, double z, Node target) {
        double toX = centerX(target), toY = feetY(target), toZ = centerZ(target);
        return Math.hypot(toX - x, toZ - z) <= 1.8
            && Double.isFinite(supportHeight(x, z, y - FLOOR_REACH, y + FLOOR_REACH, EPSILON))
            && supportedAt(toX, toY, toZ)
            && walkSweep(x, y, z, toX, toY, toZ);
    }

    /** A short bend can only use real, currently clear ground at the same feet height. */
    boolean canRoundWalk(Vec3 feet, Vec3 target) {
        if (Math.abs(feet.y - target.y) > EPSILON || feet.distanceToSqr(target) > 1.8 * 1.8
            || !supportedAt(feet.x, feet.y, feet.z) || !supportedAt(target.x, target.y, target.z)) return false;
        return turnClearOfDoorsAndEntities(feet, target)
            && WalkingSweep.canWalkLevel(walkingGeometry, feet.x, feet.y, feet.z, target.x, target.z);
    }

    /** Recheck a turn from actual feet, including ordinary changes of walking height. */
    boolean canWalkThroughTurn(Vec3 feet, Vec3 target) {
        return Math.hypot(target.x - feet.x, target.z - feet.z) <= 1.8
            && supportedAt(feet.x, feet.y, feet.z) && supportedAt(target.x, target.y, target.z)
            && turnClearOfDoorsAndEntities(feet, target) && walkSweep(feet.x, feet.y, feet.z, target.x, target.y, target.z);
    }

    boolean canSteerThroughTurn(Vec3 feet, Vec3 predicted) {
        double support = supportHeight(predicted.x, predicted.z, feet.y - STEP_HEIGHT, feet.y + STEP_HEIGHT, EPSILON);
        return Double.isFinite(support) && canWalkThroughTurn(feet, new Vec3(predicted.x, support, predicted.z));
    }

    private boolean turnClearOfDoorsAndEntities(Vec3 feet, Vec3 target) {
        AABB sweep = sweptBody(feet.x, feet.y, feet.z, target.x, target.y, target.z);
        return (!permissions.useDoors() || closedDoors(sweep).isEmpty()) && level.noEntityCollision(player, sweep);
    }

    /** Find the next support below actual feet during a vanilla step down a slab or tread. */
    boolean canFinishWalkingDescent(Vec3 feet, Node target) {
        double targetY = feetY(target);
        if (feet.y < targetY - FLOOR_REACH) return false;
        double support = supportHeight(feet.x, feet.z, Math.max(targetY - FLOOR_REACH, feet.y - STEP_HEIGHT),
            feet.y + EPSILON, MIN_FOOT_OVERLAP);
        if (!Double.isFinite(support)) return false;
        AABB fallingBody = sweptBody(feet.x, feet.y, feet.z, feet.x, support, feet.z);
        return level.noBlockCollision(player, fallingBody) && level.noEntityCollision(player, fallingBody)
            && clearSegment(feet.x, feet.y, feet.z, feet.x, support, feet.z)
            && canApproachFrom(feet.x, support, feet.z, target);
    }

    public boolean isPlayerClear() {
        double x = player.getX(), y = player.getY(), z = player.getZ();
        return clearSegment(x, y, z, x, y, z)
            && level.noBlockCollision(player, sweptBody(x, y, z, x, y, z));
    }

    public double groundDrag() {
        BlockPos floor = player.getBlockPosBelowThatAffectsMyMovement();
        double drag = level.getBlockState(floor).getBlock().getFriction() * .91;
        return Math.clamp(drag, .01, .99);
    }

    public boolean clearBodyAt(double x, double y, double z) {
        return clearSegment(x, y, z, x, y, z);
    }

    @Override
    public boolean hasLineOfSight(Node node, Goal goal) {
        if (goal.sightY() == null) return true;
        return hasLineOfSight(centerX(node), feetY(node), centerZ(node), goal);
    }

    boolean hasLineOfSight(double x, double y, double z, Goal goal) {
        if (goal.sightY() == null) return true;
        var target = new BlockPos(goal.x(), goal.sightY(), goal.z());
        var eye = new Vec3(x, y + player.getEyeHeight(Pose.STANDING), z);
        var center = Vec3.atCenterOf(target);
        if (eye.distanceToSqr(center) > goal.radius() * (double) goal.radius()
            || !areaAvailable(Math.min(eye.x, center.x), Math.min(eye.z, center.z),
                Math.max(eye.x, center.x), Math.max(eye.z, center.z))) return false;
        if (goal.observedBlockId() != null && !ObservedBlocks.hasId(level.getBlockState(target), goal.observedBlockId())) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    String obstructionReason(String fallback) { return exclusionReason == null ? fallback : exclusionReason + "."; }

    boolean isSafeCheckpoint(double x, double y, double z) {
        AABB body = sweptBody(x, y, z, x, y, z);
        if (!supportedAt(x, y, z) || !clearBodyAt(x, y, z) || !hazardClearance(x, y, z)
            || !level.noBlockCollision(player, body) || !level.noEntityCollision(player, body)) return false;
        int fromX = cell(x), fromZ = cell(z);
        int lower = (int) Math.ceil((y - STEP_HEIGHT) * 16);
        int upper = (int) Math.floor((y + STEP_HEIGHT) * 16);
        for (int[] offset : CARDINALS) {
            for (Node exit : floors(fromX + offset[0], fromZ + offset[1], lower, upper)) {
                if (canApproachFrom(x, y, z, exit) && hazardClearance(exit.centerX(), exit.y(), exit.centerZ())) return true;
            }
        }
        return false;
    }

    private boolean hazardClearance(double x, double y, double z) {
        double radius = 1.2;
        if (!areaAvailable(x - radius, z - radius, x + radius, z + radius)) return false;
        for (int blockX = cell(x - radius); blockX <= cell(x + radius); blockX++) {
            for (int blockZ = cell(z - radius); blockZ <= cell(z + radius); blockZ++) {
                for (int blockY = Math.max(minY, cell(y - 1)); blockY <= Math.min(maxY, cell(y + standingHeight)); blockY++) {
                    if (unsafeAt(blockX, blockY, blockZ)) return false;
                }
            }
        }
        return true;
    }

    /** Dynamic entities are checked before movement so a transient blocker is not part of the static graph. */
    public boolean isClearOfEntities(Node target) {
        AABB sweep = sweptBody(player.getX(), player.getY(), player.getZ(),
            centerX(target), feetY(target), centerZ(target));
        return areaAvailable(sweep.minX, sweep.minZ, sweep.maxX, sweep.maxZ)
            && level.noEntityCollision(player, sweep);
    }

    private boolean walkSweep(double x, double y, double z, double toX, double toY, double toZ) {
        return Math.abs(y - toY) <= MAX_WALK_RISE
            && WalkingSweep.canWalk(walkingGeometry, x, y, z, toX, toY, toZ);
    }

    private boolean aerialSweep(double x, double y, double z, double toX, double toY, double toZ, double travelY) {
        return clearSegment(x, y, z, x, travelY, z)
            && clearSegment(x, travelY, z, toX, travelY, toZ)
            && clearSegment(toX, travelY, toZ, toX, toY, toZ);
    }

    private boolean supportsEither(double x, double z, double firstY, double secondY) {
        return supportedAt(x, firstY, z) || Math.abs(firstY - secondY) > EPSILON && supportedAt(x, secondY, z);
    }

    private boolean supportedAt(double x, double y, double z) {
        return Double.isFinite(supportHeight(x, z, y - FLOOR_REACH, y + FLOOR_REACH, MIN_FOOT_OVERLAP));
    }

    private double supportHeight(double x, double z, double bottomY, double topY, double overlap) {
        double minX = x - halfWidth, maxX = x + halfWidth;
        double minZ = z - halfWidth, maxZ = z + halfWidth;
        if (!areaAvailable(minX, minZ, maxX, maxZ)) return Double.NaN;
        int bottom = Math.max(minY, cell(bottomY) - 1);
        int top = Math.min(maxY, cell(topY));
        double best = Double.NaN;
        for (int blockX = cell(minX); blockX <= cell(maxX - EPSILON); blockX++) {
            for (int blockZ = cell(minZ); blockZ <= cell(maxZ - EPSILON); blockZ++) {
                for (int blockY = bottom; blockY <= top; blockY++) {
                    Collision collision = collision(blockX, blockY, blockZ);
                    for (AABB shape : collision.boxes()) {
                        double surface = blockY + shape.maxY;
                        if (surface < bottomY || surface > topY
                            || !footOverlap(x, z, blockX, blockZ, shape, overlap)) continue;
                        if (collision.unsafe() || shape.maxY > 1 + EPSILON) return Double.NaN;
                        if (Double.isNaN(best) || surface > best) best = surface;
                    }
                }
            }
        }
        return best;
    }

    private boolean footOverlap(double x, double z, int blockX, int blockZ, AABB shape, double overlap) {
        double overlapX = Math.min(x + halfWidth, blockX + shape.maxX)
            - Math.max(x - halfWidth, blockX + shape.minX);
        double overlapZ = Math.min(z + halfWidth, blockZ + shape.maxZ)
            - Math.max(z - halfWidth, blockZ + shape.minZ);
        return overlapX >= overlap && overlapZ >= overlap;
    }

    private boolean clear(AABB body) { return clear(body, null, null); }

    private boolean clear(AABB body, Node shaft, BubbleFlow flow) {
        if (body.minY < minY || !areaAvailable(body.minX, body.minZ, body.maxX, body.maxZ)) return false;
        int bottom = Math.max(minY, cell(body.minY)), top = Math.min(maxY, cell(body.maxY));
        for (int x = cell(body.minX); x <= cell(body.maxX - EPSILON); x++) {
            for (int z = cell(body.minZ); z <= cell(body.maxZ - EPSILON); z++) {
                for (int y = bottom; y <= top; y++) {
                    if (unsafeAt(x, y, z) && !(shaft != null && x == shaft.x() && z == shaft.z()
                        && bubbleAt(x, y, z) == flow)) {
                        exclusionReason = "Hazard at " + x + ", " + y + ", " + z;
                        return false;
                    }
                }
            }
        }
        return level.noBlockCollision(player, body) || shaft == null && permissions.useDoors() && clearAfterOpeningDoors(body);
    }

    private boolean clearAfterOpeningDoors(AABB body) {
        List<DoorCollision> doors = closedDoors(body);
        if (doors.isEmpty()) return false;
        for (DoorCollision door : doors) {
            BlockState opened = door.state().setValue(BlockStateProperties.OPEN, true);
            for (AABB box : opened.getCollisionShape(level, door.position()).toAabbs()) {
                if (box.move(door.position()).intersects(body)) return false;
            }
        }
        for (var shape : level.getBlockCollisions(player, body)) {
            for (AABB box : shape.toAabbs()) {
                if (!box.intersects(body)) continue;
                if (doors.stream().noneMatch(door -> door.boxes().contains(box))) return false;
            }
        }
        return true;
    }

    private List<DoorCollision> closedDoors(AABB body) {
        List<DoorCollision> result = new ArrayList<>(2);
        for (int x = cell(body.minX); x <= cell(body.maxX - EPSILON); x++) {
            for (int z = cell(body.minZ); z <= cell(body.maxZ - EPSILON); z++) {
                for (int y = Math.max(minY, cell(body.minY) - 1); y <= Math.min(maxY, cell(body.maxY)); y++) {
                    if (!chunks.loaded(x >> 4, z >> 4)) continue;
                    var state = collision(x, y, z);
                    if (state.state() == null || !manualDoor(state.state()) || state.state().getValue(BlockStateProperties.OPEN)) continue;
                    var pos = new BlockPos(x, y, z);
                    List<AABB> boxes = state.boxes().stream().map(box -> box.move(pos)).toList();
                    if (boxes.stream().anyMatch(body::intersects)) result.add(new DoorCollision(pos, state.state(), boxes));
                }
            }
        }
        return result;
    }

    static boolean manualDoor(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock
            || state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
            || state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS);
    }

    static BlockPos doorBase(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    BlockPos closedDoorOnStep(dev.soprano.path.Step step) {
        if (!permissions.useDoors()) return null;
        Node from = step.from(), to = step.to();
        double toY = step.move() == Move.JUMP ? Math.max(feetY(to), feetY(from) + JUMP_APEX) : feetY(to);
        AABB sweep = sweptBody(centerX(from), feetY(from), centerZ(from), centerX(to), toY, centerZ(to));
        List<DoorCollision> doors = closedDoors(sweep);
        if (doors.isEmpty()) return null;
        for (DoorCollision door : doors) {
            if (door.state().getBlock() instanceof TrapDoorBlock
                && (supportsTrapdoor(from, door) || supportsTrapdoor(to, door))) continue;
            return doorBase(door.position(), door.state());
        }
        return null;
    }

    private boolean closedTrapdoorOverFooting(Node from, Node to) {
        AABB body = sweptBody(centerX(from), feetY(from), centerZ(from), centerX(to), feetY(to), centerZ(to));
        for (DoorCollision door : closedDoors(body)) {
            if (door.state().getBlock() instanceof TrapDoorBlock
                && !supportsTrapdoor(from, door) && !supportsTrapdoor(to, door)) return true;
        }
        return false;
    }

    private boolean supportsTrapdoor(Node node, DoorCollision door) {
        for (AABB shape : door.boxes()) {
            if (Math.abs(shape.maxY - feetY(node)) <= FLOOR_REACH
                && Math.min(centerX(node) + halfWidth, shape.maxX) - Math.max(centerX(node) - halfWidth, shape.minX) >= MIN_FOOT_OVERLAP
                && Math.min(centerZ(node) + halfWidth, shape.maxZ) - Math.max(centerZ(node) - halfWidth, shape.minZ) >= MIN_FOOT_OVERLAP) return true;
        }
        return false;
    }

    private boolean clearSegment(double x, double y, double z, double toX, double toY, double toZ) {
        if (!exclusionClear(x, y, z, toX, toY, toZ)) return false;
        return clear(sweptBody(x, y, z, toX, toY, toZ));
    }

    boolean exclusionClear(double x, double y, double z, double toX, double toY, double toZ) {
        if (!routePolicy.containsBody(x, y, z, halfWidth, standingHeight)
            || !routePolicy.containsBody(toX, toY, toZ, halfWidth, standingHeight)) {
            exclusionReason = "Movement leaves the selected route volume";
            return false;
        }
        RouteExclusions active = cacheActive ? exclusions : exclusionSource.get();
        if (active != null && !active.isEmpty()) {
            // Map selections commonly mark the blocks people stand on. Include that floor contact.
            var blocked = active.blockingSegment(new RouteExclusions.Point(x, y - FOOTING_EXCLUSION_DEPTH, z),
                new RouteExclusions.Point(toX, toY - FOOTING_EXCLUSION_DEPTH, toZ), footprint);
            if (blocked.isPresent()) {
                exclusionReason = blocked.get().reason();
                return false;
            }
        }
        return true;
    }

    private boolean areaAvailable(double minX, double minZ, double maxX, double maxZ) {
        var border = level.getWorldBorder();
        if (!border.isWithinBounds(minX, minZ) || !border.isWithinBounds(maxX, maxZ)) return false;
        for (int chunkX = cell(minX) >> 4; chunkX <= cell(maxX) >> 4; chunkX++) {
            for (int chunkZ = cell(minZ) >> 4; chunkZ <= cell(maxZ) >> 4; chunkZ++) {
                if (!chunks.loaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private AABB sweptBody(double x, double y, double z, double toX, double toY, double toZ) {
        return new AABB(Math.min(x, toX) - halfWidth, Math.min(y, toY) + EPSILON,
            Math.min(z, toZ) - halfWidth, Math.max(x, toX) + halfWidth,
            Math.max(y, toY) + standingHeight, Math.max(z, toZ) + halfWidth);
    }

    private boolean unsafeAt(int x, int y, int z) {
        Collision sampled = cacheActive ? collisionCache.get(BlockPos.asLong(x, y, z)) : null;
        if (sampled != null) return sampled.unsafe();
        BlockPos position = queryPosition.set(x, y, z);
        return unsafe(level.getBlockState(position), position);
    }

    private boolean unsafe(BlockState state, BlockPos position) {
        if ((state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) && permittedPortalCells.contains(position)) return false;
        return inherentHazard(state);
    }

    public static boolean inherentHazard(BlockState state) { return !state.getFluidState().isEmpty() || HAZARDS.contains(state.getBlock()); }

    private Collision collision(int x, int y, int z) {
        if (!cacheActive) return sampleCollision(x, y, z);
        long key = BlockPos.asLong(x, y, z);
        Collision present = collisionCache.get(key);
        if (present != null) return present;
        Collision sampled = sampleCollision(x, y, z);
        collisionCache.put(key, sampled);
        return sampled;
    }

    private Collision sampleCollision(int x, int y, int z) {
        BlockPos position = queryPosition.set(x, y, z);
        BlockState state = level.getBlockState(position);
        if (state.isAir()) return AIR;
        boolean unsafe = unsafe(state, position);
        boolean full = state.isCollisionShapeFullBlock(level, position);
        List<AABB> boxes = full ? UNIT_BOXES : state.getCollisionShape(level, position).toAabbs();
        return new Collision(state, unsafe, full, boxes);
    }

    private static int cell(double coordinate) { return (int) Math.floor(coordinate); }
    private static double centerX(Node node) { return node.x() + .5; }
    private static double centerZ(Node node) { return node.z() + .5; }
    private static double feetY(Node node) { return node.y16() / 16.0; }
}
