package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Persistent ground indicators for physical junctions. Call only on the server thread. */
public final class RailwayJunctionWorld {
    private static final long NO_ID = -10L;
    private static final Logger LOG = Logger.getLogger(RailwayJunctionWorld.class.getName());
    private static final String[] DIRECTIONS = {
            "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"
    };

    private RailwayJunctionWorld() { }

    /**
     * Reconcile saved native items against the current graph. No item IDs are kept in
     * the graph: the indicator's persistent data field identifies its parent track.
     * Call on visual changes and periodically for repair, rather than every poll.
     */
    public static void sync(RailNetwork network) {
        if (network == null || WurmRailWorld.junctionAlignmentTemplateId == 0) return;
        Map<Long, Node> junctions = new HashMap<Long, Node>();
        for (Node node : network.nodes.values()) {
            Item track = Items.getItemOptional(node.markerId).orElse(null);
            if (node.links.size() >= 3 && validTrack(track, node)) junctions.put(node.markerId, node);
        }

        Map<Long, Item> indicators = new HashMap<Long, Item>();
        List<Item> stale = new ArrayList<Item>();
        for (Item item : Items.getAllItems()) {
            if (item.getTemplateId() != WurmRailWorld.junctionAlignmentTemplateId) continue;
            Node node = junctions.get(item.getData());
            if (node == null || !onNode(item, node)) {
                stale.add(item);
                continue;
            }
            Item existing = indicators.get(node.markerId);
            // Select deterministically after reload, even when native item iteration changes.
            if (existing == null) indicators.put(node.markerId, item);
            else if (item.getWurmId() < existing.getWurmId()) {
                stale.add(existing);
                indicators.put(node.markerId, item);
            } else stale.add(item);
        }
        for (Item item : stale) {
            try { Items.destroyItem(item.getWurmId()); }
            catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Could not remove stale railway junction indicator " + item.getWurmId(), failure);
            }
        }

        for (Node node : junctions.values()) {
            Item item = indicators.get(node.markerId);
            boolean created = false;
            try {
                if (item == null) {
                    item = WurmRailWorld.spawnJunctionIndicator(node);
                    created = true;
                }
                int mask = validMask(network, node);
                String name = "Junction alignment: " + alignmentName(mask);
                boolean changed = created || item.getData() != node.markerId
                        || (item.getAuxData() & 255) != mask || item.getRotation() != 0
                        || !name.equals(item.getName());
                if (!changed) continue;
                item.setData(node.markerId);
                item.setAuxData((byte)mask);
                item.setRotation(0);
                item.setName(name);
                // Re-announce ground scenery so an already nearby client replaces its mesh.
                item.updateIfGroundItem();
            } catch (Exception failure) {
                if (created && item != null) {
                    try { Items.destroyItem(item.getWurmId()); }
                    catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                LOG.log(Level.WARNING, "Could not update alignment indicator for railway " + node.name, failure);
            }
        }
    }

    private static boolean validTrack(Item item, Node node) {
        return item != null && item.getTemplateId() == WurmRailWorld.trackTemplateId && onGround(item, node)
                && item.getTileX() == node.tileX && item.getTileY() == node.tileY;
    }

    private static boolean onNode(Item item, Node node) {
        return onGround(item, node)
                && Math.abs(item.getPosX() - node.worldX()) < 0.01f
                && Math.abs(item.getPosY() - node.worldY()) < 0.01f;
    }

    private static boolean onGround(Item item, Node node) {
        return item.getParentId() == NO_ID && item.getOwnerId() == NO_ID && !item.isBanked()
                && !item.isMailed() && !item.isTraded() && item.getFloorLevel() == 0 && item.getBridgeId() == node.bridgeId
                && RailwayBridgeDeck.matches(node.tileX,node.tileY,node.layer,node.bridgeId)
                && item.isOnSurface() == (node.layer == 0);
    }

    private static int validMask(RailNetwork network, Node node) {
        int mask = node.getAlignmentMask();
        if (mask < 0 || mask > 255 || Integer.bitCount(mask) != 2) return 0;
        int connections = 0;
        for (String key : node.links) {
            Node other = network.nodes.get(key);
            if (other != null) connections |= 1 << RailNetwork.direction(other.tileX - node.tileX, other.tileY - node.tileY);
        }
        return (mask & connections) == mask ? mask : 0;
    }

    public static String alignmentName(int mask) {
        if (mask < 0 || mask > 255 || Integer.bitCount(mask) != 2) return "Not aligned";
        String first = null;
        for (int direction = 0; direction < 8; direction++) {
            if ((mask & (1 << direction)) == 0) continue;
            if (first == null) first = DIRECTIONS[direction];
            else return first + " \u2194 " + DIRECTIONS[direction];
        }
        return "Not aligned";
    }
}
