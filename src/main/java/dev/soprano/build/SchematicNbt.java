package dev.soprano.build;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Reads bounded, big-endian Java Edition NBT from a gzip stream. */
final class SchematicNbt {
    static final long MAX_COMPRESSED_BYTES = 32L * 1024 * 1024;
    private static final long MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_TAGS = 500_000;

    private final LimitedInputStream bytes;
    private final DataInputStream input;
    private int remainingTags = MAX_TAGS;

    private SchematicNbt(LimitedInputStream bytes) {
        this.bytes = bytes;
        this.input = new DataInputStream(bytes);
    }

    /** Consumes the stream without taking ownership of the caller's stream. */
    static Compound readCompressed(InputStream input) throws IOException {
        LimitedInputStream compressed = new LimitedInputStream(input, MAX_COMPRESSED_BYTES, "Compressed schematic");
        try (var gzip = new GZIPInputStream(compressed)) {
            var decompressed = new LimitedInputStream(new BufferedInputStream(gzip), MAX_DECOMPRESSED_BYTES, "Decoded NBT");
            return new SchematicNbt(decompressed).readRoot();
        } catch (EOFException exception) {
            throw new IOException("Truncated schematic NBT", exception);
        }
    }

    private Compound readRoot() throws IOException {
        if (input.readUnsignedByte() != 10) throw new IOException("Schematic NBT root must be a compound");
        input.readUTF();
        Compound root = (Compound) readValue(10, 0);
        if (input.read() != -1) throw new IOException("Unexpected data after schematic NBT root");
        return root;
    }

    private Object readValue(int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Schematic NBT is nested too deeply");
        if (--remainingTags < 0) throw new IOException("Schematic NBT contains too many tags");
        return switch (type) {
            case 1 -> input.readByte();
            case 2 -> input.readShort();
            case 3 -> input.readInt();
            case 4 -> input.readLong();
            case 5 -> input.readFloat();
            case 6 -> input.readDouble();
            case 7 -> readByteArray();
            case 8 -> input.readUTF();
            case 9 -> readList(depth);
            case 10 -> readCompound(depth);
            case 11 -> readIntArray();
            case 12 -> readLongArray();
            default -> throw new IOException("Unknown schematic NBT tag type " + type);
        };
    }

    private Compound readCompound(int depth) throws IOException {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int type = input.readUnsignedByte(); type != 0; type = input.readUnsignedByte()) {
            String name = input.readUTF();
            if (fields.containsKey(name)) throw new IOException("Duplicate schematic NBT field: " + name);
            fields.put(name, readValue(type, depth + 1));
        }
        return new Compound(fields);
    }

    private List<Object> readList(int depth) throws IOException {
        int type = input.readUnsignedByte();
        int length = input.readInt();
        if (length < 0 || length > remainingTags || length > bytes.remaining()) {
            throw new IOException("Invalid or oversized schematic NBT list");
        }
        if (type > 12 || (type == 0 && length != 0)) throw new IOException("Invalid schematic NBT list type");
        List<Object> list = new ArrayList<>(length);
        for (int i = 0; i < length; i++) list.add(readValue(type, depth + 1));
        return list;
    }

    private byte[] readByteArray() throws IOException {
        byte[] result = new byte[arrayLength(1)];
        input.readFully(result);
        return result;
    }

    private int[] readIntArray() throws IOException {
        int[] result = new int[arrayLength(Integer.BYTES)];
        for (int i = 0; i < result.length; i++) result[i] = input.readInt();
        return result;
    }

    private long[] readLongArray() throws IOException {
        long[] result = new long[arrayLength(Long.BYTES)];
        for (int i = 0; i < result.length; i++) result[i] = input.readLong();
        return result;
    }

    private int arrayLength(int width) throws IOException {
        int length = input.readInt();
        if (length < 0 || (long) length * width > bytes.remaining()) {
            throw new IOException("Invalid or oversized schematic NBT array");
        }
        return length;
    }

    record Compound(Map<String, Object> fields) {
        <T> T required(String field, Class<T> type) throws IOException {
            if (!fields.containsKey(field)) throw new IOException("Missing schematic field " + field);
            return typed(field, type);
        }

        <T> T optional(String field, Class<T> type) throws IOException {
            return fields.containsKey(field) ? typed(field, type) : null;
        }

        private <T> T typed(String field, Class<T> type) throws IOException {
            Object value = fields.get(field);
            if (!type.isInstance(value)) throw new IOException("Unexpected type for schematic field " + field);
            return type.cast(value);
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final String label;
        private long remaining;

        private LimitedInputStream(InputStream input, long limit, String label) {
            super(input);
            this.remaining = limit;
            this.label = label;
        }

        private long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) consume(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) return 0;
            int count = in.read(buffer, offset, (int) Math.min(length, Math.max(1, remaining)));
            if (count > 0) consume(count);
            return count;
        }

        private void consume(int count) throws IOException {
            remaining -= count;
            if (remaining < 0) throw new IOException(label + " exceeds the size limit");
        }

        @Override
        public void close() {
            // The caller owns the underlying stream.
        }
    }
}
