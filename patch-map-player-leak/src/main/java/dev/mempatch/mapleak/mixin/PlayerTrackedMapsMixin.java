package dev.mempatch.mapleak.mixin;

import dev.mempatch.mapleak.EntityTrackedMapsDuck;
import dev.mempatch.mapleak.MapItemSavedDataAccess;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Player.class)
public abstract class PlayerTrackedMapsMixin implements EntityTrackedMapsDuck {
    @Unique
    private final List<WeakReference<MapItemSavedData>> trackedMaps$pmpl = new ArrayList<>(4);

    @Override
    public void patchMapPlayerLeak$track(MapItemSavedData data) {
        if (data == null) {
            return;
        }
        synchronized (this.trackedMaps$pmpl) {
            boolean found = false;
            for (int i = this.trackedMaps$pmpl.size() - 1; i >= 0; i--) {
                MapItemSavedData existing = this.trackedMaps$pmpl.get(i).get();
                if (existing == null) {
                    this.trackedMaps$pmpl.remove(i);
                } else if (existing == data) {
                    found = true;
                }
            }
            if (!found) {
                this.trackedMaps$pmpl.add(new WeakReference<>(data));
            }
        }
    }

    @Override
    public void patchMapPlayerLeak$evictAll() {
        List<MapItemSavedData> snapshot = null;
        synchronized (this.trackedMaps$pmpl) {
            if (this.trackedMaps$pmpl.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(this.trackedMaps$pmpl.size());
            for (int i = 0; i < this.trackedMaps$pmpl.size(); i++) {
                MapItemSavedData data = this.trackedMaps$pmpl.get(i).get();
                if (data != null) {
                    snapshot.add(data);
                }
            }
            this.trackedMaps$pmpl.clear();
        }
        Player self = (Player) (Object) this;
        for (int i = 0; i < snapshot.size(); i++) {
            ((MapItemSavedDataAccess) snapshot.get(i)).patchMapPlayerLeak$evictPlayer(self);
        }
    }
}
