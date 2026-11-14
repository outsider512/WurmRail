package org.wurmrail;

import com.wurmonline.server.behaviours.*;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.util.*;
import org.gotti.wurmunlimited.modsupport.actions.*;

/** Typed entry points; runtime revalidates every queued action. */
public final class RailwayPassengerActions {
    public interface Api {
        boolean canBuild(Creature actor);
        void build(Creature actor,int x,int y,int layer);
        void platform(Creature actor,long itemId);
        void journey(Creature actor,long clickedCar);
    }
    private RailwayPassengerActions() { }
    public static void register(Api api) {
        terrain(api); item(api,false,"Station"); item(api,true,"Choose station destination");
    }
    private static ActionEntry entry(String label) {
        ActionEntry e=ActionEntry.createEntry((short)ModActions.getNextActionId(),label,"using passenger railway",new int[]{0});
        ModActions.registerAction(e); return e;
    }
    private static boolean ground(Creature p,int x,int y,boolean surface) {
        return p!=null&&x>=0&&y>=0&&p.isOnSurface()==surface&&p.getFloorLevel()==0&&p.getBridgeId()==-10L
                &&Math.hypot(p.getPosX()-(x*4.0+2),p.getPosY()-(y*4.0+2))<=16;
    }
    private static void terrain(final Api api) {
        final ActionEntry e=entry("Build station");
        ModActions.registerAction(new ModAction() {
            List<ActionEntry> menu(Creature p,int x,int y,boolean s) { return ground(p,x,y,s)&&api.canBuild(p)?Collections.singletonList(e):null; }
            @Override public BehaviourProvider getBehaviourProvider() { return new BehaviourProvider() {
                @Override public List<ActionEntry> getBehavioursFor(Creature p,int x,int y,boolean s,int tile) { return menu(p,x,y,s); }
                @Override public List<ActionEntry> getBehavioursFor(Creature p,Item a,int x,int y,boolean s,int tile) { return menu(p,x,y,s); }
                @Override public List<ActionEntry> getBehavioursFor(Creature p,int x,int y,boolean s,int tile,int dir) { return dir==0?menu(p,x,y,s):null; }
                @Override public List<ActionEntry> getBehavioursFor(Creature p,Item a,int x,int y,boolean s,int tile,int dir) { return dir==0?menu(p,x,y,s):null; }
            }; }
            @Override public ActionPerformer getActionPerformer() { return new ActionPerformer() {
                @Override public short getActionId() { return e.getNumber(); }
                boolean submit(Creature p,int x,int y,boolean s,int height,int dir,short id) {
                    if(id==e.getNumber()&&height==0&&dir==0&&ground(p,x,y,s)&&api.canBuild(p))api.build(p,x,y,s?0:-1); return true;
                }
                @Override public boolean action(Action a,Creature p,int x,int y,boolean s,int tile,short id,float c) { return submit(p,x,y,s,0,0,id); }
                @Override public boolean action(Action a,Creature p,Item active,int x,int y,boolean s,int height,int tile,short id,float c) { return submit(p,x,y,s,height,0,id); }
                @Override public boolean action(Action a,Creature p,int x,int y,boolean s,int tile,int dir,short id,float c) { return submit(p,x,y,s,0,dir,id); }
                @Override public boolean action(Action a,Creature p,Item active,int x,int y,boolean s,int height,int tile,int dir,short id,float c) { return submit(p,x,y,s,height,dir,id); }
            }; }
        });
    }
    private static void item(final Api api,final boolean journey,String label) {
        final ActionEntry e=entry(label);
        ModActions.registerAction(new ModAction() {
            boolean accepts(Creature p,Item i) {
                return p!=null&&i!=null&&(journey?WurmRailWorld.isRailCar(i):WurmRailWorld.isPassengerPlatform(i))
                        &&i.getParentId()==-10L&&p.isOnSurface()==i.isOnSurface()&&p.getFloorLevel()==i.getFloorLevel()
                        &&p.getBridgeId()==i.getBridgeId()&&Math.hypot(p.getPosX()-i.getPosX(),p.getPosY()-i.getPosY())<=8;
            }
            @Override public BehaviourProvider getBehaviourProvider() { return new BehaviourProvider() {
                @Override public List<ActionEntry> getBehavioursFor(Creature p,Item i) { return accepts(p,i)?Collections.singletonList(e):null; }
                @Override public List<ActionEntry> getBehavioursFor(Creature p,Item a,Item i) { return getBehavioursFor(p,i); }
            }; }
            @Override public ActionPerformer getActionPerformer() { return new ActionPerformer() {
                @Override public short getActionId() { return e.getNumber(); }
                @Override public boolean action(Action a,Creature p,Item i,short id,float c) {
                    if(id==e.getNumber()&&accepts(p,i)) { if(journey)api.journey(p,i.getWurmId()); else api.platform(p,i.getWurmId()); } return true;
                }
                @Override public boolean action(Action a,Creature p,Item active,Item i,short id,float c) { return action(a,p,i,id,c); }
            }; }
        });
    }
}
