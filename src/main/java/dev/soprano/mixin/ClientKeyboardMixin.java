package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardHandler.class, priority = 1100, remap = false)
abstract class ClientKeyboardMixin {
    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void soprano$key(long window, int action, KeyEvent event, CallbackInfo info) {
        if (!SopranoClient.ready() || window != Minecraft.getInstance().getWindow().handle()) return;
        var input = ClientEvents.Action.fromGlfw(action);
        if (SopranoClient.stopKey(event, input)) {
            info.cancel();
            return;
        }
        if (SopranoClient.isMeteor()) return;
        if (SopranoClient.key(event, input, SopranoClient.module().isCrosshairKey(event))) info.cancel();
        else ClientEvents.keyInput(event, input);
    }
}
