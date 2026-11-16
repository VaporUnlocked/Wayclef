package dev.soprano.mixin;

import dev.soprano.inventory.StorageKnowledge;
import dev.soprano.task.StationMenuSync;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, remap = false)
abstract class ClientStorageContentsMixin {
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void soprano$storageMenu(ClientboundOpenScreenPacket packet, CallbackInfo callback) {
        StorageKnowledge.menuOpened((ClientPacketListener) (Object) this, packet.getContainerId());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void soprano$storageContents(ClientboundContainerSetContentPacket packet, CallbackInfo callback) {
        var connection = (ClientPacketListener) (Object) this;
        StorageKnowledge.menuContents(connection, packet.containerId());
        StationMenuSync.contents(connection, packet.containerId());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void soprano$stationSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo callback) {
        StationMenuSync.slot((ClientPacketListener) (Object) this, packet.getContainerId(), packet.getSlot());
    }

    @Inject(method = "handleContainerSetData", at = @At("TAIL"))
    private void soprano$stationData(ClientboundContainerSetDataPacket packet, CallbackInfo callback) {
        StationMenuSync.data((ClientPacketListener) (Object) this, packet.getContainerId(), packet.getId());
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void soprano$storagePlaced(ClientboundBlockUpdatePacket packet, CallbackInfo callback) {
        StorageKnowledge.blockUpdated((ClientPacketListener) (Object) this, packet.getPos());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void soprano$storageSectionUpdated(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo callback) {
        var connection = (ClientPacketListener) (Object) this;
        packet.runUpdates((position, state) -> StorageKnowledge.blockUpdated(connection, position));
    }
}
