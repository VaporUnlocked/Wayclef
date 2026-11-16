package dev.soprano.map;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Computes a preview against one immutable survey without a live player or movement controller. */
public final class SurveyRoute {
    private static final int MAX_NODES = 60_000;
    private static final double HALF_WIDTH = .3;
    private static final double HEIGHT = 1.8;
    private static final double JUMP_APEX = 1.253;
    private static final double CONTACT = .015;
    private static final RouteExclusions.Footprint BODY = new RouteExclusions.Footprint(.6, HEIGHT + CONTACT);

    public record Preview(List<Step> steps, Node start, boolean partial, String message) {
        public Preview { steps = List.copyOf(steps); }
    }

    public record DedicatedCandidate(List<Step> steps, String deviceName) {
        public DedicatedCandidate { steps = List.copyOf(steps); }
    }

    private SurveyRoute() { }

    public static Preview plan(MapSnapshot.Volume survey, VoxelRenderer.Hit first, VoxelRenderer.Hit second,
                               RouteExclusions exclusions) {
        return plan(survey, first, second, exclusions, false);
    }

    public static Preview plan(MapSnapshot.Volume survey, VoxelRenderer.Hit first, VoxelRenderer.Hit second,
                               RouteExclusions exclusions, boolean dedicatedGpu) {
        if (!survey.hasRouteFloors()) return failure("This survey has no recorded walkable floors. Older surveys need a fresh capture for route previews.");
        var terrain = new SurveyTerrain(survey, exclusions);
        Node start = terrain.selectedFloor(first), end = terrain.selectedFloor(second);
        if (start == null) return failure("Select a start floor with surveyed standing space above it.");
        if (end == null) return failure("Select a destination floor with surveyed standing space above it.");
        if (dedicatedGpu) {
            var candidate = GpuSurveyRoute.plan(survey, terrain, start, end);
            if (candidate != null) return new Preview(candidate.steps(), start, false,
                "Survey route verified after GPU search on " + candidate.deviceName() + ". Travel needs a live route.");
        }
        int range = Math.clamp(Math.max(survey.maxX() - survey.minX(), survey.maxZ() - survey.minZ()), 1, 4_096);
        var config = new IncrementalSearch.Config(true, true, 1, range, MAX_NODES, true);
        var search = new IncrementalSearch(terrain, start, new Goal(end.x(), end.y16(), end.z(), 0), config);
        var result = search.result();
        while (result.status() == IncrementalSearch.Status.SEARCHING) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            result = search.advance(160, 5_000_000L);
        }
        return switch (result.status()) {
            case FOUND -> new Preview(result.path(), start, false, result.path().isEmpty()
                ? "Both points use the same surveyed floor." : "Survey preview ready. Travel needs a live route.");
            case PARTIAL -> new Preview(result.path(), start, true, "Partial survey preview. The remaining terrain is unverified.");
            default -> failure("No route found between the points in surveyed terrain.");
        };
    }

    /** May run off the client thread. A live caller must independently confirm every returned step. */
    public static DedicatedCandidate tryDedicatedCandidate(MapSnapshot.Volume survey, Node start, Node end,
                                                            RouteExclusions exclusions) {
        if (!survey.hasRouteFloors() || start.y16() % 16 != 0 || end.y16() % 16 != 0) return null;
        var terrain = new SurveyTerrain(survey, exclusions);
        if (!terrain.canStand(start.x(), start.y16() / 16, start.z())
            || !terrain.canStand(end.x(), end.y16() / 16, end.z())) return null;
        var result = GpuSurveyRoute.plan(survey, terrain, start, end);
        return result == null ? null : new DedicatedCandidate(result.steps(), result.deviceName());
    }

    private static Preview failure(String message) { return new Preview(List.of(), null, false, message); }

    private record SurveyTerrain(MapSnapshot.Volume survey, RouteExclusions exclusions) implements Terrain {
        Node selectedFloor(VoxelRenderer.Hit block) {
            int y = block.y() + 1;
            if (y * 16L < Node.MIN_Y16 || y * 16L > Node.MAX_Y16
                || !Node.validColumn(block.x()) || !Node.validColumn(block.z())) return null;
            return canStand(block.x(), y, block.z()) ? new Node(block.x(), y * 16, block.z()) : null;
        }

        @Override public List<Node> floors(int x, int z, int minY16, int maxY16) {
            int bottom = Math.max(survey.minY() + 1, Math.ceilDiv(minY16, 16));
            int top = Math.min(survey.maxY() - 1, Math.floorDiv(maxY16, 16));
            List<Node> nodes = new ArrayList<>(2);
            for (int y = bottom; y <= top; y++) if (canStand(x, y, z)) nodes.add(new Node(x, y * 16, z));
            return nodes;
        }

        private boolean canStand(int x, int y, int z) {
            int floor = survey.blockAt(x, y - 1, z);
            if (floor <= 0 || !survey.material(floor).routeFloor()) return false;
            return clear(x + .5, y, z + .5, x + .5, y, z + .5);
        }

        @Override public boolean canTraverse(Node from, Node to, Move move) {
            int dx = Math.abs(to.x() - from.x()), dz = Math.abs(to.z() - from.z());
            if (dx > 1 || dz > 1 || dx + dz == 0) return false;
            if (move == Move.WALK && to.y16() == from.y16()) {
                if (dx + dz == 2 && (!canStand(to.x(), from.y16() / 16, from.z())
                    || !canStand(from.x(), from.y16() / 16, to.z()))) return false;
                return clear(from.centerX(), from.y(), from.centerZ(), to.centerX(), to.y(), to.centerZ());
            }
            if (dx + dz != 1) return false;
            double travelY;
            if (move == Move.JUMP && to.y16() - from.y16() == 16) travelY = from.y() + JUMP_APEX;
            else if (move == Move.DROP && from.y16() - to.y16() == 16) travelY = from.y();
            else return false;
            return clear(from.centerX(), from.y(), from.centerZ(), from.centerX(), travelY, from.centerZ())
                && clear(from.centerX(), travelY, from.centerZ(), to.centerX(), travelY, to.centerZ())
                && clear(to.centerX(), travelY, to.centerZ(), to.centerX(), to.y(), to.centerZ());
        }

        private boolean clear(double x, double y, double z, double toX, double toY, double toZ) {
            if (!exclusions.isEmpty() && exclusions.blockingSegment(new RouteExclusions.Point(x, y - CONTACT, z),
                new RouteExclusions.Point(toX, toY - CONTACT, toZ), BODY).isPresent()) return false;
            int minX = cell(Math.min(x, toX) - HALF_WIDTH), maxX = cell(Math.max(x, toX) + HALF_WIDTH);
            int minY = cell(Math.min(y, toY)), maxY = cell(Math.max(y, toY) + HEIGHT);
            int minZ = cell(Math.min(z, toZ) - HALF_WIDTH), maxZ = cell(Math.max(z, toZ) + HALF_WIDTH);
            for (int blockX = minX; blockX <= maxX; blockX++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    for (int blockY = minY; blockY <= maxY; blockY++) {
                        if (survey.blockAt(blockX, blockY, blockZ) != 0) return false;
                    }
                }
            }
            return true;
        }

        private static int cell(double value) { return (int) Math.floor(value); }
    }
}
