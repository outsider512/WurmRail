package org.wurmrail.core;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.RailNetwork.Pose;

/** Real station arrivals and forward departures, including a backing receiving train. */
public final class LoopOrientationSelfTest {
    private static int assertions;
    private static long id=76000000;
    public static void main(String[] args) throws Exception {
        for(boolean right:new boolean[]{false,true})for(int cars:new int[]{1,2})for(int followers:new int[]{1,3,5,6})
            run(right,cars,followers,0,false);
        run(false,2,3,1,false);
        run(true,1,6,2,false);
        run(false,2,6,3,false);
        run(true,1,3,0,true);
        completedStationClearsFirst();
        System.out.println("LoopOrientationSelfTest: "+assertions+" assertions passed; opposite-arm followers, backing arrival priority, forward departures, mixed consists, mirrored loops and restart.");
    }
    private static void completedStationClearsFirst() throws Exception {
        RailNetwork n=graph();n.setStation("l11",false,0);
        // Combined passenger stops are itinerary destinations even when the
        // underlying rail's optional station flag is unset.
        Train departing=make(n,"Departing","l11","l12",1,Arrays.asList("r11","l11"));
        Train incoming=make(n,"Incoming","l12","l11",1,Arrays.asList("l11","r11"));
        n.start(departing.name);n.start(incoming.name);
        List<Pose> a=new ArrayList<Pose>(departing.poses),b=new ArrayList<Pose>(incoming.poses);
        check(n.resolveTrafficJams()==1,"an immediate recovery can clear a head-on at a completed itinerary stop");
        check(departing.direction==-1&&incoming.direction==1,"clear the served station rather than make its incoming receiver back around the loop");
        for(int i=0;i<a.size();i++)check(a.get(i).x==departing.poses.get(i).x&&a.get(i).y==departing.poses.get(i).y&&a.get(i).rotation==departing.poses.get(i).rotation,"station clearing changes traction without spinning the body");
        check(b.get(0).x==incoming.poses.get(0).x&&b.get(0).y==incoming.poses.get(0).y,"station approach priority leaves the arriving train in place");
    }
    private static RailNetwork graph() throws Exception {
        Method fixture=TrafficControlSelfTest.class.getDeclaredMethod("dumbbell",int.class);fixture.setAccessible(true);
        return (RailNetwork)fixture.invoke(null,40);
    }
    private static Train make(RailNetwork n,String name,String at,String facing,int cars,List<String> route) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<cars;i++)ids.add(id++);
        Train train=n.createTrain(name,1,ids,at,facing);n.setRoute(name,route,.5);return train;
    }
    private static void run(boolean right,int cars,int count,int restartPhase,boolean secondBacker) throws Exception {
        RailNetwork n=graph();String p=right?"r":"l",station=p+"11",other=right?"l11":"r11",junction=right?"R":"L";
        List<String> route=Arrays.asList(station,other);List<String> names=new ArrayList<String>();
        Train backer=make(n,"BackingArrival",p+"8",p+"7",cars,route);n.reverse(backer.name);setYield(backer);names.add(backer.name);
        if(secondBacker) { Train tail=make(n,"BackingTail",p+"4",p+"3",1,route);n.reverse(tail.name);setYield(tail);names.add(tail.name); }
        for(int i=0;i<count;i++) {
            int at=right?40-(i+1)*3:(i+1)*3;
            Train train=make(n,"Follower"+i,"c"+at,"c"+(at+(right?1:-1)),i==0?2:1,route);names.add(train.name);
        }
        for(String name:names)n.resumeScheduled(name);
        List<Train> trains=trains(n,names);int[] arrivals=new int[names.size()],cursor=new int[names.size()];
        boolean backerArrived=false,backerForward=false,restarted=false,done=false;
        Set<String> firstArrivals=new HashSet<String>(),waitingDeparture=new HashSet<String>();
        for(int tick=0;tick<18000&&!done;tick++) {
            Map<Long,Pose> before=new HashMap<Long,Pose>();for(Train t:trains)for(Pose pose:t.poses)before.put(pose.carId,pose);
            n.tick(.1);trains=trains(n,names);backer=n.trains.get(backer.name);
            for(int i=0;i<trains.size();i++) {
                Train t=trains.get(i);
                check(t.powered&&t.route.equals(route),"coordination preserves saved routes and running intent");
                for(Pose pose:t.poses) {
                    Pose old=before.get(pose.carId);double moved=Math.hypot(pose.x-old.x,pose.y-old.y),turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                    check(moved<=.501&&turn<90&&(moved>.00001||turn<.01),"orientation changes through rail motion, never a stationary body rotation");
                }
                if(t.routeIndex!=cursor[i]) {
                    arrivals[i]++;cursor[i]=t.routeIndex;firstArrivals.add(t.name);waitingDeparture.add(t.name);
                    if(t==backer) { check(t.direction==-1||backerArrived,"the priority receiver genuinely arrives while backing");backerArrived=true; }
                    else if(t.name.startsWith("Follower"))check(backerArrived&&backerForward,"forward followers cannot reach the station before the backing arrival restores forward traction");
                }
                Pose old=before.get(t.poses.get(0).carId),now=t.poses.get(0);
                if(waitingDeparture.contains(t.name)&&Math.hypot(now.x-old.x,now.y-old.y)>.00001&&t.dwellRemaining<=0) {
                    check(t.direction==1,"every serviced station departure restores engine-leading traction: "+t.name+" tick="+tick+" right="+right+" cars="+cars+" count="+count+" "+describe(n,names));
                    waitingDeparture.remove(t.name);if(t==backer)backerForward=true;
                }
                if(!firstArrivals.contains(t.name)&&t.name.startsWith("Follower")) {
                    for(TrafficControl.Lease lease:n.signals().traffic().leases())if(lease.trainName.equals(t.name)&&lease.exitJunction.equals(junction))
                        check(lease.exitNeighbor.equals(p+"22"),"followers choose the arm matching the receiver's eventual engine-leading circulation");
                    if(!backerForward)check(!n.signalOccupiesNode(t,junction),"new arrivals wait outside the points during the receiver's station turn");
                }
                if(tick>6000)check(t.direction==1,"a restored service cannot settle into a permanently backing schedule");
            }
            for(int a=0;a<trains.size();a++)for(int b=a+1;b<trains.size();b++)for(Pose x:trains.get(a).poses)for(Pose y:trains.get(b).poses)
                check(x.layer!=y.layer||Math.hypot(x.x-y.x,x.y-y.y)>=3.99,"coordinated arrivals retain physical train clearance");
            if(!restarted&&((restartPhase==1&&tick==5)||(restartPhase==2&&backerArrived&&!backerForward)||(restartPhase==3&&backerForward))) {
                n=reload(n);for(String name:names)n.resumeScheduled(name);trains=trains(n,names);restarted=true;
            }
            done=backerForward;for(int total:arrivals)if(total<3)done=false;
        }
        check(done,"all "+names.size()+" services resume repeated forward station visits; right="+right+" cars="+cars+" second="+secondBacker+" arrivals="+Arrays.toString(arrivals)+" "+describe(n,names));
    }
    private static String describe(RailNetwork n,List<String> names) {StringBuilder b=new StringBuilder();for(String name:names){Train t=n.trains.get(name);b.append(name).append(':').append(t.direction).append(' ').append(t.status).append(' ').append(t.poses).append(';');}return b.toString();}
    private static List<Train> trains(RailNetwork n,List<String> names) {List<Train> result=new ArrayList<Train>();for(String name:names)result.add(n.trains.get(name));return result;}
    private static void setYield(Train train) throws Exception {Field f=Train.class.getDeclaredField("recoveringTraffic");f.setAccessible(true);f.setBoolean(train,true);}
    private static RailNetwork reload(RailNetwork n) throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-orientation-");Path file=directory.resolve("network.properties");
        try {n.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(directory);}
    }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
