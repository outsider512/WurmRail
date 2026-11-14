package org.wurmrail.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** A red Y entrance cannot obstruct vehicles sweeping around the loop curve. */
public final class LoopEntranceClearanceSelfTest {
    private static int assertions;
    private static long carId=99600000;
    private static double worst=Double.POSITIVE_INFINITY;
    private static String context="";
    private LoopEntranceClearanceSelfTest() { }

    public static void main(String[] args) throws Exception {
        for(int origin:new int[]{100,4096,16384})for(boolean diagonal:new boolean[]{false,true})
            for(boolean mirror:new boolean[]{false,true})for(int rotation=0;rotation<4;rotation++)
                for(int direction:new int[]{1,-1})for(boolean extended:new boolean[]{false,true})
                    run(origin,diagonal,mirror,rotation,direction,extended);
        System.out.println("LoopEntranceClearanceSelfTest: "+assertions+" assertions passed; curved Y approaches, every compass heading, mirrors, forward/reverse traction, appended-point geometry, earlier RED precedence, three-car bypass and large-world float poses; minimum junction sweep gap="+worst+" metres.");
    }
    private static void run(int origin,boolean diagonal,boolean mirror,int rotation,int direction,boolean extended) throws Exception {
        context="origin="+origin+" diagonal="+diagonal+" mirror="+mirror+" rotation="+rotation+" direction="+direction+" extended="+extended+": ";
        RailNetwork n=new RailNetwork();
        int[][] loop=diagonal?new int[][]{{0,0},{1,1},{1,2},{0,3},{-1,3},{-2,2},{-2,1},{-1,0}}
                :new int[][]{{0,0},{1,0},{2,1},{2,2},{1,3},{0,3},{-1,2},{-1,1}};
        List<String> ring=new ArrayList<String>();String previous=null;
        for(int index=0;index<loop.length;index++) {
            String name=index==0?"Y":"r"+index;point(n,name,loop[index][0],loop[index][1],origin,mirror,rotation);
            ring.add(name);if(previous!=null)n.connect(previous,name);previous=name;
        }
        n.connect(previous,"Y");
        int[][] outside=diagonal?new int[][]{{1,-1},{2,0},{2,1},{2,2},{2,3},{2,4},{2,5}}
                :new int[][]{{0,-1},{1,-1},{2,0},{3,1},{4,2},{5,3},{6,4}};
        previous="Y";
        for(int index=0;index<outside.length;index++) {
            String name="q"+index;point(n,name,outside[index][0],outside[index][1],origin,mirror,rotation);
            n.connect(previous,name);previous=name;
        }
        n.setJunctionAlignment("Y",(1<<n.signalSide("Y","r1"))|(1<<n.signalSide("Y","r"+(loop.length-1))));
        n.signals().add("Y_entrance","Y",n.signalSide("Y","q0"),-10);
        check(n.signals().traffic().loopEntrance("Y","q0"),"fixture is a recognized simple-loop entrance");

        Train queue=n.createTrain("Queued",1,Arrays.asList(carId++),"q4","q3");
        List<String> approach=new ArrayList<String>();for(int index=6;index>=0;index--)approach.add("q"+index);
        approach.add("Y");if(extended)approach.add("r"+(loop.length-1));
        if(direction<0)Collections.reverse(approach);
        seedPath(queue,approach);queue.direction=direction;
        Object queueGeometry=geometry(n,queue);double[] starts=(double[])field(queueGeometry,"starts"),ends=(double[])field(queueGeometry,"ends");
        int at=approach.indexOf("q0");

        // Earlier dispatch authority must remain the next gate even when a
        // later Y gate's expanded holding position lies further upstream.
        double earlier=(direction>0?starts[at]:ends[at])-direction*.45;
        set(queue,"locomotiveDistance",earlier-direction*.02);
        Object first=gate(n,queue,queueGeometry);SignalSystem.Signal firstSignal=(SignalSystem.Signal)field(first,"signal");
        check(firstSignal.rail.equals("q0"),"the prior dispatch RED cannot be shadowed by an expanded Y holding line: "+firstSignal.id);
        check(n.signals().status(firstSignal.id).aspect==SignalSystem.Aspect.RED,"the preceding boundary has no authority and must be treated as RED");

        // A real append first knows the junction when the leader reaches the
        // outside tile's incoming midpoint. Preserve that physical position;
        // an earlier holding line must clamp here rather than move it back.
        double append=direction>0?starts[at]:ends[at];set(queue,"locomotiveDistance",append);
        Object selected=gate(n,queue,queueGeometry);SignalSystem.Signal signal=(SignalSystem.Signal)field(selected,"signal");
        check(signal.id.equals("Y_entrance"),"the Y gate follows the already crossed outside dispatch boundary");
        double held=(Double)field(selected,"distance");
        check(direction*(held-append)>=-.000001,"discovering a more distant holding line never moves a saved or live train backwards");
        List<Pose> heldPoses=poses(n,queue,held);check(heldPoses.size()==1,"the leading queued body is physical engine geometry");

        // All cars use the same native geometry projection. Sweep each one
        // across the actual Y curve, including when its engine is already off
        // the points, rather than checking only the abstract junction centre.
        List<String> bypass=new ArrayList<String>();for(int index=4;index>=0;index--)bypass.add(ring.get(index));
        for(int index=ring.size()-1;index>=4;index--)bypass.add(ring.get(index));
        Train passing=n.createTrain("Passing",2,Arrays.asList(carId++,carId++,carId++),ring.get(4),ring.get(3));
        seedPath(passing,bypass);Object passingGeometry=geometry(n,passing);
        double[] sweepStarts=(double[])field(passingGeometry,"starts"),sweepEnds=(double[])field(passingGeometry,"ends");
        double begin=sweepStarts[bypass.indexOf("Y")],end=sweepEnds[bypass.indexOf("Y")];
        for(int car=0;car<3;car++)for(int sample=0;sample<=512;sample++) {
            double distance=begin+(end-begin)*sample/512+car*RailNetwork.CAR_SPACING;
            Pose moving=poses(n,passing,distance).get(car);
            for(Pose waiting:heldPoses) {
                double gap=Math.hypot(moving.x-waiting.x,moving.y-waiting.y);worst=Math.min(worst,gap);
                check(gap>=3.9999,"a queued entrance must leave four metres for every body sweeping across Y; gap="+gap+" queued="+waiting+" moving="+moving);
            }
        }
    }
    private static void point(RailNetwork n,String name,int x,int y,int origin,boolean mirror,int rotation) {
        if(mirror)x=-x;for(int turn=0;turn<rotation;turn++){int old=x;x=-y;y=old;}
        n.addNode(name,origin+x,origin+y,0);
    }
    @SuppressWarnings("unchecked")
    private static void seedPath(Train train,List<String> path) throws Exception {
        List<String> history=(List<String>)field(train,"path");history.clear();history.addAll(path);set(train,"initialTowards",null);
    }
    private static Object geometry(RailNetwork n,Train train) throws Exception {
        Method geometry=RailNetwork.class.getDeclaredMethod("geometry",Train.class);geometry.setAccessible(true);return geometry.invoke(n,train);
    }
    private static Object gate(RailNetwork n,Train train,Object geometry) throws Exception {
        Method next=RailNetwork.class.getDeclaredMethod("nextSignalGate",Train.class,geometry.getClass());next.setAccessible(true);
        Object gate=next.invoke(n,train,geometry);check(gate!=null,"the curved approach has a real next signal gate");return gate;
    }
    @SuppressWarnings("unchecked")
    private static List<Pose> poses(RailNetwork n,Train train,double at) throws Exception {
        Method poses=RailNetwork.class.getDeclaredMethod("poses",Train.class,double.class);poses.setAccessible(true);return (List<Pose>)poses.invoke(n,train,at);
    }
    private static Object field(Object object,String name) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static void check(boolean okay,String message){assertions++;if(!okay)throw new AssertionError(context+message);}
}
