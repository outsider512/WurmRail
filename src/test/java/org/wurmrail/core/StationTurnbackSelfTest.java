package org.wurmrail.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** A pull-in platform uses its adjacent reversing tail, rather than a remote loop. */
public final class StationTurnbackSelfTest {
    private static int assertions;
    private static long carId=99000000;
    private static String context="";
    private StationTurnbackSelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>2&&args[2].equals("sparse"))runSparse(Boolean.parseBoolean(args[0]));
        else if(args.length>0)run(Boolean.parseBoolean(args[0]),Integer.parseInt(args[1]),
                args.length>2?args[2]:"normal");
        else for(boolean mirror:new boolean[]{false,true}) {
            run(mirror,1,"normal");run(mirror,2,"normal");run(mirror,3,"normal");
            run(mirror,3,"restart");run(mirror,2,"stop");
            run(mirror,3,"short");run(mirror,2,"occupied");
            run(mirror,2,"busy");
            runSparse(mirror);
        }
        System.out.println("StationTurnbackSelfTest: "+assertions+" assertions passed; nearest pull-in station tail, whole-consist clearance, no spins, occupied/short alternatives, busy receiving space, sparse-block point transfer with retained resources, restart and operator STOP.");
    }
    /** No cyclic component and no signal at the points: a pivot remains inside
     * the old broad physical signal block and must safely transfer its own lock. */
    private static void runSparse(boolean mirror) throws Exception {
        RailNetwork n=new RailNetwork();point(n,null,"J",0,0,mirror);String previous="J";
        for(int i=1;i<=20;i++)previous=point(n,previous,"e"+i,i,0,mirror);
        previous="J";for(int i=1;i<=20;i++)previous=point(n,previous,"w"+i,-i,0,mirror);
        previous="J";for(int i=1;i<=10;i++)previous=point(n,previous,"n"+i,0,-i,mirror);
        for(String rail:Arrays.asList("e4","w4"))for(String neighbor:n.nodes.get(rail).links)
            n.signals().add(rail+"_"+neighbor,rail,n.signalSide(rail,neighbor),-10);
        for(String neighbor:n.nodes.get("n3").links)n.signals().add("n3_"+neighbor,"n3",n.signalSide("n3",neighbor),-10);
        List<String> itinerary=Arrays.asList("e16","n10","w8");for(String station:itinerary)n.setStation(station,true,1);
        Train train=n.createTrain("Sparse",1,Arrays.asList(carId++),"n4","n3");n.setRoute(train.name,itinerary,1);n.resumeScheduled(train.name);
        int visits=0,cursor=train.routeIndex;boolean backed=false,pivot=false,transferred=false,reloaded=false,paused=false,resumed=false;double pausedAt=0;
        List<Pose> held=null;
        for(int step=1;step<=9000&&visits<3;step++) {
            double seconds=step*.1;context="sparse mirror="+mirror+" seconds="+seconds+": ";
            List<Pose> before=new ArrayList<Pose>(train.poses);int traction=train.direction;String target=train.route.get(train.routeIndex);
            SignalSystem.Reservation retained=null;Set<String> resources=null;
            if(visits==1&&traction<0)for(SignalSystem.Reservation reservation:n.signals().reservations())
                if(reservation.trainName.equals(train.name)&&reservation.junctionLocks().containsKey("J")) {retained=reservation;resources=resources(reservation);}
            n.tick(.1);train=n.trains.get("Sparse");
            check(train.powered!=((paused&&!resumed))&&train.automated&&train.route.equals(itinerary),"sparse signals preserve the route and operator power setting: "+train.status);
            for(int i=0;i<train.poses.size();i++) {
                Pose a=before.get(i),b=train.poses.get(i);double distance=Math.hypot(a.x-b.x,a.y-b.y),turn=Math.abs((b.rotation-a.rotation+540)%360-180);
                check(distance<=train.maxSpeed*.1+.011&&turn<90&&(distance>.00001||turn<.01),"a sparse-block turn moves progressively without any stationary spin");
            }
            if(train.routeIndex!=cursor) {
                Node station=n.nodes.get(target);boolean there=false;for(Pose pose:train.poses)there|=Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
                check(there,"a sparse-block turning waypoint cannot count as a station visit");visits++;cursor=train.routeIndex;
            }
            if(visits==1) {
                for(Pose pose:train.poses)if(pose.railName.startsWith("w")&&train.direction<0)backed=true;
                if(traction<0&&train.direction>0) {
                    check(backed&&!n.signalOccupiesNode(train,"J"),"the sparse-block train must physically clear the nearest points before its forward pivot");
                    for(Pose pose:train.poses)check(pose.railName.startsWith("w"),"the sparse-block pivot occurs entirely on the backing tail");
                    check(retained!=null,"the sparse pivot must exercise an existing broad signal block containing the junction");
                    SignalSystem.Reservation after=null;for(SignalSystem.Reservation reservation:n.signals().reservations())if(reservation.id.equals(retained.id))after=reservation;
                    check(after!=null&&after.state==SignalSystem.ReservationState.CLEARING&&after.path.equals(retained.path)&&resources(after).equals(resources),"re-aligning the train's own cleared points retains every resource of the historical signal block");
                    int northWest=(1<<n.signalSide("J","w1"))|(1<<n.signalSide("J","n1"));
                    check(after.junctionLocks().get("J")==northWest&&n.nodes.get("J").getAlignmentMask()==northWest,"the retained protection transfers its physical lock from straight backing to the requested north departure");
                    pivot=true;transferred=true;
                }
                if(mirror&&!paused&&backed&&train.direction<0) {n.stop(train.name);held=new ArrayList<Pose>(train.poses);paused=true;pausedAt=seconds;}
                if(mirror&&pivot&&!reloaded) {
                    List<Pose> saved=new ArrayList<Pose>(train.poses);n=reload(n);train=n.trains.get("Sparse");samePoses(saved,train.poses,"restart after sparse-block handover preserves physical pose");
                    check(!train.powered&&train.direction>0,"restart retains the saved forward departure phase");n.resumeScheduled(train.name);reloaded=true;
                }
            }
            if(paused&&!resumed) {
                samePoses(held,train.poses,"Clear cannot move an operator-stopped sparse-block turn");if(step%25==0)n.resolveTrafficJams();
                if(seconds-pausedAt>=15) {n.resumeScheduled(train.name);resumed=true;}
            }
        }
        check(visits==3&&backed&&pivot&&transferred&&(!mirror||paused&&resumed&&reloaded),"sparse signals must hand over their own retained point lock and complete three real station calls: visits="+visits+" "+train.status+" "+train.poses);
    }
    @SuppressWarnings("unchecked") private static Set<String> resources(SignalSystem.Reservation reservation) throws Exception {
        Field field=SignalSystem.Reservation.class.getDeclaredField("resources");field.setAccessible(true);return new LinkedHashSet<String>((Set<String>)field.get(reservation));
    }

    private static RailNetwork graph(boolean mirror,int tailLength) {
        RailNetwork n=new RailNetwork();point(n,null,"J",0,0,mirror);
        String previous="J";
        for(int i=1;i<=5;i++)previous=point(n,previous,"e"+i,i,0,mirror);
        previous="J";for(int i=1;i<=tailLength;i++)previous=point(n,previous,"w"+i,-i,0,mirror);
        previous="J";for(int i=1;i<=19;i++)previous=point(n,previous,"s"+i,0,i,mirror);
        previous=point(n,previous,"RemoteY",0,20,mirror);
        for(int i=1;i<=10;i++)previous=point(n,previous,"r"+i,i,20+i,mirror);
        for(int i=11;i<=20;i++)previous=point(n,previous,"r"+i,20-i,20+i,mirror);
        for(int i=21;i<=30;i++)previous=point(n,previous,"r"+i,20-i,60-i,mirror);
        for(int i=31;i<=39;i++)previous=point(n,previous,"r"+i,i-40,60-i,mirror);
        n.connect(previous,"RemoteY");
        n.setJunctionAlignment("J",(1<<n.signalSide("J","s1"))|(1<<n.signalSide("J","e1")));
        n.setJunctionAlignment("RemoteY",(1<<n.signalSide("RemoteY","r1"))|(1<<n.signalSide("RemoteY","r39")));
        for(String junction:Arrays.asList("J","RemoteY"))for(String link:n.nodes.get(junction).links)
            n.signals().add(junction+"_"+link,junction,n.signalSide(junction,link),-10);
        n.setStation("e5",true,2);n.setStation("r19",true,2);return n;
    }
    private static String point(RailNetwork n,String previous,String name,int x,int y,boolean mirror) {
        n.addNode(name,100+(mirror?-x:x),100+y,0);if(previous!=null)n.connect(previous,name);return name;
    }
    private static void run(boolean mirror,int count,String mode) throws Exception {
        boolean usable=!mode.equals("short")&&!mode.equals("occupied");
        RailNetwork n=graph(mirror,mode.equals("short")?1:10);
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<count;i++)ids.add(carId++);
        Train t=n.createTrain("Service",1,ids,mode.equals("busy")?"e4":"s4",mode.equals("busy")?"e5":"s3");n.setRoute(t.name,Arrays.asList("e5","r19"),2);n.resumeScheduled(t.name);
        Train parked=null;List<Pose> parkedPoses=null;
        if(mode.equals("occupied")) {
            parked=n.createTrain("Parked",2,Arrays.asList(carId++),"w1","J");
            parkedPoses=new ArrayList<Pose>(parked.poses);
        }
        Train receiver=null;List<Pose> receiverPoses=null;boolean receiverRemoved=false;
        if(mode.equals("busy")) {
            receiver=n.createTrain("Receiver",2,Arrays.asList(carId++),"s2","s3");
            // A powered vehicle with a long hold represents a service ahead
            // which is expected to clear, rather than an unavailable parked
            // backing lane that should cause a different maneuver to be chosen.
            receiver.powered=true;receiver.dwellRemaining=100;
            receiverPoses=new ArrayList<Pose>(receiver.poses);
        }
        List<String> route=new ArrayList<String>(t.route);List<Long> vehicles=new ArrayList<Long>(t.carIds);
        int cursor=t.routeIndex,visits=0;boolean firstStation=false,backedIntoTail=false,localForward=false;
        boolean reloaded=false,paused=false,resumed=false;double pausedAt=0;
        List<Pose> held=null;double lastArrival=0;
        for(int tick=1;tick<=18000;tick++) {
            double seconds=tick*.1;context="mirror="+mirror+" cars="+count+" mode="+mode+" seconds="+seconds+": ";
            List<Pose> before=new ArrayList<Pose>(t.poses);int direction=t.direction;
            String target=t.route.get(t.routeIndex);n.tick(.1);t=n.trains.get("Service");
            check(t.automated&&t.route.equals(route)&&t.carIds.equals(vehicles),"the station turn preserves the saved schedule and consist");
            check(t.powered!=((paused&&!resumed)),"automatic station turning preserves the operator power setting: "+t.status);
            for(int i=0;i<t.poses.size();i++) {
                Pose a=before.get(i),b=t.poses.get(i);double distance=Math.hypot(b.x-a.x,b.y-a.y);
                double turn=Math.abs((b.rotation-a.rotation+540)%360-180);
                check(distance<=t.maxSpeed*.1+.011&&turn<90&&(distance>.00001||turn<.01),"station turning moves along rail geometry without teleporting or stationary spins");
            }
            if(t.routeIndex!=cursor) {
                Node stop=n.nodes.get(target);boolean there=false;
                for(Pose pose:t.poses)there|=Math.hypot(pose.x-stop.worldX(),pose.y-stop.worldY())<.51;
                check(there,"a temporary reversing tail cannot manufacture a station visit");
                visits++;cursor=t.routeIndex;lastArrival=seconds;if(target.equals("e5"))firstStation=true;
            }
            if(firstStation&&visits==1) {
                boolean west=false,south=false;
                for(Pose pose:t.poses) {west|=pose.railName!=null&&pose.railName.startsWith("w");south|=pose.railName!=null&&pose.railName.startsWith("s");}
                if(t.direction<0&&west)backedIntoTail=true;
                if(mode.equals("busy")&&!receiverRemoved) {
                    check(t.direction>0&&!west&&!south&&!n.signalOccupiesNode(t,"J"),"a local turn cannot start until its forward receiving track is available");
                    if(seconds>=10)check(t.stationTurnback!=null,"a temporarily busy receiving track preserves the nearest-junction plan rather than sending the train around a remote loop");
                }
                if(usable&&t.direction<0&&south)
                    check(false,"a usable adjacent backing tail must be preferred to backing down the main toward the remote loop: "+t.status+" "+t.poses);
                if(direction<0&&t.direction>0) {
                    boolean entirelyWest=true;
                    for(Pose pose:t.poses)entirelyWest&=pose.railName!=null&&pose.railName.startsWith("w");
                    if(entirelyWest) {
                        check(usable,"an occupied or undersized tail cannot be accepted as a local turning space");
                        check(!n.signalOccupiesNode(t,"J"),"the entire train must clear the junction before changing its alignment");
                        localForward=true;
                    }
                }
                if(usable&&mode.equals("restart")&&!reloaded&&backedIntoTail&&t.direction<0) {
                    List<Pose> saved=new ArrayList<Pose>(t.poses);int traction=t.direction;
                    n=reload(n);t=n.trains.get("Service");samePoses(saved,t.poses,"restart preserves every body during a local station turn");
                    check(!t.powered&&t.direction==traction,"loading retains local turning traction without silently powering a train");
                    n.resumeScheduled(t.name);reloaded=true;
                }
                if(usable&&mode.equals("stop")&&!paused&&backedIntoTail&&t.direction<0) {
                    n.stop(t.name);held=new ArrayList<Pose>(t.poses);paused=true;pausedAt=seconds;
                }
            }
            if(paused&&!resumed) {
                samePoses(held,t.poses,"Clear traffic jams cannot move an operator-stopped local turn");
                if(tick%25==0)n.resolveTrafficJams();
                if(seconds-pausedAt>=20) {n.resumeScheduled(t.name);resumed=true;}
            }
            if(parked!=null) {
                parked=n.trains.get("Parked");check(!parked.powered,"the station dispatcher cannot start a parked obstacle");
                samePoses(parkedPoses,parked.poses,"the parked backup-track train remains fixed");
                for(Pose a:t.poses)for(Pose b:parked.poses)check(Math.hypot(a.x-b.x,a.y-b.y)>=3.99,"occupied-tail admission retains four-metre headway");
            }
            if(mode.equals("busy")&&!receiverRemoved) {
                samePoses(receiverPoses,receiver.poses,"the held receiving service remains physically fixed until it clears");
                for(Pose a:t.poses)for(Pose b:receiver.poses)check(Math.hypot(a.x-b.x,a.y-b.y)>=3.99,"busy receiving-track admission preserves physical train headway");
                if(seconds>=30) {n.stop(receiver.name);n.removeTrain(receiver.name);receiverRemoved=true;}
            }
            // A parked vehicle can legitimately keep the wider terminal yard
            // unavailable on the next circuit. This case proves that the
            // arriving train escapes via the other turn rather than borrowing
            // occupied clearance; the parked owner need not be auto-started.
            if(visits>=(mode.equals("occupied")?2:4)&&seconds-lastArrival<180) {
                if(usable)check(backedIntoTail&&localForward,"a viable adjacent tail must actually be used to restore engine-leading departure");
                check(!mode.equals("restart")||reloaded,"the restart case must interrupt a real backing maneuver");
                check(!mode.equals("stop")||(paused&&resumed),"the STOP case must interrupt and resume a real backing maneuver");return;
            }
        }
        check(false,"the service must resume repeated real station calls after a protected local turn; visits="+visits+" "+t.status+" "+t.poses);
    }
    private static RailNetwork reload(RailNetwork n) throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-station-turn-");Path file=folder.resolve("network.properties");
        try {n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
    private static void samePoses(List<Pose> a,List<Pose> b,String message) {
        check(a.size()==b.size(),message);for(int i=0;i<a.size();i++)check(a.get(i).x==b.get(i).x&&a.get(i).y==b.get(i).y&&a.get(i).rotation==b.get(i).rotation&&a.get(i).carId==b.get(i).carId,message);
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(context+message);}
}
