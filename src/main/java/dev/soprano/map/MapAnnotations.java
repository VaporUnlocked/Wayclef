package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.game.Navigation;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Client-thread map notes and vanilla-confirmed respawn landmarks. */
public final class MapAnnotations {
    public static final int MAX_NOTE_LENGTH = 160;
    private static final long RESPAWN_ACK_NANOS = 3_000_000_000L;

    public enum Kind { DEATH, RESPAWN, NOTE, PORTAL }

    public record Marker(Kind kind, BlockPos position, String text) {
        public Marker {
            Objects.requireNonNull(kind, "marker type");
            position = Objects.requireNonNull(position, "marker position").immutable();
            text = Objects.requireNonNull(text, "marker text").strip();
            if (Math.abs((long) position.getX()) > 30_002_000 || Math.abs((long) position.getZ()) > 30_002_000
                || Math.abs((long) position.getY()) > 32_768) throw new IllegalArgumentException("Map marker is outside the supported world.");
            if (text.isEmpty() || text.length() > MAX_NOTE_LENGTH || text.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Use one line of 1–" + MAX_NOTE_LENGTH + " characters for a map note.");
        }
    }

    private record RespawnCandidate(LocalPlayer player, Scope scope, BlockPos position, String label, long timestamp) { }
    private record RecordedDeath(String server, String world, GlobalPos position) { }

    private static Scope scope;
    private static Path directory;
    private static List<Marker> markers = List.of();
    private static IOException readError;
    private static RespawnCandidate pendingRespawn;
    private static RecordedDeath lastObservedDeath;
    private static BlockPos lastSleep;
    private static LocalPlayer observedPlayer;

    private MapAnnotations() { }

    public static void tick(Minecraft client) {
        selectScope(client);
        if (scope == null || client.player == null || readError != null) return;
        LocalPlayer player = client.player;
        if (player != observedPlayer) {
            observedPlayer = player;
            lastSleep = null;
        }
        player.getLastDeathLocation().ifPresent(position -> recordDeath(position));
        BlockPos sleepingAt = player.getSleepingPos().orElse(null);
        if (sleepingAt != null && !sleepingAt.equals(lastSleep)) {
            lastSleep = sleepingAt.immutable();
            recordRespawn(scope, sleepingAt, "Last bed");
        } else if (sleepingAt == null) {
            lastSleep = null;
        }
        if (pendingRespawn != null && System.nanoTime() - pendingRespawn.timestamp() > RESPAWN_ACK_NANOS) pendingRespawn = null;
    }

    public static void onUse(BlockHitResult hit) {
        Minecraft client = Minecraft.getInstance();
        selectScope(client);
        if (scope == null || client.level == null || client.player == null) return;
        BlockPos position = hit.getBlockPos();
        var state = client.level.getBlockState(position);
        String label;
        if (state.getBlock() instanceof BedBlock) {
            label = "Last bed";
            if (state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT)
                position = position.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        } else if (state.is(Blocks.RESPAWN_ANCHOR)) {
            label = "Last respawn anchor";
        } else return;
        pendingRespawn = new RespawnCandidate(client.player, scope, position.immutable(), label, System.nanoTime());
    }

    public static void onSystemMessage(Component message) {
        if (!(message.getContents() instanceof TranslatableContents translation)
            || !translation.getKey().equals("block.minecraft.set_spawn")) return;
        Minecraft client = Minecraft.getInstance();
        selectScope(client);
        RespawnCandidate candidate = pendingRespawn;
        pendingRespawn = null;
        if (candidate != null && candidate.player() == client.player && candidate.scope().equals(scope)
            && System.nanoTime() - candidate.timestamp() <= RESPAWN_ACK_NANOS)
            recordRespawn(scope, candidate.position(), candidate.label());
    }

    public static List<Marker> markers(Minecraft client) {
        selectScope(client);
        List<Marker> portals = PortalTracker.markers(client);
        if (portals.isEmpty()) return markers;
        if (markers.isEmpty()) return portals;
        var combined = new ArrayList<Marker>(markers.size() + portals.size());
        combined.addAll(markers);
        combined.addAll(portals);
        return List.copyOf(combined);
    }

    public static void saveNote(Minecraft client, BlockPos position, String text) {
        requireScope(client);
        Marker note = new Marker(Kind.NOTE, position, text);
        var updated = new ArrayList<>(markers);
        updated.removeIf(existing -> existing.kind() == Kind.NOTE && existing.position().equals(position));
        if (updated.stream().filter(marker -> marker.kind() == Kind.NOTE).count() >= MapAnnotationFiles.MAX_NOTES)
            throw new IllegalStateException("This dimension already has " + MapAnnotationFiles.MAX_NOTES + " map notes.");
        updated.add(note);
        saveNotes(updated);
    }

    public static boolean removeNote(Minecraft client, BlockPos position) {
        requireScope(client);
        var updated = new ArrayList<>(markers);
        if (!updated.removeIf(existing -> existing.kind() == Kind.NOTE && existing.position().equals(position))) return false;
        saveNotes(updated);
        return true;
    }

    private static void requireScope(Minecraft client) {
        selectScope(client);
        if (scope == null) throw new IllegalStateException("Join a world with a stable save or server address to edit map notes.");
        if (readError != null) throw new IllegalStateException("Could not read this dimension's map notes.", readError);
    }

    private static void saveNotes(List<Marker> updated) {
        try {
            MapAnnotationFiles.write(directory, scope, updated);
            markers = List.copyOf(updated);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save this dimension's map notes.", failure);
        }
    }

    private static void selectScope(Minecraft client) {
        var module = SopranoClient.module();
        Scope active = module == null ? Navigation.currentScope() : module.storageScope();
        if (Objects.equals(scope, active)) return;
        scope = active;
        pendingRespawn = null;
        lastObservedDeath = null;
        lastSleep = null;
        observedPlayer = null;
        markers = List.of();
        readError = null;
        if (active == null) return;
        directory = client.gameDirectory.toPath().resolve("soprano").resolve("map-markers");
        try {
            markers = MapAnnotationFiles.read(directory, active);
        } catch (IOException failure) {
            readError = failure;
            SopranoClient.LOG.warn("Soprano could not read map markers", failure);
        }
    }

    private static void recordDeath(GlobalPos position) {
        var death = new RecordedDeath(scope.server(), scope.world(), position);
        if (death.equals(lastObservedDeath)) return;
        lastObservedDeath = death;
        var deathScope = new Scope(death.server(), death.world(), position.dimension().identifier().toString());
        updateTracked(deathScope, Kind.DEATH, position.pos(), "Last death");
    }

    private static void recordRespawn(Scope active, BlockPos position, String label) {
        updateTracked(active, Kind.RESPAWN, position, label);
    }

    private static void updateTracked(Scope markerScope, Kind kind, BlockPos position, String text) {
        if (readError != null && scope.equals(markerScope)) return;
        try {
            Marker marker = new Marker(kind, position, text);
            List<Marker> existing = markerScope.equals(scope) ? markers : MapAnnotationFiles.read(directory, markerScope);
            if (existing.contains(marker)) return;
            var updated = new ArrayList<>(existing);
            updated.removeIf(item -> item.kind() == marker.kind());
            updated.addFirst(marker);
            MapAnnotationFiles.write(directory, markerScope, updated);
            if (markerScope.equals(scope)) markers = List.copyOf(updated);
        } catch (IOException failure) {
            SopranoClient.LOG.warn("Soprano could not save a tracked map marker", failure);
        } catch (IllegalArgumentException outsideMap) {
            SopranoClient.LOG.debug("Tracked map marker is outside the supported map bounds", outsideMap);
        }
    }
}
