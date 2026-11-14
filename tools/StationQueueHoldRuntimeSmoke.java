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

/** Waiting outside a station branch is preferable to an unnecessary ring circuit. */
public final class StationQueueHoldRuntimeSmoke {
    private static int assertions;
    private StationQueueHoldRuntimeSmoke() { }
    public static void run() throws Exception {
        runCase(false,true,2,3,1,true,false,.1);
        runCase(true,true,3,2,2,true,true,.25);
        runCase(false,false,1,2,1,false,false,.1);
        System.out.println("PASS native station queue holding: FIFO approach waits instead of unnecessary circuits, safe opposite-arm return, mandatory-circulation fallback, mixed carts, mirrored points, waiting restart, operator STOP, repeated real station calls, native guards, seats and cargo ("+assertions+" assertions)");
    }
    private static void runCase(boolean mirror,boolean backup,int ownerCars,int frontCars,int followerCars,boolean restart,boolean pause,double dt)throws Exception {
        String tag="station-queue-"+mirror+"-"+backup+"-"+ownerCars+"-"+frontCars;
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998511104L+(mirror?256:0)+(backup?512:0),true,2);
        RailNetwork net=new RailNetwork();List<Item> vehicles=new ArrayList<Item>();
        try {
            graph(net,actor,mirror,backup);
            Train owner=vehicle(net,actor,vehicles,"StationOwner","n4","n5",ownerCars,rear("n",3,-1,ownerCars));
            net.setRoute(owner.name,Arrays.asList("n5","r20"),5);
            List<String> ownerPath=new ArrayList<String>();for(int i=5-ownerCars;i<=5;i++)ownerPath.add("n"+i);
            seed(net,owner,ownerPath,4*(ownerCars-1));
            Train front=vehicle(net,actor,vehicles,"QueueFirst","r4","r3",frontCars,rear("r",5,1,frontCars));
            net.setRoute(front.name,Arrays.asList("n5","r20"),5);seed(net,front,Arrays.asList("r7","r6","r5","r4","r3"),12*Math.sqrt(2));
            Train follower=vehicle(net,actor,vehicles,"QueueFollowing","r8","r7",followerCars,rear("r",9,1,followerCars));
            net.setRoute(follower.name,Arrays.asList("n5","r20"),5);seed(net,follower,Arrays.asList("r10","r9","r8","r7"),8*Math.sqrt(2));
            List<String> names=Arrays.asList(owner.name,front.name,follower.name);List<Train> all=trains(net,names);
            Item holder=Items.getItem(front.carIds.get(front.carIds.size()-1));Item cargo=ItemFactory.createItem(ItemList.plank,87f,"Native station queue fixture");
            check(holder.insertItem(cargo),"native station queue wagon accepts a real cargo item");long holderId=holder.getWurmId();
            WurmRailRuntime runtime=bind(net,tag);long[][] initialSeats=seats(all);
            Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();
            Map<String,Integer> cursor=new LinkedHashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>();Map<String,Double> last=new HashMap<String,Double>();
            for(Train train:all){routes.put(train.name,new ArrayList<String>(train.route));cars.put(train.name,new ArrayList<Long>(train.carIds));cursor.put(train.name,train.routeIndex);visits.put(train.name,0);last.put(train.name,0d);net.resumeScheduled(train.name);}
            boolean frontCalled=false,followerCalled=false,circulated=false,reloaded=false,paused=false,resumed=false,complete=false;
            double waiting=0,pausedAt=-1,restartAt=-1;List<Pose> stopped=null;
            for(int step=1;step<=Math.ceil(1800/dt)&&!complete;step++) {
                double seconds=step*dt;Map<String,String> target=new HashMap<String,String>();for(Train train:all)target.put(train.name,train.route.get(train.routeIndex));
                tick(runtime,net,all,dt);
                front=net.trains.get("QueueFirst");
                for(Train train:all) {
                    boolean operatorStopped=pause&&paused&&!resumed&&train.name.equals("QueueFirst");
                    check(train.powered!=operatorStopped&&train.automated&&train.route.equals(routes.get(train.name))&&train.carIds.equals(cars.get(train.name)),"native station queue preserves power intent, schedule and consist: "+train.name+" "+train.status);
                    if(operatorStopped)same(stopped,train.poses,"Clear cannot move an operator-stopped native station queue");
                    if(train.routeIndex!=cursor.get(train.name)) {
                        Node station=net.nodes.get(target.get(train.name));boolean physical=false;
                        for(Pose pose:train.poses)physical|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                        check(physical,"native queue release cannot invent a station call");
                        visits.put(train.name,visits.get(train.name)+1);cursor.put(train.name,train.routeIndex);last.put(train.name,seconds);
                        if(target.get(train.name).equals("n5")) {
                            if(train.name.equals("QueueFirst"))frontCalled=true;
                            if(train.name.equals("QueueFollowing")){check(frontCalled,"the following native train cannot overtake the first station queue place");followerCalled=true;}
                        }
                    }
                }
                if(!frontCalled) {
                    boolean otherArm=false;for(Pose pose:front.poses)otherArm|=pose.railName!=null&&pose.railName.matches("r(3[5-9]|4[0-6])");
                    if(otherArm)circulated=true;
                    if(backup)check(!otherArm,"a native train waiting for the station must hold at the Y when the returning owner has a clear opposite arm: "+front.status+" "+front.poses);
                    Node junction=net.nodes.get("Y");Pose leading=front.poses.get(0);
                    if(front.powered&&front.speed<.000001&&front.dwellRemaining<=0&&leading.railName!=null&&leading.railName.matches("r[1-4]")&&Math.hypot(leading.x-junction.worldX(),leading.y-junction.worldY())<=25)waiting+=dt;
                }
                if(backup&&!followerCalled)for(Pose pose:net.trains.get("QueueFollowing").poses)
                    check(pose.railName==null||!pose.railName.matches("r(3[5-9]|4[0-6])"),"the following native station queue also holds behind the first train instead of making an unnecessary circuit");
                protectedPoints(net);
                if(restart&&!reloaded&&waiting>=1) {
                    Map<Long,Pose> before=new HashMap<Long,Pose>();for(Train train:all)for(Pose pose:train.poses)before.put(pose.carId,pose);
                    net.movementGuard=null;net=(RailNetwork)helper("reload",new Class<?>[]{RailNetwork.class,String.class},net,tag+"-held");all=trains(net,names);runtime=bind(net,tag+"-restart");
                    for(Train train:all){for(Pose pose:train.poses){Pose old=before.get(pose.carId);check(old.x==pose.x&&old.y==pose.y&&old.rotation==pose.rotation&&old.layer==pose.layer,"waiting native queue restart retains every exact body pose");}net.resumeScheduled(train.name);}
                    check(Arrays.deepEquals(initialSeats,seats(all)),"a waiting queue restart retains native passenger socket occupants");reloaded=true;restartAt=seconds;
                }
                if(pause&&!paused&&waiting>=1&&(!restart||reloaded)) {front=net.trains.get("QueueFirst");net.stop(front.name);stopped=new ArrayList<Pose>(front.poses);paused=true;pausedAt=seconds;}
                if(pause&&paused&&!resumed) {
                    if(step%Math.max(1,(int)(5/dt))==0)net.resolveTrafficJams();
                    if(seconds-pausedAt>=10){net.resumeScheduled("QueueFirst");resumed=true;}
                }
                complete=seconds>=600;
                for(String name:names)if(visits.get(name)<4||seconds-last.get(name)>180)complete=false;
            }
            check(complete&&frontCalled&&followerCalled&&(!restart||reloaded)&&(!pause||resumed),"native station queue must release in order and every service must keep making real calls: "+visits+" "+status(net,all));
            check(backup?waiting>=1:circulated,"native station holding waits with a clear return arm and still circulates when the owner needs that occupied arm");
            check(Arrays.deepEquals(initialSeats,seats(all))&&cargo.getParentId()==holderId&&cargo.getQualityLevel()==87f,"station queuing, real guarded movement, restart and STOP preserve native seats and the exact wagon cargo item");
            System.out.println("PASS native station queue case "+tag+" visits="+visits+" initialWaitingSeconds="+waiting+" extraInitialCircuit="+circulated+" waitingRestartAt="+restartAt);
        } finally {
            net.movementGuard=null;for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:vehicles)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            Set<Long> markers=new HashSet<Long>();for(Node node:net.nodes.values())markers.add(node.markerId);
            for(Item item:Items.getAllItems())if(markers.contains(item.getData())&&(WurmRailWorld.isSignal(item)||item.getTemplateId()==WurmRailWorld.junctionAlignmentTemplateId))Items.destroyItem(item.getWurmId());
            for(long marker:markers)if(Items.getItemOptional(marker).isPresent())Items.destroyItem(marker);
            if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());
        }
    }
    private static void graph(RailNetwork net,Creature actor,boolean mirror,boolean backup)throws Exception {
        if(backup)invoke(OpposedYLoopRuntimeSmoke.class,null,"graph",new Class<?>[]{RailNetwork.class,Creature.class,boolean.class,int.class},net,actor,mirror,8);
        else invoke(YLoopRuntimeSmoke.class,null,"graph",new Class<?>[]{RailNetwork.class,Creature.class,boolean.class},net,actor,mirror);
    }
    private static String[] rear(String prefix,int first,int delta,int count){String[] names=new String[count-1];for(int i=0;i<names.length;i++)names[i]=prefix+(first+delta*i);return names;}
    private static Train vehicle(RailNetwork net,Creature actor,List<Item> items,String name,String at,String toward,int cars,String[] rear)throws Exception{return(Train)invoke(YLoopRuntimeSmoke.class,null,"vehicle",new Class<?>[]{RailNetwork.class,Creature.class,List.class,String.class,String.class,String.class,int.class,String[].class},net,actor,items,name,at,toward,cars,rear);}
    private static void seed(RailNetwork net,Train train,List<String> path,double distance)throws Exception{invoke(YLoopRuntimeSmoke.class,null,"seed",new Class<?>[]{RailNetwork.class,Train.class,List.class,double.class},net,train,path,distance);}
    private static List<Train> trains(RailNetwork net,List<String> names){List<Train> result=new ArrayList<Train>();for(String name:names)result.add(net.trains.get(name));return result;}
    private static WurmRailRuntime bind(RailNetwork net,String tag)throws Exception{return(WurmRailRuntime)helper("bind",new Class<?>[]{RailNetwork.class,String.class},net,tag);}
    private static void tick(WurmRailRuntime runtime,RailNetwork net,List<Train> all,double dt)throws Exception{helper("tick",new Class<?>[]{WurmRailRuntime.class,RailNetwork.class,List.class,double.class},runtime,net,all,dt);}
    private static Object helper(String name,Class<?>[] types,Object...args)throws Exception{return invoke(JamRecoveryRuntimeSmoke.class,null,name,types,args);}
    private static Object invoke(Class<?> type,Object target,String name,Class<?>[] types,Object...args)throws Exception{Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw failure;}}
    private static long[][] seats(List<Train> all)throws Exception{List<long[]> rows=new ArrayList<long[]>();for(Train train:all)for(long id:train.carIds){Seat[] seats=Vehicles.getVehicle(Items.getItem(id)).seats;long[] row=new long[seats.length];for(int i=0;i<row.length;i++)row[i]=seats[i].occupant;rows.add(row);}return rows.toArray(new long[rows.size()][]);}
    private static void same(List<Pose> a,List<Pose> b,String message){check(a!=null&&a.size()==b.size(),message);for(int i=0;i<a.size();i++)check(a.get(i).carId==b.get(i).carId&&a.get(i).x==b.get(i).x&&a.get(i).y==b.get(i).y&&a.get(i).rotation==b.get(i).rotation&&a.get(i).layer==b.get(i).layer,message);}
    private static void protectedPoints(RailNetwork net){Map<String,String> locks=new HashMap<String,String>();for(SignalSystem.Reservation reservation:net.signals().reservations())for(Map.Entry<String,Integer> point:reservation.junctionLocks().entrySet()){String previous=locks.put(point.getKey(),reservation.trainName);Node node=net.nodes.get(point.getKey());check(previous==null||previous.equals(reservation.trainName),"station queue cannot share physical point ownership between trains");check(reservation.trainName.equals(node.getAlignmentOwner())&&point.getValue()==node.getAlignmentMask(),"station queue preserves the physical bidirectional alignment and reservation owner");}}
    private static String status(RailNetwork net,List<Train> all){List<String> result=new ArrayList<String>();for(Train train:all)result.add(train.name+" "+train.direction+" "+train.status+" "+train.poses+" "+net.signals().trafficStatus(train.name));return result.toString();}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
