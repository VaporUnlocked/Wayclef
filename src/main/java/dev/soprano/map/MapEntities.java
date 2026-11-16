package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Extracts visible live models on the client thread and highlights the local player. */
public final class MapEntities {
    private static final int MAX_MODELS = 112;
    private static final int MAX_LABELS = 18;
    private static final int MAX_MODEL_WIDTH = 1_536;
    private static final int MAX_MODEL_HEIGHT = 960;
    private static final double MIN_MODEL_PIXELS = 3;
    private static final int YOU = 0xff8fffe0;
    private static final int LABEL_BACKGROUND = 0xe9151f2c;
    private static final int LABEL_TEXT = 0xfff2f6fb;
    private static final double DEPTH_TOLERANCE = .04;

    private record ProjectedEntity(Entity entity, boolean local, VoxelRenderer.ProjectedPoint anchor,
                                   boolean visible, double span, float partialTick) { }
    record ModelSize(int width, int height) { }
    private record Label(int left, int top, int right, int bottom, String text, int tint) {
        boolean overlaps(Label other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }

    private List<Entity> entities = List.of();
    private final Set<EntityType<?>> extractionFailures = new HashSet<>();

    public void tick(Minecraft client, MapVolume terrain) {
        if (client.level == null || client.player == null || terrain == null) {
            clear();
            return;
        }

        Player viewer = client.player;
        List<Entity> loaded = new ArrayList<>();
        if (inBounds(viewer, terrain)) loaded.add(viewer);
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity != viewer && canShow(entity, viewer) && inBounds(entity, terrain)) loaded.add(entity);
        }
        entities = loaded;
    }

    public void clear() {
        entities = List.of();
    }

    public void draw(GuiGraphicsExtractor graphics, Minecraft client, VoxelRenderer.Frame frame,
                     VoxelRenderer.Camera camera, int left, int top, int width, int height, int cutY,
                     boolean names) {
        draw(graphics, client, frame, camera, left, top, width, height, Integer.MIN_VALUE, cutY, names);
    }

    public void draw(GuiGraphicsExtractor graphics, Minecraft client, VoxelRenderer.Frame frame,
                     VoxelRenderer.Camera camera, int left, int top, int width, int height, int lowerCutY, int cutY,
                     boolean names) {
        if (width <= 0 || height <= 0 || frame == null || camera == null || entities.isEmpty()) return;
        if (client.player == null || client.level == null) return;

        int guiScale = Math.max(1, client.getWindow().getGuiScale());
        ModelSize modelSize = modelSize(width, height, guiScale);
        double modelPixelScale = modelSize.height() * (double) guiScale / frame.height();
        var ticks = client.level.tickRateManager();
        var delta = client.getDeltaTracker();
        List<ProjectedEntity> projected = new ArrayList<>();
        for (Entity entity : entities) {
            boolean local = entity == client.player;
            if (!local && !canShow(entity, client.player)) continue;
            // Screen rendering receives elapsed frame ticks. World entities use the fraction of the game tick.
            float partialTick = delta.getGameTimeDeltaPartialTick(!ticks.isEntityFrozen(entity));
            Vec3 position = entity.getPosition(partialTick);
            if (position.y >= cutY + 1.0 || position.y < lowerCutY) continue;
            double heightBlocks = Math.max(.06, entity.getBbHeight());
            VoxelRenderer.ProjectedPoint center = frame.project(position.x, position.y + heightBlocks * .56, position.z);
            VoxelRenderer.ProjectedPoint head = frame.project(position.x, position.y + heightBlocks * .96, position.z);
            VoxelRenderer.ProjectedPoint feet = frame.project(position.x, position.y + heightBlocks * .08, position.z);
            VoxelRenderer.ProjectedPoint anchor = center != null ? center : head != null ? head : feet;
            if (anchor == null) continue;

            boolean visible = visible(frame, center) || visible(frame, head) || visible(frame, feet);
            if (!visible && !local) continue;
            double span = Math.max(heightBlocks, entity.getBbWidth()) * frame.height()
                / (2 * Math.tan(Math.toRadians(VoxelRenderer.FIELD_OF_VIEW / 2)) * anchor.depth());
            if (!local && span * modelPixelScale < MIN_MODEL_PIXELS) continue;
            projected.add(new ProjectedEntity(entity, local, anchor, visible, span, partialTick));
        }
        if (projected.isEmpty()) return;

        projected.sort(Comparator.<ProjectedEntity>comparingInt(projectedEntity ->
            projectedEntity.local() ? 0 : projectedEntity.entity() instanceof Player ? 1 : 2)
            .thenComparingDouble(projectedEntity -> projectedEntity.anchor().depth()));

        List<EntityRenderState> models = extract(client.getEntityRenderDispatcher(), client.player, projected);
        if (!models.isEmpty()) {
            int modelWidth = modelSize.width();
            int modelHeight = modelSize.height();
            Matrix3x2f pose = new Matrix3x2f(graphics.pose()).translate(left, top)
                .scale((float) width / modelWidth, (float) height / modelHeight);
            var clip = new ScreenRectangle(left, top, width, height);
            var scene = new MapModelPassState(modelWidth, modelHeight, pose, clip, frame, camera, models);
            ((GuiGraphicsExtractorAccessor) graphics).soprano$guiRenderState().addPicturesInPictureState(scene);
        }
        drawLabels(graphics, client.font, frame, projected, left, top, width, height, names);
    }

    static ModelSize modelSize(int width, int height, int guiScale) {
        // Keep geometry at display resolution even while the terrain is rendering at interaction quality.
        double ratio = Math.min(1, Math.min(MAX_MODEL_WIDTH / ((double) width * guiScale),
            MAX_MODEL_HEIGHT / ((double) height * guiScale)));
        return new ModelSize(Math.max(1, (int) Math.floor(width * ratio)), Math.max(1, (int) Math.floor(height * ratio)));
    }

    private List<EntityRenderState> extract(EntityRenderDispatcher dispatcher, Player viewer,
                                            List<ProjectedEntity> projected) {
        List<EntityRenderState> models = new ArrayList<>(Math.min(projected.size(), MAX_MODELS));
        for (ProjectedEntity item : projected) {
            Entity entity = item.entity();
            if (!item.visible() || !canShow(entity, viewer) || extractionFailures.contains(entity.getType())) continue;
            try {
                EntityRenderState state = dispatcher.extractEntity(entity, item.partialTick());
                state.outlineColor = EntityRenderState.NO_OUTLINE;
                state.nameTag = null;
                state.scoreText = null;
                state.nameTagAttachment = null;
                state.leashStates = null;
                state.shadowPieces.clear();
                state.lightCoords = LightCoordsUtil.FULL_BRIGHT;
                models.add(state);
            } catch (RuntimeException failure) {
                if (extractionFailures.add(entity.getType()))
                    SopranoClient.LOG.warn("Soprano skipped an unavailable entity model in its planning map", failure);
            }
            if (models.size() >= MAX_MODELS) break;
        }
        return List.copyOf(models);
    }

    private static void drawLabels(GuiGraphicsExtractor graphics, Font font, VoxelRenderer.Frame frame,
                                   List<ProjectedEntity> projected, int left, int top, int width, int height, boolean names) {
        if (width < 24 || height < 20) return;
        List<Label> labels = new ArrayList<>(MAX_LABELS);
        for (ProjectedEntity item : projected) {
            if (labels.size() >= MAX_LABELS) break;
            if (!item.local() && (!names || item.anchor().depth() > (item.entity() instanceof Player ? 96 : 42))) continue;

            int x = left + (int) (item.anchor().x() * width / frame.width());
            int y = top + (int) (item.anchor().y() * height / frame.height());
            int radius = Math.clamp((int) Math.ceil(item.span() * height / frame.height() * .53), 4, 80);
            if (item.local()) drawPlayerBrackets(graphics, x, y, radius + 2);

            String labelName = item.local() ? "You" : item.entity().getName().getString();
            String text = font.plainSubstrByWidth(labelName, Math.min(120, width - 14));
            if (text.isBlank()) continue;
            int labelWidth = font.width(text) + 9;
            int labelX = Math.clamp(x - labelWidth / 2, left + 1, left + width - labelWidth - 1);
            int labelY = y - radius - 15;
            if (labelY < top + 1) labelY = y + radius + 3;
            if (labelY + 12 > top + height - 1) continue;
            int tint = item.local() ? YOU : item.entity() instanceof Player ? 0xff70d9ff : 0xffbbbedc;
            Label label = new Label(labelX, labelY, labelX + labelWidth, labelY + 12, text, tint);
            if (labels.stream().noneMatch(label::overlaps)) labels.add(label);
        }
        for (Label label : labels) {
            graphics.fill(label.left(), label.top(), label.right(), label.bottom(), LABEL_BACKGROUND);
            graphics.fill(label.left(), label.top(), label.left() + 2, label.bottom(), label.tint());
            graphics.text(font, label.text(), label.left() + 5, label.top() + 2, LABEL_TEXT, false);
        }
    }

    private static void drawPlayerBrackets(GuiGraphicsExtractor graphics, int x, int y, int radius) {
        int arm = Math.clamp(radius / 3, 3, 9);
        int x0 = x - radius, x1 = x + radius, y0 = y - radius, y1 = y + radius;
        graphics.fill(x0, y0, x0 + arm, y0 + 1, YOU);
        graphics.fill(x0, y0, x0 + 1, y0 + arm, YOU);
        graphics.fill(x1 - arm, y0, x1, y0 + 1, YOU);
        graphics.fill(x1 - 1, y0, x1, y0 + arm, YOU);
        graphics.fill(x0, y1 - 1, x0 + arm, y1, YOU);
        graphics.fill(x0, y1 - arm, x0 + 1, y1, YOU);
        graphics.fill(x1 - arm, y1 - 1, x1, y1, YOU);
        graphics.fill(x1 - 1, y1 - arm, x1, y1, YOU);
    }

    private static boolean visible(VoxelRenderer.Frame frame, VoxelRenderer.ProjectedPoint point) {
        return point != null && point.depth() <= frame.entityOcclusionAt((int) point.x(), (int) point.y()) + DEPTH_TOLERANCE;
    }

    private static boolean canShow(Entity entity, Player viewer) {
        // Text displays submit world-space glyphs independently of the ordinary entity name tag.
        return !(entity instanceof Display.TextDisplay)
            && !entity.isRemoved() && entity.isAlive() && !entity.isSpectator()
            && !entity.isInvisible() && !entity.isInvisibleTo(viewer);
    }

    private static boolean inBounds(Entity entity, MapVolume terrain) {
        double x = entity.getX(), y = entity.getY(), z = entity.getZ();
        return x >= terrain.minX() && x < terrain.maxX() + 1.0 && y >= terrain.minY() && y < terrain.maxY() + 1.0
            && z >= terrain.minZ() && z < terrain.maxZ() + 1.0
            && terrain.sectionState(section(x), section(y), section(z)) >= 0;
    }

    private static int section(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }
}
