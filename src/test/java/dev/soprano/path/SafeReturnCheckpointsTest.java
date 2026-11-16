package dev.soprano.path;

import dev.soprano.path.RouteExclusions.Point;
import dev.soprano.path.SafeReturnCheckpoints.Checkpoint;
import dev.soprano.path.SafeReturnCheckpoints.Observation;
import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.util.ArrayList;
import java.util.List;

public final class SafeReturnCheckpointsTest {
    private static final Scope HOME = new Scope("server-a", "world-a", "overworld");
    private static final Point START = new Point(0.5, 64, 0.5);
    private static final Point NEXT = new Point(5.5, 64, 0.5);

    public static void main(String[] args) {
        recordsOnlyAfterSettling();
        rejectsUnsafeSamples();
        separatesScopesAndSessions();
        revalidatesAndFallsBack();
        retainsRecentDistinctPositions();
        System.out.println("Soprano safe return checkpoint checks passed");
    }

    private static void recordsOnlyAfterSettling() {
        var trail = new SafeReturnCheckpoints();
        check(trail.observe(HOME, 0, safe(START)).isEmpty(), "first tick is insufficient");
        check(trail.observe(HOME, 0, safe(START)).isEmpty(), "duplicate tick does not count");
        check(trail.observe(HOME, 1, safe(START)).isEmpty(), "second tick is insufficient");
        check(trail.observe(HOME, 3, safe(START)).isEmpty(), "missing tick restarts sampling");
        check(trail.observe(HOME, 4, safe(START)).isEmpty(), "still settling after gap");
        var shifted = new Point(0.55, 64, 0.5);
        check(trail.observe(HOME, 5, safe(shifted)).orElseThrow().equals(new Checkpoint(HOME, shifted, 5)),
            "third consecutive tick records the current feet position");
        check(trail.observe(HOME, 6, safe(shifted)).isEmpty(), "remaining still does not record duplicates");
        for (int i = 0; i < 8; i++) {
            check(trail.observe(HOME, 7 + i, safe(new Point(1 + 0.2 * i, 64, 0.5))).isEmpty(),
                "walking does not record an unsettled position");
        }
    }

    private static void rejectsUnsafeSamples() {
        var trail = new SafeReturnCheckpoints();
        List<Observation> unsafe = List.of(
            new Observation(START, false, true, true, true, true),
            new Observation(START, true, false, true, true, true),
            new Observation(START, true, true, false, true, true),
            new Observation(START, true, true, true, false, true),
            new Observation(START, true, true, true, true, false)
        );
        long tick = 0;
        for (Observation observation : unsafe) {
            check(trail.observe(HOME, tick++, safe(START)).isEmpty(), "first settled tick before unsafe observation");
            check(trail.observe(HOME, tick++, safe(START)).isEmpty(), "second settled tick before unsafe observation");
            check(trail.observe(HOME, tick++, observation).isEmpty(), "every safety condition is required");
        }
        check(trail.checkpoints(HOME).isEmpty(), "unsafe observations never create history");
        check(settle(trail, HOME, tick, START).feet().equals(START), "safe sampling resumes after an unsafe tick");
    }

    private static void separatesScopesAndSessions() {
        var trail = new SafeReturnCheckpoints();
        var otherServer = new Scope("server-b", "world-a", "overworld");
        var otherWorld = new Scope("server-a", "world-b", "overworld");
        var otherDimension = new Scope("server-a", "world-a", "nether");
        check(trail.observe(HOME, 0, safe(START)).isEmpty(), "first observation in home scope");
        check(trail.observe(HOME, 1, safe(START)).isEmpty(), "second observation in home scope");
        check(trail.observe(otherDimension, 2, safe(START)).isEmpty(), "sampling cannot carry between dimensions");
        var checkpoint = settle(trail, HOME, 3, START);
        for (Scope other : List.of(otherServer, otherWorld, otherDimension)) {
            check(trail.checkpoints(other).isEmpty(), "other scope has no checkpoints");
            check(trail.mostRecentSafe(other, ignored -> true).isEmpty(), "fallback stays in the requested scope");
        }
        trail.observe(HOME, 6, safe(NEXT));
        trail.observe(HOME, 7, safe(NEXT));
        trail.resetSampling();
        check(trail.observe(HOME, 8, safe(NEXT)).isEmpty(), "reconnect resets partial sampling");
        check(trail.checkpoints(HOME).equals(List.of(checkpoint)), "reconnect keeps this scope's recorded checkpoints");
    }

    private static void revalidatesAndFallsBack() {
        var trail = new SafeReturnCheckpoints();
        var first = settle(trail, HOME, 0, START);
        var second = settle(trail, HOME, 3, NEXT);
        var inspected = new ArrayList<Checkpoint>();
        check(trail.mostRecentSafe(HOME, checkpoint -> {
            inspected.add(checkpoint);
            return checkpoint.equals(first);
        }).orElseThrow().equals(first), "stale newest point falls back to live safe point");
        check(inspected.equals(List.of(second, first)), "checkpoints revalidated newest first");
        check(trail.mostRecentSafe(HOME, ignored -> false).isEmpty(), "no result when all checks fail");
        check(trail.mostRecentSafe(HOME, ignored -> true).orElseThrow().equals(second),
            "a temporarily unavailable checkpoint can be revalidated later");
    }

    private static void retainsRecentDistinctPositions() {
        var trail = new SafeReturnCheckpoints();
        for (int i = 0; i < 40; i++) settle(trail, HOME, i * 3L, new Point(2 * i, 64, 0.5));
        List<Checkpoint> checkpoints = trail.checkpoints(HOME);
        check(checkpoints.size() == 32, "history is bounded per scope");
        check(checkpoints.getFirst().feet().x() == 16, "oldest positions evicted first");
        check(checkpoints.getLast().feet().x() == 78, "most recent position retained");
        var updated = settle(trail, HOME, 120, new Point(78.4, 64, 0.5));
        check(trail.checkpoints(HOME).getLast().equals(updated), "nearby settled movement refreshes the position");
        check(trail.checkpoints(HOME).size() == 32, "refresh does not evict another position");
        check(checkpoints.getLast().feet().x() == 78, "history snapshots are independent of new samples");
    }

    private static Checkpoint settle(SafeReturnCheckpoints trail, Scope scope, long firstTick, Point feet) {
        check(trail.observe(scope, firstTick, safe(feet)).isEmpty(), "settle starts with one tick");
        check(trail.observe(scope, firstTick + 1, safe(feet)).isEmpty(), "settle waits for three ticks");
        return trail.observe(scope, firstTick + 2, safe(feet)).orElseThrow();
    }

    private static Observation safe(Point feet) {
        return new Observation(feet, true, true, true, true, true);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
