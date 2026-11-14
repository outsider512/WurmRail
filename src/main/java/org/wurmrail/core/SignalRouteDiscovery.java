package org.wurmrail.core;

import java.util.*;

/** Topology-only route tracing. This class neither aligns points nor grants movement authority. */
public final class SignalRouteDiscovery {
    private SignalRouteDiscovery() { }
    public static final class Route {
        public final List<String> path, nodes;
        public final Map<String,Integer> junctionAlignments;
        public final String exitSignalId;
        Route(List<String> path,List<String> nodes,Map<String,Integer> alignments,String exit) {
            this.path=Collections.unmodifiableList(new ArrayList<String>(path));
            this.nodes=Collections.unmodifiableList(new ArrayList<String>(nodes));
            junctionAlignments=Collections.unmodifiableMap(new LinkedHashMap<String,Integer>(alignments));
            exitSignalId=exit;
        }
    }
    public static Route discover(RailNetwork network,String trainName,SignalSystem.Signal entry) {
        synchronized(network) {
            RailNetwork.Train train=network.trains.get(trainName);
            if(train==null)throw new IllegalArgumentException("Unknown train: "+trainName);
            RailNetwork.Node start=network.nodes.get(entry.rail);
            if(start==null)throw new IllegalArgumentException("Signal track is missing.");
            String previous=network.signalNeighbor(entry.rail,entry.approachDirection);
            if(previous==null)throw new IllegalArgumentException("Signal approach is no longer connected.");
            List<String> path=new ArrayList<String>(),protectedNodes=new ArrayList<String>();
            Map<String,Integer> alignments=new LinkedHashMap<String,Integer>();
            Set<String> visited=new HashSet<String>(); String current=start.name;
            // Look-ahead must advance its own itinerary, never the live train's next station.
            int routeCursor=train.routeIndex;
            for(int count=0;count<=network.nodes.size();count++) {
                RailNetwork.Node node=network.nodes.get(current);
                if(node==null||!node.links.contains(previous))throw new IllegalArgumentException("Route topology is disconnected at "+current+".");
                path.add(current);
                int incoming=network.signalSide(current,previous);
                SignalSystem.Signal exit=network.signals().at(current,incoming);
                if(path.size()>1&&exit!=null)return new Route(path,protectedNodes,alignments,exit.id);
                // A circuit can return to its own directional entrance signal. That is
                // a real block boundary: reject only a repeated rail with no protecting
                // signal on the side entered, after checking that boundary first.
                if(!visited.add(current))throw new IllegalArgumentException("Route loops without a protecting signal at "+current+".");
                protectedNodes.add(current);
                if(network.isTrafficRecoveryStop(train,current))return new Route(path,protectedNodes,alignments,null);
                boolean scheduledStop=train.automated&&!train.route.isEmpty()&&current.equals(train.route.get(routeCursor));
                boolean destinationEnd=scheduledStop&&train.route.size()==1;
                if(scheduledStop&&!destinationEnd) {
                    int duplicates=0;
                    do { routeCursor=(routeCursor+1)%train.route.size();duplicates++; }
                    while(current.equals(train.route.get(routeCursor))&&duplicates<train.route.size());
                    destinationEnd=current.equals(train.route.get(routeCursor));
                }
                String next=destinationEnd?null:network.signalIntendedNext(train,current,previous,routeCursor,scheduledStop);
                // A planned station reversal is a route end. Keep the acquired authority
                // while the train arrives, dwells and backs out along the same protected rail.
                boolean plannedReversal=scheduledStop&&previous.equals(next);
                if(node.links.size()>2) {
                    if(next==null||plannedReversal)throw new IllegalArgumentException("Intended junction route is ambiguous at "+current+"; place a reversing station on a two-sided track or terminus.");
                    alignments.put(current,(1<<incoming)|(1<<network.signalSide(current,next)));
                }
                if(next==null||plannedReversal)return new Route(path,protectedNodes,alignments,null);
                if(next.equals(previous)||!node.links.contains(next))throw new IllegalArgumentException("Route reverses or disconnects at "+current+".");
                previous=current;current=next;
            }
            throw new IllegalArgumentException("Route exceeds track topology.");
        }
    }
}
