package dev.soprano.ui;

import dev.soprano.map.MapSnapshot;
import dev.soprano.map.MapSnapshotFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Keeps file I/O off both the client and the map renderer. */
final class SavedLocations implements AutoCloseable {
    record Opened(String name, MapSnapshot snapshot) { }

    private enum Operation { LIST, SAVE, OPEN }
    private record Result(Opened opened, List<String> names, String message) { }

    private final Path directory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Soprano saved locations");
        thread.setDaemon(true);
        return thread;
    });

    private String world;
    private List<String> names = List.of();
    private Future<Result> pending;
    private Operation operation;
    private long context;
    private long pendingContext;
    private String message;
    private boolean listRequested;

    SavedLocations(Minecraft minecraft) {
        directory = minecraft.gameDirectory.toPath().resolve("soprano").resolve("locations");
    }

    void setWorld(Minecraft minecraft) {
        String key = worldKey(minecraft);
        if (Objects.equals(world, key)) return;
        world = key;
        names = List.of();
        message = null;
        listRequested = false;
        context++;
    }

    boolean available() { return world != null; }
    boolean busy() { return pending != null; }
    List<String> names() { return names; }
    String message() { return message; }

    void list() {
        if (!available()) return;
        if (busy()) listRequested = true;
        else submit(Operation.LIST, "", null);
    }

    void save(String name, MapSnapshot snapshot) {
        submit(Operation.SAVE, name, snapshot);
    }

    void open(String name) {
        submit(Operation.OPEN, name, null);
    }

    void ignorePendingOpen() {
        if (operation == Operation.OPEN && busy() && pendingContext == context) {
            context++;
            message = null;
        }
    }

    private void submit(Operation request, String name, MapSnapshot snapshot) {
        if (!available()) throw new IllegalStateException("Join the location's world or server first.");
        if (busy()) throw new IllegalStateException("A location is already loading or saving.");
        String scope = world;
        pendingContext = context;
        operation = request;
        message = switch (request) {
            case LIST -> "Reading saved locations…";
            case SAVE -> "Saving location…";
            case OPEN -> "Opening " + name + "…";
        };
        pending = worker.submit(() -> switch (request) {
            case LIST -> new Result(null, MapSnapshotFiles.list(directory, scope), null);
            case SAVE -> {
                MapSnapshotFiles.save(directory, scope, name, snapshot);
                yield new Result(null, MapSnapshotFiles.list(directory, scope), "Saved " + name + ".");
            }
            case OPEN -> new Result(new Opened(name, MapSnapshotFiles.load(directory, scope, name)), null, "Opened " + name + ".");
        });
    }

    Opened poll() {
        if (pending == null || !pending.isDone()) return null;
        try {
            Result result = pending.get();
            if (context != pendingContext) return null;
            if (result.names() != null) names = result.names();
            message = result.message();
            return result.opened();
        } catch (ExecutionException failed) {
            if (context == pendingContext) {
                Throwable problem = failed.getCause();
                message = problem instanceof IOException || problem instanceof IllegalArgumentException
                    ? problem.getMessage() : "Could not read or save this location.";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            pending = null;
            operation = null;
            if (listRequested) {
                listRequested = false;
                list();
            }
        }
        return null;
    }

    private static String worldKey(Minecraft minecraft) {
        if (minecraft.level == null) return null;
        String dimension = minecraft.level.dimension().identifier().toString();
        IntegratedServer singleplayer = minecraft.getSingleplayerServer();
        if (singleplayer != null) {
            Path folder = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "singleplayer\n" + folder + "\n" + dimension;
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank())
            return "multiplayer\n" + server.ip.strip().toLowerCase(Locale.ROOT) + "\n" + dimension;
        return null;
    }

    @Override public void close() {
        worker.shutdown();
    }
}
