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

/** Two physical opponents in one Y ring must clear its occupied entrance together. */
public final class OpposedYLoopSelfTest {
    private static int assertions;
    private static long carId=99000000;
    private static String context="";
    private OpposedYLoopSelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0)run(Boolean.parseBoolean(args[0]),args.length>1&&Boolean.parseBoolean(args[1]),
                args.length>2&&Boolean.parseBoolean(args[2]),args.length>3&&Boolean.parseBoolean(args[3]),args.length>4&&Boolean.parseBoolean(args[4]),args.length>5&&Boolean.parseBoolean(args[5]));
        else for(boolean mirror:new boolean[]{false,true}) {
            run(mirror,false,false,false,false,false);
            run(mirror,true,true,false,false,false);
            run(mirror,true,false,true,false,false);
            run(mirror,false,false,false,true,false);
            run(mirror,true,true,false,true,false);
            run(mirror,true,false,true,true,false);
            run(mirror,true,true,true,false,true);
        }
        System.out.println("OpposedYLoopSelfTest: "+assertions+" assertions passed; physical loop opponents, occupied Y stem, coordinated clearance, real station progress, mixed carts, mirror, restart and operator STOP.");
    }

    private static RailNetwork graph(boolean mirror,boolean heavy) throws Exception {
        for(String field:Arrays.asList("originX","originY")) {
            Field coordinate=YLoopRecoverySelfTest.class.getDeclaredField(field);coordinate.setAccessible(true);
            coordinate.setInt(null,heavy?(field.equals("originX")?110:80):100);
        }
        Method fixture=YLoopRecoverySelfTest.class.getDeclaredMethod("graph",boolean.class);
        fixture.setAccessible(true);return (RailNetwork)fixture.invoke(null,mirror);
    }
    private static Train service(RailNetwork n,String name,List<String> path,int cars,List<String> route) throws Exception {
        return service(n,name,path,cars,route,1);
    }
    private static Train service(RailNetwork n,String name,List<String> path,int cars,List<String> route,int atIndex) throws Exception {
        List<Long> ids=new ArrayList<Long>();for(int index=0;index<cars;index++)ids.add(carId++);
        Train t=n.createTrain(name,1,ids,path.get(atIndex),path.get(atIndex+1));n.setRoute(name,route,5);
        double at=0;for(int index=1;index<=atIndex;index++) {
            Node before=n.nodes.get(path.get(index-1)),after=n.nodes.get(path.get(index));
            at+=Math.hypot(before.worldX()-after.worldX(),before.worldY()-after.worldY());
        }
        seed(n,t,path,at);return t;
    }
    private static void seed(RailNetwork n,Train t,List<String> path,double at) throws Exception {
        // Use established histories so Start's bare-engine placement heuristic
        // cannot repair the deliberately opposed physical orientations for us.
        Field history=Train.class.getDeclaredField("path");history.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> data=(List<String>)history.get(t);data.clear();data.addAll(path);
        Field distance=Train.class.getDeclaredField("locomotiveDistance");distance.setAccessible(true);
        Node first=n.nodes.get(path.get(0)),second=n.nodes.get(path.get(1));
        distance.setDouble(t,at<0?Math.hypot(first.worldX()-second.worldX(),first.worldY()-second.worldY()):at);
        Field towards=Train.class.getDeclaredField("initialTowards");towards.setAccessible(true);towards.set(t,null);
        Method refresh=RailNetwork.class.getDeclaredMethod("refresh",Train.class);refresh.setAccessible(true);refresh.invoke(n,t);
    }
    private static void run(boolean mirror,boolean carts,boolean restart,boolean pause,boolean warm,boolean heavy) throws Exception {
        RailNetwork n=graph(mirror,heavy);
        if(warm||heavy) {
            // The later world added a south backup lane to the outer branch;
            // preserve that four-way topology as well as the old saved poses.
            Node branch=n.nodes.get("B");String previous="B";
            for(int i=1;i<=(heavy?8:4);i++){String name="z"+i;n.addNode(name,branch.tileX,branch.tileY+i,branch.layer);n.connect(previous,name);previous=name;}
            n.signals().add("B_z1","B",n.signalSide("B","z1"),-10);
        }
        Train stem=heavy?service(n,"StemWaiting",Arrays.asList("s3","s2","s1","Y"),2,Arrays.asList("r20","n5"),2):null;
        Train a=service(n,"LoopClockwise",heavy?Arrays.asList("r15","r16","r17","r18"):warm?Arrays.asList("r7","r6","r5"):Arrays.asList("r16","r17","r18"),carts?2:1,
                warm?Arrays.asList("r20","n5"):Arrays.asList("n5","r20"),heavy?2:1);
        if(warm){seed(n,a,Arrays.asList("r7","r6","r5"),8.035281374238561);a.routeIndex=1;}
        Train b=service(n,"LoopOpposed",heavy?Arrays.asList("r23","r22","r21","r20","r19"):warm?Arrays.asList("r6","r7","r8"):Arrays.asList("r21","r20","r19"),heavy?3:1,Arrays.asList("n5","r20"),heavy?3:1);
        if(!heavy)stem=service(n,"StemWaiting",Arrays.asList("s2","s1","Y"),carts?2:1,Arrays.asList("r20","n5"));
        if(warm) {
            seed(n,b,Arrays.asList("r6","r7","r8"),6.108118270594552);n.reverse(b.name);
            Field yielding=Train.class.getDeclaredField("recoveringTraffic");yielding.setAccessible(true);yielding.setBoolean(b,true);
            n.setRoute(stem.name,Arrays.asList("n5","r20"),5);stem.routeIndex=1;
            seed(n,stem,Arrays.asList("s3","s2","s1"),5.55);
        }
        List<String> names=Arrays.asList(a.name,b.name,stem.name);
        Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();
        Map<String,Integer> cursor=new HashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>(),direction=new HashMap<String,Integer>(),flips=new LinkedHashMap<String,Integer>();
        Map<String,Double> lastArrival=new HashMap<String,Double>();
        for(String name:names) {
            Train t=n.trains.get(name);routes.put(name,new ArrayList<String>(t.route));cars.put(name,new ArrayList<Long>(t.carIds));
            cursor.put(name,t.routeIndex);visits.put(name,0);direction.put(name,t.direction);flips.put(name,0);lastArrival.put(name,0d);n.resumeScheduled(name);
        }
        String pausedName=heavy?stem.name:b.name;boolean paused=pause&&!heavy;
        if(paused)n.stop(pausedName);
        List<Pose> held=paused?new ArrayList<Pose>(n.trains.get(pausedName).poses):null;
        boolean resumed=!pause,reloaded=false,complete=false,stemEnteredOccupiedRing=false;double dt=heavy?.25:.1;
        for(int tick=1;tick<=Math.ceil(1800/dt)&&!complete;tick++) {
            double seconds=tick*dt;context="mirror="+mirror+" carts="+carts+" restart="+restart+" pause="+pause+" warm="+warm+" heavy="+heavy+" seconds="+seconds+": ";
            Map<String,List<Pose>> before=poses(n,names);Map<String,String> target=new HashMap<String,String>();
            for(String name:names){Train t=n.trains.get(name);target.put(name,t.route.get(t.routeIndex));}
            n.tick(dt);
            for(String name:names) {
                Train t=n.trains.get(name);boolean operatorStopped=paused&&!resumed&&name.equals(pausedName);
                check(t.powered!=operatorStopped&&t.automated&&t.route.equals(routes.get(name))&&t.carIds.equals(cars.get(name)),
                        "coordinated loop clearance preserves schedules, vehicles and operator power settings: "+name+" "+t.status);
                for(int index=0;index<t.poses.size();index++) {
                    Pose old=before.get(name).get(index),now=t.poses.get(index);double moved=Math.hypot(now.x-old.x,now.y-old.y);
                    double turn=Math.abs((now.rotation-old.rotation+540)%360-180);
                    check(moved<=t.maxSpeed*dt+.011&&turn<90&&(moved>.00001||turn<.01),"loop recovery follows physical rails without teleporting or stationary spins");
                }
                if(t.direction!=direction.get(name)){flips.put(name,flips.get(name)+1);direction.put(name,t.direction);}
                if(t.routeIndex!=cursor.get(name)) {
                    Node station=n.nodes.get(target.get(name));boolean atStation=false;
                    for(Pose pose:t.poses)atStation|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                    check(atStation,"a coordinated clearance never manufactures a scheduled station arrival");
                    visits.put(name,visits.get(name)+1);cursor.put(name,t.routeIndex);lastArrival.put(name,seconds);
                }
                if(operatorStopped)samePoses(held,t.poses,"Clear traffic jams cannot move the operator-stopped loop opponent");
            }
            for(int first=0;first<names.size();first++)for(int second=first+1;second<names.size();second++)
                for(Pose x:n.trains.get(names.get(first)).poses)for(Pose y:n.trains.get(names.get(second)).poses)
                    check(x.layer!=y.layer||Math.hypot(x.x-y.x,x.y-y.y)>=3.99,"opposed loop recovery preserves four-metre physical separation");
            protectedPoints(n);
            if(onRing(n.trains.get(stem.name))&&(onRing(n.trains.get(a.name))||onRing(n.trains.get(b.name))))stemEnteredOccupiedRing=true;
            if(!heavy&&tick==50)n.resolveTrafficJams();
            boolean recovering=false;for(String name:names)recovering|=!n.trafficRecoveryStatus(name).isEmpty()||n.trains.get(name).direction<0;
            if(restart&&!reloaded&&(heavy?seconds>=8&&(recovering||seconds>=40):seconds>=30)) {
                Map<String,List<Pose>> saved=poses(n,names);n=reload(n);
                for(String name:names){samePoses(saved.get(name),n.trains.get(name).poses,"reload must retain every physical body");if(!(paused&&!resumed&&name.equals(pausedName)))n.resumeScheduled(name);}
                reloaded=true;
            }
            if(pause&&!paused&&seconds>=90){n.stop(pausedName);held=new ArrayList<Pose>(n.trains.get(pausedName).poses);paused=true;}
            if(paused&&!resumed) {
                if(tick%Math.max(1,(int)(5/dt))==0)n.resolveTrafficJams();
                if(seconds>=(heavy?120:60)){n.resumeScheduled(pausedName);resumed=true;if(!heavy)n.resolveTrafficJams();}
            }
            complete=seconds>=600;
            for(String name:names)if(visits.get(name)<4||seconds-lastArrival.get(name)>180)complete=false;
        }
        check(complete,"every train must leave the initial opposed-loop jam and keep completing real station calls: visits="+visits+" flips="+flips+" "+status(n,names));
        check(stemEnteredOccupiedRing,"the blocked stem train must join the occupied loop rather than wait for the ring to empty");
        for(String name:names)check(flips.get(name)<=visits.get(name)*3+8,"coordinated loop recovery cannot repeatedly reverse without route progress: "+flips);
        System.out.println("PASS opposed Y: mirror="+mirror+" carts="+carts+" restart="+restart+" pause="+pause+" warm="+warm+" heavy="+heavy+" visits="+visits+" tractionChanges="+flips);
    }
    private static boolean onRing(Train t){for(Pose p:t.poses)if(p.railName!=null&&p.railName.matches("r[1-9][0-9]*"))return true;return false;}
    private static Map<String,List<Pose>> poses(RailNetwork n,List<String> names){Map<String,List<Pose>> result=new LinkedHashMap<String,List<Pose>>();for(String name:names)result.put(name,new ArrayList<Pose>(n.trains.get(name).poses));return result;}
    private static void protectedPoints(RailNetwork n) {
        Map<String,String> owners=new HashMap<String,String>();
        for(SignalSystem.Reservation reservation:n.signals().reservations())for(Map.Entry<String,Integer> lock:reservation.junctionLocks().entrySet()) {
            Node point=n.nodes.get(lock.getKey());String old=owners.put(lock.getKey(),reservation.trainName);
            check(old==null||old.equals(reservation.trainName),"opposing trains cannot lock one physical junction simultaneously");
            check(reservation.trainName.equals(point.getAlignmentOwner())&&point.getAlignmentMask()==lock.getValue(),"reserved points preserve their bidirectional physical alignment and owner");
        }
    }
    private static RailNetwork reload(RailNetwork n) throws Exception {
        Path dir=Files.createTempDirectory("wurmrail-opposed-y-");Path file=dir.resolve("network.properties");
        try{n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
    }
    private static void samePoses(List<Pose> before,List<Pose> after,String message){check(before.size()==after.size(),message);for(int i=0;i<before.size();i++){Pose a=before.get(i),b=after.get(i);check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);}}
    private static String status(RailNetwork n,List<String> names){StringBuilder out=new StringBuilder();for(String name:names){Train t=n.trains.get(name);out.append(name).append(" dir=").append(t.direction).append(" ").append(t.poses).append(" ").append(t.status).append(" traffic=").append(n.signals().trafficStatus(name)).append(';');}return out.toString();}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(context+message);}
}
