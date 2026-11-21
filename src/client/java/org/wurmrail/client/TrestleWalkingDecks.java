package org.wurmrail.client;

import java.util.Map;
import com.wurmonline.client.renderer.GroundItemData;
import org.wurmrail.shared.TrestleDeckSurface;
import org.wurmrail.shared.TrestlePanel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Deck pieces announced to the client, keyed by absolute tile. */
public final class TrestleWalkingDecks {
    private final ConcurrentMap<Long, Map<Long, Deck>> decks = new ConcurrentHashMap<>();

    public void add(GroundItemData item, int axis, int grade) {
        add(item, axis, grade, null);
    }

    public void add(GroundItemData item, int axis, int grade, TrestlePanel panel) {
        if (axis < 0 && panel == null || item.getLayer() != 0 || item.getParent() != -10L || item.getBridgeId() > 0L) return;
        long key = key((int)(item.getX() / 4), (int)(item.getY() / 4));
        decks.compute(key, (tile, current) -> {
            Map<Long, Deck> pieces = current == null ? new ConcurrentHashMap<>() : current;
            pieces.put(item.getId(),new Deck(item,axis,grade,panel));
            return pieces;
        });
    }

    public void remove(GroundItemData item) {
        long key = key((int)(item.getX() / 4), (int)(item.getY() / 4));
        decks.computeIfPresent(key, (tile, pieces) -> {
            pieces.remove(item.getId());
            return pieces.isEmpty() ? null : pieces;
        });
    }

    public void clear() { decks.clear(); }

    public float height(int tileX, int tileY, float x, float y, float feet) {
        Map<Long, Deck> pieces = decks.get(key(tileX,tileY));
        float height = Float.NaN;
        if (pieces != null) for (Deck deck : pieces.values()) {
            float surface = deck.panel == null
                    ? TrestleDeckSurface.height(x,y,feet,deck.x,deck.y,deck.z,deck.axis,deck.grade)
                    : deck.z + deck.panel.height(x-deck.x,y-deck.y,feet-deck.z);
            height = TrestleDeckSurface.higher(height,surface);
        }
        return height;
    }

    private static long key(int tileX, int tileY) {
        return ((long)tileX << 32) | (tileY & 0xffffffffL);
    }

    private static final class Deck {
        final float x, y, z;
        final int axis, grade;
        final TrestlePanel panel;

        Deck(GroundItemData item, int axis, int grade, TrestlePanel panel) {
            x = item.getX();
            y = item.getY();
            z = item.getH();
            this.axis = axis;
            this.grade = grade;
            this.panel = panel;
        }
    }
}
