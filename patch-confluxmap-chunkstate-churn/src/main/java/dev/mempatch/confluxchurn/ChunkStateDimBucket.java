package dev.mempatch.confluxchurn;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;

public final class ChunkStateDimBucket {
    public final ServerLevel level;
    public final Long2ObjectOpenHashMap<ChunkStateWatchEntry> byPos = new Long2ObjectOpenHashMap<>();
    public int cachedWorldIdx = -1;
    public long cachedTick = Long.MIN_VALUE;

    public ChunkStateDimBucket(ServerLevel level) {
        this.level = level;
    }
}
