package org.wurmrail.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Focused physical-orientation and rear-of-convoy recovery safety checks. */
public final class LoopRecoverySafetySelfTest {
    private static long car=98000000;
    private static int assertions;
    private LoopRecoverySafetySelfTest() { }

    public static void main(String[] args) throws Exception {
        for(boolean right:new boolean[]{false,true}) {
            junctionOrientation(right);
            tailStillInLoop(right);
            rearOnlyRecovery(right);
            mixedTractionStationPivot(right);
            resetFutureExit(right);
        }
        System.out.println("LoopRecoverySafetySelfTest: "+assertions
                +" assertions passed; occupied points, loop tails, backing entry forecasts, rear-only convoy recovery, mixed traction station turns and operator STOP.");
    }

    private static void junctionOrientation(boolean right) throws Exception {
        for(boolean clockwise:new boolean[]{false,true}) {
            RailNetwork n=graph();String junction=right?"R":"L",p=right?"r":"l",bridge=right?"c39":"c1";
            List<String> ring=ring(junction,p);String front=p+(clockwise?"0":"22");int sign=clockwise?1:-1;
            Train resident=make(n,"Resident",junction,front,1,Arrays.asList(p+"11",right?"l11":"r11"));
            Train incoming=make(n,"Incoming",right?"c35":"c5",right?"c36":"c4",1,resident.route);
            check(resident.poses.get(0).railName.equals(junction),"fixture places the locomotive on the physical junction");
            check(n.signalLoopOrientation(resident,ring)==sign,"junction occupant establishes its actual physical loop orientation");
            check(exits(n,incoming,junction,bridge).equals(Collections.singletonList(front)),
                    "an engine on the junction cannot be omitted from receiving circulation");
            List<Pose> unchanged=new ArrayList<Pose>(resident.poses);n.reverse(resident.name);
            samePoses(unchanged,resident.poses,"reversing traction cannot rotate a junction occupant");
            check(n.signalLoopOrientation(resident,ring)==sign,"physical loop orientation is independent of forward or reverse traction");
            check(exits(n,incoming,junction,bridge).equals(Collections.singletonList(front)),
                    "a backing station receiver forecasts its eventual forward circulation");
            n.reverse(incoming.name);
            String opposite=p+(clockwise?"22":"0");
            check(exits(n,incoming,junction,bridge).equals(Collections.singletonList(opposite)),
                    "a backing arrival selects the opposite entry arm to preserve the same eventual physical circulation");
        }
    }

    private static void tailStillInLoop(boolean right) throws Exception {
        for(boolean clockwise:new boolean[]{false,true}) {
            RailNetwork n=graph();String junction=right?"R":"L",p=right?"r":"l",bridge=right?"c39":"c1";
            String loopArm=p+(clockwise?"22":"0"),engine=right?"c39":"c1",towards=right?"c38":"c2";
            int mask=(1<<n.signalSide(junction,loopArm))|(1<<n.signalSide(junction,bridge));
            n.setJunctionAlignment(junction,mask);
            Train resident=make(n,"Leaving",engine,towards,3,Arrays.asList(right?"l11":"r11",p+"11"));
            Train incoming=make(n,"Incoming",right?"c32":"c8",right?"c33":"c7",1,Arrays.asList(p+"11",right?"l11":"r11"));
            check(!ring(junction,p).contains(n.signalLeadingRail(resident)),"fixture engine has already entered the connector");
            boolean tail=false;for(Pose pose:resident.poses)tail|=ring(junction,p).contains(pose.railName);
            check(tail,"fixture retains a real wagon on the loop or its receiving junction");
            check(n.signalLoopOrientation(resident,ring(junction,p))==(clockwise?1:-1),
                    "remaining loop history supplies orientation after the locomotive leaves the loop");
            check(exits(n,incoming,junction,bridge).equals(Collections.singletonList(p+(clockwise?"0":"22"))),
                    "a consist straddling the entrance retains the receiving loop's physical circulation");
        }
    }

    private static void rearOnlyRecovery(boolean right) throws Exception {
        RailNetwork n=graph();List<String> route=Arrays.asList(right?"l11":"r11",right?"r11":"l11");
        Train front=make(n,"Front",connector(right,28),connector(right,29),2,route);
        Train middle=make(n,"Middle",connector(right,20),connector(right,21),1,route);
        Train rear=make(n,"Rear",connector(right,12),connector(right,13),2,route);
        List<Train> convoy=Arrays.asList(front,middle,rear);
        for(Train train:convoy)n.resumeScheduled(train.name);
        TrafficControl traffic=n.signals().traffic();
        for(Train train:convoy) {
            TrafficControl.Lease lease=lease(traffic,train.name);
            check(lease!=null&&lease.entered&&lease.entryJunction.equals(right?"R":"L"),
                    "three physically entered trains reconstruct one same-direction convoy");
            n.stop(train.name);
        }
        for(Train train:convoy) {
            List<Pose> unchanged=new ArrayList<Pose>(train.poses);int direction=train.direction;
            failure(()->n.reverse(train.name),"ordinary reversal stays barred for every member of an entered convoy");
            samePoses(unchanged,train.poses,"a denied manual reversal cannot move or spin the consist");
            check(train.direction==direction&&!train.powered,"a denied reversal preserves traction and operator STOP");
        }
        failure(()->traffic.requireRecoveryReversalSafe(front.name),"recovery cannot reverse the front into its followers");
        failure(()->traffic.requireRecoveryReversalSafe(middle.name),"recovery cannot reverse a middle member into the tail");
        traffic.requireRecoveryReversalSafe(rear.name);check(true,"only the actual rearmost consist can pass the recovery reversal guard");
        List<Pose> stopped=new ArrayList<Pose>(rear.poses);
        n.resumeScheduled(front.name);n.resumeScheduled(middle.name);
        n.resolveTrafficJams();
        for(int tick=0;tick<300;tick++) {
            n.tick(.1);
            check(!rear.powered&&rear.direction==1,"automatic recovery never resumes a manually stopped rear train");
            samePoses(stopped,rear.poses,"operator STOP holds every rear vehicle in place during fleet movement and recovery");
        }
    }

    private static void mixedTractionStationPivot(boolean right) throws Exception {
        for(boolean pivot:new boolean[]{false,true})for(boolean backingFirst:new boolean[]{false,true}) {
            RailNetwork n=graph();String p=right?"r":"l",junction=right?"R":"L",bridge=right?"c39":"c1";
            List<String> route=pivot?Arrays.asList(right?"l11":"r11",p+"11"):Collections.<String>emptyList();
            Train forward=make(n,"Forward",p+"0",junction,1,route);
            Train backing=make(n,"Backing",p+"5",p+"6",1,route);n.reverse(backing.name);
            if(pivot) { n.resumeScheduled(forward.name);n.resumeScheduled(backing.name); }
            else { n.start(forward.name);n.start(backing.name); }
            TrafficControl traffic=n.signals().traffic();Train first=backingFirst?backing:forward,second=backingFirst?forward:backing;
            TrafficControl.Admission admission=traffic.prepare(first,Arrays.asList(junction,bridge));
            check(admission.granted&&traffic.commit(admission),"the first physical direction owner can acquire an empty receiving loop");
            admission=traffic.prepare(second,Arrays.asList(junction,bridge));
            if(pivot) {
                check(!admission.granted&&admission.reason.contains("backing"),
                        "opposed future station turns cannot share a convoy even when absolute entry sides match");
                check(lease(traffic,second.name)==null&&lease(traffic,first.name)!=null,
                        "denied mixed station-pivot preflight retains the first owner and grants no conflicting lease");
            } else {
                check(admission.granted&&traffic.commit(admission),
                        "manual forward and backing services without a station pivot may share the same absolute corridor entry");
                check(traffic.compatible(first.name,second.name),"traction alone cannot prohibit a physically same-direction manual convoy");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void resetFutureExit(boolean right) throws Exception {
        for(boolean backing:new boolean[]{false,true}) {
            RailNetwork n=graph();String junction=right?"R":"L",p=right?"r":"l",bridge=right?"c39":"c1";
            n.setJunctionAlignment(junction,(1<<n.signalSide(junction,bridge))|(1<<n.signalSide(junction,p+"0")));
            String at=right?"c35":"c5",facing=right?(backing?"c34":"c36"):(backing?"c6":"c4");
            Train train=make(n,"FutureReplan",at,facing,2,Arrays.asList(p+"11",right?"l11":"r11"));
            if(backing)n.reverse(train.name);
            List<Pose> original=new ArrayList<Pose>(train.poses);
            if(backing) {
                Method extend=RailNetwork.class.getDeclaredMethod("extendBehind",Train.class,double.class,String.class);
                extend.setAccessible(true);extend.invoke(n,train,35d,null);
            } else {
                java.lang.reflect.Field field=Train.class.getDeclaredField("path");field.setAccessible(true);
                List<String> path=(List<String>)field.get(train);
                for(int offset=4;offset>=1;offset--)path.add(right?"c"+(40-offset):"c"+offset);
                path.add(junction);path.add(p+"0");path.add(p+"1");
            }
            Method refresh=RailNetwork.class.getDeclaredMethod("refresh",Train.class);refresh.setAccessible(true);refresh.invoke(n,train);
            samePoses(original,train.poses,"fixture adds only future track history and preserves the actual occupied geometry");
            check(!n.signalOccupiesNode(train,junction),"receiving points remain physically clear before a forecast reset");
            java.lang.reflect.Field field=Train.class.getDeclaredField("path");field.setAccessible(true);
            List<String> path=(List<String>)field.get(train);int index=path.indexOf(junction);
            check(index>=0&&path.get(index+train.direction).equals(p+"0"),"fixture contains a stale future receiving bend in the active traction direction");
            n.resetUnenteredExit(train.name,junction,p+"22");refresh.invoke(n,train);
            samePoses(original,train.poses,"resetting a future bend preserves every physical vehicle's exact position and orientation");
            path=(List<String>)field.get(train);index=path.indexOf(junction);int next=index+train.direction;
            check(index>=0&&(next<0||next>=path.size()),
                    "forecast reset discards the stale receiving arm while retaining the junction and occupied approach history");
        }
    }

    private static String connector(boolean right,int offset) {return "c"+(right?40-offset:offset);}
    private static List<String> ring(String junction,String prefix) {
        List<String> result=new ArrayList<String>();result.add(junction);
        for(int i=0;i<=22;i++)result.add(prefix+i);return result;
    }
    @SuppressWarnings("unchecked")
    private static List<String> exits(RailNetwork n,Train train,String junction,String previous) throws Exception {
        Method method=TrafficControl.class.getDeclaredMethod("receivingExits",Train.class,String.class,String.class);
        method.setAccessible(true);return (List<String>)method.invoke(n.signals().traffic(),train,junction,previous);
    }
    private static RailNetwork graph() throws Exception {
        Method method=TrafficControlSelfTest.class.getDeclaredMethod("dumbbell",int.class);method.setAccessible(true);
        return (RailNetwork)method.invoke(null,40);
    }
    private static Train make(RailNetwork n,String name,String at,String facing,int count,List<String> route) {
        List<Long> ids=new ArrayList<Long>();for(int i=0;i<count;i++)ids.add(car++);
        Train train=n.createTrain(name,1,ids,at,facing);n.setRoute(name,route,.5);return train;
    }
    private static TrafficControl.Lease lease(TrafficControl traffic,String name) {
        for(TrafficControl.Lease lease:traffic.leases())if(lease.trainName.equals(name))return lease;return null;
    }
    private interface Action {void run();}
    private static void failure(Action action,String message) {
        try {action.run();}catch(IllegalArgumentException expected){check(true,message);return;}
        check(false,message);
    }
    private static void samePoses(List<Pose> before,List<Pose> after,String message) {
        check(before.size()==after.size(),message);
        for(int i=0;i<before.size();i++) {
            Pose a=before.get(i),b=after.get(i);
            check(a.carId==b.carId&&a.x==b.x&&a.y==b.y&&a.rotation==b.rotation&&a.layer==b.layer,message);
        }
    }
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
