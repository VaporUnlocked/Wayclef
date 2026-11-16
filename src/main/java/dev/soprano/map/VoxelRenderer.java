package dev.soprano.map;

import java.util.Arrays;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;

/**
 * Perspective voxel renderer for the planning map. Each pixel traces to the
 * first solid block, so the image and its block picker share the same depth.
 */
public final class VoxelRenderer {
    private static final int X_FACE = 0;
    private static final int Y_FACE = 1;
    private static final int Z_FACE = 2;
    private static final int FOG_COLOR = 0xff273747;
    public static final double FIELD_OF_VIEW = 52;
    private static final double TAN_HALF_FOV = Math.tan(Math.toRadians(FIELD_OF_VIEW * .5));
    private static final double DIRECTION_EPSILON = 1e-12;
    private static final double CELL_ROUNDING_WINDOW = 1e-7;
    private static final double PREVIEW_BLEND = .64;
    private static final double SURVEY_EDGE_BLEND = .48;
    private static final double[] COVERAGE_X = {-.375, -.125, .125, .375};
    private static final double[] COVERAGE_Y = {-.125, .375, -.375, .125};
    private static final Surface CONTINUOUS_SOLID = new Surface(0, 0, Y_FACE);
    private static final long[] NO_ROUTE = {};
    private static final int PARALLEL_PIXELS = 32_768;
    private static final int ROWS_PER_TASK = 16;
    private static final int RENDER_THREADS = Math.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, 4);
    private static final ForkJoinPool WORKERS = RENDER_THREADS == 1 ? null : new ForkJoinPool(RENDER_THREADS);
    private final GpuMapAirPrefix gpu = new GpuMapAirPrefix();

    /** The camera orbits the target. Yaw zero places it on the positive Z side. */
    public record Camera(double x, double y, double z, double yaw, double pitch, double distance) {
        public Camera {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(yaw)
                || !Double.isFinite(pitch) || !Double.isFinite(distance) || distance <= 0) {
                throw new IllegalArgumentException("The map camera needs finite coordinates and a positive distance.");
            }
        }
    }

    public record Hit(int x, int y, int z) { }

    public record Face(int x, int y, int z) { }

    /** Frame pixel coordinates, with camera-forward depth measured in blocks. */
    public record ProjectedPoint(double x, double y, double depth) { }

    public static final class Frame {
        private final int width, height;
        private final int minX, minY, minZ;
        private final long sizeY, sizeZ;
        private final int[] argb;
        private final long[] picked;
        private final byte[] faces;
        private final float[] depths;
        private final float[] unknownDepths;
        private final Projection projection;
        private String gpuDevice;

        private Frame(int width, int height, MapVolume volume, Projection projection) {
            this.width = width;
            this.height = height;
            this.projection = projection;
            minX = volume.minX();
            minY = volume.minY();
            minZ = volume.minZ();
            sizeY = (long) volume.maxY() - minY + 1;
            sizeZ = (long) volume.maxZ() - minZ + 1;
            argb = new int[width * height];
            picked = new long[argb.length];
            faces = new byte[argb.length];
            depths = new float[argb.length];
            unknownDepths = new float[argb.length];
        }

        public int width() { return width; }
        public int height() { return height; }
        public String gpuDevice() { return gpuDevice; }

        /** Packed ARGB pixels in row order. The completed frame owns this buffer. */
        public int[] argb() { return argb; }

        public Hit hitAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return null;
            long encoded = picked[y * width + x];
            if (encoded == 0) return null;
            long value = encoded - 1;
            long column = value / sizeZ;
            return new Hit((int) (minX + column / sizeY), (int) (minY + column % sizeY),
                (int) (minZ + value % sizeZ));
        }

        public Face faceAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return null;
            return switch (faces[y * width + x]) {
                case 1 -> new Face(1, 0, 0);
                case 2 -> new Face(-1, 0, 0);
                case 3 -> new Face(0, 1, 0);
                case 4 -> new Face(0, -1, 0);
                case 5 -> new Face(0, 0, 1);
                case 6 -> new Face(0, 0, -1);
                default -> null;
            };
        }

        /** Finds a block column on a horizontal construction plane through this rendered pixel. */
        public Hit cellAtPlane(int x, int y, int planeY) {
            if (x < 0 || x >= width || y < 0 || y >= height) return null;
            double horizontal = (x + .5 - width * .5) * projection.scale;
            double vertical = (height * .5 - y - .5) * projection.scale;
            double rayY = projection.forwardY + vertical * projection.upY;
            if (Math.abs(rayY) < DIRECTION_EPSILON) return null;
            double time = (planeY - projection.originY) / rayY;
            if (!(time > 0) || time > 4_096) return null;
            double rayX = projection.forwardX + horizontal * projection.rightX + vertical * projection.upX;
            double rayZ = projection.forwardZ + horizontal * projection.rightZ + vertical * projection.upZ;
            return new Hit((int) Math.floor(projection.originX + rayX * time), planeY,
                (int) Math.floor(projection.originZ + rayZ * time));
        }

        /** Camera-forward depth at a pixel, or positive infinity for sky or an out-of-frame pixel. */
        public float depthAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return Float.POSITIVE_INFINITY;
            int index = y * width + x;
            return picked[index] == 0 ? Float.POSITIVE_INFINITY : depths[index];
        }

        /** Nearest terrain or unsurveyed-section depth, for conservatively occluded map markers. */
        public float entityOcclusionAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return Float.POSITIVE_INFINITY;
            float unknown = unknownDepths[y * width + x];
            return unknown == 0 ? depthAt(x, y) : Math.min(depthAt(x, y), unknown);
        }

        private void markUnknown(int pixel, double time) {
            if (unknownDepths[pixel] == 0) unknownDepths[pixel] = Math.max(Float.MIN_VALUE, (float) time);
        }

        /** Projects with this frame's camera, or returns null for points behind it or outside the image. */
        public ProjectedPoint project(double x, double y, double z) {
            double dx = x - projection.originX, dy = y - projection.originY, dz = z - projection.originZ;
            double depth = dx * projection.forwardX + dy * projection.forwardY + dz * projection.forwardZ;
            if (!(depth > DIRECTION_EPSILON)) return null;
            double pixelScale = 1 / (depth * projection.scale);
            double screenX = width * .5 + (dx * projection.rightX + dz * projection.rightZ) * pixelScale;
            double screenY = height * .5 - (dx * projection.upX + dy * projection.upY + dz * projection.upZ) * pixelScale;
            return screenX >= 0 && screenX < width && screenY >= 0 && screenY < height
                ? new ProjectedPoint(screenX, screenY, depth) : null;
        }

        private void hit(int pixel, int x, int y, int z, byte face, double depth) {
            picked[pixel] = (((long) x - minX) * sizeY + ((long) y - minY)) * sizeZ + ((long) z - minZ) + 1;
            depths[pixel] = (float) depth;
            faces[pixel] = face;
        }
    }

    record Projection(double originX, double originY, double originZ,
                              double forwardX, double forwardY, double forwardZ,
                              double upX, double upY, double upZ, double rightX, double rightZ, double scale) {
        static Projection of(Camera camera, int height) {
            double yaw = Math.toRadians(camera.yaw() % 360);
            double pitch = Math.toRadians(Math.clamp(camera.pitch(), -90, 90));
            double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
            double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
            double forwardX = -cosPitch * sinYaw, forwardY = -sinPitch, forwardZ = -cosPitch * cosYaw;
            return new Projection(camera.x() - forwardX * camera.distance(), camera.y() - forwardY * camera.distance(),
                camera.z() - forwardZ * camera.distance(), forwardX, forwardY, forwardZ,
                -sinPitch * sinYaw, cosPitch, -sinPitch * cosYaw, cosYaw, -sinYaw, 2 * TAN_HALF_FOV / height);
        }
    }

    private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }

    /** The height cut includes the top face of blocks at cutY. */
    public Frame render(MapVolume volume, Camera camera, int cutY, int width, int height) {
        return render(volume, camera, cutY, width, height, null, null);
    }

    /** Highlights the selected block and the visible faces of the selected volume. */
    public Frame render(MapVolume volume, Camera camera, int cutY, int width, int height,
                        Hit selected, MapVolume.Box region) {
        return render(volume, camera, cutY, width, height, selected, region, NO_ROUTE);
    }

    /** Also highlights the visible faces of the sorted route block keys. */
    public Frame render(MapVolume volume, Camera camera, int cutY, int width, int height,
                        Hit selected, MapVolume.Box region, long[] routeBlocks) {
        return render(volume, camera, cutY, width, height, selected, region, routeBlocks, false);
    }

    /** Refines geometric coverage while keeping picking and depth on the center rays. */
    public Frame render(MapVolume volume, Camera camera, int cutY, int width, int height,
                        Hit selected, MapVolume.Box region, long[] routeBlocks, boolean refineEdges) {
        return render(volume, camera, volume.minY(), cutY, width, height, selected, region, routeBlocks, refineEdges, false);
    }

    /** Both section limits are inclusive. Patterns reinforce overlay meanings without relying on hue. */
    public Frame render(MapVolume volume, Camera camera, int lowerCutY, int cutY, int width, int height,
                        Hit selected, MapVolume.Box region, long[] routeBlocks, boolean refineEdges, boolean patterns) {
        return render(volume, camera, lowerCutY, cutY, width, height, selected, region, routeBlocks, refineEdges, patterns, false);
    }

    public Frame render(MapVolume volume, Camera camera, int lowerCutY, int cutY, int width, int height,
                        Hit selected, MapVolume.Box region, long[] routeBlocks, boolean refineEdges, boolean patterns,
                        boolean dedicatedGpu) {
        if (width <= 0 || height <= 0 || (long) width * height > 1_048_576) {
            throw new IllegalArgumentException("Invalid planning map image size.");
        }
        Projection projection = Projection.of(camera, height);
        Frame frame = new Frame(width, height, volume, projection);
        Bounds bounds = new Bounds(volume.minX(), Math.max(volume.minY(), lowerCutY), volume.minZ(), volume.maxX(),
            Math.min(volume.maxY(), cutY), volume.maxZ());
        boolean hasBounds = bounds.minX() <= bounds.maxX() && bounds.minY() <= bounds.maxY() && bounds.minZ() <= bounds.maxZ();
        GpuMapAirPrefix.Hint hint = dedicatedGpu && hasBounds && volume instanceof MapSnapshot.Volume terrain
            ? gpu.trace(terrain, new MapVolume.Box(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(),
                bounds.maxZ()), projection, width, height, refineEdges) : null;
        frame.gpuDevice = hint == null ? null : hint.device();
        Scene scene = new Scene(volume, bounds, frame, selected, region, routeBlocks, camera.y(),
            camera.distance() * .85, Math.max(36, camera.distance() * 1.2), volume.hasSectionViews(),
            hasBounds, refineEdges ? new int[width * height] : null, patterns, hint);
        runRows(scene, false);
        if (refineEdges && scene.hasBounds) runRows(scene, true);
        return frame;
    }

    private static void runRows(Scene scene, boolean refine) {
        if (WORKERS != null && scene.frame.argb.length >= PARALLEL_PIXELS) {
            WORKERS.invoke(new RenderRows(scene, 0, scene.frame.height, refine));
        } else {
            renderRows(scene, 0, scene.frame.height, refine);
        }
    }

    private record Scene(MapVolume volume, Bounds bounds, Frame frame, Hit selected, MapVolume.Box region,
                         long[] routeBlocks, double planeY, double fogStart, double fogWidth, boolean hasSectionViews,
                         boolean hasBounds, int[] surfaceKeys, boolean patterns, GpuMapAirPrefix.Hint hint) { }

    private static final class RenderRows extends RecursiveAction {
        private final Scene scene;
        private final int first, end;
        private final boolean refine;

        RenderRows(Scene scene, int first, int end, boolean refine) {
            this.scene = scene;
            this.first = first;
            this.end = end;
            this.refine = refine;
        }

        @Override protected void compute() {
            if (end - first <= ROWS_PER_TASK) {
                renderRows(scene, first, end, refine);
            } else {
                int middle = (first + end) >>> 1;
                invokeAll(new RenderRows(scene, first, middle, refine), new RenderRows(scene, middle, end, refine));
            }
        }
    }

    private static void renderRows(Scene scene, int first, int end, boolean refine) {
        Frame frame = scene.frame;
        Projection p = frame.projection;
        int width = frame.width, height = frame.height;
        for (int row = first; row < end; row++) {
            double vertical = (height * .5 - row - .5) * p.scale;
            double baseX = p.forwardX + p.upX * vertical;
            double baseZ = p.forwardZ + p.upZ * vertical;
            double rayY = zeroTiny(p.forwardY + p.upY * vertical);
            int sky = blend(0xff16212f, 0xff293848, .52 * row / Math.max(1, height - 1));
            for (int column = 0; column < width; column++) {
                int pixel = row * width + column;
                if (refine && !crossesEdge(scene.surfaceKeys, width, height, column, row, pixel)) continue;
                double horizontal = (column + .5 - width * .5) * p.scale;
                double airPrefix = scene.hint == null ? 0 : scene.hint.at(pixel);
                if (refine) {
                    frame.argb[pixel] = resolveEdge(scene, p, horizontal, vertical, sky, frame.argb[pixel], airPrefix);
                    continue;
                }
                double rayX = zeroTiny(baseX + p.rightX * horizontal);
                double rayZ = zeroTiny(baseZ + p.rightZ * horizontal);
                frame.argb[pixel] = trace(scene, pixel, rayX, rayY, rayZ, sky, airPrefix);
            }
        }
    }

    private static boolean crossesEdge(int[] keys, int width, int height, int x, int y, int index) {
        int key = keys[index];
        return x > 0 && keys[index - 1] != key || x + 1 < width && keys[index + 1] != key
            || y > 0 && keys[index - width] != key || y + 1 < height && keys[index + width] != key;
    }

    private static int resolveEdge(Scene scene, Projection p, double horizontal, double vertical, int sky, int center,
                                   double airPrefix) {
        // A rotated sample pattern covers horizontal, vertical and diagonal edges without changing the picker.
        int red = (center >>> 16) & 255, green = (center >>> 8) & 255, blue = center & 255;
        for (int i = 0; i < COVERAGE_X.length; i++) {
            double sampleX = horizontal + COVERAGE_X[i] * p.scale;
            double sampleY = vertical + COVERAGE_Y[i] * p.scale;
            double dx = zeroTiny(p.forwardX + p.upX * sampleY + p.rightX * sampleX);
            double dy = zeroTiny(p.forwardY + p.upY * sampleY);
            double dz = zeroTiny(p.forwardZ + p.upZ * sampleY + p.rightZ * sampleX);
            int color = trace(scene, -1, dx, dy, dz, sky, airPrefix);
            red += (color >>> 16) & 255;
            green += (color >>> 8) & 255;
            blue += color & 255;
        }
        return 0xff000000 | ((red + 2) / 5) << 16 | ((green + 2) / 5) << 8 | (blue + 2) / 5;
    }

    private static int trace(Scene scene, int pixel, double dx, double dy, double dz, int sky, double airPrefix) {
        MapVolume volume = scene.volume;
        Bounds bounds = scene.bounds;
        Frame frame = scene.frame;
        Projection p = frame.projection;
        double ox = p.originX, oy = p.originY, oz = p.originZ;
        if (!scene.hasBounds || outsideParallelAxis(ox, dx, bounds.minX(), bounds.maxX())
            || outsideParallelAxis(oy, dy, bounds.minY(), bounds.maxY())
            || outsideParallelAxis(oz, dz, bounds.minZ(), bounds.maxZ())) {
            return gridBackground(sky, scene.planeY, ox, oy, oz, dx, dy, dz, p.scale);
        }

        double ix = dx == 0 ? Double.POSITIVE_INFINITY : 1 / dx;
        double iy = dy == 0 ? Double.POSITIVE_INFINITY : 1 / dy;
        double iz = dz == 0 ? Double.POSITIVE_INFINITY : 1 / dz;
        double nearX = near(ox, dx, ix, bounds.minX(), bounds.maxX());
        double nearY = near(oy, dy, iy, bounds.minY(), bounds.maxY());
        double nearZ = near(oz, dz, iz, bounds.minZ(), bounds.maxZ());
        double enter = Math.max(0, Math.max(nearX, Math.max(nearY, nearZ)));
        double exit = Math.min(far(ox, dx, ix, bounds.minX(), bounds.maxX()),
            Math.min(far(oy, dy, iy, bounds.minY(), bounds.maxY()), far(oz, dz, iz, bounds.minZ(), bounds.maxZ())));
        if (airPrefix > enter + 8) enter = airPrefix;
        if (!(enter < exit)) return gridBackground(sky, scene.planeY, ox, oy, oz, dx, dy, dz, p.scale);

        int stepX = step(dx), stepY = step(dy), stepZ = step(dz);
        int x = cellAfter(ox, dx, ix, enter), y = cellAfter(oy, dy, iy, enter), z = cellAfter(oz, dz, iz, enter);
        int face = entryFace(ox, oy, oz, ix, iy, iz, stepX, stepY, stepZ, x, y, z, enter);
        double time = enter;
        // The first solid run encloses the eye. Hide that run until a surveyed air gap is reached.
        boolean cutInterior = enter == 0;
        boolean unsurveyedApproach = false;
        int previewColor = 0;
        int previewKey = 0;

        traversal:
        while (time < exit && within(bounds, x, y, z)) {
            int sectionX = x >> 4, sectionY = y >> 4, sectionZ = z >> 4;
            MapVolume.SectionView section = scene.hasSectionViews ? volume.sectionView(sectionX, sectionY, sectionZ) : null;
            int state = scene.hasSectionViews ? (section == null ? -1 : 1) : volume.sectionState(sectionX, sectionY, sectionZ);
            long bricks = section == null ? (state > 0 ? -1L : 0L) : section.occupiedBricks();
            int uniform = cutInterior && section != null ? section.uniformSolidMaterial() : 0;
            MapMaterial uniformTile = uniform > 0 ? volume.material(uniform) : null;
            if (bricks == 0 || uniform > 0 && (uniformTile == null || uniformTile.boxes().isEmpty())) {
                if (bricks == 0) cutInterior = false;
                unsurveyedApproach = state < 0;
                if (pixel >= 0 && unsurveyedApproach) frame.markUnknown(pixel, time);
                double crossX = boundary(ox, ix, stepX, sectionX << 4, 16);
                double crossY = boundary(oy, iy, stepY, sectionY << 4, 16);
                double crossZ = boundary(oz, iz, stepZ, sectionZ << 4, 16);
                double next = Math.min(crossX, Math.min(crossY, crossZ));
                if (!(next < exit) || next < time) break;
                x = cellAfter(ox, dx, ix, next);
                y = cellAfter(oy, dy, iy, next);
                z = cellAfter(oz, dz, iz, next);
                face = entryFace(ox, oy, oz, ix, iy, iz, stepX, stepY, stepZ, x, y, z, next);
                time = next;
                continue;
            }

            double crossX = boundary(ox, ix, stepX, x, 1);
            double crossY = boundary(oy, iy, stepY, y, 1);
            double crossZ = boundary(oz, iz, stepZ, z, 1);
            do {
                int localIndex = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                if (bricks != -1L) {
                    int brick = MapVolume.SectionView.brickIndex(localIndex);
                    if ((bricks & (1L << brick)) == 0) {
                        int brickX = x & ~3, brickY = y & ~3, brickZ = z & ~3;
                        double brickCrossX = boundary(ox, ix, stepX, brickX, 4);
                        double brickCrossY = boundary(oy, iy, stepY, brickY, 4);
                        double brickCrossZ = boundary(oz, iz, stepZ, brickZ, 4);
                        double next = Math.min(brickCrossX, Math.min(brickCrossY, brickCrossZ));
                        if (!(next < exit) || next < time) break traversal;
                        unsurveyedApproach = false;
                        x = cellAfter(ox, dx, ix, next);
                        y = cellAfter(oy, dy, iy, next);
                        z = cellAfter(oz, dz, iz, next);
                        face = entryFace(ox, oy, oz, ix, iy, iz, stepX, stepY, stepZ, x, y, z, next);
                        time = next;
                        cutInterior = false;
                        crossX = boundary(ox, ix, stepX, x, 1);
                        crossY = boundary(oy, iy, stepY, y, 1);
                        crossZ = boundary(oz, iz, stepZ, z, 1);
                        continue;
                    }
                }
                int material = section == null ? volume.blockAt(x, y, z) : section.materialAt(localIndex);
                int preview = material > 0 && volume.previewMaterial(material) ? material : 0;
                if (preview != 0) material = volume.blockBehindPreview(x, y, z);
                double next = Math.min(crossX, Math.min(crossY, crossZ));
                MapMaterial tile = material > 0 ? volume.material(material) : null;
                double surfaceTime = time;
                int surfaceFace = face;
                boolean visible = material > 0 && !cutInterior;
                if (tile != null && !tile.boxes().isEmpty()) {
                    Surface surface = outlineSurface(tile, ox - x, oy - y, oz - z, ix, iy, iz,
                        time, Math.min(next, exit), cutInterior);
                    cutInterior = surface == CONTINUOUS_SOLID;
                    visible = surface != null && !cutInterior;
                    if (visible) {
                        surfaceTime = Math.max(time, surface.near());
                        surfaceFace = surface.face();
                    }
                } else if (material <= 0) {
                    cutInterior = false;
                }
                if (!cutInterior && preview != 0 && time > 0 && previewColor == 0) {
                    MapMaterial previewTile = volume.material(preview);
                    Surface outline = previewTile == null || previewTile.boxes().isEmpty() ? null
                        : outlineSurface(previewTile, ox - x, oy - y, oz - z, ix, iy, iz,
                            time, Math.min(next, exit), false);
                    if (previewTile != null && (previewTile.boxes().isEmpty() || outline != null && outline != CONTINUOUS_SOLID)) {
                        int previewFace = outline == null ? face : outline.face();
                        double previewTime = outline == null ? time : Math.max(time, outline.near());
                        previewColor = drawSurface(scene, previewTile, preview, previewFace, x, y, z, dx, dy, dz,
                            previewTime, true, false);
                        if (pixel >= 0 && scene.surfaceKeys != null) {
                            previewKey = surfaceKey(p, preview, previewFace, dx, dy, dz, previewTime, false);
                        }
                    }
                }
                if (visible) {
                    boolean surveyEdge = surfaceFace != Y_FACE && atCrossing(surfaceTime, time)
                        && (unsurveyedApproach || onSurveyEdge(bounds, x, z, surfaceFace, dx, dz));
                    int color = drawSurface(scene, tile, material, surfaceFace, x, y, z, dx, dy, dz, surfaceTime, false,
                        surveyEdge);
                    if (pixel >= 0) {
                        byte faceCode = faceCode(surfaceFace, dx, dy, dz);
                        frame.hit(pixel, x, y, z, faceCode, surfaceTime);
                        if (scene.surfaceKeys != null) {
                            int surface = surfaceKey(p, material, surfaceFace, dx, dy, dz, surfaceTime, surveyEdge);
                            scene.surfaceKeys[pixel] = (surface ^ Integer.rotateLeft(previewKey, 11)) | 1;
                        }
                    }
                    return previewColor == 0 ? color : blend(color, previewColor, PREVIEW_BLEND);
                }
                unsurveyedApproach = material < 0;
                if (pixel >= 0 && unsurveyedApproach) frame.markUnknown(pixel, time);
                if (!(next < exit)) break traversal;
                boolean crossCellX = atCrossing(crossX, next);
                boolean crossCellY = atCrossing(crossY, next);
                boolean crossCellZ = atCrossing(crossZ, next);
                face = crossCellY ? Y_FACE : crossCellX ? X_FACE : Z_FACE;
                if (crossCellX) { x += stepX; crossX = boundary(ox, ix, stepX, x, 1); }
                if (crossCellY) { y += stepY; crossY = boundary(oy, iy, stepY, y, 1); }
                if (crossCellZ) { z += stepZ; crossZ = boundary(oz, iz, stepZ, z, 1); }
                time = next;
            } while (x >> 4 == sectionX && y >> 4 == sectionY && z >> 4 == sectionZ && within(bounds, x, y, z));
        }
        int background = gridBackground(sky, scene.planeY, ox, oy, oz, dx, dy, dz, p.scale);
        if (pixel >= 0 && scene.surfaceKeys != null) scene.surfaceKeys[pixel] = previewKey;
        return previewColor == 0 ? background : blend(background, previewColor, PREVIEW_BLEND);
    }

    private static boolean onSurveyEdge(Bounds bounds, int x, int z, int face, double dx, double dz) {
        return face == X_FACE ? x == (dx > 0 ? bounds.minX() : bounds.maxX())
            : z == (dz > 0 ? bounds.minZ() : bounds.maxZ());
    }

    private static byte faceCode(int face, double dx, double dy, double dz) {
        return (byte) switch (face) {
            case X_FACE -> dx < 0 ? 1 : 2;
            case Y_FACE -> dy < 0 ? 3 : 4;
            default -> dz < 0 ? 5 : 6;
        };
    }

    private static int surfaceKey(Projection p, int material, int face, double dx, double dy, double dz, double time,
                                  boolean surveyEdge) {
        double coordinate = switch (face) {
            case X_FACE -> Math.fma(dx, time, p.originX);
            case Y_FACE -> Math.fma(dy, time, p.originY);
            default -> Math.fma(dz, time, p.originZ);
        };
        long plane = Math.round(coordinate * 4_096);
        long hash = plane * 0x9e3779b97f4a7c15L ^ (long) material * 0xc2b2ae3d27d4eb4fL;
        int code = faceCode(face, dx, dy, dz) + (surveyEdge ? 8 : 0);
        return ((int) (hash ^ hash >>> 32) ^ code * 0x9e3779b9) | 1;
    }

    private record Surface(double near, double far, int face) { }

    private static Surface outlineSurface(MapMaterial material, double ox, double oy, double oz,
                                          double ix, double iy, double iz, double start, double end, boolean cutInterior) {
        double clearAfter = start;
        if (cutInterior) {
            // Extend through touching shape pieces only while their union encloses the start of the ray.
            double previous;
            do {
                previous = clearAfter;
                for (MapMaterial.Box box : material.boxes()) {
                    Surface surface = intersect(box, ox, oy, oz, ix, iy, iz);
                    if (surface != null && surface.far() > clearAfter
                        && (surface.near() <= clearAfter || atCrossing(surface.near(), clearAfter))) {
                        clearAfter = surface.far();
                        if (clearAfter >= end || atCrossing(clearAfter, end)) return CONTINUOUS_SOLID;
                    }
                }
            } while (clearAfter > previous);
        }
        Surface nearest = null;
        for (MapMaterial.Box box : material.boxes()) {
            Surface surface = intersect(box, ox, oy, oz, ix, iy, iz);
            if (surface == null || surface.near() >= end || surface.far() <= clearAfter
                || cutInterior && surface.near() < clearAfter && !atCrossing(surface.near(), clearAfter)) continue;
            if (nearest == null || surface.near() < nearest.near()) nearest = surface;
        }
        return nearest;
    }

    private static Surface intersect(MapMaterial.Box box, double ox, double oy, double oz, double ix, double iy, double iz) {
        if (outsideParallelBox(ox, ix, box.minX(), box.maxX()) || outsideParallelBox(oy, iy, box.minY(), box.maxY())
            || outsideParallelBox(oz, iz, box.minZ(), box.maxZ())) return null;
        double nearX = boxNear(ox, ix, box.minX(), box.maxX());
        double nearY = boxNear(oy, iy, box.minY(), box.maxY());
        double nearZ = boxNear(oz, iz, box.minZ(), box.maxZ());
        double near = Math.max(nearX, Math.max(nearY, nearZ));
        double far = Math.min(boxFar(ox, ix, box.minX(), box.maxX()),
            Math.min(boxFar(oy, iy, box.minY(), box.maxY()), boxFar(oz, iz, box.minZ(), box.maxZ())));
        if (!(near < far) || atCrossing(near, far)) return null;
        return new Surface(near, far, atCrossing(nearY, near) ? Y_FACE : atCrossing(nearX, near) ? X_FACE : Z_FACE);
    }

    private static boolean outsideParallelBox(double origin, double inverse, double minimum, double maximum) {
        return Double.isInfinite(inverse) && (origin < minimum || origin >= maximum);
    }

    private static double boxNear(double origin, double inverse, double minimum, double maximum) {
        if (Double.isInfinite(inverse)) return Double.NEGATIVE_INFINITY;
        return ((inverse > 0 ? minimum : maximum) - origin) * inverse;
    }

    private static double boxFar(double origin, double inverse, double minimum, double maximum) {
        if (Double.isInfinite(inverse)) return Double.POSITIVE_INFINITY;
        return ((inverse > 0 ? maximum : minimum) - origin) * inverse;
    }

    private static int drawSurface(Scene scene, MapMaterial material, int materialId, int face, int x, int y, int z,
                                   double dx, double dy, double dz, double time, boolean preview, boolean surveyEdge) {
        Projection p = scene.frame.projection;
        double u = face == X_FACE ? Math.fma(dz, time, p.originZ - z) : Math.fma(dx, time, p.originX - x);
        double v = face == Y_FACE ? Math.fma(dz, time, p.originZ - z) : 1 - Math.fma(dy, time, p.originY - y);
        double normal = face == X_FACE ? dx : face == Y_FACE ? dy : dz;
        double shade = switch (face) {
            case X_FACE -> dx > 0 ? .78 : .88;
            case Y_FACE -> dy > 0 ? .58 : 1.0;
            default -> dz > 0 ? .72 : .84;
        };
        double fog = Math.clamp((time - scene.fogStart) / scene.fogWidth, 0, 1) * .46;
        double footprint = time * p.scale / Math.max(.15, Math.abs(normal));
        double edge = Math.min(Math.min(u, 1 - u), Math.min(v, 1 - v));
        int color = surfaceColor(material, u, v, footprint, shade, fog);
        if (preview) {
            color = blend(color, 0xffb998ff, edge < .10 ? .64 : .25);
            return scene.patterns ? pattern(color, MapVolume.OverlayPattern.DOTS, u, v) : color;
        }
        if (surveyEdge) color = blend(color, FOG_COLOR, SURVEY_EDGE_BLEND);

        Hit selected = scene.selected;
        if (selected != null && selected.x() == x && selected.y() == y && selected.z() == z) {
            color = blend(color, 0xffffce72, edge < .11 ? .78 : .48);
            if (scene.patterns && (edge < .1 || Math.abs(u - .5) < .08 || Math.abs(v - .5) < .08))
                color = contrastMark(color, edge < .045);
        } else if (scene.routeBlocks.length != 0 && Arrays.binarySearch(scene.routeBlocks, blockKey(x, y, z)) >= 0) {
            color = blend(color, 0xff79aff9, .60);
            if (scene.patterns) color = pattern(color, MapVolume.OverlayPattern.DOTS, u, v);
        } else if (scene.region != null && scene.region.contains(x, y, z)) {
            color = blend(color, 0xff62ddcb, .37);
            if (scene.patterns) color = pattern(color, MapVolume.OverlayPattern.DIAGONAL, u, v);
        }
        if (edge < .075 && scene.volume.plannedMaterial(materialId)) color = blend(color, 0xffd8c2ff, .5);
        return scene.patterns ? pattern(color, scene.volume.overlayPattern(materialId), u, v) : color;
    }

    private static int pattern(int color, MapVolume.OverlayPattern pattern, double u, double v) {
        int x = Math.clamp((int) (u * 12), 0, 11), y = Math.clamp((int) (v * 12), 0, 11);
        int band = switch (pattern) {
            case NONE -> -1;
            case HORIZONTAL -> y % 4;
            case DIAGONAL -> (x + y) % 5;
            case GRID -> Math.min(x % 6, y % 6);
            case DOTS -> Math.max(x % 6, y % 6);
        };
        return band < 0 || band > 1 ? color : contrastMark(color, band == 0);
    }

    private static int contrastMark(int color, boolean light) {
        return blend(color, light ? 0xfff8f9fc : 0xff101216, .78);
    }

    /** Packs signed 26-bit horizontal and signed 12-bit vertical block coordinates. */
    public static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL);
    }

    private static int gridBackground(int sky, double planeY, double ox, double oy, double oz,
                                      double dx, double dy, double dz, double scale) {
        if (dy >= -.02) return sky;
        double time = (planeY - oy) / dy;
        if (!(time > 0) || time > 3_000) return sky;
        double worldX = ox + dx * time, worldZ = oz + dz * time;
        double edgeX = Math.abs(worldX - Math.rint(worldX / 16) * 16);
        double edgeZ = Math.abs(worldZ - Math.rint(worldZ / 16) * 16);
        double footprint = time * scale / Math.max(.08, -dy);
        double width = Math.clamp(footprint * .52, .025, 1.5);
        double fade = Math.clamp((4 - footprint) / 3, 0, 1);
        double strength = (1 - Math.clamp(Math.min(edgeX, edgeZ) / width, 0, 1)) * .26 * fade;
        return blend(sky, 0xff4b6278, strength);
    }

    private static boolean within(Bounds bounds, int x, int y, int z) {
        return x >= bounds.minX() && x <= bounds.maxX() && y >= bounds.minY() && y <= bounds.maxY()
            && z >= bounds.minZ() && z <= bounds.maxZ();
    }

    private static boolean outsideParallelAxis(double origin, double direction, int minimum, int maximum) {
        return direction == 0 && (origin < minimum || origin >= maximum + 1.0);
    }

    private static double near(double origin, double direction, double inverse, int minimum, int maximum) {
        if (direction == 0) return Double.NEGATIVE_INFINITY;
        return ((direction > 0 ? minimum : maximum + 1.0) - origin) * inverse;
    }

    private static double far(double origin, double direction, double inverse, int minimum, int maximum) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        return ((direction > 0 ? maximum + 1.0 : minimum) - origin) * inverse;
    }

    private static double boundary(double origin, double inverse, int step, int cell, int size) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return ((step > 0 ? cell + (double) size : cell) - origin) * inverse;
    }

    private static int cellAfter(double origin, double direction, double inverse, double time) {
        double position = Math.fma(direction, time, origin);
        double nearest = Math.rint(position);
        if (direction != 0 && Math.abs(position - nearest) <= CELL_ROUNDING_WINDOW) {
            // Compare crossing times so a narrowly missed edge is not rounded into the neighboring voxel.
            double edgeTime = (nearest - origin) * inverse;
            boolean crossed = edgeTime <= time || atCrossing(edgeTime, time);
            return (int) nearest - ((direction < 0) == crossed ? 1 : 0);
        }
        return (int) Math.floor(position);
    }

    private static int entryFace(double ox, double oy, double oz, double ix, double iy, double iz,
                                 int sx, int sy, int sz, int x, int y, int z, double time) {
        // Include voxel boundaries that coincide with a brick or section crossing.
        if (atCrossing(boundary(oy, iy, -sy, y, 1), time)) return Y_FACE;
        if (atCrossing(boundary(ox, ix, -sx, x, 1), time)) return X_FACE;
        if (atCrossing(boundary(oz, iz, -sz, z, 1), time)) return Z_FACE;
        return Y_FACE;
    }

    private static boolean atCrossing(double crossing, double time) {
        return Math.abs(crossing - time) <= DIRECTION_EPSILON + Math.abs(time) * 1.8e-15;
    }

    private static int step(double direction) { return direction > 0 ? 1 : direction < 0 ? -1 : 0; }
    private static double zeroTiny(double direction) { return Math.abs(direction) < DIRECTION_EPSILON ? 0 : direction; }
    private static int surfaceColor(MapMaterial material, double u, double v, double footprint, double light, double fog) {
        int color = material == null ? 0xff78818b : material.sample(u, v, footprint);
        int shaded = light == 1 ? color : shade(color, light);
        return fog == 0 ? shaded : blend(shaded, FOG_COLOR, fog);
    }

    private static int shade(int color, double light) {
        int red = (int) (((color >>> 16) & 255) * light);
        int green = (int) (((color >>> 8) & 255) * light);
        int blue = (int) ((color & 255) * light);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int blend(int first, int second, double amount) {
        int red = mix((first >>> 16) & 255, (second >>> 16) & 255, amount);
        int green = mix((first >>> 8) & 255, (second >>> 8) & 255, amount);
        int blue = mix(first & 255, second & 255, amount);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int mix(int first, int second, double amount) {
        return first + (int) Math.round((second - first) * amount);
    }
}
