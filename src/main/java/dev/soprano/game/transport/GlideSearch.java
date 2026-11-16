package dev.soprano.game.transport;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Searches loaded air using descending, heading-constrained legs. The client supplies collision checks. */
final class GlideSearch {
    @FunctionalInterface
    interface Clearance {
        boolean clear(Vec3 start, Vec3 end, double horizontalMargin);
    }

    private static final int GRID = 8;
    private static final int VERTICAL_GRID = 2;
    private static final int MAX_VISITED = 2_048;
    private static final double DIRECT_DISTANCE = 96;
    private static final int[][] HEADINGS = {
        {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };

    private record Key(int x, int z, int descent, int heading) { }
    private record Link(Key key, Link parent, double cost, double rank) { }

    private final Vec3 start;
    private final Vec3 landing;
    private final Clearance clearance;
    private final int range;
    private final int maxVisited;
    private final int initialHeading;
    private final double startDirectionX;
    private final double startDirectionZ;
    private final Map<Key, Link> best = new HashMap<>();
    private final PriorityQueue<Link> open = new PriorityQueue<>(Comparator.comparingDouble(Link::rank)
        .thenComparingDouble(Link::cost));
    private boolean directTried;
    private boolean complete;
    private int visited;
    private List<Vec3> path = List.of();

    GlideSearch(Vec3 start, Vec3 velocity, Vec3 landing, int range, int maxVisited, Clearance clearance) {
        this.start = start;
        this.landing = landing;
        this.clearance = clearance;
        this.range = range;
        this.maxVisited = Math.min(MAX_VISITED, maxVisited);
        double speed = Math.hypot(velocity.x, velocity.z);
        if (speed < .05) throw new IllegalArgumentException("Glide needs horizontal momentum.");
        startDirectionX = velocity.x / speed;
        startDirectionZ = velocity.z / speed;
        initialHeading = Math.floorMod((int) Math.round(Math.atan2(velocity.x, velocity.z) * 4 / Math.PI), 8);
        if (landing.y >= start.y || !withinRange(landing)) {
            complete = true;
            return;
        }
        Key origin = new Key(0, 0, 0, initialHeading);
        Link first = new Link(origin, null, 0, estimate(start));
        open.add(first);
        best.put(origin, first);
    }

    void advance(int expansions, long budgetNanos) {
        if (complete || expansions <= 0 || budgetNanos <= 0) return;
        long begin = System.nanoTime();
        if (!directTried) {
            directTried = true;
            if (withinRange(landing) && canFinish(start, startDirectionX, startDirectionZ, DIRECT_DISTANCE)) {
                path = List.of(start, landing);
                complete = true;
                return;
            }
        }
        for (int i = 0; i < expansions && visited < maxVisited; i++) {
            if (System.nanoTime() - begin >= budgetNanos) break;
            Link link = open.poll();
            if (link == null) {
                complete = true;
                return;
            }
            if (best.get(link.key()) != link) continue;
            visited++;
            Vec3 position = position(link.key());
            int[] heading = HEADINGS[link.key().heading()];
            if (link.parent() != null && canFinish(position, heading[0], heading[1], 24)) {
                publish(link);
                return;
            }
            expand(link, position);
        }
        if (visited >= maxVisited || open.isEmpty()) complete = true;
    }

    private void expand(Link link, Vec3 from) {
        Key key = link.key();
        for (int turn = -1; turn <= 1; turn++) {
            int heading = Math.floorMod(key.heading() + turn, HEADINGS.length);
            int[] direction = HEADINGS[heading];
            if (link.parent() == null && !aligned(direction[0], direction[1], startDirectionX, startDirectionZ)) continue;
            if (turn != 0 && !clearance.clear(from, from, 1.2)) continue;
            for (int descent = 1; descent <= 3; descent++) {
                Key next = new Key(key.x() + direction[0], key.z() + direction[1], key.descent() + descent, heading);
                Vec3 to = position(next);
                if (!withinRange(to) || to.y <= landing.y + 1.0) continue;
                double cost = link.cost() + from.distanceTo(to) + Math.abs(turn) * 1.4;
                Link previous = best.get(next);
                if (previous != null && previous.cost() <= cost) continue;
                if (!clearance.clear(from, to, .72)) continue;
                Link candidate = new Link(next, link, cost, cost + estimate(to));
                best.put(next, candidate);
                open.add(candidate);
            }
        }
    }

    private boolean canFinish(Vec3 from, double directionX, double directionZ, double maximumDistance) {
        double dx = landing.x - from.x, dz = landing.z - from.z;
        double distance = Math.hypot(dx, dz);
        double descent = from.y - landing.y;
        return distance >= 3.0 && distance <= maximumDistance
            && descent >= distance * .09 && descent <= distance * .85
            && aligned(dx, dz, directionX, directionZ)
            && clearance.clear(from, landing, .72);
    }

    private static boolean aligned(double dx, double dz, double headingX, double headingZ) {
        double divisor = Math.hypot(dx, dz) * Math.hypot(headingX, headingZ);
        return divisor > 0 && (dx * headingX + dz * headingZ) / divisor >= .60;
    }

    private boolean withinRange(Vec3 point) {
        return Math.abs(point.x - start.x) <= range && Math.abs(point.z - start.z) <= range;
    }

    private Vec3 position(Key key) {
        return start.add(key.x() * GRID, -key.descent() * VERTICAL_GRID, key.z() * GRID);
    }

    private double estimate(Vec3 from) {
        double horizontal = Math.hypot(landing.x - from.x, landing.z - from.z);
        double descent = Math.max(0, from.y - landing.y);
        return Math.max(horizontal, descent / .82) * 1.08;
    }

    private void publish(Link link) {
        List<Vec3> result = new ArrayList<>();
        for (Link part = link; part != null; part = part.parent()) result.add(position(part.key()));
        Collections.reverse(result);
        result.add(landing);
        path = List.copyOf(result);
        complete = true;
    }

    boolean complete() { return complete; }
    int visited() { return visited; }
    List<Vec3> path() { return path; }
}
