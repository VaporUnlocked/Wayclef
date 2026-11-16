package dev.soprano.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A shared palette and independently compressed chunk columns with a seekable index. */
final class SurveyTileFile implements AutoCloseable {
    static final int TILE_SHIFT = 3;
    static final int TILE_SIDE = 1 << TILE_SHIFT;
    static final int COLUMNS = TILE_SIDE * TILE_SIDE;
    static final int MAX_HEIGHT = 256;
    static final String EXTENSION = ".sopsurvey";

    private static final int MAGIC = 0x534F5356;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * (5 + COLUMNS);
    private static final int MAX_COLUMN_BYTES = 6 << 20;
    private static final int MAX_PALETTE_BYTES = 36 << 20;
    private static final long MAX_TILE_BYTES = 128L << 20;
    private static final int BUFFER = 8 << 10;

    record Column(int firstY, MapSection[] sections, long packedBytes) {
        void copyInto(MapSection[] target, int offset, int firstTargetY) {
            System.arraycopy(sections, 0, target, offset + firstY - firstTargetY, sections.length);
        }
    }

    @FunctionalInterface
    private interface Encoder { void write(DataOutputStream output) throws IOException; }

    private final RandomAccessFile file;
    private final int minY, maxY, paletteLength;
    private final int[] lengths = new int[COLUMNS];
    private final long[] offsets = new long[COLUMNS];

    private SurveyTileFile(RandomAccessFile file) throws IOException {
        this.file = file;
        long size = file.length();
        if (size < HEADER_BYTES || size > MAX_TILE_BYTES || file.readInt() != MAGIC || file.readInt() != VERSION)
            throw new IOException("Invalid survey tile header.");
        minY = file.readInt();
        maxY = file.readInt();
        checkHeight(minY, maxY);
        paletteLength = file.readInt();
        if (paletteLength <= 0 || paletteLength > MAX_PALETTE_BYTES)
            throw new IOException("Invalid survey tile palette size.");
        long next = HEADER_BYTES + (long) paletteLength;
        for (int index = 0; index < COLUMNS; index++) {
            int length = file.readInt();
            if (length < 0 || length > MAX_COLUMN_BYTES) throw new IOException("Invalid survey column size.");
            offsets[index] = next;
            lengths[index] = length;
            next += length;
        }
        if (next != size) throw new IOException("Survey tile data does not match its index.");
    }

    static SurveyTileFile open(Path source) throws IOException {
        if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid survey tile file.");
        var file = new RandomAccessFile(source.toFile(), "r");
        try {
            return new SurveyTileFile(file);
        } catch (IOException invalid) {
            try { file.close(); }
            catch (IOException closeFailed) { invalid.addSuppressed(closeFailed); }
            throw invalid;
        }
    }

    int minY() { return minY; }
    int maxY() { return maxY; }

    MapMaterial[] palette() throws IOException {
        try (var input = decode(HEADER_BYTES, paletteLength)) {
            MapMaterial[] result = MapSnapshotFiles.readPalette(input, true);
            if (input.read() != -1) throw new IOException("Unexpected data in survey tile palette.");
            return result;
        }
    }

    Column column(int index, MapSection[] uniform) throws IOException {
        if (lengths[index] == 0) return null;
        try (var input = decode(offsets[index], lengths[index])) {
            int firstY = input.readShort(), count = input.readUnsignedShort();
            if (count == 0 || firstY < (minY >> 4) || firstY + count - 1 > (maxY >> 4))
                throw new IOException("Invalid survey column height.");
            MapSection[] sections = new MapSection[count];
            long packedBytes = 0;
            for (int y = 0; y < count; y++) {
                MapSection section = MapSnapshotFiles.readSection(input, uniform);
                if (section != null) packedBytes += section.packedBytes();
                sections[y] = section;
            }
            if (input.read() != -1) throw new IOException("Unexpected data in survey column.");
            return new Column(firstY, sections, packedBytes);
        }
    }

    static void write(Path temporary, int minY, int maxY, MapMaterial[] palette, Map<Integer, MapSection[]> changes,
                      SurveyTileFile prior) throws IOException {
        checkHeight(minY, maxY);
        byte[] compressedPalette = palette == null ? null : compress(output -> MapSnapshotFiles.writePalette(output, palette));
        int paletteLength = compressedPalette == null ? prior.paletteLength : compressedPalette.length;
        if (paletteLength > MAX_PALETTE_BYTES) throw new IOException("Survey tile palette is too large.");
        try (var output = new RandomAccessFile(temporary.toFile(), "rw")) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(minY);
            output.writeInt(maxY);
            output.writeInt(paletteLength);
            output.seek(HEADER_BYTES);
            byte[] buffer = new byte[BUFFER];
            if (compressedPalette != null) output.write(compressedPalette);
            else prior.copyRecord(HEADER_BYTES, paletteLength, output, buffer);
            int[] lengths = new int[COLUMNS];
            for (int index = 0; index < COLUMNS; index++) {
                MapSection[] replacement = changes.get(index);
                if (replacement != null) {
                    byte[] data = encodeColumn(minY >> 4, replacement);
                    if (data.length > MAX_COLUMN_BYTES) throw new IOException("Survey column is too large.");
                    lengths[index] = data.length;
                    output.write(data);
                } else if (prior != null) {
                    lengths[index] = prior.lengths[index];
                    prior.copyRecord(prior.offsets[index], lengths[index], output, buffer);
                }
            }
            if (output.getFilePointer() > MAX_TILE_BYTES) throw new IOException("Survey tile is too large.");
            output.seek(5L * Integer.BYTES);
            for (int length : lengths) output.writeInt(length);
        }
    }

    private static byte[] encodeColumn(int firstY, MapSection[] sections) throws IOException {
        int start = 0;
        while (start < sections.length && sections[start] == null) start++;
        if (start == sections.length) return new byte[0];
        int end = sections.length;
        while (sections[end - 1] == null) end--;
        int startIndex = start, endIndex = end;
        return compress(output -> {
            output.writeShort(firstY + startIndex);
            output.writeShort(endIndex - startIndex);
            for (int y = startIndex; y < endIndex; y++) MapSnapshotFiles.writeSection(output, sections[y]);
        });
    }

    private void copyRecord(long offset, int size, RandomAccessFile output, byte[] buffer) throws IOException {
        if (size == 0) return;
        file.seek(offset);
        for (int remaining = size; remaining > 0;) {
            int amount = Math.min(remaining, buffer.length);
            file.readFully(buffer, 0, amount);
            output.write(buffer, 0, amount);
            remaining -= amount;
        }
    }

    private DataInputStream decode(long offset, int length) throws IOException {
        byte[] compressed = new byte[length];
        file.seek(offset);
        file.readFully(compressed);
        return new DataInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed), BUFFER), BUFFER));
    }

    private static byte[] compress(Encoder encoder) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(compressed, BUFFER), BUFFER))) {
            encoder.write(output);
        }
        return compressed.toByteArray();
    }

    static void checkHeight(int minY, int maxY) throws IOException {
        if (minY < -32_768 || maxY > 32_768 || maxY < minY || (maxY >> 4) - (minY >> 4) + 1 > MAX_HEIGHT)
            throw new IOException("Invalid survey tile height.");
    }

    @Override public void close() throws IOException { file.close(); }
}
