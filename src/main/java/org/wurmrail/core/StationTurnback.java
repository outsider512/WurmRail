package org.wurmrail.core;

import java.util.*;

/** Pure planning for a station departure which backs into a nearby clearing track.
 * The movement controller owns the plan and signal authority remains separate. */
public final class StationTurnback {
    private static final double EPS=0.000001;
    private static final double CLEARANCE=12.0;
    private StationTurnback() { }

    /** Immutable maneuver state. The waypoint is a movement stop, never a station visit. */
    public static final class Plan {
        public final String station,junction,target,waypoint;
        public final List<String> backingPath,departurePath;
        public final Set<String> resources;
        public final boolean departing;

        public Plan(RailNetwork network,String station,String junction,String target,
                    List<String> backingPath,List<String> departurePath,boolean departing) {
            this(station,junction,target,immutable(backingPath),immutable(departurePath),departing,
                    envelope(network,junction,backingPath,departurePath));
        }
        private Plan(String station,String junction,String target,List<String> backingPath,
                     List<String> departurePath,boolean departing,Set<String> resources) {
            this.station=Objects.requireNonNull(station,"Turnback station");
            this.junction=Objects.requireNonNull(junction,"Turnback junction");
            this.target=Objects.requireNonNull(target,"Turnback destination");
            if(backingPath.isEmpty()||departurePath.isEmpty())throw new IllegalArgumentException("A turnback needs both movement legs.");
            this.backingPath=backingPath;this.departurePath=departurePath;
            waypoint=backingPath.get(backingPath.size()-1);this.departing=departing;this.resources=resources;
        }
        public Plan withDeparting() {
            return departing?this:new Plan(station,junction,target,backingPath,departurePath,true,resources);
        }
    }

    /** Prefer the nearest geometrically feasible junction, independently of temporary
     * traffic. A caller must acquire the envelope and ordinary signal authorities. */
    public static Plan find(RailNetwork network,RailNetwork.Train train,String station,String incoming,String target) {
        synchronized(network) {
            if(train==null||station==null||incoming==null||target==null||station.equals(target))return null;
            RailNetwork.Node start=network.nodes.get(station);
            if(start==null||!start.links.contains(incoming)||!network.nodes.containsKey(target))return null;
            // A forward route, even a longer loop, does not require a station turnback.
            if(!TrafficRouter.path(network,train,station,incoming,target,false).isEmpty())return null;
            PriorityQueue<Search> queue=new PriorityQueue<Search>((a,b)->{
                int distance=Double.compare(a.distance,b.distance);return distance!=0?distance:Long.compare(a.order,b.order);
            });
            Map<String,Double> best=new HashMap<String,Double>();long order=0;
            List<String> initial=new ArrayList<String>();initial.add(station);initial.add(incoming);
            queue.add(new Search(initial,edgeLength(network,station,incoming),order++));
            while(!queue.isEmpty()) {
                Search search=queue.remove();List<String> path=search.path;int last=path.size()-1;
                String previous=path.get(last-1),current=path.get(last),key=previous+"\n"+current;
                Double old=best.get(key);if(old!=null&&old<=search.distance+EPS)continue;best.put(key,search.distance);
                RailNetwork.Node node=network.nodes.get(current);
                if(node.links.size()>2) {
                    Plan plan=candidate(network,train,station,target,path);
                    if(plan!=null)return plan;
                }
                for(String next:node.links)if(!path.contains(next)&&legalPair(network,current,previous,next)) {
                    List<String> extension=new ArrayList<String>(path);extension.add(next);
                    queue.add(new Search(extension,search.distance+edgeLength(network,current,next),order++));
                }
            }
            return null;
        }
    }

    /** Validate optional saved maneuver state without changing the itinerary or points. */
    public static boolean valid(RailNetwork network,RailNetwork.Train train,Plan plan) {
        synchronized(network) {
            if(train==null||plan==null||!train.automated||train.route.isEmpty()
                    ||train.routeIndex<0||train.routeIndex>=train.route.size()
                    ||!plan.target.equals(train.route.get(train.routeIndex))
                    ||!plan.station.equals(train.route.get((train.routeIndex+train.route.size()-1)%train.route.size())))return false;
            try {
                List<String> backing=plan.backingPath,departure=plan.departurePath;
                if(!validPath(network,backing)||!validPath(network,departure)
                        ||!backing.get(0).equals(plan.station)||!departure.get(0).equals(plan.waypoint)
                        ||!departure.get(departure.size()-1).equals(plan.target))return false;
                int junction=backing.indexOf(plan.junction),out=departure.indexOf(plan.junction);
                if(junction<=0||junction+1>=backing.size()||out<=0||out+1>=departure.size()
                        ||network.nodes.get(plan.junction).links.size()<3)return false;
                String stationArm=backing.get(junction-1),pocketArm=backing.get(junction+1);
                if((network.signalSide(plan.junction,stationArm)+4)%8!=network.signalSide(plan.junction,pocketArm)
                        ||!legalPair(network,plan.junction,stationArm,pocketArm))return false;
                if(out!=backing.size()-1-junction)return false;
                for(int i=0;i<=out;i++)if(!departure.get(i).equals(backing.get(backing.size()-1-i)))return false;
                if(departure.get(out+1).equals(stationArm)||departure.get(out+1).equals(pocketArm))return false;
                Set<String> stationLeg=new HashSet<String>(backing.subList(0,junction));
                for(int i=out+1;i<departure.size();i++)if(stationLeg.contains(departure.get(i)))return false;
                for(int i=junction+1;i<backing.size();i++)if(network.nodes.get(backing.get(i)).links.size()>2)return false;
                return pathLength(network,backing.subList(junction,backing.size()))+EPS>=required(train);
            } catch(IllegalArgumentException|NullPointerException invalid) { return false; }
        }
    }

    private static Plan candidate(RailNetwork network,RailNetwork.Train train,String station,String target,List<String> stationLeg) {
        int last=stationLeg.size()-1;String junction=stationLeg.get(last),stationArm=stationLeg.get(last-1),pocketArm=null;
        RailNetwork.Node points=network.nodes.get(junction);int opposite=(network.signalSide(junction,stationArm)+4)%8;
        for(String next:points.links)if(network.signalSide(junction,next)==opposite)pocketArm=next;
        if(pocketArm==null||!legalPair(network,junction,stationArm,pocketArm))return null;
        List<String> pocket=new ArrayList<String>();pocket.add(junction);pocket.add(pocketArm);
        Set<String> seen=new HashSet<String>(stationLeg);
        while(true) {
            String current=pocket.get(pocket.size()-1),previous=pocket.get(pocket.size()-2);
            RailNetwork.Node node=network.nodes.get(current);
            if(!seen.add(current)||node.links.size()>2)return null;
            if(pathLength(network,pocket)+EPS>=required(train))break;
            String next=null;for(String link:node.links)if(!link.equals(previous))next=link;
            if(next==null||!legalPair(network,current,previous,next))return null;pocket.add(next);
        }
        String waypoint=pocket.get(pocket.size()-1);Set<String> forbidden=new HashSet<String>(stationLeg);
        forbidden.addAll(pocket);forbidden.remove(junction);
        List<String> bestDeparture=null;double bestLength=Double.POSITIVE_INFINITY;
        for(String main:points.links)if(!main.equals(stationArm)&&!main.equals(pocketArm)
                &&legalPair(network,junction,pocketArm,main)) {
            List<String> tail=departurePath(network,junction,main,target,forbidden);
            if(tail.isEmpty())continue;
            List<String> departure=new ArrayList<String>(pocket);Collections.reverse(departure);departure.addAll(tail);
            double distance=pathLength(network,departure);
            if(distance<bestLength-EPS) { bestLength=distance;bestDeparture=departure; }
        }
        if(bestDeparture==null)return null;
        List<String> backing=new ArrayList<String>(stationLeg);backing.addAll(pocket.subList(1,pocket.size()));
        return new Plan(network,station,junction,target,backing,bestDeparture,false);
    }

    /** Directed shortest path leaving the chosen main side, excluding the station
     * and clearing pocket. It cannot return to the turning junction or U-turn. */
    private static List<String> departurePath(RailNetwork network,String junction,String first,String target,Set<String> forbidden) {
        if(forbidden.contains(first)||forbidden.contains(target))return Collections.emptyList();
        PriorityQueue<Search> queue=new PriorityQueue<Search>((a,b)->{
            int distance=Double.compare(a.distance,b.distance);return distance!=0?distance:Long.compare(a.order,b.order);
        });
        List<String> initial=new ArrayList<String>();initial.add(junction);initial.add(first);long order=0;
        queue.add(new Search(initial,edgeLength(network,junction,first),order++));Map<String,Double> best=new HashMap<String,Double>();
        while(!queue.isEmpty()) {
            Search search=queue.remove();List<String> path=search.path;int last=path.size()-1;
            String current=path.get(last),previous=path.get(last-1),key=previous+"\n"+current;
            Double old=best.get(key);if(old!=null&&old<=search.distance+EPS)continue;best.put(key,search.distance);
            if(current.equals(target))return new ArrayList<String>(path.subList(1,path.size()));
            for(String next:network.nodes.get(current).links)if(!forbidden.contains(next)&&!path.contains(next)
                    &&legalPair(network,current,previous,next)) {
                List<String> extension=new ArrayList<String>(path);extension.add(next);
                queue.add(new Search(extension,search.distance+edgeLength(network,current,next),order++));
            }
        }
        return Collections.emptyList();
    }

    private static Set<String> envelope(RailNetwork network,String junction,List<String> backing,List<String> departure) {
        synchronized(network) {
            Set<String> result=new LinkedHashSet<String>(network.signalRouteResources(backing));
            int start=departure.indexOf(junction);
            if(start<0)throw new IllegalArgumentException("Turnback departure misses its junction.");
            List<String> clearance=new ArrayList<String>();clearance.add(junction);
            for(int i=start+1;i<departure.size();i++) {
                clearance.add(departure.get(i));if(pathLength(network,clearance)+EPS>=CLEARANCE)break;
            }
            result.addAll(network.signalRouteResources(clearance));return Collections.unmodifiableSet(result);
        }
    }
    private static List<String> immutable(List<String> path) {
        if(path==null||path.isEmpty())throw new IllegalArgumentException("Turnback track path is missing.");
        List<String> copy=new ArrayList<String>(path);for(String rail:copy)Objects.requireNonNull(rail,"Turnback track");
        return Collections.unmodifiableList(copy);
    }
    private static boolean validPath(RailNetwork network,List<String> path) {
        if(path.size()<2||new HashSet<String>(path).size()!=path.size())return false;
        for(int i=0;i<path.size();i++) {
            RailNetwork.Node node=network.nodes.get(path.get(i));if(node==null)return false;
            if(i>0&&!node.links.contains(path.get(i-1)))return false;
            if(i>0&&i+1<path.size()&&!legalPair(network,path.get(i),path.get(i-1),path.get(i+1)))return false;
        }
        return true;
    }
    private static boolean legalPair(RailNetwork network,String rail,String previous,String next) {
        RailNetwork.Node node=network.nodes.get(rail);
        if(node==null||previous.equals(next)||!node.links.contains(previous)||!node.links.contains(next))return false;
        int mask=(1<<network.signalSide(rail,previous))|(1<<network.signalSide(rail,next));
        return RailNetwork.isBuildableAlignment(mask)&&network.junctionAlignments(rail).contains(mask);
    }
    private static double required(RailNetwork.Train train) { return (train.carIds.size()-1)*RailNetwork.CAR_SPACING+CLEARANCE; }
    private static double edgeLength(RailNetwork network,String first,String second) {
        RailNetwork.Node a=network.nodes.get(first),b=network.nodes.get(second);
        if(a==null||b==null||!a.links.contains(second))throw new IllegalArgumentException("Turnback track is disconnected.");
        return Math.hypot(b.worldX()-a.worldX(),b.worldY()-a.worldY());
    }
    /** Matches RailNetwork.Geometry's sampled curves and straight endpoint halves. */
    private static double pathLength(RailNetwork network,List<String> path) {
        double length=0;
        for(int i=0;i<path.size();i++) {
            RailNetwork.Node node=network.nodes.get(path.get(i));
            RailNetwork.Node before=i==0?node:network.nodes.get(path.get(i-1)),after=i+1==path.size()?node:network.nodes.get(path.get(i+1));
            if(node==null||before==null||after==null)throw new IllegalArgumentException("Turnback track is missing.");
            double ax=(before.worldX()+node.worldX())*.5,ay=(before.worldY()+node.worldY())*.5;
            double bx=(after.worldX()+node.worldX())*.5,by=(after.worldY()+node.worldY())*.5;
            double cx=i==0||i+1==path.size()?(ax+bx)*.5:node.worldX();
            double cy=i==0||i+1==path.size()?(ay+by)*.5:node.worldY(),px=ax,py=ay;
            for(int sample=1;sample<=64;sample++) {
                double t=sample/64.0,u=1-t,x=u*u*ax+2*u*t*cx+t*t*bx,y=u*u*ay+2*u*t*cy+t*t*by;
                length+=Math.hypot(x-px,y-py);px=x;py=y;
            }
        }
        return length;
    }
    private static final class Search {
        final List<String> path;final double distance;final long order;
        Search(List<String> path,double distance,long order) { this.path=path;this.distance=distance;this.order=order; }
    }
}
