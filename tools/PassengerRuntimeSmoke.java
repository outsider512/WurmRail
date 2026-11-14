import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Properties;
import org.wurmrail.RailwayPassengerUi;
import org.wurmrail.RailwayTrackMaterials;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.Platform;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;

/** Native runtime passenger form/queue/cost tests; no actual boarding or client socket required. */
public final class PassengerRuntimeSmoke {
    private static int assertions;

    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(730000032L,true,0);
        position(actor,100,181);
        RailNetwork network=new RailNetwork();
        for(int x=100;x<=110;x++) {
            Node node=network.addNode("passengerRuntime"+x,x,180,0);
            node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();
            if(x>100)network.connect("passengerRuntime"+(x-1),node.name);
        }
        WurmRailRuntime runtime=runtime(network);
        RailwayPassengerUi.BuilderApi builder=(RailwayPassengerUi.BuilderApi)get(runtime,"passengerBuilderApi");
        RailwayPassengerUi.Api journeys=(RailwayPassengerUi.Api)get(runtime,"passengerApi");
        RailwayPassengerUi.BuildRequest pickup=buildRequest(runtime,actor,100,181,0,"Village pickup");
        failure(()->build(runtime,actor,100,181,pickup),"missing materials reject passenger construction");
        require(stations(runtime).platforms.isEmpty()&&RailwayTrackMaterials.count(actor)==0,"failed cost check leaves native world and platform state unchanged");
        Item firstSection=section(actor,63),secondSection=section(actor,64);
        builder.build(actor,100,181,0,pickup);
        require(stations(runtime).platforms.isEmpty()&&RailwayTrackMaterials.count(actor)==2,"passenger construction answer only queues the work");
        runtime.onServerPoll();
        Platform home=stations(runtime).at(100,181,0);
        require(home!=null&&home.mode==PassengerStations.Mode.STATION&&RailwayTrackMaterials.count(actor)==1,"server poll builds a combined station using exactly one crafted section");
        Item homeMarker=Items.getItem(home.itemId);
        require(homeMarker.getTemplateId()==WurmRailWorld.pickupTemplateId&&homeMarker.getName().equals("Station: Village pickup"),"combined station uses its native platform template and clear station display name");
        boolean firstConsumed=!Items.getItemOptional(firstSection.getWurmId()).isPresent();
        boolean secondConsumed=!Items.getItemOptional(secondSection.getWurmId()).isPresent();
        require(firstConsumed!=secondConsumed&&homeMarker.getQualityLevel()==(firstConsumed?63:64)
                &&home.ownerId==actor.getWurmId()&&home.rail.equals("passengerRuntime100"),"platform retains the consumed section's quality, builder ownership and selected adjacent rail");
        require(network.nodes.size()==11,"beside-track passenger platform does not replace or add rail graph nodes");
        Path passengers=((Path)get(runtime,"stateFile")).resolveSibling("passengers.properties");
        require(Files.isRegularFile(passengers)&&PassengerStations.load(passengers).get(home.itemId).name.equals(home.name),"queued build persists platform identity to the world passenger state file");
        failure(()->build(runtime,actor,100,181,pickup),"replayed construction cannot replace an existing platform or consume another section");
        require(RailwayTrackMaterials.count(actor)==1,"replayed construction leaves remaining material untouched");

        RailwayPassengerUi.BuildView forgedView=new RailwayPassengerUi.BuildView("Untrusted choice fixture",
                Collections.singletonList(new RailwayPassengerUi.RailChoice("passengerRuntime110","Far rail")),
                RailwayPassengerUi.Mode.STATION,1,false);
        RailwayPassengerUi.BuildRequest far=RailwayPassengerUi.buildFromAnswers(forgedView,answer("build","rail","0","mode","0","stop_name","Invalid far stop"));
        failure(()->build(runtime,actor,101,181,far),"runtime rejects a selected rail that is not immediately adjacent");
        RailwayPassengerUi.BuildView diagonalView=new RailwayPassengerUi.BuildView("Diagonal choice fixture",
                Collections.singletonList(new RailwayPassengerUi.RailChoice("passengerRuntime100","Diagonal rail")),
                RailwayPassengerUi.Mode.STATION,1,false);
        RailwayPassengerUi.BuildRequest diagonal=RailwayPassengerUi.buildFromAnswers(diagonalView,answer("build","rail","0","mode","0","stop_name","Invalid diagonal stop"));
        failure(()->build(runtime,actor,101,181,diagonal),"platform cannot link only diagonally across a corner");
        require(stations(runtime).platforms.size()==1&&RailwayTrackMaterials.count(actor)==1,"invalid link choices do not consume costs or create world items");

        RailwayPassengerUi.BuildRequest queued=buildRequest(runtime,actor,101,181,0,"Permission recheck");
        builder.build(actor,101,181,0,queued); set(runtime,"builders","Nobody"); runtime.onServerPoll();
        require(stations(runtime).at(101,181,0)==null&&RailwayTrackMaterials.count(actor)==1,"builder ACL is rechecked when a queued passenger build executes");
        set(runtime,"builders","*");
        builder.build(actor,101,181,0,queued); position(actor,50,181); runtime.onServerPoll();
        require(stations(runtime).at(101,181,0)==null&&RailwayTrackMaterials.count(actor)==1,"queued passenger construction rechecks actor reach before payment");

        position(actor,110,181);
        RailwayPassengerUi.BuildRequest dropRequest=buildRequest(runtime,actor,110,181,1,"Mine drop-off");
        builder.build(actor,110,181,0,dropRequest); runtime.onServerPoll();
        Platform mine=stations(runtime).at(110,181,0);
        require(mine!=null&&mine.mode==PassengerStations.Mode.STATION&&RailwayTrackMaterials.count(actor)==0,"legacy drop-off form answers now build a paid combined station");
        require(Items.getItem(mine.itemId).getTemplateId()==WurmRailWorld.pickupTemplateId
                &&Items.getItem(mine.itemId).getName().equals("Station: Mine drop-off"),"all new station construction uses the same native template and station naming");
        position(actor,100,181);
        RailwayPassengerUi.View pickupView=snapshot(runtime,actor,home.itemId);
        require(actor.getVehicle()==-10L&&pickupView.trainEngineId==-10L&&pickupView.destinations.size()==2
                &&destinationIndex(pickupView,home.itemId)>=0&&destinationIndex(pickupView,mine.itemId)>=0,
                "unmounted station visitor can see every connected combined station without a train registration");
        RailwayPassengerUi.Request choose=RailwayPassengerUi.fromAnswers(pickupView,answer("destination","destination_choice",Integer.toString(destinationIndex(pickupView,mine.itemId))));
        journeys.submit(actor,home.itemId,choose);
        require(stations(runtime).destination(actor.getWurmId())==-10L,"personal destination answer is queued rather than immediately persisted");
        runtime.onServerPoll();
        require(stations(runtime).destination(actor.getWurmId())==mine.itemId&&actor.getVehicle()==-10L,"queued selection saves a journey from an unmounted pickup window without boarding or moving the player");
        require(PassengerStations.load(passengers).destination(actor.getWurmId())==mine.itemId,"selected drop-off survives passenger-state reload");

        RailwayPassengerUi.Request staleRename=RailwayPassengerUi.fromAnswers(pickupView,answer("rename","stop_name","Stale name"));
        RailwayPassengerUi.Request rename=RailwayPassengerUi.fromAnswers(snapshot(runtime,actor,home.itemId),answer("rename","stop_name","Village pickup renamed"));
        apply(runtime,actor,home.itemId,rename);
        require(stations(runtime).get(home.itemId).revision==2&&Items.getItem(home.itemId).getName().equals("Station: Village pickup renamed"),"rename updates station revision and native marker together");
        failure(()->apply(runtime,actor,home.itemId,staleRename),"old revision cannot rename the current platform");
        failure(()->apply(runtime,actor,home.itemId,choose),"old platform revision cannot apply a queued old journey choice");
        require(stations(runtime).get(home.itemId).name.equals("Village pickup renamed")&&stations(runtime).destination(actor.getWurmId())==mine.itemId,"stale forms change neither marker name nor selected destination");
        RailwayPassengerUi.Request wrongIdentity=RailwayPassengerUi.fromAnswers(snapshot(runtime,actor,home.itemId),answer("cancel_destination"));
        position(actor,110,181);
        failure(()->apply(runtime,actor,mine.itemId,wrongIdentity),"form bound to another platform identity cannot clear a journey");
        require(stations(runtime).destination(actor.getWurmId())==mine.itemId,"identity mismatch leaves the destination intact");

        Node linked=network.nodes.get(mine.rail); long trackMarker=linked.markerId;
        failure(()->removeTrack(runtime,actor,linked),"track linked to a passenger platform cannot be removed");
        require(network.nodes.get(linked.name)==linked&&Items.getItemOptional(trackMarker).isPresent()&&RailwayTrackMaterials.count(actor)==0,"linked-track protection does not produce a free recovered section");
        RailwayPassengerUi.Request remove=RailwayPassengerUi.fromAnswers(snapshot(runtime,actor,mine.itemId),answer("remove","confirm_remove","true"));
        actor.failDelivery=true;
        try { failure(()->apply(runtime,actor,mine.itemId,remove),"native recovery failure is reported while removing passenger platform"); }
        finally { actor.failDelivery=false; }
        require(stations(runtime).platforms.containsKey(mine.itemId)&&Items.getItemOptional(mine.itemId).isPresent()
                &&stations(runtime).destination(actor.getWurmId())==mine.itemId&&RailwayTrackMaterials.count(actor)==0,
                "failed recovery restores platform, native marker and all selected journeys without creating material");
        stations(runtime).choose(actor.getWurmId()+256,mine.itemId);
        call(runtime,"savePassengers",new Class<?>[0]);
        apply(runtime,actor,mine.itemId,remove);
        require(!stations(runtime).platforms.containsKey(mine.itemId)&&!Items.getItemOptional(mine.itemId).isPresent()&&RailwayTrackMaterials.count(actor)==1,"removal deletes physical platform and recovers exactly one section");
        require(stations(runtime).destination(actor.getWurmId())==-10L&&stations(runtime).destination(actor.getWurmId()+256)==-10L,"removing a station clears every rider's selection for it");
        PassengerStations saved=PassengerStations.load(passengers);
        require(!saved.platforms.containsKey(mine.itemId)&&saved.destination(actor.getWurmId())==-10L,"platform removal and destination cleanup persist together");
        failure(()->apply(runtime,actor,mine.itemId,remove),"old removal request cannot recover another section");
        require(RailwayTrackMaterials.count(actor)==1,"removal replay never duplicates materials");
        removeTrack(runtime,actor,linked);
        require(!network.nodes.containsKey(linked.name)&&!Items.getItemOptional(trackMarker).isPresent()&&RailwayTrackMaterials.count(actor)==2,"linked rail becomes removable after its passenger platform is removed");

        position(actor,100,181);
        RailwayPassengerUi.Request removeHome=RailwayPassengerUi.fromAnswers(snapshot(runtime,actor,home.itemId),answer("remove","confirm_remove","true"));
        apply(runtime,actor,home.itemId,removeHome);
        require(stations(runtime).platforms.isEmpty()&&RailwayTrackMaterials.count(actor)==3,"combined station removal recovers one section and leaves an empty station network");
        require(PassengerStations.load(passengers).platforms.isEmpty(),"final native platform cleanup survives state reload");
        System.out.println("PASS passenger runtime queued paid construction, adjacent rail checks, journey persistence, stale forms, ACL and refund/removal guards ("+assertions+" assertions)");
    }

    private static RailwayPassengerUi.BuildRequest buildRequest(WurmRailRuntime runtime,Creature actor,int x,int y,int mode,String name) throws Exception {
        RailwayPassengerUi.BuildView view=(RailwayPassengerUi.BuildView)call(runtime,"passengerBuildSnapshot",new Class<?>[]{Creature.class,int.class,int.class,int.class},actor,x,y,0);
        return RailwayPassengerUi.buildFromAnswers(view,answer("build","rail","0","mode",Integer.toString(mode),"stop_name",name));
    }
    private static RailwayPassengerUi.View snapshot(WurmRailRuntime runtime,Creature actor,long id) throws Exception {
        return (RailwayPassengerUi.View)call(runtime,"passengerSnapshot",new Class<?>[]{Creature.class,long.class},actor,id);
    }
    private static int destinationIndex(RailwayPassengerUi.View view,long id) {
        for(int i=0;i<view.destinations.size();i++)if(view.destinations.get(i).id==id)return i;
        throw new AssertionError("Connected station was not offered as a destination: "+id);
    }
    private static void build(WurmRailRuntime runtime,Creature actor,int x,int y,RailwayPassengerUi.BuildRequest request) throws Exception {
        call(runtime,"buildPassengerPlatform",new Class<?>[]{Creature.class,int.class,int.class,int.class,RailwayPassengerUi.BuildRequest.class},actor,x,y,0,request);
    }
    private static void apply(WurmRailRuntime runtime,Creature actor,long id,RailwayPassengerUi.Request request) throws Exception {
        call(runtime,"applyPassengerRequest",new Class<?>[]{Creature.class,long.class,RailwayPassengerUi.Request.class},actor,id,request);
    }
    private static void removeTrack(WurmRailRuntime runtime,Creature actor,Node node) throws Exception {
        call(runtime,"removeAndRecover",new Class<?>[]{Creature.class,Node.class},actor,node);
    }
    private static PassengerStations stations(WurmRailRuntime runtime) throws Exception { return (PassengerStations)get(runtime,"passengerStations"); }
    private static Item section(TrackMaterialsSmoke.InventoryActor actor,float quality) throws Exception {
        Item item=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,quality,"Passenger runtime fixture");
        require(actor.inventory.insertItem(item,true),"native passenger construction material inserted into actor inventory");
        return item;
    }
    private static void position(Creature actor,int x,int y) {
        actor.getStatus().getPosition().setPosX(x*4+2); actor.getStatus().getPosition().setPosY(y*4+2);
    }
    private static WurmRailRuntime runtime(RailNetwork network) throws Exception {
        WurmRailRuntime runtime=new WurmRailRuntime();
        Path file=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("runtime-passengers").resolve("network.properties");
        set(runtime,"network",network); set(runtime,"ready",true); set(runtime,"stateFile",file);
        set(runtime,"lastTick",System.nanoTime()); set(runtime,"lastSave",System.currentTimeMillis()); set(runtime,"lastDiscovery",System.nanoTime());
        call(runtime,"initService",new Class<?>[0]); return runtime;
    }
    private static Properties answer(String button,String... fields) {
        Properties answer=new Properties(); answer.setProperty(button,"true");
        for(int index=0;index<fields.length;index+=2)answer.setProperty(fields[index],fields[index+1]); return answer;
    }
    private static Object get(Object object,String name) throws Exception { Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    private static void set(Object object,String name,Object value) throws Exception { Field f=object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object,value); }
    private static Object call(Object object,String name,Class<?>[] types,Object... values) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types); method.setAccessible(true);
        try { return method.invoke(object,values); }
        catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();
            if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause(); throw wrapped;
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void failure(Checked checked,String message) throws Exception {
        try { checked.run(); } catch(IllegalArgumentException|IllegalStateException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void require(boolean condition,String message) { assertions++; if(!condition)throw new AssertionError(message); }
}
