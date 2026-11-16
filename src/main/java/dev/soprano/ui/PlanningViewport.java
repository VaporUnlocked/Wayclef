package dev.soprano.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.soprano.SopranoClient;
import dev.soprano.game.FootSurvey;
import dev.soprano.build.BlueprintActions;
import dev.soprano.build.BuildPalette;
import dev.soprano.build.BuildPlan;
import dev.soprano.build.BuildPlanFiles;
import dev.soprano.build.BuildPreview;
import dev.soprano.build.BuildVolume;
import dev.soprano.game.Navigation;
import dev.soprano.map.MapEntities;
import dev.soprano.map.MapAnnotations;
import dev.soprano.map.MapCoverage;
import dev.soprano.map.MapMaterial;
import dev.soprano.map.MapSnapshot;
import dev.soprano.map.MapVolume;
import dev.soprano.map.RouteElevation;
import dev.soprano.map.SurveyRoute;
import dev.soprano.map.SurveyCache;
import dev.soprano.map.VoxelRenderer;
import dev.soprano.map.WorldMapCapture;
import dev.soprano.path.Node;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import dev.soprano.path.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Captures on the client thread, renders pixels off-thread, and uploads completed frames on the client. */
public final class PlanningViewport implements AutoCloseable {
    static final int COVERAGE_LOADED = 0xFF63CAB0;
    static final int COVERAGE_SURVEYED = 0xFF4F75A5;
    static final int COVERAGE_UNKNOWN = 0xFF233140;
    private static final int COVERAGE_SIDE = 21;
    private static final int MAX_FRAME_WIDTH = 640;
    private static final int MAX_FRAME_HEIGHT = 400;
    private static final long INTERACTION_SETTLE_TIME = 150_000_000L;
    private static final long MIN_FRAME_INTERVAL = 15_000_000L;
    private static final long MIN_STATS_INTERVAL = 350_000_000L;
    private static final AtomicInteger NEXT_TEXTURE = new AtomicInteger();
    private static final Map<ClientLevel, BuildPlan> WORLD_PLANS = new WeakHashMap<>();
    private static final long[] NO_ROUTE = new long[0];
    private static final int MAX_DRAWN_MARKERS = 24;
    private static RetainedSurvey retainedSurvey;

    private record RetainedSurvey(Scope scope, WorldMapCapture capture) { }

    private record View(MapSnapshot.Volume terrain, VoxelRenderer.Camera camera, int lowerCutY, int cutY, int width, int height,
                        VoxelRenderer.Hit selected, MapVolume.Box region, long[] route, long buildRevision,
                        Map<BuildPlan.Cell, String> blueprint, BuildPalette palette, BuildPreview preview,
                        BlueprintActions.Comparison comparison, boolean refineEdges, boolean patterned, boolean dedicatedGpu) { }
    private record Count(MapSnapshot.Volume terrain, MapVolume.Box box, MapVolume.AreaStats stats) { }

    private final Minecraft minecraft;
    private final SopranoModule module;
    private final SavedLocations locations;
    private final MapEntities entities = new MapEntities();
    private final VoxelRenderer renderer = new VoxelRenderer();
    private final Identifier textureId = Identifier.fromNamespaceAndPath("wayclef", "planning/" + NEXT_TEXTURE.incrementAndGet());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "Soprano planning map");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService routeWorker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "Soprano survey route");
        thread.setDaemon(true);
        return thread;
    });

    private ClientLevel buildLevel;
    private BuildPlan buildPlan = new BuildPlan();
    private BuildPalette buildPalette;
    private boolean buildVisible;
    private BuildPreview buildPreview;
    private BlueprintActions.Comparison comparison;
    private long comparisonRevision;
    private int buildY = 64;

    // Overlay cache used only on the render worker.
    private MapSnapshot.Volume overlayTerrain;
    private Map<BuildPlan.Cell, String> overlayBlueprint;
    private BuildPalette overlayPalette;
    private BuildVolume overlay;
    private MapVolume comparisonBase;
    private BlueprintActions.Comparison overlayComparison;
    private MapVolume comparisonVolume;
    private MapVolume previewBase;
    private BuildPreview overlayPreview;
    private Map<BuildPlan.Cell, String> previewBlocks = Map.of();
    private MapVolume previewVolume;

    private WorldMapCapture capture;
    private Scope surveyScope;
    private SurveyCache surveyCache;
    private MapCoverage coverage;
    private long lastCoverageSave;
    private long lastCoverageDraw;
    private int coverageX = Integer.MIN_VALUE, coverageZ = Integer.MIN_VALUE;
    private final int[] coverageColors = new int[COVERAGE_SIDE * COVERAGE_SIDE];
    private SavedLocations.Opened savedLocation;
    private VoxelRenderer.Camera displayedCamera;
    private MapSnapshot.Volume displayedTerrain;
    private VoxelRenderer.Camera camera;
    private VoxelRenderer.Frame frame;
    private View submitted;
    private Future<VoxelRenderer.Frame> pendingFrame;
    private DynamicTexture texture;
    private VoxelRenderer.Hit selected;
    private List<Step> currentPath = List.of();
    private long[] pathBlocks = new long[0];
    private List<Step> profilePath = List.of();
    private List<Vec3> profileTransport = List.of();
    private RouteElevation.Profile liveProfile;
    private VoxelRenderer.Hit previewStart, previewEnd;
    private Future<SurveyRoute.Preview> pendingMapPreview;
    private RouteElevation.Profile mapProfile;
    private long[] mapPreviewBlocks = NO_ROUTE;
    private String previewStatus = "Select a start and destination floor.";
    private boolean patternedOverlays;
    private boolean mapNotesVisible = true;
    private MapVolume.Box region;
    private MapVolume.Box countedBox;
    private Count count;
    private Future<Count> pendingCount;
    private int cutY, displayedCutY, x, y, width, height, moveKeys;
    private Integer lowerCutY;
    private int displayedLowerCutY;
    private long lastFrameRequest, lastStatsRequest, lastCameraUpdate, lastInteraction;
    private boolean renderFailed, countFailed, closed;

    public PlanningViewport(Minecraft minecraft, SopranoModule module) {
        this.minecraft = minecraft;
        this.module = module;
        locations = new SavedLocations(minecraft);
        refresh();
    }

    public void tick() {
        if (closed) return;
        if (buildLevel != minecraft.level || (capture != null && !capture.valid(minecraft))
            || !Objects.equals(surveyScope, module.storageScope())) refresh();
        if (capture == null && minecraft.level != null && minecraft.player != null) refresh();
        if (surveyCache != null) surveyCache.setDiskLimit(module.surveyCacheLimitBytes());
        SavedLocations.Opened opened = locations.poll();
        if (opened != null) showSnapshot(opened);
        readMapPreview();
        if (capture != null && savedLocation == null) {
            capture.tick();
            long now = System.nanoTime();
            if (now - lastCoverageSave >= 15_000_000_000L) {
                coverage.flush();
                capture.flushSurvey();
                lastCoverageSave = now;
            }
            if (module.showMapEntities()) entities.tick(minecraft, capture);
            else entities.clear();
        }
    }

    public void draw(GuiGraphicsExtractor graphics, int left, int top, int w, int h) {
        x = left;
        y = top;
        width = w;
        height = h;
        MapSnapshot.Volume terrain = terrain();
        if (closed || terrain == null || camera == null || w < 1 || h < 1) return;

        advanceCamera();
        readFrame();
        if (texture != null && frame != null) {
            graphics.blit(textureId, x, y, x + w, y + h, 0, 1, 0, 1);
            if (savedLocation == null && !buildVisible) {
                if (previewStart == null && previewEnd == null) {
                    drawTransportPath(graphics);
                    drawRouteDirection(graphics);
                }
            }
            if (savedLocation == null && module.showMapEntities()) {
                entities.draw(graphics, minecraft, frame, displayedCamera, x, y, w, h, displayedLowerCutY, displayedCutY,
                    module.showMapEntityNames());
            }
            if (!buildVisible) drawMapMarkers(graphics);
        }

        long now = System.nanoTime();
        boolean interacting = MapCameraMotion.moving(moveKeys) || now - lastInteraction < INTERACTION_SETTLE_TIME;
        double quality = module.mapRenderQuality() / 100.0 * (interacting ? .65 : 1);
        double scale = Math.min(minecraft.getWindow().getGuiScale(),
            Math.min(MAX_FRAME_WIDTH * quality / w, MAX_FRAME_HEIGHT * quality / h));
        int frameWidth = Math.max(1, (int) Math.round(w * scale));
        int frameHeight = Math.max(1, (int) Math.round(h * scale));
        boolean showBuild = buildVisible && savedLocation == null;
        Map<BuildPlan.Cell, String> blueprint = showBuild && buildPlan.size() != 0 ? buildPlan.snapshot() : Map.of();
        BuildPreview preview = showBuild ? buildPreview : null;
        BlueprintActions.Comparison visibleComparison = showBuild && comparisonRevision == buildPlan.revision() ? comparison : null;
        long[] route = showBuild ? NO_ROUTE : previewStart != null || previewEnd != null ? mapPreviewBlocks
            : savedLocation == null ? routeBlocks() : NO_ROUTE;
        View request = new View(terrain, camera, lowerCutY == null ? terrain.minY() : lowerCutY, cutY,
            frameWidth, frameHeight, selected, visibleComparison == null ? region : null, route, showBuild ? buildPlan.revision() : 0,
            blueprint, blueprint.isEmpty() && preview == null ? null : buildPalette(), preview, visibleComparison, !interacting,
            patternedOverlays, module.useDedicatedGpu());
        if (pendingFrame == null && !request.equals(submitted) && now - lastFrameRequest >= MIN_FRAME_INTERVAL) {
            submitted = request;
            lastFrameRequest = now;
            pendingFrame = worker.submit(() -> renderView(request));
        }
    }

    private VoxelRenderer.Frame renderView(View request) {
        MapVolume volume = request.terrain();
        if (!request.blueprint().isEmpty()) {
            if (overlayTerrain != request.terrain() || overlayBlueprint != request.blueprint()
                || overlayPalette != request.palette()) {
                overlay = new BuildVolume(request.terrain(), request.blueprint(), request.palette(), request.buildRevision());
                overlayTerrain = request.terrain();
                overlayBlueprint = request.blueprint();
                overlayPalette = request.palette();
            }
            volume = overlay;
        }
        if (request.comparison() != null) {
            if (comparisonBase != volume || overlayComparison != request.comparison()) {
                comparisonBase = volume;
                overlayComparison = request.comparison();
                comparisonVolume = BuildVolume.comparison(volume, overlayComparison);
            }
            volume = comparisonVolume;
        }
        if (request.preview() != null) {
            boolean changed = !request.preview().equals(overlayPreview);
            if (changed) {
                try {
                    previewBlocks = request.preview().blocks();
                } catch (IllegalArgumentException oversized) {
                    previewBlocks = Map.of();
                }
                overlayPreview = request.preview();
            }
            if (previewBase != volume || changed) {
                previewVolume = previewBlocks.isEmpty() ? volume : BuildVolume.preview(volume, previewBlocks, request.palette());
                previewBase = volume;
            }
            volume = previewVolume;
        }
        return renderer.render(volume, request.camera(), request.lowerCutY(), request.cutY(), request.width(), request.height(),
            request.selected(), request.region(), request.route(), request.refineEdges(), request.patterned(), request.dedicatedGpu());
    }

    void drawCoverage(GuiGraphicsExtractor graphics, int right, int top, int space) {
        if (camera == null || coverage == null || space < COVERAGE_SIDE * 2 + 8) return;
        int size = Math.clamp((space - 8) / COVERAGE_SIDE, 2, 4);
        int left = right - COVERAGE_SIDE * size;
        int centerX = (int) Math.floor(camera.x()) >> 4;
        int centerZ = (int) Math.floor(camera.z()) >> 4;
        long now = System.nanoTime();
        if (centerX != coverageX || centerZ != coverageZ || now - lastCoverageDraw >= 250_000_000L) {
            coverageX = centerX;
            coverageZ = centerZ;
            lastCoverageDraw = now;
            for (int dz = 0; dz < COVERAGE_SIDE; dz++) {
                for (int dx = 0; dx < COVERAGE_SIDE; dx++) {
                    int chunkX = centerX + dx - COVERAGE_SIDE / 2;
                    int chunkZ = centerZ + dz - COVERAGE_SIDE / 2;
                    boolean loaded = minecraft.level != null
                        && minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
                    boolean surveyed = coverage.surveyed(chunkX, chunkZ) || surveyedSnapshot(chunkX, chunkZ);
                    coverageColors[dz * COVERAGE_SIDE + dx] = loaded ? COVERAGE_LOADED : surveyed ? COVERAGE_SURVEYED : COVERAGE_UNKNOWN;
                }
            }
        }
        graphics.fill(left - 3, top - 3, right + 3, top + COVERAGE_SIDE * size + 3, 0xEE101821);
        for (int dz = 0; dz < COVERAGE_SIDE; dz++) {
            for (int dx = 0; dx < COVERAGE_SIDE; dx++) {
                int px = left + dx * size, py = top + dz * size;
                int color = coverageColors[dz * COVERAGE_SIDE + dx];
                graphics.fill(px, py, px + size, py + size, patternedOverlays ? 0xff1b2531 : color);
                if (patternedOverlays) drawCoveragePattern(graphics, px, py, size, color);
            }
        }
        graphics.outline(left + COVERAGE_SIDE / 2 * size, top + COVERAGE_SIDE / 2 * size, size, size, 0xFFF5FAFF);
    }

    private static void drawCoveragePattern(GuiGraphicsExtractor graphics, int x, int y, int size, int color) {
        if (color == COVERAGE_LOADED) graphics.fill(x, y, x + size, y + size, 0xffe3e7eb);
        else if (color == COVERAGE_SURVEYED) graphics.fill(x, y + size / 2, x + size, y + size / 2 + 1, 0xffc0cbd4);
        else graphics.fill(x, y, x + 1, y + 1, 0xff8796a3);
    }

    private boolean surveyedSnapshot(int chunkX, int chunkZ) {
        if (savedLocation == null) return false;
        MapSnapshot.Volume volume = savedLocation.snapshot().volume();
        for (int y = volume.minY() >> 4; y <= volume.maxY() >> 4; y++) {
            if (volume.sectionView(chunkX, y, chunkZ) != null) return true;
        }
        return false;
    }

    private void drawMapMarkers(GuiGraphicsExtractor graphics) {
        int drawn = 0;
        for (var marker : mapMarkers()) {
            boolean visible = switch (marker.kind()) {
                case DEATH -> drawMarker(graphics, marker.position(), "X", marker.text(), 0xffffaaaa);
                case RESPAWN -> drawMarker(graphics, marker.position(), "R", marker.text(), 0xffc1aeff);
                case NOTE -> mapNotesVisible && drawMarker(graphics, marker.position(), "N", marker.text(), 0xffffdb95);
                case PORTAL -> drawMarker(graphics, marker.position(), "P", marker.text(), 0xffd59aff);
            };
            if (visible && ++drawn >= MAX_DRAWN_MARKERS) break;
        }
        if (previewStart != null) drawMarker(graphics, position(previewStart), "A", "Route start", 0xff91f4dd);
        if (previewEnd != null) drawMarker(graphics, position(previewEnd), "B", "Route destination", 0xff91f4dd);
    }

    private boolean drawMarker(GuiGraphicsExtractor graphics, BlockPos position, String symbol, String text, int accent) {
        int blockX = position.getX(), blockY = position.getY(), blockZ = position.getZ();
        if (blockY < displayedLowerCutY || blockY > displayedCutY || displayedTerrain == null
            || blockX < displayedTerrain.minX() || blockX > displayedTerrain.maxX()
            || blockZ < displayedTerrain.minZ() || blockZ > displayedTerrain.maxZ()) return false;
        var point = frame.project(blockX + .5, blockY + 1.08, blockZ + .5);
        if (point == null) return false;
        int screenX = x + (int) (point.x() * width / frame.width());
        int screenY = y + (int) (point.y() * height / frame.height());
        if (screenX < x + 7 || screenX >= x + width - 7 || screenY < y + 7 || screenY >= y + height - 3) return false;
        int labelWidth = Math.min(108, Math.max(0, x + width - screenX - 11));
        var font = minecraft.font;
        String label = font.plainSubstrByWidth(text, labelWidth);
        int ink = patternedOverlays ? 0xfff7f8fb : accent;
        graphics.fill(screenX - 6, screenY - 7, screenX + 7, screenY + 6, 0xff10171f);
        graphics.outline(screenX - 6, screenY - 7, 13, 13, ink);
        graphics.text(font, symbol, screenX - font.width(symbol) / 2, screenY - 5, ink, false);
        if (!label.isBlank()) {
            graphics.fill(screenX + 7, screenY - 6, screenX + 11 + font.width(label), screenY + 5, 0xe9151f2c);
            graphics.text(font, label, screenX + 9, screenY - 5, 0xfff2f6fb, false);
        }
        return true;
    }

    public List<MapAnnotations.Marker> mapMarkers() { return MapAnnotations.markers(minecraft); }

    public void saveMapNote(VoxelRenderer.Hit block, String note) { MapAnnotations.saveNote(minecraft, position(block), note); }

    public boolean removeMapNote(VoxelRenderer.Hit block) { return MapAnnotations.removeNote(minecraft, position(block)); }

    public boolean mapNotesVisible() { return mapNotesVisible; }
    public void setMapNotesVisible(boolean visible) { mapNotesVisible = visible; }
    public void setPatternedOverlays(boolean enabled) { patternedOverlays = enabled; }

    public VoxelRenderer.Hit previewStart() { return previewStart; }
    public VoxelRenderer.Hit previewEnd() { return previewEnd; }
    public String previewStatus() { return previewStatus; }

    public void setPreviewStart(VoxelRenderer.Hit selectedFloor) {
        previewStart = Objects.requireNonNull(selectedFloor, "route start");
        queueMapPreview();
    }

    public void setPreviewEnd(VoxelRenderer.Hit selectedFloor) {
        previewEnd = Objects.requireNonNull(selectedFloor, "route destination");
        queueMapPreview();
    }

    private void queueMapPreview() {
        cancelMapPreview();
        previewStatus = "Select a start and destination floor.";
        if (previewStart == null || previewEnd == null) return;
        var terrain = terrain();
        if (terrain == null) throw new IllegalStateException("Open surveyed terrain before previewing a map route.");
        var exclusions = Navigation.activeExclusions(false);
        var start = previewStart;
        var end = previewEnd;
        previewStatus = "Checking the route on surveyed terrain…";
        boolean dedicatedGpu = module.useDedicatedGpu();
        pendingMapPreview = routeWorker.submit(() -> SurveyRoute.plan(terrain, start, end, exclusions, dedicatedGpu));
    }

    private void readMapPreview() {
        if (pendingMapPreview == null || !pendingMapPreview.isDone()) return;
        try {
            var preview = pendingMapPreview.get();
            previewStatus = preview.message();
            mapPreviewBlocks = routeBlocks(preview.steps(), preview.start());
            mapProfile = RouteElevation.walking(preview.steps(), preview.start(), preview.partial(), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException problem) {
            previewStatus = "Could not finish the survey preview.";
            SopranoClient.LOG.warn("Soprano could not preview a survey route", problem);
        } finally {
            pendingMapPreview = null;
        }
    }

    public void clearMapPreview() {
        cancelMapPreview();
        previewStart = previewEnd = null;
        previewStatus = "Select a start and destination floor.";
    }

    private void cancelMapPreview() {
        if (pendingMapPreview != null) pendingMapPreview.cancel(true);
        pendingMapPreview = null;
        mapProfile = null;
        mapPreviewBlocks = NO_ROUTE;
    }

    public RouteElevation.Profile elevationProfile() {
        if (previewStart != null || previewEnd != null) return mapProfile;
        if (savedLocation != null) return null;
        Navigation navigation = Navigation.get();
        List<Step> path = navigation.path();
        List<Vec3> transport = navigation.transportPath();
        if (path != profilePath || transport != profileTransport) {
            profilePath = path;
            profileTransport = transport;
            liveProfile = path.isEmpty() ? RouteElevation.transport(transport)
                : RouteElevation.walking(path, null, navigation.travelProgress().partial(), false);
        }
        return liveProfile;
    }

    private static BlockPos position(VoxelRenderer.Hit block) { return new BlockPos(block.x(), block.y(), block.z()); }

    private void drawTransportPath(GuiGraphicsExtractor graphics) {
        List<Vec3> waypoints = Navigation.get().transportPath();
        if (waypoints.size() < 2) return;
        double total = 0;
        for (int i = 1; i < waypoints.size(); i++) total += waypoints.get(i - 1).distanceTo(waypoints.get(i));
        double spacing = Math.max(1.5, total / 580);
        double next = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            Vec3 from = waypoints.get(i - 1);
            Vec3 to = waypoints.get(i);
            double length = from.distanceTo(to);
            if (length < 0.001) continue;
            for (; next <= length; next += spacing) {
                Vec3 point = from.lerp(to, next / length);
                if (point.y > displayedCutY + 1 || point.y < displayedLowerCutY) continue;
                VoxelRenderer.ProjectedPoint projected = frame.project(point.x, point.y, point.z);
                if (projected == null || projected.depth() > frame.entityOcclusionAt((int) projected.x(), (int) projected.y()) + .3)
                    continue;
                int sx = x + (int) (projected.x() * width / frame.width());
                int sy = y + (int) (projected.y() * height / frame.height());
                graphics.fill(sx, sy, sx + 2, sy + 2, patternedOverlays ? 0xfff5f7fa : 0xff62ceff);
            }
            next -= length;
        }
    }

    private void drawRouteDirection(GuiGraphicsExtractor graphics) {
        Navigation navigation = Navigation.get();
        var progress = navigation.travelProgress();
        List<Step> route = navigation.path();
        if (!progress.partial() || progress.intendedGoal() == null || route.isEmpty()) return;
        Node last = route.getLast().to();
        var goal = progress.intendedGoal();
        double dx = goal.x() - last.x(), dz = goal.z() - last.z();
        double length = Math.hypot(dx, dz);
        if (length < 1) return;
        double maxDistance = Math.min(length, Math.max(12, displayedCamera.distance() * .65));
        double baseY = last.y() + .6;
        if (baseY < displayedLowerCutY || baseY > displayedCutY + 1) return;
        for (double distance = 2; distance <= maxDistance; distance += Math.max(1.7, maxDistance / 23)) {
            double fraction = distance / length;
            var point = frame.project(last.centerX() + dx * fraction, baseY, last.centerZ() + dz * fraction);
            if (point == null) continue;
            int px = x + (int) (point.x() * width / frame.width());
            int py = y + (int) (point.y() * height / frame.height());
            if (patternedOverlays) {
                graphics.fill(px - 1, py, px + 2, py + 1, 0xfff5f7fa);
                graphics.fill(px, py - 1, px + 1, py + 2, 0xfff5f7fa);
            } else graphics.fill(px, py, px + 2, py + 2, 0xFFFFC275);
        }
    }

    private long[] routeBlocks() {
        List<Step> path = Navigation.get().path();
        if (path == currentPath) return pathBlocks;
        currentPath = path;
        pathBlocks = routeBlocks(path, path.isEmpty() ? null : path.getFirst().from());
        return pathBlocks;
    }

    private static long[] routeBlocks(List<Step> path, Node start) {
        if (start == null) return NO_ROUTE;
        long[] blocks = new long[path.size() + 1];
        blocks[0] = floorKey(start);
        for (int i = 0; i < path.size(); i++) blocks[i + 1] = floorKey(path.get(i).to());
        Arrays.sort(blocks);
        return blocks;
    }

    private static long floorKey(Node node) {
        return VoxelRenderer.blockKey(node.x(), Math.floorDiv(node.y16() - 1, 16), node.z());
    }

    private void readFrame() {
        if (pendingFrame == null || !pendingFrame.isDone()) return;
        try {
            VoxelRenderer.Frame next = pendingFrame.get();
            if (next != null) {
                upload(next);
                frame = next;
                displayedLowerCutY = submitted.lowerCutY();
                displayedCutY = submitted.cutY();
                displayedCamera = submitted.camera();
                displayedTerrain = submitted.terrain();
                renderFailed = false;
            }
        } catch (ExecutionException | RuntimeException problem) {
            if (!renderFailed) SopranoClient.LOG.warn("Soprano could not render the planning map", problem);
            renderFailed = true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            pendingFrame = null;
        }
    }

    private void upload(VoxelRenderer.Frame next) {
        if (texture == null || frame == null || next.width() != frame.width() || next.height() != frame.height()) {
            releaseTexture();
            texture = new DynamicTexture("Soprano planning map", next.width(), next.height(), false);
            minecraft.getTextureManager().register(textureId, texture);
        }
        NativeImage pixels = texture.getPixels();
        int[] colors = next.argb();
        for (int row = 0; row < next.height(); row++) {
            int offset = row * next.width();
            for (int column = 0; column < next.width(); column++) pixels.setPixel(column, row, colors[offset + column]);
        }
        texture.upload();
    }

    public VoxelRenderer.Hit pick(double mouseX, double mouseY) {
        if (frame == null || width <= 0 || height <= 0 || mouseX < x || mouseY < y || mouseX >= x + width || mouseY >= y + height)
            return null;
        int pixelX = (int) ((mouseX - x) * frame.width() / width);
        int pixelY = (int) ((mouseY - y) * frame.height() / height);
        return frame.hitAt(pixelX, pixelY);
    }

    public BuildPlan.Cell pickPlacement(double mouseX, double mouseY) {
        if (capture == null || frame == null || width <= 0 || height <= 0 || mouseX < x || mouseY < y
            || mouseX >= x + width || mouseY >= y + height) return null;
        int pixelX = (int) ((mouseX - x) * frame.width() / width);
        int pixelY = (int) ((mouseY - y) * frame.height() / height);
        VoxelRenderer.Hit hit = frame.hitAt(pixelX, pixelY);
        if (hit != null) {
            VoxelRenderer.Face face = frame.faceAt(pixelX, pixelY);
            if (face != null) return buildCell(hit.x() + face.x(), hit.y() + face.y(), hit.z() + face.z());
            return buildCell(hit.x(), hit.y() + 1, hit.z());
        }
        return workPlaneCell(pixelX, pixelY);
    }

    public BuildPlan.Cell pickWorkPlane(double mouseX, double mouseY) {
        if (capture == null || frame == null || width <= 0 || height <= 0 || mouseX < x || mouseY < y
            || mouseX >= x + width || mouseY >= y + height) return null;
        return workPlaneCell((int) ((mouseX - x) * frame.width() / width),
            (int) ((mouseY - y) * frame.height() / height));
    }

    private BuildPlan.Cell workPlaneCell(int pixelX, int pixelY) {
        VoxelRenderer.Hit plane = frame.cellAtPlane(pixelX, pixelY, buildY);
        return plane == null ? null : buildCell(plane.x(), plane.y(), plane.z());
    }

    private BuildPlan.Cell buildCell(int cellX, int cellY, int cellZ) {
        if (cellX < capture.minX() || cellX > capture.maxX() || cellY < capture.minY() || cellY > capture.maxY()
            || cellZ < capture.minZ() || cellZ > capture.maxZ()) return null;
        return new BuildPlan.Cell(cellX, cellY, cellZ);
    }

    public BuildPlan buildPlan() { return buildPlan; }

    static BuildPlan worldBlueprint(ClientLevel level) {
        return WORLD_PLANS.computeIfAbsent(level, world -> new BuildPlan());
    }

    public BuildPalette buildPalette() {
        if (buildPalette == null) {
            BlockPos position = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
            buildPalette = new BuildPalette(minecraft.level, position);
        }
        return buildPalette;
    }

    public void showBuildPlan(boolean visible) {
        buildVisible = visible;
        if (!visible) buildPreview = null;
    }

    public void setBuildPreview(BuildPreview preview) {
        buildPreview = preview;
    }

    public int buildY() { return buildY; }

    public void setBuildY(int value) {
        buildY = Math.clamp(value, minY(), maxY());
    }

    public void saveBuild(String name) {
        try {
            BuildPlanFiles.save(buildDirectory(), name, buildPlan.snapshot());
        } catch (IOException problem) {
            throw new IllegalStateException("Could not save blueprint. " + problem.getMessage(), problem);
        }
    }

    public void loadBuild(String name) {
        try {
            buildPlan.replace(BuildPlanFiles.load(buildDirectory(), name));
        } catch (IOException problem) {
            throw new IllegalStateException("Could not load blueprint. " + problem.getMessage(), problem);
        }
    }

    Path buildDirectory() {
        return minecraft.gameDirectory.toPath().resolve("soprano").resolve("build-plans");
    }

    public String blockName(VoxelRenderer.Hit hit) {
        if (displayedTerrain == null || hit == null) return "Unsurveyed";
        if (buildVisible) {
            String plannedId = buildPlan.snapshot().get(new BuildPlan.Cell(hit.x(), hit.y(), hit.z()));
            if (plannedId != null) {
                BuildPalette.Entry entry = buildPalette().find(plannedId);
                return entry == null ? plannedId : entry.displayName();
            }
        }
        int id = displayedTerrain.blockAt(hit.x(), hit.y(), hit.z());
        MapMaterial material = id > 0 ? displayedTerrain.material(id) : null;
        return material == null ? "Unsurveyed" : material.name();
    }

    public void highlight(VoxelRenderer.Hit hit, MapVolume.Box area) {
        selected = hit;
        region = area;
    }

    public MapVolume.AreaStats stats(MapVolume.Box box) {
        if (closed || box == null) return null;
        MapSnapshot.Volume terrain = terrain();
        if (terrain == null) return null;
        if (!Objects.equals(countedBox, box)) {
            cancelCount();
            countedBox = box;
            count = null;
            countFailed = false;
        }
        if (pendingCount != null && pendingCount.isDone()) {
            try {
                Count next = pendingCount.get();
                if (next.box().equals(box)) count = next;
            } catch (ExecutionException | CancellationException problem) {
                if (!countFailed) SopranoClient.LOG.warn("Soprano could not count the selected area", problem);
                countFailed = true;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                pendingCount = null;
            }
        }
        long now = System.nanoTime();
        if (pendingCount == null && !countFailed && (count == null || count.terrain() != terrain)
            && (count == null || now - lastStatsRequest >= MIN_STATS_INTERVAL)) {
            lastStatsRequest = now;
            pendingCount = worker.submit(() -> new Count(terrain, box, terrain.stats(box)));
        }
        return count == null ? null : count.stats();
    }

    public String progressText() {
        if (renderFailed) return "Map rendering could not finish.";
        String status = savedLocation != null ? "Saved location · " + savedLocation.name()
            : capture == null ? "Join a world to capture terrain." : capture.progressText();
        return frame == null || frame.gpuDevice() == null ? status : "Vulkan air traversal · " + frame.gpuDevice() + " · " + status;
    }

    private MapSnapshot.Volume terrain() {
        return savedLocation != null ? savedLocation.snapshot().volume() : capture == null ? null : capture.freeze();
    }

    private MapVolume bounds() {
        return savedLocation != null ? savedLocation.snapshot().volume() : capture;
    }

    public String snapshotName() { return savedLocation == null ? null : savedLocation.name(); }
    BlockPos savedDestination() {
        if (savedLocation == null) return null;
        VoxelRenderer.Camera focus = savedLocation.snapshot().camera();
        return BlockPos.containing(focus.x(), focus.y(), focus.z());
    }

    void focusLocation(BlockPos target) {
        if (camera == null) return;
        camera = new VoxelRenderer.Camera(target.getX() + .5, target.getY(), target.getZ() + .5,
            camera.yaw(), camera.pitch(), camera.distance());
    }

    void setComparison(BlueprintActions.Comparison next) {
        comparison = next;
        comparisonRevision = buildPlan.revision();
    }
    public boolean locationsAvailable() { return locations.available(); }
    public List<String> locationNames() { return locations.names(); }
    public boolean locationsBusy() { return locations.busy(); }
    public String locationStatus() { return locations.message(); }
    public boolean canSaveLocation() {
        return locations.available() && !locations.busy() && savedLocation == null && camera != null
            && capture != null && capture.revision() != 0;
    }

    public void listLocations() { locations.list(); }
    public void openLocation(String name) { locations.open(name); }

    public void saveLocation(String name) {
        if (!canSaveLocation()) throw new IllegalStateException("Wait for the live survey before saving a location.");
        locations.save(name, new MapSnapshot(capture.freeze(), camera, cutY, lowerCutY));
    }

    public void useLive() {
        locations.ignorePendingOpen();
        if (savedLocation != null) refresh();
    }

    private void showSnapshot(SavedLocations.Opened opened) {
        savedLocation = opened;
        clearDisplayedMap();
        cutY = opened.snapshot().cutY();
        lowerCutY = opened.snapshot().lowerCutY();
        home();
    }

    public int cutY() { return cutY; }
    public Integer lowerCutY() { return lowerCutY; }
    public int minY() { MapVolume bounds = bounds(); return bounds == null ? -64 : bounds.minY(); }
    public int maxY() { MapVolume bounds = bounds(); return bounds == null ? 320 : bounds.maxY(); }

    public void setCutY(int value) {
        cutY = Math.clamp(value, lowerCutY == null ? minY() : lowerCutY, maxY());
    }

    public void setLowerCutY(Integer value) {
        lowerCutY = value == null ? null : Math.clamp(value, minY(), cutY);
    }

    public boolean setMoveKey(int key, boolean pressed) {
        int flag = MapCameraMotion.keyFlag(key);
        if (flag == 0) return false;
        if (pressed) {
            if (!MapCameraMotion.moving(moveKeys)) lastCameraUpdate = System.nanoTime();
            moveKeys |= flag;
        } else {
            moveKeys &= ~flag;
        }
        return true;
    }

    public void clearMoveKeys() {
        moveKeys = 0;
        lastCameraUpdate = 0;
    }

    private void advanceCamera() {
        if (moveKeys == 0) return;
        if (!minecraft.getWindow().isFocused() || bounds() == null || camera == null) {
            clearMoveKeys();
            return;
        }
        long now = System.nanoTime();
        double seconds = Math.clamp((now - lastCameraUpdate) / 1_000_000_000.0, 0, .05);
        lastCameraUpdate = now;
        VoxelRenderer.Camera next = MapCameraMotion.advance(camera, bounds(), moveKeys, module.mapMoveSpeed(), seconds);
        if (!next.equals(camera)) {
            camera = next;
            lastInteraction = now;
        }
    }

    public void orbit(double dx, double dy) {
        if (camera == null) return;
        lastInteraction = System.nanoTime();
        camera = new VoxelRenderer.Camera(camera.x(), camera.y(), camera.z(),
            (camera.yaw() - dx * .42) % 360, Math.clamp(camera.pitch() + dy * .36, 4, 88), camera.distance());
    }

    public void pan(double dx, double dy) {
        if (camera == null || bounds() == null) return;
        double angle = Math.toRadians(camera.yaw());
        double scale = camera.distance() / Math.max(64, height);
        lastInteraction = System.nanoTime();
        camera = MapCameraMotion.translate(camera, bounds(), (-dx * Math.cos(angle) + dy * Math.sin(angle)) * scale, 0,
            (dx * Math.sin(angle) + dy * Math.cos(angle)) * scale);
    }

    public void zoom(double scroll) {
        MapVolume bounds = bounds();
        if (camera == null || bounds == null) return;
        lastInteraction = System.nanoTime();
        double size = Math.max(bounds.maxX() - (double) bounds.minX(), bounds.maxZ() - (double) bounds.minZ());
        double distance = Math.clamp(camera.distance() * Math.pow(.82, scroll), 5, Math.max(96, size * 1.65));
        camera = new VoxelRenderer.Camera(camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch(), distance);
    }

    public void home() {
        if (savedLocation != null) {
            camera = savedLocation.snapshot().camera();
            return;
        }
        if (capture == null || minecraft.player == null) return;
        camera = new VoxelRenderer.Camera(Math.clamp(minecraft.player.getX(), capture.minX(), capture.maxX()),
            Math.clamp(minecraft.player.getY() + 3, capture.minY(), capture.maxY()),
            Math.clamp(minecraft.player.getZ(), capture.minZ(), capture.maxZ()), 36, 56, 60);
    }

    public void refresh() {
        refresh(false);
    }

    void refreshLoaded() { refresh(true); }

    private void refresh(boolean resurvey) {
        if (closed) return;
        savedLocation = null;
        locations.ignorePendingOpen();
        locations.setWorld(minecraft);
        clearDisplayedMap();
        if (coverage != null) coverage.flush();
        if (capture != null) capture.flushSurvey();
        surveyScope = module.storageScope();
        String coverageScope = surveyScope == null ? null : surveyScope.server() + "\n" + surveyScope.world() + "\n" + surveyScope.dimension();
        Path directory = minecraft.gameDirectory.toPath().resolve("soprano");
        coverage = MapCoverage.open(directory.resolve("coverage"), coverageScope);
        surveyCache = SurveyCache.open(directory.resolve("survey-cache"), surveyScope, module.surveyCacheLimitBytes());
        coverageX = Integer.MIN_VALUE;
        lastCoverageSave = System.nanoTime();
        comparison = null;
        if (minecraft.level == null || minecraft.player == null) capture = null;
        else if (!resurvey && retainedSurvey != null && Objects.equals(surveyScope, retainedSurvey.scope())
            && retainedSurvey.capture().nearPlayer(minecraft)) capture = retainedSurvey.capture();
        else capture = new WorldMapCapture(minecraft, coverage, surveyCache, resurvey);
        retainedSurvey = capture == null ? null : new RetainedSurvey(surveyScope, capture);
        if (buildLevel != minecraft.level) {
            buildLevel = minecraft.level;
            buildPlan = buildLevel == null ? new BuildPlan() : worldBlueprint(buildLevel);
            buildPalette = null;
            buildPreview = null;
            setBuildY(minecraft.player == null ? 64 : minecraft.player.blockPosition().getY());
        }
        cutY = maxY();
        lowerCutY = null;
        home();
    }

    void clearSurvey() {
        if (capture == null || savedLocation != null) throw new IllegalStateException("Open the live map to clear its survey.");
        FootSurvey.get().stop();
        if (surveyCache != null) surveyCache.clear();
        if (coverage != null) coverage.clear();
        retainedSurvey = null;
        capture = null;
        refresh(false);
    }

    public static void restoreRetainedSurvey(MapVolume.Box region) {
        if (retainedSurvey != null) retainedSurvey.capture().restoreSurvey(region);
    }

    public static void releaseRetainedSurvey() {
        if (retainedSurvey == null) return;
        retainedSurvey.capture().flushSurvey();
        retainedSurvey = null;
    }

    public static MapSnapshot.Volume retainedTerrain(Minecraft minecraft) {
        return retainedSurvey == null || !retainedSurvey.capture().valid(minecraft) ? null : retainedSurvey.capture().freeze();
    }

    private void clearDisplayedMap() {
        clearMoveKeys();
        entities.clear();
        clearMapPreview();
        if (pendingFrame != null) pendingFrame.cancel(true);
        pendingFrame = null;
        submitted = null;
        cancelCount();
        count = null;
        countedBox = null;
        renderFailed = countFailed = false;
        frame = null;
        selected = null;
        region = null;
        releaseTexture();
        displayedTerrain = null;
        displayedCamera = null;
    }

    private void cancelCount() {
        if (pendingCount != null) pendingCount.cancel(true);
        pendingCount = null;
    }

    private void releaseTexture() {
        if (texture == null) return;
        minecraft.getTextureManager().release(textureId);
        texture = null;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        clearMoveKeys();
        entities.clear();
        if (pendingFrame != null) pendingFrame.cancel(true);
        cancelCount();
        worker.shutdownNow();
        clearMapPreview();
        routeWorker.shutdownNow();
        locations.close();
        if (coverage != null) coverage.flush();
        if (capture != null) capture.flushSurvey();
        releaseTexture();
        capture = null;
        frame = null;
    }
}
