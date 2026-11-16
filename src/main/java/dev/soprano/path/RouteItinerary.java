package dev.soprano.path;

import dev.soprano.game.transport.TransportMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** An immutable trip definition. Runtime progress is stored separately. */
public record RouteItinerary(String name, List<Stop> stops, Repeat repeat) {
    public static final int MAX_STOPS = 128;
    public static final int MAX_WAIT_SECONDS = 3_600;

    public enum Repeat { ONCE, CIRCUIT, BACK_AND_FORTH }

    public record Stop(String dimension, Goal goal, int waitSeconds, PortalExit portal, TransportMode transport) {
        public Stop(String dimension, Goal goal, int waitSeconds) {
            this(dimension, goal, waitSeconds, null, null);
        }

        public Stop(String dimension, Goal goal, int waitSeconds, PortalExit portal) {
            this(dimension, goal, waitSeconds, portal, null);
        }

        public Stop {
            dimension = dimensionId(dimension);
            Objects.requireNonNull(goal, "stop goal");
            if (waitSeconds < 0 || waitSeconds > MAX_WAIT_SECONDS) {
                throw new IllegalArgumentException("Wait time must be between 0 and 3,600 seconds.");
            }
            if (portal != null && (goal.y16() == null || goal.radius() != 0 || dimension.equals(portal.dimension()))) {
                throw new IllegalArgumentException("A portal stop needs exact entry coordinates and a different exit dimension.");
            }
            if (portal != null && transport != null && transport != TransportMode.WALK && transport != TransportMode.AUTO) {
                throw new IllegalArgumentException("A portal stop uses walking.");
            }
        }

        public String arrivalDimension() { return portal == null ? dimension : portal.dimension(); }
    }

    /** The expected destination of a portal, checked after the actual dimension change. */
    public record PortalExit(String dimension, Goal area) {
        public PortalExit {
            dimension = dimensionId(dimension);
            Objects.requireNonNull(area, "portal exit area");
            if (area.y16() == null ? area.radius() < 1 || area.radius() > 128 : area.radius() > 16) {
                throw new IllegalArgumentException("A portal exit accepts up to 16 blocks with a height or 128 blocks without one.");
            }
        }

        public boolean contains(double x, double y, double z) {
            return Math.abs(Math.floor(x) - area.x()) <= area.radius()
                && (area.y16() == null || Math.abs(y - area.y16() / 16.0) <= 4)
                && Math.abs(Math.floor(z) - area.z()) <= area.radius();
        }
    }

    public record Cursor(int index, int direction) {
        public Cursor {
            if (index < 0 || Math.abs(direction) != 1) throw new IllegalArgumentException("Invalid trip progress.");
        }
    }

    public RouteItinerary {
        name = checkedName(name);
        stops = List.copyOf(stops);
        Objects.requireNonNull(repeat, "repeat mode");
        if (stops.size() > MAX_STOPS) throw new IllegalArgumentException("A trip can have up to 128 stops.");
    }

    public static String checkedName(String name) {
        String result = Objects.requireNonNull(name, "trip name").strip();
        if (result.isEmpty() || result.length() > 64 || result.codePoints().anyMatch(Character::isISOControl)
            || !StandardCharsets.UTF_8.newEncoder().canEncode(result)) {
            throw new IllegalArgumentException("Enter a trip name between 1 and 64 characters.");
        }
        return result;
    }

    /** Allow incomplete drafts to be saved and validate their whole sequence before movement. */
    public void validateTravel() {
        if (stops.isEmpty()) throw new IllegalStateException("Add at least one trip stop.");
        if (repeat != Repeat.ONCE && stops.size() < 2) {
            throw new IllegalStateException("A repeating trip needs at least two stops.");
        }
        if (repeat == Repeat.BACK_AND_FORTH && stops.stream().anyMatch(stop -> stop.portal() != null)) {
            throw new IllegalStateException("Use a circuit with an explicit return portal for a repeating multi-dimension trip.");
        }
        for (int index = 1; index < stops.size(); index++) validatePair(stops.get(index - 1), stops.get(index));
        if (repeat == Repeat.CIRCUIT) validatePair(stops.getLast(), stops.getFirst());
    }

    public Cursor after(Cursor previous) {
        if (previous.index() >= stops.size()) throw new IllegalArgumentException("Invalid trip stop index.");
        int next = previous.index() + previous.direction();
        if (next >= 0 && next < stops.size()) return new Cursor(next, previous.direction());
        return switch (repeat) {
            case ONCE -> null;
            case CIRCUIT -> new Cursor(0, 1);
            case BACK_AND_FORTH -> new Cursor(previous.index() - previous.direction(), -previous.direction());
        };
    }

    private static void validatePair(Stop first, Stop second) {
        if (!first.arrivalDimension().equals(second.dimension())) {
            throw new IllegalStateException("Add a known portal stop before switching to " + second.dimension() + ".");
        }
    }

    private static String dimensionId(String dimension) {
        if (dimension == null || dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Enter a complete dimension ID, such as minecraft:overworld.");
        }
        return dimension;
    }
}
