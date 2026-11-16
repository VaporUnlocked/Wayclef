package dev.soprano.game;

import dev.soprano.game.transport.TransportMode;
import dev.soprano.game.transport.VehicleBoarding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;

/** Walks to a checked boarding position and waits for the actual passenger update. */
final class TripBoarding {
    enum Result { APPROACHING, BOARDED, UNAVAILABLE }

    private static final int CONFIRM_TIMEOUT = 40;
    private final Navigation navigation;
    private final Object owner;
    private final Navigation.Options options;
    private final ArrayDeque<VehicleBoarding.Approach> remaining;
    private VehicleBoarding.Approach approach;
    private int waitTicks;
    private String detail = "Looking for a boardable vehicle.";

    TripBoarding(Minecraft mc, Navigation navigation, Object owner, TransportMode mode, Navigation.Options options) {
        this.navigation = navigation;
        this.owner = owner;
        this.options = options;
        remaining = new ArrayDeque<>(VehicleBoarding.approaches(mc.level, mc.player, mode, options));
    }

    static boolean canStart(LocalPlayer player) {
        return !player.isPassenger() && !player.isInLiquid() && player.onGround() && !player.isShiftKeyDown()
            && !player.isUsingItem() && !player.isSpectator();
    }

    String detail() { return detail; }
    VehicleBoarding.Approach approach() { return approach; }

    Result advance(Minecraft mc) {
        if (mc.player.isPassenger()) {
            if (approach != null && mc.player.getControlledVehicle() == approach.vehicle()) return Result.BOARDED;
            throw new IllegalStateException("The player boarded a different vehicle. Resume when ready.");
        }
        if (waitTicks > 0) {
            if (--waitTicks == 0) rejectVehicle();
            else detail = "Waiting for the server to confirm boarding.";
            return Result.APPROACHING;
        }
        if (approach == null) {
            approach = remaining.pollFirst();
            if (approach == null) return Result.UNAVAILABLE;
            if (VehicleBoarding.tryBoard(mc, approach, options.avoidMobs())) {
                waitTicks = CONFIRM_TIMEOUT;
                detail = "Waiting for the server to confirm boarding.";
                return Result.APPROACHING;
            }
            if (navigation.requestTrip(owner, approach.footing(), options.withTransport(TransportMode.WALK).withoutBlockEdits())) {
                detail = "Walking to " + approach.vehicle().getName().getString() + ".";
            } else throw new IllegalStateException("Another tool owns navigation.");
            return Result.APPROACHING;
        }
        if (!approach.vehicle().isAlive() || approach.vehicle().level() != mc.level) {
            rejectVehicle();
            return Result.APPROACHING;
        }
        switch (navigation.ownedState(owner)) {
            case "ARRIVED" -> {
                if (mc.gui.screen() == null) {
                    if (VehicleBoarding.tryBoard(mc, approach, options.avoidMobs())) {
                        waitTicks = CONFIRM_TIMEOUT;
                        detail = "Waiting for the server to confirm boarding.";
                    } else abandonApproach();
                }
            }
            case "FAILED" -> abandonApproach();
            case "PAUSED", "STOPPED", "IDLE" -> throw new IllegalStateException(navigation.ownedStatus(owner));
            default -> detail = "Approaching " + approach.vehicle().getName().getString() + " · " + navigation.ownedStatus(owner);
        }
        return Result.APPROACHING;
    }

    /** An unsuccessful ride can return to dry ground before this candidate is skipped. */
    void rejectVehicle() {
        if (approach == null) return;
        var vehicle = approach.vehicle();
        remaining.removeIf(candidate -> candidate.vehicle() == vehicle);
        abandonApproach();
    }

    private void abandonApproach() {
        navigation.cancelOwned(owner);
        approach = null;
        waitTicks = 0;
    }
}
