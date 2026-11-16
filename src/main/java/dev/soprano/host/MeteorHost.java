package dev.soprano.host;

import dev.soprano.SopranoClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Meteor-only movement arbitration and input adapter. */
public final class MeteorHost implements HostAccess {
    private static final Set<String> MOVEMENT_MODULES = Set.of("AirJump", "AutoJump", "AutoWalk", "Flight", "HighJump",
        "LongJump", "Parkour", "ReverseStep", "SafeWalk", "Scaffold", "Sneak", "Speed", "Spider", "Sprint", "Step");

    private MeteorSopranoModule module;
    private Vec3 movementBefore;
    private LocalPlayer changedPlayer;
    private int changedTick;

    public void attach(MeteorSopranoModule module) { this.module = module; }
    @Override public boolean meteor() { return true; }
    @Override public boolean isCompatibleScreen(Screen screen) { return screen instanceof WidgetScreen; }
    @Override public boolean freecamActive() { return Modules.get().isActive(Freecam.class); }
    @Override public void navigationStarted() { if (module != null && !module.isActive()) module.enable(); }
    @Override public void disconnected() { changedPlayer = null; movementBefore = null; }

    @Override
    public String movementConflict() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        for (var active : Modules.get().getActive()) {
            String type = active.getClass().getSimpleName();
            if (MOVEMENT_MODULES.contains(type) || player.isPassenger() && type.equals("EntityControl")
                || player.isFallFlying() && (type.equals("ElytraFly") || type.equals("ElytraBoost"))
                || player.isInWater() && type.equals("Jesus")) return active.title;
        }
        return player == changedPlayer && player.tickCount - changedTick <= 1 ? "a Meteor movement listener" : null;
    }

    @EventHandler(priority = 1_000)
    private void beforeMove(PlayerMoveEvent event) { movementBefore = event.movement; }

    @EventHandler(priority = -1_000)
    private void afterMove(PlayerMoveEvent event) {
        if (movementBefore == null || movementBefore.equals(event.movement)) return;
        changedPlayer = Minecraft.getInstance().player;
        if (changedPlayer != null) changedTick = changedPlayer.tickCount;
    }

    @EventHandler(priority = 100)
    private void launcher(KeyInputEvent event) {
        if (!event.isCancelled() && SopranoClient.key(event.input, action(event.action),
            module != null && module.matchesCrosshairKey(event.input))) event.cancel();
    }

    @EventHandler(priority = -940)
    private void key(KeyInputEvent event) {
        if (event.isCancelled()) return;
        // Meteor reads this binding directly to boost the detached camera.
        if (freecamActive() && Minecraft.getInstance().options.keySprint.matches(event.input)) return;
        ClientEvents.keyInput(event.input, action(event.action));
    }

    @EventHandler(priority = -900)
    private void mouse(MouseClickEvent event) {
        if (!event.isCancelled()) ClientEvents.mouseClick(action(event.action));
    }

    @EventHandler(priority = -900)
    private void scroll(MouseScrollEvent event) { if (!event.isCancelled()) ClientEvents.mouseScroll(); }

    @EventHandler(priority = -900)
    private void sending(PacketEvent.Send event) {
        if (!event.isCancelled() && SopranoClient.observes(event.connection)) ClientEvents.packetSending(event.connection, event.packet);
    }

    @EventHandler
    private void sent(PacketEvent.Sent event) {
        if (SopranoClient.observes(event.connection)) ClientEvents.packetSent(event.connection, event.packet);
    }

    private static ClientEvents.Action action(KeyAction action) {
        return switch (action) {
            case Press -> ClientEvents.Action.PRESS;
            case Release -> ClientEvents.Action.RELEASE;
            case Repeat -> ClientEvents.Action.REPEAT;
        };
    }
}
