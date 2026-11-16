package dev.soprano.ui;

import dev.soprano.task.JobQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creative inventory browser and independent Gather and Mine baskets. */
final class PlannerCatalog {
    private static final int GAP = 8;
    private static final int CONTROL_HEIGHT = 22;
    private static final int TILE_HEIGHT = 50;
    private static final int BASKET_ROW = 24;
    private static final int CATEGORY_ROW = 19;
    private static final int MAX_QUANTITY = 32_768;
    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance();
    private static final Category ALL = new Category("All categories", Set.of());
    private static final Area EMPTY_AREA = new Area(0, 0, 0, 0);

    private record Area(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
    }

    private record Category(String label, Set<Identifier> items) { }

    private record Entry(Identifier itemId, Identifier blockId, ItemStack icon, String name, String search) {
        static Entry of(ItemStack creative) {
            var item = creative.getItem();
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            Identifier blockId = item instanceof BlockItem block ? BuiltInRegistries.BLOCK.getKey(block.getBlock()) : null;
            ItemStack icon = item.getDefaultInstance();
            String name = icon.getHoverName().getString();
            String search = (name + " " + itemId + " " + (blockId == null ? "" : blockId)).toLowerCase(Locale.ROOT);
            return new Entry(itemId, blockId, icon, name, search);
        }

        boolean exact(String query, Mode mode) {
            Identifier id = mode.id(this);
            return id != null && (query.equals(id.toString()) || query.equals(id.getPath()) || name.equalsIgnoreCase(query));
        }
    }

    private enum Mode {
        GET("Get", "item", "Search items"), MINE("Mine", "block", "Search blocks");

        private final String label, noun, searchHint;

        Mode(String label, String noun, String searchHint) {
            this.label = label;
            this.noun = noun;
            this.searchHint = searchHint;
        }

        Identifier id(Entry entry) { return this == GET ? entry.itemId() : entry.blockId(); }

        String noun(int count) { return count == 1 ? noun : noun + "s"; }
    }

    private static final class Selection {
        final Entry entry;
        String quantity = "1";

        Selection(Entry entry) { this.entry = entry; }

        int amount() {
            if (quantity.isEmpty()) return 0;
            try { return Integer.parseInt(quantity); }
            catch (NumberFormatException ignored) { return 0; }
        }
    }

    private record Filter(Mode mode, Category category, String query) { }

    private final Map<Identifier, Selection> gather = new LinkedHashMap<>();
    private final Map<Identifier, Selection> mining = new LinkedHashMap<>();
    private Mode mode = Mode.GET;
    private ClientLevel level;
    private String language;
    private boolean operatorItems;
    private Map<Identifier, Entry> entries = Map.of();
    private List<Category> categories = List.of(ALL);
    private Category category = ALL;
    private Filter filter;
    private List<Entry> filtered = List.of();
    private Area grid = EMPTY_AREA, basketList = EMPTY_AREA, categoryList = EMPTY_AREA;
    private boolean categoryOpen;
    private int gridRow, basketRow, categoryRow;
    private int maxGridRow, maxBasketRow, maxCategoryRow;
    private String notice;

    void render(PlannerPage page) {
        refresh(page.minecraft);
        int top = renderControls(page);
        int height = Math.max(0, page.y + page.height - top);
        boolean side = page.width >= 440 || height < 172 && page.width >= 266;
        Area browser;
        Area basket;
        if (side) {
            int basketWidth = page.width >= 440 ? Math.clamp(page.width * 38 / 100, 168, 250)
                : Math.clamp(page.width / 3, 132, 244);
            browser = new Area(page.x, top, page.width - basketWidth - GAP, height);
            basket = new Area(browser.right() + GAP, top, basketWidth, height);
        } else {
            int basketHeight = selected().isEmpty() ? Math.min(63, height / 3) : Math.min(141, Math.max(height / 2, Math.min(78, height - 50)));
            browser = new Area(page.x, top, page.width, Math.max(0, height - basketHeight - GAP));
            basket = new Area(page.x, browser.bottom() + GAP, page.width, basketHeight);
        }
        renderBrowser(page, browser);
        renderBasket(page, basket);
    }

    private int renderControls(PlannerPage page) {
        int x = page.x, y = page.y;
        boolean inline = page.width >= 170;
        int modeWidth = page.width >= 242 ? 51 : 35;
        page.button(x, y, modeWidth, CONTROL_HEIGHT, "Get", true, mode == Mode.GET, () -> selectMode(Mode.GET));
        page.button(x + modeWidth + 3, y, modeWidth, CONTROL_HEIGHT, "Mine", true, mode == Mode.MINE, () -> selectMode(Mode.MINE));
        int searchX = inline ? x + modeWidth * 2 + 11 : x;
        int searchY = inline ? y + 1 : y + CONTROL_HEIGHT + 5;
        int searchWidth = Math.max(30, page.width - (searchX - x));
        var search = page.field("catalog.search", mode.searchHint, 96, searchX, searchY, searchWidth);
        var nextFilter = new Filter(mode, category, search.getValue().strip().toLowerCase(Locale.ROOT));
        if (!nextFilter.equals(filter)) {
            filter = nextFilter;
            List<String> words = filter.query().isEmpty() ? List.of() : List.of(filter.query().split("\\s+"));
            Set<Identifier> members = category.items();
            var matches = entries.values().stream().filter(entry -> mode.id(entry) != null)
                .filter(entry -> category == ALL || members.contains(entry.itemId()))
                .filter(entry -> words.stream().allMatch(entry.search()::contains));
            filtered = (words.isEmpty() ? matches
                : matches.sorted(Comparator.comparing(entry -> !entry.exact(filter.query(), mode)))).toList();
            gridRow = 0;
        }
        return searchY + CONTROL_HEIGHT + GAP;
    }

    private void renderBrowser(PlannerPage page, Area area) {
        int headerX = area.x() + GAP, headerY = area.y() + 6;
        int categoryWidth = Math.min(138, area.width() - GAP * 2);
        int viewportTop = headerY + 26;
        grid = new Area(headerX, viewportTop, Math.max(0, area.width() - GAP * 2), Math.max(0, area.bottom() - GAP - viewportTop));
        categoryList = EMPTY_AREA;
        if (area.height() < 30 || categoryWidth <= 10) return;
        page.panel(area.x(), area.y(), area.width(), area.height());
        String categoryLabel = PlannerTheme.truncate(page.font, category.label(), Math.max(10, categoryWidth - 19));
        page.button(headerX, headerY, categoryWidth, CONTROL_HEIGHT, categoryLabel + (categoryOpen ? "  ▴" : "  ▾"),
            categories.size() > 1, false, () -> {
                categoryOpen = !categoryOpen;
                categoryRow = 0;
            });
        String count = NUMBERS.format(filtered.size()) + " " + mode.noun(filtered.size());
        int countWidth = page.measure(count);
        if (headerX + categoryWidth + 8 <= area.right() - GAP - countWidth) {
            page.text(count, area.right() - GAP - countWidth, headerY + 7, page.muted);
        }
        if (grid.height() >= 12) renderGrid(page);
        if (categoryOpen) renderCategories(page, headerX, viewportTop, Math.min(155, grid.width()), grid.height());
    }

    private void renderGrid(PlannerPage page) {
        if (filtered.isEmpty()) {
            maxGridRow = 0;
            String empty = level == null ? "Join a world to browse items."
                : entries.isEmpty() ? "The creative catalog is empty." : "No matching " + mode.noun(0) + ".";
            page.label(empty, grid.x() + 5, grid.y() + 9, grid.width() - 10, page.muted);
            return;
        }
        int columns = Math.max(1, grid.width() / 64);
        int cellWidth = Math.max(1, (grid.width() - 5) / columns);
        int visibleRows = Math.max(1, grid.height() / TILE_HEIGHT);
        int rows = Math.ceilDiv(filtered.size(), columns);
        maxGridRow = Math.max(0, rows - visibleRows);
        gridRow = Math.clamp(gridRow, 0, maxGridRow);
        int first = gridRow * columns;
        int count = Math.min(filtered.size() - first, visibleRows * columns);
        var graphics = page.graphics;
        graphics.enableScissor(grid.x(), grid.y(), grid.right(), grid.bottom());
        try {
            for (int index = 0; index < count; index++) {
                int x = grid.x() + index % columns * cellWidth;
                int y = grid.y() + index / columns * TILE_HEIGHT;
                renderTile(page, filtered.get(first + index), x, y, Math.max(8, cellWidth - 4));
            }
        } finally {
            graphics.disableScissor();
        }
        scrollbar(page, grid, gridRow, maxGridRow, visibleRows);
    }

    private void renderTile(PlannerPage page, Entry entry, int x, int y, int width) {
        Identifier id = mode.id(entry);
        Selection held = selected().get(id);
        int height = Math.min(TILE_HEIGHT - 4, grid.bottom() - y);
        boolean hovered = !categoryOpen && page.over(x, y, width, height);
        int color = held != null ? page.selected : hovered ? page.surface : page.field;
        PlannerTheme.bordered(page.graphics, x, y, width, height, color, held == null ? page.border : page.accent);
        int iconX = x + (width - 16) / 2;
        if (held != null) {
            int amount = held.amount();
            boolean valid = amount >= 1 && amount <= MAX_QUANTITY;
            String marker = valid ? Integer.toString(amount) : "!";
            int markerWidth = page.measure(marker) + 6;
            int markerX = x + width - markerWidth - 4;
            int markerColor = valid ? page.accent : page.warning;
            PlannerTheme.rounded(page.graphics, markerX, y + 4, markerWidth, 12, page.field);
            page.text(marker, markerX + 3, y + 6, markerColor);
            iconX = Math.min(iconX, Math.max(x + 4, markerX - 18));
        }
        page.item(entry.icon(), iconX, y + 5);
        renderTileTitle(page, entry.name(), x, y, width, height);
        if (!categoryOpen) page.hit(x, y, width, height, () -> add(entry));
        if (hovered) tooltip(page, entry, id);
    }

    private static void renderTileTitle(PlannerPage page, String name, int x, int y, int width, int height) {
        int available = Math.max(1, width - 8);
        if (height < 42 || page.measure(name) <= available) {
            String title = PlannerTheme.truncate(page.font, name, available);
            int baseline = height < 42 ? y + 23 : y + 29;
            page.text(title, x + (width - page.measure(title)) / 2, baseline, page.text);
            return;
        }
        var lines = page.font.getSplitter().splitLines(PlannerTheme.lettering(name), available, Style.EMPTY);
        if (lines.isEmpty()) return;
        String first = PlannerTheme.truncate(page.font, lines.getFirst().getString(), available);
        page.text(first, x + (width - page.measure(first)) / 2, y + 24, page.text);
        if (lines.size() >= 2) {
            String second = lines.get(1).getString() + (lines.size() > 2 ? "…" : "");
            second = PlannerTheme.truncate(page.font, second, available);
            page.text(second, x + (width - page.measure(second)) / 2, y + 34, page.text);
        }
    }

    private void renderCategories(PlannerPage page, int x, int y, int width, int space) {
        List<Category> visible = categories.stream()
            .filter(value -> value == ALL || mode == Mode.GET || value.items().stream().anyMatch(id -> entries.get(id).blockId() != null))
            .toList();
        int rows = Math.min(visible.size(), Math.max(0, (space - 6) / CATEGORY_ROW));
        if (rows == 0 || width < 40) return;
        maxCategoryRow = Math.max(0, visible.size() - rows);
        categoryRow = Math.clamp(categoryRow, 0, maxCategoryRow);
        categoryList = new Area(x, y, width, rows * CATEGORY_ROW + 5);
        page.panel(x, y, width, categoryList.height());
        page.graphics.outline(x, y, width, categoryList.height(), page.border);
        for (int index = 0; index < rows; index++) {
            Category option = visible.get(categoryRow + index);
            String label = PlannerTheme.truncate(page.font, option.label(), Math.max(10, width - 17));
            page.button(x + 3, y + 3 + index * CATEGORY_ROW, width - 9, CATEGORY_ROW - 2, label, true, option == category, () -> {
                category = option;
                categoryOpen = false;
            });
        }
        scrollbar(page, categoryList, categoryRow, maxCategoryRow, rows);
    }

    private void renderBasket(PlannerPage page, Area area) {
        var selections = selected();
        basketList = EMPTY_AREA;
        if (area.height() < 31) return;
        page.panel(area.x(), area.y(), area.width(), area.height());
        page.text("Basket", area.x() + GAP, area.y() + 10, page.text);
        page.button(area.right() - GAP - 44, area.y() + 5, 44, 18, "Clear", !selections.isEmpty(), false, () -> {
            selections.clear();
            basketRow = 0;
            notice = null;
        });
        int footer = area.bottom() - CONTROL_HEIGHT - 4;
        int listTop = area.y() + 24;
        basketList = new Area(area.x() + GAP, listTop, Math.max(0, area.width() - GAP * 2), Math.max(0, footer - 3 - listTop));
        int shownRows = basketList.height() / BASKET_ROW;
        maxBasketRow = Math.max(0, selections.size() - shownRows);
        basketRow = Math.clamp(basketRow, 0, maxBasketRow);
        if (selections.isEmpty()) {
            if (basketList.height() >= 14) page.label("Select " + mode.noun(0) + " to add them.", basketList.x() + 3,
                basketList.y() + 5, basketList.width() - 4, page.muted);
        } else {
            int index = 0;
            int drawn = 0;
            for (Selection selection : selections.values()) {
                if (index++ < basketRow) continue;
                if (drawn >= shownRows) break;
                renderSelection(page, selection, basketList.x(), basketList.y() + drawn * BASKET_ROW, basketList.width() - 7);
                drawn++;
            }
            scrollbar(page, basketList, basketRow, maxBasketRow, Math.max(1, shownRows));
        }
        renderBasketFooter(page, area, footer);
    }

    private void renderSelection(PlannerPage page, Selection selection, int x, int y, int width) {
        Identifier id = mode.id(selection.entry);
        page.graphics.fill(x, y, x + width, y + BASKET_ROW - 2, page.field);
        page.item(selection.entry.icon(), x + 4, y + 5);
        int amountWidth = 38;
        int amountX = x + width - amountWidth - 22;
        int labelWidth = Math.max(0, amountX - x - 29);
        if (labelWidth > 7) {
            page.label(selection.entry.name(), x + 25, y + (labelWidth > 77 ? 4 : 9), labelWidth, page.text);
            if (labelWidth > 77) page.label(id.toString(), x + 25, y + 15, labelWidth, page.muted);
        }
        if (page.over(x, y, Math.max(0, amountX - x), BASKET_ROW - 2)) tooltip(page, selection.entry, id);
        if (amountX <= x + 19) return;
        var field = page.field("catalog.quantity." + mode.name() + "." + id, "Qty", 5, amountX, y + 2, amountWidth);
        if (!field.getValue().equals(selection.quantity)) {
            field.setResponder(value -> { });
            field.setValue(selection.quantity);
        }
        field.setResponder(value -> {
            selection.quantity = value;
            notice = null;
        });
        page.button(x + width - 18, y + 3, 16, 18, "×", true, false, () -> {
            selected().remove(id);
            notice = null;
        });
    }

    private void renderBasketFooter(PlannerPage page, Area area, int y) {
        var selections = selected();
        int total = selections.values().stream().mapToInt(Selection::amount).sum();
        boolean invalid = selections.values().stream().anyMatch(entry -> entry.amount() < 1 || entry.amount() > MAX_QUANTITY);
        String action = total == 0 ? mode.label : mode.label + " " + NUMBERS.format(total);
        int actionWidth = Math.min(area.width() - GAP * 2, Math.max(73, page.measure(action) + 14));
        int actionX = area.right() - GAP - actionWidth;
        var detail = invalid ? "Use 1–" + NUMBERS.format(MAX_QUANTITY) : notice != null ? notice
            : selections.isEmpty() ? "" : selections.size() + (selections.size() == 1 ? " target" : " targets");
        int detailWidth = actionX - area.x() - GAP * 2;
        if (!detail.isEmpty() && detailWidth > 10) {
            page.label(detail, area.x() + GAP, y + 7, detailWidth,
                invalid || notice != null ? page.warning : page.muted);
        }
        page.button(actionX, y, actionWidth, CONTROL_HEIGHT, action, !selections.isEmpty() && !invalid,
            true, () -> submit(page));
    }

    private void submit(PlannerPage page) {
        List<String> commands = new ArrayList<>();
        for (Selection selection : selected().values()) {
            Identifier id = mode.id(selection.entry);
            String amount = Integer.toString(selection.amount());
            commands.add(mode == Mode.GET ? "gather " + id + " " + amount
                : "mine " + id + " " + amount + " " + AutomationControls.NEARBY_RADIUS);
        }
        page.module.startJobs(commands);
        selected().clear();
        basketRow = 0;
        page.close();
    }

    private Map<Identifier, Selection> selected() { return mode == Mode.GET ? gather : mining; }

    private void add(Entry entry) {
        var selections = selected();
        Identifier id = mode.id(entry);
        Selection previous = selections.get(id);
        if (previous != null) previous.quantity = Integer.toString(Math.min(MAX_QUANTITY, previous.amount() + 1));
        else if (selections.size() < JobQueue.MAX_STEPS) selections.put(id, new Selection(entry));
        else {
            notice = "Max " + JobQueue.MAX_STEPS + " targets";
            return;
        }
        notice = null;
    }

    private void selectMode(Mode next) {
        if (mode == next) return;
        mode = next;
        category = ALL;
        categoryOpen = false;
        basketRow = 0;
        notice = null;
    }

    private static void tooltip(PlannerPage page, Entry entry, Identifier id) {
        page.graphics.setComponentTooltipForNextFrame(page.font,
            List.of(Component.literal(entry.name()), Component.literal(id.toString())), page.mouseX, page.mouseY);
    }

    boolean scroll(double x, double y, double amount) {
        if (amount == 0) return false;
        int step = amount > 0 ? -1 : 1;
        if (categoryOpen && categoryList.contains(x, y)) categoryRow = Math.clamp(categoryRow + step, 0, maxCategoryRow);
        else if (basketList.contains(x, y)) basketRow = Math.clamp(basketRow + step, 0, maxBasketRow);
        else if (grid.contains(x, y)) gridRow = Math.clamp(gridRow + step, 0, maxGridRow);
        else return false;
        return true;
    }

    private static void scrollbar(PlannerPage page, Area area, int offset, int maximum, int visibleRows) {
        if (maximum <= 0 || area.height() <= 7) return;
        int thumb = Math.clamp(area.height() * visibleRows / (maximum + visibleRows), 6, area.height());
        int top = area.y() + (area.height() - thumb) * offset / maximum;
        page.graphics.fill(area.right() - 3, area.y(), area.right() - 1, area.bottom(), page.border);
        page.graphics.fill(area.right() - 3, top, area.right() - 1, top + thumb, page.muted);
    }

    private void refresh(Minecraft minecraft) {
        String selectedLanguage = minecraft.getLanguageManager().getSelected();
        boolean allowOperatorItems = minecraft.player != null && minecraft.player.canUseGameMasterBlocks()
            && minecraft.options.operatorItemsTab().get();
        if (level == minecraft.level && Objects.equals(language, selectedLanguage) && operatorItems == allowOperatorItems) return;
        if (level != minecraft.level) {
            gather.clear();
            mining.clear();
        }
        level = minecraft.level;
        language = selectedLanguage;
        operatorItems = allowOperatorItems;
        filter = null;
        category = ALL;
        categoryOpen = false;
        entries = Map.of();
        categories = List.of(ALL);
        if (level == null) return;

        var registries = level.registryAccess();
        var connection = minecraft.getConnection();
        if (CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), operatorItems, registries) && connection != null) {
            // Match vanilla inventory initialization when this screen is the first creative-catalog consumer.
            var search = List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
            connection.searchTrees().updateCreativeTooltips(registries, search);
            connection.searchTrees().updateCreativeTags(search);
        }
        var index = new LinkedHashMap<Identifier, Entry>();
        var groups = new ArrayList<Category>();
        groups.add(ALL);
        for (CreativeModeTab tab : CreativeModeTabs.tabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            Set<Identifier> members = addItems(index, tab.getDisplayItems());
            if (!members.isEmpty()) groups.add(new Category(tab.getDisplayName().getString(), members));
        }
        Set<Identifier> categorized = Set.copyOf(index.keySet());
        Set<Identifier> uncategorized = new HashSet<>(addItems(index, CreativeModeTabs.searchTab().getDisplayItems()));
        uncategorized.removeAll(categorized);
        if (!uncategorized.isEmpty()) groups.add(new Category("Other", Set.copyOf(uncategorized)));
        entries = Collections.unmodifiableMap(index);
        categories = List.copyOf(groups);
    }

    private static Set<Identifier> addItems(Map<Identifier, Entry> index, Iterable<ItemStack> stacks) {
        Set<Identifier> members = new HashSet<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            members.add(id);
            index.computeIfAbsent(id, ignored -> Entry.of(stack));
        }
        return Set.copyOf(members);
    }
}
