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

/** Real vehicles must drain a mixed-orientation ring before admitting a fresh head-on. */
public final class OpposedYLoopRuntimeSmoke {
    private static int assertions;
    private OpposedYLoopRuntimeSmoke() { }
    public static void run() throws Exception {
        runCase(false,true,1,1,1,4,true,false,.1);
        runCase(true,false,2,3,2,8,true,true,.25);
        System.out.println("PASS native opposed Y loop: exact saved mixed-flow queue, cold head-on, three services, mixed carts, coordinated stem entry, repeated real station calls, restart, operator STOP, exclusive points, progressive native poses, seats and cargo ("+assertions+" assertions)");
    }

    private static void runCase(boolean mirror,boolean warm,int forwardCars,int opposedCars,int stemCars,int tailLength,boolean restart,boolean pause,double dt) throws Exception {
        String tag="opposed-y-"+mirror+"-"+warm+"-"+forwardCars+"-"+opposedCars;
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998411104L+(mirror?256:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        try {
            graph(net,actor,mirror,tailLength);
            Train stem=vehicle(net,actor,vehicles,"OpposedStem",warm?"s2":"s1",warm?"s1":"Y",stemCars,rear("s",warm?3:2,1,stemCars));
            net.setRoute(stem.name,warm?Arrays.asList("n5","r20"):Arrays.asList("r20","n5"),5);if(warm)stem.routeIndex=1;
            Train forward=vehicle(net,actor,vehicles,"OpposedForward",warm?"r6":"r17",warm?"r5":"r18",forwardCars,rear("r",warm?7:16,warm?1:-1,forwardCars));
            net.setRoute(forward.name,warm?Arrays.asList("r20","n5"):Arrays.asList("n5","r20"),5);if(warm)forward.routeIndex=1;
            Train opposed=vehicle(net,actor,vehicles,"OpposedAdded",warm?"r7":"r20",warm?"r8":"r19",opposedCars,rear("r",warm?6:21,warm?-1:1,opposedCars));
            net.setRoute(opposed.name,Arrays.asList("n5","r20"),5);
            if(warm) {
                seed(net,stem,Arrays.asList("s3","s2","s1"),5.55);
                seed(net,forward,Arrays.asList("r7","r6","r5"),8.035281374238561);
                seed(net,opposed,Arrays.asList("r6","r7","r8"),6.108118270594552);
                opposed.direction=-1;set(opposed,"recoveringTraffic",true);
            } else {
                // These are established physical histories, so initial route
                // selection cannot silently make this a clean spawn instead.
                seed(net,stem,Arrays.asList("s3","s2","s1","Y"),8);
                seed(net,forward,Arrays.asList("r15","r16","r17","r18"),8);
                seed(net,opposed,Arrays.asList("r23","r22","r21","r20","r19"),12);
            }
            List<String> names=Arrays.asList(stem.name,forward.name,opposed.name);List<Train> all=trains(net,names);
            Item holder=Items.getItem(opposed.carIds.get(opposed.carIds.size()-1));
            Item cargo=ItemFactory.createItem(ItemList.plank,83f,"Native opposed Y fixture");
            check(holder.insertItem(cargo),"opposed Y native train accepts its actual cargo item");long holderId=holder.getWurmId();
            WurmRailRuntime runtime=bind(net,tag);long[][] initialSeats=seats(all);
            Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();
            Map<String,Integer> cursor=new LinkedHashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>();Map<String,Double> last=new HashMap<String,Double>();
            for(Train train:all) {cars.put(train.name,new ArrayList<Long>(train.carIds));routes.put(train.name,new ArrayList<String>(train.route));cursor.put(train.name,train.routeIndex);visits.put(train.name,0);last.put(train.name,0d);net.resumeScheduled(train.name);}
            boolean reloaded=false,paused=false,resumed=false,entered=false,complete=false;int emergency=0;
            List<Pose> stopped=null;double restartAt=-1;
            for(int step=1;step<=Math.ceil(1800/dt)&&!complete;step++) {
                double seconds=step*dt;Map<String,Integer> traction=new HashMap<String,Integer>();Map<String,String> target=new HashMap<String,String>();
                for(Train train:all) {traction.put(train.name,train.direction);target.put(train.name,train.route.get(train.routeIndex));}
                tick(runtime,net,all,dt);
                for(Train train:all) {
                    boolean operatorStopped=pause&&paused&&!resumed&&train.name.equals("OpposedStem");
                    check(train.powered!=operatorStopped&&train.automated&&train.carIds.equals(cars.get(train.name))&&train.route.equals(routes.get(train.name)),
                            "native mixed-orientation recovery preserves service intent, cars and itinerary: "+train.name+" "+train.status);
                    if(operatorStopped)same(stopped,train.poses,"Clear cannot override an operator-stopped native Y service");
                    if(train.routeIndex!=cursor.get(train.name)) {
                        Node actualStop=net.nodes.get(target.get(train.name));boolean physical=false;
                        for(Pose body:train.poses)physical|=body.layer==actualStop.layer&&Math.hypot(body.x-actualStop.worldX(),body.y-actualStop.worldY())<.51;
                        check(physical,"native opposed Y correction cannot manufacture an unvisited station arrival");
                        visits.put(train.name,visits.get(train.name)+1);cursor.put(train.name,train.routeIndex);last.put(train.name,seconds);
                    }
                    if(train.direction!=traction.get(train.name)&&train.status.startsWith("Yielding to ")) {
                        emergency++;check(seconds<=300,"native ring must settle into route service instead of repeat emergency head-ons indefinitely: seconds="+seconds+" visits="+visits+" yields="+emergency+" "+status(net,all));
                    }
                }
                if(onRing(net.trains.get("OpposedStem")))entered=true;
                protectedPoints(net);
                if(restart&&!reloaded&&seconds>=8) {
                    boolean recovering=false;for(Train train:all)recovering|=!net.trafficRecoveryStatus(train.name).isEmpty()||train.direction<0;
                    if(recovering||seconds>=40) {
                        Map<Long,Pose> before=new HashMap<Long,Pose>();for(Train train:all)for(Pose pose:train.poses)before.put(pose.carId,pose);
                        net.movementGuard=null;net=(RailNetwork)helper("reload",new Class<?>[]{RailNetwork.class,String.class},net,tag+"-active");
                        all=trains(net,names);runtime=bind(net,tag+"-restart");
                        for(Train train:all) {for(Pose pose:train.poses) {Pose old=before.get(pose.carId);check(old.x==pose.x&&old.y==pose.y&&old.rotation==pose.rotation&&old.layer==pose.layer,"native mid-recovery restart preserves exact body progress and heading");}net.resumeScheduled(train.name);}
                        check(Arrays.deepEquals(initialSeats,seats(all)),"native mixed-orientation restart retains all passenger seat occupants");
                        reloaded=true;restartAt=seconds;
                    }
                }
                if(pause&&!paused&&seconds>=90) {Train train=net.trains.get("OpposedStem");net.stop(train.name);stopped=new ArrayList<Pose>(train.poses);paused=true;}
                if(pause&&paused&&!resumed) {
                    if(step%Math.max(1,(int)(5/dt))==0)net.resolveTrafficJams();
                    if(seconds>=120){net.resumeScheduled("OpposedStem");resumed=true;}
                }
                complete=seconds>=600;
                for(String name:names)if(visits.get(name)<4||seconds-last.get(name)>180)complete=false;
            }
            check(complete&&entered&&(!restart||reloaded)&&(!pause||resumed),"all three native opposed services must regain repeated actual station calls and the waiting stem must enter: "+visits+" "+status(net,all));
            check(emergency<=8,"native opposed recovery uses a bounded sequence of safe maneuvers: "+emergency);
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==holderId&&cargo.getQualityLevel()==83f,"coordinated ring drain, native moves, STOP and restart preserve exact seats and wagon cargo");
            System.out.println("PASS native opposed Y case "+tag+" dt="+dt+" visits="+visits+" emergencyYields="+emergency+" restartAt="+restartAt);
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
            if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());
        }
    }
    private static void graph(RailNetwork n,Creature actor,boolean mirror,int tailLength)throws Exception {
        invoke(YLoopRuntimeSmoke.class,null,"graph",new Class<?>[]{RailNetwork.class,Creature.class,boolean.class},n,actor,mirror);
        String previous="B";for(int i=1;i<=tailLength;i++) {
            Node node=n.addNode("z"+i,110+(mirror?8:-8),80+i,0);
            node.markerId=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0).getWurmId();n.connect(previous,node.name);previous=node.name;
        }
        n.signals().add("B_z1","B",side(n,"B","z1"),-10);
        check(n.nodes.size()==65+tailLength,"opposed Y fixture retains its ring, station, stubs and opposite station backing lane");
    }
    private static String[] rear(String prefix,int first,int delta,int count){String[] result=new String[count-1];for(int i=0;i<result.length;i++)result[i]=prefix+(first+delta*i);return result;}
    private static int side(RailNetwork n,String a,String b){Node from=n.nodes.get(a),to=n.nodes.get(b);return RailNetwork.direction(to.tileX-from.tileX,to.tileY-from.tileY);}
    private static Train vehicle(RailNetwork n,Creature actor,List<Item> items,String name,String at,String toward,int cars,String[] rear)throws Exception{return(Train)invoke(YLoopRuntimeSmoke.class,null,"vehicle",new Class<?>[]{RailNetwork.class,Creature.class,List.class,String.class,String.class,String.class,int.class,String[].class},n,actor,items,name,at,toward,cars,rear);}
    private static void seed(RailNetwork n,Train train,List<String> path,double distance)throws Exception{invoke(YLoopRuntimeSmoke.class,null,"seed",new Class<?>[]{RailNetwork.class,Train.class,List.class,double.class},n,train,path,distance);}
    private static List<Train> trains(RailNetwork n,List<String> names){List<Train> result=new ArrayList<Train>();for(String name:names)result.add(n.trains.get(name));return result;}
    private static boolean onRing(Train train){for(Pose pose:train.poses)if(pose.railName!=null&&pose.railName.matches("r[1-9][0-9]*"))return true;return false;}
    private static WurmRailRuntime bind(RailNetwork n,String tag)throws Exception{return(WurmRailRuntime)helper("bind",new Class<?>[]{RailNetwork.class,String.class},n,tag);}
    private static void tick(WurmRailRuntime runtime,RailNetwork n,List<Train> all,double dt)throws Exception{helper("tick",new Class<?>[]{WurmRailRuntime.class,RailNetwork.class,List.class,double.class},runtime,n,all,dt);}
    private static Object helper(String name,Class<?>[] types,Object...args)throws Exception{return invoke(JamRecoveryRuntimeSmoke.class,null,name,types,args);}
    private static Object invoke(Class<?> type,Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(target,args);}catch(InvocationTargetException f){Throwable c=f.getCause();if(c instanceof Exception)throw(Exception)c;if(c instanceof Error)throw(Error)c;throw f;}}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static long[][] seats(List<Train> all)throws Exception{List<long[]> rows=new ArrayList<long[]>();for(Train train:all)for(long id:train.carIds){Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<row.length;i++)row[i]=seats[i].occupant;rows.add(row);}return rows.toArray(new long[rows.size()][]);}
    private static void same(List<Pose> a,List<Pose> b,String message){check(a!=null&&a.size()==b.size(),message);for(int i=0;i<a.size();i++)check(a.get(i).carId==b.get(i).carId&&a.get(i).x==b.get(i).x&&a.get(i).y==b.get(i).y&&a.get(i).rotation==b.get(i).rotation&&a.get(i).layer==b.get(i).layer,message);}
    private static void protectedPoints(RailNetwork n){Map<String,String> locks=new HashMap<String,String>();for(SignalSystem.Reservation reservation:n.signals().reservations())for(Map.Entry<String,Integer> point:reservation.junctionLocks().entrySet()){String previous=locks.put(point.getKey(),reservation.trainName);Node node=n.nodes.get(point.getKey());check(previous==null||previous.equals(reservation.trainName),"native opposed Y points cannot be reserved by two trains");check(reservation.trainName.equals(node.getAlignmentOwner())&&point.getValue()==node.getAlignmentMask(),"native opposed Y physical point alignment retains its exclusive owner");}}
    private static String status(RailNetwork n,List<Train> all){List<String> result=new ArrayList<String>();for(Train train:all)result.add(train.name+" "+train.direction+" "+train.status+" "+train.poses+" "+n.signals().trafficStatus(train.name));return result.toString();}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
