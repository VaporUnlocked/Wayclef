package dev.soprano.map;

import dev.soprano.map.PortalLinks.Endpoint;
import dev.soprano.path.Goal;
import dev.soprano.path.RouteItinerary;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.util.Map;

public final class PortalLinksTest {
    public static void main(String[] args) throws Exception {
        String overworld = PortalCoordinates.OVERWORLD, nether = PortalCoordinates.NETHER;
        var start = new Endpoint(overworld, new BlockPos(344, 64, 100));
        var actual = new Endpoint(nether, new BlockPos(46, 79, 14));
        var reverse = new Endpoint(overworld, new BlockPos(370, 80, 108));
        var expected = PortalLinks.destination(start, Map.of());
        check(expected.position().equals(new BlockPos(43, 64, 12)) && expected.radius() == 16 && expected.projected(),
            "Overworld projection preserves height and scales X/Z");
        check(PortalCoordinates.project(overworld, new BlockPos(-1, 80, -9)).position().equals(new BlockPos(-1, 80, -2)),
            "Negative positions floor after scaling");
        check(PortalCoordinates.project(nether, new BlockPos(3_750_000, 80, 0)) == null, "Reverse projection checks its world bounds");
        var learned = PortalLinks.destination(start, Map.of(start, actual));
        check(learned.position().equals(actual.position()) && learned.observed() && !learned.projected(),
            "Observed exit overrides the projection");
        var returnCandidate = PortalLinks.destination(actual, Map.of(start, actual));
        check(returnCandidate.position().equals(start.position()) && !returnCandidate.observed(), "Reverse direction is a candidate");
        check(PortalLinks.destination(actual, Map.of(start, actual, reverse, actual)).projected(),
            "Multiple incoming links do not imply a single return");
        var saved = Map.of(start, actual, actual, reverse);
        var finalStop = new RouteItinerary.Stop(nether, new Goal(50, null, 50, 0), 0);
        var trip = new RouteItinerary("Nether destination", java.util.List.of(finalStop), RouteItinerary.Repeat.ONCE);
        var connected = PortalLinks.connectTrip(trip, overworld, new BlockPos(330, 64, 100), saved);
        check(connected.stops().size() == 2 && connected.stops().getFirst().portal().area().x() == actual.position().getX(),
            "Trips insert an observed directed crossing");
        connected.validateTravel();
        check(PortalLinks.connectTrip(trip, overworld, start.position(), Map.of(actual, start)) == trip,
            "Automatic trip planning requires an observed exit in the needed direction");
        var dir = Files.createTempDirectory("portal-links-test");
        var source = PortalLinks.path(dir, new Scope("server-a", "world-a", overworld));
        try {
            check(source.equals(PortalLinks.path(dir, new Scope("server-a", "world-a", nether))), "Both dimensions share a world link file");
            PortalLinks.write(source, saved);
            check(PortalLinks.read(source).equals(saved), "Directional and asymmetric links persist");
            check(PortalLinks.read(PortalLinks.path(dir, new Scope("server-b", "world-a", overworld))).isEmpty(),
                "A separate server cannot see this world");
            check(PortalLinks.read(PortalLinks.path(dir, new Scope("server-a", "world-b", overworld))).isEmpty(),
                "A second world cannot see these links");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(source.getParent());
            Files.deleteIfExists(dir);
        }
        System.out.println("Soprano portal projection and observed link checks passed.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
