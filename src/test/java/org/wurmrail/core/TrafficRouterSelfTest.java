package org.wurmrail.core;

import java.util.*;

/** Topology and contention cases for direction-preserving automatic path selection. */
public final class TrafficRouterSelfTest {
    private static int assertions;
    private static long car=784000;
    public static void main(String[] args) {
        straightDirection();dumbbellDirection();clearLoopBranch();reservationAvoidance();engineLeadingDeparture();
        System.out.println("TrafficRouterSelfTest: "+assertions+" assertions passed.");
    }
    private static RailNetwork line(int length) {
        RailNetwork n=new RailNetwork();
        for(int x=0;x<length;x++) { n.addNode("p"+x,100+x,100,0);if(x>0)n.connect("p"+(x-1),"p"+x); }
        return n;
    }
    private static RailNetwork.Train train(RailNetwork n,String name,String start,String towards) {
        return n.createTrain(name,1,Arrays.asList(car++),start,towards);
    }
    private static void straightDirection() {
        RailNetwork n=line(12);RailNetwork.Train t=train(n,"A","p3","p4");
        check(TrafficRouter.path(n,t,"p3","p2","p8",false).equals(Arrays.asList("p3","p4","p5","p6","p7","p8")),"forward straight route");
        check(TrafficRouter.path(n,t,"p3","p2","p1",false).isEmpty(),"target behind cannot cause a U-turn on a straight tile");
        check(TrafficRouter.path(n,t,"p3",null,"p1",false).equals(Arrays.asList("p3","p2","p1")),"explicit stationary departure may select its own side");
        check(TrafficRouter.path(n,t,"p3","p2","p3",false).equals(Arrays.asList("p3")),"arrival completes before a subsequent departure is selected");
        check(TrafficRouter.path(n,t,"p3","p7","p8",false).isEmpty(),"disconnected incoming side cannot manufacture a heading");
        check(TrafficRouter.path(n,t,"p3","p2","missing",false).isEmpty(),"missing destination has no path");
        check(!t.powered&&!t.automated&&t.route.isEmpty(),"planning leaves live controls unchanged");
    }
    private static RailNetwork dumbbell() {
        RailNetwork n=new RailNetwork();
        n.addNode("L",108,100,0);n.addNode("R",118,100,0);
        loop(n,"L","l",108,-1);loop(n,"R","r",118,1);
        String previous="L";
        for(int x=109;x<118;x++) { String name="c"+(x-108);n.addNode(name,x,100,0);n.connect(previous,name);previous=name; }
        n.connect(previous,"R");
        n.setJunctionAlignment("L",(1<<0)|(1<<4));n.setJunctionAlignment("R",(1<<0)|(1<<4));
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
    private static void dumbbellDirection() {
        RailNetwork n=dumbbell();RailNetwork.Train t=train(n,"A","c7","c6");
        List<String> path=TrafficRouter.path(n,t,"c7","c8","r11",false);
        check(path.size()>30,"destination behind is reached by travelling around an actual circle");
        check(path.get(1).equals("c6"),"connector train continues its original physical heading");
        check(path.contains("l11")&&Collections.frequency(path,"L")==2,"left loop supplies the physical change of heading");
        check(path.get(path.size()-1).equals("r11"),"oriented route reaches the requested opposite circle");
        noUturn(path,"c8");
        for(int i=0;i<20;i++)check(path.equals(TrafficRouter.path(n,t,"c7","c8","r11",false)),"equal-distance paths are deterministic");
        try { path.add("c8");throw new AssertionError("mutable path"); }
        catch(UnsupportedOperationException expected) { assertions++; }
    }
    private static void clearLoopBranch() {
        RailNetwork n=dumbbell();RailNetwork.Train a=train(n,"A","c6","c7");
        List<String> unoccupied=TrafficRouter.path(n,a,"R","c9","r11",true);
        check(unoccupied.get(1).equals("r0"),"empty equal-distance circle uses a stable branch");
        RailNetwork.Train b=train(n,"B","r1","r0");
        List<String> avoiding=TrafficRouter.path(n,a,"R","c9","r11",true);
        check(!avoiding.isEmpty()&&avoiding.get(1).equals("r22"),"receiving junction chooses the other circle branch when one is occupied");
        check(!avoiding.contains("r1"),"selected escape does not enter the other locomotive's tile");
        check(TrafficRouter.path(n,a,"R","c9","r11",false).equals(unoccupied),"topology-only fallback remains available for a wait reason");
        check(b.poses.get(0).railName.equals("r1"),"route search cannot move the obstructing train");
        noUturn(avoiding,"c9");
    }
    private static void reservationAvoidance() {
        RailNetwork n=line(12);RailNetwork.Train a=train(n,"A","p0","p1"),b=train(n,"B","p10","p9");
        n.signals().add("entry","p7",2,-10);n.signals().add("exit","p3",2,-10);
        SignalSystem.RequestResult granted=n.signals().request(b.name,"entry");
        check(granted.granted,"foreign block reservation exists before routing");
        check(!TrafficRouter.path(n,a,"p2","p1","p8",false).isEmpty(),"topology alone can reach through the held block");
        check(TrafficRouter.path(n,a,"p2","p1","p8",true).isEmpty(),"foreign reserved resources are excluded even while physically empty");
        check(n.signals().ownsAuthority(b.name,"entry"),"planning cannot cancel or borrow another train's authority");
    }
    private static void noUturn(List<String> path,String previous) {
        for(int i=1;i<path.size();i++) {
            check(!path.get(i).equals(previous),"no consecutive opposite traversal at "+path.get(i-1));
            previous=path.get(i-1);
        }
    }
    private static RailNetwork rectangle(int width,int height) {
        RailNetwork n=new RailNetwork();int index=0;
        for(int x=0;x<width;x++)n.addNode("q"+(index++),200+x,200,0);
        for(int y=0;y<height;y++)n.addNode("q"+(index++),200+width,200+y,0);
        for(int x=width;x>0;x--)n.addNode("q"+(index++),200+x,200+height,0);
        for(int y=height;y>0;y--)n.addNode("q"+(index++),200,200+y,0);
        for(int i=0;i<index;i++)n.connect("q"+i,"q"+((i+1)%index));return n;
    }
    private static void engineLeadingDeparture() {
        RailNetwork shortLoop=rectangle(4,2);RailNetwork.Train t=train(shortLoop,"Preferred","q1","q2");
        List<String> forward=TrafficRouter.preferredPath(shortLoop,t,"q1","q0","q0",false,true);
        check(forward.get(1).equals("q2")&&TrafficRouter.distance(shortLoop,forward)==44,"physical engine-leading circuit wins over a backing shortcut");
        t.direction=-1;
        check(TrafficRouter.preferredPath(shortLoop,t,"q1","q2","q0",false,true).get(1).equals("q2"),"saved backing train prefers restored engine-leading departure over its current shorter backing route");
        check(TrafficRouter.preferredPath(shortLoop,t,"q1","q2","q0",false,false).get(1).equals("q0"),"moving backing train remains directional until a safe stationary departure");
        t.direction=1;
        RailNetwork longLoop=rectangle(12,6);t=train(longLoop,"Preferred","q1","q2");
        List<String> reverse=TrafficRouter.preferredPath(longLoop,t,"q1","q0","q0",false,true);
        check(reverse.get(1).equals("q2")&&TrafficRouter.distance(longLoop,reverse)>100,"long physical turning loop wins even when backing would take only one track tile");
        check(TrafficRouter.preferredPath(longLoop,t,"q1","q0","q0",false,false).get(1).equals("q2"),"ordinary intermediate route tracing never changes traction");
        RailNetwork largeLoop=rectangle(60,40);t=train(largeLoop,"Preferred","q1","q2");
        check(TrafficRouter.preferredPath(largeLoop,t,"q1","q0","q102",false,true).get(1).equals("q2"),"arbitrarily large engine-leading route keeps its physical departure");
        RailNetwork deadEnd=line(12);t=train(deadEnd,"Preferred","p3","p4");
        check(TrafficRouter.preferredPath(deadEnd,t,"p3","p2","p1",false,true).equals(Arrays.asList("p3","p2","p1")),"necessary backing remains available when no oriented forward route exists");
        t=train(shortLoop,"Blocking","q4","q5");RailNetwork.Train owner=shortLoop.trains.get("Preferred");
        check(TrafficRouter.preferredPath(shortLoop,owner,"q1","q0","q0",true,true).isEmpty(),"temporary forward obstruction causes a wait instead of choosing a reverse shortcut");
        check(TrafficRouter.preferredPath(shortLoop,owner,"q1","q0","q0",false,true).get(1).equals("q2"),"topological fallback preserves intended travel direction while another train blocks it");
        check(TrafficRouter.distance(shortLoop,Arrays.asList("q1","q7"))==Double.POSITIVE_INFINITY,"disconnected path has no finite movement cost");
        check(!t.powered&&!owner.powered,"engine-leading route planning changes neither train control state");
    }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
