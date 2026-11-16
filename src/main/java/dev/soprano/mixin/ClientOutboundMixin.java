package dev.soprano.mixin;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Connection.class, remap = false, priority = 850)
abstract class ClientOutboundMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void soprano$sending(Packet<?> packet, ChannelFutureListener completion, boolean flush, CallbackInfo info) {
        Connection connection = (Connection) (Object) this;
        if (!SopranoClient.isMeteor() && SopranoClient.observes(connection)) ClientEvents.packetSending(connection, packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("RETURN"))
    private void soprano$sent(Packet<?> packet, ChannelFutureListener completion, boolean flush, CallbackInfo info) {
        Connection connection = (Connection) (Object) this;
        if (!SopranoClient.isMeteor() && SopranoClient.observes(connection)) ClientEvents.packetSent(connection, packet);
    }
}
