package dev.soprano.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/** Versioned, compressed snapshots of terrain already received and surveyed by the client. */
public final class MapSnapshotFiles {
    private static final int MAGIC = 0x534f504d;
    private static final int VERSION = 2;
    private static final int BUFFER = 64 * 1024;
    private static final int MAX_HORIZONTAL_SECTIONS = 65;
    private static final int MAX_VERTICAL_SECTIONS = 256;
    private static final int MAX_HORIZONTAL_COORDINATE = 30_002_000;
    private static final int MAX_VERTICAL_COORDINATE = 32_768;
    private static final int MAX_MATERIALS = 131_072;
    private static final int MAX_MATERIAL_NAME = 1_024;
    private static final long MAX_PALETTE_BYTES = 32L << 20;
    private static final long MAX_PACKED_BYTES = 96L << 20;
    private static final long MAX_FILE_BYTES = 128L << 20;
    private static final int UNKNOWN = 0, AIR = 1, UNIFORM = 2, PACKED = 3;
    private static final String EXTENSION = ".sopmap";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,47}");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");

    private MapSnapshotFiles() { }

    public static List<String> list(Path directory, String worldKey) throws IOException {
        Path scope = scope(directory, worldKey);
        if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var children = Files.list(scope)) {
            return children.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(EXTENSION))
                .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                .filter(MapSnapshotFiles::validName)
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
                .toList();
        } catch (UncheckedIOException unreadable) {
            throw unreadable.getCause();
        }
    }

    public static void save(Path directory, String worldKey, String name, MapSnapshot snapshot) throws IOException {
        Path destination = file(directory, worldKey, name);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".soprano-map-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                Files.newOutputStream(temporary), BUFFER), BUFFER))) {
                write(output, snapshot);
            }
            if (Files.size(temporary) > MAX_FILE_BYTES) throw new IOException("Map snapshot exceeds the file size limit.");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static MapSnapshot load(Path directory, String worldKey, String name) throws IOException {
        Path source = file(directory, worldKey, name);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Map snapshot was not found.");
        if (Files.size(source) > MAX_FILE_BYTES) throw new IOException("Map snapshot exceeds the file size limit.");
        try (var input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(
            Files.newInputStream(source), BUFFER), BUFFER))) {
            MapSnapshot snapshot = read(input);
            if (input.read() != -1) throw new IOException("Map snapshot contains unexpected extra data.");
            return snapshot;
        } catch (EOFException | ZipException damaged) {
            throw new IOException("Map snapshot is damaged or incomplete.", damaged);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Map snapshot contains invalid geometry or camera data.", invalid);
        }
    }

    static MapSnapshot loadIfPresent(Path directory, String worldKey, String name) throws IOException {
        return Files.notExists(file(directory, worldKey, name), LinkOption.NOFOLLOW_LINKS) ? null : load(directory, worldKey, name);
    }

    private static void write(DataOutputStream output, MapSnapshot snapshot) throws IOException {
        var volume = snapshot.volume();
        checkBounds(volume.minX(), volume.minY(), volume.minZ(), volume.maxX(), volume.maxY(), volume.maxZ());
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(volume.minX());
        output.writeInt(volume.minY());
        output.writeInt(volume.minZ());
        output.writeInt(volume.maxX());
        output.writeInt(volume.maxY());
        output.writeInt(volume.maxZ());
        var camera = snapshot.camera();
        output.writeDouble(camera.x());
        output.writeDouble(camera.y());
        output.writeDouble(camera.z());
        output.writeDouble(camera.yaw());
        output.writeDouble(camera.pitch());
        output.writeDouble(camera.distance());
        output.writeInt(snapshot.cutY());
        output.writeInt(snapshot.lowerCutY() == null ? Integer.MIN_VALUE : snapshot.lowerCutY());

        writePalette(output, volume.materials());
        long packedBytes = 0;
        for (int slot = 0; slot < volume.sectionCount(); slot++) {
            MapSection section = volume.sectionAt(slot);
            if (section != null) packedBytes = checkPackedSize(packedBytes + section.packedBytes());
            writeSection(output, section);
        }
    }

    private static MapSnapshot read(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Map snapshot format or version is unsupported.");
        int version = input.readInt();
        if (version < 1 || version > VERSION)
            throw new IOException("Map snapshot format or version is unsupported.");
        int x1 = input.readInt(), y1 = input.readInt(), z1 = input.readInt();
        int x2 = input.readInt(), y2 = input.readInt(), z2 = input.readInt();
        int sectionCount = checkBounds(x1, y1, z1, x2, y2, z2);
        var camera = new VoxelRenderer.Camera(input.readDouble(), input.readDouble(), input.readDouble(),
            input.readDouble(), input.readDouble(), input.readDouble());
        int cut = input.readInt();
        if (cut < y1 || cut > y2) throw new IOException("Invalid snapshot height cut.");
        int lowerCut = version >= 2 ? input.readInt() : Integer.MIN_VALUE;

        MapMaterial[] materials = readPalette(input, version >= 2);
        MapSection[] sections = new MapSection[sectionCount];
        MapSection[] uniform = new MapSection[materials.length];
        long packedBytes = 0;
        for (int slot = 0; slot < sections.length; slot++) {
            MapSection section = readSection(input, uniform);
            if (section != null) packedBytes = checkPackedSize(packedBytes + section.packedBytes());
            sections[slot] = section;
        }
        var bounds = new MapVolume.Box(x1, y1, z1, x2, y2, z2);
        return new MapSnapshot(new MapSnapshot.Volume(bounds, sections, materials, 0), camera, cut,
            lowerCut == Integer.MIN_VALUE ? null : lowerCut);
    }

    static void writePalette(DataOutputStream output, MapMaterial[] materials) throws IOException {
        if (materials.length < 1 || materials.length > MAX_MATERIALS) throw new IOException("Map snapshot has too many materials.");
        output.writeInt(materials.length);
        long paletteBytes = 0;
        for (MapMaterial material : materials) {
            paletteBytes = checkMaterialSize(material.name(), material.boxes().size(), paletteBytes);
            output.writeUTF(material.name());
            for (int y = 0; y < MapMaterial.TILE_SIZE; y++) {
                for (int x = 0; x < MapMaterial.TILE_SIZE; x++) output.writeInt(material.pixel(x, y));
            }
            output.writeByte(material.boxes().size());
            for (MapMaterial.Box box : material.boxes()) {
                output.writeDouble(box.minX());
                output.writeDouble(box.minY());
                output.writeDouble(box.minZ());
                output.writeDouble(box.maxX());
                output.writeDouble(box.maxY());
                output.writeDouble(box.maxZ());
            }
            output.writeBoolean(material.routeFloor());
        }
    }

    static MapMaterial[] readPalette(DataInputStream input, boolean routeFloor) throws IOException {
        int materialCount = input.readInt();
        if (materialCount < 1 || materialCount > MAX_MATERIALS)
            throw new IOException("Invalid map snapshot material count.");
        MapMaterial[] materials = new MapMaterial[materialCount];
        long paletteBytes = 0;
        for (int id = 0; id < materials.length; id++) {
            String name = input.readUTF();
            int[] tile = new int[MapMaterial.TILE_SIZE * MapMaterial.TILE_SIZE];
            for (int i = 0; i < tile.length; i++) tile[i] = input.readInt();
            int boxCount = input.readUnsignedByte();
            paletteBytes = checkMaterialSize(name, boxCount, paletteBytes);
            List<MapMaterial.Box> boxes = new ArrayList<>(boxCount);
            for (int box = 0; box < boxCount; box++) {
                boxes.add(new MapMaterial.Box(input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readDouble(), input.readDouble(), input.readDouble()));
            }
            materials[id] = new MapMaterial(name, tile, boxes, routeFloor && input.readBoolean());
        }
        return materials;
    }

    static void writeSection(DataOutputStream output, MapSection section) throws IOException {
        if (section == null) output.writeByte(UNKNOWN);
        else if (section.uniformId() == 0) output.writeByte(AIR);
        else if (section.uniformId() > 0) {
            output.writeByte(UNIFORM);
            output.writeInt(section.uniformId());
        } else {
            output.writeByte(PACKED);
            section.writePacked(output);
        }
    }

    static MapSection readSection(DataInputStream input, MapSection[] uniform) throws IOException {
        return switch (input.readUnsignedByte()) {
            case UNKNOWN -> null;
            case AIR -> MapSection.uniform(0);
            case UNIFORM -> {
                int id = input.readInt();
                if (id < 1 || id >= uniform.length) throw new IOException("Invalid snapshot material ID.");
                if (uniform[id] == null) uniform[id] = MapSection.uniform(id);
                yield uniform[id];
            }
            case PACKED -> {
                int size = input.readUnsignedShort();
                if (size < 2 || size > MapSection.CELLS) throw new IOException("Invalid packed snapshot palette size.");
                int[] palette = new int[size];
                for (int i = 0; i < size; i++) {
                    int id = input.readInt();
                    if (id < 0 || id >= uniform.length) throw new IOException("Invalid snapshot material ID.");
                    palette[i] = id;
                }
                long[] words = new long[MapSection.wordCount(size)];
                for (int i = 0; i < words.length; i++) words[i] = input.readLong();
                yield MapSection.fromPacked(palette, words);
            }
            default -> throw new IOException("Invalid map snapshot section type.");
        };
    }

    private static int checkBounds(int x1, int y1, int z1, int x2, int y2, int z2) throws IOException {
        if (x1 < -MAX_HORIZONTAL_COORDINATE || x2 > MAX_HORIZONTAL_COORDINATE
            || z1 < -MAX_HORIZONTAL_COORDINATE || z2 > MAX_HORIZONTAL_COORDINATE
            || y1 < -MAX_VERTICAL_COORDINATE || y2 > MAX_VERTICAL_COORDINATE
            || x1 > x2 || y1 > y2 || z1 > z2) throw new IOException("Invalid map snapshot bounds.");
        int width = (x2 >> 4) - (x1 >> 4) + 1;
        int height = (y2 >> 4) - (y1 >> 4) + 1;
        int length = (z2 >> 4) - (z1 >> 4) + 1;
        if (width > MAX_HORIZONTAL_SECTIONS || length > MAX_HORIZONTAL_SECTIONS || height > MAX_VERTICAL_SECTIONS)
            throw new IOException("Map snapshot bounds exceed the size limit.");
        return width * height * length;
    }

    private static long checkMaterialSize(String name, int boxes, long used) throws IOException {
        long total = used + name.length() * 3L + MapMaterial.TILE_SIZE * MapMaterial.TILE_SIZE * Integer.BYTES
            + boxes * 6L * Double.BYTES;
        if (name.length() > MAX_MATERIAL_NAME || boxes > MapMaterial.MAX_BOXES || total > MAX_PALETTE_BYTES)
            throw new IOException("Map snapshot material data exceeds the size limit.");
        return total;
    }

    static long checkPackedSize(long bytes) throws IOException {
        if (bytes > MAX_PACKED_BYTES) throw new IOException("Map snapshot terrain data exceeds the size limit.");
        return bytes;
    }

    private static Path file(Path directory, String worldKey, String name) {
        String clean = Objects.requireNonNull(name, "map snapshot name").strip();
        if (!validName(clean))
            throw new IllegalArgumentException("Use 1–48 letters, numbers, spaces, underscores or hyphens for a location name.");
        return scope(directory, worldKey).resolve(clean + EXTENSION);
    }

    private static boolean validName(String name) {
        return NAME.matcher(name).matches() && !WINDOWS_DEVICE.matcher(name.toUpperCase(Locale.ROOT)).matches();
    }

    static Path scope(Path directory, String worldKey) {
        if (Objects.requireNonNull(worldKey, "snapshot world key").isBlank())
            throw new IllegalArgumentException("A map snapshot needs a world identifier.");
        byte[] key;
        try {
            key = MessageDigest.getInstance("SHA-256").digest(worldKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("Snapshot world hashing is unavailable.", unsupported);
        }
        return directory.toAbsolutePath().normalize().resolve(HexFormat.of().formatHex(key));
    }
}
