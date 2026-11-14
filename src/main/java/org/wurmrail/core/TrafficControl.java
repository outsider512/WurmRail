package org.wurmrail.core;

import java.util.*;

/** Transient dispatching for circulation loops, shared connectors and terminal turnbacks. */
public final class TrafficControl {
    public static final int DIRECTIONAL_BATCH_LIMIT=3;
    public static final class Admission {
        public final boolean granted;
        public final String reason;
        private final List<Lease> leases;
        private final long topology;
        private final long revision;
        private Admission(boolean granted,String reason,List<Lease> leases,long topology,long revision) {
            this.granted=granted;this.reason=reason;this.leases=leases;this.topology=topology;this.revision=revision;
        }
    }
    /** A whole-consist crossing token, including a clear receiving berth. */
    public static final class Lease {
        public final String trainName,corridorId,entryJunction,exitJunction,exitNeighbor;
        public final List<String> nodes,berth;
        public final Set<String> resources;
        public final boolean entered;
        public final int tractionDirection;
        private final int consistCars;
        private Lease(RailNetwork.Train train,Corridor corridor,String entry,List<String> berth,Set<String> resources,boolean entered) {
            trainName=train.name;tractionDirection=train.direction;consistCars=train.carIds.size();corridorId=corridor.id;entryJunction=entry;
            exitJunction=corridor.other(entry);exitNeighbor=berth.get(1);
            nodes=corridor.path;this.berth=Collections.unmodifiableList(new ArrayList<String>(berth));
            this.resources=Collections.unmodifiableSet(new LinkedHashSet<String>(resources));this.entered=entered;
        }
        private Lease(Lease old,boolean entered) {
            trainName=old.trainName;corridorId=old.corridorId;entryJunction=old.entryJunction;
            exitJunction=old.exitJunction;exitNeighbor=old.exitNeighbor;nodes=old.nodes;berth=old.berth;
            resources=old.resources;this.entered=entered;
            tractionDirection=old.tractionDirection;
            consistCars=old.consistCars;
        }
        private Lease(Lease old,List<String> berth,Set<String> resources) {
            trainName=old.trainName;corridorId=old.corridorId;entryJunction=old.entryJunction;exitJunction=old.exitJunction;
            exitNeighbor=berth.get(1);nodes=old.nodes;this.berth=Collections.unmodifiableList(new ArrayList<String>(berth));
            this.resources=Collections.unmodifiableSet(new LinkedHashSet<String>(resources));entered=old.entered;
            tractionDirection=old.tractionDirection;consistCars=old.consistCars;
        }
    }
    public static final class Checkpoint {
        private final Map<String,Lease> leases;
        private final Map<String,Lease> discharged;
        private final Map<String,LinkedHashMap<String,Request>> queues;
        private final Map<String,LinkedHashSet<String>> turnbackQueues;
        private final Map<String,Phase> phases;
        private final long sequence;
        private Checkpoint(TrafficControl owner) {
            leases=Collections.unmodifiableMap(new LinkedHashMap<String,Lease>(owner.leases));
            discharged=Collections.unmodifiableMap(new LinkedHashMap<String,Lease>(owner.discharged));
            queues=copyQueues(owner.queues);phases=new LinkedHashMap<String,Phase>(owner.phases);sequence=owner.sequence;
            turnbackQueues=copyTurnbackQueues(owner.turnbackQueues);
        }
    }
    /** Entry junctions are absolute track ends, independent of locomotive traction. */
    private static final class Request {
        final String entry,approach;
        final long sequence;
        Request(String entry,String approach,long sequence) { this.entry=entry;this.approach=approach;this.sequence=sequence; }
    }
    private static final class Phase {
        final String entry;
        final int admissions;
        Phase(String entry,int admissions) { this.entry=entry;this.admissions=admissions; }
    }
    private static final class Crossing {
        final Lease lease;
        final String reason;
        Crossing(Lease lease,String reason) { this.lease=lease;this.reason=reason; }
    }
    private static final class LoopTurnback {
        final Set<String> members,nodes,resources;
        LoopTurnback(Set<String> members,List<String> path,Set<String> resources) {
            this.members=members;nodes=new HashSet<String>(path);this.resources=resources;
        }
    }
    private static final class Corridor {
        final String id,first,last;
        final List<String> path;
        Corridor(List<String> rails) {
            List<String> ordered=new ArrayList<String>(rails);
            if(ordered.get(0).compareTo(ordered.get(ordered.size()-1))>0)Collections.reverse(ordered);
            path=Collections.unmodifiableList(ordered);first=path.get(0);last=path.get(path.size()-1);
            id=first+"|"+last+"|"+path.toString();
        }
        String other(String end) { return first.equals(end)?last:first; }
        String neighbor(String end) { return first.equals(end)?path.get(1):path.get(path.size()-2); }
    }
    private static final class Frame {
        final String rail,parent;
        final Iterator<String> next;
        Frame(RailNetwork.Node node,String parent) { rail=node.name;this.parent=parent;next=node.links.iterator(); }
    }
    private final RailNetwork network;
    private final Map<String,Corridor> corridors=new LinkedHashMap<String,Corridor>();
    private final Map<String,SignalSystem.Signal> boundaries=new LinkedHashMap<String,SignalSystem.Signal>();
    private final Set<String> activeRails=new HashSet<String>();
    private final Map<String,Lease> leases=new LinkedHashMap<String,Lease>();
    // Members already off the connector still need to move away through the
    // shared receiving lane while the rear of their convoy clears it.
    private final Map<String,Lease> discharged=new LinkedHashMap<String,Lease>();
    private final Map<String,LinkedHashMap<String,Request>> queues=new LinkedHashMap<String,LinkedHashMap<String,Request>>();
    private final Map<String,Phase> phases=new LinkedHashMap<String,Phase>();
    private final Map<String,List<String>> simpleLoops=new HashMap<String,List<String>>();
    private final Map<String,String> loopApproaches=new LinkedHashMap<String,String>();
    private final Map<String,Set<String>> turnbackYards=new LinkedHashMap<String,Set<String>>();
    private final Map<String,LinkedHashSet<String>> turnbackQueues=new LinkedHashMap<String,LinkedHashSet<String>>();
    private long topology=Long.MIN_VALUE,sequence,revision;
    private Checkpoint cachedCheckpoint;
    private boolean planningExit,selectingTurn;
    public TrafficControl(RailNetwork network) { this.network=Objects.requireNonNull(network); }
    public long revision() { synchronized(network) { return revision; } }
    public Collection<Lease> leases() {
        synchronized(network) { return Collections.unmodifiableList(new ArrayList<Lease>(leases.values())); }
    }
    public boolean activeAt(String rail) { synchronized(network) { topology();return activeRails.contains(rail); } }
    /** A waiting entrant must leave clearance for complete consists passing
     * around the loop bend, including carts still sweeping across the points. */
    boolean loopEntrance(String junction,String previous) {
        topology();return previous!=null&&previous.equals(loopApproaches.get(junction));
    }
    boolean stationQueueApproach(RailNetwork.Train train,String junction,String previous) {
        topology();Set<String> yard=turnbackYards.get(junction);
        return yard!=null&&previous!=null&&!previous.equals(loopApproaches.get(junction))
                &&train.automated&&!train.route.isEmpty()&&yard.contains(train.route.get(train.routeIndex));
    }
    public SignalSystem.Signal boundaryAt(String rail,int approach) {
        synchronized(network) { topology();return boundaries.get(boundaryKey(rail,approach)); }
    }
    public SignalSystem.Signal signal(String id) {
        synchronized(network) {
            topology();for(SignalSystem.Signal signal:boundaries.values())if(signal.id.equals(id))return signal;return null;
        }
    }
    /** Plan an atomic acquire; only FIFO registration changes until commit succeeds. */
    public Admission prepare(RailNetwork.Train train,List<String> path) {
        synchronized(network) { return prepare(train,path,network.signalIncomingRail(train)); }
    }
    Admission prepare(RailNetwork.Train train,List<String> path,String entryPrevious) {
        synchronized(network) {
            topology();restoreOccupiedCorridors();updateClearance();List<Lease> planned=new ArrayList<Lease>();
            String unsafeExit=receivingUnsafe(train,path,entryPrevious);
            if(unsafeExit!=null)return denied(unsafeExit);
            Set<String> seen=new HashSet<String>();
            for(int i=0;i+1<path.size();i++)for(Corridor corridor:corridors.values()) {
                String junction=path.get(i);
                if(!(junction.equals(corridor.first)||junction.equals(corridor.last))
                        ||!corridor.neighbor(junction).equals(path.get(i+1))||!seen.add(corridor.id))continue;
                List<Lease> cohort=cohort(corridor.id);
                if(leases.containsKey(leaseKey(corridor.id,train.name)))continue;
                String orienting=stationOrientationHold(train,corridor.other(junction),corridor.neighbor(corridor.other(junction)));
                if(orienting!=null)return denied(orienting);
                enqueue(corridor.id,train.name,junction);
                String ineligible=admissionDenied(train,corridor,junction,cohort);
                if(ineligible!=null)return denied(ineligible);
                Crossing crossing=planCrossing(train,corridor,junction,cohort);
                if(crossing.lease==null)return denied(crossing.reason);
                planned.add(crossing.lease);
            }
            return new Admission(true,"Single-track crossing and receiving exit are clear.",Collections.unmodifiableList(planned),topology,revision);
        }
    }
    /** A loop's entry direction is independent of whether its approach joins
     * another loop. Short blocks protect each movement after this admission. */
    private String loopEntryUnsafe(RailNetwork.Train train,List<String> path,String entryPrevious) {
        for(int i=0;i+1<path.size();i++) {
            String junction=path.get(i),outside=loopApproaches.get(junction);
            if(outside==null||!outside.equals(i==0?entryPrevious:path.get(i-1)))continue;
            LoopTurnback maneuver=loopTurnback(junction);
            String orienting=maneuver!=null&&maneuver.members.contains(train.name)?null:stationOrientationHold(train,junction,outside);
            if(orienting!=null)return orienting;
            if(!receivingExits(train,junction,outside).contains(path.get(i+1)))
                return "Waiting for a safe entry behind the trains on receiving loop "+junction+".";
            if(maneuver!=null&&maneuver.members.contains(train.name)
                    &&(foreignOccupant(train.name,maneuver.resources,maneuver.members)!=null
                    ||network.signals().conflictsBlockResourcesExcept(train.name,maneuver.resources,maneuver.members)))
                return "Waiting for the loop turnback pocket to clear before backing through "+junction+".";
        }
        return null;
    }
    String receivingUnsafe(RailNetwork.Train train,List<String> path,String entryPrevious) {
        synchronized(network) {
            topology();
            for(RailNetwork.Train owner:network.trains.values())if(activeStationTurnback(owner)
                    &&owner!=train&&intersects(owner.stationTurnback.resources,network.signalRouteResources(path)))
                return "Waiting for "+owner.name+" to complete its local station turnaround at "+owner.stationTurnback.junction+".";
            String loopEntry=loopEntryUnsafe(train,path,entryPrevious);if(loopEntry!=null)return loopEntry;
            for(String junction:turnbackYards.keySet()) {
                LoopTurnback maneuver=loopTurnback(junction);
                if(maneuver==null||maneuver.members.contains(train.name))continue;
                // A resident already in the pocket must be able to drain out.
                // Other residents hold before entering it until the whole
                // backing cohort has cleared the points and restored forward.
                boolean occupying=false;for(String rail:maneuver.nodes)if(network.signalOccupiesNode(train,rail))occupying=true;
                if(!occupying&&intersects(maneuver.resources,network.signalRouteResources(path)))
                    return "Waiting for the backing cohort to complete its protected loop turn at "+junction+".";
            }
            for(int i=0;i+1<path.size();i++) {
                String junction=path.get(i),outside=loopApproaches.get(junction);
                if(outside!=null&&outside.equals(path.get(i+1))) {
                    String owner=turnbackOwner(junction,train);
                    if(owner!=null)return "Waiting for "+owner+" to return from the turnback branch and clear the Y junction.";
                    owner=turnbackQueueOwner(junction,train);
                    if(owner!=null&&!owner.equals(train.name))return "Waiting for "+owner+" to take its earlier turn into the station branch.";
                }
            }
            // A parked or newly registered receiver may appear after admission,
            // including after a GREEN aspect was acquired at this same signal.
            for(Lease own:new ArrayList<Lease>(leases.values()))if(own.trainName.equals(train.name))
                for(int i=0;i+1<path.size();i++)if(path.get(i).equals(own.exitJunction)) {
                    Corridor corridor=corridors.get(own.corridorId);if(corridor==null)continue;
                    String approachedFrom=i>0?path.get(i-1):entryPrevious;
                    // The receiving forecast applies when arriving FROM the
                    // connector. A recovering train already in the loop may
                    // safely circulate past these points to clear a blockage;
                    // its old receiving arm must not pin that local movement.
                    if(!corridor.neighbor(own.exitJunction).equals(approachedFrom))continue;
                    if(path.get(i+1).equals(corridor.neighbor(own.exitJunction)))continue;
                    String orienting=stationOrientationHold(train,own.exitJunction,corridor.neighbor(own.exitJunction));
                    if(orienting!=null)return orienting;
                    if(!path.get(i+1).equals(own.exitNeighbor))return "Receiving exit changed; waiting for an updated protected route at junction "+own.exitJunction+".";
                    List<Lease> convoy=cohort(own.corridorId);Set<String> permitted=permittedOwners(own.corridorId,convoy);permitted.add(train.name);
                    double required=0;
                    for(Lease member:members(own.corridorId,convoy)) {
                        RailNetwork.Train owner=network.trains.get(member.trainName);
                        if(owner!=null)required+=(owner.carIds.size()-1)*4.0+8.0;
                    }
                    if(receivingBerth(train,own.exitJunction,corridor.neighbor(own.exitJunction),required,permitted,own.exitNeighbor)==null)
                        return "Waiting for a safe receiving direction and space beyond junction "+own.exitJunction+".";
                }
            return null;
        }
    }
    public boolean commit(Admission admission) {
        synchronized(network) {
            topology();if(admission==null||!admission.granted||admission.topology!=topology||admission.revision!=revision)return false;
            for(Lease lease:admission.leases) {
                RailNetwork.Train candidate=network.trains.get(lease.trainName);
                if(!followable(lease)||candidate.carIds.size()!=lease.consistCars)return false;
                List<Lease> cohort=cohort(lease.corridorId);
                Corridor corridor=corridors.get(lease.corridorId);
                if(corridor==null||admissionDenied(candidate,corridor,lease.entryJunction,cohort)!=null)return false;
                Set<String> permitted=permittedOwners(lease.corridorId,cohort);permitted.add(lease.trainName);
                if(foreignOccupant(lease.trainName,lease.resources,permitted)!=null
                        ||network.signals().conflictsBlockResourcesExcept(lease.trainName,lease.resources,permitted))return false;
            }
            for(Lease lease:admission.leases) {
                String key=leaseKey(lease.corridorId,lease.trainName);
                if(discharged.remove(key)!=null)changed();
                if(!leases.containsKey(key)) {
                    Phase phase=phases.get(lease.corridorId);
                    boolean continuing=phase!=null&&phase.entry.equals(lease.entryJunction)
                            &&(!cohort(lease.corridorId).isEmpty()||phase.admissions<DIRECTIONAL_BATCH_LIMIT);
                    phases.put(lease.corridorId,new Phase(lease.entryJunction,continuing?phase.admissions+1:1));
                    leases.put(key,lease);changed();
                }
                dequeue(lease.corridorId,lease.trainName);
            }
            return true;
        }
    }
    /** One gate is shared by planning and commit; prepares never spend a turn slot. */
    private String admissionDenied(RailNetwork.Train train,Corridor corridor,String entry,List<Lease> cohort) {
        for(Lease existing:cohort) {
            if(!existing.entryJunction.equals(entry))return "Waiting for the opposing convoy to clear the single-track connection.";
            if(!followable(existing))return "Waiting for reversing train "+existing.trainName+" to clear the single-track connection.";
        }
        String first=oldestOnSide(corridor.id,entry,train.name);
        if(first!=null&&!first.equals(train.name))return "Waiting turn behind train "+first+" in this direction for the single-track connection.";
        Phase phase=phases.get(corridor.id);
        if(!cohort.isEmpty()) {
            if(phase!=null&&phase.entry.equals(entry)&&phase.admissions>=DIRECTIONAL_BATCH_LIMIT
                    &&oldestOnSide(corridor.id,corridor.other(entry),null)!=null)
                return "Three-train turn complete; waiting for this convoy to clear before the opposing turn.";
        } else {
            String turn=selectedEntry(corridor,train.name);
            if(turn!=null&&!turn.equals(entry))return "Waiting for the opposing direction's single-track turn.";
        }
        return null;
    }
    /** Keep a short batch only for its oldest physically ready follower, never an empty quota. */
    private String selectedEntry(Corridor corridor,String requester) {
        List<Lease> active=cohort(corridor.id);
        if(!active.isEmpty())return active.get(0).entryJunction;
        Phase phase=phases.get(corridor.id);
        if(phase==null) {
            String first=oldest(corridor.id,requester);Request request=request(corridor.id,first);
            return request==null?null:request.entry;
        }
        String opposite=oldestOnSide(corridor.id,corridor.other(phase.entry),requester);
        if(opposite==null)return phase.entry;
        String follower=oldestOnSide(corridor.id,phase.entry,requester);
        if(phase.admissions<DIRECTIONAL_BATCH_LIMIT&&follower!=null) {
            // signalIntendedNext may query preferredExit while choosing a berth.
            // That recursive query keeps this same absolute phase, without
            // modifying the train path or any reservation.
            if(selectingTurn)return phase.entry;
            selectingTurn=true;
            try {
                RailNetwork.Train train=network.trains.get(follower);
                if(train!=null&&approachReady(train,phase.entry)&&planCrossing(train,corridor,phase.entry,active).lease!=null)return phase.entry;
            } finally { selectingTurn=false; }
        }
        return corridor.other(phase.entry);
    }
    private boolean approachReady(RailNetwork.Train train,String entry) {
        String leading=network.signalLeadingRail(train),incoming=network.signalIncomingRail(train);
        if(leading==null)return false;
        List<String> approach=TrafficRouter.path(network,train,leading,incoming,entry,false);
        if(approach.isEmpty())return false;
        Set<String> resources=network.signalRouteResources(approach);
        return foreignOccupant(train.name,resources)==null&&!network.signals().conflictsBlockResources(train.name,resources);
    }
    /** Physical protection and aggregate receiving capacity stay independent of turn policy. */
    private Crossing planCrossing(RailNetwork.Train train,Corridor corridor,String entry,List<Lease> cohort) {
        Set<String> permitted=permittedOwners(corridor.id,cohort);permitted.add(train.name);
        Set<String> crossing=network.signalRouteResources(corridor.path);
        String receiving=corridor.other(entry),incoming=corridor.neighbor(receiving);
        Set<String> receivingLoop=loopAt(receiving,incoming);
        for(Lease member:members(corridor.id,cohort)) {
            RailNetwork.Train owner=network.trains.get(member.trainName);
            if(owner!=null&&owner.direction!=train.direction
                    &&(backingStationInLoop(owner,receivingLoop)||backingStationInLoop(train,receivingLoop)))
                return new Crossing(null,"Waiting for backing train "+(owner.direction<0?owner.name:train.name)
                        +" to finish its station turn before admitting a differently oriented follower.");
        }
        for(String arm:network.nodes.get(receiving).links)if(!arm.equals(incoming))crossing.add("N:"+arm);
        String occupied=foreignOccupant(train.name,crossing,permitted);
        if(occupied!=null)return new Crossing(null,"Waiting for train "+occupied+" to clear the single-track connection.");
        if(network.signals().conflictsBlockResourcesExcept(train.name,crossing,permitted))
            return new Crossing(null,"Waiting for an earlier reserved route to clear the single-track connection.");
        double required=(train.carIds.size()-1)*4.0+8.0;
        for(Lease member:members(corridor.id,cohort)) {
            RailNetwork.Train owner=network.trains.get(member.trainName);
            if(owner!=null)required+=(owner.carIds.size()-1)*4.0+8.0;
        }
        List<String> berth=receivingBerth(train,receiving,incoming,required,permitted,cohort.isEmpty()?null:cohort.get(0).exitNeighbor);
        if(berth==null)return new Crossing(null,"Waiting for a clear exit beyond junction "+receiving+" with room for every car.");
        crossing.addAll(network.signalRouteResources(berth));
        return new Crossing(new Lease(train,corridor,entry,berth,crossing,false),null);
    }
    public String preferredExit(String trainName,String junction,String previous) {
        synchronized(network) {
            topology();
            for(Lease lease:leases.values())if(lease.trainName.equals(trainName)&&lease.exitJunction.equals(junction)) {
                Corridor corridor=corridors.get(lease.corridorId);
                if(corridor!=null&&corridor.neighbor(junction).equals(previous))return lease.exitNeighbor;
            }
            String outside=loopApproaches.get(junction);
            if(outside!=null&&outside.equals(previous)) {
                RailNetwork.Train train=network.trains.get(trainName);
                if(train!=null) {
                    List<String> exits=receivingExits(train,junction,outside);
                    if(!exits.isEmpty())return exits.get(0);
                }
            }
            RailNetwork.Train looping=network.trains.get(trainName);Set<String> yard=turnbackYards.get(junction);
            if(yard!=null&&previous!=null&&!previous.equals(outside)&&looping!=null&&looping.automated
                    &&!looping.route.isEmpty()&&yard.contains(looping.route.get(looping.routeIndex))
                    &&(!hasTurnbackTurn(junction,looping)||turnbackOwner(junction,looping)!=null)) {
                if(!stationQueueMustCirculate(looping,junction,previous))return outside;
                for(String arm:network.nodes.get(junction).links)if(!arm.equals(previous)&&!arm.equals(outside))return arm;
            }
            // Keep a queued crossing request on its intended branch. A changing
            // block reservation is a reason to wait, not to circle the station.
            // Only a train already inside the receiving clearance may need to
            // circulate once to let an older opposing request enter safely.
            for(Corridor corridor:corridors.values())if((junction.equals(corridor.first)||junction.equals(corridor.last))
                    &&!corridor.neighbor(junction).equals(previous)) {
                LinkedHashMap<String,Request> queue=queues.get(corridor.id);
                if(queue==null||!queue.containsKey(trainName))continue;
                String turn=selectedEntry(corridor,trainName);
                RailNetwork.Train train=network.trains.get(trainName);
                String leading=train==null?null:network.signalLeadingRail(train);
                boolean blocking=train!=null&&(junction.equals(leading)||Objects.equals(previous,leading))
                        &&(network.signalOccupiesNode(train,junction)||network.signalOccupiesNode(train,previous));
                if(!blocking&&train!=null&&previous!=null&&leading!=null) {
                    RailNetwork.Node position=network.nodes.get(leading),points=network.nodes.get(junction);
                    blocking=Math.hypot(position.worldX()-points.worldX(),position.worldY()-points.worldY())<=12.0
                            &&network.signals().holdsOccupiedApproach(trainName,previous);
                }
                if(!blocking&&train!=null&&turn!=null&&!turn.equals(junction)&&cohort(corridor.id).isEmpty()) {
                    String waiting=oldestOnSide(corridor.id,corridor.other(junction),null);
                    RailNetwork.Train incoming=network.trains.get(waiting);
                    if(incoming!=null&&receivingExits(incoming,junction,corridor.neighbor(junction)).contains(previous)) {
                        List<String> approach=TrafficRouter.path(network,train,leading,network.signalIncomingRail(train),junction,false);
                        double required=(incoming.carIds.size()-1)*4.0+8.0;
                        blocking=!approach.isEmpty()&&TrafficRouter.distance(network,approach)<=required+(train.carIds.size()-1)*4.0+4.0;
                    }
                }
                if(!blocking||!cohort(corridor.id).isEmpty()||turn==null||turn.equals(junction))return corridor.neighbor(junction);
                String onward=null;
                for(String exit:network.nodes.get(junction).links)
                    if(!exit.equals(previous)&&!exit.equals(corridor.neighbor(junction))) {
                        if(onward!=null) { onward=null;break; }onward=exit;
                    }
                if(onward!=null)return onward;
                return corridor.neighbor(junction);
            }
            return null;
        }
    }
    /** Refresh forecasts before any train takes its movement/path rollback snapshot. */
    public void prepareMovement() {
        synchronized(network) {
            topology();
            restoreLoopExitIntents();
            refreshStationQueueExits();
            for(Lease old:new ArrayList<Lease>(leases.values())) {
                Lease current=leases.get(leaseKey(old.corridorId,old.trainName));Corridor corridor=corridors.get(old.corridorId);
                if(current!=null&&corridor!=null)refreshReceivingExit(current,corridor);
            }
            for(Lease lease:leases.values()) {
                RailNetwork.Train owner=network.trains.get(lease.trainName);
                if(owner!=null&&!network.signalOccupiesNode(owner,lease.exitJunction)
                        &&network.signals().canReplanUnenteredJunction(owner.name,lease.exitJunction))
                    network.resetUnenteredExit(owner.name,lease.exitJunction,lease.exitNeighbor);
            }
        }
    }
    /** Change a forecast only while the entire convoy is clear of the physical exit points. */
    private void refreshReceivingExit(Lease requested,Corridor corridor) {
        if(planningExit)return;planningExit=true;
        try {
            List<Lease> convoy=cohort(corridor.id);Set<String> permitted=permittedOwners(corridor.id,convoy);
            RailNetwork.Train train=network.trains.get(requested.trainName);if(train==null)return;permitted.add(train.name);
            for(Lease member:convoy) {
                RailNetwork.Train owner=network.trains.get(member.trainName);
                if(!member.entryJunction.equals(requested.entryJunction)||owner==null
                        ||network.signalOccupiesNode(owner,requested.exitJunction)
                        ||network.signalOccupiesNode(owner,member.exitNeighbor)
                        ||!network.signals().canReplanUnenteredJunction(owner.name,requested.exitJunction))return;
            }
            double required=0;
            for(Lease member:members(corridor.id,convoy)) {
                RailNetwork.Train owner=network.trains.get(member.trainName);
                if(owner!=null)required+=(owner.carIds.size()-1)*4.0+8.0;
            }
            String incoming=corridor.neighbor(requested.exitJunction);
            if(receivingBerth(train,requested.exitJunction,incoming,required,permitted,requested.exitNeighbor)!=null)return;
            List<String> berth=receivingBerth(train,requested.exitJunction,incoming,required,permitted,null);
            if(berth==null||berth.get(1).equals(requested.exitNeighbor))return;
            Set<String> resources=network.signalRouteResources(corridor.path);
            for(String arm:network.nodes.get(requested.exitJunction).links)if(!arm.equals(incoming))resources.add("N:"+arm);
            resources.addAll(network.signalRouteResources(berth));
            if(foreignOccupant(train.name,resources,permitted)!=null
                    ||network.signals().conflictsBlockResourcesExcept(train.name,resources,permitted))return;
            for(Lease member:convoy) {
                network.signals().cancelUnenteredJunction(member.trainName,requested.exitJunction);
                network.resetUnenteredExit(member.trainName,requested.exitJunction,berth.get(1));
                leases.put(leaseKey(corridor.id,member.trainName),new Lease(member,berth,resources));
            }
            changed();
        } finally { planningExit=false; }
    }
    public boolean conflictsResources(String trainName,Set<String> resources) {
        return conflictsResources(trainName,resources,null);
    }
    /** Direction permissions may be shared; actual signal-block resources are checked separately. */
    public boolean conflictsResources(String trainName,Set<String> resources,Admission admission) {
        synchronized(network) {
            for(RailNetwork.Train owner:network.trains.values())if(!owner.name.equals(trainName)&&activeStationTurnback(owner)
                    &&intersects(owner.stationTurnback.resources,resources))return true;
            for(Lease lease:leases.values())if(!lease.trainName.equals(trainName)&&intersects(lease.resources,resources)) {
                if(network.isRecoveringTraffic(trainName)&&!intersects(network.signalRouteResources(lease.nodes),resources))continue;
                if(network.isRecoveringTraffic(trainName)&&localReceivingCircuit(trainName,lease,resources))continue;
                if(network.isRecoveringTraffic(trainName)&&escapingOwner(trainName,lease))continue;
                Lease own=leases.get(leaseKey(lease.corridorId,trainName));
                if(own==null)own=discharged.get(leaseKey(lease.corridorId,trainName));
                if(own==null&&admission!=null&&admission.granted&&admission.topology==topology)
                    for(Lease pending:admission.leases)if(pending.trainName.equals(trainName)&&pending.corridorId.equals(lease.corridorId))own=pending;
                if(own==null||!own.entryJunction.equals(lease.entryJunction)||!followable(own)||!followable(lease))return true;
            }
            return false;
        }
    }
    /** Yield on an outer receiving lane only; connector and junction direction locks remain exclusive. */
    boolean conflictsRecoveryResources(String trainName,Set<String> resources) {
        synchronized(network) {
            for(RailNetwork.Train owner:network.trains.values())if(!owner.name.equals(trainName)&&activeStationTurnback(owner)
                    &&intersects(owner.stationTurnback.resources,resources))return true;
            for(Lease lease:leases.values())if(!lease.trainName.equals(trainName)&&intersects(lease.resources,resources)
                    &&intersects(network.signalRouteResources(lease.nodes),resources)&&!localReceivingCircuit(trainName,lease,resources)
                    &&!escapingOwner(trainName,lease))return true;
            return false;
        }
    }
    /** An entrance forecast must not trap a local recovery circuit on the
     * receiving points. Actual signal blocks, occupied points and physical
     * junction locks are still checked independently for this entire route. */
    private boolean localReceivingCircuit(String trainName,Lease lease,Set<String> resources) {
        if(!resources.contains("N:"+lease.exitJunction))return false;
        Corridor corridor=corridors.get(lease.corridorId);RailNetwork.Train train=network.trains.get(trainName);
        if(corridor==null||train==null||!touchesLoop(train,loopAt(lease.exitJunction,corridor.neighbor(lease.exitJunction))))return false;
        Set<String> line=network.signalRouteResources(corridor.path);line.remove("N:"+lease.exitJunction);
        return !intersects(line,resources);
    }
    public boolean compatible(String first,String second) {
        synchronized(network) {
            for(Lease lease:members(null,new ArrayList<Lease>(leases.values())))if(lease.trainName.equals(first)&&followable(lease)) {
                Lease other=leases.get(leaseKey(lease.corridorId,second));
                if(other==null)other=discharged.get(leaseKey(lease.corridorId,second));
                if(other!=null&&other.entryJunction.equals(lease.entryJunction)&&followable(other))return true;
            }
            return false;
        }
    }
    /** Old saves can contain opposing owners too far apart for a near-nose check.
     * Their broad corridor protection prevents either from approaching, so the
     * physically occupied, unchanged opposing leases are the conflict itself.
     */
    boolean opposingEntered(String first,String second) {
        synchronized(network) {
            RailNetwork.Train a=network.trains.get(first),b=network.trains.get(second);
            if(a==null||b==null)return false;
            for(Lease own:leases.values())if(own.trainName.equals(first)&&own.entered&&followable(own)&&occupies(a,own.nodes)) {
                Lease other=leases.get(leaseKey(own.corridorId,second));
                if(other!=null&&other.entered&&followable(other)&&!own.entryJunction.equals(other.entryJunction)&&occupies(b,other.nodes))return true;
            }
            return false;
        }
    }
    /** A waiting opposite service cannot enter until the receiving loop has one clear circulation. */
    Map<String,String> receivingYieldCandidates() {
        synchronized(network) {
            topology();Map<String,String> result=new LinkedHashMap<String,String>();
            for(Corridor corridor:corridors.values()) {
                List<Lease> active=cohort(corridor.id);RailNetwork.Train requester=null;String entry=null;
                if(!active.isEmpty()) {
                    boolean stalled=true;
                    for(Lease member:active) {
                        RailNetwork.Train owner=network.trains.get(member.trainName);
                        if(owner==null||!owner.powered||!owner.automated||owner.speed>.000001||owner.dwellRemaining>.000001
                                ||owner.passengerStopRail!=null||!followable(member)
                                ||(entry!=null&&!entry.equals(member.entryJunction))) { stalled=false;break; }
                        requester=owner;entry=member.entryJunction;
                    }
                    if(!stalled)continue;
                } else requester=network.trains.get(oldest(corridor.id,""));
                if(requester==null||!requester.powered||!requester.automated||requester.speed>.000001
                        ||requester.dwellRemaining>.000001||requester.passengerStopRail!=null)continue;
                String leading=network.signalLeadingRail(requester);
                if(entry==null) {
                    if(loopAt(corridor.first,corridor.neighbor(corridor.first)).contains(leading))entry=corridor.first;
                    else if(loopAt(corridor.last,corridor.neighbor(corridor.last)).contains(leading))entry=corridor.last;
                }
                if(entry==null)continue;
                String exit=corridor.other(entry),incoming=corridor.neighbor(exit);
                Set<String> loop=loopAt(exit,incoming);
                List<RailNetwork.Train> candidates=new ArrayList<RailNetwork.Train>();boolean moving=false;
                for(RailNetwork.Train other:network.trains.values())if(other!=requester&&loop.contains(network.signalLeadingRail(other))) {
                    if(other.speed>.000001) { moving=true;break; }
                    if(other.powered&&other.automated&&!other.route.isEmpty()) {
                        if(other.dwellRemaining>.000001||other.passengerStopRail!=null) { moving=true;break; }
                        candidates.add(other);
                    }
                }
                if(moving||candidates.isEmpty())continue;
                Set<String> permitted=permittedOwners(corridor.id,active);permitted.add(requester.name);
                double required=active.isEmpty()?(requester.carIds.size()-1)*4.0+8.0:0;
                for(Lease member:members(corridor.id,active)) {
                    RailNetwork.Train owner=network.trains.get(member.trainName);if(owner!=null)required+=(owner.carIds.size()-1)*4.0+8.0;
                }
                if(receivingBerth(requester,exit,incoming,required,permitted,null)!=null)continue;
                final Map<String,Double> distance=new HashMap<String,Double>();
                for(RailNetwork.Train candidate:candidates)distance.put(candidate.name,TrafficRouter.distance(network,
                        TrafficRouter.path(network,candidate,network.signalLeadingRail(candidate),network.signalIncomingRail(candidate),exit,false)));
                Collections.sort(candidates,(a,b)->{
                    int order=Double.compare(distance.get(a.name),distance.get(b.name));return order!=0?order:a.name.compareTo(b.name);
                });
                for(RailNetwork.Train candidate:candidates)result.put(candidate.name,requester.name);
            }
            return result;
        }
    }
    /** A sole owner may back out; its changed traction closes the corridor to followers. */
    public void requireReversalSafe(String trainName) {
        requireReversalSafe(trainName,false);
    }
    void requireRecoveryReversalSafe(String trainName) { requireReversalSafe(trainName,true); }
    private void requireReversalSafe(String trainName,boolean escapeOpposite) {
        synchronized(network) {
            for(Lease lease:leases.values())if(lease.trainName.equals(trainName)) {
                RailNetwork.Train owner=network.trains.get(trainName);
                if(owner!=null&&(lease.entered||occupies(owner,lease.nodes)))
                    for(Lease other:cohort(lease.corridorId))if(!other.trainName.equals(trainName)
                            &&(!escapeOpposite||(other.entryJunction.equals(lease.entryJunction)
                                    &&(other.entered||occupies(network.trains.get(other.trainName),other.nodes))))) {
                        if(escapeOpposite&&owner.direction==lease.tractionDirection&&retreatBehind(owner,other,lease))continue;
                        throw new IllegalArgumentException("Clear the single-track connection before reversing; other trains are following this convoy.");
                    }
            }
            for(Lease lease:discharged.values())if(lease.trainName.equals(trainName)&&!cohort(lease.corridorId).isEmpty()) {
                RailNetwork.Train owner=network.trains.get(trainName);
                if(owner!=null&&intersects(lease.resources,network.signalOccupiedResources(owner)))
                    throw new IllegalArgumentException("Keep moving clear of the receiving exit before reversing; other trains are following this convoy.");
            }
        }
    }
    private boolean escapingOwner(String trainName,Lease other) {
        Lease own=leases.get(leaseKey(other.corridorId,trainName));RailNetwork.Train train=network.trains.get(trainName);
        if(own==null||!own.entered||train==null||train.direction==own.tractionDirection)return false;
        for(Lease member:cohort(other.corridorId))if(!member.trainName.equals(trainName)&&member.entryJunction.equals(own.entryJunction)
                &&(member.entered||occupies(network.trains.get(member.trainName),member.nodes))&&!retreatBehind(train,member,own))return false;
        return true;
    }
    /** Recovery may unwind the actual rear of a convoy. The front must never
     * reverse into its followers; ordinary manual reversal remains unchanged. */
    private boolean retreatBehind(RailNetwork.Train train,Lease ahead,Lease own) {
        if(!ahead.entryJunction.equals(own.entryJunction))return false;
        RailNetwork.Train other=network.trains.get(ahead.trainName);Corridor corridor=corridors.get(own.corridorId);
        if(other==null||corridor==null)return false;
        double[] rear=progressBounds(train,corridor,own.entryJunction),front=progressBounds(other,corridor,own.entryJunction);
        return rear!=null&&front!=null&&rear[1]+.9<=front[0]+.000001;
    }
    private double[] progressBounds(RailNetwork.Train train,Corridor corridor,String entry) {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(RailNetwork.Pose pose:train.poses) {
            double best=Double.POSITIVE_INFINITY,progress=0,offset=0;
            for(int i=1;i<corridor.path.size();i++) {
                RailNetwork.Node a=network.nodes.get(corridor.path.get(i-1)),b=network.nodes.get(corridor.path.get(i));
                double dx=b.worldX()-a.worldX(),dy=b.worldY()-a.worldY(),length=Math.hypot(dx,dy);
                double ratio=Math.max(0,Math.min(1,((pose.x-a.worldX())*dx+(pose.y-a.worldY())*dy)/(length*length)));
                double gap=Math.hypot(pose.x-a.worldX()-ratio*dx,pose.y-a.worldY()-ratio*dy);
                if(gap<best) { best=gap;progress=offset+ratio*length; }offset+=length;
            }
            if(!entry.equals(corridor.first))progress=offset-progress;
            min=Math.min(min,progress);max=Math.max(max,progress);
        }
        return Double.isFinite(min)&&Double.isFinite(max)?new double[]{min,max}:null;
    }
    /** Receiving space is planned for the admitted consist, including its last wagon. */
    public void requireConsistExpansionSafe(String trainName) {
        synchronized(network) {
            RailNetwork.Train owner=network.trains.get(trainName);
            if(owner!=null&&owner.stationTurnback!=null)throw new IllegalArgumentException("Complete the local station turnaround before coupling another wagon.");
            for(Lease lease:leases.values())if(lease.trainName.equals(trainName))
                throw new IllegalArgumentException("Clear the single-track connection before coupling another wagon; receiving space is reserved for this consist.");
            for(Lease lease:discharged.values())if(lease.trainName.equals(trainName)&&!cohort(lease.corridorId).isEmpty())
                throw new IllegalArgumentException("Wait for the following convoy to clear before coupling another wagon; its receiving space is reserved.");
        }
    }
    public void cancelUnused(String trainName) {
        synchronized(network) {
            for(String corridor:new ArrayList<String>(queues.keySet()))dequeue(corridor,trainName);
            RailNetwork.Train train=network.trains.get(trainName);
            Iterator<Lease> iterator=leases.values().iterator();
            while(iterator.hasNext()) {
                Lease lease=iterator.next();
                if(lease.trainName.equals(trainName)&&!lease.entered&&(train==null||!occupies(train,lease.nodes))) {
                    iterator.remove();refund(lease);changed();
                }
            }
        }
    }
    public void removeTrain(String trainName) {
        synchronized(network) {
            cancelUnused(trainName);
            Iterator<Lease> iterator=leases.values().iterator();
            while(iterator.hasNext()) { Lease lease=iterator.next();if(lease.trainName.equals(trainName)) { iterator.remove();if(!lease.entered)refund(lease);changed(); } }
            iterator=discharged.values().iterator();
            while(iterator.hasNext())if(iterator.next().trainName.equals(trainName)) { iterator.remove();changed(); }
            clearDischarged();
        }
    }
    public void updateClearance() {
        synchronized(network) {
            Iterator<Map.Entry<String,Lease>> iterator=leases.entrySet().iterator();
            while(iterator.hasNext()) {
                Map.Entry<String,Lease> entry=iterator.next();Lease lease=entry.getValue();
                RailNetwork.Train train=network.trains.get(lease.trainName);
                boolean occupying=train!=null&&(occupies(train,lease.nodes)||network.signalOccupiesNode(train,lease.exitNeighbor));
                if(train==null||(lease.entered&&!occupying)||(!lease.entered&&!occupying&&!train.powered)) {
                    iterator.remove();if(!lease.entered)refund(lease);
                    else if(train!=null&&followable(lease))discharged.put(entry.getKey(),lease);changed();
                }
                else if(!lease.entered&&occupying) { entry.setValue(new Lease(lease,true));changed(); }
            }
            clearDischarged();
            for(String corridor:new ArrayList<String>(queues.keySet()))
                for(String train:new ArrayList<String>(queues.get(corridor).keySet())) {
                    RailNetwork.Train owner=network.trains.get(train);
                    Request request=queues.get(corridor).get(train);
                    if(owner==null||!owner.powered||leftApproach(owner,request))dequeue(corridor,train);
                }
        }
    }
    public void requireTrackEditable(String rail) {
        synchronized(network) {
            for(RailNetwork.Train owner:network.trains.values())if(activeStationTurnback(owner)&&owner.stationTurnback.resources.contains("N:"+rail))
                throw new IllegalArgumentException("Track is reserved for "+owner.name+" to complete its local station turnaround.");
            for(Lease lease:leases.values())if(lease.resources.contains("N:"+rail))
                throw new IllegalArgumentException("Track is reserved for train "+lease.trainName+" to clear the single-track connection.");
        }
    }
    public String queueStatus(String trainName) {
        synchronized(network) {
            RailNetwork.Train owner=network.trains.get(trainName);
            if(owner!=null&&owner.stationTurnback!=null)return (owner.stationTurnback.departing?"Leaving forward through ":"Local station turnaround via ")+owner.stationTurnback.junction+".";
            String recovery=network.trafficRecoveryStatus(trainName);if(!recovery.isEmpty())return recovery;
            for(Lease lease:leases.values())if(lease.trainName.equals(trainName))
                return lease.entered?"Crossing single track; receiving exit reserved.":"Single-track crossing reserved; receiving exit clear.";
            for(Map.Entry<String,LinkedHashMap<String,Request>> queue:queues.entrySet())if(queue.getValue().containsKey(trainName)) {
                String side=queue.getValue().get(trainName).entry;int position=1;
                for(Map.Entry<String,Request> request:queue.getValue().entrySet()) { if(request.getKey().equals(trainName))break;if(request.getValue().entry.equals(side))position++; }
                List<Lease> active=cohort(queue.getKey());
                Phase phase=phases.get(queue.getKey());
                return "Waiting for single track (direction queue "+position+")"+(active.isEmpty()?".":"; "+active.size()+" train(s) in the active convoy.")
                        +(phase==null?"":" Turn admissions: "+Math.min(phase.admissions,DIRECTIONAL_BATCH_LIMIT)+"/"+DIRECTIONAL_BATCH_LIMIT+" under opposing traffic.");
            }
            for(Map.Entry<String,LinkedHashSet<String>> entry:turnbackQueues.entrySet())if(entry.getValue().contains(trainName)) {
                LinkedHashSet<String> queue=entry.getValue();
                int position=1;for(String pending:queue) { if(pending.equals(trainName))break;position++; }
                String previous=stationQueueIncoming(owner,entry.getKey());
                return "Station branch queue "+position+(previous!=null&&stationQueueMustCirculate(owner,entry.getKey(),previous)
                        ?"; clearing the junction approach so an earlier or returning train can pass."
                        :"; waiting at the junction signal for the station branch.");
            }
            return "";
        }
    }
    /** Rebuild direction protection from the complete saved bodies before fresh grants. */
    public void restoreOccupiedCorridors() {
        synchronized(network) {
            topology();
            for(Corridor corridor:corridors.values())for(RailNetwork.Train train:network.trains.values()) {
                String key=leaseKey(corridor.id,train.name);if(leases.containsKey(key))continue;
                boolean interior=false;
                for(int i=1;i+1<corridor.path.size();i++)if(network.signalOccupiesNode(train,corridor.path.get(i))) { interior=true;break; }
                if(!interior)continue;
                String entry=network.signalCorridorEntry(train,corridor.path);
                if(entry==null)continue; // Body occupancy still makes every incompatible actual block RED.
                String exit=corridor.other(entry),incoming=corridor.neighbor(exit),next=null;
                Set<String> permitted=new HashSet<String>();permitted.add(train.name);
                double required=(train.carIds.size()-1)*4.0+8.0;
                for(RailNetwork.Train other:network.trains.values())if(other!=train&&entry.equals(network.signalCorridorEntry(other,corridor.path))) {
                    boolean inside=false;for(int i=1;i+1<corridor.path.size();i++)if(network.signalOccupiesNode(other,corridor.path.get(i))) { inside=true;break; }
                    if(inside) { permitted.add(other.name);required+=(other.carIds.size()-1)*4.0+8.0; }
                }
                String fixedExit=null;
                for(Lease member:cohort(corridor.id))if(member.entryJunction.equals(entry)) { fixedExit=member.exitNeighbor;break; }
                List<String> berth=receivingBerth(train,exit,incoming,required,permitted,fixedExit);
                if(berth!=null)next=berth.get(1);
                else {
                    List<String> oriented=receivingExits(train,exit,incoming);
                    if(!oriented.isEmpty())next=fixedExit!=null&&oriented.contains(fixedExit)?fixedExit:oriented.get(0);
                    else try { next=network.signalIntendedNext(train,exit,incoming,train.routeIndex); }
                    catch(IllegalArgumentException unavailable) { /* Retain physical protection even if its route was edited. */ }
                }
                if(next==null||next.equals(incoming)||!network.nodes.get(exit).links.contains(next))
                    for(String arm:network.nodes.get(exit).links)if(!arm.equals(incoming)) { next=arm;break; }
                if(next==null)continue;
                Set<String> crossing=network.signalRouteResources(corridor.path);
                for(String arm:network.nodes.get(exit).links)if(!arm.equals(incoming))crossing.add("N:"+arm);
                if(berth==null)berth=Arrays.asList(exit,next);
                crossing.addAll(network.signalRouteResources(berth));
                leases.put(key,new Lease(train,corridor,entry,berth,crossing,true));
                Phase phase=phases.get(corridor.id);
                if(phase==null||!phase.entry.equals(entry))phases.put(corridor.id,new Phase(entry,1));
                else phases.put(corridor.id,new Phase(entry,phase.admissions+1));
                discharged.remove(key);changed();
            }
        }
    }
    public Checkpoint checkpoint() {
        synchronized(network) { if(cachedCheckpoint==null)cachedCheckpoint=new Checkpoint(this);return cachedCheckpoint; }
    }
    public void restore(Checkpoint checkpoint) {
        synchronized(network) {
            if(checkpoint==null)throw new IllegalArgumentException("A traffic checkpoint is required.");
            leases.clear();leases.putAll(checkpoint.leases);queues.clear();queues.putAll(copyQueues(checkpoint.queues));
            discharged.clear();discharged.putAll(checkpoint.discharged);
            phases.clear();phases.putAll(checkpoint.phases);
            turnbackQueues.clear();turnbackQueues.putAll(copyTurnbackQueues(checkpoint.turnbackQueues));
            sequence=checkpoint.sequence;changed();
        }
    }
    private Admission denied(String reason) { return new Admission(false,reason,Collections.<Lease>emptyList(),topology,revision); }
    private void enqueue(String corridor,String train,String entry) {
        LinkedHashMap<String,Request> queue=queues.get(corridor);
        if(queue==null) { queue=new LinkedHashMap<String,Request>();queues.put(corridor,queue); }
        Request previous=queue.get(train);
        if(previous==null||!previous.entry.equals(entry)) {
            RailNetwork.Train owner=network.trains.get(train);String approach=null;
            if(owner!=null) {
                String leading=network.signalLeadingRail(owner),incoming=network.signalIncomingRail(owner);
                if(entry.equals(leading))approach=incoming;
                else {
                    List<String> toward=TrafficRouter.path(network,owner,leading,incoming,entry,false);
                    if(toward.size()>1)approach=toward.get(toward.size()-2);
                }
            }
            queue.remove(train);queue.put(train,new Request(entry,approach,++sequence));changed();
        }
    }
    private void dequeue(String corridor,String train) {
        LinkedHashMap<String,Request> queue=queues.get(corridor);if(queue==null)return;
        if(queue.remove(train)!=null)changed();if(queue.isEmpty())queues.remove(corridor);
    }
    private String oldest(String corridor,String requester) {
        LinkedHashMap<String,Request> queue=queues.get(corridor);if(queue==null)return null;
        for(String name:queue.keySet()) {
            RailNetwork.Train train=network.trains.get(name);
            if(train!=null&&(train.powered||name.equals(requester)))return name;
        }
        return null;
    }
    private Request request(String corridor,String train) {
        Map<String,Request> queue=queues.get(corridor);return queue==null?null:queue.get(train);
    }
    /** Circulating to clear the receiving points relinquishes a physical queue place. */
    private boolean leftApproach(RailNetwork.Train train,Request request) {
        if(request==null||request.approach==null)return false;
        String leading=network.signalLeadingRail(train),incoming=network.signalIncomingRail(train);
        RailNetwork.Node entry=network.nodes.get(request.entry);
        return entry!=null&&request.entry.equals(incoming)&&entry.links.contains(leading)&&!leading.equals(request.approach);
    }
    private String oldestOnSide(String corridor,String entry,String requester) {
        Map<String,Request> queue=queues.get(corridor);if(queue==null)return null;
        for(Map.Entry<String,Request> pending:queue.entrySet())if(pending.getValue().entry.equals(entry)) {
            RailNetwork.Train train=network.trains.get(pending.getKey());
            if(train!=null&&(train.powered||train.name.equals(requester)))return train.name;
        }
        return null;
    }
    private void refund(Lease lease) {
        Phase phase=phases.get(lease.corridorId);
        if(phase!=null&&phase.entry.equals(lease.entryJunction)&&phase.admissions>0)
            phases.put(lease.corridorId,new Phase(phase.entry,phase.admissions-1));
    }
    /** Future engine-leading circulation, even while a backing station arrival has priority. */
    private List<String> receivingExits(RailNetwork.Train train,String junction,String previous) {
        List<String> exits=new ArrayList<String>(network.nodes.get(junction).links);exits.remove(previous);
        try {
            // This is only a preference among receiving arms, not a request
            // for another dispatch decision. Re-entering preferredExit here
            // made FIFO turn selection depend on planner recursion depth.
            List<String> topological=train.automated&&!train.route.isEmpty()
                    ?TrafficRouter.path(network,train,junction,previous,train.route.get(train.routeIndex),false):Collections.<String>emptyList();
            String desired=topological.size()>1?topological.get(1):train.automated?null:network.signalIntendedNext(train,junction,previous,train.routeIndex);
            if(exits.remove(desired))exits.add(0,desired);
        } catch(IllegalArgumentException unavailable) { /* Other physical exit may still provide a safe berth. */ }
        // Follow the circulation already established by a train on the receiving
        // loop. A locally empty opposite arm is not a safe head-on meeting place.
        Set<String> loop=loopAt(junction,previous);
        List<String> ring=simpleLoop(junction,previous);
        if(!ring.isEmpty()) {
            int physical=0;
            for(RailNetwork.Train other:network.trains.values())if(other!=train&&touchesLoop(other,loop)) {
                int orientation=network.signalLoopOrientation(other,ring);
                if(orientation==0)return Collections.emptyList();
                // A backing service leaving this loop must first drain in its
                // present travel direction. A local station pivot restores
                // the physical engine-leading circulation instead.
                if(other.direction<0&&!backingStationInLoop(other,loop))orientation=-orientation;
                if(physical!=0&&physical!=orientation)return Collections.emptyList();
                physical=orientation;
            }
            if(physical!=0) {
                boolean backingArrival=backingStationInLoop(train,loop);
                Iterator<String> iterator=exits.iterator();
                while(iterator.hasNext()) {
                    String exit=iterator.next();int travel=exit.equals(ring.get(1))?1:exit.equals(ring.get(ring.size()-1))?-1:0;
                    if((backingArrival?-travel:travel)!=physical)iterator.remove();
                }
            }
            return exits;
        }
        String circulation=null;
        for(RailNetwork.Train other:network.trains.values())if(other!=train) {
            String leading=network.signalLeadingRail(other);if(leading==null||!loop.contains(leading)||leading.equals(junction))continue;
            List<String> ahead=TrafficRouter.path(network,other,leading,network.signalIncomingRail(other),junction,false);
            if(ahead.size()<2)continue;
            String arriving=ahead.get(ahead.size()-2),following=null;
            boolean turnsAtStation=backingStationInLoop(other,loop);
            boolean reverseEntry=backingStationInLoop(train,loop);
            for(String exit:exits)if(turnsAtStation!=reverseEntry?exit.equals(arriving):!exit.equals(arriving)) {
                if(following!=null) { following=null;break; }following=exit;
            }
            if(following==null)continue;
            if(circulation!=null&&!circulation.equals(following))return Collections.emptyList();
            circulation=following;
        }
        if(circulation!=null) { exits.clear();exits.add(circulation); }
        return exits;
    }
    private List<String> receivingBerth(RailNetwork.Train train,String junction,String previous,double required,Set<String> permitted,String fixedExit) {
        List<String> exits=receivingExits(train,junction,previous);
        if(fixedExit!=null) {
            if(!exits.contains(fixedExit))return null;
            exits.clear();exits.add(fixedExit);
        }
        for(String exit:exits) {
            List<String> berth=new ArrayList<String>();berth.add(junction);
            Set<String> visited=new HashSet<String>();visited.add(junction);
            String last=junction,current=exit;double length=0;
            while(current!=null&&visited.add(current)) {
                RailNetwork.Node a=network.nodes.get(last),b=network.nodes.get(current);
                if(b==null)break;
                double step=Math.hypot(b.worldX()-a.worldX(),b.worldY()-a.worldY());
                if(berth.size()==1)length+=step;
                else {
                    RailNetwork.Node before=network.nodes.get(berth.get(berth.size()-2));
                    double oldHalf=Math.hypot(a.worldX()-before.worldX(),a.worldY()-before.worldY())*.5;
                    length+=curveLength(before,a,b)-oldHalf+step*.5;
                }
                berth.add(current);
                Set<String> resources=network.signalRouteResources(berth);
                if(foreignOccupant(train.name,resources,permitted)!=null||network.signals().conflictsBlockResourcesExcept(train.name,resources,permitted))break;
                if(length>=required)return berth;
                if(b.links.size()!=2)break;
                String next=null;for(String candidate:b.links)if(!candidate.equals(last))next=candidate;
                last=current;current=next;
            }
        }
        return null;
    }
    /** Keep the approach behind a backing arrival clear until its station pivot. */
    private String stationOrientationHold(RailNetwork.Train requester,String junction,String incoming) {
        Set<String> loop=loopAt(junction,incoming);
        for(RailNetwork.Train other:network.trains.values())if(other!=requester&&other.powered
                &&touchesLoop(other,loop)&&backingStationInLoop(other,loop)) {
                return "Waiting for "+other.name+" to finish its backing station arrival and resume forward travel.";
        }
        return null;
    }
    private boolean touchesLoop(RailNetwork.Train train,Set<String> loop) {
        for(RailNetwork.Pose pose:train.poses)if(loop.contains(pose.railName))return true;
        for(String rail:loop)if(network.signalOccupiesNode(train,rail))return true;
        return false;
    }
    private boolean activeStationTurnback(RailNetwork.Train train) {
        return train.stationTurnback!=null&&(train.stationTurnback.departing||train.direction<0);
    }
    /** A tree attached to a loop has no forward route back out: its service
     * must turn back. Keep that return neck clear until its owner is forward
     * and wholly on the loop, instead of following it into a head-on trap. */
    private String turnbackOwner(String junction,RailNetwork.Train requester) {
        Set<String> yard=turnbackYards.get(junction);if(yard==null)return null;
        Set<String> ring=new HashSet<String>(simpleLoop(junction,loopApproaches.get(junction)));
        for(RailNetwork.Train other:network.trains.values())if(other!=requester) {
            if(touchesLoop(other,yard))return other.name;
            if(other.direction<0&&touchesLoop(other,ring)&&returningFromYard(other,ring,yard))return other.name;
        }
        return null;
    }
    /** Keep a waiting place through extra circuits. Without this order, the
     * same two arrivals can repeatedly beat a third service to the branch. */
    private String turnbackQueueOwner(String junction,RailNetwork.Train requester) {
        Set<String> yard=turnbackYards.get(junction);if(yard==null)return null;
        Set<String> loop=new HashSet<String>(simpleLoop(junction,loopApproaches.get(junction)));
        LinkedHashSet<String> queue=turnbackQueues.get(junction);
        if(queue==null) { queue=new LinkedHashSet<String>();turnbackQueues.put(junction,queue); }
        for(Iterator<String> pending=queue.iterator();pending.hasNext();) {
            RailNetwork.Train train=network.trains.get(pending.next());
            if(!waitingForTurnback(train,loop,yard)) { pending.remove();changed(); }
        }
        if(waitingForTurnback(requester,loop,yard)&&queue.add(requester.name))changed();
        // Admitted bodies must drain into the branch even when the next
        // service becomes first. Physical ownership protects their return.
        if(touchesLoop(requester,yard))return requester.name;
        return queue.isEmpty()?null:queue.iterator().next();
    }
    private boolean waitingForTurnback(RailNetwork.Train train,Set<String> loop,Set<String> yard) {
        return train!=null&&train.powered&&train.automated&&!train.route.isEmpty()
                &&yard.contains(train.route.get(train.routeIndex))
                &&!touchesLoop(train,yard)&&touchesLoop(train,loop);
    }
    private boolean hasTurnbackTurn(String junction,RailNetwork.Train train) {
        String owner=turnbackQueueOwner(junction,train);return owner==null||owner.equals(train.name);
    }
    private String stationQueueIncoming(RailNetwork.Train train,String junction) {
        if(train==null)return null;
        List<String> approach=TrafficRouter.path(network,train,network.signalLeadingRail(train),network.signalIncomingRail(train),junction,false);
        return approach.size()>1?approach.get(approach.size()-2):null;
    }
    /** Waiting is the normal FIFO policy. Circulate only to free physical
     * approach space needed by an earlier waiter or an actual backing return. */
    private boolean stationQueueMustCirculate(RailNetwork.Train train,String junction,String previous) {
        if(network.signalOccupiesNode(train,junction))return true;
        LinkedHashSet<String> queue=turnbackQueues.get(junction);
        String first=queue==null||queue.isEmpty()?null:queue.iterator().next();
        RailNetwork.Train head=network.trains.get(first);
        if(head!=null&&head!=train) {
            List<String> approach=TrafficRouter.path(network,head,network.signalLeadingRail(head),network.signalIncomingRail(head),junction,false);
            if(intersects(network.signalRouteResources(approach),network.signalOccupiedResources(train)))return true;
        }
        Set<String> yard=turnbackYards.get(junction);String outside=loopApproaches.get(junction);
        Set<String> ring=new HashSet<String>(simpleLoop(junction,outside));
        for(RailNetwork.Train returning:network.trains.values())if(returning!=train) {
            // Older saves may already have stopped closer than the new hold.
            // Let that body drain rather than foul even the other return arm.
            if(returning.powered&&returning.automated&&!returning.route.isEmpty()
                    &&ring.contains(returning.route.get(returning.routeIndex))&&touchesLoop(returning,yard)
                    &&returning.dwellRemaining<=.000001&&(returning.stationTurnback==null||returning.stationTurnback.departing)) {
                RailNetwork.Node points=network.nodes.get(junction);
                for(RailNetwork.Pose body:train.poses)
                    if(Math.hypot(body.x-points.worldX(),body.y-points.worldY())<RailNetwork.TRAIN_CLEARANCE+.45)return true;
            }
            if(returning.stationTurnback!=null||returning.direction>=0||!returningFromYard(returning,ring,yard)
                    ||!(touchesLoop(returning,yard)||touchesLoop(returning,ring)))continue;
            List<String> exits=receivingExits(returning,junction,outside);
            if(exits.isEmpty()||exits.get(0).equals(previous)) {
                List<String> approach=TrafficRouter.path(network,train,network.signalLeadingRail(train),network.signalIncomingRail(train),junction,false);
                double required=24+4.0*(returning.carIds.size()+train.carIds.size()-2);
                if(!approach.isEmpty()&&TrafficRouter.distance(network,approach)<=required)return true;
            }
        }
        return false;
    }
    /** A waiting service may have forecast another lap before a returner
     * cleared, or need to vacate its hold for a newly backing arrival. */
    private void refreshStationQueueExits() {
        for(RailNetwork.Train train:network.trains.values())if(train.powered&&train.stationTurnback==null)
            for(String junction:turnbackYards.keySet()) {
                String old=network.unenteredExit(train,junction);if(old==null)continue;
                String previous=stationQueueIncoming(train,junction);
                if(!stationQueueApproach(train,junction,previous)||network.signalOccupiesNode(train,junction)
                        ||!network.signals().canReplanUnenteredJunction(train.name,junction))continue;
                String desired=preferredExit(train.name,junction,previous);
                if(desired==null)desired=loopApproaches.get(junction);
                if(!old.equals(desired)) {
                    network.signals().cancelUnenteredJunction(train.name,junction);
                    network.resetUnenteredExit(train.name,junction,desired);
                }
            }
    }
    /** The return maneuver ends at a physical clearance milestone, not at a
     * timeout: restore the engine end once every car is clear of the points. */
    List<RailNetwork.Train> loopForwardCohort(RailNetwork.Train train) {
        topology();
        if(train.direction>=0||!train.automated||train.route.isEmpty())return Collections.emptyList();
        for(Map.Entry<String,Set<String>> entry:turnbackYards.entrySet()) {
            String junction=entry.getKey(),outside=loopApproaches.get(junction);
            List<String> ring=simpleLoop(junction,outside);
            Set<String> loop=new HashSet<String>(ring);
            if(!returningFromYard(train,loop,entry.getValue()))continue;
            List<RailNetwork.Train> cohort=new ArrayList<RailNetwork.Train>();boolean clear=true;
            for(RailNetwork.Train other:network.trains.values())if(other.direction<0
                    &&returningFromYard(other,loop,entry.getValue())
                    &&(touchesLoop(other,loop)||touchesLoop(other,entry.getValue()))) {
                cohort.add(other);
                if(!other.powered||other.dwellRemaining>.000001||other.passengerStopRail!=null
                        ||network.signalOccupiesNode(other,junction)||network.signalOccupiesNode(other,outside)||other.poses.isEmpty())clear=false;
                for(RailNetwork.Pose pose:other.poses)if(!ring.contains(pose.railName)||pose.railName.equals(junction)
                        ||Math.hypot(pose.x-network.nodes.get(junction).worldX(),pose.y-network.nodes.get(junction).worldY())<12)clear=false;
            }
            if(!clear||!cohort.contains(train))continue;
            int orientation=network.signalLoopOrientation(train,ring);boolean compatible=orientation!=0;
            for(RailNetwork.Train other:network.trains.values())if(touchesLoop(other,loop))
                if(network.signalLoopOrientation(other,ring)!=orientation||(other.direction<0&&!cohort.contains(other)))compatible=false;
            if(compatible)return cohort;
        }
        return Collections.emptyList();
    }
    private boolean returningFromYard(RailNetwork.Train train,Set<String> ring,Set<String> yard) {
        if(!train.automated||train.route.isEmpty())return false;
        if(ring.contains(network.loopExitIntent(train)))return false;
        String target=train.route.get(train.routeIndex),previous=train.route.get((train.routeIndex+train.route.size()-1)%train.route.size());
        return ring.contains(target)&&yard.contains(previous)
                ||network.isRecoveringTraffic(train.name)&&yard.contains(target)&&ring.contains(previous);
    }
    /** This is a directional maneuver envelope, not a shared block authority.
     * Reserve enough clear space for every returning body and its following
     * gaps, while actual signal blocks continue to be acquired exclusively. */
    private LoopTurnback loopTurnback(String junction) {
        Set<String> yard=turnbackYards.get(junction);if(yard==null)return null;
        String outside=loopApproaches.get(junction);List<String> ring=simpleLoop(junction,outside);
        Set<String> loop=new HashSet<String>(ring),members=new LinkedHashSet<String>();RailNetwork.Train leader=null;int cars=0;
        for(RailNetwork.Train train:network.trains.values())if(train.direction<0&&returningFromYard(train,loop,yard)
                &&(touchesLoop(train,loop)||touchesLoop(train,yard))) {
            members.add(train.name);cars+=train.carIds.size();if(leader==null)leader=train;
        }
        if(leader==null)return null;
        List<String> exits=receivingExits(leader,junction,outside);if(exits.isEmpty())return null;
        int direction=exits.get(0).equals(ring.get(1))?1:-1,index=0;double length=0,required=20+4.0*(cars-1);
        List<String> path=new ArrayList<String>();path.add(junction);
        for(int step=1;step<ring.size()&&length<required;step++) {
            int next=(index+direction+ring.size())%ring.size();
            RailNetwork.Node a=network.nodes.get(ring.get(index)),b=network.nodes.get(ring.get(next));
            length+=Math.hypot(b.worldX()-a.worldX(),b.worldY()-a.worldY());path.add(b.name);index=next;
        }
        return new LoopTurnback(members,path,network.signalRouteResources(path));
    }
    /** Ordered physical ring, excluding the bridge; complex networks retain the
     * general directional planner instead of guessing a global circulation. */
    private List<String> simpleLoop(String junction,String previous) {
        String key=boundaryKey(junction,network.signalSide(junction,previous));
        List<String> cached=simpleLoops.get(key);if(cached!=null)return cached;
        List<String> arms=new ArrayList<String>(network.nodes.get(junction).links);arms.remove(previous);
        List<String> ring=new ArrayList<String>();ring.add(junction);
        if(arms.size()==2) {
            String before=junction,current=arms.get(0);Set<String> seen=new HashSet<String>();seen.add(junction);
            while(!current.equals(junction)&&seen.add(current)) {
                RailNetwork.Node node=network.nodes.get(current);
                if(node.links.size()!=2)break;
                ring.add(current);String next=null;for(String link:node.links)if(!link.equals(before))next=link;
                if(next==null)break;before=current;current=next;
            }
            if(!current.equals(junction)||ring.size()<3||!before.equals(arms.get(1)))ring.clear();
        } else ring.clear();
        cached=Collections.unmodifiableList(ring);simpleLoops.put(key,cached);return cached;
    }
    private boolean backingStationInLoop(RailNetwork.Train train,Set<String> loop) {
        if(train.direction>=0||!train.automated||train.route.isEmpty())return false;
        if(loop.contains(network.loopExitIntent(train)))return false;
        for(Map.Entry<String,Set<String>> entry:turnbackYards.entrySet())
            if(loop.contains(entry.getKey())&&returningFromYard(train,loop,entry.getValue()))return true;
        for(String rail:loop)if(network.isTrafficRecoveryStop(train,rail))return true;
        if(loop.contains(train.route.get(train.routeIndex)))return true;
        String arrived=train.route.get((train.routeIndex+train.route.size()-1)%train.route.size());
        return loop.contains(arrived)&&network.signalOccupiesNode(train,arrived);
    }
    /** An outbound yield keeps its travel direction until its real outside
     * station. It must not acquire an incoming returner's loop pivot envelope. */
    boolean validLoopExitIntent(RailNetwork.Train train,String junction) {
        topology();Set<String> yard=turnbackYards.get(junction);
        return junction!=null&&yard!=null&&train.automated&&!train.route.isEmpty()
                &&train.direction<0&&network.isRecoveringTraffic(train.name)
                &&!network.hasRecoveryCircuit(train)&&train.stationTurnback==null
                &&yard.contains(train.route.get(train.routeIndex));
    }
    /** Pure intent selection for prospective and committed reversals. The
     * movement controller records it only after the complete yield is safe. */
    String directLoopExit(RailNetwork.Train train) {
        topology();if(train.direction>=0||!train.automated||train.route.isEmpty())return null;
        for(Map.Entry<String,Set<String>> entry:turnbackYards.entrySet())
            if(entry.getValue().contains(train.route.get(train.routeIndex))&&whollyInRing(train,entry.getKey()))return entry.getKey();
        return null;
    }
    private boolean whollyInRing(RailNetwork.Train train,String junction) {
        List<String> ring=simpleLoop(junction,loopApproaches.get(junction));
        if(ring.isEmpty()||train.poses.isEmpty()||network.signalOccupiesNode(train,junction)
                ||network.signalOccupiesNode(train,loopApproaches.get(junction)))return false;
        for(RailNetwork.Pose pose:train.poses)if(!ring.contains(pose.railName))return false;
        return true;
    }
    /** Migrate the unambiguous old saved head-on yield after every resident is
     * available. Opposed engines already moving the same way must drain that
     * way; a genuine yard returner retains its existing station-pivot meaning. */
    void restoreLoopExitIntents() {
        topology();
        for(RailNetwork.Train train:network.trains.values())if(network.loopExitIntent(train)!=null
                &&!validLoopExitIntent(train,network.loopExitIntent(train)))network.loopExitIntent(train,null);
        Map<RailNetwork.Train,String> inferred=new LinkedHashMap<RailNetwork.Train,String>();
        for(Map.Entry<String,Set<String>> entry:turnbackYards.entrySet()) {
            String junction=entry.getKey();List<String> ring=simpleLoop(junction,loopApproaches.get(junction));
            Set<String> loop=new HashSet<String>(ring);int travel=0,physical=0;boolean mixed=false,coherent=true;
            for(RailNetwork.Train other:network.trains.values())if(touchesLoop(other,loop)) {
                int orientation=network.signalLoopOrientation(other,ring),motion=orientation*other.direction;
                if(orientation==0||!other.automated||other.route.isEmpty()||(travel!=0&&travel!=motion)) { coherent=false;break; }
                if(physical!=0&&physical!=orientation)mixed=true;
                physical=orientation;travel=motion;
            }
            if(!coherent||!mixed)continue;
            for(RailNetwork.Train train:network.trains.values())if(network.loopExitIntent(train)==null
                    &&validLoopExitIntent(train,junction)&&whollyInRing(train,junction)
                    &&loop.contains(train.route.get((train.routeIndex+train.route.size()-1)%train.route.size())))inferred.put(train,junction);
        }
        for(Map.Entry<RailNetwork.Train,String> intent:inferred.entrySet())network.loopExitIntent(intent.getKey(),intent.getValue());
    }
    /** Complete a local recovery circuit before trying a blocked remote
     * destination again. This is a maneuver waypoint, never an itinerary edit. */
    String recoveryStation(RailNetwork.Train train) {
        topology();String best=null;double distance=Double.POSITIVE_INFINITY;
        for(Map.Entry<String,String> approach:loopApproaches.entrySet()) {
            List<String> ring=simpleLoop(approach.getKey(),approach.getValue());if(ring.isEmpty())continue;
            Set<String> loop=new HashSet<String>(ring);
            if(train.direction>=0||!touchesLoop(train,loop)||loop.contains(train.route.get(train.routeIndex)))continue;
            int orientation=network.signalLoopOrientation(train,ring);boolean compatible=orientation!=0;
            for(RailNetwork.Train other:network.trains.values())if(other!=train&&touchesLoop(other,loop)
                    &&network.signalLoopOrientation(other,ring)!=orientation)compatible=false;
            if(!compatible)continue;
            for(String stop:train.route)if(loop.contains(stop)&&network.nodes.get(stop).links.size()==2&&!network.signalOccupiesNode(train,stop)) {
                List<String> path=TrafficRouter.path(network,train,network.signalLeadingRail(train),network.signalIncomingRail(train),stop,false);
                if(path.size()<2||!loop.containsAll(path))continue;
                double length=TrafficRouter.distance(network,path);
                if(length<distance) { distance=length;best=stop; }
            }
        }
        return best;
    }
    private Set<String> loopAt(String junction,String previous) {
        Set<String> loop=new HashSet<String>();Deque<String> pending=new ArrayDeque<String>();pending.add(junction);
        while(!pending.isEmpty()) {
            String rail=pending.removeFirst();if(!loop.add(rail))continue;
            for(String link:network.nodes.get(rail).links)
                if(!(rail.equals(junction)&&link.equals(previous))&&!(rail.equals(previous)&&link.equals(junction)))pending.add(link);
        }
        return loop;
    }
    private static double curveLength(RailNetwork.Node before,RailNetwork.Node center,RailNetwork.Node after) {
        double ax=(before.worldX()+center.worldX())*.5,ay=(before.worldY()+center.worldY())*.5;
        double bx=(after.worldX()+center.worldX())*.5,by=(after.worldY()+center.worldY())*.5;
        double px=ax,py=ay,length=0;
        for(int i=1;i<=64;i++) {
            double t=i/64.0,u=1-t,x=u*u*ax+2*u*t*center.worldX()+t*t*bx;
            double y=u*u*ay+2*u*t*center.worldY()+t*t*by;
            length+=Math.hypot(x-px,y-py);px=x;py=y;
        }
        return length;
    }
    private boolean occupies(RailNetwork.Train train,List<String> nodes) {
        if(train==null)return false;
        for(String rail:nodes)if(network.signalOccupiesNode(train,rail))return true;return false;
    }
    private String foreignOccupant(String trainName,Set<String> resources) {
        return foreignOccupant(trainName,resources,Collections.<String>emptySet());
    }
    private String foreignOccupant(String trainName,Set<String> resources,Set<String> permitted) {
        for(RailNetwork.Train other:network.trains.values())if(!other.name.equals(trainName)
                &&!permitted.contains(other.name)&&intersects(resources,network.signalOccupiedResources(other)))return other.name;
        return null;
    }
    private static String leaseKey(String corridor,String train) { return corridor+"\n"+train; }
    private List<Lease> cohort(String corridor) { List<Lease> result=new ArrayList<Lease>();for(Lease lease:leases.values())if(lease.corridorId.equals(corridor))result.add(lease);return result; }
    private Set<String> owners(List<Lease> cohort) { Set<String> result=new HashSet<String>();for(Lease lease:cohort)result.add(lease.trainName);return result; }
    private List<Lease> members(String corridor,List<Lease> active) {
        List<Lease> result=new ArrayList<Lease>(active);
        for(Lease old:discharged.values())if(corridor==null||old.corridorId.equals(corridor))result.add(old);
        return result;
    }
    private Set<String> permittedOwners(String corridor,List<Lease> active) {
        Set<String> result=new HashSet<String>();for(Lease member:members(corridor,active))if(followable(member))result.add(member.trainName);return result;
    }
    private void clearDischarged() {
        Iterator<Lease> iterator=discharged.values().iterator();
        while(iterator.hasNext())if(cohort(iterator.next().corridorId).isEmpty()) { iterator.remove();changed(); }
    }
    private boolean followable(Lease lease) { RailNetwork.Train train=network.trains.get(lease.trainName);return train!=null&&train.direction==lease.tractionDirection; }
    private static boolean intersects(Set<String> first,Set<String> second) {
        Set<String> small=first.size()<second.size()?first:second,large=small==first?second:first;
        for(String value:small)if(large.contains(value))return true;return false;
    }
    private static Map<String,LinkedHashMap<String,Request>> copyQueues(Map<String,LinkedHashMap<String,Request>> source) {
        Map<String,LinkedHashMap<String,Request>> result=new LinkedHashMap<String,LinkedHashMap<String,Request>>();
        for(Map.Entry<String,LinkedHashMap<String,Request>> entry:source.entrySet())result.put(entry.getKey(),new LinkedHashMap<String,Request>(entry.getValue()));
        return result;
    }
    private static Map<String,LinkedHashSet<String>> copyTurnbackQueues(Map<String,LinkedHashSet<String>> source) {
        Map<String,LinkedHashSet<String>> result=new LinkedHashMap<String,LinkedHashSet<String>>();
        for(Map.Entry<String,LinkedHashSet<String>> entry:source.entrySet())
            result.put(entry.getKey(),new LinkedHashSet<String>(entry.getValue()));
        return result;
    }
    private void changed() { revision++;cachedCheckpoint=null; }
    private static String boundaryKey(String rail,int approach) { return rail+"|"+approach; }
    private static String edge(String first,String second) { return first.compareTo(second)<0?first+"|"+second:second+"|"+first; }
    private void topology() {
        long current=network.topologyRevision();if(current==topology)return;
        topology=current;corridors.clear();boundaries.clear();activeRails.clear();simpleLoops.clear();loopApproaches.clear();turnbackYards.clear();
        Set<String> bridges=bridges();Set<String> cycleJunctions=new HashSet<String>();
        for(RailNetwork.Node node:network.nodes.values())if(node.links.size()>2) {
            int cyclic=0;for(String link:node.links)if(!bridges.contains(edge(node.name,link)))cyclic++;
            if(cyclic>=2)cycleJunctions.add(node.name);
        }
        for(String junction:cycleJunctions)for(String outside:network.nodes.get(junction).links)
            if(bridges.contains(edge(junction,outside))&&!simpleLoop(junction,outside).isEmpty())
                loopApproaches.put(junction,outside);
        for(Map.Entry<String,String> entry:loopApproaches.entrySet()) {
            Set<String> yard=loopAt(entry.getValue(),entry.getKey());boolean acyclic=true;
            for(String rail:yard)for(String next:network.nodes.get(rail).links)
                if(yard.contains(next)&&!bridges.contains(edge(rail,next)))acyclic=false;
            if(acyclic)turnbackYards.put(entry.getKey(),Collections.unmodifiableSet(yard));
        }
        for(String start:cycleJunctions)for(String first:network.nodes.get(start).links)if(bridges.contains(edge(start,first))) {
            List<String> path=new ArrayList<String>();path.add(start);
            String previous=start,rail=first;Set<String> visited=new HashSet<String>();visited.add(start);
            while(visited.add(rail)) {
                RailNetwork.Node node=network.nodes.get(rail);path.add(rail);
                if(cycleJunctions.contains(rail)) {
                    Corridor corridor=new Corridor(path);corridors.put(corridor.id,corridor);break;
                }
                if(node.links.size()!=2)break;
                String next=null;for(String link:node.links)if(!link.equals(previous))next=link;
                if(next==null||!bridges.contains(edge(rail,next)))break;
                previous=rail;rail=next;
            }
        }
        // A Y loop needs following blocks even when its stem ends at a
        // station or a branch, rather than reaching a second circulation loop.
        Deque<String> connected=new ArrayDeque<String>();connected.addAll(cycleJunctions);
        while(!connected.isEmpty()) {
            String rail=connected.removeFirst();if(!activeRails.add(rail))continue;
            connected.addAll(network.nodes.get(rail).links);
        }
        // Short internal holding blocks let an arriving train get fully off the
        // connector while another waits elsewhere on the receiving circle.
        for(String adjacent:activeRails) {
            RailNetwork.Node node=network.nodes.get(adjacent);if(node.links.size()!=2)continue;
            for(String from:node.links) {
                int approach=network.signalSide(adjacent,from);String key=boundaryKey(adjacent,approach);
                boundaries.put(key,new SignalSystem.Signal("dispatch_"+adjacent+"_"+approach,adjacent,approach,-10L));
            }
        }
        phases.keySet().retainAll(corridors.keySet());queues.keySet().retainAll(corridors.keySet());
        turnbackQueues.keySet().retainAll(turnbackYards.keySet());
        changed();
    }
    /** Iterative Tarjan traversal avoids stack overflow on a large player-built railway. */
    private Set<String> bridges() {
        Set<String> result=new HashSet<String>();Map<String,Integer> discovery=new HashMap<String,Integer>(),low=new HashMap<String,Integer>();
        int order=0;Deque<Frame> stack=new ArrayDeque<Frame>();
        for(RailNetwork.Node origin:network.nodes.values())if(!discovery.containsKey(origin.name)) {
            discovery.put(origin.name,++order);low.put(origin.name,order);stack.push(new Frame(origin,null));
            while(!stack.isEmpty()) {
                Frame frame=stack.peek();
                if(frame.next.hasNext()) {
                    String adjacent=frame.next.next();if(adjacent.equals(frame.parent))continue;
                    if(!discovery.containsKey(adjacent)) {
                        discovery.put(adjacent,++order);low.put(adjacent,order);stack.push(new Frame(network.nodes.get(adjacent),frame.rail));
                    } else low.put(frame.rail,Math.min(low.get(frame.rail),discovery.get(adjacent)));
                } else {
                    stack.pop();if(frame.parent==null)continue;
                    low.put(frame.parent,Math.min(low.get(frame.parent),low.get(frame.rail)));
                    if(low.get(frame.rail)>discovery.get(frame.parent))result.add(edge(frame.parent,frame.rail));
                }
            }
        }
        return result;
    }
}
