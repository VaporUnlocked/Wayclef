package dev.soprano.path;

import dev.soprano.path.RouteExclusions.Bounds;

import java.util.Objects;

/** An arrival area with optional height, visibility, observed-block and selection constraints. */
public record Goal(int x, Integer y16, int z, int radius, Integer sightY, HeightBand heightBand,
                   String observedBlockId, Bounds bounds, boolean skyExposed) {
    public record HeightBand(int minY16, int maxY16) {
        public HeightBand {
            if (minY16 < Node.MIN_Y16 || maxY16 > Node.MAX_Y16 || minY16 > maxY16) {
                throw new IllegalArgumentException("Enter an ordered height range within the supported world bounds.");
            }
        }
    }

    public Goal(int x, Integer y16, int z, int radius) {
        this(x, y16, z, radius, null, null, null, null);
    }

    public Goal(int x, Integer y16, int z, int radius, Integer sightY) {
        this(x, y16, z, radius, sightY, null, null, null);
    }

    public Goal(int x, Integer y16, int z, int radius, Integer sightY, HeightBand heightBand,
                String observedBlockId, Bounds bounds) {
        this(x, y16, z, radius, sightY, heightBand, observedBlockId, bounds, false);
    }

    public Goal {
        if (!Node.validColumn(x) || !Node.validColumn(z)
            || y16 != null && (y16 < Node.MIN_Y16 || y16 > Node.MAX_Y16)) {
            throw new IllegalArgumentException("Navigation goal is outside the supported world bounds.");
        }
        if (radius < 0 || radius > 1_024) {
            throw new IllegalArgumentException("Goal radius must be between 0 and 1,024 blocks.");
        }
        if (sightY != null && (y16 != null || radius < 1 || radius > 32
            || sightY < Math.floorDiv(Node.MIN_Y16, 16) || sightY >= Math.floorDiv(Node.MAX_Y16, 16))) {
            throw new IllegalArgumentException("A line-of-sight goal needs a valid target and range of 1-32 blocks.");
        }
        if (heightBand != null && (y16 != null || sightY != null || radius < 1)) {
            throw new IllegalArgumentException("A height goal needs a horizontal search limit of 1-1,024 blocks.");
        }
        if (skyExposed && (y16 != null || sightY != null || heightBand != null || radius < 1)) {
            throw new IllegalArgumentException("A surface goal needs a horizontal search limit of 1-1,024 blocks.");
        }
        if (observedBlockId != null && (sightY == null || observedBlockId.isBlank())) {
            throw new IllegalArgumentException("An observed block goal needs a block ID and position.");
        }
        if (bounds != null && (x + radius + .5 < bounds.minX() || x - radius + .5 >= bounds.maxX()
            || z + radius + .5 < bounds.minZ() || z - radius + .5 >= bounds.maxZ()
            || y16 != null && (y16 / 16.0 < bounds.minY() || y16 / 16.0 >= bounds.maxY())
            || heightBand != null && (heightBand.maxY16() / 16.0 < bounds.minY()
                || heightBand.minY16() / 16.0 >= bounds.maxY()))) {
            throw new IllegalArgumentException("The destination has no overlap with the selected route volume.");
        }
    }

    public static Goal lineOfSight(int x, int y, int z, int range) { return new Goal(x, null, z, range, y); }

    public static Goal observedBlock(int x, int y, int z, String id) {
        return new Goal(x, null, z, 4, y, null, Objects.requireNonNull(id, "block ID"), null);
    }

    public static Goal height(int x, int z, int minY16, int maxY16, int range) {
        return new Goal(x, null, z, range, null, new HeightBand(minY16, maxY16), null, null);
    }

    public static Goal surface(int x, int z, int range) {
        return new Goal(x, null, z, range, null, null, null, null, true);
    }

    public Goal within(Bounds selection) {
        return new Goal(x, y16, z, radius, sightY, heightBand, observedBlockId,
            Objects.requireNonNull(selection, "route selection"), skyExposed);
    }

    public boolean matches(Node node) {
        return allows(node) && horizontalX(node) == 0 && horizontalZ(node) == 0 && vertical(node) == 0;
    }

    boolean allows(Node node) {
        return bounds == null || (node.centerX() >= bounds.minX() && node.centerX() < bounds.maxX()
            && node.centerZ() >= bounds.minZ() && node.centerZ() < bounds.maxZ()
            && node.y() >= bounds.minY() && node.y() < bounds.maxY());
    }

    long horizontalX(Node node) { return Math.max(0, Math.abs((long) node.x() - x) - radius); }
    long horizontalZ(Node node) { return Math.max(0, Math.abs((long) node.z() - z) - radius); }
    long vertical(Node node) {
        if (y16 != null) return Math.abs((long) node.y16() - y16);
        if (heightBand == null) return 0;
        return Math.max(0L, Math.max((long) heightBand.minY16() - node.y16(), (long) node.y16() - heightBand.maxY16()));
    }

    boolean above(Node node) {
        return y16 != null ? y16 > node.y16() : heightBand != null && heightBand.minY16() > node.y16();
    }
}
