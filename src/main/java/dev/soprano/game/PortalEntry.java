package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.RouteItinerary;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/** Identifies the loaded blocks of a specific walk-in portal without changing world state. */
record PortalEntry(Goal goal, Set<BlockPos> cells) {
    static PortalEntry find(ClientLevel level, Goal selected) {
        int y = Math.floorDiv(selected.y16(), 16);
        BlockPos base = new BlockPos(selected.x(), y, selected.z());
        if (loaded(level, base) && level.getBlockState(base).is(Blocks.END_PORTAL)) {
            BlockPos floor = base.below();
            if (!loaded(level, floor) || !level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                throw new IllegalStateException("The marked End portal requires an unsupported drop. Use an entry with solid footing.");
            }
            return new PortalEntry(new Goal(base.getX(), base.getY() * 16, base.getZ(), 0), Set.of(base));
        }
        if (!netherPortalAt(level, base)) throw new IllegalStateException("The marked portal is missing, inactive, or unsupported.");
        while (base.getY() > level.getMinY() && y - base.getY() < 21 && netherPortalAt(level, base.below())) {
            base = base.below();
        }
        Set<BlockPos> column = new HashSet<>();
        for (int height = 0; height < 3 && netherPortalAt(level, base.above(height)); height++) {
            column.add(base.above(height));
        }
        return new PortalEntry(new Goal(base.getX(), base.getY() * 16, base.getZ(), 0), Set.copyOf(column));
    }

    static Set<BlockPos> touching(ClientLevel level, LocalPlayer player) {
        var body = player.getBoundingBox();
        var minimum = BlockPos.containing(body.minX, body.minY, body.minZ);
        var maximum = BlockPos.containing(body.maxX - 1e-7, body.maxY - 1e-7, body.maxZ - 1e-7);
        Set<BlockPos> touching = new HashSet<>();
        for (BlockPos position : BlockPos.betweenClosed(minimum, maximum)) {
            if (!loaded(level, position)) continue;
            var state = level.getBlockState(position);
            if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) touching.add(position.immutable());
        }
        return Set.copyOf(touching);
    }

    static boolean arrived(RouteItinerary.PortalExit exit, String dimension, LocalPlayer player) {
        return exit.dimension().equals(dimension) && exit.contains(player.getX(), player.getY(), player.getZ());
    }

    private static boolean netherPortalAt(ClientLevel level, BlockPos position) {
        return loaded(level, position) && level.getBlockState(position).is(Blocks.NETHER_PORTAL);
    }

    private static boolean loaded(ClientLevel level, BlockPos position) {
        return position.getY() >= level.getMinY() && position.getY() <= level.getMaxY()
            && level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }
}
