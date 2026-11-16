package dev.soprano.inventory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves nearby inventory labels and plans transfers against detached inventory snapshots. */
public final class SignStorage {
    public static final int UNREACHABLE = -1;
    private static final int SIGN_LINES = 4;
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Comparator<LabeledContainer> NEAREST_FIRST =
        Comparator.comparingInt((LabeledContainer label) -> label.container().travelCost())
            .thenComparingInt(label -> label.container().position().x())
            .thenComparingInt(label -> label.container().position().y())
            .thenComparingInt(label -> label.container().position().z());

    private SignStorage() { }

    public record Position(int x, int y, int z) {
        private Position offset(int dx, int dy, int dz) {
            long nextX = (long) x + dx;
            long nextY = (long) y + dy;
            long nextZ = (long) z + dz;
            return inRange(nextX) && inRange(nextY) && inRange(nextZ)
                ? new Position((int) nextX, (int) nextY, (int) nextZ) : null;
        }

        private static boolean inRange(long coordinate) {
            return coordinate >= Integer.MIN_VALUE && coordinate <= Integer.MAX_VALUE;
        }
    }

    public enum Front {
        NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0),
        UP(0, 1, 0), DOWN(0, -1, 0), UNKNOWN(0, 0, 0);

        private final int x;
        private final int y;
        private final int z;

        Front(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** Travel cost is a verified route cost in the current world, or UNREACHABLE. */
    public record Container(Position position, Front front, int travelCost) {
        public Container {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(front, "front");
            if (travelCost < UNREACHABLE) throw new IllegalArgumentException("Invalid travel cost");
        }
    }

    public record Sign(Position position, List<String> lines) {
        public Sign {
            Objects.requireNonNull(position, "position");
            lines = List.copyOf(lines);
            if (lines.size() > SIGN_LINES) throw new IllegalArgumentException("A sign has at most four lines");
        }
    }

    public interface ItemRegistry {
        boolean contains(String itemId);

        /** Returns all matches for a lower-case display name with collapsed whitespace. */
        Collection<String> findDisplayName(String normalizedName);
    }

    public record LabeledContainer(Container container, Set<String> acceptedItems) {
        public LabeledContainer {
            Objects.requireNonNull(container, "container");
            acceptedItems = Set.copyOf(acceptedItems);
            if (acceptedItems.isEmpty() || acceptedItems.size() > SIGN_LINES) {
                throw new IllegalArgumentException("A container label needs one to four item types");
            }
            acceptedItems.forEach(SignStorage::requireItemId);
        }
    }

    /** All containers and signs in a call must come from the same current world and dimension. */
    public static List<LabeledContainer> label(Collection<Container> containers, Collection<Sign> signs,
                                               ItemRegistry items) {
        Objects.requireNonNull(items, "items");
        Map<Position, Set<Container>> signClaims = new HashMap<>();
        Set<Position> positions = new HashSet<>();
        for (Container container : containers) {
            Position position = container.position();
            if (!positions.add(position)) throw new IllegalArgumentException("Duplicate container at " + position);
            claim(signClaims, position.offset(0, 1, 0), container);
            Front front = container.front();
            if (front != Front.UNKNOWN) claim(signClaims, position.offset(front.x, front.y, front.z), container);
        }

        Map<Container, Set<String>> labels = new HashMap<>();
        Set<Container> conflictingLabels = new HashSet<>();
        positions.clear();
        for (Sign sign : signs) {
            if (!positions.add(sign.position())) throw new IllegalArgumentException("Duplicate sign at " + sign.position());
            Set<Container> claims = signClaims.get(sign.position());
            if (claims == null || claims.size() != 1) continue;
            Set<String> accepted = new HashSet<>();
            for (String line : sign.lines()) {
                String item = resolveItem(line, items);
                if (item != null) accepted.add(item);
            }
            if (accepted.isEmpty()) continue;
            Container container = claims.iterator().next();
            Set<String> existing = labels.putIfAbsent(container, accepted);
            if (existing != null && !existing.equals(accepted)) conflictingLabels.add(container);
        }
        return labels.entrySet().stream()
            .filter(entry -> !conflictingLabels.contains(entry.getKey()))
            .map(entry -> new LabeledContainer(entry.getKey(), entry.getValue()))
            .sorted(NEAREST_FIRST)
            .toList();
    }

    private static void claim(Map<Position, Set<Container>> claims, Position signPosition, Container container) {
        if (signPosition != null) claims.computeIfAbsent(signPosition, position -> new HashSet<>()).add(container);
    }

    private static String resolveItem(String line, ItemRegistry items) {
        String name = line.strip();
        if (name.isEmpty()) return null;
        if (name.indexOf(':') >= 0) return isItemId(name) && items.contains(name) ? name : null;

        String normalized = WHITESPACE.matcher(name).replaceAll(" ").toLowerCase(Locale.ROOT);
        String unique = null;
        for (String match : items.findDisplayName(normalized)) {
            if (!isItemId(match) || !items.contains(match)) continue;
            if (unique != null && !unique.equals(match)) return null;
            unique = match;
        }
        return unique;
    }

    public enum Direction { DEPOSIT, WITHDRAW }

    public record Amount(String itemId, int count) {
        public Amount {
            requireItemId(itemId);
            if (count <= 0) throw new IllegalArgumentException("Item count must be positive");
        }
    }

    public record Limits(int maxTravelCost, int maxChecks, int maxTransfers) {
        public Limits {
            if (maxTravelCost < 0 || maxChecks < 0 || maxTransfers < 0) {
                throw new IllegalArgumentException("Transfer limits cannot be negative");
            }
        }
    }

    @FunctionalInterface
    public interface InventoryPreview {
        /**
         * Reserves up to maximum items in a detached snapshot of both inventories.
         * Return zero for unavailable or unsupported transfers. Capacity and source stock
         * reserved here must be deducted from subsequent calls, including shared slots.
         */
        int reserve(Container container, Direction direction, String itemId, int maximum);
    }

    public record Transfer(Container container, Direction direction, Amount amount) { }

    public record Plan(List<Transfer> transfers, List<Amount> remaining, int checks) {
        public Plan {
            transfers = List.copyOf(transfers);
            remaining = List.copyOf(remaining);
        }
    }

    /** Builds a preview only. A live executor must revalidate labels, stock, capacity and route access. */
    public static Plan plan(Direction direction, List<Amount> requests, Collection<LabeledContainer> labeled,
                            Limits limits, InventoryPreview preview) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(preview, "preview");
        List<Amount> orderedRequests = List.copyOf(requests);
        Map<String, List<Container>> destinations = new HashMap<>();
        Set<Position> positions = new HashSet<>();
        for (LabeledContainer label : labeled.stream().sorted(NEAREST_FIRST).toList()) {
            Container container = label.container();
            if (!positions.add(container.position())) {
                throw new IllegalArgumentException("Duplicate labeled container at " + container.position());
            }
            if (container.travelCost() == UNREACHABLE || container.travelCost() > limits.maxTravelCost()) continue;
            for (String item : label.acceptedItems()) {
                destinations.computeIfAbsent(item, id -> new ArrayList<>()).add(container);
            }
        }

        List<Transfer> transfers = new ArrayList<>();
        List<Amount> remaining = new ArrayList<>();
        int checks = 0;
        for (Amount request : orderedRequests) {
            int pending = request.count();
            for (Container container : destinations.getOrDefault(request.itemId(), List.of())) {
                if (pending == 0 || checks == limits.maxChecks() || transfers.size() == limits.maxTransfers()) break;
                checks++;
                int reserved = preview.reserve(container, direction, request.itemId(), pending);
                if (reserved < 0 || reserved > pending) throw new IllegalStateException("Invalid inventory reservation");
                if (reserved == 0) continue;
                transfers.add(new Transfer(container, direction, new Amount(request.itemId(), reserved)));
                pending -= reserved;
            }
            if (pending != 0) remaining.add(new Amount(request.itemId(), pending));
        }
        return new Plan(transfers, remaining, checks);
    }

    private static boolean isItemId(String itemId) {
        return itemId != null && ITEM_ID.matcher(itemId).matches();
    }

    private static void requireItemId(String itemId) {
        if (!isItemId(itemId)) throw new IllegalArgumentException("Expected a namespaced item ID: " + itemId);
    }
}
