package dev.soprano.game;

import dev.soprano.path.RouteExclusions;
import dev.soprano.path.RouteExclusions.Bounds;
import dev.soprano.path.RouteExclusions.Exclusion;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Live, dimension-scoped route exclusions. All access is on the client thread. */
final class NavigationSafety {
    private static final RouteExclusions EMPTY = new RouteExclusions(List.of());
    private ClientLevel level;
    private Scope scope;
    private RouteZones store;
    private List<Exclusion> zones = List.of();
    private RouteExclusions staticExclusions = EMPTY;
    private RouteExclusions mobExclusions = EMPTY;
    private long mobsAt = Long.MIN_VALUE;
    private IllegalStateException zoneError;

    Scope scope() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            scope = identify(mc);
            zones = List.of();
            staticExclusions = EMPTY;
            mobsAt = Long.MIN_VALUE;
            zoneError = null;
            if (scope != null) {
                store = new RouteZones(mc.gameDirectory.toPath().resolve("soprano").resolve("no-go-zones"));
                try {
                    replace(store.read(scope));
                } catch (IOException failure) {
                    zoneError = new IllegalStateException("Could not read this dimension's no-go zones.", failure);
                }
            }
        }
        return scope;
    }

    private static Scope identify(Minecraft mc) {
        if (mc.level == null) return null;
        String dimension = mc.level.dimension().identifier().toString();
        var integrated = mc.getSingleplayerServer();
        if (integrated != null) {
            String world = integrated.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
            return new Scope("singleplayer", world, dimension);
        }
        var server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return new Scope(server.ip.strip().toLowerCase(Locale.ROOT), "server-world", dimension);
        }
        return null;
    }

    List<Exclusion> zones() {
        scope();
        if (zoneError != null) throw zoneError;
        return zones;
    }

    Exclusion put(String name, BlockPos first, BlockPos second) {
        Objects.requireNonNull(first, "first corner");
        Objects.requireNonNull(second, "second corner");
        String label = RouteZones.name(name);
        Bounds bounds = Bounds.blocksInclusive(first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ());
        var updated = new ArrayList<>(zones());
        updated.removeIf(zone -> zone.name().equalsIgnoreCase(label));
        Exclusion zone = new Exclusion(RouteExclusions.Kind.NO_GO_ZONE, label, bounds);
        updated.add(zone);
        save(updated);
        return zone;
    }

    boolean remove(String name) {
        String label = RouteZones.name(name);
        var updated = new ArrayList<>(zones());
        if (!updated.removeIf(zone -> zone.name().equalsIgnoreCase(label))) return false;
        save(updated);
        return true;
    }

    private void save(List<Exclusion> updated) {
        if (scope == null) throw new IllegalStateException("Join a world with a stable address or save name to edit no-go zones.");
        try {
            store.write(scope, updated);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save this dimension's no-go zones.", failure);
        }
        replace(updated);
    }

    private void replace(List<Exclusion> updated) {
        zones = List.copyOf(updated);
        staticExclusions = new RouteExclusions(zones);
        mobsAt = Long.MIN_VALUE;
    }

    RouteExclusions active(boolean avoidMobs) {
        scope();
        if (zoneError != null) throw zoneError;
        Minecraft mc = Minecraft.getInstance();
        if (!avoidMobs || level == null || mc.player == null) return staticExclusions;
        long now = mc.player.tickCount;
        if (now != mobsAt) {
            var entries = new ArrayList<>(zones);
            var near = mc.player.getBoundingBox().inflate(48, 24, 48);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, near, candidate -> candidate.isAlive() && !candidate.isRemoved()
                && candidate != mc.player.getVehicle() && !candidate.isPassengerOfSameVehicle(mc.player))) {
                var box = mob.getBoundingBox().inflate(.75, .2, .75);
                String name = mob.getName().getString().strip();
                if (name.isEmpty()) name = mob.getType().toShortString();
                entries.add(new Exclusion(RouteExclusions.Kind.MOB, name,
                    new Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)));
            }
            mobExclusions = new RouteExclusions(entries, true);
            mobsAt = now;
        }
        return mobExclusions;
    }
}
