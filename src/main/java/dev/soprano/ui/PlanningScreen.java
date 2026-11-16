package dev.soprano.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.soprano.build.BlueprintActions;
import dev.soprano.build.BlueprintImport;
import dev.soprano.build.BlueprintStorage;
import dev.soprano.build.BuildLayers;
import dev.soprano.build.BuildPalette;
import dev.soprano.build.BuildPlan;
import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.BuildPreview;
import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.ItineraryController;
import dev.soprano.game.Navigation;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.game.RouteBlockProtection;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.map.MapAnnotations;
import dev.soprano.map.MapVolume;
import dev.soprano.map.NamedRegions;
import dev.soprano.map.PortalCoordinates;
import dev.soprano.map.RouteElevation;
import dev.soprano.map.VoxelRenderer.Hit;
import dev.soprano.path.Goal;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.RouteItinerary;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import dev.soprano.path.Step;
import dev.soprano.task.JobQueue;
import dev.soprano.task.TaskHistory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class PlanningScreen extends Screen {
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 23;
    private static final int BUTTON_HEIGHT = 18;
    private static final int SETTING_HEIGHT = 41;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault());

    private enum Tab {
        MAP("Map"), GET("Get"), GOTO("Go to"), FOLLOW("Follow"), WAYPOINTS("Waypoints"), SCHEMATICS("Schematics"),
        STATS("Stats"), HELP("Help"), SETTINGS("Settings"), ADVANCED("Advanced"),
        BUILD("Build"), LOCATIONS("Locations"), ACTIVITIES("Activities"), TRIPS("Trips"),
        HISTORY("History"), NAVIGATION("Navigation"), TERRAIN("Terrain"), PLANNING("Planning"), PROFILES("Profiles"), DISPLAY("Display");

        final String label;

        Tab(String label) { this.label = label; }

        boolean isAdvanced() { return ordinal() > ADVANCED.ordinal(); }
    }

    private record ToolPage(Tab tab, String description) { }
    private static final List<Tab> RAIL = List.of(Tab.MAP, Tab.GET, Tab.GOTO, Tab.FOLLOW, Tab.WAYPOINTS,
        Tab.SCHEMATICS, Tab.STATS, Tab.HELP, Tab.SETTINGS, Tab.ADVANCED);
    private static final Map<Tab, Long> RAIL_ICONS = Map.of(Tab.MAP, 0x7F41495563417FL, Tab.GET, 0x1C22495551413EL,
        Tab.GOTO, 0x0F05090F182042L, Tab.FOLLOW, 0x1C22221C00227FL, Tab.WAYPOINTS, 0x7E42427E404040L,
        Tab.SCHEMATICS, 0x7F49557F51477FL, Tab.STATS, 0x0101090949497FL, Tab.HELP, 0x1C220204080008L,
        Tab.SETTINGS, 0x142A635D632A14L, Tab.ADVANCED, 0x00000049000000L);
    private static final List<ToolPage> ADVANCED_PAGES = List.of(new ToolPage(Tab.BUILD, "Edit a region or blueprint"),
        new ToolPage(Tab.ACTIVITIES, "Job queue and Replace"), new ToolPage(Tab.TRIPS, "Multi-stop routes"),
        new ToolPage(Tab.HISTORY, "Review and rerun past jobs"), new ToolPage(Tab.LOCATIONS, "Saved terrain surveys"),
        new ToolPage(Tab.NAVIGATION, "Route goals and transport"), new ToolPage(Tab.TERRAIN, "Terrain paths and protection"),
        new ToolPage(Tab.PLANNING, "Route planning limits"), new ToolPage(Tab.PROFILES, "Save movement presets"),
        new ToolPage(Tab.DISPLAY, "Map, overlays and stop key"));

    private record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }

        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        Rect intersection(Rect other) {
            int left = Math.max(x, other.x);
            int top = Math.max(y, other.y);
            return new Rect(left, top, Math.max(0, Math.min(right(), other.right()) - left),
                Math.max(0, Math.min(bottom(), other.bottom()) - top));
        }
    }

    private record Action(Rect area, Runnable run) { }
    private record Replacement(MapVolume.Box region, BlockPos source, ReplaceActivity.Preview preview) { }


    private enum PresetTransfer { COPY, PASTE }

    private enum RegionFace {
        MIN_X("−X", 0, false), MAX_X("+X", 0, true), MIN_Y("−Y", 1, false),
        MAX_Y("+Y", 1, true), MIN_Z("−Z", 2, false), MAX_Z("+Z", 2, true);

        final String label;
        final int axis;
        final boolean upper;

        RegionFace(String label, int axis, boolean upper) {
            this.label = label;
            this.axis = axis;
            this.upper = upper;
        }
    }

    private final SopranoModule module;
    private final Screen previous;
    private final List<Action> actions = new ArrayList<>();
    private final Map<String, EditBox> pageFields = new LinkedHashMap<>();
    private final NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.ROOT);
    private final BuildPanel builder = new BuildPanel();
    private final PlannerCatalog catalog = new PlannerCatalog();
    private final PlannerTools tools = new PlannerTools();
    private final PlannerSchematics schematics = new PlannerSchematics();
    private final PlannerPreferences settings = new PlannerPreferences();
    private PlannerTheme theme = PlannerTheme.DARK;
    private Font fieldFont;
    private PlanningViewport viewport;
    private ClientLevel mapLevel;
    private Tab tab = Tab.MAP;
    private Hit selected;
    private Hit cornerA;
    private Hit cornerB;
    private Hit replaceSource;
    private Replacement replacement;
    private Rect left;
    private Rect center;
    private Rect inspector;
    private Rect map;
    private Rect clip;
    private EditBox goalX;
    private EditBox goalY;
    private EditBox goalZ;
    private EditBox followName;
    private EditBox locationName;
    private EditBox breakLimit, placeLimit, zoneName, jobSpec;
    private EditBox supportReserve, durabilityReserve;
    private EditBox knownBlock, minGoalY, maxGoalY, heightRange, facingDistance, corridorName, presetName, hostedWorldName;
    private EditBox lowerCut, upperCut, mapNote;

    private EditBox regionA, regionB, faceCoordinate, regionName, importedPresetName, protectedBlocks;
    private NamedRegions namedRegions;
    private Scope namedRegionScope;
    private boolean regionEditor;
    private boolean buildRegionEditor;
    private RegionFace regionFace = RegionFace.MIN_X;
    private PresetTransfer presetTransfer;
    private MovementProfiles.Preset incomingPreset;
    private Map<String, String> incomingSupported = Map.of();
    private List<String> transferPresets = List.of();
    private String transferMessage;
    private boolean bindingStopKey;
    private boolean bindingOpenKey;
    private boolean stayInRegion;
    private boolean confirmSurveyClear;
    private List<String> presetNames = List.of();
    private EditBox tripName, tripX, tripY, tripZ, tripWait, tripDimension, portalExit;
    private final List<RouteItinerary.Stop> tripStops = new ArrayList<>();
    private List<String> savedTrips = List.of();
    private RouteItinerary.Repeat tripRepeat = RouteItinerary.Repeat.ONCE;
    private TransportMode tripTransport;
    private int tripStop = -1;
    private List<TaskHistory.Entry> historyEntries = List.of();
    private TaskHistory.Entry historyPreview;
    private Rect contextMenu;
    private int contextActionStart;
    private boolean coverageVisible;
    private int inspectedStep = -1;
    private Navigation.StepInspection stepInspection;
    private boolean jobHelp;
    private String shownLocation;
    private String error;
    private int settingsScroll;
    private int inspectorScroll;
    private int settingsScrollLimit;
    private int inspectorScrollLimit;
    private int mouseX;
    private int mouseY;
    private int mapButton = -1;
    private double dragDistance;
    private boolean fillEstimate;
    private List<Step> lastPath = List.of();
    private int pathDistance;

    public PlanningScreen(SopranoModule module, Screen previous) {
        super(Component.literal("Wayclef"));
        this.module = module;
        this.previous = previous;
    }

    @Override
    protected void init() {
        theme = module.plannerTheme();
        PlannerTheme.refreshFont(minecraft);
        fieldFont = PlannerTheme.fieldFont(font);
        pageFields.values().forEach(this::addRenderableWidget);
        if (viewport == null) {
            viewport = new PlanningViewport(minecraft, module);
            mapLevel = minecraft.level;
            BlockPos a = module.automation().first(), b = module.automation().second();
            cornerA = a == null ? null : new Hit(a.getX(), a.getY(), a.getZ());
            cornerB = b == null ? null : new Hit(b.getX(), b.getY(), b.getZ());
            viewport.showBuildPlan(tab == Tab.BUILD);
            updateHighlights();
        }
        goalX = editBox("X", 9, value(goalX));
        goalY = editBox("Y optional", 5, value(goalY));
        goalZ = editBox("Z", 9, value(goalZ));
        followName = editBox("Player name", 16, value(followName));
        locationName = editBox("Location name", 48, value(locationName));
        breakLimit = editBox("Default", 5, value(breakLimit));
        placeLimit = editBox("Default", 5, value(placeLimit));
        supportReserve = editBox("Support material reserve", 5,
            supportReserve == null ? Integer.toString(module.editableMaterialReserve()) : value(supportReserve));
        durabilityReserve = editBox("Tool durability reserve", 5,
            durabilityReserve == null ? Integer.toString(module.editableToolReserve()) : value(durabilityReserve));
        zoneName = editBox("No-go zone name", 48, value(zoneName));
        knownBlock = editBox("Block ID", 160, value(knownBlock));
        minGoalY = editBox("Minimum Y", 5, value(minGoalY));
        maxGoalY = editBox("Maximum Y", 5, value(maxGoalY));
        heightRange = editBox("Search radius", 4, heightRange == null ? "96" : value(heightRange));
        facingDistance = editBox("Blocks", 7, facingDistance == null ? "32" : value(facingDistance));
        corridorName = editBox("Corridor name", 48, value(corridorName));
        presetName = editBox("Preset name", 48, value(presetName));
        hostedWorldName = editBox("Hosted world name", 40, value(hostedWorldName));
        lowerCut = editBox("Lower Y", 6, value(lowerCut));
        upperCut = editBox("Upper Y", 6, value(upperCut));
        mapNote = editBox("Note for picked block", 160, value(mapNote));
        jobSpec = editBox("Job command", 512, value(jobSpec));
        tripName = editBox("Trip name", 64, value(tripName));
        tripX = editBox("X", 9, value(tripX));
        tripY = editBox("Y optional", 5, value(tripY));
        tripZ = editBox("Z", 9, value(tripZ));
        tripWait = editBox("Wait seconds", 4, value(tripWait));
        tripDimension = editBox("Dimension, or current", 256, value(tripDimension));
        portalExit = editBox("Exit dimension X Y Z [radius]", 300, value(portalExit));

        regionA = editBox("A · X Y Z", 35, value(regionA));
        regionB = editBox("B · X Y Z", 35, value(regionB));
        faceCoordinate = editBox("Face coordinate", 9, value(faceCoordinate));
        regionName = editBox("Region name", 64, value(regionName));
        importedPresetName = editBox("Preset name", 64, value(importedPresetName));
        protectedBlocks = editBox("Block IDs and #tags", RouteBlockProtection.MAX_INPUT_LENGTH, value(protectedBlocks));
        if (namedRegions == null) {
            namedRegions = new NamedRegions(minecraft.gameDirectory.toPath().resolve("soprano").resolve("named-regions"));
            syncRegionScope();
        }
        builder.initialize();
        syncRegionFields();
        layout();
        hideFields();
    }

    private EditBox editBox(String name, int maxLength, String value) {
        Component label = Component.literal(name);
        EditBox box = addRenderableWidget(new EditBox(fieldFont, 0, 0, 12, BUTTON_HEIGHT, label));
        box.setBordered(false);
        box.setTextColor(theme.text);
        box.setTextShadow(false);
        box.setMaxLength(maxLength);
        box.setValue(value);
        return box;
    }

    private static String value(EditBox box) { return box == null ? "" : box.getValue(); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        if (viewport == null) return;
        if (mapLevel != minecraft.level) {
            mapButton = -1;
            mapLevel = minecraft.level;
            selected = cornerA = cornerB = replaceSource = null;
            stayInRegion = false;
            confirmSurveyClear = false;
            contextMenu = null;
            historyPreview = null;
            historyEntries = List.of();
            savedTrips = List.of();
            tripStops.clear();
            tripStop = -1;
            replacement = null;
            builder.resetSelection();
            syncRegionFields();
            viewport.clearMoveKeys();
            viewport.refresh();
            if (tab == Tab.LOCATIONS) viewport.listLocations();
            if (tab == Tab.HISTORY) run(() -> historyEntries = SopranoHistory.entries());
            if (tab == Tab.TRIPS && mapLevel != null) run(() -> savedTrips = ItineraryController.get().list());
            updateHighlights();
        }
        syncRegionScope();
        viewport.setPatternedOverlays(module.patternedOverlays());
        viewport.tick();
        schematics.tick(minecraft);
        if (builder.importer.pending()) run(builder::finishImport);
        if (!minecraft.getWindow().isFocused() && mapButton != -1) {
            mapButton = -1;
            updateHighlights();
        }
        if (!Objects.equals(shownLocation, viewport.snapshotName())) {
            shownLocation = viewport.snapshotName();
            clearMapSelection();
        }
    }

    @Override
    public void removed() {
        builder.importer.cancel();
        schematics.close();
        if (viewport != null) {
            if (mapLevel == minecraft.level && viewport.snapshotName() == null) module.automation().select(pos(cornerA), pos(cornerB));
            viewport.clearMoveKeys();
            viewport.close();
            viewport = null;
        }
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(previous == this ? null : previous);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (bindingOpenKey) {
            if (event.key() != InputConstants.KEY_ESCAPE) run(() -> module.bindOpenKey(event));
            if (event.key() == InputConstants.KEY_ESCAPE || error == null) bindingOpenKey = false;
            return true;
        }
        if (bindingStopKey) {
            if (event.key() != InputConstants.KEY_ESCAPE) run(() -> module.bindStopKey(event));
            if (event.key() == InputConstants.KEY_ESCAPE || error == null) bindingStopKey = false;
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE && presetTransfer != null) {
            closePresetTransfer();
            return true;
        }
        if (module.isOpenKey(event) && acceptsOpenKey()) {
            onClose();
            return true;
        }
        if (!mapTab() && tools.keyPressed(event)) return true;
        if (tab == Tab.BUILD && (builder.typing() || mapFieldFocused())) {
            if (builder.confirmHeight(event)) return true;
            return super.keyPressed(event);
        }
        if (event.key() == InputConstants.KEY_ESCAPE && contextMenu != null) {
            contextMenu = null;
            return true;
        }
        if (tab == Tab.LOCATIONS && locationName != null && locationName.isFocused()) return super.keyPressed(event);
        if (tab == Tab.MAP && mapFieldFocused()) return super.keyPressed(event);
        if (tab == Tab.BUILD && viewport != null && builder.shortcut(event)) return true;
        if (mapTab() && viewport != null && viewport.setMoveKey(event.key(), true)) return true;
        return super.keyPressed(event);
    }

    public boolean acceptsStopKey() {
        return !bindingStopKey && !bindingOpenKey;
    }

    public boolean acceptsOpenKey() { return acceptsStopKey() && !(getFocused() instanceof EditBox); }

    void closeToGame() { minecraft.gui.setScreen(null); }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (tab == Tab.SCHEMATICS) schematics.filesDropped(minecraft, paths);
        else super.onFilesDrop(paths);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (mapTab() && viewport != null && viewport.setMoveKey(event.key(), false)) return true;
        return super.keyReleased(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, module.plannerTheme().background);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        theme = module.plannerTheme();
        actions.clear();
        layout();
        drawHeader(graphics);
        drawTabs(graphics);
        drawFooter(graphics);
        hideFields();
        if (mapTab() && viewport != null) {
            if (selectingRegion()) updateRegionPreview(mouseX, mouseY);
            if (tab == Tab.BUILD) viewport.setBuildPreview(builder.preview(mouseX, mouseY));
            drawMap(graphics);
            if (tab == Tab.BUILD) builder.draw(graphics);
            else if (tab == Tab.LOCATIONS) drawLocations(graphics);
            else if (presetTransfer != null) drawPresetTransfer(graphics);
            else drawInspector(graphics);
        } else if (!mapTab()) {
            var page = new PlannerPage(this, graphics, minecraft, module, theme, center.x, center.y,
                center.width, center.height, mouseX, mouseY);
            switch (tab) {
                case GET -> catalog.render(page);
                case GOTO, FOLLOW, WAYPOINTS, STATS, HELP -> tools.render(tab.label, page);
                case SCHEMATICS -> schematics.render(page);
                case SETTINGS -> settings.render(page, bindingOpenKey, () -> { error = null; bindingOpenKey = true; });
                case ADVANCED -> drawAdvanced(page);
                default -> drawSettings(graphics);
            }
        }
        if (contextMenu != null) {
            contextActionStart = actions.size();
            drawContextMenu(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private boolean mapTab() { return tab == Tab.MAP || tab == Tab.BUILD || tab == Tab.LOCATIONS; }

    private void layout() {
        int margin = width >= 500 ? 12 : 7;
        int gap = width >= 500 ? 9 : 6;
        int top = HEADER_HEIGHT + 8;
        int bottom = Math.max(top + 1, height - FOOTER_HEIGHT - margin);
        int bodyHeight = bottom - top;
        int railMinimum = width < 300 ? 76 : Math.clamp(PlannerTheme.width(font, "Schematics") + 35, 92, 110);
        int leftWidth = Math.clamp(width / 5, railMinimum, 140);
        int rightWidth = mapTab() ? Math.clamp((width - leftWidth) / 3, 91, 194) : 0;
        left = new Rect(0, HEADER_HEIGHT, leftWidth, Math.max(1, height - FOOTER_HEIGHT - HEADER_HEIGHT));
        int centerX = left.right() + margin;
        int centerRight = width - margin - (rightWidth > 0 ? rightWidth + gap : 0);
        center = new Rect(centerX, top, Math.max(1, centerRight - centerX), bodyHeight);
        inspector = new Rect(center.right() + gap, top, rightWidth, bodyHeight);
        int footer = tab == Tab.MAP ? 75 : 53;
        map = new Rect(center.x + 6, center.y + 29, Math.max(1, center.width - 12), Math.max(1, center.height - footer));
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, left.right(), HEADER_HEIGHT, theme.rail);
        graphics.fill(left.right(), 0, width, HEADER_HEIGHT, theme.panel);
        graphics.fill(0, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, theme.border);
        drawBrand(graphics);
        int x = center.x;
        if (tab.isAdvanced()) {
            drawButton(graphics, new Rect(x, 9, 28, 18), "‹", true, false, () -> selectTab(Tab.ADVANCED));
            x += 36;
        }
        graphics.text(font, PlannerTheme.heading(tab.label), x, 15, theme.text, false);
        drawButton(graphics, new Rect(width - 28, 9, 19, 18), "×", true, false, this::onClose);
    }

    private void drawBrand(GuiGraphicsExtractor graphics) {
        int size = 15, x = 9, y = 10;
        PlannerTheme.rounded(graphics, x, y, size, size, theme.accent);
        graphics.fill(x + 4, y + 9, x + 6, y + 11, theme.onAccent);
        graphics.fill(x + 5, y + 7, x + 9, y + 9, theme.onAccent);
        graphics.fill(x + 8, y + 5, x + 10, y + 8, theme.onAccent);
        graphics.fill(x + 9, y + 4, x + 12, y + 6, theme.onAccent);
        graphics.text(font, PlannerTheme.brand("Wayclef"), x + size + 6, 14, theme.text, false);
    }

    private void drawFooter(GuiGraphicsExtractor graphics) {
        int y = height - FOOTER_HEIGHT;
        graphics.fill(0, y, width, height, theme.rail);
        graphics.fill(0, y, width, y + 1, theme.border);
        int dot = error != null ? theme.warning : module.running() ? theme.accent : theme.dim;
        PlannerTheme.rounded(graphics, 10, y + 10, 4, 4, dot);
        Rect stop = new Rect(width - 56, y + 4, 47, 15);
        drawButton(graphics, stop, "Stop", module.running() || !Navigation.get().path().isEmpty(), false, () -> run(module::stop));
        String status = error != null ? error : tab == Tab.BUILD && builder.status != null ? builder.status : module.statusText();
        if (status == null || status.isBlank() || status.equals("Idle.") || status.startsWith("Stopped · Select a destination")) {
            status = "Ready";
        }
        text(graphics, ellipsis(status, stop.x - 27), 20, y + 7, error == null ? theme.muted : theme.warning);
    }

    private void drawTabs(GuiGraphicsExtractor graphics) {
        graphics.fill(0, left.y, left.right(), left.bottom(), theme.rail);
        graphics.fill(left.right() - 1, 0, left.right(), left.bottom(), theme.border);
        boolean captions = left.height >= 232;
        int captionHeight = captions ? 13 : 0;
        int y = left.y + 6;
        int rowStep = Math.clamp((left.height - 12 - 3 * captionHeight) / RAIL.size(), 10, 27);
        for (Tab candidate : RAIL) {
            String group = switch (candidate) {
                case GET -> "Actions";
                case WAYPOINTS -> "Library";
                case HELP -> "Preferences";
                default -> "";
            };
            if (captions && !group.isEmpty()) {
                text(graphics, group, 12, y + 2, theme.dim);
                y += captionHeight;
            }
            Rect row = new Rect(6, y, left.width - 12, rowStep - 2);
            boolean active = tab == candidate || candidate == Tab.ADVANCED && tab.isAdvanced();
            boolean hovered = row.contains(mouseX, mouseY);
            if (active || hovered) PlannerTheme.rounded(graphics, row.x, row.y, row.width, row.height,
                active ? theme.selected : theme.hover);
            int ink = active ? theme.accent : hovered ? theme.text : theme.muted;
            int glyphY = row.y + (row.height - 8) / 2;
            drawRailIcon(graphics, candidate, row.x + 5, glyphY, ink);
            text(graphics, ellipsis(candidate.label, row.width - 21), row.x + 19, glyphY, ink);
            actions.add(new Action(row, () -> selectTab(candidate)));
            y += rowStep;
        }
    }

    private void drawRailIcon(GuiGraphicsExtractor graphics, Tab page, int x, int y, int ink) {
        long icon = RAIL_ICONS.get(page);
        for (int row = 0; row < 7; row++) {
            int pixels = (int) (icon >>> (6 - row) * 8);
            for (int column = 0; column < 7; column++) {
                if ((pixels & (1 << (6 - column))) != 0) graphics.fill(x + column, y + row, x + column + 1, y + row + 1, ink);
            }
        }
    }

    private void drawAdvanced(PlannerPage page) {
        int columns = page.width >= 510 ? 3 : 2;
        int gap = 6;
        int cardWidth = (page.width - (columns - 1) * gap) / columns;
        int pitch = 45;
        page.clip(page.x, page.y, page.width, page.height);
        for (int i = 0; i < ADVANCED_PAGES.size(); i++) {
            ToolPage tool = ADVANCED_PAGES.get(i);
            int x = page.x + i % columns * (cardWidth + gap);
            int y = page.y + i / columns * pitch - settingsScroll;
            page.panel(x, y, cardWidth, pitch - gap);
            page.label(tool.tab.label, x + 8, y + 8, cardWidth - 16, page.over(x, y, cardWidth, pitch - gap) ? page.accent : page.text);
            page.label(tool.description, x + 8, y + 22, cardWidth - 16, page.muted);
            page.hit(x, y, cardWidth, pitch - gap, () -> selectTab(tool.tab));
        }
        page.unclip();
        settingsScrollLimit = Math.max(0, (ADVANCED_PAGES.size() + columns - 1) / columns * pitch - gap - page.height);
        settingsScroll = Math.min(settingsScroll, settingsScrollLimit);
    }

    void showPage(String label) {
        for (Tab candidate : Tab.values()) {
            if (candidate.label.equalsIgnoreCase(label)) { selectTab(candidate); return; }
        }
        throw new IllegalArgumentException("Unknown planner page " + label + ".");
    }

    private void selectTab(Tab next) {
        if (next == tab) return;
        if (viewport != null) viewport.clearMoveKeys();
        clearFocus();
        tab = next;
        bindingStopKey = false;
        bindingOpenKey = false;
        closePresetTransfer();
        syncRegionFields();
        contextMenu = null;
        confirmSurveyClear = false;
        error = null;
        settingsScroll = inspectorScroll = 0;
        mapButton = -1;
        if (viewport != null) {
            if (next == Tab.BUILD || next == Tab.ACTIVITIES || next == Tab.NAVIGATION) {
                if (viewport.snapshotName() != null) clearMapSelection();
                viewport.useLive();
            }
            viewport.showBuildPlan(tab == Tab.BUILD);
            if (tab == Tab.LOCATIONS) viewport.listLocations();
        }
        if (next == Tab.TERRAIN) {
            protectedBlocks.setValue(module.editableProtectedRouteBlocks());
            refreshReserveFields();
        }
        if (next == Tab.HISTORY) run(() -> historyEntries = SopranoHistory.entries());
        if (next == Tab.TRIPS && minecraft.level != null) run(() -> savedTrips = ItineraryController.get().list());
        if (next == Tab.PROFILES) run(() -> {
            presetNames = module.movementPresetNames();
            hostedWorldName.setValue(module.hostedWorld());
        });
        tools.enter(next.label);
        updateHighlights();
        layout();
        hideFields();
    }

    private void drawMap(GuiGraphicsExtractor graphics) {
        drawPanel(graphics, center);
        int x = center.x + 6;
        int y = center.y + 6;
        int smallWidth = center.width >= 174 ? 49 : 34;
        drawButton(graphics, new Rect(x, y, smallWidth, BUTTON_HEIGHT), "Home", true, false, viewport::home);
        boolean viewingSaved = viewport.snapshotName() != null;
        drawButton(graphics, new Rect(x + smallWidth + 4, y, smallWidth + 11, BUTTON_HEIGHT), viewingSaved ? "Live" : "Refresh",
            true, false, () -> run(viewingSaved ? this::returnToLive : this::refreshMap));
        int statusX = x + 2 * smallWidth + 21;
        int coverageWidth = center.width >= 210 ? PlannerTheme.width(font, "Coverage") + 11 : 28;
        int coverageX = center.right() - coverageWidth - 6;
        if (coverageX - statusX > 15) {
            text(graphics, ellipsis(viewport.progressText(), coverageX - statusX - 4), statusX, y + 5, theme.muted);
        }
        if (coverageX >= statusX - 3) drawButton(graphics, new Rect(coverageX, y, coverageWidth, BUTTON_HEIGHT),
            coverageWidth > 30 ? "Coverage" : "Cov", true, coverageVisible, () -> coverageVisible = !coverageVisible);

        graphics.fill(map.x - 1, map.y - 1, map.right() + 1, map.bottom() + 1, theme.border);
        graphics.fill(map.x, map.y, map.right(), map.bottom(), theme.field);
        graphics.enableScissor(map.x, map.y, map.right(), map.bottom());
        viewport.draw(graphics, map.x, map.y, map.width, map.height);
        if (coverageVisible && Math.min(map.width, map.height - 24) >= 50) {
            viewport.drawCoverage(graphics, map.right() - 6, map.y + 6, Math.min(map.width, map.height - 24));
            coverageLegend(graphics);
        }
        graphics.disableScissor();

        int layerY = map.bottom() + 4;
        int xLayer = x + 5;
        int right = center.right() - 6;
        String heightLabel = viewport.cutY() >= viewport.maxY() ? "All heights" : "Cut Y " + viewport.cutY();
        if (tab == Tab.MAP) {
            int presetY = layerY + BUTTON_HEIGHT + 4;
            int presetWidth = Math.min(82, Math.max(1, (right - x - 3) / 2));
            String copy = presetWidth >= 76 ? "Copy preset" : "Copy";
            String paste = presetWidth >= 76 ? "Paste preset" : "Paste";
            drawButton(graphics, new Rect(x, presetY, presetWidth, BUTTON_HEIGHT), copy, true,
                presetTransfer == PresetTransfer.COPY, () -> run(this::openPresetCopy));
            drawButton(graphics, new Rect(x + presetWidth + 3, presetY, presetWidth, BUTTON_HEIGHT), paste, true,
                presetTransfer == PresetTransfer.PASTE, () -> run(this::openPresetPaste));
        }
        if (right - 70 > xLayer)
            text(graphics, ellipsis(heightLabel, right - 70 - xLayer), xLayer, layerY + 5, theme.muted);
        drawButton(graphics, new Rect(right - 24, layerY, 24, BUTTON_HEIGHT), "All", true, false, () -> {
            viewport.setLowerCutY(null);
            viewport.setCutY(viewport.maxY());
        });
        drawButton(graphics, new Rect(right - 46, layerY, 18, BUTTON_HEIGHT), "+", viewport.cutY() < viewport.maxY(), false,
            () -> changeLayer(1));
        drawButton(graphics, new Rect(right - 68, layerY, 18, BUTTON_HEIGHT), "−", viewport.cutY() > viewport.minY(), false,
            () -> changeLayer(-1));
        if (map.contains(mouseX, mouseY) && map.width > 105) {
            String movement = ellipsis("WASD move  ·  Space/Ctrl height  ·  Shift 5×", map.width - 12);
            String pointer = ellipsis("Left orbit  ·  Right pan  ·  Middle select  ·  Wheel zoom", map.width - 12);
            int hintY = map.bottom() - 28;
            graphics.fill(map.x + 3, hintY, map.x + Math.max(PlannerTheme.width(font, movement), PlannerTheme.width(font, pointer)) + 11,
                hintY + 24, 0xE7101821);
            text(graphics, movement, map.x + 7, hintY + 3, 0xFFF5F1EC);
            text(graphics, pointer, map.x + 7, hintY + 13, 0xFFF5F1EC);
        }
    }


    private void openPresetCopy() {
        if (presetTransfer == PresetTransfer.COPY) {
            closePresetTransfer();
            return;
        }
        transferPresets = module.movementPresetNames();
        presetTransfer = PresetTransfer.COPY;
        transferMessage = null;
        inspectorScroll = 0;
    }

    private void openPresetPaste() {
        if (presetTransfer == PresetTransfer.PASTE) {
            closePresetTransfer();
            return;
        }
        MovementProfiles.Preset decoded = module.readMovementPreset(minecraft.keyboardHandler.getClipboard());
        var supported = module.supportedPresetSettings(decoded);
        var names = module.movementPresetNames();
        incomingPreset = decoded;
        incomingSupported = supported;
        transferPresets = names;
        importedPresetName.setValue(incomingPreset.name());
        presetTransfer = PresetTransfer.PASTE;
        transferMessage = null;
        inspectorScroll = 0;
    }

    private void closePresetTransfer() {
        if (presetTransfer == null) return;
        presetTransfer = null;
        incomingPreset = null;
        incomingSupported = Map.of();
        transferPresets = List.of();
        transferMessage = null;
        inspectorScroll = 0;
        clearFocus();
    }

    private void copyPreset(String name) {
        minecraft.keyboardHandler.setClipboard(module.copyMovementPreset(name));
        transferMessage = (name == null ? "Active movement settings" : name) + " copied.";
    }

    private int drawPresetImport(GuiGraphicsExtractor graphics, Rect body, int y) {
        int x = body.x, available = body.width;
        int skipped = incomingPreset.settings().size() - incomingSupported.size();
        y = wrap(graphics, "Review " + incomingPreset.name(), x, y, available, theme.accent) + 5;
        if (skipped > 0) y = wrap(graphics, skipped + " unsupported or invalid settings will be skipped.", x, y, available, theme.warning) + 5;
        for (var entry : new java.util.TreeMap<>(incomingPreset.settings()).entrySet()) {
            String key = entry.getKey(), value = entry.getValue(), supported = incomingSupported.get(key);
            String label = key + " = " + value;
            if (supported == null) label += " (skipped)";
            else if (!supported.equals(value)) label += " → " + supported;
            y = wrap(graphics, label, x, y, available, supported == null ? theme.warning : theme.muted) + 2;
        }
        field(graphics, body, importedPresetName, new Rect(x, y + 3, available, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 7;
        boolean replace = transferPresets.stream().anyMatch(importedPresetName.getValue().strip()::equalsIgnoreCase);
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), replace ? "Replace saved preset" : "Import preset",
            !incomingSupported.isEmpty() && !importedPresetName.getValue().isBlank(), true, () -> run(() -> {
                module.importMovementPreset(importedPresetName.getValue(), incomingPreset);
                closePresetTransfer();
            }));
        return y + BUTTON_HEIGHT + 4;
    }

    private void drawPresetTransfer(GuiGraphicsExtractor graphics) {
        drawPanel(graphics, inspector);
        int x = inspector.x + 7, available = inspector.width - 14;
        boolean copy = presetTransfer == PresetTransfer.COPY;
        drawButton(graphics, new Rect(inspector.right() - 24, inspector.y + 5, 18, BUTTON_HEIGHT),
            "×", true, false, this::closePresetTransfer);
        text(graphics, ellipsis(copy ? "Copy preset" : "Paste preset", available - 22), x, inspector.y + 10, theme.text);
        Rect body = new Rect(x, inspector.y + 30, available, Math.max(0, inspector.height - 37));
        beginClip(graphics, body);
        int y = body.y - inspectorScroll;
        if (transferMessage != null) y = wrap(graphics, transferMessage, x, y, available, theme.accent) + 4;
        if (copy) {
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Active settings", true, false,
                () -> run(() -> copyPreset(null)));
            y += BUTTON_HEIGHT + 4;
            for (String name : transferPresets) {
                drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), name, true, false,
                    () -> run(() -> copyPreset(name)));
                y += BUTTON_HEIGHT + 3;
            }
        } else {
            y = drawPresetImport(graphics, body, y);
        }
        endClip(graphics);
        inspectorScrollLimit = Math.max(0, y + inspectorScroll - body.bottom() + 5);
        inspectorScroll = Math.min(inspectorScroll, inspectorScrollLimit);
        drawScrollMark(graphics, inspector, body, inspectorScroll, inspectorScrollLimit);
    }

    private void coverageLegend(GuiGraphicsExtractor graphics) {
        String[] labels = {"Loaded", "Surveyed", "Unexplored"};
        int[] colors = {PlanningViewport.COVERAGE_LOADED, PlanningViewport.COVERAGE_SURVEYED, PlanningViewport.COVERAGE_UNKNOWN};
        int x = map.x + 5, y = map.y + 6;
        graphics.fill(x - 2, y - 2, x + 66, y + 34, 0xEE101821);
        for (int i = 0; i < labels.length; i++) {
            graphics.fill(x, y + 1, x + 7, y + 8, colors[i]);
            text(graphics, labels[i], x + 10, y, theme.text);
            y += 11;
        }
    }

    private void refreshMap() {
        mapButton = -1;
        viewport.refreshLoaded();
        builder.comparison = null;
        replacement = null;
        updateHighlights();
    }

    private void returnToLive() {
        viewport.useLive();
        shownLocation = null;
        clearMapSelection();
    }

    private void clearMapSelection() {
        mapButton = -1;
        selected = cornerA = cornerB = replaceSource = null;
        syncRegionFields();
        replacement = null;
        inspectorScroll = 0;
        updateHighlights();
    }

    private void drawLocations(GuiGraphicsExtractor graphics) {
        drawPanel(graphics, inspector);
        int x = inspector.x + 7;
        int available = inspector.width - 14;
        text(graphics, ellipsis("Saved locations", available), x, inspector.y + 10, theme.text);
        graphics.fill(x, inspector.y + 25, x + available, inspector.y + 26, theme.border);

        Rect body = new Rect(x, inspector.y + 31, available, Math.max(0, inspector.height - 38));
        beginClip(graphics, body);
        int y = body.y - inspectorScroll;
        if (viewport.snapshotName() == null) {
            y = wrap(graphics, "Save surveyed terrain and this camera view.", x, y, available, theme.muted) + 6;
            field(graphics, body, locationName, new Rect(x, y, available, BUTTON_HEIGHT));
            y += BUTTON_HEIGHT + 5;
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Save location",
                viewport.canSaveLocation() && !locationName.getValue().isBlank(), true,
                () -> run(() -> viewport.saveLocation(locationName.getValue().strip())));
        } else {
            y = wrap(graphics, "Viewing " + viewport.snapshotName(), x, y, available, theme.accent) + 6;
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Go to saved location", true, true, () -> run(() -> {
                BlockPos target = viewport.savedDestination();
                module.walkTo(target.getX(), null, target.getZ());
                returnToLive();
            }));
            y += BUTTON_HEIGHT + 4;
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Back to live map",
                true, false, this::returnToLive);
        }
        y += BUTTON_HEIGHT + 8;
        String message = viewport.locationStatus();
        if (message != null) y = wrap(graphics, message, x, y, available, theme.muted) + 5;
        if (viewport.locationNames().isEmpty() && !viewport.locationsBusy()) {
            String empty = viewport.locationsAvailable() ? "No saved locations in this dimension."
                : "Join a world or server to access its saved locations.";
            y = wrap(graphics, empty, x, y, available, theme.dim) + 5;
        }
        for (String name : viewport.locationNames()) {
            boolean active = name.equals(viewport.snapshotName());
            Rect row = new Rect(x, y, available, BUTTON_HEIGHT);
            drawButton(graphics, row, name, !viewport.locationsBusy(), active,
                () -> run(() -> viewport.openLocation(name)));
            if (body.contains(mouseX, mouseY) && row.contains(mouseX, mouseY))
                graphics.setTooltipForNextFrame(Component.literal("Open " + name), mouseX, mouseY);
            y += BUTTON_HEIGHT + 4;
        }
        endClip(graphics);
        inspectorScrollLimit = Math.max(0, y + inspectorScroll - body.bottom() + 5);
        inspectorScroll = Math.min(inspectorScroll, inspectorScrollLimit);
        drawScrollMark(graphics, inspector, body, inspectorScroll, inspectorScrollLimit);
    }

    private void changeLayer(int amount) {
        viewport.setCutY(Math.clamp(viewport.cutY() + amount, viewport.minY(), viewport.maxY()));
    }

    private void drawInspector(GuiGraphicsExtractor graphics) {
        drawPanel(graphics, inspector);
        int x = inspector.x + 7;
        int available = inspector.width - 14;
        text(graphics, ellipsis("Selection", available), x, inspector.y + 10, theme.text);
        graphics.fill(x, inspector.y + 25, x + available, inspector.y + 26, theme.border);

        Rect body = new Rect(x, inspector.y + 31, available, Math.max(0, inspector.height - 38));
        beginClip(graphics, body);
        int y = body.y - inspectorScroll;
        if (selected == null) {
            text(graphics, ellipsis("No block selected", available), x, y, theme.muted);
            y += 15;
            y = wrap(graphics, "Click a block in the map to inspect it.", x, y, available, theme.dim) + 8;
        } else {
            text(graphics, ellipsis(viewport.blockName(selected), available), x, y, theme.accent);
            y += 13;
            y = wrap(graphics, coordinates(selected), x, y, available, theme.muted) + 7;
            if (mapLevel != null && viewport.snapshotName() == null) y = drawEquivalent(graphics, body, y, selected) + 3;
        }
        boolean canUseBlock = selected != null;
        int half = (available - 4) / 2;
        boolean live = viewport.snapshotName() == null;
        if (live) {
            drawButton(graphics, new Rect(x, y, half, BUTTON_HEIGHT), "Preview", canUseBlock, false,
                () -> run(() -> useSelected(false)));
            drawButton(graphics, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT), "Go", canUseBlock, true,
                () -> run(() -> useSelected(true)));
            y += BUTTON_HEIGHT + 5;
        } else {
            y = wrap(graphics, "Saved terrain · view only", x, y, available, theme.muted) + 5;
        }
        if (selected != null) {
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Slice at block", true, false,
                () -> viewport.setCutY(selected.y()));
            y += BUTTON_HEIGHT + 5;
        }
        if (live && mapLevel != null) {
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Find surface", true, false,
                () -> run(module::routeToSurface));
            y += BUTTON_HEIGHT + 4;
            if (ItineraryController.get().canDetour()) {
                drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Detour to block", selected != null, false,
                    () -> run(() -> module.detourTrip(pos(selected))));
                y += BUTTON_HEIGHT + 4;
            }
        }
        y = drawMapRouteTools(graphics, body, y + 3);
        y = drawFloorCuts(graphics, body, y + 5);
        y = drawMapMarkers(graphics, body, y + 5);
        if (live && updatePathDistance()) {
            y = wrap(graphics, "Path " + numbers.format(lastPath.size()) + " steps  ·  " + numbers.format(pathDistance)
                + " blocks", x, y, available, theme.muted);
            if (Navigation.get().travelProgress().partial()) y = wrap(graphics, "Amber dots show direction beyond the confirmed route.",
                x, y, available, theme.warning);
            y = drawRouteInspection(graphics, body, y + 4);
        }
        y += 6;

        graphics.fill(x, y, x + available, y + 1, theme.border);
        y += 8;
        text(graphics, "Region", x, y, theme.text);
        y += 13;
        int third = (available - 6) / 3;
        drawButton(graphics, new Rect(x, y, third, BUTTON_HEIGHT), "Set A", canUseBlock, cornerA != null, () -> {
            cornerA = selected;
            syncRegionFields();
            updateHighlights();
        });
        drawButton(graphics, new Rect(x + third + 3, y, third, BUTTON_HEIGHT), "Set B", canUseBlock, cornerB != null, () -> {
            cornerB = selected;
            syncRegionFields();
            updateHighlights();
        });
        drawButton(graphics, new Rect(x + 2 * (third + 3), y, available - 2 * (third + 3), BUTTON_HEIGHT),
            "Clear", cornerA != null || cornerB != null, false, () -> {
                cornerA = cornerB = null;
                syncRegionFields();
                updateHighlights();
            });
        y += BUTTON_HEIGHT + 6;
        y = wrap(graphics, "A  " + (cornerA == null ? "—" : coordinates(cornerA)), x, y, available, theme.muted);
        y = wrap(graphics, "B  " + (cornerB == null ? "—" : coordinates(cornerB)), x, y, available, theme.muted);

        y = drawRegionTools(graphics, body, y + 5, live);
        MapVolume.Box box = selectionBox();
        if (box != null) {
            y += 7;
            if (live) {
                drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Survey on foot", mapLevel != null, false,
                    () -> run(() -> module.startFootSurvey(selectionBox())));
                y += BUTTON_HEIGHT + 4;
                drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Replace in region", true, false, () -> {
                    if (selected != null) setReplaceSource(selected);
                    selectTab(Tab.ACTIVITIES);
                });
                y += BUTTON_HEIGHT + 6;
            }
            y = drawEstimate(graphics, box, x, y, available);
        }
        if (live && mapLevel != null) y = drawNoGoZones(graphics, body, y + 7, box);
        if (live && mapLevel != null) y = drawCorridors(graphics, body, y + 7, box);
        if (live && mapLevel != null) {
            y += 7;
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT),
                confirmSurveyClear ? "Confirm clear survey" : "Clear surveyed sections", true, false, () -> run(() -> {
                    if (!confirmSurveyClear) {
                        confirmSurveyClear = true;
                        return;
                    }
                    viewport.clearSurvey();
                    confirmSurveyClear = false;
                    clearMapSelection();
                }));
            y += BUTTON_HEIGHT + 4;
        }
        endClip(graphics);
        inspectorScrollLimit = Math.max(0, y + inspectorScroll - body.bottom() + 5);
        inspectorScroll = Math.min(inspectorScroll, inspectorScrollLimit);
        drawScrollMark(graphics, inspector, body, inspectorScroll, inspectorScrollLimit);
    }


    private int drawEquivalent(GuiGraphicsExtractor graphics, Rect body, int y, Hit hit) {
        var equivalent = PortalCoordinates.equivalent(mapLevel.dimension().identifier().toString(), hit);
        if (equivalent == null) return y;
        String label = equivalent.dimension() + " X " + equivalent.x() + " · Z " + equivalent.z();
        if (!equivalent.inRange()) label += " (outside world bounds)";
        return wrap(graphics, label, body.x, y, body.width, theme.muted);
    }

    private int drawRegionTools(GuiGraphicsExtractor graphics, Rect body, int y, boolean live) {
        int x = body.x, available = body.width;
        boolean build = tab == Tab.BUILD;
        boolean expanded = build ? buildRegionEditor : regionEditor;
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), expanded ? "Hide coordinate editor" : "Edit coordinates",
            true, expanded, () -> {
                if (build) buildRegionEditor = !buildRegionEditor;
                else regionEditor = !regionEditor;
                if (!expanded) syncRegionFields();
            });
        y += BUTTON_HEIGHT + 4;
        if (expanded) {
            field(graphics, body, regionA, new Rect(x, y, available, BUTTON_HEIGHT));
            y += BUTTON_HEIGHT + 3;
            field(graphics, body, regionB, new Rect(x, y, available, BUTTON_HEIGHT));
            y += BUTTON_HEIGHT + 3;
            drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Apply A and B",
                !regionA.getValue().isBlank() || !regionB.getValue().isBlank(), true, () -> run(this::applyRegionCorners));
            y += BUTTON_HEIGHT + 5;
            if (editorBox() != null) y = drawRegionFaces(graphics, body, y);
        }
        if (!live || namedRegionScope == null) return y;
        text(graphics, "Named regions", x, y + 2, theme.text);
        y += 15;
        field(graphics, body, regionName, new Rect(x, y, available, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Save current region",
            editorBox() != null && !regionName.getValue().isBlank(), false, () -> run(() -> {
                namedRegions.setScope(module.storageScope());
                namedRegions.save(new NamedRegions.Region(regionName.getValue(), editorBox()));
            }));
        y += BUTTON_HEIGHT + 4;
        for (var region : namedRegions.list()) {
            Rect row = new Rect(x, y, available - 23, BUTTON_HEIGHT);
            drawButton(graphics, row, region.name(), true, false, () -> run(() -> loadRegion(region)));
            if (body.contains(mouseX, mouseY) && row.contains(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(Component.literal("Recall " + region.name()), mouseX, mouseY);
            }
            drawButton(graphics, new Rect(body.right() - 20, y, 20, BUTTON_HEIGHT), "×", true, false,
                () -> run(() -> namedRegions.delete(region)));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private int drawRegionFaces(GuiGraphicsExtractor graphics, Rect body, int y) {
        text(graphics, "Move a face", body.x, y + 2, theme.text);
        y += 15;
        int third = (body.width - 6) / 3;
        for (var face : RegionFace.values()) {
            int column = face.axis;
            int x = body.x + column * (third + 3);
            int available = column == 2 ? body.right() - x : third;
            drawButton(graphics, new Rect(x, y + (face.upper ? BUTTON_HEIGHT + 3 : 0), available, BUTTON_HEIGHT),
                face.label, true, regionFace == face, () -> {
                    regionFace = face;
                    syncFaceCoordinate();
                });
        }
        y += 2 * (BUTTON_HEIGHT + 3);
        field(graphics, body, faceCoordinate, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Move " + regionFace.label,
            !faceCoordinate.getValue().isBlank(), false, () -> run(this::applyRegionFace));
        return y + BUTTON_HEIGHT + 4;
    }

    private void syncRegionScope() {
        Scope next = module.storageScope();
        if (Objects.equals(namedRegionScope, next)) return;
        boolean hadScope = namedRegionScope != null;
        namedRegionScope = next;
        if (hadScope) {
            builder.clearCorners();
            clearMapSelection();
            regionName.setValue("");
        }
        run(() -> namedRegions.setScope(next));
    }

    private Hit editorA() { return tab == Tab.BUILD ? hit(builder.first) : cornerA; }

    private Hit editorB() { return tab == Tab.BUILD ? hit(builder.second) : cornerB; }

    private MapVolume.Box editorBox() { return tab == Tab.BUILD ? builder.box() : selectionBox(); }

    private void syncRegionFields() {
        regionA.setValue(cornerText(editorA()));
        regionB.setValue(cornerText(editorB()));
        syncFaceCoordinate();
    }

    private void syncFaceCoordinate() {
        Hit first = editorA(), second = editorB();
        if (first == null || second == null) {
            faceCoordinate.setValue("");
        } else {
            int a = axisCoordinate(first, regionFace.axis), b = axisCoordinate(second, regionFace.axis);
            faceCoordinate.setValue(Integer.toString(regionFace.upper ? Math.max(a, b) : Math.min(a, b)));
        }
    }

    private static Hit hit(Cell cell) { return cell == null ? null : new Hit(cell.x(), cell.y(), cell.z()); }

    private static String cornerText(Hit hit) { return hit == null ? "" : hit.x() + " " + hit.y() + " " + hit.z(); }

    private static Hit readCorner(EditBox field, String name) {
        String text = field.getValue().strip();
        if (text.isEmpty()) return null;
        String[] coordinates = text.split("\\s+");
        if (coordinates.length == 3) {
            try {
                return checkedCorner(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            } catch (NumberFormatException ignored) {
                // Use the same input hint as a wrong coordinate count.
            }
        }
        throw new IllegalArgumentException("Enter three whole numbers for corner " + name + " (X Y Z).");
    }

    private void applyRegionCorners() {
        Hit a = readCorner(regionA, "A"), b = readCorner(regionB, "B");
        if (tab == Tab.BUILD && a == null && b != null) throw new IllegalArgumentException("Set A before B.");
        setEditorCorners(a, b);
    }

    private void setEditorCorners(Hit a, Hit b) {
        if (tab == Tab.BUILD) builder.setEditedCorners(a, b);
        else {
            cornerA = a;
            cornerB = b;
        }
        syncRegionFields();
        updateHighlights();
    }

    private static int axisCoordinate(Hit hit, int axis) {
        return switch (axis) {
            case 0 -> hit.x();
            case 1 -> hit.y();
            default -> hit.z();
        };
    }

    private static Hit checkedCorner(int x, int y, int z) {
        NamedRegions.checkCorner(x, y, z);
        return new Hit(x, y, z);
    }

    private void applyRegionFace() {
        Hit first = editorA(), second = editorB();
        if (first == null || second == null) throw new IllegalStateException("Set A and B first.");
        int next = coordinate(faceCoordinate, "the face coordinate");
        int a = axisCoordinate(first, regionFace.axis), b = axisCoordinate(second, regionFace.axis);
        boolean moveA = regionFace.upper ? a > b : a <= b;
        int opposite = moveA ? b : a;
        if (regionFace.upper ? next < opposite : next > opposite)
            throw new IllegalArgumentException("The face cannot move past the opposite face.");
        Hit current = moveA ? first : second;
        Hit moved = switch (regionFace.axis) {
            case 0 -> checkedCorner(next, current.y(), current.z());
            case 1 -> checkedCorner(current.x(), next, current.z());
            default -> checkedCorner(current.x(), current.y(), next);
        };
        setEditorCorners(moveA ? moved : first, moveA ? second : moved);
    }

    private void loadRegion(NamedRegions.Region region) {
        MapVolume.Box box = region.box();
        setEditorCorners(new Hit(box.x1(), box.y1(), box.z1()), new Hit(box.x2(), box.y2(), box.z2()));
        regionName.setValue(region.name());
    }

    private int drawMapRouteTools(GuiGraphicsExtractor graphics, Rect body, int y) {
        text(graphics, "Map route preview", body.x, y, theme.text);
        y += 13;
        y = paired(graphics, body, y, "Set start", "Set end", selected != null, selected != null,
            () -> run(() -> viewport.setPreviewStart(selected)), () -> run(() -> viewport.setPreviewEnd(selected)));
        if (viewport.previewStart() != null || viewport.previewEnd() != null) {
            y = wrap(graphics, viewport.previewStatus(), body.x, y, body.width, theme.muted) + 4;
            y = paired(graphics, body, y, "Clear preview", "Go to destination", true, viewport.previewEnd() != null && mapLevel != null,
                viewport::clearMapPreview, () -> run(this::startPreviewDestination));
        }
        return drawElevation(graphics, body, y + 2);
    }

    private void startPreviewDestination() {
        Hit destination = viewport.previewEnd();
        if (destination == null || mapLevel == null) return;
        Goal goal = mapLevel.hasChunk(destination.x() >> 4, destination.z() >> 4)
            ? Navigation.get().goalAtBlock(pos(destination))
            : module.coordinateGoal(destination.x(), destination.y() + 1, destination.z());
        module.request(bounded(goal), true);
        if (viewport.snapshotName() != null) returnToLive();
    }

    private int drawElevation(GuiGraphicsExtractor graphics, Rect body, int y) {
        RouteElevation.Profile profile = viewport.elevationProfile();
        if (profile == null || profile.samples().size() < 2) return y;
        String caption = profile.snapshotOnly() ? "Survey elevation" : profile.partial() ? "Confirmed elevation" : "Route elevation";
        y = wrap(graphics, caption + " · " + Math.round(profile.minY()) + "–" + Math.round(profile.maxY()) + " Y",
            body.x, y, body.width, theme.muted) + 3;
        Rect plot = new Rect(body.x, y, body.width, 36);
        graphics.fill(plot.x, plot.y, plot.right(), plot.bottom(), theme.field);
        graphics.outline(plot.x, plot.y, plot.width, plot.height, theme.border);
        List<RouteElevation.Sample> samples = profile.samples();
        double scale = Math.max(.01, profile.distance());
        double span = Math.max(1, profile.maxY() - profile.minY());
        int previousY = plot.bottom() - 3;
        int next = 1;
        for (int offset = 2; offset < plot.width - 2; offset++) {
            double distance = scale * (offset - 2) / Math.max(1, plot.width - 5);
            while (next + 1 < samples.size() && samples.get(next).distance() < distance) next++;
            var start = samples.get(next - 1);
            var end = samples.get(next);
            double ratio = Math.clamp((distance - start.distance()) / Math.max(.001, end.distance() - start.distance()), 0, 1);
            double height = start.y() + (end.y() - start.y()) * ratio;
            int pixel = plot.bottom() - 3 - (int) Math.round((plot.height - 6) * (height - profile.minY()) / span);
            if (offset == 2) previousY = pixel;
            graphics.fill(plot.x + offset, Math.min(pixel, previousY), plot.x + offset + 1, Math.max(pixel, previousY) + 1, theme.accent);
            previousY = pixel;
        }
        Rect click = plot.intersection(body);
        if (click.width > 0 && click.height > 0) actions.add(new Action(click, () -> {
            double along = scale * Math.clamp((mouseX - plot.x - 2.0) / Math.max(1, plot.width - 5), 0, 1);
            var sample = samples.stream().min(Comparator.comparingDouble(value -> Math.abs(value.distance() - along))).orElseThrow();
            viewport.focusLocation(BlockPos.containing(sample.position()));
        }));
        return plot.bottom() + 5;
    }

    private int drawFloorCuts(GuiGraphicsExtractor graphics, Rect body, int y) {
        String lower = viewport.lowerCutY() == null ? "All" : viewport.lowerCutY().toString();
        y = wrap(graphics, "Floor cuts  " + lower + " to " + viewport.cutY(), body.x, y, body.width, theme.text) + 4;
        int half = (body.width - 4) / 2;
        field(graphics, body, lowerCut, new Rect(body.x, y, half, BUTTON_HEIGHT));
        field(graphics, body, upperCut, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        return paired(graphics, body, y, "Apply cuts", "All heights", true, true, () -> run(() -> {
            Integer lowerY = lowerCut.getValue().isBlank() ? null : coordinate(lowerCut, "lower Y cut");
            int upperY = upperCut.getValue().isBlank() ? viewport.maxY() : coordinate(upperCut, "upper Y cut");
            if (lowerY != null && lowerY > upperY) throw new IllegalArgumentException("Lower Y must be at or below upper Y.");
            viewport.setCutY(upperY);
            viewport.setLowerCutY(lowerY);
        }), () -> {
            viewport.setLowerCutY(null);
            viewport.setCutY(viewport.maxY());
            lowerCut.setValue("");
            upperCut.setValue("");
        });
    }

    private int drawMapMarkers(GuiGraphicsExtractor graphics, Rect body, int y) {
        text(graphics, "Markers and notes", body.x, y, theme.text);
        y += 13;
        field(graphics, body, mapNote, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        y = paired(graphics, body, y, "Save note", viewport.mapNotesVisible() ? "Hide notes" : "Show notes",
            selected != null && !mapNote.getValue().isBlank(), true, () -> run(() -> {
                viewport.saveMapNote(selected, mapNote.getValue());
                mapNote.setValue("");
            }), () -> viewport.setMapNotesVisible(!viewport.mapNotesVisible()));
        for (var marker : viewport.mapMarkers()) {
            if (marker.kind() == MapAnnotations.Kind.PORTAL) continue;
            if (marker.kind() == MapAnnotations.Kind.NOTE && !viewport.mapNotesVisible()) continue;
            BlockPos position = marker.position();
            y = wrap(graphics, marker.text(), body.x, y + 3, body.width, theme.muted);
            if (marker.kind() == MapAnnotations.Kind.NOTE) {
                y = paired(graphics, body, y + 2, "Focus note", "Delete note", true, true, () -> viewport.focusLocation(position),
                    () -> run(() -> viewport.removeMapNote(new Hit(position.getX(), position.getY(), position.getZ()))));
            } else {
                y = paired(graphics, body, y + 2, "Focus marker", "Preview route", true, viewport.snapshotName() == null,
                    () -> viewport.focusLocation(position), () -> run(() -> module.request(bounded(new Goal(position.getX(), null, position.getZ(), 1)), false)));
            }
        }
        return y;
    }

    private int drawRouteInspection(GuiGraphicsExtractor graphics, Rect body, int y) {
        int half = (body.width - 4) / 2;
        drawButton(graphics, new Rect(body.x, y, half, BUTTON_HEIGHT), "Prior step", inspectedStep > 0, false,
            () -> run(() -> inspectStep(inspectedStep - 1)));
        drawButton(graphics, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT), "Inspect step",
            inspectedStep + 1 < lastPath.size(), false, () -> run(() -> inspectStep(inspectedStep + 1)));
        y += BUTTON_HEIGHT + 4;
        if (stepInspection == null) return y;
        y = wrap(graphics, "Step " + (inspectedStep + 1) + " • " + stepInspection.step().move() + " • Cost " + stepInspection.cost(),
            body.x, y, body.width, theme.accent);
        for (String alternative : stepInspection.alternatives()) y = wrap(graphics, alternative, body.x, y, body.width, theme.muted);
        return y;
    }

    private void inspectStep(int index) {
        stepInspection = Navigation.get().inspectStep(index);
        inspectedStep = index;
    }

    private int drawNoGoZones(GuiGraphicsExtractor graphics, Rect body, int y, MapVolume.Box box) {
        text(graphics, "No-go zones", body.x, y, theme.text);
        y += 13;
        field(graphics, body, zoneName, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Save A–B as zone", box != null && !zoneName.getValue().isBlank(),
            false, () -> run(() -> Navigation.get().putNoGoZone(zoneName.getValue(), pos(cornerA), pos(cornerB))));
        y += BUTTON_HEIGHT + 4;
        for (var zone : Navigation.get().noGoZones()) {
            drawButton(graphics, new Rect(body.x, y, body.width - 23, BUTTON_HEIGHT), zone.name(), true, false, () -> {
                var bounds = zone.bounds();
                cornerA = new Hit((int) bounds.minX(), (int) bounds.minY(), (int) bounds.minZ());
                cornerB = new Hit((int) bounds.maxX() - 1, (int) bounds.maxY() - 1, (int) bounds.maxZ() - 1);
                syncRegionFields();
                zoneName.setValue(zone.name());
                updateHighlights();
            });
            drawButton(graphics, new Rect(body.right() - 20, y, 20, BUTTON_HEIGHT), "×", true, false,
                () -> run(() -> Navigation.get().removeNoGoZone(zone.name())));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private int drawCorridors(GuiGraphicsExtractor graphics, Rect body, int y, MapVolume.Box box) {
        text(graphics, "Preferred corridors", body.x, y, theme.text);
        y += 13;
        field(graphics, body, corridorName, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Save corridor A–B",
            box != null && !corridorName.getValue().isBlank(), false,
            () -> run(() -> Navigation.get().putCorridor(corridorName.getValue(), pos(cornerA), pos(cornerB))));
        y += BUTTON_HEIGHT + 4;
        for (var corridor : Navigation.get().corridors()) {
            drawButton(graphics, new Rect(body.x, y, body.width - 23, BUTTON_HEIGHT), corridor.name(), true, false, () -> {
                var bounds = corridor.bounds();
                cornerA = new Hit((int) bounds.minX(), (int) bounds.minY(), (int) bounds.minZ());
                cornerB = new Hit((int) bounds.maxX() - 1, (int) bounds.maxY() - 1, (int) bounds.maxZ() - 1);
                syncRegionFields();
                corridorName.setValue(corridor.name());
                updateHighlights();
            });
            drawButton(graphics, new Rect(body.right() - 20, y, 20, BUTTON_HEIGHT), "×", true, false,
                () -> run(() -> Navigation.get().removeCorridor(corridor.name())));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private int drawEstimate(GuiGraphicsExtractor graphics, MapVolume.Box box, int x, int y, int available) {
        String dimensions = ((long) box.maxX() - box.minX() + 1) + " × "
            + ((long) box.maxY() - box.minY() + 1) + " × " + ((long) box.maxZ() - box.minZ() + 1);
        y = wrap(graphics, dimensions, x, y, available, theme.text);
        MapVolume.AreaStats area = viewport.stats(box);
        if (area == null) {
            y = wrap(graphics, "Counting captured blocks…", x, y, available, theme.muted);
        } else {
            y = wrap(graphics, numbers.format(area.solid()) + " occupied blocks", x, y, available, theme.muted);
            if (area.unknown() > 0) y = wrap(graphics, numbers.format(area.unknown()) + " uncaptured", x, y, available, theme.warning);
        }
        y += 6;
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), fillEstimate ? "Fill entire volume" : "Clear occupied blocks",
            true, false, () -> fillEstimate = !fillEstimate);
        y += BUTTON_HEIGHT + 4;
        double workRate = module.estimateRate();
        drawButton(graphics, new Rect(x, y, 18, BUTTON_HEIGHT), "−", workRate > .25, false,
            () -> module.adjustEstimateRate(-1));
        drawButton(graphics, new Rect(x + available - 18, y, 18, BUTTON_HEIGHT), "+", workRate < 128, false,
            () -> module.adjustEstimateRate(1));
        String rate = String.format(Locale.ROOT, "%.2f blk/s", workRate);
        centerText(graphics, ellipsis(rate, available - 40), x + available / 2, y + 5, theme.muted);
        y += BUTTON_HEIGHT + 5;
        if (fillEstimate || area != null) {
            long blocks = fillEstimate ? box.volume() : area.solid();
            String prefix = !fillEstimate && area.unknown() > 0 ? "Known work  " : "Estimate  ";
            y = wrap(graphics, prefix + duration(blocks / workRate), x, y, available, theme.accent);
        }
        return y;
    }

    private boolean updatePathDistance() {
        List<Step> path = Navigation.get().path();
        if (path != lastPath) {
            lastPath = path;
            inspectedStep = -1;
            stepInspection = null;
            double total = 0;
            for (Step step : path) {
                double horizontal = Math.hypot(step.to().x() - step.from().x(), step.to().z() - step.from().z());
                total += Math.hypot(horizontal, (step.to().y16() - step.from().y16()) / 16.0);
            }
            pathDistance = (int) Math.ceil(total);
        }
        return !lastPath.isEmpty();
    }

    private static String duration(double seconds) {
        long rounded = (long) Math.ceil(seconds);
        if (rounded >= 86_400) return rounded / 86_400 + "d " + rounded % 86_400 / 3_600 + "h";
        if (rounded >= 3_600) return rounded / 3_600 + "h " + rounded % 3_600 / 60 + "m";
        if (rounded >= 60) return rounded / 60 + "m " + rounded % 60 + "s";
        return rounded + "s";
    }

    private void drawSettings(GuiGraphicsExtractor graphics) {
        drawPanel(graphics, center);
        int x = center.x + 9;
        int available = center.width - 18;
        text(graphics, tab.label, x, center.y + 10, theme.text);
        graphics.fill(x, center.y + 25, x + available, center.y + 26, theme.border);
        Rect body = new Rect(x, center.y + 32, available, Math.max(0, center.height - 39));
        beginClip(graphics, body);
        int y = body.y - settingsScroll;
        if (tab == Tab.NAVIGATION || tab == Tab.TERRAIN || tab == Tab.PLANNING || tab == Tab.DISPLAY || tab == Tab.ACTIVITIES)
            y = wrap(graphics, module.editingWorldDefaults() ? "Editing world defaults" : "Editing global defaults", x, y, available, theme.muted) + 6;
        if (tab == Tab.NAVIGATION) y = drawNavigationForm(graphics, body, y);
        if (tab == Tab.TERRAIN) y = drawEditReserves(graphics, body, drawProtectedBlocks(graphics, body, y));
        if (tab == Tab.DISPLAY) y = drawStopKey(graphics, body, y);
        if (tab == Tab.ACTIVITIES) y = drawActivityForm(graphics, body, drawJobs(graphics, body, y) + 11) + 8;
        if (tab == Tab.TRIPS) y = drawTrips(graphics, body, y);
        if (tab == Tab.HISTORY) y = drawHistory(graphics, body, y);
        if (tab == Tab.PROFILES) y = drawProfiles(graphics, body, y);
        if (tab == Tab.PLANNING) {
            y = wrap(graphics, "Optimal finds the lowest-cost path. Quick favors faster planning.", x, y, available, theme.muted) + 6;
        }
        for (PlanningSettings.Control control : module.controls(tab.label)) {
            text(graphics, ellipsis(control.name(), available - 4), x + 2, y + 2, theme.text);
            int buttonWidth = 18;
            Rect minus = new Rect(x, y + 15, buttonWidth, BUTTON_HEIGHT);
            Rect plus = new Rect(x + available - buttonWidth, y + 15, buttonWidth, BUTTON_HEIGHT);
            Runnable decrease = control.decrease();
            Runnable increase = control.increase();
            if (decrease != null && decrease == increase) {
                drawButton(graphics, new Rect(x, y + 15, available, BUTTON_HEIGHT), control.value().get(), true, false,
                    () -> run(increase));
            } else {
                graphics.fill(minus.right() + 3, y + 15, plus.x - 3, y + 15 + BUTTON_HEIGHT, theme.field);
                centerText(graphics, ellipsis(control.value().get(), available - 2 * buttonWidth - 10),
                    x + available / 2, y + 20, theme.muted);
                drawButton(graphics, minus, "−", decrease != null, false, () -> run(decrease));
                drawButton(graphics, plus, "+", increase != null, false, () -> run(increase));
            }
            Rect row = new Rect(x, y, available, SETTING_HEIGHT);
            if (body.contains(mouseX, mouseY) && row.contains(mouseX, mouseY) && mouseY < y + 14
                && control.description() != null && !control.description().isBlank()) {
                graphics.setTooltipForNextFrame(Component.literal(control.description()), mouseX, mouseY);
            }
            y += SETTING_HEIGHT;
        }
        endClip(graphics);
        settingsScrollLimit = Math.max(0, y + settingsScroll - body.bottom() + 4);
        settingsScroll = Math.min(settingsScroll, settingsScrollLimit);
        drawScrollMark(graphics, center, body, settingsScroll, settingsScrollLimit);
    }


    private int drawProtectedBlocks(GuiGraphicsExtractor graphics, Rect body, int y) {
        y = wrap(graphics, "Protect these block IDs or #tags from route breaking.", body.x, y, body.width, theme.text) + 4;
        field(graphics, body, protectedBlocks, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Apply protected blocks", true, false,
            () -> run(() -> {
                module.setProtectedRouteBlocks(protectedBlocks.getValue());
                protectedBlocks.setValue(module.editableProtectedRouteBlocks());
            }));
        return y + BUTTON_HEIGHT + 9;
    }

    private int drawStopKey(GuiGraphicsExtractor graphics, Rect body, int y) {
        text(graphics, "Stop all Wayclef activity", body.x, y, theme.text);
        y += 14;
        String binding = module.stopKeyLabel();
        return paired(graphics, body, y, bindingStopKey ? "Press a key…" : binding, "Clear stop key",
            true, !binding.equals("Unbound"), () -> {
                error = null;
                bindingStopKey = true;
            }, () -> run(() -> {
                module.clearStopKey();
                bindingStopKey = false;
            })) + 7;
    }

    private int drawEditReserves(GuiGraphicsExtractor graphics, Rect body, int y) {
        y = wrap(graphics, "Keep this many blocks of each support material. Automated mining pauses before using the tool reserve.",
            body.x, y, body.width, theme.muted) + 4;
        int leftWidth = (body.width - 4) / 2;
        int rightX = body.x + leftWidth + 4;
        int rightWidth = body.width - leftWidth - 4;
        text(graphics, ellipsis("Support blocks", leftWidth), body.x, y, theme.text);
        text(graphics, ellipsis("Tool durability", rightWidth), rightX, y, theme.text);
        y += 12;
        field(graphics, body, supportReserve, new Rect(body.x, y, leftWidth, BUTTON_HEIGHT));
        field(graphics, body, durabilityReserve, new Rect(rightX, y, rightWidth, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Apply reserves",
            !supportReserve.getValue().isBlank() && !durabilityReserve.getValue().isBlank(), false, () -> run(() -> {
                module.setEditReserves(coordinate(supportReserve, "support reserve"), coordinate(durabilityReserve, "tool reserve"));
                refreshReserveFields();
            }));
        return y + BUTTON_HEIGHT + 8;
    }

    private void refreshReserveFields() {
        supportReserve.setValue(Integer.toString(module.editableMaterialReserve()));
        durabilityReserve.setValue(Integer.toString(module.editableToolReserve()));
    }

    private int drawProfiles(GuiGraphicsExtractor graphics, Rect body, int y) {
        y = paired(graphics, body, y, "Global defaults", "World defaults", true, module.hasPreferenceScope(),
            () -> module.editWorldDefaults(false), () -> module.editWorldDefaults(true));
        y = wrap(graphics, module.editingWorldDefaults() ? "Editing world overrides. Other settings inherit global values."
            : "Editing global defaults. Saved world overrides take priority in that world.", body.x, y, body.width, theme.muted) + 4;
        if (module.preferenceError() != null) y = wrap(graphics, module.preferenceError(), body.x, y, body.width, theme.warning) + 4;
        if (module.supportsHostedWorldName()) {
            field(graphics, body, hostedWorldName, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
            y += BUTTON_HEIGHT + 4;
            y = paired(graphics, body, y, "Use hosted world", "Use server default", !hostedWorldName.getValue().isBlank(),
                !module.hostedWorld().isBlank(), () -> run(() -> module.setHostedWorld(hostedWorldName.getValue())), () -> run(() -> {
                    module.setHostedWorld("");
                    hostedWorldName.setValue("");
                }));
            if (!module.hostedWorld().isBlank()) y = wrap(graphics, "Active world: " + module.hostedWorld(), body.x, y, body.width, theme.accent) + 4;
        }
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Clear current world overrides", module.hasPreferenceScope(),
            false, () -> run(module::clearWorldDefaults));
        y += BUTTON_HEIGHT + 8;
        field(graphics, body, presetName, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Save active movement preset", !presetName.getValue().isBlank(),
            true, () -> run(() -> {
                module.saveMovementPreset(presetName.getValue());
                presetNames = module.movementPresetNames();
            }));
        y += BUTTON_HEIGHT + 4;
        y = wrap(graphics, module.editingWorldDefaults() ? "Select a preset to apply it to this world." : "Select a preset to apply it globally.",
            body.x, y, body.width, theme.muted) + 4;
        for (String name : presetNames) {
            drawButton(graphics, new Rect(body.x, y, body.width - 23, BUTTON_HEIGHT), name, true, false,
                () -> run(() -> module.applyMovementPreset(name)));
            drawButton(graphics, new Rect(body.right() - 20, y, 20, BUTTON_HEIGHT), "×", true, false, () -> run(() -> {
                module.deleteMovementPreset(name);
                presetNames = module.movementPresetNames();
            }));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private int drawJobs(GuiGraphicsExtractor graphics, Rect body, int y) {
        JobQueue queue = JobQueue.get();
        boolean active = queue.active();
        text(graphics, "Job queue", body.x, y, theme.text);
        y += 13;
        field(graphics, body, jobSpec, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        int half = (body.width - 4) / 2;
        drawButton(graphics, new Rect(body.x, y, half, BUTTON_HEIGHT), "Queue step", !active && !jobSpec.getValue().isBlank(), false,
            () -> run(() -> {
                queue.enqueue(jobSpec.getValue(), selectionBox(), pos(selected));
                jobSpec.setValue("");
            }));
        drawButton(graphics, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT),
            jobHelp ? "Hide commands" : "Job commands", true, false, () -> jobHelp = !jobHelp);
        y += BUTTON_HEIGHT + 4;
        if (jobHelp) {
            for (String example : List.of("gather <item> <amount>", "mine <block[,block]> <count|all> [radius]", "collect <item[,item]> <count|all> [radius]", "crops <limit> [radius]",
                "trees <log[,log]> <limit> [replant]", "quarry <limit> [preserve-block[,block]]",
                "tunnel <width> <height> <grade-percent> <torch-spacing> <limit>", "craft <item> <count>",
                "furnace <input> <fuel> <output> <count>", "unload|restock <item=count[,item=count]> [max-container-checks]", "return")) {
                y = wrap(graphics, example, body.x, y, body.width, theme.muted);
            }
            y = wrap(graphics, "Set A–B for region jobs, or enter a radius of 1–32 for mining, pickup, and crops. Pick the tunnel destination or station on the map.",
                body.x, y + 3, body.width, theme.muted) + 5;
        }
        List<String> steps = queue.steps();
        drawButton(graphics, new Rect(body.x, y, half, BUTTON_HEIGHT), active ? "Stop queue" : "Start queue", active || !steps.isEmpty(),
            !active, () -> run(() -> {
                if (active) queue.stop();
                else {
                    module.startJobs();
                    minecraft.gui.setScreen(null);
                }
            }));
        drawButton(graphics, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT), "Clear queue", !active && !steps.isEmpty(),
            false, () -> run(queue::clear));
        y += BUTTON_HEIGHT + 5;
        y = wrap(graphics, queue.statusText(), body.x, y, body.width, active ? theme.accent : theme.muted);
        if (!active && !steps.isEmpty()) {
            y += 4;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Check queue", true, false,
                () -> run(queue::preflight));
            y += BUTTON_HEIGHT + 4;
            JobQueue.Preflight checked = queue.lastPreflight();
            if (checked != null) {
                if (checked.issues().isEmpty()) {
                    y = wrap(graphics, "No issues found in " + checked.steps() + " checked steps.", body.x, y, body.width, theme.accent);
                } else {
                    for (JobQueue.PreflightIssue issue : checked.issues()) {
                        y = wrap(graphics, "Step " + issue.step() + " • " + issue.message(), body.x, y, body.width,
                            issue.blocking() ? theme.warning : theme.muted);
                    }
                }
                y = wrap(graphics, "Checked loaded terrain and current inventory. Earlier steps can change later requirements.",
                    body.x, y, body.width, theme.muted) + 5;
            }
        }
        int current = queue.currentStepIndex();
        for (int index = 0; index < steps.size(); index++) {
            String prefix = index == current ? "Current  " : "";
            y = wrap(graphics, prefix + (index + 1) + ". " + steps.get(index), body.x, y, body.width,
                index == current ? theme.accent : current >= 0 && index < current ? theme.dim : theme.text);
        }
        return y;
    }

    private int drawTrips(GuiGraphicsExtractor graphics, Rect body, int y) {
        ItineraryController trips = ItineraryController.get();
        boolean connected = minecraft.level != null;
        boolean running = trips.active();
        y = wrap(graphics, trips.statusText(), body.x, y, body.width, running ? theme.accent : theme.muted) + 4;
        y = paired(graphics, body, y, running ? "Pause trip" : "Resume progress", "Stop trip", running || trips.canResume(),
            running || trips.canResume(), () -> run(() -> {
                if (running) trips.pause();
                else module.resumeTrip();
            }), () -> run(trips::cancel));
        field(graphics, body, tripName, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        String repeat = switch (tripRepeat) {
            case ONCE -> "Once";
            case CIRCUIT -> "Circuit";
            case BACK_AND_FORTH -> "Back and forth";
        };
        y = paired(graphics, body, y, repeat, "New draft", connected, connected,
            () -> tripRepeat = RouteItinerary.Repeat.values()[(tripRepeat.ordinal() + 1) % RouteItinerary.Repeat.values().length], this::newTrip);
        int third = (body.width - 8) / 3;
        field(graphics, body, tripX, new Rect(body.x, y, third, BUTTON_HEIGHT));
        field(graphics, body, tripY, new Rect(body.x + third + 4, y, third, BUTTON_HEIGHT));
        field(graphics, body, tripZ, new Rect(body.x + 2 * (third + 4), y, body.width - 2 * (third + 4), BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        int half = (body.width - 4) / 2;
        field(graphics, body, tripWait, new Rect(body.x, y, half, BUTTON_HEIGHT));
        field(graphics, body, tripDimension, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        field(graphics, body, portalExit, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT),
            "Stop transport  " + (tripTransport == null ? "Inherit" : tripTransport.name()), connected, false, this::cycleTripTransport);
        y += BUTTON_HEIGHT + 4;
        y = paired(graphics, body, y, "Add stop", "Use picked block", connected && tripStops.size() < RouteItinerary.MAX_STOPS,
            connected && selected != null, () -> run(() -> tripStops.add(readTripStop())), () -> {
                tripX.setValue(Integer.toString(selected.x()));
                tripY.setValue(Integer.toString(selected.y() + 1));
                tripZ.setValue(Integer.toString(selected.z()));
            });
        if (tripStop >= 0) {
            y = paired(graphics, body, y, "Update stop", "Remove stop", connected, true,
                () -> run(() -> tripStops.set(tripStop, readTripStop())), () -> {
                    tripStops.remove(tripStop);
                    tripStop = -1;
                });
            y = paired(graphics, body, y, "Move earlier", "Move later", tripStop > 0, tripStop + 1 < tripStops.size(),
                () -> moveTripStop(-1), () -> moveTripStop(1));
        }
        for (int i = 0; i < tripStops.size(); i++) {
            RouteItinerary.Stop stop = tripStops.get(i);
            int index = i;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), (i + 1) + ". " + describeStop(stop), true,
                tripStop == i, () -> editTripStop(index));
            y += BUTTON_HEIGHT + 3;
        }
        y = paired(graphics, body, y + 3, "Save trip", "Start trip", connected && !tripName.getValue().isBlank(),
            connected && !running && !tripStops.isEmpty(), () -> run(() -> {
                trips.save(currentTrip());
                savedTrips = trips.list();
            }), () -> run(() -> module.startTrip(currentTrip(), module.options())));
        for (String name : savedTrips) {
            drawButton(graphics, new Rect(body.x, y, body.width - 24, BUTTON_HEIGHT), name, connected, false, () -> run(() -> openTrip(trips.load(name))));
            drawButton(graphics, new Rect(body.right() - 20, y, 20, BUTTON_HEIGHT), "×", connected, false, () -> run(() -> {
                trips.delete(name);
                savedTrips = trips.list();
            }));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private void newTrip() {
        tripName.setValue("");
        tripStops.clear();
        tripStop = -1;
        tripRepeat = RouteItinerary.Repeat.ONCE;
        tripTransport = null;
        for (EditBox field : List.of(tripX, tripY, tripZ, tripWait, tripDimension, portalExit)) field.setValue("");
    }

    private void openTrip(RouteItinerary trip) {
        newTrip();
        tripName.setValue(trip.name());
        tripRepeat = trip.repeat();
        tripStops.addAll(trip.stops());
    }

    private RouteItinerary currentTrip() { return new RouteItinerary(tripName.getValue(), tripStops, tripRepeat); }

    private void moveTripStop(int direction) {
        var stop = tripStops.remove(tripStop);
        tripStop += direction;
        tripStops.add(tripStop, stop);
    }

    private void cycleTripTransport() {
        var modes = TransportMode.values();
        int next = tripTransport == null ? 0 : tripTransport.ordinal() + 1;
        tripTransport = next == modes.length ? null : modes[next];
    }

    private static String describeStop(RouteItinerary.Stop stop) {
        Goal goal = stop.goal();
        return goal.x() + " " + (goal.y16() == null ? "*" : goal.y16() / 16) + " " + goal.z() + " • " + stop.dimension()
            + (stop.portal() == null ? "" : " → " + stop.portal().dimension())
            + (stop.transport() == null ? "" : " • " + stop.transport()) + (stop.waitSeconds() == 0 ? "" : " • " + stop.waitSeconds() + "s");
    }

    private void editTripStop(int index) {
        tripStop = index;
        RouteItinerary.Stop stop = tripStops.get(index);
        Goal goal = stop.goal();
        tripX.setValue(Integer.toString(goal.x()));
        tripY.setValue(goal.y16() == null ? "" : Integer.toString(goal.y16() / 16));
        tripZ.setValue(Integer.toString(goal.z()));
        tripWait.setValue(Integer.toString(stop.waitSeconds()));
        tripDimension.setValue(stop.dimension());
        tripTransport = stop.transport();
        var portal = stop.portal();
        portalExit.setValue(portal == null ? "" : portal.dimension() + " " + portal.area().x() + " "
            + (portal.area().y16() == null ? "~" : Integer.toString(portal.area().y16() / 16))
            + " " + portal.area().z() + " " + portal.area().radius());
    }

    private RouteItinerary.Stop readTripStop() {
        String dimension = tripDimension.getValue().strip();
        if (dimension.isEmpty()) dimension = ItineraryController.currentDimension();
        int wait = tripWait.getValue().isBlank() ? 0 : coordinate(tripWait, "wait seconds");
        int x = coordinate(tripX, "X"), z = coordinate(tripZ, "Z");
        Integer y = tripY.getValue().isBlank() ? null : tripHeight(coordinate(tripY, "Y"));
        String exit = portalExit.getValue().strip();
        if (exit.isEmpty()) return new RouteItinerary.Stop(dimension, new Goal(x, y, z, 0), wait, null, tripTransport);
        String[] fields = exit.split("\\s+");
        if (fields.length < 4 || fields.length > 5) throw new IllegalArgumentException("Enter the exit dimension and X Y Z, with an optional radius.");
        Integer exitHeight = fields[2].equals("~") ? null : tripHeight(Integer.parseInt(fields[2]));
        var area = new Goal(Integer.parseInt(fields[1]), exitHeight,
            Integer.parseInt(fields[3]), fields.length == 5 ? Integer.parseInt(fields[4]) : 8);
        return new RouteItinerary.Stop(dimension, new Goal(x, y, z, 0), wait, new RouteItinerary.PortalExit(fields[0], area));
    }

    private static int tripHeight(int height) {
        long units = (long) height * 16;
        if (units < dev.soprano.path.Node.MIN_Y16 || units > dev.soprano.path.Node.MAX_Y16)
            throw new IllegalArgumentException("Trip height is outside supported world bounds.");
        return (int) units;
    }

    private int drawHistory(GuiGraphicsExtractor graphics, Rect body, int y) {
        if (historyEntries.isEmpty()) return wrap(graphics, "No saved tasks in this world.", body.x, y, body.width, theme.muted);
        if (historyPreview != null) {
            TaskHistory.Task task = historyPreview.task();
            y = wrap(graphics, task.kind() + " • " + task.goal(), body.x, y, body.width, theme.accent);
            y = wrap(graphics, task.dimension(), body.x, y, body.width, theme.muted) + 5;
            boolean here = task.dimension().equals(SopranoHistory.dimension());
            y = paired(graphics, body, y, "Preview task", "Rerun task", here, here, () -> run(this::previewHistory), () -> run(() -> {
                module.reviewHistory(historyPreview, true);
                minecraft.gui.setScreen(null);
            }));
        }
        for (TaskHistory.Entry entry : historyEntries) {
            String label = HISTORY_TIME.format(entry.recordedAt()) + " • " + entry.task().kind() + " • " + entry.task().goal();
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), label, true, entry.equals(historyPreview),
                () -> run(() -> historyPreview = SopranoHistory.reopen(entry.id())));
            y += BUTTON_HEIGHT + 3;
        }
        return y;
    }

    private void previewHistory() {
        module.reviewHistory(historyPreview, false);
        switch (historyPreview.task().kind()) {
            case "route", "follow" -> selectTab(Tab.MAP);
            case "build" -> {
                viewport.buildPlan().replace(SopranoHistory.blueprint(historyPreview).snapshot());
                builder.pane = BuildPane.BLUEPRINT;
                selectTab(Tab.BUILD);
            }
            case "job-queue" -> selectTab(Tab.ACTIVITIES);
            case "trip" -> {
                openTrip(SopranoHistory.trip(historyPreview));
                selectTab(Tab.TRIPS);
            }
        }
    }

    private int paired(GuiGraphicsExtractor graphics, Rect body, int y, String leftLabel, String rightLabel,
                       boolean leftEnabled, boolean rightEnabled, Runnable leftAction, Runnable rightAction) {
        int half = (body.width - 4) / 2;
        drawButton(graphics, new Rect(body.x, y, half, BUTTON_HEIGHT), leftLabel, leftEnabled, false, leftAction);
        drawButton(graphics, new Rect(body.x + half + 4, y, body.width - half - 4, BUTTON_HEIGHT), rightLabel, rightEnabled, false, rightAction);
        return y + BUTTON_HEIGHT + 4;
    }

    private int drawActivityForm(GuiGraphicsExtractor graphics, Rect body, int y) {
        int x = body.x;
        int available = body.width;
        ReplaceActivity activity = ReplaceActivity.get();
        boolean running = activity.active();
        MapVolume.Box box = selectionBox();

        text(graphics, "Replace blocks", x, y, theme.text);
        y = wrap(graphics, "Replace exposed cubes in A–B. Hold the new block, or keep it in your offhand while using a mining tool.",
            x, y + 15, available, theme.muted) + 8;
        y = wrap(graphics, box == null ? "Region  ·  Set A and B on the map."
            : "Region  ·  " + numbers.format(box.volume()) + " voxels", x, y, available, box == null ? theme.warning : theme.text);
        y = wrap(graphics, replaceSource == null ? "Source  ·  Pick a block on the map."
            : "Source  ·  " + viewport.blockName(replaceSource) + "  " + coordinates(replaceSource),
            x, y + 3, available, replaceSource == null ? theme.warning : theme.text) + 5;

        int half = (available - 4) / 2;
        drawButton(graphics, new Rect(x, y, half, BUTTON_HEIGHT), "Open map", true, false, () -> selectTab(Tab.MAP));
        drawButton(graphics, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT), "Use picked block",
            selected != null && !running, false, () -> setReplaceSource(selected));
        y += BUTTON_HEIGHT + 10;

        if (replacement != null) {
            ReplaceActivity.Preview preview = replacement.preview();
            y = wrap(graphics, preview.source() + " → " + preview.replacement(), x, y, available, theme.accent) + 3;
            y = wrap(graphics, numbers.format(preview.matches()) + " exposed matches  ·  "
                + numbers.format(preview.available()) + " available", x, y, available, theme.text);
            if (preview.unknown() > 0) y = wrap(graphics, numbers.format(preview.unknown()) + " unloaded voxels skipped",
                x, y, available, theme.warning);
            if (preview.missing() > 0) y = wrap(graphics, "Short by " + numbers.format(preview.missing())
                + " blocks. The job stops when the held stack runs out.", x, y, available, theme.warning);
            double workRate = module.estimateRate();
            if (preview.matches() > 0) y = wrap(graphics, "Break + place estimate  ·  "
                + duration(2.0 * preview.matches() / workRate), x, y, available, theme.muted);
            y += 5;
        }

        drawButton(graphics, new Rect(x, y, half, BUTTON_HEIGHT), "Preview matches", box != null && replaceSource != null && !running,
            false, () -> run(this::previewReplace));
        drawButton(graphics, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT), "Start Replace",
            replacement != null && replacement.preview().matches() > 0 && !running, true, () -> run(this::startReplace));
        y += BUTTON_HEIGHT + 4;
        if (replacement != null && !running) y = wrap(graphics, "Start returns you to the game to work.", x, y, available, theme.muted) + 5;
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), "Cancel activity", running, false, activity::stop);
        y += BUTTON_HEIGHT + 8;
        return wrap(graphics, activity.statusText(), x, y, available, running ? theme.accent : theme.muted);
    }

    private void setReplaceSource(Hit source) {
        replaceSource = source;
        replacement = null;
        error = null;
    }

    private void previewReplace() {
        replacement = null;
        MapVolume.Box region = selectionBox();
        if (region == null || replaceSource == null) throw new IllegalStateException("Select a region and a source block first.");
        BlockPos source = new BlockPos(replaceSource.x(), replaceSource.y(), replaceSource.z());
        replacement = new Replacement(region, source, ReplaceActivity.get().preview(region, source));
    }

    private void startReplace() {
        if (replacement == null) throw new IllegalStateException("Preview the region first.");
        Replacement approved = replacement;
        ReplaceActivity.Preview current = ReplaceActivity.get().preview(approved.region(), approved.source());
        if (!current.source().equals(approved.preview().source()) || !current.replacement().equals(approved.preview().replacement())
            || current.matches() != approved.preview().matches() || current.unknown() != approved.preview().unknown()) {
            replacement = new Replacement(approved.region(), approved.source(), current);
            throw new IllegalStateException("The preview changed. Review it before starting.");
        }
        module.startReplace(approved.region(), approved.source());
        minecraft.gui.setScreen(null);
    }

    private int drawNavigationForm(GuiGraphicsExtractor graphics, Rect body, int y) {
        int x = body.x;
        int available = body.width;
        text(graphics, "Coordinate target", x, y, theme.text);
        y += 15;
        drawButton(graphics, new Rect(x, y, available, BUTTON_HEIGHT), stayInRegion ? "Route bounds: A–B" : "Route bounds: off",
            selectionBox() != null || stayInRegion, stayInRegion, () -> stayInRegion = !stayInRegion);
        y += BUTTON_HEIGHT + 4;
        int third = (available - 8) / 3;
        field(graphics, body, goalX, new Rect(x, y, third, BUTTON_HEIGHT));
        field(graphics, body, goalY, new Rect(x + third + 4, y, third, BUTTON_HEIGHT));
        field(graphics, body, goalZ, new Rect(x + 2 * third + 8, y, available - 2 * third - 8, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        int half = (available - 4) / 2;
        y = wrap(graphics, "Leave blank to use your route limits. Use 0 to disable an action.", x, y, available, theme.muted) + 4;
        text(graphics, "Breaks (" + module.routeBreakLimit() + ")", x, y, theme.muted);
        text(graphics, "Placements (" + module.routePlaceLimit() + ")", x + half + 4, y, theme.muted);
        y += 11;
        field(graphics, body, breakLimit, new Rect(x, y, half, BUTTON_HEIGHT));
        field(graphics, body, placeLimit, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        drawButton(graphics, new Rect(x, y, half, BUTTON_HEIGHT), "Preview", true, false, () -> run(() -> useCoordinates(false)));
        drawButton(graphics, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT), "Go", true, true,
            () -> run(() -> useCoordinates(true)));
        y += BUTTON_HEIGHT + 10;
        y = paired(graphics, body, y, "Return to safe point", "Preview line of sight", minecraft.level != null,
            selected != null, () -> run(() -> module.request(bounded(Navigation.get().safeReturnGoal()), true)), () -> run(() -> {
                module.request(bounded(Navigation.get().lineOfSightGoal(pos(selected), 6)), false);
                selectTab(Tab.MAP);
            }));
        y = drawAdvancedGoals(graphics, body, y + 7);
        text(graphics, "Follow player", x, y, theme.text);
        y += 15;
        field(graphics, body, followName, new Rect(x, y, available - 52, BUTTON_HEIGHT));
        drawButton(graphics, new Rect(x + available - 47, y, 47, BUTTON_HEIGHT), "Follow", true, false,
            () -> run(() -> module.followPlayer(followName.getValue().strip())));
        y += BUTTON_HEIGHT + 7;
        drawButton(graphics, new Rect(x, y, half, BUTTON_HEIGHT), "Pause", true, false, () -> run(module::pause));
        drawButton(graphics, new Rect(x + half + 4, y, available - half - 4, BUTTON_HEIGHT), "Resume", true, false,
            () -> run(module::resume));
        y += BUTTON_HEIGHT + 12;
        graphics.fill(x, y, x + available, y + 1, theme.border);
        return y + 9;
    }

    private int drawAdvancedGoals(GuiGraphicsExtractor graphics, Rect body, int y) {
        int x = body.x, available = body.width, third = (available - 8) / 3;
        text(graphics, "Known block", x, y, theme.text);
        y += 13;
        field(graphics, body, knownBlock, new Rect(x, y, available, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        y = goalButtons(graphics, body, y, () -> Navigation.get().knownBlockGoal(knownBlock.getValue().strip()),
            !knownBlock.getValue().isBlank());
        text(graphics, "Reachable height", x, y + 4, theme.text);
        y += 17;
        field(graphics, body, minGoalY, new Rect(x, y, third, BUTTON_HEIGHT));
        field(graphics, body, maxGoalY, new Rect(x + third + 4, y, third, BUTTON_HEIGHT));
        field(graphics, body, heightRange, new Rect(x + 2 * third + 8, y, available - 2 * third - 8, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        y = goalButtons(graphics, body, y, () -> {
            int minimum = coordinate(minGoalY, "minimum height");
            int maximum = maxGoalY.getValue().isBlank() ? minimum : coordinate(maxGoalY, "maximum height");
            return Navigation.get().heightGoal(minimum, maximum, coordinate(heightRange, "search radius"));
        }, !minGoalY.getValue().isBlank() && !heightRange.getValue().isBlank());
        text(graphics, "Travel along current heading", x, y + 4, theme.text);
        y += 17;
        field(graphics, body, facingDistance, new Rect(x, y, available, BUTTON_HEIGHT));
        y += BUTTON_HEIGHT + 4;
        return goalButtons(graphics, body, y, () -> Navigation.get().facingGoal(Double.parseDouble(facingDistance.getValue().strip())),
            !facingDistance.getValue().isBlank()) + 8;
    }

    private int goalButtons(GuiGraphicsExtractor graphics, Rect body, int y, Supplier<Goal> target, boolean enabled) {
        return paired(graphics, body, y, "Preview", "Go", enabled, enabled,
            () -> run(() -> useRouteGoal(target.get(), false)), () -> run(() -> useRouteGoal(target.get(), true)));
    }

    private void field(GuiGraphicsExtractor graphics, Rect body, EditBox field, Rect position) {
        graphics.fill(position.x, position.y, position.right(), position.bottom(), theme.field);
        graphics.outline(position.x, position.y, position.width, position.height, field.isFocused() ? theme.accent : theme.border);
        int inset = Math.max(1, (position.height - 9) / 2);
        field.setRectangle(Math.max(1, position.width - 10), position.height - inset, position.x + 5, position.y + inset);
        field.setTextColor(theme.text);
        boolean visible = position.y >= body.y && position.bottom() <= body.bottom();
        if (visible) fieldHint(graphics, field, field.getMessage().getString(), position.width - 10);
        field.setVisible(visible);
    }

    private void hideFields() {
        pageFields.values().forEach(field -> field.setVisible(false));
        if (goalX == null) return;
        goalX.setVisible(false);
        goalY.setVisible(false);
        goalZ.setVisible(false);
        followName.setVisible(false);
        locationName.setVisible(false);
        for (EditBox field : List.of(breakLimit, placeLimit, supportReserve, durabilityReserve, zoneName, jobSpec, tripName, tripX, tripY, tripZ,
            tripWait, tripDimension, portalExit, knownBlock, minGoalY, maxGoalY, heightRange, facingDistance, corridorName,
            presetName, hostedWorldName, lowerCut, upperCut, mapNote, regionA, regionB, faceCoordinate, regionName,
            importedPresetName, protectedBlocks)) field.setVisible(false);
        builder.hideFields();
    }

    private void useCoordinates(boolean walk) {
        int x = coordinate(goalX, "X");
        Integer y = goalY.getValue().isBlank() ? null : coordinate(goalY, "Y");
        int z = coordinate(goalZ, "Z");
        useRouteGoal(module.coordinateGoal(x, y, z), walk);
    }

    private void useRouteGoal(Goal goal, boolean walk) {
        module.request(bounded(goal), walk, routeOptions());
        if (!walk) selectTab(Tab.MAP);
    }

    private Navigation.Options routeOptions() {
        int breaks = breakLimit.getValue().isBlank() ? module.routeBreakLimit() : coordinate(breakLimit, "break limit");
        int placements = placeLimit.getValue().isBlank() ? module.routePlaceLimit() : coordinate(placeLimit, "placement limit");
        return module.options(breaks, placements);
    }

    private Goal bounded(Goal goal) {
        if (!stayInRegion) return goal;
        var box = selectionBox();
        if (box == null) throw new IllegalStateException("Select A and B for the route bounds.");
        return goal.within(RouteExclusions.Bounds.blocksInclusive(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
    }

    private static int coordinate(EditBox field, String axis) {
        try {
            return Integer.parseInt(field.getValue().strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter a whole number for " + axis + ".");
        }
    }

    private void useSelected(boolean walk) {
        if (selected == null || viewport == null || viewport.snapshotName() != null) return;
        module.request(bounded(Navigation.get().goalAtBlock(pos(selected))), walk, routeOptions());
    }

    private static BlockPos pos(Hit hit) { return hit == null ? null : new BlockPos(hit.x(), hit.y(), hit.z()); }

    private void openMapContext(double x, double y) {
        Hit hit = viewport.pick(x, y);
        if (hit == null && selectionBox() == null) return;
        if (hit != null) selectObservedBlock(hit);
        updateHighlights();
        int w = Math.min(119, Math.max(1, width - 8));
        int h = 7 * (BUTTON_HEIGHT + 3) + 5;
        contextMenu = new Rect(Math.clamp((int) x, 4, Math.max(4, width - w - 4)),
            Math.clamp((int) y, 4, Math.max(4, height - h - 4)), w, h);
        contextActionStart = actions.size();
        viewport.clearMoveKeys();
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics) {
        Rect menu = contextMenu;
        drawPanel(graphics, menu);
        int x = menu.x + 3, y = menu.y + 3, w = menu.width - 6;
        boolean live = viewport.snapshotName() == null;
        boolean block = selected != null;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Go to block", live && block, true, () -> run(() -> useSelected(true)));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Preview route", live && block, false, () -> run(() -> useSelected(false)));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Set preview start", block, false, () -> run(() -> viewport.setPreviewStart(selected)));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Set preview end", block, false, () -> run(() -> viewport.setPreviewEnd(selected)));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Route to line of sight", live && block, false,
            () -> run(() -> module.request(bounded(Navigation.get().lineOfSightGoal(pos(selected), 6)), true)));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), "Save as location", live && block, false, () -> run(() -> {
            viewport.focusLocation(pos(selected));
            locationName.setValue("Location " + selected.x() + " " + selected.z());
            selectTab(Tab.LOCATIONS);
        }));
        y += BUTTON_HEIGHT + 3;
        drawButton(graphics, new Rect(x, y, w, BUTTON_HEIGHT), selectionBox() == null ? "Inspect block" : "Inspect region",
            block || selectionBox() != null, false, () -> {
                selectTab(Tab.MAP);
                inspectorScroll = 0;
                updateHighlights();
            });
    }

    private MapVolume.Box selectionBox() {
        if (cornerA == null || cornerB == null) return null;
        return regionBetween(cornerA, cornerB);
    }

    private void updateHighlights() {
        MapVolume.Box region = selectionBox();
        if (replacement != null && !replacement.region().equals(region)) replacement = null;
        if (viewport == null) return;
        if (tab == Tab.BUILD) viewport.highlight(builder.marker, builder.box());
        else viewport.highlight(selected, region);
    }

    private static String coordinates(Hit hit) { return "X " + hit.x() + "   Y " + hit.y() + "   Z " + hit.z(); }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (contextMenu != null) {
            Rect menu = contextMenu;
            contextMenu = null;
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && menu.contains(event.x(), event.y())) {
                for (Action action : actions.subList(Math.min(contextActionStart, actions.size()), actions.size())) {
                    if (action.area.contains(event.x(), event.y())) {
                        clearFocus();
                        action.run.run();
                        break;
                    }
                }
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Action action : actions) {
                if (action.area.contains(event.x(), event.y())) {
                    clearFocus();
                    action.run.run();
                    return true;
                }
            }
        }
        if (mapTab() && viewport != null && map.contains(event.x(), event.y())
            && (event.button() == InputConstants.MOUSE_BUTTON_LEFT || event.button() == InputConstants.MOUSE_BUTTON_RIGHT
            || event.button() == InputConstants.MOUSE_BUTTON_MIDDLE)) {
            if (mapButton != -1) return true;
            clearFocus();
            mapButton = event.button();
            dragDistance = 0;
            if (selectingRegion()) startRegion(event.x(), event.y());
            return true;
        }
        boolean handled = super.mouseClicked(event, doubleClick);
        if (viewport != null && (tab == Tab.BUILD && (builder.typing() || mapFieldFocused())
            || tab == Tab.LOCATIONS && locationName.isFocused() || tab == Tab.MAP && mapFieldFocused())) viewport.clearMoveKeys();
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (mapTab() && viewport != null && mapButton == event.button()) {
            if (selectingRegion()) {
                updateRegionPreview(event.x(), event.y());
                return true;
            }
            dragDistance += Math.hypot(deltaX, deltaY);
            if (dragDistance >= 2.5) {
                if (mapButton == InputConstants.MOUSE_BUTTON_LEFT) viewport.orbit(deltaX, deltaY);
                else viewport.pan(deltaX, deltaY);
            }
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (mapTab() && viewport != null && mapButton == event.button()) {
            if (selectingRegion()) {
                mapButton = -1;
                finishRegion(event.x(), event.y());
                return true;
            }
            if (dragDistance < 2.5 && map.contains(event.x(), event.y())) {
                if (mapButton == InputConstants.MOUSE_BUTTON_LEFT) {
                    if (tab == Tab.BUILD) run(() -> builder.click(event.x(), event.y()));
                    else {
                        selectObservedBlock(viewport.pick(event.x(), event.y()));
                        error = null;
                        updateHighlights();
                    }
                } else if (mapButton == InputConstants.MOUSE_BUTTON_RIGHT && tab != Tab.BUILD) {
                    openMapContext(event.x(), event.y());
                }
            }
            mapButton = -1;
            return true;
        }
        return super.mouseReleased(event);
    }

    private boolean mapFieldFocused() {
        for (EditBox field : List.of(zoneName, corridorName, lowerCut, upperCut, mapNote, regionA, regionB, faceCoordinate,
            regionName, importedPresetName)) {
            if (field.visible && field.isFocused()) return true;
        }
        return false;
    }

    private void selectObservedBlock(Hit hit) {
        selected = hit;
        if (hit != null && viewport.snapshotName() == null) Navigation.get().observeBlock(pos(hit));
    }

    private boolean selectingRegion() {
        return mapButton == InputConstants.MOUSE_BUTTON_MIDDLE;
    }

    private void startRegion(double x, double y) {
        Hit anchor = regionPoint(x, y);
        if (anchor == null) {
            mapButton = -1;
            return;
        }
        error = null;
        if (tab == Tab.BUILD) {
            builder.beginRegion(anchor);
            syncRegionFields();
        } else {
            cornerA = selected = anchor;
            cornerB = null;
            syncRegionFields();
        }
        updateHighlights();
    }

    private void updateRegionPreview(double x, double y) {
        Hit end = map.contains(x, y) ? regionPoint(x, y) : null;
        if (tab == Tab.BUILD) {
            builder.previewRegion(end);
        } else {
            viewport.highlight(selected, cornerA == null || end == null ? null : regionBetween(cornerA, end));
        }
    }

    private void finishRegion(double x, double y) {
        Hit end = map.contains(x, y) ? regionPoint(x, y) : null;
        if (tab == Tab.BUILD) {
            builder.finishRegion(end);
            syncRegionFields();
        } else {
            cornerB = end;
            if (end != null) selected = end;
            syncRegionFields();
        }
        updateHighlights();
    }

    private Hit regionPoint(double x, double y) {
        if (tab == Tab.BUILD && builder.planeOnly) {
            Cell cell = viewport.pickWorkPlane(x, y);
            return cell == null ? null : new Hit(cell.x(), cell.y(), cell.z());
        }
        return viewport.pick(x, y);
    }

    private static MapVolume.Box regionBetween(Hit first, Hit second) {
        return new MapVolume.Box(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (mapTab() && viewport != null) {
            if (map.contains(x, y)) {
                viewport.zoom(scrollY);
                return true;
            }
            if (inspector.contains(x, y)) {
                if (tab == Tab.BUILD) builder.scrollBy(scrollY);
                else inspectorScroll = scroll(inspectorScroll, inspectorScrollLimit, scrollY);
                return true;
            }
        } else if (center.contains(x, y)) {
            boolean handled = switch (tab) {
                case GET -> catalog.scroll(x, y, scrollY);
                case GOTO, FOLLOW, WAYPOINTS, STATS, HELP -> tools.scroll(x, y, scrollY);
                case SCHEMATICS -> schematics.scroll(x, y, scrollY);
                case SETTINGS -> settings.scroll(y, scrollY);
                default -> false;
            };
            if (handled) { clearFocus(); return true; }
            if (!tab.isAdvanced() && tab != Tab.ADVANCED) return super.mouseScrolled(x, y, scrollX, scrollY);
            settingsScroll = scroll(settingsScroll, settingsScrollLimit, scrollY);
            clearFocus();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private enum BuildPane {
        TOOLS("Tools"), BLOCKS("Blocks"), BLUEPRINT("Blueprint"), MATERIALS("Materials");

        final String label;

        BuildPane(String label) { this.label = label; }
    }

    private enum BuildTool {
        PLACE("Place", "Click a face or the work plane to place a block."),
        PAINT("Paint", "Click a planned block to change its material."),
        ERASE("Erase", "Click a planned block to erase it."),
        SAMPLE("Sample", "Click a block in the map to pick its material."),
        LINE("Line", "Set the endpoints of a line.", BuildPreview.Kind.LINE),
        BOX("Box", "Set opposite corners of a solid box.", BuildPreview.Kind.BOX),
        SHELL("Shell", "Set opposite corners of a hollow box.", BuildPreview.Kind.SHELL),
        CYLINDER("Cylinder", "Set the bounds of a vertical cylinder.", BuildPreview.Kind.CYLINDER),
        TUBE("Tube", "Set the bounds of a capped hollow cylinder.", BuildPreview.Kind.TUBE),
        PASTE("Paste", "Click where the copied region's minimum corner should go.");

        final String label;
        final String hint;
        final BuildPreview.Kind shape;

        BuildTool(String label, String hint) {
            this(label, hint, null);
        }

        BuildTool(String label, String hint, BuildPreview.Kind shape) {
            this.label = label;
            this.hint = hint;
            this.shape = shape;
        }

        boolean usesCorners() {
            return shape != null;
        }
    }

    private final class BuildPanel {
        private static final int ROW = BUTTON_HEIGHT + 3;
        private static final int BLOCK_ROW = 24;
        private static final int MATERIAL_ROW = 16;
        private final int[] scrolls = new int[BuildPane.values().length];
        private BuildPane pane = BuildPane.TOOLS;
        private BuildTool tool = BuildTool.PLACE;
        private boolean planeOnly;
        private String material = "minecraft:stone";
        private EditBox search;
        private EditBox name;
        private EditBox workHeight;
        private EditBox importPath;
        private EditBox placements;
        private EditBox scaffolds;
        private EditBox swapSource;
        private EditBox swapTarget;
        private boolean swapSelectionOnly;
        private boolean swapAttentionOnly;
        private BlueprintActions.MaterialSwap materialSwap;
        private final BlueprintImport importer = new BlueprintImport();
        private BuildLayers.Direction layerOrder = BuildLayers.Direction.BOTTOM_UP;
        private BlueprintActions.Comparison comparison;
        private long comparedRevision;
        private BuildPalette paletteSource;
        private String previousQuery;
        private List<BuildPalette.Entry> matches = List.of();
        private BuildPlan materialSource;
        private long materialRevision = -1;
        private List<Map.Entry<String, Integer>> materials = List.of();
        private Map<String, Integer> requirements = Map.of();
        private Map<String, BlueprintActions.MaterialCount> materialCounts = Map.of();
        private long nextMaterialRefresh;
        private Cell first;
        private Cell second;
        private Cell hoverCorner;
        private boolean shapeApplied;
        private Hit marker;
        private BuildPlan.Clipboard clipboard;
        private int limit;
        private String status = "Build plan";

        void initialize() {
            search = editBox("Search blocks", 100, value(search));
            name = editBox("Plan name", 64, name == null ? "My build" : value(name));
            workHeight = editBox("Y", 6, value(workHeight));
            importPath = editBox("Schematic file path", 2048, value(importPath));
            placements = editBox("Placement limit", 7, placements == null ? "512" : value(placements));
            scaffolds = editBox("Scaffold limit", 5, scaffolds == null ? "0" : value(scaffolds));
            swapSource = editBox("Replace block ID", BuildPlan.MAX_BLOCK_ID_LENGTH, value(swapSource));
            swapTarget = editBox("With block ID", BuildPlan.MAX_BLOCK_ID_LENGTH, value(swapTarget));
            swapSource.setResponder(value -> materialSwap = null);
            swapTarget.setResponder(value -> materialSwap = null);
            workHeight.setResponder(this::previewHeight);
        }

        void hideFields() {
            search.setVisible(false);
            name.setVisible(false);
            workHeight.setVisible(false);
            importPath.setVisible(false);
            placements.setVisible(false);
            scaffolds.setVisible(false);
            swapSource.setVisible(false);
            swapTarget.setVisible(false);
        }

        boolean typing() {
            return (search.visible && search.isFocused()) || (name.visible && name.isFocused())
                || (workHeight.visible && workHeight.isFocused()) || (importPath.visible && importPath.isFocused())
                || (placements.visible && placements.isFocused()) || (scaffolds.visible && scaffolds.isFocused())
                || (swapSource.visible && swapSource.isFocused()) || (swapTarget.visible && swapTarget.isFocused());
        }

        boolean confirmHeight(KeyEvent event) {
            if (!workHeight.visible || !workHeight.isFocused()
                || (event.key() != InputConstants.KEY_RETURN && event.key() != InputConstants.KEY_NUMPADENTER)) return false;
            run(this::setHeight);
            if (error == null) clearFocus();
            return true;
        }

        boolean shortcut(KeyEvent event) {
            if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)
                && tool.usesCorners() && second != null && currentMaterialSwap() == null) {
                run(this::applyShape);
                return true;
            }
            if (!event.hasControlDownWithQuirk()) return false;
            if (event.key() == InputConstants.KEY_Z) {
                run(() -> history(event.hasShiftDown()));
                return true;
            }
            if (event.key() == InputConstants.KEY_Y) {
                run(() -> history(true));
                return true;
            }
            return false;
        }

        void resetSelection() {
            importer.cancel();
            first = second = hoverCorner = null;
            shapeApplied = false;
            marker = null;
            clipboard = null;
            if (tool == BuildTool.PASTE) tool = BuildTool.PLACE;
            materialSource = null;
            comparison = null;
            materialSwap = null;
            status = "Build plan";
        }

        MapVolume.Box box() {
            if (first == null || second == null) return null;
            return new MapVolume.Box(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
        }

        void setEditedCorners(Hit a, Hit b) {
            Cell nextA = a == null ? null : new Cell(a.x(), a.y(), a.z());
            Cell nextB = b == null ? null : new Cell(b.x(), b.y(), b.z());
            first = nextA;
            second = nextB;
            hoverCorner = second;
            marker = b == null ? a : b;
            shapeApplied = false;
            status = a == null ? "Selection cleared" : b == null ? "Point A set"
                : tool.usesCorners() ? "Preview locked · Apply to place" : "Region selected";
        }

        void beginRegion(Hit anchor) {
            first = new Cell(anchor.x(), anchor.y(), anchor.z());
            second = hoverCorner = null;
            marker = anchor;
            shapeApplied = false;
            status = "Point A set · Release on point B";
        }

        void previewRegion(Hit end) {
            hoverCorner = end == null ? null : new Cell(end.x(), end.y(), end.z());
            viewport.highlight(marker, marker == null || end == null ? null : regionBetween(marker, end));
        }

        void finishRegion(Hit end) {
            second = end == null ? null : new Cell(end.x(), end.y(), end.z());
            hoverCorner = second;
            if (end != null) marker = end;
            status = end == null ? "Point A set" : tool.usesCorners() ? "Preview locked · Apply to place" : "Region selected";
        }

        void scrollBy(double amount) {
            int index = pane.ordinal();
            scrolls[index] = scroll(scrolls[index], limit, amount);
            if (pane != BuildPane.BLOCKS) clearFocus();
        }

        void draw(GuiGraphicsExtractor graphics) {
            drawPanel(graphics, inspector);
            int x = inspector.x + 6;
            int y = inspector.y + 7;
            int available = inspector.width - 12;
            text(graphics, "Build plan", x, y, theme.text);
            String total = numbers.format(viewport.buildPlan().size());
            if (available >= font.width("Build plan") + font.width(total) + 5) {
                text(graphics, total, x + available - font.width(total), y, theme.accent);
            }
            y += 13;

            int tabWidth = (available - 6) / BuildPane.values().length;
            for (BuildPane candidate : BuildPane.values()) {
                int offset = candidate.ordinal() * (tabWidth + 2);
                String label = font.width(candidate.label) + 5 <= tabWidth ? candidate.label : switch (candidate) {
                    case TOOLS -> "Edit";
                    case BLOCKS -> "Pick";
                    case BLUEPRINT -> "Plan";
                    case MATERIALS -> "List";
                };
                drawButton(graphics, new Rect(x + offset, y, tabWidth, BUTTON_HEIGHT), label, true,
                    pane == candidate, () -> {
                        pane = candidate;
                        limit = 0;
                        error = null;
                    });
            }
            y += ROW + 1;

            BuildPalette.Entry selectedMaterial = viewport.buildPalette().find(material);
            swatch(graphics, x, y, 12, selectedMaterial);
            String selectedName = selectedMaterial == null ? material : selectedMaterial.displayName();
            text(graphics, ellipsis(selectedName, available - 17), x + 16, y + 2, theme.accent);
            y += 16;

            if (pane == BuildPane.BLOCKS && y + BUTTON_HEIGHT <= inspector.bottom() - 6) {
                Rect searchPosition = new Rect(x, y, available, BUTTON_HEIGHT);
                field(graphics, searchPosition, search, searchPosition);
                y += ROW + 1;
            }
            Rect body = new Rect(x, y, available - 2, Math.max(0, inspector.bottom() - y - 5));
            if (body.height == 0) return;
            beginClip(graphics, body);
            limit = switch (pane) {
                case TOOLS -> drawTools(graphics, body, scrolls[pane.ordinal()]);
                case BLOCKS -> drawPalette(graphics, body);
                case BLUEPRINT -> drawBlueprint(graphics, body);
                case MATERIALS -> drawMaterials(graphics, body);
            };
            endClip(graphics);
            int index = pane.ordinal();
            scrolls[index] = Math.min(scrolls[index], limit);
            drawScrollMark(graphics, inspector, body, scrolls[index], limit);
        }

        private int drawTools(GuiGraphicsExtractor graphics, Rect body, int offset) {
            int y = body.y - offset;
            BuildPlan plan = viewport.buildPlan();
            drawPair(graphics, body, y, "Undo", "Redo", plan.canUndo(), plan.canRedo(), false, false,
                () -> run(() -> history(false)), () -> run(() -> history(true)));
            y += ROW;
            drawPair(graphics, body, y, "Faces", "Plane", true, true, !planeOnly, planeOnly,
                () -> selectPlane(false), () -> selectPlane(true));
            y += ROW + 4;
            BuildTool[] tools = BuildTool.values();
            for (int i = 0; i < tools.length; i += 2) {
                BuildTool leftTool = tools[i];
                BuildTool rightTool = tools[i + 1];
                drawPair(graphics, body, y, leftTool.label, rightTool.label, true,
                    rightTool != BuildTool.PASTE || clipboard != null, tool == leftTool, tool == rightTool,
                    () -> changeTool(leftTool), () -> changeTool(rightTool));
                y += ROW;
            }
            String toolHint = tool.usesCorners() && first != null
                ? second == null ? "Move to preview. Click to set B." : "Preview locked. Apply or press Enter to add."
                : tool.hint;
            if (tool.usesCorners() && shapeApplied) toolHint = "Change material, tool, or points to preview again.";
            y = wrap(graphics, toolHint, body.x + 2, y + 2, body.width - 4, theme.muted) + 5;

            int changeX = body.right() - 22;
            drawButton(graphics, new Rect(body.x, y, 18, BUTTON_HEIGHT), "−", viewport.buildY() > viewport.minY(),
                false, () -> run(() -> adjustHeight(-1)));
            drawButton(graphics, new Rect(body.x + 20, y, 18, BUTTON_HEIGHT), "+", viewport.buildY() < viewport.maxY(),
                false, () -> run(() -> adjustHeight(1)));
            text(graphics, ellipsis("Y " + viewport.buildY(), body.width - 42), body.x + 42, y + 5, theme.text);
            y += ROW;
            if (body.width >= 63) {
                field(graphics, body, workHeight, new Rect(body.x, y, changeX - body.x - 3, BUTTON_HEIGHT));
                drawButton(graphics, new Rect(changeX, y, 22, BUTTON_HEIGHT), "Set", true, false,
                    () -> run(this::setHeight));
                y += ROW;
            }

            y += 4;
            y = corner(graphics, body, y, "A", first);
            y = corner(graphics, body, y, "B", second);
            if (second != null) {
                String dimensions = extent(first.x(), second.x()) + " × " + extent(first.y(), second.y())
                    + " × " + extent(first.z(), second.z());
                text(graphics, ellipsis(dimensions, body.width - 4), body.x + 2, y, theme.accent);
                y += 12;
            }
            boolean region = second != null;
            drawPair(graphics, body, y, "Apply", "Reset", region && tool.usesCorners(), first != null,
                true, false, () -> run(this::applyShape), () -> run(this::clearCorners));
            y += ROW;
            drawPair(graphics, body, y, "Copy", "Erase box", region, region, false, false,
                () -> run(this::copy), () -> run(() -> changed("Erased", viewport.buildPlan().eraseBox(first, second))));
            y += ROW;
            drawPair(graphics, body, y, "Mirror X", "Mirror Z", region, region, false, false,
                () -> run(() -> mirror(BuildPlan.Axis.X)), () -> run(() -> mirror(BuildPlan.Axis.Z)));
            y += ROW;
            drawPair(graphics, body, y, "Rotate 90°", "Repeat", region, region && clipboard != null, false, false,
                () -> run(this::rotateBlueprint),
                () -> run(() -> changed("Repeated", BlueprintActions.repeat(plan, clipboard, first, second))));
            y = drawRegionTools(graphics, body, y + ROW + 4, mapLevel != null);
            return Math.max(0, y + offset - body.bottom());
        }

        private int drawBlueprint(GuiGraphicsExtractor graphics, Rect body) {
            int offset = scrolls[BuildPane.BLUEPRINT.ordinal()];
            int y = body.y - offset;
            BuildPlan plan = viewport.buildPlan();
            boolean nonempty = plan.size() > 0;
            boolean live = mapLevel != null;
            boolean building = BlueprintConstruction.get().active();
            field(graphics, body, name, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
            y += ROW;
            drawPair(graphics, body, y, "Save", "Load", nonempty, true, false, false,
                () -> run(() -> {
                    viewport.saveBuild(name.getValue());
                    status = "Saved " + name.getValue().strip();
                }), () -> run(() -> {
                    viewport.loadBuild(name.getValue());
                    clearCorners();
                    status = "Loaded " + name.getValue().strip();
                }));
            y += ROW;
            field(graphics, body, importPath, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
            y += ROW;
            drawPair(graphics, body, y, importer.pending() ? "Importing…" : "Import", "Export A–B",
                live && !importer.pending() && !importPath.getValue().isBlank(), live && second != null,
                false, false, () -> run(this::importBlueprint), () -> run(this::exportBlueprint));
            y += ROW;
            y = wrap(graphics, "Import origin is A, the picked block, or your feet.", body.x + 2, y, body.width - 4, theme.muted) + 4;
            drawPair(graphics, body, y, "Align to A", "Compare", nonempty && first != null, nonempty && live, false, false,
                () -> run(() -> changed("Aligned", BlueprintActions.align(plan, first))), () -> run(this::compareBlueprint));
            y += ROW;
            if (comparison != null && comparedRevision == plan.revision()) {
                for (BlueprintActions.Difference difference : BlueprintActions.Difference.values()) {
                    y = wrap(graphics, difference.label() + " " + comparison.count(difference), body.x + 2, y,
                        body.width - 4, difference.color());
                }
                if (comparison.unloaded() != 0) y = wrap(graphics, comparison.unloaded() + " unloaded cells", body.x + 2, y,
                    body.width - 4, theme.warning);
                drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Hide comparison", true, false, () -> {
                    comparison = null;
                    viewport.setComparison(null);
                });
                y += ROW;
            }
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Clear plan", nonempty && !building, false,
                () -> run(() -> changed("Cleared", plan.clear())));
            y += ROW + 5;
            y = drawMaterialSwap(graphics, body, y, plan, building);
            text(graphics, "Construction limits", body.x + 2, y, theme.text);
            y += 13;
            int half = (body.width - 3) / 2;
            text(graphics, ellipsis("Placements", half), body.x + 2, y, theme.muted);
            text(graphics, ellipsis("Scaffolds", half), body.x + half + 4, y, theme.muted);
            y += 11;
            field(graphics, body, placements, new Rect(body.x, y, half, BUTTON_HEIGHT));
            field(graphics, body, scaffolds, new Rect(body.x + half + 3, y, body.width - half - 3, BUTTON_HEIGHT));
            y += ROW;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT),
                layerOrder == BuildLayers.Direction.BOTTOM_UP ? "Layers bottom-up" : "Layers top-down", !building, false,
                () -> layerOrder = layerOrder == BuildLayers.Direction.BOTTOM_UP ? BuildLayers.Direction.TOP_DOWN : BuildLayers.Direction.BOTTOM_UP);
            y += ROW;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), building ? "Stop construction" : "Start construction",
                building || live && nonempty, !building, () -> run(() -> {
                    if (building) BlueprintConstruction.get().stop();
                    else {
                        module.startConstruction(plan, layerOrder, coordinate(placements, "placement limit"), coordinate(scaffolds, "scaffold limit"));
                        minecraft.gui.setScreen(null);
                    }
                }));
            y += ROW;
            if (BlueprintConstruction.get().cleanupAvailable()) {
                drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Retry scaffold cleanup", !building, false,
                    () -> run(() -> {
                        module.cleanupConstruction();
                        minecraft.gui.setScreen(null);
                    }));
                y += ROW;
            }
            y = wrap(graphics, BlueprintConstruction.get().statusText(), body.x + 2, y, body.width - 4, theme.muted);
            return Math.max(0, y + offset - body.bottom());
        }

        private int drawMaterialSwap(GuiGraphicsExtractor graphics, Rect body, int y, BuildPlan plan, boolean building) {
            text(graphics, "Swap materials", body.x + 2, y, theme.text);
            y += 13;
            field(graphics, body, swapSource, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
            y += ROW;
            field(graphics, body, swapTarget, new Rect(body.x, y, body.width, BUTTON_HEIGHT));
            y += ROW;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT),
                swapSelectionOnly ? "Scope: A–B selection" : "Scope: whole blueprint", true, false, () -> {
                    swapSelectionOnly = !swapSelectionOnly;
                    materialSwap = null;
                });
            y += ROW;
            boolean canPreview = plan.size() > 0 && !building && !swapSource.getValue().isBlank() && !swapTarget.getValue().isBlank()
                && (!swapSelectionOnly || second != null);
            BlueprintActions.MaterialSwap swap = currentMaterialSwap();
            drawPair(graphics, body, y, "Preview swap", "Apply swap", canPreview, !building && swap != null && swap.size() > 0,
                false, true, () -> run(this::previewMaterialSwap), () -> run(this::applyMaterialSwap));
            y += ROW;
            if (swap != null) {
                y = wrap(graphics, numbers.format(swap.size()) + " matching blocks", body.x + 2, y, body.width - 4, theme.text);
                if (!swap.attention().isEmpty()) {
                    int count = swap.attentionPreview().replacements().size();
                    y = wrap(graphics, count + " blocks need review. Unavailable properties use the replacement defaults.",
                        body.x + 2, y, body.width - 4, theme.warning);
                    drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT),
                        swapAttentionOnly ? "Preview all replacements" : "Preview blocks needing review", true, false,
                        () -> swapAttentionOnly = !swapAttentionOnly);
                    y += ROW;
                    int shown = 0;
                    for (var entry : swap.attention().entrySet()) {
                        if (shown++ == 5) {
                            y = wrap(graphics, "Other variants: " + (swap.attention().size() - 5), body.x + 2, y, body.width - 4, theme.warning);
                            break;
                        }
                        y = wrap(graphics, entry.getValue() + " × " + entry.getKey(), body.x + 2, y, body.width - 4, theme.warning);
                    }
                }
                drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT),
                    "Dismiss swap preview", true, false, () -> materialSwap = null);
                y += ROW;
            }
            return y + 5;
        }

        private BlueprintActions.MaterialSwap currentMaterialSwap() {
            return materialSwap != null && materialSwap.matches(viewport.buildPlan(),
                swapSelectionOnly ? first : null, swapSelectionOnly ? second : null) ? materialSwap : null;
        }

        private void previewMaterialSwap() {
            swapAttentionOnly = false;
            materialSwap = BlueprintActions.previewMaterialSwap(viewport.buildPlan(), swapSelectionOnly ? first : null,
                swapSelectionOnly ? second : null, swapSource.getValue(), swapTarget.getValue());
        }

        private void applyMaterialSwap() {
            BlueprintActions.MaterialSwap swap = currentMaterialSwap();
            if (swap == null) throw new IllegalStateException("Preview the current material swap first.");
            int count = swap.apply(viewport.buildPlan(), swapSelectionOnly ? first : null, swapSelectionOnly ? second : null);
            materialSwap = null;
            changed("Swapped", count);
        }

        private void importBlueprint() {
            String entered = importPath.getValue().strip();
            if (entered.length() > 1 && entered.startsWith("\"") && entered.endsWith("\"")) entered = entered.substring(1, entered.length() - 1);
            Path source = minecraft.gameDirectory.toPath().resolve(Path.of(entered)).normalize();
            BlockPos feet = minecraft.player.blockPosition();
            Cell origin = first != null ? first : marker != null ? new Cell(marker.x(), marker.y(), marker.z())
                : new Cell(feet.getX(), feet.getY(), feet.getZ());
            importer.start(source, origin, mapLevel, viewport.buildPlan());
            status = "Reading " + source.getFileName();
        }

        private void finishImport() {
            BlueprintActions.ImportResult result = importer.poll(mapLevel, viewport.buildPlan());
            if (result == null) return;
            clearCorners();
            changed("Imported", result.blocks());
            if (result.unavailable() != 0) status += "  •  " + result.unavailable() + " unavailable";
        }

        private void exportBlueprint() {
            try {
                changed("Exported", BlueprintActions.exportSelection(mapLevel, first, second, viewport.buildDirectory(), name.getValue()));
            } catch (IOException problem) {
                throw new IllegalStateException("Could not export blueprint. " + problem.getMessage(), problem);
            }
        }

        private void compareBlueprint() {
            comparison = BlueprintActions.compare(mapLevel, viewport.buildPlan(), first, second);
            comparedRevision = viewport.buildPlan().revision();
            viewport.setComparison(comparison);
            status = comparison.differences().isEmpty() ? "No differences in loaded terrain" : comparison.differences().size() + " differences";
        }

        private void rotateBlueprint() {
            MapVolume.Box bounds = box();
            Cell nextA = new Cell(bounds.minX(), bounds.minY(), bounds.minZ());
            Cell nextB = new Cell(bounds.minX() + bounds.maxZ() - bounds.minZ(), bounds.maxY(),
                bounds.minZ() + bounds.maxX() - bounds.minX());
            int count = BlueprintActions.rotate(viewport.buildPlan(), first, second);
            first = nextA;
            second = nextB;
            syncRegionFields();
            shapeApplied = true;
            updateHighlights();
            changed("Rotated", count);
        }

        private int drawPalette(GuiGraphicsExtractor graphics, Rect body) {
            BuildPalette palette = viewport.buildPalette();
            String query = search.getValue();
            if (palette != paletteSource || !query.equals(previousQuery)) {
                paletteSource = palette;
                previousQuery = query;
                matches = palette.search(query);
                scrolls[BuildPane.BLOCKS.ordinal()] = 0;
            }
            text(graphics, matches.isEmpty() ? "No matching blocks" : numbers.format(matches.size()) + " blocks",
                body.x + 2, body.y + 1, theme.muted);
            int offset = scrolls[BuildPane.BLOCKS.ordinal()];
            Rect rows = new Rect(body.x, body.y + 13, body.width, Math.max(0, body.height - 13));
            if (rows.height == 0) return 0;
            graphics.enableScissor(rows.x, rows.y, rows.right(), rows.bottom());
            int firstRow = offset / BLOCK_ROW;
            int lastRow = Math.min(matches.size(), (offset + rows.height + BLOCK_ROW - 1) / BLOCK_ROW);
            for (int i = firstRow; i < lastRow; i++) {
                BuildPalette.Entry entry = matches.get(i);
                Rect row = new Rect(rows.x, rows.y + i * BLOCK_ROW - offset, rows.width, BLOCK_ROW - 2);
                Rect hit = row.intersection(rows);
                boolean active = material.equals(entry.id());
                if (active || hit.contains(mouseX, mouseY)) {
                    graphics.fill(row.x, row.y, row.right(), row.bottom(), active ? theme.selected : theme.hover);
                }
                swatch(graphics, row.x + 3, row.y + 4, 13, entry);
                text(graphics, ellipsis(entry.displayName(), row.width - 23), row.x + 19, row.y + 2, active ? theme.accent : theme.text);
                text(graphics, ellipsis(entry.id(), row.width - 23), row.x + 19, row.y + 12, theme.muted);
                if (hit.height > 0) actions.add(new Action(hit, () -> {
                    chooseMaterial(entry.id());
                    status = entry.displayName() + " selected";
                    error = null;
                }));
            }
            graphics.disableScissor();
            return Math.max(0, matches.size() * BLOCK_ROW - rows.height);
        }

        private int drawMaterials(GuiGraphicsExtractor graphics, Rect body) {
            BuildPlan plan = viewport.buildPlan();
            if (plan != materialSource || plan.revision() != materialRevision) {
                materialSource = plan;
                materialRevision = plan.revision();
                requirements = BlueprintActions.requirements(plan);
                materials = requirements.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                    .toList();
                nextMaterialRefresh = 0;
            }
            long now = System.nanoTime();
            if (minecraft.player != null && now >= nextMaterialRefresh) {
                materialCounts = BlueprintActions.materials(minecraft.player, requirements, BlueprintStorage.counts(mapLevel));
                nextMaterialRefresh = now + 250_000_000L;
            }
            int offset = scrolls[BuildPane.MATERIALS.ordinal()];
            int y = body.y - offset;
            drawButton(graphics, new Rect(body.x, y, body.width, BUTTON_HEIGHT), "Read picked storage", marker != null && mapLevel != null,
                false, () -> run(() -> status = BlueprintStorage.request(minecraft, new Cell(marker.x(), marker.y(), marker.z()))));
            y += ROW + 4;
            String storageStatus = BlueprintStorage.status(mapLevel);
            if (storageStatus != null && !storageStatus.isBlank()) y = wrap(graphics, storageStatus, body.x + 2, y, body.width - 4, theme.muted) + 3;
            y = wrap(graphics, "Needed • on hand • storage • missing", body.x + 2, y, body.width - 4, theme.muted) + 4;
            int rowHeight = MATERIAL_ROW + 9;
            int firstRow = Math.max(0, (body.y - y) / rowHeight);
            int lastRow = Math.min(materials.size(), (body.bottom() - y + rowHeight - 1) / rowHeight);
            BuildPalette palette = viewport.buildPalette();
            for (int i = firstRow; i < lastRow; i++) {
                var entry = materials.get(i);
                int rowY = y + i * rowHeight;
                BuildPalette.Entry block = palette.find(entry.getKey());
                swatch(graphics, body.x + 2, rowY + 2, 10, block);
                String blockName = block == null ? entry.getKey() : block.displayName();
                text(graphics, ellipsis(blockName, body.width - 17), body.x + 15, rowY + 3, theme.text);
                var count = materialCounts.getOrDefault(entry.getKey(), new BlueprintActions.MaterialCount(entry.getValue(), 0, 0));
                String totals = count.required() + " / " + count.inventory() + " / " + count.storage() + " / " + count.missing();
                text(graphics, ellipsis(totals, body.width - 4), body.x + 3, rowY + 14, count.missing() > 0 ? theme.warning : theme.accent);
            }
            return Math.max(0, y + materials.size() * rowHeight + offset - body.bottom());
        }

        private void drawPair(GuiGraphicsExtractor graphics, Rect body, int y, String firstLabel, String secondLabel,
                              boolean firstEnabled, boolean secondEnabled, boolean firstAccent, boolean secondAccent,
                              Runnable firstAction, Runnable secondAction) {
            int half = (body.width - 3) / 2;
            drawButton(graphics, new Rect(body.x, y, half, BUTTON_HEIGHT), firstLabel, firstEnabled, firstAccent, firstAction);
            drawButton(graphics, new Rect(body.x + half + 3, y, body.width - half - 3, BUTTON_HEIGHT), secondLabel,
                secondEnabled, secondAccent, secondAction);
        }

        private void swatch(GuiGraphicsExtractor graphics, int x, int y, int size, BuildPalette.Entry block) {
            graphics.fill(x, y, x + size, y + size, block == null ? theme.dim : block.color() | 0xFF000000);
            graphics.outline(x, y, size, size, 0x44000000);
        }

        private int corner(GuiGraphicsExtractor graphics, Rect body, int y, String label, Cell cell) {
            String position = cell == null ? "–" : cell.x() + " / " + cell.y() + " / " + cell.z();
            text(graphics, label, body.x + 2, y, theme.accent);
            return wrap(graphics, position, body.x + 13, y, body.width - 15, theme.muted) + 2;
        }

        private int extent(int a, int b) { return Math.abs(a - b) + 1; }

        private void changeTool(BuildTool next) {
            if (tool != next) shapeApplied = false;
            tool = next;
            error = null;
            status = next.label;
        }

        private void selectPlane(boolean enabled) {
            planeOnly = enabled;
            error = null;
            status = enabled ? "Work plane Y " + viewport.buildY() : "Place against block faces";
        }

        private void previewHeight(String value) {
            try {
                viewport.setBuildY(Integer.parseInt(value.strip()));
                status = "Work plane Y " + viewport.buildY();
                error = null;
            } catch (NumberFormatException incomplete) {
                // Keep the last valid plane while the field is blank or contains a partial number.
            }
        }

        private void setHeight() {
            viewport.setBuildY(coordinate(workHeight, "work plane Y"));
            status = "Work plane Y " + viewport.buildY();
            workHeight.setValue("");
        }

        private void adjustHeight(int amount) {
            viewport.setBuildY(viewport.buildY() + amount);
            status = "Work plane Y " + viewport.buildY();
        }

        private BuildPreview preview(double x, double y) {
            if (pane == BuildPane.BLUEPRINT) {
                BlueprintActions.MaterialSwap swap = currentMaterialSwap();
                if (swap != null) return swapAttentionOnly ? swap.attentionPreview() : swap.preview();
            }
            boolean hovering = map.contains(x, y) && (mapButton == -1 || dragDistance < 2.5);
            if (tool.usesCorners()) {
                if (first == null || shapeApplied) return null;
                if (second == null && hovering && !selectingRegion()) hoverCorner = placement(x, y);
                Cell end = second == null ? hoverCorner : second;
                return end == null ? null : shape(end);
            }
            if (!hovering || selectingRegion()) return null;
            if (tool == BuildTool.PAINT) {
                Hit hit = viewport.pick(x, y);
                if (hit == null) return null;
                Cell position = new Cell(hit.x(), hit.y(), hit.z());
                String current = viewport.buildPlan().snapshot().get(position);
                return current == null || current.equals(material) ? null : BuildPreview.block(position, material);
            }
            if (tool != BuildTool.PLACE && (tool != BuildTool.PASTE || clipboard == null)) return null;
            Cell target = placement(x, y);
            if (target == null) return null;
            return tool == BuildTool.PASTE ? BuildPreview.paste(target, clipboard) : BuildPreview.block(target, material);
        }

        private Cell placement(double x, double y) {
            return planeOnly ? viewport.pickWorkPlane(x, y) : viewport.pickPlacement(x, y);
        }

        private BuildPreview shape(Cell end) {
            return BuildPreview.shape(tool.shape, first, end, material);
        }

        private void chooseMaterial(String id) {
            if (!material.equals(id)) shapeApplied = false;
            material = id;
        }

        void click(double x, double y) {
            if (pane == BuildPane.BLUEPRINT && currentMaterialSwap() != null) {
                throw new IllegalStateException("Apply or dismiss the material swap before editing the plan.");
            }
            BuildPlan plan = viewport.buildPlan();
            switch (tool) {
                case SAMPLE -> sample(x, y);
                case PAINT, ERASE -> {
                    Hit hit = viewport.pick(x, y);
                    if (hit == null) throw new IllegalArgumentException("Click a planned block.");
                    Cell cell = new Cell(hit.x(), hit.y(), hit.z());
                    if (!plan.snapshot().containsKey(cell)) throw new IllegalArgumentException("Click a planned block.");
                    if (tool == BuildTool.PAINT) changed("Painted", plan.place(cell, material));
                    else changed("Erased", plan.erase(cell));
                    marker = hit;
                }
                default -> {
                    Cell target = placement(x, y);
                    if (target == null) throw new IllegalArgumentException(planeOnly
                        ? "The work plane is outside this view." : "Aim at a block face or the work plane.");
                    if (tool == BuildTool.PLACE) changed("Placed", plan.place(target, material));
                    else if (tool == BuildTool.PASTE && clipboard != null) changed("Pasted", plan.paste(target, clipboard));
                    else if (tool.usesCorners()) {
                        shapeApplied = false;
                        hoverCorner = target;
                        if (first == null || second != null) {
                            first = target;
                            second = null;
                            status = "Point A set · Move to preview";
                        } else {
                            second = target;
                            status = "Preview locked · Enter to apply";
                        }
                        syncRegionFields();
                    }
                    marker = new Hit(target.x(), target.y(), target.z());
                }
            }
            updateHighlights();
        }

        void sample(double x, double y) {
            Hit hit = viewport.pick(x, y);
            if (hit == null) throw new IllegalArgumentException("Click a block in the map.");
            Cell cell = new Cell(hit.x(), hit.y(), hit.z());
            String id = viewport.buildPlan().snapshot().get(cell);
            if (id == null) id = terrainBlock(cell);
            if (id == null) throw new IllegalArgumentException("That block is outside loaded terrain.");
            chooseMaterial(id);
            BuildPalette.Entry entry = viewport.buildPalette().find(material);
            status = (entry == null ? material : entry.displayName()) + " selected";
            if (tool == BuildTool.SAMPLE) tool = BuildTool.PLACE;
            marker = hit;
            updateHighlights();
        }

        private String terrainBlock(Cell cell) {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (mapLevel == null || !mapLevel.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
            var state = mapLevel.getBlockState(pos);
            return state.isAir() ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        }

        private void applyShape() {
            if (second == null || !tool.usesCorners()) return;
            int count = viewport.buildPlan().apply(shape(second));
            shapeApplied = true;
            changed("Planned", count);
        }

        private void clearCorners() {
            first = second = hoverCorner = null;
            shapeApplied = false;
            marker = null;
            if (tab == Tab.BUILD) syncRegionFields();
            updateHighlights();
        }

        private void copy() {
            if (second == null) return;
            BuildPlan.Clipboard copied = viewport.buildPlan().copy(first, second);
            if (copied.blocks().isEmpty()) throw new IllegalArgumentException("The selection has no planned blocks.");
            clipboard = copied;
            changeTool(BuildTool.PASTE);
            changed("Copied", clipboard.blocks().size());
        }

        private void mirror(BuildPlan.Axis axis) {
            if (second != null) changed("Mirrored", BlueprintActions.mirror(viewport.buildPlan(), first, second, axis));
        }

        private void history(boolean redo) {
            BuildPlan plan = viewport.buildPlan();
            changed(redo ? "Redid" : "Undid", redo ? plan.redo() : plan.undo());
        }

        private void changed(String verb, int count) {
            status = count == 0 ? "No blocks changed" : verb + " " + numbers.format(count) + (count == 1 ? " block" : " blocks");
        }
    }

    private static int scroll(int current, int limit, double amount) {
        return Math.clamp(current - (int) Math.round(amount * 27), 0, limit);
    }

    void run(Runnable action) {
        try {
            action.run();
            error = null;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            error = rejected.getMessage();
        }
    }

    private void beginClip(GuiGraphicsExtractor graphics, Rect body) {
        clip = body;
        graphics.enableScissor(body.x, body.y, body.right(), body.bottom());
    }

    private void endClip(GuiGraphicsExtractor graphics) {
        clip = null;
        graphics.disableScissor();
    }

    private void drawPanel(GuiGraphicsExtractor graphics, Rect panel) {
        PlannerTheme.bordered(graphics, panel.x, panel.y, panel.width, panel.height, theme.panel, theme.border);
    }

    private void drawScrollMark(GuiGraphicsExtractor graphics, Rect panel, Rect body, int current, int limit) {
        if (limit <= 0 || body.height < 8) return;
        int size = Math.max(8, body.height * body.height / (body.height + limit));
        int y = body.y + (body.height - size) * current / limit;
        graphics.fill(panel.right() - 4, y, panel.right() - 2, y + size, theme.dim);
    }

    private void drawButton(GuiGraphicsExtractor graphics, Rect rect, String label, boolean enabled, boolean accent, Runnable action) {
        if (rect.width < 1 || rect.height < 1) return;
        Rect hitArea = clip == null ? rect : rect.intersection(clip);
        if (hitArea.width < 1 || hitArea.height < 1) return;
        boolean hovered = enabled && hitArea.contains(mouseX, mouseY);
        int background = enabled ? (accent ? hovered ? theme.selectedHover : theme.selected : hovered ? theme.hover : theme.surface) : theme.field;
        PlannerTheme.bordered(graphics, rect.x, rect.y, rect.width, rect.height, background, accent && enabled ? theme.accent : theme.border);
        centerText(graphics, ellipsis(label, rect.width - 5), rect.x + rect.width / 2,
            rect.y + (rect.height - 8) / 2, enabled ? accent ? theme.accent : theme.text : theme.dim);
        if (enabled) actions.add(new Action(hitArea, action));
    }

    void pageButton(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String label, boolean enabled, boolean accent, Runnable action) {
        if (!accent || !enabled) {
            drawButton(graphics, new Rect(x, y, w, h), label, enabled, false, () -> run(action));
            return;
        }
        Rect rect = new Rect(x, y, w, h);
        Rect hit = clip == null ? rect : rect.intersection(clip);
        if (hit.width < 1 || hit.height < 1) return;
        int fill = hit.contains(mouseX, mouseY) ? theme.selectedHover : theme.accent;
        PlannerTheme.bordered(graphics, x, y, w, h, fill, theme.accent);
        centerText(graphics, ellipsis(label, w - 8), x + w / 2, y + (h - 8) / 2,
            hit.contains(mouseX, mouseY) ? theme.text : theme.onAccent);
        actions.add(new Action(hit, () -> run(action)));
    }

    void pageHit(int x, int y, int w, int h, Runnable action) {
        Rect area = new Rect(x, y, w, h);
        if (clip != null) area = area.intersection(clip);
        if (area.width > 0 && area.height > 0) actions.add(new Action(area, () -> run(action)));
    }

    EditBox pageField(GuiGraphicsExtractor graphics, String key, String hint, int maxLength, int x, int y, int w) {
        EditBox box = pageFields.computeIfAbsent(tab.name() + "/" + key, id -> editBox(hint, maxLength, ""));
        Rect rect = new Rect(x, y, Math.max(11, w), 21);
        boolean visible = clip == null || rect.y >= clip.y && rect.bottom() <= clip.bottom();
        if (visible) PlannerTheme.bordered(graphics, rect.x, rect.y, rect.width, rect.height, theme.field,
            box.isFocused() ? theme.accent : theme.border);
        box.setRectangle(Math.max(1, rect.width - 12), rect.height - 5, rect.x + 7, rect.y + 5);
        if (visible) fieldHint(graphics, box, hint, rect.width - 14);
        box.setTextColor(theme.text);
        box.setVisible(visible);
        return box;
    }

    private void fieldHint(GuiGraphicsExtractor graphics, EditBox box, String hint, int availableWidth) {
        if (box.getValue().isEmpty() && !box.isFocused()) {
            text(graphics, ellipsis(hint, availableWidth), box.getX(), box.getY(), theme.muted);
        }
    }

    void pageClip(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        beginClip(graphics, new Rect(x, y, Math.max(0, w), Math.max(0, h)));
    }

    void pageUnclip(GuiGraphicsExtractor graphics) { endClip(graphics); }

    private int wrap(GuiGraphicsExtractor graphics, String value, int x, int y, int available, int color) {
        for (var line : font.split(PlannerTheme.lettering(value), Math.max(1, available))) {
            graphics.text(font, line, x, y, color, false);
            y += 11;
        }
        return y;
    }

    private void text(GuiGraphicsExtractor graphics, String value, int x, int y, int color) {
        graphics.text(font, PlannerTheme.lettering(value), x, y, color, false);
    }

    private void centerText(GuiGraphicsExtractor graphics, String value, int x, int y, int color) {
        text(graphics, value, x - PlannerTheme.width(font, value) / 2, y, color);
    }

    private String ellipsis(String value, int available) {
        return PlannerTheme.truncate(font, value, available);
    }
}
