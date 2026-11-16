package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.map.MapAnnotations;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiPlayerGameMode.class, remap = false)
abstract class ClientBlockUseMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void soprano$usedBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> info) {
        if (SopranoClient.ready() && info.getReturnValue().consumesAction()) MapAnnotations.onUse(hit);
    }
}
