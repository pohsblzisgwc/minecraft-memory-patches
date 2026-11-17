package dev.mempatch.mapleak.mixin;

import dev.mempatch.mapleak.EntityTrackedMapsDuck;
import dev.mempatch.mapleak.MapItemSavedDataAccess;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataCleanupMixin implements MapItemSavedDataAccess {
    @Shadow
    @Final
    private List<MapItemSavedData.HoldingPlayer> carriedBy;

    @Shadow
    @Final
    private Map<Player, MapItemSavedData.HoldingPlayer> carriedByPlayers;

    @Shadow
    private void removeDecoration(String identifier) {}

    @Unique
    private int sweepCursor$pmpl = 0;

    @Inject(
        method = "tickCarriedBy(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/decoration/ItemFrame;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void patchMapPlayerLeak$guardRemovedAtHead(
        Player player, ItemStack stack, ItemFrame placedInFrame, CallbackInfo ci
    ) {
        if (player == null) {
            return;
        }
        if (player.isRemoved()) {
            this.patchMapPlayerLeak$evictDirect(player);
            ci.cancel();
            return;
        }
        if (player instanceof EntityTrackedMapsDuck duck) {
            duck.patchMapPlayerLeak$track((MapItemSavedData) (Object) this);
        }
    }

    @Inject(
        method = "tickCarriedBy(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/decoration/ItemFrame;)V",
        at = @At("TAIL")
    )
    private void patchMapPlayerLeak$amortizedSweep(
        Player player, ItemStack stack, ItemFrame placedInFrame, CallbackInfo ci
    ) {
        // When Lithium's entity.framed_maps is active, placedInFrame != null bypasses vanilla's loop over carriedBy.
        // Amortized O(1) cursor check guarantees zero leaked removed HoldingPlayers even if another mod adds one.
        if (placedInFrame != null && this.carriedBy.size() > 1) {
            this.patchMapPlayerLeak$sweepStep(2);
        }
    }

    @Inject(
        method = "getHoldingPlayer(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData$HoldingPlayer;",
        at = @At("RETURN")
    )
    private void patchMapPlayerLeak$onGetHoldingPlayer(
        Player player, CallbackInfoReturnable<MapItemSavedData.HoldingPlayer> cir
    ) {
        if (player == null) {
            return;
        }
        if (player.isRemoved()) {
            this.patchMapPlayerLeak$evictDirect(player);
        } else if (player instanceof EntityTrackedMapsDuck duck) {
            duck.patchMapPlayerLeak$track((MapItemSavedData) (Object) this);
        }
    }

    @Override
    public void patchMapPlayerLeak$evictPlayer(Player player) {
        if (player == null) {
            return;
        }
        if (player.level() instanceof ServerLevel sl) {
            MinecraftServer server = sl.getServer();
            if (server != null && !server.isSameThread()) {
                server.execute(() -> this.patchMapPlayerLeak$evictDirect(player));
                return;
            }
        }
        this.patchMapPlayerLeak$evictDirect(player);
    }

    @Unique
    private void patchMapPlayerLeak$evictDirect(Player player) {
        MapItemSavedData.HoldingPlayer hp = this.carriedByPlayers.remove(player);
        if (hp != null) {
            this.carriedBy.remove(hp);
            this.removeDecoration(player.getPlainTextName());
        }
    }

    @Unique
    private void patchMapPlayerLeak$sweepStep(int maxChecks) {
        for (int i = 0; i < maxChecks; i++) {
            int size = this.carriedBy.size();
            if (size <= 0) {
                this.sweepCursor$pmpl = 0;
                return;
            }
            if (this.sweepCursor$pmpl >= size) {
                this.sweepCursor$pmpl = 0;
            }
            MapItemSavedData.HoldingPlayer hp = this.carriedBy.get(this.sweepCursor$pmpl);
            if (hp.player.isRemoved()) {
                this.carriedByPlayers.remove(hp.player);
                this.carriedBy.remove(this.sweepCursor$pmpl);
                this.removeDecoration(hp.player.getPlainTextName());
            } else {
                this.sweepCursor$pmpl++;
            }
        }
    }
}
