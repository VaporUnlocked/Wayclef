package dev.soprano.ui;

import dev.soprano.game.Navigation;
import dev.soprano.game.RoutePermissions;
import dev.soprano.game.VitalityPolicy;
import dev.soprano.game.transport.ElytraReservePolicy;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.RouteExclusions;

/** Encodes immutable route settings for task history. */
final class HistorySettings {
    private HistorySettings() { }

    static String goal(Goal goal) {
        Goal.HeightBand band = goal.heightBand();
        RouteExclusions.Bounds bounds = goal.bounds();
        String region = bounds == null ? ",,,,," : bounds.minX() + "," + bounds.minY() + "," + bounds.minZ() + ","
            + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ();
        String encoded = String.join(",", goal.skyExposed() ? "3" : "2", Integer.toString(goal.x()), nullable(goal.y16()), Integer.toString(goal.z()),
            Integer.toString(goal.radius()), nullable(goal.sightY()), band == null ? "" : Integer.toString(band.minY16()),
            band == null ? "" : Integer.toString(band.maxY16()), goal.observedBlockId() == null ? "" : goal.observedBlockId(), region);
        return goal.skyExposed() ? encoded + ",true" : encoded;
    }

    static Goal goal(String stored) {
        String[] value = fields(stored);
        if (value.length == 5) return new Goal(integer(value, 0), optional(value, 1), integer(value, 2),
            integer(value, 3), optional(value, 4));
        boolean surface = value.length == 16 && value[0].equals("3") && value[15].equals("true");
        if (!surface && (value.length != 15 || !value[0].equals("2"))) throw new IllegalArgumentException("Unknown saved goal format.");
        Goal.HeightBand band = value[6].isEmpty() ? null : new Goal.HeightBand(integer(value, 6), integer(value, 7));
        RouteExclusions.Bounds bounds = value[9].isEmpty() ? null : new RouteExclusions.Bounds(
            Double.parseDouble(value[9]), Double.parseDouble(value[10]), Double.parseDouble(value[11]),
            Double.parseDouble(value[12]), Double.parseDouble(value[13]), Double.parseDouble(value[14]));
        return new Goal(integer(value, 1), optional(value, 2), integer(value, 3), integer(value, 4), optional(value, 5),
            band, value[8].isEmpty() ? null : value[8], bounds, surface);
    }

    static String navigation(Navigation.Options options) {
        var search = options.search();
        var reserves = options.elytraReserves();
        var actions = options.permissions();
        return String.join(",", "2", flag(search.diagonals()), flag(search.jumping()), Integer.toString(search.maxDrop()),
            Integer.toString(search.horizontalRange()), Integer.toString(search.maxVisited()), flag(search.allowPartial()),
            search.searchMode().name(), Integer.toString(options.expansionsPerTick()), flag(options.steerView()), flag(options.autoReplan()),
            Integer.toString(options.planningBudgetMillis()), Integer.toString(options.replanLimit()),
            Integer.toString(options.partialSegmentLimit()), options.transportMode().name(), Integer.toString(reserves.rockets()),
            Integer.toString(reserves.durability()), flag(options.avoidMobs()), Integer.toString(actions.breakLimit()),
            Integer.toString(actions.placeLimit()), flag(actions.useDoors()), flag(actions.closeDoors()), flag(search.gapJumps()),
            Integer.toString(options.vitality().healthPoints()), Integer.toString(options.vitality().foodLevel()), flag(options.adaptivePlanning()));
    }

    static Navigation.Options navigation(String stored) {
        String[] value = fields(stored);
        boolean current = value.length == 26 && value[0].equals("2");
        if (!current && (value.length != 22 || !value[0].equals("1"))) throw new IllegalArgumentException("Unknown saved route settings version.");
        var config = new IncrementalSearch.Config(flag(value, 1), flag(value, 2), integer(value, 3), integer(value, 4),
            integer(value, 5), flag(value, 6), IncrementalSearch.SearchMode.valueOf(value[7]), current && flag(value, 22));
        var reserves = new ElytraReservePolicy.Reserves(integer(value, 15), integer(value, 16));
        var actions = new RoutePermissions(integer(value, 18), integer(value, 19), flag(value, 20), flag(value, 21));
        return new Navigation.Options(config, integer(value, 8), flag(value, 9), flag(value, 10), integer(value, 11),
            integer(value, 12), integer(value, 13), TransportMode.valueOf(value[14]), reserves, flag(value, 17), actions,
            current ? new VitalityPolicy.Minimums(integer(value, 23), integer(value, 24)) : VitalityPolicy.Minimums.DISABLED,
            current && flag(value, 25));
    }

    private static String[] fields(String stored) {
        if (stored == null) throw new IllegalArgumentException("Saved task settings are missing.");
        return stored.split(",", -1);
    }

    private static String nullable(Integer value) { return value == null ? "" : value.toString(); }
    private static int integer(String[] values, int index) { return Integer.parseInt(values[index]); }
    private static Integer optional(String[] values, int index) { return values[index].isEmpty() ? null : integer(values, index); }
    private static String flag(boolean value) { return value ? "1" : "0"; }
    private static boolean flag(String[] values, int index) {
        return switch (values[index]) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new IllegalArgumentException("Invalid saved route setting.");
        };
    }
}
