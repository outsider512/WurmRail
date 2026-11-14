import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.*;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;
import org.wurmrail.core.TrafficControl;

/** Real Wurm movement validation and native commits for a mixed-length directional convoy. */
public final class ConvoyRuntimeSmoke {
    private static int assertions;
    private ConvoyRuntimeSmoke() { }

    public static void run() throws Exception {
        runCase(.1);
        runCase(.25);
        persistentQueuesCase(.25);
    }

    /** An already queued opponent permits useful following, but caps the phase and gets its turn. */
    private static void persistentQueuesCase(double dt) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997100032L,true,2);
        RailNetwork net=new RailNetwork();WurmRailRuntime runtime=new WurmRailRuntime();
        List<Item> vehicles=new ArrayList<Item>();List<Train> all=new ArrayList<Train>();
        try {
            dumbbell(net,actor);
            Train a=train(net,actor,vehicles,"batchLeft0","followl0","followL",2,"followl1");
            Train b=train(net,actor,vehicles,"batchLeft1","followl7","followl6",1);
            Train c=train(net,actor,vehicles,"batchLeft2","followl14","followl13",2,"followl15");
            Train fourth=train(net,actor,vehicles,"batchLeft3","followl21","followl20",1);
            Train x=train(net,actor,vehicles,"batchRight0","followr0","followR",1);
            Train y=train(net,actor,vehicles,"batchRight1","followr8","followr7",1);
            Train z=train(net,actor,vehicles,"batchRight2","followr16","followr15",1);
            List<Train> left=Arrays.asList(a,b,c,fourth),right=Arrays.asList(x,y,z);
            all.addAll(left);all.addAll(right);
            Item cargo=ItemFactory.createItem(ItemList.plank,82f,"Native bounded directional batch fixture");
            Item cart=Items.getItem(c.carIds.get(1));check(cart.insertItem(cargo),"persistent-queue convoy accepts native wagon cargo");
            long[][] initialSeats=seats(all);
            runtime.configure(new Properties());set(runtime,"network",net);set(runtime,"ready",true);
            set(runtime,"stateFile",Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-bounded-batches/network.properties"));
            call(runtime,"initService",new Class<?>[0]);call(runtime,"refreshTrackVisuals",new Class<?>[0]);
            for(Train train:all)call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            net.movementGuard=poses->{
                try{return (Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},poses);}
                catch(Exception failure){throw new IllegalStateException("Native bounded-batch motion validation failed",failure);}
            };
            for(Train train:left){net.setRoute(train.name,Arrays.asList("followr11","followl11"),.5);net.start(train.name);}
            for(Train train:right){net.setRoute(train.name,Arrays.asList("followl11","followr11"),.5);net.start(train.name);}
            TrafficControl traffic=net.signals().traffic();
            // Route requests may queue from look-ahead, but only real approach signals grant blocks.
            traffic.prepare(a,Arrays.asList("followL","followc1"));
            traffic.prepare(x,Arrays.asList("followR","followc39"));
            for(Train train:Arrays.asList(b,c,fourth))traffic.prepare(train,Arrays.asList("followL","followc1"));
            for(Train train:Arrays.asList(y,z))traffic.prepare(train,Arrays.asList("followR","followc39"));
            check(traffic.leases().isEmpty()&&net.signals().reservations().isEmpty()
                            &&net.signals().trafficStatus(x.name).contains("Waiting"),
                    "native opposing queue is active before the first outbound lease or signal grant");

            Set<String> arrived=new HashSet<String>(),previousLeases=new HashSet<String>(),firstLeft=new LinkedHashSet<String>();
            List<String> phases=new ArrayList<String>();List<Integer> phaseCounts=new ArrayList<Integer>();
            String phase=null;int count=0;boolean leftTogether=false,rightTogether=false,opponentStarted=false,fourthHeld=false;
            for(int step=0;step<Math.ceil(1600/dt)&&(arrived.size()<all.size()||!leftTogether||!rightTogether);step++) {
                tick(runtime,net,all,dt,arrived);
                Set<String> currentLeases=new HashSet<String>();int insideLeft=0,insideRight=0;
                for(TrafficControl.Lease lease:traffic.leases()) {
                    String key=lease.entryJunction+":"+lease.trainName;currentLeases.add(key);
                    if(!previousLeases.contains(key)) {
                        if(!lease.entryJunction.equals(phase)) {
                            if(phase!=null)phaseCounts.add(count);
                            phase=lease.entryJunction;phases.add(phase);count=0;
                        }
                        count++;
                        check(count<=3,"persistent opposite queues cap each native directional phase at three committed trains: "+phases+" "+phaseCounts+" current="+count);
                        if(!opponentStarted&&lease.entryJunction.equals("followL"))firstLeft.add(lease.trainName);
                    }
                    if(lease.entryJunction.equals("followR")) {
                        opponentStarted=true;
                        Train member=net.trains.get(lease.trainName);if(member!=null&&onConnector(member))insideRight++;
                    }
                }
                for(Train train:left)if(onConnector(train))insideLeft++;
                leftTogether|=insideLeft>=3&&!opponentStarted;
                rightTogether|=insideRight>=3;
                if(!opponentStarted) {
                    check(!hasLease(net,fourth.name),"fourth outbound train cannot extend a three-train batch ahead of an already queued opponent");
                    fourthHeld|=fourth.powered&&fourth.speed==0&&fourth.status.startsWith("Signal ")&&fourth.status.contains(" RED:");
                }
                for(Train train:all)check(train.powered,"bounded directional handovers keep each automatic native service powered");
                previousLeases=currentLeases;
            }
            phaseCounts.add(count);
            check(firstLeft.equals(new LinkedHashSet<String>(Arrays.asList(a.name,b.name,c.name)))&&leftTogether&&fourthHeld,
                    "three native outbound trains follow together despite an older opposite request while the fourth waits its turn; phases="+phases+" counts="+phaseCounts+"; "+status(net,all));
            check(opponentStarted&&rightTogether&&phases.size()>=3&&phases.get(0).equals("followL")&&phases.get(1).equals("followR"),
                    "persistent right-hand queue receives the next directional turn and forms its own real three-train convoy before outbound backlog continues; phases="+phases+" counts="+phaseCounts+"; "+status(net,all));
            check(arrived.containsAll(Arrays.asList(a.name,b.name,c.name,fourth.name,x.name,y.name,z.name)),
                    "all seven native scheduled trains reach the opposite station without starvation; "+status(net,all));
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==82f,
                    "persistent bounded batches retain native seats and the exact cargo item");
            System.out.println("PASS native persistent opposing queues before first grant, directional batches <=3, actual three-train convoys both ways, exclusive signal blocks, 4m headway and all seven station arrivals; phases="+phases+" counts="+phaseCounts+" ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;
            for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
        }
    }

    private static void runCase(double dt) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(997000032L+(dt>.1?256:0),true,2);
        RailNetwork net=new RailNetwork();
        WurmRailRuntime runtime=new WurmRailRuntime();
        List<Item> vehicles=new ArrayList<Item>();
        List<Train> all=new ArrayList<Train>();
        String tag=dt>.1?"quarter":"tenth";
        try {
            dumbbell(net,actor);
            Train a=train(net,actor,vehicles,"convoyA","followl0","followL",2,"followl1");
            Train b=train(net,actor,vehicles,"convoyB","followl8","followl7",1);
            Train c=train(net,actor,vehicles,"convoyC","followl16","followl15",3,"followl17","followl18");
            Train d=train(net,actor,vehicles,"convoyOpposite","followr11","followr10",1);
            all.addAll(Arrays.asList(a,b,c,d));
            List<Train> outbound=Arrays.asList(a,b,c);
            Item cargo=ItemFactory.createItem(ItemList.plank,79f,"Native convoy fixture");
            Item cargoCart=Items.getItem(c.carIds.get(2));
            check(cargoCart.insertItem(cargo),"last wagon accepts the convoy fixture cargo");
            long[][] seatSnapshot=seats(all);

            runtime.configure(new Properties());
            set(runtime,"network",net);
            set(runtime,"ready",true);
            set(runtime,"stateFile",Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath())
                    .resolve("native-convoy-"+tag+"/network.properties"));
            call(runtime,"initService",new Class<?>[0]);
            call(runtime,"refreshTrackVisuals",new Class<?>[0]);
            for(Train t:all)call(runtime,"applyPoses",new Class<?>[]{Train.class},t);
            net.movementGuard=poses->{
                try { return (Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},poses); }
                catch(Exception failure) { throw new IllegalStateException("Native convoy motion validation failed",failure); }
            };

            // An unmanaged vehicle has no authoritative projected pose to trust.
            Pose origin=a.poses.get(0);
            Item parked=WurmRailWorld.spawnCart(actor,origin.x+.25,origin.y,origin.layer);
            vehicles.add(parked);
            check(!(Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},a.poses)
                            &&String.valueOf(get(runtime,"validationError")).contains("parked rail vehicle"),
                    "native projection exemption never hides an unmanaged parked vehicle collision");
            Items.destroyItem(parked.getWurmId());
            check((Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},a.poses),
                    "native movement becomes valid again after the unmanaged obstruction is removed");

            for(Train t:outbound) { net.setRoute(t.name,Arrays.asList("followr11","followl11"),.5);net.start(t.name); }
            net.setRoute(d.name,Arrays.asList("followl11","followr11"),.5);
            Set<String> arrived=new HashSet<String>();
            boolean together=false;
            TrafficControl.Lease lastLease=null;
            for(int step=0;step<Math.ceil(400/dt)&&!together;step++) {
                tick(runtime,net,all,dt,arrived);
                int members=0,inside=0;
                for(TrafficControl.Lease lease:net.signals().traffic().leases())
                    if(lease.entryJunction.equals("followL")&&outbound.contains(net.trains.get(lease.trainName))) {
                        members++;
                        if(lease.trainName.equals(c.name))lastLease=lease;
                    }
                for(Train t:outbound)if(onConnector(t))inside++;
                together=members==3&&inside==3;
            }
            check(together&&lastLease!=null,"three native 2/1/3-vehicle trains actually share the connector with distinct directional leases; "+status(net,all));
            Set<String> authorized=new HashSet<String>();
            for(SignalSystem.Reservation r:net.signals().reservations())authorized.add(r.trainName);
            check(authorized.containsAll(Arrays.asList(a.name,b.name,c.name)),
                    "each simultaneous native follower has its own protected block authority");

            // Starting the opposing service only after all three enter tests full batch drain.
            net.start(d.name);
            net.stop(a.name);
            List<Pose> leaderStop=a.poses;
            List<String> leaderRoute=new ArrayList<String>(a.route);
            int leaderDirection=a.direction;
            String rejection=null;
            try { call(runtime,"reverseForOperator",new Class<?>[]{Creature.class,Train.class},actor,a); }
            catch(IllegalArgumentException expected) { rejection=expected.getMessage(); }
            check(rejection!=null&&(rejection.contains("convoy")||rejection.contains("following")),
                    "native operator reverse is refused while other trains occupy the same convoy");
            check(a.direction==leaderDirection&&!a.powered&&a.automated&&a.route.equals(leaderRoute)
                            &&samePoses(leaderStop,a.poses)&&nativeMatches(all),
                    "refused native reverse preserves every pose, physical heading, automatic route and stopped state");

            boolean queued=false,waiting=false;
            float bx=b.poses.get(0).x,by=b.poses.get(0).y,cx=c.poses.get(0).x,cy=c.poses.get(0).y;
            int steady=0;
            for(int step=0;step<Math.ceil(150/dt)&&!waiting;step++) {
                tick(runtime,net,all,dt,arrived);
                queued|=net.signals().trafficStatus(d.name).contains("Waiting");
                check(!a.powered&&samePoses(leaderStop,a.poses),"STOP holds the native convoy leader at its exact position");
                check(b.powered&&c.powered&&d.powered,"followers and queued opposing service remain powered while waiting");
                check(!hasLease(net,d.name),"opposing service cannot enter while any member of the stopped batch is on the connector");
                boolean moved=Math.hypot(b.poses.get(0).x-bx,b.poses.get(0).y-by)>1
                        &&Math.hypot(c.poses.get(0).x-cx,c.poses.get(0).y-cy)>1;
                if(moved&&b.speed<.000001&&c.speed<.000001)steady++;else steady=0;
                waiting=steady>=Math.ceil(1/dt);
            }
            check(queued&&waiting,"both native followers catch the stopped leader and safely wait with power on; "+status(net,all));
            check(Arrays.deepEquals(seatSnapshot,seats(all))&&cargo.getParentId()==cargoCart.getWurmId(),
                    "stopping and refusing reversal retain all native seats and wagon cargo");

            net.start(a.name);
            check(a.powered&&a.direction==leaderDirection&&samePoses(leaderStop,a.poses),
                    "restarting the convoy leader does not move or rotate it before a validated tick");
            boolean opposingAdmitted=false;
            for(int step=0;step<Math.ceil(700/dt)&&(!opposingAdmitted||arrived.size()<4);step++) {
                tick(runtime,net,all,dt,arrived);
                if(!opposingAdmitted&&hasLease(net,d.name)) {
                    opposingAdmitted=true;
                    for(Train t:outbound) {
                        for(String rail:lastLease.nodes)
                            check(!(Boolean)call(net,"signalOccupiesNode",new Class<?>[]{Train.class,String.class},t,rail),
                                    "opposing admission waits for every outbound cart to clear the complete connector");
                        check(!(Boolean)call(net,"signalOccupiesNode",new Class<?>[]{Train.class,String.class},t,lastLease.exitNeighbor),
                                "opposing admission waits until the receiving exit is clear of the last outbound cart");
                    }
                }
            }
            check(opposingAdmitted&&arrived.containsAll(Arrays.asList(a.name,b.name,c.name,d.name)),
                    "three native followers reach the receiving station and the opposing service gets its fair turn and reaches its station; "+status(net,all));
            check(nativeMatches(all)&&Arrays.deepEquals(seatSnapshot,seats(all))
                            &&cargo.getParentId()==cargoCart.getWurmId()&&cargo.getQualityLevel()==79f,
                    "mixed convoy movement preserves native positions, headings, every seat and the exact cargo item");
            System.out.println("PASS native three-train 2/1/3-car convoy, dt="+dt+", real movement guard, stopped leader, safe following/reverse refusal, opposing fair turn, station arrivals, seats and cargo ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;
            for(Train t:new ArrayList<Train>(net.trains.values())) { net.stop(t.name);net.removeTrain(t.name); }
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();
            for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())
                    &&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))
                Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }

    private static void tick(WurmRailRuntime runtime,RailNetwork net,List<Train> all,double dt,Set<String> arrived) throws Exception {
        Map<Long,Pose> old=new HashMap<Long,Pose>();
        for(Train t:all)for(Pose pose:t.poses)old.put(pose.carId,pose);
        net.tick(dt);
        Map<?,?> motionErrors=new LinkedHashMap<Object,Object>((Map<?,?>)get(runtime,"motionErrors"));
        String validationError=String.valueOf(get(runtime,"validationError"));
        for(Train t:all) {
            boolean healthy=!t.status.equals("Movement blocked by the world")&&!t.status.startsWith("Blocked:")
                    &&!t.status.startsWith("Restore blocked:")&&!t.status.contains("world error");
            check(healthy,healthy?"native convoy motion remains valid":
                    "native convoy validation never halts a healthy managed service: "+t.name+" "+t.status
                            +"; dt="+dt+", validationError="+validationError+", immediateMotionErrors="+motionErrors
                            +"; "+status(net,all)+"; "+nativeDiagnostics(all));
        }
        // Commit every managed vehicle after the entire core tick, matching the server poll.
        for(Train t:all)call(runtime,"applyPoses",new Class<?>[]{Train.class},t);
        check(nativeMatches(all),"native convoy items match all committed core positions and headings");
        for(Train t:all) {
            if(t.routeIndex==1)arrived.add(t.name);
            for(Pose pose:t.poses) {
                Pose before=old.get(pose.carId);
                double movement=Math.hypot(pose.x-before.x,pose.y-before.y);
                double turn=Math.abs((pose.rotation-before.rotation+540)%360-180);
                check(turn<90&&(movement>.00001||turn<.01),
                        "each native locomotive and cart turns progressively on physical bends and never spins while stationary");
            }
        }
        for(int i=0;i<all.size();i++)for(int j=i+1;j<all.size();j++)for(Pose first:all.get(i).poses)for(Pose second:all.get(j).poses)
            check(first.layer!=second.layer||Math.hypot(first.x-second.x,first.y-second.y)>=RailNetwork.TRAIN_CLEARANCE-.0001,
                    "every pair of cars belonging to different trains keeps the full managed-train clearance");
        Map<String,String> entries=new HashMap<String,String>();
        for(TrafficControl.Lease lease:net.signals().traffic().leases()) {
            String existing=entries.put(lease.corridorId,lease.entryJunction);
            check(existing==null||existing.equals(lease.entryJunction),"active native connector members all enter from the same end");
        }
        List<SignalSystem.Reservation> reservations=new ArrayList<SignalSystem.Reservation>(net.signals().reservations());
        for(int i=0;i<reservations.size();i++)for(int j=i+1;j<reservations.size();j++)
            if(!reservations.get(i).trainName.equals(reservations.get(j).trainName)) {
                Set<String> overlap=new HashSet<String>(resources(reservations.get(i)));
                overlap.retainAll(resources(reservations.get(j)));
                check(overlap.isEmpty(),"simultaneous native followers never share actual signal-block resources: "+overlap);
            }
    }
    @SuppressWarnings("unchecked")
    private static Set<String> resources(SignalSystem.Reservation reservation) throws Exception { return (Set<String>)get(reservation,"resources"); }
    private static boolean hasLease(RailNetwork net,String train) { for(TrafficControl.Lease lease:net.signals().traffic().leases())if(lease.trainName.equals(train))return true;return false; }
    private static boolean onConnector(Train train) { for(Pose pose:train.poses)if(pose.railName!=null&&pose.railName.startsWith("followc"))return true;return false; }
    private static boolean samePoses(List<Pose> first,List<Pose> second) {
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++) { Pose a=first.get(i),b=second.get(i);if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false; }
        return true;
    }
    private static boolean nativeMatches(List<Train> all) throws Exception {
        for(Train train:all)for(Pose pose:train.poses) { Item item=Items.getItem(pose.carId);if(Math.abs(item.getPosX()-pose.x)>.01||Math.abs(item.getPosY()-pose.y)>.01||Math.abs(item.getRotation()-pose.rotation)>.01)return false; }
        return true;
    }
    private static long[][] seats(List<Train> all) throws Exception {
        List<long[]> result=new ArrayList<long[]>();
        for(Train train:all)for(long id:train.carIds) {
            Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];
            for(int index=0;index<row.length;index++)row[index]=seats[index].occupant;
            result.add(row);
        }
        return result.toArray(new long[result.size()][]);
    }
    private static String status(RailNetwork net,List<Train> all) {
        List<String> result=new ArrayList<String>();
        for(Train t:all)result.add(t.name+"="+t.status+", powered="+t.powered+", next="+t.routeIndex+", positions="+t.poses+", traffic="+net.signals().trafficStatus(t.name));
        return result.toString();
    }
    private static String nativeDiagnostics(List<Train> all) {
        Set<Long> managed=new HashSet<Long>();
        for(Train t:all)managed.addAll(t.carIds);
        Map<Long,String> nearby=new LinkedHashMap<Long,String>();
        for(Train t:all)for(Pose pose:t.poses)for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++) {
            VolaTile tile=Zones.getTileOrNull((int)(pose.x/4)+dx,(int)(pose.y/4)+dy,pose.layer==0);
            if(tile!=null)for(Item item:tile.getItems())if(WurmRailWorld.isRailCar(item))
                nearby.put(item.getWurmId(),(managed.contains(item.getWurmId())?"managed ":"UNMANAGED ")+item.getWurmId()
                        +" "+item.getName()+" native=("+item.getPosX()+","+item.getPosY()+","+item.getRotation()+")"
                        +" live="+Items.getItemOptional(item.getWurmId()).isPresent());
        }
        return "nearby rail cars="+nearby.values();
    }
    private static Train train(RailNetwork net,Creature actor,List<Item> vehicles,String name,String start,String towards,int count,String...rear) throws Exception {
        Node origin=net.nodes.get(start);List<Long> ids=new ArrayList<Long>();
        Item engine=WurmRailWorld.spawnLocomotive(actor,origin.worldX(),origin.worldY(),0);engine.setName(name);vehicles.add(engine);ids.add(engine.getWurmId());
        for(int index=0;index<count-1;index++) {
            Node at=net.nodes.get(rear[index]);Item cart=WurmRailWorld.spawnCart(actor,at.worldX(),at.worldY(),0);
            vehicles.add(cart);ids.add(cart.getWurmId());
        }
        return net.createTrain(name,actor.getWurmId(),ids,start,towards);
    }
    private static void dumbbell(RailNetwork net,Creature actor) throws Exception {
        track(net,actor,"followL",108,100);track(net,actor,"followR",148,100);
        loop(net,actor,"followL","followl",108,-1);loop(net,actor,"followR","followr",148,1);
        String previous="followL";
        for(int x=109;x<148;x++)previous=point(net,actor,previous,"followc"+(x-108),x,100);
        net.connect(previous,"followR");
        for(String key:Arrays.asList("followL","followR")) {
            net.setJunctionAlignment(key,17);Node node=net.nodes.get(key);
            for(String next:node.links) {
                Node adjacent=net.nodes.get(next);int side=RailNetwork.direction(adjacent.tileX-node.tileX,adjacent.tileY-node.tileY);
                net.signals().add(key+"_"+side,key,side,-10L);
            }
        }
        for(String key:Arrays.asList("followl11","followr11")) { net.setStation(key,true,.5);Items.getItem(net.nodes.get(key).markerId).setName("Station: "+key); }
    }
    private static void loop(RailNetwork net,Creature actor,String junction,String prefix,int x,int across) throws Exception {
        String previous=junction;int index=0;
        for(int y=99;y>=96;y--)previous=point(net,actor,previous,prefix+(index++),x,y);
        for(int step=1;step<=4;step++)previous=point(net,actor,previous,prefix+(index++),x+across*step,96);
        for(int y=97;y<=104;y++)previous=point(net,actor,previous,prefix+(index++),x+across*4,y);
        for(int step=3;step>=0;step--)previous=point(net,actor,previous,prefix+(index++),x+across*step,104);
        for(int y=103;y>=101;y--)previous=point(net,actor,previous,prefix+(index++),x,y);
        net.connect(previous,junction);
    }
    private static String point(RailNetwork net,Creature actor,String previous,String name,int x,int y) throws Exception { track(net,actor,name,x,y);net.connect(previous,name);return name; }
    private static void track(RailNetwork net,Creature actor,String name,int x,int y) throws Exception {
        // Preserve the core fixture graph while avoiding earlier retained vehicles on y=100.
        Node node=net.addNode(name,x,y+90,0);
        node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();
    }
    private static Object get(Object object,String name) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
    private static void set(Object object,String name,Object value) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value); }
    private static Object call(Object object,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(object,args); }
        catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
