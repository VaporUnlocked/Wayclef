package dev.soprano.build;

import dev.soprano.build.SchematicReader.Format;
import dev.soprano.build.SchematicReader.Position;
import dev.soprano.build.SchematicReader.Schematic;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.zip.GZIPOutputStream;

public final class SchematicReaderTest {
    private SchematicReaderTest() {
    }

    public static void main(String[] args) throws IOException {
        spongeVersionsPreserveStatesAndOffset();
        litematicSignedRegionsAndCrossWordIndexes();
        rejectMalformedOrOversizedFiles();
        blueprintIntegration();
        System.out.println("Schematic reader checks passed");
    }

    private static void spongeVersionsPreserveStatesAndOffset() throws IOException {
        byte[] indexes = {0, (byte) 0x82, 0x01, 0, 0};
        Schematic v1 = read(sponge(1, 2, 2, 1, indexes));
        Schematic v2 = read(sponge(2, 2, 2, 1, indexes));
        Schematic v3 = read(sponge(3, 2, 2, 1, indexes));
        check(v2.format() == Format.SPONGE && v3.format() == Format.SPONGE, "Sponge format");
        check(v2.formatVersion() == 2 && v3.formatVersion() == 3, "Sponge versions");
        check(v3.minecraftDataVersion().orElseThrow() == 3953, "Data version retained");
        check(v1.minecraftDataVersion().isEmpty() && v1.blocks().equals(v3.blocks()), "Version 1 has no required data version");
        check(v2.blocks().equals(v3.blocks()), "Equivalent Sponge layouts");
        check(v3.blocks().size() == 4 && v3.blocks().get(new Position(4, -2, 8)).isAir(), "Air cells retained");
        var stair = v3.blocks().get(new Position(5, -2, 8));
        check(stair.canonical().equals("example:stair[facing=west,half=top,waterlogged=false]"),
            "Multi-byte palette index, offset, and state properties");
        try {
            v3.blocks().clear();
            throw new AssertionError("Block map should be immutable");
        } catch (UnsupportedOperationException expected) {
            // The input cannot alter an imported schematic after decoding.
        }
    }

    private static void litematicSignedRegionsAndCrossWordIndexes() throws IOException {
        Schematic litematic = read(litematic());
        check(read(litematic(true)).blocks().equals(litematic.blocks()), "Current lowercase palette tags retain block properties");
        check(litematic.format() == Format.LITEMATIC && litematic.formatVersion() == 7, "Litematica format version");
        check(litematic.regions().size() == 2 && litematic.blocks().size() == 24, "Both regions decoded");
        check(litematic.regions().getFirst().minimum().equals(new Position(-2, 5, -3)), "Signed size normalizes the minimum");
        check(litematic.blocks().get(new Position(19, 5, -3)).canonical().equals("minecraft:oak_stairs[facing=north,half=bottom]"),
            "Cross-word index and Litematica state properties");
        check(litematic.blocks().get(new Position(20, 5, -3)).blockId().equals("minecraft:dirt"), "Final block in main region");
        check(litematic.blocks().get(new Position(21, 5, -3)).blockId().equals("minecraft:stone"), "Second region offset");
    }

    private static byte[] litematic() throws IOException {
        return litematic(false);
    }

    private static byte[] litematic(boolean lowercase) throws IOException {
        return nbt(out -> {
            integer(out, "Version", 7);
            integer(out, "MinecraftDataVersion", lowercase ? 5006 : 3953);
            compound(out, "Regions", regions -> {
                // Five states use three bits. Cell 21 begins at bit 63 and spans both words.
                compound(regions, "Main", region -> writeRegion(region, 20, -23, new long[] {Long.MIN_VALUE, 9}, lowercase));
                compound(regions, "Extension", region -> writeRegion(region, 21, 1, new long[] {1}, lowercase));
            });
        });
    }

    private static void blueprintIntegration() throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path directory = Files.createTempDirectory("soprano-schematic-check-");
        Path schem = directory.resolve("offset.schem");
        Path litematic = directory.resolve("states.litematic");
        Path mcedit = directory.resolve("blocks.schematic");
        try {
            Files.write(schem, sponge(3, 2, 2, 1, new byte[] {0, (byte) 0x82, 0x01, 0, 0}));
            Files.write(litematic, litematic());
            BuildPlan plan = new BuildPlan();
            var origin = new BuildPlan.Cell(100, 64, 200);
            Files.write(mcedit, legacySchematic(new byte[] {53, 0, 0}));
            Schematic decodedLegacy = SchematicReader.read(mcedit);
            check(decodedLegacy.format() == Format.MCEDIT && decodedLegacy.blocks().size() == 3, "MCEdit layout and air");
            check(decodedLegacy.blocks().get(new Position(0, 0, 0)).blockId().equals("legacy:block_256_0"),
                "MCEdit odd block IDs use the upper nibble");
            var importedLegacy = BlueprintActions.importFile(mcedit, plan, origin);
            check(importedLegacy.blocks() == 2 && importedLegacy.unavailable() == 1, "Unmapped numeric blocks are reported");
            var legacyStair = BlueprintBlockStates.resolve(plan.snapshot().get(new BuildPlan.Cell(99, 64, 200)));
            check(legacyStair != null && legacyStair.is(Blocks.OAK_STAIRS)
                && legacyStair.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.WEST,
                "MCEdit offset and stair metadata are retained");
            var sponge = BlueprintActions.importFile(schem, plan, origin);
            check(sponge.blocks() == 1 && sponge.unavailable() == 1, "Unavailable states are retained and reported");
            check(SchematicReader.readForBlueprint(schem).blocks().size() == 1, "Plan import keeps only occupied cells");
            check(plan.snapshot().equals(Map.of(new BuildPlan.Cell(105, 62, 208),
                "example:stair[facing=west,half=top,waterlogged=false]")), "Sponge origin applied once");
            var imported = BlueprintActions.importFile(litematic, plan, origin);
            check(imported.blocks() == 3 && imported.unavailable() == 0, "Litematica blocks resolved in the game registry");
            var legacy = BlueprintBlockStates.migrate(SchematicReader.parseState("minecraft:grass_path"), OptionalInt.of(2586));
            check(legacy.available() && legacy.canonical().equals("minecraft:dirt_path"), "Legacy block renamed by the game data fixer");
            var oldStair = SchematicReader.parseState("minecraft:oak_stairs[facing=west,half=top,waterlogged=true]");
            var migratedStair = BlueprintBlockStates.migrate(oldStair, OptionalInt.of(3953));
            check(migratedStair.available() && migratedStair.canonical().contains("facing=west,half=top,shape=straight,waterlogged=true"),
                "Migration retains non-default properties");
            var stair = new BuildPlan.Cell(119, 69, 197);
            var edge = new BuildPlan.Cell(121, 69, 197);
            var serialized = plan.snapshot().get(stair);
            var north = BlueprintBlockStates.resolve(serialized);
            check(north.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH, "Imported stair faces north");
            check(BlueprintActions.rotate(plan, stair, edge) == 5, "Rotation moves two blocks and changes the anchor state");
            var east = BlueprintBlockStates.resolve(plan.snapshot().get(stair));
            check(east.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST, "Rotation updates stair facing");
            check(BlueprintActions.mirror(plan, stair, stair, BuildPlan.Axis.X) == 1, "Mirror updates state in place");
            check(BlueprintBlockStates.resolve(plan.snapshot().get(stair)).getValue(BlockStateProperties.HORIZONTAL_FACING)
                == Direction.WEST, "X mirror flips east to west");
            check(BlueprintActions.difference(serialized, north, east) == BlueprintActions.Difference.MISORIENTED,
                "Comparison distinguishes direction from a wrong block");
            check(BlueprintActions.difference(serialized, north, Blocks.STONE.defaultBlockState()) == BlueprintActions.Difference.WRONG,
                "Comparison identifies a wrong block");
            check(BlueprintActions.difference(serialized, north, Blocks.AIR.defaultBlockState()) == BlueprintActions.Difference.MISSING,
                "Comparison identifies a missing block");
            check(BlueprintActions.difference(null, null, east) == BlueprintActions.Difference.EXTRA, "Comparison identifies an extra block");
            var bottom = BlueprintBlockStates.resolve("minecraft:oak_slab[type=bottom]");
            var top = BlueprintBlockStates.resolve("minecraft:oak_slab[type=top]");
            var doubled = BlueprintBlockStates.resolve("minecraft:oak_slab[type=double]");
            check(BlueprintActions.difference("minecraft:oak_slab", bottom, top) == BlueprintActions.Difference.MISORIENTED,
                "Opposite slab side is an orientation mismatch");
            check(BlueprintActions.difference("minecraft:oak_slab", bottom, doubled) == BlueprintActions.Difference.WRONG,
                "A double slab is a different state, not an orientation mismatch");

            var pattern = new BuildPlan.Clipboard(2, 1, 1,
                Map.of(new BuildPlan.Offset(0, 0, 0), "stone", new BuildPlan.Offset(1, 0, 0), "oak_slab[type=double]"));
            BuildPlan tiled = new BuildPlan();
            check(BlueprintActions.repeat(tiled, pattern, new BuildPlan.Cell(1, 5, 0), new BuildPlan.Cell(3, 5, 1)) == 6,
                "Repeat covers a flat region and clips its partial edge tile");
            check(BlueprintActions.requirements(tiled).equals(Map.of("minecraft:stone", 4, "minecraft:oak_slab", 4)),
                "Requirements count the material used by double slabs");
            BlueprintImport pending = new BlueprintImport();
            pending.start(schem, origin, null, plan);
            plan.place(origin, "glass");
            try {
                pending.poll(null, plan);
                throw new AssertionError("Import should not replace a newer blueprint revision");
            } catch (IllegalStateException expected) {
                check(!pending.pending() && plan.snapshot().get(origin).equals("minecraft:glass"),
                    "An in-flight import cancels when the user edits the plan");
            }
            var before = plan.snapshot();
            Files.write(litematic, new byte[] {0, 0, 0});
            try {
                BlueprintActions.importFile(litematic, plan, origin);
                throw new AssertionError("Malformed import should fail");
            } catch (IOException expected) {
                check(plan.snapshot() == before, "Import failure preserves the active blueprint");
            }
        } finally {
            Files.deleteIfExists(schem);
            Files.deleteIfExists(litematic);
            Files.deleteIfExists(mcedit);
            Files.deleteIfExists(directory);
        }
    }

    private static void rejectMalformedOrOversizedFiles() throws IOException {
        expectError(sponge(3, 2, 2, 1, new byte[] {0, (byte) 0x80}), "Truncated Sponge block data");
        expectError(sponge(2, 1, 1, 1, new byte[] {3}), "Unknown Sponge palette index");
        expectError(sponge(3, 1, 1, 1, new byte[] {0, 0}), "more entries than the region volume");
        expectError(sponge(3, 4096, 4096, 4096, new byte[0]), "voxel limit");
        expectError(legacySchematic(new byte[] {53, 0}), "do not match the region volume");
        expectError(nbt(out -> {
            integer(out, "Version", 7);
            compound(out, "Regions", regions -> {
                compound(regions, "A", region -> writeRegion(region, 2, 1, new long[] {1}));
                compound(regions, "B", region -> writeRegion(region, 2, 1, new long[] {2}));
            });
        }), "Overlapping Litematica regions");
    }

    private static byte[] legacySchematic(byte[] ids) throws IOException {
        return nbt(out -> {
            string(out, "Materials", "Alpha");
            field(out, 2, "Width", tag -> tag.writeShort(3));
            field(out, 2, "Height", tag -> tag.writeShort(1));
            field(out, 2, "Length", tag -> tag.writeShort(1));
            integer(out, "WEOffsetX", -1);
            byteArray(out, "Blocks", ids);
            byteArray(out, "Data", new byte[] {1, 0, 0});
            byteArray(out, "AddBlocks", new byte[] {0x10, 0});
        });
    }

    private static void byteArray(DataOutputStream out, String name, byte[] value) throws IOException {
        field(out, 7, name, tag -> {
            tag.writeInt(value.length);
            tag.write(value);
        });
    }

    private static byte[] sponge(int version, int width, int height, int length, byte[] data) throws IOException {
        return nbt(out -> {
            if (version == 3) compound(out, "Schematic", root -> writeSponge(root, version, width, height, length, data));
            else writeSponge(out, version, width, height, length, data);
        });
    }

    private static void writeSponge(DataOutputStream out, int version, int width, int height, int length, byte[] data)
        throws IOException {
        integer(out, "Version", version);
        if (version > 1) integer(out, "DataVersion", 3953);
        field(out, 2, "Width", tag -> tag.writeShort(width));
        field(out, 2, "Height", tag -> tag.writeShort(height));
        field(out, 2, "Length", tag -> tag.writeShort(length));
        field(out, 11, "Offset", tag -> {
            tag.writeInt(3);
            tag.writeInt(4);
            tag.writeInt(-2);
            tag.writeInt(8);
        });
        if (version == 3) compound(out, "Blocks", blocks -> writeSpongeBlocks(blocks, "Data", data));
        else writeSpongeBlocks(out, "BlockData", data);
    }

    private static void writeSpongeBlocks(DataOutputStream out, String dataField, byte[] data) throws IOException {
        compound(out, "Palette", palette -> {
            integer(palette, "minecraft:air", 0);
            integer(palette, "example:stair[waterlogged=false,half=top,facing=west]", 130);
        });
        field(out, 7, dataField, tag -> {
            tag.writeInt(data.length);
            tag.write(data);
        });
    }

    private static void writeRegion(DataOutputStream out, int x, int sizeX, long[] packed) throws IOException {
        writeRegion(out, x, sizeX, packed, false);
    }

    private static void writeRegion(DataOutputStream out, int x, int sizeX, long[] packed, boolean lowercase) throws IOException {
        vector(out, "Position", x, 5, -3);
        vector(out, "Size", sizeX, 1, 1);
        field(out, 9, "BlockStatePalette", list -> {
            list.writeByte(10);
            list.writeInt(5);
            String id = lowercase ? "id" : "Name";
            paletteState(list, id, "minecraft:air");
            paletteState(list, id, "minecraft:stone");
            paletteState(list, id, "minecraft:dirt");
            string(list, id, "minecraft:oak_stairs");
            compound(list, lowercase ? "properties" : "Properties", properties -> {
                string(properties, "half", "bottom");
                string(properties, "facing", "north");
            });
            list.writeByte(0);
            paletteState(list, id, "minecraft:glass");
        });
        field(out, 12, "BlockStates", tag -> {
            tag.writeInt(packed.length);
            for (long word : packed) tag.writeLong(word);
        });
    }

    private static void paletteState(DataOutputStream out, String field, String id) throws IOException {
        string(out, field, id);
        out.writeByte(0);
    }

    private static void vector(DataOutputStream out, String field, int x, int y, int z) throws IOException {
        compound(out, field, tag -> {
            integer(tag, "x", x);
            integer(tag, "y", y);
            integer(tag, "z", z);
        });
    }

    private static void integer(DataOutputStream out, String name, int value) throws IOException {
        field(out, 3, name, tag -> tag.writeInt(value));
    }

    private static void string(DataOutputStream out, String name, String value) throws IOException {
        field(out, 8, name, tag -> tag.writeUTF(value));
    }

    private static void compound(DataOutputStream out, String name, Writer body) throws IOException {
        field(out, 10, name, tag -> {
            body.write(tag);
            tag.writeByte(0);
        });
    }

    private static void field(DataOutputStream out, int type, String name, Writer body) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
        body.write(out);
    }

    private static byte[] nbt(Writer body) throws IOException {
        var result = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(result))) {
            compound(out, "", body);
        }
        return result.toByteArray();
    }

    private static Schematic read(byte[] data) throws IOException {
        return SchematicReader.readCompressed(new ByteArrayInputStream(data));
    }

    private static void expectError(byte[] data, String expected) throws IOException {
        try {
            read(data);
            throw new AssertionError("Expected error containing " + expected);
        } catch (IOException error) {
            if (!error.getMessage().contains(expected)) throw error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
