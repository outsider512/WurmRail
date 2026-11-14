package org.wurmrail.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Actual movement, topology, ownership and restart checks without a native Wurm dependency. */
public final class SignallingSelfTest {
    private static int assertions;
    private static long nextCar=90000;
    public static void main(String[] args) throws Exception {
        emptyBlock();occupiedBlock();reservedAndOwned();junctionDesiredRoute();junctionRearClearance();
        oppositeJunction();conflictingJunctionRoutes();automaticWaiting();directionalSignals();ambiguousRoute();
        cancellationAndEditing();atomicJunctionAcquisition();checkpointRollback();restart();unsignalledIntrusion();allCompassDirections();
        diagonalReservedCrossing();occupiedStopAndReverse();waitingForReservation();inconsistentAuthority();
        scheduledStopsInsideBlock();scheduledTerminalShuttle();scheduledCurvedShuttle();
        singleSignalCircuit();junctionSignalCircuit();sameEntryJunctionCircuit();redSignalRecovery();
        blockConflictSeparation();pendingCohortAdmissionAtomicity();followingCohortAuthorities();
        System.out.println("SignallingSelfTest: "+assertions+" assertions passed.");
    }
    private static RailNetwork line(int length) {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<length;i++) { n.addNode("p"+i,100+i,100,0);if(i>0)n.connect("p"+(i-1),"p"+i); }
        return n;
    }
    private static void blockConflictSeparation() {
        RailNetwork n=line(20);signals(n);
        RailNetwork.Train leader=train(n,"blockLeader","p1","p2",1),follower=train(n,"blockFollower","p17","p16",1);
        check(n.signals().request(leader.name,"entry").granted,"block separation fixture owns an actual green authority");
        Set<String> inside=n.signalRouteResources(Collections.singletonList("p6"));
        check(n.signals().conflictsBlockResources(follower.name,inside),"actual foreign block protection is detected independently of dispatch direction");
        check(!n.signals().conflictsBlockResourcesExcept(follower.name,inside,Collections.singleton(leader.name)),
                "a prospective corridor plan can inspect compatible members without claiming their actual blocks");
        check(n.signals().conflictsBlockResourcesExcept(follower.name,inside,Collections.singleton(follower.name)),
                "prospective exemptions never ignore an owner who was not explicitly permitted");
        check(n.signals().conflictsResources(follower.name,inside),"normal movement still conflicts with the leader's exclusive block after a prospective exemption");
        check(!n.signals().request(follower.name,"entry").granted&&!n.signals().ownsAuthority(follower.name,"entry")
                &&n.signals().ownsAuthority(leader.name,"entry"),"same direction eligibility can never borrow another train's displayed green authority");
    }

    private static void pendingCohortAdmissionAtomicity() {
        RailNetwork n=followingDumbbell();
        RailNetwork.Train a=train(n,"atomicLeader","followc20","followc21",1);
        RailNetwork.Train b=train(n,"atomicFollower","followl0","followL",1);
        RailNetwork.Train lock=train(n,"atomicPointsOwner","followl11","followl10",1);
        n.setRoute(a.name,Collections.singletonList("followr11"));n.setRoute(b.name,Collections.singletonList("followr11"));
        check(n.signals().request(a.name,"followL_0").granted,"pending admission fixture acquires its initial directional crossing");
        n.signals().cancelUnused(a.name);n.signals().updateClearance();
        check(hasTrafficLease(n,a.name)&&n.signals().reservations().isEmpty(),"an entered leader retains its crossing after an unused remote signal block is cancelled");
        check(n.requestJunctionAlignment(lock.name,"followL",5),"independent point lock can reject a following route after its corridor admission was prepared");
        Set<String> beforeLeases=leaseSnapshot(n),beforeAuthorities=authoritySnapshot(n);
        SignalSystem.RequestResult refused=n.signals().request(b.name,"followL_0");
        check(!refused.granted&&refused.reason.contains("locked")&&!hasTrafficLease(n,b.name),"failed junction acquisition cannot commit a pending follower into the cohort");
        check(beforeLeases.equals(leaseSnapshot(n))&&beforeAuthorities.equals(authoritySnapshot(n))
                &&lock.name.equals(n.nodes.get("followL").getAlignmentOwner())&&n.getJunctionAlignment("followL")==5,
                "failed follower acquisition preserves existing leases, block authorities and physical point ownership exactly");
        check(n.signals().trafficStatus(b.name).contains("Waiting"),"a failed pending follower keeps only its fair queue registration");
        check(n.releaseJunctionAlignment(lock.name,"followL"),"temporary independent point lock can clear without changing the occupied corridor");
        RailNetwork.Checkpoint before=n.checkpoint(b.name);String beforeQueue=n.signals().trafficStatus(b.name);
        check(n.signals().request(b.name,"followL_0").granted&&hasTrafficLease(n,b.name),"the same queued follower may acquire its own block after the point lock clears");
        check(n.signals().ownsAuthority(b.name,"followL_0")&&!n.signals().ownsAuthority(a.name,"followL_0"),"committed follower authority belongs only to the actual following train");
        n.restore(before);
        check(beforeLeases.equals(leaseSnapshot(n))&&beforeAuthorities.equals(authoritySnapshot(n))
                &&beforeQueue.equals(n.signals().trafficStatus(b.name))&&n.nodes.get("followL").getAlignmentOwner()==null,
                "checkpoint rollback atomically restores the pre-commit cohort, queue, exclusive block and point lock state");
    }

    /** Three independent block authorities may follow one corridor direction; the other end must drain them all. */
    private static void followingCohortAuthorities() {
        RailNetwork n=followingDumbbell();
        RailNetwork.Train a=train(n,"followA","followl0","followL",2);
        RailNetwork.Train b=train(n,"followB","followl8","followl7",1);
        RailNetwork.Train c=train(n,"followC","followl16","followl15",3);
        RailNetwork.Train d=train(n,"followOpposite","followr11","followr10",1);
        List<RailNetwork.Train> all=Arrays.asList(a,b,c,d),outbound=Arrays.asList(a,b,c);
        for(RailNetwork.Train t:outbound) { n.setRoute(t.name,Arrays.asList("followr11","followl11"),.5);n.start(t.name); }
        n.setRoute(d.name,Arrays.asList("followl11","followr11"),.5);
        boolean together=false;TrafficControl.Lease lastLease=null;
        for(int step=0;step<3000&&!together;step++) {
            n.tick(.1);assertExclusiveAuthorities(n);assertSingleCorridorDirection(n);
            int members=0,onConnector=0;
            for(TrafficControl.Lease lease:n.signals().traffic().leases())if(lease.entryJunction.equals("followL")) {
                if(lease.trainName.equals(a.name)||lease.trainName.equals(b.name)||lease.trainName.equals(c.name))members++;
                if(lease.trainName.equals(c.name))lastLease=lease;
            }
            for(RailNetwork.Train t:outbound)for(RailNetwork.Pose pose:t.poses)if(pose.railName.startsWith("followc")) { onConnector++;break; }
            together=members==3&&onConnector==3;
        }
        check(together,"three same-direction trains simultaneously hold separate block authorities inside the shared corridor");
        check(lastLease!=null,"last following consist has its own whole-train directional lease");
        n.start(d.name);boolean queued=false,rolledBack=false,dischargedRollback=false,opposingAdmitted=false;
        for(int step=0;step<4000&&!opposingAdmitted;step++) {
            n.tick(.1);assertExclusiveAuthorities(n);assertSingleCorridorDirection(n);
            if(n.signals().trafficStatus(d.name).contains("Waiting"))queued=true;
            if(queued&&!rolledBack) {
                Map<String,RailNetwork.Checkpoint> snapshots=new LinkedHashMap<String,RailNetwork.Checkpoint>();
                for(RailNetwork.Train t:all)snapshots.put(t.name,n.checkpoint(t.name));
                Set<String> beforeLeases=leaseSnapshot(n),beforeReservations=authoritySnapshot(n);
                String beforeQueue=n.signals().trafficStatus(d.name);
                n.tick(.7);for(RailNetwork.Checkpoint snapshot:snapshots.values())n.restore(snapshot);
                check(beforeLeases.equals(leaseSnapshot(n))&&beforeReservations.equals(authoritySnapshot(n))
                        &&beforeQueue.equals(n.signals().trafficStatus(d.name)),"a full movement rollback restores every cohort member, block authority and opposing queue position");
                rolledBack=true;
            }
            if(queued&&!dischargedRollback&&!hasTrafficLease(n,a.name)&&hasTrafficLease(n,c.name)) {
                Map<String,RailNetwork.Checkpoint> snapshots=new LinkedHashMap<String,RailNetwork.Checkpoint>();
                for(RailNetwork.Train t:all)snapshots.put(t.name,n.checkpoint(t.name));
                Set<String> beforeLeases=leaseSnapshot(n),beforeReservations=authoritySnapshot(n);
                n.tick(30);for(RailNetwork.Checkpoint snapshot:snapshots.values())n.restore(snapshot);
                check(beforeLeases.equals(leaseSnapshot(n))&&beforeReservations.equals(authoritySnapshot(n)),
                        "rollback restores a partially discharged cohort after a later direction handover");
                dischargedRollback=true;
            }
            for(TrafficControl.Lease lease:n.signals().traffic().leases())if(lease.trainName.equals(d.name)) {
                opposingAdmitted=true;
                for(TrafficControl.Lease other:n.signals().traffic().leases())if(other.corridorId.equals(lease.corridorId))
                    check(other.entryJunction.equals(lease.entryJunction),"opposing admission cannot overlap any member of the preceding direction");
                for(String rail:lastLease.nodes)check(!n.signalOccupiesNode(c,rail),"opposing admission waits for the last following cart to clear every corridor rail");
                check(!n.signalOccupiesNode(c,lastLease.exitNeighbor),"opposing admission also waits for the last following cart to clear the receiving exit");
            }
        }
        check(queued&&rolledBack&&dischargedRollback&&opposingAdmitted,"an opposing service waits for the cohort, survives rollback and eventually receives its fair direction turn; queued="+queued+", rollback="+rolledBack+", dischargedRollback="+dischargedRollback+", admitted="+opposingAdmitted+", leases="+leaseSnapshot(n)+", A="+a.status+"/"+a.poses+", B="+b.status+"/"+b.poses+", C="+c.status+"/"+c.poses+", D="+d.status+"/"+d.poses+", traffic="+n.signals().trafficStatus(d.name));
    }
    private static RailNetwork followingDumbbell() {
        RailNetwork n=new RailNetwork();n.addNode("followL",108,100,0);n.addNode("followR",148,100,0);
        followingLoop(n,"followL","followl",108,-1);followingLoop(n,"followR","followr",148,1);
        String previous="followL";
        for(int x=109;x<148;x++) { String key="followc"+(x-108);n.addNode(key,x,100,0);n.connect(previous,key);previous=key; }
        n.connect(previous,"followR");
        for(String junction:Arrays.asList("followL","followR")) {
            n.setJunctionAlignment(junction,17);RailNetwork.Node node=n.nodes.get(junction);
            for(String next:node.links)n.signals().add(junction+"_"+n.signalSide(junction,next),junction,n.signalSide(junction,next),-10L);
        }
        n.setStation("followl11",true,.5);n.setStation("followr11",true,.5);return n;
    }
    private static void followingLoop(RailNetwork n,String junction,String prefix,int x,int across) {
        String previous=junction;int index=0;
        for(int y=99;y>=96;y--)previous=followingPoint(n,previous,prefix+(index++),x,y);
        for(int step=1;step<=4;step++)previous=followingPoint(n,previous,prefix+(index++),x+across*step,96);
        for(int y=97;y<=104;y++)previous=followingPoint(n,previous,prefix+(index++),x+across*4,y);
        for(int step=3;step>=0;step--)previous=followingPoint(n,previous,prefix+(index++),x+across*step,104);
        for(int y=103;y>=101;y--)previous=followingPoint(n,previous,prefix+(index++),x,y);
        n.connect(previous,junction);
    }
    private static String followingPoint(RailNetwork n,String previous,String key,int x,int y) { n.addNode(key,x,y,0);n.connect(previous,key);return key; }
    private static void assertExclusiveAuthorities(RailNetwork n) {
        List<SignalSystem.Reservation> rows=new ArrayList<SignalSystem.Reservation>(n.signals().reservations());
        for(int i=0;i<rows.size();i++)for(int j=i+1;j<rows.size();j++)if(!rows.get(i).trainName.equals(rows.get(j).trainName)) {
            Set<String> overlap=authorityResources(n,rows.get(i));overlap.retainAll(authorityResources(n,rows.get(j)));
            check(overlap.isEmpty(),"cohort members retain disjoint actual signal block resources");
        }
    }
    private static Set<String> authorityResources(RailNetwork n,SignalSystem.Reservation r) {
        Set<String> result=n.signalRouteResources(r.nodes);SignalSystem.Signal entry=n.signals().get(r.entrySignalId);
        n.signalAddEntryResources(result,entry.rail,n.signalNeighbor(entry.rail,entry.approachDirection));return result;
    }
    private static void assertSingleCorridorDirection(RailNetwork n) {
        Map<String,String> entries=new HashMap<String,String>();
        for(TrafficControl.Lease lease:n.signals().traffic().leases()) {
            String old=entries.put(lease.corridorId,lease.entryJunction);
            check(old==null||old.equals(lease.entryJunction),"every concurrent member of a shared corridor has the same entry direction");
        }
    }
    private static Set<String> leaseSnapshot(RailNetwork n) {
        Set<String> result=new HashSet<String>();for(TrafficControl.Lease lease:n.signals().traffic().leases())
            result.add(lease.trainName+"|"+lease.corridorId+"|"+lease.entryJunction+"|"+lease.entered+"|"+lease.berth);return result;
    }
    private static boolean hasTrafficLease(RailNetwork n,String train) { for(TrafficControl.Lease lease:n.signals().traffic().leases())if(lease.trainName.equals(train))return true;return false; }
    private static Set<String> authoritySnapshot(RailNetwork n) {
        Set<String> result=new HashSet<String>();for(SignalSystem.Reservation r:n.signals().reservations())
            result.add(r.id+"|"+r.trainName+"|"+r.state+"|"+r.path+"|"+r.junctionLocks());return result;
    }
    private static RailNetwork branch() {
        RailNetwork n=line(16);
        for(int i=1;i<=9;i++) { n.addNode("n"+i,106,100-i,0);n.connect(i==1?"p6":"n"+(i-1),"n"+i); }
        n.setJunctionAlignment("p6",(1<<2)|(1<<6));return n;
    }
    private static RailNetwork.Train train(RailNetwork n,String id,String start,String toward,int cars) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<cars;i++)ids.add(nextCar++);
        return n.createTrain(id,1,ids,start,toward);
    }
    private static void signals(RailNetwork n) { n.signals().add("entry","p4",6,-10);n.signals().add("exit","p10",6,-10); }
    private static void emptyBlock() {
        RailNetwork n=line(18);signals(n);RailNetwork.Train t=train(n,"A","p1","p2",1);
        check(n.signals().aspect("entry")==SignalSystem.Aspect.RED,"unrequested signal is red");
        SignalSystem.RequestResult request=n.signals().request(t.name,"entry");
        check(request.granted,"empty block grants train-owned authority");
        check(request.reservation.nodes.equals(Arrays.asList("p4","p5","p6","p7","p8","p9")),"entire block is protected, excluding exit signal tile");
        check(request.reservation.path.get(6).equals("p10")&&request.reservation.exitSignalId.equals("exit"),"route traces to next protecting signal");
        check(n.signals().ownsAuthority(t.name,"entry")&&n.signals().aspect("entry")==SignalSystem.Aspect.GREEN,"owner sees valid green route");
        n.start(t.name);n.tick(35);
        check(t.poses.get(0).x>n.nodes.get("p10").worldX(),"train passes empty block and obtains the next block");
        check(n.signals().status("entry").ownerTrain==null,"entry reservation releases after rear clears");
    }
    private static void occupiedBlock() {
        RailNetwork n=line(18);signals(n);RailNetwork.Train a=train(n,"A","p1","p2",1);
        train(n,"occupant","p8","p9",1);
        SignalSystem.RequestResult result=n.signals().request(a.name,"entry");
        check(!result.granted&&result.reason.contains("occupant"),"distant occupied tile keeps whole block red");
        n.start(a.name);n.tick(20);
        check(a.poses.get(0).x<n.nodes.get("p4").worldX()-2,"red signal stops locomotive before tile entrance");
        check(a.powered&&a.speed==0&&a.status.contains("RED"),"manual driver stays powered waiting at red");
        check(n.signals().aspect("entry")==SignalSystem.Aspect.RED,"occupied signal never clears");
    }
    private static void reservedAndOwned() {
        RailNetwork n=line(20);signals(n);n.signals().add("opposite","p12",2,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1),b=train(n,"B","p16","p15",1);
        check(n.signals().request(a.name,"entry").granted,"first train reserves empty route");
        check(!n.signals().request(b.name,"opposite").granted,"opposite route sharing track conflicts");
        check(!n.signals().request(b.name,"entry").granted,"second train cannot borrow visible green signal");
        check(!n.signals().ownsAuthority(b.name,"entry")&&n.signals().ownsAuthority(a.name,"entry"),"authority remains attached to first train");
        check(n.signals().status("entry").ownerTrain.equals("A"),"visible signal diagnostics name actual owner");
        String first=n.signals().status("entry").reservationId;
        for(int i=0;i<30;i++)check(n.signals().request(a.name,"entry").reservation.id.equals(first),"repeated request reuses explicit reservation lifecycle");
    }
    private static void junctionDesiredRoute() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);n.signals().add("north_exit","n3",4,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1);n.setRoute(a.name,Arrays.asList("n9"));
        check(n.getJunctionAlignment("p6")==68,"junction initially points away from automatic destination");
        SignalSystem.RequestResult result=n.signals().request(a.name,"entry");
        check(result.granted&&result.reservation.junctionAlignments.get("p6")==65,"destination path requests absolute West-North pair");
        check(n.getJunctionAlignment("p6")==65&&n.nodes.get("p6").getAlignmentOwner().equals(a.name),"route acquire aligns and locks existing junction API");
        fails(()->n.setJunctionAlignment("p6",68),"manual switch cannot alter route-locked junction");
        check(!n.releaseJunctionAlignment(a.name,"p6"),"external release cannot drop a route's retained junction lock");
        check(!n.requestJunctionAlignment(a.name,"p6",68),"same train cannot change reserved alignment to another path");
        n.start(a.name);n.tick(50);
        check(a.poses.get(0).y<n.nodes.get("n7").worldY(),"automatic route passes correctly aligned junction");
    }
    private static void junctionRearClearance() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);n.signals().add("north_exit","n3",4,-10);
        RailNetwork.Train a=train(n,"long_train","p3","p4",3);n.setRoute(a.name,Arrays.asList("n9"));n.start(a.name);
        boolean enginePassed=false,tailClearedPoint=false,routeReleased=false;
        for(int i=0;i<1200;i++) {
            n.tick(.05);RailNetwork.Pose engine=a.poses.get(0),tail=a.poses.get(2);
            if(engine.y<n.nodes.get("n1").worldY()&&!enginePassed) {
                enginePassed=true;check(n.nodes.get("p6").getAlignmentOwner().equals(a.name),"engine clearing junction retains lock while rear cars approach");
                check(n.signals().status("entry").ownerTrain.equals(a.name),"reservation holds after locomotive passes junction");
            }
            if(enginePassed&&tail.y<n.nodes.get("n1").worldY()&&!tailClearedPoint) {
                tailClearedPoint=true;check(n.nodes.get("p6").getAlignmentOwner().equals(a.name),"junction remains locked after tail clears points but before protected block clears");
            }
            if(enginePassed&&n.signals().status("entry").ownerTrain==null) {
                routeReleased=true;check(n.nodes.get("p6").getAlignmentOwner()==null,"junction releases only after whole protected route clears");break;
            }
        }
        check(enginePassed&&tailClearedPoint&&routeReleased,"long consist traverses and clears protected block");
    }
    private static void oppositeJunction() {
        RailNetwork n=branch();n.signals().add("entry","n3",0,-10);n.signals().add("west_exit","p4",2,-10);
        RailNetwork.Train a=train(n,"reverse_approach","n6","n5",1);n.setRoute(a.name,Arrays.asList("p0"));
        SignalSystem.RequestResult result=n.signals().request(a.name,"entry");
        check(result.granted&&result.reservation.junctionAlignments.get("p6")==65,"opposite traversal requests same bidirectional North-West pair");
        n.start(a.name);n.tick(45);
        check(a.poses.get(0).x<n.nodes.get("p2").worldX(),"opposite traversal passes identical physical alignment");
    }
    private static void conflictingJunctionRoutes() {
        RailNetwork n=branch();n.signals().add("west","p4",6,-10);n.signals().add("north","n3",0,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1),b=train(n,"B","n7","n6",1);
        n.setRoute(a.name,Arrays.asList("p15"));n.setRoute(b.name,Arrays.asList("p12"));
        check(n.signals().request(a.name,"west").granted,"first crossing route owns junction");
        check(!n.signals().request(b.name,"north").granted,"incompatible whole-junction route remains red");
        check(n.nodes.get("p6").getAlignmentOwner().equals(a.name)&&n.getJunctionAlignment("p6")==68,"rejected route cannot disturb first physical alignment");
    }
    private static void automaticWaiting() {
        RailNetwork n=line(25);signals(n);RailNetwork.Train a=train(n,"waiting","p1","p2",1),b=train(n,"obstruction","p7","p8",1);
        n.setRoute(a.name,Arrays.asList("p24"));n.start(a.name);n.tick(10);
        float held=a.poses.get(0).x;check(a.powered&&a.speed==0&&a.status.contains("RED"),"automatic train waits powered before red");
        n.removeTrain(b.name);n.tick(15);
        check(a.poses.get(0).x>held+8,"waiting automatic train resumes when occupied route clears");
    }
    private static void directionalSignals() {
        RailNetwork n=line(18);n.signals().add("entry","p4",6,-10);n.signals().add("backside","p8",2,-10);
        RailNetwork.Train a=train(n,"eastbound","p1","p2",1);
        check(n.signals().request(a.name,"entry").reservation.exitSignalId==null,"backside signal does not delimit forward protected route");
        check(n.signals().status("backside").aspect==SignalSystem.Aspect.RED,"backside display has no forward authority");
        n.start(a.name);n.tick(35);
        check(a.poses.get(0).x>n.nodes.get("p8").worldX()+4,"forward train ignores backside signal");
    }
    private static void ambiguousRoute() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);n.setJunctionAlignment("p6",0);
        RailNetwork.Train a=train(n,"A","p1","p2",1);
        SignalSystem.RequestResult result=n.signals().request(a.name,"entry");
        check(!result.granted&&result.reason.contains("alignment"),"ambiguous manual junction fails red with diagnostic");
        check(n.signals().reservations().isEmpty()&&n.nodes.get("p6").getAlignmentOwner()==null,"failed discovery creates no phantom lock");
        fails(()->n.signals().add("bad","p2",0,-10),"signal cannot protect nonexistent approach");
        fails(()->n.signals().add("duplicate","p4",6,-10),"duplicate directional boundary rejected");
        fails(()->SignalRouteDiscovery.discover(n,a.name,new SignalSystem.Signal("broken","p4",0,-10)),"disconnected approach fails topology discovery");
        n.start(a.name);n.tick(20);
        check(a.poses.get(0).x<n.nodes.get("p4").worldX()-2&&n.signals().aspect("entry")==SignalSystem.Aspect.RED,"ambiguous route holds train before signal");
    }
    private static void cancellationAndEditing() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);RailNetwork.Train a=train(n,"A","p1","p2",1);
        n.setRoute(a.name,Arrays.asList("n9"));n.signals().request(a.name,"entry");
        fails(()->n.signals().remove("entry"),"reserved signal cannot be removed");
        fails(()->n.signals().configure("entry","p3",6),"reserved signal cannot be reconfigured");
        fails(()->n.disconnect("p5","p6"),"reserved route graph cannot be disconnected");
        fails(()->n.signals().add("inside","n2",4,-10),"cannot add block boundary inside reserved route");
        n.stop(a.name);check(n.signals().reservations().isEmpty()&&n.nodes.get("p6").getAlignmentOwner()==null,"STOP cancels unused route and unentered junction lock");
        long revision=n.signals().definitionRevision();n.signals().setItemId("entry",1234);
        check(n.signals().definitionRevision()==revision,"visual item repair does not invalidate captured semantic configuration");
        SignalSystem.DefinitionsCheckpoint before=n.signals().checkpointDefinitions();n.signals().remove("entry");n.signals().restoreDefinitions(before);
        check(n.signals().get("entry").itemId==1234,"definition transaction restores removed signal after failed save");
        n.signals().remove("entry");check(n.signals().definitions().isEmpty(),"unreserved signal can be removed");
    }
    private static void atomicJunctionAcquisition() {
        RailNetwork n=line(24);
        for(int hub:new int[]{6,12})for(int i=1;i<=4;i++) {
            String id="b"+hub+"_"+i;n.addNode(id,100+hub,100-i,0);n.connect(i==1?"p"+hub:"b"+hub+"_"+(i-1),id);
        }
        n.setJunctionAlignment("p6",65);n.setJunctionAlignment("p12",68);
        n.signals().add("entry","p4",6,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1),b=train(n,"B","b12_4","b12_3",1);
        n.setRoute(a.name,Arrays.asList("p23"));check(n.requestJunctionAlignment(b.name,"p12",68),"second junction prelocked by independent train");
        check(!n.signals().request(a.name,"entry").granted,"later junction refusal denies full route");
        check(n.getJunctionAlignment("p6")==65&&n.nodes.get("p6").getAlignmentOwner()==null,"failed multi-junction acquire rolls back earlier alignment and lock atomically");
        check(n.nodes.get("p12").getAlignmentOwner().equals(b.name),"rollback preserves preexisting other owner");
        long revision=n.getJunctionRevision();for(int i=0;i<20;i++)n.signals().request(a.name,"entry");
        check(n.getJunctionRevision()==revision,"denied acquisition preflights all junctions without flipping any points every tick");
    }
    private static void checkpointRollback() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);RailNetwork.Train a=train(n,"A","p1","p2",1);
        n.setRoute(a.name,Arrays.asList("n9"));RailNetwork.Checkpoint before=n.checkpoint(a.name);
        n.signals().request(a.name,"entry");n.start(a.name);n.tick(5);
        n.restore(before);
        check(n.signals().reservations().isEmpty()&&n.signals().aspect("entry")==SignalSystem.Aspect.RED,"native world rollback removes provisional global signal authority");
        check(n.getJunctionAlignment("p6")==68&&n.nodes.get("p6").getAlignmentOwner()==null,"native rollback restores original physical alignment and locks");
        n.signals().request(a.name,"entry");RailNetwork.Checkpoint held=n.checkpoint(a.name);String id=n.signals().status("entry").reservationId;
        n.stop(a.name);check(n.signals().reservations().isEmpty(),"fixture cancels authority after checkpoint");n.restore(held);
        check(n.signals().status("entry").reservationId.equals(id)&&n.signals().ownsAuthority(a.name,"entry"),"rollback restores preexisting global reservation and matching junction owner");
    }
    private static void restart() throws Exception {
        RailNetwork n=branch();n.signals().add("entry","p4",6,98765);RailNetwork.Train a=train(n,"A","p1","p2",1);
        n.setRoute(a.name,Arrays.asList("n9"));n.signals().request(a.name,"entry");
        Path directory=Files.createTempDirectory("wurmrail-signals-");Path file=directory.resolve("rail.properties");
        try {
            n.save(file);RailNetwork loaded=RailNetwork.load(file);
            SignalSystem.Signal s=loaded.signals().get("entry");
            check(s!=null&&s.rail.equals("p4")&&s.approachDirection==6&&s.itemId==98765,"signal definition persists absolute approach and native identity");
            check(loaded.signals().reservations().isEmpty()&&loaded.signals().aspect("entry")==SignalSystem.Aspect.RED,"restart resets all signal authorities fail red");
            check(loaded.nodes.get("p6").getAlignmentOwner()==null&&!loaded.trains.get(a.name).powered,"restart has no phantom junction locks or moving trains");
            check(loaded.getJunctionAlignment("p6")==65,"restart preserves physical pair independently from transient authority");
            check(loaded.signals().request(a.name,"entry").granted,"fresh owner can reacquire route after restart");
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}p.setProperty("format","3");p.remove("signals");
            checksum(file,p);RailNetwork old=RailNetwork.load(file);
            check(old.signals().definitions().isEmpty()&&old.getJunctionAlignment("p6")==65,"format 3 worlds migrate without changing junction state");
        } finally { try(java.util.stream.Stream<Path> paths=Files.walk(directory)){Iterator<Path> all=paths.sorted(Comparator.reverseOrder()).iterator();while(all.hasNext())Files.delete(all.next());} }
    }
    private static void unsignalledIntrusion() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1),b=train(n,"intruder","n7","n6",1);
        n.setRoute(a.name,Arrays.asList("p15"));check(n.signals().request(a.name,"entry").granted,"through-route reserved before unsignalled approach");
        n.start(b.name);n.tick(30);
        check(!n.signalOccupiesNode(b,"p6")&&b.poses.get(0).y<n.nodes.get("p6").worldY()-2.4,
                "unsignalled side entrant stops outside another train's reserved junction and its safety buffer");
        check(n.nodes.get("p6").getAlignmentOwner().equals(a.name),"unsignalled movement cannot take or realign owner lock");
    }
    private static void allCompassDirections() {
        int[][] d={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        for(int direction=0;direction<8;direction++) {
            RailNetwork n=new RailNetwork();
            for(int i=0;i<15;i++){n.addNode("r"+i,100+d[direction][0]*i,100+d[direction][1]*i,0);if(i>0)n.connect("r"+(i-1),"r"+i);}
            n.signals().add("entry","r4",(direction+4)%8,-10);n.signals().add("exit","r10",(direction+4)%8,-10);
            RailNetwork.Train a=train(n,"A","r1","r2",1),b=train(n,"B","r8","r9",1);
            n.start(a.name);n.tick(20);
            RailNetwork.Pose pose=a.poses.get(0);RailNetwork.Node boundary=n.nodes.get("r4");
            double distance=Math.hypot(pose.x-boundary.worldX(),pose.y-boundary.worldY());
            check(distance>2&&a.status.contains("RED"),"absolute compass "+direction+" stops before protected tile");
            n.removeTrain(b.name);n.tick(30);
            double progress=(a.poses.get(0).x-n.nodes.get("r0").worldX())*d[direction][0]+(a.poses.get(0).y-n.nodes.get("r0").worldY())*d[direction][1];
            check(progress>40,"absolute compass "+direction+" resumes after block clears");
        }
    }
    private static void diagonalReservedCrossing() {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<11;i++) {
            n.addNode("a"+i,100+i,100+i,0);if(i>0)n.connect("a"+(i-1),"a"+i);
            n.addNode("b"+i,107-i,98+i,0);if(i>0)n.connect("b"+(i-1),"b"+i);
        }
        n.signals().add("a_entry","a3",7,-10);n.signals().add("b_entry","b3",1,-10);
        RailNetwork.Train a=train(n,"A","a0","a1",1),b=train(n,"B","b0","b1",1);
        check(n.signals().request(a.name,"a_entry").granted,"diagonal protected route acquires its entry crossing resource");
        check(!n.signals().request(b.name,"b_entry").granted,"distinct-node diagonal routes crossing at entry cannot both reserve");
        n.start(b.name);n.tick(30);
        check(b.poses.get(0).x>n.nodes.get("b5").worldX(),"unsignalled/backside diagonal crossing cannot enter reserved crossing");
    }
    private static void occupiedStopAndReverse() {
        RailNetwork n=line(24);signals(n);RailNetwork.Train a=train(n,"long_manual","p3","p4",3);n.start(a.name);
        for(int i=0;i<300&&a.poses.get(0).x<n.nodes.get("p8").worldX();i++)n.tick(.05);
        check(n.signals().status("entry").ownerTrain.equals(a.name),"long train fixture occupies protected route");
        n.stop(a.name);check(n.signals().status("entry").ownerTrain.equals(a.name),"STOP inside block preserves route for standing cars");
        n.reverse(a.name);check(n.signals().status("entry").ownerTrain.equals(a.name),"reverse inside block preserves occupied route");
        n.start(a.name);n.tick(40);
        check(n.signals().status("entry").ownerTrain==null,"backing completely out releases occupied route after last car");
    }
    private static void waitingForReservation() {
        RailNetwork n=branch();n.signals().add("west","p4",6,-10);n.signals().add("north","n3",0,-10);
        RailNetwork.Train a=train(n,"A","p1","p2",1),b=train(n,"B","n7","n6",1);
        n.setRoute(a.name,Arrays.asList("p15"));n.setRoute(b.name,Arrays.asList("p12"));
        check(n.signals().request(a.name,"west").granted,"first automatic train holds unentered route");
        n.start(b.name);n.tick(8);float held=b.poses.get(0).y;
        check(b.status.contains("RED")&&b.powered,"second automatic train waits for conflicting reservation");
        n.stop(a.name);n.tick(15);
        check(b.poses.get(0).y>held+4||b.poses.get(0).x>n.nodes.get("p7").worldX(),"automatic train acquires released reservation and continues without manual restart");
    }
    private static void inconsistentAuthority() {
        RailNetwork n=branch();n.signals().add("entry","p4",6,-10);RailNetwork.Train a=train(n,"A","p1","p2",1);
        n.setRoute(a.name,Arrays.asList("n9"));RailNetwork.JunctionCheckpoint old=n.checkpointJunctions();n.signals().request(a.name,"entry");
        n.restoreJunctions(old);check(n.signals().aspect("entry")==SignalSystem.Aspect.RED,"mismatched global junction ownership makes aspect fail red");
        n.start(a.name);float before=a.poses.get(0).x;n.tick(5);
        check(a.poses.get(0).x==before&&a.status.contains("inconsistent"),"movement stops on inconsistent owned reservation even before approaching signal");
    }
    private static void scheduledStopsInsideBlock() {
        RailNetwork n=line(20);signals(n);n.setStation("p6",true,.4);n.setStation("p8",true,.4);
        RailNetwork.Train a=train(n,"through_stations","p1","p2",1);n.setRoute(a.name,Arrays.asList("p6","p8","p16","p0"),.4);
        SignalSystem.RequestResult request=n.signals().request(a.name,"entry");
        check(request.granted&&request.reservation.exitSignalId.equals("exit"),"one protected route can pass multiple planned stations before its exit signal");
        check(request.reservation.path.equals(Arrays.asList("p4","p5","p6","p7","p8","p9","p10")),"look-ahead advances through station itinerary without backtracking");
        check(a.routeIndex==0&&a.dwellRemaining==0,"route discovery never consumes live station arrivals");
        boolean first=false,second=false;n.start(a.name);
        for(int i=0;i<1600;i++) {
            n.tick(.05);
            if(a.routeIndex==1&&a.dwellRemaining>0)first=true;
            if(a.routeIndex==2&&a.dwellRemaining>0)second=true;
            if(a.poses.get(0).x>n.nodes.get("p11").worldX())break;
        }
        check(first&&second,"actual train stops and dwells at both intermediate stations in order");
        check(a.poses.get(0).x>n.nodes.get("p11").worldX(),"actual train resumes after intermediate stops and passes exit signal");
    }
    private static void scheduledTerminalShuttle() {
        RailNetwork n=line(18);n.signals().add("eastbound","p4",6,-10);n.signals().add("westbound","p13",2,-10);
        n.setStation("p0",true,.2);n.setStation("p17",true,.2);
        RailNetwork.Train a=train(n,"terminal_shuttle","p2","p3",1);n.setRoute(a.name,Arrays.asList("p17","p0"),.2);
        SignalSystem.RequestResult first=n.signals().request(a.name,"eastbound");
        check(first.granted&&first.reservation.exitSignalId==null&&first.reservation.nodes.contains("p17"),"planned terminal reversal safely ends reserved route at its destination");
        int east=0,west=0,previous=a.routeIndex;n.start(a.name);
        for(int i=0;i<4800&&Math.min(east,west)<3;i++) {
            n.tick(.05);if(a.routeIndex!=previous) { if(a.routeIndex==1)east++;else west++;previous=a.routeIndex; }
        }
        check(east>=3&&west>=3,"automatic terminal shuttle repeatedly visits both ends without permanent red");
        check(a.powered,"automatic shuttle keeps operating after multiple signalled reversals");
    }
    private static void scheduledCurvedShuttle() {
        RailNetwork n=branch();n.signals().add("west_inbound","p4",6,-10);n.signals().add("north_inbound","n3",0,-10);
        n.setStation("p0",true,.2);n.setStation("n9",true,.2);
        RailNetwork.Train a=train(n,"curved_shuttle","p3","p4",3);n.setRoute(a.name,Arrays.asList("n9","p0"),.2);
        n.start(a.name);int north=0,west=0,previous=a.routeIndex;boolean wrongPair=false;
        for(int i=0;i<6000&&Math.min(north,west)<3;i++) {
            n.tick(.05);if(n.nodes.get("p6").getAlignmentOwner()!=null&&n.getJunctionAlignment("p6")!=65)wrongPair=true;
            if(a.routeIndex!=previous) { if(a.routeIndex==1)north++;else west++;previous=a.routeIndex; }
        }
        check(north>=3&&west>=3,"three-car shuttle repeatedly backs out of protected terminal blocks through curved junction");
        check(!wrongPair&&n.getJunctionAlignment("p6")==65,"curved shuttle keeps identical absolute North-West alignment for both directions");
        check(a.powered,"long curved shuttle retains automatic service after repeated block clear/reacquire cycles");
    }
    private static RailNetwork circuit() {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<24;i++) {
            int x=i<6?100+i:i<12?106:i<18?118-i:100;
            int y=i<6?100:i<12?94+i:i<18?106:124-i;
            n.addNode("c"+i,x,y,0);if(i>0)n.connect("c"+(i-1),"c"+i);
        }
        n.connect("c23","c0");n.signals().add("circuit_entry","c4",6,-10);return n;
    }
    private static void singleSignalCircuit() {
        for(int cars:new int[]{1,3})for(boolean automatic:new boolean[]{false,true}) {
            RailNetwork n=circuit();RailNetwork.Train a=train(n,"circuit_train","c2","c3",cars);
            if(automatic)n.setRoute(a.name,Arrays.asList("c6","c12","c18","c0"),.1);
            RailNetwork.Train obstruction=train(n,"obstruction","c15","c16",1);
            SignalSystem.RequestResult occupied=n.signals().request(a.name,"circuit_entry");
            check(!occupied.granted&&occupied.reason.contains("obstruction"),"one-signal circuit remains red for another train anywhere in its complete block");
            n.removeTrain(obstruction.name);
            SignalSystem.RequestResult result=n.signals().request(a.name,"circuit_entry");
            check(result.granted,"a circuit may terminate its protected route at the same directional signal after one complete lap");
            check(result.reservation.nodes.size()==24&&result.reservation.path.size()==25,
                    "one-signal circuit reserves every distinct tile and retains the returning signal as its exit boundary");
            check(result.reservation.exitSignalId.equals("circuit_entry"),"complete circuit has an explicit protecting exit signal");
            fails(()->train(n,"intruder","c15","c16",1),"an acquired circuit cannot admit another train even far from the owner's current position");
            n.start(a.name);Set<String> visited=new HashSet<String>();
            for(int i=0;i<2200;i++) { n.tick(.05);visited.add(a.poses.get(0).railName); }
            check(visited.size()>=23,"manual and automatic one- and three-car circuits pass every side without permanent red");
            check(a.powered&&!a.status.contains("RED"),"single-block circuit continues operating after several laps");
            check(n.signals().ownsAuthority(a.name,"circuit_entry"),"circuit remains exclusively reserved while its own train still occupies the block");
            n.stop(a.name);check(n.signals().ownsAuthority(a.name,"circuit_entry"),"STOP retains occupied circuit authority instead of releasing track under standing cars");
            n.setRoute(a.name,Collections.<String>emptyList());n.reverse(a.name);n.start(a.name);n.tick(4);
            check(a.powered&&a.direction==-1&&!a.status.contains("RED"),"an occupied circuit can safely reverse while retaining its exclusive route");
        }
    }
    /** Signals directly on the points: the next lap needs another physical pair. */
    private static RailNetwork junctionCircuit() {
        RailNetwork n=new RailNetwork();
        for(int i=0;i<24;i++) {
            int x=i<6?100+i:i<12?106:i<18?118-i:100;
            int y=i<6?100:i<12?94+i:i<18?106:124-i;
            n.addNode("c"+i,x,y,0);if(i>0)n.connect("c"+(i-1),"c"+i);
        }
        n.connect("c23","c0");
        for(int i=1;i<=10;i++) { n.addNode("n"+i,100,100-i,0);n.connect(i==1?"c0":"n"+(i-1),"n"+i); }
        n.setJunctionAlignment("c0",17);
        n.signals().add("north","c0",0,-10);
        n.signals().add("east","c0",2,-10);
        n.signals().add("south","c0",4,-10);
        for(int i=0;i<4;i++) { n.addNode("lock"+i,140+i,140,0);if(i>0)n.connect("lock"+(i-1),"lock"+i); }
        return n;
    }
    private static void junctionSignalCircuit() {
        for(int cars:new int[]{1,2,3}) {
            RailNetwork n=junctionCircuit();RailNetwork.Train a=train(n,"EXPRESS","n4","n3",cars);
            n.setRoute(a.name,Arrays.asList("c18","c12","c6","n10"),.1);
            SignalSystem.RequestResult first=n.signals().request(a.name,"north");
            check(first.granted,"junction signal grants the first independently protected dispatch block");
            checkJunctionDispatchBoundary(n,first.reservation,17);
            String originalReservation=first.reservation.id;
            n.start(a.name);int arrivals=0,previous=a.routeIndex,mostReservations=0;
            boolean occupied=false,released=false,bodyChecked=false,conflictChecked=false,rollbackChecked=false,returned=false;
            for(int i=0;i<14000&&arrivals<12;i++) {
                n.tick(.05);
                mostReservations=Math.max(mostReservations,n.signals().reservations().size());
                if(a.routeIndex!=previous) { arrivals++;previous=a.routeIndex; }
                if(!released&&n.signalOccupiesNode(a,"c0")) {
                    occupied=true;
                    check(n.getJunctionAlignment("c0")==17&&reservation(n,originalReservation)!=null,
                            "the outbound short block keeps its original physical alignment while any car occupies the points");
                }
                if(occupied&&!bodyChecked&&n.signalOccupiesNode(a,"c0")) {
                    SignalSystem.RequestResult underBody=n.signals().request(a.name,"east");
                    check(!underBody.granted,"requesting a different junction route while any coupled car occupies the points stays red");
                    check(n.getJunctionAlignment("c0")==17&&originalReservation.equals(n.signals().status("north").reservationId),
                            "refused occupied-points handover preserves the existing alignment and signal authority");
                    RailNetwork.Checkpoint before=n.checkpoint(a.name);List<RailNetwork.Pose> poses=new ArrayList<RailNetwork.Pose>(a.poses);
                    n.stop(a.name);n.signals().cancelUnused(a.name);
                    check(reservation(n,originalReservation)!=null&&n.signals().ownsAuthority(a.name,"north"),
                            "STOP and unused-route cancellation retain authority under the complete standing consist");
                    n.setRoute(a.name,Collections.<String>emptyList());n.reverse(a.name);
                    check(samePoses(a,poses)&&reservation(n,originalReservation)!=null&&n.getJunctionAlignment("c0")==17,
                            "manual reversal keeps every vehicle pose and the occupied junction block intact");
                    check(!n.requestJunctionAlignment(a.name,"c0",5),"manual reversal cannot change points under an occupied short block");
                    fails(()->train(n,"points_intruder","c0","c1",1),"a second train cannot spawn on the occupied or reserved points");
                    n.restore(before);
                    check(samePoses(a,poses)&&a.powered&&a.automated&&n.signals().ownsAuthority(a.name,"north"),
                            "checkpoint restores the moving service, every pose and its occupied short-block authority");
                    bodyChecked=true;
                }
                if(occupied&&!released&&!n.signalOccupiesNode(a,"c0")) {
                    check(reservation(n,originalReservation)==null&&n.signals().aspect("north")==SignalSystem.Aspect.RED,
                            "junction authority releases only after the last car clears the protected points");
                    train(n,"distant_follower","c12","c13",1);n.removeTrain("distant_follower");
                    check(n.nodes.get("c0").getAlignmentOwner()==null,
                            "cleared dispatch blocks release the point lock and admit independent distant loop traffic");
                    released=true;
                }
                if(released&&!conflictChecked&&a.routeIndex==3&&!n.signalOccupiesNode(a,"c0")) {
                    RailNetwork.Train lock=train(n,"points_owner","lock1","lock2",1);
                    check(n.requestJunctionAlignment(lock.name,"c0",17),"a foreign train can acquire genuinely cleared points");
                    SignalSystem.RequestResult denied=n.signals().request(a.name,"east");
                    check(!denied.granted&&denied.reason.contains(lock.name)&&!n.signals().ownsAuthority(a.name,"east")
                                    &&n.getJunctionAlignment("c0")==17&&lock.name.equals(n.nodes.get("c0").getAlignmentOwner()),
                            "returning short-block request stays red without changing another train's point lock");
                    check(n.releaseJunctionAlignment(lock.name,"c0"),"the independent point owner releases its unused lock");n.removeTrain(lock.name);
                    RailNetwork.Checkpoint before=n.checkpoint(a.name);List<RailNetwork.Pose> poses=new ArrayList<RailNetwork.Pose>(a.poses);
                    SignalSystem.RequestResult successor=n.signals().request(a.name,"east");
                    check(successor.granted&&!originalReservation.equals(successor.reservation.id)&&successor.reservation.junctionAlignments.get("c0")==5,
                            "the returning lap acquires a fresh East-North short-block authority");
                    n.restore(before);
                    check(samePoses(a,poses)&&n.getJunctionAlignment("c0")==17&&n.nodes.get("c0").getAlignmentOwner()==null
                                    &&!n.signals().ownsAuthority(a.name,"east")&&reservation(n,originalReservation)==null,
                            "rollback restores the cleared point state and removes only the provisional return authority");
                    check(n.signals().request(a.name,"east").granted,"a rolled-back return request may safely acquire its own authority again");
                    conflictChecked=true;rollbackChecked=true;
                }
                if(conflictChecked&&a.poses.get(0).railName.startsWith("n"))returned=true;
                assertExclusiveAuthorities(n);
            }
            check(occupied&&bodyChecked&&released,"junction circuit checks occupied protection, manual recovery and complete rear clearance");
            check(conflictChecked&&rollbackChecked&&returned,"returning circuit safely resolves a foreign point lock and reaches its northern station");
            check(arrivals>=12,"one-, two- and three-car automatic junction circuits repeat twelve arrivals without manual reversal: cars="+cars+", arrivals="+arrivals+", status="+a.status);
            check(a.powered&&a.automated,"junction circuit retains powered automatic service after repeated alignment handovers");
            check(mostReservations<=cars+4,"short dispatch blocks keep active and clearing authorities bounded by the consist: "+mostReservations);
            check(reservation(n,originalReservation)==null,"later circuits never resurrect an obsolete cleared point authority");
        }
    }
    private static void checkJunctionDispatchBoundary(RailNetwork n,SignalSystem.Reservation r,int pair) {
        SignalSystem.Signal exit=r==null?null:n.signals().get(r.exitSignalId);
        check(r!=null&&r.path.equals(Arrays.asList("c0","c23"))&&r.nodes.equals(Collections.singletonList("c0")),
                "a Y-loop junction reserves its points up to the next independent dispatch boundary");
        check(exit!=null&&exit.id.startsWith("dispatch_")&&exit.rail.equals("c23")&&exit.approachDirection==n.signalSide("c23","c0"),
                "short junction block terminates at a real, correctly facing virtual signal");
        check(r.junctionAlignments.get("c0")==pair&&r.junctionLocks().get("c0")==pair,
                "short block records and physically locks the requested absolute junction pair");
    }
    private static boolean samePoses(RailNetwork.Train train,List<RailNetwork.Pose> poses) {
        if(train.poses.size()!=poses.size())return false;
        for(int i=0;i<poses.size();i++) {
            RailNetwork.Pose a=train.poses.get(i),b=poses.get(i);
            if(a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer||!Objects.equals(a.railName,b.railName))return false;
        }
        return true;
    }
    private static SignalSystem.Reservation reservation(RailNetwork n,String id) {
        for(SignalSystem.Reservation reservation:n.signals().reservations())if(reservation.id.equals(id))return reservation;return null;
    }
    private static void sameEntryJunctionCircuit() {
        for(boolean obstruct:new boolean[]{false,true}) {
            RailNetwork n=junctionCircuit();n.setJunctionAlignment("c0",20);
            RailNetwork.Train a=train(n,"same_entry","c3","c2",2);
            n.setRoute(a.name,Arrays.asList("c18","c12","c6","n10"),.1);
            SignalSystem.Reservation first=n.signals().request(a.name,"east").reservation;
            checkJunctionDispatchBoundary(n,first,20);
            boolean occupied=false,released=false,denied=false,renewed=false;int arrivals=0,previous=a.routeIndex,maxReservations=0;
            n.start(a.name);
            for(int i=0;i<14000&&arrivals<12;i++) {
                n.tick(.05);if(a.routeIndex!=previous) { arrivals++;previous=a.routeIndex; }
                maxReservations=Math.max(maxReservations,n.signals().reservations().size());
                if(!released&&n.signalOccupiesNode(a,"c0")) {
                    occupied=true;check(reservation(n,first.id)!=null&&n.getJunctionAlignment("c0")==20,
                            "same-entry departure keeps its East-South alignment until the last car clears");
                }
                if(occupied&&!released&&!n.signalOccupiesNode(a,"c0")) {
                    check(reservation(n,first.id)==null&&!n.signals().ownsAuthority(a.name,"east"),
                            "same-entry short authority and its displayed green retire after complete rear clearance");released=true;
                }
                if(obstruct&&released&&!denied&&a.routeIndex==3&&!n.signalOccupiesNode(a,"c0")) {
                    RailNetwork.Train lock=train(n,"points_owner","lock1","lock2",1);
                    check(n.requestJunctionAlignment(lock.name,"c0",20),"foreign owner can hold cleared same-entry points");
                    SignalSystem.RequestResult refused=n.signals().request(a.name,"east");SignalSystem.Status state=n.signals().status("east");
                    check(!refused.granted&&state.aspect==SignalSystem.Aspect.RED&&!n.signals().ownsAuthority(a.name,"east")
                                    &&state.reason.contains(lock.name)&&n.getJunctionAlignment("c0")==20
                                    &&lock.name.equals(n.nodes.get("c0").getAlignmentOwner())&&reservation(n,first.id)==null,
                            "a denied same-entry renewal preserves the foreign physical lock without reviving obsolete green authority");
                    check(n.releaseJunctionAlignment(lock.name,"c0"),"foreign same-entry obstruction can release its own unused points");n.removeTrain(lock.name);denied=true;
                }
                if(released&&n.signals().ownsAuthority(a.name,"east")&&n.getJunctionAlignment("c0")==5) {
                    SignalSystem.Reservation successor=n.signals().status("east").reservation;
                    check(!successor.id.equals(first.id)&&successor.junctionAlignments.get("c0")==5&&successor.junctionLocks().get("c0")==5,
                            "a later visit to the same signal uses a fresh authority for the new East-North pair");
                    if(!renewed) {
                        check(first.junctionAlignments.get("c0")==20&&first.junctionLocks().get("c0")==20,
                                "released route snapshots retain their historical pair after a new same-entry route is aligned");
                        boolean immutable=false;try { first.junctionLocks().clear(); }catch(UnsupportedOperationException expected) { immutable=true; }
                        check(immutable,"historical point-lock snapshots are immutable");renewed=true;
                    }
                }
                assertExclusiveAuthorities(n);
            }
            check(occupied&&released&&(!obstruct||denied)&&renewed&&arrivals>=12&&a.powered,
                    "same-entry short blocks renew repeatedly and recover after a foreign point obstruction clears: arrivals="+arrivals+", status="+a.status);
            check(maxReservations<=6,"same-entry short blocks keep active and clearing authorities bounded: "+maxReservations);
        }
    }
    private static void redSignalRecovery() {
        for(int cars:new int[]{1,3})for(boolean automatic:new boolean[]{false,true}) {
            RailNetwork n=line(28);n.signals().add("entry","p7",6,-10);n.signals().add("exit","p20",6,-10);
            RailNetwork.Train a=train(n,"red_waiter","p3","p4",cars),obstruction=train(n,"obstruction","p15","p16",1);
            if(automatic)n.setRoute(a.name,Arrays.asList("p27"));n.start(a.name);n.tick(20);
            check(a.speed==0&&a.status.contains("RED")&&a.powered,"recovery fixture is stationary and powered waiting at a genuinely occupied red signal");
            float held=a.poses.get(0).x;List<RailNetwork.Pose> positions=new ArrayList<RailNetwork.Pose>(a.poses);
            n.stop(a.name);n.setRoute(a.name,Collections.<String>emptyList());n.reverse(a.name);
            check(!a.powered&&!a.automated&&a.direction==-1,"operator can pause automatic service and reverse a red-waiting consist while stopped");
            for(int i=0;i<cars;i++)check(a.poses.get(i).x==positions.get(i).x&&a.poses.get(i).y==positions.get(i).y,"reverse never teleports any coupled car");
            n.start(a.name);n.tick(2);
            check(a.poses.get(0).x<held-1,"manual recovery backs away from the red signal using the existing planned path");
            check(!n.signals().ownsAuthority(a.name,"entry")&&n.signals().aspect("entry")==SignalSystem.Aspect.RED,
                    "retreat does not clear or borrow authority for the occupied route");
            check(obstruction.poses.get(0).x==n.nodes.get("p15").worldX(),"recovery leaves the obstructing train untouched");
        }
    }
    private static void checksum(Path file,Properties p)throws Exception {
        java.lang.reflect.Method checksum=RailNetwork.class.getDeclaredMethod("checksum",Properties.class);checksum.setAccessible(true);
        p.setProperty("checksum",(String)checksum.invoke(null,p));try(OutputStream out=Files.newOutputStream(file)){p.store(out,"signalling test fixture");}
    }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
    private static void fails(Runnable action,String message) { try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;} }
}
