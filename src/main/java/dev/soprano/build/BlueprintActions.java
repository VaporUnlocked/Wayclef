package dev.soprano.build;

import dev.soprano.build.BuildPlan.Bounds;
import dev.soprano.build.BuildPlan.Cell;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Operations shared by the build panel and live construction. */
public final class BlueprintActions {
    private static final Set<String> ORIENTATION_PROPERTIES = Set.of("facing", "horizontal_facing", "vertical_direction",
        "axis", "rotation", "orientation", "face", "attachment", "half", "hinge", "shape", "part",
        "north", "south", "east", "west", "up", "down");

    private BlueprintActions() { }

    public record ImportResult(int blocks, int unavailable) { }

    static final class PreparedImport {
        private final Map<Cell, String> blueprint;
        private final ImportResult result;

        private PreparedImport(Map<Cell, String> blueprint, int unavailable) {
            this.blueprint = blueprint;
            result = new ImportResult(blueprint.size(), unavailable);
        }

        ImportResult apply(BuildPlan plan) {
            plan.replace(blueprint);
            return result;
        }
    }

    public enum Difference {
        MISSING("Missing", 0xfff1c44c), WRONG("Wrong block or state", 0xfff05c67),
        EXTRA("Extra", 0xff58c9e6), MISORIENTED("Misoriented", 0xffbc88f7);

        private final String label;
        private final int color;

        Difference(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() { return label; }
        public int color() { return color; }
    }

    public record Comparison(Map<Cell, Difference> differences, Map<Difference, Integer> counts, int unloaded) {
        public Comparison {
            differences = Map.copyOf(differences);
            counts = Map.copyOf(counts);
        }

        public int count(Difference difference) { return counts.getOrDefault(difference, 0); }
    }

    public record MaterialCount(int required, int inventory, int storage) {
        public int missing() { return Math.max(0, required - inventory - storage); }
    }

    public static final class MaterialSwap {
        private final BuildPlan plan;
        private final long revision;
        private final Bounds selection;
        private final BuildPreview preview;
        private final BuildPreview attentionPreview;
        private final Map<String, Integer> attention;

        private MaterialSwap(BuildPlan plan, Bounds selection, Map<Cell, String> changes,
                             Map<Cell, String> needsReview, Map<String, Integer> attention) {
            this.plan = plan;
            this.revision = plan.revision();
            this.selection = selection;
            this.preview = BuildPreview.replacements(changes);
            this.attentionPreview = BuildPreview.replacements(needsReview);
            this.attention = Collections.unmodifiableMap(attention);
        }

        public boolean matches(BuildPlan plan, Cell first, Cell second) {
            if ((first == null) != (second == null)) return false;
            Bounds current = first == null ? null : new Bounds(first, second);
            return this.plan == plan && revision == plan.revision() && Objects.equals(selection, current);
        }

        public BuildPreview preview() { return preview; }
        public BuildPreview attentionPreview() { return attentionPreview; }
        public int size() { return preview.replacements().size(); }
        public Map<String, Integer> attention() { return attention; }

        public int apply(BuildPlan plan, Cell first, Cell second) {
            if (!matches(plan, first, second)) throw new IllegalStateException("Preview the current material swap before applying it.");
            return plan.apply(preview);
        }
    }

    /** Builds an undoable substitution while retaining each valid property value on the destination. */
    public static MaterialSwap previewMaterialSwap(BuildPlan plan, Cell first, Cell second, String from, String to) {
        String source = BuildPlan.normalizeBlockId(from);
        String destination = BuildPlan.normalizeBlockId(to);
        if (source.equals(destination)) throw new IllegalArgumentException("Choose a different replacement material.");
        BlockState target = BlueprintBlockStates.resolve(destination);
        if (target == null || target.isAir()) throw new IllegalArgumentException("Choose a registered replacement block.");
        if ((first == null) != (second == null)) throw new IllegalArgumentException("Set both region corners first.");
        Bounds selection = first == null ? null : new Bounds(first, second);
        Map<String, BlueprintBlockStates.Replacement> resolved = new HashMap<>();
        Map<Cell, String> changes = new HashMap<>();
        Map<Cell, String> needsReview = new HashMap<>();
        Map<String, Integer> attention = new TreeMap<>();
        for (var entry : plan.snapshot().entrySet()) {
            if (selection != null && !selection.contains(entry.getKey())) continue;
            String state = entry.getValue();
            if (!BlueprintBlockStates.blockId(state).equals(source)) continue;
            var replacement = resolved.computeIfAbsent(state, original -> BlueprintBlockStates.replaceMaterial(original, target));
            changes.put(entry.getKey(), replacement.state());
            List<String> details = replacement.attention();
            if (!details.isEmpty()) {
                needsReview.put(entry.getKey(), replacement.state());
                attention.merge(String.join(", ", details), 1, Integer::sum);
            }
        }
        return new MaterialSwap(plan, selection, changes, needsReview, attention);
    }

    /** Applies the file's relative offsets once, using the chosen point as the file origin. */
    public static ImportResult importFile(Path path, BuildPlan plan, Cell origin) throws IOException {
        return prepareImport(path, origin).apply(plan);
    }

    static PreparedImport prepareImport(Path path, Cell origin) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".schematic") && !name.endsWith(".schem") && !name.endsWith(".litematic")) {
            throw new IOException("Choose a .schematic, .schem, or .litematic file.");
        }
        SchematicReader.Schematic schematic = SchematicReader.readForBlueprint(path);
        Map<SchematicReader.BlockState, BlueprintBlockStates.Imported> states = new HashMap<>();
        Map<Cell, String> blueprint = new LinkedHashMap<>();
        int unavailable = 0;
        for (var entry : schematic.blocks().entrySet()) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Schematic import cancelled.");
            SchematicReader.BlockState source = entry.getValue();
            BlueprintBlockStates.Imported state = states.computeIfAbsent(source,
                key -> BlueprintBlockStates.migrate(key, schematic.minecraftDataVersion()));
            var position = entry.getKey();
            blueprint.put(translated(origin, position.x(), position.y(), position.z()), state.canonical());
            if (!state.available()) unavailable++;
        }
        if (blueprint.isEmpty()) throw new IOException("The schematic contains no blocks to import.");
        return new PreparedImport(blueprint, unavailable);
    }

    public static int exportSelection(ClientLevel level, Cell first, Cell second, Path directory, String name)
        throws IOException {
        Bounds bounds = new Bounds(first, second);
        bounds.volume();
        requireWorldBounds(level, bounds);
        Map<Cell, String> blueprint = new LinkedHashMap<>();
        Map<BlockState, String> states = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) throw new IOException("Load the entire selection before exporting.");
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.isAir()) continue;
                    BuildPlan.requireBlockLimit((long) blueprint.size() + 1);
                    blueprint.put(new Cell(x, y, z), states.computeIfAbsent(state, BlueprintBlockStates::encode));
                }
            }
        }
        if (blueprint.isEmpty()) throw new IOException("The selected volume contains no blocks.");
        BuildPlanFiles.save(directory, name, blueprint);
        return blueprint.size();
    }

    /** Scans the selected region, or the entire blueprint's bounds when both corners are omitted. */
    public static Comparison compare(ClientLevel level, BuildPlan plan, Cell first, Cell second) {
        Map<Cell, String> blueprint = plan.snapshot();
        if (blueprint.isEmpty()) throw new IllegalArgumentException("Open a blueprint before comparing.");
        Bounds bounds = first == null || second == null ? boundsOf(blueprint) : new Bounds(first, second);
        bounds.volume();
        requireWorldBounds(level, bounds);
        Map<Cell, Difference> differences = new HashMap<>();
        Map<Difference, Integer> counts = new EnumMap<>(Difference.class);
        Map<String, BlockState> states = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int unloaded = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    unloaded += bounds.height();
                    continue;
                }
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    Cell cell = new Cell(x, y, z);
                    String expected = blueprint.get(cell);
                    BlockState actual = level.getBlockState(pos.set(x, y, z));
                    if (expected != null && !states.containsKey(expected)) {
                        states.put(expected, BlueprintBlockStates.resolve(expected));
                    }
                    Difference difference = difference(expected, states.get(expected), actual);
                    if (difference == null) continue;
                    differences.put(cell, difference);
                    counts.merge(difference, 1, Integer::sum);
                }
            }
        }
        return new Comparison(differences, counts, unloaded);
    }

    static Difference difference(String expectedId, BlockState expected, BlockState actual) {
        if (expectedId == null) return actual.isAir() ? null : Difference.EXTRA;
        if (actual.isAir()) return Difference.MISSING;
        if (expected == actual) return null;
        if (expected == null || expected.getBlock() != actual.getBlock()) return Difference.WRONG;
        boolean orientation = false;
        for (var property : expected.getProperties()) {
            if (expected.getValue(property).equals(actual.getValue(property))) continue;
            boolean slabSide = property == BlockStateProperties.SLAB_TYPE
                && expected.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE
                && actual.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE;
            if (!slabSide && !ORIENTATION_PROPERTIES.contains(property.getName())) return Difference.WRONG;
            orientation = true;
        }
        return orientation ? Difference.MISORIENTED : Difference.WRONG;
    }

    public static int rotate(BuildPlan plan, Cell first, Cell second) {
        Bounds bounds = new Bounds(first, second);
        return transform(plan, bounds, cell -> new Cell(bounds.minX() + bounds.maxZ() - cell.z(), cell.y(),
            bounds.minZ() + cell.x() - bounds.minX()), state -> state.rotate(Rotation.CLOCKWISE_90));
    }

    public static int mirror(BuildPlan plan, Cell first, Cell second, BuildPlan.Axis axis) {
        Bounds bounds = new Bounds(first, second);
        Mirror mirror = axis == BuildPlan.Axis.X ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
        return transform(plan, bounds, cell -> new Cell(
            axis == BuildPlan.Axis.X ? bounds.minX() + bounds.maxX() - cell.x() : cell.x(), cell.y(),
            axis == BuildPlan.Axis.Z ? bounds.minZ() + bounds.maxZ() - cell.z() : cell.z()), state -> state.mirror(mirror));
    }

    private static int transform(BuildPlan plan, Bounds selection, UnaryOperator<Cell> coordinates,
                                 UnaryOperator<BlockState> transformState) {
        Map<Cell, String> source = plan.snapshot();
        Map<Cell, String> updated = new HashMap<>();
        Map<String, String> states = new HashMap<>();
        source.forEach((cell, state) -> {
            if (!selection.contains(cell)) updated.put(cell, state);
        });
        for (var entry : source.entrySet()) {
            if (!selection.contains(entry.getKey())) continue;
            Cell destination = coordinates.apply(entry.getKey());
            if (updated.containsKey(destination)) throw new IllegalArgumentException("The transformed selection overlaps the plan.");
            String state = states.computeIfAbsent(entry.getValue(), serialized -> {
                BlockState resolved = BlueprintBlockStates.resolve(serialized);
                if (resolved == null) {
                    if (serialized.indexOf('[') < 0) return serialized;
                    throw new IllegalArgumentException("Cannot orient unavailable block state " + BlueprintBlockStates.blockId(serialized) + ".");
                }
                BlockState transformed = transformState.apply(resolved);
                return transformed == resolved ? serialized : BlueprintBlockStates.encode(transformed);
            });
            updated.put(destination, state);
        }
        return plan.replace(updated);
    }

    /** Moves the entire blueprint so its occupied minimum corner matches the selected origin. */
    public static int align(BuildPlan plan, Cell origin) {
        Map<Cell, String> source = plan.snapshot();
        Bounds bounds = boundsOf(source);
        Map<Cell, String> moved = new HashMap<>();
        source.forEach((cell, state) -> moved.put(translated(origin, cell.x() - bounds.minX(),
            cell.y() - bounds.minY(), cell.z() - bounds.minZ()), state));
        return plan.replace(moved);
    }

    /** Tiles the clipboard in a one-block-thick selection and clips partial edge tiles. */
    public static int repeat(BuildPlan plan, BuildPlan.Clipboard pattern, Cell first, Cell second) {
        Bounds target = new Bounds(first, second);
        target.volume();
        if (!(target.width() == 1 && pattern.width() == 1 || target.height() == 1 && pattern.height() == 1
            || target.depth() == 1 && pattern.depth() == 1)) {
            throw new IllegalArgumentException("Copy a flat pattern in the same plane as the target selection.");
        }
        Map<Cell, String> patch = new HashMap<>();
        for (var entry : pattern.blocks().entrySet()) {
            BuildPlan.Offset offset = entry.getKey();
            for (int x = target.minX() + offset.x(); x <= target.maxX(); x += pattern.width()) {
                for (int y = target.minY() + offset.y(); y <= target.maxY(); y += pattern.height()) {
                    for (int z = target.minZ() + offset.z(); z <= target.maxZ(); z += pattern.depth()) {
                        BuildPlan.requireBlockLimit((long) patch.size() + 1);
                        patch.put(new Cell(x, y, z), entry.getValue());
                    }
                }
            }
        }
        return plan.applyBlocks(patch);
    }

    public static Map<String, Integer> requirements(BuildPlan plan) {
        Map<String, Ingredient> states = new HashMap<>();
        Map<String, Integer> required = new TreeMap<>();
        for (String serialized : plan.snapshot().values()) {
            Ingredient material = states.computeIfAbsent(serialized, BlueprintActions::ingredient);
            if (material.count() > 0) required.merge(material.id(), material.count(), Integer::sum);
        }
        return Collections.unmodifiableMap(required);
    }

    private record Ingredient(String id, int count) { }

    public static String materialId(String serialized) { return ingredient(serialized).id(); }

    private static Ingredient ingredient(String serialized) {
        BlockState state = BlueprintBlockStates.resolve(serialized);
        if (state == null) return new Ingredient(BlueprintBlockStates.blockId(serialized), 1);
        var item = state.getBlock().asItem();
        String id = item == Items.AIR ? BlueprintBlockStates.blockId(serialized) : BuiltInRegistries.ITEM.getKey(item).toString();
        return new Ingredient(id, placementCost(state));
    }

    private static int placementCost(BlockState state) {
        var block = state.getBlock();
        if ((block instanceof DoorBlock || block instanceof DoublePlantBlock)
            && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return 0;
        if (block instanceof BedBlock && state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return 0;
        if (block instanceof SlabBlock && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        if (block instanceof CandleBlock) return state.getValue(BlockStateProperties.CANDLES);
        if (block instanceof SeaPickleBlock) return state.getValue(BlockStateProperties.PICKLES);
        if (block instanceof TurtleEggBlock) return state.getValue(BlockStateProperties.EGGS);
        if (block == Blocks.SNOW) return state.getValue(BlockStateProperties.LAYERS);
        return 1;
    }

    public static Map<String, MaterialCount> materials(LocalPlayer player, BuildPlan plan, Map<String, Integer> selectedStorage) {
        return materials(player, requirements(plan), selectedStorage);
    }

    public static Map<String, MaterialCount> materials(LocalPlayer player, Map<String, Integer> requirements,
                                                        Map<String, Integer> selectedStorage) {
        Map<String, Integer> inventory = new HashMap<>();
        if (player != null) {
            for (ItemStack stack : player.getInventory().getNonEquipmentItems()) addStack(inventory, stack);
            addStack(inventory, player.getOffhandItem());
        }
        Map<String, MaterialCount> result = new LinkedHashMap<>();
        requirements.forEach((id, count) -> result.put(id, new MaterialCount(count, inventory.getOrDefault(id, 0),
            selectedStorage.getOrDefault(id, 0))));
        return Collections.unmodifiableMap(result);
    }

    static void addStack(Map<String, Integer> counts, ItemStack stack) {
        if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    private static Cell translated(Cell origin, int x, int y, int z) {
        try {
            return new Cell(Math.addExact(origin.x(), x), Math.addExact(origin.y(), y), Math.addExact(origin.z(), z));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Blueprint is outside supported world coordinates.", overflow);
        }
    }

    private static Bounds boundsOf(Map<Cell, String> blueprint) {
        if (blueprint.isEmpty()) throw new IllegalArgumentException("Open a blueprint first.");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : blueprint.keySet()) {
            minX = Math.min(minX, cell.x());
            minY = Math.min(minY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x());
            maxY = Math.max(maxY, cell.y());
            maxZ = Math.max(maxZ, cell.z());
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void requireWorldBounds(ClientLevel level, Bounds bounds) {
        if (bounds.minY() < level.getMinY() || bounds.maxY() > level.getMaxY()) {
            throw new IllegalArgumentException("Selection extends outside the world's build height.");
        }
    }
}
