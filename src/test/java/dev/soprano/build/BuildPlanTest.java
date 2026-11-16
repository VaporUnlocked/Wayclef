package dev.soprano.build;

import dev.soprano.build.BuildPlan.Axis;
import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.BuildPlan.Clipboard;
import dev.soprano.build.BuildPlan.Offset;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class BuildPlanTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        editHistory();
        shapes();
        previewEdits();
        copyAndMirror();
        boundedAtomicEdits();
        historyLimit();
        files();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        materialSwaps();
        System.out.println("Soprano build planner passed " + checks + " checks.");
    }

    private static void materialSwaps() {
        BuildPlan plan = new BuildPlan();
        Cell first = cell(0, 64, 0), outside = cell(2, 64, 0), planks = cell(0, 63, 0);
        String oak = "minecraft:oak_stairs[facing=west,half=top,shape=inner_left,waterlogged=true]";
        String spruce = "minecraft:spruce_stairs[facing=west,half=top,shape=inner_left,waterlogged=true]";
        plan.place(first, oak);
        plan.place(outside, oak);
        plan.place(planks, "oak_planks");

        BlueprintActions.MaterialSwap selected = BlueprintActions.previewMaterialSwap(plan, first, planks, "oak_stairs", "spruce_stairs");
        equal(Map.of(first, spruce), selected.preview().blocks());
        equal(Map.of(), selected.attention());
        equal(oak, plan.snapshot().get(first));
        check(!selected.matches(plan, first, outside), "Changing the selection invalidates the preview.");
        equal(1, selected.apply(plan, first, planks));
        equal(spruce, plan.snapshot().get(first));
        equal(oak, plan.snapshot().get(outside));
        expect(IllegalStateException.class, () -> selected.apply(plan, first, planks));
        equal(1, plan.undo());
        equal(oak, plan.snapshot().get(first));

        BlueprintActions.MaterialSwap all = BlueprintActions.previewMaterialSwap(plan, null, null, "oak_stairs", "spruce_slab");
        String replacement = "minecraft:spruce_slab[type=bottom,waterlogged=true]";
        equal(Map.of(first, replacement, outside, replacement), all.preview().blocks());
        equal(all.preview().blocks(), all.attentionPreview().blocks());
        equal(2, all.attention().values().stream().mapToInt(Integer::intValue).sum());
        check(all.attention().keySet().iterator().next().contains("half=top"), "Unavailable properties are reported.");
        equal(2, all.apply(plan, null, null));
        equal("minecraft:oak_planks", plan.snapshot().get(planks));
        equal(2, plan.undo());
        equal(oak, plan.snapshot().get(outside));
        expect(IllegalArgumentException.class, () -> BlueprintActions.previewMaterialSwap(plan, null, null, "oak_stairs", "missing:block"));
    }

    private static void editHistory() {
        BuildPlan plan = new BuildPlan();
        Cell a = cell(-9, 4, 7), b = cell(8, 6, -10);
        equal(1, plan.place(a, "oak_planks"));
        equal(1L, plan.revision());
        Map<Cell, String> first = plan.snapshot();
        equal("minecraft:oak_planks", first.get(a));
        equal(0, plan.place(a, "minecraft:oak_planks"));
        same(first, plan.snapshot());
        equal(1L, plan.revision());
        expect(UnsupportedOperationException.class, () -> first.put(b, "minecraft:stone"));
        equal(1, plan.place(a, "soprano:demo/slab"));
        equal(1, plan.place(b, "soprano:demo/slab"));
        equal(Map.of("soprano:demo/slab", 2), plan.materialCounts());
        equal("minecraft:oak_planks", first.get(a));
        equal(1, plan.erase(a));
        equal(1, plan.size());
        equal(1, plan.undo());
        equal(2, plan.size());
        equal(0, plan.erase(cell(0, 0, 0)));
        check(plan.canRedo(), "No-op must preserve redo.");
        equal(1, plan.redo());
        equal(1, plan.size());
        equal(1, plan.undo());
        equal(1, plan.place(a, "stone"));
        check(!plan.canRedo(), "New edit clears redo.");
        equal(2, plan.clear());
        check(plan.snapshot().isEmpty(), "Clear removes the blueprint.");
        equal(2, plan.undo());
        equal(Map.of("minecraft:stone", 1, "soprano:demo/slab", 1), plan.materialCounts());
    }

    private static void shapes() {
        BuildPlan plan = new BuildPlan();
        Cell a = cell(-2, -2, -2), b = cell(1, 1, 1);
        equal(56, plan.box(b, a, "stone", true));
        check(!plan.snapshot().containsKey(cell(0, 0, 0)), "Hollow box has an empty interior.");
        check(plan.snapshot().containsKey(cell(0, 1, 0)), "Hollow box includes the roof.");
        equal(8, plan.box(a, b, "stone", false));
        equal(64, plan.size());
        equal(8, plan.undo());
        equal(56, plan.size());

        BuildPlan forward = new BuildPlan(), backward = new BuildPlan();
        a = cell(-6, 3, 12);
        b = cell(4, -2, 15);
        equal(11, forward.line(a, b, "minecraft:glass"));
        equal(11, backward.line(b, a, "minecraft:glass"));
        equal(forward.snapshot(), backward.snapshot());
        check(forward.snapshot().containsKey(a) && forward.snapshot().containsKey(b), "Line includes both endpoints.");
        for (int x = a.x(); x <= b.x(); x++) {
            int column = x;
            equal(1L, forward.snapshot().keySet().stream().filter(cell -> cell.x() == column).count());
        }
        equal(1, forward.line(cell(8, 4, 2), cell(8, 4, 2), "stone"));

        BuildPlan cylinder = new BuildPlan();
        a = cell(-2, 10, -2);
        b = cell(2, 12, 2);
        equal(54, cylinder.cylinder(a, b, "glass", true));
        check(!cylinder.snapshot().containsKey(cell(0, 11, 0)), "Hollow cylinder has an empty middle.");
        check(cylinder.snapshot().containsKey(cell(0, 10, 0)), "Hollow cylinder has a bottom cap.");
        check(cylinder.snapshot().containsKey(cell(0, 12, 0)), "Hollow cylinder has a top cap.");
        check(cylinder.snapshot().containsKey(cell(2, 11, 0)), "Hollow cylinder has a side wall.");
        check(!cylinder.snapshot().containsKey(cell(2, 11, 2)), "Cylinder leaves the bounding corners empty.");
        equal(9, cylinder.cylinder(b, a, "glass", false));
        equal(63, cylinder.size());
        equal(3, cylinder.cylinder(cell(8, 9, -1), cell(8, 11, -1), "glass", true));
    }

    private static void previewEdits() {
        Cell start = cell(-2, 4, 0), hover = cell(2, 6, 4), anchor = cell(2, 7, 4);
        BuildPlan plan = new BuildPlan();
        BuildPreview first = BuildPreview.shape(BuildPreview.Kind.TUBE, start, hover, "stone");
        BuildPreview locked = BuildPreview.shape(BuildPreview.Kind.TUBE, start, anchor, "glass");
        Map<Cell, String> hoverBlocks = first.blocks();
        Map<Cell, String> lockedBlocks = locked.blocks();
        check(hoverBlocks.size() < lockedBlocks.size(), "Live endpoints update preview geometry.");
        check(lockedBlocks.values().stream().allMatch("minecraft:glass"::equals), "Preview uses the chosen palette block.");
        check(plan.snapshot().isEmpty() && !plan.canUndo(), "Preview geometry has no plan or history side effects.");
        expect(UnsupportedOperationException.class, hoverBlocks::clear);

        equal(lockedBlocks.size(), plan.apply(locked));
        equal(lockedBlocks, plan.snapshot());
        equal(lockedBlocks.size(), plan.undo());
        check(plan.snapshot().isEmpty(), "Apply commits a single undoable edit.");

        BuildPlan source = new BuildPlan();
        source.place(start, "oak_planks");
        Clipboard clipboard = source.copy(start, hover);
        BuildPreview paste = BuildPreview.paste(anchor, clipboard);
        equal(Map.of(anchor, "minecraft:oak_planks"), paste.blocks());
        equal(1, plan.apply(paste));
        equal(paste.blocks(), plan.snapshot());

        BuildPreview huge = BuildPreview.shape(BuildPreview.Kind.BOX, start, cell(200, 200, 200), "stone");
        expect(IllegalArgumentException.class, huge::blocks);
        expect(IllegalArgumentException.class, () -> plan.apply(huge));
        equal(paste.blocks(), plan.snapshot());
    }

    private static void copyAndMirror() {
        BuildPlan plan = new BuildPlan();
        Cell a = cell(0, 1, 3), b = cell(4, 2, 7);
        plan.place(cell(1, 1, 4), "stone");
        plan.place(cell(2, 2, 6), "glass");
        Clipboard copy = plan.copy(b, a);
        equal(Map.of(new Offset(1, 0, 1), "minecraft:stone", new Offset(2, 1, 3), "minecraft:glass"), copy.blocks());
        equal(5, copy.width());
        equal(2, plan.paste(cell(8, 10, 12), copy));
        equal("minecraft:glass", plan.snapshot().get(cell(10, 11, 15)));
        equal(2, plan.mirror(a, b, Axis.X));
        equal("minecraft:stone", plan.snapshot().get(cell(3, 1, 4)));
        check(!plan.snapshot().containsKey(cell(1, 1, 4)), "Mirror moves the old position.");
        equal(4, plan.mirror(a, b, Axis.Z));
        equal("minecraft:glass", plan.snapshot().get(cell(2, 2, 4)));
        equal(2, plan.eraseBox(b, a));
        equal(2, plan.size());
        equal(2, plan.undo());
        equal(4, plan.size());
        expect(UnsupportedOperationException.class, () -> copy.blocks().clear());
        expect(IllegalArgumentException.class, () -> new Clipboard(1, 1, 1, Map.of(new Offset(1, 0, 0), "stone")));
    }

    private static void boundedAtomicEdits() {
        BuildPlan plan = new BuildPlan();
        Cell a = cell(0, 0, 0);
        plan.place(a, "stone");
        Map<Cell, String> before = plan.snapshot();
        long revision = plan.revision();
        expect(IllegalArgumentException.class, () -> plan.replace(Map.of(cell(1, 0, 0), "bad id")));
        expect(IllegalArgumentException.class, () -> plan.box(a, cell(127, 63, 63), "stone", false));
        expect(IllegalArgumentException.class, () -> plan.cylinder(cell(-29_999_999, 0, 0), cell(29_999_999, 3, 0), "glass", true));
        expect(IllegalArgumentException.class, () -> plan.line(a, cell(200_000, 0, 0), "stone"));
        Clipboard copy = new Clipboard(2, 1, 1, Map.of(new Offset(1, 0, 0), "stone"));
        expect(IllegalArgumentException.class, () -> plan.paste(cell(29_999_999, 0, 0), copy));
        expect(IllegalArgumentException.class, () -> cell(0, 2048, 0));
        same(before, plan.snapshot());
        equal(revision, plan.revision());

        equal(BuildPlan.MAX_BLOCKS - 1, plan.box(a, cell(127, 31, 31), "stone", false));
        equal(BuildPlan.MAX_BLOCKS, plan.size());
        expect(IllegalArgumentException.class, () -> plan.place(cell(128, 0, 0), "stone"));
        equal(BuildPlan.MAX_BLOCKS, plan.size());
        equal(BuildPlan.MAX_BLOCKS - 1, plan.undo());
        equal(Map.of(a, "minecraft:stone"), plan.snapshot());
    }

    private static void historyLimit() {
        BuildPlan plan = new BuildPlan();
        for (int i = 0; i < 66; i++) plan.place(cell(i, 0, 0), "stone");
        int undoCount = 0;
        while (plan.canUndo()) undoCount += plan.undo();
        equal(64, undoCount);
        equal(2, plan.size());
        int redoCount = 0;
        while (plan.canRedo()) redoCount += plan.redo();
        equal(64, redoCount);
        equal(66, plan.size());
    }

    private static void files() throws IOException {
        Path directory = Files.createTempDirectory("soprano-blueprint-check-");
        Path saved = directory.resolve("Test Build.sopplan");
        Path damaged = directory.resolve("Damaged.sopplan");
        try {
            Map<Cell, String> plan = Map.of(cell(-91, -45, 20), "minecraft:stone", cell(70, 80, -90), "soprano:demo/slab");
            BuildPlanFiles.save(directory, "Test Build", plan);
            equal(plan, BuildPlanFiles.load(directory, "Test Build"));
            BuildPlanFiles.save(directory, "Test Build", Map.of(cell(8, 12, 1), "glass"));
            equal(Map.of(cell(8, 12, 1), "minecraft:glass"), BuildPlanFiles.load(directory, "Test Build"));
            BuildPlan states = new BuildPlan();
            Cell stair = cell(1, 2, 3), secondStair = cell(1, 3, 3);
            states.place(stair, "oak_stairs[half=top,facing=north]");
            states.place(secondStair, "oak_stairs[facing=south,half=bottom]");
            equal("minecraft:oak_stairs[facing=north,half=top]", states.snapshot().get(stair));
            equal(Map.of("minecraft:oak_stairs", 2), states.materialCounts());
            equal(2, states.copy(stair, secondStair).blocks().size());
            BuildPlanFiles.save(directory, "Test Build", states.snapshot());
            equal(states.snapshot(), BuildPlanFiles.load(directory, "Test Build"));
            expect(IllegalArgumentException.class, () -> states.place(stair, "oak_stairs[facing=north,facing=south]"));
            expect(IllegalArgumentException.class, () -> BuildPlanFiles.save(directory, "../outside", plan));
            expect(IllegalArgumentException.class, () -> BuildPlanFiles.save(directory, "NUL", plan));
            Files.writeString(damaged, "SOPRANO-PLAN 1\n0 0 0 minecraft:glass\n0 0 0 minecraft:stone\n", StandardCharsets.UTF_8);
            expect(IOException.class, () -> BuildPlanFiles.load(directory, "Damaged"));
            Files.writeString(damaged, "SOPRANO-PLAN 3\n", StandardCharsets.UTF_8);
            expect(IOException.class, () -> BuildPlanFiles.load(directory, "Damaged"));
            expect(IOException.class, () -> BuildPlanFiles.load(directory, "Missing"));
            try (var paths = Files.list(directory)) {
                equal(2L, paths.count());
            }
        } finally {
            Files.deleteIfExists(saved);
            Files.deleteIfExists(damaged);
            Files.deleteIfExists(directory);
        }
    }

    private static Cell cell(int x, int y, int z) { return new Cell(x, y, z); }

    private static void equal(Object expected, Object actual) {
        check(expected.equals(actual), "Expected " + expected + ", got " + actual);
    }

    private static void same(Object expected, Object actual) {
        check(expected == actual, "Expected cached snapshot.");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, Checked task) {
        checks++;
        try {
            task.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Unexpected exception type", failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
