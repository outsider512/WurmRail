package org.wurmrail.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** FIFO station-branch turns survive safe holds without replacing physical block protection. */
public final class YTurnbackQueueSelfTest {
    private static int assertions;
    private static long carId=98500000;
    private YTurnbackQueueSelfTest() { }

    public static void main(String[] args) throws Exception {
        holdingAndActualAdmission();
        laterTrainAheadOfHeadClears();
        occupiedYardWins();
        pruneInactiveServices();
        checkpointCopies();
        topologyChanges();
        System.out.println("YTurnbackQueueSelfTest: "+assertions+" assertions passed; FIFO holding, actual signal admission, physical ownership, pruning, independent checkpoints and topology changes.");
    }
    private static RailNetwork graph() throws Exception {
        Method graph=YLoopRecoverySelfTest.class.getDeclaredMethod("graph",boolean.class);graph.setAccessible(true);
        return (RailNetwork)graph.invoke(null,false);
    }
    private static Train service(RailNetwork n,String name,String at,String facing,boolean yardDestination) {
        Train train=n.createTrain(name,1,Collections.singletonList(carId++),at,facing);
        n.setRoute(name,yardDestination?Arrays.asList("n5","r20"):Arrays.asList("r20","n5"),.1);
        n.start(name);return train;
    }
    private static Train[] waiters(RailNetwork n) {
        return new Train[]{service(n,"First","r10","r9",true),service(n,"Second","r20","r19",true),
                service(n,"Third","r30","r29",true)};
    }
    private static void register(TrafficControl traffic,Train train) { traffic.preferredExit(train.name,"Y","r1"); }
    private static TrafficControl.Admission requestYard(TrafficControl traffic,Train train) {
        return traffic.prepare(train,Arrays.asList("Y","s1"),"r1");
    }
    private static void holdingAndActualAdmission() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();Train[] trains=waiters(n);
        for(Train train:trains)register(traffic,train);
        check(queue(traffic).equals(Arrays.asList("First","Second","Third")),"branch requests enter the queue in their actual request order");
        for(int lap=0;lap<4;lap++) {
            // Revisit the points from either connected loop arm. Replanning a
            // different lap must not move an old waiter behind a new arrival.
            check("s1".equals(traffic.preferredExit("Second","Y","r1"))
                            &&"s1".equals(traffic.preferredExit("Second","Y","r46")),
                    "a later waiter retains its station exit and waits from either safe approach");
            register(traffic,trains[2]);register(traffic,trains[0]);
            check(queue(traffic).equals(Arrays.asList("First","Second","Third")),"extra circuits and repeated route forecasts preserve the first waiter's place");
        }
        TrafficControl.Admission denied=requestYard(traffic,trains[1]);
        check(!denied.granted&&denied.reason.contains("First"),"a later service cannot reserve an empty branch ahead of the earlier waiter");
        check(requestYard(traffic,trains[0]).granted,"the earliest eligible waiter may reserve the empty branch");
        SignalSystem.RequestResult hold=n.signals().request("Second","Y_r1");
        check(!hold.granted&&hold.reason.contains("First")&&!n.signals().ownsAuthority("Second","Y_r1"),
                "an actual later signal request stays RED for the earlier station turn instead of granting an unnecessary circuit");
        List<String> before=queue(traffic);n.signals().cancelUnused("Second");
        check(queue(traffic).equals(before),"cancelling an unused local signal block does not discard a held branch turn");
        SignalSystem.RequestResult enter=n.signals().request("First","Y_r1");
        check(enter.granted&&enter.reservation.path.equals(Arrays.asList("Y","s1"))
                        &&"First".equals(n.nodes.get("Y").getAlignmentOwner()),
                "the first waiter acquires its own actual junction signal and physical station-branch alignment");
        SignalSystem.RequestResult conflict=n.signals().request("Second","Y_r1");
        check(!conflict.granted&&!n.signals().ownsAuthority("Second","Y_r1")
                        &&n.signals().ownsAuthority("First","Y_r1"),
                "queue permission never lets a later train borrow or override an acquired physical block");
    }
    private static void occupiedYardWins() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();Train[] trains=waiters(n);
        register(traffic,trains[0]);register(traffic,trains[1]);
        Train owner=service(n,"ActualOwner","s4","s3",false);List<Pose> poses=new ArrayList<Pose>(owner.poses);
        n.stop(owner.name);
        TrafficControl.Admission denied=requestYard(traffic,trains[0]);
        check(!denied.granted&&denied.reason.contains(owner.name),"a stopped real branch occupant takes precedence over an earlier queue place");
        SignalSystem.RequestResult hold=n.signals().request("First","Y_r1");
        check(!hold.granted&&hold.reason.contains(owner.name)&&!n.signals().ownsAuthority("First","Y_r1"),
                "the first waiter stays RED while the actual branch owner can drain through the other clear loop arm");
        n.signals().cancelUnused("First");
        TrafficControl.Admission drain=traffic.prepare(owner,Arrays.asList("s1","Y","r46"),"s2");
        check(drain.granted,"the existing branch occupant may clear onto a compatible loop despite queued inward services");
        check(!owner.powered&&samePoses(owner,poses),"admission forecasting never starts or moves a stopped physical branch owner");
        n.removeTrain(owner.name);
        check(requestYard(traffic,trains[0]).granted&&!requestYard(traffic,trains[1]).granted,
                "the original FIFO head receives the branch after its physical owner is gone");
    }
    private static void laterTrainAheadOfHeadClears() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();
        Train first=service(n,"EarlierFar","r10","r9",true),later=service(n,"LaterAhead","r4","r3",true);
        register(traffic,first);register(traffic,later);
        check(queue(traffic).equals(Arrays.asList(first.name,later.name)),"the earlier request keeps its FIFO turn even when another train is physically ahead");
        String firstExit=traffic.preferredExit(first.name,"Y","r1");
        check(firstExit==null||"s1".equals(firstExit),"the older head retains its natural requested station branch");
        check("r46".equals(traffic.preferredExit(later.name,"Y","r1")),"a later train ahead of the older FIFO head must clear the shared approach instead of blocking its turn");
        SignalSystem.RequestResult clear=n.signals().request(later.name,"Y_r1");
        check(clear.granted&&clear.reservation.path.equals(Arrays.asList("Y","r46")),"the genuinely required FIFO clearance circuit obtains its own protected signal authority");
        check(queue(traffic).equals(Arrays.asList(first.name,later.name)),"clearing the older train's approach does not discard either station turn");
    }
    private static void pruneInactiveServices() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();Train[] trains=waiters(n);
        for(Train train:trains)register(traffic,train);
        List<Pose> held=new ArrayList<Pose>(trains[0].poses);n.stop("First");
        check(requestYard(traffic,trains[1]).granted&&queue(traffic).equals(Arrays.asList("Second","Third")),
                "a new actual branch request prunes a stopped head and promotes the next running waiter");
        check(!trains[0].powered&&samePoses(trains[0],held),"pruning respects an operator STOP and every stopped vehicle pose");
        n.stop("Second");n.setRoute("Second",Collections.singletonList("r20"),.1);n.start("Second");
        check(requestYard(traffic,trains[2]).granted&&queue(traffic).equals(Collections.singletonList("Third")),
                "changing a queued route to a loop stop releases its obsolete station-branch turn");
        n.stop("Third");n.removeTrain("Third");
        Train next=service(n,"NewService","r40","r39",true);
        check(requestYard(traffic,next).granted&&queue(traffic).equals(Collections.singletonList(next.name)),
                "removed services cannot leave a ghost queue head blocking a new service");
        n.stop(next.name);n.setRoute(next.name,Collections.<String>emptyList());n.start(next.name);
        n.stop("Second");n.setRoute("Second",Arrays.asList("n5","r20"),.1);n.start("Second");
        check(requestYard(traffic,trains[1]).granted&&queue(traffic).equals(Collections.singletonList("Second")),
                "cancelling a schedule also releases its branch turn without discarding other eligible services");
    }
    private static void checkpointCopies() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();Train[] trains=waiters(n);
        register(traffic,trains[0]);register(traffic,trains[1]);TrafficControl.Checkpoint saved=traffic.checkpoint();
        List<String> original=Arrays.asList("First","Second");
        check(queue(saved).equals(original)&&queueSets(saved)!=queueSets(traffic),"a traffic checkpoint owns independent FIFO queue containers");
        register(traffic,trains[2]);
        check(queue(traffic).equals(Arrays.asList("First","Second","Third"))&&queue(saved).equals(original),
                "live enqueues cannot mutate the saved FIFO order");
        traffic.restore(saved);
        check(queue(traffic).equals(original)&&queueSets(saved).get("Y")!=queueSets(traffic).get("Y"),
                "restore copies the queue set instead of exposing the reusable checkpoint to live writes");
        n.stop("First");n.setRoute("First",Collections.singletonList("r20"),.1);n.start("First");
        check(requestYard(traffic,trains[1]).granted&&queue(traffic).equals(Collections.singletonList("Second"))&&queue(saved).equals(original),
                "post-restore pruning changes only live admission, leaving the checkpoint reusable");
        n.stop("First");n.setRoute("First",Arrays.asList("n5","r20"),.1);n.start("First");traffic.restore(saved);
        TrafficControl.Admission denied=requestYard(traffic,trains[1]);
        check(!denied.granted&&denied.reason.contains("First")&&queue(traffic).equals(original),
                "restoring the same checkpoint again reinstates the first service's actual admission priority");
        SignalSystem.RequestResult later=n.signals().request("Second","Y_r1");
        check(!later.granted&&later.reason.contains("First")&&!n.signals().ownsAuthority("Second","Y_r1"),
                "checkpoint-restored FIFO priority keeps a later safe approach RED instead of sending it around again");
    }
    private static void topologyChanges() throws Exception {
        RailNetwork n=graph();TrafficControl traffic=n.signals().traffic();Train[] trains=waiters(n);
        register(traffic,trains[0]);register(traffic,trains[1]);
        n.addNode("unrelated",200,200,0);
        check(!requestYard(traffic,trains[1]).granted&&queue(traffic).equals(Arrays.asList("First","Second")),
                "an unrelated topology rebuild preserves valid existing branch turns");
        n.signals().remove("Y_s1");n.disconnect("Y","s1");
        check(!traffic.activeAt("Y")&&queue(traffic).isEmpty(),"removing the loop's station-branch connection prunes its now-invalid queue");
        check(traffic.prepare(trains[1],Arrays.asList("Y","r46"),"r1").granted,
                "an obsolete branch queue cannot block the remaining valid through-loop route");
        n.connect("Y","s1");
        check(requestYard(traffic,trains[1]).granted&&queue(traffic).equals(Collections.singletonList("Second")),
                "a newly reconnected station branch starts with a fresh valid request order");
        TrafficControl.Admission denied=requestYard(traffic,trains[0]);
        check(!denied.granted&&denied.reason.contains("Second")&&queue(traffic).equals(Arrays.asList("Second","First")),
                "topology edits cannot resurrect an invalid old queue priority over a newer valid request");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,LinkedHashSet<String>> queueSets(Object owner) throws Exception {
        Field field=owner.getClass().getDeclaredField("turnbackQueues");field.setAccessible(true);
        return (Map<String,LinkedHashSet<String>>)field.get(owner);
    }
    private static List<String> queue(Object owner) throws Exception {
        LinkedHashSet<String> value=queueSets(owner).get("Y");
        return value==null?Collections.<String>emptyList():new ArrayList<String>(value);
    }
    private static boolean samePoses(Train train,List<Pose> before) {
        if(train.poses.size()!=before.size())return false;
        for(int i=0;i<before.size();i++) { Pose a=train.poses.get(i),b=before.get(i);
            if(a.carId!=b.carId||a.x!=b.x||a.y!=b.y||a.rotation!=b.rotation||a.layer!=b.layer)return false; }
        return true;
    }
    private static void check(boolean okay,String message) { assertions++;if(!okay)throw new AssertionError(message); }
}
