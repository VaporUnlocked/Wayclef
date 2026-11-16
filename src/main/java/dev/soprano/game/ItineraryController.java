package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.game.transport.MountRoute;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.game.transport.StriderRoute;
import dev.soprano.game.transport.CushionRoute;
import dev.soprano.path.Goal;
import dev.soprano.path.ItineraryStore;
import dev.soprano.path.ItineraryStore.Phase;
import dev.soprano.path.ItineraryStore.Progress;
import dev.soprano.path.RouteItinerary;
import dev.soprano.task.TaskHistory.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Owns the active trip on the client thread and asks Navigation to plan each leg on live terrain. */
public final class ItineraryController implements ClientEvents.Listener {
    private static final ItineraryController INSTANCE = new ItineraryController();
    private static final int PORTAL_TIMEOUT = 20 * 15;

    private final Object owner = new Object();
    private final Navigation navigation = Navigation.get();
    private Scope loadedScope;
    private ItineraryStore store;
    private Progress progress;
    private Navigation.Options options;
    private ClientLevel tripLevel;
    private LocalPlayer tripPlayer;
    private boolean requestedLeg;
    private boolean detouring;
    private boolean approachingPortal;
    private boolean waitingForTransfer;
    private boolean transferCuePlayed;
    private boolean triedBoarding;
    private boolean ridingAfterBoarding;
    private boolean returningFromRide;
    private TripBoarding boarding;
    private boolean groundTransferAttempted;
    private boolean groundTransferLeg;
    private String transferFailure;
    private String walkingFailure;
    private int portalTicks;
    private String detail = "No trip in progress.";

    private ItineraryController() { }

    public static ItineraryController get() { return INSTANCE; }

    public void initialize() { ClientEvents.subscribe(this); }

    public static String currentDimension() {
        var level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("Join a world before editing or running a trip.");
        return level.dimension().identifier().toString();
    }

    public List<String> list() {
        return withStore(ItineraryStore::list);
    }

    public RouteItinerary load(String name) {
        return withStore(current -> current.load(name)
            .orElseThrow(() -> new IllegalArgumentException("No saved trip named " + name + ".")));
    }

    public void save(RouteItinerary trip) {
        withStore(current -> {
            current.save(trip);
            return null;
        });
    }

    public boolean delete(String name) { return withStore(current -> current.delete(name)); }

    public boolean active() { return options != null; }

    public boolean canResume() {
        Scope scope = currentScope();
        if (scope == null) return false;
        refreshScope(scope);
        return !active() && progress != null;
    }

    public boolean canDetour() {
        Scope scope = currentScope();
        if (scope == null) return false;
        refreshScope(scope);
        return progress != null && !detouring && progress.phase() != Phase.PORTAL;
    }

    public String statusText() {
        if (progress == null) return detail;
        return progress.trip().name() + " · Stop " + (progress.cursor().index() + 1) + "/"
            + progress.trip().stops().size() + " · " + detail;
    }

    public void start(String name, Navigation.Options requestedOptions) {
        start(load(name), requestedOptions);
    }

    public void start(RouteItinerary trip, Navigation.Options requestedOptions) {
        Objects.requireNonNull(trip, "trip").validateTravel();
        var first = new Progress(trip, new RouteItinerary.Cursor(0, 1), Phase.TRAVEL, 0);
        prepare(first, requestedOptions);
        persist(first);
        activate(requestedOptions);
    }

    public void resume(Navigation.Options requestedOptions) {
        requireScope();
        if (progress == null) throw new IllegalStateException("There is no unfinished trip in this world.");
        prepare(progress, requestedOptions);
        activate(requestedOptions);
    }

    public void detour(Goal goal, Navigation.Options requestedOptions) {
        requireScope();
        if (!canDetour()) throw new IllegalStateException("Start or resume a trip outside a portal transition before taking a detour.");
        String expected = expectedDimension(progress);
        if (!expected.equals(currentDimension())) {
            throw new IllegalStateException("Return to " + expected + " before taking this detour.");
        }
        if (navigation.movementReservedByOther(owner)) throw new IllegalStateException("Another tool owns navigation.");
        if (active()) persist(progress);
        if (!navigation.requestOwned(owner, goal, requestedOptions)) throw new IllegalStateException("Another tool owns navigation.");
        activate(requestedOptions);
        detouring = requestedLeg = true;
        detail = "Detour · " + navigation.ownedStatus(owner);
    }

    public void pause() {
        if (active()) pauseFor("Paused. Resume to replan from your current position.");
    }

    public void cancel() {
        requireScope();
        releaseLeg();
        deactivate();
        clearProgress("Trip stopped.");
    }

    private void prepare(Progress next, Navigation.Options requestedOptions) {
        Objects.requireNonNull(requestedOptions, "navigation settings");
        requireScope();
        if (active()) throw new IllegalStateException("Pause or stop the current trip first.");
        if (navigation.movementReservedByOther(owner)) throw new IllegalStateException("Another tool owns navigation.");
        String dimension = currentDimension();
        String expected = expectedDimension(next);
        boolean pendingExit = next.phase() == Phase.PORTAL && next.stop().portal().dimension().equals(dimension);
        if (!expected.equals(dimension) && !pendingExit) {
            throw new IllegalStateException("Return to " + expected + " before continuing this trip.");
        }
    }

    private static String expectedDimension(Progress next) {
        return next.phase() == Phase.WAIT ? next.stop().arrivalDimension() : next.stop().dimension();
    }

    private void activate(Navigation.Options requestedOptions) {
        options = requestedOptions;
        requestedLeg = approachingPortal = false;
        waitingForTransfer = false;
        transferCuePlayed = false;
        triedBoarding = ridingAfterBoarding = returningFromRide = false;
        boarding = null;
        groundTransferAttempted = groundTransferLeg = false;
        transferFailure = walkingFailure = null;
        portalTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        tripLevel = mc.level;
        tripPlayer = mc.player;
        detail = "Checking the next stop against live terrain.";
    }

    @Override
    public void tickPre() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (progress.phase() == Phase.PORTAL && mc.gui.screen() instanceof LevelLoadingScreen) {
                if (++portalTicks >= PORTAL_TIMEOUT) pauseFor("Portal transition timed out. Check the arrival before resuming.");
                return;
            }
            if (!Objects.equals(loadedScope, currentScope()) || mc.player == null || !mc.player.isAlive()) {
                pauseFor("The world or player changed. Resume explicitly after returning.");
                return;
            }
            if (!Navigation.allowedScreen(mc.gui.screen())) {
                pauseFor("A game screen opened. Resume the trip when ready.");
                return;
            }
            if (mc.gui.screen() == null && (!mc.getWindow().isFocused() || !mc.mouseHandler.isMouseGrabbed())) {
                pauseFor("Minecraft lost focus. Resume the trip when ready.");
                return;
            }
            if (navigation.movementReservedByOther(owner)) {
                pauseFor("Another tool took control of movement. Resume the trip when ready.");
                return;
            }
            String conflict = SopranoClient.movementConflictReason();
            if (conflict != null && (detouring || progress.phase() != Phase.WAIT)) {
                pauseFor(conflict);
                return;
            }
            if (detouring) {
                if (tripLevel != mc.level || tripPlayer != mc.player) pauseFor("World or player changed during the detour.");
                else tickDetour();
            } else {
                advance(mc);
            }
        } catch (IllegalArgumentException | IllegalStateException problem) {
            pauseFor(problem.getMessage());
        }
    }

    private void tickDetour() {
        String state = navigation.ownedState(owner);
        String status = navigation.ownedStatus(owner);
        switch (state) {
            case "ARRIVED", "FAILED", "PAUSED", "STOPPED", "IDLE" -> {
                releaseLeg();
                deactivate();
                detail = (state.equals("ARRIVED") ? "Detour complete." : "Detour ended. " + status)
                    + " Resume progress to continue the trip.";
                SopranoClient.feedback(detail);
            }
            default -> detail = "Detour · " + status;
        }
    }

    private void advance(Minecraft mc) {
        var stop = progress.stop();
        String dimension = currentDimension();
        if (progress.phase() == Phase.PORTAL && !stop.dimension().equals(dimension)) {
            if (!PortalEntry.arrived(stop.portal(), dimension, mc.player)) {
                pauseFor("Portal arrival did not match the expected dimension and position. Check the destination.");
                return;
            }
            releaseLeg();
            tripLevel = mc.level;
            tripPlayer = mc.player;
            arrive();
            return;
        }
        String expected = progress.phase() == Phase.WAIT ? stop.arrivalDimension() : stop.dimension();
        if (!expected.equals(dimension) || tripLevel != mc.level || tripPlayer != mc.player) {
            pauseFor("The world or player changed. Resume explicitly to replan this stop.");
            return;
        }
        if (progress.phase() == Phase.WAIT) {
            tickWait();
        } else if (!requestedLeg) {
            startLeg(mc, stop);
        } else {
            tickLeg(mc, stop);
        }
    }

    private void startLeg(Minecraft mc, RouteItinerary.Stop stop) {
        TransportMode mode = requestedMode(stop);
        if (boarding != null && !ridingAfterBoarding) {
            switch (boarding.advance(mc)) {
                case APPROACHING -> {
                    detail = boarding.detail();
                    return;
                }
                case BOARDED -> ridingAfterBoarding = true;
                case UNAVAILABLE -> {
                    boarding = null;
                    if (mode == TransportMode.AUTO && !allowsBlockEdits()) {
                        pauseFor(walkingFailure + " No reachable transport could complete the stop.");
                        return;
                    }
                }
            }
        }
        String transfer = transferInstruction(mc.player, mode);
        if (transfer != null) {
            if (mode == TransportMode.WALK && stop.portal() == null && !groundTransferAttempted
                && (mc.player.getControlledVehicle() instanceof AbstractBoat || MountRoute.supports(mc.player))) {
                groundTransferAttempted = true;
                try {
                    if (navigation.requestGroundTransfer(owner, stop.goal(), options.withTransport(mode))) {
                        groundTransferLeg = requestedLeg = true;
                        waitingForTransfer = false;
                        detail = navigation.ownedStatus(owner);
                        return;
                    }
                } catch (IllegalStateException problem) {
                    transferFailure = problem.getMessage();
                }
            }
            waitingForTransfer = true;
            if (!triedBoarding && mc.gui.screen() == null && (mode == TransportMode.BOAT || mode == TransportMode.MOUNT)
                && TripBoarding.canStart(mc.player)) {
                startBoarding(mc, mode);
                return;
            }
            detail = transferFailure == null ? transfer : transferFailure + " " + transfer;
            if (!transferCuePlayed) {
                RouteAudio.signal(RouteAudio.Cue.INPUT_NEEDED);
                transferCuePlayed = true;
            }
            return;
        }
        waitingForTransfer = false;
        Navigation.Options legOptions = options.withTransport(mode);
        Goal goal = stop.goal();
        if (canTryAutomaticBoarding(mc.player, stop)) legOptions = legOptions.withoutBlockEdits();
        var portalCells = new HashSet<>(PortalEntry.touching(mc.level, mc.player));
        approachingPortal = stop.portal() != null && !mc.level.getChunkSource().hasChunk(goal.x() >> 4, goal.z() >> 4);
        if (approachingPortal) {
            goal = new Goal(goal.x(), null, goal.z(), 2);
        } else if (stop.portal() != null) {
            PortalEntry portal = PortalEntry.find(mc.level, goal);
            goal = portal.goal();
            portalCells.addAll(portal.cells());
            if (progress.phase() != Phase.PORTAL) persist(new Progress(progress.trip(), progress.cursor(), Phase.PORTAL, 0));
        }
        try {
            boolean accepted = portalCells.isEmpty()
                ? navigation.requestTrip(owner, goal, legOptions)
                : navigation.requestPortal(owner, goal, legOptions, portalCells);
            if (!accepted) throw new IllegalStateException("Another tool owns navigation.");
        } catch (IllegalStateException failure) {
            if (recoverBoarding(stop, failure.getMessage())) return;
            throw failure;
        }
        requestedLeg = true;
        portalTicks = 0;
        detail = navigation.ownedStatus(owner);
    }

    private boolean canTryAutomaticBoarding(LocalPlayer player, RouteItinerary.Stop stop) {
        Goal goal = stop.goal();
        return !triedBoarding && stop.portal() == null && requestedMode(stop) == TransportMode.AUTO
            && !goal.skyExposed() && goal.sightY() == null && goal.heightBand() == null && goal.bounds() == null
            && TripBoarding.canStart(player);
    }

    private boolean allowsBlockEdits() {
        return options.permissions().breakLimit() != 0 || options.permissions().placeLimit() != 0;
    }

    private void startBoarding(Minecraft mc, TransportMode mode) {
        triedBoarding = true;
        waitingForTransfer = false;
        boarding = new TripBoarding(mc, navigation, owner, mode, options);
        detail = boarding.detail();
    }

    private boolean recoverBoarding(RouteItinerary.Stop stop, String failure) {
        if (!ridingAfterBoarding || returningFromRide || requestedMode(stop) != TransportMode.AUTO) return false;
        try {
            if (!navigation.requestGroundTransfer(owner, boarding.approach().footing(), options.withoutBlockEdits())) return false;
        } catch (IllegalStateException unavailable) {
            return false;
        }
        returningFromRide = requestedLeg = true;
        detail = failure + " Returning to safe footing to check another vehicle.";
        return true;
    }

    private TransportMode requestedMode(RouteItinerary.Stop stop) {
        if (stop.portal() != null) return TransportMode.WALK;
        return stop.transport() == null ? options.transportMode() : stop.transport();
    }

    private static String transferInstruction(LocalPlayer player, TransportMode mode) {
        return switch (mode) {
            case AUTO -> null;
            case SWIM -> !player.isPassenger() && player.isInWater() ? null : "Enter open water for the next swim.";
            case CUSHION -> CushionRoute.readiness(player);
            case WALK -> !player.isPassenger() && !player.isInWater() && (player.onGround() || player.onClimbable())
                ? null : "Reach safe footing on foot to start the next walking leg.";
            case BOAT -> player.getControlledVehicle() instanceof AbstractBoat
                ? null : "Take control of a boat to start the next water leg.";
            case ELYTRA -> player.isFallFlying() ? null : "Begin a safe Elytra glide to start the next flight leg.";
            case MOUNT -> MountRoute.supports(player) ? null : "Mount a steerable saddle animal for the next leg.";
            case STRIDER -> StriderRoute.readiness(player);
            case RAIL -> player.getVehicle() instanceof Minecart || !player.isPassenger() && player.onGround()
                ? null : "Stand by a rideable minecart or board one for the next rail leg.";
        };
    }

    private void tickLeg(Minecraft mc, RouteItinerary.Stop stop) {
        String state = navigation.ownedState(owner);
        detail = navigation.ownedStatus(owner);
        switch (state) {
            case "ARRIVED" -> {
                if (returningFromRide) {
                    returningFromRide = ridingAfterBoarding = requestedLeg = false;
                    boarding.rejectVehicle();
                    detail = "Checking another available vehicle.";
                } else if (approachingPortal) {
                    releaseLeg();
                    if (!mc.level.getChunkSource().hasChunk(stop.goal().x() >> 4, stop.goal().z() >> 4)) {
                        pauseFor("The marked portal's chunk is still unavailable. Move closer and resume.");
                    }
                } else if (progress.phase() == Phase.PORTAL) {
                    if (PortalEntry.touching(mc.level, mc.player).isEmpty()) {
                        pauseFor("The marked portal no longer surrounds the player. Check its entry and resume.");
                    } else if (++portalTicks >= PORTAL_TIMEOUT) {
                        pauseFor("The portal did not complete the expected transition.");
                    } else {
                        detail = "Waiting for the server to transfer through the marked portal.";
                    }
                } else {
                    releaseLeg();
                    arrive();
                }
            }
            case "FAILED" -> {
                if (recoverBoarding(stop, detail)) return;
                if (canTryAutomaticBoarding(mc.player, stop)) {
                    walkingFailure = detail;
                    navigation.cancelOwned(owner);
                    requestedLeg = false;
                    startBoarding(mc, TransportMode.AUTO);
                } else if (groundTransferLeg && mc.player.isPassenger()) {
                    String failure = detail;
                    releaseLeg();
                    groundTransferAttempted = true;
                    transferFailure = failure;
                    waitingForTransfer = true;
                } else {
                    pauseFor(detail);
                }
            }
            case "PAUSED", "STOPPED", "IDLE" -> pauseFor(detail);
            default -> { }
        }
    }

    private void arrive() {
        int wait = progress.stop().waitSeconds() * 20;
        if (wait == 0) finishStop();
        else persist(new Progress(progress.trip(), progress.cursor(), Phase.WAIT, wait));
    }

    private void tickWait() {
        int remaining = progress.waitTicks() - 1;
        if (remaining <= 0) {
            finishStop();
            return;
        }
        progress = new Progress(progress.trip(), progress.cursor(), Phase.WAIT, remaining);
        if (remaining % 20 == 0) persist(progress);
        detail = "Waiting " + ((remaining + 19) / 20) + " seconds at this stop.";
    }

    private void finishStop() {
        var next = progress.trip().after(progress.cursor());
        if (next == null) {
            deactivate();
            clearProgress("Trip completed.");
        } else {
            persist(new Progress(progress.trip(), next, Phase.TRAVEL, 0));
            detail = "Checking the next stop against live terrain.";
        }
    }

    @Override
    public void disconnected() {
        if (active()) pauseFor("Disconnected. Resume the unfinished trip after joining this world.");
        loadedScope = null;
        store = null;
        progress = null;
    }

    @Override
    public void keyInput(KeyEvent input, ClientEvents.Action action) {
        Minecraft mc = Minecraft.getInstance();
        if (!active() || action == ClientEvents.Action.RELEASE || mc.gui.screen() != null) return;
        if (boarding == null && !detouring && (waitingForTransfer || !requestedLeg && progress.phase() != Phase.WAIT && mc.player != null
            && transferInstruction(mc.player, requestedMode(progress.stop())) != null)) return;
        if (SopranoClient.manualMovementKey(mc.options, input)) {
            pauseFor("You took control of movement. Resume to replan this stop.");
        }
    }

    private void pauseFor(String reason) {
        boolean navigationAlreadyPaused = requestedLeg && "PAUSED".equals(navigation.ownedState(owner));
        releaseLeg();
        deactivate();
        detail = reason;
        if (!navigationAlreadyPaused) RouteAudio.signal(RouteAudio.Cue.PAUSED);
        if (progress == null) return;
        try {
            persist(progress);
        } catch (IllegalStateException failure) {
            detail = reason + " " + failure.getMessage();
        }
    }

    private void releaseLeg() {
        if (requestedLeg || boarding != null) navigation.cancelOwned(owner);
        requestedLeg = approachingPortal = false;
        waitingForTransfer = false;
        transferCuePlayed = false;
        triedBoarding = ridingAfterBoarding = returningFromRide = false;
        boarding = null;
        groundTransferAttempted = groundTransferLeg = false;
        transferFailure = walkingFailure = null;
        portalTicks = 0;
    }

    private void deactivate() {
        options = null;
        detouring = false;
        tripLevel = null;
        tripPlayer = null;
    }

    private void persist(Progress next) {
        try {
            store.saveProgress(next);
            progress = next;
        } catch (IOException failure) {
            throw storageFailure(failure);
        }
    }

    private void clearProgress(String message) {
        try {
            store.clearProgress();
            progress = null;
            detail = message;
        } catch (IOException failure) {
            throw storageFailure(failure);
        }
    }

    private <T> T withStore(StoreAction<T> action) {
        requireScope();
        try {
            return action.run(store);
        } catch (IOException failure) {
            throw storageFailure(failure);
        }
    }

    private void requireScope() {
        Scope scope = currentScope();
        if (scope == null) throw new IllegalStateException("Join a named Minecraft world or server to use trips.");
        refreshScope(scope);
    }

    private void refreshScope(Scope scope) {
        if (scope.equals(loadedScope)) return;
        if (active()) pauseFor("World changed. The previous trip was paused.");
        var current = new ItineraryStore(Minecraft.getInstance().gameDirectory.toPath().resolve("soprano/trips"), scope);
        loadedScope = scope;
        store = current;
        try {
            progress = current.progress().orElse(null);
        } catch (IOException failure) {
            progress = null;
            detail = "Could not read saved trip progress. Open a saved trip to start over.";
            SopranoClient.LOG.warn("Soprano could not restore trip progress", failure);
            return;
        }
        detail = progress == null ? "No trip in progress." : "Unfinished trip. Resume to replan the remaining stops.";
    }

    private static Scope currentScope() {
        var scope = Navigation.currentScope();
        return scope == null ? null : new Scope(scope.server(), scope.world());
    }

    private static IllegalStateException storageFailure(IOException failure) {
        SopranoClient.LOG.warn("Soprano trip storage failed", failure);
        return new IllegalStateException("Could not read or save trip progress.", failure);
    }

    @FunctionalInterface private interface StoreAction<T> { T run(ItineraryStore current) throws IOException; }
}
