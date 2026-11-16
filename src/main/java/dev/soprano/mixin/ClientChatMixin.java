package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.map.MapAnnotations;
import dev.soprano.ui.StandaloneChat;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientPacketListener.class, remap = false)
abstract class ClientChatMixin {
    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void soprano$localCommands(String message, CallbackInfo info) {
        var listener = (ClientPacketListener) (Object) this;
        if (StandaloneChat.handle(message, listener.getSuggestionsProvider())) info.cancel();
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void soprano$systemMessage(ClientboundSystemChatPacket packet, CallbackInfo info) {
        if (SopranoClient.ready()) MapAnnotations.onSystemMessage(packet.content());
    }
}
