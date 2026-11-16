package dev.soprano.host;

import dev.soprano.game.Navigation;
import dev.soprano.path.Step;
import dev.soprano.ui.SopranoModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Projects nearby, unobstructed route marks through Minecraft's camera. */
public final class StandalonePathOverlay {
    private static final int MAX_MARKS = 48;

    private StandalonePathOverlay() { }

    public static void draw(Minecraft minecraft, GuiGraphicsExtractor graphics, SopranoModule module) {
        if (!module.showPath() || minecraft.level == null) return;
        var camera = minecraft.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        var forward = camera.forwardVector();
        int range = module.displayRange();
        int drawn = 0;
        for (Step step : Navigation.get().path()) {
            var to = step.to();
            Vec3 point = new Vec3(to.centerX(), to.y() + .12, to.centerZ());
            Vec3 relative = point.subtract(eye);
            if (relative.lengthSqr() > range * range || relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z() <= .15) continue;
            Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
            if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y) || Math.abs(projected.x) >= 1 || Math.abs(projected.y) >= 1) continue;
            if (minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, minecraft.player)).getType() != HitResult.Type.MISS) continue;
            int x = (int) ((projected.x + 1) * .5 * graphics.guiWidth());
            int y = (int) ((1 - projected.y) * .5 * graphics.guiHeight());
            int color = switch (step.move()) {
                case WALK -> 0xE66BDFB9;
                case DROP -> 0xF592B7FF;
                case CLIMB -> 0xF5D19DFF;
                case BUBBLE -> 0x65CCF5FF;
                default -> 0xF5FFC76B;
            };
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
            if (++drawn >= MAX_MARKS) break;
        }
    }
}
