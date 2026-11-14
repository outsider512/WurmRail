package org.wurmrail.core;

import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.SteamRockDrills.Drill;
import org.wurmrail.core.SteamRockDrillService.Result;

/** Timed cardinal mining, changing faces, output stops and bounded machine scheduling. */
public final class SteamRockDrillServiceSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        timingAndRoundRobin();changingFaces();outputStops();controlsAndRestart();boundedFleet();validation();
        System.out.println("SteamRockDrillServiceSelfTest: "+assertions+" assertions passed.");
    }
    private static final class World implements SteamRockDrillService.World {
        int available=15,checks,inspections,calls;
        String unavailable;
        final List<Integer> mined=new ArrayList<Integer>();
        final List<Long> machineOrder=new ArrayList<Long>();
        final Map<Integer,Result> outcomes=new HashMap<Integer,Result>();
        RuntimeException fail;
        boolean depleteMinedFace;
        @Override public String unavailableReason(Drill drill){checks++;return unavailable;}
        @Override public int availableFaces(Drill drill){inspections++;return available;}
        @Override public Result mine(Drill drill,int face) {
            calls++;machineOrder.add(drill.itemId);if(fail!=null)throw fail;
            Result result=outcomes.get(face);if(result==null)result=Result.mined("Real resource transferred.");
            if(result.completedAction){mined.add(face);if(depleteMinedFace)available&=~(1<<face);}return result;
        }
    }
    private static SteamRockDrills registry(int mask) {
        SteamRockDrills store=new SteamRockDrills();store.put(new Drill(100,900,store.nextRevision(),12,14,-1,800,mask,0,false,"Stopped."));return store;
    }
    private static void seconds(SteamRockDrillService service,int seconds){for(int i=0;i<seconds;i++)service.tick(1);}
    private static void timingAndRoundRobin() {
        SteamRockDrills store=registry(7);World world=new World();SteamRockDrillService service=new SteamRockDrillService(store,world,30);
        service.start(100);check(store.get(100).running&&service.dirty(),"explicit start records the running setting");
        long revision=store.get(100).revision;service.clearDirty();
        int checks=world.checks,inspections=world.inspections;
        for(int i=0;i<299;i++)service.tick(.1);
        check(world.calls==0&&Math.abs(service.secondsUntilAction(100)-.1)<.00001,"a normal action earns the full thirty-second interval");
        check(world.checks==checks&&world.inspections==inspections&&!service.dirty(),"waiting polls do no native inspection, world search or state write");
        service.tick(.1);check(world.mined.equals(Arrays.asList(0)),"first completed thirty-second turn mines North once");
        check(store.get(100).nextFace==1&&store.get(100).revision==revision&&service.dirty(),"success advances cursor without invalidating current control forms");
        seconds(service,150);
        check(world.mined.equals(Arrays.asList(0,1,2,0,1,2)),"enabled North East South faces receive equal round-robin actions");
        check(service.currentFace(100)==0,"explicit current-face inspection reports the next enabled physical face");
    }
    private static void changingFaces() {
        SteamRockDrills store=registry(15);World world=new World();SteamRockDrillService service=new SteamRockDrillService(store,world,1);
        service.start(100);service.tick(1);world.available=14;service.tick(1);service.tick(1);service.tick(1);service.tick(1);
        check(world.mined.equals(Arrays.asList(0,1,2,3,1)),"a depleted North face is skipped without starving other enabled sides");
        world.available=0;service.tick(1);
        check(!store.get(100).running&&store.get(100).status.contains("No enabled mineable faces remain"),"machine stops clearly when every enabled vein is unavailable");
        int calls=world.calls;seconds(service,100);check(world.calls==calls,"stopped depleted machines remain passive");

        store=registry(15);world=new World();service=new SteamRockDrillService(store,world,1);
        world.outcomes.put(0,Result.skip("Depleted since inspection."));world.outcomes.put(1,Result.skip("Closed face."));
        service.start(100);service.tick(1);
        check(world.calls==3&&world.mined.equals(Arrays.asList(2))&&store.get(100).nextFace==3,"stale faces are skipped within the same earned turn, with only one actual action");
        world.outcomes.put(2,Result.skip("Depleted"));world.outcomes.put(3,Result.skip("Depleted"));service.tick(1);
        check(world.calls==7&&!store.get(100).running,"at most four stale faces are attempted before stopping, never an endless same-tile loop");

        store=registry(2);world=new World();world.available=13;service=new SteamRockDrillService(store,world,1);service.start(100);
        check(!store.get(100).running&&world.calls==0,"disabled mineable faces cannot be used as fallback");

        store=registry(5);world=new World();world.available=5;world.depleteMinedFace=true;service=new SteamRockDrillService(store,world,1);
        service.start(100);service.tick(1);
        check(store.get(100).running&&store.get(100).nextFace==1,"exhausting one vein keeps the remaining enabled face in the round-robin service");
        service.tick(1);
        check(world.mined.equals(Arrays.asList(0,2))&&!store.get(100).running&&store.get(100).nextFace==3
                &&store.get(100).status.contains("No enabled mineable faces remain"),"the last native action stops the drill immediately with its advanced cursor instead of waiting another interval");
        calls=world.calls;int inspections=world.inspections,checks=world.checks;seconds(service,100);
        check(world.calls==calls&&world.inspections==inspections&&world.checks==checks,"an exhausted stopped machine performs no later native inspection or mining calls");
    }
    private static void outputStops() {
        for(String reason:new String[]{"No hopper exists.","Bulk Hopper full.","Bulk Hopper rejects this resource.","Hopper ownership changed.","Drill moved from its configured anchor."}) {
            SteamRockDrills store=registry(15);World world=new World();SteamRockDrillService service=new SteamRockDrillService(store,world,1);
            service.start(100);world.unavailable=reason;service.tick(1);
            check(!store.get(100).running&&store.get(100).status.contains(reason)&&world.calls==0,"invalid output or machine anchor stops before a native mining action: "+reason);
        }
        SteamRockDrills store=registry(15);World world=new World();world.outcomes.put(0,Result.stopAfterMining("Produced resource retained in the drill; hopper insertion failed."));
        SteamRockDrillService service=new SteamRockDrillService(store,world,1);service.start(100);service.tick(1);
        check(!store.get(100).running&&store.get(100).nextFace==1&&world.mined.equals(Arrays.asList(0)),"a completed action with retained pending output advances the cursor and stops for recovery");
        check(store.get(100).status.contains("retained in the drill"),"pending real resource recovery is visible in stopped status");
        store=registry(15);world=new World();world.outcomes.put(0,Result.stop("No item was mined."));service=new SteamRockDrillService(store,world,1);service.start(100);service.tick(1);
        check(store.get(100).nextFace==0&&world.mined.isEmpty(),"a preflight output rejection consumes no action or round-robin position");
        store=registry(15);world=new World();world.fail=new IllegalStateException("native\nresource problem");service=new SteamRockDrillService(store,world,1);service.start(100);service.tick(1);
        check(!store.get(100).running&&store.get(100).status.contains("native resource problem")&&!store.get(100).status.contains("\n"),"native faults stop this machine and persist a bounded one-line diagnostic");
        store=registry(15);Drill old=store.get(100);store.put(new Drill(old.itemId,old.ownerId,old.revision,old.tileX,old.tileY,old.layer,0,15,0,false,"Unlinked"));
        world=new World();service=new SteamRockDrillService(store,world,1);service.start(100);
        check(!store.get(100).running&&store.get(100).status.contains("No Bulk Hopper is linked")&&world.checks==0&&world.calls==0,"unlinked machines stop without probing native resources");
        store=registry(0);world=new World();service=new SteamRockDrillService(store,world,1);service.start(100);
        check(!store.get(100).running&&store.get(100).status.contains("No mining faces are enabled")&&world.calls==0,"no enabled faces produces a clear stopped status");
    }
    private static void controlsAndRestart()throws Exception {
        SteamRockDrills store=registry(15);World world=new World();SteamRockDrillService service=new SteamRockDrillService(store,world,30);
        service.start(100);long startRevision=store.get(100).revision;seconds(service,29);service.stop(100);
        check(store.get(100).revision>startRevision&&!store.get(100).running&&service.secondsUntilAction(100)==0,"manual STOP invalidates stale controls and clears earned production time");
        seconds(service,40);check(world.calls==0,"manual STOP remains stopped for every future interval");
        service.start(100);seconds(service,29);check(world.calls==0,"restart does not inherit time earned before STOP");
        service.tick(1);check(world.mined.equals(Arrays.asList(0)),"a fresh full interval is required after restarting");
        seconds(service,29);
        Path directory=Files.createTempDirectory("wurmrail-drill-restart-");
        try {
            Path file=directory.resolve("drills.properties");store.save(file);SteamRockDrills restored=SteamRockDrills.load(file);
            World after=new World();SteamRockDrillService restarted=new SteamRockDrillService(restored,after,30);
            seconds(restarted,29);check(after.calls==0,"saved running machines earn a fresh interval, with no offline or pre-shutdown elapsed credit");
            restarted.tick(1);check(after.mined.equals(Arrays.asList(1)),"saved round-robin cursor resumes at East rather than resetting to North");
        }finally{SteamRockDrillsSelfTest.cleanup(directory);}
        World afterPause=new World();SteamRockDrillService paused=new SteamRockDrillService(registry(15),afterPause,30);paused.start(100);
        paused.tick(1000000);check(afterPause.calls==0&&paused.secondsUntilAction(100)==25,"a long server pause contributes only the bounded five seconds rather than an offline production burst");
        paused.tick(25);check(afterPause.calls==0&&paused.secondsUntilAction(100)==20,"every server pause remains bounded independently");
        seconds(paused,20);check(afterPause.calls==1,"remaining online time eventually completes one normal action");
        paused.tick(1000000);check(afterPause.calls==1,"a completed action discards any server-pause backlog");

        final SteamRockDrills[] selected={registry(15)};World changing=new World();SteamRockDrillService supplied=new SteamRockDrillService(()->selected[0],changing,1);
        supplied.start(100);supplied.tick(.5);selected[0]=selected[0].copy();Drill d=selected[0].get(100);
        selected[0].put(new Drill(d.itemId,d.ownerId,selected[0].nextRevision(),d.tileX,d.tileY,d.layer,d.hopperId,8,3,true,"Reconfigured"));
        supplied.tick(.5);check(changing.calls==0,"reconfiguration and transactional registry replacement discard the previous timer");
        supplied.tick(.5);check(changing.mined.equals(Arrays.asList(3)),"replacement configuration mines only the newly enabled West face");
        supplied.reset(100);supplied.tick(.5);check(changing.calls==1,"explicit clock reset cannot immediately reuse prior elapsed time");
        supplied.tick(.5);check(changing.calls==2,"an explicitly reset running machine is rescheduled rather than forgotten");
        selected[0].remove(100);supplied.tick(5);check(changing.calls==2,"removed machine IDs cannot fire stale queued actions");
    }
    private static void boundedFleet() {
        SteamRockDrills store=new SteamRockDrills();World world=new World();
        for(int i=1;i<=100;i++)store.put(new Drill(i,900,store.nextRevision(),i,14,-1,800,1,0,true,"Running"));
        SteamRockDrillService service=new SteamRockDrillService(store,world,.1);service.tick(.1);
        check(world.calls==SteamRockDrillService.MAX_ACTIONS_PER_POLL,"synchronized industrial machines respect the shared native action budget");
        service.tick(0);check(world.calls==64,"a second bounded poll advances the next waiting machines");
        service.tick(0);service.tick(0);
        check(world.calls==100&&new HashSet<Long>(world.machineOrder).size()==100,"oldest queued turns serve every machine without a repeated early machine");
        check(world.machineOrder.get(0)==1&&world.machineOrder.get(99)==100,"equal-time industrial turns are deterministic and fair by item ID");
        service.tick(5);check(world.calls==132,"even a long delayed poll performs at most one action for any individual machine");
        service.close();service.tick(0);check(world.calls==132,"closing clears all old due entries and reopening schedules a fresh interval");
    }
    private static void validation() {
        SteamRockDrills store=registry(1);World world=new World();
        for(double interval:new double[]{0,.09,-1,Double.NaN,Double.POSITIVE_INFINITY,86401})invalid(()->new SteamRockDrillService(store,world,interval),"unsafe mining interval rejected");
        SteamRockDrillService service=new SteamRockDrillService(store,world,30);
        for(double elapsed:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})invalid(()->service.tick(elapsed),"invalid poll time rejected");
        invalid(()->service.start(999),"unknown machine cannot start");invalid(()->service.stop(999),"unknown machine cannot stop");
        check(service.currentFace(999)==-1&&service.secondsUntilAction(999)==0,"unknown machines have no active face or earned timer");
    }
    private interface Work {void run();}
    private static void invalid(Work work,String detail){try{work.run();throw new AssertionError(detail);}catch(IllegalArgumentException expected){assertions++;}}
    private static void check(boolean value,String detail){assertions++;if(!value)throw new AssertionError(detail);}
}
