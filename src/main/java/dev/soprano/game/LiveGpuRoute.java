package dev.soprano.game;

import dev.soprano.map.MapSnapshot;
import dev.soprano.map.SurveyRoute;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch.Config;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** A GPU result from immutable survey data. Client-thread checks own the decision to adopt it. */
final class LiveGpuRoute {
    enum Status { PENDING, VERIFYING, READY, REJECTED }

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(), job -> {
            var thread = new Thread(job, "Soprano GPU route");
            thread.setDaemon(true);
            return thread;
        });

    private final Node start;
    private final Goal goal;
    private final Config config;
    private final Future<SurveyRoute.DedicatedCandidate> task;
    private SurveyRoute.DedicatedCandidate candidate;
    private Node cursor;
    private int checked;
    private long minimumCost;
    private long verifiedCost;

    LiveGpuRoute(Node start, Goal goal, Config config, Future<SurveyRoute.DedicatedCandidate> task) {
        this.start = start;
        this.goal = goal;
        this.config = config;
        this.task = task;
        cursor = start;
    }

    static LiveGpuRoute submit(MapSnapshot.Volume survey, Node start, Goal goal, Config config, RouteExclusions exclusions) {
        if (survey == null || !survey.hasRouteFloors() || start.y16() % 16 != 0) return null;
        Node end = new Node(goal.x(), goal.y16() == null ? start.y16() : goal.y16(), goal.z());
        if (end.y16() % 16 != 0 || start.equals(end) || !inRange(start, end, config)) return null;
        try {
            var future = WORKER.submit(() -> SurveyRoute.tryDedicatedCandidate(survey, start, end, exclusions));
            return new LiveGpuRoute(start, goal, config, future);
        } catch (RejectedExecutionException busy) {
            return null;
        }
    }

    SurveyRoute.DedicatedCandidate candidate() { return candidate; }
    void cancel() { task.cancel(true); }

    Status advance(Terrain live, AdaptivePlanning.Slice budget, long optimalCostLimit, BooleanSupplier startConnected) {
        if (candidate == null) {
            if (!task.isDone()) return Status.PENDING;
            try {
                candidate = task.get();
            } catch (CancellationException | ExecutionException unavailable) {
                return Status.REJECTED;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Status.REJECTED;
            }
            if (candidate == null || candidate.steps().isEmpty() || candidate.steps().size() > config.maxVisited()
                || !goal.matches(candidate.steps().getLast().to())) return Status.REJECTED;
            minimumCost = candidate.steps().stream().mapToLong(Step::cost).sum();
        }

        // Quick supplies an unlimited bound. Optimal lets A* keep searching for any cheaper route.
        if (minimumCost > optimalCostLimit) return Status.PENDING;

        long began = System.nanoTime();
        live.beginSearchSlice();
        try {
            if (checked == 0 && !hasFloor(live, start)) return Status.REJECTED;
            int checks = 0;
            while (checked < candidate.steps().size() && checks < budget.nodes()) {
                if (checks > 0 && System.nanoTime() - began >= budget.nanos()) return Status.VERIFYING;
                Step step = candidate.steps().get(checked);
                if (!step.from().equals(cursor) || !inRange(start, step.to(), config) || !compatible(step, config)
                    || !hasFloor(live, step.to()) || !live.canTraverse(step.from(), step.to(), step.move())
                    || !diagonalSidesClear(live, step)) return Status.REJECTED;
                cursor = step.to();
                verifiedCost += (long) step.cost() + Math.max(0, live.transitionPenalty(step.from(), step.to(), step.move()));
                checked++;
                checks++;
            }
            if (checked < candidate.steps().size()) return Status.VERIFYING;
            if (verifiedCost > optimalCostLimit) return Status.PENDING;
            return live.canFinish(cursor) && startConnected.getAsBoolean() ? Status.READY : Status.REJECTED;
        } finally {
            live.endSearchSlice();
        }
    }

    private static boolean hasFloor(Terrain live, Node node) {
        return live.floors(node.x(), node.z(), node.y16(), node.y16()).contains(node);
    }

    private static boolean inRange(Node start, Node target, Config config) {
        return Math.abs((long) target.x() - start.x()) <= config.horizontalRange()
            && Math.abs((long) target.z() - start.z()) <= config.horizontalRange();
    }

    private static boolean compatible(Step step, Config config) {
        int dx = Math.abs(step.from().x() - step.to().x());
        int dz = Math.abs(step.from().z() - step.to().z());
        int rise = step.to().y16() - step.from().y16();
        if (dx > 1 || dz > 1 || dx + dz == 0) return false;
        if (dx + dz == 2) return config.diagonals() && step.move() == Move.WALK && rise == 0;
        return switch (step.move()) {
            case WALK -> Math.abs(rise) <= 16;
            case JUMP -> config.jumping() && rise > 0 && rise <= 16;
            case DROP -> rise < 0 && -rise <= config.maxDrop() * 16;
            case CLIMB, BUBBLE -> false;
        };
    }

    private static boolean diagonalSidesClear(Terrain live, Step step) {
        Node from = step.from(), to = step.to();
        if (from.x() == to.x() || from.z() == to.z()) return true;
        Node xSide = new Node(to.x(), from.y16(), from.z()), zSide = new Node(from.x(), from.y16(), to.z());
        return hasFloor(live, xSide) && live.canTraverse(from, xSide, Move.WALK)
            && hasFloor(live, zSide) && live.canTraverse(from, zSide, Move.WALK);
    }
}
