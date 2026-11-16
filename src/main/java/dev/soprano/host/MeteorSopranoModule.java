package dev.soprano.host;

import com.mojang.blaze3d.platform.InputConstants;
import dev.soprano.SopranoAddon;
import dev.soprano.SopranoClient;
import dev.soprano.game.Navigation;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import dev.soprano.ui.SopranoModule;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/** The formal Meteor module delegates navigation and planner settings to shared code. */
public final class MeteorSopranoModule extends Module {
    private static final Color WALK_COLOR = new Color(107, 223, 185, 230);
    private static final Color JUMP_COLOR = new Color(255, 199, 107, 245);
    private static final Color DROP_COLOR = new Color(146, 183, 255, 245);
    private static final Color CLIMB_COLOR = new Color(209, 157, 255, 245);
    private static final Color BUBBLE_COLOR = new Color(101, 204, 245, 245);
    private static final int DRAW_LIMIT = 512;

    private final SopranoModule module;
    private final Setting<Keybind> crosshairKey = settings.createGroup("Navigation").add(new KeybindSetting.Builder()
        .name("route-to-crosshair-key").description("Start a route to the block under the in-game crosshair.")
        .defaultValue(Keybind.fromKey(InputConstants.KEY_J)).build());

    public MeteorSopranoModule(SopranoModule module) {
        super(SopranoAddon.CATEGORY, "wayclef-navigation", "Preview and walk a path through loaded terrain using ordinary movement inputs.");
        this.module = module;
        chatFeedback = false;
    }

    public boolean matchesCrosshairKey(KeyEvent input) { return crosshairKey.get().matches(input); }

    @Override public void onDeactivate() { module.onDeactivate(); }
    @Override public String getInfoString() { return module.getInfoString(); }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        var launcher = theme.button("Open Wayclef");
        launcher.action = SopranoClient::openPlanningScreen;
        return launcher;
    }

    @Override
    public Module fromTag(CompoundTag tag) {
        Map<String, String> legacy = new LinkedHashMap<>();
        for (var group : tag.getCompoundOrEmpty("settings").getListOrEmpty("groups")) {
            if (!(group instanceof CompoundTag savedGroup)) continue;
            for (var entry : savedGroup.getListOrEmpty("settings")) {
                if (!(entry instanceof CompoundTag saved)) continue;
                String name = saved.getStringOr("name", "");
                if (name.isEmpty() || name.equals("route-to-crosshair-key")) continue;
                var value = saved.get("value");
                if (value instanceof StringTag string) legacy.put(name, string.value());
                else if (value instanceof ByteTag flag) legacy.put(name, Boolean.toString(flag.byteValue() != 0));
                else if (value instanceof NumericTag number) legacy.put(name, Integer.toString(number.intValue()));
            }
        }
        if (!legacy.isEmpty()) module.importLegacySettings(legacy);
        return super.fromTag(tag);
    }

    @EventHandler
    private void drawStatus(Render2DEvent event) { module.drawStatus(event.graphics, event.screenWidth); }

    @EventHandler
    private void drawPath(Render3DEvent event) {
        if (!module.showPath() || mc.player == null || mc.gui.hud.isHidden()) return;
        Vec3 eye = mc.gameRenderer.mainCamera().position();
        int range = module.displayRange();
        int visible = 0;
        for (Step step : Navigation.get().path()) {
            Node from = step.from();
            Node to = step.to();
            if (eye.distanceToSqr(from.centerX(), from.y(), from.centerZ()) > range * range
                && eye.distanceToSqr(to.centerX(), to.y(), to.centerZ()) > range * range) continue;
            Color color = switch (step.move()) {
                case WALK -> WALK_COLOR;
                case DROP -> DROP_COLOR;
                case CLIMB -> CLIMB_COLOR;
                case BUBBLE -> BUBBLE_COLOR;
                default -> JUMP_COLOR;
            };
            double offset = step.move() == Move.WALK ? .08 : .15;
            event.depthRenderer.line(from.centerX(), from.y() + offset, from.centerZ(),
                to.centerX(), to.y() + offset, to.centerZ(), color);
            if (++visible >= DRAW_LIMIT) break;
        }
    }
}
