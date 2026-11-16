package org.wurmrail.client;

import com.wurmonline.client.renderer.GroundItemData;
import org.wurmrail.shared.TrestleDeckSurface;
import org.wurmrail.shared.TrestlePanel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Deck pieces announced to the client, keyed by absolute tile. */
public final class TrestleWalkingDecks {
    private final ConcurrentMap<Long, Deck> decks = new ConcurrentHashMap<>();

    public void add(GroundItemData item, int axis, int grade) {
        add(item, axis, grade, null);
    }

    public void add(GroundItemData item, int axis, int grade, TrestlePanel panel) {
        if (axis < 0 && panel == null || item.getLayer() != 0 || item.getParent() != -10L || item.getBridgeId() > 0L) return;
        decks.put(key((int)(item.getX() / 4), (int)(item.getY() / 4)), new Deck(item, axis, grade, panel));
    }

    public void remove(GroundItemData item) {
        long key = key((int)(item.getX() / 4), (int)(item.getY() / 4));
        Deck deck = decks.get(key);
        if (deck != null && deck.id == item.getId()) decks.remove(key, deck);
    }

    public void clear() { decks.clear(); }

    public float height(int tileX, int tileY, float x, float y, float feet) {
        Deck deck = decks.get(key(tileX, tileY));
        if (deck == null) return Float.NaN;
        return deck.panel == null ? TrestleDeckSurface.height(x, y, feet, deck.x, deck.y, deck.z, deck.axis, deck.grade)
                : deck.z + deck.panel.height(x - deck.x, y - deck.y, feet - deck.z);
    }

    private static long key(int tileX, int tileY) {
        return ((long)tileX << 32) | (tileY & 0xffffffffL);
    }

    private static final class Deck {
        final long id;
        final float x, y, z;
        final int axis, grade;
        final TrestlePanel panel;

        Deck(GroundItemData item, int axis, int grade, TrestlePanel panel) {
            id = item.getId();
            x = item.getX();
            y = item.getY();
            z = item.getH();
            this.axis = axis;
            this.grade = grade;
            this.panel = panel;
        }
    }
}
