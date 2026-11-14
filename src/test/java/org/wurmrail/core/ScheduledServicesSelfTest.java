package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.wurmrail.core.RailNetwork.Train;

/** Run intent never grants movement authority or overrides an authoritative rail snapshot. */
public final class ScheduledServicesSelfTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        explicitIntent(); identitiesAndRoutes(); progressAndCheckpoint(); persistence();
        System.out.println("ScheduledServicesSelfTest: "+assertions+" assertions passed.");
    }
    private static RailNetwork network() {
        RailNetwork network=new RailNetwork();
        for(int i=0;i<10;i++) { network.addNode("R"+i,10+i,10,0);if(i>0)network.connect("R"+(i-1),"R"+i); }
        network.createTrain("Express",99,Collections.singletonList(101L),"R1","R2");
        network.setRoute("Express",Arrays.asList("R9","R0"),7);
        return network;
    }
    private static void explicitIntent() {
        RailNetwork network=network();Train train=network.trains.get("Express");ScheduledServices services=new ScheduledServices();
        check(services.size()==0&&services.resumeCandidates(network).isEmpty(),"existing world with no intent never starts automatically");
        network.start(train.name); services.refresh(network);
        check(!services.runningIntent(train),"periodic save cannot infer an explicit start from transient power");
        services.record(train,true);check(services.runningIntent(train)&&services.size()==1,"explicit scheduled start records run intent");
        network.stop(train.name);services.refresh(network);
        check(services.runningIntent(train),"shutdown safety STOP does not erase operator intent");
        check(!train.powered&&train.speed==0,"intent query and refresh cannot move or power a train");
        check(services.resumeCandidates(network).equals(Collections.singletonList(train.name)),"only matching scheduled service is offered for safe runtime resume");
        immutable(services.resumeCandidates(network),"candidate list is immutable");
        services.record(train,false);services.refresh(network);
        check(!services.runningIntent(train)&&services.resumeCandidates(network).isEmpty(),"operator STOP persists stopped intent");
        network.setRoute(train.name,Collections.<String>emptyList());services.record(train,true);
        check(services.size()==0,"manual power cannot create automatic resume intent");
        network.setRoute(train.name,Collections.singletonList("R9"));services.record(train,true);
        check(services.size()==0,"one-shot route is not a repeating scheduled service");
        invalid(() -> services.record(null,true),"null registration rejected");
    }
    private static void identitiesAndRoutes() {
        RailNetwork network=network();Train train=network.trains.get("Express");ScheduledServices services=new ScheduledServices();services.record(train,true);
        network.removeTrain(train.name);Train replacement=network.createTrain("Express",99,Collections.singletonList(102L),"R1","R2");
        network.setRoute(replacement.name,Arrays.asList("R9","R0"));
        check(!services.runningIntent(replacement)&&services.resumeCandidates(network).isEmpty(),"same-name replacement locomotive cannot inherit a running service");
        services.refresh(network);check(services.size()==0,"removed registrations are pruned");
        services.record(replacement,true);network.removeTrain(replacement.name);
        Train foreign=network.createTrain("Express",100,Collections.singletonList(102L),"R1","R2");network.setRoute(foreign.name,Arrays.asList("R9","R0"));
        check(!services.runningIntent(foreign),"a different owner cannot inherit automatic restart");
        services.refresh(network);services.record(foreign,true);
        network.setRoute(foreign.name,Arrays.asList("R8","R0"));
        check(!services.runningIntent(foreign)&&services.resumeCandidates(network).isEmpty(),"retasked route requires a new explicit start");
        services.refresh(network);check(services.size()==0,"retasked routes are pruned");
        services.record(foreign,true);network.setRoute(foreign.name,Collections.<String>emptyList());services.refresh(network);
        check(services.size()==0,"manual driving removes stale automatic service intent");
    }
    private static void progressAndCheckpoint() {
        RailNetwork network=network();Train train=network.trains.get("Express");ScheduledServices services=new ScheduledServices();
        train.routeIndex=1;train.dwellRemaining=4.5;services.record(train,true);
        ScheduledServices.Checkpoint saved=services.checkpoint();
        services.record(train,false);services.restore(saved);train.dwellRemaining=0;
        check(services.runningIntent(train)&&services.restoreProgress(train)&&train.dwellRemaining==4.5,"rollback checkpoint preserves intent and matching station dwell");
        train.routeIndex=0;train.dwellRemaining=3;
        check(!services.restoreProgress(train)&&train.routeIndex==0&&train.dwellRemaining==3,"stale sidecar never rewrites newer rail route cursor or dwell");
        train.routeIndex=1;List<RailNetwork.Pose> original=train.poses;RailNetwork.Pose pose=original.get(0);
        train.poses=Collections.singletonList(new RailNetwork.Pose(pose.carId,pose.x+.5f,pose.y,pose.rotation,pose.layer,pose.railName));
        check(!services.restoreProgress(train)&&train.dwellRemaining==3,"stale sidecar never restores dwell to a train that moved since save");
        train.poses=Collections.singletonList(new RailNetwork.Pose(pose.carId,pose.x,pose.y,pose.rotation,-1,pose.railName));
        check(!services.restoreProgress(train),"progress fingerprint includes tunnel layer");
        train.poses=original;train.dwellRemaining=2;services.refresh(network);train.dwellRemaining=0;
        check(services.restoreProgress(train)&&train.dwellRemaining==2,"periodic progress snapshot updates remaining dwell without changing run intent");
        services.restore(saved);train.dwellRemaining=0;services.restoreProgress(train);
        check(train.dwellRemaining==4.5,"checkpoint owns immutable intent records across later refresh");
        invalid(() -> services.restore(null),"missing checkpoint rejected");
    }
    private static void persistence() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-services-test-");Path file=directory.resolve("scheduled.properties"),railFile=directory.resolve("rail.properties");
        try {
            RailNetwork network=network();Train train=network.trains.get("Express");ScheduledServices services=ScheduledServices.load(file);
            check(services.size()==0,"missing first-install sidecar safely migrates to no running intents");
            train.routeIndex=1;train.dwellRemaining=6.25;train.maxSpeed=4;train.direction=-1;services.record(train,true);
            network.save(railFile);services.save(file,network);
            RailNetwork reloaded=RailNetwork.load(railFile);Train restored=reloaded.trains.get(train.name);ScheduledServices loaded=ScheduledServices.load(file);
            check(!restored.powered&&restored.speed==0,"rail reload remains stationary until native world and dispatcher are ready");
            check(loaded.resumeCandidates(reloaded).equals(Collections.singletonList(train.name)),"checked sidecar remembers the exact scheduled service across restart");
            check(loaded.restoreProgress(restored)&&restored.dwellRemaining==6.25,"remaining dwell survives restart when both snapshots agree");
            check(restored.routeIndex==1&&restored.maxSpeed==4&&restored.direction==-1,"intent restoration preserves rail route cursor, selected speed and physical traction");
            loaded.record(restored,false);loaded.save(file,reloaded);
            check(ScheduledServices.load(file).resumeCandidates(reloaded).isEmpty(),"explicit STOP is durably saved");
            Path backup=file.resolveSibling(file.getFileName()+".bak");byte[] previous=Files.readAllBytes(backup),stopped=Files.readAllBytes(file);
            Files.write(file,"corrupt".getBytes(StandardCharsets.UTF_8));
            io(() -> ScheduledServices.load(file),"corrupt STOP snapshot does not fall back to older START backup");
            Files.delete(file);io(() -> ScheduledServices.load(file),"missing primary with backup disables unsafe automatic restart");
            Files.write(file,"corrupt".getBytes(StandardCharsets.UTF_8));loaded.save(file,reloaded);
            check(Arrays.equals(previous,Files.readAllBytes(backup)),"writing after corruption preserves last checked backup");
            check(ScheduledServices.load(file).size()==0,"repaired stopped intent is valid and has no automatic candidates");
            Files.write(file,stopped);Properties p=read(file);
            p.setProperty("format","2");write(file,p);io(() -> ScheduledServices.load(file),"unsupported future schema rejected");
            services.save(file,network);Properties valid=read(file);
            for(String[] bad:new String[][]{{"service.0.locomotive","0"},{"service.0.name","bad name"},{"service.0.route","R1"},
                    {"service.0.routeIndex","2"},{"service.0.dwell","NaN"},{"service.0.dwell","3601"},{"service.0.x","Infinity"},
                    {"service.0.layer","1"},{"services","-1"},{"services","100001"},{"unexpected","field"}}) {
                Properties malformed=new Properties();malformed.putAll(valid);malformed.setProperty(bad[0],bad[1]);write(file,malformed);
                io(() -> ScheduledServices.load(file),"checked malformed service field rejected: "+bad[0]+"="+bad[1]);
            }
            Files.write(file,stopped);check(ScheduledServices.load(file).size()==0,"valid zero-service STOP remains readable after malformed files");
            long temporaries;try(java.util.stream.Stream<Path> paths=Files.list(directory)){temporaries=paths.filter(path->path.toString().endsWith(".tmp")).count();}
            check(temporaries==0,"atomic save leaves no temporary files");
        } finally {try(java.util.stream.Stream<Path> paths=Files.walk(directory)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static Properties read(Path file) throws IOException {
        Properties p=new Properties();try(InputStream input=Files.newInputStream(file)){p.load(input);}return p;
    }
    private static void write(Path file,Properties p) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum"))
            digest.update((key.length()+":"+key+p.getProperty(key).length()+":"+p.getProperty(key)+"\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));p.setProperty("checksum",hex.toString());
        try(OutputStream out=Files.newOutputStream(file)){p.store(out,"Checked test fixture");}
    }
    private interface Checked { void run() throws Exception; }
    private static void io(Checked task,String message) throws Exception {
        try {task.run();}catch(IOException expected){assertions++;return;}throw new AssertionError(message);
    }
    private static void invalid(Runnable task,String message) {
        try {task.run();}catch(IllegalArgumentException expected){assertions++;return;}throw new AssertionError(message);
    }
    private static void immutable(List<String> list,String message) {
        try {list.add("forged");}catch(UnsupportedOperationException expected){assertions++;return;}throw new AssertionError(message);
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
