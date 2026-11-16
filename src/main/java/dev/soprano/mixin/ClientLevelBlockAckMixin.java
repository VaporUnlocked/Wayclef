package dev.soprano.mixin;

import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.Navigation;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.task.JobQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLevel.class, remap = false)
abstract class ClientLevelBlockAckMixin {
    @Inject(method = "handleBlockChangedAck(I)V", at = @At("TAIL"))
    private void soprano$confirmedActions(int sequence, CallbackInfo callback) {
        ReplaceActivity.get().acknowledge((ClientLevel) (Object) this, sequence);
        BlueprintConstruction.get().acknowledge((ClientLevel) (Object) this, sequence);
        JobQueue.get().acknowledge((ClientLevel) (Object) this, sequence);
        Navigation.get().acknowledge((ClientLevel) (Object) this, sequence);
    }

    @Inject(method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
        at = @At("HEAD"))
    private void soprano$trackScaffoldBlock(BlockPos position, BlockState state, int flags, CallbackInfo callback) {
        BlueprintConstruction.get().serverBlock((ClientLevel) (Object) this, position, state);
    }

    @Inject(method = "unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V", at = @At("HEAD"))
    private void soprano$scaffoldChunkUnloaded(LevelChunk chunk, CallbackInfo callback) {
        BlueprintConstruction.get().chunkChanged((ClientLevel) (Object) this, chunk.getPos());
    }

    @Inject(method = "onChunkLoaded(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"))
    private void soprano$scaffoldChunkLoaded(ChunkPos position, CallbackInfo callback) {
        BlueprintConstruction.get().chunkChanged((ClientLevel) (Object) this, position);
    }
}
