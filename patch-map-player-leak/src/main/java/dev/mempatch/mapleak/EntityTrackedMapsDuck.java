package dev.mempatch.mapleak;

import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

public interface EntityTrackedMapsDuck {
    void patchMapPlayerLeak$track(MapItemSavedData data);

    void patchMapPlayerLeak$evictAll();
}
