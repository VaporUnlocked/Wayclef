package dev.soprano.map;

/** A client-captured terrain volume. Readers may use it while complete sections are published. */
public interface MapVolume {
    enum OverlayPattern { NONE, HORIZONTAL, DIAGONAL, GRID, DOTS }
    int minX();
    int maxX();
    int minY();
    int maxY();
    int minZ();
    int maxZ();

    /** Positive material ID, zero for air, or minus one for unknown terrain. */
    int blockAt(int x, int y, int z);

    /** Returns minus one for unknown, zero for empty, and one for a populated section. */
    int sectionState(int sectionX, int sectionY, int sectionZ);

    /**
     * An immutable section reader in local index order: (y * 16 + z) * 16 + x.
     * Each occupancy bit represents a 4×4×4 brick in the same axis order.
     */
    interface SectionView {
        int materialAt(int index);
        long occupiedBricks();

        /** Material ID for a uniform occupied section, or zero when it cannot be skipped as uniform. */
        default int uniformSolidMaterial() { return 0; }

        static int brickIndex(int localIndex) {
            return (localIndex >>> 2 & 3) | (localIndex >>> 4 & 12) | (localIndex >>> 6 & 48);
        }
    }

    default boolean hasSectionViews() { return false; }

    /** Returns an immutable captured section, or null when it has not been surveyed. */
    default SectionView sectionView(int sectionX, int sectionY, int sectionZ) { return null; }

    MapMaterial material(int id);
    default boolean plannedMaterial(int material) { return false; }
    default OverlayPattern overlayPattern(int material) { return OverlayPattern.NONE; }

    /** Temporary blueprint geometry can color a ray while leaving its underlying pick intact. */
    default boolean previewMaterial(int material) { return false; }

    /** Committed material in a cell drawn with a temporary preview. */
    default int blockBehindPreview(int x, int y, int z) { return 0; }
    long revision();

    record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public int minX() { return Math.min(x1, x2); }
        public int minY() { return Math.min(y1, y2); }
        public int minZ() { return Math.min(z1, z2); }
        public int maxX() { return Math.max(x1, x2); }
        public int maxY() { return Math.max(y1, y2); }
        public int maxZ() { return Math.max(z1, z2); }
        public long volume() {
            return ((long) maxX() - minX() + 1) * ((long) maxY() - minY() + 1) * ((long) maxZ() - minZ() + 1);
        }
        public boolean contains(int x, int y, int z) {
            return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
        }
    }

    record AreaStats(long known, long solid, long unknown) { }
}
