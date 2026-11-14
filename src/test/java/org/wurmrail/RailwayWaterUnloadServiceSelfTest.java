package org.wurmrail;

import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.WaterUnloadStops;
import org.wurmrail.core.WaterUnloadStops.Stop;
import org.wurmrail.RailwayWaterFillService.Transfer;

/** Arrival/volume timing and shared-stop authority, independently of native liquid representation. */
public final class RailwayWaterUnloadServiceSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        passive();timingAndTanks();restart();fractionalAndBounds();destinationCapacity();editsAndIsolation();unavailableAndFaults();
        System.out.println("RailwayWaterUnloadServiceSelfTest: "+assertions+" assertions passed.");
    }
    private static final class Fixture {
        final RailNetwork net;
        final Train train;
        final WaterUnloadStops stops=new WaterUnloadStops();
        final NativeWorld world;
        final RailwayWaterUnloadService service;
        int faults;
        Fixture(double rate,long... capacities) {
            net=line();train=net.trains.get("WaterTrain");world=new NativeWorld(capacities);
            stops.put(stop(net,rate,1));
            service=new RailwayWaterUnloadService(net,()->stops,world,(t,r)->faults++);
            net.routeStopServices=service::shouldService;
        }
        void arrive() {net.setRoute(train.name,Arrays.asList("R4","R10"),1);net.start(train.name);untilHeld(net,train);}
        void step(double elapsed){net.tick(Math.min(elapsed,.25));service.serviceStops(elapsed);}
    }
    private static final class NativeWorld implements RailwayWaterUnloadService.World {
        final long[] capacity,stored;
        long destinationCapacity=Long.MAX_VALUE;
        int checks,fills;
        long lastAllowance,maxAllowance;
        String unavailable;
        boolean backpressure,throwNative,returnFault,invalidAccounting,nullResult;
        NativeWorld(long... capacity){this.capacity=capacity;stored=new long[capacity.length];}
        @Override public String unavailableReason(Train train,Stop stop){checks++;return unavailable;}
        @Override public Transfer unload(Train train,Stop stop,long allowance) {
            fills++;lastAllowance=allowance;maxAllowance=Math.max(maxAllowance,allowance);
            if(throwNative)throw new IllegalStateException("native mutation result uncertain");
            if(returnFault)return Transfer.fault(0,"Native liquid parent/weight links require inspection.");
            if(invalidAccounting)return Transfer.ongoing(allowance+1,"invalid native accounting");
            if(nullResult)return null;
            if(backpressure)return Transfer.ongoing(0,"Waiting for native output.");
            long remaining=Math.min(allowance,Math.max(0,destinationCapacity-total())),filled=0;boolean empty=true;
            for(int i=0;i<stored.length;i++) {
                long moved=Math.min(remaining,capacity[i]-stored[i]);stored[i]+=moved;remaining-=moved;filled+=moved;
                empty&=stored[i]==capacity[i];
            }
            return empty||total()>=destinationCapacity?Transfer.completed(filled,empty?"Water unloading complete: compatible Tank Cars are empty.":"Water unloading complete: destination tank is full.")
                    :Transfer.ongoing(filled,"Unloading compatible Tank Cars.");
        }
        long total(){long total=0;for(long value:stored)total+=value;return total;}
    }
    private static void passive() {
        Fixture f=new Fixture(100,1000000);
        boolean routeable=true;
        for(int i=0;i<200;i++){routeable&=f.service.shouldService(f.train,f.net.nodes.get("R4"));f.service.serviceStops(.1);}
        check(routeable,"known native marker remains routeable without source polling");
        check(f.world.checks==0&&f.world.fills==0&&f.service.activeVisits()==0,"passive definitions and non-arrived trains never inspect a source or tank");
        check(!f.service.shouldService(f.train,f.net.nodes.get("R5"))&&!f.service.shouldService(f.train,null),"ordinary rail and absent rail are ignored");
        f.net.nodes.get("R4").markerId++;
        check(!f.service.shouldService(f.train,f.net.nodes.get("R4")),"stale physical marker cannot claim a new arrival");
        check(f.service.status("missing").isEmpty()&&f.service.statusForRail("R4").startsWith("Ready"),"idle UI status is read-only and clear");
    }
    private static void timingAndTanks() {
        Fixture f=new Fixture(100,150000,250000);f.arrive();int cursor=f.train.routeIndex;long serial=f.train.serviceStopSerial;
        f.service.serviceStops(500);
        check(f.world.total()==0&&f.world.checks==0&&f.service.activeVisits()==1,"arrival does not grant unloading time even when given a large first interval");
        check(f.service.statusForRail("R4").contains("WaterTrain")&&f.service.status("WaterTrain").contains("preparing"),"active visit is visible without polling native storage");
        for(int i=0;i<3;i++)f.step(.25);
        check(f.world.total()==0&&f.world.fills==0,"subsecond updates wait for the scheduled transfer interval");
        f.step(.25);
        check(f.world.total()==100000&&f.world.stored[0]==100000&&f.world.stored[1]==0,"one second earns100L once for the entire consist");
        check(f.train.serviceStopRail.equals("R4")&&f.train.speed==0&&f.train.routeIndex==cursor,"ongoing filling retains the existing route cursor and stationary service hold");
        f.net.stop(f.train.name);int calls=f.world.fills;
        f.step(1000);
        check(f.world.total()==100000&&f.world.fills==calls&&!f.train.powered&&f.train.serviceStopSerial==serial,"operator STOP freezes transfer and preserves the exact arrival identity");
        check(f.service.statusForRail("R4").contains("Paused"),"paused unloading is distinguishable from ready state");
        f.net.start(f.train.name);f.step(.75);
        check(f.world.total()==100000,"time while stopped cannot become resumed unloading credit");
        f.step(.25);
        check(f.world.stored[0]==150000&&f.world.stored[1]==50000,"one shared allowance finishes the first tank and continues into the next");
        f.step(1);f.step(1);
        check(f.world.total()==400000&&f.train.serviceStopRail==null&&f.train.powered,"all empty Tank Cars release the normal hold without overriding scheduled power");
        check(f.service.activeVisits()==0&&f.service.statusForRail("R4").contains("400.0 litres"),"completion reports actual total volume and releases in-memory visit state");
        check(f.train.automated&&f.train.routeIndex==cursor,"liquid service never substitutes its own route or itinerary cursor");
    }
    private static void restart()throws Exception {
        Fixture original=new Fixture(100,500000);original.arrive();original.service.serviceStops(.1);original.step(1);
        Path directory=Files.createTempDirectory("wurmrail-water-service-");Path file=directory.resolve("network.properties");
        try {
            original.net.stop(original.train.name);original.net.save(file);original.service.close();
            RailNetwork loaded=RailNetwork.load(file);Train train=loaded.trains.get("WaterTrain");
            check(!train.powered&&"R4".equals(train.serviceStopRail),"saved liquid arrival is retained while a restarted train remains stopped");
            RailwayWaterUnloadService resumed=new RailwayWaterUnloadService(loaded,()->original.stops,original.world);loaded.routeStopServices=resumed::shouldService;
            loaded.resumeScheduled(train.name);resumed.serviceStops(1000);
            check(original.world.total()==100000,"restart creates a new zero-credit native visit rather than filling instantly");
            resumed.serviceStops(.75);check(original.world.total()==100000,"resumed service earns a full new interval");
            resumed.serviceStops(.25);check(original.world.total()==200000,"restarted visit transfers only one newly earned allowance into existing real contents");
            resumed.close();check(resumed.activeVisits()==0&&resumed.statusForRail("R4").startsWith("Ready"),"closing a service clears ephemeral sessions and status");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(directory.resolve("network.properties.bak"));Files.delete(directory);}
    }
    private static void fractionalAndBounds() {
        Fixture f=new Fixture(.1234,1000000);f.arrive();f.service.serviceStops(.1);
        for(int i=0;i<100;i++)f.step(1);
        check(f.world.total()==12340,"fractional litres-per-second retain fractional millilitre credit without rate drift");
        check(f.world.maxAllowance==124,"native transfer budgets are quantized to actual millilitres, never whole artificial water stacks");
        Fixture pausedServer=new Fixture(100,10000000);pausedServer.arrive();pausedServer.service.serviceStops(.1);pausedServer.step(100000);
        check(pausedServer.world.total()==500000,"a server pause earns at most five seconds of bounded filling rather than an instant full tank");
        Fixture blocked=new Fixture(100,10000000);blocked.arrive();blocked.service.serviceStops(.1);blocked.world.backpressure=true;
        for(int i=0;i<100;i++)blocked.step(1);
        blocked.world.backpressure=false;blocked.step(1);
        check(blocked.world.total()==500000,"native backpressure cannot build unlimited deferred volume credit");
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            try{f.service.serviceStops(invalid);throw new AssertionError("invalid elapsed accepted");}catch(IllegalArgumentException expected){assertions++;}
        }
        try{Transfer.ongoing(-1,"bad");throw new AssertionError("negative volume accepted");}catch(IllegalArgumentException expected){assertions++;}
    }
    private static void destinationCapacity() {
        Fixture full=new Fixture(100,300000,400000);full.world.destinationCapacity=125000;
        full.arrive();full.service.serviceStops(.1);full.step(1);full.step(1);
        check(full.world.total()==125000&&full.world.stored[0]==125000&&full.world.stored[1]==0,
                "a shared allowance stops exactly at destination capacity without draining the other car");
        check(full.train.serviceStopRail==null&&full.train.powered&&full.service.statusForRail("R4").contains("full"),
                "full destination resumes the ordinary itinerary with remaining source water untouched");
        long remaining=0;for(int i=0;i<full.world.capacity.length;i++)remaining+=full.world.capacity[i]-full.world.stored[i];
        check(remaining+full.world.total()==700000,"unloading conserves total source and destination volume");
        Fixture noCapacity=new Fixture(100,100000);noCapacity.world.destinationCapacity=0;
        noCapacity.arrive();noCapacity.service.serviceStops(.1);noCapacity.step(1);
        check(noCapacity.world.total()==0&&noCapacity.train.serviceStopRail==null&&noCapacity.train.powered,
                "an already full destination transfers nothing and does not strand the train");
    }
    private static void editsAndIsolation() {
        Fixture ordinary=new Fixture(100,1000000);
        ordinary.net.routeStopServices=(t,n)->n.name.equals("R6");ordinary.net.setRoute(ordinary.train.name,Arrays.asList("R6","R10"),1);
        ordinary.net.start(ordinary.train.name);untilHeld(ordinary.net,ordinary.train);
        long foreignSerial=ordinary.train.serviceStopSerial;ordinary.service.serviceStops(10);
        check("R6".equals(ordinary.train.serviceStopRail)&&ordinary.train.serviceStopSerial==foreignSerial&&ordinary.world.checks==0,
                "an unknown freight or other-service hold is never released by the water service");
        Fixture removed=new Fixture(100,1000000);removed.arrive();removed.service.serviceStops(.1);removed.stops.remove("R4");removed.step(1);
        check(removed.train.serviceStopRail==null&&removed.world.total()==0,"a known visit removed during service cancels without creating water");
        Fixture edited=new Fixture(100,1000000);edited.arrive();edited.service.serviceStops(.1);edited.stops.put(stop(edited.net,200,2));edited.step(1);
        check(edited.train.serviceStopRail==null&&edited.world.fills==0&&edited.service.statusForRail("R4").contains("reconfigured"),"editing a station cannot silently apply new rate/source settings to an old visit");
        Fixture reverse=new Fixture(100,1000000);reverse.arrive();reverse.service.serviceStops(.1);reverse.net.stop(reverse.train.name);reverse.net.reverse(reverse.train.name);reverse.service.serviceStops(1);
        check(reverse.service.activeVisits()==0&&reverse.world.total()==0,"manual reverse cancels visit authority before any later filling");
        Fixture newer=new Fixture(100,1000000);newer.arrive();newer.service.serviceStops(.1);long first=newer.train.serviceStopSerial;
        newer.train.serviceStopSerial++;newer.service.serviceStops(100);
        check(newer.world.total()==0&&newer.train.serviceStopSerial==first+1&&newer.service.activeVisits()==1,"a new serial gets a new zero-credit visit rather than stale completion or allowance");
        newer.net.stop(newer.train.name);newer.net.setRoute(newer.train.name,Collections.<String>emptyList());newer.service.serviceStops(1);
        check(newer.service.activeVisits()==0,"route replacement invalidates the previous water visit");
    }
    private static void unavailableAndFaults() {
        for(String reason:new String[]{"No compatible owned Tank Cars are attached.","The destination water tank has no capacity.","The station marker is missing."}) {
            Fixture f=new Fixture(100,1000000);f.arrive();f.service.serviceStops(.1);f.world.unavailable=reason;f.step(1);
            check(f.train.serviceStopRail==null&&f.train.powered&&f.world.fills==0&&f.service.statusForRail("R4").contains(reason),
                    "unavailable destination/car/marker gives a clear reason and resumes the ordinary itinerary without filling");
        }
        Fixture full=new Fixture(100,0);full.arrive();full.service.serviceStops(.1);full.step(1);
        check(full.train.serviceStopRail==null&&full.world.total()==0&&full.service.statusForRail("R4").contains("empty"),"already empty tanks complete without adding liquid");
        for(int mode=0;mode<4;mode++) {
            Fixture f=new Fixture(100,1000000);f.arrive();f.service.serviceStops(.1);
            if(mode==0)f.world.throwNative=true;else if(mode==1)f.world.returnFault=true;else if(mode==2)f.world.invalidAccounting=true;else f.world.nullResult=true;
            f.step(1);
            check(!f.train.powered&&"R4".equals(f.train.serviceStopRail)&&f.faults==1,"uncertain native mutation/accounting stops and retains the exact hold for operator review");
            int calls=f.world.fills;f.net.start(f.train.name);f.step(1);
            check(f.world.fills==calls&&!f.train.powered&&f.faults==2,"power-on cannot silently retry an unresolved native liquid fault");
        }
    }
    private static RailNetwork line() {
        RailNetwork net=new RailNetwork();for(int i=0;i<=12;i++) {
            Node node=net.addNode("R"+i,20+i,20,0);node.markerId=100+i;if(i>0)net.connect("R"+(i-1),node.name);
        }
        net.createTrain("WaterTrain",7,Arrays.asList(100L,101L,102L),"R2","R3");return net;
    }
    private static Stop stop(RailNetwork net,double rate,long revision){return new Stop("R4",net.nodes.get("R4").markerId,7,revision,"Lake Water",800,rate);}
    private static void untilHeld(RailNetwork net,Train train){for(int i=0;i<1000&&train.serviceStopRail==null;i++)net.tick(.1);check(train.serviceStopRail!=null,"train arrives through the existing routing hold");}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
