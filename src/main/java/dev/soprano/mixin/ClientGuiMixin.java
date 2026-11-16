package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.host.StandalonePathOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Gui.class, remap = false)
abstract class ClientGuiMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GuiRenderState guiRenderState;
    @Shadow public abstract Screen screen();

    @Unique private Screen soprano$lastScreen;

    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void soprano$screenChanged(Screen requested, CallbackInfo info) {
        Screen actual = screen();
        if (soprano$lastScreen == actual) return;
        soprano$lastScreen = actual;
        if (SopranoClient.ready()) ClientEvents.screenChanged(actual);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V", at = @At("TAIL"))
    private void soprano$standaloneHud(DeltaTracker delta, boolean hud, boolean doUpdate, CallbackInfo info) {
        if (!hud || !SopranoClient.ready() || SopranoClient.isMeteor() || screen() != null
            || minecraft.player == null || minecraft.gui.hud.isHidden()) return;
        var graphics = new GuiGraphicsExtractor(minecraft, guiRenderState, 0, 0);
        StandalonePathOverlay.draw(minecraft, graphics, SopranoClient.module());
        SopranoClient.drawStatus(graphics);
    }
}
