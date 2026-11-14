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
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** A backing loop exit is durable intent, not a backing arrival into the ring. */
public final class LoopExitRecoveryStateSelfTest {
    private static int assertions;
    private static long carId=99500000;
    private LoopExitRecoveryStateSelfTest() { }

    public static void main(String[] args) throws Exception {
        stopAndResume();checkpointRollback();restartAndLegacy();restartAcrossExit();invalidAndObsoleteState();explicitEdits();
        legacyReturnersRemainReturners();ambiguousLegacyStateRemainsUnmarked();
        System.out.println("LoopExitRecoveryStateSelfTest: "+assertions+" assertions passed; durable backing exit, authoritative STOP, checkpoint rollback, restart, conservative old-yield migration, obsolete intent, invalid-state rejection and unchanged returning cohorts.");
    }
    private static RailNetwork graph() throws Exception {
        Method graph=YLoopRecoverySelfTest.class.getDeclaredMethod("graph",boolean.class);graph.setAccessible(true);
        return (RailNetwork)graph.invoke(null,false);
    }
    private static RailNetwork fixture(boolean resident) throws Exception {
        RailNetwork n=graph();Train train=n.createTrain("BackingExit",1,Arrays.asList(carId++),"r7","r8");
        n.setRoute(train.name,Arrays.asList("n5","r20"),.5);n.reverse(train.name);
        set(train,"recoveringTraffic",true);set(train,"trafficRecoveryExit","Y");
        if(resident) {
            Train leader=n.createTrain("ForwardLeader",1,Arrays.asList(carId++),"r4","r3");
            n.setRoute(leader.name,Arrays.asList("n5","r20"),.5);
        }
        return n;
    }
    private static void stopAndResume() throws Exception {
        RailNetwork n=fixture(false);Train train=n.trains.get("BackingExit");n.resumeScheduled(train.name);n.tick(.5);n.stop(train.name);
        List<Pose> stopped=new ArrayList<Pose>(train.poses);
        for(int tick=0;tick<600;tick++) {
            n.tick(.1);if(tick%50==0)n.resolveTrafficJams();
            check(!train.powered&&train.speed==0&&train.direction<0&&"Y".equals(exit(train)),"operator STOP retains the backing exit without automatically starting it");
            samePoses(stopped,train.poses,"operator STOP keeps every backing body physically fixed");
        }
        n.resumeScheduled(train.name);finish(n,train);
    }
    private static void checkpointRollback() throws Exception {
        RailNetwork n=fixture(false);Train train=n.trains.get("BackingExit");n.resumeScheduled(train.name);n.tick(.5);
        RailNetwork.Checkpoint checkpoint=n.checkpoint(train.name);List<Pose> original=new ArrayList<Pose>(train.poses);
        double speed=train.speed;n.tick(.8);List<Pose> expected=new ArrayList<Pose>(train.poses);
        n.stop(train.name);n.setRoute(train.name,Arrays.asList("r20","n5"),3);
        check(exit(train)==null,"an explicit itinerary edit clears the old loop-exit intent");n.restore(checkpoint);
        check(train.powered&&train.direction<0&&train.speed==speed&&"Y".equals(exit(train))&&train.routeIndex==0&&train.route.equals(Arrays.asList("n5","r20")),
                "rollback restores the backing exit with its original itinerary, traction and power");
        samePoses(original,train.poses,"rollback restores physical positions without a turn");n.tick(.8);
        samePoses(expected,train.poses,"restored backing exit repeats the original physical movement");
        n.restore(checkpoint);finish(n,train);
    }
    private static void restartAndLegacy() throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-loop-exit-state-");Path file=folder.resolve("network.properties");
        try {
            RailNetwork n=fixture(false);Train train=n.trains.get("BackingExit");n.resumeScheduled(train.name);n.tick(.5);
            List<Pose> saved=new ArrayList<Pose>(train.poses);n.save(file);Properties properties=read(file);
            check("4".equals(properties.getProperty("format"))&&"Y".equals(properties.getProperty("train.0.recoveryExit"))
                    &&"true".equals(properties.getProperty("train.0.trafficYield")),"the existing checksummed format stores the optional loop exit and yield intent");
            RailNetwork loaded=RailNetwork.load(file);Train restored=loaded.trains.get(train.name);
            check(!restored.powered&&restored.speed==0&&restored.direction<0&&"Y".equals(exit(restored))&&restored.routeIndex==0,
                    "restart retains exit intent and waits for an authorized service resume");samePoses(saved,restored.poses,"restart preserves every physical body");
            loaded.tick(60);check(!restored.powered&&restored.routeIndex==0&&"Y".equals(exit(restored)),"loading a stopped exit cannot advance or resume it");
            samePoses(saved,restored.poses,"a loaded stopped exit stays fixed");loaded.resumeScheduled(restored.name);finish(loaded,restored);

            n=fixture(true);n.save(file);properties=read(file);properties.remove("train.0.recoveryExit");signedWrite(file,properties);
            loaded=RailNetwork.load(file);restored=loaded.trains.get("BackingExit");
            check(!restored.powered&&restored.direction<0&&loaded.isRecoveringTraffic(restored.name),"an old yield without the new optional property remains loadable and stopped");
            for(Train member:loaded.trains.values())loaded.resumeScheduled(member.name);
            loaded.tick(.1);
            check("Y".equals(exit(restored)),"legacy inference recognizes an outgoing backer whose actual motion matches the opposed-facing forward leader");
        } finally {deleteFiles(folder);}
    }
    private static void invalidAndObsoleteState() throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-loop-exit-invalid-");Path file=folder.resolve("valid.properties");
        try {
            fixture(false).save(file);Properties valid=read(file);
            invalid(folder,valid,"empty","train.0.recoveryExit","");
            invalid(folder,valid,"not-yielding","train.0.trafficYield","false");
            invalid(folder,valid,"manual","train.0.automated","false");
            invalid(folder,valid,"forward","train.0.direction","1");
            invalid(folder,valid,"circuit-and-exit","train.0.recoveryRail","r20");
            for(String obsolete:Arrays.asList("missing","r20","B")) {
                Properties changed=new Properties();changed.putAll(valid);changed.setProperty("train.0.recoveryExit",obsolete);
                Path modified=folder.resolve("obsolete-"+obsolete+".properties");signedWrite(modified,changed);
                RailNetwork loaded=RailNetwork.load(modified);Train train=loaded.trains.get("BackingExit");
                check(exit(train)==null&&!train.powered&&train.direction<0&&loaded.isRecoveringTraffic(train.name)
                        &&train.route.equals(Arrays.asList("n5","r20"))&&train.routeIndex==0,
                        "a removed or no longer suitable junction discards only the obsolete exit intent: "+obsolete);
            }
        } finally {deleteFiles(folder);}
    }
    private static void restartAcrossExit() throws Exception {
        for(String stage:Arrays.asList("Y","s3")) {
            RailNetwork n=fixture(false);Train train=n.trains.get("BackingExit");n.resumeScheduled(train.name);boolean reached=false;
            for(int tick=0;tick<1200&&!reached;tick++) {
                n.tick(.1);for(Pose pose:train.poses)reached|=stage.equals(pose.railName);
            }
            check(reached&&"Y".equals(exit(train)),"a backing exit retains its intent while physically clearing "+stage);
            List<Pose> held=new ArrayList<Pose>(train.poses);Path folder=Files.createTempDirectory("wurmrail-loop-exit-stage-");Path file=folder.resolve("network.properties");
            try {
                n.save(file);RailNetwork loaded=RailNetwork.load(file);Train restored=loaded.trains.get(train.name);
                check(!restored.powered&&restored.direction<0&&"Y".equals(exit(restored)),"loading an already committed exit must retain its intent at "+stage);
                samePoses(held,restored.poses,"mid-exit restart keeps physical junction/stem occupancy fixed");
                loaded.resumeScheduled(restored.name);finish(loaded,restored);
            }finally{deleteFiles(folder);}
        }
    }
    private static void explicitEdits() throws Exception {
        RailNetwork n=fixture(false);Train train=n.trains.get("BackingExit");List<Pose> held=new ArrayList<Pose>(train.poses);n.reverse(train.name);
        check(exit(train)==null&&!n.isRecoveringTraffic(train.name)&&train.direction>0,"explicit stopped reversal clears the old exit and returns control to its operator");
        samePoses(held,train.poses,"an operator traction reversal cannot rotate or teleport the engine");
        n=fixture(false);train=n.trains.get("BackingExit");held=new ArrayList<Pose>(train.poses);n.setRoute(train.name,Arrays.asList("r20","n5"),4);
        check(exit(train)==null&&!n.isRecoveringTraffic(train.name)&&train.routeIndex==0,"explicit route replacement cancels the old exit without manufacturing progress");
        samePoses(held,train.poses,"changing a route leaves every physical body fixed");
    }
    private static void legacyReturnersRemainReturners() throws Exception {
        for(boolean fromYard:new boolean[]{false,true}) {
            RailNetwork n=graph();Train train=n.createTrain("LegacyReturn",1,Arrays.asList(carId++),fromYard?"s4":"r7",fromYard?"s5":"r8");
            n.setRoute(train.name,Arrays.asList("n5","r20"),.5);n.reverse(train.name);set(train,"recoveringTraffic",true);
            if(!fromYard)set(train,"trafficRecoveryRail","r20");
            if(!fromYard){Train resident=n.createTrain("Leader",1,Arrays.asList(carId++),"r4","r3");n.setRoute(resident.name,Arrays.asList("n5","r20"),.5);n.resumeScheduled(resident.name);}
            n.resumeScheduled(train.name);n.tick(.1);
            check(exit(train)==null,"legacy yard returners and saved correction circuits must retain their existing turnback meaning");
        }
    }
    private static void ambiguousLegacyStateRemainsUnmarked() throws Exception {
        for(String variant:Arrays.asList("opposite-motion","same-physical","manual-resident")) {
            RailNetwork n=fixture(false);Train backer=n.trains.get("BackingExit");set(backer,"trafficRecoveryExit",null);
            // The proposed legacy backer is wholly inside the ring, targets
            // the outside station, has no correction waypoint, and already
            // has a yielding reverse intent. Those facts alone are ambiguous.
            Train other=n.createTrain("OtherResident",2,Arrays.asList(carId++),"r4",variant.equals("same-physical")?"r5":"r3");
            if(!variant.equals("manual-resident")) {
                n.setRoute(other.name,Arrays.asList("n5","r20"),.5);
                // Opposed physical engines both backing travel opposite ways;
                // equal physical engines both backing have coherent motion,
                // but do not prove the old mixed-facing escape maneuver.
                n.reverse(other.name);
            }
            List<String> ring=new ArrayList<String>();ring.add("Y");for(int index=1;index<=46;index++)ring.add("r"+index);
            int facing=n.signalLoopOrientation(backer,ring),otherFacing=n.signalLoopOrientation(other,ring);
            check(facing!=0&&otherFacing!=0,"negative migration fixtures have definite physical ring orientation");
            if(variant.equals("opposite-motion"))check(facing!=otherFacing&&facing*backer.direction!=otherFacing*other.direction,
                    "opposed physical engines travelling opposite ways are not the old coherent exit maneuver");
            if(variant.equals("same-physical"))check(facing==otherFacing&&facing*backer.direction==otherFacing*other.direction,
                    "same-facing residents with coherent travel do not establish mixed physical exit intent");
            if(variant.equals("manual-resident"))check(facing!=otherFacing&&facing*backer.direction==otherFacing*other.direction&&!other.automated,
                    "an uncontrolled manual resident cannot establish an automatic legacy exit plan");
            List<Pose> backerPoses=new ArrayList<Pose>(backer.poses),otherPoses=new ArrayList<Pose>(other.poses);
            int otherDirection=other.direction;n.signals().traffic().restoreLoopExitIntents();
            check(exit(backer)==null,"ambiguous legacy state cannot infer a backing exit: "+variant);
            samePoses(backerPoses,backer.poses,"querying legacy exit inference cannot move the recovering train");
            samePoses(otherPoses,other.poses,"querying legacy exit inference cannot move another resident");
            check(backer.direction<0&&other.direction==otherDirection&&!backer.powered&&!other.powered,"legacy inference cannot change physical traction or operator power");
            Path folder=Files.createTempDirectory("wurmrail-loop-exit-ambiguous-");Path file=folder.resolve("network.properties");
            try {
                n.save(file);RailNetwork loaded=RailNetwork.load(file);Train restored=loaded.trains.get(backer.name);
                check(exit(restored)==null&&restored.direction<0&&!restored.powered&&loaded.isRecoveringTraffic(restored.name)
                        &&restored.route.equals(backer.route)&&restored.routeIndex==backer.routeIndex,
                        "old saved ambiguity remains unmarked without dropping its itinerary or yield: "+variant);
                samePoses(backerPoses,restored.poses,"loading ambiguous legacy state retains the actual recovering pose");
                samePoses(otherPoses,loaded.trains.get(other.name).poses,"loading ambiguous legacy state retains the other physical resident");
            }finally{deleteFiles(folder);}
        }
    }
    private static void finish(RailNetwork n,Train train) throws Exception {
        int cursor=train.routeIndex;List<String> route=new ArrayList<String>(train.route);boolean arrived=false;
        for(int tick=0;tick<2400&&!arrived;tick++) {
            List<Pose> before=new ArrayList<Pose>(train.poses);n.tick(.1);
            check(train.powered&&train.automated&&train.route.equals(route),"a backing exit preserves its authorized service and itinerary");
            for(int i=0;i<before.size();i++) {Pose a=before.get(i),b=train.poses.get(i);double moved=Math.hypot(b.x-a.x,b.y-a.y),turn=Math.abs((b.rotation-a.rotation+540)%360-180);
                check(moved<=train.maxSpeed*.1+.011&&turn<90&&(moved>.00001||turn<.01),"exit movement follows the real track without teleporting or stationary turns");}
            arrived=train.routeIndex!=cursor;
        }
        Node station=n.nodes.get(route.get(cursor));boolean physicallyThere=false;
        for(Pose pose:train.poses)physicallyThere|=Math.hypot(pose.x-station.worldX(),pose.y-station.worldY())<.51;
        // The existing post-arrival yield may still protect a backing train's
        // receiving berth while it restores forward travel. Its distinct loop
        // exit intent must clear at this real stop, without removing that guard.
        check(arrived&&physicallyThere&&exit(train)==null,"the backing exit clears only after an actual scheduled station arrival; arrived="+arrived+" atStation="+physicallyThere+" exit="+exit(train)+" yielding="+n.isRecoveringTraffic(train.name)+" cursor="+train.routeIndex+" poses="+train.poses+" status="+train.status);
    }
    private static void invalid(Path folder,Properties valid,String tag,String key,String value) throws Exception {
        Properties changed=new Properties();changed.putAll(valid);changed.setProperty(key,value);Path file=folder.resolve(tag+".properties");signedWrite(file,changed);
        try{RailNetwork.load(file);throw new AssertionError("accepted invalid loop-exit state: "+tag);}catch(IOException expected){assertions++;}
    }
    private static String exit(Train train) throws Exception {Field field=Train.class.getDeclaredField("trafficRecoveryExit");field.setAccessible(true);return (String)field.get(train);}
    private static void set(Train train,String name,Object value) throws Exception {Field field=Train.class.getDeclaredField(name);field.setAccessible(true);field.set(train,value);}
    private static Properties read(Path file) throws IOException {Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void signedWrite(Path file,Properties p) throws Exception {Method checksum=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);checksum.setAccessible(true);p.setProperty("checksum",(String)checksum.invoke(null,p));try(OutputStream out=Files.newOutputStream(file)){p.store(out,"signed loop-exit regression state");}}
    private static void samePoses(List<Pose> before,List<Pose> after,String message){check(before.size()==after.size(),message);for(int i=0;i<before.size();i++){Pose a=before.get(i),b=after.get(i);check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);}}
    private static void deleteFiles(Path folder) throws IOException {try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(folder)){for(Path file:files)Files.delete(file);}Files.delete(folder);}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(message);}
}
