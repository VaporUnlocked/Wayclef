package dev.soprano.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.ItemStack;

/** A draw pass and shared controls for one planner page. Coordinates are in GUI pixels. */
final class PlannerPage {
    final GuiGraphicsExtractor graphics;
    final Minecraft minecraft;
    final Font font;
    final SopranoModule module;
    final int x, y, width, height, mouseX, mouseY;
    final int text, muted, accent, surface, border, selected, warning, field;
    private final PlanningScreen screen;
    private final PlannerTheme theme;

    PlannerPage(PlanningScreen screen, GuiGraphicsExtractor graphics, Minecraft minecraft, SopranoModule module,
                PlannerTheme theme, int x, int y, int width, int height, int mouseX, int mouseY) {
        this.screen = screen;
        this.graphics = graphics;
        this.minecraft = minecraft;
        font = minecraft.font;
        this.module = module;
        this.theme = theme;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        text = theme.text;
        muted = theme.muted;
        accent = theme.accent;
        surface = theme.surface;
        border = theme.border;
        selected = theme.selected;
        warning = theme.warning;
        field = theme.field;
    }

    void text(String value, int x, int y, int color) { graphics.text(font, PlannerTheme.lettering(value), x, y, color, false); }

    void label(String value, int x, int y, int maxWidth, int color) {
        text(PlannerTheme.truncate(font, value, maxWidth), x, y, color);
    }

    int measure(String value) { return PlannerTheme.width(font, value); }

    void panel(int x, int y, int w, int h) { PlannerTheme.bordered(graphics, x, y, w, h, theme.panel, border); }

    void item(ItemStack stack, int x, int y) { graphics.fakeItem(stack, x, y); }

    boolean over(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    void button(int x, int y, int w, int h, String label, boolean enabled, boolean accent, Runnable action) {
        screen.pageButton(graphics, x, y, w, h, label, enabled, accent, action);
    }

    void hit(int x, int y, int w, int h, Runnable action) { screen.pageHit(x, y, w, h, action); }

    EditBox field(String key, String hint, int maxLength, int x, int y, int w) {
        return screen.pageField(graphics, key, hint, maxLength, x, y, w);
    }

    void close() { screen.closeToGame(); }
    void show(String tab) { screen.showPage(tab); }
    void run(Runnable action) { screen.run(action); }
    void clip(int x, int y, int w, int h) { screen.pageClip(graphics, x, y, w, h); }
    void unclip() { screen.pageUnclip(graphics); }
}
