import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.lang.reflect.*;
import java.util.*;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.SignalSystem;

/** Native vehicles must follow the coordinated single-cycle Y maneuver safely. */
public final class YLoopRuntimeSmoke {
    private static int assertions;
    private YLoopRuntimeSmoke() { }
    public static void run() throws Exception {
        runCase(false,2,1,false,true,false,.1);
        runCase(true,1,2,false,true,true,.25);
        runCase(false,1,1,true,true,false,.1);
        System.out.println("PASS native coordinated Y loop: two/three services, mixed carts, loop following, occupied legacy approaches, backing restart, operator STOP, real repeated station visits, 12m whole-consist clearance, exclusive points, seats and cargo ("+assertions+" assertions)");
    }

    private static void runCase(boolean mirror,int loopCars,int followers,boolean warm,boolean restart,boolean pause,double dt) throws Exception {
        String tag="y-"+mirror+"-"+loopCars+"-"+followers+"-"+warm;
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998211104L+(mirror?256:0)+(warm?512:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        try {
            graph(net,actor,mirror);List<String> names=new ArrayList<String>();
            Train loop=vehicle(net,actor,vehicles,"YLoop",warm?"r1":"r21",warm?"Y":"r20",loopCars,
                    loopCars>1?new String[]{warm?"r2":"r22"}:new String[0]);
            net.setRoute(loop.name,Arrays.asList("n5","r20"),5);set(loop,"recoveringTraffic",true);names.add(loop.name);
            for(int index=0;index<followers;index++) {
                int cars=index==0&&followers>1?2:1;
                String at="s"+(index*3+1),front=index==0?"Y":"s"+(index*3);
                String[] rear=cars>1?new String[]{"s"+(index*3+2)}:new String[0];
                Train train=vehicle(net,actor,vehicles,"YStem"+index,at,front,cars,rear);
                net.setRoute(train.name,Arrays.asList("r20","n5"),5);names.add(train.name);
            }
            if(warm) {
                seed(net,loop,Arrays.asList("r2","r1","Y"),8.035281374238561);
                seed(net,net.trains.get("YStem0"),Arrays.asList("s2","s1","Y"),5.55);
            }
            List<Train> all=trains(net,names);Item hold=Items.getItem(loop.carIds.get(loop.carIds.size()-1));
            if(followers>1)hold=Items.getItem(net.trains.get("YStem0").carIds.get(1));
            Item cargo=ItemFactory.createItem(ItemList.plank,81f,"Native Y loop fixture");check(hold.insertItem(cargo),"native Y vehicle accepts its real cargo item");
            long cargoHolder=hold.getWurmId();WurmRailRuntime runtime=bind(net,tag);long[][] initialSeats=seats(all);
            Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();
            Map<String,Integer> cursor=new LinkedHashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>();Map<String,Double> last=new HashMap<String,Double>();
            for(Train train:all) {cars.put(train.name,new ArrayList<Long>(train.carIds));routes.put(train.name,new ArrayList<String>(train.route));cursor.put(train.name,train.routeIndex);visits.put(train.name,0);last.put(train.name,0d);net.resumeScheduled(train.name);}
            boolean reloaded=false,paused=false,resumed=false,shared=false,complete=false;int pivots=0,emergency=0;List<Pose> held=null;
            double reloadAt=-1;
            for(int step=1;step<=Math.ceil(1800/dt)&&!complete;step++) {
                double seconds=step*dt;Map<String,Integer> direction=new HashMap<String,Integer>();Map<String,String> target=new HashMap<String,String>();
                for(Train train:all) {direction.put(train.name,train.direction);target.put(train.name,train.route.get(train.routeIndex));}
                tick(runtime,net,all,dt);
                for(Train train:all) {
                    boolean stopped=pause&&paused&&!resumed&&train.name.equals("YStem0");
                    check(train.powered!=stopped&&train.automated&&train.route.equals(routes.get(train.name))&&train.carIds.equals(cars.get(train.name)),
                            "native Y plan preserves service power, consist and itinerary: "+train.name+" "+train.status);
                    if(stopped)same(held,train.poses,"automatic or requested recovery cannot move an operator-stopped native train");
                    if(train.routeIndex!=cursor.get(train.name)) {
                        Node station=net.nodes.get(target.get(train.name));boolean physical=false;
                        for(Pose pose:train.poses)physical|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                        check(physical,"native Y recovery cannot count an unvisited scheduled station");
                        visits.put(train.name,visits.get(train.name)+1);cursor.put(train.name,train.routeIndex);last.put(train.name,seconds);
                    }
                    if(train.direction!=direction.get(train.name)) {
                        if(train.status.startsWith("Yielding to "))emergency++;
                        if(direction.get(train.name)<0&&train.direction>0&&net.trafficRecoveryStatus(train.name).startsWith("Turnback complete:")) {
                            pivots++;Node junction=net.nodes.get("Y");
                            for(Pose pose:train.poses)check(pose.railName.startsWith("r")&&Math.hypot(pose.x-junction.worldX(),pose.y-junction.worldY())>=12-.001,
                                    "coordinated forward pivot waits until every native car is twelve metres clear of the Y points");
                        }
                    }
                }
                protectedPoints(net);
                if(onRing(all.get(0)))for(int i=1;i<all.size();i++)if(onRing(all.get(i)))shared=true;
                if(restart&&!reloaded) {
                    boolean backing=false;for(Train train:all)if(train.direction<0)for(Pose pose:train.poses)
                        backing|=pose.railName.startsWith("s")||pose.railName.startsWith("n")||pose.railName.equals("B");
                    if(backing) {
                        Map<Long,Pose> original=new HashMap<Long,Pose>();for(Train train:all)for(Pose pose:train.poses)original.put(pose.carId,pose);
                        net.movementGuard=null;net=(RailNetwork)helper("reload",new Class<?>[]{RailNetwork.class,String.class},net,tag+"-active");
                        all=trains(net,names);runtime=bind(net,tag+"-restart");
                        for(Train train:all){for(Pose pose:train.poses){Pose old=original.get(pose.carId);check(old.x==pose.x&&old.y==pose.y&&old.rotation==pose.rotation,"native backing restart retains exact body progress and physical heading");}net.resumeScheduled(train.name);}
                        check(Arrays.deepEquals(initialSeats,seats(all)),"mid-backing restart retains every native passenger socket occupant");
                        reloaded=true;reloadAt=seconds;
                    }
                }
                if(pause&&!paused&&seconds>=90) {Train train=net.trains.get("YStem0");net.stop(train.name);held=new ArrayList<Pose>(train.poses);paused=true;}
                if(pause&&paused&&!resumed) {
                    if(step%Math.max(1,(int)(5/dt))==0)net.resolveTrafficJams();
                    if(seconds>=120){net.resumeScheduled("YStem0");resumed=true;}
                }
                complete=seconds>=600;
                for(String name:names)if(visits.get(name)<4||seconds-last.get(name)>180)complete=false;
            }
            check(complete&&shared&&pivots>0&&(!restart||reloaded)&&(!pause||resumed),
                    "all native Y services follow into the loop and complete repeated real routes after coordinated backing/restart: "+visits+" "+status(net,all));
            check(emergency<=(warm||pause?3:0),"native Y service uses planned clearance maneuvers rather than repeated emergency head-ons: "+emergency+" "+status(net,all));
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==cargoHolder&&cargo.getQualityLevel()==81f,
                    "Y following, guarded turns, restart and STOP preserve native seats and exact cargo identity");
            System.out.println("PASS native Y case "+tag+" dt="+dt+" visits="+visits+" earlyPivots="+pivots+" emergencyYields="+emergency+" backingRestartAt="+reloadAt);
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
            if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());
        }
    }

    private static void graph(RailNetwork net,Creature actor,boolean mirror) throws Exception {
        String previous=point(net,actor,null,"Y",0,0,mirror);
        for(int i=1;i<=10;i++)previous=point(net,actor,previous,"r"+i,i,-i,mirror);
        for(int i=11;i<=14;i++)previous=point(net,actor,previous,"r"+i,i,i-20,mirror);
        for(int i=15;i<=33;i++)previous=point(net,actor,previous,"r"+i,14,i-20,mirror);
        previous=point(net,actor,previous,"r34",13,13,mirror);
        for(int i=35;i<=46;i++)previous=point(net,actor,previous,"r"+i,47-i,47-i,mirror);
        net.connect(previous,"Y");previous="Y";
        for(int i=1;i<=7;i++)previous=point(net,actor,previous,"s"+i,-i,0,mirror);
        previous=point(net,actor,previous,"B",-8,0,mirror);
        for(int i=1;i<=5;i++)previous=point(net,actor,previous,"n"+i,-8,-i,mirror);
        previous="B";for(int i=1;i<=5;i++)previous=point(net,actor,previous,"w"+i,-8-i,0,mirror);
        net.setJunctionAlignment("Y",(1<<side(net,"Y","r1"))|(1<<side(net,"Y","r46")));
        net.setJunctionAlignment("B",(1<<side(net,"B","s7"))|(1<<side(net,"B","n1")));
        for(String junction:Arrays.asList("Y","B"))for(String link:net.nodes.get(junction).links)net.signals().add(junction+"_"+link,junction,side(net,junction,link),-10L);
        for(String stop:Arrays.asList("r20","n5")){net.setStation(stop,true,5);Items.getItem(net.nodes.get(stop).markerId).setName("Station: "+stop);}
        check(net.nodes.size()==65,"native Y fixture includes the actual single-cycle ring, stem and terminal/holding branches");
    }
    private static String point(RailNetwork net,Creature actor,String previous,String name,int x,int y,boolean mirror) throws Exception {
        Node node=net.addNode(name,110+(mirror?-x:x),80+y,0);node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();if(previous!=null)net.connect(previous,name);return name;
    }
    private static int side(RailNetwork net,String a,String b){Node x=net.nodes.get(a),y=net.nodes.get(b);return RailNetwork.direction(y.tileX-x.tileX,y.tileY-x.tileY);}
    private static Train vehicle(RailNetwork net,Creature actor,List<Item> items,String name,String at,String toward,int cars,String[] rear) throws Exception {
        return (Train)invoke(ConvoyRuntimeSmoke.class,null,"train",new Class<?>[]{RailNetwork.class,Creature.class,List.class,String.class,String.class,String.class,int.class,String[].class},new Object[]{net,actor,items,name,at,toward,cars,rear});
    }
    @SuppressWarnings("unchecked") private static void seed(RailNetwork net,Train train,List<String> path,double distance) throws Exception {
        List<String> history=(List<String>)get(train,"path");history.clear();history.addAll(path);set(train,"locomotiveDistance",distance);set(train,"initialTowards",null);call(net,"refresh",new Class<?>[]{Train.class},train);
    }
    private static WurmRailRuntime bind(RailNetwork net,String tag)throws Exception{return (WurmRailRuntime)helper("bind",new Class<?>[]{RailNetwork.class,String.class},net,tag);}
    private static void tick(WurmRailRuntime runtime,RailNetwork net,List<Train> all,double dt)throws Exception{helper("tick",new Class<?>[]{WurmRailRuntime.class,RailNetwork.class,List.class,double.class},runtime,net,all,dt);}
    private static Object helper(String name,Class<?>[] types,Object...args)throws Exception{return invoke(JamRecoveryRuntimeSmoke.class,null,name,types,args);}
    private static List<Train> trains(RailNetwork net,List<String> names){List<Train> result=new ArrayList<Train>();for(String name:names)result.add(net.trains.get(name));return result;}
    private static long[][] seats(List<Train> all)throws Exception{List<long[]> result=new ArrayList<long[]>();for(Train train:all)for(long id:train.carIds){Seat[] values=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[values.length];for(int i=0;i<row.length;i++)row[i]=values[i].occupant;result.add(row);}return result.toArray(new long[result.size()][]);}
    private static boolean onRing(Train train){for(Pose pose:train.poses)if(pose.railName!=null&&pose.railName.matches("r[1-9][0-9]*"))return true;return false;}
    private static void protectedPoints(RailNetwork net){Map<String,String> locks=new HashMap<String,String>();for(SignalSystem.Reservation reservation:net.signals().reservations())for(Map.Entry<String,Integer> point:reservation.junctionLocks().entrySet()){String previous=locks.put(point.getKey(),reservation.trainName);Node node=net.nodes.get(point.getKey());check(previous==null||previous.equals(reservation.trainName),"native Y points cannot be locked simultaneously by different trains");check(reservation.trainName.equals(node.getAlignmentOwner())&&point.getValue()==node.getAlignmentMask(),"native Y alignment and reservation ownership remain physically consistent");}}
    private static void same(List<Pose> before,List<Pose> after,String message){check(before!=null&&before.size()==after.size(),message);for(int i=0;i<before.size();i++){Pose a=before.get(i),b=after.get(i);check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);}}
    private static Object get(Object object,String field)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(object);}
    private static void set(Object object,String field,Object value)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value);}
    private static Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{return invoke(object.getClass(),object,name,types,args);}
    private static Object invoke(Class<?> type,Object object,String name,Class<?>[] types,Object[] args)throws Exception{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(object,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failure;}}
    private static String status(RailNetwork net,List<Train> all){List<String> values=new ArrayList<String>();for(Train t:all)values.add(t.name+" "+t.direction+" "+t.status+" "+t.poses+" "+net.signals().trafficStatus(t.name));return values.toString();}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
}
