package dev.soprano.ui;

import dev.soprano.mixin.FontProviderAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

enum PlannerTheme {
    DARK(0xFF15191D, 0xFF111518, 0xFF1C2228, 0xFF14191E, 0xFF333E47, 0xFF252D34, 0xFF303B44,
        0xFF3D3128, 0xFF4B392C, 0xFFF9AE7A, 0xFFF5F1EC, 0xFFABB8C2, 0xFF83919D, 0xFFFFA596, 0xFF211813),
    LIGHT(0xFFF4F1EC, 0xFFE8E5E0, 0xFFFCFAF7, 0xFFF1EEE9, 0xFFCDC6BB, 0xFFE8E4DD, 0xFFDBD6CC,
        0xFFF1D9C6, 0xFFEBCBB1, 0xFF944319, 0xFF232B31, 0xFF505D65, 0xFF69767E, 0xFFA52B26, 0xFFFFF9F4);

    private static FontDescription bodyFont = FontDescription.DEFAULT;
    private static FontDescription brandFont = FontDescription.DEFAULT;
    private static FontDescription headingFont = FontDescription.DEFAULT;

    final int background, rail, panel, field, border, surface, hover, selected, selectedHover, accent, text, muted, dim, warning, onAccent;

    PlannerTheme(int background, int rail, int panel, int field, int border, int surface, int hover,
                 int selected, int selectedHover, int accent, int text, int muted, int dim, int warning, int onAccent) {
        this.background = background;
        this.rail = rail;
        this.panel = panel;
        this.field = field;
        this.border = border;
        this.surface = surface;
        this.hover = hover;
        this.selected = selected;
        this.selectedHover = selectedHover;
        this.accent = accent;
        this.text = text;
        this.muted = muted;
        this.dim = dim;
        this.warning = warning;
        this.onAccent = onAccent;
    }

    static void refreshFont(Minecraft minecraft) {
        // The Minecraft font atlas uses nearest sampling. Match the atlas raster to the GUI's actual pixel scale.
        int scale = Math.clamp(minecraft.getWindow().getGuiScale(), 1, 8);
        bodyFont = face(minecraft, scale, "body");
        brandFont = face(minecraft, scale, "brand");
        headingFont = face(minecraft, scale, "heading");
    }

    private static FontDescription face(Minecraft minecraft, int scale, String use) {
        Identifier name = Identifier.fromNamespaceAndPath("wayclef", "planner/" + scale + "/" + use);
        Identifier definition = name.withPath(path -> "font/" + path + ".json");
        return minecraft.getResourceManager().getResource(definition).isPresent()
            ? new FontDescription.Resource(name) : FontDescription.DEFAULT;
    }

    static Font fieldFont(Font vanilla) {
        Font.Provider provider = ((FontProviderAccessor) vanilla).soprano$provider();
        return new Font(new Font.Provider() {
            @Override public GlyphSource glyphs(FontDescription requested) {
                return provider.glyphs(FontDescription.DEFAULT.equals(requested) ? bodyFont : requested);
            }

            @Override public EffectGlyph effect() { return provider.effect(); }
        });
    }

    static MutableComponent lettering(String text) {
        return Component.literal(text).withStyle(style -> style.withFont(bodyFont));
    }

    static MutableComponent brand(String text) {
        return Component.literal(text).withStyle(style -> style.withFont(brandFont));
    }

    static MutableComponent heading(String text) {
        return Component.literal(text).withStyle(style -> style.withFont(headingFont));
    }

    static int width(Font font, String text) { return font.width(lettering(text)); }

    static String truncate(Font font, String value, int width) {
        if (width <= 0 || width(font, "…") > width) return "";
        if (width(font, value) <= width) return value;
        return font.substrByWidth(lettering(value), width - width(font, "…")).getString() + "…";
    }

    static void rounded(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        if (w < 7 || h < 7) {
            graphics.fill(x, y, x + w, y + h, color);
            return;
        }
        graphics.fill(x + 3, y, x + w - 3, y + 1, color);
        graphics.fill(x + 1, y + 1, x + w - 1, y + 3, color);
        graphics.fill(x, y + 3, x + w, y + h - 3, color);
        graphics.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, color);
        graphics.fill(x + 3, y + h - 1, x + w - 3, y + h, color);
    }

    static void bordered(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int fill, int stroke) {
        rounded(graphics, x, y, w, h, stroke);
        if (w > 2 && h > 2) rounded(graphics, x + 1, y + 1, w - 2, h - 2, fill);
    }
}
