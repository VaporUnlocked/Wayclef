package dev.soprano.game.transport;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/** Supply checks for flight legs and collision-verified landings. */
public final class ElytraReservePolicy {
    // Vanilla Elytra cannot glide with one durability remaining.
    public static final int MINIMUM_GLIDING_DURABILITY = 2;
    public static final Reserves DEFAULT_RESERVES = new Reserves(3, 12);

    private static final Comparator<Landing> EARLIEST_LANDING =
        Comparator.comparingInt(Landing::ticksToTouchdown)
            .thenComparingInt(landing -> landing.cost().rockets())
            .thenComparingInt(landing -> landing.cost().durability())
            .thenComparingInt(Landing::x)
            .thenComparingInt(Landing::y)
            .thenComparingInt(Landing::z);

    private ElytraReservePolicy() { }

    public record Reserves(int rockets, int durability) {
        public Reserves {
            if (rockets < 0 || durability < MINIMUM_GLIDING_DURABILITY) {
                throw new IllegalArgumentException("Rocket reserve must be nonnegative and Elytra reserve must be at least two.");
            }
        }
    }

    public record Supplies(int rockets, int durability) {
        public Supplies {
            if (rockets < 0 || durability < 0) throw new IllegalArgumentException("Supplies cannot be negative.");
        }
    }

    public record Cost(int rockets, int durability) {
        public Cost {
            if (rockets < 0 || durability < 0) throw new IllegalArgumentException("Flight costs cannot be negative.");
        }

        /**
         * Rounds up for a wear tick that can arrive immediately. Use the actual wear period
         * and budget at the full wear rate, without relying on enchantment rolls.
         */
        public static Cost forFlight(int rockets, int ticks, int ticksPerWear) {
            if (ticks < 0 || ticksPerWear <= 0) throw new IllegalArgumentException("Invalid flight duration or wear period.");
            return new Cost(rockets, Math.ceilDiv(ticks, ticksPerWear));
        }
    }

    /** Cost and time from the position where this landing approach begins. */
    public record Landing(int x, int y, int z, int ticksToTouchdown, Cost cost, boolean confirmedTerrain) {
        public Landing {
            if (ticksToTouchdown < 0) throw new IllegalArgumentException("Touchdown time cannot be negative.");
            Objects.requireNonNull(cost, "cost");
        }
    }

    /**
     * Cost from the current position to the end of the proposed leg. The optional
     * exit landing is evaluated from that endpoint and is required for a void crossing.
     */
    public record Leg(Cost cost, boolean crossesVoid, Landing exitLanding) {
        public Leg {
            Objects.requireNonNull(cost, "cost");
        }
    }

    public enum Action { CONTINUE, LAND, HOLD_ON_GROUND, EMERGENCY }

    public enum Reason { READY, ROCKET_RESERVE, DURABILITY_RESERVE, NO_CONFIRMED_EXIT, LOW_VITALITY }

    /** A LAND decision supplies the landing to commit to and revalidate until touchdown. */
    public record Decision(Action action, Reason reason, Landing landing) { }

    /**
     * The caller supplies conservative costs and verifies landing terrain and reachability.
     * Immediate landings are approached from the current position, excluding the proposed leg.
     * Emergency means no verified diversion fits current supplies and calls for a fresh search.
     */
    public static Decision assess(Reserves reserves, Supplies supplies, Leg nextLeg,
                                  boolean airborne, Collection<Landing> immediateLandings) {
        Objects.requireNonNull(reserves, "reserves");
        Objects.requireNonNull(supplies, "supplies");
        Objects.requireNonNull(nextLeg, "nextLeg");
        Objects.requireNonNull(immediateLandings, "immediateLandings");
        Reason reason = reasonToDivert(reserves, supplies, nextLeg);
        if (reason == Reason.READY) return new Decision(Action.CONTINUE, reason, null);
        if (!airborne) return new Decision(Action.HOLD_ON_GROUND, reason, null);

        Landing best = null;
        for (Landing candidate : immediateLandings) {
            if (!candidate.confirmedTerrain() || !canFinish(supplies, candidate.cost())) continue;
            if (best == null || EARLIEST_LANDING.compare(candidate, best) < 0) best = candidate;
        }
        return best == null ? new Decision(Action.EMERGENCY, reason, null)
            : new Decision(Action.LAND, reason, best);
    }

    private static Reason reasonToDivert(Reserves reserves, Supplies supplies, Leg nextLeg) {
        if (supplies.durability() < reserves.durability()) return Reason.DURABILITY_RESERVE;
        if (supplies.rockets() < reserves.rockets()) return Reason.ROCKET_RESERVE;

        Landing exit = nextLeg.exitLanding();
        if (exit == null ? nextLeg.crossesVoid() : !exit.confirmedTerrain()) return Reason.NO_CONFIRMED_EXIT;

        long plannedWear = (long) nextLeg.cost().durability() + (exit == null ? 0 : exit.cost().durability());
        long plannedRockets = (long) nextLeg.cost().rockets() + (exit == null ? 0 : exit.cost().rockets());
        if (supplies.durability() - plannedWear < reserves.durability()) return Reason.DURABILITY_RESERVE;
        if (supplies.rockets() - plannedRockets < reserves.rockets()) return Reason.ROCKET_RESERVE;
        return Reason.READY;
    }

    static boolean canFinish(Supplies supplies, Cost cost) {
        return cost.rockets() <= supplies.rockets()
            && supplies.durability() - cost.durability() >= MINIMUM_GLIDING_DURABILITY;
    }
}
