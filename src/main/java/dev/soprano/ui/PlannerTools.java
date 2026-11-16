package dev.soprano.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.soprano.SopranoClient;
import dev.soprano.game.FollowController;
import dev.soprano.map.MapAnnotations;
import dev.soprano.map.PortalLinks;
import dev.soprano.map.PortalTracker;
import dev.soprano.task.TaskHistory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Live forms and lists used by the planner's navigation and reference pages. */
final class PlannerTools {
    private static final int CONTROL = 22;
    private static final int ROW = 33;
    private static final int GAP = 8;
    private static final int MAX_COMMAND_HISTORY = 36;
    private static final List<Identifier> TRAVEL_STATS = List.of(Stats.WALK_ONE_CM, Stats.CROUCH_ONE_CM,
        Stats.SPRINT_ONE_CM, Stats.WALK_ON_WATER_ONE_CM, Stats.FALL_ONE_CM, Stats.CLIMB_ONE_CM, Stats.FLY_ONE_CM,
        Stats.WALK_UNDER_WATER_ONE_CM, Stats.MINECART_ONE_CM, Stats.BOAT_ONE_CM, Stats.PIG_ONE_CM,
        Stats.HAPPY_GHAST_ONE_CM, Stats.HORSE_ONE_CM, Stats.AVIATE_ONE_CM, Stats.SWIM_ONE_CM,
        Stats.STRIDER_ONE_CM, Stats.NAUTILUS_ONE_CM);
    private static final Set<String> WORLD_ACTIONS = Set.of("goto", "xz", "here", "surface", "follow", "detour",
        "resume", "mine", "gather", "breed", "brew", "enchant", "cushion", "portal", "stronghold", "escape", "farm", "pickup", "tunnel", "job", "build");

    private record Command(String syntax, String description, String input) { }
    private static final List<Command> COMMANDS = List.of(
        new Command("goto <x> [y] <z>", "Walk to a coordinate. Y is optional.", "goto "),
        new Command("xz <x> <z>", "Walk to a column at a reachable height.", "xz "),
        new Command("preview <x> [y] <z>", "Find a route and display it on the map.", "preview "),
        new Command("here", "Walk to the block under the crosshair.", "here"),
        new Command("surface", "Walk to a safe spot under open sky.", "surface"),
        new Command("follow <player>", "Follow a visible player.", "follow "),
        new Command("gather <item> <amount>", "Gather and craft up to the requested inventory count.", "gather "),
        new Command("breed <mob> [count]", "Breed eligible animals using carried food.", "breed "),
        new Command("brew <potion> [amount] [form]", "Use carried supplies to brew at the targeted stand.", "brew "),
        new Command("enchant <item> [offer] [enchantment]", "Choose a displayed offer at the targeted enchanting table.", "enchant "),
        new Command("cushion [color]", "Walk to a free cushion and sit.", "cushion"),
        new Command("cushion to <x> <y> <z>", "Travel by right-clicking a chain of cushions.", "cushion to "),
        new Command("portal build <x> <y> <z> <x|z>", "Build an obsidian frame around the lower opening.", "portal build "),
        new Command("portal light [x y z]", "Light a complete Nether portal frame.", "portal light "),
        new Command("portal mark [x y z]", "Record an active portal and show its counterpart.", "portal mark "),
        new Command("portal enter [x y z]", "Walk through a marked Nether portal.", "portal enter "),
        new Command("portal eyes [x y z]", "Place Eyes of Ender in the remaining frame sockets.", "portal eyes "),
        new Command("portal gateway [x y z]", "Use an ender pearl on a loaded End gateway.", "portal gateway "),
        new Command("stronghold", "Estimate a stronghold from your next two Eye of Ender throws.", "stronghold"),
        new Command("mine [count] <blocks>", "Mine nearby blocks, including buried targets.", "mine "),
        new Command("farm [radius]", "Harvest and replant nearby crops.", "farm"),
        new Command("pickup <item> [count]", "Pick up an item dropped in the world.", "pickup "),
        new Command("tunnel [length]", "Excavate a tunnel in the current direction.", "tunnel"),
        new Command("escape [pearl|chorus]", "Use a carried teleport item to get away.", "escape"),
        new Command("build [file]", "Build the current plan or a schematic file.", "build"),
        new Command("build at <x> <y> <z> <file>", "Start a schematic at a specific origin.", "build at "),
        new Command("sel pos1|pos2 [x y z]", "Set the corners of a world selection.", "sel "),
        new Command("sel clear", "Clear the selected corners.", "sel clear"),
        new Command("sel fill|shell <block>", "Fill the selection or build its outer shell.", "sel "),
        new Command("sel cleararea", "Excavate the selected area.", "sel cleararea"),
        new Command("sel replace [x y z]", "Replace matching blocks inside the selection.", "sel replace"),
        new Command("job <step>", "Run one step from the Activities job commands.", "job "),
        new Command("detour", "Suspend a trip and route to the targeted block.", "detour"),
        new Command("pause", "Pause the current route.", "pause"),
        new Command("resume", "Continue a paused route or trip.", "resume"),
        new Command("stop | cancel", "Stop movement and world interactions.", "stop"),
        new Command("status", "Read the current job status.", "status"),
        new Command("settings", "Open Wayclef.", "settings"),
        new Command("help", "Print command usage in chat.", "help"));

    private record Metric(String label, String value) { }

    private final NumberFormat integers = NumberFormat.getIntegerInstance();
    private final List<String> commandHistory = new ArrayList<>();
    private String tab = "";
    private int scroll;
    private int scrollLimit;
    private int listX, listY, listWidth, listHeight;
    private String lastQuery = "";
    private EditBox gotoX, gotoY, gotoZ, command;
    private PlannerPage currentPage;
    private ClientLevel routesLevel;
    private List<TaskHistory.Entry> routes = List.of();
    private String historyError;
    private ClientLevel targetsLevel;
    private List<Entity> targets = List.of();
    private long targetRefresh;
    private boolean alphabeticalWaypoints, portalWaypointsOnly;
    private MapAnnotations.Marker selectedWaypoint;
    private ClientLevel statsLevel;
    private List<Metric> metrics = List.of();
    private long statsRefresh;
    private CommandDispatcher<ClientSuggestionProvider> dispatcher;
    private String helpResult;
    private boolean helpError;
    private int historyCursor;
    private String unfinishedCommand = "";

    void enter(String tab) {
        this.tab = tab;
        scroll = scrollLimit = 0;
        listHeight = 0;
        lastQuery = "";
        currentPage = null;
        if (tab.equals("Go to")) routesLevel = null;
        if (tab.equals("Follow")) targetsLevel = null;
        if (tab.equals("Stats")) statsLevel = null;
        if (tab.equals("Help")) historyCursor = commandHistory.size();
    }

    void render(String tab, PlannerPage page) {
        if (!this.tab.equals(tab)) enter(tab);
        currentPage = page;
        listHeight = 0;
        switch (tab) {
            case "Go to" -> goTo(page);
            case "Follow" -> follow(page);
            case "Waypoints" -> waypoints(page);
            case "Stats" -> stats(page);
            case "Help" -> help(page);
            default -> { }
        }
    }

    boolean scroll(double x, double y, double amount) {
        if (scrollLimit == 0 || x < listX || x >= listX + listWidth || y < listY || y >= listY + listHeight) return false;
        scroll = Math.clamp(scroll - (int) Math.round(amount * ROW), 0, scrollLimit);
        return true;
    }

    boolean keyPressed(KeyEvent key) {
        if (currentPage == null) return false;
        int code = key.key();
        boolean enter = code == InputConstants.KEY_RETURN || code == InputConstants.KEY_NUMPADENTER;
        if (tab.equals("Go to") && enter && (focused(gotoX) || focused(gotoY) || focused(gotoZ))) {
            currentPage.run(() -> coordinateRoute(currentPage, true));
            return true;
        }
        if (!tab.equals("Help") || !focused(command)) return false;
        if (enter) {
            runCommand(currentPage);
            return true;
        }
        if ((code == InputConstants.KEY_UP || code == InputConstants.KEY_DOWN) && !commandHistory.isEmpty()) {
            if (historyCursor == commandHistory.size()) unfinishedCommand = command.getValue();
            historyCursor = Math.clamp(historyCursor + (code == InputConstants.KEY_UP ? -1 : 1), 0, commandHistory.size());
            command.setValue(historyCursor == commandHistory.size() ? unfinishedCommand : commandHistory.get(historyCursor));
            return true;
        }
        return false;
    }

    private void goTo(PlannerPage page) {
        if (!inWorld(page)) return;
        int x = page.x + 12, y = page.y + 12, width = page.width - 24;
        int column = (width - 2 * GAP) / 3;
        page.panel(page.x, page.y, page.width, 108);
        page.text("X", x, y, page.muted);
        page.text("Y", x + column + GAP, y, page.muted);
        page.text("Z", x + (column + GAP) * 2, y, page.muted);
        gotoX = page.field("tools.go.x", "X coordinate", 10, x, y + 13, column);
        gotoY = page.field("tools.go.y", "Optional", 6, x + column + GAP, y + 13, column);
        gotoZ = page.field("tools.go.z", "Z coordinate", 10, x + (column + GAP) * 2, y + 13, column);
        Integer targetX = integer(gotoX), targetY = integer(gotoY), targetZ = integer(gotoZ);
        boolean ready = targetX != null && targetZ != null && (gotoY.getValue().isBlank() || targetY != null);
        String dimension = worldName(page.minecraft);
        if (ready) {
            var player = page.minecraft.player;
            double dy = targetY == null ? 0 : targetY - player.getY();
            double distance = Math.hypot(Math.hypot(targetX + .5 - player.getX(), targetZ + .5 - player.getZ()), dy);
            dimension = formatDistance(distance) + " straight line  ·  " + dimension;
        }
        page.label(dimension, x, y + 43, width, page.muted);
        int first = width / 5, second = width / 4, third = width / 3;
        page.button(x, y + 62, first - 4, CONTROL, "Go", ready, true, () -> coordinateRoute(page, true));
        page.button(x + first, y + 62, second - 4, CONTROL, "Preview", ready, false, () -> coordinateRoute(page, false));
        page.button(x + first + second, y + 62, third - 4, CONTROL, "My position", true, false, () -> {
            BlockPos at = page.minecraft.player.blockPosition();
            gotoX.setValue(Integer.toString(at.getX()));
            gotoY.setValue(Integer.toString(at.getY()));
            gotoZ.setValue(Integer.toString(at.getZ()));
        });
        page.button(x + first + second + third, y + 62, width - first - second - third, CONTROL, "Clear", true, false, () -> {
            gotoX.setValue("");
            gotoY.setValue("");
            gotoZ.setValue("");
        });
        recentRoutes(page, page.y + 123);
    }

    private void coordinateRoute(PlannerPage page, boolean walk) {
        Integer x = integer(gotoX), y = integer(gotoY), z = integer(gotoZ);
        if (x == null || z == null || y == null && !gotoY.getValue().isBlank()) {
            throw new IllegalArgumentException("Enter whole-number X and Z coordinates. Y may be left empty.");
        }
        route(page, x, y, z, walk);
    }

    private void recentRoutes(PlannerPage page, int y) {
        if (routesLevel != page.minecraft.level) {
            routesLevel = page.minecraft.level;
            try {
                routes = SopranoHistory.entries().stream().filter(entry -> entry.task().kind().equals("route"))
                    .filter(entry -> entry.task().dimension().equals(routesLevel.dimension().identifier().toString()))
                    .sorted(Comparator.comparing(TaskHistory.Entry::recordedAt).reversed()).limit(16).toList();
                historyError = null;
            } catch (IllegalStateException disabled) {
                routes = List.of();
                historyError = disabled.getMessage();
            }
        }
        page.text("Recent destinations", page.x, y, page.text);
        y += 16;
        if (routes.isEmpty()) {
            page.label(historyError == null ? "No routes in this dimension yet." : historyError, page.x, y, page.width, page.muted);
            return;
        }
        int start = y;
        drawRows(page, start, routes.size(), (index, top) -> {
            TaskHistory.Entry recent = routes.get(index);
            page.label(recent.task().goal(), page.x + 10, top + 6, page.width - 85, page.text);
            page.label(worldName(page.minecraft) + "  ·  " + age(recent), page.x + 10, top + 19, page.width - 85, page.muted);
            page.button(page.x + page.width - 60, top + 5, 51, CONTROL, "Go", true, false, () -> {
                page.module.reviewHistory(recent, true);
                page.close();
            });
        });
    }

    private void follow(PlannerPage page) {
        if (!inWorld(page)) return;
        FollowController follow = FollowController.get();
        int y = page.y;
        if (follow.active()) {
            page.panel(page.x, y, page.width, 34);
            page.label(follow.statusText(), page.x + 10, y + 13, page.width - 75, page.text);
            page.button(page.x + page.width - 60, y + 6, 51, CONTROL, "Stop", true, false, follow::stop);
            y += 43;
        }
        EditBox search = page.field("tools.follow.query", "Search players and entities", 80, page.x, y, page.width);
        String query = query(search);
        resetScroll(query);
        y += 29;
        refreshTargets(page.minecraft);
        boolean players = targets.stream().anyMatch(Player.class::isInstance);
        boolean entities = targets.stream().anyMatch(target -> !(target instanceof Player));
        int section = (page.width - 5) / 2;
        page.button(page.x, y, section, CONTROL, "Nearest player", players, false, () -> {
            page.module.followNearest(true);
            page.close();
        });
        page.button(page.x + section + 5, y, page.width - section - 5, CONTROL, "Nearest entity", entities, false, () -> {
            page.module.followNearest(false);
            page.close();
        });
        List<Entity> shown = targets.stream().filter(target -> matches(query, targetName(target), typeName(target),
            BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString())).toList();
        y += 34;
        page.text("Visible targets  ·  " + shown.size(), page.x, y, page.text);
        y += 16;
        if (shown.isEmpty()) {
            page.label(query.isEmpty() ? "No visible players or entities within 48 blocks." : "No targets match that search.",
                page.x, y, page.width, page.muted);
            return;
        }
        drawRows(page, y, shown.size(), (index, top) -> {
            Entity target = shown.get(index);
            String range = formatDistance(page.minecraft.player.distanceTo(target));
            int left = page.x + 10;
            if (target instanceof ItemEntity item) {
                page.item(item.getItem(), left, top + 7);
                left += 23;
            }
            int textWidth = page.x + page.width - left - 75;
            page.label(targetName(target), left, top + 6, textWidth, page.text);
            page.label(typeName(target) + "  ·  " + range, left, top + 19, textWidth, page.muted);
            page.button(page.x + page.width - 60, top + 5, 51, CONTROL, "Follow", true, false, () -> {
                page.module.followEntity(target);
                page.close();
            });
        });
    }

    private void refreshTargets(Minecraft minecraft) {
        long now = System.nanoTime();
        if (targetsLevel == minecraft.level && now < targetRefresh) return;
        targetsLevel = minecraft.level;
        targetRefresh = now + 300_000_000L;
        targets = FollowController.visibleTargets(minecraft);
    }

    private void waypoints(PlannerPage page) {
        if (!inWorld(page)) return;
        int y = page.y;
        EditBox search = page.field("tools.waypoints.query", "Search waypoints", 80, page.x, y, page.width);
        String query = query(search);
        resetScroll(query);
        y += 28;
        int addWidth = 67;
        EditBox name = page.field("tools.waypoints.name", "Waypoint name", MapAnnotations.MAX_NOTE_LENGTH,
            page.x, y, page.width - addWidth - GAP);
        page.button(page.x + page.width - addWidth, y, addWidth, CONTROL, "Add here", !name.getValue().isBlank(), true, () -> {
            BlockPos at = page.minecraft.player.blockPosition();
            MapAnnotations.saveNote(page.minecraft, at, name.getValue());
            name.setValue("");
            scroll = 0;
        });
        y += 32;
        List<MapAnnotations.Marker> available = MapAnnotations.markers(page.minecraft);
        var sorter = alphabeticalWaypoints ? Comparator.comparing(MapAnnotations.Marker::text, String.CASE_INSENSITIVE_ORDER)
            : Comparator.<MapAnnotations.Marker>comparingDouble(marker -> distanceSquared(page.minecraft, marker.position()));
        List<MapAnnotations.Marker> markers = available.stream()
            .filter(marker -> !portalWaypointsOnly || marker.kind() == MapAnnotations.Kind.PORTAL)
            .filter(marker -> matches(query, marker.text(), markerKind(marker), marker.position().toShortString()))
            .sorted(sorter).toList();
        if (selectedWaypoint != null && !markers.contains(selectedWaypoint)) selectedWaypoint = null;
        String count = markers.size() + (portalWaypointsOnly ? " portals" : " waypoints");
        page.label(worldName(page.minecraft) + "  ·  " + count, page.x, y + 7, page.width - 145, page.text);
        page.button(page.x + page.width - 136, y, 52, CONTROL, "Portals", true, portalWaypointsOnly, () -> {
            portalWaypointsOnly = !portalWaypointsOnly;
            scroll = 0;
        });
        page.button(page.x + page.width - 78, y, 78, CONTROL, alphabeticalWaypoints ? "By name" : "By distance", true, false, () -> {
            alphabeticalWaypoints = !alphabeticalWaypoints;
            scroll = 0;
        });
        y += 30;
        if (selectedWaypoint != null) {
            MapAnnotations.Marker marker = selectedWaypoint;
            boolean nether = marker.kind() == MapAnnotations.Kind.PORTAL && PortalTracker.isNether(page.minecraft, marker.position());
            var destination = nether ? PortalLinks.destination(page.minecraft, marker.position()) : null;
            int action = (page.width - 16) / 3;
            int buttonsY = y + (destination == null ? 25 : 40);
            page.panel(page.x, y, page.width, destination == null ? 57 : 72);
            page.label(marker.text() + "  ·  " + marker.position().toShortString(), page.x + 8, y + 8, page.width - 16, page.text);
            if (destination != null) page.label(destination.label(), page.x + 8, y + 22, page.width - 16, page.muted);
            page.button(page.x + 8, buttonsY, action - 4, CONTROL, marker.kind() == MapAnnotations.Kind.PORTAL ? "Go near" : "Go",
                true, true, () -> markerRoute(page, marker, true));
            page.button(page.x + action + 8, buttonsY, action - 4, CONTROL, "Preview", true, false, () -> markerRoute(page, marker, false));
            if (destination != null) {
                page.button(page.x + 2 * action + 8, buttonsY, action, CONTROL, "Enter", true, false, () -> {
                    page.module.enterPortal(marker.position());
                    page.close();
                });
            } else if (marker.kind() == MapAnnotations.Kind.PORTAL && marker.text().equals("End gateway")) {
                page.button(page.x + 2 * action + 8, buttonsY, action, CONTROL, "Use pearl", true, false, () -> {
                    page.module.hopGateway(marker.position());
                    page.close();
                });
            } else {
                page.button(page.x + 2 * action + 8, buttonsY, action, CONTROL, "Remove", marker.kind() == MapAnnotations.Kind.NOTE,
                    false, () -> MapAnnotations.removeNote(page.minecraft, marker.position()));
            }
            y += destination == null ? 65 : 80;
        }
        if (markers.isEmpty()) {
            String empty = portalWaypointsOnly ? "No portals found here." : "No waypoints here yet.";
            page.label(query.isEmpty() ? empty : "No waypoints match that search.", page.x, y, page.width, page.muted);
            return;
        }
        drawRows(page, y, markers.size(), (index, top) -> {
            MapAnnotations.Marker marker = markers.get(index);
            int left = page.x + 10;
            if (marker.equals(selectedWaypoint) || page.over(page.x + 1, top, page.width - 2, ROW - 1))
                page.graphics.fill(page.x + 1, top, page.x + page.width - 1, top + ROW - 1, page.selected);
            page.hit(page.x + 1, top, page.width - 2, ROW - 1, () -> selectedWaypoint = marker.equals(selectedWaypoint) ? null : marker);
            page.label(marker.text(), left, top + 6, page.width - 86, page.text);
            page.label(markerKind(marker) + "  ·  " + marker.position().toShortString(), left, top + 19, page.width - 86, page.muted);
            String distance = formatDistance(Math.sqrt(distanceSquared(page.minecraft, marker.position())));
            page.text(distance, page.x + page.width - 10 - page.measure(distance), top + 12, page.muted);
        });
    }

    private void markerRoute(PlannerPage page, MapAnnotations.Marker marker, boolean walk) {
        BlockPos at = marker.position();
        if (marker.kind() == MapAnnotations.Kind.PORTAL) {
            page.module.routeToPortal(at, walk);
            if (walk) page.close();
            else page.show("Map");
            return;
        }
        var level = page.minecraft.level;
        Integer height = null;
        if (level != null && level.hasChunk(at.getX() >> 4, at.getZ() >> 4) && level.getBlockState(at).isAir()) height = at.getY();
        route(page, at.getX(), height, at.getZ(), walk);
    }

    private void stats(PlannerPage page) {
        if (!inWorld(page)) return;
        long now = System.nanoTime();
        if (statsLevel != page.minecraft.level) {
            statsLevel = page.minecraft.level;
            statsRefresh = 0;
            requestStats(page.minecraft);
        }
        if (now >= statsRefresh) {
            statsRefresh = now + 1_000_000_000L;
            metrics = readStats(page.minecraft.player.getStats());
        }
        page.label("Player statistics for this world", page.x, page.y + 6, page.width - 75, page.muted);
        page.button(page.x + page.width - 67, page.y, 67, CONTROL, "Refresh", true, false, () -> requestStats(page.minecraft));
        int columns = page.width >= 270 ? 2 : 1;
        int stride = (page.width + GAP) / columns;
        int cardWidth = stride - GAP;
        int y = page.y + 32;
        int totalRows = Math.ceilDiv(metrics.size(), columns);
        int visible = visibleRows(page, y, totalRows);
        if (visible == 0) return;
        int first = scroll / ROW;
        for (int row = first; row < Math.min(first + visible, totalRows); row++) {
            int top = y + (row - first) * ROW;
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= metrics.size()) break;
                int x = page.x + column * stride;
                page.panel(x, top, cardWidth, ROW - 4);
                Metric metric = metrics.get(index);
                int valueWidth = page.measure(metric.value());
                page.label(metric.label(), x + 8, top + 10, cardWidth - valueWidth - 24, page.text);
                page.text(metric.value(), x + cardWidth - valueWidth - 8, top + 10, page.muted);
            }
        }
    }

    private void requestStats(Minecraft minecraft) {
        var connection = minecraft.getConnection();
        if (connection != null) connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
    }

    private List<Metric> readStats(StatsCounter stats) {
        long distance = TRAVEL_STATS.stream().mapToLong(stat -> stats.getValue(Stats.CUSTOM, stat)).sum();
        long mined = 0, crafted = 0, blockUses = 0;
        for (var stat : Stats.BLOCK_MINED) mined += stats.getValue(stat);
        for (var stat : Stats.ITEM_CRAFTED) crafted += stats.getValue(stat);
        for (var stat : Stats.ITEM_USED) if (stat.getValue() instanceof BlockItem) blockUses += stats.getValue(stat);
        return List.of(new Metric("Distance travelled", formatDistance(distance / 100.0)),
            new Metric("Blocks mined", integers.format(mined)), new Metric("Block items used", integers.format(blockUses)),
            new Metric("Mobs killed", integers.format(stats.getValue(Stats.CUSTOM, Stats.MOB_KILLS))),
            new Metric("Jumps", integers.format(stats.getValue(Stats.CUSTOM, Stats.JUMP))),
            new Metric("Time played", StatFormatter.TIME.format(stats.getValue(Stats.CUSTOM, Stats.PLAY_TIME))),
            new Metric("Deaths", integers.format(stats.getValue(Stats.CUSTOM, Stats.DEATHS))),
            new Metric("Items crafted", integers.format(crafted)),
            new Metric("Damage dealt", StatFormatter.DIVIDE_BY_TEN.format(stats.getValue(Stats.CUSTOM, Stats.DAMAGE_DEALT))));
    }

    private void help(PlannerPage page) {
        int y = page.y;
        EditBox search = page.field("tools.help.query", "Search commands", 80, page.x, y, page.width);
        String query = query(search);
        resetScroll(query);
        y += 28;
        command = page.field("tools.help.command", "Command, e.g. goto 120 -30", 512, page.x, y, page.width - 63);
        page.button(page.x + page.width - 56, y, 56, CONTROL, "Run", !command.getValue().isBlank(), true, () -> runCommand(page));
        y += 30;
        page.label(helpResult == null ? "Enter runs a command. Up and down recall earlier commands." : helpResult,
            page.x, y, page.width, helpError ? page.warning : page.muted);
        y += 16;
        List<Command> filtered = COMMANDS.stream().filter(row -> matches(query, row.syntax(), row.description())).toList();
        if (filtered.isEmpty()) {
            page.text("No matching commands.", page.x, y, page.muted);
            return;
        }
        drawRows(page, y, filtered.size(), (index, top) -> {
            Command row = filtered.get(index);
            if (page.over(page.x + 1, top, page.width - 2, ROW - 1))
                page.graphics.fill(page.x + 1, top, page.x + page.width - 1, top + ROW - 1, page.selected);
            page.label(row.syntax(), page.x + 10, top + 6, page.width - 35, page.text);
            page.label(row.description(), page.x + 10, top + 19, page.width - 35, page.muted);
            page.text("›", page.x + page.width - 19, top + 11, page.muted);
            page.hit(page.x + 1, top, page.width - 2, ROW - 1, () -> command.setValue(row.input()));
        });
    }

    private void runCommand(PlannerPage page) {
        String input = command.getValue().strip();
        if (input.isEmpty()) return;
        String arguments = commandArguments(input);
        helpError = false;
        helpResult = null;
        if (commandHistory.isEmpty() || !commandHistory.getLast().equals(input)) commandHistory.add(input);
        if (commandHistory.size() > MAX_COMMAND_HISTORY) commandHistory.removeFirst();
        historyCursor = commandHistory.size();
        unfinishedCommand = "";
        if (page.minecraft.getConnection() == null) {
            commandFeedback("Join a world to run commands.", true);
            return;
        }
        if (dispatcher == null) {
            dispatcher = new CommandDispatcher<>();
            var root = LiteralArgumentBuilder.<ClientSuggestionProvider>literal(SopranoCommandTree.NAME);
            new SopranoCommandTree(page.module, message -> commandFeedback(message, false),
                message -> commandFeedback(message, true), syntax -> "." + SopranoCommandTree.NAME + " " + syntax).build(root);
            dispatcher.register(root);
        }
        try {
            int result = dispatcher.execute(SopranoCommandTree.NAME + (arguments.isEmpty() ? "" : " " + arguments),
                page.minecraft.getConnection().getSuggestionsProvider());
            if (result <= 0) return;
            command.setValue("");
            if (WORLD_ACTIONS.contains(arguments.split("\\s+", 2)[0]) || arguments.startsWith("sel fill ")
                || arguments.startsWith("sel shell ") || arguments.startsWith("sel replace") || arguments.equals("sel cleararea")) {
                page.close();
            } else if (arguments.startsWith("preview ")) page.show("Map");
        } catch (CommandSyntaxException invalid) {
            commandFeedback(invalid.getRawMessage().getString(), true);
        }
    }

    private void commandFeedback(String message, boolean error) {
        helpResult = message;
        helpError = error;
        SopranoClient.feedback(message);
    }

    private static String commandArguments(String input) {
        int offset = input.startsWith(".") ? 1 : 0;
        int end = input.indexOf(' ', offset);
        String name = input.substring(offset, end < 0 ? input.length() : end);
        if (name.equals(SopranoCommandTree.NAME) || name.equals(SopranoCommandTree.SHORT_NAME)) {
            return end < 0 ? "" : input.substring(end).strip();
        }
        return input;
    }

    private static void route(PlannerPage page, int x, Integer y, int z, boolean walk) {
        if (x < SopranoModule.MIN_XZ || x > SopranoModule.MAX_XZ || z < SopranoModule.MIN_XZ || z > SopranoModule.MAX_XZ)
            throw new IllegalArgumentException("Choose X and Z coordinates within the world border.");
        if (walk) {
            page.module.walkTo(x, y, z);
            page.close();
        } else {
            page.module.previewTo(x, y, z);
            page.show("Map");
        }
    }

    @FunctionalInterface
    private interface DrawRow { void draw(int index, int top); }

    private void drawRows(PlannerPage page, int top, int size, DrawRow content) {
        int visible = visibleRows(page, top, size);
        if (visible == 0) return;
        int first = scroll / ROW;
        int end = Math.min(size, first + visible);
        page.panel(page.x, top, page.width, (end - first) * ROW + 1);
        for (int index = first; index < end; index++) {
            int y = top + (index - first) * ROW;
            if (index > first) page.graphics.fill(page.x + 1, y, page.x + page.width - 1, y + 1, page.border);
            content.draw(index, y);
        }
        if (scrollLimit > 0) {
            int height = visible * ROW;
            int thumb = Math.max(12, height * visible / size);
            int offset = (height - thumb) * scroll / scrollLimit;
            page.graphics.fill(page.x + page.width - 3, top + offset, page.x + page.width - 1, top + offset + thumb, page.border);
        }
    }

    private int visibleRows(PlannerPage page, int top, int size) {
        listX = page.x;
        listY = top;
        listWidth = page.width;
        int rows = Math.max(0, (page.y + page.height - top - 2) / ROW);
        listHeight = Math.min(size, rows) * ROW;
        scrollLimit = Math.max(0, (size - rows) * ROW);
        scroll = Math.clamp(scroll, 0, scrollLimit);
        return rows;
    }

    private void resetScroll(String query) {
        if (query.equals(lastQuery)) return;
        lastQuery = query;
        scroll = 0;
    }

    private static boolean inWorld(PlannerPage page) {
        if (page.minecraft.level != null && page.minecraft.player != null) return true;
        page.text("Join a world to use this page.", page.x, page.y + 5, page.muted);
        return false;
    }

    private static Integer integer(EditBox box) {
        try {
            return Integer.valueOf(box.getValue().strip());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static boolean focused(EditBox box) { return box != null && box.visible && box.isFocused(); }

    private static String query(EditBox field) { return field.getValue().strip().toLowerCase(Locale.ROOT); }

    private static boolean matches(String query, String... fields) {
        if (query.isEmpty()) return true;
        for (String field : fields) if (field.toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }

    private static String worldName(Minecraft minecraft) {
        return minecraft.level.dimension().identifier().getPath().replace('_', ' ');
    }

    private static String targetName(Entity target) {
        if (target instanceof Player player) return player.getGameProfile().name();
        if (target instanceof ItemEntity item) return item.getItem().getHoverName().getString();
        return target.getDisplayName().getString();
    }

    private static String typeName(Entity target) {
        if (target instanceof Player) return "Player";
        if (target instanceof ItemEntity) return "Dropped item";
        return target.getType().getDescription().getString();
    }

    private static String markerKind(MapAnnotations.Marker marker) {
        return switch (marker.kind()) {
            case NOTE -> "Saved";
            case RESPAWN -> "Respawn";
            case DEATH -> "Death";
            case PORTAL -> "Portal";
        };
    }

    private static String age(TaskHistory.Entry entry) {
        Duration elapsed = Duration.between(entry.recordedAt(), Instant.now());
        if (elapsed.toMinutes() < 1) return "Just now";
        if (elapsed.toHours() < 1) return elapsed.toMinutes() + " min ago";
        if (elapsed.toDays() < 1) return elapsed.toHours() + " h ago";
        return elapsed.toDays() + " d ago";
    }

    private static double distanceSquared(Minecraft minecraft, BlockPos at) {
        return minecraft.player.position().distanceToSqr(at.getX() + .5, at.getY(), at.getZ() + .5);
    }

    private static String formatDistance(double distance) {
        if (distance < 1_000) return Math.round(distance) + " m";
        return String.format(Locale.ROOT, "%.1f km", distance / 1_000.0);
    }
}
