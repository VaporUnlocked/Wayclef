package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import meteordevelopment.meteorclient.utils.player.Rotations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps Freecam's optional rotate-to-crosshair setting from steering an automated player. */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.render.Freecam", remap = false)
abstract class MeteorFreecamMixin {
    @Redirect(method = "onTick", at = @At(value = "INVOKE",
        target = "Lmeteordevelopment/meteorclient/utils/player/Rotations;rotate(DDILjava/lang/Runnable;)V"))
    private void soprano$rotatePlayer(double yaw, double pitch, int priority, Runnable callback) {
        if (!SopranoClient.controlsPlayer()) Rotations.rotate(yaw, pitch, priority, callback);
    }
}
