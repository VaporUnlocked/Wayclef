package dev.soprano.map;

import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class SurveyCacheTest {
    private static final Scope MAIN = new Scope("server-a", "world-a", "minecraft:overworld");
    private static final Scope OTHER_SERVER = new Scope("server-b", "world-a", "minecraft:overworld");
    private static final Scope OTHER_WORLD = new Scope("server-a", "world-b", "minecraft:overworld");
    private static final Scope OTHER_DIMENSION = new Scope("server-a", "world-a", "minecraft:the_nether");

    private static final MapMaterial UNKNOWN = material("Unknown", 0, false);
    private static final MapMaterial STONE = material("Stone", 0xff777777, true);
    private static final MapMaterial WOOD = material("Wood", 0xff956c32, false);
    private static final MapMaterial GLASS = material("Glass", 0xffacdceb, false);
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("soprano-survey-cache-test-").toAbsolutePath().normalize();
        try {
            overlappingPatches(directory);
            scopeAndExplicitClear(directory);
            legacyAndIndependentColumns(directory.resolve("migration"));
            diskLimit(directory.resolve("budget"));
            System.out.println("Soprano survey cache passed " + checks + " checks.");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(directory)) throw new IllegalStateException("Unexpected test path.");
                    Files.delete(path);
                }
            }
        }
    }

    private static void overlappingPatches(Path directory) throws Exception {
        SurveyCache cache = SurveyCache.open(directory, MAIN);
        int[] cells = new int[MapSection.CELLS];
        cells[0] = 1;
        cells[257] = 2;
        awaitSave(cache, List.of(new SurveyCache.Section(-1, -1, 0, MapSection.pack(cells, 2)),
            section(-2, 0, 0, 1), section(8, 0, -1, 2)), new MapMaterial[] {UNKNOWN, STONE, WOOD}, -16, 31);

        // A later survey supplies a different material order and extends the stored vertical window.
        cache = SurveyCache.open(directory, MAIN);
        awaitSave(cache, List.of(section(-2, 0, 0, 0), section(-3, -2, 0, 2), section(-2, 1, 0, 1)),
            new MapMaterial[] {UNKNOWN, GLASS, STONE}, -32, 47);

        SurveyCache reopened = SurveyCache.open(directory, MAIN);
        var nearby = get(reopened.loadWindow(-3, 0, -1, 0));
        equal(nearby.size(), 1, "a negative-coordinate window opens only its intersecting tile");
        var tile = nearby.getFirst();
        equal(tile.minY(), -32, "an older tile is widened to include lower sections");
        equal(tile.maxY(), 47, "an older tile is widened to include upper sections");
        equal(readMaterial(tile, -16, -16, 0), STONE, "an older packed section and route-floor flag survive another patch");
        equal(readMaterial(tile, -15, -15, 0), WOOD, "an older packed material retains its cell and texture");
        equal(tile.blockAt(-32, 0, 0), 0, "newly surveyed air replaces an older solid section");
        equal(readMaterial(tile, -48, -32, 0), STONE, "new uniform material IDs resolve against the incoming palette");
        equal(readMaterial(tile, -32, 16, 0), GLASS, "new material is added without changing the older palette values");
        equal(tile.blockAt(-48, 0, 0), -1, "an unsurveyed section remains unknown");
        var distant = get(reopened.loadWindow(8, -1, 8, -1));
        equal(readMaterial(distant.getFirst(), 128, 0, -16), WOOD, "a distant tile survives other window writes and reopens");
    }

    private static void scopeAndExplicitClear(Path directory) throws Exception {
        var otherScopes = List.of(OTHER_SERVER, OTHER_WORLD, OTHER_DIMENSION);
        var otherMaterials = List.of(GLASS, WOOD, STONE);
        for (int index = 0; index < otherScopes.size(); index++) {
            SurveyCache cache = SurveyCache.open(directory, otherScopes.get(index));
            equal(get(cache.loadWindow(-3, 0, -1, 0)), List.of(), "a fresh server, world, or dimension inherits no survey");
            awaitSave(cache, List.of(section(-2, 0, 0, 1)), new MapMaterial[] {UNKNOWN, otherMaterials.get(index)}, 0, 15);
        }

        SurveyCache main = SurveyCache.open(directory, MAIN);
        var inFlight = main.save(List.of(section(-2, 0, 0, 1)), new MapMaterial[] {UNKNOWN, WOOD}, 0, 15);
        main.clear();
        equal(get(inFlight), List.of(), "a write queued before explicit clear succeeds");
        equal(get(SurveyCache.open(directory, MAIN).loadWindow(-3, 0, -1, 0)), List.of(),
            "explicit clear removes nearby tiles and earlier queued writes");
        equal(get(SurveyCache.open(directory, MAIN).loadWindow(8, -1, 8, -1)), List.of(), "explicit clear removes distant tiles in the same scope");
        for (int index = 0; index < otherScopes.size(); index++) {
            var tile = get(SurveyCache.open(directory, otherScopes.get(index)).loadWindow(-2, 0, -2, 0)).getFirst();
            equal(readMaterial(tile, -32, 0, 0), otherMaterials.get(index), "clear leaves the other scoped survey intact");
        }

        awaitSave(main, List.of(section(-2, 0, 0, 1)), new MapMaterial[] {UNKNOWN, STONE}, 0, 15);
        var newTile = get(SurveyCache.open(directory, MAIN).loadWindow(-2, 0, -2, 0)).getFirst();
        equal(readMaterial(newTile, -32, 0, 0), STONE, "the world can be surveyed again after explicit clear");
        equal(newTile.blockAt(-16, -16, 0), -1, "cleared sections do not reappear after a new capture");
    }

    private static void legacyAndIndependentColumns(Path directory) throws Exception {
        SurveyCache cache = SurveyCache.open(directory, MAIN);
        MapMaterial[] materials = {UNKNOWN, STONE, WOOD};
        awaitSave(cache, List.of(section(0, 0, 0, 1)), materials, -16, 15);
        Path index;
        try (var files = Files.walk(directory)) {
            index = files.filter(file -> file.getFileName().toString().equals("tile_0_0.sopsurvey")).findFirst().orElseThrow();
        }
        Files.delete(index);
        Path scope = index.getParent().getParent();
        var bounds = new MapVolume.Box(0, -16, 0, 127, 15, 127);
        MapSection[] oldSections = new MapSection[128];
        oldSections[0] = MapSection.uniform(1);
        oldSections[2] = MapSection.uniform(2);
        var legacy = new MapSnapshot(new MapSnapshot.Volume(bounds, oldSections, materials, 0),
            new VoxelRenderer.Camera(0, 0, 0, 36, 56, 60), 15);
        MapSnapshotFiles.save(scope, "survey tiles", "tile_0_0", legacy);

        var oldTile = get(cache.loadWindow(1, 0, 1, 0)).getFirst();
        equal(readMaterial(oldTile, 16, -16, 0), WOOD, "the new cache can read an older compressed tile");
        awaitSave(cache, List.of(section(0, 0, 0, 0)), materials, -16, 15);
        equal(Files.exists(index), true, "the first updated legacy tile is migrated into the indexed format");
        equal(MapSnapshotFiles.loadIfPresent(scope, "survey tiles", "tile_0_0"), null, "legacy data is retired after migration");
        equal(get(cache.coveredColumns(0, 0, 1, 0, -1, 0)), Set.of(0L), "coverage requires every vertical section and includes cached air");
        var local = get(cache.loadWindow(0, 0, 0, 0)).getFirst();
        equal(local.blockAt(16, -16, 0), -1, "opening a window decodes only the requested indexed columns");
        equal(readMaterial(get(cache.loadWindow(1, 0, 1, 0)).getFirst(), 16, -16, 0), WOOD,
            "migration retains a column outside the new survey patch");

        try (var file = new RandomAccessFile(index.toFile(), "rw")) {
            file.seek(4L * Integer.BYTES);
            int palette = file.readInt();
            int firstColumn = file.readInt();
            long secondColumn = (5L + SurveyTileFile.COLUMNS) * Integer.BYTES + palette + firstColumn;
            file.seek(secondColumn);
            file.writeByte(0);
        }
        awaitSave(cache, List.of(section(0, -1, 0, 2)), materials, -16, 15);
        equal(readMaterial(get(cache.loadWindow(0, 0, 0, 0)).getFirst(), 0, -16, 0), WOOD,
            "a write and read succeed beside a damaged compressed column without decoding it");
        equal(get(cache.loadWindow(1, 0, 1, 0)), List.of(), "damage is detected when the affected record is actually read");
    }

    private static void diskLimit(Path directory) throws Exception {
        SurveyCache cache = SurveyCache.open(directory, MAIN);
        SurveyCache other = SurveyCache.open(directory, OTHER_WORLD);
        MapMaterial[] materials = {UNKNOWN, STONE};
        awaitSave(cache, List.of(section(0, 0, 0, 1), section(8, 0, 0, 1)), materials, 0, 15);
        awaitSave(other, List.of(section(0, 0, 0, 1)), materials, 0, 15);
        equal(get(cache.loadWindow(0, 0, 8, 0)).size(), 2, "unlimited storage retains both cached tiles");
        long tileSize;
        try (var files = Files.walk(directory)) {
            tileSize = Files.size(files.filter(file -> file.getFileName().toString().equals("tile_1_0.sopsurvey")).findFirst().orElseThrow());
        }
        cache.setDiskLimit(2 * tileSize);
        equal(get(cache.loadWindow(0, 0, 0, 0)).size(), 1, "opening a cached tile updates its eviction order");
        cache.setDiskLimit(tileSize);
        equal(get(cache.loadWindow(8, 0, 8, 0)), List.of(), "an explicit limit evicts the least recently used tile");
        equal(get(cache.loadWindow(0, 0, 0, 0)).size(), 1, "an explicit limit keeps the most recently used tile");
        equal(get(other.loadWindow(0, 0, 0, 0)).size(), 1, "a disk limit applies only within the configured scope");
        cache.setDiskLimit(0);
        awaitSave(cache, List.of(section(8, 0, 0, 1)), materials, 0, 15);
        equal(get(cache.loadWindow(0, 0, 8, 0)).size(), 2, "turning the limit off preserves subsequent writes");
    }

    private static SurveyCache.Section section(int x, int y, int z, int material) {
        return new SurveyCache.Section(x, y, z, MapSection.uniform(material));
    }

    private static MapMaterial material(String name, int color, boolean routeFloor) {
        int[] pixels = new int[MapMaterial.TILE_SIZE * MapMaterial.TILE_SIZE];
        Arrays.fill(pixels, color);
        return new MapMaterial(name, pixels, List.of(), routeFloor);
    }

    private static MapMaterial readMaterial(MapSnapshot.Volume volume, int x, int y, int z) {
        int material = volume.blockAt(x, y, z);
        if (material <= 0) throw new AssertionError("Expected a retained solid cell at " + x + ", " + y + ", " + z);
        return volume.material(material);
    }

    private static void awaitSave(SurveyCache cache, List<SurveyCache.Section> patch, MapMaterial[] materials, int minY, int maxY)
        throws Exception {
        equal(get(cache.save(patch, materials, minY, maxY)), List.of(), "survey patch saves without retries");
    }

    private static <T> T get(Future<T> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }

    private static void equal(Object actual, Object expected, String purpose) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(purpose + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
