package dev.soprano.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.soprano.build.BlueprintActions;
import dev.soprano.build.BlueprintBlockStates;
import dev.soprano.build.BlueprintImport;
import dev.soprano.build.BuildLayers;
import dev.soprano.build.BuildPlan;
import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.BuildPlanFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.material.MapColor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local files and the schematic currently being placed. */
final class PlannerSchematics {
    private static final int FILE_ROW = 29;
    private static final int MATERIAL_ROW = 25;
    private static final int BUTTON = 20;
    private static final int MAX_DISCOVERED = 2_048;
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Soprano schematic library");
        thread.setDaemon(true);
        return thread;
    });

    private record FileEntry(Path path, String name, String format, long size, boolean ownPlan) { }
    private record Area(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
    }
    private record Bounds(Cell minimum, Cell maximum) {
        int width() { return maximum.x() - minimum.x() + 1; }
        int height() { return maximum.y() - minimum.y() + 1; }
        int depth() { return maximum.z() - minimum.z() + 1; }
    }
    private record Footprint(int width, int depth, int[] colors) { }

    private final BlueprintImport importer = new BlueprintImport();
    private final Map<Path, FileEntry> extraFiles = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();
    private final Map<String, ItemStack> materialIcons = new HashMap<>();
    private CompletableFuture<List<FileEntry>> scan;
    private CompletableFuture<Map<Cell, String>> savedPlan;
    private CompletableFuture<Path> openFolder;
    private Path library;
    private ClientLevel level;
    private List<FileEntry> files = List.of();
    private List<FileEntry> matching = List.of();
    private String query;
    private FileEntry selected;
    private BuildPlan preview = new BuildPlan();
    private BuildPlan lastPlan;
    private long lastRevision = -1;
    private Bounds bounds;
    private Footprint footprint;
    private Map<String, Integer> requirements = Map.of();
    private Map<String, BlueprintActions.MaterialCount> counts = Map.of();
    private List<String> materialIds = List.of();
    private long nextCount;
    private int unavailable;
    private String failure;
    private Area fileArea, materialArea;
    private int fileScroll, materialScroll, fileScrollLimit, materialScrollLimit;

    void tick(Minecraft minecraft) {
        if (level != minecraft.level) {
            resetPreview();
            level = minecraft.level;
        }
        if (scan != null && scan.isDone()) {
            var finished = scan;
            scan = null;
            report(() -> {
                files = finished.join();
                query = null;
            });
        }
        if (savedPlan != null && savedPlan.isDone()) {
            var finished = savedPlan;
            savedPlan = null;
            report(() -> preview.replace(finished.join()));
        }
        if (openFolder != null && openFolder.isDone()) {
            var finished = openFolder;
            openFolder = null;
            report(() -> Blaze3D.openPath(finished.join()));
        }
        if (importer.pending()) report(() -> {
            var loaded = importer.poll(level, preview);
            if (loaded != null) unavailable = loaded.unavailable();
        });
    }

    void render(PlannerPage page) {
        initialize(page.minecraft);
        int x = page.x, y = page.y, width = page.width;
        int toolbarWidth = width < 300 ? 119 : 143;
        int fieldWidth = Math.max(28, width - toolbarWidth - 6);
        var path = page.field("schematic-path", "Schematic path or drop file", 2_048, x, y, fieldWidth);
        int buttonX = x + fieldWidth + 6;
        int importWidth = width < 300 ? 38 : 44;
        int folderWidth = width < 300 ? 34 : 48;
        page.button(buttonX, y, importWidth, BUTTON, "Import", !path.getValue().isBlank() && page.minecraft.player != null,
            true, () -> report(() -> importPath(page.minecraft, path.getValue())));
        buttonX += importWidth + 4;
        page.button(buttonX, y, folderWidth, BUTTON, "Folder", openFolder == null, false, () -> report(this::showFolder));
        buttonX += folderWidth + 4;
        page.button(buttonX, y, Math.max(28, x + width - buttonX), BUTTON, "Refresh", scan == null, false, this::refresh);

        int bodyY = y + BUTTON + 8;
        int bodyHeight = Math.max(0, page.height - BUTTON - 8);
        if (bodyHeight < 60) return;
        if (width >= 365) {
            int left = Math.clamp(width / 3, 122, 216);
            renderFiles(page, new Area(x, bodyY, left, bodyHeight));
            renderDetails(page, new Area(x + left + 8, bodyY, width - left - 8, bodyHeight));
        } else {
            int top = Math.min(124, Math.max(57, bodyHeight / 3));
            renderFiles(page, new Area(x, bodyY, width, top));
            renderDetails(page, new Area(x, bodyY + top + 6, width, bodyHeight - top - 6));
        }
    }

    private void renderFiles(PlannerPage page, Area bounds) {
        page.panel(bounds.x, bounds.y, bounds.width, bounds.height);
        var search = page.field("schematic-search", "Search schematics", 120, bounds.x + 6, bounds.y + 6, bounds.width - 12);
        String input = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (!input.equals(query)) {
            query = input;
            var combined = new HashMap<Path, FileEntry>();
            files.forEach(entry -> combined.put(entry.path, entry));
            combined.putAll(extraFiles);
            matching = combined.values().stream().filter(entry -> entry.name.toLowerCase(Locale.ROOT).contains(query)
                || entry.format.toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.path.toString()))
                .toList();
            fileScroll = 0;
        }
        int top = bounds.y + 33;
        fileArea = new Area(bounds.x + 2, top, bounds.width - 4, Math.max(0, bounds.bottom() - top - 3));
        fileScrollLimit = Math.max(0, matching.size() * FILE_ROW - fileArea.height);
        fileScroll = Math.clamp(fileScroll, 0, fileScrollLimit);
        if (matching.isEmpty()) {
            String empty = scan != null ? "Reading library…" : query.isBlank() ? "No schematics in this folder." : "No matching schematics.";
            page.label(empty, bounds.x + 9, top + 6, bounds.width - 18, page.muted);
            return;
        }
        page.clip(fileArea.x, fileArea.y, fileArea.width, fileArea.height);
        for (int index = fileScroll / FILE_ROW; index < matching.size(); index++) {
            int rowY = top + index * FILE_ROW - fileScroll;
            if (rowY >= fileArea.bottom()) break;
            FileEntry entry = matching.get(index);
            int rowTop = Math.max(rowY, fileArea.y), rowBottom = Math.min(rowY + FILE_ROW, fileArea.bottom());
            boolean active = selected != null && selected.path.equals(entry.path);
            if (active || page.over(fileArea.x, rowTop, fileArea.width, rowBottom - rowTop)) {
                page.graphics.fill(fileArea.x, rowY, fileArea.right(), rowY + FILE_ROW, active ? page.selected : page.surface);
            }
            if (active) page.graphics.fill(fileArea.x, rowY + 4, fileArea.x + 2, rowY + FILE_ROW - 4, page.accent);
            page.label(entry.name, fileArea.x + 7, rowY + 5, fileArea.width - 13, page.text);
            page.label(entry.format + " · " + sizeText(entry.size), fileArea.x + 7, rowY + 17, fileArea.width - 13, page.muted);
            page.graphics.fill(fileArea.x + 7, rowY + FILE_ROW - 1, fileArea.right() - 7, rowY + FILE_ROW, page.border);
            if (page.minecraft.player != null && rowBottom > rowTop) {
                page.hit(fileArea.x, rowTop, fileArea.width, rowBottom - rowTop, () -> report(() -> open(page.minecraft, entry)));
            }
        }
        page.unclip();
    }

    private void renderDetails(PlannerPage page, Area area) {
        materialArea = null;
        if (area.height < 32) return;
        page.panel(area.x, area.y, area.width, area.height);
        int x = area.x + 9, y = area.y + 9, width = area.width - 18;
        if (selected == null || preview.size() == 0) {
            String title = selected == null ? "Blueprint preview" : selected.name;
            page.label(title, x, y, width, page.text);
            String hint = failure != null ? failure : selected != null ? pending() ? "Reading schematic…" : "This blueprint has no blocks."
                : page.minecraft.player == null ? "Join a world to place a schematic." : "Select a schematic to inspect its materials and placement.";
            page.label(hint, x, y + 15, width, failure == null ? page.muted : page.warning);
            page.button(x, area.bottom() - 28, Math.min(110, width), BUTTON, "Build tools", true, false, () -> page.show("Build"));
            return;
        }

        collectDetails(page.minecraft);
        page.label(selected.name, x, y, width, page.text);
        String dimensions = bounds.width() + " × " + bounds.height() + " × " + bounds.depth();
        page.label(dimensions + "  ·  " + quantity(preview.size(), "block") + "  ·  " + quantity(materialIds.size(), "material"),
            x, y + 13, width, page.muted);
        Cell origin = bounds.minimum();
        page.label("Origin " + origin.x() + "  " + origin.y() + "  " + origin.z(), x, y + 25, width, page.muted);
        y += 42;
        if (area.height >= 224) {
            int reserved = width >= 235 ? 171 : 199;
            int imageHeight = Math.clamp(area.height - reserved, 26, 96);
            drawFootprint(page, new Area(x, y, width, imageHeight));
            y += imageHeight + 6;
        }
        int footer = area.bottom() - 29;
        if (y + BUTTON + 34 < footer && width >= 235) {
            int cell = width / 4;
            button(page, x, y, cell - 3, "Move to me", () -> move(feet(page.minecraft)));
            button(page, x + cell, y, cell - 3, "Rotate 90°", this::rotate);
            button(page, x + cell * 2, y, cell - 3, "Mirror X", () -> mirror(BuildPlan.Axis.X));
            button(page, x + cell * 3, y, width - cell * 3, "Mirror Z", () -> mirror(BuildPlan.Axis.Z));
            y += BUTTON + 4;
        } else if (y + BUTTON + 34 < footer) {
            int half = (width - 4) / 2;
            button(page, x, y, half, "Move to me", () -> move(feet(page.minecraft)));
            button(page, x + half + 4, y, width - half - 4, "Rotate 90°", this::rotate);
            y += BUTTON + 4;
            if (y + BUTTON + 54 < footer) {
                button(page, x, y, half, "Mirror X", () -> mirror(BuildPlan.Axis.X));
                button(page, x + half + 4, y, width - half - 4, "Mirror Z", () -> mirror(BuildPlan.Axis.Z));
                y += BUTTON + 4;
            }
        }
        if (area.height >= 224 && y + BUTTON + 58 < footer) {
            int cell = width / 6;
            String[] controls = {"X−", "X+", "Y−", "Y+", "Z−", "Z+"};
            for (int index = 0; index < controls.length; index++) {
                int axis = index / 2, amount = index % 2 == 0 ? -1 : 1;
                button(page, x + cell * index, y, cell - 3, controls[index], () -> nudge(axis, amount));
            }
            y += BUTTON + 5;
        }
        int statusY = footer - 14;
        if (y < statusY - 14) renderMaterials(page, new Area(x, y, width, statusY - y));
        if (failure != null || statusY >= y) {
            int shortfall = materialIds.stream().filter(id -> !excluded.contains(id)).mapToInt(id -> counts.get(id).missing()).sum();
            String status = failure != null ? failure : unavailable > 0 ? quantity(unavailable, "unavailable block")
                : shortfall == 0 ? "Materials ready" : quantity(shortfall, "item") + " short";
            if (!excluded.isEmpty() && failure == null) status += "  ·  " + excluded.size() + " excluded";
            page.label(status, x, Math.max(area.y + 10, statusY), width,
                failure != null || unavailable > 0 || shortfall > 0 ? page.warning : page.muted);
        }
        boolean canBuild = !pending() && page.minecraft.player != null && (excluded.isEmpty() || excluded.size() < materialIds.size());
        int half = (width - 4) / 2;
        page.button(x, footer, half, BUTTON, "Edit on map", canBuild, false, () -> report(() -> {
            preview = publish(page.minecraft, buildPlan());
            excluded.clear();
            failure = null;
            page.show("Build");
        }));
        page.button(x + half + 4, footer, width - half - 4, BUTTON, "Build here", canBuild, true, () -> report(() -> {
            BuildPlan plan = buildPlan();
            page.module.startConstruction(plan, BuildLayers.Direction.BOTTOM_UP, BuildPlan.MAX_BLOCKS * 2, 0);
            publish(page.minecraft, plan);
            page.close();
        }));
    }

    private static String quantity(int amount, String noun) { return amount + " " + noun + (amount == 1 ? "" : "s"); }

    private void button(PlannerPage page, int x, int y, int width, String label, Runnable run) {
        page.button(x, y, width, BUTTON, label, !pending() && page.minecraft.player != null, false, () -> report(run));
    }

    private void renderMaterials(PlannerPage page, Area area) {
        page.label("Materials", area.x, area.y + 2, area.width / 2, page.text);
        String tally = "On hand / needed";
        page.label(tally, area.right() - page.measure(tally), area.y + 2, area.width / 2, page.muted);
        materialArea = new Area(area.x, area.y + 14, area.width, Math.max(0, area.height - 14));
        materialScrollLimit = Math.max(0, materialIds.size() * MATERIAL_ROW - materialArea.height);
        materialScroll = Math.clamp(materialScroll, 0, materialScrollLimit);
        page.clip(materialArea.x, materialArea.y, materialArea.width, materialArea.height);
        for (int index = materialScroll / MATERIAL_ROW; index < materialIds.size(); index++) {
            int rowY = materialArea.y + index * MATERIAL_ROW - materialScroll;
            if (rowY >= materialArea.bottom()) break;
            int rowTop = Math.max(rowY, materialArea.y), rowBottom = Math.min(rowY + MATERIAL_ROW, materialArea.bottom());
            var graphics = page.graphics;
            String id = materialIds.get(index);
            boolean checked = !excluded.contains(id);
            if (page.over(area.x, rowTop, area.width, rowBottom - rowTop)) {
                graphics.fill(area.x, rowY, area.right(), rowY + MATERIAL_ROW, page.surface);
            }
            graphics.fill(area.x, rowY + 7, area.x + 11, rowY + 18, checked ? page.selected : page.field);
            graphics.outline(area.x, rowY + 7, 11, 11, checked ? page.accent : page.border);
            if (checked) graphics.fill(area.x + 3, rowY + 10, area.x + 8, rowY + 15, page.accent);
            var stack = materialIcons.computeIfAbsent(id, name -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(name))
                .map(Item::getDefaultInstance).orElse(ItemStack.EMPTY));
            if (!stack.isEmpty()) page.item(stack, area.x + 17, rowY + 4);
            String name = stack.isEmpty() ? id : stack.getHoverName().getString();
            var count = counts.get(id);
            String amount = count.inventory() + " / " + count.required();
            int amountWidth = page.measure(amount);
            page.label(name, area.x + 37, rowY + 8, area.width - amountWidth - 43, checked ? page.text : page.muted);
            page.text(amount, area.right() - amountWidth - 2, rowY + 8,
                checked && count.inventory() < count.required() ? page.warning : page.muted);
            graphics.fill(area.x, rowY + MATERIAL_ROW - 1, area.right(), rowY + MATERIAL_ROW, page.border);
            page.hit(area.x, rowTop, area.width, rowBottom - rowTop, () -> {
                if (!excluded.add(id)) excluded.remove(id);
                if (preview.size() > 0 && bounds != null) footprint = footprint(preview.snapshot(), bounds, excluded);
                failure = null;
            });
        }
        page.unclip();
    }

    private void collectDetails(Minecraft minecraft) {
        if (lastPlan != preview || lastRevision != preview.revision()) {
            lastPlan = preview;
            lastRevision = preview.revision();
            Map<Cell, String> blocks = preview.snapshot();
            bounds = bounds(blocks);
            requirements = BlueprintActions.requirements(preview);
            materialIds = requirements.keySet().stream().sorted(Comparator.<String>comparingInt(requirements::get).reversed()
                .thenComparing(Comparator.naturalOrder())).toList();
            excluded.retainAll(requirements.keySet());
            footprint = footprint(blocks, bounds, excluded);
            nextCount = 0;
        }
        long now = System.nanoTime();
        if (now >= nextCount) {
            counts = BlueprintActions.materials(minecraft.player, requirements, Map.of());
            nextCount = now + 250_000_000L;
        }
    }

    private void drawFootprint(PlannerPage page, Area area) {
        var graphics = page.graphics;
        graphics.fill(area.x, area.y, area.right(), area.bottom(), page.field);
        for (int line = area.x + 8; line < area.right(); line += 8) graphics.fill(line, area.y, line + 1, area.bottom(), page.border);
        for (int line = area.y + 8; line < area.bottom(); line += 8) graphics.fill(area.x, line, area.right(), line + 1, page.border);
        double cell = Math.min((area.width - 16.0) / footprint.width, (area.height - 10.0) / footprint.depth);
        if (cell <= 0) return;
        int left = area.x + (int) ((area.width - footprint.width * cell) / 2);
        int top = area.y + (int) ((area.height - footprint.depth * cell) / 2);
        for (int z = 0; z < footprint.depth; z++) {
            int row = z * footprint.width;
            int y1 = top + (int) Math.floor(z * cell), y2 = top + (int) Math.floor((z + 1) * cell);
            if (y2 <= y1) continue;
            for (int x = 0; x < footprint.width;) {
                int color = footprint.colors[row + x], start = x++;
                while (x < footprint.width && footprint.colors[row + x] == color) x++;
                int x1 = left + (int) Math.floor(start * cell), x2 = left + (int) Math.floor(x * cell);
                if (color != 0 && x2 > x1) graphics.fill(x1, y1, x2, y2, color);
            }
        }
        graphics.outline(area.x, area.y, area.width, area.height, page.border);
    }

    private static Bounds bounds(Map<Cell, String> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell cell : blocks.keySet()) {
            minX = Math.min(minX, cell.x()); minY = Math.min(minY, cell.y()); minZ = Math.min(minZ, cell.z());
            maxX = Math.max(maxX, cell.x()); maxY = Math.max(maxY, cell.y()); maxZ = Math.max(maxZ, cell.z());
        }
        return new Bounds(new Cell(minX, minY, minZ), new Cell(maxX, maxY, maxZ));
    }

    private static Footprint footprint(Map<Cell, String> blocks, Bounds bounds, Set<String> excluded) {
        double scale = Math.min(1, Math.min(64.0 / bounds.width(), 40.0 / bounds.depth()));
        int width = Math.max(1, (int) Math.ceil(bounds.width() * scale));
        int depth = Math.max(1, (int) Math.ceil(bounds.depth() * scale));
        int[] colors = new int[width * depth], heights = new int[colors.length];
        Arrays.fill(heights, Integer.MIN_VALUE);
        Map<String, Integer> palette = new HashMap<>();
        Map<String, String> materials = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            if (!excluded.isEmpty() && excluded.contains(materials.computeIfAbsent(entry.getValue(), BlueprintActions::materialId))) continue;
            Cell cell = entry.getKey();
            int x = Math.min(width - 1, (int) ((cell.x() - bounds.minimum.x()) * scale));
            int z = Math.min(depth - 1, (int) ((cell.z() - bounds.minimum.z()) * scale));
            int index = z * width + x;
            if (cell.y() < heights[index]) continue;
            heights[index] = cell.y();
            colors[index] = palette.computeIfAbsent(entry.getValue(), PlannerSchematics::color);
        }
        return new Footprint(width, depth, colors);
    }

    private static int color(String block) {
        var state = BlueprintBlockStates.resolve(block);
        MapColor color = state == null ? MapColor.NONE : state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return color == MapColor.NONE ? 0xff7d8ba0 : color.calculateARGBColor(MapColor.Brightness.NORMAL);
    }

    boolean scroll(double x, double y, double amount) {
        if (fileArea != null && fileArea.contains(x, y)) {
            fileScroll = Math.clamp(fileScroll - (int) Math.round(amount * FILE_ROW), 0, fileScrollLimit);
            return true;
        }
        if (materialArea != null && materialArea.contains(x, y)) {
            materialScroll = Math.clamp(materialScroll - (int) Math.round(amount * MATERIAL_ROW), 0, materialScrollLimit);
            return true;
        }
        return false;
    }

    void filesDropped(Minecraft minecraft, List<Path> paths) {
        initialize(minecraft);
        report(() -> {
            Path source = paths.stream().filter(PlannerSchematics::supported).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Drop a schematic or a Wayclef blueprint."));
            useSource(minecraft, source.toAbsolutePath().normalize());
        });
    }

    void close() {
        resetPreview();
        if (scan != null) scan.cancel(true);
        scan = null;
    }

    private void initialize(Minecraft minecraft) {
        if (library != null) return;
        library = minecraft.gameDirectory.toPath().resolve("schematics").toAbsolutePath().normalize();
        refresh();
    }

    private void resetPreview() {
        importer.cancel();
        if (savedPlan != null) savedPlan.cancel(true);
        savedPlan = null;
        selected = null;
        preview = new BuildPlan();
        excluded.clear();
        materialIcons.clear();
        failure = null;
        unavailable = 0;
        materialScroll = 0;
    }

    private boolean pending() { return importer.pending() || savedPlan != null; }

    private void open(Minecraft minecraft, FileEntry entry) {
        if (minecraft.player == null || minecraft.level == null) throw new IllegalStateException("Join a world first.");
        if (selected != null && selected.path.equals(entry.path) && (pending() || preview.size() > 0)) return;
        resetPreview();
        level = minecraft.level;
        selected = entry;
        if (entry.ownPlan) {
            String filename = entry.path.getFileName().toString();
            String name = filename.substring(0, filename.length() - ".sopplan".length());
            savedPlan = onDisk(() -> BuildPlanFiles.load(entry.path.getParent(), name));
        } else {
            importer.start(entry.path, feet(minecraft), level, preview);
        }
    }

    private void importPath(Minecraft minecraft, String input) {
        String clean = input.strip();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) clean = clean.substring(1, clean.length() - 1);
        Path source = library.resolve(clean).normalize();
        if (!Files.isRegularFile(source)) source = minecraft.gameDirectory.toPath().resolve(clean).normalize();
        useSource(minecraft, source.toAbsolutePath().normalize());
    }

    private void useSource(Minecraft minecraft, Path source) {
        if (!supported(source) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Choose a .schem, .schematic, .litematic, or .sopplan file.");
        }
        try {
            FileEntry entry = file(source, library);
            extraFiles.put(source, entry);
            query = null;
            open(minecraft, entry);
        } catch (IOException error) {
            throw new IllegalStateException("Could not read the selected file.", error);
        }
    }

    private void refresh() {
        if (scan != null || library == null) return;
        scan = onDisk(() -> {
            var entries = new ArrayList<FileEntry>();
            discover(library, false, entries);
            discover(library.resolveSibling("soprano").resolve("build-plans"), true, entries);
            return List.copyOf(entries);
        });
    }

    private static void discover(Path directory, boolean saved, List<FileEntry> entries) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.walk(directory, saved ? 1 : 12)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(PlannerSchematics::supported)
                .limit(Math.max(0, MAX_DISCOVERED - entries.size())).toList()) {
                try { entries.add(file(path, directory)); }
                catch (IOException changed) { /* The file disappeared during the scan. */ }
            }
        }
    }

    private static FileEntry file(Path source, Path root) throws IOException {
        String name = source.startsWith(root) ? root.relativize(source).toString().replace('\\', '/') : source.getFileName().toString();
        String format = format(source);
        return new FileEntry(source, name, format, Files.size(source), format.equals("Wayclef plan"));
    }

    private static boolean supported(Path path) { return format(path) != null; }

    private static String format(Path path) {
        if (path.getFileName() == null) return null;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".schematic")) return "MCEdit";
        if (name.endsWith(".schem")) return "Sponge";
        if (name.endsWith(".litematic")) return "Litematica";
        if (name.endsWith(".sopplan")) return "Wayclef plan";
        return null;
    }

    private void showFolder() {
        openFolder = onDisk(() -> Files.createDirectories(library));
    }

    @FunctionalInterface
    private interface DiskTask<T> { T get() throws IOException; }

    private static <T> CompletableFuture<T> onDisk(DiskTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try { return task.get(); }
            catch (IOException error) { throw new CompletionException(error); }
        }, DISK);
    }

    private void report(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | CompletionException failure) {
            Throwable reason = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
            this.failure = reason.getMessage() == null ? "Could not read the schematic." : reason.getMessage();
        }
    }

    private void move(Cell origin) { BlueprintActions.align(preview, origin); failure = null; }

    private void nudge(int axis, int amount) {
        Cell origin = bounds.minimum;
        move(new Cell(origin.x() + (axis == 0 ? amount : 0), origin.y() + (axis == 1 ? amount : 0), origin.z() + (axis == 2 ? amount : 0)));
    }

    private void rotate() { BlueprintActions.rotate(preview, bounds.minimum, bounds.maximum); failure = null; }

    private void mirror(BuildPlan.Axis axis) { BlueprintActions.mirror(preview, bounds.minimum, bounds.maximum, axis); failure = null; }

    private BuildPlan buildPlan() {
        if (excluded.isEmpty()) return preview;
        Map<String, String> materials = new HashMap<>();
        Map<Cell, String> blocks = new HashMap<>();
        preview.snapshot().forEach((cell, block) -> {
            String material = materials.computeIfAbsent(block, BlueprintActions::materialId);
            if (!excluded.contains(material)) blocks.put(cell, block);
        });
        BuildPlan plan = new BuildPlan();
        plan.replace(blocks);
        return plan;
    }

    private static BuildPlan publish(Minecraft minecraft, BuildPlan plan) {
        BuildPlan world = PlanningViewport.worldBlueprint(minecraft.level);
        if (world != plan) world.replace(plan.snapshot());
        return world;
    }

    private static Cell feet(Minecraft minecraft) {
        BlockPos pos = minecraft.player.blockPosition();
        return new Cell(pos.getX(), pos.getY(), pos.getZ());
    }

    private static String sizeText(long size) {
        return size < 1_024 ? size + " B" : Math.round(size / 1_024.0) + " KB";
    }
}
