package dev.mempatch.fakeplayer.mixin;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.impl.networking.AbstractNetworkAddon;
import net.fabricmc.fabric.impl.networking.PacketListenerExtensions;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FakePlayerDisconnectMixin {
    @Inject(
        method = "onDisconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
        at = @At("TAIL")
    )
    private void patchFakePlayer$cleanupFabricAddonOnFakeDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        Connection conn = ((ServerCommonConnectionAccessor) this).patchFakePlayer$getConnection();
        if (conn == null) {
            return;
        }
        Channel channel = ((ConnectionStateAccessor) conn).patchFakePlayer$getChannel();
        boolean isSynthetic =
            "carpet.patches.FakeClientConnection".equals(conn.getClass().getName())
                || (channel instanceof EmbeddedChannel && channel.pipeline().context(conn) == null);
        if (!isSynthetic) {
            return;
        }

        try {
            if ((Object) this instanceof PacketListenerExtensions ext) {
                AbstractNetworkAddon<?> addon = ext.getAddon();
                if (addon != null) {
                    try {
                        addon.handleDisconnect();
                    } finally {
                        // Fabric skips endSession when an event listener throws.
                        // Removing the same session twice is harmless.
                        addon.endSession();
                    }
                }
            }
        } finally {
            ((ConnectionStateAccessor) conn).patchFakePlayer$setDisconnectionHandled(true);
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }
}
