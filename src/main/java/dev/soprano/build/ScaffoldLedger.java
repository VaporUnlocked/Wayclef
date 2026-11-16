package dev.soprano.build;

import dev.soprano.build.BuildPlan.Cell;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Tracks temporary blocks whose ownership was confirmed in the current world session. */
public final class ScaffoldLedger {
    /** A loaded block's canonical state and latest observed block-event revision. */
    public record Observation(String state, long revision) {
        public Observation {
            requireState(state);
            if (revision < 0) throw new IllegalArgumentException("Block revision cannot be negative");
        }

        public boolean isAir() {
            return ScaffoldLedger.isAir(state);
        }
    }

    public record Placement(Cell cell, Observation before, String intendedState) {
    }

    public record Removal(Cell cell, Observation ownedState) {
    }

    /** The live reach check and a safe exit checked against terrain with this block removed. */
    public record CleanupCheck(Cell cell, Optional<Observation> current, boolean reachable, boolean safeExit) {
        public CleanupCheck {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(current, "current");
        }
    }

    public enum CleanupStatus {
        READY,
        NOTHING_TO_REMOVE,
        ACTION_PENDING,
        TARGET_CHANGED,
        NOT_LOADED,
        STALE_OBSERVATION,
        OWNERSHIP_LOST,
        OUT_OF_REACH,
        EXIT_BLOCKED
    }

    public record CleanupAttempt(CleanupStatus status, Optional<Removal> removal) {
        private static CleanupAttempt blocked(CleanupStatus status) {
            return new CleanupAttempt(status, Optional.empty());
        }
    }

    private final LinkedHashMap<Cell, Observation> owned = new LinkedHashMap<>();
    private Placement pendingPlacement;
    private Removal pendingRemoval;

    /** Reserves the single in-flight action against a confirmed, previously air cell. */
    public synchronized Optional<Placement> beginPlacement(Cell cell, Observation before, String intendedState) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(before, "before");
        requireState(intendedState);
        if (actionPending() || !before.isAir() || isAir(intendedState) || owned.containsKey(cell)) return Optional.empty();
        pendingPlacement = new Placement(cell, before, intendedState);
        return Optional.of(pendingPlacement);
    }

    /**
     * Acknowledges the matching placement action. A generic block update alone does not prove
     * ownership. The adapter must associate the acknowledgement with this action and deliver it
     * before forwarding the same revision as a generic block update.
     */
    public synchronized boolean confirmOwnPlacement(Placement placement, Observation after) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(after, "after");
        if (pendingPlacement != placement || after.revision() <= placement.before().revision()) return false;
        pendingPlacement = null;
        if (!placement.intendedState().equals(after.state())) return false;
        owned.put(placement.cell(), after);
        return true;
    }

    /** Releases a failed or unconfirmed placement. A later acknowledgement cannot reclaim it. */
    public synchronized void abandonPlacement(Placement placement) {
        if (pendingPlacement == placement) pendingPlacement = null;
    }

    /** A newer unrelated event invalidates ownership, including a replacement with the same state. */
    public synchronized void observeBlockChange(Cell cell, Observation update) {
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(update, "update");
        if (pendingPlacement != null && pendingPlacement.cell().equals(cell)
            && invalidates(pendingPlacement.before(), update)) pendingPlacement = null;
        Observation expected = owned.get(cell);
        if (expected != null && invalidates(expected, update)) loseObservation(cell);
    }

    /** Drops ownership when the client loses its observation chain for a cell, such as chunk unload. */
    public synchronized void loseObservation(Cell cell) {
        Objects.requireNonNull(cell, "cell");
        owned.remove(cell);
        if (pendingPlacement != null && pendingPlacement.cell().equals(cell)) pendingPlacement = null;
        if (pendingRemoval != null && pendingRemoval.cell().equals(cell)) pendingRemoval = null;
    }

    /** Returns the newest confirmed block. Cleanup pauses here if this target is unsafe. */
    public synchronized Optional<Cell> nextCleanupCell() {
        Map.Entry<Cell, Observation> newest = owned.lastEntry();
        return newest == null ? Optional.empty() : Optional.of(newest.getKey());
    }

    public synchronized CleanupAttempt beginRemoval(CleanupCheck check) {
        Objects.requireNonNull(check, "check");
        if (actionPending()) return CleanupAttempt.blocked(CleanupStatus.ACTION_PENDING);
        Map.Entry<Cell, Observation> newest = owned.lastEntry();
        if (newest == null) return CleanupAttempt.blocked(CleanupStatus.NOTHING_TO_REMOVE);
        if (!newest.getKey().equals(check.cell())) return CleanupAttempt.blocked(CleanupStatus.TARGET_CHANGED);
        CleanupStatus status = validate(check, newest.getValue());
        if (status != CleanupStatus.READY) return CleanupAttempt.blocked(status);
        pendingRemoval = new Removal(newest.getKey(), newest.getValue());
        return new CleanupAttempt(CleanupStatus.READY, Optional.of(pendingRemoval));
    }

    /** Rechecks the proof immediately before dispatching the removal action. */
    public synchronized CleanupStatus recheckRemoval(Removal removal, CleanupCheck check) {
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(check, "check");
        if (pendingRemoval != removal || !removal.cell().equals(check.cell())) return CleanupStatus.TARGET_CHANGED;
        return validate(check, removal.ownedState());
    }

    /** Finishes the matching removal after a fresh, attributed update. */
    public synchronized boolean confirmOwnRemoval(Removal removal, Observation after) {
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(after, "after");
        if (pendingRemoval != removal || after.revision() <= removal.ownedState().revision()) return false;
        loseObservation(removal.cell());
        return after.isAir();
    }

    /**
     * Releases the matching failed removal so cleanup can be retried. Ownership is retained only
     * when a fresh client check proves the original observation chain is unchanged.
     */
    public synchronized boolean releaseFailedRemoval(Removal removal, Optional<Observation> current) {
        Objects.requireNonNull(removal, "removal");
        Objects.requireNonNull(current, "current");
        if (pendingRemoval != removal) return false;
        pendingRemoval = null;
        if (current.filter(removal.ownedState()::equals).isPresent()) return true;
        loseObservation(removal.cell());
        return false;
    }

    public synchronized int ownedCount() {
        return owned.size();
    }

    public synchronized Optional<Observation> ownedObservation(Cell cell) {
        return Optional.ofNullable(owned.get(cell));
    }

    public synchronized void loseChunk(int chunkX, int chunkZ) {
        owned.keySet().removeIf(cell -> inChunk(cell, chunkX, chunkZ));
        if (pendingPlacement != null && inChunk(pendingPlacement.cell(), chunkX, chunkZ)) pendingPlacement = null;
        if (pendingRemoval != null && inChunk(pendingRemoval.cell(), chunkX, chunkZ)) pendingRemoval = null;
    }

    private static boolean inChunk(Cell cell, int chunkX, int chunkZ) {
        return (cell.x() >> 4) == chunkX && (cell.z() >> 4) == chunkZ;
    }

    /** A new world session cannot reuse the previous session's ownership or in-flight tickets. */
    public synchronized void clear() {
        owned.clear();
        pendingPlacement = null;
        pendingRemoval = null;
    }

    private boolean actionPending() {
        return pendingPlacement != null || pendingRemoval != null;
    }

    private CleanupStatus validate(CleanupCheck check, Observation expected) {
        if (check.current().isEmpty()) return CleanupStatus.NOT_LOADED;
        Observation current = check.current().orElseThrow();
        if (current.revision() < expected.revision()) return CleanupStatus.STALE_OBSERVATION;
        if (!current.equals(expected)) {
            loseObservation(check.cell());
            return CleanupStatus.OWNERSHIP_LOST;
        }
        if (!check.reachable()) return CleanupStatus.OUT_OF_REACH;
        if (!check.safeExit()) return CleanupStatus.EXIT_BLOCKED;
        return CleanupStatus.READY;
    }

    private static boolean invalidates(Observation expected, Observation update) {
        return update.revision() >= expected.revision() && !update.equals(expected);
    }

    private static void requireState(String state) {
        if (state == null || state.isBlank()) throw new IllegalArgumentException("Expected a known block state");
    }

    private static boolean isAir(String state) {
        return state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air");
    }
}
