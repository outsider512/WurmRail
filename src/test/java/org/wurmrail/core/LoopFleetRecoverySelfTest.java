package org.wurmrail.core;

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

/** Fleet correction when a receiving loop already contains opposed locomotives. */
public final class LoopFleetRecoverySelfTest {
    private static int assertions;
    private static long nextId=89000000;
    private LoopFleetRecoverySelfTest() { }

    public static void main(String[] args) throws Exception {
        if(args.length>0) {
            if(args[0].equals("saved"))saved(args.length>1&&Boolean.parseBoolean(args[1]));
            else run(args[0],args.length>1?Integer.parseInt(args[1]):4,
                        args.length>2&&Boolean.parseBoolean(args[2]),args.length>3&&Boolean.parseBoolean(args[3]));
        } else {
            saved(false);saved(true);
            for(boolean mirror:new boolean[]{false,true}) {
                run("opposed",4,mirror,false);
                run("opposed",4,mirror,true);
                run("opposed-clear",4,mirror,false);
                run("opposed-short",4,mirror,false);
                run("sparse",3,mirror,false);
                for(int trains=4;trains<=7;trains++)run("dense",trains,mirror,trains%2==0);
            }
        }
        System.out.println("LoopFleetRecoverySelfTest: "+assertions+" assertions passed; opposed receiving-loop trains, mixed consists, extra-loop correction, three-to-seven services, mirrored tracks and restart.");
    }

    private static RailNetwork graph() throws Exception {
        Method fixture=TrafficControlSelfTest.class.getDeclaredMethod("dumbbell",int.class);
        fixture.setAccessible(true);return (RailNetwork)fixture.invoke(null,40);
    }
    private static Train make(RailNetwork network,String name,String at,String facing,int cars,List<String> route) {
        List<Long> ids=new ArrayList<Long>();for(int car=0;car<cars;car++)ids.add(nextId++);
        Train train=network.createTrain(name,1,ids,at,facing);network.setRoute(name,route,.5);return train;
    }

    /** Frozen with the released 2.14.0 implementation after ten minutes of a
     * reproducible four-train jam. It must remain recoverable even when a new
     * version prevents this arrangement from being formed in the first place. */
    private static void saved(boolean clearOnce) throws Exception {
        Path location=java.nio.file.Paths.get(LoopFleetRecoverySelfTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path fixture=null;
        for(Path directory=location;directory!=null;directory=directory.getParent()) {
            Path candidate=directory.resolve("src/test/resources/loop-opposed-jam.properties");
            if(Files.isRegularFile(candidate)){fixture=candidate;break;}
        }
        check(fixture!=null,"the immutable old-version traffic jam fixture is available");
        RailNetwork network=RailNetwork.load(fixture);List<String> names=new ArrayList<String>(network.trains.keySet());
        check(names.size()==4&&network.trains.get("LocalA").carIds.size()==2,"old jam fixture retains four trains and its attached cart");
        exercise(network,names,Arrays.asList("l11","r11"),"saved",4,false,false,true,clearOnce);
    }

    private static void run(String scenario,int count,boolean mirror,boolean restart) throws Exception {
        RailNetwork network=graph();String home=mirror?"r":"l",away=mirror?"l":"r";
        List<String> route=Arrays.asList(home+"11",away+"11");
        List<String> names=new ArrayList<String>();
        if(scenario.startsWith("opposed")) {
            names.add(make(network,"LocalA",home+"4",home+"3",scenario.equals("opposed-short")?1:2,route).name);
            names.add(make(network,"LocalB",home+"19",home+"20",1,route).name);
            names.add(make(network,"Inbound","c"+(mirror?35:5),"c"+(mirror?36:4),1,route).name);
            names.add(make(network,"Opposite",away+"11",away+"10",1,route).name);
        } else if(scenario.equals("sparse")) {
            names.add(make(network,"LocalA",home+"8",home+"7",1,route).name);
            names.add(make(network,"LocalB",home+"15",home+"16",1,route).name);
            names.add(make(network,"Inbound","c"+(mirror?35:5),"c"+(mirror?36:4),1,route).name);
        } else if(scenario.equals("dense")) {
            for(int index=0;index<count-1;index++) {
                int at=index*3+2;
                names.add(make(network,"Local"+index,home+at,home+(at+(index%2==0?-1:1)),index==1&&count>=6?2:1,route).name);
            }
            names.add(make(network,"Inbound","c"+(mirror?35:5),"c"+(mirror?36:4),1,route).name);
        } else throw new IllegalArgumentException("Unknown scenario "+scenario);
        exercise(network,names,route,scenario,count,mirror,restart,false,false);
    }
    private static void exercise(RailNetwork network,List<String> names,List<String> route,String scenario,int count,
                                 boolean mirror,boolean restart,boolean loaded,boolean clearOnce) throws Exception {
        Map<String,Integer> visits=new LinkedHashMap<String,Integer>(),cursors=new HashMap<String,Integer>();
        Map<String,List<Long>> cars=new HashMap<String,List<Long>>();Map<String,Double> lastArrival=new HashMap<String,Double>();
        for(String name:names) {
            Train train=network.trains.get(name);if(loaded)network.resumeScheduled(name);else network.start(name);
            visits.put(name,0);cursors.put(name,train.routeIndex);
            cars.put(name,new ArrayList<Long>(train.carIds));lastArrival.put(name,0d);
        }
        if(clearOnce) {
            Map<Long,Pose> original=new HashMap<Long,Pose>();
            for(Train train:network.trains.values())for(Pose pose:train.poses)original.put(pose.carId,pose);
            network.resolveTrafficJams();
            for(Train train:network.trains.values())for(Pose pose:train.poses) {
                Pose before=original.get(pose.carId);
                check(before!=null&&before.x==pose.x&&before.y==pose.y&&before.rotation==pose.rotation,
                        "Clear traffic jams plans correction without moving or spinning any train immediately");
            }
        }
        boolean reloaded=false,complete=false;double lastFleetMovement=0;
        for(int tick=1;tick<=18000&&!complete;tick++) {
            double seconds=tick*.1;Map<Long,Pose> before=new HashMap<Long,Pose>();
            Map<String,String> target=new HashMap<String,String>();
            for(String name:names) {
                Train train=network.trains.get(name);target.put(name,train.route.get(train.routeIndex));
                for(Pose pose:train.poses)before.put(pose.carId,pose);
            }
            network.tick(.1);
            for(String name:names) {
                Train train=network.trains.get(name);
                check(train.powered&&train.automated&&train.route.equals(route)&&train.carIds.equals(cars.get(name)),
                        "fleet recovery retains power, itinerary and every physical vehicle");
                boolean moved=false;
                for(Pose pose:train.poses) {
                    Pose old=before.get(pose.carId);double distance=Math.hypot(pose.x-old.x,pose.y-old.y);
                    double turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                    check(distance<=train.maxSpeed*.1+.011&&turn<90&&(distance>.00001||turn<.01),
                            "correction moves and turns real bodies along track without teleporting or stationary spins");
                    moved|=distance>.00001;
                }
                if(moved)lastFleetMovement=seconds;
                if(train.routeIndex!=cursors.get(name)) {
                    Node stop=network.nodes.get(target.get(name));boolean physicallyAtStop=false;
                    for(Pose pose:train.poses)physicallyAtStop|=pose.layer==stop.layer&&Math.hypot(pose.x-stop.worldX(),pose.y-stop.worldY())<.51;
                    check(physicallyAtStop,"an extra correction loop cannot manufacture a scheduled arrival: "+name);
                    visits.put(name,visits.get(name)+1);cursors.put(name,train.routeIndex);lastArrival.put(name,seconds);
                }
                // Backing and additional loops are legitimate while aligning
                // the fleet. They must not become its permanent normal service.
                if(seconds>600&&moved)check(train.direction==1,
                        "all services eventually recover engine-leading running: "+scenario+" count="+count+" mirror="+mirror+" restart="+restart+" "+describe(network,names,visits));
            }
            for(int first=0;first<names.size();first++)for(int second=first+1;second<names.size();second++)
                for(Pose a:network.trains.get(names.get(first)).poses)for(Pose b:network.trains.get(names.get(second)).poses)
                    check(a.layer!=b.layer||Math.hypot(a.x-b.x,a.y-b.y)>=3.99,"fleet correction preserves four-metre physical following clearance");
            if(restart&&!reloaded&&seconds>=120) {
                network=reload(network);for(String name:names)network.resumeScheduled(name);reloaded=true;
            }
            if(scenario.equals("opposed-clear")&&tick==3000) {
                Map<Long,Pose> original=new HashMap<Long,Pose>();
                for(String name:names)for(Pose pose:network.trains.get(name).poses)original.put(pose.carId,pose);
                network.resolveTrafficJams();
                for(String name:names)for(Pose pose:network.trains.get(name).poses) {
                    Pose old=original.get(pose.carId);
                    check(old.x==pose.x&&old.y==pose.y&&old.rotation==pose.rotation,
                            "a requested warm-jam correction retains every body's exact position and heading");
                }
            }
            complete=seconds>=600;
            for(String name:names)if(visits.get(name)<3||seconds-lastArrival.get(name)>180)complete=false;
            if(seconds-lastFleetMovement>300)break;
        }
        check(complete,"every train must escape the jam, correct orientation and resume repeated real station visits: "+scenario
                +" count="+count+" mirror="+mirror+" restart="+restart+" "+describe(network,names,visits));
    }
    private static RailNetwork reload(RailNetwork network) throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-fleet-recovery-");Path file=folder.resolve("network.properties");
        try {network.save(file);return RailNetwork.load(file);}finally{Files.deleteIfExists(file);Files.deleteIfExists(folder);}
    }
    private static String describe(RailNetwork network,List<String> names,Map<String,Integer> visits) {
        StringBuilder out=new StringBuilder("visits="+visits+" ");
        for(String name:names){Train train=network.trains.get(name);out.append(name).append(" dir=").append(train.direction).append(' ')
                .append(train.status).append(' ').append(train.poses).append(" traffic=").append(network.signals().trafficStatus(name)).append(';');}
        return out.toString();
    }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
