package org.wurmrail.client;

import com.wurmonline.client.renderer.GroundItemData;
import org.wurmrail.shared.TrestleDeckSurface;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Deck pieces announced to the client, keyed by absolute tile. */
public final class TrestleWalkingDecks {
    private final ConcurrentMap<Long, Deck> decks = new ConcurrentHashMap<>();

    public void add(GroundItemData item, int axis, int grade) {
        if (axis < 0 || item.getLayer() != 0 || item.getParent() != -10L || item.getBridgeId() > 0L) return;
        decks.put(key((int)(item.getX() / 4), (int)(item.getY() / 4)), new Deck(item, axis, grade));
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
        return TrestleDeckSurface.height(x, y, feet, deck.x, deck.y, deck.z, deck.axis, deck.grade);
    }

    private static long key(int tileX, int tileY) {
        return ((long)tileX << 32) | (tileY & 0xffffffffL);
    }

    private static final class Deck {
        final long id;
        final float x, y, z;
        final int axis, grade;

        Deck(GroundItemData item, int axis, int grade) {
            id = item.getId();
            x = item.getX();
            y = item.getY();
            z = item.getH();
            this.axis = axis;
            this.grade = grade;
        }
    }
}
