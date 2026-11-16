package dev.soprano.game;

/** Samples the actual surface under the player's body as it crosses a walking edge. */
final class WalkingSweep {
    static final double MAX_STEP = .60001;
    static final double EPSILON = 1e-5;
    private static final double SAMPLE_DISTANCE = .125;

    interface Geometry {
        double support(double x, double z, double bottom, double top);
        boolean clear(double x, double y, double z, double toX, double toY, double toZ);
    }

    private WalkingSweep() { }

    static boolean canWalk(Geometry geometry, double x, double y, double z, double toX, double toY, double toZ) {
        return canWalk(geometry, x, y, z, toX, toY, toZ, MAX_STEP);
    }

    static boolean canWalkLevel(Geometry geometry, double x, double y, double z, double toX, double toZ) {
        return canWalk(geometry, x, y, z, toX, y, toZ, EPSILON);
    }

    private static boolean canWalk(Geometry geometry, double x, double y, double z, double toX, double toY, double toZ,
                                   double supportRange) {
        double distance = Math.hypot(toX - x, toZ - z);
        int slices = Math.max(1, (int) Math.ceil(distance / SAMPLE_DISTANCE));
        double lastX = x, lastY = y, lastZ = z;
        for (int slice = 1; slice <= slices; slice++) {
            double amount = slice / (double) slices;
            double nextX = x + (toX - x) * amount;
            double nextZ = z + (toZ - z) * amount;
            double nextY = geometry.support(nextX, nextZ, lastY - supportRange, lastY + supportRange);
            if (!Double.isFinite(nextY)) return false;
            double travelY = Math.max(lastY, nextY);
            if (nextY > lastY + EPSILON && !geometry.clear(lastX, lastY, lastZ, lastX, nextY, lastZ))
                return false;
            if (!geometry.clear(lastX, travelY, lastZ, nextX, travelY, nextZ)) return false;
            if (nextY < lastY - EPSILON && !geometry.clear(nextX, lastY, nextZ, nextX, nextY, nextZ))
                return false;
            lastX = nextX;
            lastY = nextY;
            lastZ = nextZ;
        }
        return Math.abs(lastY - toY) <= EPSILON;
    }
}
