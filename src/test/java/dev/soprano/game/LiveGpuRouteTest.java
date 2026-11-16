package dev.soprano.game;

import dev.soprano.map.SurveyRoute;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.IncrementalSearch.Config;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LiveGpuRouteTest {
    private static final Node START = new Node(0, 16, 0), MIDDLE = new Node(1, 16, 0), END = new Node(2, 16, 0);
    private static final List<Step> DIRECT = List.of(new Step(START, MIDDLE, Move.WALK), new Step(MIDDLE, END, Move.WALK));
    private static final Config CONFIG = new Config(false, false, 0, 12, 2_000, false);
    private static final AdaptivePlanning.Slice ONE_STEP = new AdaptivePlanning.Slice(1, Long.MAX_VALUE);
    private static final AdaptivePlanning.Slice FULL = new AdaptivePlanning.Slice(100, Long.MAX_VALUE);

    public static void main(String[] args) {
        var checked = candidate(DIRECT, END);
        check(checked.advance(ground(false), ONE_STEP, Long.MAX_VALUE, () -> true) == LiveGpuRoute.Status.VERIFYING,
            "A valid prefix does not publish a partially checked candidate.");
        check(checked.advance(ground(false), ONE_STEP, Long.MAX_VALUE, () -> true) == LiveGpuRoute.Status.READY,
            "A full candidate can be adopted after all live checks and the actual start connection.");
        check(candidate(DIRECT, END).advance(ground(false), FULL, Long.MAX_VALUE, () -> false) == LiveGpuRoute.Status.REJECTED,
            "A player movement correction invalidates the original GPU start.");
        check(candidate(DIRECT, END).advance(ground(true), FULL, Long.MAX_VALUE, () -> true) == LiveGpuRoute.Status.REJECTED,
            "Live terrain can invalidate a previously surveyed edge.");
        check(candidate(List.of(DIRECT.getLast()), END).advance(ground(false), FULL, Long.MAX_VALUE, () -> true) == LiveGpuRoute.Status.REJECTED,
            "A route cannot start from a disconnected GPU waypoint.");
        Node diagonal = new Node(1, 16, 1);
        check(candidate(List.of(new Step(START, diagonal, Move.WALK)), diagonal).advance(ground(false), FULL, Long.MAX_VALUE, () -> true)
            == LiveGpuRoute.Status.REJECTED, "A GPU path must honor the live profile's movement settings.");
        directWalkChecks();
        optimalKeepsCheaperCpuRoute();
        System.out.println("Soprano live GPU candidate checks passed.");
    }

    private static void directWalkChecks() {
        Node end = new Node(9, 16, 7);
        Goal goal = new Goal(end.x(), end.y16(), end.z(), 0);
        var config = new Config(true, true, 1, 32, 500, false);
        Terrain flat = ground(false);
        var straight = DirectWalkRoute.forGoal(START, goal, config);
        check(straight.advance(flat, ONE_STEP) == DirectWalkRoute.Status.CHECKING
            && straight.checkedSteps() == 3, "direct checks respect the per-slice work limit");
        check(straight.advance(flat, FULL) == DirectWalkRoute.Status.FOUND,
            "direct level route reaches the exact point");
        var independent = new IncrementalSearch(flat, START, goal, config).advance(500);
        check(independent.status() == IncrementalSearch.Status.FOUND
            && straight.path().stream().mapToInt(Step::cost).sum() == independent.path().stream().mapToInt(Step::cost).sum(),
            "a verified direct route has the same cost as the full optimal search");
        check(DirectWalkRoute.forGoal(START, new Goal(9, 0, 7, 0), config) == null,
            "elevation changes use the full planner");
        var arrival = DirectWalkRoute.forGoal(START, new Goal(9, 16, 7, 1), config);
        check(arrival.advance(flat, FULL) == DirectWalkRoute.Status.FOUND
            && arrival.path().getLast().to().equals(new Node(8, 16, 6)),
            "direct routes can reach the geometrically nearest corner of an arrival area");
        Goal shortGoal = new Goal(END.x(), END.y16(), END.z(), 0);
        check(DirectWalkRoute.forGoal(START, shortGoal, config).advance(ground(true), FULL) == DirectWalkRoute.Status.BLOCKED,
            "changed terrain invalidates a direct route");
        Terrain sideBlocked = new Terrain() {
            @Override public List<Node> floors(int x, int z, int low, int high) {
                return x == 1 && z == 0 ? List.of() : flat.floors(x, z, low, high);
            }
            @Override public boolean canTraverse(Node from, Node to, Move move) { return true; }
        };
        check(DirectWalkRoute.forGoal(START, goal, config).advance(sideBlocked, FULL) == DirectWalkRoute.Status.BLOCKED,
            "a level diagonal still requires both cardinal sides");
        Terrain preferredRoute = new Terrain() {
            @Override public List<Node> floors(int x, int z, int low, int high) { return flat.floors(x, z, low, high); }
            @Override public boolean canTraverse(Node from, Node to, Move move) { return true; }
            @Override public int transitionPenalty(Node from, Node to, Move move) { return 1; }
        };
        check(DirectWalkRoute.forGoal(START, goal, config).advance(preferredRoute, FULL) == DirectWalkRoute.Status.BLOCKED,
            "routes charging preference penalties use the full planner");
    }

    private static void optimalKeepsCheaperCpuRoute() {
        Terrain terrain = ground(false);
        Goal goal = new Goal(END.x(), END.y16(), END.z(), 0);
        var optimal = new IncrementalSearch(terrain, START, goal, CONFIG);
        long lowerBound = optimal.optimalFrontierLowerBound();
        check(lowerBound == 200, "The live search starts with an admissible frontier bound.");
        Node a = new Node(0, 16, 1), b = new Node(1, 16, 1), c = new Node(2, 16, 1);
        var detour = candidate(List.of(new Step(START, a, Move.WALK), new Step(a, b, Move.WALK), new Step(b, c, Move.WALK),
            new Step(c, END, Move.WALK)), END);
        check(detour.advance(terrain, FULL, lowerBound, () -> true) == LiveGpuRoute.Status.PENDING,
            "A fully walkable GPU detour cannot beat the live frontier's cheaper possibility.");
        var direct = optimal.advance(100);
        check(direct.status() == IncrementalSearch.Status.FOUND && direct.path().equals(DIRECT),
            "Optimal A* can select its strictly cheaper route.");
        check(candidate(DIRECT, END).advance(terrain, FULL, lowerBound, () -> true) == LiveGpuRoute.Status.READY,
            "A fully verified GPU route at the admissible bound can be adopted.");
    }

    private static LiveGpuRoute candidate(List<Step> steps, Node end) {
        var goal = new Goal(end.x(), end.y16(), end.z(), 0);
        var result = new SurveyRoute.DedicatedCandidate(steps, "test device");
        return new LiveGpuRoute(START, goal, CONFIG, CompletableFuture.completedFuture(result));
    }

    private static Terrain ground(boolean changed) {
        return new Terrain() {
            @Override public List<Node> floors(int x, int z, int low, int high) {
                return low <= 16 && high >= 16 ? List.of(new Node(x, 16, z)) : List.of();
            }

            @Override public boolean canTraverse(Node from, Node to, Move move) { return !changed || !to.equals(END); }
        };
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
