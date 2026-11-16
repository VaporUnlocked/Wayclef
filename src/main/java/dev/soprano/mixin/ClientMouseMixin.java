package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, remap = false)
abstract class ClientMouseMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void soprano$standaloneClick(long window, MouseButtonInfo input, int action, CallbackInfo info) {
        if (SopranoClient.ready() && !SopranoClient.isMeteor() && window == Minecraft.getInstance().getWindow().handle()) {
            ClientEvents.mouseClick(ClientEvents.Action.fromGlfw(action));
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"))
    private void soprano$standaloneScroll(long window, double horizontal, double vertical, CallbackInfo info) {
        if (SopranoClient.ready() && !SopranoClient.isMeteor() && window == Minecraft.getInstance().getWindow().handle()) {
            ClientEvents.mouseScroll();
        }
    }
}
