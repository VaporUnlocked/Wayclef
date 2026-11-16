package dev.soprano.game.transport;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.Map;

public final class RailGeometryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos west = origin.west();
        BlockPos east = origin.east();
        BlockPos eastUp = east.above();
        BlockPos south = origin.south();

        var flat = Map.of(west, RailShape.EAST_WEST, east, RailShape.NORTH_SOUTH);
        var neighbors = RailGeometry.connections(origin, RailShape.EAST_WEST, flat::get);
        check(neighbors.size() == 1 && neighbors.contains(west), "require reciprocal exits");

        var slope = Map.of(origin, RailShape.ASCENDING_EAST, west, RailShape.EAST_WEST, eastUp, RailShape.EAST_WEST);
        neighbors = RailGeometry.connections(origin, RailShape.ASCENDING_EAST, slope::get);
        check(neighbors.size() == 2 && neighbors.contains(west) && neighbors.contains(eastUp), "ascending rail joins the raised east rail");
        check(RailGeometry.connections(eastUp, RailShape.EAST_WEST, slope::get).contains(origin), "high rail connects down the slope");
        check(RailGeometry.connections(origin, RailShape.ASCENDING_EAST,
            Map.of(east, RailShape.EAST_WEST)::get).isEmpty(), "reject same-height rail at the slope's high end");

        var corner = Map.of(east, RailShape.EAST_WEST, south, RailShape.NORTH_SOUTH, west, RailShape.EAST_WEST);
        neighbors = RailGeometry.connections(origin, RailShape.SOUTH_EAST, corner::get);
        check(neighbors.size() == 2 && neighbors.contains(east) && neighbors.contains(south), "curved rail exposes its two ends");
        System.out.println("Soprano rail geometry checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
