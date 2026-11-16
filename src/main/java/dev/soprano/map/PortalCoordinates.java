package dev.soprano.map;

import dev.soprano.map.VoxelRenderer.Hit;
import net.minecraft.core.BlockPos;

/** The ordinary 8:1 horizontal scale for the vanilla Overworld and Nether. */
public record PortalCoordinates(String dimension, int x, int z, boolean inRange) {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public record Projection(String dimension, BlockPos position, int searchRadius) { }

    public static Projection project(String dimension, BlockPos position) {
        if (position == null || dimension == null) return null;
        if (dimension.equals(OVERWORLD)) {
            return new Projection(NETHER, new BlockPos(Math.floorDiv(position.getX(), 8), position.getY(),
                Math.floorDiv(position.getZ(), 8)), 16);
        }
        if (!dimension.equals(NETHER)) return null;
        long x = (long) position.getX() * 8, z = (long) position.getZ() * 8;
        if (x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000) return null;
        return new Projection(OVERWORLD, new BlockPos((int) x, position.getY(), (int) z), 128);
    }

    public static PortalCoordinates equivalent(String sourceDimension, Hit position) {
        if (position == null || sourceDimension == null) return null;
        return switch (sourceDimension) {
            case "minecraft:overworld" -> new PortalCoordinates("Nether", Math.floorDiv(position.x(), 8),
                Math.floorDiv(position.z(), 8), true);
            case "minecraft:the_nether" -> netherToOverworld(position);
            default -> null;
        };
    }

    private static PortalCoordinates netherToOverworld(Hit position) {
        long x = (long) position.x() * 8, z = (long) position.z() * 8;
        boolean inRange = x >= -30_000_000 && x < 30_000_000 && z >= -30_000_000 && z < 30_000_000;
        return new PortalCoordinates("Overworld", (int) x, (int) z, inRange);
    }
}
