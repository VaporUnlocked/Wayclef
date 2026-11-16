package dev.soprano.path;

import dev.soprano.game.transport.TransportMode;
import dev.soprano.task.TaskHistory.Scope;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Persists editable trips and a separate snapshot of the last unfinished trip in each world. */
public final class ItineraryStore {
    private static final int MAGIC = 0x53495431;
    private static final int VERSION = 2;
    private static final int MAX_TRIPS = 128;
    private static final long MAX_FILE_BYTES = 512 * 1024L;

    public enum Phase { TRAVEL, WAIT, PORTAL }

    public record Progress(RouteItinerary trip, RouteItinerary.Cursor cursor, Phase phase, int waitTicks) {
        public Progress {
            Objects.requireNonNull(trip, "trip").validateTravel();
            Objects.requireNonNull(cursor, "trip cursor");
            Objects.requireNonNull(phase, "trip phase");
            if (cursor.index() >= trip.stops().size()
                || (cursor.direction() == -1 && trip.repeat() != RouteItinerary.Repeat.BACK_AND_FORTH)) {
                throw new IllegalArgumentException("Invalid trip progress.");
            }
            RouteItinerary.Stop stop = trip.stops().get(cursor.index());
            if (phase == Phase.WAIT ? waitTicks <= 0 || waitTicks > stop.waitSeconds() * 20 : waitTicks != 0) {
                throw new IllegalArgumentException("Invalid trip wait.");
            }
            if (phase == Phase.PORTAL && stop.portal() == null) throw new IllegalArgumentException("Invalid trip portal.");
        }

        public RouteItinerary.Stop stop() { return trip.stops().get(cursor.index()); }
    }

    private final Path directory;

    public ItineraryStore(Path root, Scope scope) {
        Objects.requireNonNull(scope, "itinerary scope");
        directory = root.resolve(hash(scope.serverKey())).resolve(hash(scope.worldKey()));
    }

    public List<String> list() throws IOException {
        if (Files.notExists(directory)) return List.of();
        List<String> names = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.trip")) {
            for (Path file : files) {
                if (names.size() >= MAX_TRIPS) throw new IOException("Too many stored trips.");
                var trip = read(file, ItineraryStore::readTrip);
                if (trip.isPresent()) names.add(trip.get().name());
            }
        }
        names.sort(String::compareToIgnoreCase);
        return List.copyOf(names);
    }

    public Optional<RouteItinerary> load(String name) throws IOException {
        String key = RouteItinerary.checkedName(name);
        var result = read(tripFile(key), ItineraryStore::readTrip);
        if (result.isPresent() && !key.equals(result.get().name())) throw new IOException("Stored trip has the wrong name.");
        return result;
    }

    public void save(RouteItinerary trip) throws IOException {
        Path file = tripFile(trip.name());
        if (Files.notExists(file) && list().size() >= MAX_TRIPS) throw new IOException("This world already has 128 trips.");
        write(file, output -> writeTrip(output, trip));
    }

    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(tripFile(RouteItinerary.checkedName(name)));
    }

    public Optional<Progress> progress() throws IOException {
        return read(progressFile(), (input, version) -> {
            RouteItinerary trip = readTrip(input, version);
            var cursor = new RouteItinerary.Cursor(input.readUnsignedByte(), input.readByte());
            return new Progress(trip, cursor, Phase.valueOf(input.readUTF()), input.readInt());
        });
    }

    public void saveProgress(Progress progress) throws IOException {
        write(progressFile(), output -> {
            writeTrip(output, progress.trip());
            output.writeByte(progress.cursor().index());
            output.writeByte(progress.cursor().direction());
            output.writeUTF(progress.phase().name());
            output.writeInt(progress.waitTicks());
        });
    }

    public void clearProgress() throws IOException { Files.deleteIfExists(progressFile()); }

    private Path tripFile(String name) { return directory.resolve(hash(name) + ".trip"); }
    private Path progressFile() { return directory.resolve("unfinished.progress"); }

    private static RouteItinerary readTrip(DataInputStream input, int version) throws IOException {
        String name = input.readUTF();
        var repeat = RouteItinerary.Repeat.valueOf(input.readUTF());
        int count = input.readUnsignedByte();
        if (count > RouteItinerary.MAX_STOPS) throw new IOException("Invalid stored trip length.");
        List<RouteItinerary.Stop> stops = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String dimension = input.readUTF();
            Goal goal = readGoal(input);
            int wait = input.readUnsignedShort();
            var portal = input.readBoolean() ? new RouteItinerary.PortalExit(input.readUTF(), readGoal(input)) : null;
            String mode = version >= 2 ? input.readUTF() : "";
            stops.add(new RouteItinerary.Stop(dimension, goal, wait, portal, mode.isEmpty() ? null : TransportMode.valueOf(mode)));
        }
        return new RouteItinerary(name, stops, repeat);
    }

    private static void writeTrip(DataOutputStream output, RouteItinerary trip) throws IOException {
        output.writeUTF(trip.name());
        output.writeUTF(trip.repeat().name());
        output.writeByte(trip.stops().size());
        for (RouteItinerary.Stop stop : trip.stops()) {
            output.writeUTF(stop.dimension());
            writeGoal(output, stop.goal());
            output.writeShort(stop.waitSeconds());
            output.writeBoolean(stop.portal() != null);
            if (stop.portal() != null) {
                output.writeUTF(stop.portal().dimension());
                writeGoal(output, stop.portal().area());
            }
            output.writeUTF(stop.transport() == null ? "" : stop.transport().name());
        }
    }

    private static Goal readGoal(DataInputStream input) throws IOException {
        int x = input.readInt();
        Integer y = input.readBoolean() ? input.readInt() : null;
        return new Goal(x, y, input.readInt(), input.readUnsignedShort());
    }

    private static void writeGoal(DataOutputStream output, Goal goal) throws IOException {
        output.writeInt(goal.x());
        output.writeBoolean(goal.y16() != null);
        if (goal.y16() != null) output.writeInt(goal.y16());
        output.writeInt(goal.z());
        output.writeShort(goal.radius());
    }

    private static <T> Optional<T> read(Path file, ReadValue<T> decoder) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var input = new DataInputStream(new BufferedInputStream(Channels.newInputStream(channel)))) {
            if (channel.size() > MAX_FILE_BYTES || input.readInt() != MAGIC) throw new IOException("Unsupported trip file.");
            int version = input.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported trip file version.");
            T result = decoder.read(input, version);
            if (input.read() != -1) throw new IOException("Unexpected data in trip file.");
            return Optional.of(result);
        } catch (NoSuchFileException ignored) {
            return Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new IOException("Invalid trip file.", invalid);
        }
    }

    private void write(Path file, WriteValue encoder) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "trip-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var output = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                encoder.write(output);
                output.flush();
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String hash(String value) {
        try {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    @FunctionalInterface private interface ReadValue<T> { T read(DataInputStream input, int version) throws IOException; }
    @FunctionalInterface private interface WriteValue { void write(DataOutputStream output) throws IOException; }
}
