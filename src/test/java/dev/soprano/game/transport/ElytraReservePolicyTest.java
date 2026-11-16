package dev.soprano.game.transport;

import java.util.List;

import dev.soprano.game.transport.ElytraReservePolicy.Action;
import dev.soprano.game.transport.ElytraReservePolicy.Cost;
import dev.soprano.game.transport.ElytraReservePolicy.Decision;
import dev.soprano.game.transport.ElytraReservePolicy.Landing;
import dev.soprano.game.transport.ElytraReservePolicy.Leg;
import dev.soprano.game.transport.ElytraReservePolicy.Reason;
import dev.soprano.game.transport.ElytraReservePolicy.Reserves;
import dev.soprano.game.transport.ElytraReservePolicy.Supplies;
import net.minecraft.world.phys.Vec3;

public final class ElytraReservePolicyTest {
    private static final Reserves RESERVES = new Reserves(3, 12);
    private static int checks;

    private ElytraReservePolicyTest() { }

    public static void main(String[] args) {
        Landing exit = landing(1, 40, 1, 2, true);
        Leg crossing = new Leg(new Cost(3, 5), true, exit);
        check(decide(new Supplies(7, 19), crossing, false).action() == Action.CONTINUE,
            "a confirmed exit fits with the reserves intact");
        check(decide(new Supplies(6, 19), crossing, false).reason() == Reason.ROCKET_RESERVE,
            "exit approach rockets count before committing");
        check(decide(new Supplies(7, 18), crossing, false).reason() == Reason.DURABILITY_RESERVE,
            "exit approach wear counts before committing");
        check(decide(new Supplies(99, 99), new Leg(new Cost(0, 0), true, null), false)
            .action() == Action.HOLD_ON_GROUND, "a void crossing requires a confirmed exit");
        Landing unknown = landing(2, 20, 0, 0, false);
        check(decide(new Supplies(99, 99), new Leg(new Cost(0, 0), true, unknown), false)
            .reason() == Reason.NO_CONFIRMED_EXIT, "an unknown exit cannot establish reachability");

        Landing consumesWing = landing(3, 10, 0, 4, true);
        Landing consumesRockets = landing(4, 12, 3, 0, true);
        Landing first = landing(5, 30, 2, 3, true);
        Landing later = landing(6, 50, 1, 0, true);
        Decision diversion = ElytraReservePolicy.assess(RESERVES, new Supplies(2, 5), crossing, true,
            List.of(later, unknown, consumesWing, first, consumesRockets));
        check(diversion.action() == Action.LAND && diversion.landing().equals(first),
            "the earliest viable landing can spend the reserve and retains two durability");
        Decision noLanding = ElytraReservePolicy.assess(RESERVES, new Supplies(2, 5), crossing, true,
            List.of(unknown, consumesWing, consumesRockets));
        check(noLanding.action() == Action.EMERGENCY && noLanding.landing() == null,
            "an unconfirmed, unaffordable or wing-disabling landing is skipped");
        check(decide(new Supplies(3, 1), crossing, true).action() == Action.EMERGENCY,
            "a tattered wing cannot complete a flight");
        check(Cost.forFlight(2, 21, 20).equals(new Cost(2, 2)), "wear rounds up");
        check(Cost.forFlight(0, Integer.MAX_VALUE, 20).durability() == 107374183, "wear rounding cannot overflow");
        check(Cost.forFlight(0, 0, 20).durability() == 0, "zero time has no wear");
        var flat = new ElytraFlightPhysics.Look(0, 0);
        Vec3 descent = ElytraFlightPhysics.velocity(new Vec3(0, -.25, 1.5), flat, .08, false);
        check(Math.abs(descent.y - -.23814) < 1e-5 && Math.abs(descent.z - 1.509057) < 1e-5,
            "flight forecasting accounts for lift, descent acceleration and drag");
        Vec3 unpowered = ElytraFlightPhysics.velocity(Vec3.ZERO, flat, .08, false);
        Vec3 powered = ElytraFlightPhysics.velocity(Vec3.ZERO, flat, .08, true);
        check(powered.z - unpowered.z > .8, "the rocket trajectory accounts for added speed before collision checks");
        Vec3 start = new Vec3(0, 75, 0);
        Vec3 end = new Vec3(0, 70, -15);
        var look = ElytraFlightPhysics.steer(start, new Vec3(0, -.15, -.7), start, end, end, start,
            false, new ElytraFlightPhysics.Look(179, 0));
        check(Math.abs(look.yaw() - 179) <= 14 && Math.abs(look.pitch()) <= 9,
            "forecast steering follows the same yaw and pitch rate limits as live flight");
        System.out.println("Soprano Elytra reserves passed " + checks + " checks.");
    }

    private static Landing landing(int x, int ticks, int rockets, int wear, boolean confirmed) {
        return new Landing(x, 64, 0, ticks, new Cost(rockets, wear), confirmed);
    }

    private static Decision decide(Supplies supplies, Leg leg, boolean airborne) {
        return ElytraReservePolicy.assess(RESERVES, supplies, leg, airborne, List.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
