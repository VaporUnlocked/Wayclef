package dev.soprano.path;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.soprano.path.IncrementalSearch.Status.FAILED;
import static dev.soprano.path.IncrementalSearch.Status.FOUND;
import static dev.soprano.path.IncrementalSearch.Status.PARTIAL;
import static dev.soprano.path.IncrementalSearch.Status.SEARCHING;

public final class IncrementalSearchTest {
    private static int checks;

    public static void main(String[] args) {
        adjacentMovement();
        shapedStepsWithoutJumping();
        ladderRoutesAndStableArrivals();
        noCornerCutting();
        cornerChecksReuseCardinalClearance();
        terrainRefreshesBetweenSlices();
        quickModeHonorsMovementRules();
        walksWhenCheaper();
        arrivalAreaAndAltitude();
        lineOfSightGoals();
        sliceAndSearchLimits();
        partialProgress();
        worldBoundsAndInvalidInput();
        System.out.printf("Soprano path search passed %d checks.%n", checks);
    }

    private static void adjacentMovement() {
        Node origin = node(0, 0, 0);
        Node slab = node(1, 8, 0);
        Node jump = node(2, 24, 0);
        Node land = node(3, -8, 0);
        Grid terrain = new Grid(origin, slab, jump, land);
        var route = finish(new IncrementalSearch(terrain, origin, exact(land), config(false, true, 2)));
        check(route.status() == FOUND, "stair, one-block jump and bounded drop connect");
        check(route.path().equals(List.of(new Step(origin, slab, Move.WALK), new Step(slab, jump, Move.JUMP),
            new Step(jump, land, Move.DROP))), "height transitions select the correct movement");
        check(finish(new IncrementalSearch(terrain, origin, exact(land), config(false, false, 2))).status() == FAILED,
            "disabling jumping removes the jump edge");
        check(finish(new IncrementalSearch(terrain, origin, exact(land), config(false, true, 1))).status() == FAILED,
            "the drop limit is enforced");
        Node unsafeRise = node(1, 17, 0);
        check(finish(new IncrementalSearch(new Grid(origin, unsafeRise), origin, exact(unsafeRise),
            config(false, true, 0))).status() == FAILED, "a rise higher than one block is never a jump");
        Node acrossGap = node(2, 0, 0);
        check(finish(new IncrementalSearch(new Grid(origin, acrossGap), origin, exact(acrossGap),
            config(false, true, 0))).status() == FAILED, "a missing adjacent floor cannot become a gap jump");
        terrain.block(origin, slab, Move.WALK);
        check(finish(new IncrementalSearch(terrain, origin, exact(land), config(false, true, 2))).status() == FAILED,
            "terrain clearance can deny an otherwise legal action");
    }

    private static void shapedStepsWithoutJumping() {
        Node bottom = node(0, 0, 0), top = node(1, 16, 0);
        Grid terrain = new Grid(bottom, top);
        terrain.extendedSteps = true;
        terrain.extendedWalks.add(new Step(bottom, top, Move.WALK));
        terrain.extendedWalks.add(new Step(top, bottom, Move.WALK));
        var careful = config(false, false, 0);
        var uphill = finish(new IncrementalSearch(terrain, bottom, exact(top), careful));
        var downhill = finish(new IncrementalSearch(terrain, top, exact(bottom), careful));
        check(uphill.path().equals(List.of(new Step(bottom, top, Move.WALK))),
            "a collision-verified stair can rise a full block without enabling jumps");
        check(downhill.path().equals(List.of(new Step(top, bottom, Move.WALK))),
            "a collision-verified stair can descend a full block without enabling drops");
        terrain.extendedWalks.clear();
        check(finish(new IncrementalSearch(terrain, bottom, exact(top), careful)).status() == FAILED,
            "the same rise still fails if the shape would need an unapproved jump");
        check(finish(new IncrementalSearch(terrain, bottom, exact(top), config(false, true, 0)))
            .path().equals(List.of(new Step(bottom, top, Move.JUMP))),
            "a blocked extended walk can still use an enabled jump");
    }

    private static void ladderRoutesAndStableArrivals() {
        Node foot = node(0, 0, 0), rung = node(0, 16, 0), high = node(0, 32, 0), top = node(1, 48, 0);
        Grid terrain = new Grid(foot, rung, high, top);
        terrain.ladderOnly.addAll(List.of(rung, high));
        terrain.climb(foot, rung);
        terrain.climb(rung, high);
        terrain.climb(high, top);
        var careful = config(false, false, 0);
        var up = finish(new IncrementalSearch(terrain, foot, exact(top), careful));
        var down = finish(new IncrementalSearch(terrain, top, exact(foot), careful));
        check(up.path().equals(List.of(new Step(foot, rung, Move.CLIMB), new Step(rung, high, Move.CLIMB),
            new Step(high, top, Move.CLIMB))), "a ladder is an ascending connection between supported floors");
        check(down.path().equals(List.of(new Step(top, high, Move.CLIMB), new Step(high, rung, Move.CLIMB),
            new Step(rung, foot, Move.CLIMB))), "a ladder works in reverse with zero drop allowance");
        check(finish(new IncrementalSearch(terrain, foot, exact(rung), careful)).status() == FAILED,
            "an unsupported ladder rung cannot count as arrival");
        var partial = new IncrementalSearch.Config(false, false, 0, 4, 10, true);
        var safePartial = finish(new IncrementalSearch(terrain, foot, new Goal(0, 48, 0, 0), partial));
        check(safePartial.status() == PARTIAL && safePartial.path().getLast().to().equals(top),
            "a partial route can end at the supported ladder exit");
        terrain.climbEdges.remove(new Step(high, top, Move.CLIMB));
        check(finish(new IncrementalSearch(terrain, foot, new Goal(0, 48, 0, 0), partial)).status() == FAILED,
            "without the exit a partial route cannot strand the player on a rung");
        terrain.climbEdges.remove(new Step(rung, high, Move.CLIMB));
        check(finish(new IncrementalSearch(terrain, foot, exact(top), careful)).status() == FAILED,
            "search refuses to invent a missing ladder segment");
    }

    private static void noCornerCutting() {
        Node origin = node(0, 0, 0), goal = node(1, 0, 1), sideX = node(1, 0, 0), sideZ = node(0, 0, 1);
        Grid terrain = new Grid(origin, goal);
        check(finish(new IncrementalSearch(terrain, origin, exact(goal), config(true, true, 3))).status() == FAILED,
            "a diagonal cannot cut across two missing corners");
        terrain.add(sideX);
        var singleSide = finish(new IncrementalSearch(terrain, origin, exact(goal), config(true, true, 3)));
        check(singleSide.path().size() == 2, "one missing corner requires two cardinal moves");
        terrain.add(sideZ);
        var bothSides = finish(new IncrementalSearch(terrain, origin, exact(goal), config(true, true, 3)));
        check(bothSides.path().equals(List.of(new Step(origin, goal, Move.WALK))), "both clear corners allow a single diagonal move");
        terrain.block(origin, sideX, Move.WALK);
        var blockedSide = finish(new IncrementalSearch(terrain, origin, exact(goal), config(true, true, 3)));
        check(blockedSide.path().size() == 2 && blockedSide.path().getFirst().to().equals(sideZ),
            "the diagonal also checks both cardinal departure corridors");
    }

    private static void cornerChecksReuseCardinalClearance() {
        Node start = node(0, 0, 0), goal = node(1, 0, 1);
        Node east = node(1, 0, 0), south = node(0, 0, 1);
        Grid terrain = new Grid(start, east, south, goal);
        var search = new IncrementalSearch(terrain, start, exact(goal), config(true, true, 1));
        search.advance(1);
        check(terrain.checks(start, east) == 1 && terrain.checks(start, south) == 1,
            "one expansion reuses each cardinal collision check for adjacent diagonals");
        check(finish(search).path().equals(List.of(new Step(start, goal, Move.WALK))),
            "reusing checks leaves the optimal diagonal path intact");
    }

    private static void terrainRefreshesBetweenSlices() {
        Node start = node(0, 0, 0), via = node(0, 0, 1), corner = node(1, 0, 1), goal = node(1, 0, 0);
        Grid terrain = new Grid(start, via, corner);
        var search = new IncrementalSearch(terrain, start, exact(goal), config(true, false, 0));
        check(search.advance(1).status() == SEARCHING, "the unavailable goal is queried during the first slice");
        terrain.add(goal);
        var updated = finish(search);
        check(updated.status() == FOUND && updated.path().getLast().to().equals(goal),
            "the following slice can see newly loaded footing");
    }

    private static void quickModeHonorsMovementRules() {
        Node start = node(0, 0, 0), east = node(1, 0, 0), south = node(0, 0, 1), goal = node(1, 0, 1);
        Grid terrain = new Grid(start, east, south, goal);
        terrain.block(start, east, Move.WALK);
        var quick = new IncrementalSearch.Config(true, false, 0, 32, 200, false, IncrementalSearch.SearchMode.QUICK);
        var route = finish(new IncrementalSearch(terrain, start, exact(goal), quick));
        check(route.path().equals(List.of(new Step(start, south, Move.WALK), new Step(south, goal, Move.WALK))),
            "quick search still obeys blocked cardinal corners");
        check(config(true, true, 1).searchMode() == IncrementalSearch.SearchMode.OPTIMAL,
            "the existing configuration constructor retains optimal search");
    }

    private static void walksWhenCheaper() {
        Node origin = node(0, 0, 0), target = node(4, 0, 0);
        Grid terrain = new Grid(origin, target, node(1, 16, 0), node(2, 0, 0), node(3, 16, 0));
        for (int x = 0; x <= 4; x++) terrain.add(node(x, 0, 1));
        var route = finish(new IncrementalSearch(terrain, origin, exact(target), config(false, true, 1)));
        check(route.path().size() == 6, "a longer flat walk can cost less than repeated jumps and drops");
        check(route.path().stream().allMatch(step -> step.move() == Move.WALK), "the cheaper route contains ordinary walks");
        Node previous = origin;
        for (Step step : route.path()) {
            check(step.from().equals(previous), "every reported step connects to the previous one");
            previous = step.to();
        }
        check(previous.equals(target), "the reported path ends on the exact requested node");
        expectFailure(() -> route.path().add(new Step(origin, target, Move.WALK)), UnsupportedOperationException.class);
        var repeated = finish(new IncrementalSearch(terrain, origin, exact(target), config(false, true, 1)));
        check(repeated.path().equals(route.path()) && repeated.visited() == route.visited(), "a fixed terrain and profile produce a deterministic result");
    }

    private static void arrivalAreaAndAltitude() {
        Node origin = node(0, 0, 0), below = node(1, 0, 0), upper = node(1, 16, 0), side = node(2, 16, 0);
        Grid terrain = new Grid(origin, below, upper, side);
        var anyAltitude = finish(new IncrementalSearch(terrain, origin, new Goal(1, null, 0, 0), config(false, true, 1)));
        check(anyAltitude.path().getLast().to().equals(below), "a horizontal goal chooses the cheaper known altitude");
        var exactAltitude = finish(new IncrementalSearch(terrain, origin, exact(upper), config(false, true, 1)));
        check(exactAltitude.path().equals(List.of(new Step(origin, upper, Move.JUMP))), "an explicit altitude distinguishes stacked floors");
        var approach = finish(new IncrementalSearch(terrain, origin, new Goal(3, 16, 0, 1), config(false, true, 1)));
        check(approach.path().getLast().to().equals(side), "a radius goal stops on the nearest valid position in its area");
        var alreadyThere = new IncrementalSearch(terrain, origin, new Goal(1, null, 1, 1), config(false, true, 1)).result();
        check(alreadyThere.status() == FOUND && alreadyThere.path().isEmpty() && alreadyThere.visited() == 0,
            "a position already inside the arrival area needs no movement");
    }

    private static void sliceAndSearchLimits() {
        Node origin = node(0, 0, 0), target = node(4, 0, 0);
        Grid terrain = new Grid(origin, target, node(1, 0, 0), node(2, 0, 0), node(3, 0, 0));
        var search = new IncrementalSearch(terrain, origin, exact(target), config(false, false, 0));
        check(search.result().visited() == 0, "the initial call reports zero expanded nodes");
        check(search.advance(1, 1).visited() == 1, "a tiny time slice still attempts one expansion");
        var second = search.advance(1);
        check(second.status() == SEARCHING && second.path().isEmpty() && second.visited() == 2,
            "one expansion per slice keeps path intent hidden until completion");
        var found = finish(search);
        check(found.status() == FOUND && found.visited() == 5, "multiple slices keep the same search frontier");
        check(search.advance(1) == found, "an already-complete search returns its stable result");
        var range = finish(new IncrementalSearch(terrain, origin, exact(target), new IncrementalSearch.Config(false, false, 0, 2, 100, false)));
        check(range.status() == FAILED && range.visited() == 3, "a bounded search cannot leave its configured horizon");
        var limit = finish(new IncrementalSearch(terrain, origin, exact(target), new IncrementalSearch.Config(false, false, 0, 8, 2, false)));
        check(limit.status() == FAILED && limit.visited() == 2 && limit.reason().contains("budget"), "global node limit stops search predictably");
    }

    private static void partialProgress() {
        Node origin = node(0, 0, 0), middle = node(1, 0, 0), frontier = node(2, 0, 0), target = node(4, 0, 0);
        Grid terrain = new Grid(origin, middle, frontier);
        var profile = new IncrementalSearch.Config(false, true, 1, 8, 20, true);
        var first = finish(new IncrementalSearch(terrain, origin, exact(target), profile));
        check(first.status() == PARTIAL && first.path().getLast().to().equals(frontier),
            "partial mode ends at a confirmed position closer to the goal");
        var next = finish(new IncrementalSearch(terrain, frontier, exact(target), profile));
        check(next.status() == FAILED && next.path().isEmpty(), "restarting at the frontier cannot circle back to the previous start");
        terrain.add(node(3, 0, 0));
        terrain.add(target);
        var expanded = finish(new IncrementalSearch(terrain, frontier, exact(target), profile));
        check(expanded.status() == FOUND && expanded.path().size() == 2, "new terrain lets the next job finish the route");
        Node below = node(1, -16, 0);
        var lower = finish(new IncrementalSearch(new Grid(origin, below), origin, new Goal(4, 0, 0, 0), profile));
        check(lower.status() == PARTIAL, "a modest safe altitude change can still make useful horizontal progress");
    }

    private static void worldBoundsAndInvalidInput() {
        Node origin = node(Node.MIN_COLUMN, 0, 0);
        Grid terrain = new Grid(origin);
        var failed = finish(new IncrementalSearch(terrain, origin, new Goal(Node.MIN_COLUMN + 1, 0, 0, 0),
            config(true, true, 3)));
        check(failed.status() == FAILED, "search finishes at a world border without querying outside it");
        check(new IncrementalSearch(new Grid(), origin, exact(origin), config(false, true, 0)).result().status() == FAILED,
            "a missing floor cannot count as arrival at the start");
        expectFailure(() -> node(Node.MAX_COLUMN + 1, 0, 0), IllegalArgumentException.class);
        expectFailure(() -> new Goal(0, null, 0, -1), IllegalArgumentException.class);
        expectFailure(() -> new IncrementalSearch.Config(false, true, 4, 8, 100, false), IllegalArgumentException.class);
        var search = new IncrementalSearch(terrain, origin, exact(origin), config(false, true, 0));
        expectFailure(() -> search.advance(0), IllegalArgumentException.class);
    }

    private static IncrementalSearch.Config config(boolean diagonal, boolean jump, int drop) {
        return new IncrementalSearch.Config(diagonal, jump, drop, 32, 200, false);
    }

    private static void lineOfSightGoals() {
        Node start = node(0, 0, 0), middle = node(1, 0, 0), visible = node(2, 0, 0);
        var terrain = new Grid(start, middle, visible);
        var goal = Goal.lineOfSight(3, 2, 0, 4);
        check(finish(new IncrementalSearch(terrain, start, goal, config(false, false, 0))).status() == FAILED,
            "a standing position within range needs verified line of sight");
        terrain.visible.add(visible);
        var result = finish(new IncrementalSearch(terrain, start, goal, config(false, false, 0)));
        check(result.status() == FOUND && result.path().getLast().to().equals(visible), "line of sight can be found after approaching the target");
    }

    private static Goal exact(Node node) { return new Goal(node.x(), node.y16(), node.z(), 0); }
    private static Node node(int x, int y16, int z) { return new Node(x, y16, z); }

    private static IncrementalSearch.Result finish(IncrementalSearch search) {
        for (int i = 0; i < 300; i++) {
            var state = search.advance(7);
            if (state.status() != SEARCHING) return state;
        }
        throw new AssertionError("Synthetic search did not reach a terminal state.");
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }

    private static void expectFailure(Runnable operation, Class<? extends Throwable> type) {
        try {
            operation.run();
        } catch (RuntimeException error) {
            if (!type.isInstance(error)) throw error;
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class Grid implements Terrain {
        private final Set<Node> floors = new HashSet<>();
        private final Set<Step> blocked = new HashSet<>();
        private final Map<Step, Integer> checks = new HashMap<>();
        private final Set<Step> extendedWalks = new HashSet<>();
        private final Set<Step> climbEdges = new HashSet<>();
        private final Set<Node> ladderOnly = new HashSet<>();
        private final Set<Node> visible = new HashSet<>();
        private boolean extendedSteps;

        private Grid(Node... nodes) { floors.addAll(List.of(nodes)); }
        private void add(Node node) { floors.add(node); }
        private void block(Node from, Node to, Move move) { blocked.add(new Step(from, to, move)); }

        @Override
        public List<Node> floors(int x, int z, int minY16, int maxY16) {
            if (!Node.validColumn(x) || !Node.validColumn(z)) throw new AssertionError("Invalid terrain query");
            return floors.stream().filter(node -> node.x() == x && node.z() == z && node.y16() >= minY16 && node.y16() <= maxY16).toList();
        }

        void climb(Node from, Node to) {
            climbEdges.add(new Step(from, to, Move.CLIMB));
            climbEdges.add(new Step(to, from, Move.CLIMB));
        }

        @Override public boolean supportsExtendedSteps() { return extendedSteps; }
        @Override public boolean supportsClimbing() { return !climbEdges.isEmpty(); }
        @Override public boolean canFinish(Node node) { return !ladderOnly.contains(node); }
        @Override public boolean hasLineOfSight(Node node, Goal goal) { return visible.contains(node); }

        @Override
        public List<Node> climbNeighbors(Node from) {
            return climbEdges.stream().filter(step -> step.from().equals(from)).map(Step::to).toList();
        }

        @Override
        public boolean canTraverse(Node from, Node to, Move move) {
            Step step = new Step(from, to, move);
            checks.merge(step, 1, Integer::sum);
            if (move == Move.CLIMB) return climbEdges.contains(step);
            if (move == Move.WALK && Math.abs(from.y16() - to.y16()) > 9) return extendedWalks.contains(step);
            return !blocked.contains(step);
        }

        private int checks(Node from, Node to) {
            return checks.getOrDefault(new Step(from, to, Move.WALK), 0);
        }
    }
}
