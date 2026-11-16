package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.game.Navigation;
import dev.soprano.map.MapAnnotations.Marker;
import dev.soprano.map.PortalAperture.Cell;
import dev.soprano.map.PortalAperture.Portal;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Observes active portals in data already loaded by the Minecraft client. */
public final class PortalTracker {
    private static final PortalTracker INSTANCE = new PortalTracker();
    private static final int PALETTE_CHECKS_PER_TICK = 160;
    private static final int VERIFY_PER_TICK = 32;
    private static final int RESCAN_TICKS = 100;
    private static final int SAVE_DELAY = 20;
    private static final long SCAN_BUDGET_NANOS = 1_500_000;

    private final ExecutorService files = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Soprano portal observations");
        thread.setDaemon(true);
        return thread;
    });
    private final TreeMap<BlockPos, Portal> observed = new TreeMap<>(PortalAperture.ANCHOR_ORDER);
    private Scope scope;
    private Path root;
    private Future<List<Portal>> loading;
    private Future<Boolean> writing;
    private boolean readFailed, dirty, viewChanged;
    private List<Portal> verification = List.of();
    private List<Marker> markers = List.of();
    private int tick, rescanAt, writeAt, nextVerification;
    private ClientLevel scanLevel;
    private Sweep sweep;

    private PortalTracker() { }

    public static void tick(Minecraft client) { INSTANCE.update(client); }

    /** A published view of this dimension's live and previously recorded portals. */
    public static List<Marker> markers(Minecraft client) {
        return client.level != null && client.level == INSTANCE.scanLevel ? INSTANCE.markers : List.of();
    }

    public static boolean isNether(Minecraft client, BlockPos anchor) {
        if (client.level == null || client.level != INSTANCE.scanLevel) return false;
        var portal = INSTANCE.observed.get(anchor);
        return portal != null && (portal.kind() == PortalAperture.Kind.NETHER_X || portal.kind() == PortalAperture.Kind.NETHER_Z);
    }

    /** Resolves any loaded aperture block to its canonical anchor. */
    public static BlockPos anchor(ClientLevel level, BlockPos block) {
        var actual = cell(level, block);
        if (actual.kind() == null) return null;
        var scan = PortalAperture.scan(block, actual.kind(), at -> cell(level, at));
        return scan.portal() == null ? null : scan.portal().anchor();
    }

    public static BlockPos mark(Minecraft client, BlockPos block) {
        if (client.level == null) throw new IllegalStateException("Join a world first.");
        INSTANCE.select(client);
        if (INSTANCE.scope == null || !INSTANCE.finishRead()) throw new IllegalStateException("Portal knowledge is loading.");
        var actual = cell(client.level, block);
        if (actual.kind() == null) throw new IllegalArgumentException("Choose a loaded active portal block.");
        var scan = PortalAperture.scan(block, actual.kind(), at -> cell(client.level, at));
        if (scan.portal() == null) throw new IllegalStateException("The entire portal aperture must be loaded.");
        INSTANCE.discover(scan.portal(), scan.cells());
        INSTANCE.publish();
        return scan.portal().anchor();
    }

    /** Enqueues observed changes before the client drops its world. */
    public static void flush() { INSTANCE.save(); }

    /** Gives the queued writes time to finish before the Minecraft client exits. */
    public static void finishWrites() {
        flush();
        try {
            INSTANCE.files.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            SopranoClient.LOG.warn("Could not finish saving Soprano portal observations", failure);
        }
    }

    private void update(Minecraft client) {
        select(client);
        if (scope == null || client.level == null || client.player == null || !finishRead()) return;
        ++tick;
        if (writing != null && writing.isDone()) {
            if (!writeSucceeded()) changed();
            writing = null;
        }
        ClientLevel level = client.level;
        verify(level);
        ChunkPos center = client.player.chunkPosition();
        int radius = Math.max(1, client.options.getEffectiveRenderDistance()) + 1;
        if (sweep != null && !sweep.covers(center, radius)) sweep = null;
        if (sweep == null && tick >= rescanAt) sweep = new Sweep(center, radius);
        if (sweep != null && sweep.scan(level, this)) {
            sweep = null;
            rescanAt = tick + RESCAN_TICKS;
        }
        if (viewChanged) publish();
        if (writing == null && dirty && tick >= writeAt) save();
    }

    private void select(Minecraft client) {
        var module = SopranoClient.module();
        Scope active = client.level == null ? null : module == null ? Navigation.currentScope() : module.storageScope();
        if (scanLevel != client.level) {
            scanLevel = client.level;
            sweep = null;
            rescanAt = 0;
        }
        if (Objects.equals(scope, active)) return;
        save();
        scope = active;
        observed.clear();
        verification = List.of();
        markers = List.of();
        readingReset();
        if (active == null) return;
        root = client.gameDirectory.toPath().resolve("soprano").resolve("portals");
        Path directory = root;
        loading = files.submit(() -> PortalFiles.read(directory, active));
    }

    private void readingReset() {
        loading = null;
        writing = null;
        readFailed = dirty = viewChanged = false;
        sweep = null;
        tick = rescanAt = writeAt = nextVerification = 0;
    }

    private boolean finishRead() {
        if (loading == null) return true;
        if (!loading.isDone()) return false;
        try {
            for (Portal portal : loading.get()) observed.put(portal.anchor(), portal);
            publish();
        } catch (ExecutionException failure) {
            readFailed = true;
            SopranoClient.LOG.warn("Could not read Soprano portal observations", failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        loading = null;
        return true;
    }

    private boolean writeSucceeded() {
        try {
            return writing.get();
        } catch (ExecutionException failure) {
            SopranoClient.LOG.warn("Could not save Soprano portal observations", failure.getCause());
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void save() {
        if (!dirty || scope == null || readFailed) return;
        var saved = List.copyOf(observed.values());
        Path directory = root;
        Scope destination = scope;
        writing = files.submit(() -> {
            try {
                PortalFiles.write(directory, destination, saved);
                return true;
            } catch (IOException failure) {
                SopranoClient.LOG.warn("Could not save Soprano portal observations", failure);
                return false;
            }
        });
        dirty = false;
    }

    private void verify(ClientLevel level) {
        for (int i = 0; i < Math.min(VERIFY_PER_TICK, verification.size()); i++) {
            if (nextVerification >= verification.size()) nextVerification = 0;
            Portal saved = verification.get(nextVerification++);
            Cell actual = cell(level, saved.anchor());
            if (actual.loaded() && actual.kind() != saved.kind() && observed.remove(saved.anchor(), saved)) {
                changed();
                if (saved.kind() == PortalAperture.Kind.NETHER_X || saved.kind() == PortalAperture.Kind.NETHER_Z) {
                    PortalLinks.invalidated(Minecraft.getInstance(), saved.anchor());
                }
            }
        }
    }

    private void discover(Portal portal, Set<BlockPos> cells) {
        // A resized or joined aperture can include an anchor observed during an earlier scan.
        for (BlockPos cell : cells) {
            if (!cell.equals(portal.anchor()) && observed.remove(cell) != null) changed();
        }
        if (observed.size() >= PortalFiles.MAX_PORTALS && !observed.containsKey(portal.anchor())) return;
        if (!portal.equals(observed.put(portal.anchor(), portal))) changed();
    }

    private void changed() {
        viewChanged = true;
        if (!dirty) writeAt = tick + SAVE_DELAY;
        dirty = true;
    }

    private void publish() {
        verification = List.copyOf(observed.values());
        markers = verification.stream()
            .map(portal -> new Marker(MapAnnotations.Kind.PORTAL, portal.anchor(), portal.kind().label()))
            .toList();
        viewChanged = false;
    }

    private static LevelChunk loaded(ClientLevel level, int x, int z) {
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private static Cell cell(ClientLevel level, BlockPos at) {
        LevelChunk chunk = loaded(level, at.getX() >> 4, at.getZ() >> 4);
        if (chunk == null) return Cell.UNLOADED;
        if (at.getY() < level.getMinY() || at.getY() > level.getMaxY()) return Cell.OTHER;
        return Cell.of(chunk.getBlockState(at));
    }

    /** A near-to-far chunk sweep, with ordinary sections skipped at their block palette. */
    private static final class Sweep {
        private final ChunkPos center;
        private final int radius;
        private int ring, perimeterIndex, sectionIndex;
        private LevelChunk chunk;
        private SectionScan details;

        Sweep(ChunkPos center, int radius) {
            this.center = center;
            this.radius = radius;
        }

        boolean covers(ChunkPos at, int currentRadius) {
            return radius == currentRadius && Math.abs(at.x() - center.x()) <= 2 && Math.abs(at.z() - center.z()) <= 2;
        }

        boolean scan(ClientLevel level, PortalTracker tracker) {
            long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
            if (chunk != null && chunk != loaded(level, chunk.getPos().x(), chunk.getPos().z())) {
                chunk = null;
                details = null;
            }
            for (int attempts = 0; attempts < PALETTE_CHECKS_PER_TICK && System.nanoTime() < deadline; attempts++) {
                if (details != null) {
                    if (!details.scan(level, tracker, deadline)) return false;
                    details = null;
                }
                if (chunk == null || sectionIndex == chunk.getSections().length) {
                    if (ring > radius) return true;
                    ChunkPos next = nextChunk();
                    chunk = loaded(level, next.x(), next.z());
                    sectionIndex = 0;
                    if (chunk == null) continue;
                }
                int index = sectionIndex++;
                var section = chunk.getSections()[index];
                if (section != null && !section.hasOnlyAir() && section.maybeHas(PortalAperture::isPortal)) {
                    int bottom = level.getMinY() + 16 * index;
                    details = new SectionScan(chunk.getPos(), bottom, section);
                }
            }
            return false;
        }

        private ChunkPos nextChunk() {
            if (ring == 0) {
                ring = 1;
                return center;
            }
            int edgeLength = ring * 2;
            int edge = perimeterIndex / edgeLength;
            int offset = perimeterIndex % edgeLength - ring;
            ChunkPos next = switch (edge) {
                case 0 -> new ChunkPos(center.x() + offset, center.z() - ring);
                case 1 -> new ChunkPos(center.x() + ring, center.z() + offset);
                case 2 -> new ChunkPos(center.x() - offset, center.z() + ring);
                default -> new ChunkPos(center.x() - ring, center.z() - offset);
            };
            if (++perimeterIndex == 4 * edgeLength) {
                perimeterIndex = 0;
                ring++;
            }
            return next;
        }
    }

    private static final class SectionScan {
        private final int x, y, z;
        private final LevelChunkSection section;
        private final Set<BlockPos> seen = new HashSet<>();
        private int cursor;

        SectionScan(ChunkPos column, int bottom, LevelChunkSection section) {
            x = column.x() * 16;
            y = bottom;
            z = column.z() * 16;
            this.section = section;
        }

        boolean scan(ClientLevel level, PortalTracker tracker, long deadline) {
            while (cursor < 4096) {
                if ((cursor & 63) == 0 && System.nanoTime() >= deadline) return false;
                int index = cursor++;
                int localX = index & 15, localY = index >> 8, localZ = (index >> 4) & 15;
                var kind = Cell.of(section.getBlockState(localX, localY, localZ)).kind();
                if (kind == null) continue;
                var position = new BlockPos(x + localX, y + localY, z + localZ);
                if (seen.contains(position)) continue;
                var result = PortalAperture.scan(position, kind, at -> cell(level, at));
                seen.addAll(result.cells());
                if (result.portal() != null) tracker.discover(result.portal(), result.cells());
                if (System.nanoTime() >= deadline) return false;
            }
            return true;
        }
    }
}
