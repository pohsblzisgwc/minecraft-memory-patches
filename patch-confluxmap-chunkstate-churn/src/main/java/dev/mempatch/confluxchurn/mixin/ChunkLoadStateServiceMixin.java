package dev.mempatch.confluxchurn.mixin;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.ChunkLoadStatePublisher;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.server.ChunkLoadStateService;
import dev.mempatch.confluxchurn.ChunkStateDimBucket;
import dev.mempatch.confluxchurn.ChunkStateWatchEntry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkLoadStateService.class, remap = false)
public abstract class ChunkLoadStateServiceMixin {
    @Shadow
    @Final
    private ChunkLoadStatePublisher publisher;

    @Shadow
    @Final
    private Map<UUID, Consumer<LoadStateDeltaS2C>> subscribers;

    @Unique
    private final Map<ServerLevel, ChunkStateDimBucket> dims$pcmc = new IdentityHashMap<>(4);

    @Unique
    private final ArrayList<ChunkStateWatchEntry> ring$pcmc = new ArrayList<>(4096);

    @Unique
    private int cursor$pcmc = 0;

    @Unique
    private long tickSerial$pcmc = 0L;

    @Inject(
        method = "onChunkLoad(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void patchConfluxChurn$onChunkLoad(ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        ci.cancel();
        ChunkPos pos = chunk.getPos();
        long packed = pos.pack();
        ChunkStateDimBucket bucket = this.dims$pcmc.get(level);
        if (bucket == null) {
            bucket = new ChunkStateDimBucket(level);
            this.dims$pcmc.put(level, bucket);
        }
        ChunkStateWatchEntry entry = bucket.byPos.get(packed);
        if (entry == null) {
            entry = new ChunkStateWatchEntry(bucket, pos.x(), pos.z(), packed, this.ring$pcmc.size());
            bucket.byPos.put(packed, entry);
            this.ring$pcmc.add(entry);
        }
        this.patchConfluxChurn$refreshEntry(level.getServer(), entry);
    }

    @Inject(
        method = "onChunkUnload(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void patchConfluxChurn$onChunkUnload(ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        ci.cancel();
        ChunkPos pos = chunk.getPos();
        long packed = pos.pack();
        ChunkStateDimBucket bucket = this.dims$pcmc.get(level);
        if (bucket != null) {
            ChunkStateWatchEntry removed = bucket.byPos.remove(packed);
            if (removed != null) {
                this.patchConfluxChurn$swapRemove(removed.ringIndex);
            }
            int worldIdx = this.patchConfluxChurn$resolveWorldIndex(level.getServer(), bucket);
            if (worldIdx >= 0) {
                this.publisher.remove(worldIdx, pos.x(), pos.z());
            }
            if (bucket.byPos.isEmpty()) {
                this.dims$pcmc.remove(level);
            }
            return;
        }
        int worldIdx = patchConfluxChurn$computeWorldIndex(level.getServer(), level);
        if (worldIdx >= 0) {
            this.publisher.remove(worldIdx, pos.x(), pos.z());
        }
    }

    @Inject(
        method = "tick(Lnet/minecraft/server/MinecraftServer;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void patchConfluxChurn$tick(MinecraftServer server, CallbackInfo ci) {
        ci.cancel();
        this.tickSerial$pcmc++;
        this.patchConfluxChurn$refreshLevelsFast(server, 2048);

        int subCount = this.subscribers.size();
        if (subCount <= 0) {
            return;
        }
        int budget = 2048;
        if (subCount == 1) {
            Map.Entry<UUID, Consumer<LoadStateDeltaS2C>> only = this.subscribers.entrySet().iterator().next();
            LoadStateDeltaS2C delta = this.publisher.poll(only.getKey(), budget);
            if (delta != null) {
                only.getValue().accept(delta);
            }
            return;
        }
        int remaining = Math.min(subCount, budget);
        while (remaining > 0) {
            Iterator<Map.Entry<UUID, Consumer<LoadStateDeltaS2C>>> it = this.subscribers.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<UUID, Consumer<LoadStateDeltaS2C>> entry = it.next();
            UUID uuid = entry.getKey();
            Consumer<LoadStateDeltaS2C> sender = entry.getValue();
            it.remove();
            this.subscribers.put(uuid, sender);
            int share = Math.max(1, budget / remaining);
            LoadStateDeltaS2C delta = this.publisher.poll(uuid, share);
            if (delta != null) {
                sender.accept(delta);
            }
            budget -= share;
            remaining--;
        }
    }

    @Inject(method = "refreshLevels(I)V", at = @At("HEAD"), cancellable = true)
    private void patchConfluxChurn$refreshLevels(int maxChecks, CallbackInfo ci) {
        ci.cancel();
        this.patchConfluxChurn$refreshLevelsFast(null, maxChecks);
    }

    @Inject(method = "clear()V", at = @At("TAIL"))
    private void patchConfluxChurn$clear(CallbackInfo ci) {
        this.dims$pcmc.clear();
        this.ring$pcmc.clear();
        this.cursor$pcmc = 0;
    }

    @Unique
    private void patchConfluxChurn$refreshLevelsFast(MinecraftServer server, int maxChecks) {
        int size = this.ring$pcmc.size();
        int limit = Math.min(size, maxChecks);
        for (int i = 0; i < limit; i++) {
            int curSize = this.ring$pcmc.size();
            if (curSize == 0) {
                this.cursor$pcmc = 0;
                return;
            }
            if (this.cursor$pcmc >= curSize) {
                this.cursor$pcmc = 0;
            }
            ChunkStateWatchEntry entry = this.ring$pcmc.get(this.cursor$pcmc++);
            MinecraftServer srv = server != null ? server : entry.dim.level.getServer();
            this.patchConfluxChurn$refreshEntry(srv, entry);
        }
    }

    @Unique
    private void patchConfluxChurn$refreshEntry(MinecraftServer server, ChunkStateWatchEntry entry) {
        int worldIdx = this.patchConfluxChurn$resolveWorldIndex(server, entry.dim);
        if (worldIdx < 0) {
            return;
        }
        ChunkHolder holder = ((ChunkMapChunkHolderInvoker) entry.dim.level.getChunkSource().chunkMap)
            .patchConfluxChurn$getVisibleChunkIfPresent(entry.packedPos);
        if (holder == null) {
            if (entry.publishedState != ChunkStateWatchEntry.STATE_UNLOADED) {
                entry.publishedState = ChunkStateWatchEntry.STATE_UNLOADED;
                entry.lastTicketLevel = Integer.MIN_VALUE;
                this.publisher.remove(worldIdx, entry.chunkX, entry.chunkZ);
            }
            return;
        }
        int ticketLevel = holder.getTicketLevel();
        if (entry.publishedState == ChunkStateWatchEntry.STATE_LOADED && entry.lastTicketLevel == ticketLevel) {
            return;
        }
        ChunkLoadBand band = ChunkLoadBand.fromTicketLevel(ticketLevel);
        if (band == ChunkLoadBand.UNLOADED) {
            if (entry.publishedState != ChunkStateWatchEntry.STATE_UNLOADED) {
                entry.publishedState = ChunkStateWatchEntry.STATE_UNLOADED;
                entry.lastTicketLevel = Integer.MIN_VALUE;
                this.publisher.remove(worldIdx, entry.chunkX, entry.chunkZ);
            }
        } else {
            entry.publishedState = ChunkStateWatchEntry.STATE_LOADED;
            entry.lastTicketLevel = ticketLevel;
            this.publisher.update(worldIdx, entry.chunkX, entry.chunkZ, ticketLevel, band);
        }
    }

    @Unique
    private void patchConfluxChurn$swapRemove(int idx) {
        int lastIdx = this.ring$pcmc.size() - 1;
        if (idx < 0 || idx > lastIdx) {
            return;
        }
        if (idx != lastIdx) {
            ChunkStateWatchEntry moved = this.ring$pcmc.get(lastIdx);
            moved.ringIndex = idx;
            this.ring$pcmc.set(idx, moved);
            if (this.cursor$pcmc == lastIdx) {
                this.cursor$pcmc = idx;
            }
        }
        this.ring$pcmc.remove(lastIdx);
        if (this.cursor$pcmc >= this.ring$pcmc.size()) {
            this.cursor$pcmc = 0;
        }
    }

    @Unique
    private int patchConfluxChurn$resolveWorldIndex(MinecraftServer server, ChunkStateDimBucket bucket) {
        if (bucket.cachedTick == this.tickSerial$pcmc && this.tickSerial$pcmc != 0L) {
            return bucket.cachedWorldIdx;
        }
        int idx = patchConfluxChurn$computeWorldIndex(server, bucket.level);
        bucket.cachedWorldIdx = idx;
        bucket.cachedTick = this.tickSerial$pcmc;
        return idx;
    }

    @Unique
    private static int patchConfluxChurn$computeWorldIndex(MinecraftServer server, ServerLevel target) {
        if (server == null) {
            return -1;
        }
        int idx = 0;
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl == target) {
                return idx;
            }
            idx++;
        }
        return -1;
    }
}
