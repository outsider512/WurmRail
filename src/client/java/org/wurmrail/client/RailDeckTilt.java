package org.wurmrail.client;

import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.structures.BridgePartData;
import com.wurmonline.shared.constants.BridgeConstants.BridgeState;
import org.wurmrail.shared.TrestleDeckSurface;

/** Reads the deck under each car's delayed render position. */
public final class RailDeckTilt {
    private static final float PROBE = .55f;
    private static final float DECK_PROXIMITY = .35f;

    private RailDeckTilt() { }

    public static BridgePartData bridge(World world, float x, float y, int layer) {
        if (world.getCellRenderer() == null) return null;
        int tx = (int)Math.floor(x/4), ty = (int)Math.floor(y/4);
        Cell cell = world.getCellRenderer().findCellFromTile(tx,ty,layer);
        BridgePartData deck = cell == null ? null : cell.getBridgeAtSurfaceAt(tx,ty);
        return deck != null && deck.getState() == BridgeState.COMPLETED ? deck : null;
    }

    public static float trestlePitch(World world, float x, float y, float z, double alongX, double alongY) {
        Object renderer = world.getCellRenderer();
        if (!(renderer instanceof TrestleDeckSurface.TileHeight)) return Float.NaN;
        TrestleDeckSurface.TileHeight decks = (TrestleDeckSurface.TileHeight)renderer;
        float center = deckHeight(decks,x,y,z);
        if (!near(center,z)) return Float.NaN;
        float dx = (float)(alongX*PROBE), dy = (float)(alongY*PROBE);
        float before = deckHeight(decks,x-dx,y-dy,center);
        float after = deckHeight(decks,x+dx,y+dy,center);
        if (Float.isNaN(before)) return Float.isNaN(after) ? Float.NaN : angle(after-center,PROBE);
        if (Float.isNaN(after)) return angle(center-before,PROBE);
        return angle(after-before,2*PROBE);
    }

    /** Use the local deck plane between server samples, including at a grade break. */
    public static float alignHeight(World world, float x, float y, float z, int layer) {
        if (world == null) return z;
        try {
            Object renderer = world.getCellRenderer();
            if (layer == 0 && renderer instanceof TrestleDeckSurface.TileHeight) {
                float trestle = deckHeight((TrestleDeckSurface.TileHeight)renderer,x,y,z);
                if (near(trestle,z)) return trestle;
            }
            BridgePartData nativeBridge = bridge(world,x,y,layer);
            if (nativeBridge != null) {
                float height = nativeBridge.getBridgeInterpolatedHeight(x,y);
                if (near(height,z)) return height;
            }
        } catch (RuntimeException changingCell) {
            // Tile data may be arriving on another thread. The server height is usable.
        }
        return z;
    }

    private static boolean near(float deck, float z) {
        return Math.abs(deck-z) < DECK_PROXIMITY;
    }

    private static float deckHeight(TrestleDeckSurface.TileHeight deck, float x, float y, float z) {
        // A grade of 100% moves at most .55 m over either probe. The walking
        // surface already permits that step up and resolves tile overhangs.
        return TrestleDeckSurface.find(x,y,z,deck);
    }

    private static float angle(float rise, float run) {
        return (float)Math.toDegrees(Math.atan2(rise,run));
    }
}
