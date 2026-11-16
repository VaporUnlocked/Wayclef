package dev.soprano.game.transport;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class ElytraSupplies {
    private ElytraSupplies() { }

    static ElytraReservePolicy.Supplies read(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int rockets = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (safeRocketDuration(stack) >= 0) rockets += stack.getCount();
        }
        ItemStack wings = player.getItemBySlot(EquipmentSlot.CHEST);
        int durability = wings.is(Items.ELYTRA) ? Math.max(0, wings.getMaxDamage() - wings.getDamageValue()) : 0;
        return new ElytraReservePolicy.Supplies(rockets, durability);
    }

    /** The slot remains selected only for the normal client item-use interaction. */
    record Rocket(InteractionHand hand, int slot, int flightDuration) {
        int minimumBoostTicks() { return 10 * (1 + flightDuration); }
        int maximumBoostTicks() { return minimumBoostTicks() + 12; }

        boolean use(LocalPlayer player) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameMode == null || minecraft.player != player || minecraft.gui.screen() != null
                || player.isUsingItem() || !player.isFallFlying()) return false;
            Inventory inventory = player.getInventory();
            int oldSlot = inventory.getSelectedSlot();
            ItemStack stack = hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : inventory.getItem(slot);
            if (safeRocketDuration(stack) != flightDuration) return false;
            var animation = stack.getInteractAnimation();
            try {
                if (hand == InteractionHand.MAIN_HAND) inventory.setSelectedSlot(slot);
                var result = minecraft.gameMode.useItem(player, hand);
                if (!(result instanceof InteractionResult.Success success)) return false;
                if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) player.swing(hand, animation, false);
                player.itemUsed(hand);
                return true;
            } finally {
                if (inventory.getSelectedSlot() != oldSlot) inventory.setSelectedSlot(oldSlot);
            }
        }
    }

    static Rocket availableRocket(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.player != player || minecraft.gui.screen() != null || player.isUsingItem()) return null;
        int duration = safeRocketDuration(player.getOffhandItem());
        if (duration >= 0) return new Rocket(InteractionHand.OFF_HAND, Inventory.SLOT_OFFHAND, duration);
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        duration = safeRocketDuration(inventory.getItem(selected));
        if (duration >= 0) return new Rocket(InteractionHand.MAIN_HAND, selected, duration);
        Rocket shortest = null;
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            duration = safeRocketDuration(inventory.getItem(slot));
            if (duration >= 0 && (shortest == null || duration < shortest.flightDuration())) {
                shortest = new Rocket(InteractionHand.MAIN_HAND, slot, duration);
            }
        }
        return shortest;
    }

    private static int safeRocketDuration(ItemStack stack) {
        if (!stack.is(Items.FIREWORK_ROCKET)) return -1;
        var fireworks = stack.get(DataComponents.FIREWORKS);
        if (fireworks == null || !fireworks.explosions().isEmpty() || fireworks.flightDuration() > 3) return -1;
        return Math.max(0, fireworks.flightDuration());
    }
}
