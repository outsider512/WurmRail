package org.wurmrail.core;

import java.util.*;
import java.util.logging.Logger;

/** Directional signal definitions and train-owned block authorities; visuals are deliberately external. */
public final class SignalSystem {
    private static final Logger LOG=Logger.getLogger(SignalSystem.class.getName());
    public enum Aspect { RED, GREEN }
    public enum ReservationState { RESERVED, OCCUPIED, CLEARING }
    public static final class Signal {
        public final String id,rail;
        /** Absolute connected side the controlled train enters FROM, N=0 through NW=7. */
        public final int approachDirection;
        public final long itemId;
        Signal(String id,String rail,int approach,long itemId) { this.id=id;this.rail=rail;approachDirection=approach;this.itemId=itemId; }
    }
    public static final class Reservation {
        public final String id,trainName,entrySignalId,exitSignalId;
        public final List<String> path,nodes;
        public final Map<String,Integer> junctionAlignments;
        public final ReservationState state;
        public final long createdSequence;
        private final Set<String> resources;
        // CLEARING retains protection, not permission to follow its historical path.
        // These physical locks may transfer alignment while the old track stays reserved.
        private final Map<String,Integer> junctionLocks;
        /** Current physical locks, including retained protection after a handover. */
        public Map<String,Integer> junctionLocks() { return junctionLocks; }
        Reservation(String id,String train,Signal entry,SignalRouteDiscovery.Route route,long sequence,Set<String> resources,ReservationState state) {
            this.id=id;trainName=train;entrySignalId=entry.id;exitSignalId=route.exitSignalId;
            path=route.path;nodes=route.nodes;junctionAlignments=route.junctionAlignments;
            createdSequence=sequence;this.resources=Collections.unmodifiableSet(new LinkedHashSet<String>(resources));this.state=state;
            junctionLocks=junctionAlignments;
        }
        Reservation(Reservation old,ReservationState state) {
            this(old,state,old.junctionLocks);
        }
        private Reservation(Reservation old,ReservationState state,Map<String,Integer> locks) {
            id=old.id;trainName=old.trainName;entrySignalId=old.entrySignalId;exitSignalId=old.exitSignalId;
            path=old.path;nodes=old.nodes;junctionAlignments=old.junctionAlignments;
            createdSequence=old.createdSequence;resources=old.resources;this.state=state;
            junctionLocks=Collections.unmodifiableMap(new LinkedHashMap<String,Integer>(locks));
        }
    }
    public static final class Status {
        public final Aspect aspect;
        public final String ownerTrain,reservationId,reason;
        public final Reservation reservation;
        public final List<String> route;
        Status(Reservation reservation,String reason) {
            this.reservation=reservation;this.reason=reason;
            aspect=reservation==null||reservation.state==ReservationState.CLEARING?Aspect.RED:Aspect.GREEN;
            ownerTrain=reservation==null?null:reservation.trainName;
            reservationId=reservation==null?null:reservation.id;
            route=reservation==null?Collections.<String>emptyList():reservation.path;
        }
    }
    public static final class RequestResult {
        public final boolean granted;
        public final String reason;
        public final Reservation reservation;
        RequestResult(Reservation reservation,String reason) { this.reservation=reservation;this.reason=reason;granted=reservation!=null; }
    }
    /** Definition transaction snapshot. Restoration is for a failed native save/edit transaction only. */
    public static final class DefinitionsCheckpoint {
        private final Map<String,Signal> definitions;
        private DefinitionsCheckpoint(Map<String,Signal> source) { definitions=new LinkedHashMap<String,Signal>(source); }
    }
    static final class Checkpoint {
        final Map<String,Signal> definitions;
        final Map<String,Reservation> reservations;
        final Map<String,String> reasons;
        final long sequence,definitionRevision;
        final TrafficControl.Checkpoint traffic;
        Checkpoint(SignalSystem s) {
            definitions=new LinkedHashMap<String,Signal>(s.definitions);reservations=new LinkedHashMap<String,Reservation>(s.reservations);
            reasons=new HashMap<String,String>(s.reasons);sequence=s.sequence;definitionRevision=s.definitionRevision;
            traffic=s.traffic.checkpoint();
        }
    }
    private final RailNetwork network;
    private final Map<String,Signal> definitions=new LinkedHashMap<String,Signal>();
    private final Map<String,Reservation> reservations=new LinkedHashMap<String,Reservation>();
    private final Map<String,String> reasons=new HashMap<String,String>();
    private long revision,definitionRevision,sequence;
    private Checkpoint cachedCheckpoint;
    private long cachedTrafficRevision=-1;
    private final TrafficControl traffic;
    SignalSystem(RailNetwork network) { this.network=network;traffic=new TrafficControl(network); }
    public long revision() { synchronized(network) { return revision; } }
    public long definitionRevision() { synchronized(network) { return definitionRevision; } }
    public Collection<Signal> definitions() { synchronized(network) { return Collections.unmodifiableList(new ArrayList<Signal>(definitions.values())); } }
    public Collection<Reservation> reservations() { synchronized(network) { return Collections.unmodifiableList(new ArrayList<Reservation>(reservations.values())); } }
    public Signal get(String id) { synchronized(network) { Signal s=definitions.get(id);return s==null?traffic.signal(id):s; } }
    public Signal at(String rail,int approach) {
        synchronized(network) { Signal s=definedAt(rail,approach);return s==null?traffic.boundaryAt(rail,approach):s; }
    }
    private Signal definedAt(String rail,int approach) { for(Signal s:definitions.values())if(s.rail.equals(rail)&&s.approachDirection==approach)return s;return null; }
    boolean hasApproachSignal(String rail,int approach) { return definedAt(rail,approach)!=null; }
    boolean trafficRouting(String rail) { return traffic.activeAt(rail); }
    String preferredExit(String train,String rail,String previous) { return traffic.preferredExit(train,rail,previous); }
    public String trafficStatus(String trainName) { synchronized(network) { return traffic.queueStatus(trainName); } }
    public TrafficControl traffic() { return traffic; }
    public Signal add(String id,String rail,int approachDirection,long itemId) {
        synchronized(network) {
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw problem("Signal IDs need 1 to 80 letters, numbers, underscores or hyphens.");
            if(definitions.containsKey(id))throw problem("A signal named "+id+" already exists.");
            validateDefinition(rail,approachDirection,null);requireTrackEditable(rail);
            Signal signal=new Signal(id,rail,approachDirection,itemId);definitions.put(id,signal);definitionChanged();return signal;
        }
    }
    public Signal configure(String id,String rail,int approachDirection) {
        synchronized(network) {
            Signal old=requiredDefinition(id);requireDefinitionEditable(old);validateDefinition(rail,approachDirection,id);requireTrackEditable(rail);
            Signal signal=new Signal(id,rail,approachDirection,old.itemId);definitions.put(id,signal);reasons.remove(id);definitionChanged();return signal;
        }
    }
    public void remove(String id) {
        synchronized(network) { Signal signal=requiredDefinition(id);requireDefinitionEditable(signal);definitions.remove(id);reasons.remove(id);definitionChanged(); }
    }
    public Signal setItemId(String id,long itemId) {
        synchronized(network) {
            Signal old=requiredDefinition(id);if(old.itemId==itemId)return old;
            Signal signal=new Signal(id,old.rail,old.approachDirection,itemId);definitions.put(id,signal);changed();return signal;
        }
    }
    public DefinitionsCheckpoint checkpointDefinitions() { synchronized(network) { return new DefinitionsCheckpoint(definitions); } }
    public void restoreDefinitions(DefinitionsCheckpoint checkpoint) {
        synchronized(network) {
            if(checkpoint==null)throw problem("A signal definition checkpoint is required.");
            definitions.clear();definitions.putAll(checkpoint.definitions);reasons.keySet().retainAll(definitions.keySet());definitionChanged();
        }
    }
    public Aspect aspect(String id) { return status(id).aspect; }
    public Status status(String id) {
        synchronized(network) {
            required(id);Reservation active=entryReservation(id);
            if(active==null)for(Reservation r:reservations.values())if(r.entrySignalId.equals(id)) { active=r;break; }
            if(active!=null&&!authorityValid(active))return new Status(null,"Reservation ownership or junction alignment is inconsistent.");
            return new Status(active,active==null?reasons.getOrDefault(id,"No train has reserved the route ahead."):
                    active.state==ReservationState.CLEARING?(reasons.containsKey(id)?reasons.get(id)+" ":"")+"Previous route protected for "+active.trainName+" until the last car clears; no movement authority.":
                    "Route reserved for "+active.trainName+".");
        }
    }
    /** Atomic acquire: discover first, reject occupancy/conflicts, then align all points or roll back all. */
    public RequestResult request(String trainName,String signalId) {
        synchronized(network) {
            RailNetwork.Train train=network.trains.get(trainName);if(train==null)throw problem("Unknown train: "+trainName);
            Signal signal=required(signalId);Reservation existing=entryReservation(signalId);
            boolean approaching=network.signalApproaching(train,signalId);
            if(existing!=null) {
                if(!existing.trainName.equals(trainName))return denied(signal,"Route is reserved by train "+existing.trainName+".");
                if(!authorityValid(existing))return denied(signal,"Reservation ownership or junction alignment is inconsistent.");
                String unsafeExit=traffic.receivingUnsafe(train,existing.path,network.signalNeighbor(signal.rail,signal.approachDirection));
                if(unsafeExit!=null)return deniedReplacement(signal,unsafeExit,existing);
                if(!approaching||!handoverBoundary(existing,signal))return new RequestResult(existing,"Existing route authority belongs to "+trainName+".");
            }
            final SignalRouteDiscovery.Route route;
            try { route=SignalRouteDiscovery.discover(network,trainName,signal); }
            catch(IllegalArgumentException invalid) { return deniedReplacement(signal,invalid.getMessage(),existing); }
            if(existing!=null&&existing.path.equals(route.path)&&existing.junctionAlignments.equals(route.junctionAlignments)
                    &&Objects.equals(existing.exitSignalId,route.exitSignalId))
                return new RequestResult(existing,"Existing route authority belongs to "+trainName+".");
            List<String> dispatchPath=new ArrayList<String>(route.path);
            if(dispatchPath.size()>1) {
                String end=dispatchPath.get(dispatchPath.size()-1);
                if(traffic.activeAt(end)&&network.nodes.get(end).links.size()>2)try {
                    String next=network.signalIntendedNext(train,end,dispatchPath.get(dispatchPath.size()-2),train.routeIndex);
                    if(next!=null)dispatchPath.add(next);
                }catch(IllegalArgumentException unresolved) { /* The protecting junction signal will explain it. */ }
            }
            TrafficControl.Admission admission=traffic.prepare(train,dispatchPath,network.signalNeighbor(signal.rail,signal.approachDirection));
            if(!admission.granted)return deniedReplacement(signal,admission.reason,existing);
            Set<String> resources=network.signalRouteResources(route.nodes);
            network.signalAddEntryResources(resources,signal.rail,network.signalNeighbor(signal.rail,signal.approachDirection));
            for(RailNetwork.Train other:network.trains.values())if(other!=train&&intersects(resources,network.signalOccupiedResources(other)))
                return deniedReplacement(signal,"Protected route is occupied by train "+other.name+".",existing);
            for(Reservation other:reservations.values())if(!other.trainName.equals(trainName)&&intersects(resources,other.resources))
                return deniedReplacement(signal,"Protected route conflicts with reservation "+other.id+" owned by train "+other.trainName+".",existing);
            if(traffic.conflictsResources(trainName,resources,admission))
                return deniedReplacement(signal,"Waiting for another train to clear the shared line or its receiving track.",existing);
            // Retire only a real predecessor at this directional boundary. Its entire
            // block stays protected; no junction can move beneath a car of any train.
            Set<String> retiring=new HashSet<String>();
            if(existing!=null)retiring.add(existing.id);
            Set<String> transferring=new HashSet<String>();
            for(Reservation old:reservations.values())if(old.trainName.equals(trainName))
                for(Map.Entry<String,Integer> point:route.junctionAlignments.entrySet()) {
                    Integer held=old.junctionLocks.get(point.getKey());
                    if(held==null||held.equals(point.getValue()))continue;
                    if(!approaching||(old.state!=ReservationState.CLEARING&&!handoverBoundary(old,signal)))
                        return deniedReplacement(signal,"Junction "+point.getKey()+" is held by this train's earlier route. Place a protecting exit signal before a reversing station and leave room for every cart to clear.",existing);
                    transferring.add(point.getKey());
                    if(old.state!=ReservationState.CLEARING)retiring.add(old.id);
                }
            Map<String,Reservation> original=new LinkedHashMap<String,Reservation>(reservations);
            // This temporary interlocking view is private to the synchronized atomic
            // acquire. The physical owner remains unchanged throughout the transfer.
            for(Reservation old:original.values())if(old.trainName.equals(trainName)
                    &&(retiring.contains(old.id)||old.state==ReservationState.CLEARING)) {
                Map<String,Integer> locks=new LinkedHashMap<String,Integer>(old.junctionLocks);
                for(String rail:transferring)locks.remove(rail);
                reservations.put(old.id,new Reservation(old,ReservationState.CLEARING,locks));
            }
            for(Map.Entry<String,Integer> point:route.junctionAlignments.entrySet())if(!network.signalJunctionAvailable(trainName,point.getKey(),point.getValue())) {
                RailNetwork.Node blocked=network.nodes.get(point.getKey());
                reservations.clear();reservations.putAll(original);
                return deniedReplacement(signal,"Junction "+point.getKey()+" is occupied or locked"+(blocked.getAlignmentOwner()==null?".":" by train "+blocked.getAlignmentOwner()+"."),existing);
            }
            RailNetwork.JunctionCheckpoint before=network.checkpointJunctions();
            try {
                for(Map.Entry<String,Integer> point:route.junctionAlignments.entrySet()) {
                    if(!network.requestJunctionAlignment(trainName,point.getKey(),point.getValue())) {
                        network.restoreJunctions(before);reservations.clear();reservations.putAll(original);
                        return deniedReplacement(signal,"Junction "+point.getKey()+" is occupied or locked by another train.",existing);
                    }
                }
            } catch(IllegalArgumentException invalid) {
                network.restoreJunctions(before);reservations.clear();reservations.putAll(original);
                return deniedReplacement(signal,"Junction route cannot be aligned safely: "+invalid.getMessage(),existing);
            }
            for(Reservation old:original.values())if(old.trainName.equals(trainName)
                    &&(retiring.contains(old.id)||old.state==ReservationState.CLEARING)) {
                Map<String,Integer> locks=new LinkedHashMap<String,Integer>(old.junctionLocks);
                for(String rail:transferring)if(locks.containsKey(rail))locks.put(rail,route.junctionAlignments.get(rail));
                reservations.put(old.id,new Reservation(old,ReservationState.CLEARING,locks));
            }
            if(!traffic.commit(admission)) {
                network.restoreJunctions(before);reservations.clear();reservations.putAll(original);
                return deniedReplacement(signal,"The shared-line dispatch plan changed; waiting for a clear crossing.",existing);
            }
            String id="route_"+(++sequence);
            Reservation reservation=new Reservation(id,trainName,signal,route,sequence,resources,ReservationState.RESERVED);
            reservations.put(id,reservation);reasons.remove(signal.id);changed();
            coalesceClearing();
            LOG.fine("Created "+id+" for train "+trainName+" at signal "+signal.id+" path="+route.path+" alignments="+route.junctionAlignments);
            return new RequestResult(reservation,"Route reserved for "+trainName+".");
        }
    }
    private boolean handoverBoundary(Reservation old,Signal next) {
        if(old.state!=ReservationState.OCCUPIED)return false;
        if(next.id.equals(old.exitSignalId))return true;
        // A scheduled terminus reverses back through the route's entrance. The
        // signal facing its former outgoing side is the return boundary.
        return old.path.size()>1&&old.path.get(0).equals(next.rail)
                &&network.signalSide(next.rail,old.path.get(1))==next.approachDirection;
    }
    private void coalesceClearing() {
        for(Reservation old:new ArrayList<Reservation>(reservations.values()))if(old.state==ReservationState.CLEARING)
            for(Reservation active:reservations.values())if(active.state!=ReservationState.CLEARING&&active.trainName.equals(old.trainName)
                    &&active.resources.containsAll(old.resources)&&active.junctionLocks.entrySet().containsAll(old.junctionLocks.entrySet())) {
                reservations.remove(old.id);changed();break;
            }
    }
    /** GREEN is a displayed aspect, not a transferable permission. */
    public boolean ownsAuthority(String trainName,String signalId) {
        synchronized(network) { Reservation r=entryReservation(signalId);return r!=null&&r.trainName.equals(trainName)&&authorityValid(r); }
    }
    /** An occupied approach block can still protect its next tile before the
     * leading car reaches that tile. Dispatch must include that protection. */
    boolean holdsOccupiedApproach(String trainName,String rail) {
        for(Reservation reservation:reservations.values())if(reservation.trainName.equals(trainName)
                &&reservation.state!=ReservationState.RESERVED&&reservation.resources.contains("N:"+rail))return true;
        return false;
    }
    private boolean authorityValid(Reservation r) {
        if(!network.trains.containsKey(r.trainName)||get(r.entrySignalId)==null)return false;
        for(int i=0;i<r.path.size();i++) {
            RailNetwork.Node node=network.nodes.get(r.path.get(i));if(node==null)return false;
            if(i>0&&!node.links.contains(r.path.get(i-1)))return false;
        }
        for(Map.Entry<String,Integer> point:r.junctionLocks.entrySet()) {
            RailNetwork.Node n=network.nodes.get(point.getKey());
            if(n==null||!r.trainName.equals(n.getAlignmentOwner())||point.getValue()!=n.getAlignmentMask())return false;
        }
        return true;
    }
    private RequestResult denied(Signal signal,String reason) {
        if(!Objects.equals(reasons.put(signal.id,reason),reason)) { changed();LOG.fine("Route request denied at signal "+signal.id+": "+reason); }
        return new RequestResult(null,reason);
    }
    private RequestResult deniedReplacement(Signal signal,String reason,Reservation replaced) {
        // Returning to the SAME signal with a different itinerary invalidates its
        // old permission even if the replacement is denied. Keep all protection.
        if(replaced!=null) {
            reservations.put(replaced.id,new Reservation(replaced,ReservationState.CLEARING));changed();
        }
        return denied(signal,reason);
    }
    /** Cancel unentered approaches; a train standing in a protected route continues to hold its authority. */
    public void cancelUnused(String trainName) {
        synchronized(network) {
            for(Reservation reservation:new ArrayList<Reservation>(reservations.values()))
                if(reservation.trainName.equals(trainName)&&reservation.state==ReservationState.RESERVED&&!occupiesRoute(reservation))release(reservation,"Unused route cancelled");
            traffic.cancelUnused(trainName);
        }
    }
    /** A protected stationary yield retires history, without dropping occupied locks. */
    void prepareTrafficReversal(String trainName) {
        cancelUnused(trainName);
        for(Reservation reservation:new ArrayList<Reservation>(reservations.values()))
            if(reservation.trainName.equals(trainName)&&reservation.state!=ReservationState.CLEARING) {
                reservations.put(reservation.id,new Reservation(reservation,ReservationState.CLEARING));changed();
            }
    }
    /** A local turn can change its points without passing another signal. Transfer
     * only its cleared physical lock; the earlier block remains fully protected. */
    boolean alignStationTurnback(String trainName,boolean departing) {
        synchronized(network) {
            RailNetwork.Train train=network.trains.get(trainName);
            if(train==null||!StationTurnback.valid(network,train,train.stationTurnback))return false;
            StationTurnback.Plan plan=train.stationTurnback;
            List<String> path=departing?plan.departurePath:plan.backingPath;
            int at=path.indexOf(plan.junction);
            if(at<=0||at+1>=path.size())return false;
            int wanted=(1<<network.signalSide(plan.junction,path.get(at-1)))
                    |(1<<network.signalSide(plan.junction,path.get(at+1)));
            RailNetwork.Node points=network.nodes.get(plan.junction);
            if(points==null||!network.junctionAlignments(plan.junction).contains(wanted))return false;
            boolean changing=points.getAlignmentMask()!=wanted;
            if(changing&&network.signalOccupiesNode(train,plan.junction))return false;
            for(RailNetwork.Train other:network.trains.values())if(other!=train
                    &&network.signalOccupiesNode(other,plan.junction))return false;
            for(Reservation reservation:reservations.values()) {
                if(!reservation.trainName.equals(trainName)&&reservation.resources.contains("N:"+plan.junction))return false;
                if(changing&&reservation.trainName.equals(trainName)
                        &&reservation.junctionLocks.containsKey(plan.junction)
                        &&reservation.state!=ReservationState.CLEARING)return false;
            }
            Map<String,Reservation> original=new LinkedHashMap<String,Reservation>(reservations);
            RailNetwork.JunctionCheckpoint before=network.checkpointJunctions();
            try {
                if(changing)for(Reservation reservation:original.values())if(reservation.trainName.equals(trainName)
                        &&reservation.state==ReservationState.CLEARING&&reservation.junctionLocks.containsKey(plan.junction)) {
                    Map<String,Integer> locks=new LinkedHashMap<String,Integer>(reservation.junctionLocks);
                    locks.remove(plan.junction);
                    reservations.put(reservation.id,new Reservation(reservation,ReservationState.CLEARING,locks));
                }
                if(!network.signalJunctionAvailable(trainName,plan.junction,wanted)
                        ||!network.requestJunctionAlignment(trainName,plan.junction,wanted)) {
                    reservations.clear();reservations.putAll(original);network.restoreJunctions(before);return false;
                }
                if(changing)for(Reservation reservation:original.values())if(reservation.trainName.equals(trainName)
                        &&reservation.state==ReservationState.CLEARING&&reservation.junctionLocks.containsKey(plan.junction)) {
                    Map<String,Integer> locks=new LinkedHashMap<String,Integer>(reservation.junctionLocks);
                    locks.put(plan.junction,wanted);
                    reservations.put(reservation.id,new Reservation(reservation,ReservationState.CLEARING,locks));
                }
                changed();return true;
            } catch(IllegalArgumentException unavailable) {
                reservations.clear();reservations.putAll(original);network.restoreJunctions(before);return false;
            }
        }
    }
    boolean canReplanUnenteredJunction(String trainName,String rail) {
        for(Reservation reservation:reservations.values())if(reservation.trainName.equals(trainName)
                &&reservation.junctionLocks.containsKey(rail)&&occupiesRoute(reservation))return false;
        return true;
    }
    void cancelUnenteredJunction(String trainName,String rail) {
        if(!canReplanUnenteredJunction(trainName,rail))throw problem("An entered route still protects junction "+rail+".");
        for(Reservation reservation:new ArrayList<Reservation>(reservations.values()))if(reservation.trainName.equals(trainName)
                &&reservation.junctionLocks.containsKey(rail))release(reservation,"Unentered receiving exit replanned");
    }
    void removeTrain(String trainName) {
        for(Reservation reservation:new ArrayList<Reservation>(reservations.values()))if(reservation.trainName.equals(trainName))release(reservation,"Train removed");
        traffic.removeTrain(trainName);
    }
    /** Called after every movement substep, never solely when the engine passes the points. */
    void updateClearance() {
        for(Reservation reservation:new ArrayList<Reservation>(reservations.values())) {
            if(!network.trains.containsKey(reservation.trainName)) { release(reservation,"Train removed");continue; }
            boolean occupied=occupiesRoute(reservation);
            if(occupied&&reservation.state==ReservationState.RESERVED) {
                reservations.put(reservation.id,new Reservation(reservation,ReservationState.OCCUPIED));changed();
            } else if(!occupied&&reservation.state!=ReservationState.RESERVED)release(reservation,"Rear of train cleared protected route");
        }
        traffic.updateClearance();
    }
    private boolean occupiesRoute(Reservation r) {
        RailNetwork.Train train=network.trains.get(r.trainName);if(train==null)return false;
        for(String rail:r.nodes)if(network.signalOccupiesNode(train,rail))return true;return false;
    }
    private void release(Reservation reservation,String reason) {
        reservations.remove(reservation.id);reasons.put(reservation.entrySignalId,reason+"; no route authority.");changed();
        for(String rail:reservation.junctionLocks.keySet())if(!holdsJunction(reservation.trainName,rail)) {
            if(network.trains.containsKey(reservation.trainName))network.releaseJunctionAlignment(reservation.trainName,rail);
        }
        LOG.fine("Released "+reservation.id+" for train "+reservation.trainName+": "+reason);
    }
    boolean holdsJunction(String trainName,String rail) {
        for(Reservation r:reservations.values())if(r.trainName.equals(trainName)&&r.junctionLocks.containsKey(rail))return true;return false;
    }
    boolean conflictsResources(String trainName,Set<String> resources) {
        return conflictsBlockResources(trainName,resources)||traffic.conflictsResources(trainName,resources);
    }
    /** Actual block authority stays exclusive even when dispatch admits a same-direction cohort. */
    boolean conflictsBlockResources(String trainName,Set<String> resources) {
        return conflictsBlockResourcesExcept(trainName,resources,Collections.<String>emptySet());
    }
    /**
     * Prospective dispatch planning only: compatible owners may share a corridor's direction
     * and receiving-capacity plan. Never use this exemption to acquire or move into a block.
     */
    boolean conflictsBlockResourcesExcept(String trainName,Set<String> resources,Set<String> permittedOwners) {
        Objects.requireNonNull(permittedOwners,"Permitted dispatch owners are required.");
        for(Reservation r:reservations.values())if(!r.trainName.equals(trainName)&&!permittedOwners.contains(r.trainName)
                &&intersects(resources,r.resources))return true;
        return false;
    }
    String invalidAuthority(String trainName) {
        for(Reservation r:reservations.values())if(r.trainName.equals(trainName)&&!authorityValid(r))return "Reservation "+r.id+" ownership or junction alignment is inconsistent.";
        return null;
    }
    String reservedNext(String trainName,String rail,String previous) {
        for(Reservation r:reservations.values())if(r.state!=ReservationState.CLEARING&&r.trainName.equals(trainName))for(int i=0;i<r.path.size();i++)if(r.path.get(i).equals(rail)) {
            if(i>0&&r.path.get(i-1).equals(previous))return i+1<r.path.size()?r.path.get(i+1):null;
            Signal entry=get(r.entrySignalId);
            if(i==0&&entry!=null&&network.signalNeighbor(rail,entry.approachDirection).equals(previous))return i+1<r.path.size()?r.path.get(i+1):null;
        }
        return null;
    }
    void requireTrackEditable(String rail) {
        traffic.requireTrackEditable(rail);
        for(Reservation r:reservations.values())if(r.path.contains(rail))throw problem("Track is reserved by train "+r.trainName+" ("+r.id+").");
    }
    boolean hasSignal(String rail) { for(Signal s:definitions.values())if(s.rail.equals(rail))return true;return false; }
    private void requireDefinitionEditable(Signal s) {
        requireTrackEditable(s.rail);
        for(Reservation r:reservations.values())if(s.id.equals(r.entrySignalId)||s.id.equals(r.exitSignalId))throw problem("Signal is reserved by train "+r.trainName+".");
    }
    private void validateDefinition(String rail,int approach,String except) {
        RailNetwork.Node node=network.nodes.get(rail);
        if(node==null)throw problem("Unknown signal track: "+rail);
        if(approach<0||approach>7||network.signalNeighbor(rail,approach)==null)throw problem("Choose an existing connected absolute approach side.");
        Signal duplicate=definedAt(rail,approach);
        if(duplicate!=null&&!duplicate.id.equals(except))throw problem("A signal already controls this track approach.");
    }
    Checkpoint checkpoint() {
        long trafficRevision=traffic.revision();
        if(cachedCheckpoint==null||cachedTrafficRevision!=trafficRevision) { cachedCheckpoint=new Checkpoint(this);cachedTrafficRevision=trafficRevision; }
        return cachedCheckpoint;
    }
    void restore(Checkpoint checkpoint) {
        definitions.clear();definitions.putAll(checkpoint.definitions);reservations.clear();reservations.putAll(checkpoint.reservations);
        reasons.clear();reasons.putAll(checkpoint.reasons);sequence=checkpoint.sequence;definitionRevision=checkpoint.definitionRevision;
        traffic.restore(checkpoint.traffic);changed();
    }
    private Reservation entryReservation(String id) { for(Reservation r:reservations.values())if(r.state!=ReservationState.CLEARING&&r.entrySignalId.equals(id))return r;return null; }
    private Signal required(String id) { Signal s=get(id);if(s==null)throw problem("Unknown signal: "+id);return s; }
    private Signal requiredDefinition(String id) { Signal s=definitions.get(id);if(s==null)throw problem("Choose a player-built signal to configure.");return s; }
    private void definitionChanged() { definitionRevision++;changed(); }
    private void changed() { revision++;cachedCheckpoint=null; }
    private static boolean intersects(Set<String> first,Set<String> second) { for(String key:first)if(second.contains(key))return true;return false; }
    private static IllegalArgumentException problem(String message) { return new IllegalArgumentException(message); }
}
