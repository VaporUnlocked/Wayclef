package dev.soprano.path;

import dev.soprano.path.RouteExclusions.Point;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Retains recent standing positions for one client, sampled and queried on the client thread. */
public final class SafeReturnCheckpoints {
    private static final int REQUIRED_STABLE_TICKS = 3;
    private static final int MAX_CHECKPOINTS_PER_SCOPE = 32;
    private static final double STANDING_DRIFT = 0.125;
    private static final double CHECKPOINT_SPACING = 0.5;

    public record Scope(String server, String world, String dimension) {
        public Scope {
            server = requireKey(server, "server");
            world = requireKey(world, "world");
            dimension = requireKey(dimension, "dimension");
        }
    }

    /** Results of the caller's current terrain and player checks at a standing position. */
    public record Observation(Point feet, boolean grounded, boolean stableSupport, boolean headroom,
                              boolean hazardClearance, boolean exitAvailable) {
        public Observation {
            Objects.requireNonNull(feet, "feet");
        }

        public boolean safe() {
            return grounded && stableSupport && headroom && hazardClearance && exitAvailable;
        }
    }

    public record Checkpoint(Scope scope, Point feet, long recordedTick) {
        public Checkpoint {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(feet, "feet");
            requireTick(recordedTick);
        }
    }

    private final Map<Scope, ArrayDeque<Checkpoint>> history = new HashMap<>();
    private Scope sampledScope;
    private Point standingAnchor;
    private long lastTick;
    private int stableTicks;

    /** Records a checkpoint after consecutive safe ticks at a settled position. */
    public Optional<Checkpoint> observe(Scope scope, long tick, Observation observation) {
        Objects.requireNonNull(scope, "scope");
        requireTick(tick);
        Objects.requireNonNull(observation, "observation");

        boolean sameScope = scope.equals(sampledScope);
        if (sameScope && tick == lastTick) {
            if (!observation.safe()) clearStandingSample();
            return Optional.empty();
        }
        boolean consecutive = sameScope && tick > lastTick && tick - lastTick == 1;
        sampledScope = scope;
        lastTick = tick;
        if (!observation.safe()) {
            clearStandingSample();
            return Optional.empty();
        }

        Point feet = observation.feet();
        if (!consecutive || standingAnchor == null || !within(standingAnchor, feet, STANDING_DRIFT)) {
            standingAnchor = feet;
            stableTicks = 1;
        } else if (stableTicks < REQUIRED_STABLE_TICKS) {
            stableTicks++;
        } else {
            return Optional.empty();
        }

        if (stableTicks < REQUIRED_STABLE_TICKS) return Optional.empty();
        ArrayDeque<Checkpoint> checkpoints = history.computeIfAbsent(scope, ignored -> new ArrayDeque<>());
        Checkpoint latest = checkpoints.peekLast();
        if (latest != null && latest.feet().equals(feet)) return Optional.empty();
        checkpoints.removeIf(checkpoint -> within(checkpoint.feet(), feet, CHECKPOINT_SPACING));
        if (checkpoints.size() == MAX_CHECKPOINTS_PER_SCOPE) checkpoints.removeFirst();
        Checkpoint recorded = new Checkpoint(scope, feet, tick);
        checkpoints.addLast(recorded);
        return Optional.of(recorded);
    }

    /** Breaks the sample sequence after a disconnect, teleport, or sampling pause. */
    public void resetSampling() {
        sampledScope = null;
        clearStandingSample();
    }

    /** Returns a snapshot from oldest to newest. */
    public List<Checkpoint> checkpoints(Scope scope) {
        ArrayDeque<Checkpoint> checkpoints = history.get(Objects.requireNonNull(scope, "scope"));
        return checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    /**
     * Selects the newest checkpoint that passes live revalidation. Unavailable terrain should fail
     * validation. A failed check leaves the checkpoint available for a later visit.
     */
    public Optional<Checkpoint> mostRecentSafe(Scope scope, Predicate<Checkpoint> revalidate) {
        Objects.requireNonNull(revalidate, "revalidate");
        for (Checkpoint checkpoint : checkpoints(scope).reversed()) {
            if (revalidate.test(checkpoint)) return Optional.of(checkpoint);
        }
        return Optional.empty();
    }

    private void clearStandingSample() {
        standingAnchor = null;
        stableTicks = 0;
    }

    private static boolean within(Point first, Point second, double distance) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz <= distance * distance;
    }

    private static String requireKey(String key, String name) {
        String value = Objects.requireNonNull(key, name).strip();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + name + " identity");
        return value;
    }

    private static void requireTick(long tick) {
        if (tick < 0) throw new IllegalArgumentException("Sample tick must be nonnegative");
    }
}
