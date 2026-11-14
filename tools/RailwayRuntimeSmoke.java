import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import org.wurmrail.RailwayBuilderUi;
import org.wurmrail.RailwayTrackMaterials;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;

/** Exercises runtime form handlers and their server-poll queue against native items and terrain. */
public final class RailwayRuntimeSmoke {
    private static int assertions;

    public static void run() throws Exception {
        builderTransactions();
        trainIdentityAndSettings();
        System.out.println("PASS commandless runtime queued mutations, paid construction, stale forms and train settings ("
                + assertions + " assertions)");
    }

    private static void builderTransactions() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(720000032L,true,0);
        RailNetwork network=new RailNetwork();
        WurmRailRuntime runtime=runtime(network,"builder");
        RailwayBuilderUi.View empty=builder(runtime,actor,40,120);
        RailwayBuilderUi.Request place=RailwayBuilderUi.fromAnswers(empty,answer("place","direction","2","count","3"));
        expectFailure(()->applyBuilder(runtime,actor,40,120,place),"missing materials reject construction");
        require(network.nodes.isEmpty()&&RailwayTrackMaterials.count(actor)==0,"failed cost check leaves graph and inventory untouched");
        for(int index=0;index<3;index++) {
            Item section=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,40+index,"Runtime fixture");
            require(actor.inventory.insertItem(section,true),"native building sections inserted");
        }
        RailwayBuilderUi.Api api=(RailwayBuilderUi.Api)get(runtime,"builderApi");
        api.submit(actor,40,120,0,place);
        require(network.nodes.isEmpty()&&RailwayTrackMaterials.count(actor)==3,"form answer only queues construction");
        runtime.onServerPoll();
        require(network.nodes.size()==3&&RailwayTrackMaterials.count(actor)==0,"server poll builds three paid sections");
        Node start=nodeAt(network,40,120),middle=nodeAt(network,41,120),end=nodeAt(network,42,120);
        require(start!=null&&middle!=null&&end!=null&&start.links.contains(middle.name)&&middle.links.contains(end.name),
                "requested line is connected without manual node registration");
        java.util.Set<Integer> qualities=new java.util.HashSet<Integer>();
        for(Node node:network.nodes.values()) qualities.add((int)Items.getItem(node.markerId).getQualityLevel());
        require(qualities.contains(40)&&qualities.contains(41)&&qualities.contains(42),"placed models retain crafted section qualities");

        RailwayBuilderUi.View oldView=builder(runtime,actor,40,120);
        RailwayBuilderUi.Request station=RailwayBuilderUi.fromAnswers(oldView,
                answer("station","station_name","Riverside","dwell","11"));
        long original=start.markerId;
        Items.destroyItem(original);
        start.markerId=WurmRailWorld.spawnTrackMarker(actor,start.worldX(),start.worldY(),0).getWurmId();
        expectFailure(()->applyBuilder(runtime,actor,40,120,station),"replaced track rejects old form identity");
        require(!start.station,"stale station form does not change replacement track");
        applyBuilder(runtime,actor,40,120,RailwayBuilderUi.fromAnswers(builder(runtime,actor,40,120),
                answer("station","station_name","Riverside","dwell","11")));
        require(start.station&&start.dwellSeconds==11&&Items.getItem(start.markerId).getName().equals("Station: Riverside"),
                "station name, platform and dwell apply together");

        Node startNorth=network.addNode("rollbackStartNorth",40,119,0);
        Node startSouth=network.addNode("rollbackStartSouth",40,121,0);
        Node middleNorth=network.addNode("rollbackMiddleNorth",41,119,0);
        for(Node branch:Arrays.asList(startNorth,startSouth,middleNorth))
            branch.markerId=WurmRailWorld.spawnTrackMarker(actor,branch.worldX(),branch.worldY(),0).getWurmId();
        network.connect(start.name,startNorth.name); network.connect(start.name,startSouth.name);
        network.connect(middle.name,middleNorth.name);
        applyBuilder(runtime,actor,40,120,RailwayBuilderUi.fromAnswers(builder(runtime,actor,40,120),
                answer("alignment","alignment_choice","1")));
        applyBuilder(runtime,actor,41,120,RailwayBuilderUi.fromAnswers(builder(runtime,actor,41,120),
                answer("alignment","alignment_choice","2")));
        require(start.getAlignmentMask()==5&&middle.getAlignmentMask()==65,
                "builder applies captured connected compass pairs without a train-relative exit");
        RailwayBuilderUi.Request remove=RailwayBuilderUi.fromAnswers(builder(runtime,actor,40,120),
                answer("remove","confirm_remove","true"));
        long beforeFailedRemoval=start.markerId;
        actor.failDelivery=true;
        try {
            expectFailure(()->applyBuilder(runtime,actor,40,120,remove),"native recovery delivery failure is reported");
        } finally { actor.failDelivery=false; }
        start=nodeAt(network,40,120);
        require(start!=null&&start.markerId==beforeFailedRemoval&&Items.getItemOptional(beforeFailedRemoval).isPresent(),
                "failed recovery keeps the original physical marker and restores the logical track");
        require(start.links.contains(middle.name)&&middle.links.contains(start.name)
                        &&start.getAlignmentMask()==5&&middle.getAlignmentMask()==65,
                "failed recovery restores both track links and every affected physical junction alignment");
        require(start.station&&start.dwellSeconds==11&&RailwayTrackMaterials.count(actor)==0,
                "failed recovery preserves station data and never creates a free section");
        applyBuilder(runtime,actor,40,120,remove);
        require(network.nodes.size()==5&&RailwayTrackMaterials.count(actor)==1&&!Items.getItemOptional(start.markerId).isPresent(),
                "removal recovers one section and removes its graph/model together");
        expectFailure(()->applyBuilder(runtime,actor,40,120,remove),"old removal form cannot recover a second section");
        require(RailwayTrackMaterials.count(actor)==1,"replayed removal does not duplicate material");

        // Opening a window grants no lasting permission or reach to mutate the world.
        RailwayBuilderUi.Request remote=RailwayBuilderUi.fromAnswers(builder(runtime,actor,40,120),
                answer("place","direction","0","count","1"));
        api.submit(actor,40,120,0,remote);
        actor.getStatus().getPosition().setPosX(10);
        runtime.onServerPoll();
        require(nodeAt(network,40,120)==null&&RailwayTrackMaterials.count(actor)==1,
                "queued action rechecks distance before consuming materials");
        actor.getStatus().getPosition().setPosX(162);
        api.submit(actor,40,120,0,remote);
        set(runtime,"builders","Nobody");
        runtime.onServerPoll();
        require(nodeAt(network,40,120)==null&&RailwayTrackMaterials.count(actor)==1,
                "queued action rechecks builder access before mutation");
        set(runtime,"builders","*");

        // Removing a graph node used by an existing train is rejected before recovery.
        Item engine=WurmRailWorld.spawnLocomotive(actor,middle.worldX(),middle.worldY(),0);
        Train train=network.createTrain("RuntimeBuilderTrain",actor.getWurmId(),Collections.singletonList(engine.getWurmId()),middle.name,end.name);
        RailwayBuilderUi.Request occupied=RailwayBuilderUi.fromAnswers(builder(runtime,actor,41,120),
                answer("remove","confirm_remove","true"));
        expectFailure(()->applyBuilder(runtime,actor,41,120,occupied),"occupied track cannot be removed");
        require(network.nodes.get(middle.name)==middle&&RailwayTrackMaterials.count(actor)==1,
                "occupied removal preserves node and never gives a free section");
        network.removeTrain(train.name); Items.destroyItem(engine.getWurmId());
    }

    private static void trainIdentityAndSettings() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(720001032L,true,2);
        actor.getStatus().getPosition().setPosX(70*4+2);
        actor.getStatus().getPosition().setPosY(120*4+2);
        RailNetwork network=new RailNetwork();
        Node a=network.addNode("runtimeA",70,120,0),b=network.addNode("runtimeB",71,120,0),c=network.addNode("runtimeC",72,120,0);
        for(Node node:network.nodes.values()) node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();
        network.connect(a.name,b.name); network.connect(b.name,c.name);
        WurmRailRuntime runtime=runtime(network,"train");
        Item engine=WurmRailWorld.spawnLocomotive(actor,a.worldX(),a.worldY(),0);
        RailwayTrainUi.View first=(RailwayTrainUi.View)call(runtime,"trainSnapshot",new Class<?>[]{Creature.class,long.class},actor,engine.getWurmId());
        require(first.train!=null&&network.trains.size()==1,"opening controls automatically sets up locomotive on connected track");
        Train train=first.train;
        double originalSpeed=train.maxSpeed;
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0.1,99})
            expectFailure(()->applyTrain(runtime,actor,engine,train,RailwayTrainUi.Request.speed(invalid)),"runtime rejects invalid speed");
        require(train.maxSpeed==originalSpeed,"invalid speeds leave previous tuning untouched");
        network.setStation(c.name,true,7);
        applyTrain(runtime,actor,engine,train,RailwayTrainUi.Request.route(RailwayTrainUi.Action.ROUTE_SAVE,Collections.singletonList(c.name)));
        require(train.automated&&train.route.equals(Collections.singletonList(c.name)),"station route is selected without node-name commands");
        expectFailure(()->applyTrain(runtime,actor,engine,train,RailwayTrainUi.Request.route(
                RailwayTrainUi.Action.ROUTE_SAVE,Arrays.asList(c.name,"missingStation"))),"missing station rejects full route change");
        require(train.route.equals(Collections.singletonList(c.name)),"invalid route preserves previous itinerary");

        network.removeTrain(train.name);
        Train replacement=network.createTrain(train.name,actor.getWurmId(),Collections.singletonList(engine.getWurmId()),a.name,b.name);
        expectFailure(()->applyTrain(runtime,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START)),
                "train name reuse cannot authorize a stale form for a different train object");
        require(!replacement.powered,"stale controls never start replacement train");
        RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        api.submit(actor,engine.getWurmId(),replacement,RailwayTrainUi.Request.speed(1.25));
        require(replacement.maxSpeed!=1.25,"train form answer queues tuning rather than mutating immediately");
        runtime.onServerPoll();
        require(replacement.maxSpeed==1.25,"server poll applies validated train tuning");
    }

    private static WurmRailRuntime runtime(RailNetwork network,String suffix) throws Exception {
        WurmRailRuntime runtime=new WurmRailRuntime();
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("runtime-ui-"+suffix).resolve("network.properties");
        set(runtime,"network",network); set(runtime,"ready",true); set(runtime,"stateFile",state);
        set(runtime,"lastTick",System.nanoTime()); set(runtime,"lastSave",System.currentTimeMillis());
        call(runtime,"initService",new Class<?>[0]);
        return runtime;
    }

    private static RailwayBuilderUi.View builder(WurmRailRuntime runtime,Creature actor,int x,int y) throws Exception {
        return (RailwayBuilderUi.View)call(runtime,"builderSnapshot",new Class<?>[]{Creature.class,int.class,int.class,int.class},actor,x,y,0);
    }
    private static void applyBuilder(WurmRailRuntime runtime,Creature actor,int x,int y,RailwayBuilderUi.Request request) throws Exception {
        call(runtime,"applyBuilder",new Class<?>[]{Creature.class,int.class,int.class,int.class,RailwayBuilderUi.Request.class},actor,x,y,0,request);
    }
    private static void applyTrain(WurmRailRuntime runtime,Creature actor,Item engine,Train expected,RailwayTrainUi.Request request) throws Exception {
        call(runtime,"applyTrainRequest",new Class<?>[]{Creature.class,long.class,Train.class,RailwayTrainUi.Request.class},actor,engine.getWurmId(),expected,request);
    }
    private static Node nodeAt(RailNetwork network,int x,int y) {
        for(Node node:network.nodes.values()) if(node.tileX==x&&node.tileY==y&&node.layer==0) return node;
        return null;
    }
    private static Properties answer(String button,String... fields) {
        Properties result=new Properties(); result.setProperty(button,"true");
        for(int index=0;index<fields.length;index+=2) result.setProperty(fields[index],fields[index+1]);
        return result;
    }
    private static Object get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static Object call(Object object,String name,Class<?>[] types,Object... arguments) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types); method.setAccessible(true);
        try { return method.invoke(object,arguments); }
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof Exception) throw (Exception)failure.getCause();
            if(failure.getCause() instanceof Error) throw (Error)failure.getCause();
            throw failure;
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action,String message) throws Exception {
        boolean rejected=false;
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { rejected=true; }
        require(rejected,message);
    }
    private static void require(boolean success,String message) {
        assertions++;
        if(!success) throw new AssertionError(message);
    }
}
