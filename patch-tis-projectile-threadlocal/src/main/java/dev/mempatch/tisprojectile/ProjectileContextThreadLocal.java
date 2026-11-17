package dev.mempatch.tisprojectile;

import carpet.logging.LoggerRegistry;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

/** Constructor context only: never let an idle worker own an entity or its world. */
public final class ProjectileContextThreadLocal extends ThreadLocal<Entity> {
    private final ThreadLocal<WeakReference<Entity>> context = new ThreadLocal<>();

    @Override
    public void set(Entity entity) {
        // These are the two families whose Carpet constructors create a trajectory
        // helper. Unsupported types must not leave a context for an unrelated helper.
        if (entity == null || !LoggerRegistry.__projectiles || entity.level().isClientSide()
            || !(entity instanceof AbstractArrow || entity instanceof ThrowableProjectile)) {
            remove();
            return;
        }
        // A supported constructor may fail or never create a helper (e.g. an NBT
        // constructor). Weak storage also covers those paths without another thread
        // trying to clear this thread's context. The active constructor owns `this`.
        context.set(new WeakReference<>(entity));
    }

    @Override
    public Entity get() {
        WeakReference<Entity> ref = context.get();
        Entity entity = ref == null ? null : ref.get();
        if (entity == null) context.remove();
        return entity;
    }

    @Override
    public void remove() {
        context.remove();
    }
}
