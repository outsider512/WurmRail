import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.RailwayTrainUi.Action;
import org.wurmrail.RailwayTrainUi.Choice;
import org.wurmrail.RailwayTrainUi.Request;
import org.wurmrail.RailwayTrainUi.View;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.Mode;
import org.wurmrail.core.PassengerStations.Platform;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;

/** Native regression for using ordinary side platforms as automatic train route stops. */
public final class PlatformRouteSmoke {
    private static int assertions;

    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(980000032L,true,0);
        position(actor,188,240);
        RailNetwork network=new RailNetwork();
        for(int x=184;x<=224;x++) {
            Node n=track(network,actor,"platformRoute"+x,x,240);
            if(x>184)network.connect("platformRoute"+(x-1),n.name);
        }
        for(int y=239;y>=230;y--) {
            Node n=track(network,actor,"platformBranch"+y,204,y);
            network.connect(y==239?"platformRoute204":"platformBranch"+(y+1),n.name);
        }
        Node start=network.nodes.get("platformRoute188");
        Item engine=WurmRailWorld.spawnLocomotive(actor,start.worldX(),start.worldY(),0);
        Train train=network.createTrain("platformOnlyRoute",actor.getWurmId(),Collections.singletonList(engine.getWurmId()),start.name,"platformRoute189");
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("platform-route/network.properties");
        WurmRailRuntime runtime=new WurmRailRuntime();
        set(runtime,"network",network);set(runtime,"ready",true);set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);
        PassengerStations platforms=stations(runtime);
        Platform home=platform(platforms,actor,network.nodes.get("platformRoute196"),196,241,"Village drop-off",Mode.DROPOFF);
        Platform mine=platform(platforms,actor,network.nodes.get("platformRoute220"),220,241,"Mine drop-off",Mode.DROPOFF);
        RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        View two=api.snapshot(actor,engine.getWurmId());
        check(!network.nodes.get(home.rail).station&&!network.nodes.get(mine.rail).station,"fixture has two migrated side stations and no track stations");
        check(two.stations.size()==2&&offered(two,home.rail)&&offered(two,mine.rail),"both legacy drop-off station models appear directly in the automatic route choices");
        check(label(two,home.rail).contains(home.name)&&label(two,mine.rail).contains(mine.name),"route choices use the players' platform names");
        check(label(two,home.rail).contains("[Station]")&&home.mode==Mode.STATION&&mine.mode==Mode.STATION,
                "legacy drop-off definitions appear as combined stations in route labels");
        check(Items.getItem(home.itemId).getTemplateId()==WurmRailWorld.dropoffTemplateId
                &&Items.getItem(mine.itemId).getTemplateId()==WurmRailWorld.dropoffTemplateId,"legacy physical drop-off models remain supported after data migration");
        check(network.nodes.values().stream().noneMatch(n->n.station),"listing platform stops does not convert native rails to track stations");

        List<String> ordered=Arrays.asList(mine.rail,home.rail);
        Request save=RailwayTrainUi.routeFromAnswers(two,ordered,answer("save"));
        api.submit(actor,engine.getWurmId(),two.train,save);
        check(train.route.isEmpty()&&!train.powered,"saving a platform route only queues server work");
        poll(runtime);
        check(train.automated&&!train.powered&&train.route.equals(ordered),"queued route save accepts platform rails and preserves their chosen order");
        check(RailNetwork.load(state).trains.get(train.name).route.equals(ordered),"platform route identities survive native network state reload");
        check(api.snapshot(actor,engine.getWurmId()).routeSummary.contains(home.name)&&api.snapshot(actor,engine.getWurmId()).routeSummary.contains(mine.name),"saved route summary uses the same friendly platform labels");
        call(runtime,"savePassengers",new Class<?>[0]);
        Path passengers=state.resolveSibling("passengers.properties");
        PassengerStations reloaded=PassengerStations.load(passengers);
        check(reloaded.platforms.size()==2&&reloaded.get(home.itemId).rail.equals(home.rail)&&reloaded.get(mine.itemId).rail.equals(mine.rail)
                &&reloaded.get(home.itemId).mode==Mode.STATION&&reloaded.get(mine.itemId).mode==Mode.STATION,
                "combined station persistence and network files retain the same existing rail identities");

        Platform pickup=platform(platforms,actor,network.nodes.get("platformBranch230"),204,229,"Forest pickup",Mode.PICKUP);
        Platform shared=platform(platforms,actor,network.nodes.get(home.rail),196,239,"Village pickup",Mode.PICKUP);
        View expanded=api.snapshot(actor,engine.getWurmId());
        check(expanded.stations.size()==3&&offered(expanded,pickup.rail)&&pickup.mode==Mode.STATION
                &&Items.getItem(pickup.itemId).getTemplateId()==WurmRailWorld.pickupTemplateId,
                "legacy pickup models also advertise combined station route stops");
        check(count(expanded,home.rail)==1&&label(expanded,home.rail).contains(home.name)&&label(expanded,home.rail).contains(shared.name)
                &&label(expanded,home.rail).contains("[Station]"),"two legacy station models on one rail produce one stop with both names and combined station labels");
        platforms.rename(home.itemId,"Village renamed");
        View renamed=api.snapshot(actor,engine.getWurmId());
        check(label(renamed,home.rail).contains("Village renamed")&&!label(renamed,home.rail).contains("Village drop-off"),"a platform rename immediately refreshes route choices");
        check(renamed.routeSummary.contains("Village renamed")&&!renamed.routeSummary.contains("Village drop-off"),"saved train route labels refresh after a platform rename without changing route identities");
        Node classic=network.nodes.get("platformRoute190");network.setStation(classic.name,true,2);
        Items.getItem(classic.markerId).setName("Station: Depot");
        check(offered(api.snapshot(actor,engine.getWurmId()),classic.name),"classic named track stations remain valid alongside side-platform stops");

        unavailableChoices(runtime,api,actor,engine,network,platforms);
        final Platform removableShared=shared;
        position(actor,shared.x,shared.y);
        call(runtime,"removePassengerPlatform",new Class<?>[]{Creature.class,Platform.class},actor,removableShared);
        position(actor,188,240);
        check(!platforms.platforms.containsKey(shared.itemId)&&offered(api.snapshot(actor,engine.getWurmId()),home.rail),"removing one of two platforms preserves the shared saved train stop");
        fail(()->apply(runtime,actor,engine.getWurmId(),train,Request.route(Action.ROUTE_SAVE,Arrays.asList("platformRoute194",home.rail))),"forged new arbitrary rail cannot become an automatic stop");
        check(train.route.equals(ordered)&&!train.powered,"forged route rejection preserves the existing stopped service");

        // Queued route work must revalidate reach and operator authority on the poll thread.
        List<String> branchRoute=Arrays.asList(mine.rail,pickup.rail,home.rail);
        api.submit(actor,engine.getWurmId(),train,Request.route(Action.ROUTE_SAVE,branchRoute));
        position(actor,150,240);poll(runtime);
        check(train.route.equals(ordered)&&!train.powered,"queued route change cannot apply after its operator walks away");
        position(actor,188,240);
        TrackMaterialsSmoke.InventoryActor visitor=TrackMaterialsSmoke.actor(980001032L,true,0);position(visitor,188,240);
        View visitorView=api.snapshot(visitor,engine.getWurmId());
        check(visitorView.canControl,"configured operator can initially inspect and edit a nearby unlocked train");
        api.submit(visitor,engine.getWurmId(),train,Request.route(Action.ROUTE_SAVE,branchRoute));
        set(runtime,"operators","Nobody");poll(runtime);
        check(train.route.equals(ordered)&&!train.powered,"queued route change rechecks the operator ACL before mutation");
        set(runtime,"operators","*");

        // Save & start is the primary route workflow and must be a durable transaction.
        Action routeStart=Action.valueOf("ROUTE_START");
        Request primary=RailwayTrainUi.routeFromAnswers(api.snapshot(actor,engine.getWurmId()),branchRoute,answer("saveStart"));
        check(primary.action==routeStart&&primary.route.equals(branchRoute),"native Save and start answer decodes the exact captured ordered route");
        Path blocked=state.resolveSibling("state-is-directory");Files.createDirectories(blocked);
        set(runtime,"stateFile",blocked);
        fail(()->apply(runtime,actor,engine.getWurmId(),train,Request.route(routeStart,branchRoute)),"failed disk save rejects Save and start");
        set(runtime,"stateFile",state);
        check(train.route.equals(ordered)&&!train.powered&&train.speed==0,"failed Save and start restores previous route and power state");
        api.submit(actor,engine.getWurmId(),train,Request.route(routeStart,branchRoute));
        check(!train.powered&&train.route.equals(ordered),"Save and start remains queued until server poll");
        poll(runtime);
        check(train.powered&&train.automated&&train.route.equals(branchRoute),"one queued action saves the selected platform route and powers on automatic service");
        RailNetwork started=RailNetwork.load(state);
        check(started.trains.get(train.name).route.equals(branchRoute)&&!started.trains.get(train.name).powered,"Save and start persists the route and restart still leaves the train safely powered off");
        network.stop(train.name);

        // A stale catalog or deleted last platform cannot silently become an arbitrary rail destination.
        List<String> previous=new ArrayList<String>(train.route);
        final Platform usedPickup=pickup;
        position(actor,usedPickup.x,usedPickup.y);
        fail(()->call(runtime,"removePassengerPlatform",new Class<?>[]{Creature.class,Platform.class},actor,usedPickup),"a platform used by a saved route cannot be removed as its last stop");
        check(platforms.platforms.containsKey(usedPickup.itemId)&&Items.getItemOptional(usedPickup.itemId).isPresent(),"refused active-stop removal preserves both the native platform and its saved definition");
        position(actor,188,240);
        Items.destroyItem(pickup.itemId);
        fail(()->apply(runtime,actor,engine.getWurmId(),train,Request.route(Action.ROUTE_SAVE,branchRoute)),"saving a draft whose platform was removed is rejected");
        fail(()->apply(runtime,actor,engine.getWurmId(),train,Request.route(routeStart,branchRoute)),"Save and start rechecks removed platform stops");
        fail(()->apply(runtime,actor,engine.getWurmId(),train,Request.of(Action.START)),"Power on rechecks a previously saved route whose platform is now gone");
        check(train.route.equals(previous)&&!train.powered,"stale route rejection preserves the previous route and keeps power off");
        apply(runtime,actor,engine.getWurmId(),train,Request.route(Action.ROUTE_EDIT,previous));
        check(train.route.equals(previous)&&!train.powered,"a stale route can still open for Remove or Clear repair");
        api.submit(actor,engine.getWurmId(),train,Request.route(Action.ROUTE_SAVE,ordered));poll(runtime);
        check(train.route.equals(ordered)&&!train.powered,"operator can repair a stale route by saving remaining valid stops");
        position(actor,usedPickup.x,usedPickup.y);
        call(runtime,"removePassengerPlatform",new Class<?>[]{Creature.class,Platform.class},actor,usedPickup);
        check(!stations(runtime).platforms.containsKey(usedPickup.itemId),"unused damaged platform can be removed after its train route is repaired");
        position(actor,188,240);

        // Destroying a native stop after a form was captured is rechecked at queued execution too.
        Items.destroyItem(mine.itemId);
        api.submit(actor,engine.getWurmId(),train,Request.route(routeStart,ordered));poll(runtime);
        check(!train.powered&&train.route.equals(ordered),"queued Save and start rejects a physically missing platform without starting the train");
        check(!offered(api.snapshot(actor,engine.getWurmId()),mine.rail),"missing native platform disappears from fresh route choices");
        platforms.remove(mine.itemId);
        mine=platform(platforms,actor,network.nodes.get(mine.rail),220,241,"Mine rebuilt",Mode.DROPOFF);
        pickup=platform(platforms,actor,network.nodes.get("platformBranch230"),204,229,"Forest rebuilt",Mode.PICKUP);

        // The selected rails feed ordinary automatic movement, route locks and directional signals.
        network.signals().add("platformEntryWest","platformRoute200",6,-10L);
        network.signals().add("platformExitWest","platformRoute200",2,-10L);
        network.signals().add("platformEntryEast","platformRoute208",2,-10L);
        network.signals().add("platformExitEast","platformRoute208",6,-10L);
        network.signals().add("platformEntrySouth","platformBranch237",0,-10L);
        network.signals().add("platformExitSouth","platformBranch237",4,-10L);
        List<String> service=Arrays.asList(mine.rail,pickup.rail,home.rail);
        apply(runtime,actor,engine.getWurmId(),train,Request.route(routeStart,service));
        train.routeDwellSeconds=1.5;
        Set<Integer> visited=new HashSet<Integer>();boolean reserved=false,locked=false;
        for(int step=0;step<6000&&visited.size()<3;step++) {
            network.tick(.1);
            if(step%10==0)call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            if(train.dwellRemaining>0&&train.status.equals("Station dwell"))visited.add((train.routeIndex+service.size()-1)%service.size());
            if(!network.signals().reservations().isEmpty())reserved=true;
            if(network.nodes.get("platformRoute204").getAlignmentOwner()!=null)locked=true;
        }
        check(visited.equals(new HashSet<Integer>(Arrays.asList(0,1,2))),"automatic train visits and waits at all three combined stations without track-station flags; visited="+visited+", status="+train.status+", next="+train.routeIndex+", pose="+train.poses.get(0).railName+" at "+train.poses.get(0).x+","+train.poses.get(0).y+", hold="+train.passengerStopRail+", reservations="+network.signals().reservations().size());
        check(reserved&&locked,"platform-based automatic routes reserve signals and align and lock the physical junction");
        check(train.automated&&train.route.equals(service),"platform arrival preserves the repeating ordered automatic service");
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        check(Math.abs(engine.getPosX()-train.poses.get(0).x)<.01&&Math.abs(engine.getPosY()-train.poses.get(0).y)<.01,"native locomotive accepts the resulting platform-route position");
        network.stop(train.name);network.removeTrain(train.name);Items.destroyItem(engine.getWurmId());
        for(Platform stop:new ArrayList<Platform>(platforms.platforms.values()))if(Items.getItemOptional(stop.itemId).isPresent())Items.destroyItem(stop.itemId);
        for(Node rail:network.nodes.values())if(Items.getItemOptional(rail.markerId).isPresent())Items.destroyItem(rail.markerId);
        System.out.println("PASS native automatic platform routes, friendly labels, deduplication, reach/ACL, stale saves, restart, signal and branch service ("+assertions+" assertions)");
    }

    private static void unavailableChoices(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Item engine,RailNetwork network,PassengerStations platforms) throws Exception {
        Node isolated=track(network,actor,"platformIsolated",232,240),isolatedNext=track(network,actor,"platformIsolatedNext",233,240);network.connect(isolated.name,isolatedNext.name);
        Platform disconnected=platform(platforms,actor,isolated,232,241,"Disconnected drop-off",Mode.DROPOFF);
        check(!offered(api.snapshot(actor,engine.getWurmId()),disconnected.rail),"disconnected platform is excluded from this train's route choices");
        Train expected=api.snapshot(actor,engine.getWurmId()).train;
        fail(()->apply(runtime,actor,engine.getWurmId(),expected,Request.route(Action.ROUTE_SAVE,Collections.singletonList(disconnected.rail))),"route commit rejects disconnected platform identities even when submitted directly");
        Platform missing=platform(platforms,actor,network.nodes.get("platformRoute210"),210,241,"Missing model",Mode.DROPOFF);Items.destroyItem(missing.itemId);
        check(!offered(api.snapshot(actor,engine.getWurmId()),missing.rail),"missing native platform model is excluded");
        Platform cave=platform(platforms,actor,network.nodes.get("platformRoute212"),212,241,"Wrong cave layer",Mode.DROPOFF);
        platforms.remove(cave.itemId);platforms.add(new Platform(cave.itemId,cave.ownerId,1,cave.x,cave.y,-1,cave.rail,cave.name,cave.mode));
        check(!offered(api.snapshot(actor,engine.getWurmId()),cave.rail),"platform on another layer cannot advertise a surface rail stop");
        Item wrongNative=WurmRailWorld.spawnTrackMarker(actor,214*4f+2,241*4f+2,0);
        Platform wrongKind=new Platform(wrongNative.getWurmId(),actor.getWurmId(),1,214,241,0,"platformRoute214","Wrong native template",Mode.STATION);
        platforms.add(wrongKind);
        check(!offered(api.snapshot(actor,engine.getWurmId()),wrongKind.rail),"a track marker cannot impersonate a combined station's native model");
        Platform misplaced=platform(platforms,actor,network.nodes.get("platformRoute218"),218,241,"Misplaced platform",Mode.DROPOFF);
        platforms.remove(misplaced.itemId);platforms.add(new Platform(misplaced.itemId,misplaced.ownerId,1,218,239,0,misplaced.rail,misplaced.name,misplaced.mode));
        check(!offered(api.snapshot(actor,engine.getWurmId()),misplaced.rail),"native model on a different tile cannot advertise a phantom stop");
        Platform banked=platform(platforms,actor,network.nodes.get("platformRoute222"),222,241,"Banked platform",Mode.DROPOFF);
        Items.getItem(banked.itemId).setBanked(true);
        check(!offered(api.snapshot(actor,engine.getWurmId()),banked.rail),"banked platform cannot advertise a usable train stop");
        Items.getItem(banked.itemId).setBanked(false);
        check(offered(api.snapshot(actor,engine.getWurmId()),banked.rail),"restoring an available ground platform restores its route choice");
        Node brokenRail=network.nodes.get("platformRoute216");
        Platform broken=platform(platforms,actor,brokenRail,216,241,"Missing linked track",Mode.DROPOFF);
        Items.destroyItem(brokenRail.markerId);
        check(!offered(api.snapshot(actor,engine.getWurmId()),broken.rail),"platform whose linked native track model is gone is excluded");
        brokenRail.markerId=WurmRailWorld.spawnTrackMarker(actor,brokenRail.worldX(),brokenRail.worldY(),0).getWurmId();
        check(offered(api.snapshot(actor,engine.getWurmId()),broken.rail),"repaired linked track restores the existing platform stop");
        platforms.remove(banked.itemId);Items.destroyItem(banked.itemId);
        platforms.remove(broken.itemId);Items.destroyItem(broken.itemId);
        View valid=api.snapshot(actor,engine.getWurmId());
        check(valid.stations.size()==4,"invalid platform records do not hide the connected valid platforms or classic station");
        for(Platform invalid:Arrays.asList(disconnected,missing,cave,wrongKind,misplaced)) { platforms.remove(invalid.itemId);if(Items.getItemOptional(invalid.itemId).isPresent())Items.destroyItem(invalid.itemId); }
    }
    private static Node track(RailNetwork network,Creature actor,String name,int x,int y) throws Exception {
        Node n=network.addNode(name,x,y,0);n.markerId=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0).getWurmId();return n;
    }
    private static Platform platform(PassengerStations platforms,Creature actor,Node rail,int x,int y,String name,Mode mode) throws Exception {
        Item marker=WurmRailWorld.spawnPassengerPlatform(actor,x,y,rail.layer,mode==Mode.PICKUP);marker.setName((mode==Mode.PICKUP?"Pickup: ":"Drop-off: ")+name);
        Platform p=new Platform(marker.getWurmId(),actor.getWurmId(),1,x,y,rail.layer,rail.name,name,mode);platforms.add(p);return p;
    }
    private static PassengerStations stations(WurmRailRuntime runtime) throws Exception { return (PassengerStations)get(runtime,"passengerStations"); }
    private static boolean offered(View view,String key) { return count(view,key)>0; }
    private static int count(View view,String key) { int count=0;for(Choice choice:view.stations)if(choice.key.equals(key))count++;return count; }
    private static String label(View view,String key) { for(Choice choice:view.stations)if(choice.key.equals(key))return choice.label;throw new AssertionError("Route stop not offered: "+key); }
    private static void apply(WurmRailRuntime runtime,Creature actor,long engine,Train expected,Request request) throws Exception { call(runtime,"applyTrainRequest",new Class<?>[]{Creature.class,long.class,Train.class,Request.class},actor,engine,expected,request); }
    private static Properties answer(String button) { Properties p=new Properties();p.setProperty(button,"true");return p; }
    private static void poll(WurmRailRuntime runtime) throws Exception { set(runtime,"lastTick",System.nanoTime());set(runtime,"lastDiscovery",System.nanoTime());set(runtime,"lastSave",System.currentTimeMillis());runtime.onServerPoll(); }
    private static void position(Creature actor,int x,int y) { actor.getStatus().getPosition().setPosX(x*4+2);actor.getStatus().getPosition().setPosY(y*4+2); }
    private static Object get(Object object,String key) throws Exception { Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object); }
    private static void set(Object object,String key,Object value) throws Exception { Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);f.set(object,value); }
    private static Object call(Object object,String key,Class<?>[] types,Object...args) throws Exception {
        Method m=object.getClass().getDeclaredMethod(key,types);m.setAccessible(true);
        try { return m.invoke(object,args); }catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private interface Check { void run() throws Exception; }
    private static void fail(Check operation,String message) throws Exception { boolean failed=false;try { operation.run(); }catch(Exception expected) { failed=true; }check(failed,message); }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
