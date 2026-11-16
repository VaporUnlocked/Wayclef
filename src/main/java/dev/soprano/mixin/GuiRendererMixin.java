package dev.soprano.mixin;

import dev.soprano.map.MapModelPassRenderer;
import dev.soprano.map.MapModelPassState;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final @Mutable
    private Map<Class<? extends PictureInPictureRenderState>, PictureInPictureRenderer<?>> pictureInPictureRenderers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void soprano$registerMapModelRenderer(CallbackInfo callback) {
        var renderers = new HashMap<>(pictureInPictureRenderers);
        renderers.put(MapModelPassState.class, new MapModelPassRenderer());
        pictureInPictureRenderers = Map.copyOf(renderers);
    }
}
