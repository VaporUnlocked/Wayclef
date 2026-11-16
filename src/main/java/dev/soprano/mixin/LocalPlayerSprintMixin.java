package dev.soprano.mixin;

import dev.soprano.game.NavigationInput;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = LocalPlayer.class, remap = false)
abstract class LocalPlayerSprintMixin {
    @Inject(method = "shouldStopRunSprinting()Z", at = @At("RETURN"), cancellable = true)
    private void soprano$releaseRouteSprint(CallbackInfoReturnable<Boolean> callback) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // Vanilla normally keeps sprinting after the sprint key is released while forward remains held.
        if (player.input instanceof NavigationInput input && input.soprano$releaseSprint()) callback.setReturnValue(true);
    }
}
