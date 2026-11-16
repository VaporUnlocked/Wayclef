package dev.soprano.path;

import dev.soprano.path.RouteExclusions.Bounds;

import java.util.List;

/** Selection boundary and optional road preference for one walking search. */
public record RoutePolicy(Bounds selection, List<Bounds> corridors) {
    public static final RoutePolicy UNRESTRICTED = new RoutePolicy(null, List.of());
    private static final int OFF_CORRIDOR_COST = 42;
    private static final double EPSILON = 1e-6;

    public RoutePolicy { corridors = List.copyOf(corridors); }

    /** A swept box between two samples stays inside a convex selection if both endpoint boxes do. */
    public boolean containsBody(double x, double y, double z, double halfWidth, double height) {
        return selection == null || x - halfWidth >= selection.minX() - EPSILON
            && x + halfWidth <= selection.maxX() + EPSILON && y >= selection.minY() - EPSILON
            && y + height <= selection.maxY() + EPSILON && z - halfWidth >= selection.minZ() - EPSILON
            && z + halfWidth <= selection.maxZ() + EPSILON;
    }

    public int penalty(Node target) {
        if (corridors.isEmpty()) return 0;
        for (Bounds corridor : corridors) {
            if (target.centerX() >= corridor.minX() && target.centerX() < corridor.maxX()
                && target.centerZ() >= corridor.minZ() && target.centerZ() < corridor.maxZ()
                && target.y() >= corridor.minY() && target.y() <= corridor.maxY()) return 0;
        }
        return OFF_CORRIDOR_COST;
    }
}
