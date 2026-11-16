package dev.soprano.build;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** A client-side collection of planned block positions, separate from the world. */
public final class BuildPlan {
    public static final int MAX_BLOCKS = 131_072;
    public static final int MAX_BLOCK_ID_LENGTH = 256;
    static final int MAX_BLOCK_STATE_LENGTH = MAX_BLOCK_ID_LENGTH + 2 + 64 * 258;

    private static final long MAX_SHAPE_VOLUME = 1_048_576;
    private static final int MAX_HISTORY_EDITS = 64;
    private static final int MAX_HISTORY_CHANGES = MAX_BLOCKS * 2;
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public record Cell(int x, int y, int z) {
        public Cell {
            if (x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000
                || y < -2048 || y >= 2048) throw new IllegalArgumentException("Block is outside supported world coordinates.");
        }
    }

    public record Offset(int x, int y, int z) { }

    public record Clipboard(int width, int height, int depth, Map<Offset, String> blocks) {
        public Clipboard {
            checkedVolume(width, height, depth);
            requireBlockLimit(blocks.size());
            Map<Offset, String> validated = new LinkedHashMap<>();
            for (var entry : blocks.entrySet()) {
                Offset offset = Objects.requireNonNull(entry.getKey(), "clipboard offset");
                if (offset.x < 0 || offset.x >= width || offset.y < 0 || offset.y >= height
                    || offset.z < 0 || offset.z >= depth)
                    throw new IllegalArgumentException("Clipboard block is outside its selection.");
                validated.put(offset, normalizeBlockState(entry.getValue()));
            }
            blocks = Collections.unmodifiableMap(validated);
        }
    }

    public enum Axis { X, Z }

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Bounds(Cell first, Cell second) {
            this(Math.min(first.x, second.x), Math.min(first.y, second.y), Math.min(first.z, second.z),
                Math.max(first.x, second.x), Math.max(first.y, second.y), Math.max(first.z, second.z));
        }

        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        int depth() { return maxZ - minZ + 1; }

        long volume() { return checkedVolume(width(), height(), depth()); }

        boolean contains(Cell cell) {
            return cell.x >= minX && cell.x <= maxX && cell.y >= minY && cell.y <= maxY
                && cell.z >= minZ && cell.z <= maxZ;
        }
    }

    private record Change(Cell cell, String before, String after) { }

    private final Map<Cell, String> blocks = new HashMap<>();
    private final ArrayDeque<List<Change>> undo = new ArrayDeque<>();
    private final ArrayDeque<List<Change>> redo = new ArrayDeque<>();
    private long revision;
    private int historyChanges;
    private Map<Cell, String> snapshot;
    private Map<String, Integer> materials;

    public int size() { return blocks.size(); }
    public long revision() { return revision; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    /** An immutable copy suitable for a renderer to retain across client edits. */
    public Map<Cell, String> snapshot() {
        if (snapshot == null) snapshot = Map.copyOf(blocks);
        return snapshot;
    }

    public Map<String, Integer> materialCounts() {
        if (materials == null) {
            Map<String, Integer> counts = new TreeMap<>();
            for (String state : blocks.values()) counts.merge(blockId(state), 1, Integer::sum);
            materials = Collections.unmodifiableMap(counts);
        }
        return materials;
    }

    public int place(Cell cell, String block) {
        return commit(Map.of(cell, normalizeBlockState(block)));
    }

    public int erase(Cell cell) {
        return commit(Collections.singletonMap(Objects.requireNonNull(cell), null));
    }

    public int box(Cell first, Cell second, String block, boolean hollow) {
        return commit(boxBlocks(first, second, block, hollow));
    }

    static Map<Cell, String> boxBlocks(Cell first, Cell second, String block, boolean hollow) {
        Bounds bounds = new Bounds(first, second);
        String id = normalizeBlockState(block);
        long volume = bounds.volume();
        long interior = (long) Math.max(0, bounds.width() - 2) * Math.max(0, bounds.height() - 2)
            * Math.max(0, bounds.depth() - 2);
        requireBlockLimit(hollow ? volume - interior : volume);
        Map<Cell, String> patch = new LinkedHashMap<>();
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int y = bounds.minY; y <= bounds.maxY; y++) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                    if (hollow && x > bounds.minX && x < bounds.maxX && y > bounds.minY && y < bounds.maxY
                        && z > bounds.minZ && z < bounds.maxZ) continue;
                    patch.put(new Cell(x, y, z), id);
                }
            }
        }
        return patch;
    }

    /** A 26-connected line of block centers, including both endpoints. */
    public int line(Cell first, Cell second, String block) {
        return commit(lineBlocks(first, second, block));
    }

    static Map<Cell, String> lineBlocks(Cell first, Cell second, String block) {
        String id = normalizeBlockState(block);
        int steps = Math.max(Math.abs(second.x - first.x),
            Math.max(Math.abs(second.y - first.y), Math.abs(second.z - first.z)));
        requireBlockLimit((long) steps + 1);
        if (steps == 0) return Map.of(first, id);

        Map<Cell, String> patch = new LinkedHashMap<>();
        for (int i = 0; i <= steps; i++) {
            Cell cell = new Cell(interpolate(first.x, second.x, i, steps),
                interpolate(first.y, second.y, i, steps), interpolate(first.z, second.z, i, steps));
            patch.put(cell, id);
        }
        return patch;
    }

    private static int interpolate(int start, int end, int step, int steps) {
        long numerator = (long) start * (steps - step) + (long) end * step;
        return (int) Math.floorDiv(numerator + steps / 2, steps);
    }

    /** A Y-aligned cylinder or ellipse whose hollow variant includes both caps. */
    public int cylinder(Cell first, Cell second, String block, boolean hollow) {
        return commit(cylinderBlocks(first, second, block, hollow));
    }

    static Map<Cell, String> cylinderBlocks(Cell first, Cell second, String block, boolean hollow) {
        Bounds bounds = new Bounds(first, second);
        String id = normalizeBlockState(block);
        bounds.volume();
        double centerX = (bounds.minX + bounds.maxX) * .5;
        double centerZ = (bounds.minZ + bounds.maxZ) * .5;
        double radiusX = bounds.width() * .5;
        double radiusZ = bounds.depth() * .5;

        Map<Cell, String> patch = new LinkedHashMap<>();
        for (int x = bounds.minX; x <= bounds.maxX; x++) {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                if (!insideEllipse(x, z, centerX, centerZ, radiusX, radiusZ)) continue;
                boolean wall = !insideEllipse(x - 1, z, centerX, centerZ, radiusX, radiusZ)
                    || !insideEllipse(x + 1, z, centerX, centerZ, radiusX, radiusZ)
                    || !insideEllipse(x, z - 1, centerX, centerZ, radiusX, radiusZ)
                    || !insideEllipse(x, z + 1, centerX, centerZ, radiusX, radiusZ);
                if (hollow && !wall) {
                    patch.put(new Cell(x, bounds.minY, z), id);
                    patch.put(new Cell(x, bounds.maxY, z), id);
                } else {
                    requireBlockLimit((long) patch.size() + bounds.height());
                    for (int y = bounds.minY; y <= bounds.maxY; y++) patch.put(new Cell(x, y, z), id);
                }
                requireBlockLimit(patch.size());
            }
        }
        return patch;
    }

    private static boolean insideEllipse(int x, int z, double centerX, double centerZ,
                                         double radiusX, double radiusZ) {
        double dx = (x - centerX) / radiusX;
        double dz = (z - centerZ) / radiusZ;
        return dx * dx + dz * dz <= 1;
    }

    public int eraseBox(Cell first, Cell second) {
        Bounds bounds = new Bounds(first, second);
        Map<Cell, String> patch = new HashMap<>();
        for (Cell cell : blocks.keySet()) {
            if (bounds.contains(cell)) patch.put(cell, null);
        }
        return commit(patch);
    }

    public Clipboard copy(Cell first, Cell second) {
        Bounds bounds = new Bounds(first, second);
        bounds.volume();
        Map<Offset, String> copied = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            Cell cell = entry.getKey();
            if (bounds.contains(cell))
                copied.put(new Offset(cell.x - bounds.minX, cell.y - bounds.minY, cell.z - bounds.minZ), entry.getValue());
        }
        return new Clipboard(bounds.width(), bounds.height(), bounds.depth(), copied);
    }

    public int paste(Cell origin, Clipboard clipboard) {
        return commit(pasteBlocks(origin, clipboard));
    }

    static Map<Cell, String> pasteBlocks(Cell origin, Clipboard clipboard) {
        Map<Cell, String> patch = new LinkedHashMap<>();
        for (var entry : clipboard.blocks.entrySet()) {
            Offset offset = entry.getKey();
            Cell cell = new Cell(origin.x + offset.x, origin.y + offset.y, origin.z + offset.z);
            patch.put(cell, entry.getValue());
        }
        return patch;
    }

    public int apply(BuildPreview preview) {
        return preview.kind() == BuildPreview.Kind.REPLACEMENTS ? applyBlocks(preview.replacements()) : commit(preview.blocks());
    }

    int applyBlocks(Map<Cell, String> patch) {
        Map<Cell, String> validated = new HashMap<>();
        Map<String, String> states = new HashMap<>();
        for (var entry : patch.entrySet()) {
            validated.put(Objects.requireNonNull(entry.getKey()), states.computeIfAbsent(entry.getValue(), BuildPlan::normalizeBlockState));
        }
        return commit(validated);
    }

    /** Reflects selected plan blocks within their selection about the chosen horizontal axis. */
    public int mirror(Cell first, Cell second, Axis axis) {
        Objects.requireNonNull(axis);
        Bounds bounds = new Bounds(first, second);
        Map<Cell, String> patch = new HashMap<>();
        Map<Cell, String> reflected = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            Cell cell = entry.getKey();
            if (!bounds.contains(cell)) continue;
            patch.put(cell, null);
            int x = axis == Axis.X ? bounds.minX + bounds.maxX - cell.x : cell.x;
            int z = axis == Axis.Z ? bounds.minZ + bounds.maxZ - cell.z : cell.z;
            reflected.put(new Cell(x, cell.y, z), entry.getValue());
        }
        patch.putAll(reflected);
        return commit(patch);
    }

    public int clear() {
        return replace(Map.of());
    }

    /** Replaces the blueprint in one undoable edit, including when opening a saved plan. */
    public int replace(Map<Cell, String> replacement) {
        requireBlockLimit(replacement.size());
        Map<Cell, String> patch = new HashMap<>();
        Map<String, String> states = new HashMap<>();
        for (Cell existing : blocks.keySet()) patch.put(existing, null);
        for (var entry : replacement.entrySet()) {
            patch.put(Objects.requireNonNull(entry.getKey()), states.computeIfAbsent(entry.getValue(), BuildPlan::normalizeBlockState));
        }
        return commit(patch);
    }

    public int undo() {
        if (!canUndo()) return 0;
        List<Change> edit = undo.removeLast();
        apply(edit, false);
        redo.addLast(edit);
        return edit.size();
    }

    public int redo() {
        if (!canRedo()) return 0;
        List<Change> edit = redo.removeLast();
        apply(edit, true);
        undo.addLast(edit);
        return edit.size();
    }

    private int commit(Map<Cell, String> patch) {
        List<Change> changes = new ArrayList<>();
        int nextSize = blocks.size();
        for (var entry : patch.entrySet()) {
            Cell cell = entry.getKey();
            String before = blocks.get(cell);
            String after = entry.getValue();
            if (Objects.equals(before, after)) continue;
            changes.add(new Change(cell, before, after));
            if (before == null) nextSize++;
            if (after == null) nextSize--;
        }
        if (changes.isEmpty()) return 0;
        requireBlockLimit(nextSize);

        while (!redo.isEmpty()) historyChanges -= redo.removeFirst().size();
        List<Change> edit = List.copyOf(changes);
        apply(edit, true);
        undo.addLast(edit);
        historyChanges += edit.size();
        while (undo.size() > MAX_HISTORY_EDITS || historyChanges > MAX_HISTORY_CHANGES)
            historyChanges -= undo.removeFirst().size();
        return edit.size();
    }

    private void apply(List<Change> edit, boolean forward) {
        for (Change change : edit) {
            String id = forward ? change.after : change.before;
            if (id == null) blocks.remove(change.cell);
            else blocks.put(change.cell, id);
        }
        revision++;
        snapshot = null;
        materials = null;
    }

    static String normalizeBlockId(String block) {
        String id = Objects.requireNonNull(block, "block ID").strip();
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        if (id.length() > MAX_BLOCK_ID_LENGTH || !BLOCK_ID.matcher(id).matches())
            throw new IllegalArgumentException("Invalid block identifier.");
        return id;
    }

    static String normalizeBlockState(String block) {
        String state = Objects.requireNonNull(block, "block state").strip();
        if (state.indexOf('[') < 0) return normalizeBlockId(state);
        if (state.length() > MAX_BLOCK_STATE_LENGTH) throw new IllegalArgumentException("Block state is too long.");
        return SchematicReader.parseState(state).canonical();
    }

    static String blockId(String state) {
        int properties = state.indexOf('[');
        return properties < 0 ? state : state.substring(0, properties);
    }

    private static long checkedVolume(int width, int height, int depth) {
        if (width < 1 || height < 1 || depth < 1 || width > MAX_SHAPE_VOLUME || height > MAX_SHAPE_VOLUME
            || depth > MAX_SHAPE_VOLUME || (long) width * height > MAX_SHAPE_VOLUME
            || (long) width * height * depth > MAX_SHAPE_VOLUME)
            throw new IllegalArgumentException("Shape or clipboard exceeds " + MAX_SHAPE_VOLUME + " voxels.");
        return (long) width * height * depth;
    }

    static void requireBlockLimit(long count) {
        if (count > MAX_BLOCKS) throw new IllegalArgumentException("A build plan can hold up to " + MAX_BLOCKS + " blocks.");
    }
}
