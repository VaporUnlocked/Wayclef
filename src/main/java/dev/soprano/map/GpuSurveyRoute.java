package dev.soprano.map;

import dev.soprano.gpu.DiscreteCompute;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CancellationException;

/** Finds an optimal route in a bounded immutable survey on a discrete compute device. */
final class GpuSurveyRoute {
    private static final int GROUP_SIZE = 128;
    private static final int MAX_CELLS = 131_072;
    private static final int MAX_PASSES = 256;
    private static final int HORIZONTAL_MARGIN = 12, VERTICAL_MARGIN = 8;
    private static final int UNREACHABLE = 1_000_000_000;
    private static final int[][] DIRECTIONS = {
        {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1},
        {1, 0, 1}, {-1, 0, 1}, {-1, 0, -1}, {1, 0, -1},
        {1, 1, 0}, {0, 1, 1}, {-1, 1, 0}, {0, 1, -1},
        {1, -1, 0}, {0, -1, 1}, {-1, -1, 0}, {0, -1, -1}
    };
    private static final int[] COSTS = movementCosts();
    private static final int MIN_VERTICAL_SURCHARGE = Math.min(COSTS[8], COSTS[12]) - COSTS[0];

    // Voxel data, movement masks, and two alternating distance fields share one storage buffer.
    static final String SHADER = """
        #version 450
        layout(local_size_x = 128) in;
        layout(std430, set = 0, binding = 0) buffer State { int data[]; };
        layout(push_constant) uniform Params {
            int iteration;
            int width;
            int height;
            int depth;
            int goal;
        } p;
        const int infinity = 1000000000;
        const ivec3 up = ivec3(0, 1, 0);
        const ivec3 direction[16] = ivec3[16](@directions@);
        const int weight[16] = int[16](@weights@);

        int indexOf(ivec3 q) { return (q.y * p.depth + q.z) * p.width + q.x; }

        int voxel(ivec3 q) {
            if (any(lessThan(q, ivec3(0))) || any(greaterThanEqual(q, ivec3(p.width, p.height, p.depth)))) return -1;
            return data[indexOf(q)];
        }

        bool standable(ivec3 q) {
            return q.y >= 1 && q.y < p.height - 3
                && voxel(q - up) == 2 && voxel(q) == 0 && voxel(q + up) == 0;
        }

        int moves(ivec3 q) {
            if (!standable(q)) return 0;
            int mask = 0;
            for (int d = 0; d < 16; ++d) {
                ivec3 delta = direction[d];
                ivec3 to = q + delta;
                if (!standable(to)) continue;
                if (d >= 4 && d < 8 && (!standable(q + ivec3(delta.x, 0, 0))
                    || !standable(q + ivec3(0, 0, delta.z)))) continue;
                if (d >= 8 && d < 12 && (voxel(q + 2 * up) != 0 || voxel(q + 3 * up) != 0
                    || voxel(to + 2 * up) != 0)) continue;
                if (d >= 12 && voxel(to + 2 * up) != 0) continue;
                mask |= 1 << d;
            }
            return mask;
        }

        void main() {
            int i = int(gl_GlobalInvocationID.x);
            int n = p.width * p.height * p.depth;
            if (i >= n) return;
            ivec3 q = ivec3(i % p.width, (i / p.width) / p.depth, (i / p.width) % p.depth);
            if (p.iteration == 0) {
                data[n + i] = moves(q);
                data[2 * n + i] = i == p.goal ? 0 : infinity;
                data[3 * n + i] = infinity;
                return;
            }
            int readBase = ((p.iteration & 1) == 1 ? 2 : 3) * n;
            int writeBase = 5 * n - readBase;
            int mask = data[n + i];
            int best = data[readBase + i];
            while (mask != 0) {
                int d = findLSB(mask);
                best = min(best, weight[d] + data[readBase + indexOf(q + direction[d])]);
                mask &= mask - 1;
            }
            data[writeBase + i] = best;
        }
        """.replace("@directions@", shaderDirections()).replace("@weights@", shaderWeights());

    record Result(List<Step> steps, String deviceName) { }

    record Grid(int minX, int minY, int minZ, int width, int height, int depth) {
        int cells() { return width * height * depth; }
        int maxX() { return minX + width - 1; }
        int maxZ() { return minZ + depth - 1; }
        int minStandY() { return minY + 1; }
        int maxStandY() { return minY + height - 4; }

        boolean contains(Node node) {
            return node.x() >= minX && node.x() <= maxX() && node.z() >= minZ && node.z() <= maxZ()
                && node.y() >= minStandY() && node.y() <= maxStandY();
        }

        int index(Node node) {
            return ((node.y16() / 16 - minY) * depth + node.z() - minZ) * width + node.x() - minX;
        }
    }

    private GpuSurveyRoute() { }

    static Result plan(MapSnapshot.Volume survey, Terrain terrain, Node start, Node end) {
        if (start.equals(end) || !DiscreteCompute.mayRun()) return null;
        Grid grid = crop(survey, start, end);
        if (grid == null) return null;
        int n = grid.cells();
        int passes = Math.min(MAX_PASSES, grid.width() + grid.depth() + grid.height() + 16);
        int[] storage = voxelBuffer(survey, grid);
        DiscreteCompute.Result computed;
        try {
            computed = DiscreteCompute.run(SHADER, storage, Math.ceilDiv(n, GROUP_SIZE), passes + 1,
                new int[] {grid.width(), grid.height(), grid.depth(), grid.index(end)});
        } catch (IllegalStateException | LinkageError unavailable) {
            return null;
        }
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        List<Step> route = verify(computed.storage(), survey, terrain, grid, start, end, passes);
        return route == null ? null : new Result(route, computed.deviceName());
    }

    static Grid crop(MapSnapshot.Volume survey, Node start, Node end) {
        int minX = Math.max(survey.minX(), Math.min(start.x(), end.x()) - HORIZONTAL_MARGIN);
        int minZ = Math.max(survey.minZ(), Math.min(start.z(), end.z()) - HORIZONTAL_MARGIN);
        int minY = Math.max(survey.minY() + 1, Math.min(start.y16(), end.y16()) / 16 - VERTICAL_MARGIN) - 1;
        int maxX = Math.min(survey.maxX(), Math.max(start.x(), end.x()) + HORIZONTAL_MARGIN);
        int maxZ = Math.min(survey.maxZ(), Math.max(start.z(), end.z()) + HORIZONTAL_MARGIN);
        int maxY = Math.min(survey.maxY() - 1, Math.max(start.y16(), end.y16()) / 16 + VERTICAL_MARGIN) + 3;
        int width = maxX - minX + 1, height = maxY - minY + 1, depth = maxZ - minZ + 1;
        return (long) width * height * depth > MAX_CELLS ? null : new Grid(minX, minY, minZ, width, height, depth);
    }

    private static int[] voxelBuffer(MapSnapshot.Volume survey, Grid grid) {
        int[] buffer = new int[4 * grid.cells()];
        int index = 0;
        for (int y = grid.minY(); y < grid.minY() + grid.height(); y++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) for (int x = grid.minX(); x <= grid.maxX(); x++) {
                int material = survey.blockAt(x, y, z);
                buffer[index++] = material <= 0 ? material : survey.material(material).routeFloor() ? 2 : 1;
            }
        }
        return buffer;
    }

    static List<Step> verify(int[] data, MapSnapshot.Volume survey, Terrain terrain, Grid grid, Node start, Node end, int passes) {
        int n = grid.cells();
        if (data.length != 4 * n) return null;
        int distance = (passes % 2 == 0 ? 2 : 3) * n, previous = 5 * n - distance;
        if (Arrays.mismatch(data, distance, distance + n, data, previous, previous + n) >= 0) return null;
        int cost = data[distance + grid.index(start)];
        if (cost <= 0 || cost >= UNREACHABLE || data[distance + grid.index(end)] != 0
            || cost > outsideRouteLowerBound(survey, grid, start, end)) return null;

        var steps = new ArrayList<Step>();
        Node from = start;
        while (!from.equals(end)) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            int fromIndex = grid.index(from), mask = data[n + fromIndex] & 0xffff;
            Step chosen = null;
            while (mask != 0) {
                int direction = Integer.numberOfTrailingZeros(mask);
                mask &= mask - 1;
                int[] offset = DIRECTIONS[direction];
                int x = from.x() + offset[0], y16 = from.y16() + 16 * offset[1], z = from.z() + offset[2];
                if (!Node.validColumn(x) || !Node.validColumn(z) || y16 < Node.MIN_Y16 || y16 > Node.MAX_Y16) continue;
                var to = new Node(x, y16, z);
                if (!grid.contains(to)) continue;
                int nextCost = data[distance + grid.index(to)];
                if (nextCost < 0 || nextCost >= UNREACHABLE || nextCost + COSTS[direction] != cost) continue;
                Move move = move(direction);
                if (!terrain.floors(x, z, y16, y16).contains(to) || !terrain.canTraverse(from, to, move)) continue;
                chosen = new Step(from, to, move);
                cost = nextCost;
                break;
            }
            if (chosen == null) return null;
            steps.add(chosen);
            from = chosen.to();
        }
        return List.copyOf(steps);
    }

    /** Any route that exits the crop must cover one of these larger axis distances. */
    private static long outsideRouteLowerBound(MapSnapshot.Volume survey, Grid grid, Node start, Node end) {
        long x = Math.abs((long) start.x() - end.x()), z = Math.abs((long) start.z() - end.z());
        long y1 = start.y16() / 16, y2 = end.y16() / 16;
        long vertical = MIN_VERTICAL_SURCHARGE * Math.abs(y1 - y2);
        long bound = Long.MAX_VALUE;
        if (grid.minX() > survey.minX()) bound = Math.min(bound, horizontal(via(start.x(), end.x(), grid.minX() - 1), z) + vertical);
        if (grid.maxX() < survey.maxX()) bound = Math.min(bound, horizontal(via(start.x(), end.x(), grid.maxX() + 1), z) + vertical);
        if (grid.minZ() > survey.minZ()) bound = Math.min(bound, horizontal(x, via(start.z(), end.z(), grid.minZ() - 1)) + vertical);
        if (grid.maxZ() < survey.maxZ()) bound = Math.min(bound, horizontal(x, via(start.z(), end.z(), grid.maxZ() + 1)) + vertical);
        if (grid.minStandY() > survey.minY() + 1)
            bound = Math.min(bound, horizontal(x, z) + MIN_VERTICAL_SURCHARGE * via(y1, y2, grid.minStandY() - 1));
        if (grid.maxStandY() < survey.maxY() - 1)
            bound = Math.min(bound, horizontal(x, z) + MIN_VERTICAL_SURCHARGE * via(y1, y2, grid.maxStandY() + 1));
        return bound;
    }

    private static long via(long a, long b, long border) { return Math.abs(a - border) + Math.abs(b - border); }
    private static long horizontal(long x, long z) {
        return COSTS[0] * Math.abs(x - z) + COSTS[4] * Math.min(x, z);
    }
    private static Move move(int direction) { return direction < 8 ? Move.WALK : direction < 12 ? Move.JUMP : Move.DROP; }

    private static int[] movementCosts() {
        Node from = new Node(0, 0, 0);
        int[] weights = new int[DIRECTIONS.length];
        for (int direction = 0; direction < weights.length; direction++) {
            int[] offset = DIRECTIONS[direction];
            weights[direction] = new Step(from, new Node(offset[0], offset[1] * 16, offset[2]), move(direction)).cost();
        }
        return weights;
    }

    private static String shaderDirections() {
        var result = new StringJoiner(", ");
        for (int[] offset : DIRECTIONS) result.add("ivec3(" + offset[0] + ", " + offset[1] + ", " + offset[2] + ")");
        return result.toString();
    }

    private static String shaderWeights() {
        var result = new StringJoiner(", ");
        for (int weight : COSTS) result.add(Integer.toString(weight));
        return result.toString();
    }
}
