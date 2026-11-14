import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
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

/** Native queued signal construction, ownership diagnostics, persistence and transaction checks. */
public final class SignalRuntimeSmoke {
    private static int assertions;
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(950000032L,true,0);
        position(actor,198,210);
        RailNetwork net=new RailNetwork();
        for(int x=190;x<=206;x++) {
            Node n=net.addNode("signalNative"+x,x,210,0);
            n.markerId=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0).getWurmId();
            if(x>190)net.connect("signalNative"+(x-1),n.name);
        }
        Node rail=net.nodes.get("signalNative198"),next=net.nodes.get("signalNative199");
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("signal-runtime/network.properties");
        WurmRailRuntime runtime=new WurmRailRuntime();
        set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);
        RailwaySignalUi.Api api=(RailwaySignalUi.Api)get(runtime,"signalApi");
        RailwaySignalUi.View initial=api.snapshot(actor,rail.markerId);
        check(initial.signals.isEmpty()&&initial.approachSides.equals(Arrays.asList(2,6)),"configuration offers absolute connected approaches only");
        RailwaySignalUi.Request west=place(initial,1);
        fail(()->apply(runtime,actor,rail.markerId,west),"placing a signal requires a crafted section");
        check(net.signals().definitions().isEmpty(),"cost rejection leaves no phantom definition");
        Item first=section(actor,67),second=section(actor,71);
        api.submit(actor,rail.markerId,west);
        check(net.signals().definitions().isEmpty()&&RailwayTrackMaterials.count(actor)==2,"signal answers queue native construction");
        poll(runtime);
        SignalSystem.Signal signal=net.signals().at(rail.name,6);
        check(signal!=null&&RailwayTrackMaterials.count(actor)==1,"server poll consumes exactly one section for the selected approach");
        Item physical=physical(rail.markerId,6);
        check(physical!=null&&physical.getData()==rail.markerId&&(physical.getAuxData()&255)==6,"new signal is red and has a persistent absolute parent/approach");
        boolean usedFirst=!Items.getItemOptional(first.getWurmId()).isPresent();
        check(physical.getQualityLevel()==(usedFirst?67:71),"signal preserves the consumed crafted section quality");
        check(RailNetwork.load(state).signals().at(rail.name,6)!=null,"signal definition survives network format persistence");
        fail(()->apply(runtime,actor,rail.markerId,west),"replayed construction cannot consume another material");
        check(RailwayTrackMaterials.count(actor)==1,"replayed signal creation is free of side effects");
        for(int heading=0;heading<360;heading+=45) {
            actor.getStatus().setRotation(heading);
            RailwaySignalUi.View facing=api.snapshot(actor,physical.getWurmId());
            check(facing.signals.get(0).approach==6&&signal.approachDirection==6,"player heading cannot reinterpret signal direction");
        }

        RailwaySignalUi.Request east=place(api.snapshot(actor,rail.markerId),0);
        api.submit(actor,rail.markerId,east);set(runtime,"builders","Nobody");poll(runtime);
        check(net.signals().at(rail.name,2)==null&&RailwayTrackMaterials.count(actor)==1,"queued signal creation rechecks builder permissions");
        set(runtime,"builders","*");api.submit(actor,rail.markerId,east);position(actor,150,210);poll(runtime);
        check(net.signals().at(rail.name,2)==null&&RailwayTrackMaterials.count(actor)==1,"queued signal creation rechecks native reach");
        position(actor,198,210);apply(runtime,actor,rail.markerId,east);
        check(net.signals().at(rail.name,2)!=null&&RailwayTrackMaterials.count(actor)==0,"opposite travel direction uses a distinct paid signal");
        RailwaySignalUi.View visitor;
        set(runtime,"builders","Nobody");visitor=api.snapshot(actor,rail.markerId);
        check(!visitor.canManage&&visitor.signals.size()==2,"visitors can inspect aspects without builder privileges");
        set(runtime,"builders","*");
        fail(()->call(runtime,"removeAndRecover",new Class<?>[]{Creature.class,Node.class},actor,rail),"signal definitions protect linked track removal");

        // A failed disk write restores definition/material/native item as one construction transaction.
        section(actor,59);
        RailwaySignalUi.Request doomed=place(api.snapshot(actor,next.markerId),0);
        Path blocked=state.resolveSibling("state-is-directory");Files.createDirectories(blocked);
        set(runtime,"stateFile",blocked);
        fail(()->apply(runtime,actor,next.markerId,doomed),"save failure aborts signal construction");
        set(runtime,"stateFile",state);
        check(net.signals().at(next.name,2)==null&&physical(next.markerId,2)==null&&RailwayTrackMaterials.count(actor)==1,
                "save failure refunds material and removes both signal definition and native item");

        // A green aspect is owned by A; another train cannot borrow it or remove the protection.
        Node start=net.nodes.get("signalNative196"),towards=net.nodes.get("signalNative197"),destination=net.nodes.get("signalNative204");
        destination.station=true;
        Item engine=WurmRailWorld.spawnLocomotive(actor,start.worldX(),start.worldY(),0);
        engine.setName("Harbor Express");
        Train owner=net.createTrain("nativeSignalOwner",actor.getWurmId(),Collections.singletonList(engine.getWurmId()),start.name,towards.name);
        net.setRoute(owner.name,Collections.singletonList(destination.name),2);
        SignalSystem.RequestResult acquired=net.signals().request(owner.name,signal.id);
        check(acquired.granted&&net.signals().ownsAuthority(owner.name,signal.id),"explicit native route request grants authority to its train");
        call(runtime,"refreshSignalVisuals",new Class<?>[]{boolean.class},true);
        physical=physical(rail.markerId,6);
        check((physical.getAuxData()&255)==14&&physical.getName().contains("Harbor Express"),"reserved route shows green and the player's train name while authority keeps its stable core identity");
        RailwaySignalUi.View active=api.snapshot(actor,physical.getWurmId());
        RailwaySignalUi.Signal presented=null;for(RailwaySignalUi.Signal shown:active.signals)if(shown.id.equals(signal.id))presented=shown;
        check(presented!=null&&presented.aspect.equals("GREEN")&&presented.owner.equals("Harbor Express")&&presented.route.contains("Track at 198, 210")
                        &&!presented.route.contains("signalNative198"),
                "inspection reports the green owner and useful route coordinates without internal track keys");
        RailwaySignalUi.Request lockedRemoval=remove(active,signal.id);
        fail(()->apply(runtime,actor,rail.markerId,lockedRemoval),"players cannot remove a protecting signal while its route is owned");
        check(net.signals().ownsAuthority(owner.name,signal.id),"refused edit preserves active movement authority");
        net.signals().cancelUnused(owner.name);
        check(net.signals().aspect(signal.id)==SignalSystem.Aspect.RED,"unentered cancelled route returns red");
        net.removeTrain(owner.name);Items.destroyItem(engine.getWurmId());
        call(runtime,"refreshSignalVisuals",new Class<?>[]{boolean.class},false);
        check((physical(rail.markerId,6).getAuxData()&255)==6,"release updates the same visible native signal back to red");

        RailwaySignalUi.Request failedRemoval=remove(api.snapshot(actor,rail.markerId),signal.id);
        actor.failDelivery=true;
        try { fail(()->apply(runtime,actor,rail.markerId,failedRemoval),"failed native recovery aborts signal removal"); }
        finally { actor.failDelivery=false; }
        check(net.signals().get(signal.id)!=null&&physical(rail.markerId,6)!=null&&RailwayTrackMaterials.count(actor)==1,
                "failed removal restores definition and native marker without duplicating material");
        RailwaySignalUi.Request removal=remove(api.snapshot(actor,rail.markerId),signal.id);
        apply(runtime,actor,rail.markerId,removal);
        check(net.signals().get(signal.id)==null&&physical(rail.markerId,6)==null&&RailwayTrackMaterials.count(actor)==2,
                "successful removal removes only its configured approach and returns one section");
        check(net.signals().at(rail.name,2)!=null,"removing one direction preserves the opposite signal");
        RailwaySignalUi.Request replay=removal;
        fail(()->apply(runtime,actor,rail.markerId,replay),"stale removal cannot recover another section");
        check(RailNetwork.load(state).signals().at(rail.name,6)==null&&RailNetwork.load(state).signals().at(rail.name,2)!=null,
                "final definition cleanup and opposite approach persist together");
        recoveryControls(false,false,222);
        recoveryControls(false,true,224);
        recoveryControls(true,false,226);
        recoveryControls(true,true,228);
        mountedRearControls();
        System.out.println("PASS native signal queued construction, paid costs, save rollback, absolute direction, status, ownership and locked edits ("+assertions+" assertions)");
    }

    /** Real controls must let a stationary red-waiting consist retreat, without bypassing red. */
    private static void recoveryControls(boolean automatic,boolean stopFirst,int y) throws Exception {
        String prefix="redRecovery"+y;
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(960000032L+y*256L,true,2);
        RailNetwork net=new RailNetwork();
        for(int x=8;x<=48;x++) {
            Node n=net.addNode(prefix+x,x,y,0);
            n.markerId=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0).getWurmId();
            if(x>8)net.connect(prefix+(x-1),n.name);
        }
        Node start=net.nodes.get(prefix+20),towards=net.nodes.get(prefix+21),blocked=net.nodes.get(prefix+32);
        Item engine=WurmRailWorld.spawnLocomotive(actor,start.worldX(),start.worldY(),0);
        Item cart=WurmRailWorld.spawnCart(actor,net.nodes.get(prefix+19).worldX(),start.worldY(),0);
        Item obstruction=WurmRailWorld.spawnLocomotive(actor,blocked.worldX(),blocked.worldY(),0);
        Item cargo=ItemFactory.createItem(ItemList.plank,53f,"Red signal recovery fixture");
        check(cart.insertItem(cargo),"red-recovery cart accepts real native cargo");
        Train train=net.createTrain(prefix,actor.getWurmId(),Arrays.asList(engine.getWurmId(),cart.getWurmId()),start.name,towards.name);
        Train blocker=net.createTrain(prefix+"Blocker",actor.getWurmId(),Collections.singletonList(obstruction.getWurmId()),blocked.name,prefix+33);
        net.signals().add(prefix+"Entry",prefix+24,6,-10L);
        net.signals().add(prefix+"Exit",prefix+40,6,-10L);
        Node destination=net.nodes.get(prefix+46);net.setStation(destination.name,true,0);
        if(automatic)net.setRoute(train.name,Collections.singletonList(destination.name),0);
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve(prefix+"/network.properties");
        WurmRailRuntime runtime=new WurmRailRuntime();
        set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);
        net.start(train.name);net.tick(20);
        check(train.powered&&train.speed==0&&train.status.contains("RED"),"real red route denial waits powered at zero speed: "+prefix);
        check(train.poses.get(0).x<net.nodes.get(prefix+24).worldX()-2,"red wait stays outside the protected entry: "+prefix);
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        actor.getStatus().getPosition().setPosX(engine.getPosX());actor.getStatus().getPosition().setPosY(engine.getPosY());
        RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        RailwayTrainUi.View view=api.snapshot(actor,engine.getWurmId());
        check(view.canControl&&view.powered&&view.speed==0&&view.train==train,"native controls retain the exact powered red-waiting train identity");
        List<Pose> held=new ArrayList<Pose>(train.poses);
        double[][] nativeHeld=nativePoses(train);
        long[][] seats=seatOccupants(train);
        int direction=train.direction;
        if(stopFirst) {
            api.submit(actor,engine.getWurmId(),view.train,RailwayTrainUi.fromAnswers(view,answer("stop")));
            check(train.powered,"STOP answer waits for server poll before changing power");poll(runtime);
            check(!train.powered&&train.speed==0,"STOP releases the powered red waiting state");
            net.tick(5);poll(runtime);
            check(!train.powered&&train.speed==0&&train.status.equals("Stopped")&&samePoses(held,train.poses)
                            &&sameNative(nativeHeld,nativePoses(train)),
                    "STOP at red survives later movement polls without automatic restart or position change");
            view=api.snapshot(actor,engine.getWurmId());
        }
        api.submit(actor,engine.getWurmId(),view.train,RailwayTrainUi.fromAnswers(view,answer("reverse","speed","NaN","name","bad\nname")));
        check(train.direction==direction&&samePoses(held,train.poses),"Reverse answer is queued and ignores unrelated form fields");
        poll(runtime);
        check(train.direction==-direction&&!train.powered&&train.speed==0,"stationary red-waiting Reverse reverses and remains safely stopped: "+prefix);
        check(!train.automated&&train.route.isEmpty(),"operator Reverse clears automation so automatic station seeking cannot flip it back");
        check(samePoses(held,train.poses)&&sameNative(nativeHeld,nativePoses(train)),"stationary Reverse keeps all native/core car positions and facings unchanged");
        check(sameSeats(seats,seatOccupants(train))&&cargo.getParentId()==cart.getWurmId()&&train.carIds.size()==2,
                "red recovery preserves every native seat, both car identities, and cargo parent");
        RailwayTrainUi.View reversed=api.snapshot(actor,engine.getWurmId());
        api.submit(actor,engine.getWurmId(),reversed.train,RailwayTrainUi.fromAnswers(reversed,answer("start")));poll(runtime);
        check(train.powered,"Start after red recovery powers the reversed train");
        float before=engine.getPosX();net.tick(2);call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        check(engine.getPosX()<before-.25f&&train.speed>0,"reversed train actually retreats from the red signal after Start: "+prefix);
        check(net.signals().aspect(prefix+"Entry")==SignalSystem.Aspect.RED&&net.signals().reservations().isEmpty(),
                "retreat does not grant or borrow a forward red route");
        check(cargo.getParentId()==cart.getWurmId()&&sameSeats(seats,seatOccupants(train)),"native retreat retains cargo and every passenger seat");
        actor.getStatus().getPosition().setPosX(engine.getPosX());actor.getStatus().getPosition().setPosY(engine.getPosY());
        RailwayTrainUi.View moving=api.snapshot(actor,engine.getWurmId());
        int movingDirection=train.direction;double movingSpeed=train.speed;
        double[][] movingPoses=nativePoses(train);
        api.submit(actor,engine.getWurmId(),moving.train,RailwayTrainUi.fromAnswers(moving,answer("reverse")));poll(runtime);
        check(train.direction==movingDirection&&train.powered&&train.speed==movingSpeed&&sameNative(movingPoses,nativePoses(train)),
                "moving Reverse is still rejected without changing native movement, direction, or power");
        net.stop(train.name);net.removeTrain(train.name);net.removeTrain(blocker.name);
        Items.destroyItem(engine.getWurmId());Items.destroyItem(cart.getWurmId());Items.destroyItem(obstruction.getWurmId());
    }
    private static double[][] nativePoses(Train train) throws Exception {
        double[][] result=new double[train.carIds.size()][4];
        for(int i=0;i<result.length;i++) { Item item=Items.getItem(train.carIds.get(i));result[i]=new double[]{item.getPosX(),item.getPosY(),item.getPosZ(),item.getRotation()}; }
        return result;
    }
    private static long[][] seatOccupants(Train train) throws Exception {
        long[][] result=new long[train.carIds.size()][];
        for(int i=0;i<result.length;i++) { Vehicle vehicle=Vehicles.getVehicle(Items.getItem(train.carIds.get(i)));result[i]=new long[vehicle.seats.length];for(int s=0;s<vehicle.seats.length;s++)result[i][s]=vehicle.seats[s].occupant; }
        return result;
    }
    private static boolean sameSeats(long[][] first,long[][] second) { return Arrays.deepEquals(first,second); }
    private static boolean sameNative(double[][] first,double[][] second) { return Arrays.deepEquals(first,second); }
    private static boolean samePoses(List<Pose> first,List<Pose> second) {
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++) { Pose a=first.get(i),b=second.get(i);if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false; }
        return true;
    }

    /** A mounted operator must retain the engine panel from a rear cart beyond ground reach. */
    private static void mountedRearControls() throws Exception {
        int y=230;String prefix="mountedRedRecovery";
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(960100032L,true,2);
        RailNetwork net=new RailNetwork();
        for(int x=8;x<=48;x++) {
            Node n=net.addNode(prefix+x,x,y,0);n.markerId=WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0).getWurmId();
            if(x>8)net.connect(prefix+(x-1),n.name);
        }
        Node start=net.nodes.get(prefix+20),blocked=net.nodes.get(prefix+32);
        Item engine=WurmRailWorld.spawnLocomotive(actor,start.worldX(),start.worldY(),0);
        Item cart=WurmRailWorld.spawnCart(actor,net.nodes.get(prefix+19).worldX(),start.worldY(),0);
        Item middle=WurmRailWorld.spawnCart(actor,net.nodes.get(prefix+18).worldX(),start.worldY(),0);
        Item rear=WurmRailWorld.spawnCart(actor,net.nodes.get(prefix+17).worldX(),start.worldY(),0);
        Item obstruction=WurmRailWorld.spawnLocomotive(actor,blocked.worldX(),blocked.worldY(),0);
        Train train=net.createTrain(prefix,actor.getWurmId(),Arrays.asList(engine.getWurmId(),cart.getWurmId(),middle.getWurmId(),rear.getWurmId()),start.name,prefix+21);
        Train blocker=net.createTrain(prefix+"Blocker",actor.getWurmId(),Collections.singletonList(obstruction.getWurmId()),blocked.name,prefix+33);
        net.signals().add(prefix+"Entry",prefix+24,6,-10L);net.signals().add(prefix+"Exit",prefix+40,6,-10L);
        WurmRailRuntime runtime=new WurmRailRuntime();
        set(runtime,"network",net);set(runtime,"ready",true);
        set(runtime,"stateFile",Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve(prefix+"/network.properties"));
        call(runtime,"initService",new Class<?>[0]);net.start(train.name);net.tick(20);
        check(train.powered&&train.speed==0&&train.status.contains("RED"),"mounted recovery fixture is truly waiting powered at red");
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        actor.getStatus().getPosition().setPosX(rear.getPosX());actor.getStatus().getPosition().setPosY(rear.getPosY());
        check(Math.hypot(actor.getPosX()-engine.getPosX(),actor.getPosY()-engine.getPosY())>8,
                "rear-cart rider is beyond the ordinary engine ground-interaction range");
        RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        fail(()->api.snapshot(actor,engine.getWurmId()),"unmounted player cannot open distant engine controls");
        actor.vehicle=rear.getWurmId();
        fail(()->api.snapshot(actor,engine.getWurmId()),"forged vehicle membership without a native occupied seat grants no remote controls");
        Vehicle vehicle=Vehicles.getVehicle(rear);Seat seat=vehicle.seats[0];long oldOccupant=seat.occupant;
        seat.occupant=actor.getWurmId();
        try {
            RailwayTrainUi.View view=api.snapshot(actor,engine.getWurmId());
            check(view.train==train&&view.canControl&&view.powered,"actual native rear seat keeps access to the original engine panel at red");
            call(runtime,"openVehicle",new Class<?>[]{Creature.class,long.class,String.class},actor,engine.getWurmId(),"controls");
            check(train.powered&&train.speed==0,"opening remote engine panel from native rear seat leaves red waiting state untouched");
            double[][] held=nativePoses(train);List<Pose> poses=new ArrayList<Pose>(train.poses);
            api.submit(actor,engine.getWurmId(),view.train,RailwayTrainUi.fromAnswers(view,answer("stop")));
            check(train.powered,"rear-seat STOP is queued until the server poll");poll(runtime);
            check(!train.powered&&train.speed==0&&train.status.equals("Stopped"),"rear-seat STOP powers off the train at a red signal");
            net.tick(5);poll(runtime);
            check(!train.powered&&train.status.equals("Stopped")&&samePoses(poses,train.poses)&&sameNative(held,nativePoses(train))
                            &&seat.occupant==actor.getWurmId()&&actor.getVehicle()==rear.getWurmId(),
                    "mounted rear-seat STOP persists across later polls without dismounting or moving any car");
            net.start(train.name);
            RailwayTrainUi.View mounted=api.snapshot(actor,engine.getWurmId());
            api.submit(actor,engine.getWurmId(),mounted.train,RailwayTrainUi.fromAnswers(mounted,answer("stop")));
            seat.occupant=oldOccupant;poll(runtime);
            check(train.powered,"queued remote STOP rechecks actual native seat occupancy rather than trusting stale window membership");
        } finally { seat.occupant=oldOccupant;actor.vehicle=-10L; }
        net.stop(train.name);net.removeTrain(train.name);net.removeTrain(blocker.name);
        for(Item item:Arrays.asList(engine,cart,middle,rear,obstruction))Items.destroyItem(item.getWurmId());
    }
    private static RailwaySignalUi.Request place(RailwaySignalUi.View view,int index) { return RailwaySignalUi.fromAnswers(view,answer("place","approach_choice",Integer.toString(index))); }
    private static RailwaySignalUi.Request remove(RailwaySignalUi.View view,String id) {
        for(int i=0;i<view.signals.size();i++)if(view.signals.get(i).id.equals(id))return RailwaySignalUi.fromAnswers(view,answer("remove","signal_choice",Integer.toString(i),"confirm_remove","true"));
        throw new AssertionError("signal missing from captured UI");
    }
    private static void apply(WurmRailRuntime runtime,Creature actor,long clicked,RailwaySignalUi.Request request) throws Exception {
        call(runtime,"applySignalRequest",new Class<?>[]{Creature.class,long.class,RailwaySignalUi.Request.class},actor,clicked,request);
    }
    private static Item physical(long marker,int approach) { for(Item item:Items.getAllItems())if(WurmRailWorld.isSignal(item)&&item.getData()==marker&&(item.getAuxData()&7)==approach)return item;return null; }
    private static Item section(TrackMaterialsSmoke.InventoryActor actor,float quality) throws Exception {
        Item item=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,quality,"Signal fixture");check(actor.inventory.insertItem(item,true),"crafted signal material enters native inventory");return item;
    }
    private static Properties answer(String button,String...pairs) { Properties p=new Properties();p.setProperty(button,"true");for(int i=0;i<pairs.length;i+=2)p.setProperty(pairs[i],pairs[i+1]);return p; }
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
