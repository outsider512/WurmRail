package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import org.wurmrail.shared.TrestleDeckSurface;

/** Finds the exposed top of an installed deck using the native ground items. */
public final class TrestleWalkingWorld {
    private TrestleWalkingWorld() { }

    public static float height(float x, float y, float feet) {
        if (WurmRailWorld.trestleDeckTemplateId == 0) return Float.NaN;
        return TrestleDeckSurface.find(x, y, feet, TrestleWalkingWorld::tileHeight);
    }

    public static boolean needsExplicitHeight(Creature creature) {
        if (!creature.isPlayer() || !creature.isOnSurface() || creature.getBridgeId() > 0L
                || creature.getVehicle() != -10L || creature.getFloorLevel() > 0)
            return false;
        float feet = creature.getPositionZ();
        float deck = height(creature.getPosX(), creature.getPosY(), feet);
        return Float.isFinite(deck) && Math.abs(feet - deck) <= .75f;
    }

    private static float tileHeight(int tileX, int tileY, float x, float y, float feet) {
        if (tileX < 0 || tileY < 0 || tileX >= Zones.worldTileSizeX || tileY >= Zones.worldTileSizeY)
            return Float.NaN;
        VolaTile tile = Zones.getTileOrNull(tileX, tileY, true);
        if (tile == null) return Float.NaN;
        float height = Float.NaN;
        for (Item item : tile.getItems()) {
            if (item.getTemplateId() == WurmRailWorld.trestleDeckTemplateId && item.getParentId() == -10L
                    && item.getOwnerId() == -10L && item.onBridge() == -10L)
                height = TrestleDeckSurface.higher(height, TrestleDeckSurface.height(x, y, feet,
                        item.getPosX(), item.getPosY(), item.getPosZ(), item.getAuxData() & 3, item.getData1()));
        }
        return height;
    }
}
