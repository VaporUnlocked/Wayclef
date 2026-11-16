package dev.soprano.ui;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PlannerPreferences {
    private static final List<String> GROUPS = List.of("Navigation", "Terrain", "Planning", "Display", "Activities");
    private static final Set<String> COMMON = Set.of("Transport", "Profile", "Face path", "Show path", "Show status", "Map entities");

    private String query = "";
    private int scroll, scrollLimit;
    private int listTop, listBottom;

    void render(PlannerPage page, boolean binding, Runnable bind) {
        int x = page.x, w = page.width, y = page.y;
        var search = page.field("settings-search", "Search settings", 80, x, y, w);
        String nextQuery = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (!query.equals(nextQuery)) scroll = 0;
        query = nextQuery;
        int headerY = y + 27;
        page.panel(x, headerY, w, 48);
        int inset = x + 9, right = x + w - 9;
        page.text("Appearance", inset, headerY + 8, page.text);
        int themeW = Math.clamp((w - 88) / 2, 35, 54);
        page.button(right - 2 * themeW - 4, headerY + 4, themeW, 17, "Light", true,
            page.module.plannerTheme() == PlannerTheme.LIGHT, () -> page.module.setPlannerTheme(PlannerTheme.LIGHT));
        page.button(right - themeW, headerY + 4, themeW, 17, "Dark", true,
            page.module.plannerTheme() == PlannerTheme.DARK, () -> page.module.setPlannerTheme(PlannerTheme.DARK));
        page.text("Open planner", inset, headerY + 30, page.text);
        int toggleWidth = 50, keyWidth = Math.clamp(w - 142, 56, 104);
        page.button(right - toggleWidth - keyWidth - 4, headerY + 25, keyWidth, 18,
            binding ? "Press a key" : page.module.openKeyName(), true, binding, bind);
        page.button(right - toggleWidth, headerY + 25, toggleWidth, 18, page.module.openKeyEnabled() ? "Enabled" : "Disabled",
            page.module.hasOpenKey(), page.module.openKeyEnabled(), page.module::toggleOpenKey);
        listTop = headerY + 56;
        listBottom = y + page.height;
        if (listBottom - listTop < 10) return;
        var entries = entries(page.module);
        page.clip(x, listTop, w, listBottom - listTop);
        int rowY = listTop - scroll;
        if (entries.isEmpty()) page.text("No settings match your search.", inset, rowY + 8, page.muted);
        if (query.isEmpty()) {
            page.text("Common settings", x, rowY + 3, page.text);
            rowY += 17;
            var common = entries.stream().filter(entry -> COMMON.contains(entry.control().name())).toList();
            int columns = w >= 320 ? 3 : 2;
            int cardW = (w - (columns - 1) * 5) / columns;
            for (int i = 0; i < common.size(); i++) {
                var control = common.get(i).control();
                int cardX = x + (i % columns) * (cardW + 5), cardY = rowY + (i / columns) * 33;
                drawCommon(page, control, cardX, cardY, cardW);
            }
            rowY += (common.size() + columns - 1) / columns * 33 + 7;
        }
        String group = "";
        for (var entry : entries) {
            if (!group.equals(entry.group())) {
                group = entry.group();
                page.text(group, x, rowY + 3, page.text);
                rowY += 17;
            }
            if (rowY + 31 >= listTop && rowY < listBottom) drawRow(page, entry.control(), x, rowY, w);
            rowY += 34;
        }
        page.unclip();
        scrollLimit = Math.max(0, rowY + scroll - listBottom);
        scroll = Math.min(scroll, scrollLimit);
        if (scrollLimit > 0) {
            int height = listBottom - listTop;
            int thumb = Math.max(8, height * height / (height + scrollLimit));
            int offset = (height - thumb) * scroll / scrollLimit;
            page.graphics.fill(page.x + w + 2, listTop + offset, page.x + w + 4, listTop + offset + thumb, page.border);
        }
    }

    private record Entry(String group, PlanningSettings.Control control) { }

    private List<Entry> entries(SopranoModule module) {
        List<Entry> entries = new ArrayList<>();
        var encountered = new HashSet<String>();
        for (String group : GROUPS) {
            for (var control : module.controls(group)) {
                if (!encountered.add(control.name())) continue;
                String content = group + " " + control.name() + " " + control.description();
                if (content.toLowerCase(Locale.ROOT).contains(query)) entries.add(new Entry(group, control));
            }
        }
        return entries;
    }

    private void drawCommon(PlannerPage page, PlanningSettings.Control control, int x, int y, int w) {
        if (y + 30 < listTop || y >= listBottom) return;
        var change = control.increase();
        page.panel(x, y, w, 29);
        page.label(control.name(), x + 7, y + 5, w - 14, page.text);
        String value = control.value().get();
        page.label(value, x + 7, y + 17, w - 14, value.equals("Off") ? page.muted : page.accent);
        if (change != null) {
            if (page.over(x, y, w, 29) && page.mouseY >= listTop && page.mouseY < listBottom) {
                page.graphics.outline(x, y, w, 29, page.accent);
                tooltip(page, control);
            }
            page.hit(x, y, w, 29, change);
        }
    }

    private void drawRow(PlannerPage page, PlanningSettings.Control control, int x, int y, int w) {
        page.panel(x, y, w, 31);
        int controlsW = Math.clamp(w / 3, 75, 140), controlsX = x + w - controlsW - 6;
        page.label(control.name(), x + 8, y + 6, controlsX - x - 13, page.text);
        page.label(control.description(), x + 8, y + 18, controlsX - x - 13, page.muted);
        String value = control.value().get();
        var decrease = control.decrease();
        var increase = control.increase();
        if (increase != null && increase == decrease) {
            page.button(controlsX, y + 7, controlsW, 17, value, true, value.equals("On"), increase);
        } else if (increase == null && decrease == null) {
            page.label(value, controlsX + 4, y + 11, controlsW - 8, page.muted);
        } else {
            page.button(controlsX, y + 7, 17, 17, "−", decrease != null, false, decrease);
            page.label(value, controlsX + 21, y + 11, controlsW - 41, page.text);
            page.button(controlsX + controlsW - 17, y + 7, 17, 17, "+", increase != null, false, increase);
        }
        if (page.over(x + 7, y, controlsX - x - 10, 31) && page.mouseY >= listTop && page.mouseY < listBottom) tooltip(page, control);
    }

    private static void tooltip(PlannerPage page, PlanningSettings.Control control) {
        if (control.description() != null && !control.description().isBlank()) {
            page.graphics.setTooltipForNextFrame(Component.literal(control.description()), page.mouseX, page.mouseY);
        }
    }

    boolean scroll(double y, double amount) {
        if (y < listTop || y >= listBottom || scrollLimit == 0) return false;
        scroll = Math.clamp(scroll - (int) Math.round(amount * 28), 0, scrollLimit);
        return true;
    }
}
