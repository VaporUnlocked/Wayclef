package dev.soprano.inventory;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class StorageKnowledgeTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var single = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        var left = single.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT);
        var right = single.setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT);
        BlockPos start = new BlockPos(15, 64, 0);
        if (StorageKnowledge.partner(start, left).getX() < start.getX()) start = start.east();
        BlockPos other = StorageKnowledge.partner(start, left);
        assert start.getX() >> 4 != other.getX() >> 4;
        assert StorageKnowledge.isPair(start, left, other, right);
        assert StorageKnowledge.isPair(other, right, start, left);
        var fromLeft = StorageKnowledge.ordered(start, other, Blocks.CHEST);
        var fromRight = StorageKnowledge.ordered(other, start, Blocks.CHEST);
        assert fromLeft.equals(fromRight) && fromLeft.key().equals(fromRight.key());
        assert !fromLeft.key().equals(StorageKnowledge.ordered(start, null, Blocks.CHEST).key());
        assert !StorageKnowledge.isPair(start, left, other, single);
        assert !StorageKnowledge.isPair(start, left, other, right.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        assert StorageKnowledge.partner(start, single) == null;
        assert StorageKnowledge.ordered(start, null, Blocks.ENDER_CHEST).key()
            .equals(StorageKnowledge.ordered(other, null, Blocks.ENDER_CHEST).key());

        var input = new ItemStack(Holder.direct(Items.RAW_IRON), 9);
        var fuel = new ItemStack(Holder.direct(Items.COAL), 4);
        var output = new ItemStack(Holder.direct(Items.IRON_INGOT), 6);
        var furnace = new StorageKnowledge.FurnaceStock(input, fuel, output, 1_500, 150);
        input.setCount(1);
        fuel.setCount(1);
        output.setCount(1);
        assert furnace.input().is(Items.RAW_IRON) && furnace.input().getCount() == 9;
        assert furnace.fuel().is(Items.COAL) && furnace.fuel().getCount() == 4;
        assert furnace.output().is(Items.IRON_INGOT) && furnace.output().getCount() == 6;
        assert furnace.burnTicks() == 1_500 && furnace.cookTicks() == 150;
        System.out.println("StorageKnowledgeTest passed");
    }
}
