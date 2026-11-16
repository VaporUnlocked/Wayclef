package dev.soprano.map;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** An original 8×8 ARGB tile using the captured block's color palette. */
public final class MapMaterial {
    public static final int TILE_SIZE = 8;
    public static final int MAX_BOXES = 24;

    /** One axis-aligned part of a captured block outline, clipped to its voxel. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box {
            if (!(0 <= minX && minX < maxX && maxX <= 1 && 0 <= minY && minY < maxY && maxY <= 1
                && 0 <= minZ && minZ < maxZ && maxZ <= 1)) {
                throw new IllegalArgumentException("Block outline must fit within one voxel.");
            }
        }
    }
    private final String name;
    private final int[] tile;
    private final int[] quarterArea;
    private final int[] sixteenthArea;
    private final int average;
    private final List<Box> boxes;
    private final boolean routeFloor;

    public MapMaterial(String name, int[] tile) {
        this(name, tile, List.of());
    }

    public MapMaterial(String name, int[] tile, List<Box> boxes) {
        this(name, tile, boxes, false);
    }

    public MapMaterial(String name, int[] tile, List<Box> boxes, boolean routeFloor) {
        this.name = Objects.requireNonNull(name);
        if (tile.length != TILE_SIZE * TILE_SIZE) throw new IllegalArgumentException("Expected an 8×8 block tile.");
        if (boxes.size() > MAX_BOXES) throw new IllegalArgumentException("Block outline has too many parts.");
        this.boxes = List.copyOf(boxes);
        this.routeFloor = routeFloor;
        this.tile = tile.clone();
        quarterArea = downsample(this.tile, TILE_SIZE);
        sixteenthArea = downsample(quarterArea, TILE_SIZE / 2);
        average = downsample(sixteenthArea, TILE_SIZE / 4)[0];
    }

    public String name() { return name; }
    public List<Box> boxes() { return boxes; }
    public boolean routeFloor() { return routeFloor; }

    @Override public boolean equals(Object other) {
        return other instanceof MapMaterial material && routeFloor == material.routeFloor && name.equals(material.name)
            && boxes.equals(material.boxes) && Arrays.equals(tile, material.tile);
    }

    @Override public int hashCode() { return 31 * Objects.hash(name, boxes, routeFloor) + Arrays.hashCode(tile); }

    public static List<Box> outline(VoxelShape shape) {
        if (shape.isEmpty() || Block.isShapeFullBlock(shape)) return List.of();
        List<AABB> pieces = shape.toAabbs();
        if (pieces.size() > MAX_BOXES) return List.of();
        List<Box> result = new ArrayList<>(pieces.size());
        for (AABB box : pieces) {
            double minX = Math.max(0, box.minX), minY = Math.max(0, box.minY), minZ = Math.max(0, box.minZ);
            double maxX = Math.min(1, box.maxX), maxY = Math.min(1, box.maxY), maxZ = Math.min(1, box.maxZ);
            if (minX < maxX && minY < maxY && minZ < maxZ) result.add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
        }
        return result;
    }

    public int pixel(int u, int v) {
        return tile[v * TILE_SIZE + u];
    }

    /** Uses the original texels up close and averages them when they are smaller than a screen pixel. */
    int sample(double u, double v, double blockFootprint) {
        if (blockFootprint >= 1) return average;
        int x = Math.clamp((int) (u * TILE_SIZE), 0, TILE_SIZE - 1);
        int y = Math.clamp((int) (v * TILE_SIZE), 0, TILE_SIZE - 1);
        if (blockFootprint <= .125) return pixel(x, y);
        int small = sixteenthArea[(y >> 2) * 2 + (x >> 2)];
        if (blockFootprint >= .5) return mix(small, average, blockFootprint * 2 - 1);
        int medium = quarterArea[(y >> 1) * 4 + (x >> 1)];
        if (blockFootprint >= .25) return mix(medium, small, blockFootprint * 4 - 1);
        return mix(pixel(x, y), medium, blockFootprint * 8 - 1);
    }

    private static int[] downsample(int[] colors, int size) {
        int width = size / 2;
        int[] reduced = new int[width * width];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                int top = y * 2 * size + x * 2;
                int bottom = top + size;
                int first = colors[top], second = colors[top + 1], third = colors[bottom], fourth = colors[bottom + 1];
                int red = averageChannel(first >>> 16, second >>> 16, third >>> 16, fourth >>> 16);
                int green = averageChannel(first >>> 8, second >>> 8, third >>> 8, fourth >>> 8);
                int blue = averageChannel(first, second, third, fourth);
                reduced[y * width + x] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        return reduced;
    }

    private static int averageChannel(int first, int second, int third, int fourth) {
        return ((first & 255) + (second & 255) + (third & 255) + (fourth & 255) + 2) / 4;
    }

    private static int mix(int first, int second, double blend) {
        int red = channel(first >>> 16, second >>> 16, blend);
        int green = channel(first >>> 8, second >>> 8, blend);
        int blue = channel(first, second, blend);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int channel(int first, int second, double blend) {
        int from = first & 255;
        return from + (int) Math.round(((second & 255) - from) * blend);
    }
}
