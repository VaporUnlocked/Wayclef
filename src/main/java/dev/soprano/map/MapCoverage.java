package dev.soprano.map;

import dev.soprano.SopranoClient;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Chunk coverage retained independently of the bounded 3D terrain capture. */
public final class MapCoverage {
    private static final int MAGIC = 0x534F4301;
    private static final int LIMIT = 100_000;
    private static final Map<Path, MapCoverage> RECENT = new java.util.LinkedHashMap<>(8, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path, MapCoverage> eldest) { return size() > 8; }
    };
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "Soprano survey coverage");
        thread.setDaemon(true);
        return thread;
    });

    private final Path file;
    private final Set<Long> chunks;
    private boolean dirty;
    private volatile boolean writeFailed;

    private MapCoverage(Path file) {
        this.file = file;
        this.chunks = load(file);
    }

    public static MapCoverage open(Path directory, String scope) {
        if (scope == null) return new MapCoverage(null);
        Path file = directory.resolve(key(scope) + ".coverage").toAbsolutePath().normalize();
        return RECENT.computeIfAbsent(file, MapCoverage::new);
    }

    public boolean surveyed(int x, int z) { return chunks.contains(chunk(x, z)); }

    public void observe(int x, int z) {
        if (!chunks.add(chunk(x, z))) return;
        if (chunks.size() > LIMIT) chunks.remove(chunks.iterator().next());
        dirty = true;
    }

    public void flush() {
        if (file == null || !dirty && !writeFailed) return;
        long[] snapshot = chunks.stream().mapToLong(Long::longValue).toArray();
        dirty = false;
        WRITER.execute(() -> {
            try {
                write(file, snapshot);
                writeFailed = false;
            } catch (IOException problem) {
                writeFailed = true;
                SopranoClient.LOG.warn("Could not save Soprano survey coverage", problem);
            }
        });
    }

    public void clear() {
        chunks.clear();
        dirty = false;
        writeFailed = false;
        if (file == null) return;
        WRITER.execute(() -> {
            try { Files.deleteIfExists(file); }
            catch (IOException problem) { SopranoClient.LOG.warn("Could not clear Soprano survey coverage", problem); }
        });
    }

    private static Set<Long> load(Path file) {
        Set<Long> result = new LinkedHashSet<>();
        if (file == null || !Files.isRegularFile(file)) return result;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported survey coverage version");
            int count = input.readInt();
            if (count < 0 || count > LIMIT) throw new IOException("Invalid survey coverage count");
            for (int i = 0; i < count; i++) result.add(input.readLong());
            if (input.read() != -1 || result.size() != count) throw new IOException("Invalid survey coverage data");
        } catch (IOException problem) {
            SopranoClient.LOG.warn("Could not reopen Soprano survey coverage", problem);
            result.clear();
        }
        return result;
    }

    private static void write(Path file, long[] chunks) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "coverage-", ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(chunks.length);
                for (long chunk : chunks) output.writeLong(chunk);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long chunk(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private static String key(String scope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
