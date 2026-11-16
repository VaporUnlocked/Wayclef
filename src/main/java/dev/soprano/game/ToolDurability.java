package dev.soprano.game;

import dev.soprano.SopranoClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Checks the worst-case vanilla wear of one block break before sending a mining action. */
public final class ToolDurability {
    private ToolDurability() { }

    public static int configuredReserve() {
        var module = SopranoClient.module();
        return module == null ? 0 : module.minimumToolDurability();
    }

    public static int wearPerBlock(ItemStack stack) {
        var tool = stack.get(DataComponents.TOOL);
        return tool == null ? 0 : tool.damagePerBlock();
    }

    public static String pauseReason(ItemStack tool, int wear, int reserve) {
        if (reserve <= 0 || wear <= 0 || !tool.isDamageableItem()) return null;
        int remaining = tool.getMaxDamage() - tool.getDamageValue();
        if (remaining - wear >= reserve) return null;
        return tool.getHoverName().getString() + " has " + remaining + " durability left. Equip a tool with at least "
            + (reserve + (long) wear) + " durability for this break to keep the " + reserve + " reserve.";
    }

    public static String pauseReason(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, int reserve) {
        return pauseReason(player, player.getMainHandItem(), level, pos, state, reserve);
    }

    public static String pauseReason(LocalPlayer player, ItemStack held, BlockGetter level, BlockPos pos, BlockState state, int reserve) {
        if (reserve <= 0 || player.getAbilities().instabuild) return null;
        boolean freeBreak = held.getItem() instanceof ShearsItem ? state.is(BlockTags.FIRE) : state.getDestroySpeed(level, pos) == 0;
        return freeBreak ? null : pauseReason(held, wearPerBlock(held), reserve);
    }

    public static boolean replacementReady(LocalPlayer player, BlockGetter level, BlockPos pos, BlockState state, int reserve) {
        return reserve == 0 || player.getAbilities().instabuild || player.getMainHandItem().has(DataComponents.TOOL)
            && pauseReason(player, level, pos, state, reserve) == null;
    }
}
