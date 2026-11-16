package dev.soprano.task;

import dev.soprano.game.ToolDurability;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Discovers fresh tool stacks that actually meet the game's mining requirements. */
final class GatherMiningTools {
    private static final int MAX_ALTERNATIVES = 24;

    private GatherMiningTools() { }

    private static final class Tools {
        static final List<Item> CANDIDATES = tools();

        private static List<Item> tools() {
            var normal = List.of(
                Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.WOODEN_HOE, Items.WOODEN_SWORD,
                Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.STONE_SWORD,
                Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL, Items.IRON_HOE, Items.IRON_SWORD, Items.SHEARS,
                Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.DIAMOND_SWORD,
                Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE, Items.NETHERITE_SWORD);
            var result = new ArrayList<>(normal);
            BuiltInRegistries.ITEM.stream().filter(item -> !normal.contains(item) && item.components().has(DataComponents.TOOL))
                .sorted(java.util.Comparator.comparing(BuiltInRegistries.ITEM::getKey)).forEach(result::add);
            return List.copyOf(result);
        }
    }

    static List<Item> compatible(ClientLevel level, LocalPlayer player, BlockPos position, BlockState state, int reserve) {
        return Tools.CANDIDATES.stream().filter(item -> {
            var stack = item.getDefaultInstance();
            return JobTools.canBreak(stack, player, level, position, state)
                && ToolDurability.pauseReason(player, stack, level, position, state, reserve) == null;
        }).limit(MAX_ALTERNATIVES).toList();
    }
}
