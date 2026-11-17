package dev.mempatch.ledgerqueue.mixin;

import com.github.quiltservertools.ledger.actions.ActionType;
import com.github.quiltservertools.ledger.database.ActionQueueService;
import dev.mempatch.ledgerqueue.LedgerQueueGuardConfig;
import java.util.concurrent.LinkedBlockingQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ActionQueueService.class, remap = false)
public abstract class ActionQueueServiceGuardMixin {
    @Redirect(
        method = "addToQueue(Lcom/github/quiltservertools/ledger/actions/ActionType;)Z",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/LinkedBlockingQueue;add(Ljava/lang/Object;)Z")
    )
    private boolean patchLedgerQueue$enqueue(LinkedBlockingQueue<ActionType> queue, Object value) {
        ActionType action = (ActionType) value;
        int maxSize = LedgerQueueGuardConfig.MAX_QUEUE_SIZE;
        if (maxSize <= 0) {
            return queue.add(action);
        }
        // All producers pass through this redirect. Keep the actual insertion inside
        // the same lock as the capacity check; the consumer can only reduce the size.
        synchronized (queue) {
            if (queue.size() >= maxSize) {
                if (!LedgerQueueGuardConfig.DROP_OLDEST) {
                    LedgerQueueGuardConfig.recordDrop(queue.size(), false);
                    return false;
                }
                while (queue.size() >= maxSize) {
                    if (queue.poll() == null) break;
                    LedgerQueueGuardConfig.recordDrop(queue.size(), true);
                }
            }
            return queue.add(action);
        }
    }
}
