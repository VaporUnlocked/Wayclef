package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.game.transport.PearlTrajectory;
import dev.soprano.host.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** Performs one explicit emergency item action against real server inventory and player state. */
public final class EscapeController implements ClientEvents.Listener {
    public enum Choice { AUTO, PEARL, CHORUS }
    private enum Phase { READY, EATING, PEARL_IN_FLIGHT }

    private record Supply(InteractionHand hand, int slot) {
        ItemStack stack(LocalPlayer player) {
            return hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getInventory().getItem(slot);
        }
    }

    private static final EscapeController INSTANCE = new EscapeController();
    private static final int READY_LIMIT = 20;
    private static final int CHORUS_LIMIT = 80;
    private ClientLevel level;
    private LocalPlayer player;
    private Choice choice;
    private Phase phase;
    private Supply fruit;
    private PearlTrajectory.Aim pearlAim;
    private boolean avoidMobs;
    private boolean previousUse;
    private int previousSlot = -1;
    private int previousCount;
    private int remaining;
    private String detail = "No escape in progress.";

    private EscapeController() { }

    public static EscapeController get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }
    public boolean active() { return phase != null; }
    public String statusText() { return detail; }

    public void start(Choice selected, boolean avoidMobs) {
        Minecraft mc = Minecraft.getInstance();
        if (active()) throw new IllegalStateException("An escape is already in progress.");
        LocalPlayer local = mc.player;
        if (local == null || mc.level == null || mc.gameMode == null || !local.isAlive() || local.isSpectator()) {
            throw new IllegalStateException("Join a world with a living player to escape.");
        }
        if (selected == Choice.PEARL && available(local, Items.ENDER_PEARL) == null) {
            throw new IllegalStateException("Keep a ready ender pearl in the hotbar or offhand.");
        }
        if (selected == Choice.CHORUS && available(local, Items.CHORUS_FRUIT) == null) {
            throw new IllegalStateException("Keep a ready chorus fruit in the hotbar or offhand.");
        }
        if (selected == Choice.AUTO && available(local, Items.ENDER_PEARL) == null
            && available(local, Items.CHORUS_FRUIT) == null) {
            throw new IllegalStateException("Keep a ready ender pearl or chorus fruit in the hotbar or offhand.");
        }
        player = local;
        level = mc.level;
        choice = selected;
        this.avoidMobs = avoidMobs;
        phase = Phase.READY;
        remaining = READY_LIMIT;
        detail = "Choosing an escape.";
    }

    @Override public void tickPre() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive() || mc.gameMode == null) {
            finish("The world or player changed.");
            return;
        }
        if (phase == Phase.PEARL_IN_FLIGHT) {
            if (player.position().distanceToSqr(pearlAim.impact()) <= 16) finish("Ender pearl landing confirmed.");
            else if (--remaining <= 0) finish("The expected pearl landing was not confirmed.");
            return;
        }
        if (phase == Phase.READY && mc.gui.screen() instanceof ChatScreen && --remaining > 0) return;
        if (mc.gui.screen() != null || !mc.getWindow().isFocused() || !mc.mouseHandler.isMouseGrabbed()) {
            finish("Escape stopped when Minecraft lost focus or opened a game screen.");
            return;
        }
        if (phase == Phase.EATING) {
            continueEating(mc);
            return;
        }
        if (player.isUsingItem()) {
            if (--remaining <= 0) finish("Finish the item already in use before starting an escape.");
            return;
        }
        try {
            beginUse(mc);
        } catch (IllegalStateException failure) {
            finish(failure.getMessage());
        }
    }

    private void beginUse(Minecraft mc) {
        Supply pearl = choice == Choice.CHORUS ? null : available(player, Items.ENDER_PEARL);
        if (pearl != null && player.getHealth() + player.getAbsorptionAmount() > 5) {
            Vec3 away = escapeDirection();
            PearlTrajectory.Aim aim = PearlTrajectory.aimAway(level, player, away, avoidMobs);
            if (aim != null) {
                throwPearl(mc, pearl, aim);
                return;
            }
        }
        if (choice == Choice.PEARL) {
            throw new IllegalStateException(player.getHealth() + player.getAbsorptionAmount() <= 5
                ? "Current health is too low to risk vanilla ender pearl damage."
                : "No clear ender pearl trajectory to safe loaded ground was found.");
        }
        fruit = available(player, Items.CHORUS_FRUIT);
        if (fruit == null) throw new IllegalStateException("No safe pearl landing or ready chorus fruit was available.");
        previousUse = mc.options.keyUse.isDown();
        previousSlot = player.getInventory().getSelectedSlot();
        previousCount = fruit.stack(player).getCount();
        if (fruit.hand() == InteractionHand.MAIN_HAND) player.getInventory().setSelectedSlot(fruit.slot());
        phase = Phase.EATING;
        if (!mc.gameMode.useItem(player, fruit.hand()).consumesAction()) {
            throw new IllegalStateException("The server interaction did not start using chorus fruit.");
        }
        mc.options.keyUse.setDown(true);
        remaining = CHORUS_LIMIT;
        detail = "Eating chorus fruit for a random nearby escape.";
    }

    private Vec3 escapeDirection() {
        LivingEntity threat = player.getLastHurtByMob();
        if (threat == null || !threat.isAlive() || threat.distanceToSqr(player) > 24 * 24 || threat.level() != level) {
            threat = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12),
                    entity -> entity != player && entity.isAlive()
                        && (entity instanceof Enemy || entity instanceof Player other && !other.isSpectator()))
                .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        }
        return threat == null ? player.getLookAngle() : player.position().subtract(threat.position());
    }

    private void throwPearl(Minecraft mc, Supply supply, PearlTrajectory.Aim aim) {
        Inventory inventory = player.getInventory();
        int slot = inventory.getSelectedSlot();
        float yaw = player.getYRot(), pitch = player.getXRot();
        try {
            if (supply.hand() == InteractionHand.MAIN_HAND) inventory.setSelectedSlot(supply.slot());
            player.setYRot(aim.yaw());
            player.setXRot(aim.pitch());
            var result = mc.gameMode.useItem(player, supply.hand());
            if (!(result instanceof InteractionResult.Success success)) {
                throw new IllegalStateException("The game rejected the ender pearl throw.");
            }
            if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
                player.swing(supply.hand(), supply.stack(player).getInteractAnimation(), false);
            }
        } finally {
            inventory.setSelectedSlot(slot);
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        phase = Phase.PEARL_IN_FLIGHT;
        pearlAim = aim;
        remaining = aim.ticks() + 40;
        detail = "Ender pearl airborne. Checking the landing.";
    }

    private void continueEating(Minecraft mc) {
        if (fruit.stack(player).getCount() < previousCount && !player.isUsingItem()) {
            finish("Chorus fruit consumed.");
            return;
        }
        if (--remaining <= 0 || !player.isUsingItem() || !player.getUseItem().is(Items.CHORUS_FRUIT)) {
            finish("The chorus fruit use was interrupted.");
            return;
        }
        mc.options.keyUse.setDown(true);
    }

    public void stop() { if (active()) finish("Escape stopped."); }
    @Override public void disconnected() { stop(); }

    private void finish(String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (phase == Phase.EATING) {
            mc.options.keyUse.setDown(previousUse);
            if (mc.player == player && fruit != null) {
                if (mc.level == level && mc.gameMode != null && player.isUsingItem() && player.getUseItem().is(Items.CHORUS_FRUIT)) {
                    mc.gameMode.releaseUsingItem(player);
                }
                if (fruit.hand() == InteractionHand.MAIN_HAND && player.getInventory().getSelectedSlot() == fruit.slot()) {
                    player.getInventory().setSelectedSlot(previousSlot);
                }
            }
        }
        phase = null;
        player = null;
        level = null;
        fruit = null;
        pearlAim = null;
        detail = reason;
        SopranoClient.feedback(detail);
    }

    private static Supply available(LocalPlayer player, Item item) {
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(item) && !player.getCooldowns().isOnCooldown(offhand)) return new Supply(InteractionHand.OFF_HAND, -1);
        Inventory inventory = player.getInventory();
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && !player.getCooldowns().isOnCooldown(stack)) return new Supply(InteractionHand.MAIN_HAND, slot);
        }
        return null;
    }
}
