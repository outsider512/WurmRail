package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.structures.BridgePart;
import com.wurmonline.server.structures.Structure;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.BridgeConstants.BridgeMaterial;

/** Access to the native deck on a railway tile. */
final class RailwayBridgeDeck {
    static final long NONE = -10L;

    private RailwayBridgeDeck() { }

    static BridgePart partAt(int x, int y, int layer) {
        return Zones.getBridgePartFor(x, y, layer == 0);
    }

    static String problem(BridgePart part) {
        if (part.getMaterial() == BridgeMaterial.ROPE) return "Railway tracks cannot be laid on rope bridges.";
        VolaTile tile = Zones.getTileOrNull(part.getTileX(), part.getTileY(), part.isOnSurface());
        Structure bridge = tile == null ? null : tile.getStructure();
        if (bridge == null || !bridge.isTypeBridge() || bridge.getWurmId() != part.getStructureId()
                || !bridge.isFinished() || !bridge.isFinalFinished() || !part.isFinished() || part.getDamage() >= 100)
            return "Rails require a completed, intact bridge deck.";
        if (tile.getBridgeParts().length != 1) return "This tile has more than one bridge deck.";
        return null;
    }

    static long idAt(int x, int y, int layer) {
        BridgePart part = partAt(x, y, layer);
        return part == null || problem(part) != null ? NONE : part.getStructureId();
    }

    static boolean matches(int x, int y, int layer, long bridgeId) {
        if (bridgeId == NONE) return partAt(x, y, layer) == null;
        return bridgeId == idAt(x, y, layer);
    }

    static boolean isOnDeck(Creature actor) {
        long bridgeId=actor.getBridgeId();
        return bridgeId>0 && bridgeId==idAt(actor.getTileX(),actor.getTileY(),actor.isOnSurface()?0:-1);
    }

    static boolean isAtRailLevel(Creature actor) {
        return actor.getBridgeId()==NONE ? actor.getFloorLevel()==0 : isOnDeck(actor);
    }

    static boolean isAtItemLevel(Creature actor,long bridgeId,int floorLevel) {
        return actor.getBridgeId()==bridgeId
                && (bridgeId==NONE ? actor.getFloorLevel()==floorLevel : floorLevel==0 && isOnDeck(actor));
    }

    // Native Zones.getNodeHeights uses the north/west edge as the base and
    // applies the signed slope along the axis. Evaluate this tile's plane
    // even on its far edge, where flooring XY would select the next tile.
    static float height(BridgePart part, double x, double y) {
        double progress = part.getDir() == 0 || part.getDir() == 4
                ? y / 4 - part.getTileY() : x / 4 - part.getTileX();
        return (float)((part.getHeightOffset() + part.getSlope() * progress) / 10);
    }

    static boolean hasGroundExit(BridgePart part, int dx, int dy) {
        if (Math.abs(dx) + Math.abs(dy) != 1) return false;
        if (dx < 0) return part.getWestExitFloorLevel() == 0;
        if (dx > 0) return part.getEastExitFloorLevel() == 0;
        if (dy < 0) return part.getNorthExitFloorLevel() == 0;
        return part.getSouthExitFloorLevel() == 0;
    }
}
