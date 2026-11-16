package dev.soprano.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class TaskHistoryTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("soprano-task-history-").toRealPath();
        try {
            scopesAndReopen(temporary.resolve("scopes"));
            retention(temporary.resolve("retention"));
            concurrentWrites(temporary.resolve("concurrent"));
            corruptHistoryIsPreserved(temporary.resolve("corrupt"));
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.normalize().startsWith(temporary)) throw new IOException("Unexpected test path");
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("TaskHistoryTest passed");
    }

    private static void scopesAndReopen(Path root) throws IOException {
        var history = new TaskHistory(root);
        var origin = new TaskHistory.Scope("../server", "world/a");
        var otherWorld = new TaskHistory.Scope("../server", "world/b");
        var otherServer = new TaskHistory.Scope("../other", "world/a");
        check(history.list(origin).isEmpty(), "New scope should be empty");
        check(!Files.exists(root), "Reading an empty scope should not write");

        Map<String, String> mutableSettings = new HashMap<>();
        mutableSettings.put("rockets", "16");
        var task = new TaskHistory.Task("travel", "minecraft:the_end", "gate-α", mutableSettings);
        var entry = TaskHistory.Entry.capture(task);
        mutableSettings.put("rockets", "0");
        history.record(origin, entry);
        history.record(origin, entry);

        var reopened = new TaskHistory(root).reopen(origin, entry.id()).orElseThrow();
        check(reopened.equals(entry), "Reopening should preserve all fields");
        check(reopened.task().settings().equals(Map.of("rockets", "16")), "Settings should be a snapshot");
        check(history.list(origin).size() == 1, "Retry should not duplicate entries");
        check(history.reopen(otherWorld, entry.id()).isEmpty(), "Worlds should be isolated");
        check(history.reopen(otherServer, entry.id()).isEmpty(), "Servers should be isolated");
        var conflictingEntry = new TaskHistory.Entry(entry.id(), Instant.EPOCH, task);
        try {
            history.record(origin, conflictingEntry);
            throw new AssertionError("Conflicting retry should fail");
        } catch (IllegalArgumentException expected) {
            check(history.list(origin).equals(List.of(entry)), "Conflict should preserve original entry");
        }
    }

    private static void retention(Path root) throws IOException {
        var history = new TaskHistory(root);
        var scope = new TaskHistory.Scope("local", "main");
        TaskHistory.Entry oldest = entry("0");
        history.record(scope, oldest);
        TaskHistory.Entry newest = oldest;
        for (int index = 1; index <= TaskHistory.MAX_ENTRIES; index++) {
            newest = entry(Integer.toString(index));
            history.record(scope, newest);
        }
        List<TaskHistory.Entry> entries = history.list(scope);
        check(entries.size() == TaskHistory.MAX_ENTRIES, "History should be bounded");
        check(entries.get(0).equals(newest), "Newest entry should come first");
        check(history.reopen(scope, oldest.id()).isEmpty(), "Oldest entry should be pruned");
    }

    private static void concurrentWrites(Path root) throws Exception {
        var scope = new TaskHistory.Scope("server", "world");
        var workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Void>> writes = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                TaskHistory.Entry entry = entry(Integer.toString(index));
                writes.add(workers.submit(() -> {
                    new TaskHistory(root).record(scope, entry);
                    return null;
                }));
            }
            for (Future<Void> write : writes) write.get();
            check(new TaskHistory(root).list(scope).size() == writes.size(), "Concurrent writes should all be retained");
        } finally {
            workers.shutdownNow();
        }
    }

    private static void corruptHistoryIsPreserved(Path root) throws IOException {
        var history = new TaskHistory(root);
        var scope = new TaskHistory.Scope("server", "world");
        history.record(scope, entry("before"));
        Path file;
        try (var paths = Files.walk(root)) {
            file = paths.filter(path -> path.getFileName().toString().endsWith(".sth")).findFirst().orElseThrow();
        }
        Files.writeString(file, "invalid history");
        try {
            history.record(scope, entry("after"));
            throw new AssertionError("A corrupt history should not be overwritten");
        } catch (IOException expected) {
            check(Files.readString(file).equals("invalid history"), "Failed append should preserve existing bytes");
        }
    }

    private static TaskHistory.Entry entry(String goal) {
        return TaskHistory.Entry.capture(new TaskHistory.Task("travel", "minecraft:overworld", goal, Map.of()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
