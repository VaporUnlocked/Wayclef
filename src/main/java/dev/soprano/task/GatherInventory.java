package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps gathered output in the hotbar whenever there is room. */
final class GatherInventory {
    private GatherInventory() { }

    static List<Slot> hotbarFirst(List<Slot> destinations) {
        return destinations.stream().sorted(Comparator.comparingInt(slot -> !Inventory.isHotbarSlot(slot.getContainerSlot())
            ? 2 : slot.hasItem() ? 0 : 1)).toList();
    }

    static Map<String, Integer> hotbarCounts(LocalPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) counts.merge(InventoryJobs.itemId(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    static void promoteGains(LocalPlayer player, MultiPlayerGameMode mode, Map<String, Integer> inventoryBefore,
                             Map<String, Integer> hotbarBefore, Map<String, Integer> inventoryAfter) {
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return;
        Inventory inventory = player.getInventory();
        Map<String, Integer> hotbarAfter = hotbarCounts(player);
        for (String id : inventoryAfter.keySet().stream().sorted().toList()) {
            int gained = inventoryAfter.get(id) - inventoryBefore.getOrDefault(id, 0);
            int gainedInHotbar = Math.max(0, hotbarAfter.getOrDefault(id, 0) - hotbarBefore.getOrDefault(id, 0));
            if (gained <= gainedInHotbar) continue;
            int remaining = gained - gainedInHotbar;
            for (int slot = Inventory.getSelectionSize(); slot < Inventory.INVENTORY_SIZE && remaining > 0; slot++) {
                var stack = inventory.getItem(slot);
                if (stack.isEmpty() || !id.equals(InventoryJobs.itemId(stack))) continue;
                int empty = emptyHotbarSlot(inventory);
                if (empty < 0) return;
                var menuSlot = player.inventoryMenu.findSlot(inventory, slot);
                if (menuSlot.isEmpty()) continue;
                mode.handleContainerInput(player.inventoryMenu.containerId, menuSlot.getAsInt(), empty, ContainerInput.SWAP, player);
                if (!inventory.getItem(empty).isEmpty() && id.equals(InventoryJobs.itemId(inventory.getItem(empty)))) {
                    remaining -= inventory.getItem(empty).getCount();
                }
            }
        }
    }

    private static int emptyHotbarSlot(Inventory inventory) {
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            if (inventory.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }
}
