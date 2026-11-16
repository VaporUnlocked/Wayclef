package dev.soprano.game;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Recognizes a loaded vanilla 3-by-3 End portal opening and its twelve inward-facing sockets. */
record EndPortalRing(BlockPos center, List<Socket> sockets) {
    record Socket(BlockPos at, Direction facing) { }

    static EndPortalRing find(ClientLevel level, BlockPos picked) {
        return find(picked, at -> loaded(level, at) ? level.getBlockState(at) : null);
    }

    static EndPortalRing find(BlockPos picked, Function<BlockPos, BlockState> states) {
        EndPortalRing result = null;
        for (BlockPos center : BlockPos.betweenClosed(picked.offset(-2, 0, -2), picked.offset(2, 0, 2))) {
            if (Math.abs(center.getX() - picked.getX()) == 2 && Math.abs(center.getZ() - picked.getZ()) == 2) continue;
            var ring = new EndPortalRing(center.immutable(), sockets(center));
            if (!ring.matches(states)) continue;
            if (result != null) throw new IllegalArgumentException("More than one End portal ring matches the selected block.");
            result = ring;
        }
        if (result == null) throw new IllegalArgumentException("Select a complete, loaded End portal frame or its center.");
        return result;
    }

    static List<Socket> sockets(BlockPos center) {
        var result = new ArrayList<Socket>(12);
        for (int d = -1; d <= 1; d++) {
            result.add(new Socket(center.offset(d, 0, -2), Direction.SOUTH));
            result.add(new Socket(center.offset(d, 0, 2), Direction.NORTH));
            result.add(new Socket(center.offset(-2, 0, d), Direction.EAST));
            result.add(new Socket(center.offset(2, 0, d), Direction.WEST));
        }
        return List.copyOf(result);
    }

    boolean matches(Function<BlockPos, BlockState> states) {
        for (Socket socket : sockets) {
            var state = states.apply(socket.at());
            if (state == null || !state.is(Blocks.END_PORTAL_FRAME)
                || state.getValue(EndPortalFrameBlock.FACING) != socket.facing()) return false;
        }
        return true;
    }

    List<BlockPos> missing(ClientLevel level) {
        if (!matches(at -> loaded(level, at) ? level.getBlockState(at) : null)) {
            throw new IllegalStateException("The End portal frame changed or unloaded.");
        }
        return sockets.stream().map(Socket::at)
            .filter(at -> !level.getBlockState(at).getValue(EndPortalFrameBlock.HAS_EYE)).toList();
    }

    BlockPos next(ClientLevel level, Vec3 feet) {
        return missing(level).stream().min(Comparator.comparingDouble(at -> Vec3.atCenterOf(at).distanceToSqr(feet))).orElse(null);
    }

    boolean clearOfOpening(Vec3 feet, double margin) {
        return feet.x < center.getX() - 1 - margin || feet.x > center.getX() + 2 + margin
            || feet.z < center.getZ() - 1 - margin || feet.z > center.getZ() + 2 + margin;
    }

    boolean active(ClientLevel level) {
        return loaded(level, center) && level.getBlockState(center).is(Blocks.END_PORTAL);
    }

    static boolean loaded(ClientLevel level, BlockPos at) {
        return !level.isOutsideBuildHeight(at) && level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
    }
}
