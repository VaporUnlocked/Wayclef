package dev.soprano.map;

import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Immutable 16×16×16 block section with a local palette. Null storage represents a uniform section. */
final class MapSection implements MapVolume.SectionView {
    static final int SIDE = 16;
    static final int CELLS = SIDE * SIDE * SIDE;
    private static final MapSection EMPTY = new MapSection(0, 0, null, null, 0, 0);

    private final int uniform, solidCount, bits;
    private final int[] palette;
    private final long[] words;
    private final long occupiedBricks;

    private MapSection(int uniform, int solidCount, int[] palette, long[] words, int bits, long occupiedBricks) {
        this.uniform = uniform;
        this.solidCount = solidCount;
        this.palette = palette;
        this.words = words;
        this.bits = bits;
        this.occupiedBricks = occupiedBricks;
    }

    static MapSection uniform(int material) {
        return material == 0 ? EMPTY : new MapSection(material, CELLS, null, null, 0, -1L);
    }

    static MapSection pack(int[] ids, int solid) {
        if (solid == 0) return EMPTY;
        int first = ids[0];
        boolean uniform = true;
        for (int id : ids) {
            if (id != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) return uniform(first);

        Map<Integer, Integer> indexes = new HashMap<>();
        for (int id : ids) indexes.computeIfAbsent(id, ignored -> indexes.size());
        int[] palette = new int[indexes.size()];
        indexes.forEach((id, i) -> palette[i] = id);
        int bits = bits(palette.length);
        long[] words = new long[wordCount(palette.length)];
        long bricks = solid == CELLS ? -1L : 0;
        for (int i = 0; i < ids.length; i++) {
            int offset = i * bits, word = offset >>> 6, shift = offset & 63;
            int id = ids[i];
            long local = indexes.get(id);
            words[word] |= local << shift;
            if (shift + bits > Long.SIZE) words[word + 1] |= local >>> (Long.SIZE - shift);
            if (id > 0 && bricks != -1L) bricks |= 1L << MapVolume.SectionView.brickIndex(i);
        }
        return new MapSection(0, solid, palette, words, bits, bricks);
    }

    /** Takes ownership of the decoded arrays and validates all references in the packed stream. */
    static MapSection fromPacked(int[] palette, long[] words) throws IOException {
        int bits = bits(palette.length);
        int solid = 0;
        long bricks = 0;
        for (int i = 0; i < CELLS; i++) {
            int local = localId(words, bits, i);
            if (local >= palette.length) throw new IOException("Invalid block palette reference in snapshot.");
            if (palette[local] > 0) {
                solid++;
                bricks |= 1L << MapVolume.SectionView.brickIndex(i);
            }
        }
        return new MapSection(0, solid, palette, words, bits, bricks);
    }

    static int wordCount(int paletteSize) {
        return (CELLS * bits(paletteSize) + Long.SIZE - 1) / Long.SIZE;
    }

    private static int bits(int paletteSize) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    int solidCount() { return solidCount; }
    int uniformId() { return words == null ? uniform : -1; }
    int packedBytes() { return words == null ? 0 : Integer.BYTES * palette.length + Long.BYTES * words.length; }

    MapSection remap(IntUnaryOperator ids) {
        if (words == null) return uniform == 0 ? this : uniform(ids.applyAsInt(uniform));
        int[] mapped = palette.clone();
        for (int index = 0; index < mapped.length; index++) {
            if (mapped[index] != 0) mapped[index] = ids.applyAsInt(mapped[index]);
        }
        return new MapSection(0, solidCount, mapped, words, bits, occupiedBricks);
    }

    void writePacked(DataOutput output) throws IOException {
        output.writeShort(palette.length);
        for (int id : palette) output.writeInt(id);
        for (long word : words) output.writeLong(word);
    }

    @Override public long occupiedBricks() { return occupiedBricks; }
    @Override public int uniformSolidMaterial() { return words == null ? uniform : 0; }

    @Override public int materialAt(int index) {
        return words == null ? uniform : palette[localId(words, bits, index)];
    }

    private static int localId(long[] words, int bits, int index) {
        int offset = index * bits, word = offset >>> 6, shift = offset & 63;
        long local = words[word] >>> shift;
        if (shift + bits > Long.SIZE) local |= words[word + 1] << (Long.SIZE - shift);
        return (int) (local & ((1L << bits) - 1));
    }
}
