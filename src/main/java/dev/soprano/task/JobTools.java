package dev.soprano.task;

import dev.soprano.game.ToolDurability;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/** Selects a tool that can obtain the block's drops within the wear reserve. */
final class JobTools {
    private JobTools() { }

    static int bestSlot(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state, int reserve) {
        return bestSlot(player, level, pos, state, reserve, Inventory.getSelectionSize(), stack -> true);
    }

    static int bestInventorySlot(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state, int reserve) {
        return bestInventorySlot(player, level, pos, state, reserve, stack -> true);
    }

    static int bestInventorySlot(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state, int reserve,
                                 Predicate<ItemStack> producesWantedDrop) {
        return bestSlot(player, level, pos, state, reserve, Inventory.INVENTORY_SIZE, producesWantedDrop);
    }

    private static int bestSlot(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state, int reserve, int slots,
                                Predicate<ItemStack> producesWantedDrop) {
        Inventory inventory = player.getInventory();
        int best = -1;
        float speed = -1;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!canBreak(stack, player, level, pos, state)
                || ToolDurability.pauseReason(player, stack, level, pos, state, reserve) != null
                || !producesWantedDrop.test(stack)) continue;
            float candidate = stack.getDestroySpeed(state);
            if (candidate > speed || candidate == speed && slot == inventory.getSelectedSlot()) {
                speed = candidate;
                best = slot;
            }
        }
        return best;
    }

    static boolean canBreak(ItemStack stack, LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state) {
        return (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state))
            && stack.canDestroyBlock(state, level, pos, player);
    }
}
