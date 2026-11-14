package org.wurmrail.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import org.wurmrail.core.RailNetwork.Train;

/** Arrival authority, STOP, route edits, rollback and restart for pluggable stop services. */
public final class RouteStopServiceSelfTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        RailNetwork net=line();Train train=net.trains.get("Goods");
        net.routeStopServices=(t,rail)->rail.name.equals("R4");
        net.setRoute(train.name,Arrays.asList("R4","R10"),0);
        net.start(train.name);untilHeld(net,train);
        check(train.serviceStopRail.equals("R4")&&train.routeIndex==1,"normal arrival advances the same route cursor and holds its service");
        check(train.speed==0&&train.dwellRemaining>=1,"the service retains ordinary station occupancy");
        long serial=train.serviceStopSerial;float x=train.poses.get(0).x;
        net.tick(20);
        check(train.poses.get(0).x==x&&train.speed==0&&train.serviceStopRail!=null,"long servicing cannot depart when ordinary dwell expires");
        check(!net.releaseRouteServiceStop(train.name,"R10",serial),"wrong rail cannot release an arrival");
        check(!net.releaseRouteServiceStop(train.name,"R4",serial+1),"stale serial cannot release an arrival");
        RailNetwork.Checkpoint checkpoint=net.checkpoint(train.name);
        net.stop(train.name);
        check(train.serviceStopRail!=null&&!train.powered,"operator STOP pauses rather than forgetting a service");
        check(net.releaseRouteServiceStop(train.name,"R4",serial)&&!train.powered,"completion cannot override STOP");
        net.restore(checkpoint);
        check(train.powered&&train.serviceStopSerial==serial&&train.serviceStopRail.equals("R4"),"movement rollback restores service identity and hold");
        Path directory=Files.createTempDirectory("rail-service-stop-");Path file=directory.resolve("network.properties");
        try {
            net.save(file);RailNetwork loaded=RailNetwork.load(file);Train restored=loaded.trains.get(train.name);
            Properties valid=new Properties();try(InputStream in=Files.newInputStream(file)){valid.load(in);}
            Path invalid=directory.resolve("invalid.properties");
            try {
                for(String[] change:new String[][]{{"train.0.serviceStop","R10"},{"train.0.serviceSerial","0"},
                        {"train.0.serviceSerial","-1"},{"train.0.serviceStop",null},{"train.0.serviceSerial",null}}) {
                    Properties malformed=new Properties();malformed.putAll(valid);
                    if(change[1]==null)malformed.remove(change[0]);else malformed.setProperty(change[0],change[1]);
                    java.lang.reflect.Method hash=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);hash.setAccessible(true);
                    malformed.setProperty("checksum",(String)hash.invoke(null,malformed));
                    try(OutputStream out=Files.newOutputStream(invalid)){malformed.store(out,"Checked malformed service arrival");}
                    boolean rejected=false;try{RailNetwork.load(invalid);}catch(IOException expected){rejected=true;}
                    check(rejected,"malformed optional service arrival cannot start or skip a freight stop");
                }
            } finally {Files.deleteIfExists(invalid);}
            check(!restored.powered&&restored.serviceStopSerial==serial&&"R4".equals(restored.serviceStopRail),"restart retains the pending service without starting a train");
            loaded.resumeScheduled(restored.name);loaded.tick(10);
            check(restored.speed==0&&restored.poses.get(0).x==x,"a resumed service waits for native servicing before moving");
            loaded.releaseRouteServiceStop(restored.name,"R4",serial);
            for(int i=0;i<100;i++)loaded.tick(.1);
            check(restored.poses.get(0).x>x,"completed service continues the existing itinerary");
            check(net.releaseRouteServiceStop(train.name,"R4",serial),"matching completion releases the pending service");
            net.stop(train.name);net.setRoute(train.name,Arrays.asList("R4","R10"),0);net.start(train.name);untilHeld(net,train);
            check(train.serviceStopSerial>serial,"repeated visit gets a new arrival identity");
            net.stop(train.name);net.setRoute(train.name,Collections.<String>emptyList());
            check(train.serviceStopRail==null&&train.dwellRemaining==0,"manual route replacement cancels the service");
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(file.resolveSibling("network.properties.bak"));Files.deleteIfExists(directory);
        }
        RailNetwork ordinary=line();Train passenger=ordinary.trains.get("Goods");
        ordinary.setRoute(passenger.name,Arrays.asList("R4","R10"),.2);ordinary.start(passenger.name);
        for(int i=0;i<200;i++)ordinary.tick(.1);
        check(passenger.serviceStopRail==null,"no registered handler preserves normal station operation");
        RailNetwork together=line();Train mixed=together.trains.get("Goods");
        together.routeStopServices=(t,r)->r.name.equals("R4");together.passengerStops=(t,r,c)->r.name.equals("R4");
        together.setRoute(mixed.name,Arrays.asList("R4","R10"),0);together.start(mixed.name);untilHeld(together,mixed);
        check(mixed.passengerStopRail!=null,"passenger servicing can coexist with the same committed arrival");
        together.releasePassengerStop(mixed.name,mixed.passengerStopRail,mixed.passengerStopSerial);
        check(mixed.serviceStopRail!=null,"passenger completion cannot release a freight hold");
        together.stop(mixed.name);together.reverse(mixed.name);
        check(mixed.serviceStopRail==null,"explicit manual reverse cancels pending servicing");
        System.out.println("RouteStopServiceSelfTest: "+assertions+" assertions passed.");
    }
    private static RailNetwork line() {
        RailNetwork net=new RailNetwork();
        for(int i=0;i<=12;i++){net.addNode("R"+i,20+i,20,0);if(i>0)net.connect("R"+(i-1),"R"+i);}
        net.createTrain("Goods",7,Collections.singletonList(100L),"R1","R2");return net;
    }
    private static void untilHeld(RailNetwork net,Train train) {
        for(int i=0;i<1000&&train.serviceStopRail==null;i++)net.tick(.1);
        check(train.serviceStopRail!=null,"scheduled train reaches its freight arrival");
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
