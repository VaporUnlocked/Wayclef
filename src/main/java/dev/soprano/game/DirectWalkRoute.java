package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch.Config;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.ArrayList;
import java.util.List;

/** Checks the minimum-cost level route before expanding an A* frontier. */
final class DirectWalkRoute {
    enum Status { CHECKING, FOUND, BLOCKED }

    private static final int MAX_PROBE_STEPS = 256;

    private final Node end;
    private final boolean diagonals;
    private final List<Step> path;
    private Node cursor;
    private Status status = Status.CHECKING;

    private DirectWalkRoute(Node start, Node end, boolean diagonals, int steps) {
        this.cursor = start;
        this.end = end;
        this.diagonals = diagonals;
        path = new ArrayList<>(steps);
    }

    static DirectWalkRoute forGoal(Node start, Goal goal, Config config) {
        if (goal.skyExposed() || goal.bounds() != null || goal.sightY() != null
            || goal.heightBand() != null || goal.y16() != null && goal.y16() != start.y16()) return null;
        int x = Math.clamp(start.x(), goal.x() - goal.radius(), goal.x() + goal.radius());
        int z = Math.clamp(start.z(), goal.z() - goal.radius(), goal.z() + goal.radius());
        int dx = Math.abs(x - start.x()), dz = Math.abs(z - start.z());
        int distance = Math.max(dx, dz);
        int steps = config.diagonals() ? distance : dx + dz;
        if (steps == 0 || steps > MAX_PROBE_STEPS || steps >= config.maxVisited() || distance > config.horizontalRange()) return null;
        return new DirectWalkRoute(start, new Node(x, start.y16(), z), config.diagonals(), steps);
    }

    int checkedSteps() { return path.size(); }

    List<Step> path() { return List.copyOf(path); }

    Status advance(Terrain terrain, AdaptivePlanning.Slice budget) {
        if (status != Status.CHECKING) return status;
        long began = System.nanoTime();
        int limit = Math.min(512, budget.nodes() * 3);
        terrain.beginSearchSlice();
        try {
            if (path.isEmpty() && (!hasFloor(terrain, cursor) || !hasFloor(terrain, end) || !terrain.canFinish(end))) {
                return status = Status.BLOCKED;
            }
            for (int checked = 0; checked < limit; checked++) {
                if (checked > 0 && System.nanoTime() - began >= budget.nanos()) return status;
                Node next = nextNode();
                // Every step must contribute only its horizontal lower-bound cost.
                if (terrain.transitionPenalty(cursor, next, Move.WALK) > 0
                    || !hasFloor(terrain, next) || !sidesClear(terrain, next)
                    || !terrain.canTraverse(cursor, next, Move.WALK)) return status = Status.BLOCKED;
                path.add(new Step(cursor, next, Move.WALK));
                cursor = next;
                if (cursor.equals(end)) return status = terrain.canFinish(end) ? Status.FOUND : Status.BLOCKED;
            }
            return status;
        } finally {
            terrain.endSearchSlice();
        }
    }

    private Node nextNode() {
        int dx = Integer.compare(end.x(), cursor.x());
        int dz = Integer.compare(end.z(), cursor.z());
        if (diagonals || dx == 0) return new Node(cursor.x() + dx, cursor.y16(), cursor.z() + dz);
        return new Node(cursor.x() + dx, cursor.y16(), cursor.z());
    }

    private boolean sidesClear(Terrain terrain, Node next) {
        if (next.x() == cursor.x() || next.z() == cursor.z()) return true;
        Node xSide = new Node(next.x(), cursor.y16(), cursor.z());
        Node zSide = new Node(cursor.x(), cursor.y16(), next.z());
        return hasFloor(terrain, xSide) && terrain.canTraverse(cursor, xSide, Move.WALK)
            && hasFloor(terrain, zSide) && terrain.canTraverse(cursor, zSide, Move.WALK);
    }

    private static boolean hasFloor(Terrain terrain, Node node) {
        return terrain.floors(node.x(), node.z(), node.y16(), node.y16()).contains(node);
    }
}
