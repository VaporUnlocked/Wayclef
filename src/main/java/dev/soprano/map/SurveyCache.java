package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static dev.soprano.map.SurveyTileFile.COLUMNS;
import static dev.soprano.map.SurveyTileFile.TILE_SHIFT;
import static dev.soprano.map.SurveyTileFile.TILE_SIDE;

/** Saves surveyed sections in spatial tiles scoped to a server, world and dimension. */
public final class SurveyCache {
    private static final String CONTENT = "survey tiles";
    private static final String LEGACY_EXTENSION = ".sopmap";
    private static final Pattern TILE_FILE = Pattern.compile("tile_-?\\d+_-?\\d+\\.(sopmap|sopsurvey)");
    private static final Map<Path, SurveyCache> RECENT = new LinkedHashMap<>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, SurveyCache> eldest) { return size() > 8; }
    };
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "Soprano survey files");
        thread.setDaemon(true);
        return thread;
    });

    record Section(int x, int y, int z, MapSection data) { }

    private record Tile(int x, int z) {
        String name() { return "tile_" + x + "_" + z; }
        int firstX() { return x << TILE_SHIFT; }
        int firstZ() { return z << TILE_SHIFT; }
        int column(int chunkX, int chunkZ) { return (chunkZ - firstZ()) * TILE_SIDE + chunkX - firstX(); }
        MapVolume.Box bounds(int minY, int maxY) {
            return new MapVolume.Box(firstX() * 16, minY, firstZ() * 16,
                (firstX() + TILE_SIDE) * 16 - 1, maxY, (firstZ() + TILE_SIDE) * 16 - 1);
        }
    }

    private record DiskTile(String name, long bytes, FileTime lastUsed) { }

    static final class Palette {
        private final List<MapMaterial> materials = new ArrayList<>();
        private final Map<MapMaterial, Integer> index = new HashMap<>();

        Palette(MapMaterial[] original) {
            for (int id = 0; id < original.length; id++) {
                materials.add(original[id]);
                if (id != 0) index.putIfAbsent(original[id], id);
            }
        }

        int id(MapMaterial material) {
            return index.computeIfAbsent(material, value -> {
                int next = materials.size();
                materials.add(value);
                return next;
            });
        }

        MapMaterial[] array() { return materials.toArray(MapMaterial[]::new); }
    }

    private final Path directory;
    private final Path tileDirectory;
    private volatile String problem;
    private volatile long diskLimit;
    // Owned by DISK and allocated only while a disk limit is active.
    private LinkedHashMap<String, DiskTile> storedTiles;
    private long storedBytes;

    private SurveyCache(Path directory) {
        this.directory = directory;
        this.tileDirectory = MapSnapshotFiles.scope(directory, CONTENT);
    }

    public static synchronized SurveyCache open(Path root, Scope scope) {
        if (scope == null) return null;
        Path directory = root.resolve(hash(scope.server())).resolve(hash(scope.world())).resolve(hash(scope.dimension()));
        return RECENT.computeIfAbsent(directory.toAbsolutePath().normalize(), SurveyCache::new);
    }

    public static SurveyCache open(Path root, Scope scope, long diskLimit) {
        SurveyCache cache = open(root, scope);
        if (cache != null) cache.setDiskLimit(diskLimit);
        return cache;
    }

    public void setDiskLimit(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Survey cache disk limit cannot be negative.");
        if (diskLimit == bytes) return;
        diskLimit = bytes;
        DISK.execute(this::trim);
    }

    public static void finishWrites() {
        DISK.shutdown();
        try { DISK.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    public String problem() { return problem; }

    Future<List<MapSnapshot.Volume>> loadWindow(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return DISK.submit(() -> {
            List<MapSnapshot.Volume> result = new ArrayList<>();
            for (int z = minChunkZ >> TILE_SHIFT; z <= maxChunkZ >> TILE_SHIFT; z++) {
                for (int x = minChunkX >> TILE_SHIFT; x <= maxChunkX >> TILE_SHIFT; x++) {
                    MapSnapshot.Volume volume = read(new Tile(x, z), minChunkX, minChunkZ, maxChunkX, maxChunkZ);
                    if (volume != null) result.add(volume);
                }
            }
            return List.copyOf(result);
        });
    }

    /** Finds fully cached chunk columns, using the high word for X and low word for Z. */
    public Future<Set<Long>> coveredColumns(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                            int minSectionY, int maxSectionY) {
        return DISK.submit(() -> {
            Set<Long> result = new HashSet<>();
            for (int tileZ = minChunkZ >> TILE_SHIFT; tileZ <= maxChunkZ >> TILE_SHIFT; tileZ++) {
                for (int tileX = minChunkX >> TILE_SHIFT; tileX <= maxChunkX >> TILE_SHIFT; tileX++) {
                    if (Thread.currentThread().isInterrupted()) return Set.of();
                    Tile tile = new Tile(tileX, tileZ);
                    var volume = read(tile, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
                    if (volume == null) continue;
                    for (int z = Math.max(tile.firstZ(), minChunkZ); z <= Math.min(tile.firstZ() + TILE_SIDE - 1, maxChunkZ); z++) {
                        for (int x = Math.max(tile.firstX(), minChunkX); x <= Math.min(tile.firstX() + TILE_SIDE - 1, maxChunkX); x++) {
                            if (fullyCovered(volume, x, z, minSectionY, maxSectionY))
                                result.add(((long) x << 32) | (z & 0xffffffffL));
                        }
                    }
                }
            }
            return Set.copyOf(result);
        });
    }

    private static boolean fullyCovered(MapSnapshot.Volume volume, int x, int z, int bottom, int top) {
        if (bottom > top || bottom < (volume.minY() >> 4) || top > (volume.maxY() >> 4)) return false;
        for (int y = bottom; y <= top; y++) {
            if (volume.sectionView(x, y, z) == null) return false;
        }
        return true;
    }

    Future<List<Section>> save(List<Section> sections, MapMaterial[] materials, int minY, int maxY) {
        return DISK.submit(() -> {
            Map<Tile, List<Section>> patches = new LinkedHashMap<>();
            for (Section section : sections) {
                Tile tile = new Tile(section.x() >> TILE_SHIFT, section.z() >> TILE_SHIFT);
                patches.computeIfAbsent(tile, unused -> new ArrayList<>()).add(section);
            }
            List<Section> retry = new ArrayList<>();
            for (var patch : patches.entrySet()) {
                Tile tile = patch.getKey();
                try {
                    write(tile, patch.getValue(), materials, minY, maxY);
                } catch (IOException | IllegalArgumentException failure) {
                    retry.addAll(patch.getValue());
                    report("Could not save part of the surveyed map.", failure);
                }
                if (storedTiles != null) {
                    try { recordTile(tile.name()); }
                    catch (IOException failure) {
                        storedTiles = null;
                        report("Could not update the surveyed map disk usage.", failure);
                    }
                }
            }
            if (retry.isEmpty()) problem = null;
            trim();
            return List.copyOf(retry);
        });
    }

    public void clear() {
        DISK.execute(() -> {
            storedTiles = null;
            try {
                IOException problem = null;
                for (String name : tileNames()) {
                    try { deleteTile(name); }
                    catch (IOException failure) {
                        if (problem == null) problem = failure;
                        else problem.addSuppressed(failure);
                    }
                }
                if (problem != null) throw problem;
                this.problem = null;
            } catch (IOException failure) {
                report("Could not clear the surveyed map.", failure);
            }
        });
    }

    private MapSnapshot.Volume read(Tile tile, int minX, int minZ, int maxX, int maxZ) {
        Path source = indexedFile(tile.name());
        try {
            MapSnapshot.Volume volume;
            try (SurveyTileFile file = SurveyTileFile.open(source)) {
                if (file == null) {
                    MapSnapshot legacy = MapSnapshotFiles.loadIfPresent(directory, CONTENT, tile.name());
                    if (legacy == null) return null;
                    source = legacyFile(tile.name());
                    volume = legacy.volume();
                } else {
                    volume = readWindow(tile, file, minX, minZ, maxX, maxZ);
                }
            }
            if (volume != null) touch(tile.name(), source);
            return volume;
        } catch (IOException | IllegalArgumentException damaged) {
            report("Part of the surveyed map could not be read.", damaged);
            return null;
        }
    }

    private MapSnapshot.Volume readWindow(Tile tile, SurveyTileFile file, int minX, int minZ, int maxX, int maxZ)
        throws IOException {
        int firstY = file.minY() >> 4;
        int height = (file.maxY() >> 4) - firstY + 1;
        MapMaterial[] materials = file.palette();
        MapSection[] sections = new MapSection[COLUMNS * height];
        MapSection[] uniform = new MapSection[materials.length];
        long packedBytes = 0;
        boolean restored = false;
        for (int z = Math.max(tile.firstZ(), minZ); z <= Math.min(tile.firstZ() + TILE_SIDE - 1, maxZ); z++) {
            for (int x = Math.max(tile.firstX(), minX); x <= Math.min(tile.firstX() + TILE_SIDE - 1, maxX); x++) {
                int column = tile.column(x, z);
                try {
                    SurveyTileFile.Column data = file.column(column, uniform);
                    if (data == null) continue;
                    packedBytes = MapSnapshotFiles.checkPackedSize(packedBytes + data.packedBytes());
                    data.copyInto(sections, column * height, firstY);
                    restored = true;
                } catch (IOException damaged) {
                    report("A column of the surveyed map could not be read.", damaged);
                }
            }
        }
        return restored ? new MapSnapshot.Volume(tile.bounds(file.minY(), file.maxY()), sections, materials, 0) : null;
    }

    private void write(Tile tile, List<Section> patch, MapMaterial[] materials, int minY, int maxY) throws IOException {
        Path destination = indexedFile(tile.name());
        Files.createDirectories(tileDirectory);
        Path temporary = Files.createTempFile(tileDirectory, ".soprano-survey-", ".tmp");
        try {
            try (SurveyTileFile previous = SurveyTileFile.open(destination)) {
                MapSnapshot.Volume legacy = previous == null ? legacyVolume(tile) : null;
                int priorMinY = previous != null ? previous.minY() : legacy != null ? legacy.minY() : minY;
                int priorMaxY = previous != null ? previous.maxY() : legacy != null ? legacy.maxY() : maxY;
                int bottom = Math.min(priorMinY, minY), top = Math.max(priorMaxY, maxY);
                SurveyTileFile.checkHeight(bottom, top);
                int firstY = bottom >> 4, height = (top >> 4) - firstY + 1;
                MapMaterial[] original = previous != null ? previous.palette()
                    : legacy != null ? legacy.materials() : new MapMaterial[] {materials[0]};
                Palette palette = new Palette(original);
                MapSection[] uniform = new MapSection[original.length];
                Map<Integer, MapSection[]> changes = new HashMap<>();
                if (legacy != null) migrate(tile, legacy, changes, firstY, height);
                for (Section section : patch) {
                    int slot = section.y() - firstY;
                    if (slot < 0 || slot >= height) throw new IOException("Survey section is outside its tile height.");
                    int column = tile.column(section.x(), section.z());
                    MapSection[] target = changes.get(column);
                    if (target == null) {
                        target = new MapSection[height];
                        if (previous != null) {
                            SurveyTileFile.Column data = previous.column(column, uniform);
                            if (data != null) data.copyInto(target, 0, firstY);
                        }
                        changes.put(column, target);
                    }
                    target[slot] = section.data().remap(id -> palette.id(materials[id]));
                }
                MapMaterial[] updated = palette.array();
                if (previous != null && updated.length == original.length) updated = null;
                SurveyTileFile.write(temporary, bottom, top, updated, changes, previous);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.deleteIfExists(legacyFile(tile.name()));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private MapSnapshot.Volume legacyVolume(Tile tile) throws IOException {
        MapSnapshot legacy = MapSnapshotFiles.loadIfPresent(directory, CONTENT, tile.name());
        if (legacy == null) return null;
        MapSnapshot.Volume volume = legacy.volume();
        var bounds = tile.bounds(volume.minY(), volume.maxY());
        if (volume.minX() != bounds.minX() || volume.minZ() != bounds.minZ()
            || volume.maxX() != bounds.maxX() || volume.maxZ() != bounds.maxZ())
            throw new IOException("Legacy survey bounds do not match the tile.");
        return volume;
    }

    private static void migrate(Tile tile, MapSnapshot.Volume legacy, Map<Integer, MapSection[]> changes, int firstY, int height) {
        for (int z = tile.firstZ(); z < tile.firstZ() + TILE_SIDE; z++) {
            for (int x = tile.firstX(); x < tile.firstX() + TILE_SIDE; x++) {
                for (int y = legacy.minY() >> 4; y <= legacy.maxY() >> 4; y++) {
                    MapSection section = (MapSection) legacy.sectionView(x, y, z);
                    if (section == null) continue;
                    changes.computeIfAbsent(tile.column(x, z), ignored -> new MapSection[height])[y - firstY] = section;
                }
            }
        }
    }

    private Path indexedFile(String name) { return tileDirectory.resolve(name + SurveyTileFile.EXTENSION); }
    private Path legacyFile(String name) { return tileDirectory.resolve(name + LEGACY_EXTENSION); }

    private List<String> tileNames() throws IOException {
        if (!Files.isDirectory(tileDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var files = Files.list(tileDirectory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                .map(file -> file.getFileName().toString())
                .filter(name -> TILE_FILE.matcher(name).matches())
                .map(name -> name.substring(0, name.lastIndexOf('.')))
                .distinct().toList();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private void deleteTile(String name) throws IOException {
        // Remove the older copy first so an interrupted eviction cannot expose stale data.
        Files.deleteIfExists(legacyFile(name));
        Files.deleteIfExists(indexedFile(name));
    }

    private DiskTile diskTile(String name) throws IOException {
        long bytes = 0;
        FileTime lastUsed = FileTime.fromMillis(0);
        for (Path file : List.of(legacyFile(name), indexedFile(name))) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            var info = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            bytes += info.size();
            if (info.lastModifiedTime().compareTo(lastUsed) > 0) lastUsed = info.lastModifiedTime();
        }
        return new DiskTile(name, bytes, lastUsed);
    }

    private void recordTile(String name) throws IOException {
        DiskTile updated = diskTile(name);
        DiskTile previous = storedTiles.remove(name);
        if (previous != null) storedBytes -= previous.bytes();
        storedBytes += updated.bytes();
        storedTiles.put(name, updated);
    }

    private void touch(String name, Path source) {
        if (diskLimit == 0) return;
        try {
            Files.setLastModifiedTime(source, FileTime.from(Instant.now()));
            if (storedTiles != null) storedTiles.get(name);
        } catch (IOException failure) {
            SopranoClient.LOG.debug("Could not update surveyed tile access time", failure);
        }
    }

    private void trim() {
        if (diskLimit == 0) {
            storedTiles = null;
            return;
        }
        try {
            if (storedTiles == null) {
                List<DiskTile> tiles = new ArrayList<>();
                for (String name : tileNames()) tiles.add(diskTile(name));
                tiles.sort(Comparator.comparing(DiskTile::lastUsed).thenComparing(DiskTile::name));
                storedTiles = new LinkedHashMap<>(16, .75f, true);
                storedBytes = 0;
                for (DiskTile tile : tiles) {
                    storedTiles.put(tile.name(), tile);
                    storedBytes += tile.bytes();
                }
            }
            for (var iterator = storedTiles.values().iterator(); iterator.hasNext();) {
                long limit = diskLimit;
                if (limit == 0 || storedBytes <= limit) break;
                DiskTile tile = iterator.next();
                deleteTile(tile.name());
                storedBytes -= tile.bytes();
                iterator.remove();
            }
        } catch (IOException failure) {
            storedTiles = null;
            report("Could not apply the surveyed map disk limit.", failure);
        }
    }

    private void report(String message, Exception failure) {
        problem = message;
        SopranoClient.LOG.warn(message, failure);
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
