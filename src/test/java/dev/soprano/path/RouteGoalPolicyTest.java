package dev.soprano.path;

import dev.soprano.path.RouteExclusions.Bounds;

import java.util.List;

public final class RouteGoalPolicyTest {
    private static final IncrementalSearch.Config CONFIG = new IncrementalSearch.Config(false, true, 1, 12, 2_000, true);

    public static void main(String[] args) {
        heightRangeUsesOriginalHorizontalLimit();
        surfaceRequiresReachableOpenSky();
        preferredCorridorsCannotOverrideSafetyOrBounds();
        selectionChecksBodyAndHeadroom();
        observedGoalNeedsVerifiedSight();
        movingFollowKeepsCurrentEdgeWithoutRetracing();
        System.out.println("Soprano route goal and corridor checks passed.");
    }

    private static void heightRangeUsesOriginalHorizontalLimit() {
        Terrain slope = new Terrain() {
            @Override public List<Node> floors(int x, int z, int minY16, int maxY16) {
                if (z != 0 || x < 0 || x > 6) return List.of();
                int y = x * 16;
                return y < minY16 || y > maxY16 ? List.of() : List.of(new Node(x, y, z));
            }

            @Override public boolean canTraverse(Node from, Node to, Move move) { return true; }
        };
        Node start = new Node(0, 0, 0);
        var reachable = run(slope, start, Goal.height(0, 0, 32, 48, 2));
        check(reachable.status() == IncrementalSearch.Status.FOUND && reachable.path().getLast().to().equals(new Node(2, 32, 0)),
            "The path ends at the first reachable height in its inclusive band.");
        var bounded = run(slope, start, Goal.height(0, 0, 48, 64, 2));
        check(bounded.status() == IncrementalSearch.Status.FAILED, "A height request stays inside the horizontal limit and cannot fall back to partial travel.");
    }

    private static void surfaceRequiresReachableOpenSky() {
        var terrain = new Road(RoutePolicy.UNRESTRICTED, false) {
            @Override public boolean isSkyExposed(Node node) { return node.x() >= 2; }
            @Override public boolean canTraverse(Node from, Node to, Move move) {
                return from.z() != 0 || to.z() != 0 || to.x() != 2;
            }
        };
        var from = new Node(0, 16, 0);
        var goal = Goal.surface(0, 0, 4);
        var result = run(terrain, from, goal);
        check(result.status() == IncrementalSearch.Status.FOUND
            && result.path().getLast().to().equals(new Node(2, 16, 1)),
            "A surface route chooses the cheapest reachable sky-exposed position.");
        check(run(terrain, from, Goal.surface(0, 0, 1)).status() == IncrementalSearch.Status.FAILED,
            "A covered column never counts as an arrival or partial surface route.");
        check(run(terrain, new Node(2, 16, 1), goal).path().isEmpty(),
            "A surface route can finish immediately on sky-exposed footing.");
    }

    private static void preferredCorridorsCannotOverrideSafetyOrBounds() {
        RoutePolicy policy = new RoutePolicy(null, List.of(new Bounds(0, 0, 1, 7, 1, 2)));
        Node start = new Node(0, 16, 0);
        Goal destination = new Goal(6, 16, 0, 0);
        var preferred = run(new Road(policy, false), start, destination);
        check(preferred.status() == IncrementalSearch.Status.FOUND && preferred.path().stream().anyMatch(step -> step.to().z() == 1),
            "A slightly longer marked road can beat the unmarked direct route.");
        var blocked = run(new Road(policy, true), start, destination);
        check(blocked.status() == IncrementalSearch.Status.FOUND && blocked.path().stream().noneMatch(step ->
            step.from().z() == 1 && step.to().z() == 1 && Math.min(step.from().x(), step.to().x()) == 2),
            "The road preference cannot authorize a blocked edge.");
        var bounded = run(new Road(policy, false), start, destination.within(new Bounds(0, 0, 0, 7, 3, 1)));
        check(bounded.status() == IncrementalSearch.Status.FOUND && bounded.path().stream().allMatch(step -> step.to().z() == 0),
            "The route cannot leave A-B to reach a preferred road.");
    }

    private static void selectionChecksBodyAndHeadroom() {
        RoutePolicy policy = new RoutePolicy(new Bounds(0, 0, 0, 2, 3, 2), List.of());
        check(policy.containsBody(.5, 1, .5, .3, 1.8), "A full standing body fits the selected space.");
        check(!policy.containsBody(.2, 1, .5, .3, 1.8), "A center inside A-B is insufficient when a shoulder crosses its side.");
        check(!policy.containsBody(.5, 1.25, .5, .3, 1.8), "Jump arcs need headroom inside A-B.");
        try {
            new Goal(2, 16, 0, 0).within(policy.selection());
            throw new AssertionError("An unreachable goal outside A-B cannot start a partial route.");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("selected route volume"), "Explain why the selected volume rejected the goal.");
        }
    }

    private static void observedGoalNeedsVerifiedSight() {
        Goal target = Goal.observedBlock(3, 1, 0, "minecraft:stone");
        Terrain terrain = new Road(RoutePolicy.UNRESTRICTED, false) {
            @Override public boolean hasLineOfSight(Node node, Goal goal) {
                return node.x() == 2 && node.z() == 0 && "minecraft:stone".equals(goal.observedBlockId());
            }
        };
        var result = run(terrain, new Node(0, 16, 0), target);
        check(result.status() == IncrementalSearch.Status.FOUND && result.path().getLast().to().x() == 2,
            "Known block routing finishes at verified sight instead of accepting every node within range.");
    }

    private static void movingFollowKeepsCurrentEdgeWithoutRetracing() {
        var first = new Node(0, 16, 0);
        var activeEnd = new Node(1, 16, 0);
        var corner = new Node(2, 16, 0);
        var anchor = new Node(3, 16, 0);
        var newTarget = new Node(2, 16, 1);
        List<Step> current = List.of(new Step(first, activeEnd, Move.WALK), new Step(activeEnd, corner, Move.WALK),
            new Step(corner, anchor, Move.WALK));
        List<Step> continuation = List.of(new Step(anchor, corner, Move.WALK), new Step(corner, newTarget, Move.WALK));
        var joined = FollowSplice.join(current, 0, 3, anchor, continuation);
        check(joined.equals(List.of(current.get(0), current.get(1), continuation.get(1))),
            "A fresh target keeps the edge in progress and skips an unnecessary out-and-back leg.");
        var onLastEdge = FollowSplice.join(current, 2, 3, anchor, continuation);
        check(onLastEdge.get(2).equals(current.get(2)), "Even a reversal keeps the edge already in use.");
        check(FollowSplice.join(current, 2, 2, corner, continuation).isEmpty(), "A result for an already passed join is discarded.");
        check(FollowSplice.join(current, 0, 3, anchor, List.of(continuation.get(1))).isEmpty(), "A disconnected continuation is discarded.");
    }

    private static IncrementalSearch.Result run(Terrain terrain, Node start, Goal goal) {
        var search = new IncrementalSearch(terrain, start, goal, CONFIG);
        var result = search.result();
        while (result.status() == IncrementalSearch.Status.SEARCHING) result = search.advance(64);
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static class Road implements Terrain {
        private final RoutePolicy policy;
        private final boolean blocked;

        Road(RoutePolicy policy, boolean blocked) { this.policy = policy; this.blocked = blocked; }

        @Override public List<Node> floors(int x, int z, int minY16, int maxY16) {
            return x >= 0 && x <= 6 && (z == 0 || z == 1) && minY16 <= 16 && maxY16 >= 16
                ? List.of(new Node(x, 16, z)) : List.of();
        }

        @Override public boolean canTraverse(Node from, Node to, Move move) {
            return !blocked || from.z() != 1 || to.z() != 1 || Math.min(from.x(), to.x()) != 2;
        }

        @Override public int transitionPenalty(Node from, Node to, Move move) { return policy.penalty(to); }
    }
}
