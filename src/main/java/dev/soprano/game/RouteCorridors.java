package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.RouteExclusions.Bounds;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps preferred corridors separate from mandatory no-go geometry. */
final class RouteCorridors {
    private Scope scope;
    private RouteZones store;
    private List<RouteCorridor> corridors = List.of();
    private IllegalStateException readFailure;

    List<RouteCorridor> list() {
        Scope next = Navigation.currentScope();
        if (!Objects.equals(scope, next)) {
            scope = next;
            corridors = List.of();
            readFailure = null;
            if (scope != null) {
                // The existing atomic name-and-bounds file format is stored in a separate directory.
                store = new RouteZones(Minecraft.getInstance().gameDirectory.toPath().resolve("soprano/preferred-corridors"));
                try {
                    corridors = store.read(scope).stream().map(area -> new RouteCorridor(area.name(), area.bounds())).toList();
                } catch (IOException failure) {
                    readFailure = new IllegalStateException("Could not read the preferred corridors in this dimension.", failure);
                    SopranoClient.LOG.warn(readFailure.getMessage(), failure);
                }
            }
        }
        return corridors;
    }

    RouteCorridor put(String name, BlockPos first, BlockPos second) {
        var next = new RouteCorridor(name, Bounds.blocksInclusive(first.getX(), first.getY(), first.getZ(),
            second.getX(), second.getY(), second.getZ()));
        var updated = new ArrayList<>(editable());
        updated.removeIf(corridor -> corridor.name().equalsIgnoreCase(next.name()));
        updated.add(next);
        save(updated);
        return next;
    }

    boolean remove(String name) {
        String target = name.strip();
        var updated = new ArrayList<>(editable());
        if (!updated.removeIf(corridor -> corridor.name().equalsIgnoreCase(target))) return false;
        save(updated);
        return true;
    }

    private List<RouteCorridor> editable() {
        var current = list();
        if (readFailure != null) throw readFailure;
        return current;
    }

    private void save(List<RouteCorridor> updated) {
        if (scope == null) throw new IllegalStateException("Join a world with a stable save or server address to mark a corridor.");
        if (updated.size() > RouteZones.MAX_ZONES) throw new IllegalArgumentException("A dimension can hold at most 128 preferred corridors.");
        try {
            store.write(scope, updated.stream().map(corridor -> new RouteExclusions.Exclusion(
                RouteExclusions.Kind.NO_GO_ZONE, corridor.name(), corridor.bounds())).toList());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the preferred corridors in this dimension.", failure);
        }
        corridors = List.copyOf(updated);
    }
}
