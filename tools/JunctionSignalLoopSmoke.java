import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.wurmrail.RailwaySignalUi;
import org.wurmrail.RailwayBml;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;

/** Native version of the reported three-signal junction returning through its own block. */
public final class JunctionSignalLoopSmoke {
    private static int assertions;

    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(990000032L,true,2);
        RailNetwork network=new RailNetwork();
        for(int i=0;i<24;i++) {
            int x=i<6?148+i:i<12?154:i<18?166-i:148;
            int y=i<6?160:i<12?154+i:i<18?166:184-i;
            Node rail=track(network,actor,"junctionLoop"+i,x,y);
            if(i>0)network.connect("junctionLoop"+(i-1),rail.name);
        }
        network.connect("junctionLoop23","junctionLoop0");
        for(int i=1;i<=10;i++) {
            Node rail=track(network,actor,"junctionNorth"+i,148,160-i);
            network.connect(i==1?"junctionLoop0":"junctionNorth"+(i-1),rail.name);
        }
        Node junction=network.nodes.get("junctionLoop0"),start=network.nodes.get("junctionNorth4");
        network.setJunctionAlignment(junction.name,17);
        network.signals().add("junctionNorth",junction.name,0,-10L);
        network.signals().add("junctionEast",junction.name,2,-10L);
        network.signals().add("junctionSouth",junction.name,4,-10L);
        List<String> service=Arrays.asList("junctionLoop18","junctionLoop12","junctionLoop6","junctionNorth10");
        for(String stop:service)network.setStation(stop,true,.1);
        Item engine=WurmRailWorld.spawnLocomotive(actor,start.worldX(),start.worldY(),0);
        Item cart=WurmRailWorld.spawnCart(actor,start.worldX(),network.nodes.get("junctionNorth5").worldY(),0);
        engine.setName("EXPRESS");cart.setName("EXPRESS cargo");
        Item cargo=ItemFactory.createItem(ItemList.plank,73f,"Junction loop fixture");
        check(cart.insertItem(cargo),"junction loop wagon accepts native cargo");
        Train train=network.createTrain("EXPRESS",actor.getWurmId(),Arrays.asList(engine.getWurmId(),cart.getWurmId()),start.name,"junctionNorth3");
        long[][] occupants=seatOccupants(train);
        check(occupants.length==2&&occupants[0].length==4&&occupants[1].length==4,"engine and attached wagon retain their four native seats each");
        check(engine.getModelName().contains("wurmrail")&&cart.getModelName().contains("wurmrail"),"native train uses the custom locomotive and wagon model mappings");

        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("junction-signal-loop/network.properties");
        WurmRailRuntime runtime=new WurmRailRuntime();
        set(runtime,"network",network);set(runtime,"ready",true);set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);
        call(runtime,"refreshTrackVisuals",new Class<?>[0]);
        Item north=physical(junction.markerId,0),east=physical(junction.markerId,2),south=physical(junction.markerId,4);
        check(north!=null&&east!=null&&south!=null,"three distinct native approach signals exist directly on the three-way junction");
        check((north.getAuxData()&255)==0&&(east.getAuxData()&255)==2&&(south.getAuxData()&255)==4,"unrequested native junction signals start red in each absolute direction");

        position(actor,start.worldX(),start.worldY());
        RailwayTrainUi.Api trainApi=(RailwayTrainUi.Api)get(runtime,"trainApi");
        RailwayTrainUi.View initial=trainApi.snapshot(actor,engine.getWurmId());
        trainApi.submit(actor,engine.getWurmId(),initial.train,RailwayTrainUi.Request.route(RailwayTrainUi.Action.ROUTE_START,service));
        check(!train.powered&&train.route.isEmpty(),"native Save and start remains queued before server execution");
        poll(runtime);
        check(train.powered&&train.automated&&train.route.equals(service),"queued native Save and start activates the junction loop itinerary");
        position(actor,junction.worldX(),junction.worldY());
        RailwaySignalUi.Api signalApi=(RailwaySignalUi.Api)get(runtime,"signalApi");
        int arrivals=0,previous=train.routeIndex,mostReservations=0;
        String maximumReservationState="",oldNorthReservation=null;
        boolean deniedShown=false,clearingShown=false,northReleased=false,handoverShown=false,necessaryBacking=false,forwardRecovery=false,forwardLoopPair=false;
        Set<Integer> masks=new HashSet<Integer>();
        for(int step=0;step<14000&&arrivals<12;step++) {
            int previousDirection=train.direction;
            List<Pose> previousPoses=train.poses;
            network.tick(.1);
            necessaryBacking|=train.direction<0;
            if(previousDirection<0&&train.direction>0) {
                check(train.speed==0&&samePoses(previousPoses,train.poses),"safe forward recovery changes traction without moving or rotating the engine or its attached cart");
                forwardRecovery=true;
            }
            if(train.direction>0&&junction.getAlignmentMask()==20&&junction.name.equals(train.poses.get(0).railName))
                forwardLoopPair=true;
            if(step%10==0)call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            if(network.signals().reservations().size()>mostReservations) {
                mostReservations=network.signals().reservations().size();
                maximumReservationState=reservationState(network);
            }
            if(junction.getAlignmentOwner()!=null)masks.add(junction.getAlignmentMask());
            if(train.routeIndex!=previous) { arrivals++;previous=train.routeIndex; }
            if(oldNorthReservation!=null&&!northReleased&&!occupiesJunction(network,train,junction.name)) {
                for(SignalSystem.Reservation reservation:network.signals().reservations())
                    check(!reservation.id.equals(oldNorthReservation),"the retired entrance reservation releases once the whole consist clears its actual protected points");
                northReleased=true;
            }
            if(!deniedShown&&network.signals().ownsAuthority(train.name,"junctionNorth")&&occupiesJunction(network,train,junction.name)) {
                oldNorthReservation=network.signals().status("junctionNorth").reservationId;
                SignalSystem.RequestResult denied=network.signals().request(train.name,"junctionEast");
                check(!denied.granted&&denied.reason.length()>39,"different approach remains safely red with a complete diagnostic while the native train occupies the points");
                call(runtime,"refreshSignalVisuals",new Class<?>[]{boolean.class},true);
                RailwaySignalUi.View view=signalApi.snapshot(actor,junction.markerId);
                RailwaySignalUi.Signal shown=signal(view,"junctionEast");
                check(shown.aspect.equals("RED")&&shown.reason.equals(denied.reason)&&RailwaySignalUi.content(view).contains(RailwayBml.text(shown.reason)),"signal window preserves the full red reason beyond the native item-name length limit");
                // Retire the actually occupied short entrance block while
                // stationary, as a safe recovery does. The old test waited
                // for that North block to survive an entire circuit before
                // East became green; following blocks now release it sooner.
                network.stop(train.name);List<Pose> held=new ArrayList<Pose>(train.poses);
                call(network.signals(),"prepareTrafficReversal",new Class<?>[]{String.class},train.name);
                call(runtime,"refreshSignalVisuals",new Class<?>[]{boolean.class},true);
                RailwaySignalUi.View clearingView=signalApi.snapshot(actor,junction.markerId);
                RailwaySignalUi.Signal clearing=signal(clearingView,"junctionNorth");
                check(clearing.aspect.equals("RED")&&clearing.reason.contains("last car")
                        &&RailwaySignalUi.content(clearingView).contains(RailwayBml.text(clearing.reason)),
                        "the real occupied entrance stays protected and its native red display explains last-car clearance");
                check(hasClearing(network)&&train.name.equals(junction.getAlignmentOwner())&&samePoses(held,train.poses),
                        "retiring movement authority keeps occupied junction ownership and every native body in place");
                boolean refused=false;try {network.setJunctionAlignment(junction.name,5);}catch(IllegalArgumentException occupied){refused=true;}
                check(refused&&junction.getAlignmentMask()==17&&samePoses(held,train.poses),
                        "a retired red entrance cannot release or switch the points underneath the engine or cart");
                network.resumeScheduled(train.name);clearingShown=true;
                deniedShown=true;
            }
            if(!handoverShown&&network.signals().ownsAuthority(train.name,"junctionEast")) {
                call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
                call(runtime,"refreshJunctionIndicators",new Class<?>[]{boolean.class},true);
                call(runtime,"refreshSignalVisuals",new Class<?>[]{boolean.class},true);
                check(physical(junction.markerId,0).getWurmId()==north.getWurmId()&&physical(junction.markerId,2).getWurmId()==east.getWurmId(),"handover updates existing native posts without replacing their identities");
                check((north.getAuxData()&255)==0&&(east.getAuxData()&255)==10,"previous North signal becomes red and the successor East signal becomes green");
                RailwaySignalUi.View view=signalApi.snapshot(actor,junction.markerId);
                RailwaySignalUi.Signal old=signal(view,"junctionNorth"),active=signal(view,"junctionEast");
                check(old.aspect.equals("RED")&&(old.reason.contains("last car")||old.reason.contains("cleared"))
                        &&RailwaySignalUi.content(view).contains(RailwayBml.text(old.reason)),
                        "the previous North signal stays red and explains retained protection or completed rear clearance");
                check(active.aspect.equals("GREEN")&&active.owner.equals("EXPRESS")&&junction.getAlignmentMask()==5,"native successor display names the train and the absolute East-North pair");
                check(nativeMatches(train)&&Arrays.deepEquals(occupants,seatOccupants(train))&&cargo.getParentId()==cart.getWurmId(),"handover preserves both native vehicle poses, all seats and wagon cargo");
                handoverShown=true;
            }
        }
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        check(arrivals>=12,"native engine and attached cart repeat twelve automatic junction-loop arrivals: arrivals="+arrivals+", status="+train.status);
        check(deniedShown&&clearingShown&&northReleased&&handoverShown,"native loop exercises occupied-points refusal, retained red protection, last-car release and subsequent safe alignment handover");
        // Adjacent following blocks can overlap each car's clearance and an
        // acquired junction ahead; retaining them for a whole lap is forbidden.
        int reservationLimit=train.carIds.size()+2;
        check(masks.equals(new HashSet<Integer>(Arrays.asList(17,5,20)))&&mostReservations<=reservationLimit,"repeating native route uses its three physical pairs and bounds short block reservations by consist size; masks="+masks+", maximum="+mostReservations+", limit="+reservationLimit+", current="+reservationState(network)+", at maximum="+maximumReservationState);
        check(necessaryBacking&&forwardRecovery&&forwardLoopPair,"dead-end service backs out then recovers forward traction and physically crosses the South-East pair to use the turning loop");
        check(train.powered&&train.automated&&train.route.equals(service)&&nativeMatches(train),"native consist stays in automatic service with positions matching its route after loop travel and necessary backing");
        check(Arrays.deepEquals(occupants,seatOccupants(train))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==73f,"repeated native travel preserves every seat and the exact cargo item");
        network.stop(train.name);network.removeTrain(train.name);
        Items.destroyItem(engine.getWurmId());Items.destroyItem(cart.getWurmId());
        for(Item item:Items.getAllItems())if(item.getData()==junction.markerId&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
        for(Node rail:network.nodes.values())Items.destroyItem(rail.markerId);
        System.out.println("PASS native three-signal junction circuit, automatic alignment handover, full diagnostics, retained red protection, seats and cargo ("+assertions+" assertions)");
    }

    private static Node track(RailNetwork network,Creature actor,String id,int x,int y) throws Exception {
        Node rail=network.addNode(id,x,y,0);rail.markerId=WurmRailWorld.spawnTrackMarker(actor,rail.worldX(),rail.worldY(),0).getWurmId();return rail;
    }
    private static boolean occupiesJunction(RailNetwork network,Train train,String rail) throws Exception {
        Method method=RailNetwork.class.getDeclaredMethod("signalOccupiesNode",Train.class,String.class);method.setAccessible(true);return (Boolean)method.invoke(network,train,rail);
    }
    private static boolean hasClearing(RailNetwork network) {
        for(SignalSystem.Reservation reservation:network.signals().reservations())if(reservation.state.name().equals("CLEARING"))return true;return false;
    }
    private static String reservationState(RailNetwork network) {
        List<String> result=new ArrayList<String>();
        for(SignalSystem.Reservation r:network.signals().reservations())
            result.add(r.id+":"+r.entrySignalId+"->"+r.exitSignalId+":"+r.state+": path="+r.path+", alignments="+r.junctionAlignments+", locks="+r.junctionLocks());
        return result.toString();
    }
    private static boolean samePoses(List<Pose> previous,List<Pose> current) {
        if(previous.size()!=current.size())return false;
        for(int i=0;i<previous.size();i++) {
            Pose a=previous.get(i),b=current.get(i);
            if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false;
        }
        return true;
    }
    private static RailwaySignalUi.Signal signal(RailwaySignalUi.View view,String id) {
        for(RailwaySignalUi.Signal signal:view.signals)if(signal.id.equals(id))return signal;throw new AssertionError("Missing signal "+id);
    }
    private static Item physical(long marker,int approach) {
        for(Item item:Items.getAllItems())if(WurmRailWorld.isSignal(item)&&item.getData()==marker&&(item.getAuxData()&7)==approach)return item;return null;
    }
    private static long[][] seatOccupants(Train train) throws Exception {
        long[][] result=new long[train.carIds.size()][];
        for(int i=0;i<result.length;i++) { Vehicle vehicle=Vehicles.getVehicle(Items.getItem(train.carIds.get(i)));result[i]=new long[vehicle.seats.length];for(int seat=0;seat<vehicle.seats.length;seat++)result[i][seat]=vehicle.seats[seat].occupant; }
        return result;
    }
    private static boolean nativeMatches(Train train) throws Exception {
        for(Pose pose:train.poses) { Item item=Items.getItem(pose.carId);if(Math.abs(item.getPosX()-pose.x)>.01||Math.abs(item.getPosY()-pose.y)>.01||Math.abs(item.getRotation()-pose.rotation)>.01)return false; }return true;
    }
    private static void position(Creature actor,double x,double y) { actor.getStatus().getPosition().setPosX((float)x);actor.getStatus().getPosition().setPosY((float)y); }
    private static void poll(WurmRailRuntime runtime) throws Exception { set(runtime,"lastTick",System.nanoTime());set(runtime,"lastDiscovery",System.nanoTime());set(runtime,"lastSave",System.currentTimeMillis());runtime.onServerPoll(); }
    private static Object get(Object object,String key) throws Exception { Field field=object.getClass().getDeclaredField(key);field.setAccessible(true);return field.get(object); }
    private static void set(Object object,String key,Object value) throws Exception { Field field=object.getClass().getDeclaredField(key);field.setAccessible(true);field.set(object,value); }
    private static Object call(Object object,String key,Class<?>[] types,Object...args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(key,types);method.setAccessible(true);
        try { return method.invoke(object,args); }catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
