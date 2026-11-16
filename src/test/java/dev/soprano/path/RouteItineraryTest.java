package dev.soprano.path;

import dev.soprano.game.transport.TransportMode;
import dev.soprano.path.ItineraryStore.Phase;
import dev.soprano.path.ItineraryStore.Progress;
import dev.soprano.path.RouteItinerary.Cursor;
import dev.soprano.path.RouteItinerary.PortalExit;
import dev.soprano.path.RouteItinerary.Repeat;
import dev.soprano.path.RouteItinerary.Stop;
import dev.soprano.task.TaskHistory.Scope;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class RouteItineraryTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final Stop HOME = new Stop(OVERWORLD, new Goal(0, 1024, 0, 0), 0);
    private static final Stop OUTPOST = new Stop(OVERWORLD, new Goal(10, null, 4, 3), 10, null, TransportMode.WALK);

    public static void main(String[] args) throws Exception {
        repeatModes();
        portalTransitions();
        scopedStorage();
        System.out.println("Soprano itinerary checks passed.");
    }

    private static void repeatModes() {
        var once = new RouteItinerary("Once", List.of(HOME, OUTPOST), Repeat.ONCE);
        check(once.after(new Cursor(0, 1)).equals(new Cursor(1, 1)), "advance once");
        check(once.after(new Cursor(1, 1)) == null, "complete once");
        var circuit = new RouteItinerary("Circuit", List.of(HOME, OUTPOST), Repeat.CIRCUIT);
        var bounce = new RouteItinerary("Bounce", List.of(HOME, OUTPOST), Repeat.BACK_AND_FORTH);
        Cursor cycle = new Cursor(0, 1);
        Cursor reverse = new Cursor(0, 1);
        for (int count = 0; count < 12; count++) {
            cycle = circuit.after(cycle);
            reverse = bounce.after(reverse);
            check(cycle.index() == (count + 1) % 2 && cycle.direction() == 1, "circuit remains forward");
            check(reverse.index() == cycle.index(), "back-and-forth never duplicates an endpoint");
        }
        check(bounce.after(new Cursor(1, 1)).equals(new Cursor(0, -1)), "reverse at end");
        check(bounce.after(new Cursor(0, -1)).equals(new Cursor(1, 1)), "reverse at start");
    }

    private static void portalTransitions() {
        var exit = new PortalExit(NETHER, new Goal(10, 1024, 12, 3));
        check(exit.contains(12.9, 66, 12), "verified position within exit area");
        check(!exit.contains(14, 66, 12) && !exit.contains(12, 71, 12), "reject wrong arrival");
        var portal = new Stop(OVERWORLD, new Goal(80, 1024, 96, 0), 0, exit);
        var other = new Stop(NETHER, new Goal(12, 1024, 12, 1), 0);
        new RouteItinerary("Portal", List.of(portal, other), Repeat.ONCE).validateTravel();
        rejects(() -> new RouteItinerary("Missing portal", List.of(HOME, other), Repeat.ONCE).validateTravel());
        rejects(() -> new RouteItinerary("Missing return", List.of(portal, other), Repeat.CIRCUIT).validateTravel());
        rejects(() -> new RouteItinerary("Reverse portal", List.of(portal, other), Repeat.BACK_AND_FORTH).validateTravel());
    }

    private static void scopedStorage() throws Exception {
        Path root = Files.createTempDirectory("soprano-trip-test-");
        try {
            Scope first = new Scope("server-one", "save-one");
            Scope second = new Scope("server-one", "save-two");
            Scope third = new Scope("server-two", "save-one");
            var a = new ItineraryStore(root, first);
            var b = new ItineraryStore(root, second);
            var c = new ItineraryStore(root, third);
            var trip = new RouteItinerary("Forest circuit", List.of(HOME, OUTPOST), Repeat.BACK_AND_FORTH);
            var waiting = new Progress(trip, new Cursor(1, 1), Phase.WAIT, 143);
            a.save(trip);
            a.saveProgress(waiting);
            check(a.load(trip.name()).orElseThrow().equals(trip), "definition including mode survives");
            check(new ItineraryStore(root, first).progress().orElseThrow().equals(waiting), "wait and direction survive restart");
            check(b.load(trip.name()).isEmpty() && c.load(trip.name()).isEmpty(), "other server or world does not see the definition");
            check(b.progress().isEmpty() && c.progress().isEmpty(), "other server or world cannot resume");
            var draft = new RouteItinerary(trip.name(), List.of(HOME), Repeat.ONCE);
            a.save(draft);
            check(a.load(trip.name()).orElseThrow().equals(draft) && a.progress().orElseThrow().trip().equals(trip),
                "editable definition and active snapshot are independent");
            check(a.delete(trip.name()) && a.progress().isPresent(), "deleting definition preserves unfinished snapshot");
            a.clearProgress();
            check(a.progress().isEmpty(), "completed or cancelled trip cannot resume");
            writeV1(root, first);
            var old = a.load("Old draft").orElseThrow();
            check(old.stops().size() == 1 && old.stops().getFirst().goal().x() == 7
                && old.stops().getFirst().transport() == null, "v1 definitions inherit mode");
            check(a.list().equals(List.of("Old draft")), "list only stored trip definitions");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void writeV1(Path root, Scope scope) throws Exception {
        Path directory = root.resolve(hash(scope.serverKey())).resolve(hash(scope.worldKey()));
        try (var output = new DataOutputStream(Files.newOutputStream(directory.resolve(hash("Old draft") + ".trip")))) {
            output.writeInt(0x53495431);
            output.writeInt(1);
            output.writeUTF("Old draft");
            output.writeUTF("ONCE");
            output.writeByte(1);
            output.writeUTF(OVERWORLD);
            output.writeInt(7);
            output.writeBoolean(false);
            output.writeInt(9);
            output.writeShort(0);
            output.writeShort(5);
            output.writeBoolean(false);
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Unexpectedly accepted invalid trip sequence.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
