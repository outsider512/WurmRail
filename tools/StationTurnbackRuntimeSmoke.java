import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;

/** Actual native engines/carts back into the nearest tail and depart engine first. */
public final class StationTurnbackRuntimeSmoke {
    private static int assertions;
    private StationTurnbackRuntimeSmoke() { }
    public static void run() throws Exception {
        runCase(false,2,true,false,.1);runCase(true,3,false,true,.25);
        System.out.println("PASS native local station turnback: nearest junction, reserved straight backing tail, whole-consist points clearance, correct forward departure, mid-turn restart, operator STOP, repeated physical station calls, real guards/commits, seats and cargo ("+assertions+" assertions)");
    }
    private static void runCase(boolean mirror,int count,boolean restart,boolean pause,double dt) throws Exception {
        String tag="station-tail-"+mirror+"-"+count;
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998311104L+(mirror?256:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        try {
            graph(net,actor,mirror);String[] rear=new String[count-1];for(int i=0;i<rear.length;i++)rear[i]="s"+(i+5);
            Train train=(Train)helper("vehicle",new Class<?>[]{RailNetwork.class,Creature.class,List.class,String.class,String.class,String.class,int.class,String[].class},net,actor,vehicles,"LocalService","s4","s3",count,rear);
            net.setRoute(train.name,Arrays.asList("e5","r19"),2);
            Item wagon=Items.getItem(train.carIds.get(count-1)),cargo=ItemFactory.createItem(ItemList.plank,79f,"Native local turnback");
            check(wagon.insertItem(cargo),"native local turnback cart accepts a real cargo item");
            List<Train> all=new ArrayList<Train>(Arrays.asList(train));WurmRailRuntime runtime=bind(net,tag);long[][] initialSeats=seats(all);
            List<String> route=new ArrayList<String>(train.route);List<Long> cars=new ArrayList<Long>(train.carIds);net.resumeScheduled(train.name);
            int cursor=train.routeIndex,visits=0;boolean firstStation=false,tail=false,pivot=false,backingPoints=false,forwardPoints=false;
            boolean reloaded=false,paused=false,resumed=false;List<Pose> held=null;double pausedAt=0;
            for(int step=1;step<=Math.ceil(900/dt)&&visits<4;step++) {
                double seconds=step*dt;int traction=train.direction;String target=train.route.get(train.routeIndex);
                tick(runtime,net,all,dt);train=net.trains.get("LocalService");
                check(train.powered!=((paused&&!resumed))&&train.automated&&train.carIds.equals(cars)&&train.route.equals(route),"native station turn preserves the exact train, saved itinerary and operator power setting");
                if(train.routeIndex!=cursor) {
                    Node stop=net.nodes.get(target);boolean physical=false;
                    for(Pose pose:train.poses)physical|=Math.hypot(pose.x-stop.worldX(),pose.y-stop.worldY())<.51;
                    check(physical,"native local turnback cannot manufacture a scheduled station call");
                    visits++;cursor=train.routeIndex;if(target.equals("e5"))firstStation=true;
                }
                if(firstStation&&visits==1) {
                    boolean west=false,south=false;for(Pose pose:train.poses) {west|=pose.railName.startsWith("w");south|=pose.railName.startsWith("s");}
                    if(train.direction<0&&west)tail=true;
                    check(!(train.direction<0&&south),"native train uses the closest tail instead of backing down the main to the remote loop");
                    if(occupies(net,train,"J")) {
                        int alignment=net.nodes.get("J").getAlignmentMask();
                        if(train.direction<0) {check(alignment==pair(net,"J","e1","w1"),"backing through the nearest points uses the straight station-to-tail alignment");backingPoints=true;}
                        else if(tail) {check(alignment==pair(net,"J","w1","s1"),"forward departure changes the points from tail to single-track main");forwardPoints=true;}
                    }
                    if(traction<0&&train.direction>0) {
                        for(Pose pose:train.poses)check(pose.railName.startsWith("w"),"every native car must be in the tail before forward traction is restored");
                        check(!occupies(net,train,"J"),"forward restoration waits until the last native car clears the physical points");pivot=true;
                    }
                    if(restart&&!reloaded&&tail&&train.direction<0) {
                        List<Pose> saved=new ArrayList<Pose>(train.poses);net.movementGuard=null;
                        net=(RailNetwork)helper("reload",new Class<?>[]{RailNetwork.class,String.class},net,tag+"-active");
                        train=net.trains.get("LocalService");all=new ArrayList<Train>(Arrays.asList(train));runtime=bind(net,tag+"-restart");
                        same(saved,train.poses,"native restart preserves each body position and heading during a station turn");
                        check(!train.powered&&train.direction<0,"native restart preserves the backing maneuver and waits for authorized scheduled resume");
                        check(Arrays.deepEquals(initialSeats,seats(all)),"native restart retains each passenger seat occupant");net.resumeScheduled(train.name);reloaded=true;
                    }
                    if(pause&&!paused&&tail&&train.direction<0) {net.stop(train.name);held=new ArrayList<Pose>(train.poses);paused=true;pausedAt=seconds;}
                }
                if(paused&&!resumed) {
                    same(held,train.poses,"Clear traffic jams cannot move an operator-stopped native station turn");
                    if(step%Math.max(1,(int)(5/dt))==0)net.resolveTrafficJams();
                    if(seconds-pausedAt>=20){net.resumeScheduled(train.name);resumed=true;}
                }
                protectedPoints(net);
            }
            check(visits>=4&&tail&&pivot&&backingPoints&&forwardPoints&&(!restart||reloaded)&&(!pause||resumed),"native service must use both real point alignments and regain repeated physical station calls: "+visits+" "+train.status+" "+train.poses);
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==wagon.getWurmId()&&cargo.getQualityLevel()==79f,"native station backing, real movement, restart and STOP preserve every seat and the exact cart cargo item");
            System.out.println("PASS native station turnback "+tag+", visits="+visits+", dt="+dt+", straight backing points, whole-consist clearance and engine-leading main departure");
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
            if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());
        }
    }
    private static void graph(RailNetwork n,Creature actor,boolean mirror) throws Exception {
        point(n,actor,null,"J",0,0,mirror);String previous="J";
        for(int i=1;i<=5;i++)previous=point(n,actor,previous,"e"+i,i,0,mirror);
        previous="J";for(int i=1;i<=10;i++)previous=point(n,actor,previous,"w"+i,-i,0,mirror);
        previous="J";for(int i=1;i<=19;i++)previous=point(n,actor,previous,"s"+i,0,i,mirror);
        previous=point(n,actor,previous,"RemoteY",0,20,mirror);
        for(int i=1;i<=10;i++)previous=point(n,actor,previous,"r"+i,i,20+i,mirror);
        for(int i=11;i<=20;i++)previous=point(n,actor,previous,"r"+i,20-i,20+i,mirror);
        for(int i=21;i<=30;i++)previous=point(n,actor,previous,"r"+i,20-i,60-i,mirror);
        for(int i=31;i<=39;i++)previous=point(n,actor,previous,"r"+i,i-40,60-i,mirror);
        n.connect(previous,"RemoteY");n.setJunctionAlignment("J",pair(n,"J","s1","e1"));n.setJunctionAlignment("RemoteY",pair(n,"RemoteY","r1","r39"));
        for(String junction:Arrays.asList("J","RemoteY"))for(String link:n.nodes.get(junction).links)n.signals().add(junction+"_"+link,junction,side(n,junction,link),-10);
        for(String station:Arrays.asList("e5","r19")){n.setStation(station,true,2);Items.getItem(n.nodes.get(station).markerId).setName("Station: "+station);}
    }
    private static String point(RailNetwork n,Creature actor,String previous,String name,int x,int y,boolean mirror) throws Exception {
        Node node=n.addNode(name,110+(mirror?-x:x),80+y,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();if(previous!=null)n.connect(previous,name);return name;
    }
    private static int side(RailNetwork n,String a,String b){Node from=n.nodes.get(a),to=n.nodes.get(b);return RailNetwork.direction(to.tileX-from.tileX,to.tileY-from.tileY);}
    private static int pair(RailNetwork n,String a,String b,String c){return (1<<side(n,a,b))|(1<<side(n,a,c));}
    private static boolean occupies(RailNetwork n,Train t,String rail)throws Exception{Method m=RailNetwork.class.getDeclaredMethod("signalOccupiesNode",Train.class,String.class);m.setAccessible(true);return(Boolean)m.invoke(n,t,rail);}
    private static WurmRailRuntime bind(RailNetwork n,String tag)throws Exception{return(WurmRailRuntime)helper("bind",new Class<?>[]{RailNetwork.class,String.class},n,tag);}
    private static void tick(WurmRailRuntime runtime,RailNetwork n,List<Train> all,double dt)throws Exception{helper("tick",new Class<?>[]{WurmRailRuntime.class,RailNetwork.class,List.class,double.class},runtime,n,all,dt);}
    private static Object helper(String name,Class<?>[] types,Object...args)throws Exception{Method m=JamRecoveryRuntimeSmoke.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException f){Throwable c=f.getCause();if(c instanceof Exception)throw(Exception)c;if(c instanceof Error)throw(Error)c;throw f;}}
    private static long[][] seats(List<Train> all)throws Exception{List<long[]> rows=new ArrayList<long[]>();for(Train train:all)for(long id:train.carIds){Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<row.length;i++)row[i]=seats[i].occupant;rows.add(row);}return rows.toArray(new long[rows.size()][]);}
    private static void same(List<Pose> a,List<Pose> b,String message){check(a!=null&&a.size()==b.size(),message);for(int i=0;i<a.size();i++)check(a.get(i).carId==b.get(i).carId&&a.get(i).x==b.get(i).x&&a.get(i).y==b.get(i).y&&a.get(i).rotation==b.get(i).rotation,message);}
    private static void protectedPoints(RailNetwork n){Map<String,String> locks=new HashMap<String,String>();for(SignalSystem.Reservation reservation:n.signals().reservations())for(Map.Entry<String,Integer> point:reservation.junctionLocks().entrySet()){String previous=locks.put(point.getKey(),reservation.trainName);Node node=n.nodes.get(point.getKey());check(previous==null||previous.equals(reservation.trainName),"native station points cannot be reserved by two trains");check(reservation.trainName.equals(node.getAlignmentOwner())&&point.getValue()==node.getAlignmentMask(),"native station point alignment matches its exclusive reservation");}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
