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

/** A returning cohort clears the Y before changing traction together. */
public final class YTurnbackSafetySelfTest {
    private static int assertions;
    private static long carId=98000000;
    private static String context="";
    private YTurnbackSafetySelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            boolean resident=!args[0].startsWith("alone");
            run(args[0].contains("restart"),args[0].contains("stop"),resident,args.length>1?Double.parseDouble(args[1]):30);
        } else {
            run(false,false,true,30);run(true,false,true,30);run(false,true,true,30);
            run(false,false,false,30);run(true,false,false,30);
            run(false,true,false,30);run(false,true,false,90);
        }
        System.out.println("YTurnbackSafetySelfTest: "+assertions+" assertions passed; mixed returning cohorts, twelve-metre point clearance, atomic no-spin pivots, old yield intent, route progress, restart and operator STOP.");
    }

    private static RailNetwork graph() throws Exception {
        Method fixture=YLoopRecoverySelfTest.class.getDeclaredMethod("graph",boolean.class);
        fixture.setAccessible(true);return (RailNetwork)fixture.invoke(null,false);
    }
    private static Train service(RailNetwork n,String name,String at,String facing,int cars,List<String> route,boolean backing) throws Exception {
        List<Long> ids=new ArrayList<Long>();for(int index=0;index<cars;index++)ids.add(carId++);
        Train train=n.createTrain(name,1,ids,at,facing);n.setRoute(name,route,5);
        if(backing)n.reverse(name);return train;
    }
    private static void run(boolean restart,boolean pause,boolean forwardResident,double pauseSeconds) throws Exception {
        RailNetwork n=graph();
        Train near=service(n,"Near","s1","s2",1,Arrays.asList("r20","n5"),true);
        Train far=service(n,"Far","s4","s5",2,Arrays.asList("n5","r20"),true);
        // The tail is still trying to reach its original yard destination.
        // A previous recovery reversed it, but did not alter its itinerary.
        Field yield=Train.class.getDeclaredField("recoveringTraffic");yield.setAccessible(true);yield.setBoolean(far,true);
        List<String> names=new ArrayList<String>(Arrays.asList(near.name,far.name)),backers=Arrays.asList(near.name,far.name);
        if(forwardResident)names.add(service(n,"Resident","r21","r20",1,Arrays.asList("n5","r20"),false).name);
        Method pivot=RailNetwork.class.getDeclaredMethod("restoreForwardAfterLoopEntry",Train.class);pivot.setAccessible(true);
        Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();
        Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();
        Map<String,Integer> cursor=new HashMap<String,Integer>(),visits=new LinkedHashMap<String,Integer>();
        Map<String,Double> arrival=new HashMap<String,Double>();
        for(String name:names) {
            Train t=n.trains.get(name);routes.put(name,new ArrayList<String>(t.route));cars.put(name,new ArrayList<Long>(t.carIds));
            cursor.put(name,t.routeIndex);visits.put(name,0);arrival.put(name,0d);n.resumeScheduled(name);
        }
        if(pause)n.stop(far.name);
        List<Pose> held=new ArrayList<Pose>(far.poses);
        boolean resumed=!pause,reloaded=false,initialPivot=false,sharedLoop=false,complete=false;
        for(int tick=1;tick<=18000&&!complete;tick++) {
            double seconds=tick*.1;
            context="restart="+restart+" pause="+pause+" resident="+forwardResident+" pauseSeconds="+pauseSeconds+" seconds="+seconds+": ";
            // Query the normal movement hook directly so its traction-only
            // operation can be distinguished from later movement in a poll.
            for(String name:backers) {
                Train t=n.trains.get(name);if(!t.powered||t.direction>=0)continue;
                Map<String,List<Pose>> poses=poses(n,names);Map<String,Integer> directions=directions(n,names);
                pivot.invoke(n,t);
                if(t.direction!=directions.get(name)) {
                    check(t.direction==1,"a completed turnback restores engine-leading traction");
                    for(String other:names)samePoses(poses.get(other),n.trains.get(other).poses,"a coordinated traction pivot cannot move or rotate any body");
                    check(clear(n,t,11.999),"a backing train must reach twelve metres of physical Y clearance before pivoting");
                    if(!initialPivot&&!pause)initialCohort(n,backers,11.999);
                    initialPivot=true;
                }
            }
            Map<String,List<Pose>> before=poses(n,names);Map<String,Integer> directions=directions(n,names);
            Map<String,String> target=new HashMap<String,String>();
            for(String name:names) {Train t=n.trains.get(name);target.put(name,t.route.get(t.routeIndex));}
            n.tick(.1);
            for(String name:names) {
                Train t=n.trains.get(name);boolean stopped=pause&&!resumed&&name.equals("Far");
                check(t.powered!=stopped&&t.automated&&t.route.equals(routes.get(name))&&t.carIds.equals(cars.get(name)),
                        "return coordination retains each itinerary, vehicle and operator power setting");
                for(int index=0;index<t.poses.size();index++) {
                    Pose a=before.get(name).get(index),b=t.poses.get(index);double distance=Math.hypot(b.x-a.x,b.y-a.y);
                    double turn=Math.abs((b.rotation-a.rotation+540)%360-180);
                    check(distance<=t.maxSpeed*.1+.011&&turn<90&&(distance>.00001||turn<.01),
                            "cohort recovery uses progressive track movement without teleporting or stationary spins");
                }
                if(!initialPivot&&backers.contains(name)&&directions.get(name)<0&&t.direction>0) {
                    // A pivot may become eligible inside the movement substep;
                    // another member can then make its first tiny forward step.
                    check(clear(n,t,11.97),"a substep turnback must retain twelve metres of point clearance");
                    if(!pause)initialCohort(n,backers,11.97);initialPivot=true;
                }
                if(t.routeIndex!=cursor.get(name)) {
                    Node station=n.nodes.get(target.get(name));boolean atStation=false;
                    for(Pose pose:t.poses)atStation|=pose.layer==station.layer&&Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                    check(atStation,"a cohort maneuver cannot manufacture a scheduled arrival");
                    visits.put(name,visits.get(name)+1);cursor.put(name,t.routeIndex);arrival.put(name,seconds);
                }
                if(stopped)samePoses(held,t.poses,"neither automatic recovery nor Clear traffic jams may move an operator-stopped returner");
            }
            for(int first=0;first<names.size();first++)for(int second=first+1;second<names.size();second++)
                for(Pose a:n.trains.get(names.get(first)).poses)for(Pose b:n.trains.get(names.get(second)).poses)
                    check(a.layer!=b.layer||Math.hypot(a.x-b.x,a.y-b.y)>=3.99,"return coordination preserves four-metre physical clearance");
            near=n.trains.get("Near");far=n.trains.get("Far");
            if(onRing(near)&&onRing(far))sharedLoop=true;
            if(restart&&!reloaded&&!initialPivot&&onRing(near)&&!onRing(far)) {
                Map<String,List<Pose>> saved=poses(n,names);Map<String,Integer> savedDirection=directions(n,names);
                n=reload(n);for(String name:names) {
                    Train t=n.trains.get(name);check(!t.powered&&t.direction==savedDirection.get(name),"restart retains the pending cohort's physical traction and awaits authorized resume");
                    samePoses(saved.get(name),t.poses,"restarting a partially entered cohort never moves or rotates it");n.resumeScheduled(name);
                }
                reloaded=true;
            }
            if(pause&&!resumed) {
                if(tick%50==0)n.resolveTrafficJams();
                if(seconds>=pauseSeconds) {n.resumeScheduled("Far");resumed=true;}
            }
            complete=seconds>=600;
            for(String name:names)if(visits.get(name)<4||seconds-arrival.get(name)>180)complete=false;
        }
        check(initialPivot&&sharedLoop&&(!restart||reloaded)&&complete,
                "returning cohorts must clear the stem, pivot safely and resume repeated real station stops: visits="+visits+" "+status(n,names));
    }
    private static void initialCohort(RailNetwork n,List<String> backers,double clearance) {
        for(String name:backers) {
            Train t=n.trains.get(name);check(t.powered&&t.direction==1,"all active backing members pivot atomically, including an old yield still targeting the yard: "+status(n,backers));
            check(clear(n,t,clearance),"every member, including every attached cart, must clear the Y pocket before the first pivot");
        }
    }
    private static boolean clear(RailNetwork n,Train train,double distance) {
        Node junction=n.nodes.get("Y");if(n.signalOccupiesNode(train,"Y")||n.signalOccupiesNode(train,"s1"))return false;
        for(Pose pose:train.poses)if(!railOnRing(pose.railName)||Math.hypot(pose.x-junction.worldX(),pose.y-junction.worldY())<distance)return false;
        return !train.poses.isEmpty();
    }
    private static boolean railOnRing(String name) {return name!=null&&name.matches("r[1-9][0-9]*");}
    private static boolean onRing(Train t) {for(Pose pose:t.poses)if(railOnRing(pose.railName))return true;return false;}
    private static Map<String,List<Pose>> poses(RailNetwork n,List<String> names) {
        Map<String,List<Pose>> result=new LinkedHashMap<String,List<Pose>>();for(String name:names)result.put(name,new ArrayList<Pose>(n.trains.get(name).poses));return result;
    }
    private static Map<String,Integer> directions(RailNetwork n,List<String> names) {
        Map<String,Integer> result=new LinkedHashMap<String,Integer>();for(String name:names)result.put(name,n.trains.get(name).direction);return result;
    }
    private static RailNetwork reload(RailNetwork n) throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-y-cohort-");Path file=folder.resolve("network.properties");
        try {n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
    private static void samePoses(List<Pose> before,List<Pose> after,String message) {
        check(before.size()==after.size(),message);for(int index=0;index<before.size();index++) {
            Pose a=before.get(index),b=after.get(index);check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);
        }
    }
    private static String status(RailNetwork n,List<String> names) {
        StringBuilder result=new StringBuilder();for(String name:names) {Train t=n.trains.get(name);result.append(name).append(" dir=").append(t.direction).append(" ").append(t.poses).append(' ').append(t.status).append("; ");}return result.toString();
    }
    private static void check(boolean okay,String message) {assertions++;if(!okay)throw new AssertionError(context+message);}
}
