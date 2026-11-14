import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;

/** Real managed vehicles, native guards and commits for safe stationary jam recovery. */
public final class JamRecoveryRuntimeSmoke {
    private static int assertions;
    private JamRecoveryRuntimeSmoke() { }
    public static void run() throws Exception {
        runCase(.1,true,false);
        runCase(.25,false,false);
        runCase(.1,false,true);
        runLegacyQueue();
        runStagedTailQueue();
        runStationOrientation(false,.1);
        runStationOrientation(true,.25);
        runOpposedFleet(false,.1);
        runOpposedFleet(true,.25);
    }
    private static void runCase(double dt,boolean stoppedReceiver,boolean oldInside) throws Exception {
        String tag=oldInside?"old-connector":stoppedReceiver?"stopped-receiver":"saved-yield";
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997211104L+(oldInside?512:stoppedReceiver?0:256),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        WurmRailRuntime runtime=null;
        try {
            fixture("dumbbell",new Class<?>[]{RailNetwork.class,Creature.class},net,actor);
            Train a=vehicle(net,actor,vehicles,"JamA",oldInside?"followc8":"followr0",oldInside?"followc9":"followr1",2,oldInside?"followc7":"followR");
            Train b=vehicle(net,actor,vehicles,"JamZ",oldInside?"followc30":"followr2",oldInside?"followc29":"followr1",1);
            Train c=oldInside?vehicle(net,actor,vehicles,"JamFollower","followc5","followc6",1):null;
            net.setRoute(a.name,oldInside?Arrays.asList("followr11","followl11"):Arrays.asList("followl11","followr11"),.5);
            net.setRoute(b.name,Arrays.asList("followl11","followr11"),.5);
            if(c!=null)net.setRoute(c.name,Arrays.asList("followr11","followl11"),.5);
            Item cart=Items.getItem(a.carIds.get(1)),cargo=ItemFactory.createItem(ItemList.plank,76f,"Native jam recovery");
            check(cart.insertItem(cargo),"recovery cart accepts real cargo");
            if(oldInside)net=reload(net,tag);
            a=net.trains.get(a.name);b=net.trains.get(b.name);
            if(c!=null)c=net.trains.get(c.name);
            List<Train> all=c==null?Arrays.asList(a,b):Arrays.asList(a,b,c);runtime=bind(net,tag);long[][] seats=seats(all);
            List<String> routeA=new ArrayList<String>(a.route),routeB=new ArrayList<String>(b.route);
            List<Pose> originalA=a.poses,originalB=b.poses;
            if(!stoppedReceiver)net.resumeScheduled(a.name);
            if(c!=null)net.resumeScheduled(c.name);
            net.resumeScheduled(b.name);
            check(samePoses(originalA,a.poses)&&samePoses(originalB,b.poses)&&nativeMatches(all),"starting saved services does not move or spin a native engine or cart");
            if(!stoppedReceiver) {
                if(oldInside)tick(runtime,net,all,dt);
                List<Pose> before=b.poses;
                check(net.resolveTrafficJams(Collections.singleton(b.name))==1,"authorized powered victim has a protected backing route out of the actual head-on");
                check(b.direction==-1&&samePoses(before,b.poses)&&nativeMatches(all),"safe yield reverses traction without changing native body position or heading");
                if(!oldInside) {
                    net.movementGuard=null;net=reload(net,tag);a=net.trains.get(a.name);b=net.trains.get(b.name);all=Arrays.asList(a,b);runtime=bind(net,tag);
                    net.resumeScheduled(a.name);net.resumeScheduled(b.name);
                    check(b.direction==-1&&net.trafficRecoveryStatus(b.name).contains("yield"),"native mid-yield save and resume retain backing rather than turn back into the jam");
                }
            } else {
                List<Pose> held=a.poses;boolean yielding=false;
                for(int i=0;i<Math.ceil(32/dt);i++) {
                    tick(runtime,net,all,dt);
                    check(!a.powered&&samePoses(held,a.poses),"automatic jam recovery never moves a STOPped receiver or its cart");
                    yielding|=b.direction==-1&&!net.trafficRecoveryStatus(b.name).isEmpty();
                }
                check(yielding&&b.powered,"sustained head-on automatically selects the clear powered service for a real backing yield");
                net.resumeScheduled(a.name);
            }
            boolean arrivedA=false,arrivedB=false,arrivedC=c==null;
            for(int i=0;i<Math.ceil(850/dt)&&(!arrivedA||!arrivedB||!arrivedC);i++) {
                tick(runtime,net,all,dt);
                if(a.routeIndex!=0)arrivedA=true;if(b.routeIndex!=0)arrivedB=true;
                if(c!=null&&c.routeIndex!=0)arrivedC=true;
                check(a.powered&&b.powered,"both recovered native services retain their running schedule intent");
                check(a.route.equals(routeA)&&b.route.equals(routeB),"automatic recovery never rewrites either saved itinerary");
            }
            check(arrivedA&&arrivedB&&arrivedC,"every native train physically regains its original station schedule after "+tag+": "+status(net,all));
            check(Arrays.deepEquals(seats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==76f,"recovery, restart, junctions and backing preserve all native seats and the exact cargo item");
            System.out.println("PASS native jam recovery "+tag+", dt="+dt+", real guards/commits, no spin/overlap, saved routes, station arrivals, seats and cargo ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;
            for(Train train:new ArrayList<Train>(net.trains.values())) { net.stop(train.name);net.removeTrain(train.name); }
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }
    private static RailNetwork reload(RailNetwork net,String tag) throws Exception {
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-jam-"+tag+"/network.properties");
        net.save(state);return RailNetwork.load(state);
    }
    /** Anonymous six-service old save; four persisted yields, two congested merges and a parked manual consist. */
    private static void runLegacyQueue() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997211872L,true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();String tag="six-saved-queues";
        try {
            legacyGraph(net,actor);String south=legacyRail(103,123),north=legacyRail(111,98);
            List<Train> active=new ArrayList<Train>();
            active.add(vehicle(net,actor,vehicles,"LegacyA",legacyRail(103,100),legacyRail(104,100),2,legacyRail(102,100)));
            active.add(vehicle(net,actor,vehicles,"LegacyB",legacyRail(105,100),legacyRail(104,100),1));
            active.add(vehicle(net,actor,vehicles,"LegacyC",legacyRail(104,100),legacyRail(105,100),1));
            active.add(vehicle(net,actor,vehicles,"LegacyD",legacyRail(103,124),legacyRail(103,125),1));
            active.add(vehicle(net,actor,vehicles,"LegacyE",legacyRail(103,123),legacyRail(103,124),1));
            active.add(vehicle(net,actor,vehicles,"LegacyF",legacyRail(103,122),legacyRail(103,121),1));
            for(Train train:active)net.setRoute(train.name,Arrays.asList(south,north),.5);active.get(4).routeIndex=1;
            for(int i:new int[]{0,2,3,5}) { net.reverse(active.get(i).name);set(active.get(i),"recoveringTraffic",true); }
            for(int i=0;i<6;i++) { Node node=net.addNode("legacyPark"+i,125,230+i,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();if(i>0)net.connect("legacyPark"+(i-1),node.name); }
            Train parked=vehicle(net,actor,vehicles,"LegacyManual","legacyPark3","legacyPark4",3,"legacyPark2","legacyPark1");
            Item cart=Items.getItem(active.get(0).carIds.get(1)),cargo=ItemFactory.createItem(ItemList.plank,74f,"Native persisted recovery queue");
            check(cart.insertItem(cargo),"legacy recovery cart accepts its actual cargo");
            net=reload(net,tag);active=new ArrayList<Train>();for(Train train:net.trains.values())if(train.automated)active.add(train);
            parked=net.trains.get(parked.name);List<Train> all=new ArrayList<Train>(active);all.add(parked);
            WurmRailRuntime runtime=bind(net,tag);long[][] seats=seats(all);List<Pose> manual=parked.poses;
            for(Train train:active)net.resumeScheduled(train.name);
            List<String> names=new ArrayList<String>();for(Train train:active)names.add(train.name);
            Map<String,Integer> originalDirections=new LinkedHashMap<String,Integer>();Map<String,List<Pose>> originalPoses=new LinkedHashMap<String,List<Pose>>();
            for(Train train:all) { originalDirections.put(train.name,train.direction);originalPoses.put(train.name,train.poses); }
            check(net.resolveTrafficJams(names)==1,"native Clear can reconsider a saved yield stalled against another approach");
            List<String> selected=new ArrayList<String>();
            for(Train train:all) {
                if(train.direction!=originalDirections.get(train.name)) {
                    selected.add(train.name);check(train.powered&&names.contains(train.name),"the first native recovery primitive changes traction only on an authorized running service");
                }
                check(samePoses(originalPoses.get(train.name),train.poses),"selecting a safe queue tail or direct retreat cannot teleport or turn any native body");
            }
            check(selected.size()==1&&nativeMatches(all),"Clear starts exactly one protected native recovery stage with all item positions unchanged: "+selected);
            check(!parked.powered&&samePoses(manual,parked.poses),"selecting the initial native recovery stage preserves the manual STOPped consist");
            int[] arrivals=new int[6],cursor=new int[6];for(int i=0;i<6;i++)cursor[i]=active.get(i).routeIndex;boolean done=false;
            for(int i=0;i<18000&&!done;i++) {
                tick(runtime,net,all,.1);done=true;
                for(int service=0;service<6;service++) {
                    Train train=active.get(service);if(train.routeIndex!=cursor[service]) { arrivals[service]++;cursor[service]=train.routeIndex; }
                    if(arrivals[service]<3)done=false;
                    check(train.powered&&train.route.equals(Arrays.asList(south,north)),"native staged recovery preserves every service's power and original itinerary");
                }
                check(!parked.powered&&samePoses(manual,parked.poses),"native automatic recovery never moves the manual STOPped consist");
            }
            check(done,"all six native saved services regain repeated scheduled arrivals: "+Arrays.toString(arrivals)+"; "+status(net,all));
            check(Arrays.deepEquals(seats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==74f,"staged native recovery preserves every seat and the exact wagon cargo");
            System.out.println("PASS native six-service legacy queue: persisted-yield retry, curved head-on, protected receiving circulation, merge recovery, three arrivals each, manual STOP, seats/cargo ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())) { net.stop(train.name);net.removeTrain(train.name); }
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }
    /** A blocked recovery cannot retreat until the last train in its receiving queue makes room. */
    private static void runStagedTailQueue() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997212128L,true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();String tag="staged-queue-tail";
        try {
            legacyGraph(net,actor);String south=legacyRail(103,123),north=legacyRail(111,98);
            List<Train> active=new ArrayList<Train>();List<String> itinerary=Arrays.asList(south,north);
            Train convoyA=vehicle(net,actor,vehicles,"ConvoyA",legacyRail(100,112),legacyRail(100,113),2,legacyRail(100,111));
            net.setRoute(convoyA.name,itinerary,.5);stagedHistory(net,convoyA,7.549485558312108,1,false,new int[][]{{100,110},{100,111},{100,112},{100,113}});active.add(convoyA);
            Train tail=vehicle(net,actor,vehicles,"QueueTail",legacyRail(102,114),legacyRail(101,114),1);
            net.setRoute(tail.name,itinerary,.5);tail.routeIndex=1;stagedHistory(net,tail,3.5495192023312208,1,false,new int[][]{{103,114},{102,114},{101,114}});active.add(tail);
            Train convoyB=vehicle(net,actor,vehicles,"ConvoyB",legacyRail(100,113),legacyRail(100,114),1);
            net.setRoute(convoyB.name,itinerary,.5);stagedHistory(net,convoyB,4.837118521707888,1,false,new int[][]{{100,112},{100,113},{100,114}});active.add(convoyB);
            Train northA=vehicle(net,actor,vehicles,"NorthA",legacyRail(100,97),legacyRail(100,98),1);
            net.setRoute(northA.name,itinerary,.5);stagedHistory(net,northA,3.5481298272733954,1,false,new int[][]{{100,96},{100,97},{100,98}});active.add(northA);
            Train northB=vehicle(net,actor,vehicles,"NorthB",legacyRail(100,98),legacyRail(100,99),1);
            net.setRoute(northB.name,itinerary,.5);stagedHistory(net,northB,5.55,1,false,new int[][]{{100,97},{100,98},{100,99}});active.add(northB);
            Train yield=vehicle(net,actor,vehicles,"StalledYield",legacyRail(101,114),legacyRail(102,114),1);
            net.setRoute(yield.name,itinerary,.5);stagedHistory(net,yield,2.45,-1,true,new int[][]{{100,114},{101,114},{102,114}});active.add(yield);
            for(int i=0;i<6;i++) { Node node=net.addNode("stagedPark"+i,125,230+i,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();if(i>0)net.connect("stagedPark"+(i-1),node.name); }
            Train parked=vehicle(net,actor,vehicles,"StagedManual","stagedPark3","stagedPark4",3,"stagedPark2","stagedPark1");
            Item cart=Items.getItem(convoyA.carIds.get(1)),cargo=ItemFactory.createItem(ItemList.plank,72f,"Native queue-tail recovery");
            check(cart.insertItem(cargo),"staged recovery cart accepts the actual cargo item");
            net=reload(net,tag+"-initial");active=new ArrayList<Train>();List<String> names=new ArrayList<String>();
            for(Train train:net.trains.values())if(train.automated) { active.add(train);names.add(train.name); }
            parked=net.trains.get(parked.name);List<Train> all=new ArrayList<Train>(active);all.add(parked);
            WurmRailRuntime runtime=bind(net,tag);long[][] originalSeats=seats(all);List<Pose> manual=parked.poses;
            for(Train train:active)net.resumeScheduled(train.name);
            // Let the resumed follower close its initially loose gap and stop;
            // five seconds remains below automatic recovery's 15-second wait.
            for(int poll=0;poll<50;poll++)tick(runtime,net,all,.1);
            tail=net.trains.get("QueueTail");yield=net.trains.get("StalledYield");
            check(tail.direction==1&&yield.direction==-1&&yield.speed<.001
                    &&(yield.status.startsWith("Signal ")||yield.status.startsWith("Track occupied")||yield.status.startsWith("Waiting ")),
                    "native fixture recreates a stationary persisted backing receiver held by real traffic protection: "+yield.status);
            Map<String,List<Pose>> beforeClear=new LinkedHashMap<String,List<Pose>>();
            for(Train train:all)beforeClear.put(train.name,train.poses);
            check(net.resolveTrafficJams(names)==1,"Clear stages one authorized free queue tail before retrying the trapped recovery");
            check(tail.direction==-1&&yield.direction==-1&&net.trains.get("ConvoyA").direction==1&&net.trains.get("ConvoyB").direction==1,
                    "the initial native stage reverses only the free tail, preserving the trapped receiver and occupied incoming convoy");
            for(Train train:all)check(samePoses(beforeClear.get(train.name),train.poses),"staging changes no native vehicle position or physical facing");
            check(nativeMatches(all),"Clear cannot move or spin native items before a guarded tick");
            // Save immediately while the new tail yield is active but no vehicle has moved.
            net.movementGuard=null;net=reload(net,tag+"-active");active=new ArrayList<Train>();
            for(String name:names)active.add(net.trains.get(name));parked=net.trains.get(parked.name);all=new ArrayList<Train>(active);all.add(parked);
            runtime=bind(net,tag+"-restarted");for(Train train:active)net.resumeScheduled(train.name);
            tail=net.trains.get("QueueTail");yield=net.trains.get("StalledYield");
            check(tail.direction==-1&&yield.direction==-1&&net.trafficRecoveryStatus(tail.name).toLowerCase(Locale.ROOT).contains("yield"),
                    "native restart retains both independent yields without turning either train back into the queue");
            List<Pose> held=yield.poses;tick(runtime,net,all,.1);
            check(samePoses(held,yield.poses),"the front receiver stays stationary until its retreat has real clearance behind the moving queue tail");
            int[] arrivals=new int[active.size()],cursor=new int[active.size()];for(int i=0;i<active.size();i++)cursor[i]=active.get(i).routeIndex;
            boolean complete=false;
            for(int poll=0;poll<18000&&!complete;poll++) {
                tick(runtime,net,all,.1);complete=true;
                for(int i=0;i<active.size();i++) {
                    Train train=active.get(i);if(train.routeIndex!=cursor[i]) { arrivals[i]++;cursor[i]=train.routeIndex; }
                    if(arrivals[i]<3)complete=false;
                    check(train.powered&&train.route.equals(itinerary),"native queue-tail recovery preserves every running service and its saved itinerary");
                }
                check(!parked.powered&&samePoses(manual,parked.poses),"staged automatic recovery leaves the manual STOPped native consist untouched");
            }
            check(complete,"all six native services complete at least three scheduled arrivals after staged tail recovery and restart: "+Arrays.toString(arrivals)+"; "+status(net,all));
            check(Arrays.deepEquals(originalSeats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==72f,
                    "native staged recovery and restart preserve every passenger socket and the exact wagon cargo");
            System.out.println("PASS native staged queue-tail recovery: free tail first, front waits for physical clearance, active-yield restart, six services with three arrivals each, guarded world commits, manual STOP, seats/cargo ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())) { net.stop(train.name);net.removeTrain(train.name); }
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }
    @SuppressWarnings("unchecked")
    private static void stagedHistory(RailNetwork net,Train train,double distance,int direction,boolean yielding,int[][] coordinates) throws Exception {
        List<String> history=(List<String>)get(train,"path");history.clear();for(int[] xy:coordinates)history.add(legacyRail(xy[0],xy[1]));
        set(train,"locomotiveDistance",distance);set(train,"initialTowards",null);set(train,"recoveringTraffic",yielding);train.direction=direction;
        call(net,"refresh",new Class<?>[]{Train.class},train);
    }
    /** A backing station arrival and six real following trains must all depart forwards. */
    private static void runStationOrientation(boolean right,double dt) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997213152L+(right?256:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();String tag="station-orientation-"+right;
        try {
            fixture("dumbbell",new Class<?>[]{RailNetwork.class,Creature.class},net,actor);
            String p=right?"followr":"followl",station=p+"11",other=right?"followl11":"followr11",junction=right?"followR":"followL";
            List<String> route=Arrays.asList(station,other),names=new ArrayList<String>();
            Train backer=vehicle(net,actor,vehicles,"OrientationReceiver",p+"8",p+"7",2,p+"9");
            net.setRoute(backer.name,route,.5);net.reverse(backer.name);set(backer,"recoveringTraffic",true);names.add(backer.name);
            for(int i=0;i<6;i++) {
                int at=right?40-(i+1)*3:(i+1)*3;
                Train follower=vehicle(net,actor,vehicles,"OrientationFollower"+i,"followc"+at,"followc"+(at+(right?1:-1)),i==0?2:1,
                        i==0?new String[]{"followc"+(at+(right?-1:1))}:new String[0]);
                net.setRoute(follower.name,route,.5);names.add(follower.name);
            }
            List<Train> all=new ArrayList<Train>();for(String name:names)all.add(net.trains.get(name));
            Item cart=Items.getItem(backer.carIds.get(1)),cargo=ItemFactory.createItem(ItemList.plank,79f,"Native station orientation");
            check(cart.insertItem(cargo),"the station-turning cart holds its real cargo item");long[][] initialSeats=seats(all);
            WurmRailRuntime runtime=bind(net,tag);for(Train train:all)net.resumeScheduled(train.name);
            // Restart during the backing approach; the turn is reconstructed from physical history.
            tick(runtime,net,all,dt);net.movementGuard=null;net=reload(net,tag);all=new ArrayList<Train>();
            for(String name:names)all.add(net.trains.get(name));runtime=bind(net,tag+"-restart");for(Train train:all)net.resumeScheduled(train.name);
            int[] arrivals=new int[all.size()],cursor=new int[all.size()];boolean receiverArrived=false,receiverForward=false,done=false;
            Set<String> firstArrivals=new HashSet<String>(),departing=new HashSet<String>();
            for(int step=0;step<Math.ceil(1800/dt)&&!done;step++) {
                Map<Long,Pose> before=new HashMap<Long,Pose>();for(Train train:all)before.put(train.carIds.get(0),train.poses.get(0));
                tick(runtime,net,all,dt);
                for(int i=0;i<all.size();i++) {
                    Train train=all.get(i);boolean receiver=i==0;
                    check(train.powered&&train.route.equals(route),"native orientation coordination preserves each saved itinerary and run intent");
                    if(train.routeIndex!=cursor[i]) {
                        arrivals[i]++;cursor[i]=train.routeIndex;firstArrivals.add(train.name);departing.add(train.name);
                        if(receiver)receiverArrived=true;else check(receiverArrived&&receiverForward,"native followers serve the station after the backing receiver has restored forward travel");
                    }
                    Pose old=before.get(train.carIds.get(0)),now=train.poses.get(0);
                    if(departing.contains(train.name)&&train.dwellRemaining<=0&&Math.hypot(now.x-old.x,now.y-old.y)>.00001) {
                        check(train.direction==1,"every real serviced locomotive departs engine-leading rather than maintaining a backwards schedule");
                        departing.remove(train.name);if(receiver)receiverForward=true;
                    }
                    if(!receiver&&!firstArrivals.contains(train.name)) {
                        for(org.wurmrail.core.TrafficControl.Lease lease:net.signals().traffic().leases())if(lease.trainName.equals(train.name)&&lease.exitJunction.equals(junction))
                            check(lease.exitNeighbor.equals(p+"22"),"native followers plan the opposite receiving arm, matching the restored locomotive's physical orientation");
                        if(!receiverForward)check(!(Boolean)call(net,"signalOccupiesNode",new Class<?>[]{Train.class,String.class},train,junction),"native followers wait outside the switch until the receiver completes its station pivot");
                    }
                }
                done=receiverForward;for(int count:arrivals)if(count<3)done=false;
            }
            check(done,"all seven native services regain repeated forward station visits: "+Arrays.toString(arrivals)+" "+status(net,all));
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==79f,"station orientation and restart preserve every passenger seat and exact cargo item");
            System.out.println("PASS native coordinated station orientation: seven services, opposite-arm arrivals, forward departures, restart, guarded movement, headway, seats/cargo; mirror="+right+" dt="+dt);
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())) {net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }
    /** A loop already containing opposite physical engine orientations must
     * drain through the bridge and return via the safe arm, not keep backing
     * around the station or reverse a corrected follower into another jam. */
    private static void runOpposedFleet(boolean right,double dt) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997214176L+(right?256:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();String tag="opposed-loop-fleet-"+right;
        try {
            fixture("dumbbell",new Class<?>[]{RailNetwork.class,Creature.class},net,actor);
            String home=right?"followr":"followl",away=right?"followl":"followr";
            List<String> route=Arrays.asList(home+"11",away+"11"),names=new ArrayList<String>();
            names.add(vehicle(net,actor,vehicles,"FleetLocalA",home+"4",home+"3",2,home+"5").name);
            names.add(vehicle(net,actor,vehicles,"FleetLocalB",home+"19",home+"20",1).name);
            names.add(vehicle(net,actor,vehicles,"FleetInbound","followc"+(right?35:5),"followc"+(right?36:4),1).name);
            names.add(vehicle(net,actor,vehicles,"FleetOpposite",away+"11",away+"10",1).name);
            List<Train> all=new ArrayList<Train>();
            for(String name:names) { Train train=net.trains.get(name);net.setRoute(name,route,.5);all.add(train); }
            Item cart=Items.getItem(all.get(0).carIds.get(1)),cargo=ItemFactory.createItem(ItemList.plank,77f,"Native opposed loop fleet");
            check(cart.insertItem(cargo),"opposed fleet retains real wagon cargo");long[][] initialSeats=seats(all);
            WurmRailRuntime runtime=bind(net,tag);for(Train train:all)net.resumeScheduled(train.name);
            int[] arrivals=new int[all.size()],cursor=new int[all.size()];double[] lastArrival=new double[all.size()];
            boolean restarted=false,complete=false,sawRecovery=false;
            for(int step=1;step<=Math.ceil(1800/dt)&&!complete;step++) {
                double seconds=step*dt;Map<Long,Pose> before=new HashMap<Long,Pose>();
                Map<String,String> target=new HashMap<String,String>();
                for(Train train:all) { target.put(train.name,train.route.get(train.routeIndex));before.put(train.carIds.get(0),train.poses.get(0)); }
                tick(runtime,net,all,dt);
                for(int i=0;i<all.size();i++) {
                    Train train=all.get(i);check(train.powered&&train.automated&&train.route.equals(route),"native opposed-loop correction preserves run intent and original itinerary");
                    sawRecovery|=!net.trafficRecoveryStatus(train.name).isEmpty();
                    if(train.routeIndex!=cursor[i]) {
                        Node station=net.nodes.get(target.get(train.name));boolean atStation=false;
                        for(Pose pose:train.poses)atStation|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                        check(atStation,"extra native recovery circuits cannot manufacture an unvisited scheduled arrival");
                        arrivals[i]++;cursor[i]=train.routeIndex;lastArrival[i]=seconds;
                    }
                    Pose old=before.get(train.carIds.get(0)),now=train.poses.get(0);
                    if(seconds>600&&Math.hypot(now.x-old.x,now.y-old.y)>.00001)
                        check(train.direction==1,"every opposed native locomotive eventually remains engine-leading on its regular schedule");
                }
                if(!restarted&&seconds>=120) {
                    net.movementGuard=null;net=reload(net,tag+"-active");all=new ArrayList<Train>();
                    for(String name:names)all.add(net.trains.get(name));runtime=bind(net,tag+"-restart");
                    for(Train train:all)net.resumeScheduled(train.name);restarted=true;
                }
                complete=seconds>=600;
                for(int i=0;i<all.size();i++)if(arrivals[i]<3||seconds-lastArrival[i]>180)complete=false;
            }
            check(complete&&sawRecovery,"all four opposed native services clear the jam and continue repeated forward station visits: "+Arrays.toString(arrivals)+" "+status(net,all));
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==77f,
                    "opposed loop correction and restart preserve all native passenger seats and the exact cargo item");
            System.out.println("PASS native opposed loop fleet: four services, conflicting physical orientations, progressive extra circuits, real station visits, forward service, active recovery restart, guarded commits and seats/cargo; mirror="+right+" dt="+dt);
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())) {net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }
    private static String legacyRail(int x,int y) { return "legacy_"+x+"_"+y; }
    private static String legacyPoint(RailNetwork net,Creature actor,String previous,int x,int y) throws Exception {
        String name=legacyRail(x,y);if(!net.nodes.containsKey(name)) { Node node=net.addNode(name,x,y+90,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId(); }
        if(previous!=null)net.connect(previous,name);return name;
    }
    private static void legacyGraph(RailNetwork net,Creature actor) throws Exception {
        String north=legacyPoint(net,actor,null,100,100),south=legacyPoint(net,actor,null,100,114),previous=north;
        for(int y=99;y>=96;y--)previous=legacyPoint(net,actor,previous,100,y);
        for(int x=101;x<=111;x++)previous=legacyPoint(net,actor,previous,x,96);
        for(int y=97;y<=100;y++)previous=legacyPoint(net,actor,previous,111,y);
        for(int x=110;x>=100;x--)previous=legacyPoint(net,actor,previous,x,100);
        previous=south;for(int y=115;y<=125;y++)previous=legacyPoint(net,actor,previous,100,y);
        for(int x=101;x<=103;x++)previous=legacyPoint(net,actor,previous,x,125);
        for(int y=124;y>=114;y--)previous=legacyPoint(net,actor,previous,103,y);
        for(int x=102;x>=100;x--)previous=legacyPoint(net,actor,previous,x,114);
        previous=north;for(int y=101;y<=114;y++)previous=legacyPoint(net,actor,previous,100,y);
        for(String junction:Arrays.asList(north,south)) {
            net.setJunctionAlignment(junction,17);Node at=net.nodes.get(junction);
            for(String link:at.links) { Node other=net.nodes.get(link);int side=RailNetwork.direction(other.tileX-at.tileX,other.tileY-at.tileY);net.signals().add(junction+"_"+side,junction,side,-10); }
        }
        for(String station:Arrays.asList(legacyRail(111,98),legacyRail(103,123)))net.setStation(station,true,.5);
    }
    private static WurmRailRuntime bind(final RailNetwork net,String tag) throws Exception {
        final WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());
        set(runtime,"network",net);set(runtime,"ready",true);
        set(runtime,"stateFile",Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-jam-"+tag+"/runtime.properties"));
        call(runtime,"initService",new Class<?>[0]);call(runtime,"refreshTrackVisuals",new Class<?>[0]);
        for(Train train:net.trains.values())call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        net.movementGuard=poses->{
            try { return (Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},poses); }
            catch(Exception error) { throw new IllegalStateException("Native jam guard failed",error); }
        };
        return runtime;
    }
    private static void tick(WurmRailRuntime runtime,RailNetwork net,List<Train> all,double dt) throws Exception {
        Map<Long,Pose> before=new HashMap<Long,Pose>();for(Train train:all)for(Pose pose:train.poses)before.put(pose.carId,pose);
        net.tick(dt);
        for(Train train:all) {
            check(!train.status.equals("Movement blocked by the world")&&!train.status.startsWith("Blocked:")&&!train.status.startsWith("Restore blocked:")&&!train.status.contains("world error"),
                    "native recovery remains valid: "+train.name+" "+train.status+"; "+get(runtime,"validationError"));
            call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            for(Pose pose:train.poses) {
                Pose old=before.get(pose.carId);double distance=Math.hypot(pose.x-old.x,pose.y-old.y);
                double turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                check(distance<=5*dt+.001&&turn<90&&(distance>.00001||turn<.01),"native recovery moves and turns only progressively along real track");
            }
        }
        check(nativeMatches(all),"all real items match the complete committed core tick");
        for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)for(Pose a:all.get(i).poses)for(Pose b:all.get(j).poses)
            check(a.layer!=b.layer||Math.hypot(a.x-b.x,a.y-b.y)>=RailNetwork.TRAIN_CLEARANCE-.0001,"recovery keeps four metres between different native trains");
        List<SignalSystem.Reservation> reserved=new ArrayList<SignalSystem.Reservation>(net.signals().reservations());
        for(int i=0;i<reserved.size();i++)for(int j=i+1;j<reserved.size();j++)if(!reserved.get(i).trainName.equals(reserved.get(j).trainName)) {
            @SuppressWarnings("unchecked") Set<String> first=new HashSet<String>((Set<String>)get(reserved.get(i),"resources"));
            @SuppressWarnings("unchecked") Set<String> second=(Set<String>)get(reserved.get(j),"resources");first.retainAll(second);
            check(first.isEmpty(),"yield never shares actual exclusive signal-block resources between trains");
        }
    }
    private static Train vehicle(RailNetwork net,Creature actor,List<Item> vehicles,String name,String from,String toward,int count,String...rear) throws Exception {
        return (Train)fixture("train",new Class<?>[]{RailNetwork.class,Creature.class,List.class,String.class,String.class,String.class,int.class,String[].class},net,actor,vehicles,name,from,toward,count,rear);
    }
    private static Object fixture(String name,Class<?>[] types,Object...args) throws Exception { return invoke(ConvoyRuntimeSmoke.class,null,name,types,args); }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception { return invoke(target.getClass(),target,name,types,args); }
    private static Object invoke(Class<?> type,Object target,String name,Class<?>[] types,Object[] args) throws Exception {
        Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(target,args); }
        catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private static Object get(Object target,String name) throws Exception { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target); }
    private static void set(Object target,String name,Object value) throws Exception { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
    private static boolean nativeMatches(List<Train> trains) throws Exception {
        for(Train train:trains)for(Pose pose:train.poses) { Item item=Items.getItem(pose.carId);if(Math.abs(item.getPosX()-pose.x)>.01||Math.abs(item.getPosY()-pose.y)>.01||Math.abs(item.getRotation()-pose.rotation)>.01)return false; }return true;
    }
    private static boolean samePoses(List<Pose> first,List<Pose> second) {
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++) { Pose a=first.get(i),b=second.get(i);if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false; }return true;
    }
    private static long[][] seats(List<Train> trains) throws Exception {
        List<long[]> rows=new ArrayList<long[]>();
        for(Train train:trains)for(long id:train.carIds) { Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<row.length;i++)row[i]=seats[i].occupant;rows.add(row); }
        return rows.toArray(new long[rows.size()][]);
    }
    private static String status(RailNetwork net,List<Train> trains) { List<String> out=new ArrayList<String>();for(Train train:trains)out.add(train.name+"="+train.status+" "+train.poses+" "+net.signals().trafficStatus(train.name));return out.toString(); }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
