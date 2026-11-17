package dev.mempatch.mapleak.mixin;

import dev.mempatch.mapleak.EntityTrackedMapsDuck;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityRemovedMapCleanupMixin {
    @Inject(
        method = "setRemoved(Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
        at = @At("TAIL")
    )
    private void patchMapPlayerLeak$onRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        if ((Object) this instanceof EntityTrackedMapsDuck duck) {
            duck.patchMapPlayerLeak$evictAll();
        }
    }
}
