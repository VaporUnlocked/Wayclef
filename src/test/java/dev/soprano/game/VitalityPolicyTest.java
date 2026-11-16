package dev.soprano.game;

public final class VitalityPolicyTest {
    public static void main(String[] args) {
        var limits = new VitalityPolicy.Minimums(8, 10);
        assert limits.pauseReason(8, 10) == null;
        assert limits.pauseReason(7.5f, 10).contains("Health");
        assert limits.pauseReason(8, 9).contains("Food");
        assert limits.pauseReason(4, 0).contains("Health and food");
        assert VitalityPolicy.Minimums.DISABLED.pauseReason(1, 0) == null;
        assert new VitalityPolicy.Minimums(0, 5).pauseReason(1, 5) == null;
        try {
            new VitalityPolicy.Minimums(8, 21);
            throw new AssertionError("Food cannot exceed 20");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Vitality minimums passed 7 checks.");
    }
}
