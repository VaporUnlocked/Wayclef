package dev.soprano.build;

import net.minecraft.client.multiplayer.ClientLevel;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Loads schematic bytes off the client thread and commits to the original, still-active plan. */
public final class BlueprintImport {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Soprano schematic import");
        thread.setDaemon(true);
        return thread;
    });

    private record Request(ClientLevel level, BuildPlan plan, long revision, Future<BlueprintActions.PreparedImport> job) { }

    private Request pending;

    public boolean pending() { return pending != null; }

    public void start(Path path, BuildPlan.Cell origin, ClientLevel level, BuildPlan plan) {
        if (pending != null) throw new IllegalStateException("Wait for the current schematic import.");
        Future<BlueprintActions.PreparedImport> job = WORKER.submit(() -> BlueprintActions.prepareImport(path, origin));
        pending = new Request(level, plan, plan.revision(), job);
    }

    public BlueprintActions.ImportResult poll(ClientLevel level, BuildPlan plan) {
        Request request = pending;
        if (request == null) return null;
        if (request.level() != level || request.plan() != plan || request.revision() != plan.revision()) {
            cancel();
            throw new IllegalStateException("The active blueprint changed during import. Open the file again to replace it.");
        }
        if (!request.job().isDone()) return null;
        pending = null;
        try {
            return request.job().get().apply(plan);
        } catch (ExecutionException problem) {
            Throwable cause = problem.getCause();
            throw new IllegalStateException("Could not import schematic. " + cause.getMessage(), cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Schematic import interrupted.", interrupted);
        }
    }

    public void cancel() {
        if (pending == null) return;
        pending.job().cancel(true);
        pending = null;
    }
}
