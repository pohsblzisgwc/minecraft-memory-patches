package dev.mempatch.tisprojectile.mixin;

import carpettisaddition.helpers.carpet.tweaks.logger.projectile.TrajectoryLoggerUtil;
import dev.mempatch.tisprojectile.ProjectileContextThreadLocal;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = TrajectoryLoggerUtil.class, remap = false)
public abstract class TrajectoryLoggerUtilMixin {
    @Redirect(method = "<clinit>", at = @At(value = "NEW", target = "java/lang/ThreadLocal", ordinal = 0))
    private static ThreadLocal<Entity> patchTisProjectile$weakConstructorContext() {
        return new ProjectileContextThreadLocal();
    }
}
