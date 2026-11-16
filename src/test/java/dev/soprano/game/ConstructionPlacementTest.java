package dev.soprano.game;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.List;
import java.util.Map;

public final class ConstructionPlacementTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        stairShapeCanSettleButFacingAndHalfMustMatch();
        hingedDoorsAndLogAxesMustMatch();
        onlyMatchingDrySlabsCanUpgrade();
        routeProtectionMatchesBlockIdsAndParsesTags();
        supportReserveCountsMatchingInventoryAndOffhand();
        toolReserveAllowsExactlyTheFloor();
    }

    private static void stairShapeCanSettleButFacingAndHalfMustMatch() {
        var stair = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM);
        var turning = stair.setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.OUTER_LEFT);
        assert ConstructionPlacement.matchesDuringConstruction(stair, turning);
        assert !ConstructionPlacement.matchesDuringConstruction(
            stair.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), turning);
        assert !ConstructionPlacement.matchesDuringConstruction(stair.setValue(BlockStateProperties.HALF, Half.TOP), turning);
    }

    private static void hingedDoorsAndLogAxesMustMatch() {
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT);
        assert !ConstructionPlacement.matchesDuringConstruction(door,
            door.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT));
        var log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
        assert !ConstructionPlacement.matchesDuringConstruction(log, log.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
    }

    private static void routeProtectionMatchesBlockIdsAndParsesTags() {
        var protectedBlocks = RouteBlockProtection.parse("oak_log, #minecraft:logs oak_log mod:concrete");
        assert protectedBlocks.toString().equals("minecraft:oak_log, #minecraft:logs, mod:concrete");
        assert protectedBlocks.contains(Blocks.OAK_LOG.defaultBlockState());
        assert protectedBlocks.contains(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        assert !protectedBlocks.contains(Blocks.STONE.defaultBlockState());
        assert !RouteBlockProtection.NONE.contains(Blocks.OAK_LOG.defaultBlockState());
        var tag = TagKey.create(Registries.BLOCK, Identifier.parse("test:protected"));
        BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK,
            Map.of(tag, List.of(Blocks.BIRCH_LOG.builtInRegistryHolder())))).apply();
        var tagRule = RouteBlockProtection.parse("#test:protected");
        assert tagRule.contains(Blocks.BIRCH_LOG.defaultBlockState());
        assert !tagRule.contains(Blocks.OAK_LOG.defaultBlockState());
        try {
            RouteBlockProtection.parse("#");
            throw new AssertionError("An empty tag was accepted.");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("Invalid block ID or tag");
        }
    }

    private static void supportReserveCountsMatchingInventoryAndOffhand() {
        var cobblestone = Holder.direct(Items.COBBLESTONE);
        var inventory = List.of(new ItemStack(cobblestone, 4), new ItemStack(Holder.direct(Items.DIRT), 32));
        var offhand = new ItemStack(cobblestone, 1);
        assert RouteEdits.hasSupportBeyondReserve(inventory, offhand, Items.COBBLESTONE, 4);
        assert !RouteEdits.hasSupportBeyondReserve(inventory, offhand, Items.COBBLESTONE, 5);
        assert !RouteEdits.hasSupportBeyondReserve(inventory, offhand, Items.STONE, 0);
    }

    private static void toolReserveAllowsExactlyTheFloor() {
        var components = DataComponentMap.builder()
            .set(DataComponents.TOOL, new Tool(List.of(), 1.0f, 1, true))
            .set(DataComponents.MAX_DAMAGE, 100)
            .set(DataComponents.DAMAGE, 97)
            .set(DataComponents.ITEM_NAME, Component.literal("Test pickaxe"))
            .build();
        var pickaxe = new ItemStack(Holder.direct(Items.IRON_PICKAXE, components));
        int wear = ToolDurability.wearPerBlock(pickaxe);
        assert wear == 1;
        assert ToolDurability.pauseReason(pickaxe, wear, 2) == null;
        assert ToolDurability.pauseReason(pickaxe, wear, 3) != null;
        assert ToolDurability.pauseReason(pickaxe, wear, 0) == null;
        assert ToolDurability.pauseReason(pickaxe, 0, 3) == null;
        assert ToolDurability.pauseReason(new ItemStack(Holder.direct(Items.STICK)), wear, 3) == null;
    }

    private static void onlyMatchingDrySlabsCanUpgrade() {
        var slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        var wanted = slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        assert ConstructionPlacement.canUpgradeSlab(slab, wanted);
        assert !ConstructionPlacement.canUpgradeSlab(slab.setValue(BlockStateProperties.WATERLOGGED, true), wanted);
        assert !ConstructionPlacement.canUpgradeSlab(Blocks.OAK_SLAB.defaultBlockState(), wanted);
        assert !ConstructionPlacement.canUpgradeSlab(slab, slab);
    }
}
