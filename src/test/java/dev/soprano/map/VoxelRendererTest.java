package dev.soprano.map;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class VoxelRendererTest {
    private static final VoxelRenderer RENDERER = new VoxelRenderer();
    private static int checks;

    public static void main(String[] args) {
        cutsAndDepth();
        sparseNegativeSections();
        diagonalCrossing();
        sectionAndBrickCorners();
        embeddedCamera();
        outlineGeometry();
        geometricCoverage();
        surveyCuts();
        tileCoordinates();
        previewKeepsPicking();
        overlayKeepsPicking();
        skyAndImageBounds();
        projectionAndOcclusion();
        buildPlacement();
        fastSectionParity();
        System.out.println("Soprano voxel renderer passed " + checks + " checks.");
    }

    private static void cutsAndDepth() {
        Grid grid = new Grid(-4, 0, -4, 4, 6, 4).put(1, 0, 1).put(1, 3, 1);
        var camera = new VoxelRenderer.Camera(1.4, 0, 1.7, 0, 90, 24);
        var full = RENDERER.render(grid, camera, Integer.MAX_VALUE, 1, 1);
        equal(full.hitAt(0, 0), new VoxelRenderer.Hit(1, 3, 1), "nearest surface wins");
        var clipped = RENDERER.render(grid, camera, 2, 1, 1);
        equal(clipped.hitAt(0, 0), new VoxelRenderer.Hit(1, 0, 1), "cut excludes upper blocks");
        equal(RENDERER.render(grid, camera, 0, 1, 1).hitAt(0, 0),
            new VoxelRenderer.Hit(1, 0, 1), "cut includes blocks at its height");
        equal(RENDERER.render(grid, camera, -1, 1, 1).hitAt(0, 0), null, "cut below terrain misses");
    }

    private static void sparseNegativeSections() {
        Grid grid = new Grid(-176, -16, -16, 176, 16, 16).put(-129, -7, -3);
        grid.emptySection(2, -1, -1);
        var camera = new VoxelRenderer.Camera(-130.25, -6.5, -2.5, 90, 0, 320);
        var frame = RENDERER.render(grid, camera, 16, 1, 1);
        equal(frame.hitAt(0, 0), new VoxelRenderer.Hit(-129, -7, -3), "picking crosses negative section boundaries");
        check(grid.blockQueries <= 16, "whole missing sections skip per-block reads");
        check(grid.sectionQueries > 12, "long ray crosses multiple sections");

        Grid edge = new Grid(-29_999_984, -80, 29_999_968, -29_999_968, 300, 29_999_983)
            .put(-29_999_976, -65, 29_999_972);
        var edgeView = new VoxelRenderer.Camera(-29_999_975.5, -65, 29_999_972.5, 0, 90, 25);
        equal(RENDERER.render(edge, edgeView, 300, 1, 1).hitAt(0, 0),
            new VoxelRenderer.Hit(-29_999_976, -65, 29_999_972), "world-scale coordinates survive picking");
    }

    private static void diagonalCrossing() {
        Grid grid = new Grid(-4, 0, -4, 12, 2, 12).put(1, 0, 0).put(0, 0, 0);
        var diagonal = new VoxelRenderer.Camera(.5, .5, .5, 45, 0, 16);
        equal(RENDERER.render(grid, diagonal, 2, 1, 1).hitAt(0, 0),
            new VoxelRenderer.Hit(0, 0, 0), "corner-only contact does not hide the block behind it");
    }

    private static void sectionAndBrickCorners() {
        Grid grid = new Grid(-16, -16, -16, 48, 16, 48).put(16, 0, 15).put(15, 0, 15);
        var narrow = new VoxelRenderer.Camera(.50000002, .5, .5, 45, 0, 44);
        var diagonal = new VoxelRenderer.Camera(.5, .5, .5, 45, 0, 44);
        for (MapVolume volume : new MapVolume[] { grid, grid.sectionBacked() }) {
            var narrowFrame = RENDERER.render(volume, narrow, 16, 1, 1);
            equal(narrowFrame.hitAt(0, 0), new VoxelRenderer.Hit(16, 0, 15), "retain a narrow nonzero crossing");
            equal(narrowFrame.faceAt(0, 0), new VoxelRenderer.Face(0, 0, 1), "enter the correct section face");
            var diagonalFrame = RENDERER.render(volume, diagonal, 16, 1, 1);
            equal(diagonalFrame.hitAt(0, 0), new VoxelRenderer.Hit(15, 0, 15), "discard a zero-area corner crossing");
            equal(diagonalFrame.faceAt(0, 0), new VoxelRenderer.Face(1, 0, 0), "section-corner face is stable");
        }

        Grid brick = new Grid(-16, -16, -16, 16, 16, 16).put(0, 1, 3);
        var camera = new VoxelRenderer.Camera(.5, -1.5, .5, 0, 45, 16);
        for (MapVolume volume : new MapVolume[] { brick, brick.sectionBacked() }) {
            var frame = RENDERER.render(volume, camera, 16, 1, 1);
            equal(frame.hitAt(0, 0), new VoxelRenderer.Hit(0, 1, 3), "enter the block past the brick corner");
            equal(frame.faceAt(0, 0), new VoxelRenderer.Face(0, 1, 0), "same face with or without empty-brick skipping");
        }
    }

    private static void embeddedCamera() {
        Grid grid = new Grid(-4, 0, -4, 4, 4, 8).put(0, 0, 4).put(0, 0, 3).put(0, 0, 2).put(0, 0, 0);
        var inside = new VoxelRenderer.Camera(.5, .5, .5, 0, 0, 4);
        var outside = new VoxelRenderer.Camera(.5, .5, .5, 0, 0, 6);
        for (MapVolume volume : new MapVolume[] { grid, grid.sectionBacked() }) {
            var cutaway = RENDERER.render(volume, inside, 4, 1, 1);
            equal(cutaway.hitAt(0, 0), new VoxelRenderer.Hit(0, 0, 0), "camera peers past the connected wall enclosing it");
            equal(cutaway.faceAt(0, 0), new VoxelRenderer.Face(0, 0, 1), "cutaway retains the visible hit's entry face");
            check(Math.abs(cutaway.depthAt(0, 0) - 3.5) < 1e-6, "cutaway depth ignores the enclosing wall");
            equal(RENDERER.render(volume, outside, 4, 1, 1).hitAt(0, 0),
                new VoxelRenderer.Hit(0, 0, 4), "the same wall remains visible from outside");
        }
    }

    private static void outlineGeometry() {
        MapMaterial.Box slab = new MapMaterial.Box(0, 0, 0, 1, .5, 1);
        MapMaterial.Box riser = new MapMaterial.Box(0, .5, .5, 1, 1, 1);
        Grid stair = new Grid(-4, -4, -4, 4, 4, 4).shape(0, 0, 0, List.of(slab, riser));
        var aboveTread = new VoxelRenderer.Camera(.5, 0, .25, 0, 90, 4);
        var aboveRiser = new VoxelRenderer.Camera(.5, 0, .75, 0, 90, 4);
        var front = new VoxelRenderer.Camera(.5, .75, .5, 180, 0, 4);
        for (MapVolume volume : new MapVolume[] { stair, stair.sectionBacked() }) {
            var lower = RENDERER.render(volume, aboveTread, 4, 1, 1);
            var higher = RENDERER.render(volume, aboveRiser, 4, 1, 1);
            var side = RENDERER.render(volume, front, 4, 1, 1);
            equal(lower.hitAt(0, 0), new VoxelRenderer.Hit(0, 0, 0), "the lower tread remains selectable");
            equal(lower.faceAt(0, 0), new VoxelRenderer.Face(0, 1, 0), "lower tread uses its top normal");
            check(lower.depthAt(0, 0) == 3.5f && higher.depthAt(0, 0) == 3f, "step treads have their correct depths");
            equal(side.faceAt(0, 0), new VoxelRenderer.Face(0, 0, -1), "hit the internal stair riser from the front");
            check(side.depthAt(0, 0) == 4f, "stair riser clips at its real depth");
        }

        Grid gap = new Grid(-4, -4, -4, 4, 4, 4).shape(0, 0, 0, List.of(slab)).put(0, 0, 1);
        var seesThrough = RENDERER.render(gap.sectionBacked(), front, 4, 1, 1);
        equal(seesThrough.hitAt(0, 0), new VoxelRenderer.Hit(0, 0, 1), "the empty half of a slab does not occlude another block");
        var startsInAir = new VoxelRenderer.Camera(.5, .75, .5, 180, 0, .25);
        var startsInSlab = new VoxelRenderer.Camera(.5, .25, .5, 180, 0, .25);
        Grid interior = new Grid(-4, -4, -4, 4, 4, 4).shape(0, 0, 0, List.of(slab)).put(0, 0, 2);
        for (var camera : new VoxelRenderer.Camera[] {startsInAir, startsInSlab}) {
            equal(RENDERER.render(interior.sectionBacked(), camera, 4, 1, 1).hitAt(0, 0),
                new VoxelRenderer.Hit(0, 0, 2), "cutaway honors the real occupied part of the camera voxel");
        }
    }

    private static void geometricCoverage() {
        var cube = new MapMaterial.Box(0, 0, 0, 1, 1, 1);
        var thin = new MapMaterial.Box(.1, 0, 0, .3, .8, 1);
        Grid fixture = new Grid(-4, -4, -4, 8, 8, 8);
        for (int x = 0; x < 4; x++) {
            fixture.shape(x, 0, x, List.of(cube));
            fixture.shape(x, 1, x, List.of(thin));
        }
        MapVolume volume = fixture.sectionBacked();
        var camera = new VoxelRenderer.Camera(1.86, 0, 2.07, 32, 57, 14);
        int size = 39, samples = 7;
        var center = RENDERER.render(volume, camera, 8, size, size);
        var refined = RENDERER.render(volume, camera, 8, size, size, null, null, new long[0], true);
        var reference = RENDERER.render(volume, camera, 8, size * samples, size * samples);
        int changed = 0, coveredMisses = 0, centerError = 0, refinedError = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!Objects.equals(center.hitAt(x, y), refined.hitAt(x, y))
                    || !Objects.equals(center.faceAt(x, y), refined.faceAt(x, y))
                    || center.depthAt(x, y) != refined.depthAt(x, y)
                    || center.entityOcclusionAt(x, y) != refined.entityOcclusionAt(x, y)) {
                    throw new AssertionError("Geometry coverage changed the center-ray results at " + x + "," + y);
                }
                int index = y * size + x;
                int color = center.argb()[index], resolved = refined.argb()[index];
                if (color == resolved) continue;
                changed++;
                if (center.hitAt(x, y) == null) coveredMisses++;
                for (int shift = 0; shift <= 16; shift += 8) {
                    int total = 0;
                    for (int sy = 0; sy < samples; sy++) {
                        for (int sx = 0; sx < samples; sx++) {
                            int sample = reference.argb()[(y * samples + sy) * reference.width() + x * samples + sx];
                            total += (sample >>> shift) & 255;
                        }
                    }
                    int ideal = total / (samples * samples);
                    centerError += Math.abs(((color >>> shift) & 255) - ideal);
                    refinedError += Math.abs(((resolved >>> shift) & 255) - ideal);
                }
            }
        }
        check(changed > 25, "projected thin pieces and silhouettes gain subpixel coverage");
        check(coveredMisses > 0, "edge color may cover part of a pixel even when its center is sky");
        check(refinedError * 2 < centerError, "coverage is closer to a high-resolution reference");
        check(center.argb()[0] == refined.argb()[0], "the pass leaves the unaffected background crisp");
    }

    private static void surveyCuts() {
        var camera = new VoxelRenderer.Camera(.5, .5, .5, -90, 0, 6);
        Grid clipped = new Grid(0, -4, -4, 4, 4, 4).put(0, 0, 0);
        Grid known = new Grid(-16, -4, -4, 4, 4, 4).put(0, 0, 0);
        Grid unknown = new Grid(-16, -4, -4, 4, 4, 4).put(0, 0, 0);
        known.emptySection(-1, 0, 0);
        var cut = RENDERER.render(clipped.sectionBacked(), camera, 4, 1, 1);
        var interior = RENDERER.render(known.sectionBacked(), camera, 4, 1, 1);
        var unsurveyed = RENDERER.render(unknown.sectionBacked(), camera, 4, 1, 1);
        equal(cut.hitAt(0, 0), interior.hitAt(0, 0), "survey edge shading does not hide terrain");
        check(cut.depthAt(0, 0) == interior.depthAt(0, 0), "survey shading preserves the solid surface depth");
        check(cut.argb()[0] != interior.argb()[0], "the map edge is visually distinct from a real wall");
        check(cut.argb()[0] == unsurveyed.argb()[0], "missing chunks also create a visible survey cut");
        check(unsurveyed.entityOcclusionAt(0, 0) < cut.entityOcclusionAt(0, 0), "unsurveyed space still occludes entities");
    }

    private static void tileCoordinates() {
        Grid grid = new Grid(-4, 0, -4, 4, 3, 4).put(0, 0, 0);
        int size = MapMaterial.TILE_SIZE;
        for (int v = 0; v < size; v++) {
            for (int u = 0; u < size; u++) {
                var camera = new VoxelRenderer.Camera((u + .5) / size, 0, (v + .5) / size, 0, 90, 5);
                var frame = RENDERER.render(grid, camera, 3, 41, 41);
                check(frame.argb()[20 * 41 + 20] == grid.material(1).pixel(u, v), "top tile samples all texels at matching UVs");
            }
        }
        var side = new VoxelRenderer.Camera(.5, .5, .5, 0, 0, 5);
        var frame = RENDERER.render(grid, side, 3, 1, 1);
        int unshaded = grid.material(1).pixel(size / 2, size / 2);
        check((frame.argb()[0] & 255) < (unshaded & 255), "vertical faces use directional shading");
        check(grid.material(1).sample(.05, .05, 2) == grid.material(1).sample(.95, .95, 2),
            "subpixel tiles converge to one stable color");
    }

    private static void previewKeepsPicking() {
        Grid ground = new Grid(-4, 0, -4, 4, 8, 4).put(0, 0, 0);
        Grid floating = new Grid(-4, 0, -4, 4, 8, 4).put(0, 0, 0).preview(0, 2, 0, 0);
        Grid overlapping = new Grid(-4, 0, -4, 4, 8, 4).preview(0, 0, 0, 1);
        Grid sky = new Grid(-4, 0, -4, 4, 8, 4).preview(0, 2, 0, 0);
        var camera = new VoxelRenderer.Camera(.5, 0, .5, 0, 90, 8);
        var terrain = RENDERER.render(ground, camera, 8, 5, 5);
        var terrainHit = new VoxelRenderer.Hit(0, 0, 0);
        for (Grid grid : new Grid[] { floating, overlapping }) {
            var ghost = RENDERER.render(grid, camera, 8, 5, 5);
            equal(ghost.hitAt(2, 2), terrainHit, "a transient ghost keeps the committed block selectable");
            equal(ghost.faceAt(2, 2), terrain.faceAt(2, 2), "ghosts preserve the committed placement face");
            check(ghost.depthAt(2, 2) == terrain.depthAt(2, 2), "ghosts preserve terrain depth");
            check(ghost.argb()[12] != terrain.argb()[12], "ghosts change the displayed color");
        }
        var ghostOnly = RENDERER.render(sky, camera, 8, 5, 5);
        equal(ghostOnly.hitAt(2, 2), null, "standalone ghosts leave the work plane available");
        check(Float.isInfinite(ghostOnly.entityOcclusionAt(2, 2)), "standalone ghosts do not hide entities");
    }

    private static void overlayKeepsPicking() {
        Grid grid = new Grid(-4, 0, -4, 4, 3, 4).put(0, 0, 0);
        var camera = new VoxelRenderer.Camera(.5, 0, .5, 0, 90, 5);
        var block = new VoxelRenderer.Hit(0, 0, 0);
        var box = new MapVolume.Box(-1, 0, -1, 1, 0, 1);
        var normal = RENDERER.render(grid, camera, 3, 1, 1);
        var selected = RENDERER.render(grid, camera, 3, 1, 1, block, box);
        var area = RENDERER.render(grid, camera, 3, 1, 1, null, box);
        long[] route = {VoxelRenderer.blockKey(0, 0, 0)};
        var routeFrame = RENDERER.render(grid, camera, 3, 1, 1, null, box, route);
        var selectedRoute = RENDERER.render(grid, camera, 3, 1, 1, block, box, route);
        equal(selected.hitAt(0, 0), block, "selection retains the same front block");
        equal(area.hitAt(0, 0), block, "area tint retains the same front block");
        check(selected.argb()[0] != normal.argb()[0], "clicked block receives a tint");
        check(area.argb()[0] != normal.argb()[0] && area.argb()[0] != selected.argb()[0],
            "area and selected block have distinct tints");
        check(routeFrame.argb()[0] != area.argb()[0] && routeFrame.argb()[0] != selected.argb()[0],
            "the route tint appears over the area tint");
        check(selectedRoute.argb()[0] == selected.argb()[0], "selection takes priority over the route tint");
        check(VoxelRenderer.blockKey(-29_999_999, -2_032, 29_999_999)
                != VoxelRenderer.blockKey(-29_999_999, 2_031, 29_999_999),
            "signed vertical positions remain distinct near the world border");
    }

    private static void skyAndImageBounds() {
        Grid grid = new Grid(-32, 0, -32, 32, 8, 32);
        var camera = new VoxelRenderer.Camera(.5, 0, .5, 22, 50, 12);
        var frame = RENDERER.render(grid, camera, 8, 11, 9);
        check(frame.argb().length == 99 && frame.width() == 11 && frame.height() == 9, "image reports its dimensions");
        equal(frame.hitAt(5, 4), null, "unknown sky has no selectable block");
        equal(frame.hitAt(-1, 0), null, "out-of-bounds picks are ignored");
        check((frame.argb()[0] >>> 24) == 255, "map background is opaque");
    }

    private static void projectionAndOcclusion() {
        Grid grid = new Grid(-32, -16, -32, 32, 32, 32).put(0, 0, 0);
        var top = new VoxelRenderer.Camera(.5, 0, .5, 0, 90, 32);
        var frame = RENDERER.render(grid, top, 32, 9, 9);
        equal(frame.hitAt(4, 4), new VoxelRenderer.Hit(0, 0, 0), "frame depth refers to the picked voxel");
        check(Math.abs(frame.depthAt(4, 4) - 31) < 1e-6, "depth measures distance along the forward axis");
        var surface = frame.project(.5, 1, .5);
        check(Math.abs(surface.x() - 4.5) < 1e-6 && Math.abs(surface.y() - 4.5) < 1e-6
            && Math.abs(surface.depth() - frame.depthAt(4, 4)) < 1e-6, "projection and rendered depth agree");
        check(frame.project(.5, 33, .5) == null, "points behind the camera are excluded");
        check(frame.project(100, 1, .5) == null, "points outside the image are excluded");
        check(frame.entityOcclusionAt(4, 4) < 17, "unreceived sections stop entity visibility before terrain");
        check(Float.isInfinite(frame.depthAt(-1, 4)) && Float.isInfinite(frame.entityOcclusionAt(9, 4)),
            "depth queries ignore coordinates outside the image");
        grid.emptySection(0, 2, 0);
        grid.emptySection(0, 1, 0);
        var surveyed = RENDERER.render(grid, top, 32, 9, 9);
        check(surveyed.entityOcclusionAt(4, 4) == surveyed.depthAt(4, 4), "known air leaves terrain as the marker depth");
        var side = new VoxelRenderer.Camera(0, 5, 0, 38, 29, 24);
        var sideFrame = RENDERER.render(grid, side, 32, 15, 11);
        var target = sideFrame.project(0, 5, 0);
        check(Math.abs(target.x() - 7.5) < 1e-6 && Math.abs(target.y() - 5.5) < 1e-6
            && Math.abs(target.depth() - 24) < 1e-6, "tilted camera projects its orbit target to the center");
    }

    private static void buildPlacement() {
        Grid grid = new Grid(-8, -8, -8, 8, 8, 8).put(-2, 0, -3);
        var top = new VoxelRenderer.Camera(-1.5, 0, -2.5, 0, 90, 12);
        var topFrame = RENDERER.render(grid, top, 8, 7, 7);
        equal(topFrame.faceAt(3, 3), new VoxelRenderer.Face(0, 1, 0), "place above the top face");
        equal(topFrame.cellAtPlane(3, 3, 3), new VoxelRenderer.Hit(-2, 3, -3), "place at work height above visible terrain");
        equal(topFrame.cellAtPlane(3, 3, 13), null, "the work plane behind the camera is excluded");

        var side = new VoxelRenderer.Camera(-1.5, .5, -2.5, 0, 0, 12);
        var sideFrame = RENDERER.render(grid.sectionBacked(), side, 8, 7, 7);
        equal(sideFrame.faceAt(3, 3), new VoxelRenderer.Face(0, 0, 1), "place beside a section-backed block");
        equal(sideFrame.cellAtPlane(3, 3, 0), null, "parallel rays miss the work plane");
        equal(sideFrame.faceAt(-1, 3), null, "out-of-bounds picks have no placement face");
    }

    private static void fastSectionParity() {
        Grid grid = new Grid(-20, -16, -20, 20, 20, 20);
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                grid.put(x, -7 + Math.floorMod(x * 7 + z * 11, 5), z);
                if (Math.floorMod(x * x + z, 23) == 0) grid.put(x, 7, z);
            }
        }
        var camera = new VoxelRenderer.Camera(.25, 1, -.5, 37, 41, 49);
        var generic = RENDERER.render(grid, camera, 20, 73, 49);
        int genericQueries = grid.blockQueries;
        var fast = RENDERER.render(grid.sectionBacked(), camera, 20, 73, 49);
        check(grid.blockQueries == genericQueries, "section-backed rendering avoids voxel lookups through the map");
        for (int y = 0; y < generic.height(); y++) {
            for (int x = 0; x < generic.width(); x++) {
                if (!Objects.equals(generic.hitAt(x, y), fast.hitAt(x, y))
                    || !Objects.equals(generic.faceAt(x, y), fast.faceAt(x, y))
                    || Math.abs(generic.depthAt(x, y) - fast.depthAt(x, y)) > 1e-4) {
                    throw new AssertionError("air-brick skip changed picking or depth at " + x + "," + y);
                }
            }
        }
        check(true, "air-brick skipping keeps every pick and depth in a sloped negative-coordinate scene");
    }

    private static void equal(Object actual, Object expected, String description) {
        check(Objects.equals(actual, expected), description + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
    }

    private record Cell(int x, int y, int z) { }

    private static final class Grid implements MapVolume {
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final Map<Cell, Integer> blocks = new HashMap<>();
        private final Set<Cell> populated = new HashSet<>();
        private final Set<Cell> empty = new HashSet<>();
        private final Map<Cell, Integer> previewBackground = new HashMap<>();
        private final Map<Integer, MapMaterial> outlines = new HashMap<>();
        private final MapMaterial material;
        private int sectionQueries;
        private int blockQueries;

        Grid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            int size = MapMaterial.TILE_SIZE;
            int[] tile = new int[size * size];
            for (int v = 0; v < size; v++) {
                for (int u = 0; u < size; u++) tile[v * size + u] = 0xff003060 | (u * 25 << 16) | (v * 3);
            }
            material = new MapMaterial("Test surface", tile);
        }

        Grid put(int x, int y, int z) {
            blocks.put(new Cell(x, y, z), 1);
            populated.add(new Cell(x >> 4, y >> 4, z >> 4));
            return this;
        }

        Grid shape(int x, int y, int z, List<MapMaterial.Box> boxes) {
            put(x, y, z);
            int id = outlines.size() + 3;
            int[] tile = new int[MapMaterial.TILE_SIZE * MapMaterial.TILE_SIZE];
            java.util.Arrays.fill(tile, 0xff468abb);
            outlines.put(id, new MapMaterial("Shaped surface", tile, boxes));
            blocks.put(new Cell(x, y, z), id);
            return this;
        }

        Grid preview(int x, int y, int z, int background) {
            put(x, y, z);
            Cell cell = new Cell(x, y, z);
            blocks.put(cell, 2);
            previewBackground.put(cell, background);
            return this;
        }

        @Override public boolean previewMaterial(int id) { return id == 2; }
        @Override public int blockBehindPreview(int x, int y, int z) {
            return previewBackground.getOrDefault(new Cell(x, y, z), 0);
        }

        void emptySection(int x, int y, int z) { empty.add(new Cell(x, y, z)); }

        MapVolume sectionBacked() {
            Map<Cell, TestSection> sections = new HashMap<>();
            for (Cell cell : empty) sections.put(cell, new TestSection());
            blocks.forEach((cell, material) -> {
                Cell key = new Cell(cell.x() >> 4, cell.y() >> 4, cell.z() >> 4);
                TestSection section = sections.computeIfAbsent(key, ignored -> new TestSection());
                int x = cell.x() & 15, y = cell.y() & 15, z = cell.z() & 15;
                section.blocks[y * 256 + z * 16 + x] = material;
                section.bricks |= 1L << ((y >> 2) * 16 + (z >> 2) * 4 + (x >> 2));
            });
            return new MapVolume() {
                @Override public int minX() { return Grid.this.minX(); }
                @Override public int minY() { return Grid.this.minY(); }
                @Override public int minZ() { return Grid.this.minZ(); }
                @Override public int maxX() { return Grid.this.maxX(); }
                @Override public int maxY() { return Grid.this.maxY(); }
                @Override public int maxZ() { return Grid.this.maxZ(); }
                @Override public int blockAt(int x, int y, int z) { return Grid.this.blockAt(x, y, z); }
                @Override public int sectionState(int x, int y, int z) { return Grid.this.sectionState(x, y, z); }
                @Override public MapMaterial material(int id) { return Grid.this.material(id); }
                @Override public long revision() { return Grid.this.revision(); }
                @Override public boolean hasSectionViews() { return true; }
                @Override public SectionView sectionView(int x, int y, int z) { return sections.get(new Cell(x, y, z)); }
            };
        }

        private static final class TestSection implements MapVolume.SectionView {
            final int[] blocks = new int[4096];
            long bricks;
            @Override public int materialAt(int index) { return blocks[index]; }
            @Override public long occupiedBricks() { return bricks; }
        }

        @Override public int minX() { return minX; }
        @Override public int minY() { return minY; }
        @Override public int minZ() { return minZ; }
        @Override public int maxX() { return maxX; }
        @Override public int maxY() { return maxY; }
        @Override public int maxZ() { return maxZ; }
        @Override public int blockAt(int x, int y, int z) {
            blockQueries++;
            return blocks.getOrDefault(new Cell(x, y, z), 0);
        }
        @Override public int sectionState(int x, int y, int z) {
            sectionQueries++;
            Cell section = new Cell(x, y, z);
            return populated.contains(section) ? 1 : empty.contains(section) ? 0 : -1;
        }
        @Override public MapMaterial material(int id) { return outlines.getOrDefault(id, material); }
        @Override public long revision() { return blocks.size(); }
    }
}
