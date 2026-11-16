package dev.soprano.path;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable exclusion volumes for a single route-planning or validation snapshot. */
public final class RouteExclusions {
    public enum Kind {
        NO_GO_ZONE,
        HAZARD,
        MOB
    }

    /** Center of the body's footprint at foot height. */
    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Route positions must be finite");
            }
        }
    }

    public record Footprint(double width, double height) {
        public Footprint {
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Route footprint dimensions must be finite and positive");
            }
        }
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds {
            if (!finiteRange(minX, maxX) || !finiteRange(minY, maxY) || !finiteRange(minZ, maxZ)) {
                throw new IllegalArgumentException("Exclusion volumes must have finite, ordered bounds and positive size");
            }
        }

        /** Includes the whole block at each selected corner, regardless of selection order. */
        public static Bounds blocksInclusive(int firstX, int firstY, int firstZ,
                                             int secondX, int secondY, int secondZ) {
            return new Bounds(Math.min(firstX, secondX), Math.min(firstY, secondY), Math.min(firstZ, secondZ),
                Math.max(firstX, secondX) + 1.0, Math.max(firstY, secondY) + 1.0, Math.max(firstZ, secondZ) + 1.0);
        }

        private static boolean finiteRange(double min, double max) {
            return Double.isFinite(min) && Double.isFinite(max) && min < max;
        }
    }

    public record Exclusion(Kind kind, String name, Bounds bounds) {
        public Exclusion {
            Objects.requireNonNull(kind, "kind");
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty()) throw new IllegalArgumentException("An exclusion needs a name");
            Objects.requireNonNull(bounds, "bounds");
        }

        public String reason() {
            return switch (kind) {
                case NO_GO_ZONE -> "No-go zone " + name;
                case HAZARD -> "Hazard " + name;
                case MOB -> "Mob clearance around " + name;
            };
        }
    }

    private final List<Exclusion> active;

    /** No-go zones and hazards are always active. Mob clearance is disabled by default. */
    public RouteExclusions(List<Exclusion> exclusions) {
        this(exclusions, false);
    }

    public RouteExclusions(List<Exclusion> exclusions, boolean avoidMobs) {
        List<Exclusion> snapshot = List.copyOf(exclusions);
        this.active = avoidMobs ? snapshot : snapshot.stream().filter(exclusion -> exclusion.kind != Kind.MOB).toList();
    }

    public boolean isEmpty() { return active.isEmpty(); }

    public Optional<Exclusion> blockingAt(Point position, Footprint footprint) {
        return blockingSegment(position, position, footprint);
    }

    /**
     * Checks a straight foot-center segment with a fixed-size body. Boundary contact alone is permitted.
     * The first obstruction wins, with ties resolved in snapshot order.
     */
    public Optional<Exclusion> blockingSegment(Point from, Point to, Footprint footprint) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(footprint, "footprint");
        Exclusion first = null;
        double firstTime = Double.POSITIVE_INFINITY;
        for (Exclusion exclusion : active) {
            double hitTime = entryTime(from, to, footprint, exclusion.bounds);
            if (hitTime < firstTime) {
                first = exclusion;
                firstTime = hitTime;
                if (firstTime == 0) break;
            }
        }
        return Optional.ofNullable(first);
    }

    /** Checks all linear pieces in travel order. Supply intermediate points for jumps and other curves. */
    public Optional<Exclusion> blockingPath(List<Point> footCenters, Footprint footprint) {
        Objects.requireNonNull(footprint, "footprint");
        List<Point> points = List.copyOf(footCenters);
        if (points.size() == 1) return blockingAt(points.getFirst(), footprint);
        for (int i = 1; i < points.size(); i++) {
            Optional<Exclusion> blocking = blockingSegment(points.get(i - 1), points.get(i), footprint);
            if (blocking.isPresent()) return blocking;
        }
        return Optional.empty();
    }

    private static double entryTime(Point from, Point to, Footprint footprint, Bounds bounds) {
        // Expand the volume by the body and intersect the foot-center segment against that volume.
        double radius = footprint.width / 2.0;
        double minX = bounds.minX - radius;
        double maxX = bounds.maxX + radius;
        double minY = bounds.minY - footprint.height;
        double minZ = bounds.minZ - radius;
        double maxZ = bounds.maxZ + radius;

        double enter = Math.max(0, Math.max(axisEnter(from.x, to.x, minX, maxX),
            Math.max(axisEnter(from.y, to.y, minY, bounds.maxY), axisEnter(from.z, to.z, minZ, maxZ))));
        if (enter >= 1) return Double.POSITIVE_INFINITY;
        double leave = Math.min(1, Math.min(axisLeave(from.x, to.x, minX, maxX),
            Math.min(axisLeave(from.y, to.y, minY, bounds.maxY), axisLeave(from.z, to.z, minZ, maxZ))));
        return enter < leave ? enter : Double.POSITIVE_INFINITY;
    }

    private static double axisEnter(double from, double to, double min, double max) {
        if (from == to) return from > min && from < max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        return crossingFraction(from, to, from < to ? min : max);
    }

    private static double axisLeave(double from, double to, double min, double max) {
        if (from == to) return Double.POSITIVE_INFINITY;
        return crossingFraction(from, to, from < to ? max : min);
    }

    private static double crossingFraction(double from, double to, double boundary) {
        double displacement = to - from;
        if (Double.isInfinite(displacement)) {
            return (boundary * 0.5 - from * 0.5) / (to * 0.5 - from * 0.5);
        }
        return (boundary - from) / displacement;
    }
}
