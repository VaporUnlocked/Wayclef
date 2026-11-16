package dev.soprano.path;

/** The center of a walkable column at a particular feet height. */
public record Node(int x, int y16, int z) {
    public static final int MIN_COLUMN = -30_000_000;
    public static final int MAX_COLUMN = 29_999_999;
    public static final int MIN_Y16 = -65_536;
    public static final int MAX_Y16 = 65_536;

    public Node {
        if (!validColumn(x) || !validColumn(z) || y16 < MIN_Y16 || y16 > MAX_Y16) {
            throw new IllegalArgumentException("Navigation position is outside the supported world bounds.");
        }
    }

    public static boolean validColumn(int coordinate) {
        return coordinate >= MIN_COLUMN && coordinate <= MAX_COLUMN;
    }

    public double centerX() { return x + .5; }
    public double centerZ() { return z + .5; }
    public double y() { return y16 / 16.0; }
}
