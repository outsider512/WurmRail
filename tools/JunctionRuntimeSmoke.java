import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.gotti.wurmunlimited.modsupport.actions.*;
import org.wurmrail.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;

/** Real context action -> server queue -> physical alignment -> saved graph and ground indicator. */
public final class JunctionRuntimeSmoke {
    private static int assertions;
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(940000032L,true,2);
        actor.getStatus().getPosition().setPosX(722); actor.getStatus().getPosition().setPosY(722);
        RailNetwork net=new RailNetwork();
        Node j=net.addNode("nativeSwitch",180,180,0),north=net.addNode("nativeNorth",180,179,0),
                south=net.addNode("nativeSouth",180,181,0),east=net.addNode("nativeEast",181,180,0);
        for(Node n:net.nodes.values())n.markerId=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0).getWurmId();
        net.connect(j.name,north.name); net.connect(j.name,south.name); net.connect(j.name,east.name);
        net.setJunctionAlignment(j.name,17);
        WurmRailRuntime runtime=new WurmRailRuntime();
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("junction-runtime/network.properties");
        set(runtime,"network",net); set(runtime,"ready",true); set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]); call(runtime,"refreshTrackVisuals",new Class<?>[0]);
        int first=ModActions.getNextActionId(); runtime.init();
        short cycleId=registeredAction(first,"Cycle junction alignment"); ActionPerformer cycle=performer(cycleId);
        Item marker=Items.getItem(j.markerId),indicator=indicator(j.markerId);
        check(indicator!=null&&(indicator.getAuxData()&255)==17,"existing physical pair is visible before any menu is opened");
        cycle.action(null,actor,marker,cycleId,1f);
        check(j.getAlignmentMask()==17,"direct click only queues the switch change");
        poll(runtime);
        int after=j.getAlignmentMask();
        check(after!=17&&Integer.bitCount(after)==2&&(after&21)==after,"one direct interaction cycles only existing two-sided alignments");
        check((indicator(j.markerId).getAuxData()&255)==after,"cycle updates the native ground indicator immediately");
        check(RailNetwork.load(state).getJunctionAlignment(j.name)==after,"cycled physical pair persists with native track IDs");
        for(int heading=0;heading<360;heading+=45) {
            actor.getStatus().setRotation(heading);
            call(runtime,"builderSnapshot",new Class<?>[]{Creature.class,int.class,int.class,int.class},actor,180,180,0);
            check(j.getAlignmentMask()==after,"opening controls from another heading does not reinterpret the physical pair");
        }
        Item currentIndicator=indicator(j.markerId);
        cycle.action(null,actor,currentIndicator,cycleId,1f); poll(runtime);
        check(j.getAlignmentMask()!=after,"the visible indicator exposes the same direct cycle as the underlying rail");
        check(indicator(j.markerId).getWurmId()==currentIndicator.getWurmId(),"alignment changes reuse the persistent marker instead of accumulating scenery");

        // A queued click grants no ongoing right to change a junction after walking away.
        int before=j.getAlignmentMask();
        cycle.action(null,actor,marker,cycleId,1f);
        actor.getStatus().getPosition().setPosX(10); poll(runtime);
        check(j.getAlignmentMask()==before,"queued cycle rechecks reach before changing track alignment");
        actor.getStatus().getPosition().setPosX(722);
        set(runtime,"builders","Nobody"); actor.power=0;
        cycle.action(null,actor,marker,cycleId,1f); poll(runtime);
        check(j.getAlignmentMask()==before,"direct cycle rechecks builder access");
        actor.power=2; set(runtime,"builders","*");

        RailwayBuilderUi.View view=(RailwayBuilderUi.View)call(runtime,"builderSnapshot",new Class<?>[]{Creature.class,int.class,int.class,int.class},actor,180,180,0);
        Properties select=new Properties(); select.setProperty("alignment","true"); select.setProperty("alignment_choice","2");
        RailwayBuilderUi.Request request=RailwayBuilderUi.fromAnswers(view,select);
        call(runtime,"applyBuilder",new Class<?>[]{Creature.class,int.class,int.class,int.class,RailwayBuilderUi.Request.class},actor,180,180,0,request);
        check(j.getAlignmentMask()==request.alignmentMask,"advanced window sets the same absolute pair as the direct action");

        call(runtime,"execute",new Class<?>[]{Creature.class,String.class},actor,"align nativeSwitch S E");
        check(j.getAlignmentMask()==20,"CLI accepts both absolute compass sides");
        call(runtime,"execute",new Class<?>[]{Creature.class,String.class},actor,"align nativeSwitch E S");
        check(j.getAlignmentMask()==20,"reversed command order leaves the same bidirectional state");
        fail(()->call(runtime,"execute",new Class<?>[]{Creature.class,String.class},actor,"align nativeSwitch W S"),"nonexistent side cannot be aligned");
        check(j.getAlignmentMask()==20,"invalid command leaves the physical switch unchanged");
        call(runtime,"execute",new Class<?>[]{Creature.class,String.class},actor,"switch nativeSwitch nativeNorth");
        check(j.getAlignmentMask()==17,"legacy single-exit command safely converts to its closest opposite pair");

        Item engine=WurmRailWorld.spawnLocomotive(actor,north.worldX(),north.worldY(),0);
        Train owner=net.createTrain("junctionHolder",actor.getWurmId(),Collections.singletonList(engine.getWurmId()),north.name,j.name);
        check(net.requestJunctionAlignment(owner.name,j.name,17),"automation can request an explicit pair without relative steering");
        fail(()->call(runtime,"cycleJunction",new Class<?>[]{Creature.class,long.class},actor,marker.getWurmId()),"a reserved switch cannot be thrown through the runtime action");
        check(j.getAlignmentMask()==17&&owner.name.equals(j.getAlignmentOwner()),"refused cycle preserves the pair and its reservation");
        check(net.releaseJunctionAlignment(owner.name,j.name),"unoccupied owner reservation is explicitly releasable");
        net.removeTrain(owner.name); Items.destroyItem(engine.getWurmId());
        call(runtime,"cycleJunction",new Class<?>[]{Creature.class,long.class},actor,indicator(j.markerId).getWurmId());
        check(j.getAlignmentMask()!=17,"switch becomes available after the reservation is released");
        System.out.println("PASS native direct junction cycling, absolute heading-independent UI/commands, persistent visuals, permission/range and reservation guards ("+assertions+" assertions)");
    }
    private static void poll(WurmRailRuntime runtime) throws Exception {
        set(runtime,"lastTick",System.nanoTime()); set(runtime,"lastDiscovery",System.nanoTime()); set(runtime,"lastSave",System.currentTimeMillis());
        runtime.onServerPoll();
    }
    private static Item indicator(long parent) {
        for(Item i:Items.getAllItems())if(WurmRailWorld.isJunctionIndicator(i)&&i.getData()==parent)return i; return null;
    }
    private static short registeredAction(int first,String name) {
        short found=-1;
        for(int id=first;id<ModActions.getNextActionId();id++) {
            com.wurmonline.server.behaviours.ActionEntry entry=com.wurmonline.server.behaviours.Actions.actionEntrys[id];
            if(entry!=null&&name.equals(entry.getActionString())) {
                check(found==-1,"runtime registers one direct junction cycle action");found=entry.getNumber();
            }
        }
        check(found>=first,"direct junction cycle action is registered by name");return found;
    }
    @SuppressWarnings("unchecked") private static ActionPerformer performer(short id) throws Exception {
        Field f=ModActions.class.getDeclaredField("actionPerformers"); f.setAccessible(true); Object chain=((Map<Short,?>)f.get(null)).get(id);
        Field list=chain.getClass().getDeclaredField("actionPerformers"); list.setAccessible(true); return ((List<ActionPerformer>)list.get(chain)).get(0);
    }
    private static void set(Object target,String key,Object value) throws Exception { Field f=target.getClass().getDeclaredField(key); f.setAccessible(true); f.set(target,value); }
    private static Object call(Object target,String key,Class<?>[] types,Object...args) throws Exception {
        Method m=target.getClass().getDeclaredMethod(key,types); m.setAccessible(true);
        try { return m.invoke(target,args); } catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();
            if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause(); throw wrapped;
        }
    }
    private interface Check { void run() throws Exception; }
    private static void fail(Check check,String message) throws Exception {
        boolean rejected=false; try { check.run(); }catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; } check(rejected,message);
    }
    private static void check(boolean value,String message) { assertions++; if(!value)throw new AssertionError(message); }
}
