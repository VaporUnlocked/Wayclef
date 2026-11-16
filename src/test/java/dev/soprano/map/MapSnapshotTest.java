package dev.soprano.map;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class MapSnapshotTest {
    private static final String WORLD = "multiplayer/example.test/overworld";
    private static int checks;

    public static void main(String[] args) throws IOException {
        Path directory = Files.createTempDirectory("soprano-snapshot-test-");
        try {
            roundTrip(directory);
            System.out.println("Soprano map snapshots passed " + checks + " checks.");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void roundTrip(Path directory) throws IOException {
        var bounds = new MapVolume.Box(-17, -16, 32, 15, 15, 47);
        int[] cells = new int[MapSection.CELLS];
        cells[0] = 1;
        cells[63] = 2;
        cells[4095] = 3;
        var sections = new MapSection[] {null, MapSection.uniform(0), MapSection.uniform(1),
            MapSection.pack(cells, 3), null, null};
        var materials = new MapMaterial[] {tile("Unknown", 0), tile("Stone", 0xff444444),
            tile("Oak", 0xff886633, List.of(new MapMaterial.Box(0, 0, 0, 1, .5, 1))), tile("Grass", 0xff335522)};
        var camera = new VoxelRenderer.Camera(-4.25, 3.5, 38.0, -39.0, 64.0, 87.0);
        var original = new MapSnapshot(new MapSnapshot.Volume(bounds, sections, materials, 9), camera, 7);
        equal(MapSnapshotFiles.list(directory, WORLD), List.of(), "missing scope lists as empty");
        MapSnapshotFiles.save(directory, WORLD, "Cave lookout", original);
        equal(MapSnapshotFiles.list(directory, WORLD), List.of("Cave lookout"), "saved name is listed");
        equal(MapSnapshotFiles.list(directory, WORLD + "/nether"), List.of(), "another dimension has a separate scope");
        var loaded = MapSnapshotFiles.load(directory, WORLD, "Cave lookout");
        equal(loaded.camera(), camera, "camera pose round trips");
        equal(loaded.cutY(), 7, "height cut round trips");
        var map = loaded.volume();
        equal(map.minX(), -17, "negative coordinates round trip");
        equal(map.maxY(), 15, "height bounds round trip");
        equal(map.blockAt(-17, -16, 32), -1, "unsurveyed sections remain unknown");
        equal(map.blockAt(-17, 0, 32), 0, "surveyed air stays air");
        equal(map.blockAt(-16, -1, 32), 1, "uniform solid section stays solid");
        equal(map.blockAt(-16, 0, 32), 1, "packed section preserves first material");
        equal(map.blockAt(-1, 0, 35), 2, "packed section preserves the local axes");
        equal(map.blockAt(-1, 15, 47), 3, "packed section preserves last material");
        equal(map.blockAt(-2, 15, 47), 0, "packed empty cells stay air");
        equal(map.blockAt(-18, 0, 32), -1, "section padding stays outside the capture bounds");
        equal(map.sectionState(-2, -1, 2), -1, "unknown section is not exposed as an empty section");
        equal(map.sectionState(-2, 0, 2), 0, "empty section stays selectable by section state");
        equal(map.sectionState(-1, -1, 2), 1, "known uniform section is populated");
        equal(map.material(2).name(), "Oak", "material name round trips");
        equal(map.material(2).boxes(), materials[2].boxes(), "partial block geometry round trips");
        equal(map.material(2).pixel(3, 4), materials[2].pixel(3, 4), "exact 8×8 tile pixel round trips");
        equal(map.stats(bounds), new MapVolume.AreaStats(8_448, 4_099, 8_448), "area statistics clip section padding");
        equal(map.stats(new MapVolume.Box(-16, 0, 32, -1, 0, 35)), new MapVolume.AreaStats(64, 2, 0),
            "partly selected section counts exact materials");
        var rendered = new VoxelRenderer().render(map, new VoxelRenderer.Camera(-15.5, 0, 32.5, 0, 90, 25), 15, 1, 1);
        equal(rendered.hitAt(0, 0), new VoxelRenderer.Hit(-16, 0, 32), "loaded terrain is rendered and pickable");

        MapSnapshotFiles.save(directory, WORLD, "z view", original);
        MapSnapshotFiles.save(directory, WORLD, "A view", original);
        MapSnapshotFiles.save(directory, WORLD, "Cave lookout", new MapSnapshot(original.volume(), camera, 2));
        equal(MapSnapshotFiles.list(directory, WORLD), List.of("A view", "Cave lookout", "z view"), "list is sorted");
        equal(MapSnapshotFiles.load(directory, WORLD, "Cave lookout").cutY(), 2, "same name saves a new capture atomically");
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD + "/nether", "Cave lookout"), "cross-world load refused");
        rejectsName(() -> MapSnapshotFiles.load(directory, WORLD, "../Cave lookout"), "path traversal refused");
        rejectsName(() -> MapSnapshotFiles.save(directory, WORLD, "CON", original), "reserved filename refused");

        Path file;
        try (var files = Files.walk(directory)) {
            file = files.filter(path -> path.getFileName().toString().equals("Cave lookout.sopmap")).findFirst().orElseThrow();
        }
        byte[] valid;
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            valid = input.readAllBytes();
        }
        byte[] bad = valid.clone();
        ByteBuffer.wrap(bad).putInt(4, 999);
        writeGzip(file, bad);
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD, "Cave lookout"), "unsupported snapshot version refused");

        bad = valid.clone();
        ByteBuffer.wrap(bad).putInt(8, Integer.MIN_VALUE);
        writeGzip(file, bad);
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD, "Cave lookout"), "oversized bounds refused");

        bad = valid.clone();
        bad[bad.length - 1] = 99;
        writeGzip(file, bad);
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD, "Cave lookout"), "unknown section type refused");

        bad = new byte[valid.length + 1];
        System.arraycopy(valid, 0, bad, 0, valid.length);
        writeGzip(file, bad);
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD, "Cave lookout"), "trailing decoded data refused");

        Files.write(file, new byte[] {12, 22});
        rejectsIo(() -> MapSnapshotFiles.load(directory, WORLD, "Cave lookout"), "damaged compression refused");
    }

    private static void writeGzip(Path file, byte[] bytes) throws IOException {
        try (var output = new GZIPOutputStream(Files.newOutputStream(file))) {
            output.write(bytes);
        }
    }

    private static MapMaterial tile(String name, int color) {
        return tile(name, color, List.of());
    }

    private static MapMaterial tile(String name, int color, List<MapMaterial.Box> boxes) {
        int[] tile = new int[64];
        for (int i = 0; i < tile.length; i++) tile[i] = color + i;
        return new MapMaterial(name, tile, boxes);
    }

    private static void equal(Object actual, Object expected, String purpose) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(purpose + ": expected " + expected + ", got " + actual);
        checks++;
    }

    private static void rejectsIo(IoAction action, String purpose) {
        try {
            action.run();
            throw new AssertionError(purpose);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void rejectsName(IoAction action, String purpose) throws IOException {
        try {
            action.run();
            throw new AssertionError(purpose);
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws IOException; }
}
