package org.wurmrail.core;

import java.util.*;

/** Directional automatic route planning, separate from signal authority and physical movement. */
public final class TrafficRouter {
    private static final double EPS=0.000001;
    private TrafficRouter() { }

    /**
     * Find a route while retaining the side from which the train arrives. Reaching a
     * station completes the path; deciding whether to back out after that arrival is
     * deliberately the movement controller's responsibility. No intermediate tile
     * permits a U-turn. A null previous side allows the caller to select a departure
     * after an explicit, stationary reversal.
     *
     * When requested, foreign bodies and reservations are excluded rather than
     * assigned a small penalty: a shorter occupied branch must not defeat a clear
     * passing branch. This method grants no authority and changes no train state.
     */
    public static List<String> path(RailNetwork network,RailNetwork.Train train,String from,
                                    String previous,String target,boolean avoidForeign) {
        synchronized(network) {
            if(train==null||!network.nodes.containsKey(from)||!network.nodes.containsKey(target))
                return Collections.emptyList();
            RailNetwork.Node start=network.nodes.get(from);
            if(previous!=null&&!start.links.contains(previous))return Collections.emptyList();
            if(from.equals(target))return Collections.singletonList(from);
            Set<String> occupied=new HashSet<String>();
            if(avoidForeign)for(RailNetwork.Train other:network.trains.values())
                if(other!=train)occupied.addAll(network.signalOccupiedResources(other));
            final Map<Position,Double> distances=new HashMap<Position,Double>();
            Map<Position,Position> predecessors=new HashMap<Position,Position>();
            PriorityQueue<Step> queue=new PriorityQueue<Step>((a,b)->{
                int distance=Double.compare(a.distance,b.distance);
                return distance!=0?distance:Long.compare(a.order,b.order);
            });
            long order=0;
            Position first=new Position(previous,from);
            distances.put(first,0.0);predecessors.put(first,null);queue.add(new Step(first,0,order++));
            Position destination=null;
            while(!queue.isEmpty()) {
                Step step=queue.remove();
                if(step.distance>distances.get(step.position)+EPS)continue;
                if(step.position.current.equals(target)) { destination=step.position;break; }
                RailNetwork.Node node=network.nodes.get(step.position.current);
                for(String next:node.links) {
                    if(next.equals(step.position.previous))continue;
                    if(avoidForeign) {
                        Set<String> resources=network.signalRouteResources(Arrays.asList(node.name,next));
                        if(intersects(resources,occupied)||network.signals().conflictsResources(train.name,resources))continue;
                    }
                    RailNetwork.Node neighbor=network.nodes.get(next);
                    double distance=step.distance+Math.hypot(neighbor.worldX()-node.worldX(),neighbor.worldY()-node.worldY());
                    Position position=new Position(node.name,next);
                    Double old=distances.get(position);
                    if(old==null||distance<old-EPS) {
                        distances.put(position,distance);predecessors.put(position,step.position);
                        queue.add(new Step(position,distance,order++));
                    }
                }
            }
            if(destination==null)return Collections.emptyList();
            LinkedList<String> result=new LinkedList<String>();
            for(Position at=destination;at!=null;at=predecessors.get(at))result.addFirst(at.current);
            return Collections.unmodifiableList(result);
        }
    }

    /** Prefer an engine-leading route, including a physical turning loop of any length.
     * Backing is necessary only when no engine-leading topological route exists. A train
     * already backing can restore engine-leading traction at an allowed stationary departure.
     * No candidate may reverse again on an intermediate tile; this method changes no poses.
     */
    public static List<String> preferredPath(RailNetwork network,RailNetwork.Train train,String from,
                                             String previous,String target,boolean avoidForeign,boolean allowReversal) {
        synchronized(network) {
            List<String> forward=path(network,train,from,previous,target,false);
            if(!allowReversal||previous==null||from.equals(target))return avoidForeign?path(network,train,from,previous,target,true):forward;
            RailNetwork.Node start=network.nodes.get(from);
            if(start==null||!start.links.contains(previous))return forward;
            List<String> tail=path(network,train,previous,from,target,false);
            if(tail.isEmpty())return avoidForeign?path(network,train,from,previous,target,true):forward;
            List<String> reverse=new ArrayList<String>();reverse.add(from);reverse.addAll(tail);
            if(forward.isEmpty()||train.direction<0) {
                if(avoidForeign) {
                    if(!clearEdge(network,train,from,previous,true))return Collections.emptyList();
                    tail=path(network,train,previous,from,target,true);
                    if(tail.isEmpty())return Collections.emptyList();
                    reverse=new ArrayList<String>();reverse.add(from);reverse.addAll(tail);
                }
                return Collections.unmodifiableList(reverse);
            }
            // Compare topology before occupancy. A temporarily blocked engine-leading
            // route must wait or use a clear branch, rather than turn into a backing shortcut.
            return avoidForeign?path(network,train,from,previous,target,true):forward;
        }
    }
    /** Native metres between connected rail centres, also useful to dispatch or diagnostics. */
    public static double distance(RailNetwork network,List<String> path) {
        synchronized(network) {
            if(path==null||path.isEmpty())return Double.POSITIVE_INFINITY;
            double distance=0;
            for(int i=1;i<path.size();i++) {
                RailNetwork.Node first=network.nodes.get(path.get(i-1)),second=network.nodes.get(path.get(i));
                if(first==null||second==null||!first.links.contains(second.name))return Double.POSITIVE_INFINITY;
                distance+=Math.hypot(second.worldX()-first.worldX(),second.worldY()-first.worldY());
            }
            return distance;
        }
    }
    private static boolean clearEdge(RailNetwork network,RailNetwork.Train train,String first,String second,boolean avoidForeign) {
        if(!avoidForeign)return true;
        Set<String> resources=network.signalRouteResources(Arrays.asList(first,second));
        if(network.signals().conflictsResources(train.name,resources))return false;
        for(RailNetwork.Train other:network.trains.values())
            if(other!=train&&intersects(resources,network.signalOccupiedResources(other)))return false;
        return true;
    }

    private static boolean intersects(Set<String> first,Set<String> second) {
        for(String resource:first)if(second.contains(resource))return true;
        return false;
    }
    private static final class Position {
        final String previous,current;
        Position(String previous,String current) { this.previous=previous;this.current=current; }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Position))return false;
            Position position=(Position)other;
            return Objects.equals(previous,position.previous)&&current.equals(position.current);
        }
        @Override public int hashCode() { return 31*Objects.hashCode(previous)+current.hashCode(); }
    }
    private static final class Step {
        final Position position;final double distance;final long order;
        Step(Position position,double distance,long order) { this.position=position;this.distance=distance;this.order=order; }
    }
}
