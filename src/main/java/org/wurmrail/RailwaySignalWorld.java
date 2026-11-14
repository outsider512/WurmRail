package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Native signal scenery only. Route discovery, authority and ownership belong to the core. */
public final class RailwaySignalWorld {
    private static final long NO_ID = -10L;
    private static final Logger LOG = Logger.getLogger(RailwaySignalWorld.class.getName());
    private static final String[] DIRECTIONS = {
            "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"
    };

    private RailwaySignalWorld() { }

    /** Immutable server-thread snapshot, deliberately independent of reservation implementation types. */
    public static final class DisplayState {
        public final String id;
        public final long trackMarkerId;
        public final int tileX, tileY, layer, approachDirection;
        public final boolean green;
        public final String owner, reason;

        public DisplayState(String id, long trackMarkerId, int tileX, int tileY, int layer,
                            int approachDirection, boolean green, String owner, String reason) {
            if (id == null || id.trim().isEmpty() || trackMarkerId <= 0 || approachDirection < 0 || approachDirection > 7
                    || (layer != 0 && layer != -1)) throw new IllegalArgumentException("Invalid railway signal display state");
            this.id = id;
            this.trackMarkerId = trackMarkerId;
            this.tileX = tileX;
            this.tileY = tileY;
            this.layer = layer;
            this.approachDirection = approachDirection;
            this.owner = owner;
            this.reason = reason;
            // An aspect without an authority owner must never advertise permission.
            this.green = green && owner != null && !owner.trim().isEmpty();
        }

        public float worldX() { return tileX * 4.0f + 2.0f; }
        public float worldY() { return tileY * 4.0f + 2.0f; }
        public int aux() { return approachDirection | (green ? 8 : 0); }
        private String key() { return trackMarkerId + ":" + approachDirection; }
    }

    /**
     * Restore one persistent native item per (parent track, absolute approach side).
     * Item data stores the native parent ID and aux low bits store the approach;
     * core string IDs are never hashed into native persistence. Call after a committed
     * movement/state change and periodically for repair, never from a movement plan.
     */
    public static void sync(Collection<DisplayState> states) {
        if (states == null || WurmRailWorld.signalTemplateId == 0) return;
        Map<String, DisplayState> expected = new HashMap<String, DisplayState>();
        Set<String> ambiguous = new HashSet<String>();
        for (DisplayState state : states) {
            if (state == null || !validTrack(Items.getItemOptional(state.trackMarkerId).orElse(null), state)) continue;
            String key = state.key();
            if (expected.put(key, state) != null) ambiguous.add(key);
        }
        for (String key : ambiguous) {
            DisplayState invalid = expected.get(key);
            expected.put(key, new DisplayState(invalid.id, invalid.trackMarkerId, invalid.tileX, invalid.tileY,
                    invalid.layer, invalid.approachDirection, false, null, "Invalid duplicate signal definitions"));
            LOG.warning("Duplicate railway signal definitions for native parent/approach " + key + "; holding its visible aspect red");
        }

        Map<String, Item> items = new HashMap<String, Item>();
        List<Item> stale = new ArrayList<Item>();
        for (Item item : Items.getAllItems()) {
            if (!WurmRailWorld.isSignal(item)) continue;
            String key = item.getData() + ":" + ((item.getAuxData() & 255) & 7);
            DisplayState state = expected.get(key);
            if (state == null || !onSignal(item, state)) {
                stale.add(item);
                continue;
            }
            Item previous = items.get(key);
            if (previous == null) items.put(key, item);
            else if (item.getWurmId() < previous.getWurmId()) {
                stale.add(previous);
                items.put(key, item);
            } else stale.add(item);
        }
        for (Item item : stale) {
            try { Items.destroyItem(item.getWurmId()); }
            catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Could not remove stale railway signal item " + item.getWurmId(), failure);
            }
        }

        for (DisplayState state : expected.values()) {
            Item item = items.get(state.key());
            boolean created = false;
            try {
                if (item == null) {
                    item = WurmRailWorld.spawnSignal(state.trackMarkerId, state.worldX(), state.worldY(), state.layer, state.aux());
                    created = true;
                }
                String name = displayName(state);
                boolean changed = created || item.getData() != state.trackMarkerId || (item.getAuxData() & 255) != state.aux()
                        || item.getRotation() != 0 || !name.equals(item.getName());
                if (!changed) continue;
                item.setData(state.trackMarkerId);
                item.setAuxData((byte)state.aux());
                item.setRotation(0);
                item.setName(name);
                // Aspect changes must reach clients already watching the ground item.
                item.updateIfGroundItem();
            } catch (Exception failure) {
                if (created && item != null) {
                    try { Items.destroyItem(item.getWurmId()); }
                    catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                LOG.log(Level.WARNING, "Could not update railway signal " + state.id, failure);
            }
        }
    }

    public static String displayName(DisplayState state) {
        // DbItem.setName truncates at 39 characters. Build the same bounded name
        // before comparing it, otherwise a long diagnostic causes remove/add
        // scenery packets on every reconciliation. Full IDs/reasons stay in inspect UI.
        String name = (state.green ? "GREEN" : "RED") + " from " + DIRECTIONS[state.approachDirection];
        if (state.green) name += " for " + clean(state.owner, 39);
        else if (state.reason != null && !state.reason.trim().isEmpty()) name += ": " + clean(state.reason, 39);
        return clean(name, 39);
    }

    private static String clean(String value, int length) {
        if (value == null) return "";
        String result = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return result.length() > length ? result.substring(0, length) : result;
    }

    private static boolean validTrack(Item item, DisplayState state) {
        return item != null && item.getTemplateId() == WurmRailWorld.trackTemplateId && onGround(item, state)
                && item.getTileX() == state.tileX && item.getTileY() == state.tileY;
    }

    private static boolean onSignal(Item item, DisplayState state) {
        return onGround(item, state) && Math.abs(item.getPosX() - state.worldX()) < 0.01f
                && Math.abs(item.getPosY() - state.worldY()) < 0.01f;
    }

    private static boolean onGround(Item item, DisplayState state) {
        return item.getParentId() == NO_ID && item.getOwnerId() == NO_ID && !item.isBanked()
                && !item.isMailed() && !item.isTraded() && item.getFloorLevel() == 0 && item.getBridgeId() == NO_ID
                && item.isOnSurface() == (state.layer == 0);
    }
}
