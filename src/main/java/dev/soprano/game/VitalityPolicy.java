package dev.soprano.game;

/** User limits for starting and continuing automatic travel. */
public final class VitalityPolicy {
    private VitalityPolicy() { }

    public record Minimums(int healthPoints, int foodLevel) {
        public static final Minimums DISABLED = new Minimums(0, 0);

        public Minimums {
            if (healthPoints < 0 || healthPoints > 2048 || foodLevel < 0 || foodLevel > 20)
                throw new IllegalArgumentException("Health minimum must be 0–2048 points and food minimum 0–20.");
        }

        public String pauseReason(float health, int food) {
            boolean lowHealth = healthPoints > 0 && health < healthPoints;
            boolean lowFood = foodLevel > 0 && food < foodLevel;
            if (lowHealth && lowFood) return "Health and food are below your travel minimums.";
            if (lowHealth) return "Health is below your travel minimum of " + healthPoints + " points.";
            if (lowFood) return "Food is below your travel minimum of " + foodLevel + ".";
            return null;
        }
    }
}
