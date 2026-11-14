package org.wurmrail.core;

import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.SteamYarders.Yarder;
import org.wurmrail.core.SteamYarders.HarvestAge;
import org.wurmrail.core.SteamYarderService.Result;

/** Real clock/cursor scheduler tests; native forestry and storage have separate integration tests. */
public final class SteamYarderServiceSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        cadenceAndWaiting();circleAndCursor();outputStops();controlsAndRestart();boundedFleet();validation();
        System.out.println("SteamYarderServiceSelfTest: "+assertions+" assertions passed.");
    }
    private static final class World implements SteamYarderService.World {
        int preflights,calls,harvests;
        boolean allRipe,removeRipe;
        String unavailable;
        RuntimeException fail;
        final Set<Integer> ripe=new HashSet<Integer>();
        final Map<Integer,Result> result=new HashMap<Integer,Result>();
        final List<Integer> indices=new ArrayList<Integer>(),species=new ArrayList<Integer>();
        final List<HarvestAge> ages=new ArrayList<HarvestAge>();
        final List<Long> machineOrder=new ArrayList<Long>();
        @Override public String unavailableReason(Yarder yarder){preflights++;return unavailable;}
        @Override public Result harvest(Yarder yarder,int x,int y) {
            calls++;if(!SteamYarders.contains(yarder,x,y))throw new AssertionError("attempt outside managed woodlot");
            int index=-1;for(int i=0;i<SteamYarders.WOODLOT.size();i++){SteamYarders.Offset p=SteamYarders.WOODLOT.get(i);if(yarder.tileX+p.dx==x&&yarder.tileY+p.dy==y)index=i;}
            if(index<0)throw new AssertionError("nonmanaged candidate");if(fail!=null)throw fail;
            Result chosen=result.get(index);if(chosen==null)chosen=allRipe||ripe.contains(index)?Result.harvested("Native timber stored."):Result.skip("Not ready.");
            if(chosen.completedAction){harvests++;indices.add(index);species.add(yarder.species);ages.add(yarder.harvestAge);machineOrder.add(yarder.itemId);if(removeRipe)ripe.remove(index);}
            return chosen;
        }
    }
    private static SteamYarders registry() {
        SteamYarders store=new SteamYarders();store.put(new Yarder(100,900,store.nextRevision(),12,14,0,800,3,HarvestAge.OLD,0,false,"Stopped."));return store;
    }
    private static void seconds(SteamYarderService service,int count){for(int i=0;i<count;i++)service.tick(1);}
    private static void cadenceAndWaiting() {
        SteamYarders store=registry();World world=new World();SteamYarderService service=new SteamYarderService(store,world,30);
        service.start(100);check(store.get(100).running&&service.dirty()&&world.calls==0,"Start saves intent without cutting or planting a tree");service.clearDirty();
        int preflights=world.preflights;for(int i=0;i<299;i++)service.tick(.1);
        check(world.calls==0&&world.preflights==preflights&&!service.dirty(),"waiting polls do not inspect trees/storage or rewrite state");
        check(Math.abs(service.secondsUntilAction(100)-.1)<.00001,"a harvest opportunity earns the full thirty-second interval");
        service.tick(.1);check(world.calls==81&&world.harvests==0&&store.get(100).running&&store.get(100).status.contains("Waiting for managed trees"),"unripe/empty woodlot stays running and waits for normal growth");
        check(world.ripe.isEmpty()&&world.species.isEmpty()&&world.ages.isEmpty(),"waiting never artificially ages trees or afforests bare ground");
        service.clearDirty();seconds(service,30);
        check(world.calls==162&&!service.dirty(),"an unchanged waiting woodlot inspects only on the next due interval and avoids repeated state writes");
        world.ripe.add(7);seconds(service,29);check(world.harvests==0,"a newly naturally mature tree is not harvested instantly between due opportunities");
        service.tick(1);check(world.indices.equals(Arrays.asList(7))&&store.get(100).nextTile==8,"natural maturity makes the next scheduled opportunity harvest once");
        check(world.species.equals(Arrays.asList(3))&&world.ages.equals(Arrays.asList(HarvestAge.OLD)),"native adapter receives selected replant species and harvest age unchanged");
    }
    private static void circleAndCursor() {
        SteamYarders store=registry();World world=new World();world.ripe.addAll(Arrays.asList(0,5,18));
        SteamYarderService service=new SteamYarderService(store,world,1);service.start(100);long revision=store.get(100).revision;
        for(int i=0;i<6;i++)service.tick(1);
        check(world.indices.equals(Arrays.asList(0,5,18,0,5,18)),"physical tile cursor visits all mature trees without repeatedly exhausting the first tile");
        check(store.get(100).revision==revision&&store.get(100).nextTile==19,"harvest progress persists cursor without changing configuration revision");
        check(world.harvests==6,"each due opportunity completes at most one harvest/replant action");
        world.removeRipe=true;for(int i=0;i<4;i++)service.tick(1);
        check(world.ripe.isEmpty()&&store.get(100).running&&store.get(100).status.contains("Waiting"),"replanted young trees return to normal waiting rather than repeated automatic cutting");
        int calls=world.calls;service.tick(1);check(world.calls-calls==81,"an exhausted search is bounded by the81-circle candidates");
        check(world.indices.stream().noneMatch(index->index<0||index>=81),"no harvest index can leave the declared woodlot");
    }
    private static void outputStops() {
        for(String reason:new String[]{"Log Bunk full.","Log Bunk missing.","Bunk ownership changed.","Yarder moved from its configured anchor.","Output timber is retained for inspection."}) {
            SteamYarders store=registry();World world=new World();world.allRipe=true;SteamYarderService service=new SteamYarderService(store,world,1);service.start(100);
            world.unavailable=reason;service.tick(1);
            check(!store.get(100).running&&store.get(100).status.contains(reason)&&world.calls==0,"unsafe output stops before native tree mutation: "+reason);
            int checks=world.preflights;seconds(service,100);check(world.preflights==checks&&world.calls==0,"stopped output performs no continued world work");
        }
        SteamYarders store=registry();World world=new World();world.result.put(0,Result.stopAfterHarvest("Timber retained safely; output changed."));
        SteamYarderService service=new SteamYarderService(store,world,1);service.start(100);service.tick(1);
        check(!store.get(100).running&&store.get(100).nextTile==1&&world.harvests==1,"completed native harvest with pending output advances cursor and stops");
        store=registry();world=new World();world.result.put(0,Result.stop("No item was harvested."));service=new SteamYarderService(store,world,1);service.start(100);service.tick(1);
        check(store.get(100).nextTile==0&&world.harvests==0&&!store.get(100).running,"preflight failure consumes neither tree nor harvest position");
        store=registry();world=new World();world.fail=new IllegalStateException("native\nforest failure");service=new SteamYarderService(store,world,1);service.start(100);service.tick(1);
        check(!store.get(100).running&&store.get(100).status.contains("native forest failure")&&!store.get(100).status.contains("\n"),"native errors stop only this yarder with bounded safe diagnostics");
        store=registry();Yarder old=store.get(100);store.put(new Yarder(old.itemId,old.ownerId,old.revision,old.tileX,old.tileY,0,0,old.species,old.harvestAge,0,false,"Unlinked."));
        world=new World();service=new SteamYarderService(store,world,1);service.start(100);
        check(!store.get(100).running&&store.get(100).status.contains("No Log Bunk")&&world.preflights==0&&world.calls==0,"unlinked yarders stop without touching any tree");
    }
    private static void controlsAndRestart()throws Exception {
        SteamYarders store=registry();World world=new World();world.allRipe=true;SteamYarderService service=new SteamYarderService(store,world,30);service.start(100);
        long revision=store.get(100).revision;seconds(service,29);service.stop(100);
        check(!store.get(100).running&&store.get(100).revision>revision&&service.secondsUntilAction(100)==0,"STOP invalidates stale control windows and resets elapsed allowance");
        seconds(service,100);check(world.calls==0,"STOP never grows or harvests while inactive");service.start(100);seconds(service,29);check(world.harvests==0,"restart does not inherit old elapsed allowance");service.tick(1);
        check(world.indices.equals(Arrays.asList(0)),"restart completes one full fresh interval");
        Path directory=Files.createTempDirectory("wurmrail-yarders-restart-");
        try {
            Path file=directory.resolve("yarders.properties");seconds(service,29);store.save(file);SteamYarders loaded=SteamYarders.load(file);
            World after=new World();after.allRipe=true;SteamYarderService restarted=new SteamYarderService(loaded,after,30);seconds(restarted,29);
            check(after.calls==0,"server restart does not create instant timber or offline catch-up");restarted.tick(1);
            check(after.indices.equals(Arrays.asList(1)),"saved cursor resumes at the following managed tile");
        }finally{SteamYardersSelfTest.cleanup(directory);}
        World pausedWorld=new World();pausedWorld.allRipe=true;
        SteamYarderService paused=new SteamYarderService(registry(),pausedWorld,30);paused.start(100);
        paused.tick(1000000);check(pausedWorld.harvests==0&&paused.secondsUntilAction(100)==25,"long pauses add no more than five seconds of active allowance");seconds(paused,25);
        check(pausedWorld.harvests==1,"remaining online time eventually completes one action");
        final SteamYarders[] active={registry()};World replacement=new World();replacement.allRipe=true;
        SteamYarderService supplied=new SteamYarderService(()->active[0],replacement,1);supplied.start(100);supplied.tick(.5);
        active[0]=active[0].copy();Yarder before=active[0].get(100);
        active[0].put(new Yarder(100,900,active[0].nextRevision(),12,14,0,800,9,HarvestAge.VERY_OLD,4,true,"Reconfigured."));
        supplied.tick(.5);check(replacement.calls==0,"reconfiguring species/age/cursor discards the old due clock");supplied.tick(.5);
        check(replacement.species.equals(Arrays.asList(9))&&replacement.ages.equals(Arrays.asList(HarvestAge.VERY_OLD))&&replacement.indices.equals(Arrays.asList(4)),"new configuration is the only one sent to native harvest");
        supplied.reset(100);supplied.tick(.5);check(replacement.harvests==1,"reset does not reuse elapsed time");supplied.tick(.5);check(replacement.harvests==2,"reset running yarder is rescheduled correctly");
        active[0].remove(100);supplied.tick(5);check(replacement.harvests==2,"removed IDs cannot execute stale queued actions");
    }
    private static void boundedFleet() {
        SteamYarders store=new SteamYarders();World world=new World();world.allRipe=true;
        for(int i=1;i<=100;i++)store.put(new Yarder(i,900,store.nextRevision(),i,20,0,800,0,HarvestAge.MATURE,0,true,"Running."));
        SteamYarderService service=new SteamYarderService(store,world,.1);service.tick(.1);check(world.harvests==32,"shared native action budget caps synchronized woodlots");
        service.tick(0);service.tick(0);service.tick(0);
        check(world.harvests==100&&new HashSet<Long>(world.machineOrder).size()==100,"due-order fairness services each yarder before repeating an early machine");
        check(world.machineOrder.get(0)==1&&world.machineOrder.get(99)==100,"equal-time work is deterministic by itemID");
        service.tick(1000000);check(world.harvests==132,"server pauses never multiply a yarder's actions within one poll");service.close();service.tick(0);
        check(world.harvests==132,"close discards all stale due clocks");
    }
    private static void validation() {
        SteamYarders store=registry();World world=new World();
        for(double interval:new double[]{0,.09,-1,Double.NaN,Double.POSITIVE_INFINITY,86401})invalid(()->new SteamYarderService(store,world,interval),"invalid harvest interval");
        SteamYarderService service=new SteamYarderService(store,world,30);
        for(double elapsed:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})invalid(()->service.tick(elapsed),"invalid elapsed time");
        invalid(()->service.start(999),"unknown machine cannot start");invalid(()->service.stop(999),"unknown machine cannot stop");
        check(service.secondsUntilAction(999)==0,"missing machine has no elapsed harvest allowance");
    }
    private interface Work{void run();}
    private static void invalid(Work work,String message){try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
