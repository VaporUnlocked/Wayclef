package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.path.Goal;
import dev.soprano.path.RouteItinerary;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Remembers directed Overworld and Nether crossings for each server and hosted world. */
public final class PortalLinks {
    private static final PortalLinks INSTANCE = new PortalLinks();
    private static final int FILE_MAGIC = 0x534f4c01;
    private static final int LIMIT = 4096;
    private static final int TRANSITION_TICKS = 160;
    private static final int EXIT_TICKS = 40;

    record Endpoint(String dimension, BlockPos position) {
        Endpoint {
            if (!dimension.equals(PortalCoordinates.OVERWORLD) && !dimension.equals(PortalCoordinates.NETHER)) {
                throw new IllegalArgumentException("A link needs an Overworld or Nether portal.");
            }
            position = position.immutable();
            if (Math.abs((long) position.getX()) >= 30_000_000 || Math.abs((long) position.getZ()) >= 30_000_000
                || Math.abs((long) position.getY()) > 32_768) throw new IllegalArgumentException("Invalid portal position.");
        }
    }
    public record Destination(String dimension, BlockPos position, int radius, boolean projected, boolean observed) {
        public String label() {
            String world = dimension.equals(PortalCoordinates.NETHER) ? "Nether" : "Overworld";
            return (projected ? "Expected " : observed ? "Observed " : "Return candidate ")
                + world + "  ·  " + position.toShortString();
        }
    }

    private record Contact(Endpoint endpoint, BlockPos block, ClientPacketListener connection, int tick) { }
    private record Arrival(Contact source, String dimension, BlockPos position, int deadline) { }

    private final ExecutorService files = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Soprano portal links");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Endpoint, Endpoint> links = new HashMap<>();
    private final Set<Endpoint> missing = new HashSet<>();
    private Path file;
    private Scope selectedWorld;
    private Future<Map<Endpoint, Endpoint>> loading;
    private boolean writable;
    private int tick;
    private Contact contact;
    private Arrival arrival;

    private PortalLinks() { }

    public static Destination destination(Minecraft client, BlockPos anchor) {
        Scope scope = scope(client);
        if (scope == null) return null;
        if (!scope.dimension().equals(PortalCoordinates.OVERWORLD) && !scope.dimension().equals(PortalCoordinates.NETHER)) return null;
        INSTANCE.select(client, scope);
        INSTANCE.load();
        return destination(new Endpoint(scope.dimension(), anchor), INSTANCE.links);
    }

    static Destination destination(Endpoint source, Map<Endpoint, Endpoint> known) {
        Endpoint exit = known.get(source);
        if (exit != null) return new Destination(exit.dimension(), exit.position(), 16, false, true);
        Endpoint reverse = null;
        for (var crossing : known.entrySet()) {
            if (!crossing.getValue().equals(source)) continue;
            if (reverse != null) { reverse = null; break; }
            reverse = crossing.getKey();
        }
        if (reverse != null) return new Destination(reverse.dimension(), reverse.position(), 16, false, false);
        var guess = PortalCoordinates.project(source.dimension(), source.position());
        return guess == null ? null : new Destination(guess.dimension(), guess.position(), guess.searchRadius(), true, false);
    }

    public static void tick(Minecraft client) { INSTANCE.update(client); }

    /** Inserts observed crossings where a trip changes dimensions without an explicit portal stop. */
    public static RouteItinerary connectTrip(Minecraft client, RouteItinerary trip) {
        Scope scope = scope(client);
        if (scope == null || client.player == null || trip.stops().isEmpty()) return trip;
        INSTANCE.select(client, scope);
        if (!INSTANCE.load()) return trip;
        return connectTrip(trip, scope.dimension(), client.player.blockPosition(), INSTANCE.links);
    }

    static RouteItinerary connectTrip(RouteItinerary trip, String dimension, BlockPos from, Map<Endpoint, Endpoint> links) {
        if (links.isEmpty() || trip.repeat() == RouteItinerary.Repeat.BACK_AND_FORTH) return trip;
        var withCrossings = new ArrayList<RouteItinerary.Stop>();
        for (var stop : trip.stops()) {
            if (!dimension.equals(stop.dimension())) {
                var crossing = nearestCrossing(dimension, from, stop, links);
                if (crossing == null) return trip;
                withCrossings.add(crossing);
            }
            withCrossings.add(stop);
            dimension = stop.arrivalDimension();
            var position = stop.portal() == null ? stop.goal() : stop.portal().area();
            from = new BlockPos(position.x(), position.y16() == null ? from.getY() : Math.floorDiv(position.y16(), 16), position.z());
        }
        var first = withCrossings.getFirst();
        if (trip.repeat() == RouteItinerary.Repeat.CIRCUIT && !dimension.equals(first.dimension())) {
            var crossing = nearestCrossing(dimension, from, first, links);
            if (crossing == null) return trip;
            withCrossings.add(crossing);
        }
        if (withCrossings.size() == trip.stops().size()) return trip;
        return new RouteItinerary(trip.name(), withCrossings, trip.repeat());
    }

    private static RouteItinerary.Stop nearestCrossing(String dimension, BlockPos from, RouteItinerary.Stop next,
                                                        Map<Endpoint, Endpoint> known) {
        Map.Entry<Endpoint, Endpoint> closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var crossing : known.entrySet()) {
            Endpoint entry = crossing.getKey(), exit = crossing.getValue();
            if (!entry.dimension().equals(dimension) || !exit.dimension().equals(next.dimension())) continue;
            var a = entry.position();
            var b = exit.position();
            double cost = Math.hypot(a.getX() - (double) from.getX(), a.getZ() - (double) from.getZ())
                + Math.hypot(b.getX() - (double) next.goal().x(), b.getZ() - (double) next.goal().z());
            if (cost < distance || cost == distance && a.compareTo(closest.getKey().position()) < 0) {
                distance = cost;
                closest = crossing;
            }
        }
        if (closest == null) return null;
        var a = closest.getKey().position();
        var b = closest.getValue().position();
        var expected = new RouteItinerary.PortalExit(next.dimension(), new Goal(b.getX(), b.getY() * 16, b.getZ(), 16));
        return new RouteItinerary.Stop(dimension, new Goal(a.getX(), a.getY() * 16, a.getZ(), 0), 0, expected);
    }

    static void invalidated(Minecraft client, BlockPos anchor) {
        Scope scope = scope(client);
        if (scope == null || !scope.dimension().equals(PortalCoordinates.OVERWORLD)
            && !scope.dimension().equals(PortalCoordinates.NETHER)) return;
        INSTANCE.select(client, scope);
        INSTANCE.missing.add(new Endpoint(scope.dimension(), anchor));
        INSTANCE.load();
    }

    public static void disconnected() {
        INSTANCE.contact = null;
        INSTANCE.arrival = null;
    }

    public static void finishWrites() {
        try {
            INSTANCE.files.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException problem) {
            SopranoClient.LOG.warn("Could not finish saving portal links", problem);
        }
    }

    private void update(Minecraft client) {
        tick++;
        Scope scope = scope(client);
        if (scope == null || client.player == null || client.getConnection() == null) return;
        select(client, scope);
        if (!load()) return;
        if (!scope.dimension().equals(PortalCoordinates.OVERWORLD) && !scope.dimension().equals(PortalCoordinates.NETHER)) {
            contact = null;
            arrival = null;
            return;
        }
        if (contact != null && (tick - contact.tick() > TRANSITION_TICKS || contact.connection() != client.getConnection())) {
            contact = null;
        }
        if (contact != null && !contact.endpoint().dimension().equals(scope.dimension())) {
            arrival = new Arrival(contact, scope.dimension(), client.player.blockPosition(), tick + EXIT_TICKS);
            contact = null;
        }
        if (arrival != null) {
            if (tick > arrival.deadline() || arrival.source().connection() != client.getConnection()
                || !arrival.dimension().equals(scope.dimension())) {
                arrival = null;
            } else {
                var nearby = findNearby(client.level, arrival.position());
                if (nearby != null) {
                    remember(arrival.source().endpoint(), new Endpoint(scope.dimension(), nearby));
                    arrival = null;
                }
            }
        }
        BlockPos block = touching(client.level, client.player.getBoundingBox());
        if (block == null) return;
        BlockPos anchor = contact != null && block.equals(contact.block())
            ? contact.endpoint().position() : PortalTracker.anchor(client.level, block);
        if (anchor != null) contact = new Contact(new Endpoint(scope.dimension(), anchor), block, client.getConnection(), tick);
    }

    private static Scope scope(Minecraft client) {
        if (client.level == null || SopranoClient.module() == null) return null;
        return SopranoClient.module().storageScope();
    }

    private void select(Minecraft client, Scope scope) {
        if (selectedWorld != null && selectedWorld.server().equals(scope.server()) && selectedWorld.world().equals(scope.world())) return;
        selectedWorld = scope;
        Path folder = client.gameDirectory.toPath().resolve("soprano").resolve("portal-links");
        Path selected = path(folder, scope);
        file = selected;
        contact = null;
        arrival = null;
        links.clear();
        missing.clear();
        writable = false;
        loading = files.submit(() -> read(selected));
    }

    private boolean load() {
        if (loading != null) {
            if (!loading.isDone()) return false;
            try {
                links.putAll(loading.get());
                writable = true;
            } catch (ExecutionException problem) {
                SopranoClient.LOG.warn("Could not read portal links", problem.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            loading = null;
        }
        if (!missing.isEmpty()) {
            boolean changed = links.entrySet().removeIf(link -> missing.contains(link.getKey()) || missing.contains(link.getValue()));
            missing.clear();
            if (changed) save();
        }
        return true;
    }

    private void remember(Endpoint source, Endpoint destination) {
        if ((!links.containsKey(source) && links.size() >= LIMIT) || destination.equals(links.put(source, destination))) return;
        save();
    }

    private void save() {
        if (!writable) return;
        var snapshot = Map.copyOf(links);
        Path output = file;
        files.submit(() -> {
            try {
                write(output, snapshot);
            } catch (IOException problem) {
                SopranoClient.LOG.warn("Could not save portal links", problem);
            }
        });
    }

    private static BlockPos touching(ClientLevel level, AABB body) {
        var start = BlockPos.containing(body.minX, body.minY, body.minZ);
        var end = BlockPos.containing(body.maxX - 1e-7, body.maxY - 1e-7, body.maxZ - 1e-7);
        for (BlockPos at : BlockPos.betweenClosed(start, end)) {
            if (netherPortal(level, at)) return at.immutable();
        }
        return null;
    }

    private static BlockPos findNearby(ClientLevel level, BlockPos arrival) {
        BlockPos closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (BlockPos at : BlockPos.betweenClosed(arrival.offset(-2, -2, -2), arrival.offset(2, 2, 2))) {
            double candidate = at.distSqr(arrival);
            if (candidate < distance && netherPortal(level, at)) {
                closest = at.immutable();
                distance = candidate;
            }
        }
        return closest == null ? null : PortalTracker.anchor(level, closest);
    }

    private static boolean netherPortal(ClientLevel level, BlockPos at) {
        return !level.isOutsideBuildHeight(at) && level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4)
            && level.getBlockState(at).is(Blocks.NETHER_PORTAL);
    }

    static Path path(Path root, Scope scope) {
        return MapSnapshotFiles.scope(root, scope.server() + "\n" + scope.world()).resolve("links.dat");
    }

    static Map<Endpoint, Endpoint> read(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 + LIMIT * 25L) {
            throw new IOException("Invalid portal links file.");
        }
        try (var input = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != FILE_MAGIC) throw new IOException("Unknown portal link format.");
            int size = input.readInt();
            if (size < 0 || size > LIMIT) throw new IOException("Invalid portal link count.");
            var result = new HashMap<Endpoint, Endpoint>();
            for (int i = 0; i < size; i++) {
                boolean overworld = input.readBoolean();
                Endpoint from = endpoint(input, overworld);
                Endpoint to = endpoint(input, !overworld);
                if (result.put(from, to) != null) throw new IOException("Duplicate portal link.");
            }
            if (input.read() != -1) throw new IOException("Unexpected data in portal links file.");
            return Map.copyOf(result);
        } catch (IllegalArgumentException problem) {
            throw new IOException("Invalid portal link position.", problem);
        }
    }

    static void write(Path path, Map<Endpoint, Endpoint> links) throws IOException {
        if (links.size() > LIMIT) throw new IOException("Too many portal links.");
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".links", ".tmp");
        try {
            try (var output = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(links.size());
                for (var crossing : links.entrySet()) {
                    Endpoint from = crossing.getKey(), to = crossing.getValue();
                    if (from.dimension().equals(to.dimension())) throw new IOException("A portal link must cross dimensions.");
                    output.writeBoolean(from.dimension().equals(PortalCoordinates.OVERWORLD));
                    position(output, from.position());
                    position(output, to.position());
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Endpoint endpoint(DataInputStream input, boolean overworld) throws IOException {
        return new Endpoint(overworld ? PortalCoordinates.OVERWORLD : PortalCoordinates.NETHER,
            new BlockPos(input.readInt(), input.readInt(), input.readInt()));
    }

    private static void position(DataOutputStream output, BlockPos at) throws IOException {
        output.writeInt(at.getX());
        output.writeInt(at.getY());
        output.writeInt(at.getZ());
    }
}
