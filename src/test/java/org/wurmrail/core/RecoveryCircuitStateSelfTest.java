package org.wurmrail.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Durable temporary correction stops are independent of the passenger itinerary. */
public final class RecoveryCircuitStateSelfTest {
    private static int assertions;
    private RecoveryCircuitStateSelfTest() { }

    public static void main(String[] args) throws Exception {
        stopAndResume();checkpointRollback();restartAndLegacyState();invalidSavedState();explicitEdits();topologyEdits();
        System.out.println("RecoveryCircuitStateSelfTest: "+assertions+" assertions passed; temporary-stop progress, manual STOP, checkpoint rollback, restart, old saves, track edits and invalid-state rejection.");
    }

    /** Seed one valid, unobstructed recovery primitive. Fleet admission and the
     * choice of this primitive are covered separately by the fleet regression. */
    private static RailNetwork fixture() throws Exception {
        Method graph=TrafficControlSelfTest.class.getDeclaredMethod("dumbbell",int.class);
        graph.setAccessible(true);RailNetwork network=(RailNetwork)graph.invoke(null,40);
        Train train=network.createTrain("Circuit",1,Arrays.asList(89600001L),"l13","l14");
        network.setRoute(train.name,Arrays.asList("r11","l11"),.5);network.reverse(train.name);
        set(train,"recoveringTraffic",true);set(train,"trafficRecoveryRail","l11");
        check(network.isTrafficRecoveryStop(train,"l11")&&train.direction==-1,"fixture has a real backing circuit and a different scheduled destination");
        return network;
    }

    private static void stopAndResume() throws Exception {
        RailNetwork network=fixture();Train train=network.trains.get("Circuit");network.resumeScheduled(train.name);network.tick(1);
        network.stop(train.name);List<Pose> held=new ArrayList<Pose>(train.poses);
        for(int tick=0;tick<700;tick++) {
            network.tick(.1);if(tick%50==0)network.resolveTrafficJams();
            check(!train.powered&&train.speed==0&&train.direction==-1&&network.isTrafficRecoveryStop(train,"l11"),
                    "automatic recovery cannot override an operator STOP or discard its paused maneuver");
            samePoses(held,train.poses,"a STOPped recovering train stays physically still");
        }
        network.resumeScheduled(train.name);finishCircuit(network,train);
        network.stop(train.name);held=new ArrayList<Pose>(train.poses);network.tick(60);network.resolveTrafficJams();
        check(!train.powered&&train.speed==0,"STOP remains authoritative after the circuit has completed");
        samePoses(held,train.poses,"completed recovery cannot restart a STOPped train");
    }

    private static void checkpointRollback() throws Exception {
        RailNetwork network=fixture();Train train=network.trains.get("Circuit");network.resumeScheduled(train.name);network.tick(1);
        RailNetwork.Checkpoint checkpoint=network.checkpoint(train.name);List<Pose> original=new ArrayList<Pose>(train.poses);
        double speed=train.speed;String status=train.status;network.tick(1.7);List<Pose> expected=new ArrayList<Pose>(train.poses);
        network.stop(train.name);network.setRoute(train.name,Arrays.asList("l11","r11"),9);
        check(!network.isTrafficRecoveryStop(train,"l11"),"explicit itinerary replacement cancels the old correction goal");
        network.restore(checkpoint);
        check(train.powered&&train.direction==-1&&train.speed==speed&&train.status.equals(status)
                &&network.isTrafficRecoveryStop(train,"l11")&&train.route.equals(Arrays.asList("r11","l11")),
                "rollback atomically restores maneuver, traction, power and original itinerary");
        samePoses(original,train.poses,"rollback restores every physical body");network.tick(1.7);
        samePoses(expected,train.poses,"restored recovery repeats identical movement through its saved geometry");
        network.restore(checkpoint);finishCircuit(network,train);
    }

    private static void restartAndLegacyState() throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-circuit-state-");Path file=folder.resolve("network.properties");
        try {
            RailNetwork network=fixture();Train train=network.trains.get("Circuit");network.resumeScheduled(train.name);network.tick(1);
            List<Pose> original=new ArrayList<Pose>(train.poses);network.save(file);Properties properties=read(file);
            check("l11".equals(properties.getProperty("train.0.recoveryRail"))&&"true".equals(properties.getProperty("train.0.trafficYield")),
                    "the ordinary checksummed save includes the temporary circuit goal and traffic-yield intent");
            RailNetwork restored=RailNetwork.load(file);Train saved=restored.trains.get(train.name);
            check(!saved.powered&&saved.speed==0&&saved.direction==-1&&restored.isTrafficRecoveryStop(saved,"l11"),
                    "loading keeps a mid-circuit train stopped and retains the maneuver for authorized resumption");
            samePoses(original,saved.poses,"saving and loading cannot move or turn a recovering body");
            restored.tick(60);check(!saved.powered&&saved.routeIndex==0,"loading does not silently power on or advance the scheduled itinerary");
            samePoses(original,saved.poses,"a saved stopped circuit remains still until resumed");
            restored.resumeScheduled(saved.name);finishCircuit(restored,saved);

            properties.remove("train.0.recoveryRail");Path legacy=folder.resolve("legacy.properties");signedWrite(legacy,properties);
            RailNetwork old=RailNetwork.load(legacy);Train oldTrain=old.trains.get(train.name);
            check(!oldTrain.powered&&oldTrain.direction==-1&&!old.isTrafficRecoveryStop(oldTrain,"l11")
                    &&old.trafficRecoveryStatus(oldTrain.name).contains("paused"),
                    "an older saved traffic yield without a circuit goal remains valid and stopped");
            old.resumeScheduled(oldTrain.name);check(oldTrain.powered&&oldTrain.direction==-1,"an old saved yield resumes its physical backing direction");
        } finally {deleteFiles(folder);}
    }

    private static void invalidSavedState() throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-circuit-invalid-");
        try {
            Path original=folder.resolve("original.properties");fixture().save(original);Properties valid=read(original);
            invalid(folder,valid,"missing-track","train.0.recoveryRail","missing");
            invalid(folder,valid,"not-in-itinerary","train.0.recoveryRail","l12");
            invalid(folder,valid,"not-recovering","train.0.trafficYield","false");
            invalid(folder,valid,"manual-train","train.0.automated","false");
            invalid(folder,valid,"empty-goal","train.0.recoveryRail","");
            Properties junction=new Properties();junction.putAll(valid);junction.setProperty("train.0.route","L,r11");
            junction.setProperty("train.0.recoveryRail","L");Path changed=folder.resolve("junction-goal.properties");signedWrite(changed,junction);
            RailNetwork loaded=RailNetwork.load(changed);Train train=loaded.trains.get("Circuit");
            check(!loaded.isTrafficRecoveryStop(train,"L")&&loaded.isRecoveringTraffic(train.name)
                    &&train.route.equals(Arrays.asList("L","r11"))&&train.routeIndex==0&&!train.powered&&train.direction==-1,
                    "a saved station that has become a junction discards only its obsolete temporary goal, preserving the world, itinerary, yield and STOP");
        } finally {deleteFiles(folder);}
    }

    private static void topologyEdits() throws Exception {
        for(boolean running:new boolean[]{false,true})for(boolean spur:new boolean[]{false,true}) {
            RailNetwork network=fixture();Train train=network.trains.get("Circuit");
            Train unaffected=network.createTrain("Unaffected",2,Arrays.asList(89600002L),"r13","r14");
            network.setRoute(unaffected.name,Arrays.asList("l11","r11"),.5);network.reverse(unaffected.name);
            set(unaffected,"recoveringTraffic",true);set(unaffected,"trafficRecoveryRail","r11");
            // Make the edit before the first movement poll reserves the
            // approach; the public API correctly forbids editing live blocks.
            if(running)network.resumeScheduled(train.name);
            List<Pose> held=new ArrayList<Pose>(train.poses);int direction=train.direction,cursor=train.routeIndex;
            if(spur) {
                RailNetwork.Node station=network.nodes.get("l11");network.addNode("Spur",station.tileX-1,station.tileY,station.layer);
                check(network.isTrafficRecoveryStop(train,"l11"),"adding an unconnected track cannot invalidate a still-safe recovery goal");
                network.connect("l11","Spur");
                check(station.links.size()==3,"public construction turns the recovery station into a junction");
            } else {
                network.disconnect("l10","l11");
                check(network.nodes.get("l11").links.size()==1,"public track removal turns the recovery station into a terminus");
            }
            check(!network.isTrafficRecoveryStop(train,"l11")&&network.isRecoveringTraffic(train.name)
                    &&train.powered==running&&train.direction==direction&&train.routeIndex==cursor
                    &&train.route.equals(Arrays.asList("r11","l11")),
                    "editing the temporary stop clears only that obsolete waypoint and preserves its active or stopped service");
            samePoses(held,train.poses,"changing an unoccupied recovery stop cannot move or turn the train");
            check(network.isTrafficRecoveryStop(unaffected,"r11")&&!unaffected.powered,
                    "editing another station cannot discard a valid recovery goal or resume its stopped train");
            Path folder=Files.createTempDirectory("wurmrail-circuit-track-edit-");Path file=folder.resolve("network.properties");
            try {
                network.save(file);Properties properties=read(file);
                check(properties.getProperty("train.0.recoveryRail")==null&&"true".equals(properties.getProperty("train.0.trafficYield")),
                        "a normal save omits only the obsolete topology-dependent waypoint");
                RailNetwork restored=RailNetwork.load(file);Train saved=restored.trains.get(train.name);
                check(!restored.isTrafficRecoveryStop(saved,"l11")&&restored.isRecoveringTraffic(saved.name)
                        &&saved.route.equals(train.route)&&saved.routeIndex==cursor&&saved.direction==direction&&!saved.powered,
                        "worlds edited through public track actions remain loadable with the original itinerary and recovery intent");
                samePoses(held,saved.poses,"track editing, saving and reloading preserve physical train position");
                check(restored.isTrafficRecoveryStop(restored.trains.get(unaffected.name),"r11"),
                        "the unaffected recovery goal also survives the edited world's save and reload");
            } finally {deleteFiles(folder);}
        }
    }

    private static void explicitEdits() throws Exception {
        RailNetwork network=fixture();Train train=network.trains.get("Circuit");network.reverse(train.name);
        check(!network.isTrafficRecoveryStop(train,"l11")&&!network.isRecoveringTraffic(train.name)&&train.direction==1,
                "an explicit stopped reversal cancels recovery and gives control back to the operator");
        network=fixture();train=network.trains.get("Circuit");network.setRoute(train.name,Arrays.asList("l11","r11"),2);
        check(!network.isTrafficRecoveryStop(train,"l11")&&!network.isRecoveringTraffic(train.name)&&train.routeIndex==0,
                "an explicit route edit cannot retain a stale correction waypoint");
    }

    private static void finishCircuit(RailNetwork network,Train train) throws Exception {
        int cursor=train.routeIndex;List<String> itinerary=new ArrayList<String>(train.route);boolean completed=false;
        for(int tick=0;tick<1200&&!completed;tick++) {
            List<Pose> before=new ArrayList<Pose>(train.poses);network.tick(.1);
            check(train.powered&&train.automated&&train.route.equals(itinerary)&&train.routeIndex==cursor,
                    "an unscheduled recovery stop cannot manufacture an arrival or replace the itinerary");
            for(int car=0;car<before.size();car++) {
                Pose old=before.get(car),pose=train.poses.get(car);double movement=Math.hypot(pose.x-old.x,pose.y-old.y);
                double turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                check(movement<=train.maxSpeed*.1+.011&&(movement>.00001||turn<.01),
                        "resuming and completing a circuit moves the real body without teleporting or spinning");
            }
            completed=!network.isTrafficRecoveryStop(train,"l11");
        }
        RailNetwork.Node station=network.nodes.get("l11");Pose engine=train.poses.get(0);
        check(completed&&train.direction==1&&train.speed==0&&Math.hypot(engine.x-station.worldX(),engine.y-station.worldY())<.001,
                "the temporary circuit completes physically at its station and restores engine-leading travel");
        check(train.routeIndex==cursor&&train.dwellRemaining==0&&train.route.equals(itinerary),
                "correction completion leaves real station progress and passenger dwell untouched");
        // It must depart toward the original destination, not remain stuck at
        // the now-cleared temporary goal on its next planning boundary.
        network.tick(1);check(train.powered&&train.direction==1&&train.routeIndex==cursor
                &&Math.hypot(train.poses.get(0).x-engine.x,train.poses.get(0).y-engine.y)>.01,
                "a completed circuit continues its original scheduled route in forward travel");
    }

    private static void invalid(Path folder,Properties valid,String tag,String key,String value) throws Exception {
        Properties altered=new Properties();altered.putAll(valid);altered.setProperty(key,value);Path file=folder.resolve(tag+".properties");signedWrite(file,altered);
        try {RailNetwork.load(file);throw new AssertionError("accepted invalid circuit state: "+tag);}catch(IOException expected){assertions++;}
    }
    private static Properties read(Path file) throws IOException {
        Properties properties=new Properties();try(InputStream in=Files.newInputStream(file)){properties.load(in);}return properties;
    }
    private static void signedWrite(Path file,Properties properties) throws Exception {
        Method checksum=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);checksum.setAccessible(true);
        properties.setProperty("checksum",(String)checksum.invoke(null,properties));
        try(OutputStream out=Files.newOutputStream(file)){properties.store(out,"signed circuit state regression fixture");}
    }
    private static void set(Train train,String name,Object value) throws Exception {
        Field field=Train.class.getDeclaredField(name);field.setAccessible(true);field.set(train,value);
    }
    private static void samePoses(List<Pose> expected,List<Pose> actual,String message) {
        check(expected.size()==actual.size(),message);
        for(int index=0;index<expected.size();index++) {
            Pose a=expected.get(index),b=actual.get(index);
            check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.layer==b.layer&&a.rotation==b.rotation,message);
        }
    }
    private static void deleteFiles(Path folder) throws IOException {
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(folder)){for(Path file:files)Files.delete(file);}
        Files.delete(folder);
    }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
