package dev.soprano.game;

public final class AdaptivePlanningTest {
    public static void main(String[] args) {
        var budget = new AdaptivePlanning();
        check(budget.slice(40, 2, false, 15, 120).equals(new AdaptivePlanning.Slice(40, 2_000_000)), "fixed mode uses configured caps");
        check(budget.slice(40, 2, true, 30, 120).equals(new AdaptivePlanning.Slice(20, 1_000_000)), "low frame rate reduces work");
        check(budget.slice(40, 2, true, 30, 30).equals(new AdaptivePlanning.Slice(40, 2_000_000)), "frame cap adjusts the target");
        check(budget.slice(40, 2, true, 0, 120).equals(new AdaptivePlanning.Slice(40, 2_000_000)), "unavailable frame data uses full caps");
        budget.observe(1_000_000, 4);
        check(budget.slice(40, 2, true, 120, 120).nodes() == 8, "observed work bounds node count");
        budget.observe(0, 0);
        check(budget.slice(40, 2, true, 120, 120).nodes() == 8, "empty samples leave the estimate intact");
        check(budget.slice(4, 1, true, 1, 120).equals(new AdaptivePlanning.Slice(1, 250_000)), "slow clients still make progress");
        budget.reset();
        check(budget.slice(40, 2, true, 120, 120).nodes() == 40, "session reset discards the estimate");
        System.out.println("Soprano adaptive planning checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
