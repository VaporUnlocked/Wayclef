package dev.soprano.game;

import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Searches loaded terrain for a reversible walking approach to a block in solid terrain. */
public final class MiningApproach {
    public enum Status { SEARCHING, FOUND, FAILED }

    public record Edge(Node destination, List<BlockPos> breaks) {
        public Edge { breaks = breaks.stream().map(BlockPos::immutable).toList(); }
    }

    private record Candidate(Node node, Candidate parent, Edge edge, int depth, int breaks, int cost, int estimate) { }

    private static final int MAX_NODES = 8_192;
    private static final int MAX_DEPTH = 128;
    private static final int STEPS_PER_TICK = 32;
    private static final long SEARCH_NANOS = 2_000_000L;
    private static final int WALK_COST = 4;
    private static final int BREAK_COST = 12;
    private static final int[] HEIGHT_CHANGES = {0, -1, 1};
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final RouteExclusions.Footprint BLOCK = new RouteExclusions.Footprint(1, 1);

    private final ClientLevel level;
    private final LocalPlayer player;
    private final BlockPos target;
    private final RouteBlockProtection protection;
    private final RouteExclusions exclusions;
    private final int remainingBreaks;
    private final Node start;
    private final Map<Node, Candidate> best = new HashMap<>();
    private final PriorityQueue<Candidate> pending = new PriorityQueue<>(Comparator
        .comparingInt((Candidate candidate) -> candidate.cost() + candidate.estimate())
        .thenComparingInt(Candidate::estimate));
    private List<Edge> path = List.of();
    private Status status = Status.SEARCHING;

    public MiningApproach(ClientLevel level, LocalPlayer player, BlockPos target,
                          RouteBlockProtection protection, int remainingBreaks) {
        this.level = Objects.requireNonNull(level);
        this.player = Objects.requireNonNull(player);
        this.target = target.immutable();
        this.protection = Objects.requireNonNull(protection);
        this.exclusions = Navigation.activeExclusions(false);
        this.remainingBreaks = Math.max(0, remainingBreaks);
        start = new MinecraftTerrain(level, player).startAtPlayer();
        if (start == null || !player.onGround() || start.y16() % 16 != 0 || !stableFloor(level, floor(start))
            || floor(start).equals(target) || !loaded(level, target) || !level.mayInteract(player, target)
            || !safeNeighbors(level, target)) {
            status = Status.FAILED;
            return;
        }
        var first = new Candidate(start, null, null, 0, 0, 0, estimate(start));
        pending.add(first);
        best.put(start, first);
    }

    public Node start() { return start; }
    public List<Edge> path() { return path; }

    public Status advance() {
        if (status != Status.SEARCHING) return status;
        long deadline = System.nanoTime() + SEARCH_NANOS;
        for (int step = 0; step < STEPS_PER_TICK; step++) {
            if (step != 0 && System.nanoTime() >= deadline) break;
            Candidate current = pending.poll();
            if (current == null) {
                status = Status.FAILED;
                break;
            }
            if (best.get(current.node()) != current) continue;
            if (targetInReach(current)) {
                var edges = new ArrayList<Edge>();
                for (Candidate cursor = current; cursor.edge() != null; cursor = cursor.parent()) edges.add(cursor.edge());
                Collections.reverse(edges);
                path = List.copyOf(edges);
                status = Status.FOUND;
                pending.clear();
                best.clear();
                break;
            }
            if (current.depth() < MAX_DEPTH && best.size() < MAX_NODES) expand(current);
        }
        return status;
    }

    private void expand(Candidate current) {
        Node from = current.node();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int x = from.x() + direction.getStepX(), z = from.z() + direction.getStepZ();
            if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
            for (int height : HEIGHT_CHANGES) {
                int y16 = from.y16() + height * 16;
                if (y16 < Node.MIN_Y16 || y16 > Node.MAX_Y16) continue;
                Node to = new Node(x, y16, z);
                if (Math.abs(x - start.x()) > MAX_DEPTH || Math.abs(z - start.z()) > MAX_DEPTH) continue;
                Candidate previous = best.get(to);
                if (previous != null && previous.cost() <= current.cost() + WALK_COST) continue;
                Edge edge = edge(current, to);
                if (edge == null) continue;
                int cost = current.cost() + WALK_COST + edge.breaks().size() * BREAK_COST + Math.abs(height);
                if (previous != null && previous.cost() <= cost) continue;
                var next = new Candidate(to, current, edge, current.depth() + 1,
                    current.breaks() + edge.breaks().size(), cost, estimate(to));
                if (best.size() >= MAX_NODES && previous == null) return;
                best.put(to, next);
                pending.add(next);
            }
        }
    }

    private Edge edge(Candidate current, Node to) {
        Node from = current.node();
        BlockPos support = floor(to);
        if (support.equals(target) || wasCleared(current, support) || !stableFloor(level, support)) return null;
        var breaks = new ArrayList<BlockPos>();
        int fromY = from.y16() / 16, toY = to.y16() / 16;
        // A one-block stair must leave room for the reverse jump, including its apex.
        int top = fromY == toY ? toY + 1 : Math.min(fromY, toY) + 3;
        for (int y = top; y >= Math.min(fromY, toY); y--) {
            if (y >= toY && !planCell(current, new BlockPos(to.x(), y, to.z()), breaks)) return null;
            if (y >= fromY + 2 && !planCell(current, new BlockPos(from.x(), y, from.z()), breaks)) return null;
        }
        if (breaks.size() > remainingBreaks - current.breaks()) return null;
        var changes = plannedChanges(current);
        var ordered = new ArrayList<BlockPos>(breaks.size());
        Vec3 eye = current.parent() == null ? player.getEyePosition() : eyeAt(player, from);
        while (!breaks.isEmpty()) {
            var view = new ConstructionTerrain(level, changes);
            BlockPos next = null;
            for (BlockPos block : breaks) {
                if (visible(view, player, eye, block, Vec3.atCenterOf(block))) { next = block; break; }
            }
            if (next == null) return null;
            changes.put(next, AIR);
            if (!surroundingsStable(level, changes, next)) return null;
            ordered.add(next);
            breaks.remove(next);
        }
        return traversable(level, player, from, to, changes, exclusions) ? new Edge(to, ordered) : null;
    }

    private boolean planCell(Candidate current, BlockPos at, List<BlockPos> breaks) {
        if (!loaded(level, at)) return false;
        if (wasCleared(current, at)) return true;
        BlockState state = level.getBlockState(at);
        if (MinecraftTerrain.inherentHazard(state)) return false;
        if (state.getCollisionShape(level, at).isEmpty()) return true;
        if (at.equals(target) || !mayCut(level, player, at, state, protection, exclusions)) return false;
        for (Candidate cursor = current; cursor != null; cursor = cursor.parent()) {
            if (floor(cursor.node()).equals(at)) return false;
        }
        breaks.add(at);
        return true;
    }

    private int estimate(Node node) {
        int horizontal = Math.abs(node.x() - target.getX()) + Math.abs(node.z() - target.getZ());
        int vertical = Math.abs(node.y16() / 16 + 1 - target.getY());
        // Prefer quickly closing the distance when most paths require cutting through solid terrain.
        return Math.max(horizontal, vertical) * BREAK_COST;
    }

    private boolean targetInReach(Candidate current) {
        Node node = current.node();
        boolean first = current.parent() == null;
        Vec3 eye = first ? player.getEyePosition() : eyeAt(player, node);
        Vec3 center = Vec3.atCenterOf(target);
        double probeRange = player.blockInteractionRange() + .9;
        if (eye.distanceToSqr(center) > probeRange * probeRange) return false;
        var dimensions = player.getDimensions(Pose.STANDING);
        var body = first ? player.getBoundingBox() : dimensions.makeBoundingBox(new Vec3(node.centerX(), node.y(), node.centerZ()));
        if (body.inflate(.07).intersects(new AABB(target))) return false;
        var view = new ConstructionTerrain(level, plannedChanges(current));
        var shape = view.getBlockState(target).getShape(view, target, CollisionContext.of(player));
        return !shape.isEmpty() && visible(view, player, eye, target, shape.bounds().move(target).getCenter());
    }

    private static Map<BlockPos, BlockState> plannedChanges(Candidate candidate) {
        var changes = new HashMap<BlockPos, BlockState>();
        for (Candidate cursor = candidate; cursor.edge() != null; cursor = cursor.parent()) {
            for (BlockPos at : cursor.edge().breaks()) changes.put(at, AIR);
        }
        return changes;
    }

    private static boolean wasCleared(Candidate candidate, BlockPos at) {
        for (Candidate cursor = candidate; cursor.edge() != null; cursor = cursor.parent()) {
            if (cursor.edge().breaks().contains(at)) return true;
        }
        return false;
    }

    /** Recheck the remaining cuts and both directions of the edge against the current world. */
    public static boolean validEdge(ClientLevel level, LocalPlayer player, Node from, Edge edge,
                                    RouteBlockProtection protection) {
        Node to = edge.destination();
        if (from.y16() % 16 != 0 || to.y16() % 16 != 0 || Math.abs(from.y16() - to.y16()) > 16
            || Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()) != 1
            || !stableFloor(level, floor(from)) || !stableFloor(level, floor(to))) return false;
        RouteExclusions zones = Navigation.activeExclusions(false);
        var changes = new HashMap<BlockPos, BlockState>();
        for (BlockPos at : edge.breaks()) {
            if (!loaded(level, at) || at.equals(floor(from)) || at.equals(floor(to))) return false;
            BlockState state = level.getBlockState(at);
            if (state.isAir()) continue;
            if (!mayCut(level, player, at, state, protection, zones)) return false;
            changes.put(at, AIR);
            if (!surroundingsStable(level, changes, at)) return false;
        }
        return traversable(level, player, from, to, changes, zones);
    }

    private static boolean traversable(ClientLevel level, LocalPlayer player, Node from, Node to,
                                       Map<BlockPos, BlockState> changes, RouteExclusions zones) {
        var view = new ConstructionTerrain(level, changes);
        var standing = player.getDimensions(Pose.STANDING);
        var terrain = new MinecraftTerrain(view, player, level::hasChunk, standing.width() / 2, standing.height(), () -> zones);
        return terrain.canTraverse(from, to, move(from, to)) && terrain.canTraverse(to, from, move(to, from));
    }

    private static Move move(Node from, Node to) {
        return to.y16() == from.y16() ? Move.WALK : to.y16() > from.y16() ? Move.JUMP : Move.DROP;
    }

    private static boolean mayCut(ClientLevel level, LocalPlayer player, BlockPos at, BlockState state,
                                  RouteBlockProtection protection, RouteExclusions zones) {
        return !state.hasBlockEntity() && !(state.getBlock() instanceof FallingBlock)
            && !MinecraftTerrain.inherentHazard(state) && state.getDestroySpeed(level, at) >= 0
            && state.isCollisionShapeFullBlock(level, at) && !protection.contains(state) && level.mayInteract(player, at)
            && zones.blockingAt(new RouteExclusions.Point(at.getX() + .5, at.getY(), at.getZ() + .5), BLOCK).isEmpty()
            && safeNeighbors(level, at);
    }

    private static boolean safeNeighbors(ClientLevel level, BlockPos at) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = at.relative(direction);
            if (!loaded(level, neighbor)) return false;
            BlockState state = level.getBlockState(neighbor);
            if (!state.getFluidState().isEmpty() || direction == Direction.UP && state.getBlock() instanceof FallingBlock) return false;
        }
        return true;
    }

    private static boolean surroundingsStable(ClientLevel level, Map<BlockPos, BlockState> changes, BlockPos at) {
        var view = new ConstructionTerrain(level, changes);
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = at.relative(direction);
            if (!view.getBlockState(neighbor).canSurvive(view, neighbor)) return false;
        }
        return true;
    }

    private static boolean stableFloor(ClientLevel level, BlockPos at) {
        if (!loaded(level, at)) return false;
        BlockState state = level.getBlockState(at);
        return !(state.getBlock() instanceof FallingBlock) && !MinecraftTerrain.inherentHazard(state)
            && state.isCollisionShapeFullBlock(level, at);
    }

    private static boolean loaded(ClientLevel level, BlockPos at) {
        return !level.isOutsideBuildHeight(at) && level.hasChunk(at.getX() >> 4, at.getZ() >> 4)
            && level.getWorldBorder().isWithinBounds(at);
    }

    private static BlockPos floor(Node node) { return new BlockPos(node.x(), node.y16() / 16 - 1, node.z()); }

    private static Vec3 eyeAt(LocalPlayer player, Node node) {
        return new Vec3(node.centerX(), node.y() + player.getEyeHeight(Pose.STANDING), node.centerZ());
    }

    private static boolean visible(ConstructionTerrain view, LocalPlayer player, Vec3 eye, BlockPos target, Vec3 point) {
        if (eye.distanceTo(point) >= player.blockInteractionRange() - .08) return false;
        BlockHitResult hit = view.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }
}
