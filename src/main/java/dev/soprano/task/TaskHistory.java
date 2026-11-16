package dev.soprano.task;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Bounded task definitions stored independently for each server and world. */
public final class TaskHistory {
    public static final int MAX_ENTRIES = 128;

    private static final int MAGIC = 0x53544831;
    private static final int VERSION = 1;
    private static final int MAX_SETTINGS = 24;
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final Object WRITE_LOCK = new Object();

    public record Scope(String serverKey, String worldKey) {
        public Scope {
            validate("server key", serverKey, 512, false);
            validate("world key", worldKey, 512, false);
            var encoder = StandardCharsets.UTF_8.newEncoder();
            if (!encoder.canEncode(serverKey) || !encoder.canEncode(worldKey)) {
                throw new IllegalArgumentException("Invalid task history scope encoding");
            }
        }
    }

    /** A goal uses the encoding of its task kind. The dimension is part of the saved definition. */
    public record Task(String kind, String dimension, String goal, Map<String, String> settings) {
        public Task {
            validate("task kind", kind, 128, false);
            validate("dimension", dimension, 256, false);
            validate("goal", goal, 4096, true);
            Objects.requireNonNull(settings, "settings");
            if (settings.size() > MAX_SETTINGS) throw new IllegalArgumentException("Too many task settings");
            settings = Map.copyOf(settings);
            settings.forEach((key, value) -> {
                validate("setting name", key, 64, false);
                validate("setting value", value, 1024, true);
            });
        }
    }

    public record Entry(UUID id, Instant recordedAt, Task task) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(task, "task");
        }

        public static Entry capture(Task task) {
            return new Entry(UUID.randomUUID(), Instant.now(), task);
        }
    }

    private final Path root;

    public TaskHistory(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public List<Entry> list(Scope scope) throws IOException {
        Path directory = serverDirectory(scope, false);
        return directory == null ? List.of() : read(historyFile(directory, scope), scope);
    }

    /** Returns a saved definition for the caller to preview. No task is started here. */
    public Optional<Entry> reopen(Scope scope, UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        return list(scope).stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** Retrying with the same entry is idempotent while it remains in history. */
    public void record(Scope scope, Entry entry) throws IOException {
        Objects.requireNonNull(entry, "entry");
        synchronized (WRITE_LOCK) {
            Path directory = serverDirectory(scope, true);
            Path file = historyFile(directory, scope);
            Path lock = directory.resolve(file.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = lockChannel.lock()) {
                List<Entry> previous = read(file, scope);
                for (Entry existing : previous) {
                    if (!existing.id().equals(entry.id())) continue;
                    if (existing.equals(entry)) return;
                    throw new IllegalArgumentException("Task history ID is already in use");
                }

                List<Entry> updated = new ArrayList<>(MAX_ENTRIES);
                updated.add(entry);
                updated.addAll(previous.subList(0, Math.min(previous.size(), MAX_ENTRIES - 1)));
                write(file, scope, updated);
            }
        }
    }

    private Path serverDirectory(Scope scope, boolean create) throws IOException {
        Objects.requireNonNull(scope, "scope");
        if (create) Files.createDirectories(root);
        if (!create && Files.notExists(root)) return null;

        Path resolvedRoot = root.toRealPath();
        Path directory = resolvedRoot.resolve(hash(scope.serverKey()));
        if (create) {
            try {
                Files.createDirectory(directory);
            } catch (FileAlreadyExistsException ignored) {
                // The existing directory is checked below.
            }
        }
        if (!create && Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.toRealPath())) {
            throw new IOException("Task history server directory is not within its storage root");
        }
        return directory;
    }

    private static Path historyFile(Path directory, Scope scope) {
        return directory.resolve(hash(scope.worldKey()) + ".sth");
    }

    private static List<Entry> read(Path file, Scope scope) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             DataInputStream input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)))) {
            if (channel.size() > MAX_FILE_BYTES) throw new IOException("Task history file is too large");
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported task history format");
            }
            if (!scope.serverKey().equals(input.readUTF()) || !scope.worldKey().equals(input.readUTF())) {
                throw new IOException("Task history belongs to a different server or world");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid task history entry count");
            List<Entry> entries = new ArrayList<>(count);
            Set<UUID> ids = new HashSet<>();
            for (int index = 0; index < count; index++) {
                Entry entry = readEntry(input);
                if (!ids.add(entry.id())) throw new IOException("Duplicate task history ID");
                entries.add(entry);
            }
            if (input.read() != -1) throw new IOException("Unexpected data in task history");
            return List.copyOf(entries);
        } catch (NoSuchFileException ignored) {
            return List.of();
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new IOException("Invalid task history entry", invalid);
        }
    }

    private static Entry readEntry(DataInputStream input) throws IOException {
        UUID id = new UUID(input.readLong(), input.readLong());
        long seconds = input.readLong();
        int nanos = input.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new IOException("Invalid task history timestamp");
        Instant recordedAt = Instant.ofEpochSecond(seconds, nanos);
        String kind = input.readUTF();
        String dimension = input.readUTF();
        String goal = input.readUTF();
        int count = input.readInt();
        if (count < 0 || count > MAX_SETTINGS) throw new IOException("Invalid task history setting count");
        Map<String, String> settings = new HashMap<>();
        for (int index = 0; index < count; index++) {
            if (settings.putIfAbsent(input.readUTF(), input.readUTF()) != null) {
                throw new IOException("Duplicate task history setting");
            }
        }
        return new Entry(id, recordedAt, new Task(kind, dimension, goal, settings));
    }

    private static void write(Path file, Scope scope, List<Entry> entries) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "task-history-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeUTF(scope.serverKey());
                output.writeUTF(scope.worldKey());
                output.writeInt(entries.size());
                for (Entry entry : entries) writeEntry(output, entry);
                output.flush();
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeEntry(DataOutputStream output, Entry entry) throws IOException {
        output.writeLong(entry.id().getMostSignificantBits());
        output.writeLong(entry.id().getLeastSignificantBits());
        output.writeLong(entry.recordedAt().getEpochSecond());
        output.writeInt(entry.recordedAt().getNano());
        Task task = entry.task();
        output.writeUTF(task.kind());
        output.writeUTF(task.dimension());
        output.writeUTF(task.goal());
        output.writeInt(task.settings().size());
        for (Map.Entry<String, String> setting : task.settings().entrySet()) {
            output.writeUTF(setting.getKey());
            output.writeUTF(setting.getValue());
        }
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validate(String field, String value, int limit, boolean allowBlank) {
        Objects.requireNonNull(value, field);
        if (value.length() > limit || (!allowBlank && value.isBlank())) {
            throw new IllegalArgumentException("Invalid task history " + field);
        }
    }
}
