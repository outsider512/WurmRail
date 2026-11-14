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
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.RailNetwork.*;

/** Actual native train controls, saved run intent, safe startup and remotely authorized fleet operations. */
public final class ScheduledRuntimeSmoke {
    private static int assertions;
    private static boolean captureDiagnostics;
    private static final List<String> capturedMessages=new ArrayList<String>();
    private ScheduledRuntimeSmoke() { }
    /** Called only by the isolated native communicator hook; normal test runs leave capture disabled. */
    public static void captureMessage(String message) {if(captureDiagnostics)capturedMessages.add(message);}

    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(999300032L,true,0);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        WurmRailRuntime runtime=null;
        try {
            List<Train> services=new ArrayList<Train>();
            for(int i=0;i<3;i++)services.add(service(net,actor,vehicles,"savedService"+i,90,220+i*8,i==0?2:1,actor.getWurmId(),true));
            Train manual=service(net,actor,vehicles,"savedManual",120,220,1,actor.getWurmId(),false);
            Train foreign=service(net,actor,vehicles,"savedForeign",150,220,1,actor.getWurmId()+256,true);
            Item engine=Items.getItem(services.get(0).carIds.get(0));
            Item cargo=ItemFactory.createItem(ItemList.plank,81f,"Scheduled restart fixture");
            Item cart=Items.getItem(services.get(0).carIds.get(1));
            check(cart.insertItem(cargo),"native scheduled wagon accepts exact cargo before controls and restart");
            long[][] originalSeats=seats(net.trains.values());
            Path directory=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-scheduled-services");
            Path state=directory.resolve("network.properties"),intents=directory.resolve("services.properties");
            runtime=runtime(net,state);position(actor,engine);
            RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
            RailwayTrainUi.View initial=api.snapshot(actor,engine.getWurmId());
            check(initial.canControl&&initial.services.size()==3,"ordinary owner sees three authorized repeating services and excludes manual and foreign trains");
            check(!initial.resumeEnabled&&initial.runningServices==0,"upgraded stopped schedules are not inferred to be running");
            for(RailwayTrainUi.Service service:initial.services)check(!service.powered&&service.status.equals(service.train.status)
                            &&service.traffic.equals(net.signals().trafficStatus(service.name)),
                    "native fleet snapshot captures each authorized train's actual power, precise status and traffic reason");
            String initialPanel=RailwayTrainUi.servicesContent(initial);
            check(initialPanel.contains("Power OFF")&&initialPanel.contains("Status: Stopped")&&initialPanel.contains("Traffic:"),
                    "one native fleet window exposes every stopped service's power and both diagnostic reasons");
            List<String> stoppedMessages=resolve(runtime,api,actor,services.get(0),initial);
            check(contains(stoppedMessages,"Use Start all scheduled first"),"Clear traffic jams explains that a fully stopped fleet must be started first");
            check(stoppedMessages.size()<=6&&contains(stoppedMessages,"Power OFF | Status: Stopped | Traffic:")
                            &&contains(stoppedMessages,"Railway services shows full Power, Status and Traffic"),
                    "stopped fleet receives bounded exact status/traffic diagnostics and a single-window follow-up");
            for(Train train:services)check(!train.powered,"requesting traffic recovery never restarts explicitly stopped services");

            control(runtime,api,actor,services.get(0),RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
            control(runtime,api,actor,services.get(1),RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
            float establishedX=Items.getItem(services.get(1).carIds.get(0)).getPosX();
            tick(runtime,net,4);
            check(Items.getItem(services.get(1).carIds.get(0)).getPosX()-establishedX>6,
                    "stopped service establishes real travelled native track history before testing traction-preserving fleet resume");
            control(runtime,api,actor,services.get(1),RailwayTrainUi.Request.of(RailwayTrainUi.Action.STOP));
            control(runtime,api,actor,manual,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
            ScheduledServices remembered=(ScheduledServices)get(runtime,"scheduledServices");
            check(remembered.runningIntent(services.get(0))&&!remembered.runningIntent(services.get(1))
                            &&!remembered.runningIntent(services.get(2))&&!remembered.runningIntent(manual),
                    "real queued START/STOP distinguish running schedule, explicit STOP, never started service and manual power");
            Train leading=services.get(0);net.stop(leading.name);net.reverse(leading.name);
            leading.routeIndex=1;leading.maxSpeed=2.25;leading.dwellRemaining=3.75;
            check(api.snapshot(actor,Items.getItem(manual.carIds.get(0)).getWurmId()).train==manual,"manual control remains independent from fleet intent");
            runtime.onServerShutdown();
            for(Train train:net.trains.values())check(!train.powered&&train.speed==0,"shutdown stops every actual train before durable world save");
            ScheduledServices saved=ScheduledServices.load(intents);
            check(saved.runningIntent(leading)&&saved.size()==1,"graceful shutdown saves requested running intent without converting its safety STOP to operator STOP");

            net=RailNetwork.load(state);runtime=runtime(net,state);set(runtime,"scheduledServices",saved);
            services=Arrays.asList(net.trains.get("savedService0"),net.trains.get("savedService1"),net.trains.get("savedService2"));
            manual=net.trains.get("savedManual");foreign=net.trains.get("savedForeign");leading=services.get(0);
            // The helper must see every native item restored while all services are still stationary.
            for(Train train:net.trains.values())check(!train.powered&&matches(train),"all saved native vehicles are restored before any scheduled service receives power");
            for(Train train:net.trains.values())for(Pose pose:train.poses)
                WurmRailWorld.moveCar(pose.carId,pose.x+1,pose.y,pose.rotation,pose.layer);
            ((Map<?,?>)get(runtime,"appliedPoses")).clear();
            check(!matches(leading),"fixture deliberately begins restart with native positions different from checked rail positions");
            set(runtime,"autoResumeScheduled",false);
            call(runtime,"resumeScheduledServices",new Class<?>[0]);
            for(Train train:net.trains.values())check(matches(train),"startup restores every native saved vehicle before running services move again");
            check(!leading.powered&&saved.runningIntent(leading),"server auto-resume setting can hold a saved running service without erasing its operator intent");
            set(runtime,"autoResumeScheduled",true);call(runtime,"resumeScheduledServices",new Class<?>[0]);
            check(leading.powered&&leading.automated&&!services.get(1).powered&&!services.get(2).powered&&!manual.powered&&!foreign.powered,
                    "startup resumes only the exact requested scheduled service after the complete world restore");
            check(leading.routeIndex==1&&leading.maxSpeed==2.25&&leading.direction==-1&&leading.dwellRemaining==3.75,
                    "native startup preserves next stop, selected speed, physical traction and remaining station dwell");
            check(Arrays.deepEquals(originalSeats,seats(net.trains.values()))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==81f,
                    "native startup preserves all vehicle seats and the same cargo item");
            api=(RailwayTrainUi.Api)get(runtime,"trainApi");position(actor,engine);
            RailwayTrainUi.View fleet=api.snapshot(actor,engine.getWurmId());
            check(fleet.resumeEnabled&&fleet.runningServices==1&&fleet.services.size()==3,"reloaded native menu reports persisted restart intent and live fleet counts");
            net.reverse(services.get(1).name);
            float actorX=actor.getPosX(),actorY=actor.getPosY();
            RailwayTrainUi.Request start=RailwayTrainUi.servicesFromAnswers(fleet,answer("servicesStart"));
            queued(runtime,api,actor,leading,start);
            for(Train train:services)check(train.powered&&saved.runningIntent(train),"one queued fleet start powers and persists each owned remote schedule");
            check(leading.direction==-1&&services.get(1).direction==-1,"resuming saved or stopped services preserves their backing traction without reselecting forward at fleet start");
            check(actor.getPosX()==actorX&&actor.getPosY()==actorY&&!manual.powered&&!foreign.powered,
                    "starting remote schedules requires no walking and never powers manual or foreign trains");
            RailwayTrainUi.View runningPanel=api.snapshot(actor,engine.getWurmId());
            List<String> runningMessages=resolve(runtime,api,actor,leading,runningPanel);
            check(contains(runningMessages,"3 of 3 scheduled trains are powered ON")&&!contains(runningMessages,"Use Start all scheduled first"),
                    "no-op recovery distinguishes powered schedules from an off fleet instead of reporting generic failure");
            for(Train train:services)check(contains(runningMessages,"Power ON | Status: "+train.status+" | Traffic:"),
                    "no-op recovery reports each authorized live service's actual reason without changing its power");
            tick(runtime,net,2);
            check(services.get(1).speed>0&&services.get(2).speed>0,"remote services actually accelerate on their existing native routes");
            position(actor,engine);fleet=api.snapshot(actor,engine.getWurmId());
            queued(runtime,api,actor,leading,RailwayTrainUi.servicesFromAnswers(fleet,answer("servicesStop")));
            for(Train train:services)check(!train.powered&&train.speed==0&&!saved.runningIntent(train),"fleet STOP immediately stops moving schedules and clears restart intent independently");

            // A queued capability never survives operator access changing before execution.
            set(runtime,"operators","*");position(actor,engine);RailwayTrainUi.View formerlyAllowed=api.snapshot(actor,engine.getWurmId());
            check(formerlyAllowed.services.size()==4,"configured ordinary operator may capture a manageable foreign scheduled train");
            RailwayTrainUi.Request staleAccess=RailwayTrainUi.servicesFromAnswers(formerlyAllowed,answer("servicesStart"));set(runtime,"operators","Nobody");
            try {call(runtime,"applyServices",new Class<?>[]{Creature.class,List.class,RailwayTrainUi.Action.class},actor,staleAccess.services,staleAccess.action);}
            catch(IllegalArgumentException denied) { }
            check(!foreign.powered&&!saved.runningIntent(foreign),"fleet execution rechecks live remote ownership/operator ACL before enabling a foreign service");
            for(Train train:services)net.stop(train.name);
            for(Train train:services)saved.record(train,false);

            // Reusing the same stable name and native locomotive still creates a new registration identity.
            position(actor,engine);RailwayTrainUi.View beforeReplacement=api.snapshot(actor,engine.getWurmId());
            RailwayTrainUi.Request staleIdentity=RailwayTrainUi.servicesFromAnswers(beforeReplacement,answer("servicesStart"));
            Train old=services.get(2);Pose oldPose=old.poses.get(0);Node origin=net.nodes.get(oldPose.railName);
            net.removeTrain(old.name);String towards=origin.links.iterator().next();
            Train replacement=net.createTrain(old.name,old.ownerId,old.carIds,origin.name,towards);net.setRoute(replacement.name,old.route,old.routeDwellSeconds);
            call(runtime,"applyPoses",new Class<?>[]{Train.class},replacement);
            try {call(runtime,"applyServices",new Class<?>[]{Creature.class,List.class,RailwayTrainUi.Action.class},actor,staleIdentity.services,staleIdentity.action);}
            catch(IllegalArgumentException denied) { }
            check(!replacement.powered&&!saved.runningIntent(replacement),"reclaimed same-name same-engine registration cannot be started by a stale fleet window");
            services=Arrays.asList(services.get(0),services.get(1),replacement);
            for(Train train:services) {net.stop(train.name);saved.record(train,false);}

            // Force a real filesystem failure after the control has proposed a start.
            Path blocker=directory.resolve("blocked-parent");Files.write(blocker,new byte[]{1});set(runtime,"stateFile",blocker.resolve("network.properties"));
            position(actor,engine);final WurmRailRuntime failedRuntime=runtime;final RailwayTrainUi.Api failedApi=api;final Train failedLeading=leading;
            expectFailure(()->call(failedRuntime,"applyTrainRequest",new Class<?>[]{Creature.class,long.class,Train.class,RailwayTrainUi.Request.class},
                    actor,engine.getWurmId(),failedLeading,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START)),"single native start reports failed state save");
            check(!leading.powered&&!saved.runningIntent(leading),"failed single start rolls back both power and durable service intent");
            RailwayTrainUi.View fresh=failedApi.snapshot(actor,engine.getWurmId());RailwayTrainUi.Request failedFleet=RailwayTrainUi.servicesFromAnswers(fresh,answer("servicesStart"));
            expectFailure(()->call(failedRuntime,"applyServices",new Class<?>[]{Creature.class,List.class,RailwayTrainUi.Action.class},actor,failedFleet.services,failedFleet.action),
                    "fleet start reports failed state save");
            for(Train train:services)check(!train.powered&&!saved.runningIntent(train),"failed fleet save rolls back every changed service and its run intent");
            set(runtime,"stateFile",state);
            stoppedControlSaveFailures(runtime,net,actor,leading,state,intents);
            check(Arrays.deepEquals(originalSeats,seats(net.trains.values()))&&cargo.getParentId()==cart.getWurmId(),
                    "fleet start, STOP, stale forms and transactional failures preserve native seats and cargo");
            for(int i=0;i<3;i++) {
                Train extra=service(net,actor,vehicles,"diagnosticExtra"+i,180,220+i*8,1,actor.getWurmId(),true);
                call(runtime,"applyPoses",new Class<?>[]{Train.class},extra);
            }
            position(actor,engine);RailwayTrainUi.View sixServices=api.snapshot(actor,engine.getWurmId());
            check(sixServices.services.size()==6,"native six-service diagnostic fixture includes every authorized scheduled train");
            List<String> sixMessages=resolve(runtime,api,actor,leading,sixServices);
            check(sixMessages.size()<=6&&contains(sixMessages,"2 more trains are listed there"),
                    "six-train no-op recovery caps chat diagnostics at four train rows and points to the complete fleet panel");
            check(count(RailwayTrainUi.servicesContent(sixServices)," | Power OFF")==6,
                    "fleet panel retains every service's diagnostics even when chat output is capped");
            runtime.onServerShutdown();
            System.out.println("PASS native scheduled restart intent, explicit STOP/manual safety, saved dwell and cursor, remotely authorized fleet controls, stale forms and save rollback ("+assertions+" assertions)");
        } finally {
            if(runtime!=null)set(runtime,"ready",false);
            for(Train train:new ArrayList<Train>(net.trains.values()))net.stop(train.name);
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
        }
    }

    /** Stop intent must survive a network-only save failure with the intent sidecar still writable. */
    private static void stoppedControlSaveFailures(WurmRailRuntime runtime,RailNetwork net,Creature actor,Train train,Path state,Path intents) throws Exception {
        List<String> itinerary=new ArrayList<String>(train.route);Path blocked=state.resolveSibling("blocked-network-target");
        Files.createDirectories(blocked);Files.write(blocked.resolve("not-a-network-file"),new byte[]{1});
        for(String mode:new String[]{"manualUi","reverseUi","manualCommand","routeCommand"}) {
            net.stop(train.name);net.setRoute(train.name,itinerary,train.routeDwellSeconds);set(runtime,"stateFile",state);
            position(actor,Items.getItem(train.carIds.get(0)));call(runtime,"startService",new Class<?>[]{Train.class},train);
            ScheduledServices services=(ScheduledServices)get(runtime,"scheduledServices");
            check(train.powered&&services.runningIntent(train)&&ScheduledServices.load(intents).runningIntent(RailNetwork.load(state).trains.get(train.name)),
                    "fixture persists a running automatic service before testing stopped control failure: "+mode);
            int previousDirection=train.direction;set(runtime,"stateFile",blocked);
            try {
                expectFailure(()-> {
                    if(mode.equals("manualUi")||mode.equals("reverseUi"))call(runtime,"applyTrainRequest",
                            new Class<?>[]{Creature.class,long.class,Train.class,RailwayTrainUi.Request.class},actor,train.carIds.get(0),train,
                            RailwayTrainUi.Request.of(mode.equals("manualUi")?RailwayTrainUi.Action.MANUAL:RailwayTrainUi.Action.REVERSE));
                    else call(runtime,"execute",new Class<?>[]{Creature.class,String.class},actor,
                            mode.equals("manualCommand")?"manual "+train.name:"route "+train.name+" "+itinerary.get(1)+","+itinerary.get(0));
                },"control reports the network-only save failure: "+mode);
                check(!train.powered&&train.speed==0&&!services.runningIntent(train),"successful stopped control remains off with cleared in-memory run intent after network save fails: "+mode);
                check(mode.equals("routeCommand")?train.automated&&train.route.equals(Arrays.asList(itinerary.get(1),itinerary.get(0))):!train.automated&&train.route.isEmpty(),
                        "requested manual/replacement route change actually applied before the deliberate save failure: "+mode);
                if(mode.equals("reverseUi"))check(train.direction==-previousDirection,"successful manual reversal retains its requested traction despite later network save failure");
                RailNetwork previousRail=RailNetwork.load(state);ScheduledServices durableStopped=ScheduledServices.load(intents);
                check(previousRail.trains.get(train.name).automated&&!durableStopped.resumeCandidates(previousRail).contains(train.name),
                        "writable sidecar durably prevents restart of the old saved automatic route after stopped control network save failure: "+mode);
            } finally {set(runtime,"stateFile",state);}
        }
        call(runtime,"save",new Class<?>[0]);
    }

    private static Train service(RailNetwork network,Creature actor,List<Item> vehicles,String name,int left,int top,int cars,long owner,boolean automatic) throws Exception {
        List<String> ring=new ArrayList<String>();int index=0;
        for(int x=left;x<=left+20;x++)ring.add(point(network,actor,name,index++,x,top));
        for(int y=top+1;y<=top+4;y++)ring.add(point(network,actor,name,index++,left+20,y));
        for(int x=left+19;x>=left;x--)ring.add(point(network,actor,name,index++,x,top+4));
        for(int y=top+3;y>top;y--)ring.add(point(network,actor,name,index++,left,y));
        for(int i=0;i<ring.size();i++)network.connect(ring.get(i),ring.get((i+1)%ring.size()));
        for(int stop:new int[]{15,35}) {network.setStation(ring.get(stop),true,6);Items.getItem(network.nodes.get(ring.get(stop)).markerId).setName("Station: "+name+" "+stop);}
        List<Long> ids=new ArrayList<Long>();
        for(int car=0;car<cars;car++) {
            Node node=network.nodes.get(ring.get(5-car));Item item=car==0?WurmRailWorld.spawnLocomotive(actor,node.worldX(),node.worldY(),0):WurmRailWorld.spawnCart(actor,node.worldX(),node.worldY(),0);
            vehicles.add(item);ids.add(item.getWurmId());
        }
        Train train=network.createTrain(name,owner,ids,ring.get(5),ring.get(6));
        if(automatic)network.setRoute(name,Arrays.asList(ring.get(15),ring.get(35)),6);
        return train;
    }
    private static String point(RailNetwork network,Creature actor,String prefix,int index,int x,int y) throws Exception {
        Node node=network.addNode(prefix+"_"+index,x,y,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();return node.name;
    }
    private static WurmRailRuntime runtime(RailNetwork net,Path state) throws Exception {
        WurmRailRuntime runtime=new WurmRailRuntime();Properties config=new Properties();config.setProperty("operators","Nobody");runtime.configure(config);
        set(runtime,"network",net);set(runtime,"stateFile",state);set(runtime,"ready",true);call(runtime,"initService",new Class<?>[0]);
        call(runtime,"refreshTrackVisuals",new Class<?>[0]);for(Train train:net.trains.values())call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        return runtime;
    }
    private static void control(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Train train,RailwayTrainUi.Request request) throws Exception {
        position(actor,Items.getItem(train.carIds.get(0)));queued(runtime,api,actor,train,request);
    }
    private static void queued(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Train train,RailwayTrainUi.Request request) throws Exception {
        api.submit(actor,train.carIds.get(0),train,request);set(runtime,"lastTick",System.nanoTime());set(runtime,"lastDiscovery",System.nanoTime());set(runtime,"lastSave",System.currentTimeMillis());runtime.onServerPoll();
    }
    private static List<String> resolve(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Train train,RailwayTrainUi.View snapshot) throws Exception {
        capturedMessages.clear();captureDiagnostics=true;
        try {queued(runtime,api,actor,train,RailwayTrainUi.servicesFromAnswers(snapshot,answer("servicesResolve")));}
        finally {captureDiagnostics=false;}
        return new ArrayList<String>(capturedMessages);
    }
    private static boolean contains(List<String> messages,String text) {for(String message:messages)if(message.contains(text))return true;return false;}
    private static int count(String text,String fragment) {int count=0,at=0;while((at=text.indexOf(fragment,at))>=0){count++;at+=fragment.length();}return count;}
    private static void tick(WurmRailRuntime runtime,RailNetwork net,double seconds) throws Exception {
        for(int i=0;i<(int)(seconds*10);i++) {net.tick(.1);for(Train train:net.trains.values())call(runtime,"applyPoses",new Class<?>[]{Train.class},train);}
    }
    private static void position(Creature actor,Item item) {actor.getStatus().getPosition().setPosX(item.getPosX());actor.getStatus().getPosition().setPosY(item.getPosY());}
    private static boolean matches(Train train) throws Exception {
        for(Pose pose:train.poses){Item item=Items.getItem(pose.carId);if(item.getPosX()!=pose.x||item.getPosY()!=pose.y||item.isOnSurface()!=(pose.layer==0))return false;}return true;
    }
    private static long[][] seats(Collection<Train> trains) throws Exception {
        List<long[]> result=new ArrayList<long[]>();for(Train train:trains)for(long id:train.carIds){Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<seats.length;i++)row[i]=seats[i].occupant;result.add(row);}return result.toArray(new long[result.size()][]);
    }
    private static Properties answer(String button) {Properties p=new Properties();p.setProperty(button,"true");return p;}
    private static Object get(Object object,String key) throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String key,Object value) throws Exception {Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);f.set(object,value);}
    private static Object call(Object object,String key,Class<?>[] types,Object...args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(key,types);method.setAccessible(true);try{return method.invoke(object,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();if(failure.getCause() instanceof Error)throw (Error)failure.getCause();throw failure;}
    }
    private interface Checked {void run() throws Exception;}
    private static void expectFailure(Checked action,String message) throws Exception {try{action.run();}catch(Exception expected){assertions++;return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
