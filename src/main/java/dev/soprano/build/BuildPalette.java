package dev.soprano.build;

import dev.soprano.map.MapMaterial;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Captures the block catalog and original palette tiles on the client thread. */
public final class BuildPalette {
    static final int MATERIAL_BASE = 1 << 29;
    private static final int FALLBACK_COLOR = 0xff8892a0;

    public record Entry(String id, String displayName, int color) { }

    private record SearchEntry(Entry entry, String words) { }

    private final List<Entry> entries;
    private final List<SearchEntry> searchEntries;
    private final Map<String, Integer> indexes;
    private final MapMaterial[] materials;

    public BuildPalette(ClientLevel level, BlockPos colorPosition) {
        BlockPos sample = colorPosition == null ? BlockPos.ZERO : colorPosition.immutable();
        List<Entry> sorted = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            String name = block.getName().getString();
            sorted.add(new Entry(id, name, colorOf(block, level, sample)));
        }
        sorted.sort(Comparator.comparing(Entry::id));
        entries = List.copyOf(sorted);

        List<SearchEntry> searchable = new ArrayList<>(sorted.size());
        Map<String, Integer> byId = new HashMap<>();
        materials = new MapMaterial[sorted.size() + 1];
        materials[0] = new MapMaterial("Unavailable planned block", previewTile(FALLBACK_COLOR, 0));
        for (int index = 0; index < sorted.size(); index++) {
            Entry entry = sorted.get(index);
            int tile = index + 1;
            byId.put(entry.id(), tile);
            searchable.add(new SearchEntry(entry, (entry.id() + " " + entry.displayName()).toLowerCase(Locale.ROOT)));
            materials[tile] = new MapMaterial(entry.displayName(), previewTile(entry.color(), entry.id().hashCode()));
        }
        indexes = Map.copyOf(byId);
        searchEntries = List.copyOf(searchable);
    }

    public List<Entry> entries() { return entries; }

    public List<Entry> search(String query) {
        if (query == null || query.isBlank()) return entries;
        String[] terms = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        List<Entry> results = new ArrayList<>();
        for (SearchEntry candidate : searchEntries) {
            boolean matched = true;
            for (String term : terms) {
                if (!candidate.words().contains(term)) {
                    matched = false;
                    break;
                }
            }
            if (matched) results.add(candidate.entry());
        }
        return List.copyOf(results);
    }

    public Entry find(String id) {
        int index = indexOf(id);
        return index == 0 ? null : entries.get(index - 1);
    }

    int materialId(String id) { return MATERIAL_BASE + indexOf(id); }

    int firstStateMaterialId() { return MATERIAL_BASE + materials.length; }

    MapMaterial stateMaterial(String serialized) {
        if (serialized.indexOf('[') < 0) return null;
        Entry entry = find(serialized);
        if (entry == null) return null;
        BlockState state = BlueprintBlockStates.resolve(serialized);
        if (state == null || state.isSolidRender()) return null;
        try {
            List<MapMaterial.Box> outline = MapMaterial.outline(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            if (outline.isEmpty()) return null;
            return new MapMaterial(entry.displayName(), previewTile(entry.color(), entry.id().hashCode()), outline);
        } catch (RuntimeException unsupported) {
            // Some custom outlines need a world or a block entity.
            return null;
        }
    }

    MapMaterial material(int id) {
        int index = id - MATERIAL_BASE;
        return index >= 0 && index < materials.length ? materials[index] : null;
    }

    private int indexOf(String id) {
        if (id == null) return 0;
        String key = BuildPlan.blockId(id.strip()).toLowerCase(Locale.ROOT);
        if (key.indexOf(':') < 0) key = "minecraft:" + key;
        return indexes.getOrDefault(key, 0);
    }

    private static int colorOf(Block block, ClientLevel level, BlockPos sample) {
        if (level == null) return FALLBACK_COLOR;
        try {
            int color = block.defaultBlockState().getMapColor(level, sample).calculateARGBColor(MapColor.Brightness.NORMAL);
            return (color >>> 24) == 0 ? FALLBACK_COLOR : color;
        } catch (RuntimeException ignored) {
            // Custom blocks can require a specific placement to provide a color.
            return FALLBACK_COLOR;
        }
    }

    static int[] previewTile(int color, int seed) {
        int size = MapMaterial.TILE_SIZE;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean edge = x == 0 || y == 0 || x == size - 1 || y == size - 1;
                int fleck = Math.floorMod(seed + (x ^ y) * 11 + x * 3, 9) - 4;
                int bright = edge ? 13 : fleck;
                int red = Math.clamp((color >>> 16 & 255) + bright, 0, 255);
                int green = Math.clamp((color >>> 8 & 255) + bright + (edge ? 8 : 0), 0, 255);
                int blue = Math.clamp((color & 255) + bright + (edge ? 10 : 0), 0, 255);
                pixels[y * size + x] = 0xff000000 | red << 16 | green << 8 | blue;
            }
        }
        return pixels;
    }
}
