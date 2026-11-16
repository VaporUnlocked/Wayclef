package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
abstract class ClientRuntimeMixin {
    @Inject(method = "tick()V", at = @At("HEAD"))
    private void soprano$beforeTick(CallbackInfo info) { SopranoClient.beforeTick(); }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void soprano$afterTick(CallbackInfo info) { SopranoClient.afterTick(); }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void soprano$disconnect(Screen progress, boolean switchingWorld, boolean keepConnection, CallbackInfo info) {
        if (SopranoClient.ready()) SopranoClient.disconnected();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("TAIL"))
    private void soprano$worldReleased(Screen progress, boolean switchingWorld, boolean keepConnection, CallbackInfo info) {
        if (SopranoClient.ready()) SopranoClient.releaseRetainedSurvey();
    }

    @Inject(method = "close()V", at = @At("HEAD"))
    private void soprano$beforeClose(CallbackInfo info) {
        if (SopranoClient.ready()) SopranoClient.close();
    }
}
