package dev.soprano.build;

import dev.soprano.map.MapMaterial;
import dev.soprano.map.MapVolume;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Draws a read-only blueprint snapshot over the captured world. */
public final class BuildVolume implements MapVolume {
    private static final int SECTION_SIZE = 16 * 16 * 16;
    private static final int DENSE_SECTION_THRESHOLD = 256;
    private static final int PREVIEW_MATERIAL_OFFSET = 1 << 28;
    private static final int COMPARISON_MATERIAL_BASE = 1 << 27;
    private static final MapMaterial[] COMPARISON_MATERIALS = Arrays.stream(BlueprintActions.Difference.values())
        .map(difference -> new MapMaterial(difference.label(), BuildPalette.previewTile(difference.color(), difference.ordinal())))
        .toArray(MapMaterial[]::new);

    private record Placement(int index, int material) { }
    private enum Mode { BLUEPRINT, PREVIEW, COMPARISON }

    private final MapVolume terrain;
    private final BuildPalette palette;
    private final List<MapMaterial> stateMaterials;
    private final Map<BuildPlan.Cell, String> blocks;
    private final Long2ObjectOpenHashMap<OverlaySection> sections;
    private final long planRevision;
    private final Mode mode;

    public BuildVolume(MapVolume terrain, Map<BuildPlan.Cell, String> snapshot, BuildPalette palette, long planRevision) {
        this(terrain, snapshot, palette, planRevision, Mode.BLUEPRINT);
    }

    public static BuildVolume preview(MapVolume terrain, Map<BuildPlan.Cell, String> geometry, BuildPalette palette) {
        return new BuildVolume(terrain, geometry, palette, 0, Mode.PREVIEW);
    }

    public static BuildVolume comparison(MapVolume terrain, BlueprintActions.Comparison comparison) {
        return new BuildVolume(terrain, comparison);
    }

    private BuildVolume(MapVolume terrain, Map<BuildPlan.Cell, String> snapshot, BuildPalette palette,
                        long planRevision, Mode mode) {
        this.terrain = Objects.requireNonNull(terrain);
        this.palette = Objects.requireNonNull(palette);
        this.planRevision = planRevision;
        this.mode = mode;
        blocks = mode == Mode.PREVIEW ? Map.of() : Map.copyOf(snapshot);
        List<MapMaterial> stateTiles = new ArrayList<>();
        Map<String, Integer> stateIds = new HashMap<>();
        sections = group(mode == Mode.PREVIEW ? snapshot : blocks, state -> stateIds.computeIfAbsent(state, serialized -> {
            MapMaterial tile = palette.stateMaterial(serialized);
            int id = palette.materialId(serialized);
            if (tile != null) {
                id = palette.firstStateMaterialId() + stateTiles.size();
                stateTiles.add(tile);
            }
            return mode == Mode.PREVIEW ? id + PREVIEW_MATERIAL_OFFSET : id;
        }));
        stateMaterials = List.copyOf(stateTiles);
    }

    private BuildVolume(MapVolume terrain, BlueprintActions.Comparison comparison) {
        this.terrain = Objects.requireNonNull(terrain);
        mode = Mode.COMPARISON;
        palette = null;
        planRevision = 0;
        blocks = Map.of();
        stateMaterials = List.of();
        sections = group(comparison.differences(), difference -> COMPARISON_MATERIAL_BASE + difference.ordinal());
    }

    private <T> Long2ObjectOpenHashMap<OverlaySection> group(Map<BuildPlan.Cell, T> cells, ToIntFunction<T> materialId) {
        Long2ObjectOpenHashMap<List<Placement>> grouped = new Long2ObjectOpenHashMap<>();
        for (var entry : cells.entrySet()) {
            BuildPlan.Cell cell = entry.getKey();
            int x = cell.x(), y = cell.y(), z = cell.z();
            if (!contains(x, y, z)) continue;
            grouped.computeIfAbsent(sectionAt(x, y, z), key -> new ArrayList<>())
                .add(new Placement(localIndex(x, y, z), materialId.applyAsInt(entry.getValue())));
        }
        Long2ObjectOpenHashMap<OverlaySection> result = new Long2ObjectOpenHashMap<>(grouped.size());
        for (var group : grouped.long2ObjectEntrySet()) {
            result.put(group.getLongKey(), new OverlaySection(group.getValue()));
        }
        return result;
    }

    public String plannedBlockAt(int x, int y, int z) {
        if (mode != Mode.BLUEPRINT) return terrain instanceof BuildVolume committed ? committed.plannedBlockAt(x, y, z) : null;
        if (!contains(x, y, z)) return null;
        OverlaySection overlay = sections.get(sectionAt(x, y, z));
        if (overlay == null || overlay.materialAt(localIndex(x, y, z)) == 0) return null;
        return blocks.get(new BuildPlan.Cell(x, y, z));
    }

    @Override
    public boolean plannedMaterial(int id) {
        return mode == Mode.BLUEPRINT ? id >= BuildPalette.MATERIAL_BASE && blueprintMaterial(id) != null
            : terrain.plannedMaterial(id);
    }

    @Override
    public OverlayPattern overlayPattern(int id) {
        if (mode != Mode.COMPARISON) return terrain.overlayPattern(id);
        return switch (id - COMPARISON_MATERIAL_BASE) {
            case 0 -> OverlayPattern.HORIZONTAL;
            case 1 -> OverlayPattern.DIAGONAL;
            case 2 -> OverlayPattern.GRID;
            case 3 -> OverlayPattern.DOTS;
            default -> terrain.overlayPattern(id);
        };
    }

    @Override public boolean previewMaterial(int id) {
        return mode == Mode.PREVIEW && id >= BuildPalette.MATERIAL_BASE + PREVIEW_MATERIAL_OFFSET;
    }

    @Override public int blockBehindPreview(int x, int y, int z) { return terrain.blockAt(x, y, z); }

    @Override public int minX() { return terrain.minX(); }
    @Override public int maxX() { return terrain.maxX(); }
    @Override public int minY() { return terrain.minY(); }
    @Override public int maxY() { return terrain.maxY(); }
    @Override public int minZ() { return terrain.minZ(); }
    @Override public int maxZ() { return terrain.maxZ(); }
    @Override public long revision() { return terrain.revision() + planRevision; }

    @Override public MapMaterial material(int id) {
        if (mode == Mode.COMPARISON) {
            int index = id - COMPARISON_MATERIAL_BASE;
            return index >= 0 && index < COMPARISON_MATERIALS.length ? COMPARISON_MATERIALS[index] : terrain.material(id);
        }
        if (mode == Mode.PREVIEW) return previewMaterial(id) ? blueprintMaterial(id - PREVIEW_MATERIAL_OFFSET) : terrain.material(id);
        return id >= BuildPalette.MATERIAL_BASE ? blueprintMaterial(id) : terrain.material(id);
    }

    private MapMaterial blueprintMaterial(int id) {
        int state = id - palette.firstStateMaterialId();
        if (state < 0) return palette.material(id);
        return state < stateMaterials.size() ? stateMaterials.get(state) : null;
    }

    @Override public int blockAt(int x, int y, int z) {
        if (!sections.isEmpty() && contains(x, y, z)) {
            OverlaySection overlay = sections.get(sectionAt(x, y, z));
            if (overlay != null) {
                int material = overlay.materialAt(localIndex(x, y, z));
                if (material != 0) return material;
            }
        }
        return terrain.blockAt(x, y, z);
    }

    @Override public int sectionState(int x, int y, int z) {
        return sections.containsKey(SectionPos.asLong(x, y, z)) ? 1 : terrain.sectionState(x, y, z);
    }

    @Override public boolean hasSectionViews() { return terrain.hasSectionViews(); }

    @Override public SectionView sectionView(int x, int y, int z) {
        SectionView captured = terrain.sectionView(x, y, z);
        OverlaySection overlay = sections.get(SectionPos.asLong(x, y, z));
        return overlay == null ? captured : overlay.layeredOver(captured);
    }

    private boolean contains(int x, int y, int z) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    private static long sectionAt(int x, int y, int z) {
        return SectionPos.asLong(x >> 4, y >> 4, z >> 4);
    }

    private static int localIndex(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    private static final class OverlaySection {
        private final short[] indexes;
        private final int[] materials;
        private final long bricks;
        private volatile LayeredSection last;

        private OverlaySection(List<Placement> placements) {
            long occupied = 0;
            if (placements.size() >= DENSE_SECTION_THRESHOLD) {
                indexes = null;
                materials = new int[SECTION_SIZE];
                for (Placement placement : placements) {
                    materials[placement.index()] = placement.material();
                    occupied |= 1L << SectionView.brickIndex(placement.index());
                }
            } else {
                placements.sort(Comparator.comparingInt(Placement::index));
                indexes = new short[placements.size()];
                materials = new int[placements.size()];
                for (int i = 0; i < placements.size(); i++) {
                    Placement placement = placements.get(i);
                    indexes[i] = (short) placement.index();
                    materials[i] = placement.material();
                    occupied |= 1L << SectionView.brickIndex(placement.index());
                }
            }
            bricks = occupied;
        }

        private int materialAt(int index) {
            if (indexes == null) return materials[index];
            int found = Arrays.binarySearch(indexes, (short) index);
            return found < 0 ? 0 : materials[found];
        }

        private SectionView layeredOver(SectionView terrain) {
            LayeredSection view = last;
            if (view == null || view.terrain() != terrain) {
                view = new LayeredSection(this, terrain);
                last = view;
            }
            return view;
        }
    }

    private record LayeredSection(OverlaySection overlay, SectionView terrain) implements SectionView {
        @Override public int materialAt(int index) {
            int material = overlay.materialAt(index);
            if (material != 0) return material;
            return terrain == null ? -1 : terrain.materialAt(index);
        }

        @Override public long occupiedBricks() {
            // Unknown cells around a ghost still affect visibility and must be visited by the ray.
            return terrain == null ? -1L : terrain.occupiedBricks() | overlay.bricks;
        }
    }
}
