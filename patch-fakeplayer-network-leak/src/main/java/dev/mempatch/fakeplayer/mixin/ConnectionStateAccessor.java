package dev.mempatch.fakeplayer.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionStateAccessor {
    @Accessor("channel")
    Channel patchFakePlayer$getChannel();

    @Accessor("disconnectionHandled")
    void patchFakePlayer$setDisconnectionHandled(boolean handled);
}
