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

/** Track context entry; runtime queues configuration and independently rechecks permissions. */
public final class RailwayFreightActions {
    public interface Api {
        boolean canConfigure(Creature actor);
        void open(Creature actor,Item track);
    }
    private RailwayFreightActions() { }

    public static void register(final Api api) {
        if(api==null)throw new IllegalArgumentException("Freight menu callbacks are required.");
        final ActionEntry entry=ActionEntry.createEntry((short)ModActions.getNextActionId(),
                "Freight stop","checking freight stop",new int[]{0});
        ModActions.registerAction(entry);
        ModActions.registerAction(new ModAction() {
            private boolean accepts(Creature actor,Item item) {
                return actor!=null&&item!=null&&WurmRailWorld.trackTemplateId!=0
                        &&item.getTemplateId()==WurmRailWorld.trackTemplateId
                        &&item.getParentId()==-10L&&item.getOwnerId()==-10L
                        &&!item.isBanked()&&!item.isMailed()&&!item.isTraded()
                        &&actor.isOnSurface()==item.isOnSurface()
                        &&actor.getFloorLevel()==0&&item.getFloorLevel()==0
                        &&actor.getBridgeId()==-10L&&item.getBridgeId()==-10L
                        &&Math.hypot(actor.getPosX()-item.getPosX(),actor.getPosY()-item.getPosY())<=8.0;
            }
            @Override public BehaviourProvider getBehaviourProvider() {
                return new BehaviourProvider() {
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item target) {
                        return accepts(actor,target)?Collections.singletonList(entry):null;
                    }
                    @Override public List<ActionEntry> getBehavioursFor(Creature actor,Item active,Item target) {
                        return getBehavioursFor(actor,target);
                    }
                };
            }
            @Override public ActionPerformer getActionPerformer() {
                return new ActionPerformer() {
                    @Override public short getActionId() {return entry.getNumber();}
                    @Override public boolean action(Action action,Creature actor,Item target,short id,float counter) {
                        if(id==entry.getNumber()&&accepts(actor,target))api.open(actor,target);
                        return true;
                    }
                    @Override public boolean action(Action action,Creature actor,Item active,Item target,short id,float counter) {
                        return action(action,actor,target,id,counter);
                    }
                };
            }
        });
    }
}
