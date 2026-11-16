package dev.soprano.game;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.portal.PortalShape;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NetherFrameTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.BLOCK.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK,
            Map.of(BlockTags.NETHER_PORTAL_FRAME, List.of(Blocks.OBSIDIAN.builtInRegistryHolder())))).apply();
        var opening = new BlockPos(15, 64, 15);
        for (var axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
            Set<BlockPos> frame = new HashSet<>(NetherFrame.frameCells(opening, axis));
            check(frame.size() == 13, "Three corner supports and ten frame blocks");
            check(PortalShape.findAnyShape(new Frame(frame), opening, axis).isValid(), "Vanilla recognizes the planned orientation");
            check(!PortalShape.findAnyShape(new Frame(frame), opening,
                axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X).isValid(), "The other orientation has no frame");
            frame.remove(opening.below());
            check(!PortalShape.findAnyShape(new Frame(frame), opening, axis).isValid(), "An incomplete frame cannot light");
        }
        System.out.println("Soprano Nether frame geometry passed.");
    }

    private record Frame(Set<BlockPos> blocks) implements BlockGetter {
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public BlockEntity getBlockEntity(BlockPos at) { return null; }
        @Override public BlockState getBlockState(BlockPos at) {
            return (blocks.contains(at) ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos at) { return getBlockState(at).getFluidState(); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
