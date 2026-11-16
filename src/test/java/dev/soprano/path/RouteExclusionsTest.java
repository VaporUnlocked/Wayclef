package dev.soprano.path;

import java.util.ArrayList;
import java.util.List;

import dev.soprano.path.RouteExclusions.Bounds;
import dev.soprano.path.RouteExclusions.Exclusion;
import dev.soprano.path.RouteExclusions.Footprint;
import dev.soprano.path.RouteExclusions.Kind;
import dev.soprano.path.RouteExclusions.Point;

public final class RouteExclusionsTest {
    private static final Footprint PLAYER = new Footprint(0.6, 1.8);
    private static final Bounds ONE_BLOCK = Bounds.blocksInclusive(0, 0, 0, 0, 0, 0);
    private static final Exclusion GARDEN = new Exclusion(Kind.NO_GO_ZONE, "Garden", ONE_BLOCK);

    public static void main(String[] args) {
        checksStandingAndSelectedBounds();
        checksSweptMovement();
        checksMobOptInAndCollisionOrder();
        checksInvalidGeometry();
        System.out.println("Soprano route exclusions checks passed");
    }

    private static void checksStandingAndSelectedBounds() {
        Bounds reversed = Bounds.blocksInclusive(4, 7, 2, -3, -2, -1);
        check(reversed.equals(new Bounds(-3, -2, -1, 5, 8, 3)), "both selected blocks included");
        var exclusions = new RouteExclusions(List.of(GARDEN));
        check(exclusions.blockingAt(new Point(0.5, 0, 0.5), PLAYER).orElseThrow().equals(GARDEN), "standing inside");
        check(exclusions.blockingAt(new Point(0.5, 1, 0.5), PLAYER).isEmpty(), "standing on the upper boundary");
        check(exclusions.blockingAt(new Point(-0.3, 0, 0.5), PLAYER).isEmpty(), "exact horizontal contact");
        check(exclusions.blockingAt(new Point(-0.29, 0, 0.5), PLAYER).isPresent(), "body crosses boundary");
        check(exclusions.blockingAt(new Point(0.5, -1.79, 0.5), PLAYER).isPresent(), "head crosses boundary");
    }

    private static void checksSweptMovement() {
        var exclusions = new RouteExclusions(List.of(GARDEN));
        var diagonalStart = new Point(-2, 0, -2);
        var diagonalEnd = new Point(3, 0, 3);
        check(exclusions.blockingAt(diagonalStart, PLAYER).isEmpty(), "diagonal start clear");
        check(exclusions.blockingAt(diagonalEnd, PLAYER).isEmpty(), "diagonal end clear");
        check(exclusions.blockingSegment(diagonalStart, diagonalEnd, PLAYER).isPresent(), "diagonal crosses volume");
        check(exclusions.blockingSegment(new Point(-1, 0, 0.3), new Point(0.3, 0, -1), PLAYER).isEmpty(),
            "diagonal skirts corner even with overlapping swept bounds");
        check(exclusions.blockingSegment(new Point(-1.5, 0, 0.5), new Point(0.5, 0, -1.5), new Footprint(1, 2)).isEmpty(),
            "instantaneous diagonal corner contact");
        check(exclusions.blockingSegment(new Point(-2, 0, -0.25), new Point(3, 0, -0.25), PLAYER).isPresent(),
            "movement clips a side with the body");
        check(exclusions.blockingSegment(new Point(-2, 0, -0.3), new Point(3, 0, -0.3), PLAYER).isEmpty(),
            "movement parallel to an exact boundary");
        check(exclusions.blockingSegment(new Point(-2, 0, 0.5), new Point(-0.3, 0, 0.5), PLAYER).isEmpty(),
            "ends at the first boundary without entering");
        check(exclusions.blockingSegment(new Point(-0.3, 0, 0.5), new Point(2, 0, 0.5), PLAYER).isPresent(),
            "starts on the boundary and enters");
        check(exclusions.blockingPath(List.of(new Point(-1.5, 0, 0.5), new Point(-0.5, 1.4, 0.5),
            new Point(1.5, 1.4, 0.5), new Point(2.5, 0, 0.5)), PLAYER).isEmpty(), "jump path clears the volume");
    }

    private static void checksMobOptInAndCollisionOrder() {
        var mob = new Exclusion(Kind.MOB, "Zombie", ONE_BLOCK);
        var lava = new Exclusion(Kind.HAZARD, "Lava", Bounds.blocksInclusive(2, 0, 0, 2, 0, 0));
        var from = new Point(-2, 0, 0.5);
        var to = new Point(4, 0, 0.5);
        var mutableInput = new ArrayList<>(List.of(lava, mob));
        var defaultPolicy = new RouteExclusions(mutableInput);
        var mobPolicy = new RouteExclusions(mutableInput, true);
        mutableInput.clear();
        check(defaultPolicy.blockingSegment(from, to, PLAYER).orElseThrow().equals(lava), "default skips mobs only");
        check(mobPolicy.blockingSegment(from, to, PLAYER).orElseThrow().equals(mob), "closer obstacle wins");
        check(mobPolicy.blockingSegment(to, from, PLAYER).orElseThrow().equals(lava), "contact order follows movement");
        var tied = new RouteExclusions(List.of(GARDEN, mob), true);
        check(tied.blockingSegment(from, to, PLAYER).orElseThrow().reason().equals("No-go zone Garden"), "snapshot tie order");
        check(defaultPolicy.blockingPath(List.of(from, new Point(1.6, 0, 0.5), to), PLAYER).orElseThrow().equals(lava),
            "later path pieces checked");
    }

    private static void checksInvalidGeometry() {
        rejects(() -> new Point(Double.NaN, 0, 0));
        rejects(() -> new Footprint(0, 1));
        rejects(() -> new Bounds(0, 0, 0, 0, 1, 1));
        rejects(() -> new Exclusion(Kind.HAZARD, " ", ONE_BLOCK));
    }

    private static void rejects(Runnable create) {
        try {
            create.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid geometry accepted");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
