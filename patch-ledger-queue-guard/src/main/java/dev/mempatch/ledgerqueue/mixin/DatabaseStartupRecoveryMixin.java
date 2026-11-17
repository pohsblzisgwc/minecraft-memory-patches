package dev.mempatch.ledgerqueue.mixin;

import com.github.quiltservertools.ledger.config.DatabaseSpecKt;
import com.github.quiltservertools.ledger.config.LedgerConfigKt;
import com.github.quiltservertools.ledger.database.DatabaseManager;
import dev.mempatch.ledgerqueue.LedgerSmallDatabaseRecovery;
import javax.sql.DataSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DatabaseManager.class, remap = false)
public abstract class DatabaseStartupRecoveryMixin {
    @Inject(method = "getDefaultDatasource()Ljavax/sql/DataSource;", at = @At("RETURN"))
    private void patchLedgerQueue$recoverBeforeOpening(CallbackInfoReturnable<DataSource> cir) {
        LedgerSmallDatabaseRecovery.beforeOpen(
            DatabaseSpecKt.getDatabasePath(LedgerConfigKt.getConfig()).resolve("ledger.sqlite"));
    }
}
