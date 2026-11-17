package dev.mempatch.mapleak;

import net.minecraft.world.entity.player.Player;

public interface MapItemSavedDataAccess {
    void patchMapPlayerLeak$evictPlayer(Player player);
}
