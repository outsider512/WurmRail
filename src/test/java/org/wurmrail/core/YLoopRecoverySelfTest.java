package org.wurmrail.core;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** A single Y loop must admit a waiting stem train instead of endlessly yielding. */
public final class YLoopRecoverySelfTest {
    private static int assertions;
    private static long carId=97000000;
    private static int originX=100,originY=100;
    private YLoopRecoverySelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            boolean fairness=args.length>6&&args[6].equals("fair");
            run(Boolean.parseBoolean(args[0]),args.length>1?Integer.parseInt(args[1]):1,
                    args.length>2?Integer.parseInt(args[2]):1,args.length>3&&Boolean.parseBoolean(args[3]),
                    args.length>4&&Boolean.parseBoolean(args[4]),args.length>5&&Boolean.parseBoolean(args[5]),
                    fairness?.25:.1,fairness?3600:600,fairness);
        } else for(boolean mirror:new boolean[]{false,true}) {
            run(mirror,1,1,false,false,false);
            run(mirror,2,1,true,false,false);
            run(mirror,1,2,false,true,false);
            run(mirror,1,1,false,false,true);
            run(mirror,1,1,true,false,true);
            run(mirror,1,2,true,true,false,.25,3600,true);
        }
        System.out.println("YLoopRecoverySelfTest: "+assertions+" assertions passed; single-cycle Y with a branched stem, sustained route progress, loop followers, mixed consists, mirrored points, restart and operator STOP.");
    }

    /** Anonymous geometry of a real stalled Y railway: a 47-node ring, an
     * eight-edge stem, and five-edge terminal/holding branches at its far end.
     * Unlike a dumbbell there is only one cycle, so a bridge between two loops
     * is not available as a special-case dispatch boundary. */
    private static RailNetwork graph(boolean mirror) {
        RailNetwork n=new RailNetwork();String previous=point(n,null,"Y",0,0,mirror);
        for(int i=1;i<=10;i++)previous=point(n,previous,"r"+i,i,-i,mirror);
        for(int i=11;i<=14;i++)previous=point(n,previous,"r"+i,i,i-20,mirror);
        for(int i=15;i<=33;i++)previous=point(n,previous,"r"+i,14,i-20,mirror);
        previous=point(n,previous,"r34",13,13,mirror);
        for(int i=35;i<=46;i++)previous=point(n,previous,"r"+i,47-i,47-i,mirror);
        n.connect(previous,"Y");previous="Y";
        for(int i=1;i<=7;i++)previous=point(n,previous,"s"+i,-i,0,mirror);
        previous=point(n,previous,"B",-8,0,mirror);
        for(int i=1;i<=5;i++)previous=point(n,previous,"n"+i,-8,-i,mirror);
        previous="B";for(int i=1;i<=5;i++)previous=point(n,previous,"w"+i,-8-i,0,mirror);
        n.setJunctionAlignment("Y",(1<<n.signalSide("Y","r1"))|(1<<n.signalSide("Y","r46")));
        n.setJunctionAlignment("B",(1<<n.signalSide("B","s7"))|(1<<n.signalSide("B","n1")));
        for(String junction:Arrays.asList("Y","B"))for(String link:n.nodes.get(junction).links)
            n.signals().add(junction+"_"+link,junction,n.signalSide(junction,link),-10);
        n.setStation("r20",true,5);n.setStation("n5",true,5);
        check(n.nodes.size()==65,"the fixture is a single Y cycle and its branched stem, without another receiving loop");
        return n;
    }
    private static String point(RailNetwork n,String previous,String name,int x,int y,boolean mirror) {
        n.addNode(name,originX+(mirror?-x:x),originY+y,0);if(previous!=null)n.connect(previous,name);return name;
    }
    private static Train service(RailNetwork n,String name,String at,String facing,int cars,List<String> route) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<cars;i++)ids.add(carId++);
        Train train=n.createTrain(name,1,ids,at,facing);n.setRoute(name,route,5);return train;
    }
    private static void run(boolean mirror,int loopCars,int followers,boolean restart,boolean pause,boolean warm) throws Exception {
        run(mirror,loopCars,followers,restart,pause,warm,.1,600,false);
    }
    private static void run(boolean mirror,int loopCars,int followers,boolean restart,boolean pause,boolean warm,
                            double dt,double minimumSeconds,boolean fairness) throws Exception {
        originX=fairness?110:100;originY=fairness?80:100;
        RailNetwork n=graph(mirror);List<String> names=new ArrayList<String>();
        Train loop=service(n,"Loop","r21","r20",loopCars,Arrays.asList("n5","r20"));names.add(loop.name);
        // Old saves can already contain a yield running forward after an
        // unsuccessful previous reversal. Keep that intent rather than rely
        // on a clean newly-created train selecting a favorable direction.
        Field yielding=Train.class.getDeclaredField("recoveringTraffic");yielding.setAccessible(true);yielding.setBoolean(loop,true);
        for(int i=0;i<followers;i++)names.add(service(n,"Stem"+i,"s"+(i*3+1),i==0?"Y":"s"+(i*3),
                i==0&&followers>1?2:1,Arrays.asList("r20","n5")).name);
        if(warm) {
            // Released 2.15.2 reaches these exact two occupied approach poses
            // after thirty seconds and cannot move the stem service again.
            // Freeze that physical arrangement, not the old reservations.
            seedApproach(n,loop,Arrays.asList("r2","r1","Y"),8.035281374238561);
            seedApproach(n,n.trains.get("Stem0"),Arrays.asList("s2","s1","Y"),5.55);
        }
        String previous=null;for(int i=0;i<5;i++)previous=point(n,previous,"park"+i,-25+i,25,mirror);
        Train parked=n.createTrain("ManualParked",2,Arrays.asList(carId++,carId++),"park2","park3");
        List<Pose> parkedPoses=new ArrayList<Pose>(parked.poses);
        Map<String,List<String>> routes=new LinkedHashMap<String,List<String>>();
        Map<String,List<Long>> cars=new LinkedHashMap<String,List<Long>>();
        Map<String,Integer> visits=new LinkedHashMap<String,Integer>(),cursor=new HashMap<String,Integer>(),direction=new HashMap<String,Integer>();
        Map<String,Integer> reversals=new HashMap<String,Integer>();Map<String,Double> lastArrival=new HashMap<String,Double>();
        Map<String,Double> longestGap=new LinkedHashMap<String,Double>(),waitSince=new HashMap<String,Double>(),maxWait=new LinkedHashMap<String,Double>();
        int emergencyYields=0,plannedPivots=0;
        for(String name:names) {
            Train train=n.trains.get(name);routes.put(name,new ArrayList<String>(train.route));cars.put(name,new ArrayList<Long>(train.carIds));
            visits.put(name,0);cursor.put(name,train.routeIndex);direction.put(name,train.direction);reversals.put(name,0);lastArrival.put(name,0d);
            n.resumeScheduled(name);
        }
        boolean reloaded=false,paused=false,resumed=false,complete=false,sharedLoop=false;List<Pose> held=null;
        for(int tick=1;tick<=Math.ceil((fairness?3600:1800)/dt)&&!complete;tick++) {
            double seconds=tick*dt;
            Map<Long,Pose> before=new HashMap<Long,Pose>();Map<String,String> target=new HashMap<String,String>();
            for(String name:names) {Train train=n.trains.get(name);target.put(name,train.route.get(train.routeIndex));for(Pose pose:train.poses)before.put(pose.carId,pose);}
            n.tick(dt);
            for(String name:names) {
                Train train=n.trains.get(name);boolean heldByOperator=pause&&paused&&!resumed&&name.equals("Stem0");
                check(train.powered!=heldByOperator&&train.automated&&train.route.equals(routes.get(name))&&train.carIds.equals(cars.get(name)),
                        "Y recovery preserves every schedule, consist and operator power setting: "+name+" "+train.status);
                for(Pose pose:train.poses) {
                    Pose old=before.get(pose.carId);double moved=Math.hypot(pose.x-old.x,pose.y-old.y);
                    double turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                    check(moved<=train.maxSpeed*dt+.011&&turn<90&&(moved>.00001||turn<.01),
                            "Y correction moves real bodies along track without teleporting or stationary spins");
                }
                if(train.direction!=direction.get(name)) {
                    if(train.status.startsWith("Yielding to ")) {
                        emergencyYields++;
                        check(seconds<=180,"a cleared Y plan cannot settle into repeated emergency reversals after startup");
                    }
                    if(direction.get(name)<0&&train.direction>0&&n.trafficRecoveryStatus(name).startsWith("Turnback complete:")) {
                        plannedPivots++;Node junction=n.nodes.get("Y");
                        // A long poll may already consume a fresh, accelerating
                        // substep toward the points after the stationary pivot.
                        double margin=train.acceleration*dt*dt+.001;
                        for(Pose pose:train.poses)check(pose.railName.startsWith("r")
                                &&Math.hypot(pose.x-junction.worldX(),pose.y-junction.worldY())>=12-margin,
                                "planned Y forward traction waits for the whole consist to clear the points and twelve-metre turnback pocket");
                    }
                    direction.put(name,train.direction);reversals.put(name,reversals.get(name)+1);
                }
                if(train.routeIndex!=cursor.get(name)) {
                    Node stop=n.nodes.get(target.get(name));boolean atStop=false;
                    for(Pose pose:train.poses)atStop|=pose.layer==stop.layer&&Math.hypot(pose.x-stop.worldX(),pose.y-stop.worldY())<.51;
                    check(atStop,"a recovery circuit cannot manufacture a scheduled station visit");
                    visits.put(name,visits.get(name)+1);cursor.put(name,train.routeIndex);lastArrival.put(name,seconds);
                }
                if(heldByOperator)samePoses(held,train.poses,"Clear traffic jams cannot override a stopped stem train");
                double interval=seconds-lastArrival.get(name);
                longestGap.put(name,Math.max(longestGap.getOrDefault(name,0d),interval));
                if(train.powered&&train.speed<.00001&&train.dwellRemaining<=.00001&&train.passengerStopRail==null) {
                    double since=waitSince.getOrDefault(name,seconds);waitSince.put(name,since);
                    maxWait.put(name,Math.max(maxWait.getOrDefault(name,0d),seconds-since));
                } else waitSince.remove(name);
                if(fairness)check(interval<=240,"every running service must retain a bounded station interval instead of circling past an unfair yard queue: "+name+" interval="+interval+" visits="+visits);
            }
            for(int first=0;first<names.size();first++)for(int second=first+1;second<names.size();second++)
                for(Pose a:n.trains.get(names.get(first)).poses)for(Pose b:n.trains.get(names.get(second)).poses)
                    check(a.layer!=b.layer||Math.hypot(a.x-b.x,a.y-b.y)>=3.99,"Y recovery preserves four-metre physical headway");
            check(!n.trains.get(parked.name).powered,"recovery cannot start an unrelated manual train");
            samePoses(parkedPoses,n.trains.get(parked.name).poses,"manual train remains physically fixed during Y recovery");
            if(onRing(n.trains.get("Loop")))for(String name:names)if(!name.equals("Loop")&&onRing(n.trains.get(name)))sharedLoop=true;
            protectedPoints(n);
            if(restart&&!reloaded&&seconds>=40) {n=reload(n);for(String name:names)n.resumeScheduled(name);reloaded=true;}
            if(pause&&!paused&&seconds>=90) {Train train=n.trains.get("Stem0");n.stop(train.name);held=new ArrayList<Pose>(train.poses);paused=true;}
            if(pause&&paused&&!resumed) {
                if(tick%Math.max(1,(int)(5/dt))==0)n.resolveTrafficJams();
                if(seconds>=120) {n.resumeScheduled("Stem0");resumed=true;}
            }
            complete=seconds>=minimumSeconds;
            for(String name:names)if(visits.get(name)<4||seconds-lastArrival.get(name)>(fairness?240:180))complete=false;
        }
        check(complete,"all Y services must escape the endless yield and resume repeated real route stops; mirror="+mirror+" cars="+loopCars+" followers="+followers+" restart="+restart+" pause="+pause+" warm="+warm+" "+describe(n,names,visits,reversals));
        check(sharedLoop,"a compatible stem train can follow into an occupied Y loop so the receiving stem can clear");
        check(plannedPivots>0,"Y service uses a planned, whole-consist forward restoration after the terminal return");
        for(String name:names)check(reversals.get(name)<=visits.get(name)*3+8,
                "reversals make route progress rather than endlessly alternate around the Y loop: "+describe(n,names,visits,reversals));
        if(fairness)System.out.println("PASS sustained Y fairness: mirror="+mirror+" seconds="+minimumSeconds+" visits="+visits
                +" longestStationGap="+longestGap+" longestStationaryWait="+maxWait+" plannedPivots="+plannedPivots+" emergencyYields="+emergencyYields);
    }
    private static boolean onRing(Train train) {
        for(Pose pose:train.poses)if(pose.railName!=null&&pose.railName.matches("r[1-9][0-9]*"))return true;return false;
    }
    @SuppressWarnings("unchecked")
    private static void seedApproach(RailNetwork n,Train train,List<String> path,double distance) throws Exception {
        Field history=Train.class.getDeclaredField("path");history.setAccessible(true);
        List<String> data=(List<String>)history.get(train);data.clear();data.addAll(path);
        Field position=Train.class.getDeclaredField("locomotiveDistance");position.setAccessible(true);position.setDouble(train,distance);
        Field towards=Train.class.getDeclaredField("initialTowards");towards.setAccessible(true);towards.set(train,null);
        java.lang.reflect.Method refresh=RailNetwork.class.getDeclaredMethod("refresh",Train.class);refresh.setAccessible(true);refresh.invoke(n,train);
    }
    private static void protectedPoints(RailNetwork n) {
        Map<String,String> locks=new HashMap<String,String>();
        for(SignalSystem.Reservation reservation:n.signals().reservations())for(Map.Entry<String,Integer> point:reservation.junctionLocks().entrySet()) {
            String owner=locks.put(point.getKey(),reservation.trainName);Node node=n.nodes.get(point.getKey());
            check(owner==null||owner.equals(reservation.trainName),"different trains cannot simultaneously lock one Y junction");
            check(reservation.trainName.equals(node.getAlignmentOwner())&&node.getAlignmentMask()==point.getValue(),
                    "a protected junction retains its physical bidirectional alignment and owner");
        }
    }
    private static RailNetwork reload(RailNetwork n) throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-y-recovery-");Path file=folder.resolve("network.properties");
        try {n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
    private static void samePoses(List<Pose> before,List<Pose> after,String message) {
        check(before!=null&&before.size()==after.size(),message);
        for(int i=0;i<before.size();i++) {Pose a=before.get(i),b=after.get(i);check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);}
    }
    private static String describe(RailNetwork n,List<String> names,Map<String,Integer> visits,Map<String,Integer> reversals) {
        StringBuilder out=new StringBuilder("visits="+visits+" reversals="+reversals+" ");
        for(String name:names) {Train train=n.trains.get(name);out.append(name).append(" dir=").append(train.direction).append(' ').append(train.status).append(' ').append(train.poses).append(" traffic=").append(n.signals().trafficStatus(name)).append(';');}
        return out.toString();
    }
    private static void check(boolean okay,String message) {assertions++;if(!okay)throw new AssertionError(message);}
}
