package dev.soprano.path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Incremental A* over confirmed client terrain. Call on the client thread when the terrain is live. */
public final class IncrementalSearch {
    private static final int WALK_COST = 100;
    private static final int DIAGONAL_COST = 142;
    private static final int MAX_STEP16 = 9;
    private static final int MAX_JUMP16 = 16;
    private static final int FLOOR_CACHE_SIZE = 1_024;
    private static final int FLOOR_CACHE_WAYS = 2;
    private static final int[][] DIRECTIONS = {
        {1, 0}, {0, 1}, {-1, 0}, {0, -1},
        {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
    };

    public enum SearchMode { OPTIMAL, QUICK }

    public record Config(boolean diagonals, boolean jumping, int maxDrop, int horizontalRange,
                         int maxVisited, boolean allowPartial, SearchMode searchMode, boolean gapJumps) {
        public Config(boolean diagonals, boolean jumping, int maxDrop, int horizontalRange,
                      int maxVisited, boolean allowPartial, SearchMode searchMode) {
            this(diagonals, jumping, maxDrop, horizontalRange, maxVisited, allowPartial, searchMode, false);
        }

        public Config(boolean diagonals, boolean jumping, int maxDrop, int horizontalRange,
                      int maxVisited, boolean allowPartial) {
            this(diagonals, jumping, maxDrop, horizontalRange, maxVisited, allowPartial, SearchMode.OPTIMAL);
        }

        public Config {
            Objects.requireNonNull(searchMode, "searchMode");
            if (maxDrop < 0 || maxDrop > 3) {
                throw new IllegalArgumentException("Maximum drop must be between 0 and 3 blocks.");
            }
            if (horizontalRange < 1 || horizontalRange > 4_096) {
                throw new IllegalArgumentException("Search range must be between 1 and 4,096 blocks.");
            }
            if (maxVisited < 1 || maxVisited > 500_000) {
                throw new IllegalArgumentException("Search node budget must be between 1 and 500,000.");
            }
        }

        public Config withGapJumps(boolean enabled) {
            return new Config(diagonals, jumping, maxDrop, horizontalRange, maxVisited, allowPartial, searchMode, enabled);
        }
    }

    public enum Status { SEARCHING, FOUND, PARTIAL, FAILED }

    public record Result(Status status, List<Step> path, int visited, String reason) {
        public Result {
            path = List.copyOf(path);
        }
    }

    private static final byte UNCHECKED = 0, CLEAR = 1, BLOCKED = -1;

    private static final class Candidate {
        final Node node;
        final Candidate parent;
        final Move move;
        final long cost, heuristic, priority;
        boolean expanded, superseded;

        Candidate(Node node, Candidate parent, Move move, long cost, long heuristic, int heuristicWeight) {
            this.node = node;
            this.parent = parent;
            this.move = move;
            this.cost = cost;
            this.heuristic = heuristic;
            priority = cost * 4 + heuristic * heuristicWeight;
        }
    }

    private static final Comparator<Candidate> PRIORITY = (first, second) -> {
        int comparison = Long.compare(first.priority, second.priority);
        if (comparison == 0) comparison = Long.compare(first.heuristic, second.heuristic);
        if (comparison == 0) comparison = Integer.compare(first.node.x(), second.node.x());
        if (comparison == 0) comparison = Integer.compare(first.node.z(), second.node.z());
        if (comparison == 0) comparison = Integer.compare(first.node.y16(), second.node.y16());
        return comparison;
    };

    private final Terrain terrain;
    private final Node start;
    private final Goal goal;
    private final Config config;
    private final PriorityQueue<Candidate> open = new PriorityQueue<>(PRIORITY);
    private final Map<Node, Candidate> best = new HashMap<>();
    // A slice runs on the client thread, so loaded blocks cannot change mid-query.
    // Discard samples between slices so chunk arrivals and block updates stay visible.
    private final long[] floorColumns = new long[FLOOR_CACHE_SIZE];
    private final int[] floorEpochs = new int[FLOOR_CACHE_SIZE];
    private final int[] minFloorY = new int[FLOOR_CACHE_SIZE], maxFloorY = new int[FLOOR_CACHE_SIZE];
    @SuppressWarnings("unchecked")
    private final List<Node>[] floorLists = (List<Node>[]) new List<?>[FLOOR_CACHE_SIZE];
    private final byte[] cardinalClearance = new byte[4];
    private int floorEpoch = 1;
    private final int heuristicWeight;
    private final boolean climbing, extendedSteps;
    private final int walkRise16, walkFall16, maxRise16, maxFall16;
    private int visited;
    private final long startRemaining;
    private Candidate partial;
    private long partialRemaining;
    private Result terminal;

    public IncrementalSearch(Terrain terrain, Node start, Goal goal, Config config) {
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.start = Objects.requireNonNull(start, "start");
        this.goal = Objects.requireNonNull(goal, "goal");
        this.config = Objects.requireNonNull(config, "config");
        heuristicWeight = config.searchMode == SearchMode.QUICK ? 5 : 4;
        climbing = terrain.supportsClimbing();
        extendedSteps = terrain.supportsExtendedSteps();
        walkRise16 = config.jumping || extendedSteps ? MAX_JUMP16 : MAX_STEP16;
        walkFall16 = Math.max(extendedSteps ? MAX_JUMP16 : MAX_STEP16, config.maxDrop * 16);
        maxRise16 = climbing ? MAX_JUMP16 : walkRise16;
        maxFall16 = climbing ? Math.max(MAX_JUMP16, walkFall16) : walkFall16;
        startRemaining = remaining(start);
        partialRemaining = startRemaining;
        terrain.beginSearchSlice();
        try {
            if (!goal.allows(start)) {
                finish(Status.FAILED, null, "The route starts outside its selected volume.");
            } else if (!hasFloor(start)) {
                finish(Status.FAILED, null, "The start has no confirmed footing.");
            } else if (atGoal(start)) {
                finish(Status.FOUND, null, "The start is already at the goal.");
            } else {
                Candidate initial = candidate(start, null, null, 0);
                open.add(initial);
                best.put(start, initial);
            }
        } finally {
            terrain.endSearchSlice();
        }
    }

    public Result result() {
        return terminal == null ? new Result(Status.SEARCHING, List.of(), visited, "Checking nearby terrain.") : terminal;
    }

    /** Minimum admissible g+h remaining in Optimal mode, or zero when no active bound is available. */
    public long optimalFrontierLowerBound() {
        if (terminal != null || config.searchMode != SearchMode.OPTIMAL) return 0;
        Candidate next = open.peek();
        while (next != null && next.superseded) {
            open.poll();
            next = open.peek();
        }
        return next == null ? 0 : next.cost + next.heuristic;
    }

    public Result advance(int maxExpansions) {
        return advance(maxExpansions, Long.MAX_VALUE);
    }

    /** Expand at most this many nodes and check the elapsed time before each subsequent expansion. */
    public Result advance(int maxExpansions, long maxNanos) {
        if (maxExpansions < 1 || maxNanos < 1) {
            throw new IllegalArgumentException("Search slice budgets must be positive.");
        }
        if (terminal != null) return terminal;

        floorEpoch++;
        terrain.beginSearchSlice();
        try {
            boolean timed = maxNanos != Long.MAX_VALUE;
            long begin = timed ? System.nanoTime() : 0;
            boolean attempted = false;
            for (int expanded = 0; expanded < maxExpansions; ) {
                if (timed && attempted && System.nanoTime() - begin >= maxNanos) break;
                attempted = true;
                Candidate current = open.poll();
                if (current == null) {
                    finishLimited("No complete route through confirmed terrain was found.");
                    break;
                }
                if (current.superseded) continue;
                current.expanded = true;
                expanded++;
                visited++;
                if (atGoal(current.node)) {
                    finish(Status.FOUND, current, "Found a route to the goal.");
                    break;
                }
                rememberPartial(current);
                if (visited >= config.maxVisited) {
                    finishLimited("The search reached its node budget.");
                    break;
                }
                expand(current);
            }
            return result();
        } finally {
            terrain.endSearchSlice();
        }
    }

    private void expand(Candidate current) {
        Node from = current.node;
        int limit = config.diagonals ? DIRECTIONS.length : 4;
        if (config.diagonals) Arrays.fill(cardinalClearance, UNCHECKED);
        for (int direction = 0; direction < limit; direction++) {
            int dx = DIRECTIONS[direction][0], dz = DIRECTIONS[direction][1];
            int x = from.x() + dx, z = from.z() + dz;
            if (!withinRange(x, z)) continue;
            boolean diagonal = direction >= 4;
            int minY16 = diagonal ? from.y16() : Math.max(Node.MIN_Y16, from.y16() - walkFall16);
            int maxY16 = diagonal ? from.y16() : Math.min(Node.MAX_Y16, from.y16() + walkRise16);
            if (diagonal && (cardinalClearance[dx > 0 ? 0 : 2] == BLOCKED
                || cardinalClearance[dz > 0 ? 1 : 3] == BLOCKED)) continue;
            boolean hasLevelFloor = false;
            List<Node> floors = floorCandidates(x, z, minY16, maxY16);
            for (int index = 0; index < floors.size(); index++) {
                Node next = floors.get(index);
                if (next.x() != x || next.z() != z || next.y16() < minY16 || next.y16() > maxY16) continue;
                boolean sameLevel = !diagonal && next.y16() == from.y16();
                if (sameLevel) hasLevelFloor = true;
                byte clearance = connect(current, next, diagonal);
                if (sameLevel && config.diagonals && clearance != UNCHECKED) {
                    cardinalClearance[direction] = clearance;
                }
            }
            if (!diagonal && config.diagonals && !hasLevelFloor) cardinalClearance[direction] = BLOCKED;
        }
        if (climbing) expandClimb(current);
        expandBubble(current);
        if (config.gapJumps() && terrain.supportsGapJumps()) expandGapJumps(current);
    }

    private void expandGapJumps(Candidate current) {
        Node from = current.node;
        for (int direction = 0; direction < 4; direction++) {
            int x = from.x() + DIRECTIONS[direction][0] * 2;
            int z = from.z() + DIRECTIONS[direction][1] * 2;
            if (!withinRange(x, z)) continue;
            Node to = new Node(x, from.y16(), z);
            if (hasFloor(to)) offer(current, to, Move.JUMP, current.cost + Step.GAP_JUMP_COST, best.get(to));
        }
    }

    private void expandClimb(Candidate current) {
        Node from = current.node;
        List<Node> neighbors = terrain.climbNeighbors(from);
        for (int i = 0; i < neighbors.size(); i++) {
            Node to = neighbors.get(i);
            int horizontal = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
            int vertical = Math.abs(to.y16() - from.y16());
            if (horizontal <= 1 && vertical <= MAX_JUMP16 && (horizontal != 0 || vertical != 0)
                && withinRange(to.x(), to.z())) {
                offer(current, to, Move.CLIMB, current.cost + 150 + vertical * 4, best.get(to));
            }
        }
    }

    private void expandBubble(Candidate current) {
        Node from = current.node;
        for (Node to : terrain.bubbleNeighbors(from)) {
            int horizontal = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
            int vertical = Math.abs(to.y16() - from.y16());
            if (!withinRange(to.x(), to.z()) || !(horizontal == 1 && vertical == 0 || horizontal == 0 && vertical == 16))
                continue;
            int cost = new Step(from, to, Move.BUBBLE).cost();
            offer(current, to, Move.BUBBLE, current.cost + cost, best.get(to));
        }
    }

    private byte connect(Candidate current, Node next, boolean diagonal) {
        Candidate old = best.get(next);
        if (old != null && old.expanded && config.searchMode == SearchMode.QUICK) return UNCHECKED;
        int rise = next.y16() - current.node.y16();
        int elevation = Math.abs(rise);
        long baseCost = current.cost + (diagonal ? DIAGONAL_COST : WALK_COST);
        if (diagonal) {
            if (old != null && old.cost <= baseCost) return UNCHECKED;
            Node from = current.node;
            if (!clearCardinal(from, next.x() > from.x() ? 0 : 2)
                || !clearCardinal(from, next.z() > from.z() ? 1 : 3)) return BLOCKED;
        }
        if (elevation <= MAX_STEP16) return offer(current, next, Move.WALK, baseCost + elevation * 2, old);
        if (extendedSteps && elevation <= MAX_JUMP16) {
            byte walk = offer(current, next, Move.WALK, baseCost + elevation * 2, old);
            if (walk != BLOCKED) return walk;
        }
        if (rise > 0 && config.jumping && rise <= MAX_JUMP16)
            return offer(current, next, Move.JUMP, baseCost + 45 + rise * 2, old);
        if (rise < 0 && elevation <= config.maxDrop * 16)
            return offer(current, next, Move.DROP, baseCost + 35 + elevation * 3, old);
        return BLOCKED;
    }

    private byte offer(Candidate current, Node next, Move move, long cost, Candidate old) {
        if (!goal.allows(next)) return BLOCKED;
        if (old != null && old.cost <= cost) return UNCHECKED;
        cost += Math.max(0, terrain.transitionPenalty(current.node, next, move));
        if (old != null && old.cost <= cost) return UNCHECKED;
        if (!terrain.canTraverse(current.node, next, move)) return BLOCKED;
        long estimate = old == null ? heuristic(next) : old.heuristic;
        Candidate candidate = new Candidate(next, current, move, cost, estimate, heuristicWeight);
        if (old != null) old.superseded = true;
        best.put(next, candidate);
        open.add(candidate);
        return CLEAR;
    }

    private Candidate candidate(Node node, Candidate parent, Move move, long cost) {
        return new Candidate(node, parent, move, cost, heuristic(node), heuristicWeight);
    }

    private boolean clearCardinal(Node from, int direction) {
        if (cardinalClearance[direction] == UNCHECKED) {
            Node side = new Node(from.x() + DIRECTIONS[direction][0], from.y16(), from.z() + DIRECTIONS[direction][1]);
            cardinalClearance[direction] = hasFloor(side) && terrain.canTraverse(from, side, Move.WALK) ? CLEAR : BLOCKED;
        }
        return cardinalClearance[direction] == CLEAR;
    }

    private boolean hasFloor(Node node) {
        return floorCandidates(node.x(), node.z(), node.y16(), node.y16()).contains(node);
    }

    private List<Node> floorCandidates(int x, int z, int minY16, int maxY16) {
        long column = ((long) x << 32) | (z & 0xffffffffL);
        int mixed = (x * 0x9e3779b1) ^ (z * 0x85ebca6b);
        int bucket = ((mixed ^ (mixed >>> 16)) & (FLOOR_CACHE_SIZE / FLOOR_CACHE_WAYS - 1)) * FLOOR_CACHE_WAYS;
        int replacement = bucket;
        for (int slot = bucket; slot < bucket + FLOOR_CACHE_WAYS; slot++) {
            if (floorEpochs[slot] != floorEpoch) {
                replacement = slot;
                break;
            }
            if (floorColumns[slot] == column) {
                if (minFloorY[slot] <= minY16 && maxFloorY[slot] >= maxY16) return floorLists[slot];
                replacement = slot;
                break;
            }
        }
        List<Node> result = terrain.floors(x, z, minY16, maxY16);
        floorLists[replacement] = result;
        floorColumns[replacement] = column;
        floorEpochs[replacement] = floorEpoch;
        minFloorY[replacement] = minY16;
        maxFloorY[replacement] = maxY16;
        return result;
    }

    private boolean atGoal(Node node) {
        return goal.matches(node) && terrain.canFinish(node)
            && (!goal.skyExposed() || terrain.isSkyExposed(node))
            && (goal.sightY() == null || terrain.hasLineOfSight(node, goal));
    }

    private boolean withinRange(int x, int z) {
        if (!Node.validColumn(x) || !Node.validColumn(z)) return false;
        if (goal.heightBand() != null || goal.skyExposed()) {
            return Math.abs((long) x - goal.x()) <= goal.radius() && Math.abs((long) z - goal.z()) <= goal.radius();
        }
        return Math.abs((long) x - start.x()) <= config.horizontalRange
            && Math.abs((long) z - start.z()) <= config.horizontalRange;
    }

    private long horizontalEstimate(Node node) {
        long x = goal.horizontalX(node), z = goal.horizontalZ(node);
        if (!config.diagonals) return (x + z) * WALK_COST;
        long diagonal = Math.min(x, z);
        return diagonal * DIAGONAL_COST + (Math.max(x, z) - diagonal) * WALK_COST;
    }

    private long heuristic(Node node) {
        // Any qualifying node can be the surface destination. Expand in route-cost order.
        if (goal.skyExposed()) return 0;
        long horizontal = horizontalEstimate(node), vertical = goal.vertical(node);
        if (vertical == 0) return horizontal;
        int heightPerMove = goal.above(node) ? maxRise16 : maxFall16;
        long verticalMoves = (vertical + heightPerMove - 1) / heightPerMove;
        if (config.diagonals && !climbing) {
            // A diagonal can pay for two horizontal axes, but only a cardinal move can change elevation.
            int diagonalPerAxis = DIAGONAL_COST / 2;
            long axes = goal.horizontalX(node) + goal.horizontalZ(node);
            horizontal = Math.max(horizontal, axes * diagonalPerAxis + verticalMoves * (WALK_COST - diagonalPerAxis));
        }
        return Math.max(horizontal, verticalMoves * WALK_COST) + vertical * 2;
    }

    private long remaining(Node node) {
        // Partial jobs must make measurable progress using the same score after each restart.
        if (goal.sightY() != null) {
            return (Math.abs((long) goal.x() - node.x()) + Math.abs((long) goal.z() - node.z())) * WALK_COST
                + Math.abs((long) goal.sightY() * 16 - node.y16()) * 6;
        }
        return horizontalEstimate(node) + goal.vertical(node) * 6;
    }

    private void rememberPartial(Candidate current) {
        if (!config.allowPartial || goal.heightBand() != null || goal.skyExposed()) return;
        long distance = remaining(current.node);
        boolean better = distance < partialRemaining || distance == partialRemaining && partial != null && current.cost < partial.cost;
        if (better && terrain.canFinish(current.node)) {
            partial = current;
            partialRemaining = distance;
        }
    }

    private void finishLimited(String failure) {
        if (partial != null && partialRemaining < startRemaining) {
            finish(Status.PARTIAL, partial, "Found a route partway to the goal through confirmed terrain.");
        } else {
            finish(Status.FAILED, null, goal.skyExposed() ? "No reachable sky-exposed footing was found. " + failure : failure);
        }
    }

    private void finish(Status status, Candidate candidate, String reason) {
        List<Step> path = new ArrayList<>();
        for (Candidate current = candidate; current != null && current.parent != null; current = current.parent) {
            path.add(new Step(current.parent.node, current.node, current.move));
        }
        Collections.reverse(path);
        terminal = new Result(status, path, visited, reason);
        open.clear();
        best.clear();
        Arrays.fill(floorLists, null);
    }
}
