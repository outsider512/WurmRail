package org.wurmrail.core;

import java.nio.file.*;
import java.util.*;

/** Two complete circles, two three-way junctions and one shared connecting track. */
public final class TrafficControlSelfTest {
    private static int assertions;
    private static long car=970000;
    public static void main(String[] args) throws Exception {
        repeatedOppositeItineraries();orientationVariations();recoverCloseApproaches();stoppedOwnerAndManualApproach();checkpointAndRestart();
        followingConvoyFairness();queuedCrossingsStayOnRoute();soleMemberBackout();staleAdmission();receivingCapacity();consistExpansionSafety();
        boundedDirectionalBatches();batchAdmissionLifecycle();underfilledBatchDrain();
        headOnRecovery();restartDirectionProtection();recoveryRefusals();lateReceiver();restartConvoyProtection();oldHeadOnSave();resumeBackingConvoy();backingReceivingReplan();enteredExitStaysFixed();farOpposingSave();persistedSixServiceQueue();
        stagedReceivingQueueRecovery();stagedReceivingQueueBarriers();ordinaryFollowingDoesNotStageYield();
        System.out.println("TrafficControlSelfTest: "+assertions+" assertions passed.");
    }

    private static void headOnRecovery() {
        for(boolean immediate:new boolean[]{false,true}) {
            RailNetwork n=dumbbell(40);
            RailNetwork.Train a=service(n,"HeadOnA","r0","r1",2,false);
            RailNetwork.Train b=service(n,"HeadOnZ","r2","r1",1,false);
            List<String> originalRoute=new ArrayList<String>(b.route);
            n.start(a.name);n.start(b.name);
            RailNetwork.Checkpoint before=n.checkpoint(b.name);
            if(immediate) {
                List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(b.poses);
                check(n.resolveTrafficJams(Collections.<String>emptyList())==0,"restricted recovery cannot reverse an unauthorized service");
                check(n.resolveTrafficJams(Collections.singleton(b.name))==1,"operator can request the authorized safe backing yield");
                samePoses(original,b.poses,"safe recovery changes traction without rotating or teleporting any vehicle");
                check(b.direction==-1&&n.trafficRecoveryStatus(b.name).contains("Yielding"),"recovery reports its yielding service and uses backing traction");
                n.restore(before);
                check(b.direction==1&&n.trafficRecoveryStatus(b.name).isEmpty(),"failed world transaction restores recovery state and traction atomically");
                check(n.resolveTrafficJams()==1,"restored stationary jam can be resolved again");
            }
            boolean recovered=immediate;boolean[] arrived={false,false};
            for(int tick=0;tick<7000&&(!arrived[0]||!arrived[1]);tick++) {
                n.tick(.1);headway(Arrays.asList(a,b));exclusiveCorridor(n);
                if(!n.trafficRecoveryStatus(b.name).isEmpty())recovered=true;
                if(a.routeIndex!=0)arrived[0]=true;if(b.routeIndex!=0)arrived[1]=true;
                check(a.powered&&b.powered,"backing recovery retains power for both saved schedules");
                check(b.route.equals(originalRoute),"recovery never rewrites a saved itinerary");
            }
            check(recovered&&arrived[0]&&arrived[1],"head-on services yield through real track and both reach their scheduled station: "+describe(n,a,b));
        }
        RailNetwork n=dumbbell(40);
        RailNetwork.Train parked=service(n,"Parked","r0","r1",1,false),moving=service(n,"Yield","r2","r1",1,false);
        List<RailNetwork.Pose> untouched=new ArrayList<RailNetwork.Pose>(parked.poses);n.start(moving.name);
        check(n.resolveTrafficJams(Collections.singleton(parked.name))==0,"STOPped services cannot be forced to yield by fleet recovery");
        for(int i=0;i<500;i++) { n.tick(.1);samePoses(untouched,parked.poses,"automatic recovery never moves the STOPped obstruction"); }
        check(!parked.powered&&parked.direction==1,"STOP remains authoritative during automatic jam recovery");

        n=dumbbell(40);parked=service(n,"FrontStopped","r0","r1",2,false);
        moving=service(n,"MiddleYield","r2","r1",1,false);
        RailNetwork.Train follower=service(n,"RearStopped","r4","r3",1,false);n.start(moving.name);
        List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(moving.poses);
        check(n.resolveTrafficJams(Collections.singleton(moving.name))==0,"a stopped follower inside the required backing clearance prevents automatic reversal");
        for(int i=0;i<400;i++) { n.tick(.1);headway(Arrays.asList(parked,moving,follower)); }
        check(moving.direction==1&&n.trafficRecoveryStatus(moving.name).contains("no protected space"),"bounded recovery holds and explains a jam with no safe space behind");
        check(!parked.powered&&!follower.powered,"front and rear STOPped services remain under their operators' control");
    }

    private static void restartDirectionProtection() throws Exception {
        Fixture f=new Fixture();f.network.start(f.a.name);
        TrafficControl.Lease held=awaitEnteredConnector(f.network,240);
        Path directory=Files.createTempDirectory("wurmrail-restart-direction-");Path state=directory.resolve("rail.properties");
        try {
            f.network.save(state);RailNetwork loaded=RailNetwork.load(state);
            RailNetwork.Train inside=loaded.trains.get(held.trainName),opposite=loaded.trains.get(f.b.name);
            loaded.start(opposite.name);loaded.tick(.1);
            TrafficControl.Lease rebuilt=lease(loaded,inside.name);
            check(rebuilt!=null&&rebuilt.entered&&rebuilt.entryJunction.equals(held.entryJunction),"first restart tick rebuilds direction protection from the parked whole consist");
            check(lease(loaded,opposite.name)==null,"starting the opposite service first cannot acquire the occupied connector after restart");
            loaded.start(inside.name);
            int[] arrivals=runArrivals(loaded,inside,opposite,3,1200);
            check(arrivals[0]>=3&&arrivals[1]>=3,"resumed services coordinate after rebuilding transient direction ownership");
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void recoveryRefusals() throws Exception {
        RailNetwork n=dumbbell(40);
        RailNetwork.Train a=service(n,"AwayA","r0","R",1,false),b=service(n,"AwayB","r2","r3",1,false);
        n.start(a.name);n.start(b.name);
        List<RailNetwork.Pose> initialA=new ArrayList<RailNetwork.Pose>(a.poses),initialB=new ArrayList<RailNetwork.Pose>(b.poses);
        check(n.resolveTrafficJams()==0,"nearby opposite-facing services pointing away from each other are not a head-on conflict");
        samePoses(initialA,a.poses,"non-conflicting opposite headings leave A unchanged");samePoses(initialB,b.poses,"non-conflicting opposite headings leave B unchanged");
        check(a.direction==1&&b.direction==1,"false jam detection cannot change either traction direction");

        n=new RailNetwork();for(int i=0;i<30;i++) { n.addNode("p"+i,100+i,200,0);if(i>0)n.connect("p"+(i-1),"p"+i); }
        a=n.createTrain("BlockedA",1,Arrays.asList(car++,car++),"p10","p11");
        b=n.createTrain("BlockedB",2,Arrays.asList(car++),"p12","p11");
        n.setRoute(a.name,Arrays.asList("p29","p0"),1);n.setRoute(b.name,Arrays.asList("p0","p29"),1);n.start(a.name);n.start(b.name);
        for(int i=0;i<400;i++) { n.tick(.1);headway(Arrays.asList(a,b)); }
        check(a.direction==1&&b.direction==1,"an unresolvable plain-line head-on never causes blind backing without a through route");
        check(n.trafficRecoveryStatus(a.name).contains("no protected space")&&n.trafficRecoveryStatus(b.name).contains("no protected space"),"unresolvable traffic reports the missing safe backing route");

        n=dumbbell(40);a=service(n,"PersistA","r0","r1",2,false);b=service(n,"PersistZ","r2","r1",1,false);
        n.start(a.name);n.start(b.name);check(n.resolveTrafficJams(Collections.singleton(b.name))==1,"restart fixture begins a real protected yield");
        Path directory=Files.createTempDirectory("wurmrail-yield-restart-");Path state=directory.resolve("rail.properties");
        try {
            n.save(state);RailNetwork loaded=RailNetwork.load(state);RailNetwork.Train restored=loaded.trains.get(b.name);
            int direction=restored.direction;List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(restored.poses);
            loaded.start(restored.name);
            check(restored.direction==direction&&loaded.trafficRecoveryStatus(restored.name).contains("yield"),"mid-yield restart preserves the recovery intent instead of facing back into the jam");
            samePoses(original,restored.poses,"resuming a saved yield never rotates or moves the body on Power on");
            loaded.start(a.name);RailNetwork.Train leader=loaded.trains.get(a.name);
            boolean arrivedA=false,arrivedB=false;
            for(int i=0;i<7000&&(!arrivedA||!arrivedB);i++) {
                loaded.tick(.1);headway(Arrays.asList(leader,restored));exclusiveCorridor(loaded);
                if(leader.routeIndex!=0)arrivedA=true;if(restored.routeIndex!=0)arrivedB=true;
            }
            check(arrivedA&&arrivedB,"saved recovery resumes through actual track and reaches both saved schedules");
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void lateReceiver() {
        RailNetwork n=dumbbell(40);RailNetwork.Train incoming=service(n,"Admitted","l2","l1",2,true);
        RailNetwork.Train parked=n.createTrain("NewParkedReceiver",3,Arrays.asList(car++),"r4","r5");
        n.setRoute(parked.name,Arrays.asList("l11","r11"),1);n.start(incoming.name);
        for(int i=0;i<3000&&!onConnector(incoming);i++)n.tick(.1);
        TrafficControl.Lease held=lease(n,incoming.name);check(held!=null&&held.berth.size()>2,"late receiver fixture has a complete receiving berth");
        String signal="R_from_6";
        check(n.signals().request(incoming.name,signal).granted,"the original empty receiving direction has a valid cached GREEN authority");
        n.reverse(parked.name);
        List<RailNetwork.Pose> unchanged=new ArrayList<RailNetwork.Pose>(parked.poses);
        SignalSystem.RequestResult recheck=n.signals().request(incoming.name,signal);
        check(!recheck.granted&&recheck.reason.contains("receiving"),"a cached GREEN is rechecked when an opposite parked receiver appears later");
        for(int i=0;i<1500;i++) {
            n.tick(.1);headway(Arrays.asList(incoming,parked));samePoses(unchanged,parked.poses,"late receiver STOP is never overridden");
            check(!n.signalOccupiesNode(incoming,held.exitNeighbor),"admitted train never enters the old head-on receiving arm after its safe exit forecast changes");
        }
        check(incoming.powered&&incoming.routeIndex==1,"safe alternate receiving alignment reaches the station while the opposite parked train retains STOP");
    }
    private static void restartConvoyProtection() throws Exception {
        RailNetwork n=dumbbell(40);RailNetwork.Train a=service(n,"SavedConvoyA","l0","L",2,true),b=service(n,"SavedConvoyB","l7","l6",1,true);
        RailNetwork.Train opposite=service(n,"FirstRestarted","r11","r10",1,false);n.start(a.name);n.start(b.name);
        boolean together=false;
        for(int i=0;i<2500&&!together;i++) { n.tick(.1);together=onConnector(a)&&onConnector(b); }
        check(together,"restart fixture saves a real two-member crossing convoy");
        Path directory=Files.createTempDirectory("wurmrail-convoy-restart-");Path state=directory.resolve("rail.properties");
        try {
            n.save(state);RailNetwork loaded=RailNetwork.load(state);loaded.start(opposite.name);loaded.tick(.1);
            check(lease(loaded,a.name)!=null&&lease(loaded,b.name)!=null&&loaded.signals().traffic().compatible(a.name,b.name),"all parked convoy members regain same-direction protection before the first resumed movement");
            check(lease(loaded,opposite.name)==null,"an opposing service restarted first cannot claim between saved convoy members");
            loaded.start(a.name);loaded.start(b.name);
            List<RailNetwork.Train> trains=Arrays.asList(loaded.trains.get(a.name),loaded.trains.get(b.name),loaded.trains.get(opposite.name));boolean[] arrived=new boolean[3];
            for(int i=0;i<9000&&(!arrived[0]||!arrived[1]||!arrived[2]);i++) {
                loaded.tick(.1);headway(trains);exclusiveCorridor(loaded);
                for(int t=0;t<3;t++)if(trains.get(t).routeIndex!=0)arrived[t]=true;
            }
            check(arrived[0]&&arrived[1]&&arrived[2],"saved following convoy drains and the opposite restarted service gets a turn");
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void oldHeadOnSave() throws Exception {
        RailNetwork n=dumbbell(40);
        RailNetwork.Train a=service(n,"OldHeadOnA","c18","c19",2,true),b=service(n,"OldHeadOnZ","c21","c20",1,false);
        Path directory=Files.createTempDirectory("wurmrail-old-jam-");Path state=directory.resolve("rail.properties");
        try {
            n.save(state);RailNetwork loaded=RailNetwork.load(state);RailNetwork.Train first=loaded.trains.get(a.name),second=loaded.trains.get(b.name);
            loaded.start(first.name);loaded.start(second.name);loaded.tick(.1);
            check(lease(loaded,first.name)!=null&&lease(loaded,second.name)!=null,"an old head-on save protects both existing occupied bodies instead of inventing a clear connector");
            List<RailNetwork.Pose> before=new ArrayList<RailNetwork.Pose>(second.poses);
            check(loaded.resolveTrafficJams(Collections.singleton(second.name))==1,"a sole opposing owner may escape an old saved head-on when its rear path is actually clear");
            samePoses(before,second.poses,"old-jam recovery changes traction without rotating any locomotive/cart");
            boolean[] arrived={false,false};
            for(int i=0;i<9000&&(!arrived[0]||!arrived[1]);i++) {
                loaded.tick(.1);headway(Arrays.asList(first,second));
                if(first.routeIndex!=0)arrived[0]=true;if(second.routeIndex!=0)arrived[1]=true;
                check(first.powered&&second.powered,"existing head-on recovery keeps both services running their original itineraries");
            }
            check(arrived[0]&&arrived[1],"opposing trains already saved inside the connector back out safely and both regain their schedules: "+describe(loaded,first,second));
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void resumeBackingConvoy() throws Exception {
        RailNetwork fresh=new RailNetwork();
        for(int i=0;i<9;i++) { fresh.addNode("f"+i,90+i,90,0);if(i>0)fresh.connect("f"+(i-1),"f"+i); }
        RailNetwork.Train newlyClaimed=fresh.createTrain("FreshService",1,Arrays.asList(car++),"f6","f7");
        fresh.setRoute(newlyClaimed.name,Arrays.asList("f1"),0);List<RailNetwork.Pose> unmoved=new ArrayList<RailNetwork.Pose>(newlyClaimed.poses);
        fresh.resumeScheduled(newlyClaimed.name);
        check(newlyClaimed.powered&&newlyClaimed.direction==-1,"fleet start still selects necessary backing for a never-departed bare engine at a terminus");
        samePoses(unmoved,newlyClaimed.poses,"new schedule departure selects traction without rotating its body");
        for(int i=0;i<300&&newlyClaimed.powered;i++)fresh.tick(.1);
        check("Destination reached".equals(newlyClaimed.status),"fleet start reaches a new schedule's destination behind its original facing");
        RailNetwork n=dumbbell(40);
        RailNetwork.Train a=service(n,"BackingLeader","c24","c23",2,true),b=service(n,"BackingFollower","c20","c19",1,true);
        n.reverse(a.name);n.reverse(b.name);
        Path directory=Files.createTempDirectory("wurmrail-resume-backing-");Path state=directory.resolve("rail.properties");
        try {
            n.save(state);RailNetwork loaded=RailNetwork.load(state);a=loaded.trains.get(a.name);b=loaded.trains.get(b.name);
            List<RailNetwork.Pose> beforeA=new ArrayList<RailNetwork.Pose>(a.poses),beforeB=new ArrayList<RailNetwork.Pose>(b.poses);
            loaded.resumeScheduled(a.name);loaded.resumeScheduled(b.name);
            check(a.powered&&b.powered&&a.direction==-1&&b.direction==-1,"fleet resume powers the saved backing convoy without attempting an unsafe engine-leading reversal");
            samePoses(beforeA,a.poses,"saved backing leader resumes without any body movement or spin");samePoses(beforeB,b.poses,"saved backing follower resumes without any body movement or spin");
            float x=a.poses.get(0).x;
            for(int i=0;i<100;i++) { loaded.tick(.1);headway(Arrays.asList(a,b));exclusiveCorridor(loaded); }
            check(a.direction==-1&&b.direction==-1&&a.poses.get(0).x>x,"resumed backing convoy moves in its saved physical direction through exclusive following blocks");
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void backingReceivingReplan() {
        RailNetwork n=dumbbell(40);
        RailNetwork.Train behind=service(n,"BackingReceiver","c34","c35",2,false),lead=service(n,"BackingFirst","c18","c19",1,false);
        n.reverse(behind.name);n.reverse(lead.name);n.resumeScheduled(behind.name);n.resumeScheduled(lead.name);
        TrafficControl.Lease original=lease(n,behind.name);check(original!=null,"backing receiving fixture restores its whole-consist crossing plan");
        check(original.exitNeighbor.equals("l0"),"a backing follower forecasts the receiving arm matching its eventual forward circulation immediately");
        boolean arrivedBehind=false,arrivedLead=false;
        for(int i=0;i<8000&&(!arrivedBehind||!arrivedLead);i++) {
            List<RailNetwork.Pose> beforeBehind=behind.poses,beforeLead=lead.poses;n.tick(.1);
            if(behind.routeIndex!=0)arrivedBehind=true;if(lead.routeIndex!=0)arrivedLead=true;
            headway(Arrays.asList(behind,lead));exclusiveCorridor(n);
            for(List<RailNetwork.Pose> pair:Arrays.asList(beforeBehind,beforeLead)) {
                List<RailNetwork.Pose> after=pair==beforeBehind?behind.poses:lead.poses;
                for(int car=0;car<pair.size();car++) {
                    RailNetwork.Pose a=pair.get(car),b=after.get(car);double distance=Math.hypot(a.x-b.x,a.y-b.y);
                    double turn=Math.abs((a.rotation-b.rotation+540)%360-180);
                    // Sampled curve arc lengths and native float positions can
                    // differ by a fraction of a millimetre at a tile boundary.
                    check(distance<=.501&&turn<90&&(distance>.00001||turn<.01),"negative traction forecast never shifts or spins a car when its future bend changes");
                }
            }
        }
        check(arrivedBehind&&arrivedLead,"both backing services reach their stations using the correctly forecast receiving circulation");
    }
    private static void enteredExitStaysFixed() {
        RailNetwork n=dumbbell(40);RailNetwork.Train a=service(n,"AlreadyExiting","l2","l1",1,true);
        RailNetwork.Train other=service(n,"ChangingCirculation","r4","r5",1,false);n.start(a.name);
        TrafficControl.Lease entered=null;
        for(int i=0;i<4000;i++) {
            n.tick(.1);TrafficControl.Lease held=lease(n,a.name);
            if(held!=null&&!n.signalOccupiesNode(a,held.exitJunction)&&n.signalOccupiesNode(a,held.exitNeighbor)) { entered=held;break; }
        }
        check(entered!=null,"fixture reaches the first receiving arm with its body already clear of the points");
        n.stop(a.name);List<RailNetwork.Pose> unchanged=new ArrayList<RailNetwork.Pose>(a.poses);n.reverse(other.name);
        n.signals().traffic().prepareMovement();
        check(lease(n,a.name).exitNeighbor.equals(entered.exitNeighbor),"a train already past the points retains its committed exit even when the receiving circulation changes");
        samePoses(unchanged,a.poses,"first-arm replan guard preserves the exact stopped vehicle pose");
    }
    private static void farOpposingSave() throws Exception {
        for(boolean immediate:new boolean[]{false,true}) {
            RailNetwork n=dumbbell(40);
            RailNetwork.Train leader=service(n,"FarConvoyLeader","c8","c9",2,true),follower=service(n,"FarConvoyFollower","c5","c6",1,true);
            RailNetwork.Train opposite=service(n,"FarOpposite","c30","c29",1,false);
            Path directory=Files.createTempDirectory("wurmrail-far-jam-");Path state=directory.resolve("rail.properties");
            try {
                n.save(state);n=RailNetwork.load(state);
                List<RailNetwork.Train> trains=Arrays.asList(n.trains.get(leader.name),n.trains.get(follower.name),n.trains.get(opposite.name));
                for(RailNetwork.Train train:trains)n.resumeScheduled(train.name);
                leader=trains.get(0);follower=trains.get(1);opposite=trains.get(2);
                List<RailNetwork.Pose> originalLeader=leader.poses,originalFollower=follower.poses,originalOpposite=opposite.poses;
                n.tick(1);
                check(Math.hypot(leader.poses.get(0).x-opposite.poses.get(0).x,leader.poses.get(0).y-opposite.poses.get(0).y)>80,"saved opposing owners remain far outside the near-head-on detector");
                samePoses(originalLeader,leader.poses,"opposing corridor authority initially holds the leader safely");samePoses(originalOpposite,opposite.poses,"opposing corridor authority initially holds its distant opponent safely");
                check(leader.powered&&follower.powered&&opposite.powered&&n.signals().traffic().opposingEntered(leader.name,opposite.name),"physically occupied opposite corridor leases expose the long-range dispatch deadlock");
                if(immediate) {
                    check(n.resolveTrafficJams(Collections.singleton(leader.name))==0,"authorizing only the front cannot reverse it into a follower or authorize that follower's retreat");
                    check(n.resolveTrafficJams(Collections.singleton(opposite.name))==1,"Clear traffic jams can select the authorized distant sole opponent's protected retreat");
                    samePoses(originalOpposite,opposite.poses,"distant recovery changes traction without turning the body on its tile");
                    check(!n.signals().traffic().opposingEntered(leader.name,opposite.name),"a corridor owner already escaping is not selected as a second opposing recovery");
                }
                boolean recovered=immediate;boolean[] arrived=new boolean[3];
                for(int i=0;i<10000&&(!arrived[0]||!arrived[1]||!arrived[2]);i++) {
                    n.tick(.1);headway(trains);
                    if(opposite.direction<0)recovered=true;
                    for(int t=0;t<3;t++) { if(trains.get(t).routeIndex!=0)arrived[t]=true;check(trains.get(t).powered,"long-range recovery preserves each running scheduled service"); }
                }
                check(recovered&&arrived[0]&&arrived[1]&&arrived[2],"all three far-apart legacy jam services regain their actual scheduled station stops after "+(immediate?"operator":"automatic")+" recovery");
                check(leader.carIds.size()==2&&follower.carIds.size()==1,"far-corridor recovery preserves the mixed coupled convoy");
            } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
        }
    }
    /** Anonymous legacy queue: two merging loops, six services and four old stalled yields. */
    private static void persistedSixServiceQueue() throws Exception {
        RailNetwork original=legacyQueueGraph();String south=queueRail(103,123),north=queueRail(111,98);
        List<RailNetwork.Train> services=new ArrayList<RailNetwork.Train>();
        services.add(queueService(original,"ServiceA",103,100,104,100,2,south,north));
        services.add(queueService(original,"ServiceB",105,100,104,100,1,south,north));
        services.add(queueService(original,"ServiceC",104,100,105,100,1,south,north));
        services.add(queueService(original,"ServiceD",103,124,103,125,1,south,north));
        services.add(queueService(original,"ServiceE",103,123,103,124,1,south,north));
        services.add(queueService(original,"ServiceF",103,122,103,121,1,south,north));
        services.get(4).routeIndex=1;
        java.lang.reflect.Field yielding=RailNetwork.Train.class.getDeclaredField("recoveringTraffic");yielding.setAccessible(true);
        for(int i:new int[]{0,2,3,5}) { original.reverse(services.get(i).name);yielding.setBoolean(services.get(i),true); }
        for(int i=0;i<6;i++) { String key="park"+i;original.addNode(key,125,140+i,0);if(i>0)original.connect("park"+(i-1),key); }
        RailNetwork.Train parked=original.createTrain("ManualParked",2,Arrays.asList(car++,car++,car++),"park3","park4");
        Path directory=Files.createTempDirectory("wurmrail-six-service-legacy-");Path state=directory.resolve("rail.properties");
        try {
            original.save(state);RailNetwork n=RailNetwork.load(state);List<RailNetwork.Train> active=new ArrayList<RailNetwork.Train>();
            for(RailNetwork.Train service:services) { RailNetwork.Train train=n.trains.get(service.name);active.add(train);n.resumeScheduled(train.name); }
            List<RailNetwork.Pose> manual=n.trains.get(parked.name).poses;
            check(n.resolveTrafficJams()==1,"Clear can retry an old persisted recovery whose first yield is now blocked");
            int[] arrivals=new int[6],cursor=new int[6];for(int i=0;i<6;i++)cursor[i]=active.get(i).routeIndex;
            boolean recovered=false;
            for(int tick=0;tick<18000;tick++) {
                Map<Long,RailNetwork.Pose> before=new HashMap<Long,RailNetwork.Pose>();for(RailNetwork.Train train:active)for(RailNetwork.Pose pose:train.poses)before.put(pose.carId,pose);
                n.tick(.1);headway(active);
                for(int i=0;i<6;i++) {
                    RailNetwork.Train train=active.get(i);check(train.powered,"all six resumed services retain their run intent during staged jam recovery");
                    check(train.route.equals(Arrays.asList(south,north)),"staged recovery preserves each original station itinerary");
                    if(train.routeIndex!=cursor[i]) { arrivals[i]++;cursor[i]=train.routeIndex; }
                    for(RailNetwork.Pose pose:train.poses) {
                        RailNetwork.Pose old=before.get(pose.carId);double distance=Math.hypot(pose.x-old.x,pose.y-old.y),turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                        check(distance<=.501&&turn<90&&(distance>.00001||turn<.01),"legacy recovery uses progressive physical motion and never spins a stationary body: "+train.name+" tick="+tick+" distance="+distance+" turn="+turn+" old="+old+" next="+pose);
                    }
                }
                recovered=true;for(int count:arrivals)if(count<3)recovered=false;
                if(recovered)break;
            }
            check(recovered,"all six legacy services complete at least three actual scheduled arrivals: "+Arrays.toString(arrivals));
            samePoses(manual,n.trains.get(parked.name).poses,"automatic and explicit recovery never move the parked manual consist");
            check(!n.trains.get(parked.name).powered,"manual STOP remains authoritative throughout six-service recovery");
        } finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static RailNetwork.Train queueService(RailNetwork n,String name,int x,int y,int tx,int ty,int count,String south,String north) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<count;i++)ids.add(car++);
        RailNetwork.Train train=n.createTrain(name,1,ids,queueRail(x,y),queueRail(tx,ty));n.setRoute(name,Arrays.asList(south,north),.5);return train;
    }

    /** A saved yielding receiver is trapped by its own following train while a real convoy waits at the points. */
    private static void stagedReceivingQueueRecovery() throws Exception {
        for(boolean immediate:new boolean[]{false,true}) {
            RailNetwork n=stagedQueueFixture();resumeStagedServices(n);n.tick(5);
            RailNetwork.Train tail=n.trains.get("QueueTail"),yield=n.trains.get("StalledYield");
            check(yield.direction==-1&&tail.direction==1,"the saved stalled yield and its westbound follower retain their actual traction");
            boolean started=false;
            if(immediate) {
                Map<String,List<RailNetwork.Pose>> original=stagedPoses(n);RailNetwork.Checkpoint before=n.checkpoint(tail.name);
                check(n.resolveTrafficJams()==1,"Clear stages a free tail before retrying the blocked yielding receiver");
                check(tail.direction==-1&&yield.direction==-1,"the first stage changes only the free rear train's traction");
                for(RailNetwork.Train train:n.trains.values())samePoses(original.get(train.name),train.poses,"staging cannot teleport or spin any train");
                check(n.trains.get("ConvoyA").direction==1&&n.trains.get("ConvoyB").direction==1,
                        "staging never reverses the occupied incoming convoy");
                n.restore(before);
                check(tail.direction==1&&yield.direction==-1,"a failed world transaction rolls back the tail's recovery traction");
                check(n.resolveTrafficJams()==1,"the restored jam can stage the same protected tail again");
                // Persist after the first stage, before any car has moved. Both
                // yielding services must resume their own direction after loading.
                n=reloadStagedQueue(n);resumeStagedServices(n);tail=n.trains.get("QueueTail");yield=n.trains.get("StalledYield");
                check(tail.direction==-1&&yield.direction==-1&&n.isRecoveringTraffic(tail.name),
                        "mid-stage restart retains the tail's backing intent and the original stalled yield");
                started=true;
            }
            List<RailNetwork.Train> active=new ArrayList<RailNetwork.Train>();
            for(RailNetwork.Train train:n.trains.values())if(train.automated)active.add(train);
            RailNetwork.Train manual=n.trains.get("ManualParked");List<RailNetwork.Pose> parked=manual.poses;
            int[] arrivals=new int[active.size()],cursor=new int[active.size()];
            for(int i=0;i<active.size();i++)cursor[i]=active.get(i).routeIndex;
            boolean complete=false;
            for(int tick=0;tick<18000&&!complete;tick++) {
                Map<String,List<RailNetwork.Pose>> before=stagedPoses(n);n.tick(.1);headway(active);exclusiveCorridor(n);
                if(!started&&tail.direction<0) {
                    check(yield.direction==-1,"automatic recovery clears the free tail before reversing the trapped root");
                    samePoses(before.get(tail.name),tail.poses,"automatic staging changes traction before any physical tail movement");
                    samePoses(before.get(yield.name),yield.poses,"the trapped receiver stays put during the first recovery stage");
                    check(n.trains.get("ConvoyA").direction==1&&n.trains.get("ConvoyB").direction==1,
                            "automatic staging preserves the already entered convoy's direction");
                    started=true;
                }
                complete=true;
                for(int i=0;i<active.size();i++) {
                    RailNetwork.Train train=active.get(i);
                    check(train.powered&&train.route.equals(Arrays.asList(queueRail(103,123),queueRail(111,98))),
                            "staged recovery preserves every scheduled service's power and original itinerary");
                    if(train.routeIndex!=cursor[i]) { arrivals[i]++;cursor[i]=train.routeIndex; }
                    if(arrivals[i]<3)complete=false;
                    for(int c=0;c<train.poses.size();c++) {
                        RailNetwork.Pose old=before.get(train.name).get(c),pose=train.poses.get(c);
                        double distance=Math.hypot(pose.x-old.x,pose.y-old.y),turn=Math.abs((pose.rotation-old.rotation+540)%360-180);
                        check(distance<=.501&&turn<90&&(distance>.00001||turn<.01),
                                "staged queue recovery follows real track progressively without a stationary body turn");
                    }
                }
                check(!manual.powered,"a parked manual consist remains stopped during staged fleet recovery");
                samePoses(parked,manual.poses,"staged fleet recovery never moves the parked manual consist");
            }
            check(started&&complete,"all six services complete three station arrivals after "+(immediate?"explicit/restarted":"automatic")+" tail-first recovery: "+Arrays.toString(arrivals));
        }
        System.out.println("PASS staged receiving-queue recovery: free tail first, occupied convoy unchanged, explicit/automatic recovery, checkpoint and restart, six services with three arrivals each");
    }

    private static void stagedReceivingQueueBarriers() throws Exception {
        for(String barrier:Arrays.asList("STOP","manual","permission")) {
            RailNetwork n=stagedQueueFixture();resumeStagedServices(n);n.tick(5);
            RailNetwork.Train tail=n.trains.get("QueueTail");
            if(barrier.equals("STOP"))n.stop(tail.name);
            if(barrier.equals("manual")) { n.stop(tail.name);n.setRoute(tail.name,Collections.<String>emptyList(),0); }
            Map<String,List<RailNetwork.Pose>> before=stagedPoses(n);Map<String,Integer> directions=new HashMap<String,Integer>();
            List<String> permitted=new ArrayList<String>();
            for(RailNetwork.Train train:n.trains.values()) {
                directions.put(train.name,train.direction);
                if(!barrier.equals("permission")||!train.name.equals(tail.name))permitted.add(train.name);
            }
            check(n.resolveTrafficJams(permitted)==0,"a "+barrier+" tail is a hard barrier to recursive queue recovery");
            for(RailNetwork.Train train:n.trains.values()) {
                check(train.direction==directions.get(train.name),"refused staged recovery cannot alter another train's traction");
                samePoses(before.get(train.name),train.poses,"refused staged recovery cannot move any train");
            }
            if(!barrier.equals("permission")) {
                List<RailNetwork.Pose> held=tail.poses;
                for(int i=0;i<400;i++) { n.tick(.1);samePoses(held,tail.poses,"automatic recovery respects a stopped/manual rear obstruction"); }
                check(!tail.powered&&tail.direction==1,"automatic recovery cannot override the tail operator's STOP/manual state");
            }
        }
    }

    private static void ordinaryFollowingDoesNotStageYield() {
        RailNetwork n=legacyQueueGraph();String south=queueRail(103,123),north=queueRail(111,98);
        RailNetwork.Train front=queueService(n,"FollowingFront",103,118,103,117,1,south,north);
        RailNetwork.Train middle=queueService(n,"FollowingMiddle",103,120,103,119,1,south,north);
        RailNetwork.Train rear=queueService(n,"FollowingRear",103,122,103,121,1,south,north);
        n.start(middle.name);n.start(rear.name);
        for(int i=0;i<400;i++) { n.tick(.1);headway(Arrays.asList(front,middle,rear)); }
        check(n.resolveTrafficJams()==0,"ordinary same-direction following is not a proven deadlock and cannot stage a rear reversal");
        check(front.direction==1&&middle.direction==1&&rear.direction==1&&!front.powered,
                "ordinary following leaves all physical traction directions and the stopped leader unchanged");
        check(!n.isRecoveringTraffic(middle.name)&&!n.isRecoveringTraffic(rear.name),"ordinary following queues do not acquire traffic-yield intent");
    }

    /** Anonymous fixed geometry and progress reproduce a six-service saved queue without external world files. */
    private static RailNetwork stagedQueueFixture() throws Exception {
        RailNetwork n=legacyQueueGraph();String south=queueRail(103,123),north=queueRail(111,98);
        RailNetwork.Train convoyA=queueService(n,"ConvoyA",100,112,100,113,2,south,north);
        setStagedHistory(n,convoyA,7.549485558312108,1,false,new int[][]{{100,110},{100,111},{100,112},{100,113}});
        RailNetwork.Train tail=queueService(n,"QueueTail",102,114,101,114,1,south,north);tail.routeIndex=1;
        setStagedHistory(n,tail,3.5495192023312208,1,false,new int[][]{{103,114},{102,114},{101,114}});
        RailNetwork.Train convoyB=queueService(n,"ConvoyB",100,113,100,114,1,south,north);
        setStagedHistory(n,convoyB,4.837118521707888,1,false,new int[][]{{100,112},{100,113},{100,114}});
        RailNetwork.Train northA=queueService(n,"NorthA",100,97,100,98,1,south,north);
        setStagedHistory(n,northA,3.5481298272733954,1,false,new int[][]{{100,96},{100,97},{100,98}});
        RailNetwork.Train northB=queueService(n,"NorthB",100,98,100,99,1,south,north);
        setStagedHistory(n,northB,5.55,1,false,new int[][]{{100,97},{100,98},{100,99}});
        RailNetwork.Train yield=queueService(n,"StalledYield",101,114,102,114,1,south,north);
        setStagedHistory(n,yield,2.45,-1,true,new int[][]{{100,114},{101,114},{102,114}});
        for(int i=0;i<6;i++) { String key="stagedPark"+i;n.addNode(key,125,140+i,0);if(i>0)n.connect("stagedPark"+(i-1),key); }
        n.createTrain("ManualParked",2,Arrays.asList(car++,car++,car++),"stagedPark3","stagedPark4");
        return reloadStagedQueue(n);
    }
    @SuppressWarnings("unchecked")
    private static void setStagedHistory(RailNetwork n,RailNetwork.Train train,double distance,int direction,boolean yielding,int[][] coordinates) throws Exception {
        java.lang.reflect.Field path=RailNetwork.Train.class.getDeclaredField("path");path.setAccessible(true);
        List<String> history=(List<String>)path.get(train);history.clear();for(int[] xy:coordinates)history.add(queueRail(xy[0],xy[1]));
        java.lang.reflect.Field progress=RailNetwork.Train.class.getDeclaredField("locomotiveDistance");progress.setAccessible(true);progress.setDouble(train,distance);
        java.lang.reflect.Field towards=RailNetwork.Train.class.getDeclaredField("initialTowards");towards.setAccessible(true);towards.set(train,null);
        java.lang.reflect.Field recovery=RailNetwork.Train.class.getDeclaredField("recoveringTraffic");recovery.setAccessible(true);recovery.setBoolean(train,yielding);train.direction=direction;
        java.lang.reflect.Method refresh=RailNetwork.class.getDeclaredMethod("refresh",RailNetwork.Train.class);refresh.setAccessible(true);refresh.invoke(n,train);
    }
    private static RailNetwork reloadStagedQueue(RailNetwork n) throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-staged-queue-");Path state=directory.resolve("rail.properties");
        try { n.save(state);return RailNetwork.load(state); }
        finally { Files.deleteIfExists(state);Files.deleteIfExists(directory); }
    }
    private static void resumeStagedServices(RailNetwork n) {
        for(RailNetwork.Train train:n.trains.values())if(train.automated)n.resumeScheduled(train.name);
    }
    private static Map<String,List<RailNetwork.Pose>> stagedPoses(RailNetwork n) {
        Map<String,List<RailNetwork.Pose>> result=new LinkedHashMap<String,List<RailNetwork.Pose>>();
        for(RailNetwork.Train train:n.trains.values())result.put(train.name,new ArrayList<RailNetwork.Pose>(train.poses));return result;
    }
    private static String queueRail(int x,int y) { return "loop_"+x+"_"+y; }
    private static String queuePoint(RailNetwork n,String previous,int x,int y) {
        String name=queueRail(x,y);if(!n.nodes.containsKey(name))n.addNode(name,x,y,0);if(previous!=null)n.connect(previous,name);return name;
    }
    private static RailNetwork legacyQueueGraph() {
        RailNetwork n=new RailNetwork();String north=queuePoint(n,null,100,100),south=queuePoint(n,null,100,114),previous=north;
        for(int y=99;y>=96;y--)previous=queuePoint(n,previous,100,y);
        for(int x=101;x<=111;x++)previous=queuePoint(n,previous,x,96);
        for(int y=97;y<=100;y++)previous=queuePoint(n,previous,111,y);
        for(int x=110;x>=100;x--)previous=queuePoint(n,previous,x,100);
        previous=south;
        for(int y=115;y<=125;y++)previous=queuePoint(n,previous,100,y);
        for(int x=101;x<=103;x++)previous=queuePoint(n,previous,x,125);
        for(int y=124;y>=114;y--)previous=queuePoint(n,previous,103,y);
        for(int x=102;x>=100;x--)previous=queuePoint(n,previous,x,114);
        previous=north;for(int y=101;y<=114;y++)previous=queuePoint(n,previous,100,y);
        for(String junction:Arrays.asList(north,south)) {
            n.setJunctionAlignment(junction,17);RailNetwork.Node at=n.nodes.get(junction);
            for(String link:at.links)n.signals().add(junction+"_"+n.signalSide(junction,link),junction,n.signalSide(junction,link),-10);
        }
        n.setStation(queueRail(111,98),true,.5);n.setStation(queueRail(103,123),true,.5);return n;
    }

    private static final class Fixture {
        final RailNetwork network;
        final RailNetwork.Train a,b;
        Fixture() { this(false,false); }
        Fixture(boolean reverseA,boolean reverseB) { this(reverseA,reverseB,false); }
        Fixture(boolean reverseA,boolean reverseB,boolean nearJunction) {
            network=dumbbell();
            a=network.createTrain("EXPRESS",1,Arrays.asList(car++,car++),nearJunction?"l0":"l11",nearJunction?"L":reverseA?"l12":"l10");
            b=network.createTrain("LOCAL",2,Arrays.asList(car++),nearJunction?"r0":"r11",nearJunction?"R":reverseB?"r12":"r10");
            network.setRoute(a.name,Arrays.asList("r11","l11"),1);
            network.setRoute(b.name,Arrays.asList("l11","r11"),1);
        }
    }

    private static RailNetwork dumbbell() {
        return dumbbell(10);
    }
    private static RailNetwork dumbbell(int length) {
        int right=108+length;
        RailNetwork n=new RailNetwork();n.addNode("L",108,100,0);n.addNode("R",right,100,0);
        loop(n,"L","l",108,-1);loop(n,"R","r",right,1);
        String previous="L";
        for(int x=109;x<right;x++) { String name="c"+(x-108);n.addNode(name,x,100,0);n.connect(previous,name);previous=name; }
        n.connect(previous,"R");
        n.setJunctionAlignment("L",(1<<0)|(1<<4));n.setJunctionAlignment("R",(1<<0)|(1<<4));
        for(String junction:Arrays.asList("L","R")) {
            RailNetwork.Node node=n.nodes.get(junction);
            for(String link:node.links) {
                RailNetwork.Node neighbor=n.nodes.get(link);
                int side=RailNetwork.direction(neighbor.tileX-node.tileX,neighbor.tileY-node.tileY);
                n.signals().add(junction+"_from_"+side,junction,side,-10);
            }
        }
        n.setStation("l11",true,1);n.setStation("r11",true,1);
        return n;
    }
    private static void loop(RailNetwork n,String junction,String prefix,int x,int across) {
        String previous=junction;int index=0;
        for(int y=99;y>=96;y--)previous=point(n,previous,prefix+(index++),x,y);
        for(int step=1;step<=4;step++)previous=point(n,previous,prefix+(index++),x+across*step,96);
        for(int y=97;y<=104;y++)previous=point(n,previous,prefix+(index++),x+across*4,y);
        for(int step=3;step>=0;step--)previous=point(n,previous,prefix+(index++),x+across*step,104);
        for(int y=103;y>=101;y--)previous=point(n,previous,prefix+(index++),x,y);
        n.connect(previous,junction);
    }
    private static String point(RailNetwork n,String previous,String name,int x,int y) {
        n.addNode(name,x,y,0);n.connect(previous,name);return name;
    }

    private static void repeatedOppositeItineraries() {
        Fixture f=new Fixture();RailNetwork n=f.network;
        List<RailNetwork.Pose> initialA=new ArrayList<RailNetwork.Pose>(f.a.poses),initialB=new ArrayList<RailNetwork.Pose>(f.b.poses);
        n.start(f.a.name);n.start(f.b.name);
        samePoses(initialA,f.a.poses,"starting A does not rotate or teleport the engine/cart");
        samePoses(initialB,f.b.poses,"starting B does not rotate or teleport the engine");
        check(n.signals().definitions().size()==6,"only the six player-built junction signals exist");
        int[] arrivals=runArrivals(n,f.a,f.b,8,2400);
        check(arrivals[0]>=8&&arrivals[1]>=8,"both opposite itineraries repeat at least eight station arrivals");
        check(f.a.carIds.size()==2&&f.b.carIds.size()==1,"dispatch preserves the original unequal consists");
        check(n.signals().definitions().size()==6,"automatic holding boundaries do not become saved physical signals");
        System.out.println("Dumbbell service: EXPRESS "+arrivals[0]+" arrivals; LOCAL "+arrivals[1]+" arrivals.");
    }

    private static int[] runArrivals(RailNetwork n,RailNetwork.Train a,RailNetwork.Train b,int wanted,double seconds) {
        int[] arrivals={0,0};int oldA=a.routeIndex,oldB=b.routeIndex;double lastProgress=0;
        Map<Long,Float> headings=new HashMap<Long,Float>();
        for(RailNetwork.Pose pose:a.poses)headings.put(pose.carId,pose.rotation);
        for(RailNetwork.Pose pose:b.poses)headings.put(pose.carId,pose.rotation);
        for(int step=1;step<=seconds*10;step++) {
            n.tick(.1);
            if(a.routeIndex!=oldA) { arrivals[0]++;oldA=a.routeIndex;lastProgress=step*.1; }
            if(b.routeIndex!=oldB) { arrivals[1]++;oldB=b.routeIndex;lastProgress=step*.1; }
            clearOfEachOther(a,b);exclusiveCorridor(n);smoothHeading(a,headings);smoothHeading(b,headings);
            check(!(onConnector(a)&&onConnector(b)),"opposite trains never occupy the exclusive connector together");
            if(arrivals[0]>=wanted&&arrivals[1]>=wanted)return arrivals;
            if(step*.1-lastProgress>400)throw new AssertionError("Dumbbell stalled after arrivals "+Arrays.toString(arrivals)+"; "+describe(n,a,b));
        }
        throw new AssertionError("Both trains did not complete "+wanted+" arrivals: "+Arrays.toString(arrivals)+"; "+describe(n,a,b));
    }
    private static void orientationVariations() {
        for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true})if(a||b) {
            Fixture f=new Fixture(a,b);f.network.start(f.a.name);f.network.start(f.b.name);
            int[] arrivals=runArrivals(f.network,f.a,f.b,4,1200);
            check(arrivals[0]>=4&&arrivals[1]>=4,"both initial circle directions coordinate without a head-on meeting");
        }
    }
    private static void recoverCloseApproaches() {
        Fixture f=new Fixture(false,false,true);f.network.start(f.a.name);f.network.start(f.b.name);
        int[] arrivals=runArrivals(f.network,f.a,f.b,4,1200);
        check(arrivals[0]>=4&&arrivals[1]>=4,"services already close to both junctions circulate to clear the older crossing request");
    }

    /** Waiting for an opposite turn must not manufacture a new trip around the local station. */
    private static void queuedCrossingsStayOnRoute() {
        for(double step:new double[]{.1,.25}) {
            RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
            RailNetwork.Train a=service(n,"QueuedLeader","l2","l1",2,true);
            RailNetwork.Train b=service(n,"QueuedOpposite","r2","r1",2,false);
            RailNetwork.Train follower=service(n,"QueuedFollower","l7","l6",1,true);
            List<RailNetwork.Train> all=Arrays.asList(a,b,follower);
            for(RailNetwork.Train train:all)n.start(train.name);
            traffic.prepare(a,Arrays.asList("L","c1"));
            traffic.prepare(b,Arrays.asList("R","c39"));
            traffic.prepare(follower,Arrays.asList("L","c1"));
            check("c39".equals(traffic.preferredExit(b.name,"R","r0")),"opposite service outside the receiving boundary retains its intended crossing");
            check("c1".equals(traffic.preferredExit(follower.name,"L","l0")),"later follower retains its own crossing instead of inheriting a leader's circulation decision");
            boolean[] crossed=new boolean[3],arrived=new boolean[3];boolean heldOutside=false;
            for(int tick=0;tick<600/step&&(!arrived[0]||!arrived[1]||!arrived[2]);tick++) {
                n.tick(step);exclusiveCorridor(n);headway(all);
                RailNetwork.Node junction=n.nodes.get("R");RailNetwork.Pose leading=b.poses.get(0);
                if(!crossed[1]&&b.speed==0&&Math.hypot(leading.x-junction.worldX(),leading.y-junction.worldY())<6.6
                        &&!n.signalOccupiesNode(b,"R")&&!n.signalOccupiesNode(b,"r0")) {
                    check("c39".equals(traffic.preferredExit(b.name,"R","r0")),"actual red-light holding boundary keeps the opposite train aimed at the connector");heldOutside=true;
                }
                for(int i=0;i<all.size();i++) {
                    RailNetwork.Train train=all.get(i);
                    if(onConnector(train))crossed[i]=true;
                    String unwanted=i==1?"r22":"l22";
                    if(!crossed[i])for(RailNetwork.Pose pose:train.poses)
                        check(!unwanted.equals(pose.railName),"queued "+train.name+" must take the connector before making another station circuit");
                    if(train.routeIndex!=0) { check(crossed[i],"first scheduled stop follows the intended connector crossing");arrived[i]=true; }
                    check(train.powered,"automatic waiting retains train power and its pending station stop");
                }
            }
            check(arrived[0]&&arrived[1]&&arrived[2],"both queued directions and the later follower reach their first scheduled station without a diversion");
            check(heldOutside,"regression observes the real safely held approach outside the receiving clearance");
        }
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train older=service(n,"OlderRequest","l2","l1",2,true);
        RailNetwork.Train inside=service(n,"InsideApproach","r0","R",2,false);
        RailNetwork.Train behind=service(n,"OutsideFollower","r4","r3",1,false);
        n.start(older.name);n.start(inside.name);n.start(behind.name);
        traffic.prepare(older,Arrays.asList("L","c1"));traffic.prepare(inside,Arrays.asList("R","c39"));traffic.prepare(behind,Arrays.asList("R","c39"));
        check("r22".equals(traffic.preferredExit(inside.name,"R","r0")),"a train physically inside the receiving clearance may still circulate to release an older opposite crossing");
        check("c39".equals(traffic.preferredExit(behind.name,"R","r0")),"a safe follower does not copy the close leader's exceptional clearing circuit");
        n=dumbbell(40);traffic=n.signals().traffic();
        older=service(n,"SameSideOlder","l8","l7",1,true);
        inside=service(n,"SameSideInside","l0","L",2,true);
        n.start(older.name);n.start(inside.name);
        traffic.prepare(older,Arrays.asList("L","c1"));traffic.prepare(inside,Arrays.asList("L","c1"));
        check("c1".equals(traffic.preferredExit(inside.name,"L","l0")),"an older same-side request never requires a close train to clear an opposite receiving berth");
    }

    private static void stoppedOwnerAndManualApproach() {
        Fixture f=new Fixture();RailNetwork n=f.network;n.start(f.a.name);n.start(f.b.name);
        TrafficControl.Lease held=awaitEnteredConnector(n,240);
        RailNetwork.Train owner=n.trains.get(held.trainName),other=owner==f.a?f.b:f.a;
        n.stop(owner.name);List<RailNetwork.Pose> stopped=new ArrayList<RailNetwork.Pose>(owner.poses);
        check(lease(n,owner.name)!=null,"STOP while inside the connector retains the exclusive token");
        n.stop(other.name);n.setRoute(other.name,Collections.<String>emptyList());n.start(other.name);
        for(int i=0;i<1000;i++) {
            n.tick(.1);exclusiveCorridor(n);clearOfEachOther(owner,other);
            check(lease(n,owner.name)!=null,"a stopped connector owner retains protection during another train's manual approach");
            check(!onConnector(other),"manual operation cannot intrude into an occupied connector");
            samePoses(stopped,owner.poses,"STOP holds every native pose");
        }
        check(!owner.powered&&owner.speed==0,"dispatch never restarts an explicitly stopped train");
        check(other.speed==0,"manual opposing train waits safely outside the connector");
    }

    private static void checkpointAndRestart() throws Exception {
        Fixture f=new Fixture();RailNetwork n=f.network;
        RailNetwork.Checkpoint before=n.checkpoint(f.a.name);
        List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(f.a.poses);
        n.start(f.a.name);
        TrafficControl.Lease entered=awaitEnteredConnector(n,240);
        check(entered.trainName.equals(f.a.name),"only the started train can enter the connector");
        n.restore(before);
        check(n.signals().traffic().leases().isEmpty(),"rollback removes a provisionally acquired corridor token");
        check(n.signals().reservations().isEmpty(),"rollback removes its matching signal authorities");
        check(!f.a.powered,"rollback restores drive power");samePoses(original,f.a.poses,"rollback restores the entire consist");
        n.start(f.a.name);entered=awaitEnteredConnector(n,240);
        RailNetwork.Checkpoint held=n.checkpoint(f.a.name);String token=entered.corridorId;
        n.signals().traffic().removeTrain(f.a.name);
        check(n.signals().traffic().leases().isEmpty(),"fixture simulates a failed transaction that removed provisional dispatch state");
        n.restore(held);
        check(lease(n,f.a.name)!=null&&lease(n,f.a.name).corridorId.equals(token),"rollback restores an already entered exclusive token");
        check(lease(n,f.a.name).entered,"restored corridor token remembers the train has entered");
        Path directory=Files.createTempDirectory("wurmrail-traffic-");Path file=directory.resolve("rail.properties");
        try {
            n.save(file);RailNetwork loaded=RailNetwork.load(file);
            check(loaded.signals().traffic().leases().isEmpty(),"restart resets transient dispatcher tokens");
            check(loaded.signals().reservations().isEmpty(),"restart resets transient signal authorities");
            check(loaded.signals().definitions().size()==6,"only actual player signal definitions persist");
            check(loaded.trains.get(f.a.name).route.equals(f.a.route)&&loaded.trains.get(f.b.name).route.equals(f.b.route),"restart preserves both automatic itineraries");
            check(!loaded.trains.get(f.a.name).powered&&!loaded.trains.get(f.b.name).powered,"restart leaves every train stopped");
            samePoses(f.a.poses,loaded.trains.get(f.a.name).poses,"restart preserves position and locomotive facing inside the connector");
        } finally {
            Files.deleteIfExists(file);Files.deleteIfExists(directory);
        }
    }
    private static RailNetwork.Train service(RailNetwork n,String name,String start,String toward,int cars,boolean right) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<cars;i++)ids.add(car++);
        RailNetwork.Train train=n.createTrain(name,1,ids,start,toward);
        n.setRoute(name,right?Arrays.asList("r11","l11"):Arrays.asList("l11","r11"),.5);return train;
    }
    /** Three committed same-side members form a turn before the older opponent takes over. */
    private static void followingConvoyFairness() {
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train a=service(n,"ConvoyA","l0","L",2,true),b=service(n,"ConvoyB","l8","l7",1,true);
        RailNetwork.Train late=service(n,"ConvoyLate","l16","l15",3,true),opposite=service(n,"ConvoyOpposite","r11","r10",1,false);
        List<RailNetwork.Train> all=new ArrayList<RailNetwork.Train>(Arrays.asList(a,b,late,opposite));
        n.start(a.name);n.start(b.name);boolean together=false;
        for(int step=0;step<2200&&!together;step++) {
            n.tick(.1);exclusiveCorridor(n);headway(all);
            together=onConnector(a)&&onConnector(b)&&lease(n,a.name)!=null&&lease(n,b.name)!=null;
        }
        check(together,"two same-direction trains occupy different blocks of one connector simultaneously");
        TrafficControl.Lease aLease=lease(n,a.name),bLease=lease(n,b.name);
        check(traffic.compatible(a.name,b.name)&&aLease.entryJunction.equals(bLease.entryJunction),"following members have independent leases and a shared directional permission");
        check(bLease.berth.size()>=aLease.berth.size()&&bLease.exitNeighbor.equals(aLease.exitNeighbor),"later following member reserves additional receiving capacity in the same circulation lane");
        n.stop(a.name);n.stop(b.name);
        List<RailNetwork.Pose> stoppedA=new ArrayList<RailNetwork.Pose>(a.poses),stoppedB=new ArrayList<RailNetwork.Pose>(b.poses);
        failure(()->n.reverse(a.name),"an entered leader cannot reverse into another admitted follower");
        failure(()->n.reverse(b.name),"an entered follower cannot reverse against another admitted cohort member");
        check(lease(n,a.name)!=null&&lease(n,b.name)!=null,"STOP and denied reversal retain entered directional protection");

        TrafficControl.Admission unused=traffic.prepare(late,Arrays.asList("L","c1"));
        check(unused.granted&&traffic.commit(unused),"compatible same-side request may provisionally reserve a future crossing before reaching the connector");
        check(lease(n,late.name)!=null&&!lease(n,late.name).entered,"new admission is distinct from physically entering the connector");
        n.stop(late.name);
        check(lease(n,late.name)==null&&lease(n,a.name)!=null&&lease(n,b.name)!=null,"STOP cancels only an unused follower lease and preserves the stopped entered members");
        int originalCars=late.carIds.size();long unusedCart=car++;n.addCar(late.name,unusedCart);
        check(late.carIds.size()==originalCars+1&&late.carIds.contains(unusedCart),"cancelled unused admission permits ordinary stopped-train coupling before a new receiving-capacity request");
        n.removeCar(late.name,unusedCart);

        RailNetwork.Train third=service(n,"ConvoyThird","l4","l3",1,true);all.add(third);n.start(third.name);
        TrafficControl.Admission thirdAdmission=traffic.prepare(third,Arrays.asList("L","c1"));
        check(thirdAdmission.granted&&traffic.commit(thirdAdmission),"cancelled unused membership refunds a slot for the third committed follower");

        n.start(opposite.name);
        TrafficControl.Admission waiting=traffic.prepare(opposite,Arrays.asList("R","c39"));
        check(!waiting.granted&&traffic.queueStatus(opposite.name).contains("Waiting"),"opposing request joins the FIFO queue while the current convoy remains protected");
        n.start(late.name);
        check(!traffic.prepare(late,Arrays.asList("L","c1")).granted&&lease(n,late.name)==null,"a fourth same-direction request waits after the three committed admissions while an opponent queues");
        for(int step=0;step<20;step++) {
            n.tick(.1);exclusiveCorridor(n);headway(all);
            samePoses(stoppedA,a.poses,"stopped leader remains still while traffic queues");samePoses(stoppedB,b.poses,"stopped follower remains still while traffic queues");
            check(lease(n,opposite.name)==null&&lease(n,late.name)==null,"neither opposing traffic nor a late follower bypasses an entered stopped convoy");
        }
        Map<String,RailNetwork.Checkpoint> checkpoints=new LinkedHashMap<String,RailNetwork.Checkpoint>();
        for(RailNetwork.Train train:all)checkpoints.put(train.name,n.checkpoint(train.name));
        String queue=traffic.queueStatus(opposite.name);Set<String> membership=memberships(n);
        traffic.removeTrain(a.name);traffic.removeTrain(b.name);
        for(RailNetwork.Checkpoint checkpoint:checkpoints.values())n.restore(checkpoint);
        check(membership.equals(memberships(n))&&queue.equals(traffic.queueStatus(opposite.name)),"rollback restores all convoy members and original opposing FIFO position");
        n.start(a.name);n.start(b.name);
        boolean opposingEntered=false,lateEntered=false;
        for(int step=0;step<6000&&!lateEntered;step++) {
            n.tick(.1);exclusiveCorridor(n);headway(all);
            TrafficControl.Lease opponent=lease(n,opposite.name);
            if(opponent!=null&&!opposingEntered) {
                opposingEntered=true;
                for(String rail:aLease.nodes) { check(!n.signalOccupiesNode(a,rail),"opposing turn waits until complete leader clears connector");check(!n.signalOccupiesNode(b,rail),"opposing turn waits until complete follower clears connector"); }
                check(!n.signalOccupiesNode(a,aLease.exitNeighbor)&&!n.signalOccupiesNode(b,bLease.exitNeighbor),"opposing turn also waits for each whole consist to clear its receiving exit");
            }
            if(lease(n,late.name)!=null) {
                check(opposingEntered,"late same-side service cannot receive another crossing before the older opponent");lateEntered=true;
            }
        }
        check(opposingEntered&&lateEntered,"closed convoy batch drains and both the opposing service and late long consist receive fair subsequent turns; A="+a.status+", B="+b.status+", O="+opposite.status+", late="+late.status);
        System.out.println("Following convoy: simultaneous members, safe STOP, three-member turn, opposing handover and late long-consist turn passed.");
    }
    /** Both ends stay queued: three real followers share a turn, and a circling receiver requeues. */
    private static void boundedDirectionalBatches() {
        for(double dt:new double[]{.1,.25}) {
            RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
            List<RailNetwork.Train> all=Arrays.asList(service(n,"BatchL0","l0","L",2,true),service(n,"BatchL1","l7","l6",1,true),
                    service(n,"BatchL2","l14","l13",2,true),service(n,"BatchL3","l21","l20",1,true),
                    service(n,"BatchR0","r0","R",1,false),service(n,"BatchR1","r8","r7",1,false),service(n,"BatchR2","r16","r15",1,false));
            for(RailNetwork.Train train:all)n.start(train.name);
            for(int index:new int[]{0,4,1,2,3,5,6})traffic.prepare(all.get(index),index<4?Arrays.asList("L","c1"):Arrays.asList("R","c39"));
            Set<String> previous=new HashSet<String>();List<String> grants=new ArrayList<String>();boolean[] arrived=new boolean[7];
            boolean simultaneous=false,opposite=false,done=false;
            for(int step=0;step<(int)(1600/dt)&&!done;step++) {
                n.tick(dt);headway(all);exclusiveCorridor(n);
                Set<String> current=new HashSet<String>();int together=0;
                for(TrafficControl.Lease held:traffic.leases()) {
                    current.add(held.trainName);if(!previous.contains(held.trainName)) {
                        grants.add(held.trainName);
                        if(held.entryJunction.equals("R"))opposite=true;
                        if(held.trainName.equals("BatchL3"))check(opposite,"fourth left follower waits for an opposing turn despite continuous left demand");
                    }
                    if(held.entryJunction.equals("L")&&onConnector(n.trains.get(held.trainName)))together++;
                }
                if(together>=3)simultaneous=true;previous=current;
                done=true;for(int i=0;i<7;i++) { if(all.get(i).routeIndex!=0)arrived[i]=true;if(!arrived[i])done=false; }
            }
            check(grants.size()>=4&&grants.subList(0,3).equals(Arrays.asList("BatchL0","BatchL1","BatchL2")),"an older opposing request still allows the first directional turn to admit three FIFO members: "+grants);
            check(simultaneous&&opposite&&done,"both directional queues drain safely after receivers circulate; every service reaches its original first station at dt="+dt+": "+Arrays.toString(arrived));
        }
    }
    private static void batchAdmissionLifecycle() {
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train a=service(n,"SlotA","l0","L",1,true),b=service(n,"SlotB","l5","l4",1,true),
                c=service(n,"SlotC","l10","l9",1,true),d=service(n,"SlotD","l16","l15",1,true),opposite=service(n,"SlotOpposite","r11","r10",1,false);
        check(a.direction==1&&b.direction==1,"batch lifecycle fixture uses compatible forward station departures");
        for(RailNetwork.Train train:Arrays.asList(a,b,c,d,opposite))n.resumeScheduled(train.name);
        TrafficControl.Admission first=traffic.prepare(a,Arrays.asList("L","c1"));check(first.granted&&traffic.commit(first),"first actual commit spends the first directional slot");
        check(!traffic.prepare(opposite,Arrays.asList("R","c39")).granted,"opponent queues while first turn is active");
        TrafficControl.Admission prospective=traffic.prepare(b,Arrays.asList("L","c1"));
        check(prospective.granted&&traffic.prepare(b,Arrays.asList("L","c1")).granted,"repeated preflight does not spend follower slots");
        check(!traffic.prepare(c,Arrays.asList("L","c1")).granted,"FIFO within one direction keeps a younger third candidate behind the older follower");
        check(!traffic.commit(prospective),"queue revision invalidates an old uncommitted candidate without consuming quota");
        prospective=traffic.prepare(b,Arrays.asList("L","c1"));check(prospective.granted&&traffic.commit(prospective),"the compatible older follower shares the directional phase by track entry");
        prospective=traffic.prepare(c,Arrays.asList("L","c1"));check(prospective.granted&&traffic.commit(prospective),"third actual commit fills the bounded turn");
        check(!traffic.prepare(d,Arrays.asList("L","c1")).granted,"fourth candidate cannot starve a waiting opposite direction");
        RailNetwork.Checkpoint full=n.checkpoint(c.name);String before=traffic.queueStatus(d.name);
        n.stop(c.name);prospective=traffic.prepare(d,Arrays.asList("L","c1"));
        check(prospective.granted&&traffic.commit(prospective),"STOP refunds an unused third grant immediately");
        n.restore(full);
        check(before.equals(traffic.queueStatus(d.name))&&!traffic.prepare(d,Arrays.asList("L","c1")).granted,"checkpoint restores full quota and directional FIFO metadata atomically");
        n.stop(a.name);n.stop(b.name);n.stop(c.name);n.stop(d.name);traffic.updateClearance();
        prospective=traffic.prepare(opposite,Arrays.asList("R","c39"));
        check(!prospective.granted&&prospective.reason.contains("clear"),"refunded quota never bypasses real parked bodies on the receiving approach");
        for(RailNetwork.Train train:Arrays.asList(a,b,c,d))n.removeTrain(train.name);
        prospective=traffic.prepare(opposite,Arrays.asList("R","c39"));
        check(prospective.granted&&traffic.commit(prospective),"cancelling every unused slot hands over without waiting for quota or abandoned requests");
    }
    private static void underfilledBatchDrain() {
        RailNetwork n=dumbbell(40);RailNetwork.Train leader=service(n,"ShortTurn","l0","L",2,true),opposite=service(n,"ReadyOpposite","r11","r10",1,false);
        n.start(leader.name);n.start(opposite.name);TrafficControl traffic=n.signals().traffic();
        traffic.prepare(leader,Arrays.asList("L","c1"));traffic.prepare(opposite,Arrays.asList("R","c39"));
        boolean handedOver=false;for(int i=0;i<4000&&!handedOver;i++) { n.tick(.1);headway(Arrays.asList(leader,opposite));exclusiveCorridor(n);handedOver=lease(n,opposite.name)!=null; }
        check(handedOver,"one-train underfilled turn drains promptly when no ready same-side follower exists");
    }
    private static void soleMemberBackout() {
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train a=service(n,"BackoutOwner","l0","L",2,true),follower=service(n,"BackoutFollower","l8","l7",1,true);
        n.start(a.name);awaitEnteredConnector(n,180);n.stop(a.name);n.setRoute(a.name,Collections.<String>emptyList());
        List<RailNetwork.Pose> original=new ArrayList<RailNetwork.Pose>(a.poses);int direction=a.direction;n.reverse(a.name);
        check(a.direction==-direction&&lease(n,a.name)!=null,"sole entered owner may reverse for manual backout without losing its corridor protection");
        samePoses(original,a.poses,"manual backout traction change preserves body positions and facing");
        TrafficControl.Admission denied=traffic.prepare(follower,Arrays.asList("L","c1"));
        check(!denied.granted&&!traffic.compatible(a.name,follower.name)&&lease(n,follower.name)==null,"sole reversing owner closes the convoy to new followers under its original direction token");
        n.start(a.name);
        for(int step=0;step<1500&&lease(n,a.name)!=null;step++) { n.tick(.1);exclusiveCorridor(n);headway(Arrays.asList(a,follower)); }
        check(lease(n,a.name)==null,"manual backout releases its direction permission only after the entire consist clears the connector");
        n.stop(a.name);
        check(traffic.prepare(follower,Arrays.asList("L","c1")).granted,"new same-side service can request the corridor after the sole reversing body clears");
    }
    private static void staleAdmission() {
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train a=service(n,"StaleOwner","l4","l3",1,true),opposite=service(n,"StaleOpponent","r11","r10",1,false);
        TrafficControl.Admission first=traffic.prepare(a,Arrays.asList("L","c1"));check(first.granted,"empty connector permits a prospective dispatcher plan");
        n.start(opposite.name);traffic.prepare(opposite,Arrays.asList("R","c39"));
        check(!traffic.commit(first)&&lease(n,a.name)==null,"queue or cohort changes invalidate an older admission instead of reusing stale receiving capacity");
        traffic.cancelUnused(a.name);traffic.cancelUnused(opposite.name);
        first=traffic.prepare(a,Arrays.asList("L","c1"));check(first.granted,"fresh admission is available after cancelled prospective plans");
        a.direction=-a.direction;
        check(!traffic.commit(first)&&lease(n,a.name)==null,"a pending plan cannot commit after its train changes traction direction");
        a.direction=-a.direction;traffic.cancelUnused(a.name);
        first=traffic.prepare(a,Arrays.asList("L","c1"));check(first.granted,"unleased stopped train may prepare an admission before ordinary coupling");
        long added=car++;n.addCar(a.name,added);
        check(a.carIds.size()==2&&a.carIds.contains(added),"coupling before admission commit remains an allowed stopped-train operation");
        check(!traffic.commit(first)&&traffic.leases().isEmpty(),"changed pending consist size invalidates old receiving capacity before any corridor claims are committed");
    }
    private static void receivingCapacity() {
        RailNetwork n=dumbbell(40);TrafficControl traffic=n.signals().traffic();
        RailNetwork.Train leader=service(n,"CapacityLeader","l0","L",2,true),follower=service(n,"CapacityFollower","l8","l7",3,true);
        // Both parked receivers establish the same circulation, while the south one
        // leaves enough receiving room for one short consist, but not their combined lengths.
        service(n,"CapacityNorthBlocker","r4","r3",1,false);
        service(n,"CapacitySouthBlocker","r18","r17",1,false);
        n.start(leader.name);
        TrafficControl.Admission first=traffic.prepare(leader,Arrays.asList("L","c1"));
        check(first.granted&&traffic.commit(first),"one whole short train fits in the clear receiving section before a parked unrelated service");
        n.start(follower.name);TrafficControl.Admission second=traffic.prepare(follower,Arrays.asList("L","c1"));
        check(!second.granted&&second.reason.contains("room for every car")&&lease(n,follower.name)==null,"compatible follower is denied when aggregate whole-consist receiving capacity is blocked");
        check(lease(n,leader.name)!=null,"failed following-capacity preflight preserves the already admitted train's direction permission");
    }
    private static void consistExpansionSafety() {
        RailNetwork n=dumbbell(40);
        RailNetwork.Train a=service(n,"CapacityFixedA","l0","L",1,true),b=service(n,"CapacityFixedB","l8","l7",1,true),c=service(n,"CapacityFixedC","l16","l15",1,true);
        List<RailNetwork.Train> all=Arrays.asList(a,b,c);
        for(RailNetwork.Train train:all) { n.setRoute(train.name,Collections.singletonList("r11"),0);n.start(train.name); }
        boolean together=false;
        for(int step=0;step<2500&&!together;step++) {
            n.tick(.1);exclusiveCorridor(n);headway(all);
            together=onConnector(a)&&onConnector(b)&&onConnector(c)&&lease(n,a.name)!=null&&lease(n,b.name)!=null&&lease(n,c.name)!=null;
        }
        check(together,"consist-capacity regression has three single-car trains admitted and physically inside the connector");
        n.stop(a.name);List<Long> ids=new ArrayList<Long>(a.carIds);List<RailNetwork.Pose> poses=new ArrayList<RailNetwork.Pose>(a.poses);Set<String> before=memberships(n);long cart=car++;
        failure(()->n.addCar(a.name,cart),"entered convoy member cannot expand beyond its admitted whole-consist receiving capacity");
        check(a.carIds.equals(ids)&&before.equals(memberships(n)),"rejected entered coupling preserves original car identities and every cohort lease");
        samePoses(poses,a.poses,"rejected entered coupling preserves each original native pose");
        n.start(a.name);boolean partiallyCleared=false;
        for(int step=0;step<2500&&!partiallyCleared;step++) {
            n.tick(.1);exclusiveCorridor(n);headway(all);
            partiallyCleared=lease(n,a.name)==null&&(lease(n,b.name)!=null||lease(n,c.name)!=null);
        }
        check(partiallyCleared,"leader can discharge its active crossing while following members still need its receiving-lane permission");
        n.stop(a.name);poses=new ArrayList<RailNetwork.Pose>(a.poses);before=memberships(n);
        failure(()->n.addCar(a.name,cart),"discharged cohort member cannot expand while its receiving-lane compatibility is still needed");
        check(a.carIds.equals(ids)&&before.equals(memberships(n)),"rejected discharged coupling preserves identities and remaining cohort reservations");
        samePoses(poses,a.poses,"rejected discharged coupling preserves parked leader poses");
        n.start(a.name);
        for(int step=0;step<2500&&!n.signals().traffic().leases().isEmpty();step++) { n.tick(.1);exclusiveCorridor(n);headway(all); }
        check(n.signals().traffic().leases().isEmpty(),"all admitted bodies eventually clear and release the aggregate capacity promise");
        for(RailNetwork.Train train:all)n.stop(train.name);
        n.removeTrain(b.name);n.removeTrain(c.name); // Make the physical rail behind the stopped leader available.
        n.addCar(a.name,cart);
        check(a.carIds.size()==2&&a.carIds.contains(cart),"after the entire cohort drains a stopped train may couple when physical space is available");
    }
    private static Set<String> memberships(RailNetwork n) {
        Set<String> rows=new HashSet<String>();for(TrafficControl.Lease lease:n.signals().traffic().leases())rows.add(lease.trainName+"|"+lease.corridorId+"|"+lease.entryJunction+"|"+lease.entered+"|"+lease.tractionDirection);return rows;
    }
    private static void headway(List<RailNetwork.Train> trains) {
        for(int i=0;i<trains.size();i++)for(int j=i+1;j<trains.size();j++)for(RailNetwork.Pose a:trains.get(i).poses)for(RailNetwork.Pose b:trains.get(j).poses)
            check(Math.hypot(a.x-b.x,a.y-b.y)>=3.999,"separate train cars retain four metres physical headway while following");
    }
    private static void failure(Runnable action,String message) {
        try { action.run(); }catch(IllegalArgumentException expected) { assertions++;return; }throw new AssertionError(message);
    }

    private static TrafficControl.Lease awaitEnteredConnector(RailNetwork n,double seconds) {
        for(int step=0;step<seconds*10;step++) {
            n.tick(.1);exclusiveCorridor(n);
            for(TrafficControl.Lease lease:n.signals().traffic().leases()) {
                RailNetwork.Train train=n.trains.get(lease.trainName);
                if(lease.entered&&onConnector(train))return lease;
            }
        }
        throw new AssertionError("No train entered the connector: "+n.trains.values().iterator().next().status);
    }
    private static TrafficControl.Lease lease(RailNetwork n,String train) {
        for(TrafficControl.Lease lease:n.signals().traffic().leases())if(lease.trainName.equals(train))return lease;
        return null;
    }
    private static boolean onConnector(RailNetwork.Train train) {
        for(RailNetwork.Pose pose:train.poses)if(pose.railName!=null&&pose.railName.matches("c[1-9][0-9]*"))return true;
        return false;
    }
    private static void exclusiveCorridor(RailNetwork n) {
        Map<String,String> directions=new HashMap<String,String>();Set<String> owners=new HashSet<String>();
        for(TrafficControl.Lease lease:n.signals().traffic().leases()) {
            String previous=directions.put(lease.corridorId,lease.entryJunction);
            check(previous==null||previous.equals(lease.entryJunction),"all members of one corridor share exactly one direction");
            check(owners.add(lease.corridorId+"|"+lease.trainName),"each following train owns its separate corridor lease");
        }
    }
    private static void clearOfEachOther(RailNetwork.Train a,RailNetwork.Train b) {
        for(RailNetwork.Pose first:a.poses)for(RailNetwork.Pose second:b.poses)
            check(Math.hypot(first.x-second.x,first.y-second.y)>=.899,"separate train bodies never overlap");
    }
    private static void smoothHeading(RailNetwork.Train train,Map<Long,Float> headings) {
        for(RailNetwork.Pose pose:train.poses) {
            Float previous=headings.put(pose.carId,pose.rotation);
            if(previous!=null) {
                double delta=Math.abs(pose.rotation-previous)%360;delta=Math.min(delta,360-delta);
                check(delta<90,"locomotive/cart body cannot spin 180 degrees in place");
            }
        }
    }
    private static void samePoses(List<RailNetwork.Pose> expected,List<RailNetwork.Pose> actual,String message) {
        check(expected.size()==actual.size(),message+" (count)");
        for(int i=0;i<expected.size();i++) {
            RailNetwork.Pose a=expected.get(i),b=actual.get(i);
            check(a.carId==b.carId&&Math.abs(a.x-b.x)<.0001&&Math.abs(a.y-b.y)<.0001&&Math.abs(a.rotation-b.rotation)<.0001,message);
        }
    }
    private static String describe(RailNetwork n,RailNetwork.Train a,RailNetwork.Train b) {
        return a.name+" "+a.poses+" "+a.status+" / "+n.signals().trafficStatus(a.name)+"; "+
                b.name+" "+b.poses+" "+b.status+" / "+n.signals().trafficStatus(b.name);
    }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
