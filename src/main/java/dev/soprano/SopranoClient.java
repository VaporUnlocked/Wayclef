package dev.soprano;

import dev.soprano.build.BlueprintStorage;
import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.EscapeController;
import dev.soprano.game.FollowController;
import dev.soprano.game.FootSurvey;
import dev.soprano.game.ItineraryController;
import dev.soprano.game.Navigation;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.game.StrongholdTracker;
import dev.soprano.game.WorldInteraction;
import dev.soprano.gpu.DiscreteCompute;
import dev.soprano.host.ClientEvents;
import dev.soprano.host.HostAccess;
import dev.soprano.inventory.StorageKnowledge;
import dev.soprano.map.MapAnnotations;
import dev.soprano.map.PortalTracker;
import dev.soprano.map.PortalLinks;
import dev.soprano.map.SurveyCache;
import dev.soprano.task.JobQueue;
import dev.soprano.ui.PlanningScreen;
import dev.soprano.ui.PlanningViewport;
import dev.soprano.ui.SopranoModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared client lifecycle. Meteor installs its host from its own addon entrypoint. */
public final class SopranoClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Soprano");

    private static HostAccess host = HostAccess.STANDALONE;
    private static SopranoModule module;
    private static PlanningScreen pendingScreen;
    private static boolean standaloneRequested;

    @Override
    public void onInitializeClient() {
        standaloneRequested = !FabricLoader.getInstance().isModLoaded("meteor-client");
    }

    public static SopranoModule initialize(HostAccess access) {
        if (module != null) return module;
        host = access;
        module = new SopranoModule();
        Navigation.get().initialize();
        EscapeController.get().initialize();
        FollowController.get().initialize();
        ReplaceActivity.get().initialize();
        BlueprintConstruction.get().initialize();
        ItineraryController.get().initialize();
        FootSurvey.get().initialize();
        StrongholdTracker.get().initialize();
        StorageKnowledge.initialize();
        JobQueue.get().initialize();
        return module;
    }

    public static HostAccess host() { return host; }
    public static boolean isMeteor() { return host.meteor(); }
    public static boolean ready() { return module != null; }
    public static SopranoModule module() { return module; }
    public static void navigationStarted() { host.navigationStarted(); }
    public static boolean controlsPlayer() {
        return module != null && (Navigation.get().movementInUse() || JobQueue.get().active()
            || BlueprintConstruction.get().active() || ReplaceActivity.get().active() || WorldInteraction.get().active()
            || EscapeController.get().active());
    }

    public static boolean manualMovementHeld(Options keys) {
        return !host.freecamActive() && (keys.keyUp.isDown() || keys.keyDown.isDown() || keys.keyLeft.isDown()
            || keys.keyRight.isDown() || keys.keyJump.isDown());
    }

    public static boolean manualMovementKey(Options keys, KeyEvent input) {
        return !host.freecamActive() && (keys.keyUp.matches(input) || keys.keyDown.matches(input)
            || keys.keyLeft.matches(input) || keys.keyRight.matches(input) || keys.keyJump.matches(input)
            || keys.keyShift.matches(input) || keys.keySprint.matches(input));
    }

    public static boolean manualInteractionHeld(Options keys) {
        return manualMovementHeld(keys) || keys.keyAttack.isDown() || keys.keyUse.isDown();
    }

    public static String movementConflictReason() {
        String controller = host.movementConflict();
        return controller == null ? null : "Meteor movement is controlled by " + controller + ". Release it before resuming.";
    }

    public static void openPlanningScreen() {
        if (module == null || pendingScreen != null) return;
        Screen current = Minecraft.getInstance().gui.screen();
        if (current instanceof PlanningScreen) return;
        pendingScreen = new PlanningScreen(module, current instanceof ChatScreen ? null : current);
    }

    public static boolean stopKey(KeyEvent input, ClientEvents.Action action) {
        if (module == null || action != ClientEvents.Action.PRESS || !module.isStopKey(input)) return false;
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.gui.screen();
        if (minecraft.level == null || current instanceof PlanningScreen planner && !planner.acceptsStopKey()) return false;
        try {
            module.stop();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            feedback(failure.getMessage());
        }
        return true;
    }

    public static boolean key(KeyEvent input, ClientEvents.Action action, boolean crosshair) {
        if (module == null || action != ClientEvents.Action.PRESS) return false;
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.gui.screen();
        if (current == null && minecraft.level != null && crosshair) {
            try {
                module.routeToCrosshair();
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                feedback(rejected.getMessage());
            }
            return true;
        }
        if (!module.isOpenKey(input)) return false;
        if (current instanceof PlanningScreen planner && planner.acceptsOpenKey()) {
            planner.onClose();
            return true;
        }
        if (current == null && minecraft.level != null) {
            openPlanningScreen();
            return true;
        }
        return false;
    }

    public static void beforeTick() {
        if (module == null && standaloneRequested) initialize(HostAccess.STANDALONE);
        if (module == null) return;
        if (pendingScreen != null) {
            PlanningScreen opening = pendingScreen;
            pendingScreen = null;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) minecraft.gui.setScreen(opening);
        }
        ClientEvents.tickPre();
    }

    public static void afterTick() {
        if (module == null) return;
        ClientEvents.tickPost();
        Minecraft minecraft = Minecraft.getInstance();
        BlueprintStorage.tick(minecraft);
        MapAnnotations.tick(minecraft);
        module.afterTick();
        PortalTracker.tick(minecraft);
        PortalLinks.tick(minecraft);
    }

    public static void disconnected() {
        pendingScreen = null;
        WorldInteraction.get().stop();
        PortalTracker.flush();
        PortalLinks.disconnected();
        releaseRetainedSurvey();
        ClientEvents.disconnected();
        host.disconnected();
    }

    public static void releaseRetainedSurvey() { PlanningViewport.releaseRetainedSurvey(); }

    public static void close() {
        try {
            FootSurvey.get().disconnected();
            if (Minecraft.getInstance().gui.screen() instanceof PlanningScreen open) open.removed();
            PortalTracker.finishWrites();
            PortalLinks.finishWrites();
            releaseRetainedSurvey();
            SurveyCache.finishWrites();
        } finally {
            DiscreteCompute.close();
        }
    }

    public static void drawStatus(GuiGraphicsExtractor graphics) {
        if (module != null) module.drawStatus(graphics, graphics.guiWidth());
    }

    public static boolean observes(Connection connection) {
        if (module == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return false;
        var listener = minecraft.getConnection();
        return listener != null && listener.getConnection() == connection;
    }

    public static void feedback(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("[Wayclef] " + message));
    }
}
