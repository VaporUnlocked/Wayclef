package dev.soprano.ui;

import dev.soprano.SopranoClient;
import dev.soprano.build.BuildPlan;
import dev.soprano.build.BuildPlanFiles;
import dev.soprano.game.Navigation;
import dev.soprano.path.ItineraryStore;
import dev.soprano.path.RouteItinerary;
import dev.soprano.task.TaskHistory;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Binds saved definitions to the live server, world and dimension. */
final class SopranoHistory {
    private SopranoHistory() { }

    static String dimension() {
        var scope = Navigation.currentScope();
        return scope == null ? null : scope.dimension();
    }

    static List<TaskHistory.Entry> entries() {
        TaskHistory.Scope scope = scope();
        if (scope == null) return List.of();
        try {
            return store().list(scope);
        } catch (IOException problem) {
            throw new IllegalStateException("Could not open task history. " + problem.getMessage(), problem);
        }
    }

    static TaskHistory.Entry reopen(UUID id) {
        TaskHistory.Scope scope = scope();
        if (scope == null) throw new IllegalStateException("Join the saved task's world first.");
        try {
            return store().reopen(scope, id).orElseThrow(() -> new IllegalStateException("This task is no longer in this world's history."));
        } catch (IOException problem) {
            throw new IllegalStateException("Could not reopen this task. " + problem.getMessage(), problem);
        }
    }

    static void record(String kind, String goal, Map<String, String> settings) {
        String dimension = dimension();
        if (dimension != null) record(new TaskHistory.Task(kind, dimension, goal, settings), null);
    }

    static void record(TaskHistory.Task task, BuildPlan blueprint) {
        TaskHistory.Scope scope = scope();
        if (scope == null || !task.dimension().equals(dimension())) return;
        TaskHistory.Entry entry = TaskHistory.Entry.capture(task);
        Path attachment = blueprints().resolve(entry.id() + ".sopplan");
        try {
            if (blueprint != null) BuildPlanFiles.save(blueprints(), entry.id().toString(), blueprint.snapshot());
            store().record(scope, entry);
        } catch (IOException | IllegalArgumentException problem) {
            if (blueprint != null) {
                try { Files.deleteIfExists(attachment); }
                catch (IOException cleanup) { problem.addSuppressed(cleanup); }
            }
            SopranoClient.LOG.warn("Could not record Soprano task history", problem);
        }
    }

    static BuildPlan blueprint(TaskHistory.Entry entry) {
        try {
            BuildPlan result = new BuildPlan();
            result.replace(BuildPlanFiles.load(blueprints(), entry.id().toString()));
            return result;
        } catch (IOException problem) {
            throw new IllegalStateException("Could not reopen the saved blueprint. " + problem.getMessage(), problem);
        }
    }

    static void recordTrip(RouteItinerary trip, Navigation.Options options) {
        TaskHistory.Scope scope = scope();
        String dimension = dimension();
        if (scope == null || dimension == null) return;
        TaskHistory.Entry entry = TaskHistory.Entry.capture(new TaskHistory.Task("trip", dimension, trip.name(),
            Map.of("navigation", HistorySettings.navigation(options))));
        try {
            tripStore(entry, scope).save(trip);
            store().record(scope, entry);
        } catch (IOException problem) {
            SopranoClient.LOG.warn("Could not record Soprano trip history", problem);
        }
    }

    static RouteItinerary trip(TaskHistory.Entry entry) {
        TaskHistory.Scope scope = scope();
        if (scope == null) throw new IllegalStateException("Join the saved trip's world first.");
        try {
            return tripStore(entry, scope).load(entry.task().goal())
                .orElseThrow(() -> new IllegalStateException("The saved trip definition is unavailable."));
        } catch (IOException problem) {
            throw new IllegalStateException("Could not reopen the saved trip. " + problem.getMessage(), problem);
        }
    }

    private static ItineraryStore tripStore(TaskHistory.Entry entry, TaskHistory.Scope scope) {
        return new ItineraryStore(root().resolve("trips").resolve(entry.id().toString()), scope);
    }

    private static TaskHistory.Scope scope() {
        var current = Navigation.currentScope();
        return current == null ? null : new TaskHistory.Scope(current.server(), current.world());
    }

    private static Path root() { return Minecraft.getInstance().gameDirectory.toPath().resolve("soprano").resolve("history"); }
    private static Path blueprints() { return root().resolve("blueprints"); }
    private static TaskHistory store() { return new TaskHistory(root()); }
}
