package org.wurmrail.core;

import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.SteamWaterPumps.Pump;
import org.wurmrail.core.SteamWaterPumpService.Result;

public final class SteamWaterPumpServiceSelfTest {
    private static int assertions;
    private static final class World implements SteamWaterPumpService.World {
        long received,lastAllowance;int calls;boolean full,fault,missing,overreport;
        public Result pump(Pump p,long allowance){calls++;lastAllowance=allowance;if(overreport)return Result.pumped(allowance+1,"Invalid accounting");if(fault)return Result.fault(0,"Stopped for native recovery.");if(full)return Result.waiting("Waiting: tank full; automatic resume.");if(missing)return Result.waiting("Waiting: tank missing.");received+=allowance;return Result.pumped(allowance,"Pumping water.");}
    }
    public static void main(String[] args)throws Exception {
        SteamWaterPumps store=new SteamWaterPumps();store.put(pump(1,false));World world=new World();SteamWaterPumpService service=new SteamWaterPumpService(store,world,100);
        service.start(1);service.tick(.5);check(world.received==0,"starting produces no immediate water");service.tick(.5);check(world.received==100000&&world.calls==1,"one earned second produces exactly the configured native millilitres");service.tick(0);check(world.calls==1,"zero-time polling cannot duplicate production");service.tick(60);check(world.received==200000&&world.calls==2,"a stalled server produces at most one interval, never a catch-up burst");
        world.full=true;service.tick(1);check(store.get(1).running&&store.get(1).status.contains("full")&&world.received==200000,"full storage pauses but retains automatic intent");service.clearDirty();service.tick(1);check(!service.dirty(),"an unchanged blocked status does not rewrite durable machine controls each poll");world.full=false;service.tick(1);check(world.received==300000&&world.lastAllowance==100000,"new capacity resumes at normal rate without banked credit");
        world.missing=true;service.tick(1);check(store.get(1).running&&store.get(1).status.contains("missing"),"missing storage is clearly reported and produces no water");world.missing=false;service.tick(1);check(world.received==400000,"restored storage resumes a configured pump");
        service.stop(1);long before=world.received;service.tick(60);check(!store.get(1).running&&world.received==before,"manual STOP prevents all production and stays stopped");
        service.start(1);world.fault=true;service.tick(1);check(!store.get(1).running&&store.get(1).status.contains("recovery"),"uncertain native accounting stops intent for operator recovery");world.fault=false;service.tick(10);check(world.received==before,"native faults never auto-retry new water production");
        service.start(1);world.overreport=true;service.tick(1);check(!store.get(1).running&&world.received==before,"native over-reporting is rejected and stops production");world.overreport=false;
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY}){boolean rejected=false;try{service.tick(invalid);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid poll elapsed time is rejected");}
        for(double invalid:new double[]{0,.0001,Double.NaN,100001}){boolean rejected=false;try{new SteamWaterPumpService(store,world,invalid);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"invalid pump rates are rejected");}
        SteamWaterPumps many=new SteamWaterPumps();World each=new World();for(int i=1;i<=40;i++)many.put(pump(i,true));SteamWaterPumpService bounded=new SteamWaterPumpService(many,each,.001);bounded.tick(1);check(each.calls==32&&each.received==32,"global per-poll action bound fairly handles many producers");bounded.tick(0);check(each.calls==40&&each.received==40,"deferred due machines receive their one earned interval, with no duplicate machine work");bounded.tick(0);check(each.calls==40,"idle polls perform no machine/storage/world scans");
        Path dir=Files.createTempDirectory("wurmrail-pump-restart-");Path file=dir.resolve("pumps.properties");try{store.put(pump(1,true));store.save(file);World resumed=new World();SteamWaterPumpService restarted=new SteamWaterPumpService(SteamWaterPumps.load(file),resumed,100);restarted.tick(0);restarted.tick(.99);check(resumed.received==0,"saved power intent receives a fresh interval after restart");restarted.tick(.01);check(resumed.received==100000,"restart resumes the configured rate without offline production");}finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
        service.close();System.out.println("SteamWaterPumpServiceSelfTest: "+assertions+" assertions passed.");
    }
    private static Pump pump(long id,boolean running){return new Pump(id,2,1,10,10,0,3,11,10,0,running,"Waiting.");}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
