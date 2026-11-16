package dev.soprano.map;

import dev.soprano.path.Node;
import dev.soprano.path.Step;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Elevation samples stop at the last path edge supplied by the planner. */
public final class RouteElevation {
    public record Sample(double distance, Vec3 position) {
        public double y() { return position.y; }
    }

    public record Profile(List<Sample> samples, double minY, double maxY, double distance, boolean partial,
                          boolean snapshotOnly) {
        public Profile { samples = List.copyOf(samples); }
    }

    private RouteElevation() { }

    public static Profile walking(List<Step> path, Node start, boolean partial, boolean snapshotOnly) {
        if (path.isEmpty() && start == null) return null;
        List<Vec3> points = new ArrayList<>(path.size() + 1);
        Node end = start == null ? path.getFirst().from() : start;
        points.add(center(end));
        for (Step step : path) {
            if (!step.from().equals(end)) { partial = true; break; }
            end = step.to();
            points.add(center(end));
        }
        return sample(points, partial, snapshotOnly);
    }

    public static Profile transport(List<Vec3> points) {
        return points.isEmpty() ? null : sample(points, false, false);
    }

    private static Profile sample(List<Vec3> points, boolean partial, boolean snapshotOnly) {
        List<Sample> samples = new ArrayList<>(points.size());
        Vec3 previous = points.getFirst();
        double minY = previous.y, maxY = previous.y, distance = 0;
        for (Vec3 point : points) {
            distance += previous.distanceTo(point);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
            samples.add(new Sample(distance, point));
            previous = point;
        }
        return new Profile(samples, minY, maxY, distance, partial, snapshotOnly);
    }

    private static Vec3 center(Node node) { return new Vec3(node.centerX(), node.y(), node.centerZ()); }
}
