package org.wurmrail.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** A station queue holds at its points when the returning owner has another clear arm. */
public final class StationQueueHoldSelfTest {
    private static int assertions;
    private static long carId=99700000;
    private static String context="";
    private StationQueueHoldSelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0)run(Boolean.parseBoolean(args[0]),args.length>1&&Boolean.parseBoolean(args[1]),
                args.length>2&&Boolean.parseBoolean(args[2]),args.length>3&&Boolean.parseBoolean(args[3]),
                args.length>4&&Boolean.parseBoolean(args[4]),args.length>5?Integer.parseInt(args[5]):1);
        else for(boolean mirror:new boolean[]{false,true}) {
            legacyCloseForwardReturn(mirror);
            run(mirror,false,false,false,false,1);
            run(mirror,true,true,false,false,2);
            run(mirror,true,false,true,false,2);
            run(mirror,true,false,false,true,1);
        }
        System.out.println("StationQueueHoldSelfTest: "+assertions+" assertions passed; safe station FIFO holds, necessary receiving-arm clearance circuits, mixed carts, mirrors, restart, operator STOP, real route visits and protected points.");
    }
    private static RailNetwork graph(boolean mirror,boolean requiredCircuit) throws Exception {
        Method graph=YLoopRecoverySelfTest.class.getDeclaredMethod("graph",boolean.class);graph.setAccessible(true);
        RailNetwork n=(RailNetwork)graph.invoke(null,mirror);
        if(!requiredCircuit) {
            Node branch=n.nodes.get("B");String previous="B";
            for(int index=1;index<=8;index++){String name="z"+index;n.addNode(name,branch.tileX,branch.tileY+index,0);n.connect(previous,name);previous=name;}
            n.signals().add("B_z1","B",n.signalSide("B","z1"),-10);
        }
        return n;
    }
    private static void legacyCloseForwardReturn(boolean mirror) throws Exception {
        context="legacy close forward return; mirror="+mirror+": ";RailNetwork n=graph(mirror,false);
        Train queued=service(n,"OldCloseWaiter",Arrays.asList("r2","r1","Y"),1,1);
        // Released saves could stop at the old half-tile red boundary, only
        // 3.278 metres from the points. Retain that exact real physical pose.
        Field distance=Train.class.getDeclaredField("locomotiveDistance");distance.setAccessible(true);distance.setDouble(queued,8.035281374238561);
        Method refresh=RailNetwork.class.getDeclaredMethod("refresh",Train.class);refresh.setAccessible(true);refresh.invoke(n,queued);
        Train returning=service(n,"ForwardReturn",Arrays.asList("s5","s4","s3"),1,1);
        n.setRoute(returning.name,Arrays.asList("r20","n5"),5);n.resumeScheduled(queued.name);
        List<Pose> held=new ArrayList<Pose>(queued.poses),owner=new ArrayList<Pose>(returning.poses);
        Node junction=n.nodes.get("Y");Pose front=queued.poses.get(0);
        check(!n.signalOccupiesNode(queued,"Y")&&Math.hypot(front.x-junction.worldX(),front.y-junction.worldY())<4.45,
                "the legacy body is outside the points but too close to leave safe return clearance");
        check("s1".equals(n.signals().traffic().preferredExit(queued.name,"Y","r1")),
                "a stopped branch owner alone cannot force a close waiter around again");
        n.resumeScheduled(returning.name);returning.dwellRemaining=5;
        check("s1".equals(n.signals().traffic().preferredExit(queued.name,"Y","r1")),
                "a dwelling owner does not force premature clearance circulation");
        returning.dwellRemaining=0;
        check("r46".equals(n.signals().traffic().preferredExit(queued.name,"Y","r1")),
                "an old close waiter must drain once an actual forward yard return needs the junction clear");
        SignalSystem.RequestResult clear=n.signals().request(queued.name,"Y_r1");
        check(clear.granted&&clear.reservation.path.equals(Arrays.asList("Y","r46")),
                "the necessary legacy-clearance circuit obtains its own protected authority");
        samePoses(held,queued.poses,"forecasting legacy clearance cannot teleport or rotate the close waiter");
        samePoses(owner,returning.poses,"forecasting legacy clearance cannot teleport or rotate the returning owner");
        check(queued.powered&&returning.powered&&queued.direction==1&&returning.direction==1,
                "a defensive clearance choice leaves both physical forward tractions and authorized power intact");
    }
    @SuppressWarnings("unchecked")
    private static Train service(RailNetwork n,String name,List<String> path,int atIndex,int cars) throws Exception {
        List<Long> ids=new ArrayList<Long>();for(int index=0;index<cars;index++)ids.add(carId++);
        Train train=n.createTrain(name,1,ids,path.get(atIndex),path.get(atIndex+1));n.setRoute(name,Arrays.asList("n5","r20"),5);
        Field history=Train.class.getDeclaredField("path");history.setAccessible(true);List<String> data=(List<String>)history.get(train);data.clear();data.addAll(path);
        double at=0;for(int index=1;index<=atIndex;index++){Node a=n.nodes.get(path.get(index-1)),b=n.nodes.get(path.get(index));at+=Math.hypot(a.worldX()-b.worldX(),a.worldY()-b.worldY());}
        Field distance=Train.class.getDeclaredField("locomotiveDistance");distance.setAccessible(true);distance.setDouble(train,at);
        Field towards=Train.class.getDeclaredField("initialTowards");towards.setAccessible(true);towards.set(train,null);
        Method refresh=RailNetwork.class.getDeclaredMethod("refresh",Train.class);refresh.setAccessible(true);refresh.invoke(n,train);return train;
    }
    private static void run(boolean mirror,boolean carts,boolean restart,boolean pause,boolean requiredCircuit,int followers) throws Exception {
        RailNetwork n=graph(mirror,requiredCircuit);List<String> names=new ArrayList<String>(),queues=new ArrayList<String>();
        names.add(service(n,"YardOwner",Arrays.asList("n2","n3","n4","n5"),2,carts?2:1).name);
        for(int index=0;index<followers;index++) {
            int at=4+index*4;String name="Queue"+index;
            names.add(service(n,name,Arrays.asList("r"+(at+2),"r"+(at+1),"r"+at,"r"+(at-1)),2,carts?2:1).name);queues.add(name);
        }
        Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();
        Map<String,Integer> cursor=new HashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>();Map<String,Double> lastArrival=new HashMap<String,Double>();
        Map<String,Boolean> reachedYard=new HashMap<String,Boolean>();List<String> yardOrder=new ArrayList<String>();
        for(String name:names){Train t=n.trains.get(name);routes.put(name,new ArrayList<String>(t.route));cars.put(name,new ArrayList<Long>(t.carIds));cursor.put(name,t.routeIndex);visits.put(name,0);lastArrival.put(name,0d);reachedYard.put(name,false);n.resumeScheduled(name);}
        boolean reloaded=false,paused=false,resumed=!pause,complete=false,circulated=false;double heldSeconds=0,pauseUntil=0;List<Pose> held=null;
        for(int tick=1;tick<=18000&&!complete;tick++) {
            double seconds=tick*.1;context="mirror="+mirror+" carts="+carts+" restart="+restart+" pause="+pause+" requiredCircuit="+requiredCircuit+" followers="+followers+" seconds="+seconds+": ";
            Map<String,List<Pose>> before=poses(n,names);Map<String,String> target=new HashMap<String,String>();
            for(String name:names){Train t=n.trains.get(name);target.put(name,t.route.get(t.routeIndex));}
            n.tick(.1);
            for(String name:names) {
                Train t=n.trains.get(name);boolean operatorStopped=paused&&!resumed&&name.equals("Queue0");
                check(t.powered!=operatorStopped&&t.automated&&t.route.equals(routes.get(name))&&t.carIds.equals(cars.get(name)),"station queue preserves vehicles, service and operator power: "+name+" "+t.status);
                for(int index=0;index<t.poses.size();index++) {
                    Pose old=before.get(name).get(index),now=t.poses.get(index);double moved=Math.hypot(now.x-old.x,now.y-old.y),turn=Math.abs((now.rotation-old.rotation+540)%360-180);
                    check(moved<=t.maxSpeed*.1+.011&&turn<90&&(moved>.00001||turn<.01),"station queuing cannot teleport, spin or exceed physical track speed");
                }
                if(t.routeIndex!=cursor.get(name)) {
                    Node station=n.nodes.get(target.get(name));boolean there=false;
                    for(Pose pose:t.poses)there|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                    check(there,"a station hold cannot manufacture a scheduled visit");visits.put(name,visits.get(name)+1);cursor.put(name,t.routeIndex);lastArrival.put(name,seconds);
                    if(station.name.equals("n5")&&!reachedYard.get(name)){reachedYard.put(name,true);yardOrder.add(name);}
                }
                if(queues.contains(name)&&!reachedYard.get(name))for(Pose pose:t.poses)if("r46".equals(pose.railName)) {
                    circulated=true;check(requiredCircuit,"a safe station waiter must hold at Y instead of taking an unnecessary loop: "+name+" "+t.status);
                }
                if(operatorStopped)samePoses(held,t.poses,"Clear traffic jams cannot override a stopped station waiter");
            }
            Train waiting=n.trains.get("Queue0");Node junction=n.nodes.get("Y");Pose front=waiting.poses.get(0);
            boolean atHold=!reachedYard.get(waiting.name)&&waiting.powered&&waiting.speed<.00001&&waiting.dwellRemaining==0
                    &&Math.hypot(front.x-junction.worldX(),front.y-junction.worldY())<16;
            if(atHold)heldSeconds+=.1;
            for(int a=0;a<names.size();a++)for(int b=a+1;b<names.size();b++)for(Pose one:n.trains.get(names.get(a)).poses)for(Pose two:n.trains.get(names.get(b)).poses)
                check(one.layer!=two.layer||Math.hypot(one.x-two.x,one.y-two.y)>=3.99,"held station queues retain four metres of physical train separation");
            points(n);
            if(restart&&!reloaded&&heldSeconds>=1) {
                Map<String,List<Pose>> saved=poses(n,names);n=reload(n);for(String name:names){samePoses(saved.get(name),n.trains.get(name).poses,"restart preserves the held queue's physical placement");n.resumeScheduled(name);}reloaded=true;
            }
            if(pause&&!paused&&heldSeconds>=1){n.stop("Queue0");held=new ArrayList<Pose>(n.trains.get("Queue0").poses);paused=true;pauseUntil=seconds+15;}
            if(paused&&!resumed){if(tick%50==0)n.resolveTrafficJams();if(seconds>=pauseUntil){n.resumeScheduled("Queue0");resumed=true;}}
            complete=seconds>=600;for(String name:names)if(visits.get(name)<4||seconds-lastArrival.get(name)>180)complete=false;
        }
        check(complete,"every held service must resume real station calls: visits="+visits+" "+status(n,names));
        check(requiredCircuit?circulated:heldSeconds>=1,"the queue must choose a safe stationary hold, or the genuinely required receiving-arm clearance circuit");
        check(!restart||reloaded,"restart case reaches a real safe hold before reloading");check(!pause||paused&&resumed,"operator STOP case reaches and resumes a real safe hold");
        if(!pause&&!requiredCircuit)check(yardOrder.equals(names),"first station turns follow physical owner and FIFO arrivals: "+yardOrder);
        System.out.println("PASS station queue hold: mirror="+mirror+" carts="+carts+" restart="+restart+" pause="+pause+" requiredCircuit="+requiredCircuit+" followers="+followers+" visits="+visits+" heldSeconds="+heldSeconds+" firstYardOrder="+yardOrder);
    }
    private static Map<String,List<Pose>> poses(RailNetwork n,List<String> names){Map<String,List<Pose>> result=new LinkedHashMap<String,List<Pose>>();for(String name:names)result.put(name,new ArrayList<Pose>(n.trains.get(name).poses));return result;}
    private static void points(RailNetwork n){Map<String,String> owners=new HashMap<String,String>();for(SignalSystem.Reservation r:n.signals().reservations())for(Map.Entry<String,Integer> lock:r.junctionLocks().entrySet()){Node point=n.nodes.get(lock.getKey());String old=owners.put(lock.getKey(),r.trainName);check(old==null||old.equals(r.trainName),"station queues cannot give two trains the same points");check(r.trainName.equals(point.getAlignmentOwner())&&point.getAlignmentMask()==lock.getValue(),"held queues preserve physical point alignment and ownership");}}
    private static RailNetwork reload(RailNetwork n) throws Exception{Path dir=Files.createTempDirectory("wurmrail-station-hold-");Path file=dir.resolve("network.properties");try{n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}}
    private static void samePoses(List<Pose> a,List<Pose> b,String message){check(a!=null&&a.size()==b.size(),message);for(int i=0;i<a.size();i++){Pose x=a.get(i),y=b.get(i);check(x.carId==y.carId&&x.x==y.x&&x.y==y.y&&x.rotation==y.rotation&&x.layer==y.layer,message);}}
    private static String status(RailNetwork n,List<String> names){StringBuilder out=new StringBuilder();for(String name:names){Train t=n.trains.get(name);out.append(name).append(' ').append(t.poses).append(" dir=").append(t.direction).append(' ').append(t.status).append(';');}return out.toString();}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(context+message);}
}
