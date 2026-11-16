package dev.soprano.map;

import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import dev.soprano.path.Step;
import net.minecraft.core.BlockPos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

public final class MapFeaturesTest {
    private static final String WORLD = "multiplayer/example.test/overworld";
    private static int checks;

    public static void main(String[] args) throws IOException {
        Path directory = Files.createTempDirectory("soprano-map-features-test-");
        try {
            snapshots(directory);
            surveyRoutes();
            annotations(directory);
            namedRegions(directory);
            portalCoordinates();
            renderer();
            gpuRenderer();
            System.out.println("Soprano map features passed " + checks + " checks.");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void snapshots(Path directory) throws IOException {
        var camera = new VoxelRenderer.Camera(4, 5, 6, 0, 60, 25);
        var snapshot = new MapSnapshot(volume(), camera, 10, 1);
        MapSnapshotFiles.save(directory, WORLD, "Survey", snapshot);
        var loaded = MapSnapshotFiles.load(directory, WORLD, "Survey");
        equal(loaded.lowerCutY(), 1, "lower height cut round trips");
        equal(loaded.cutY(), 10, "upper height cut round trips");
        equal(loaded.volume().hasRouteFloors(), true, "saved survey retains route footing metadata");
        equal(loaded.volume().material(1).routeFloor(), true, "safe full block flag round trips");
        equal(loaded.volume().material(2).routeFloor(), false, "visual hazard does not become route footing");

        Path file = MapSnapshotFiles.scope(directory, WORLD).resolve("Legacy.sopmap");
        try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(0x534f504d);
            output.writeInt(1);
            for (int bound : new int[] {0, 0, 0, 15, 15, 15}) output.writeInt(bound);
            for (double part : new double[] {4, 5, 6, 0, 60, 25}) output.writeDouble(part);
            output.writeInt(15);
            output.writeInt(2);
            for (String material : List.of("Unknown", "Old stone")) {
                output.writeUTF(material);
                for (int pixel = 0; pixel < 64; pixel++) output.writeInt(0xff414141);
                output.writeByte(0);
            }
            output.writeByte(2);
            output.writeInt(1);
        }
        MapSnapshot legacy = MapSnapshotFiles.load(directory, WORLD, "Legacy");
        equal(legacy.lowerCutY(), null, "old snapshots have no lower cut");
        equal(legacy.volume().blockAt(1, 1, 1), 1, "old terrain remains renderable");
        equal(legacy.volume().hasRouteFloors(), false, "old visual materials do not imply safe footing");
    }

    private static MapSnapshot.Volume volume() {
        int[] cells = new int[MapSection.CELLS];
        for (int x = 1; x < 4; x++) cells[2 * 16 + x] = 1;
        for (int x = 4; x < 6; x++) cells[(16 + 2) * 16 + x] = 1;
        cells[2 * 16 + 8] = 2;
        return new MapSnapshot.Volume(new MapVolume.Box(0, 0, 0, 15, 15, 15),
            new MapSection[] {MapSection.pack(cells, 6)}, new MapMaterial[] {
                new MapMaterial("Unknown", new int[64]), new MapMaterial("Recorded footing", new int[64], List.of(), true),
                new MapMaterial("Lava", new int[64])
            }, 1);
    }

    private static void surveyRoutes() {
        var map = volume();
        var start = new VoxelRenderer.Hit(1, 0, 2);
        var end = new VoxelRenderer.Hit(5, 1, 2);
        var clear = new RouteExclusions(List.of());
        var preview = SurveyRoute.plan(map, start, end, clear);
        equal(preview.partial(), false, "survey reaches a walkable destination");
        equal(preview.steps().getLast().to(), new Node(5, 32, 2), "map preview uses exact selected feet");
        equal(preview.steps().stream().anyMatch(step -> step.move() == Move.JUMP), true, "map preview can plan a checked rise");
        var profile = RouteElevation.walking(preview.steps(), preview.start(), false, true);
        equal(profile.minY(), 1.0, "profile lower elevation");
        equal(profile.maxY(), 2.0, "profile upper elevation");
        equal(profile.snapshotOnly(), true, "profile distinguishes surveyed routes");
        equal(profile.samples().getLast().position().x, 5.5, "profile maps samples back to coordinates");

        var closed = new RouteExclusions(List.of(new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE,
            "closed", RouteExclusions.Bounds.blocksInclusive(3, 0, 2, 3, 0, 2))));
        var partial = SurveyRoute.plan(map, start, end, closed);
        equal(partial.partial(), true, "survey stops where a known route exclusion blocks the corridor");
        equal(partial.steps().getLast().to().x(), 2, "floor-level exclusion stops the preview");
        equal(SurveyRoute.plan(map, start, new VoxelRenderer.Hit(8, 0, 2), clear).start(), null,
            "an unsafe material is never a destination floor");
        equal(SurveyRoute.plan(map, start, new VoxelRenderer.Hit(16, 0, 2), clear).start(), null,
            "unknown destination terrain cannot be verified");

        var disconnected = List.of(preview.steps().getFirst(), new Step(new Node(9, 16, 0), new Node(10, 16, 0), Move.WALK));
        var stopped = RouteElevation.walking(disconnected, preview.start(), false, false);
        equal(stopped.partial(), true, "an elevation chart stops at a discontinuity");
        equal(stopped.samples().size(), 2, "unverified elevation samples are omitted");
    }

    private static void annotations(Path directory) throws IOException {
        var world = new Scope("test.invalid", "world", "minecraft:overworld");
        var nether = new Scope("test.invalid", "world", "minecraft:the_nether");
        var other = new Scope("other.invalid", "world", "minecraft:overworld");
        var note = new MapAnnotations.Marker(MapAnnotations.Kind.NOTE, new BlockPos(-12, 64, 23), "Way into the mine");
        var death = new MapAnnotations.Marker(MapAnnotations.Kind.DEATH, new BlockPos(-12, 64, 23), "Last death");
        MapAnnotationFiles.write(directory, world, List.of(note, death));
        equal(MapAnnotationFiles.read(directory, world), List.of(note, death), "map notes and landmarks persist at the same point");
        equal(MapAnnotationFiles.read(directory, nether), List.of(), "dimension scopes do not share map markers");
        equal(MapAnnotationFiles.read(directory, other), List.of(), "server scopes do not share map markers");
        try {
            MapAnnotationFiles.write(directory, world, List.of(note, note));
            throw new AssertionError("duplicate map notes should be refused");
        } catch (IOException expected) {
            checks++;
        }
        equal(MapAnnotationFiles.read(directory, world), List.of(note, death), "failed edit preserves the previous marker file");
    }


    private static void namedRegions(Path directory) {
        var world = new Scope("test.invalid", "world", "minecraft:overworld");
        var otherWorld = new Scope("test.invalid", "other-world", "minecraft:overworld");
        var nether = new Scope("test.invalid", "world", "minecraft:the_nether");
        var otherServer = new Scope("other.invalid", "world", "minecraft:overworld");
        var box = new MapVolume.Box(-4, 60, 5, -20, 50, 12);
        var regions = new NamedRegions(directory);
        regions.setScope(world);
        regions.save(new NamedRegions.Region("A reversed region", box));
        regions.save(new NamedRegions.Region("A REVERSED region", box));
        var reopened = new NamedRegions(directory);
        reopened.setScope(world);
        var region = new NamedRegions.Region("A REVERSED region", box);
        equal(reopened.list(), List.of(region), "named region stores the exact A/B order and updates names case-insensitively");
        for (Scope scope : List.of(otherWorld, nether, otherServer)) {
            reopened.setScope(scope);
            equal(reopened.list(), List.of(), "named regions isolate servers, worlds and dimensions");
        }
        reopened.setScope(world);
        reopened.delete(region);
        regions = new NamedRegions(directory);
        regions.setScope(world);
        equal(regions.list(), List.of(), "region deletion persists");
    }

    private static void portalCoordinates() {
        var overworld = PortalCoordinates.equivalent("minecraft:overworld", new VoxelRenderer.Hit(-1, 64, 31));
        equal(overworld, new PortalCoordinates("Nether", -1, 3, true), "negative overworld blocks use floor division");
        var nether = PortalCoordinates.equivalent("minecraft:the_nether", new VoxelRenderer.Hit(25, 72, -4));
        equal(nether, new PortalCoordinates("Overworld", 200, -32, true), "Nether equivalence scales both horizontal axes");
        equal(PortalCoordinates.equivalent("minecraft:the_nether", new VoxelRenderer.Hit(4_000_000, 80, 1)).inRange(),
            false, "beyond-border equivalents are marked");
        equal(PortalCoordinates.equivalent("minecraft:the_end", new VoxelRenderer.Hit(1, 50, 2)), null,
            "the End does not use portal coordinate scaling");
    }

    private static void renderer() {
        var map = volume();
        var renderer = new VoxelRenderer();
        var view = new VoxelRenderer.Camera(1.5, 0, 2.5, 0, 90, 12);
        var target = new VoxelRenderer.Hit(1, 0, 2);
        var colored = renderer.render(map, view, 0, 2, 1, 1, target, null, new long[0], false, false);
        var patterned = renderer.render(map, view, 0, 2, 1, 1, target, null, new long[0], false, true);
        var clipped = renderer.render(map, view, 1, 2, 1, 1, target, null, new long[0], false, false);
        equal(patterned.hitAt(0, 0), target, "patterns preserve picking and the included lower boundary");
        equal(patterned.argb()[0] != colored.argb()[0], true, "selected block gains a contrasting marker");
        equal(clipped.hitAt(0, 0), null, "a higher lower cut hides the floor");
    }

    private static void gpuRenderer() {
        final int columns = 6, height = 8;
        MapSection[] sections = new MapSection[columns * columns * height];
        Arrays.fill(sections, MapSection.uniform(0));
        int[] floor = new int[MapSection.CELLS];
        Arrays.fill(floor, 0, 256, 1);
        MapSection ground = MapSection.pack(floor, 256);
        for (int z = 0; z < columns; z++) {
            for (int x = 0; x < columns; x++) sections[(z * columns + x) * height] = ground;
        }
        sections[(2 * columns + 2) * height + 3] = null;
        sections[(3 * columns + 4) * height + 1] = MapSection.uniform(1);
        var map = new MapSnapshot.Volume(new MapVolume.Box(-96, 0, 0, -1, 127, 95), sections,
            new MapMaterial[] {new MapMaterial("Unknown", new int[64]), new MapMaterial("Stone", new int[64])}, 2);
        var renderer = new VoxelRenderer();
        var camera = new VoxelRenderer.Camera(-43.5, 16, 44.5, 38, 71, 120);
        int width = 160, imageHeight = 112;
        var cpu = renderer.render(map, camera, 0, 127, width, imageHeight, null, null, new long[0], true, true, false);
        var gpu = renderer.render(map, camera, 0, 127, width, imageHeight, null, null, new long[0], true, true, true);
        equal(Arrays.equals(gpu.argb(), cpu.argb()), true, "GPU air hints preserve exact antialiased raster colors");
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < width; x++) {
                if (!Objects.equals(cpu.hitAt(x, y), gpu.hitAt(x, y)) || !Objects.equals(cpu.faceAt(x, y), gpu.faceAt(x, y))
                    || cpu.depthAt(x, y) != gpu.depthAt(x, y) || cpu.entityOcclusionAt(x, y) != gpu.entityOcclusionAt(x, y))
                    throw new AssertionError("GPU air hints changed exact geometry at " + x + ", " + y);
            }
        }
        checks++;
        if (gpu.gpuDevice() != null) System.out.println("Soprano map Vulkan air traversal passed on " + gpu.gpuDevice());
    }

    private static void equal(Object actual, Object expected, String description) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
