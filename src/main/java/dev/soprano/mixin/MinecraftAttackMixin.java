package dev.soprano.mixin;

import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.Navigation;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.task.JobQueue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class, remap = false)
abstract class MinecraftAttackMixin {
    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), cancellable = true)
    private void soprano$continueOwnedMining(boolean attackHeld, CallbackInfo callback) {
        if (!attackHeld && (ReplaceActivity.get().ownsBreaking() || BlueprintConstruction.get().ownsBreaking()
            || JobQueue.get().ownsBreaking() || Navigation.get().ownsBreaking())) callback.cancel();
    }
}
