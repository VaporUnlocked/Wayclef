package dev.soprano.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.soprano.SopranoClient;
import dev.soprano.build.BlueprintBlockStates;
import dev.soprano.build.BuildLayers;
import dev.soprano.build.BuildPlan;
import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.EscapeController;
import dev.soprano.game.FollowController;
import dev.soprano.game.FootSurvey;
import dev.soprano.game.ItineraryController;
import dev.soprano.game.Navigation;
import dev.soprano.game.NetherFrame;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.game.StrongholdTracker;
import dev.soprano.game.RoutePermissions;
import dev.soprano.game.RouteBlockProtection;
import dev.soprano.game.RouteAudio;
import dev.soprano.game.VitalityPolicy;
import dev.soprano.game.WorldInteraction;
import dev.soprano.game.transport.ElytraReservePolicy;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.map.MapVolume;
import dev.soprano.map.MapAnnotations;
import dev.soprano.map.PortalLinks;
import dev.soprano.map.PortalTracker;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.RouteItinerary;
import dev.soprano.path.SafeReturnCheckpoints;
import dev.soprano.task.JobQueue;
import dev.soprano.task.TaskHistory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class SopranoModule {
    private final AutomationControls automation = new AutomationControls(this);
    private Supplier<String> lastActivityStatus;
    private ClientLevel lastActivityLevel;
    static final int MIN_XZ = -30_000_000;
    static final int MAX_XZ = 29_999_999;
    private static final int MIN_Y = -4_096;
    private static final int MAX_Y = 4_096;
    private static final Set<String> MOVEMENT_PREFERENCES = Set.of("profile", "transport", "arrival-radius", "follow-distance",
        "elytra-rocket-reserve", "elytra-durability-reserve", "avoid-mobs", "open-doors-and-gates", "close-behind",
        "face-path", "replan-on-obstruction", "diagonal-walking", "jump-up-steps", "max-drop", "search-range",
        "search-limit", "work-per-tick", "allow-partial-paths", "search-mode", "search-time-per-tick", "replan-attempts",
        "partial-route-legs", "jump-one-block-gaps", "pause-below-health", "pause-below-food", "adaptive-planning");

    private enum Profile {
        Cautious(new IncrementalSearch.Config(false, false, 0, 64, 8_000, false), 64),
        Balanced(new IncrementalSearch.Config(true, true, 1, 96, 12_000, false), 96),
        Explorer(new IncrementalSearch.Config(true, true, 1, 192, 28_000, true), 120),
        Custom(null, 0);

        final IncrementalSearch.Config search;
        final int workPerTick;

        Profile(IncrementalSearch.Config search, int workPerTick) {
            this.search = search;
            this.workPerTick = workPerTick;
        }
    }

    private final PlanningSettings.Store preferences = new PlanningSettings.Store(
        Minecraft.getInstance().gameDirectory.toPath().resolve("soprano").resolve("preferences.properties"));

    private final PlanningSettings.Value<Profile> profile = preferences.choice("profile",
        "Cautious walks cardinally. Balanced adds diagonals, jumps, and small drops. Explorer plans farther in loaded sections. Custom lets you set the limits.", Profile.Balanced);
    private final PlanningSettings.Value<TransportMode> transport = preferences.choice("transport",
        "Auto recognizes your current ride, glide, or swim. Select a mode to request it directly.", TransportMode.AUTO);
    private final PlanningSettings.Number arrivalRadius = preferences.number("arrival-radius",
        "How many horizontal blocks away a coordinate goal can be considered reached.", 0, 0, 6);
    private final PlanningSettings.Number crosshairKey = preferences.number("route-to-crosshair-key",
        "Start a route to the block under the in-game crosshair.", InputConstants.KEY_J, -1, 348);
    private final PlanningSettings.Number stopKey = preferences.number("stop-all-key",
        "Stop Wayclef movement and world interactions immediately.", -1, -1, 511);
    private final PlanningSettings.Number plannerKey = preferences.number("planner-key",
        "Open or close the Wayclef planner.", InputConstants.KEY_GRAVE, -1, 511);
    private final PlanningSettings.Value<Boolean> plannerKeyEnabled = preferences.toggle("planner-key-enabled",
        "Enable the keyboard shortcut for opening the planner.", true);
    private final PlanningSettings.Value<PlannerTheme> plannerTheme = preferences.choice("planner-theme",
        "Appearance of the Wayclef planner.", PlannerTheme.DARK);
    private final PlanningSettings.Number followDistance = preferences.number("follow-distance",
        "Target gap in blocks when following a visible player.", 3, 2, 32);
    private final PlanningSettings.Number rocketReserve = preferences.number("elytra-rocket-reserve",
        "Fireworks to retain at a planned landing.", ElytraReservePolicy.DEFAULT_RESERVES.rockets(), 0, 256);
    private final PlanningSettings.Number wingReserve = preferences.number("elytra-durability-reserve",
        "Equipped Elytra durability to retain at a planned landing.", ElytraReservePolicy.DEFAULT_RESERVES.durability(), 2, 432);
    private final PlanningSettings.Value<Boolean> avoidMobs = preferences.toggle("avoid-mobs",
        "Give detected mobs clearance when planning and following a route.", false);
    private final PlanningSettings.Value<Boolean> openDoors = preferences.toggle("open-doors-and-gates",
        "Open usable doors, gates, and wooden trapdoors when a route passes through them.", true);
    private final PlanningSettings.Value<Boolean> closeDoors = preferences.toggle("close-behind",
        "Restore doors, gates, and wooden trapdoors Wayclef opened once you have passed safely.", false);
    private final PlanningSettings.Value<Boolean> steerView = preferences.toggle("face-path",
        "Turn your view toward the next step while walking.", true);
    private final PlanningSettings.Value<Boolean> autoReplan = preferences.toggle("replan-on-obstruction",
        "Try another walking path when the current path is blocked.", true);
    private final PlanningSettings.Value<Boolean> diagonals = preferences.toggle("diagonal-walking",
        "Allow diagonal walking where both corners have clearance.", true);
    private final PlanningSettings.Value<Boolean> jumps = preferences.toggle("jump-up-steps",
        "Allow a normal jump onto a verified ledge up to one block high.", true);
    private final PlanningSettings.Number maxDrop = preferences.number("max-drop",
        "Maximum drop in blocks. Zero permits ordinary step-downs.", 1, 0, 3);
    private final PlanningSettings.Number searchRange = preferences.number("search-range",
        "Maximum horizontal distance in blocks from the start of each search.", 96, 16, 512);
    private final PlanningSettings.Number searchLimit = preferences.number("search-limit",
        "Maximum number of positions considered in one search.", 12_000, 2_000, 40_000);
    private final PlanningSettings.Number workPerTick = preferences.number("work-per-tick",
        "Upper limit on search expansions per tick. A lower value spreads planning over more frames.", 36, 8, 120);
    private final PlanningSettings.Value<Boolean> partial = preferences.toggle("allow-partial-paths",
        "Allow a verified partial path toward a goal beyond the current search.", false);
    private final PlanningSettings.Value<IncrementalSearch.SearchMode> searchMode = preferences.choice("search-mode",
        "Optimal looks for the lowest-cost route. Quick prioritizes reaching the goal sooner and may choose a longer route.", IncrementalSearch.SearchMode.OPTIMAL);
    private final PlanningSettings.Number planningBudget = preferences.number("search-time-per-tick",
        "Maximum planning time in each game tick. More time finishes searches sooner but can reduce frame rate.", 2, 1, 8);
    private final PlanningSettings.Number replanLimit = preferences.number("replan-attempts",
        "How many times Wayclef can plan around new obstructions before it stops.", 3, 0, 12);
    private final PlanningSettings.Number partialSegmentLimit = preferences.number("partial-route-legs",
        "Maximum number of loaded path sections Wayclef can join toward a distant destination.", 32, 1, 96);
    private final PlanningSettings.Value<Boolean> mapEntities = preferences.toggle("map-entities",
        "Show Minecraft entity models and highlight your player in the 3D map.", true);
    private final PlanningSettings.Value<Boolean> mapEntityNames = preferences.toggle("map-entity-names",
        "Show nearby entity names when zoomed in. Your player keeps its You label.", false);
    private final PlanningSettings.Number surveyCacheLimit = preferences.number("survey-cache-limit",
        "Disk limit in MiB per server, world, and dimension. Older survey tiles are removed when the cache exceeds the limit. Zero keeps all tiles.", 0, 0, 65_536);
    private final PlanningSettings.Number mapQuality = preferences.number("map-render-quality",
        "Adjust the 3D viewport resolution. Lower settings update faster on slower computers.", 100, 50, 150);
    private final PlanningSettings.Number mapSpeed = preferences.number("map-camera-speed",
        "Base movement speed for WASD in the map. Zooming out increases the movement range.", 20, 4, 64);
    private final PlanningSettings.Value<Boolean> showPath = preferences.toggle("show-path",
        "Draw the planned route on visible terrain. Amber marks jumps and blue marks drops.", true);
    private final PlanningSettings.Number displayRange = preferences.number("display-range",
        "Maximum distance in blocks to draw path segments.", 64, 16, 128);
    private final PlanningSettings.Value<Boolean> showStatus = preferences.toggle("show-status",
        "Show current navigation and recent results in the corner of the game view.", true);
    private final PlanningSettings.Number workRate = preferences.number("work-estimate-rate",
        "Assumed block operations per second for region and activity estimates.", 8, 1, 512);
    private final PlanningSettings.Number jobLookahead = preferences.number("job-lookahead",
        "How many block targets Wayclef can prepare or furnace batches Gather can have cooking at once.", 8, 1, 32);
    private final PlanningSettings.Value<Boolean> gapJumps = preferences.toggle("jump-one-block-gaps",
        "Allow a jump over a verified one-block gap from stable takeoff to stable landing.", true);
    private final PlanningSettings.Number defaultBreakLimit = preferences.number("route-break-limit",
        "Maximum block breaks for a route. Zero disables breaking.", 16, 0, 64);
    private final PlanningSettings.Number defaultPlaceLimit = preferences.number("route-placement-limit",
        "Maximum block placements for a route. Zero disables placement.", 16, 0, 64);
    private final PlanningSettings.Value<RouteBlockProtection> protectedRouteBlocks = preferences.parsed("protected-route-blocks",
        "Block IDs and #tags that route edits cannot mine.", RouteBlockProtection.NONE, RouteBlockProtection::parse);
    private final PlanningSettings.Number materialReserve = preferences.number("route-material-reserve",
        "Minimum stock of each support block material to keep in your inventory while routing.", 0, 0,
        RoutePermissions.MAX_MATERIAL_RESERVE);
    private final PlanningSettings.Number toolReserve = preferences.number("tool-durability-reserve",
        "Pause automated mining before the held tool drops below this many durability points. Zero disables the check.",
        1, 0, RoutePermissions.MAX_TOOL_RESERVE);
    private final PlanningSettings.Number minimumHealth = preferences.number("pause-below-health",
        "Pause travel below this many health points. Two points equal one heart. Zero disables the check.", 0, 0, 100);
    private final PlanningSettings.Number minimumFood = preferences.number("pause-below-food",
        "Pause travel below this many hunger points. Zero disables the check.", 0, 0, 20);
    private final PlanningSettings.Value<Boolean> adaptivePlanning = preferences.toggle("adaptive-planning",
        "Lower the planning work budget when the game is under load.", false);
    private final PlanningSettings.Value<Boolean> audioCues = preferences.toggle("route-audio-cues",
        "Play a short sound when a route arrives, pauses, or needs input.", false);
    private final PlanningSettings.Number cueVolume = preferences.number("route-cue-volume",
        "Volume of Wayclef route sounds relative to the game volume.", 50, 0, 100);
    private final PlanningSettings.Value<Boolean> patternedOverlays = preferences.toggle("patterned-route-overlays",
        "Distinguish movement types with symbols or patterns as well as color.", true);
    private final PlanningSettings.Value<Boolean> dedicatedGpu = preferences.toggle("dedicated-gpu-compute",
        "Use a dedicated Vulkan device for supported survey and route calculations when available.", true);

    private MovementProfiles movementProfiles;
    private SafeReturnCheckpoints.Scope preferenceScope;
    private Object preferenceConnection;
    private String hostedWorld = "";
    private String preferenceError;

    private String lastStatus = "";
    private int statusTicks;

    public void importLegacySettings(Map<String, String> values) {
        preferences.importLegacy(values);
    }

    public boolean showMapEntities() { return mapEntities.get(); }

    public boolean showMapEntityNames() { return mapEntityNames.get(); }

    public long surveyCacheLimitBytes() { return (long) surveyCacheLimit.get() << 20; }

    public int mapRenderQuality() { return mapQuality.get(); }

    public int mapMoveSpeed() { return mapSpeed.get(); }
    public boolean patternedOverlays() { return patternedOverlays.get(); }
    public boolean useDedicatedGpu() { return dedicatedGpu.get(); }
    public SafeReturnCheckpoints.Scope storageScope() { return namedScope(Navigation.currentScope()); }

    double estimateRate() { return workRate.get() / 4.0; }

    void adjustEstimateRate(int direction) {
        int editable = workRate.editable();
        int step = editable < 16 ? 1 : 4;
        workRate.set(Math.clamp(editable + step * direction, workRate.min, workRate.max));
    }

    public Navigation.Options options() {
        return options(routeBreakLimit(), routePlaceLimit());
    }

    int routeBreakLimit() { return defaultBreakLimit.get(); }
    int routePlaceLimit() { return defaultPlaceLimit.get(); }
    public int minimumToolDurability() { return toolReserve.get(); }
    public int jobLookahead() { return jobLookahead.get(); }
    int editableMaterialReserve() { return materialReserve.editable(); }
    int editableToolReserve() { return toolReserve.editable(); }

    void setEditReserves(int material, int tool) {
        if (material < 0 || material > materialReserve.max)
            throw new IllegalArgumentException("Choose a material reserve from 0 to " + materialReserve.max + ".");
        if (tool < 0 || tool > toolReserve.max)
            throw new IllegalArgumentException("Choose a tool durability reserve from 0 to " + toolReserve.max + ".");
        preferences.apply(Map.of(materialReserve.name, Integer.toString(material), toolReserve.name, Integer.toString(tool)),
            Set.of(materialReserve.name, toolReserve.name));
    }

    String editableProtectedRouteBlocks() { return protectedRouteBlocks.editable().toString(); }
    void setProtectedRouteBlocks(String text) { protectedRouteBlocks.set(RouteBlockProtection.parse(text)); }

    Navigation.Options options(int breakLimit, int placeLimit) {
        Profile selected = profile.get();
        IncrementalSearch.Config search = selected == Profile.Custom
            ? new IncrementalSearch.Config(diagonals.get(), jumps.get(), maxDrop.get(), searchRange.get(),
                searchLimit.get(), partial.get(), searchMode.get(), gapJumps.get())
            : new IncrementalSearch.Config(selected.search.diagonals(), selected.search.jumping(),
                selected.search.maxDrop(), selected.search.horizontalRange(), selected.search.maxVisited(),
                selected.search.allowPartial(), searchMode.get(), gapJumps.get());
        int expansions = selected == Profile.Custom ? workPerTick.get() : selected.workPerTick;
        return new Navigation.Options(search, expansions, steerView.get(), autoReplan.get(),
            planningBudget.get(), replanLimit.get(), partialSegmentLimit.get(), transport.get(),
            new ElytraReservePolicy.Reserves(rocketReserve.get(), wingReserve.get()), avoidMobs.get(),
            new RoutePermissions(breakLimit, placeLimit, openDoors.get(), closeDoors.get(),
                protectedRouteBlocks.get(), materialReserve.get(), toolReserve.get()),
            new VitalityPolicy.Minimums(minimumHealth.get(), minimumFood.get()), adaptivePlanning.get());
    }

    public List<PlanningSettings.Control> controls(String tab) {
        var profileControl = PlanningSettings.cycle(profile, Profile.values());
        Profile edited = profile.editable();
        boolean custom = edited == Profile.Custom;
        return switch (tab.toLowerCase(Locale.ROOT)) {
            case "navigation" -> List.of(PlanningSettings.cycle(transport, TransportMode.values()), profileControl,
                PlanningSettings.number(arrivalRadius, 1), PlanningSettings.number(followDistance, 1),
                PlanningSettings.toggle(steerView), PlanningSettings.toggle(autoReplan),
                PlanningSettings.number(defaultBreakLimit, 1), PlanningSettings.number(defaultPlaceLimit, 1),
                PlanningSettings.number(rocketReserve, 1), PlanningSettings.number(wingReserve, 1),
                PlanningSettings.number(minimumHealth, 1), PlanningSettings.number(minimumFood, 1));
            case "terrain" -> custom
                ? List.of(profileControl, PlanningSettings.toggle(diagonals), PlanningSettings.toggle(jumps),
                    PlanningSettings.number(maxDrop, 1), PlanningSettings.toggle(avoidMobs),
                    PlanningSettings.toggle(openDoors), PlanningSettings.toggle(closeDoors), PlanningSettings.toggle(gapJumps))
                : List.of(profileControl, PlanningSettings.readOnly(diagonals, PlanningSettings.onOff(edited.search.diagonals())),
                    PlanningSettings.readOnly(jumps, PlanningSettings.onOff(edited.search.jumping())),
                    PlanningSettings.readOnly(maxDrop, Integer.toString(edited.search.maxDrop())),
                    PlanningSettings.toggle(avoidMobs), PlanningSettings.toggle(openDoors), PlanningSettings.toggle(closeDoors),
                    PlanningSettings.toggle(gapJumps));
            case "planning" -> List.of(profileControl, PlanningSettings.toggle(adaptivePlanning),
                PlanningSettings.cycle(searchMode, IncrementalSearch.SearchMode.values()),
                PlanningSettings.number(planningBudget, 1, n -> n + " ms"),
                autoReplan.editable() ? PlanningSettings.number(replanLimit, 1) : PlanningSettings.readOnly(replanLimit, "Off"),
                custom ? PlanningSettings.number(searchRange, 8) : PlanningSettings.readOnly(searchRange, Integer.toString(edited.search.horizontalRange())),
                custom ? PlanningSettings.number(searchLimit, 1000) : PlanningSettings.readOnly(searchLimit, Integer.toString(edited.search.maxVisited())),
                custom ? PlanningSettings.number(workPerTick, 4) : PlanningSettings.readOnly(workPerTick, Integer.toString(edited.workPerTick)),
                custom ? PlanningSettings.toggle(partial) : PlanningSettings.readOnly(partial, PlanningSettings.onOff(edited.search.allowPartial())),
                (custom ? partial.editable() : edited.search.allowPartial()) ? PlanningSettings.number(partialSegmentLimit, 4)
                    : PlanningSettings.readOnly(partialSegmentLimit, "Off"));
            case "activities" -> List.of(PlanningSettings.number(jobLookahead, 1),
                PlanningSettings.number(workRate, 4, n -> String.format(Locale.ROOT, "%.2f blk/s", n / 4.0)));
            case "display" -> List.of(PlanningSettings.toggle(mapEntities), PlanningSettings.toggle(mapEntityNames),
                PlanningSettings.number(surveyCacheLimit, 64, n -> n == 0 ? "Unlimited" : n + " MiB"),
                PlanningSettings.number(mapQuality, 10, n -> n + "%"), PlanningSettings.number(mapSpeed, 4, n -> n + " blk/s"),
                PlanningSettings.toggle(showPath), PlanningSettings.number(displayRange, 8), PlanningSettings.toggle(showStatus),
                PlanningSettings.toggle(patternedOverlays), PlanningSettings.toggle(audioCues),
                PlanningSettings.number(cueVolume, 5, n -> n + "%"), PlanningSettings.toggle(dedicatedGpu));
            default -> List.of();
        };
    }

    List<String> movementPresetNames() { return profiles().names(); }
    void saveMovementPreset(String name) { profiles().savePreset(name, preferences.snapshot(MOVEMENT_PREFERENCES)); }
    void applyMovementPreset(String name) { preferences.apply(profiles().loadPreset(name), MOVEMENT_PREFERENCES); }
    void deleteMovementPreset(String name) { profiles().deletePreset(name); }

    String copyMovementPreset(String name) {
        return name == null
            ? MovementProfiles.encodePreset("Active movement", preferences.snapshot(MOVEMENT_PREFERENCES))
            : MovementProfiles.encodePreset(name, profiles().loadPreset(name));
    }

    MovementProfiles.Preset readMovementPreset(String text) { return MovementProfiles.decodePreset(text); }

    Map<String, String> supportedPresetSettings(MovementProfiles.Preset preset) {
        return preferences.validated(preset.settings(), MOVEMENT_PREFERENCES);
    }

    void importMovementPreset(String name, MovementProfiles.Preset preset) {
        Map<String, String> settings = supportedPresetSettings(preset);
        if (settings.isEmpty()) throw new IllegalArgumentException("This preset has no supported settings.");
        profiles().savePreset(name, settings);
    }

    boolean hasPreferenceScope() { return preferences.hasScope(); }
    boolean editingWorldDefaults() { return preferences.editingScope(); }
    void editWorldDefaults(boolean enabled) { preferences.editScope(enabled); }
    String preferenceError() { return preferenceError; }
    String hostedWorld() { return hostedWorld; }

    boolean supportsHostedWorldName() {
        var scope = Navigation.currentScope();
        return scope != null && scope.world().equals("server-world");
    }

    void setHostedWorld(String name) {
        if (!supportsHostedWorldName()) throw new IllegalStateException("Join the hosted world first.");
        String clean = name.strip();
        if (!clean.isEmpty() && !clean.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,39}"))
            throw new IllegalArgumentException("Use up to 40 letters, numbers, spaces, underscores, or hyphens.");
        hostedWorld = clean;
        updatePreferenceScope();
    }

    void clearWorldDefaults() {
        if (preferenceScope == null || !preferences.hasScope()) throw new IllegalStateException("Join the world first.");
        profiles().clearDefaults(preferenceScope);
        loadPreferenceScope(preferenceScope);
    }

    private MovementProfiles profiles() {
        if (movementProfiles == null) movementProfiles = MovementProfiles.open();
        return movementProfiles;
    }

    private void updatePreferenceScope() {
        Object connection = Minecraft.getInstance().getConnection();
        if (connection != preferenceConnection) {
            preferenceConnection = connection;
            hostedWorld = "";
        }
        var scope = storageScope();
        if (!Objects.equals(preferenceScope, scope)) loadPreferenceScope(scope);
    }

    private SafeReturnCheckpoints.Scope namedScope(SafeReturnCheckpoints.Scope scope) {
        if (scope != null && scope.world().equals("server-world") && !hostedWorld.isEmpty()) {
            return new SafeReturnCheckpoints.Scope(scope.server(), "named/" + hostedWorld.toLowerCase(Locale.ROOT), scope.dimension());
        }
        return scope;
    }

    private void loadPreferenceScope(SafeReturnCheckpoints.Scope scope) {
        preferenceScope = scope;
        preferenceError = null;
        try {
            SafeReturnCheckpoints.Scope serverDefault = scope != null && scope.world().startsWith("named/")
                ? new SafeReturnCheckpoints.Scope(scope.server(), "server-world", scope.dimension()) : null;
            preferences.openScope(serverDefault == null ? Map.of() : profiles().loadDefaults(serverDefault),
                scope == null ? Map.of() : profiles().loadDefaults(scope),
                scope == null ? null : overrides -> profiles().saveDefaults(scope, overrides));
        } catch (IllegalArgumentException | IllegalStateException problem) {
            preferences.openScope(Map.of(), null);
            preferenceError = problem.getMessage();
            SopranoClient.LOG.warn("Could not read Soprano world defaults", problem);
        }
    }

    public void walkTo(int x, Integer y, int z) {
        request(coordinateGoal(x, y, z), true);
    }

    public void previewTo(int x, Integer y, int z) {
        request(coordinateGoal(x, y, z), false);
    }

    void routeToPortal(BlockPos position, boolean walk) {
        var requested = options(0, 0).withTransport(TransportMode.WALK);
        request(Navigation.get().goalNearPortal(position), walk, requested);
    }

    String markPortal(BlockPos position) {
        Minecraft client = Minecraft.getInstance();
        BlockPos anchor = PortalTracker.mark(client, position);
        PortalLinks.Destination destination = PortalTracker.isNether(client, anchor) ? PortalLinks.destination(client, anchor) : null;
        return "Marked portal at " + anchor.toShortString() + (destination == null ? "." : ". " + destination.label() + ".");
    }

    void enterPortal(BlockPos position) {
        Minecraft client = Minecraft.getInstance();
        BlockPos anchor = PortalTracker.isNether(client, position) ? position : PortalTracker.mark(client, position);
        var scope = storageScope();
        var destination = PortalTracker.isNether(client, anchor) ? PortalLinks.destination(client, anchor) : null;
        if (scope == null || destination == null) throw new IllegalArgumentException("Choose a Nether portal with an Overworld or Nether destination.");
        var at = destination.position();
        var area = new Goal(at.getX(), destination.projected() ? null : at.getY() * 16, at.getZ(), destination.radius());
        var entry = new Goal(anchor.getX(), anchor.getY() * 16, anchor.getZ(), 0);
        var stop = new RouteItinerary.Stop(scope.dimension(), entry, 0, new RouteItinerary.PortalExit(destination.dimension(), area));
        startTrip(new RouteItinerary("Nether portal crossing", List.of(stop), RouteItinerary.Repeat.ONCE), options(0, 0));
    }

    void approachStronghold() {
        BlockPos estimate = StrongholdTracker.get().estimate();
        request(coordinateGoal(estimate.getX(), null, estimate.getZ()), true);
    }

    String markStronghold() {
        BlockPos estimate = StrongholdTracker.get().estimate();
        MapAnnotations.saveNote(Minecraft.getInstance(), estimate, "Estimated stronghold column");
        return "Saved the estimated stronghold column " + estimate.getX() + ", " + estimate.getZ() + ".";
    }

    void sitOnCushion(String colorName) {
        ensureActivityIdle();
        DyeColor color = colorName == null ? null : DyeColor.byName(colorName.toLowerCase(Locale.ROOT), null);
        if (colorName != null && color == null) throw new IllegalArgumentException("Unknown cushion color " + colorName + ".");
        WorldInteraction.get().sit(color, options(0, 0));
        refreshAfterRequest();
        rememberActivity(WorldInteraction.get()::statusText);
    }

    void routeByCushion(BlockPos destination) {
        int y = destination.getY();
        if (y < MIN_Y || y > MAX_Y) throw new IllegalArgumentException("Choose a supported destination height.");
        var goal = new Goal(destination.getX(), y * 16, destination.getZ(), 0);
        request(goal, true, options(0, 0).withTransport(TransportMode.CUSHION));
    }

    void buildPortal(BlockPos opening, Direction.Axis axis) {
        ensureActivityIdle();
        AutomationControls.player();
        BuildPlan plan = NetherFrame.plan(Minecraft.getInstance().level, opening, axis);
        startConstruction(plan, BuildLayers.Direction.BOTTOM_UP, plan.size(), 8, Blocks.DIRT);
    }

    void lightPortal(BlockPos opening) {
        ensureActivityIdle();
        WorldInteraction.get().ignite(opening, options(0, 0));
        refreshAfterRequest();
        rememberActivity(WorldInteraction.get()::statusText);
    }

    void fillEndPortal(BlockPos frame) {
        ensureActivityIdle();
        WorldInteraction.get().fillEndPortal(frame, options(0, 0));
        refreshAfterRequest();
        rememberActivity(WorldInteraction.get()::statusText);
    }

    void hopGateway(BlockPos gateway) {
        ensureActivityIdle();
        WorldInteraction.get().hopGateway(gateway, options(0, 0));
        refreshAfterRequest();
        rememberActivity(WorldInteraction.get()::statusText);
    }

    public void selectBlock(int x, int y, int z, boolean walk) {
        request(Navigation.get().goalAtBlock(new BlockPos(x, y, z)), walk);
    }

    void startReplace(MapVolume.Box region, BlockPos source) {
        ensureActivityIdle();
        ReplaceActivity.get().start(region, source);
        refreshAfterRequest();
        rememberActivity(ReplaceActivity.get()::statusText);
    }

    void startConstruction(BuildPlan plan, BuildLayers.Direction direction, int placements, int scaffold) {
        startConstruction(plan, direction, placements, scaffold, null);
    }

    private void startConstruction(BuildPlan plan, BuildLayers.Direction direction, int placements, int scaffold, Block scaffoldMaterial) {
        ensureActivityIdle();
        BlueprintConstruction.get().start(plan, direction, placements, scaffold, scaffoldMaterial);
        refreshAfterRequest();
        rememberActivity(BlueprintConstruction.get()::statusText);
        String dimension = SopranoHistory.dimension();
        if (dimension != null) {
            var settings = new HashMap<>(Map.of("order", direction.name(), "placements", Integer.toString(placements),
                "scaffolds", Integer.toString(scaffold)));
            if (scaffoldMaterial != null) settings.put("scaffold-material", BuiltInRegistries.BLOCK.getKey(scaffoldMaterial).toString());
            SopranoHistory.record(new TaskHistory.Task("build", dimension, plan.size() + " planned blocks", settings), plan);
        }
    }

    void cleanupConstruction() {
        ensureActivityIdle();
        BlueprintConstruction.get().cleanup();
        refreshAfterRequest();
        rememberActivity(BlueprintConstruction.get()::statusText);
    }

    void startJobs() {
        ensureActivityIdle();
        String dimension = SopranoHistory.dimension();
        TaskHistory.Task snapshot = dimension == null ? null : JobQueue.get().taskSnapshot(dimension);
        JobQueue.get().start();
        refreshAfterRequest();
        rememberActivity(JobQueue.get()::statusText);
        if (snapshot != null) SopranoHistory.record(snapshot, null);
    }

    void startSingleJob(String command, MapVolume.Box region, BlockPos target) {
        ensureActivityIdle();
        TaskHistory.Task snapshot = JobQueue.get().startSingle(command, region, target);
        refreshAfterRequest();
        rememberActivity(JobQueue.get()::statusText);
        SopranoHistory.record(snapshot, null);
    }

    void startJobs(List<String> commands) {
        ensureActivityIdle();
        TaskHistory.Task snapshot = JobQueue.get().startBatch(commands);
        refreshAfterRequest();
        rememberActivity(JobQueue.get()::statusText);
        SopranoHistory.record(snapshot, null);
    }

    AutomationControls automation() { return automation; }

    public void resume() {
        if (!Navigation.get().active() && ItineraryController.get().canResume()) resumeTrip();
        else Navigation.get().resume();
    }

    Goal coordinateGoal(int x, Integer y, int z) {
        if (y != null && (y < MIN_Y || y > MAX_Y)) {
            throw new IllegalArgumentException("Goal height must be between " + MIN_Y + " and " + MAX_Y + ".");
        }
        return new Goal(x, y == null ? null : y * 16, z, arrivalRadius.get());
    }

    void request(Goal goal, boolean walk) {
        request(goal, walk, options());
    }

    public void routeToCrosshair() {
        request(Navigation.get().goalAtCrosshair(), true);
    }

    public void routeToSurface() {
        var requested = options().withTransport(TransportMode.WALK);
        request(Navigation.get().surfaceGoal(requested.search().horizontalRange()), true, requested);
    }

    public boolean isCrosshairKey(KeyEvent event) { return crosshairKey.get() == event.key() && event.modifiers() == 0; }

    public boolean isOpenKey(KeyEvent event) { return openKeyEnabled() && plannerKey.global() == event.key(); }

    PlannerTheme plannerTheme() { return plannerTheme.global(); }
    void setPlannerTheme(PlannerTheme value) { plannerTheme.setGlobal(value); }
    boolean hasOpenKey() { return plannerKey.global() >= 0; }
    boolean openKeyEnabled() { return hasOpenKey() && plannerKeyEnabled.global(); }
    void toggleOpenKey() { if (hasOpenKey()) plannerKeyEnabled.setGlobal(!plannerKeyEnabled.global()); }
    String openKeyName() {
        int key = plannerKey.global();
        if (key < 0) return "Unbound";
        if (key == InputConstants.KEY_GRAVE) return "Grave (`)";
        return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
    }

    void bindOpenKey(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_BACKSPACE) { plannerKey.setGlobal(-1); return; }
        if (key < 0 || key > plannerKey.max || InputConstants.getKey(event).equals(InputConstants.UNKNOWN))
            throw new IllegalArgumentException("Choose a keyboard key for the planner.");
        if (key == stopKey.global() || key == crosshairKey.get())
            throw new IllegalArgumentException("That key already runs a Wayclef action.");
        plannerKey.setGlobal(key);
        if (!plannerKeyEnabled.global()) plannerKeyEnabled.setGlobal(true);
    }

    public boolean isStopKey(KeyEvent event) {
        int key = stopKey.global();
        return key >= 0 && key == event.key() && running();
    }

    String stopKeyLabel() {
        int key = stopKey.global();
        return key < 0 ? "Unbound" : InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
    }

    void clearStopKey() { stopKey.setGlobal(-1); }

    void bindStopKey(KeyEvent event) {
        int key = event.key();
        if (key < 0 || key > stopKey.max || InputConstants.getKey(event).equals(InputConstants.UNKNOWN)
            || key == InputConstants.KEY_ESCAPE || key == plannerKey.global()
            || key >= InputConstants.KEY_LCONTROL && key <= InputConstants.KEY_RGUI) {
            throw new IllegalArgumentException("Choose a key other than Escape, the planner key, or a modifier.");
        }
        stopKey.setGlobal(key);
    }

    void request(Goal goal, boolean walk, Navigation.Options requested) {
        ensureActivityIdle();
        FollowController.get().stop();
        Navigation.get().request(goal, walk, requested);
        refreshAfterRequest();
        String destination = goal.skyExposed() ? "Nearest reachable surface" : "X " + goal.x()
            + (goal.y16() == null ? "" : " Y " + goal.y16() / 16.0) + " Z " + goal.z();
        if (walk) SopranoHistory.record("route", destination,
            Map.of("goal", HistorySettings.goal(goal), "navigation", HistorySettings.navigation(requested)));
    }

    public void followPlayer(String name) {
        followPlayer(name, followDistance.get(), options());
    }

    void followEntity(net.minecraft.world.entity.Entity target) {
        if (target instanceof net.minecraft.world.entity.player.Player player) {
            followPlayer(player.getGameProfile().name());
            return;
        }
        ensureActivityIdle();
        FollowController.get().follow(target, followDistance.get(), options());
        refreshAfterRequest();
    }

    void followNearest(boolean players) {
        ensureActivityIdle();
        FollowController.get().followNearest(players, followDistance.get(), options());
        refreshAfterRequest();
    }

    private void followPlayer(String name, double distance, Navigation.Options requested) {
        ensureActivityIdle();
        FollowController.get().follow(name, distance, requested);
        refreshAfterRequest();
        SopranoHistory.record("follow", name, Map.of("distance", Double.toString(distance), "navigation", HistorySettings.navigation(requested)));
    }

    void startFootSurvey(MapVolume.Box region) {
        ensureActivityIdle();
        FollowController.get().stop();
        FootSurvey.get().start(region, options());
        refreshAfterRequest();
    }

    void detourTrip(BlockPos target) { detour(Navigation.get().goalAtBlock(target)); }

    public void detourToCrosshair() { detour(Navigation.get().goalAtCrosshair()); }

    private void detour(Goal goal) {
        ensureOtherActivitiesIdle();
        ItineraryController.get().detour(goal, options());
        FollowController.get().stop();
        refreshAfterRequest();
    }

    void startTrip(RouteItinerary trip, Navigation.Options requested) {
        ensureActivityIdle();
        FollowController.get().stop();
        RouteItinerary connected = PortalLinks.connectTrip(Minecraft.getInstance(), trip);
        ItineraryController.get().start(connected, requested);
        refreshAfterRequest();
        SopranoHistory.recordTrip(connected, requested);
    }

    void resumeTrip() {
        ensureActivityIdle();
        FollowController.get().stop();
        ItineraryController.get().resume(options());
        refreshAfterRequest();
    }

    void reviewHistory(TaskHistory.Entry selected, boolean start) {
        TaskHistory.Entry entry = SopranoHistory.reopen(selected.id());
        TaskHistory.Task task = entry.task();
        if (!task.dimension().equals(SopranoHistory.dimension())) throw new IllegalStateException("Enter " + task.dimension() + " first.");
        Map<String, String> settings = task.settings();
        switch (task.kind()) {
            case "route" -> request(HistorySettings.goal(settings.get("goal")), start, HistorySettings.navigation(settings.get("navigation")));
            case "follow" -> {
                double distance = Double.parseDouble(settings.get("distance"));
                Navigation.Options requested = HistorySettings.navigation(settings.get("navigation"));
                if (start) followPlayer(task.goal(), distance, requested);
                else request(FollowController.get().previewGoal(task.goal(), distance), false, requested);
            }
            case "build" -> {
                if (start) {
                    String material = settings.get("scaffold-material");
                    var state = material == null ? null : BlueprintBlockStates.resolve(material);
                    if (material != null && state == null) throw new IllegalStateException("The saved scaffold block is unavailable.");
                    startConstruction(SopranoHistory.blueprint(entry), BuildLayers.Direction.valueOf(settings.get("order")),
                        Integer.parseInt(settings.get("placements")), Integer.parseInt(settings.get("scaffolds")),
                        state == null ? null : state.getBlock());
                }
            }
            case "job-queue" -> {
                ensureActivityIdle();
                JobQueue.get().restore(task);
                if (start) startJobs();
            }
            case "trip" -> {
                if (start) startTrip(SopranoHistory.trip(entry), HistorySettings.navigation(settings.get("navigation")));
            }
            default -> throw new IllegalStateException("This saved task type is unavailable.");
        }
    }

    static void ensureActivityIdle() {
        ensureOtherActivitiesIdle();
        if (ItineraryController.get().active()) throw new IllegalStateException("Stop the current Wayclef trip first.");
    }

    private static void ensureOtherActivitiesIdle() {
        if (ReplaceActivity.get().active() || BlueprintConstruction.get().active() || JobQueue.get().active()
            || FootSurvey.get().active() || WorldInteraction.get().active() || EscapeController.get().active()) {
            throw new IllegalStateException("Stop the current Wayclef activity first.");
        }
    }

    void refreshAfterRequest() {
        lastActivityStatus = null;
        lastActivityLevel = null;
        SopranoClient.navigationStarted();
    }

    void escape(EscapeController.Choice choice) {
        EscapeController escape = EscapeController.get();
        escape.start(choice, true);
        try {
            automation.cancelImport();
            WorldInteraction.get().stop();
            FootSurvey.get().pause();
            ReplaceActivity.get().stop();
            BlueprintConstruction.get().stop();
            JobQueue.get().stop();
            FollowController.get().stop();
            if (ItineraryController.get().active()) ItineraryController.get().pause();
            if (Navigation.get().active()) Navigation.get().pause();
        } catch (RuntimeException failure) {
            escape.stop();
            throw failure;
        }
        refreshAfterRequest();
        rememberActivity(escape::statusText);
    }

    private void rememberActivity(Supplier<String> status) {
        lastActivityStatus = status;
        lastActivityLevel = Minecraft.getInstance().level;
    }

    public String statusText() {
        if (EscapeController.get().active()) return EscapeController.get().statusText();
        if (WorldInteraction.get().active()) return WorldInteraction.get().statusText();
        if (FootSurvey.get().active()) return FootSurvey.get().statusText();
        if (ReplaceActivity.get().active()) return ReplaceActivity.get().statusText();
        if (BlueprintConstruction.get().active()) return BlueprintConstruction.get().statusText();
        if (JobQueue.get().active()) return JobQueue.get().statusText();
        if (ItineraryController.get().active()) return ItineraryController.get().statusText();
        FollowController follow = FollowController.get();
        if (follow.active()) return follow.statusText();
        if (lastActivityStatus != null && lastActivityLevel == Minecraft.getInstance().level && !Navigation.get().active()) {
            return lastActivityStatus.get();
        }
        return Navigation.get().statusText();
    }

    public void pause() {
        if (EscapeController.get().active()) {
            EscapeController.get().stop();
            return;
        }
        if (WorldInteraction.get().active()) {
            WorldInteraction.get().stop();
            return;
        }
        if (FootSurvey.get().active()) {
            FootSurvey.get().pause();
            return;
        }
        if (ItineraryController.get().active()) {
            ItineraryController.get().pause();
            return;
        }
        FollowController follow = FollowController.get();
        if (follow.active()) {
            follow.stop();
        } else {
            Navigation.get().pause();
        }
    }

    public void stop() {
        EscapeController.get().stop();
        automation.cancelImport();
        WorldInteraction.get().stop();
        FootSurvey.get().stop();
        ReplaceActivity.get().stop();
        BlueprintConstruction.get().stop();
        JobQueue.get().stop();
        FollowController.get().stop();
        Navigation.get().stop();
        if (ItineraryController.get().active() || ItineraryController.get().canResume()) ItineraryController.get().cancel();
    }

    public void onDeactivate() {
        EscapeController.get().stop();
        automation.cancelImport();
        WorldInteraction.get().stop();
        FootSurvey.get().stop();
        ReplaceActivity.get().stop();
        BlueprintConstruction.get().stop();
        JobQueue.get().stop();
        if (ItineraryController.get().active()) ItineraryController.get().pause();
        FollowController.get().stop();
        Navigation.get().stopLocal();
    }

    public String getInfoString() {
        return running() ? profile.get().name() : null;
    }

    public void afterTick() {
        updatePreferenceScope();
        automation.tick();
        WorldInteraction.get().tick();
        if (lastActivityLevel != Minecraft.getInstance().level) {
            lastActivityStatus = null;
            lastActivityLevel = null;
        }
        RouteAudio.setEnabled(audioCues.get());
        RouteAudio.setVolume(cueVolume.get() / 100f);
        String status = statusText();
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            statusTicks = 100;
        } else if (statusTicks > 0) {
            statusTicks--;
        }
    }

    boolean running() {
        return Navigation.get().active() || FollowController.get().active() || ReplaceActivity.get().active()
            || BlueprintConstruction.get().active() || JobQueue.get().active() || ItineraryController.get().active()
            || FootSurvey.get().active() || WorldInteraction.get().active() || EscapeController.get().active();
    }

    public void drawStatus(GuiGraphicsExtractor graphics, int screenWidth) {
        Minecraft mc = Minecraft.getInstance();
        if (!showStatus.get() || mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
        if (!running() && statusTicks == 0) return;
        int maxWidth = Math.min(340, screenWidth - 26);
        if (maxWidth < 24) return;
        String text = "Wayclef  " + statusText();
        if (mc.font.width(text) > maxWidth) text = mc.font.plainSubstrByWidth(text, maxWidth - 9) + "...";
        Navigation.TravelProgress travel = Navigation.get().travelProgress();
        String instruction = travel == null || !Navigation.get().active() ? null : travel.instruction() + "  •  "
            + Math.round(travel.remainingBlocks()) + " blocks" + (travel.partial() ? " confirmed" : "")
            + (Double.isFinite(travel.etaSeconds()) ? "  •  " + Math.round(travel.etaSeconds()) + "s" : "");
        if (instruction != null && mc.font.width(instruction) > maxWidth) instruction = mc.font.plainSubstrByWidth(instruction, maxWidth - 9) + "...";
        int right = 15 + Math.max(mc.font.width(text), instruction == null ? 0 : mc.font.width(instruction));
        int bottom = instruction == null ? 23 : 34;
        graphics.fill(8, 8, right, bottom, 0xC5182423);
        graphics.fill(8, 8, 10, bottom, 0xFF6BDFB9);
        graphics.text(mc.font, text, 13, 12, 0xFFF0FAF8, false);
        if (instruction != null) graphics.text(mc.font, instruction, 13, 23, 0xFFB4D2CB, false);
    }

    public boolean showPath() { return showPath.get(); }
    public int displayRange() { return displayRange.get(); }
}
