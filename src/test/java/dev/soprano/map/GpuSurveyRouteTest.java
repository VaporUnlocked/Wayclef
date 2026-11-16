package dev.soprano.map;

import dev.soprano.gpu.DiscreteCompute;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;

public final class GpuSurveyRouteTest {
    private static final RouteExclusions CLEAR = new RouteExclusions(List.of());

    public static void main(String[] args) {
        verifiedReadback();
        String device;
        try {
            device = DiscreteCompute.run("""
                #version 450
                layout(local_size_x = 1) in;
                layout(std430, set = 0, binding = 0) buffer State { int data[]; };
                void main() { data[0] = 42; }
                """, new int[1], 1, 1, new int[0]).deviceName();
        } catch (IllegalStateException | LinkageError unavailable) {
            if (Arrays.asList(args).contains("--require-gpu")) throw unavailable;
            System.out.println("Soprano GPU survey route readback checks passed. Native parity skipped: " + unavailable.getMessage());
            return;
        }

        var flat = survey((x, z) -> 0, false);
        parity(flat, new Node(2, 16, 2), new Node(12, 16, 10), CLEAR);
        var ledge = survey((x, z) -> x < 6 ? 0 : 1, false);
        check(parity(ledge, new Node(2, 16, 2), new Node(12, 32, 10), CLEAR).stream().anyMatch(step -> step.move() == Move.JUMP),
            "GPU route includes a verified rise");
        check(parity(ledge, new Node(12, 32, 10), new Node(2, 16, 2), CLEAR).stream().anyMatch(step -> step.move() == Move.DROP),
            "GPU route includes a verified drop");
        parity(survey((x, z) -> 0, true), new Node(2, 16, 2), new Node(8, 16, 8), CLEAR);

        var zone = new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE, "Path barrier",
            RouteExclusions.Bounds.blocksInclusive(5, 1, 2, 5, 2, 2));
        var exclusions = new RouteExclusions(List.of(zone));
        Node first = new Node(2, 16, 2), last = new Node(9, 16, 2);
        check(SurveyRoute.tryDedicatedCandidate(flat, first, last, exclusions) == null,
            "CPU rejects a GPU path crossing an excluded volume");
        var fallback = SurveyRoute.plan(flat, hit(first), hit(last), exclusions, true);
        var expected = SurveyRoute.plan(flat, hit(first), hit(last), exclusions);
        check(!fallback.partial() && fallback.steps().equals(expected.steps()) && !fallback.steps().isEmpty(),
            "Rejected GPU path falls back to the normal CPU detour");
        System.out.println("Soprano GPU survey route parity passed on " + device + ".");
    }

    private static void verifiedReadback() {
        var survey = survey((x, z) -> 0, false);
        var start = new Node(2, 16, 2);
        var next = new Node(3, 16, 2);
        var end = new Node(4, 16, 2);
        var grid = GpuSurveyRoute.crop(survey, start, end);
        int n = grid.cells();
        int[] data = new int[4 * n];
        Arrays.fill(data, 2 * n, 4 * n, 1_000_000_000);
        data[n + grid.index(start)] = data[n + grid.index(next)] = 1;
        for (int base : new int[] {2 * n, 3 * n}) {
            data[base + grid.index(start)] = 200;
            data[base + grid.index(next)] = 100;
            data[base + grid.index(end)] = 0;
        }
        Terrain fixture = new Terrain() {
            @Override public List<Node> floors(int x, int z, int minY16, int maxY16) {
                return List.of(new Node(x, minY16, z));
            }

            @Override public boolean canTraverse(Node from, Node to, Move move) {
                return move == Move.WALK && to.x() == from.x() + 1 && to.y16() == from.y16() && to.z() == from.z();
            }
        };
        var steps = GpuSurveyRoute.verify(data, survey, fixture, grid, start, end, 2);
        check(steps != null && steps.size() == 2 && cost(steps) == 200, "Converged distances reconstruct an exact route");
        data[3 * n + grid.index(start)]++;
        check(GpuSurveyRoute.verify(data, survey, fixture, grid, start, end, 2) == null,
            "Unconverged distances cannot replace the CPU route");
        data[3 * n + grid.index(start)]--;
        data[n + grid.index(start)] = 1 << 2;
        check(GpuSurveyRoute.verify(data, survey, fixture, grid, start, end, 2) == null,
            "Malformed path readback cannot replace the CPU route");
    }

    private static List<Step> parity(MapSnapshot.Volume survey, Node first, Node last, RouteExclusions exclusions) {
        var cpu = SurveyRoute.plan(survey, hit(first), hit(last), exclusions);
        var gpu = SurveyRoute.tryDedicatedCandidate(survey, first, last, exclusions);
        check(!cpu.steps().isEmpty() && !cpu.partial(), "CPU fixture route must be complete");
        check(gpu != null && !gpu.steps().isEmpty(), "Discrete GPU must solve this bounded route");
        check(gpu.steps().getFirst().from().equals(first) && gpu.steps().getLast().to().equals(last), "GPU endpoints match");
        check(cost(cpu.steps()) == cost(gpu.steps()), "GPU and CPU optimal costs match");
        return gpu.steps();
    }

    private static int cost(List<Step> steps) { return steps.stream().mapToInt(Step::cost).sum(); }
    private static VoxelRenderer.Hit hit(Node feet) { return new VoxelRenderer.Hit(feet.x(), feet.y16() / 16 - 1, feet.z()); }

    private static MapSnapshot.Volume survey(IntBinaryOperator floor, boolean blockedCorner) {
        int[] cells = new int[MapSection.CELLS];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int height = floor.applyAsInt(x, z);
            for (int y = 0; y <= height; y++) cells[(y * 16 + z) * 16 + x] = 1;
        }
        if (blockedCorner) for (int y = 1; y <= 2; y++) {
            cells[(y * 16 + 2) * 16 + 3] = 2;
            cells[(y * 16 + 3) * 16 + 2] = 2;
        }
        int solid = (int) Arrays.stream(cells).filter(value -> value != 0).count();
        var materials = new MapMaterial[] {new MapMaterial("unknown", new int[64]),
            new MapMaterial("floor", new int[64], List.of(), true), new MapMaterial("wall", new int[64])};
        return new MapSnapshot.Volume(new MapVolume.Box(0, 0, 0, 15, 15, 15),
            new MapSection[] {MapSection.pack(cells, solid)}, materials, 1);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
