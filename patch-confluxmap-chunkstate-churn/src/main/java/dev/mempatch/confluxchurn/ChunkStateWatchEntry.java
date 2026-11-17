package dev.mempatch.confluxchurn;

public final class ChunkStateWatchEntry {
    public static final byte STATE_UNSET = 0;
    public static final byte STATE_UNLOADED = 1;
    public static final byte STATE_LOADED = 2;

    public final ChunkStateDimBucket dim;
    public final int chunkX;
    public final int chunkZ;
    public final long packedPos;
    public int ringIndex;
    public byte publishedState = STATE_UNSET;
    public int lastTicketLevel = Integer.MIN_VALUE;

    public ChunkStateWatchEntry(
        ChunkStateDimBucket dim, int chunkX, int chunkZ, long packedPos, int ringIndex
    ) {
        this.dim = dim;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.packedPos = packedPos;
        this.ringIndex = ringIndex;
    }
}
