package dev.soprano.game;

import dev.soprano.build.BuildPlan;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.portal.PortalShape;

import java.util.ArrayList;
import java.util.List;

/** Uses a selected lower opening cell for construction and vanilla shape checks for ignition. */
public final class NetherFrame {
    private NetherFrame() { }

    public static BuildPlan plan(ClientLevel level, BlockPos opening, Direction.Axis axis) {
        requirePortalDimension(level);
        if (!PortalShape.FRAME.test(Blocks.OBSIDIAN.defaultBlockState())) {
            throw new IllegalStateException("This world does not accept obsidian for Nether portal frames.");
        }
        BlockGetter terrain = loadedTerrain(level);
        for (BlockPos cell : openingCells(opening, axis)) {
            if (!terrain.getBlockState(cell).isAir()) {
                throw new IllegalArgumentException("The portal opening is obstructed or unloaded at " + cell.toShortString() + ".");
            }
        }
        BuildPlan result = new BuildPlan();
        for (BlockPos cell : frameCells(opening, axis)) {
            BlockState current = terrain.getBlockState(cell);
            if (!current.isAir() && !current.is(Blocks.OBSIDIAN)) {
                throw new IllegalArgumentException("The portal frame would overlap a block or unloaded terrain at " + cell.toShortString() + ".");
            }
            result.place(new BuildPlan.Cell(cell.getX(), cell.getY(), cell.getZ()), "minecraft:obsidian");
        }
        return result;
    }

    static List<BlockPos> frameCells(BlockPos opening, Direction.Axis axis) {
        Direction along = horizontal(axis);
        var result = new ArrayList<BlockPos>(13);
        for (int width = -1; width <= 2; width++) {
            result.add(opening.relative(along, width).below());
        }
        // One upper corner provides a horizontal attachment for the two top frame blocks.
        for (int width = -1; width < 2; width++) result.add(opening.relative(along, width).above(3));
        for (int height = 0; height < 3; height++) {
            result.add(opening.relative(along.getOpposite()).above(height));
            result.add(opening.relative(along, 2).above(height));
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> openingCells(BlockPos opening, Direction.Axis axis) {
        Direction along = horizontal(axis);
        var result = new ArrayList<BlockPos>(6);
        for (int height = 0; height < 3; height++) {
            result.add(opening.above(height));
            result.add(opening.above(height).relative(along));
        }
        return result;
    }

    private static Direction horizontal(Direction.Axis axis) {
        if (axis == Direction.Axis.X) return Direction.EAST;
        if (axis == Direction.Axis.Z) return Direction.SOUTH;
        throw new IllegalArgumentException("A Nether portal frame must run along X or Z.");
    }

    public static Direction.Axis requireUnlit(ClientLevel level, BlockPos opening) {
        requirePortalDimension(level);
        if (!loaded(level, opening) || !PortalShape.FRAME.test(level.getBlockState(opening.below()))
            || !level.getBlockState(opening).isAir()) {
            throw new IllegalArgumentException("Choose an empty cell directly above the bottom obsidian inside the frame.");
        }
        var terrain = loadedTerrain(level);
        if (PortalShape.findAnyShape(terrain, opening, Direction.Axis.X).isValid()) return Direction.Axis.X;
        if (PortalShape.findAnyShape(terrain, opening, Direction.Axis.Z).isValid()) return Direction.Axis.Z;
        throw new IllegalArgumentException("A complete, unlit Nether portal frame must be loaded around the selected opening.");
    }

    private static void requirePortalDimension(ClientLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD) && !level.dimension().equals(Level.NETHER)) {
            throw new IllegalStateException("Vanilla Nether portals can be lit in the Overworld and the Nether.");
        }
    }

    private static boolean loaded(ClientLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position) && level.getWorldBorder().isWithinBounds(position)
            && level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }

    private static BlockGetter loadedTerrain(ClientLevel level) {
        return new BlockGetter() {
            @Override public int getMinY() { return level.getMinY(); }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public BlockEntity getBlockEntity(BlockPos position) { return null; }
            @Override public BlockState getBlockState(BlockPos position) {
                if (loaded(level, position)) {
                    BlockState state = level.getBlockState(position);
                    if (!state.is(Blocks.NETHER_PORTAL)) return state;
                }
                return Blocks.BEDROCK.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
        };
    }
}
