package dev.soprano.game.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Uses the vanilla shape's paired exits to find reciprocal, height-matched rails. */
final class RailGeometry {
    private RailGeometry() { }

    static List<BlockPos> connections(BlockPos origin, RailShape shape, Function<BlockPos, RailShape> track) {
        var exits = AbstractMinecart.exits(shape);
        List<BlockPos> neighbors = new ArrayList<>(2);
        for (Vec3i exit : List.of(exits.getFirst(), exits.getSecond())) {
            int height = origin.getY() + endHeight(shape, exit);
            for (int neighborY = height; neighborY >= height - 1; neighborY--) {
                BlockPos neighbor = new BlockPos(origin.getX() + exit.getX(), neighborY, origin.getZ() + exit.getZ());
                RailShape adjoining = track.apply(neighbor);
                if (adjoining == null) continue;
                var ends = AbstractMinecart.exits(adjoining);
                if (joins(ends.getFirst(), exit, adjoining, neighborY, height)
                    || joins(ends.getSecond(), exit, adjoining, neighborY, height)) {
                    neighbors.add(neighbor);
                    break;
                }
            }
        }
        return neighbors;
    }

    static List<Vec3> endpoints(BlockPos pos, RailShape shape) {
        var exits = AbstractMinecart.exits(shape);
        return List.of(endpoint(pos, shape, exits.getFirst()), endpoint(pos, shape, exits.getSecond()));
    }

    private static Vec3 endpoint(BlockPos position, RailShape shape, Vec3i exit) {
        return new Vec3(position.getX() + .5 + exit.getX() * .5,
            position.getY() + .125 + endHeight(shape, exit), position.getZ() + .5 + exit.getZ() * .5);
    }

    private static boolean joins(Vec3i back, Vec3i exit, RailShape shape, int neighborY, int height) {
        return back.getX() == -exit.getX() && back.getZ() == -exit.getZ()
            && neighborY + endHeight(shape, back) == height;
    }

    private static int endHeight(RailShape shape, Vec3i exit) {
        return shape.isSlope() ? 1 + exit.getY() : 0;
    }
}
