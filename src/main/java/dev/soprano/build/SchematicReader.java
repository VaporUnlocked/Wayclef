package dev.soprano.build;

import dev.soprano.build.SchematicNbt.Compound;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.fixes.BlockStateData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Decodes block states in MCEdit, Sponge, and Litematica files. */
public final class SchematicReader {
    public static final int MAX_VOXELS = 2_000_000;
    private static final int MAX_REGIONS = 512;
    private static final int MAX_PALETTE_ENTRIES = 65_536;
    private static final int FLATTENED_DATA_VERSION = 1631;
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-zA-Z0-9_./:+-]+");

    private SchematicReader() {
    }

    public enum Format {
        MCEDIT, SPONGE, LITEMATIC
    }

    public record Position(int x, int y, int z) {
    }

    public record BlockState(String blockId, Map<String, String> properties) {
        public BlockState {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(properties, "properties");
            if (!blockId.contains(":")) blockId = "minecraft:" + blockId;
            if (blockId.length() > 256 || !BLOCK_ID.matcher(blockId).matches()) {
                throw new IllegalArgumentException("Invalid schematic block ID: " + blockId);
            }
            if (properties.size() > 64) throw new IllegalArgumentException("Too many properties for " + blockId);
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, String> property : properties.entrySet()) {
                String name = property.getKey();
                String value = property.getValue();
                if (!validProperty(name) || !validProperty(value)) {
                    throw new IllegalArgumentException("Invalid schematic block property for " + blockId);
                }
                sorted.put(name, value);
            }
            properties = Collections.unmodifiableMap(sorted);
        }

        private static boolean validProperty(String text) {
            return text != null && text.length() <= 128 && PROPERTY.matcher(text).matches();
        }

        public boolean isAir() {
            return blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air")
                || blockId.equals("minecraft:void_air");
        }

        public String canonical() {
            if (properties.isEmpty()) return blockId;
            StringJoiner state = new StringJoiner(",", blockId + "[", "]");
            properties.forEach((key, value) -> state.add(key + "=" + value));
            return state.toString();
        }
    }

    public record Region(String name, Position minimum, int width, int height, int length) {
    }

    public static final class Schematic {
        private final Format format;
        private final int formatVersion;
        private final OptionalInt minecraftDataVersion;
        private final List<Region> regions;
        private final Map<Position, BlockState> blocks;

        private Schematic(Format format, int formatVersion, OptionalInt minecraftDataVersion,
                          List<Region> regions, Map<Position, BlockState> blocks) {
            this.format = format;
            this.formatVersion = formatVersion;
            this.minecraftDataVersion = minecraftDataVersion;
            this.regions = List.copyOf(regions);
            // The decoder owns this map and hands it off only after it finishes writing.
            this.blocks = Collections.unmodifiableMap(blocks);
        }

        public Format format() { return format; }
        public int formatVersion() { return formatVersion; }
        public OptionalInt minecraftDataVersion() { return minecraftDataVersion; }
        public List<Region> regions() { return regions; }

        /** Positions are relative to the paste origin, including the file's region offsets. */
        public Map<Position, BlockState> blocks() { return blocks; }
    }

    public static Schematic read(Path path) throws IOException {
        return read(path, true);
    }

    static Schematic readForBlueprint(Path path) throws IOException {
        return read(path, false);
    }

    private static Schematic read(Path path, boolean includeAir) throws IOException {
        if (Files.size(path) > SchematicNbt.MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed schematic exceeds the size limit");
        }
        try (InputStream input = Files.newInputStream(path)) {
            return readCompressed(input, includeAir);
        }
    }

    static Schematic readCompressed(InputStream input) throws IOException {
        return readCompressed(input, true);
    }

    private static Schematic readCompressed(InputStream input, boolean includeAir) throws IOException {
        Compound root = SchematicNbt.readCompressed(input);
        if (root.fields().containsKey("Regions")) return readLitematic(root, includeAir);
        Compound wrapped = root.optional("Schematic", Compound.class);
        Compound schematic = wrapped == null ? root : wrapped;
        if (schematic.fields().containsKey("Materials")) return readMcEdit(schematic, includeAir);
        return readSponge(schematic, includeAir);
    }

    private static Schematic readMcEdit(Compound root, boolean includeAir) throws IOException {
        if (!root.required("Materials", String.class).equals("Alpha")) throw new IOException("Unsupported MCEdit materials format");
        int width = Short.toUnsignedInt(root.required("Width", Short.class));
        int height = Short.toUnsignedInt(root.required("Height", Short.class));
        int length = Short.toUnsignedInt(root.required("Length", Short.class));
        Position offset = new Position(legacyOffset(root, "WEOffsetX"), legacyOffset(root, "WEOffsetY"), legacyOffset(root, "WEOffsetZ"));
        Region region = new Region("Schematic", offset, width, height, length);
        int count = validateRegion(region, MAX_VOXELS);
        byte[] ids = root.required("Blocks", byte[].class);
        byte[] metadata = root.required("Data", byte[].class);
        byte[] highIds = root.optional("AddBlocks", byte[].class);
        if (ids.length != count || metadata.length != count
            || highIds != null && (highIds.length < count / 2 || highIds.length > (count + 1) / 2)) {
            throw new IOException("MCEdit block arrays do not match the region volume");
        }

        Map<Integer, BlockState> palette = new HashMap<>();
        Map<Position, BlockState> blocks = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            checkCancelled(i);
            int id = Byte.toUnsignedInt(ids[i]);
            if (highIds != null && i / 2 < highIds.length) id |= ((highIds[i / 2] >>> ((i % 2) * 4)) & 0xf) << 8;
            int key = (id << 4) | (metadata[i] & 0xf);
            BlockState state = palette.computeIfAbsent(key, SchematicReader::legacyState);
            if (includeAir || !state.isAir()) {
                blocks.put(position(region, i), state);
                checkBlueprintSize(blocks, includeAir);
            }
        }
        return new Schematic(Format.MCEDIT, 1, OptionalInt.of(FLATTENED_DATA_VERSION), List.of(region), blocks);
    }

    private static int legacyOffset(Compound root, String axis) throws IOException {
        Integer offset = root.optional(axis, Integer.class);
        return offset == null ? 0 : offset;
    }

    private static BlockState legacyState(int key) {
        CompoundTag tag = (CompoundTag) BlockStateData.getTag(key).convert(NbtOps.INSTANCE).getValue();
        String id = tag.getStringOr("Name", "minecraft:air");
        if ((key >> 4) != 0 && id.equals("minecraft:air")) {
            return new BlockState("legacy:block_" + (key >> 4) + "_" + (key & 0xf), Map.of());
        }
        CompoundTag encoded = tag.getCompoundOrEmpty("Properties");
        Map<String, String> properties = new HashMap<>();
        for (String property : encoded.keySet()) properties.put(property, encoded.getStringOr(property, ""));
        return new BlockState(id, properties);
    }

    private static Schematic readSponge(Compound root, boolean includeAir) throws IOException {
        int version = root.required("Version", Integer.class);
        if (version < 1 || version > 3) throw new IOException("Unsupported Sponge schematic version " + version);
        OptionalInt dataVersion = dataVersion(root, "DataVersion", version > 1);
        int width = Short.toUnsignedInt(root.required("Width", Short.class));
        int height = Short.toUnsignedInt(root.required("Height", Short.class));
        int length = Short.toUnsignedInt(root.required("Length", Short.class));
        int[] offset = root.optional("Offset", int[].class);
        Position minimum = offset == null ? new Position(0, 0, 0) : vector(offset, "Offset");
        Region region = new Region("Schematic", minimum, width, height, length);
        int count = validateRegion(region, MAX_VOXELS);

        Map<Position, BlockState> blocks = new LinkedHashMap<>();
        Compound blockContainer = version < 3 ? root : root.optional("Blocks", Compound.class);
        if (blockContainer != null) {
            Compound encodedPalette = blockContainer.optional("Palette", Compound.class);
            if (encodedPalette == null) throw new IOException("Sponge schematic needs a local block-state palette");
            Map<Integer, BlockState> palette = spongePalette(encodedPalette);
            byte[] data = blockContainer.required(version < 3 ? "BlockData" : "Data", byte[].class);
            VarInts indexes = new VarInts(data);
            for (int i = 0; i < count; i++) {
                checkCancelled(i);
                int id = indexes.next();
                BlockState state = palette.get(id);
                if (state == null) throw new IOException("Unknown Sponge palette index " + id);
                if (includeAir || !state.isAir()) {
                    blocks.put(position(region, i), state);
                    checkBlueprintSize(blocks, includeAir);
                }
            }
            if (indexes.hasRemaining()) throw new IOException("Sponge block data has more entries than the region volume");
        }
        return new Schematic(Format.SPONGE, version, dataVersion, List.of(region), blocks);
    }

    private static Map<Integer, BlockState> spongePalette(Compound encoded) throws IOException {
        validatePaletteSize(encoded.fields().size());
        Map<Integer, BlockState> palette = new HashMap<>();
        for (Map.Entry<String, Object> entry : encoded.fields().entrySet()) {
            if (!(entry.getValue() instanceof Integer id) || id < 0) {
                throw new IOException("Invalid Sponge palette index for " + entry.getKey());
            }
            BlockState previous = palette.putIfAbsent(id, parseSpongeState(entry.getKey()));
            if (previous != null) throw new IOException("Duplicate Sponge palette index " + id);
        }
        return palette;
    }

    private static BlockState parseSpongeState(String text) throws IOException {
        try {
            return parseState(text);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Sponge block state: " + text, invalid);
        }
    }

    static BlockState parseState(String text) {
        int start = text.indexOf('[');
        if (start < 0) return new BlockState(text, Map.of());
        if (!text.endsWith("]") || start == text.length() - 2) {
            throw new IllegalArgumentException("Invalid blueprint block state: " + text);
        }
        Map<String, String> properties = new HashMap<>();
        for (String property : text.substring(start + 1, text.length() - 1).split(",", -1)) {
            int equals = property.indexOf('=');
            if (equals <= 0 || equals != property.lastIndexOf('=') || equals == property.length() - 1) {
                throw new IllegalArgumentException("Invalid blueprint block state property: " + property);
            }
            String name = property.substring(0, equals);
            if (properties.putIfAbsent(name, property.substring(equals + 1)) != null) {
                throw new IllegalArgumentException("Duplicate blueprint block state property: " + name);
            }
        }
        return new BlockState(text.substring(0, start), properties);
    }

    private static Schematic readLitematic(Compound root, boolean includeAir) throws IOException {
        int version = root.required("Version", Integer.class);
        if (version < 4 || version > 7) throw new IOException("Unsupported Litematica schematic version " + version);
        OptionalInt dataVersion = dataVersion(root, "MinecraftDataVersion", false);
        Compound sourceRegions = root.required("Regions", Compound.class);
        if (sourceRegions.fields().isEmpty() || sourceRegions.fields().size() > MAX_REGIONS) {
            throw new IOException("Litematica schematic needs between 1 and " + MAX_REGIONS + " regions");
        }

        List<Region> regions = new ArrayList<>();
        Map<Position, BlockState> blocks = new LinkedHashMap<>();
        int remainingVoxels = MAX_VOXELS;
        for (String name : sourceRegions.fields().keySet()) {
            Compound source = sourceRegions.required(name, Compound.class);
            Position origin = vector(source, "Position");
            Position size = vector(source, "Size");
            Region region = new Region(name, minimumCorner(origin, size), magnitude(size.x()), magnitude(size.y()),
                magnitude(size.z()));
            int count = validateRegion(region, remainingVoxels);
            remainingVoxels -= count;
            readLitematicRegion(source, region, count, blocks, includeAir);
            regions.add(region);
        }
        return new Schematic(Format.LITEMATIC, version, dataVersion, regions, blocks);
    }

    private static void readLitematicRegion(Compound source, Region region, int count, Map<Position, BlockState> blocks,
                                            boolean includeAir) throws IOException {
        List<?> entries = source.required("BlockStatePalette", List.class);
        validatePaletteSize(entries.size());
        List<BlockState> palette = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Compound encoded)) throw new IOException("Invalid Litematica palette entry");
            boolean currentTags = encoded.fields().containsKey("id");
            Compound encodedProperties = encoded.optional(currentTags ? "properties" : "Properties", Compound.class);
            Map<String, String> properties = new HashMap<>();
            if (encodedProperties != null) {
                for (String key : encodedProperties.fields().keySet()) {
                    properties.put(key, encodedProperties.required(key, String.class));
                }
            }
            palette.add(state(encoded.required(currentTags ? "id" : "Name", String.class), properties));
        }

        long[] packed = source.required("BlockStates", long[].class);
        int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        long expected = ((long) count * bits + Long.SIZE - 1) / Long.SIZE;
        if (packed.length != expected) throw new IOException("Incorrect packed block-data length in region " + region.name());
        long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) {
            checkCancelled(i);
            long bitIndex = (long) i * bits;
            int word = (int) (bitIndex / Long.SIZE);
            int shift = (int) (bitIndex % Long.SIZE);
            long value = packed[word] >>> shift;
            if (shift + bits > Long.SIZE) value |= packed[word + 1] << (Long.SIZE - shift);
            int id = (int) (value & mask);
            if (id >= palette.size()) throw new IOException("Unknown Litematica palette index " + id + " in region " + region.name());
            BlockState state = palette.get(id);
            if (includeAir || !state.isAir()) {
                mergeBlock(blocks, position(region, i), state);
                checkBlueprintSize(blocks, includeAir);
            }
        }
    }

    private static void checkCancelled(int index) throws IOException {
        if ((index & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new IOException("Schematic import cancelled.");
    }

    private static void checkBlueprintSize(Map<Position, BlockState> blocks, boolean includeAir) throws IOException {
        if (!includeAir && blocks.size() > BuildPlan.MAX_BLOCKS) {
            throw new IOException("A build plan can hold up to " + BuildPlan.MAX_BLOCKS + " blocks.");
        }
    }

    private static void mergeBlock(Map<Position, BlockState> blocks, Position position, BlockState state) throws IOException {
        BlockState previous = blocks.putIfAbsent(position, state);
        if (previous == null || state.isAir() || previous.equals(state)) return;
        if (previous.isAir()) {
            blocks.put(position, state);
            return;
        }
        throw new IOException("Overlapping Litematica regions specify different blocks at " + position);
    }

    private static OptionalInt dataVersion(Compound root, String field, boolean required) throws IOException {
        Integer version = required ? root.required(field, Integer.class) : root.optional(field, Integer.class);
        if (version == null) return OptionalInt.empty();
        if (version < 0) throw new IOException("Invalid schematic game data version " + version);
        return OptionalInt.of(version);
    }

    private static BlockState state(String id, Map<String, String> properties) throws IOException {
        try {
            return new BlockState(id, properties);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private static void validatePaletteSize(int entries) throws IOException {
        if (entries == 0 || entries > MAX_PALETTE_ENTRIES) throw new IOException("Invalid schematic block-state palette size");
    }

    private static int validateRegion(Region region, int remainingVoxels) throws IOException {
        int width = region.width();
        int height = region.height();
        int length = region.length();
        if (width <= 0 || height <= 0 || length <= 0) throw new IOException("Empty schematic region " + region.name());
        long area = (long) width * length;
        if (area > remainingVoxels || area * height > remainingVoxels) {
            throw new IOException("Schematic exceeds the " + MAX_VOXELS + " voxel limit");
        }
        requireInt((long) region.minimum().x() + width - 1);
        requireInt((long) region.minimum().y() + height - 1);
        requireInt((long) region.minimum().z() + length - 1);
        return (int) (area * height);
    }

    private static Position position(Region region, int index) {
        int horizontal = region.width() * region.length();
        Position minimum = region.minimum();
        return new Position(minimum.x() + index % region.width(), minimum.y() + index / horizontal,
            minimum.z() + index / region.width() % region.length());
    }

    private static Position minimumCorner(Position origin, Position size) throws IOException {
        return new Position(minimum(origin.x(), size.x()), minimum(origin.y(), size.y()), minimum(origin.z(), size.z()));
    }

    private static int minimum(int origin, int size) throws IOException {
        return requireInt((long) origin + Math.min(0, (long) size + 1));
    }

    private static int magnitude(int size) throws IOException {
        return requireInt(Math.abs((long) size));
    }

    private static int requireInt(long value) throws IOException {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IOException("Schematic coordinate overflow");
        return (int) value;
    }

    private static Position vector(Compound parent, String field) throws IOException {
        Object raw = parent.fields().get(field);
        if (raw instanceof int[] xyz) return vector(xyz, field);
        Compound xyz = parent.required(field, Compound.class);
        return new Position(xyz.required("x", Integer.class), xyz.required("y", Integer.class), xyz.required("z", Integer.class));
    }

    private static Position vector(int[] xyz, String field) throws IOException {
        if (xyz.length != 3) throw new IOException("Schematic field " + field + " must have three coordinates");
        return new Position(xyz[0], xyz[1], xyz[2]);
    }

    private static final class VarInts {
        private final byte[] bytes;
        private int offset;

        private VarInts(byte[] bytes) {
            this.bytes = bytes;
        }

        private int next() throws IOException {
            int value = 0;
            for (int shift = 0; shift <= 28; shift += 7) {
                if (!hasRemaining()) throw new IOException("Truncated Sponge block data");
                int next = Byte.toUnsignedInt(bytes[offset++]);
                if (shift == 28 && (next & 0xf8) != 0) throw new IOException("Invalid Sponge block palette VarInt");
                value |= (next & 0x7f) << shift;
                if ((next & 0x80) == 0) return value;
            }
            throw new IOException("Invalid Sponge block palette VarInt");
        }

        private boolean hasRemaining() {
            return offset < bytes.length;
        }
    }
}
