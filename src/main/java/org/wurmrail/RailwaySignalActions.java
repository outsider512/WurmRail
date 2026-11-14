package org.wurmrail;

import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.util.Collections;
import java.util.List;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModAction;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;

/** Readable by nearby players; the runtime separately authorizes and queues configuration changes. */
public final class RailwaySignalActions {
    public interface Api { void open(Creature actor, long clickedItemId); }
    private RailwaySignalActions() { }

    public static void register(final Api api) {
        if (api == null) throw new IllegalArgumentException("Signal menu callbacks are required.");
        final ActionEntry entry = ActionEntry.createEntry((short)ModActions.getNextActionId(),
                "Railway signals", "checking railway signals", new int[] {0});
        ModActions.registerAction(entry);
        ModActions.registerAction(new ModAction() {
            private boolean accepts(Creature p, Item item) {
                return p != null && item != null
                        && ((WurmRailWorld.trackTemplateId != 0 && item.getTemplateId() == WurmRailWorld.trackTemplateId)
                            || WurmRailWorld.isJunctionIndicator(item) || WurmRailWorld.isSignal(item))
                        && item.getParentId() == -10L && item.getOwnerId() == -10L
                        && !item.isBanked() && !item.isMailed() && !item.isTraded()
                        && p.isOnSurface() == item.isOnSurface() && p.getFloorLevel() == 0 && item.getFloorLevel() == 0
                        && p.getBridgeId() == -10L && item.getBridgeId() == -10L
                        && Math.hypot(p.getPosX() - item.getPosX(), p.getPosY() - item.getPosY()) <= 8.0;
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
                    @Override public boolean action(Action action, Creature p, Item target, short id, float counter) {
                        if (id == entry.getNumber() && accepts(p, target)) api.open(p, target.getWurmId());
                        return true;
                    }
                    @Override public boolean action(Action action, Creature p, Item active, Item target, short id, float counter) {
                        return action(action, p, target, id, counter);
                    }
                };
            }
        });
    }
}
