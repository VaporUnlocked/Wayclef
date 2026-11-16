package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.host.ClientEvents;
import dev.soprano.map.MapCoverage;
import dev.soprano.map.MapVolume.Box;
import dev.soprano.map.SurveyCache;
import dev.soprano.map.WorldMapCapture;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import dev.soprano.ui.PlanningViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Captures selected gaps from safe standing positions using the regular walking planner. */
public final class FootSurvey implements ClientEvents.Listener {
    private static final FootSurvey INSTANCE = new FootSurvey();
    private static final int MAX_COLUMNS = 4_096;
    private static final int CHUNK_SETTLE_TICKS = 15;
    private static final int SAVE_TIMEOUT_TICKS = 200;
    private record Column(int x, int z) {
        long distanceSquared(Column other) {
            long dx = (long) x - other.x, dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }
    }

    private final Navigation navigation = Navigation.get();
    private final Set<Column> missing = new LinkedHashSet<>();
    private final Set<Column> attempted = new HashSet<>();
    private ClientLevel level;
    private LocalPlayer player;
    private Scope scope;
    private Box region;
    private MapCoverage coverage;
    private SurveyCache cache;
    private Navigation.Options options;
    private WorldMapCapture capture;
    private Column destination;
    private Column scanningFrom, lastScanned;
    private Future<Set<Long>> retained;
    private int initialColumns, chunkWait, saveWait;
    private String detail;

    private FootSurvey() { }
    public static FootSurvey get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }
    public boolean active() { return options != null; }
    public String statusText() { return "Foot survey · " + detail; }

    public void start(Box selected, Navigation.Options requested) {
        if (selected == null) throw new IllegalArgumentException("Set both survey corners first.");
        Minecraft mc = Minecraft.getInstance();
        var module = SopranoClient.module();
        Scope current = module.storageScope();
        if (mc.level == null || mc.player == null || current == null) {
            throw new IllegalStateException("Join a named world before starting a foot survey.");
        }
        if (navigation.active()) throw new IllegalStateException("Stop the current route before starting a foot survey.");
        if (!Node.validColumn(selected.minX()) || !Node.validColumn(selected.maxX())
            || !Node.validColumn(selected.minZ()) || !Node.validColumn(selected.maxZ())
            || selected.minY() < mc.level.getMinY() || selected.maxY() >= mc.level.getMinY() + mc.level.getHeight()) {
            throw new IllegalArgumentException("The survey must be inside the current world's bounds.");
        }
        int minX = selected.minX() >> 4, maxX = selected.maxX() >> 4;
        int minZ = selected.minZ() >> 4, maxZ = selected.maxZ() >> 4;
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_COLUMNS) {
            throw new IllegalArgumentException("Select at most 4,096 chunk columns for a foot survey.");
        }

        var directory = mc.gameDirectory.toPath().resolve("soprano");
        cache = SurveyCache.open(directory.resolve("survey-cache"), current, module.surveyCacheLimitBytes());
        coverage = MapCoverage.open(directory.resolve("coverage"), current.server() + "\n" + current.world() + "\n" + current.dimension());
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) missing.add(new Column(x, z));
        }
        initialColumns = missing.size();
        if (cache != null) {
            retained = cache.coveredColumns(minX, minZ, maxX, maxZ, selected.minY() >> 4, selected.maxY() >> 4);
        }
        var permissions = requested.permissions();
        options = new Navigation.Options(requested.search(), requested.expansionsPerTick(), requested.steerView(), requested.autoReplan(),
            requested.planningBudgetMillis(), requested.replanLimit(), requested.partialSegmentLimit(), TransportMode.WALK,
            requested.elytraReserves(), requested.avoidMobs(), new RoutePermissions(0, 0, permissions.useDoors(), permissions.closeDoors()),
            requested.vitality(), requested.adaptivePlanning());
        player = mc.player;
        level = mc.level;
        scope = current;
        region = selected;
        chunkWait = 0;
        detail = "Checking selected terrain near you.";
    }

    @Override
    public void tickPost() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive()
            || !scope.equals(SopranoClient.module().storageScope())) {
            finish("World or player changed. Survey stopped.");
            return;
        }
        if (!Navigation.allowedScreen(mc.gui.screen()) || !mc.getWindow().isFocused()) {
            finish("Survey paused. Select the area again to continue.");
            return;
        }
        try {
            if (retained != null) {
                if (!retained.isDone()) return;
                readRetained();
            }
            if (destination != null) {
                checkArrival();
            } else if (chunkWait > 0) {
                chunkWait--;
            } else {
                scanOrWalk(mc);
            }
        } catch (IllegalArgumentException | IllegalStateException problem) {
            finish(problem.getMessage());
        }
    }

    @Override
    public void keyInput(KeyEvent input, ClientEvents.Action action) {
        if (!active() || action != ClientEvents.Action.PRESS) return;
        var mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        var keys = mc.options;
        if (keys.keyUp.matches(input) || keys.keyDown.matches(input)
            || keys.keyLeft.matches(input) || keys.keyRight.matches(input)
            || keys.keyJump.matches(input) || keys.keyShift.matches(input) || keys.keySprint.matches(input)) pause();
    }

    private void readRetained() {
        try {
            Set<Long> covered = retained.get();
            missing.removeIf(column -> covered.contains((long) column.x() << 32 | (column.z() & 0xffffffffL)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            SopranoClient.LOG.warn("Could not check cached survey coverage", failure);
        } finally {
            retained = null;
        }
    }

    private void checkArrival() {
        String status = navigation.ownedState(this);
        switch (status) {
            case "ARRIVED" -> {
                navigation.cancelOwned(this);
                destination = null;
                chunkWait = CHUNK_SETTLE_TICKS;
                detail = "Waiting for nearby terrain to load.";
            }
            case "FAILED" -> {
                navigation.cancelOwned(this);
                destination = null;
                detail = "Checking other approaches to the selected area.";
            }
            case "PAUSED", "STOPPED", "IDLE" -> finish(navigation.ownedStatus(this));
            default -> detail = navigation.ownedStatus(this) + " · " + missing.size() + " columns remaining.";
        }
    }

    private void scanOrWalk(Minecraft mc) {
        var feet = player.blockPosition();
        Column origin = new Column(feet.getX() >> 4, feet.getZ() >> 4);
        if (!missing.isEmpty() && !origin.equals(lastScanned)) {
            if (capture == null) {
                capture = new WorldMapCapture(mc, coverage, cache, false, region);
                scanningFrom = origin;
            }
            capture.tick();
            if (!capture.settled()) {
                detail = capture.progressText() + " · " + (initialColumns - missing.size()) + "/" + initialColumns + " selected columns.";
                return;
            }
            if (saveWait % 4 != 0 || !capture.flushSurvey()) {
                detail = "Saving surveyed sections.";
                if (++saveWait >= SAVE_TIMEOUT_TICKS) {
                    String problem = cache == null ? null : cache.problem();
                    finish(problem == null ? "Survey stopped while waiting for map storage." : problem);
                }
                return;
            }
            saveWait = 0;
            int minY = region.minY() >> 4, maxY = region.maxY() >> 4;
            missing.removeIf(column -> covered(column, minY, maxY));
            lastScanned = scanningFrom;
            capture = null;
            coverage.flush();
        }
        if (missing.isEmpty()) {
            finish("Survey complete. Covered " + initialColumns + " selected chunk columns.");
            return;
        }
        Column next = null;
        long best = Long.MAX_VALUE;
        for (Column column : missing) {
            if (attempted.contains(column) || column.equals(origin)) continue;
            long distance = column.distanceSquared(origin);
            if (distance < best) {
                best = distance;
                next = column;
            }
        }
        if (next == null) {
            finish("Survey stopped with " + missing.size() + " selected chunk columns still unreceived or unreachable.");
            return;
        }
        attempted.add(next);
        int x = Math.clamp(next.x() * 16 + 7, -30_000_000, 29_999_999);
        int z = Math.clamp(next.z() * 16 + 7, -30_000_000, 29_999_999);
        if (!navigation.requestOwned(this, new Goal(x, null, z, 8), options)) {
            throw new IllegalStateException("Another tool owns navigation.");
        }
        destination = next;
        detail = "Planning a walking route toward unreceived terrain.";
    }

    private boolean covered(Column column, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            if (capture.sectionState(column.x(), y, column.z()) < 0) return false;
        }
        return true;
    }

    private void flush() {
        if (capture != null) capture.flushSurvey();
        if (coverage != null) coverage.flush();
        capture = null;
    }

    private void finish(String message) {
        stop();
        SopranoClient.feedback(message);
    }

    public void pause() {
        if (active()) finish("Survey paused. Select the area again to continue from retained coverage.");
    }

    public void stop() { stop(true); }

    private void stop(boolean refreshMap) {
        if (!active()) return;
        navigation.cancelOwned(this);
        flush();
        if (refreshMap) PlanningViewport.restoreRetainedSurvey(region);
        options = null;
        destination = null;
        scanningFrom = lastScanned = null;
        saveWait = 0;
        if (retained != null) retained.cancel(false);
        retained = null;
        region = null;
        level = null;
        player = null;
        scope = null;
        coverage = null;
        cache = null;
        attempted.clear();
        missing.clear();
    }

    @Override public void disconnected() { stop(false); }
}
