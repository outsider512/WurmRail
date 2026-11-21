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

/** Actual Wurm vehicles and menus on the user's two-circle, single-connector layout. */
public final class TrafficRuntimeSmoke {
    private static int assertions;
    public static void run() throws Exception {
        runCase(false);runCase(true);
        speedCase(1);speedCase(2);
        loopDirectionCase(1,false);loopDirectionCase(2,false);
        loopDirectionCase(1,true);loopDirectionCase(2,true);
    }
    /** A long real turning loop wins over backing a few metres; saved backing state can recover safely. */
    private static void loopDirectionCase(int cars,boolean savedBacking) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(994000032L+cars*256L+(savedBacking?1024:0),true,2);
        RailNetwork net=new RailNetwork();List<String> ring=new ArrayList<String>();
        String prefix="nativeLoop"+cars+(savedBacking?"Saved":"Fresh");int index=0;
        for(int x=100;x<=120;x++)ring.add(loopPoint(net,actor,prefix,index++,x,170));
        for(int y=171;y<=176;y++)ring.add(loopPoint(net,actor,prefix,index++,120,y));
        for(int x=119;x>=100;x--)ring.add(loopPoint(net,actor,prefix,index++,x,176));
        for(int y=175;y>=171;y--)ring.add(loopPoint(net,actor,prefix,index++,100,y));
        for(int i=0;i<ring.size();i++)net.connect(ring.get(i),ring.get((i+1)%ring.size()));
        int firstStop=10-cars,secondStop=firstStop-cars-1;
        List<String> route=Arrays.asList(ring.get(firstStop),ring.get(secondStop));
        for(String stop:route) { net.setStation(stop,true,.3);Items.getItem(net.nodes.get(stop).markerId).setName("Station: "+stop); }
        net.signals().add(prefix+"SignalForward",ring.get(12),6,-10L);
        net.signals().add(prefix+"SignalBackward",ring.get(12),2,-10L);
        Node origin=net.nodes.get(ring.get(10));
        Item engine=WurmRailWorld.spawnLocomotive(actor,origin.worldX(),origin.worldY(),0),cart=null,cargo=null;
        List<Long> ids=new ArrayList<Long>();ids.add(engine.getWurmId());
        if(cars==2) {
            Node behind=net.nodes.get(ring.get(9));cart=WurmRailWorld.spawnCart(actor,behind.worldX(),behind.worldY(),0);ids.add(cart.getWurmId());
            cargo=ItemFactory.createItem(ItemList.plank,76f,"Native forward loop fixture");check(cart.insertItem(cargo),"loop recovery cart accepts native cargo");
        }
        Train train=net.createTrain(prefix,actor.getWurmId(),ids,origin.name,ring.get(11));net.setRoute(train.name,route,.3);
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-loop-direction/"+prefix+".properties");
        if(savedBacking) { net.reverse(train.name);net.save(state);net=RailNetwork.load(state);train=net.trains.get(prefix); }
        WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());
        set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);call(runtime,"refreshTrackVisuals",new Class<?>[0]);call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        position(actor,engine);RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        RailwayTrainUi.View before=api.snapshot(actor,engine.getWurmId());
        check(before.mode.contains(savedBacking?"Backing":"Forward"),"native controls expose the actual saved traction before starting a station service");
        float[] startX=new float[cars],startY=new float[cars],lastX=new float[cars],lastY=new float[cars],lastHeading=new float[cars];
        for(int i=0;i<cars;i++) {
            Item car=Items.getItem(train.carIds.get(i));startX[i]=lastX[i]=car.getPosX();startY[i]=lastY[i]=car.getPosY();lastHeading[i]=car.getRotation();
        }
        long[][] originalSeats=seats(train);
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        check(train.powered&&train.automated&&train.direction==1,"automatic service chooses engine-leading physical loop over a short backing shortcut, including restored reverse traction");
        check(api.snapshot(actor,engine.getWurmId()).mode.contains("Forward"),"native controls show Forward after safe backing-state recovery");
        for(int i=0;i<cars;i++) {
            Item car=Items.getItem(train.carIds.get(i));
            check(car.getPosX()==startX[i]&&car.getPosY()==startY[i]&&car.getRotation()==lastHeading[i],"starting or recovering a loop service cannot move or rotate any native vehicle in place");
        }
        int arrivals=0,previousStop=train.routeIndex;double travelled=0;Set<Integer> engineHeadings=new HashSet<Integer>();boolean reserved=false;
        for(int step=0;step<2500&&arrivals<2;step++) {
            net.tick(.1);call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            check(train.direction==1&&train.powered,"automatic multi-stop service continues engine-first around its physical loop");
            check(nativeMatches(train),"native locomotive and attached wagon follow the physical loop's positions and headings");
            for(int i=0;i<cars;i++) {
                Item car=Items.getItem(train.carIds.get(i));double moved=Math.hypot(car.getPosX()-lastX[i],car.getPosY()-lastY[i]);
                double turn=angleDifference(car.getRotation(),lastHeading[i]);
                check(turn<90&&(moved>.00001||turn<.001),"native model turns progressively while moving through bends and never spins 180 degrees on a stationary tile");
                if(i==0) { travelled+=moved;engineHeadings.add(Math.round(car.getRotation()/90f)%4); }
                lastX[i]=car.getPosX();lastY[i]=car.getPosY();lastHeading[i]=car.getRotation();
            }
            if(train.routeIndex!=previousStop) { arrivals++;previousStop=train.routeIndex; }
            if(!net.signals().reservations().isEmpty())reserved=true;
        }
        check(arrivals==2&&travelled>300,"both nearby-behind station requests are reached by travelling two real long circuits; arrivals="+arrivals+", metres="+travelled+", status="+train.status);
        check(engineHeadings.containsAll(Arrays.asList(0,1,2,3)),"native locomotive visibly faces all four directions as the track physically turns it around");
        check(reserved&&train.route.equals(route),"directional signal authority supports the same repeating engine-leading station itinerary");
        check(Arrays.deepEquals(originalSeats,seats(train))&&(cargo==null||cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==76f),"physical loop circulation and saved-state recovery preserve seats and the exact cargo item");
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.STOP));net.removeTrain(train.name);
        Items.destroyItem(engine.getWurmId());if(cart!=null)Items.destroyItem(cart.getWurmId());
        Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
        for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
        for(Node node:net.nodes.values())Items.destroyItem(node.markerId);
        System.out.println("PASS native "+cars+"-vehicle engine-leading physical loop, saved backing="+savedBacking+", two arrivals, progressive model turns, signals and no start jump ("+assertions+" assertions)");
    }
    private static String loopPoint(RailNetwork net,Creature actor,String prefix,int index,int x,int y) throws Exception {
        String key=prefix+"_"+index;track(net,actor,key,x,y);return key;
    }
    private static double angleDifference(float first,float second) { return Math.abs((first-second+540)%360-180); }
    /** Equal signed travel budgets must survive native controls, path extension and vehicle updates. */
    private static void speedCase(int cars) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(993000032L+cars*256L,true,2);
        RailNetwork net=new RailNetwork();int y=146+cars;
        for(int x=100;x<=140;x++) {
            String key="speed"+cars+"_"+x;track(net,actor,key,x,y);
            if(x>100)net.connect("speed"+cars+"_"+(x-1),key);
        }
        Node origin=net.nodes.get("speed"+cars+"_120");
        Item engine=WurmRailWorld.spawnLocomotive(actor,origin.worldX(),origin.worldY(),0);engine.setRotation(90f);
        position(actor,engine);
        WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());
        set(runtime,"network",net);set(runtime,"ready",true);
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("speed-native-"+cars+"/network.properties");
        set(runtime,"stateFile",state);call(runtime,"initService",new Class<?>[0]);
        RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        RailwayTrainUi.View setup=api.snapshot(actor,engine.getWurmId());Train train=setup.train;
        check(train!=null&&setup.speedLimit==5&&train.maxSpeed==5,"fresh runtime and automatically registered native train use the 5 m/s default cap");
        check(train.acceleration==.8,"native setup retains the same default acceleration for both travel directions");
        Item cart=null,cargo=null;
        if(cars==2) {
            Node behind=net.nodes.get("speed"+cars+"_119");cart=WurmRailWorld.spawnCart(actor,behind.worldX(),behind.worldY(),0);
            control(runtime,api,actor,engine,train,RailwayTrainUi.Request.choose(RailwayTrainUi.Action.COUPLE,Long.toString(cart.getWurmId())));
            check(train.carIds.size()==2,"queued native controls couple the speed-test wagon");
            cargo=ItemFactory.createItem(ItemList.plank,73f,"Bidirectional speed fixture");check(cart.insertItem(cargo),"speed-test native wagon accepts cargo");
        }
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.speed(1.5));
        check(train.maxSpeed==1.5,"queued controls accept a lower selected speed before choosing Full");
        RailwayTrainUi.Request full=RailwayTrainUi.fromAnswers(api.snapshot(actor,engine.getWurmId()),answer("speedFull"));
        check(full.speed==5,"Full speed captures the fresh server's 5 m/s limit");
        control(runtime,api,actor,engine,train,full);check(train.maxSpeed==5,"queued Full speed applies the 5 m/s cap");
        long[][] originalSeats=seats(train);float[] headings=new float[cars],startX=new float[cars];
        for(int i=0;i<cars;i++) { Item car=Items.getItem(train.carIds.get(i));headings[i]=car.getRotation();startX[i]=car.getPosX(); }
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
        check(train.powered&&train.direction==1,"Power on starts native forward travel");
        double[][] forward=speedTrace(runtime,net,train,1,headings);
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.STOP));
        check(!train.powered&&train.speed==0,"STOP clears motion before changing direction");
        float[] reversalX=new float[cars],reversalY=new float[cars];
        for(int i=0;i<cars;i++) { Item car=Items.getItem(train.carIds.get(i));reversalX[i]=car.getPosX();reversalY[i]=car.getPosY(); }
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.REVERSE));
        call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        check(!train.powered&&train.direction==-1,"Reverse remains stopped and selects backing travel");
        for(int i=0;i<cars;i++) {
            Item car=Items.getItem(train.carIds.get(i));
            check(car.getPosX()==reversalX[i]&&car.getPosY()==reversalY[i]&&car.getRotation()==headings[i],
                    "applying reversed core poses cannot translate or rotate a native vehicle in place");
        }
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.START));
        double[][] backward=speedTrace(runtime,net,train,-1,headings);
        for(int i=0;i<cars;i++)for(int step=0;step<forward[i].length;step++)
            check(Math.abs(forward[i][step]-backward[i][step])<.002,"native forward and backward displacement match at the same acceleration tick");
        for(int i=0;i<cars;i++)check(Math.abs(Items.getItem(train.carIds.get(i)).getPosX()-startX[i])<.002,
                "equal forward and backward runs return every native vehicle to its starting position");
        check(Arrays.deepEquals(originalSeats,seats(train))&&(cargo==null||cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==73f),
                "full-speed forward and reverse preserve native seats and the exact cargo item");
        control(runtime,api,actor,engine,train,RailwayTrainUi.Request.of(RailwayTrainUi.Action.STOP));
        Train restored=RailNetwork.load(state).trains.get(train.name);
        check(restored.maxSpeed==5&&restored.acceleration==.8&&restored.direction==-1,"native stop persists selected speed, acceleration and backing direction");
        net.removeTrain(train.name);Items.destroyItem(engine.getWurmId());if(cart!=null)Items.destroyItem(cart.getWurmId());
        for(Node node:net.nodes.values())Items.destroyItem(node.markerId);
        System.out.println("PASS native "+cars+"-vehicle equal forward/reverse 5 m/s travel, acceleration, Full controls, stable bodies, seats and cargo ("+assertions+" assertions)");
    }
    private static double[][] speedTrace(WurmRailRuntime runtime,RailNetwork net,Train train,int sign,float[] headings) throws Exception {
        int steps=100;double[][] distance=new double[train.carIds.size()][steps];float[] initial=new float[train.carIds.size()];
        for(int i=0;i<initial.length;i++)initial[i]=Items.getItem(train.carIds.get(i)).getPosX();
        double expectedDistance=0;
        for(int step=0;step<steps;step++) {
            net.tick(.1);call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            double expectedSpeed=Math.min(5,(step+1)*.8*.1);expectedDistance+=expectedSpeed*.1;
            check(train.powered&&Math.abs(train.speed-expectedSpeed)<1e-9,"native-backed train accelerates equally to 5 m/s without direction-dependent speed changes");
            check(nativeMatches(train),"every full-speed native vehicle follows its authoritative core pose");
            for(int i=0;i<initial.length;i++) {
                Item car=Items.getItem(train.carIds.get(i));distance[i][step]=sign*(car.getPosX()-initial[i]);
                check(Math.abs(distance[i][step]-expectedDistance)<.002,"native vehicle consumes the complete speed budget across straight track boundaries");
                check(Math.abs(car.getRotation()-headings[i])<.01,"native vehicle keeps its body orientation while travelling forward or backing");
            }
        }
        check(train.speed==5,"native-backed run reaches and holds the 5 m/s maximum");return distance;
    }
    private static void control(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Item engine,Train train,RailwayTrainUi.Request request) throws Exception {
        position(actor,engine);api.submit(actor,engine.getWurmId(),train,request);
        // This poll applies the queued control only. speedTrace advances the simulation in fixed steps.
        set(runtime,"lastTick",Long.MAX_VALUE);set(runtime,"lastDiscovery",System.nanoTime());set(runtime,"lastSave",System.currentTimeMillis());runtime.onServerPoll();
    }
    private static Properties answer(String button) { Properties result=new Properties();result.setProperty(button,"true");return result; }
    private static void runCase(boolean nearJunction) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(991000032L,true,2);
        RailNetwork net=new RailNetwork();
        track(net,actor,"trafficL",80,120);track(net,actor,"trafficR",90,120);
        loop(net,actor,"trafficL","trafficLeft",80,-1);loop(net,actor,"trafficR","trafficRight",90,1);
        String previous="trafficL";
        for(int x=81;x<90;x++) { String id="trafficLink"+(x-80);track(net,actor,id,x,120);net.connect(previous,id);previous=id; }
        net.connect(previous,"trafficR");
        for(String key:Arrays.asList("trafficL","trafficR")) {
            net.setJunctionAlignment(key,17);Node node=net.nodes.get(key);
            for(String link:node.links) {
                Node next=net.nodes.get(link);int side=RailNetwork.direction(next.tileX-node.tileX,next.tileY-node.tileY);
                net.signals().add(key+"_"+side,key,side,-10);
            }
        }
        net.setStation("trafficLeft11",true,.5);net.setStation("trafficRight11",true,.5);
        Node left=net.nodes.get(nearJunction?"trafficLeft0":"trafficLeft11"),right=net.nodes.get(nearJunction?"trafficRight0":"trafficRight11");
        Item engineA=WurmRailWorld.spawnLocomotive(actor,left.worldX(),left.worldY(),0);
        Node behind=net.nodes.get(nearJunction?"trafficLeft1":"trafficLeft12");
        Item cart=WurmRailWorld.spawnCart(actor,behind.worldX(),behind.worldY(),0);
        Item engineB=WurmRailWorld.spawnLocomotive(actor,right.worldX(),right.worldY(),0);
        engineA.setName("Express");engineB.setName("Local");
        Item cargo=ItemFactory.createItem(ItemList.plank,71f,"Traffic fixture");check(cart.insertItem(cargo),"native cart accepts cargo");
        Train a=net.createTrain("EXPRESS",actor.getWurmId(),Arrays.asList(engineA.getWurmId(),cart.getWurmId()),left.name,nearJunction?"trafficL":"trafficLeft10");
        Train b=net.createTrain("LOCAL",actor.getWurmId(),Collections.singletonList(engineB.getWurmId()),right.name,nearJunction?"trafficR":"trafficRight10");
        WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"ready",true);
        Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("traffic-runtime/network.properties");set(runtime,"stateFile",state);
        call(runtime,"initService",new Class<?>[0]);call(runtime,"refreshTrackVisuals",new Class<?>[0]);
        call(runtime,"applyPoses",new Class<?>[]{Train.class},a);call(runtime,"applyPoses",new Class<?>[]{Train.class},b);
        long[][] seats=seats(a,b);RailwayTrainUi.Api api=(RailwayTrainUi.Api)get(runtime,"trainApi");
        start(runtime,api,actor,engineA,a,Arrays.asList("trafficRight11","trafficLeft11"));
        float before=engineB.getRotation();
        start(runtime,api,actor,engineB,b,Arrays.asList("trafficLeft11","trafficRight11"));
        check(engineB.getRotation()==before,"starting the lone locomotive cannot turn its native body in place");
        int countA=0,countB=0,oldA=a.routeIndex,oldB=b.routeIndex;boolean waiting=false,crossing=false;
        for(int step=0;step<18000&&(countA<8||countB<8);step++) {
            net.tick(.1);
            call(runtime,"applyPoses",new Class<?>[]{Train.class},a);call(runtime,"applyPoses",new Class<?>[]{Train.class},b);
            if(a.routeIndex!=oldA) { countA++;oldA=a.routeIndex; }
            if(b.routeIndex!=oldB) { countB++;oldB=b.routeIndex; }
            for(Train train:Arrays.asList(a,b)) {
                String traffic=net.signals().trafficStatus(train.name);
                if(!traffic.isEmpty()&&(!waiting&&traffic.contains("Waiting")||!crossing&&traffic.contains("Crossing"))) {
                    call(runtime,"applyPoses",new Class<?>[]{Train.class},train);Item engine=Items.getItem(train.carIds.get(0));position(actor,engine);
                    RailwayTrainUi.View view=api.snapshot(actor,engine.getWurmId());
                    check(view.trafficStatus.equals(traffic)&&!view.nextStop.isEmpty(),"native controls show actual dispatch status and next stop");
                    check(RailwayTrainUi.content(view).contains(RailwayBml.text(traffic)),"native traffic explanation survives BML escaping");
                    if(traffic.contains("Waiting"))waiting=true;if(traffic.contains("Crossing"))crossing=true;
                }
            }
            if(net.signals().traffic().leases().size()>1)throw new AssertionError("Opposing corridor tokens granted together");
        }
        call(runtime,"applyPoses",new Class<?>[]{Train.class},a);call(runtime,"applyPoses",new Class<?>[]{Train.class},b);
        check(countA>=8&&countB>=8,"native opposite services each complete eight station arrivals; "+countA+"/"+countB+"; "+a.status+" / "+b.status);
        check(waiting&&crossing,"native controls exercise both waiting and crossing states");
        check(a.powered&&b.powered&&a.automated&&b.automated,"both services stay powered and automatic");
        check(nativeMatches(a)&&nativeMatches(b),"all native positions and body headings follow their core poses");
        check(Arrays.deepEquals(seats,seats(a,b))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==71f,"coordinated travel preserves every seat and the exact cargo item");
        check(net.signals().definitions().size()==6,"dispatch adds no player signal definitions");
        int posts=0;for(Item item:Items.getAllItems())if(WurmRailWorld.isSignal(item)
                &&(item.getData()==net.nodes.get("trafficL").markerId||item.getData()==net.nodes.get("trafficR").markerId))posts++;
        check(posts==6,"automatic holding boundaries create no extra native signal posts");
        net.stop(a.name);net.stop(b.name);net.removeTrain(a.name);net.removeTrain(b.name);
        for(Item item:Arrays.asList(engineA,cart,engineB))Items.destroyItem(item.getWurmId());
        Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
        for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
        for(Node node:net.nodes.values())Items.destroyItem(node.markerId);
        System.out.println("PASS native opposing two-circle services (close approaches="+nearJunction+"): "+countA+"/"+countB+" arrivals, lone-engine facing, dispatch menus, seats and cargo ("+assertions+" assertions)");
    }
    private static void start(WurmRailRuntime runtime,RailwayTrainUi.Api api,Creature actor,Item engine,Train train,List<String> route) throws Exception {
        position(actor,engine);api.submit(actor,engine.getWurmId(),train,RailwayTrainUi.Request.route(RailwayTrainUi.Action.ROUTE_START,route));
        set(runtime,"lastTick",System.nanoTime());set(runtime,"lastDiscovery",System.nanoTime());set(runtime,"lastSave",System.currentTimeMillis());runtime.onServerPoll();
        check(train.powered&&train.route.equals(route),"queued Save and start activates the native service");
    }
    private static void loop(RailNetwork net,Creature actor,String junction,String prefix,int x,int across) throws Exception {
        String previous=junction;int i=0;
        for(int y=119;y>=116;y--)previous=point(net,actor,previous,prefix+(i++),x,y);
        for(int step=1;step<=4;step++)previous=point(net,actor,previous,prefix+(i++),x+across*step,116);
        for(int y=117;y<=124;y++)previous=point(net,actor,previous,prefix+(i++),x+across*4,y);
        for(int step=3;step>=0;step--)previous=point(net,actor,previous,prefix+(i++),x+across*step,124);
        for(int y=123;y>=121;y--)previous=point(net,actor,previous,prefix+(i++),x,y);
        net.connect(previous,junction);
    }
    private static String point(RailNetwork net,Creature actor,String previous,String key,int x,int y) throws Exception { track(net,actor,key,x,y);net.connect(previous,key);return key; }
    private static void track(RailNetwork net,Creature actor,String key,int x,int y) throws Exception { Node node=net.addNode(key,x,y,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId(); }
    private static void position(Creature actor,Item item) { actor.getStatus().getPosition().setPosX(item.getPosX());actor.getStatus().getPosition().setPosY(item.getPosY()); }
    private static long[][] seats(Train...trains) throws Exception {
        List<long[]> rows=new ArrayList<long[]>();for(Train train:trains)for(Long id:train.carIds) { Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<row.length;i++)row[i]=seats[i].occupant;rows.add(row); }return rows.toArray(new long[rows.size()][]);
    }
    private static boolean nativeMatches(Train train) throws Exception { for(Pose pose:train.poses) { Item item=Items.getItem(pose.carId);if(Math.abs(item.getPosX()-pose.x)>.01||Math.abs(item.getPosY()-pose.y)>.01||Math.abs(item.getRotation()-pose.rotation)>.01)return false; }return true; }
    private static Object get(Object object,String key) throws Exception { Field field=object.getClass().getDeclaredField(key);field.setAccessible(true);return field.get(object); }
    private static void set(Object object,String key,Object value) throws Exception { Field field=object.getClass().getDeclaredField(key);field.setAccessible(true);field.set(object,value); }
    private static Object call(Object object,String key,Class<?>[] types,Object...args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(key,types);method.setAccessible(true);
        try { return method.invoke(object,args); }catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
