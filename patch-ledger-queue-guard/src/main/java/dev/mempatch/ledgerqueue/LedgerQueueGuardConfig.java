package dev.mempatch.ledgerqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LedgerQueueGuardConfig {
    public static final Logger LOGGER = LoggerFactory.getLogger("patch_ledger_queue_guard");
    public static final int MAX_QUEUE_SIZE = Integer.getInteger("ledger.queueGuard.maxSize", 32768);
    public static final boolean DROP_OLDEST = Boolean.getBoolean("ledger.queueGuard.dropOldest");
    private static final long WARN_INTERVAL_MS = 30_000L;
    private static volatile long lastWarnMs = 0L;
    private static long droppedSinceWarn = 0L;

    private LedgerQueueGuardConfig() {}

    public static synchronized void recordDrop(int currentSize, boolean droppedOldest) {
        droppedSinceWarn++;
        long now = System.currentTimeMillis();
        if (now - lastWarnMs >= WARN_INTERVAL_MS) {
            lastWarnMs = now;
            LOGGER.warn(
                "[LedgerQueueGuard] Database write queue reached cap ({}/{}), dropped {} actions in the last interval (mode={}). Check SQLite/database health (e.g., 'no such table: actions') to prevent OOM.",
                currentSize,
                MAX_QUEUE_SIZE,
                droppedSinceWarn,
                droppedOldest ? "dropOldest" : "dropNewest"
            );
            droppedSinceWarn = 0L;
        }
    }
}
