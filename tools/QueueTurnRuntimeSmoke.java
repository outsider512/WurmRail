import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
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
import org.wurmrail.core.TrafficControl;

/** Native regression for queued crossing intent across opposite-direction handovers. */
public final class QueueTurnRuntimeSmoke {
    private static int assertions;
    private QueueTurnRuntimeSmoke() { }

    public static void run() throws Exception { runCase(.1);runCase(.25); }

    private static void runCase(double dt) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998000032L+(dt>.1?256:0),true,2);
        RailNetwork net=new RailNetwork();WurmRailRuntime runtime=new WurmRailRuntime();
        List<Item> vehicles=new ArrayList<Item>();List<Train> all=new ArrayList<Train>();
        try {
            dumbbell(net,actor);
            Train a=train(net,actor,vehicles,"queueLeader","queuel2","queuel1","queuel3");
            Train b=train(net,actor,vehicles,"queueOpposite","queuer2","queuer1","queuer3");
            Train follower=train(net,actor,vehicles,"queueFollower","queuel7","queuel6");
            all.addAll(Arrays.asList(a,b,follower));
            net.setRoute(a.name,Arrays.asList("queuer11","queuel11"),.5);
            net.setRoute(b.name,Arrays.asList("queuel11","queuer11"),.5);
            net.setRoute(follower.name,Arrays.asList("queuer11","queuel11"),.5);
            Item cargo=ItemFactory.createItem(ItemList.plank,78f,"Native queue handover fixture");
            Item cart=Items.getItem(a.carIds.get(1));
            check(cart.insertItem(cargo),"native queue-test wagon accepts its cargo");
            long[][] initialSeats=seats(all);
            runtime.configure(new Properties());set(runtime,"network",net);set(runtime,"ready",true);
            set(runtime,"stateFile",Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath())
                    .resolve("native-queue-turn-"+(dt>.1?"quarter":"tenth")+"/network.properties"));
            call(runtime,"initService",new Class<?>[0]);call(runtime,"refreshTrackVisuals",new Class<?>[0]);
            for(Train train:all)call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            net.movementGuard=poses->{
                try { return (Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},poses); }
                catch(Exception failure) { throw new IllegalStateException("Native queue motion validation failed",failure); }
            };
            for(Train train:all)net.start(train.name);
            TrafficControl traffic=net.signals().traffic();
            // Establish opposite turns and a later follower without granting any remote authority.
            traffic.prepare(a,Arrays.asList("queueL","queuec1"));
            traffic.prepare(b,Arrays.asList("queueR","queuec39"));
            traffic.prepare(follower,Arrays.asList("queueL","queuec1"));
            boolean[] crossed=new boolean[all.size()],arrived=new boolean[all.size()];
            boolean heldAtRed=false;
            for(int step=0;step<Math.ceil(600/dt)&&(!arrived[0]||!arrived[1]||!arrived[2]);step++) {
                tick(runtime,net,all,dt);
                Node junction=net.nodes.get("queueR");Pose leading=b.poses.get(0);
                boolean outside=!(Boolean)call(net,"signalOccupiesNode",new Class<?>[]{Train.class,String.class},b,"queueR")
                        &&!(Boolean)call(net,"signalOccupiesNode",new Class<?>[]{Train.class,String.class},b,"queuer0");
                if(!crossed[1]&&b.speed==0&&outside&&Math.hypot(leading.x-junction.worldX(),leading.y-junction.worldY())<6.6
                        &&b.status.startsWith("Signal ")&&b.status.contains(" RED:")) {
                    heldAtRed=true;
                    check("queuec39".equals(traffic.preferredExit(b.name,"queueR","queuer0")),
                            "native opposite train at the real red holding boundary retains its intended crossing branch");
                }
                for(int index=0;index<all.size();index++) {
                    Train train=all.get(index);
                    if(onConnector(train))crossed[index]=true;
                    String wrongExit=index==1?"queuer22":"queuel22";
                    if(!crossed[index])for(Pose pose:train.poses)
                        check(!wrongExit.equals(pose.railName),"native queued "+train.name+" crosses the connector before another local station circuit");
                    if(train.routeIndex!=0) { check(crossed[index],"native first station arrival follows the intended connector crossing");arrived[index]=true; }
                    check(train.powered&&train.direction==1,"native queued service retains power, engine-leading traction and its station itinerary");
                }
            }
            check(heldAtRed,"native regression observes an opposite train safely held at the actual red-light boundary");
            check(arrived[0]&&arrived[1]&&arrived[2],"native opposing trains and follower all reach their first scheduled station without an extra local circuit; "+status(all));
            List<List<Pose>> parked=new ArrayList<List<Pose>>();
            for(Train train:all) { net.stop(train.name);parked.add(train.poses); }
            for(int step=0;step<Math.ceil(1/dt);step++) {
                tick(runtime,net,all,dt);
                for(int index=0;index<all.size();index++)check(!all.get(index).powered&&all.get(index).speed==0
                                &&samePoses(parked.get(index),all.get(index).poses),
                        "STOP keeps every native queued-service vehicle stationary without changing its physical facing");
            }
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==cart.getWurmId()&&cargo.getQualityLevel()==78f,
                    "native opposite-turn handovers and STOP preserve all seats and the exact wagon cargo item");
            System.out.println("PASS native queued opposite turns and follower, dt="+dt+", real red holding boundary, no station diversion, real movement guard, native commits, seats, cargo and STOP ("+assertions+" assertions)");
        } finally {
            net.movementGuard=null;
            for(Train train:new ArrayList<Train>(net.trains.values())) { net.stop(train.name);net.removeTrain(train.name); }
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())
                    &&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(Node node:net.nodes.values())if(Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }

    private static void tick(WurmRailRuntime runtime,RailNetwork net,List<Train> all,double dt) throws Exception {
        Map<Long,Pose> previous=new HashMap<Long,Pose>();for(Train train:all)for(Pose pose:train.poses)previous.put(pose.carId,pose);
        net.tick(dt);
        for(Train train:all)check(!train.status.equals("Movement blocked by the world")&&!train.status.startsWith("Blocked:")
                        &&!train.status.startsWith("Restore blocked:")&&!train.status.contains("world error"),
                "native queued service remains valid: "+train.name+" "+train.status+"; validation="+get(runtime,"validationError"));
        // Server polls commit native items only after the complete core tick has succeeded.
        for(Train train:all)call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
        for(Train train:all)for(Pose pose:train.poses) {
            Item item=Items.getItem(pose.carId);Pose old=previous.get(pose.carId);
            check(Math.abs(item.getPosX()-pose.x)<.01&&Math.abs(item.getPosY()-pose.y)<.01&&Math.abs(item.getRotation()-pose.rotation)<.01,
                    "every native queue-test item matches its committed core position and heading");
            double movement=Math.hypot(pose.x-old.x,pose.y-old.y),turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
            check(turn<90&&(movement>.00001||turn<.01),"native locomotives and carts turn on physical bends and never spin while stationary");
        }
        for(int first=0;first<all.size();first++)for(int second=first+1;second<all.size();second++)
            for(Pose a:all.get(first).poses)for(Pose b:all.get(second).poses)
                check(a.layer!=b.layer||Math.hypot(a.x-b.x,a.y-b.y)>=RailNetwork.TRAIN_CLEARANCE-.0001,
                        "opposite and following native services retain the full train clearance");
        Map<String,String> entries=new HashMap<String,String>();
        for(TrafficControl.Lease lease:net.signals().traffic().leases()) {
            String prior=entries.put(lease.corridorId,lease.entryJunction);
            check(prior==null||prior.equals(lease.entryJunction),"native queue handovers never admit opposite connector entries together");
        }
    }
    private static boolean onConnector(Train train) { for(Pose pose:train.poses)if(pose.railName!=null&&pose.railName.startsWith("queuec"))return true;return false; }
    private static boolean samePoses(List<Pose> first,List<Pose> second) {
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++) { Pose a=first.get(i),b=second.get(i);if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false; }
        return true;
    }
    private static long[][] seats(List<Train> trains) throws Exception {
        List<long[]> rows=new ArrayList<long[]>();
        for(Train train:trains)for(long id:train.carIds) {
            Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];
            for(int index=0;index<seats.length;index++)row[index]=seats[index].occupant;rows.add(row);
        }
        return rows.toArray(new long[rows.size()][]);
    }
    private static String status(List<Train> trains) { List<String> rows=new ArrayList<String>();for(Train train:trains)rows.add(train.name+"="+train.status+" "+train.poses);return rows.toString(); }
    private static Train train(RailNetwork net,Creature actor,List<Item> vehicles,String name,String start,String towards,String...rear) throws Exception {
        Node origin=net.nodes.get(start);List<Long> ids=new ArrayList<Long>();
        Item engine=WurmRailWorld.spawnLocomotive(actor,origin.worldX(),origin.worldY(),0);engine.setName(name);vehicles.add(engine);ids.add(engine.getWurmId());
        for(String rail:rear) { Node node=net.nodes.get(rail);Item cart=WurmRailWorld.spawnCart(actor,node.worldX(),node.worldY(),0);vehicles.add(cart);ids.add(cart.getWurmId()); }
        return net.createTrain(name,actor.getWurmId(),ids,start,towards);
    }
    private static void dumbbell(RailNetwork net,Creature actor) throws Exception {
        track(net,actor,"queueL",108,100);track(net,actor,"queueR",148,100);
        loop(net,actor,"queueL","queuel",108,-1);loop(net,actor,"queueR","queuer",148,1);
        String previous="queueL";for(int x=109;x<148;x++)previous=point(net,actor,previous,"queuec"+(x-108),x,100);net.connect(previous,"queueR");
        for(String rail:Arrays.asList("queueL","queueR")) {
            net.setJunctionAlignment(rail,17);Node node=net.nodes.get(rail);
            for(String next:node.links) { Node adjacent=net.nodes.get(next);int side=RailNetwork.direction(adjacent.tileX-node.tileX,adjacent.tileY-node.tileY);net.signals().add(rail+"_"+side,rail,side,-10L); }
        }
        for(String rail:Arrays.asList("queuel11","queuer11")) { net.setStation(rail,true,.5);Items.getItem(net.nodes.get(rail).markerId).setName("Station: "+rail); }
    }
    private static void loop(RailNetwork net,Creature actor,String junction,String prefix,int x,int across) throws Exception {
        String previous=junction;int index=0;
        for(int y=99;y>=96;y--)previous=point(net,actor,previous,prefix+(index++),x,y);
        for(int step=1;step<=4;step++)previous=point(net,actor,previous,prefix+(index++),x+across*step,96);
        for(int y=97;y<=104;y++)previous=point(net,actor,previous,prefix+(index++),x+across*4,y);
        for(int step=3;step>=0;step--)previous=point(net,actor,previous,prefix+(index++),x+across*step,104);
        for(int y=103;y>=101;y--)previous=point(net,actor,previous,prefix+(index++),x,y);net.connect(previous,junction);
    }
    private static String point(RailNetwork net,Creature actor,String previous,String name,int x,int y) throws Exception { track(net,actor,name,x,y);net.connect(previous,name);return name; }
    private static void track(RailNetwork net,Creature actor,String name,int x,int y) throws Exception {
        // ConvoyRuntimeSmoke cleans this fixture area before the next test uses it.
        Node node=net.addNode(name,x,y+90,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();
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
