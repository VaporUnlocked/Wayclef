package dev.soprano.map;

import java.util.Objects;

/** A saved survey and the camera and height cut used to explore it. */
public record MapSnapshot(Volume volume, VoxelRenderer.Camera camera, int cutY, Integer lowerCutY) {
    public MapSnapshot(Volume volume, VoxelRenderer.Camera camera, int cutY) {
        this(volume, camera, cutY, null);
    }

    public MapSnapshot {
        Objects.requireNonNull(volume, "snapshot volume");
        Objects.requireNonNull(camera, "snapshot camera");
        if (cutY < volume.minY() || cutY > volume.maxY()) throw new IllegalArgumentException("Invalid snapshot height cut.");
        if (lowerCutY != null && (lowerCutY < volume.minY() || lowerCutY > cutY))
            throw new IllegalArgumentException("Invalid snapshot lower height cut.");
    }

    /** A published array of references to immutable sections and materials. */
    public static final class Volume implements MapVolume {
        private final Box bounds;
        private final int minSectionX, minSectionY, minSectionZ, width, height, length;
        private final MapSection[] sections;
        private final MapMaterial[] materials;
        private final long revision;
        private final boolean hasRouteFloors;

        /** The capture and decoder hand over arrays that they will never mutate. */
        Volume(Box bounds, MapSection[] sections, MapMaterial[] materials, long revision) {
            this.bounds = bounds;
            minSectionX = bounds.minX() >> 4;
            minSectionY = bounds.minY() >> 4;
            minSectionZ = bounds.minZ() >> 4;
            width = (bounds.maxX() >> 4) - minSectionX + 1;
            height = (bounds.maxY() >> 4) - minSectionY + 1;
            length = (bounds.maxZ() >> 4) - minSectionZ + 1;
            if (sections.length != (long) width * height * length || materials.length == 0)
                throw new IllegalArgumentException("Snapshot storage does not match its bounds.");
            this.sections = sections;
            this.materials = materials;
            this.revision = revision;
            hasRouteFloors = java.util.Arrays.stream(materials).anyMatch(MapMaterial::routeFloor);
        }

        @Override public int minX() { return bounds.minX(); }
        @Override public int minY() { return bounds.minY(); }
        @Override public int minZ() { return bounds.minZ(); }
        @Override public int maxX() { return bounds.maxX(); }
        @Override public int maxY() { return bounds.maxY(); }
        @Override public int maxZ() { return bounds.maxZ(); }
        @Override public long revision() { return revision; }
        @Override public boolean hasSectionViews() { return true; }
        public boolean hasRouteFloors() { return hasRouteFloors; }

        @Override public int blockAt(int x, int y, int z) {
            if (!bounds.contains(x, y, z)) return -1;
            MapSection section = section(x >> 4, y >> 4, z >> 4);
            return section == null ? -1 : section.materialAt(index(x & 15, y & 15, z & 15));
        }

        @Override public int sectionState(int x, int y, int z) {
            MapSection section = section(x, y, z);
            return section == null ? -1 : section.solidCount() == 0 ? 0 : 1;
        }

        @Override public SectionView sectionView(int x, int y, int z) {
            return section(x, y, z);
        }

        private MapSection section(int x, int y, int z) {
            int dx = x - minSectionX, dy = y - minSectionY, dz = z - minSectionZ;
            if (dx < 0 || dx >= width || dy < 0 || dy >= height || dz < 0 || dz >= length) return null;
            return sections[(dz * width + dx) * height + dy];
        }

        @Override public MapMaterial material(int id) {
            return id > 0 && id < materials.length ? materials[id] : materials[0];
        }

        int sectionCount() { return sections.length; }
        MapSection sectionAt(int i) { return sections[i]; }
        int materialCount() { return materials.length; }
        MapMaterial[] materials() { return materials; }

        public AreaStats stats(Box box) {
            return count(this, box);
        }

        /** Shared counts for the live packed capture and its frozen view. */
        static AreaStats count(MapVolume volume, Box box) {
            int x1 = Math.max(box.minX(), volume.minX()), x2 = Math.min(box.maxX(), volume.maxX());
            int y1 = Math.max(box.minY(), volume.minY()), y2 = Math.min(box.maxY(), volume.maxY());
            int z1 = Math.max(box.minZ(), volume.minZ()), z2 = Math.min(box.maxZ(), volume.maxZ());
            long known = 0, solid = 0;
            if (x1 > x2 || y1 > y2 || z1 > z2) return new AreaStats(0, 0, box.volume());

            for (int sz = z1 >> 4; sz <= z2 >> 4; sz++) {
                for (int sx = x1 >> 4; sx <= x2 >> 4; sx++) {
                    for (int sy = y1 >> 4; sy <= y2 >> 4; sy++) {
                        MapSection section = (MapSection) volume.sectionView(sx, sy, sz);
                        if (section == null) continue;
                        int lx = Math.max(0, x1 - sx * 16), hx = Math.min(15, x2 - sx * 16);
                        int ly = Math.max(0, y1 - sy * 16), hy = Math.min(15, y2 - sy * 16);
                        int lz = Math.max(0, z1 - sz * 16), hz = Math.min(15, z2 - sz * 16);
                        int size = (hx - lx + 1) * (hy - ly + 1) * (hz - lz + 1);
                        known += size;
                        if (size == MapSection.CELLS) solid += section.solidCount();
                        else if (section.solidCount() == MapSection.CELLS) solid += size;
                        else if (section.solidCount() > 0) solid += solidIn(section, lx, ly, lz, hx, hy, hz);
                    }
                }
            }
            return new AreaStats(known, solid, box.volume() - known);
        }

        private static int solidIn(MapSection section, int lx, int ly, int lz, int hx, int hy, int hz) {
            int count = 0;
            for (int y = ly; y <= hy; y++) {
                for (int z = lz; z <= hz; z++) {
                    for (int x = lx; x <= hx; x++) {
                        if (section.materialAt(index(x, y, z)) > 0) count++;
                    }
                }
            }
            return count;
        }

        private static int index(int x, int y, int z) { return (y * 16 + z) * 16 + x; }
    }
}
