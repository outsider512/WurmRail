package org.wurmrail;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.structures.BridgePart;
import java.util.Collections;
import java.util.List;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

/** Native context-menu entry points. The runtime queues and validates all work. */
public final class RailwayMenuActions {
    private static final double BUILD_RANGE = 16.0;
    private static final double VEHICLE_RANGE = 8.0;

    public interface Api {
        boolean canBuild(Creature performer);
        void openBuilder(Creature performer, int tileX, int tileY, int layer);
        void openVehicle(Creature performer, Item target, String verb);
        /** Runtime implementations queue and revalidate the graph node before changing a physical pair. */
        default void cycleAlignment(Creature performer, Item target) { }
    }

    private RailwayMenuActions() { }

    public static void register(final Api api) {
        if (api == null) throw new IllegalArgumentException("Railway menu callbacks are required");
        registerTerrain(api);
        registerItem(api, "Railway track", "checking the railway", null);
        registerItem(api, "Train controls", "checking train controls", "panel");
        registerItem(api, "Couple rail wagon", "checking wagon couplings", "couple");
        // Native passenger embark/disembark and Open already provide these
        // item interactions, including occupied-seat and already-mounted gates.
        // The control window retains its own Board and Cargo shortcuts.
        registerCycleAlignment(api);
    }

    private static ActionEntry entry(String name, String verb) {
        short id = (short)ModActions.getNextActionId();
        ActionEntry entry = ActionEntry.createEntry(id, name, verb, new int[] {0});
        ModActions.registerAction(entry);
        return entry;
    }

    private static boolean groundReachable(Creature p, int x, int y, boolean surface) {
        return p != null && x >= 0 && y >= 0 && p.isOnSurface() == surface
                && p.getFloorLevel() == 0 && p.getBridgeId() == -10L
                && Math.hypot(p.getPosX() - (x * 4.0 + 2), p.getPosY() - (y * 4.0 + 2)) <= BUILD_RANGE;
    }

    private static boolean builderReachable(Api api, Creature p, int x, int y, boolean surface) {
        return groundReachable(p, x, y, surface) && api.canBuild(p);
    }

    private static boolean itemReachable(Creature p, Item item, double range) {
        return p != null && item != null && item.getParentId() == -10L
                && !item.isBanked() && !item.isMailed() && !item.isTraded()
                && p.isOnSurface() == item.isOnSurface()
                && RailwayBridgeDeck.isAtItemLevel(p,item.getBridgeId(),item.getFloorLevel())
                && Math.hypot(p.getPosX() - item.getPosX(), p.getPosY() - item.getPosY()) <= range;
    }

    private static boolean isTrack(Item item) {
        return item != null && WurmRailWorld.trackTemplateId != 0
                && (item.getTemplateId() == WurmRailWorld.trackTemplateId || WurmRailWorld.isJunctionIndicator(item));
    }

    private static void registerCycleAlignment(final Api api) {
        final ActionEntry entry = entry("Cycle junction alignment", "changing the junction alignment");
        ModActions.registerAction(new ModAction() {
            private boolean accepts(Creature p, Item item) {
                return isTrack(item) && (WurmRailWorld.isJunctionIndicator(item)
                        || Integer.bitCount(item.getAuxData() & 255) >= 3)
                        && itemReachable(p,item,BUILD_RANGE)
                        && RailwayBridgeDeck.isAtRailLevel(p) && api.canBuild(p);
            }
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item target) {
                        return accepts(p,target) ? Collections.singletonList(entry) : null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item active, Item target) {
                        return getBehavioursFor(p,target);
                    }
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId() { return entry.getNumber(); }
                    @Override public boolean action(Action action, Creature p, Item target, short num, float counter) {
                        if(num==entry.getNumber() && accepts(p,target)) api.cycleAlignment(p,target);
                        return true;
                    }
                    @Override public boolean action(Action action, Creature p, Item active, Item target, short num, float counter) {
                        return action(action,p,target,num,counter);
                    }
                };
            }
        });
    }

    private static void registerItem(final Api api, String name, String verb, final String operation) {
        final ActionEntry entry = entry(name, verb);
        ModActions.registerAction(new ModAction() {
            private boolean accepts(Creature p, Item item) {
                if (operation == null) return isTrack(item) && itemReachable(p, item, BUILD_RANGE)
                        && RailwayBridgeDeck.isAtRailLevel(p) && api.canBuild(p);
                return WurmRailWorld.isRailCar(item) && itemReachable(p, item, VEHICLE_RANGE);
            }
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item target) {
                        return accepts(p, target) ? Collections.singletonList(entry) : null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item active, Item target) {
                        return getBehavioursFor(p, target);
                    }
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId() { return entry.getNumber(); }
                    @Override public boolean action(Action action, Creature p, Item target, short num, float counter) {
                        if (num == entry.getNumber() && accepts(p, target)) {
                            if (operation == null) api.openBuilder(p, target.getTileX(), target.getTileY(), target.isOnSurface() ? 0 : -1);
                            else api.openVehicle(p, target, operation);
                        }
                        return true;
                    }
                    @Override public boolean action(Action action, Creature p, Item active, Item target, short num, float counter) {
                        return action(action, p, target, num, counter);
                    }
                };
            }
        });
    }

    private static void registerTerrain(final Api api) {
        final ActionEntry entry = entry("Build railway", "planning the railway");
        ModActions.registerAction(new ModAction() {
            private List<ActionEntry> available(Creature p, int x, int y, boolean surface) {
                return reachable(p,x,y,surface) ? Collections.singletonList(entry) : null;
            }
            private boolean reachable(Creature p,int x,int y,boolean surface) {
                if(builderReachable(api,p,x,y,surface))return true;
                return p!=null&&p.getBridgeId()>0&&api.canBuild(p)
                        &&deckReachable(p,surface,RailwayBridgeDeck.partAt(x,y,surface?0:-1));
            }
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, boolean surface, BridgePart deck) {
                        return deckReachable(p,surface,deck) && api.canBuild(p) ? Collections.singletonList(entry) : null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item active, boolean surface, BridgePart deck) {
                        return getBehavioursFor(p,surface,deck);
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, int x, int y, boolean surface, int tile) {
                        return available(p, x, y, surface);
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item active, int x, int y, boolean surface, int tile) {
                        return available(p, x, y, surface);
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, int x, int y, boolean surface, int tile, int dir) {
                        return dir == 0 ? available(p, x, y, surface) : null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature p, Item active, int x, int y, boolean surface, int tile, int dir) {
                        return dir == 0 ? available(p, x, y, surface) : null;
                    }
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId() { return entry.getNumber(); }
                    @Override public boolean action(Action action, Creature p, boolean surface, BridgePart deck, int encodedTile, short num, float counter) {
                        if(num==entry.getNumber()&&deckReachable(p,surface,deck)&&api.canBuild(p))
                            api.openBuilder(p,deck.getTileX(),deck.getTileY(),surface?0:-1);
                        return true;
                    }
                    @Override public boolean action(Action action, Creature p, Item active, boolean surface, BridgePart deck, int encodedTile, short num, float counter) {
                        return action(action,p,surface,deck,encodedTile,num,counter);
                    }
                    private boolean submit(Creature p, int x, int y, boolean surface, int heightOffset, int dir, short num) {
                        if (num == entry.getNumber() && (heightOffset == 0 || p != null && RailwayBridgeDeck.isOnDeck(p)) && dir == 0
                                && reachable(p,x,y,surface))
                            api.openBuilder(p, x, y, surface ? 0 : -1);
                        return true;
                    }
                    @Override public boolean action(Action action, Creature p, int x, int y, boolean surface, int tile, short num, float counter) {
                        return submit(p, x, y, surface, 0, 0, num);
                    }
                    @Override public boolean action(Action action, Creature p, Item active, int x, int y, boolean surface, int heightOffset, int tile, short num, float counter) {
                        return submit(p, x, y, surface, heightOffset, 0, num);
                    }
                    @Override public boolean action(Action action, Creature p, int x, int y, boolean surface, int tile, int dir, short num, float counter) {
                        return submit(p, x, y, surface, 0, dir, num);
                    }
                    @Override public boolean action(Action action, Creature p, Item active, int x, int y, boolean surface, int heightOffset, int tile, int dir, short num, float counter) {
                        return submit(p, x, y, surface, heightOffset, dir, num);
                    }
                };
            }
        });
    }

    private static boolean deckReachable(Creature p, boolean surface, BridgePart deck) {
        return p!=null&&deck!=null&&p.isOnSurface()==surface&&deck.isOnSurface()==surface
                &&p.getBridgeId()==deck.getStructureId()&&RailwayBridgeDeck.isOnDeck(p)
                &&Math.hypot(p.getPosX()-(deck.getTileX()*4.0+2),p.getPosY()-(deck.getTileY()*4.0+2))<=BUILD_RANGE;
    }
}
