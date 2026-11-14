package org.wurmrail.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Predicate;

/** Server-independent, metre-based rail simulation. All mutation methods are synchronized. */
public final class RailNetwork {
    public static final double CAR_SPACING = 4.0;
    /** Minimum centre separation for different trains on the same nearby connected track. */
    public static final double TRAIN_CLEARANCE = 4.0;
    private static final double EPS = 0.000001;
    private static final double BUFFER = 0.45;
    private static final PassengerStopRequest NO_PASSENGER_STOPS = (train, rail, carId) -> false;
    private final Map<String, Node> nodeData = new LinkedHashMap<String, Node>();
    private final Map<String, Train> trainData = new LinkedHashMap<String, Train>();
    public final Map<String, Node> nodes = Collections.unmodifiableMap(nodeData);
    public final Map<String, Train> trains = Collections.unmodifiableMap(trainData);
    public int maxCars = 8;
    private long alignmentRevision;
    private long topologyRevision;
    public synchronized long topologyRevision() { return topologyRevision; }
    private JunctionCheckpoint cachedJunctions;
    private final Set<String> lockedJunctions=new LinkedHashSet<String>();
    private final SignalSystem signalSystem=new SignalSystem(this);
    public SignalSystem signals() { return signalSystem; }
    /** Called with a complete proposed consist, before committing any movement. */
    public Predicate<List<Pose>> movementGuard = poses -> true;
    /** Pure request check. Native boarding/disembarking belongs after the world movement commits. */
    public PassengerStopRequest passengerStops = NO_PASSENGER_STOPS;

    /** Pure arrival check; inventory work belongs after native movement commits. */
    public RouteStopService routeStopServices = (train, rail) -> false;
    public interface RouteStopService {
        boolean shouldService(Train train, Node rail);
    }

    public interface PassengerStopRequest {
        boolean shouldStop(Train train, Node rail, long carId);
    }

    public static final class Node {
        public final String name;
        public final int tileX, tileY, layer;
        public long markerId = -10L;
        public long bridgeId = -10L;
        private final Set<String> linkData = new LinkedHashSet<String>();
        public final Set<String> links = Collections.unmodifiableSet(linkData);
        private int alignmentMask;
        private String alignmentOwner;
        private boolean alignmentEntered;
        public int getAlignmentMask() { return alignmentMask; }
        public String getAlignmentOwner() { return alignmentOwner; }
        public boolean station;
        public double dwellSeconds = 5.0;
        public Node(String name, int tileX, int tileY, int layer) {
            this.name = checkedName(name);
            if (tileX < 0 || tileY < 0 || (layer != 0 && layer != -1))
                throw new IllegalArgumentException("Rail coordinates must be non-negative and layer must be 0 or -1.");
            this.tileX = tileX; this.tileY = tileY; this.layer = layer;
        }
        public float worldX() { return tileX * 4f + 2f; }
        public float worldY() { return tileY * 4f + 2f; }
    }

    public static final class Pose {
        public final long carId;
        public final float x, y, rotation;
        public final int layer;
        /** Owning curve's marker; diagonal boundary points can floor into a side tile. */
        public final String railName;
        public Pose(long carId, float x, float y, float rotation, int layer) {
            this(carId,x,y,rotation,layer,null);
        }
        public Pose(long carId, float x, float y, float rotation, int layer, String railName) {
            this.carId = carId; this.x = x; this.y = y; this.rotation = rotation; this.layer = layer;
            this.railName = railName;
        }
        @Override public String toString() { return carId + "@(" + x + "," + y + "," + layer + ")"; }
    }

    public static final class Train {
        public final String name;
        public final long ownerId;
        private final List<Long> carData;
        public final List<Long> carIds;
        public List<Pose> poses = Collections.emptyList();
        public boolean powered, automated;
        public int direction = 1;
        public double speed, maxSpeed = 5.0, acceleration = 0.8;
        public String status = "Stopped";
        private final List<String> routeData = new ArrayList<String>();
        public final List<String> route = Collections.unmodifiableList(routeData);
        public int routeIndex;
        public double dwellRemaining, routeDwellSeconds = 5.0;
        /** A scheduled arrival held for a runtime service, using normal station occupancy. */
        public String serviceStopRail;
        public long serviceStopSerial;
        /** Transient held arrival. Runtime serves this car at this rail, then releases this serial. */
        public String passengerStopRail;
        public long passengerStopCarId = -10L, passengerStopSerial;
        private String passengerResumeStatus;
        private final Map<Long,String> passengerServed = new HashMap<Long,String>();
        private final List<String> path = new ArrayList<String>();
        // Distance is measured along the actual tile curves, including diagonal track.
        // Cars keep their locomotive-first order while reversing.
        private double locomotiveDistance;
        private String initialTowards;
        private double trafficWaitSeconds;
        private boolean recoveringTraffic;
        private String trafficRecoveryRail;
        // A committed backing exit from this loop, distinct from a backing
        // arrival that still needs room to turn around inside the loop.
        private String trafficRecoveryExit;
        StationTurnback.Plan stationTurnback;
        private String recoveryStatus;
        private Geometry cachedGeometry;
        private List<String> cachedPath;
        private Train(String name, long ownerId, List<Long> cars) {
            this.name = checkedName(name); this.ownerId = ownerId;
            this.carData = new ArrayList<Long>(cars); this.carIds = Collections.unmodifiableList(carData);
        }
    }

    /** Immutable, inexpensive rollback state for a world movement transaction. */
    public static final class Checkpoint {
        public final String trainName;
        public final List<Pose> poses;
        private final Train identity;
        private final List<Long> carIds;
        private final List<String> path, route;
        private final double locomotiveDistance, speed, dwellRemaining, routeDwellSeconds;
        private final String initialTowards, status;
        private final String passengerStopRail, passengerResumeStatus;
        private final long passengerStopCarId, passengerStopSerial;
        private final String serviceStopRail;
        private final long serviceStopSerial;
        private final Map<Long,String> passengerServed;
        private JunctionCheckpoint junctions;
        private SignalSystem.Checkpoint signals;
        private final int direction, routeIndex;
        private final boolean powered, automated;
        private final double trafficWaitSeconds;
        private final boolean recoveringTraffic;
        private final String recoveryStatus;
        private final String trafficRecoveryRail;
        private final String trafficRecoveryExit;
        private final StationTurnback.Plan stationTurnback;
        private Checkpoint(Train t) {
            trainName = t.name; identity = t;
            carIds = new ArrayList<Long>(t.carIds);
            path = new ArrayList<String>(t.path);
            route = new ArrayList<String>(t.route);
            poses = Collections.unmodifiableList(new ArrayList<Pose>(t.poses));
            locomotiveDistance = t.locomotiveDistance; initialTowards = t.initialTowards;
            direction = t.direction; speed = t.speed; powered = t.powered; automated = t.automated;
            routeIndex = t.routeIndex; dwellRemaining = t.dwellRemaining; routeDwellSeconds = t.routeDwellSeconds; status = t.status;
            passengerStopRail = t.passengerStopRail; passengerStopCarId = t.passengerStopCarId;
            passengerStopSerial = t.passengerStopSerial; passengerResumeStatus = t.passengerResumeStatus;
            serviceStopRail=t.serviceStopRail;serviceStopSerial=t.serviceStopSerial;
            passengerServed = new HashMap<Long,String>(t.passengerServed);
            trafficWaitSeconds=t.trafficWaitSeconds;recoveringTraffic=t.recoveringTraffic;recoveryStatus=t.recoveryStatus;
            trafficRecoveryRail=t.trafficRecoveryRail;
            trafficRecoveryExit=t.trafficRecoveryExit;
            stationTurnback=t.stationTurnback;
        }
    }

    public synchronized Checkpoint checkpoint(String name) {
        Checkpoint checkpoint=new Checkpoint(train(name)); checkpoint.junctions=checkpointJunctions();checkpoint.signals=signalSystem.checkpoint();return checkpoint;
    }

    /** Restore only to the same registered consist; a removed/reclaimed train cannot use stale state. */
    public synchronized void restore(Checkpoint checkpoint) {
        if (checkpoint == null) throw problem("A train checkpoint is required.");
        Train t = train(checkpoint.trainName);
        if (t != checkpoint.identity || !t.carIds.equals(checkpoint.carIds))
            throw problem("The train registration or coupled vehicles changed after this checkpoint.");
        for (String rail : checkpoint.path) node(rail);
        for (String rail : checkpoint.route) node(rail);
        t.path.clear(); t.path.addAll(checkpoint.path); t.locomotiveDistance = checkpoint.locomotiveDistance;
        t.routeData.clear(); t.routeData.addAll(checkpoint.route); t.routeDwellSeconds = checkpoint.routeDwellSeconds;
        t.initialTowards = checkpoint.initialTowards; t.direction = checkpoint.direction;
        t.speed = checkpoint.speed; t.powered = checkpoint.powered; t.automated = checkpoint.automated;
        t.routeIndex = checkpoint.routeIndex; t.dwellRemaining = checkpoint.dwellRemaining;
        t.status = checkpoint.status; t.poses = checkpoint.poses;
        t.passengerStopRail = checkpoint.passengerStopRail; t.passengerStopCarId = checkpoint.passengerStopCarId;
        t.passengerStopSerial = checkpoint.passengerStopSerial; t.passengerResumeStatus = checkpoint.passengerResumeStatus;
        t.serviceStopRail=checkpoint.serviceStopRail;t.serviceStopSerial=checkpoint.serviceStopSerial;
        t.passengerServed.clear(); t.passengerServed.putAll(checkpoint.passengerServed);
        t.trafficWaitSeconds=checkpoint.trafficWaitSeconds;t.recoveringTraffic=checkpoint.recoveringTraffic;t.recoveryStatus=checkpoint.recoveryStatus;
        t.trafficRecoveryRail=checkpoint.trafficRecoveryRail;
        t.trafficRecoveryExit=checkpoint.trafficRecoveryExit;
        t.stationTurnback=checkpoint.stationTurnback;
        restoreJunctions(checkpoint.junctions);
        signalSystem.restore(checkpoint.signals);
    }

    private static final class AlignmentState {
        final int mask; final String owner; final boolean entered;
        AlignmentState(Node node) { mask=node.alignmentMask; owner=node.alignmentOwner; entered=node.alignmentEntered; }
    }
    /** Immutable shared physical-switch snapshot; only rollback APIs can force its restoration. */
    public static final class JunctionCheckpoint {
        private final long revision;
        private final Map<String,AlignmentState> states;
        private JunctionCheckpoint(long revision,Map<String,AlignmentState> states) {
            this.revision=revision; this.states=Collections.unmodifiableMap(states);
        }
    }
    public synchronized JunctionCheckpoint checkpointJunctions() {
        if(cachedJunctions==null||cachedJunctions.revision!=alignmentRevision) {
            Map<String,AlignmentState> states=new LinkedHashMap<String,AlignmentState>();
            for(Node node:nodes.values())states.put(node.name,new AlignmentState(node));
            cachedJunctions=new JunctionCheckpoint(alignmentRevision,states);
        }
        return cachedJunctions;
    }
    public synchronized void restoreJunctions(JunctionCheckpoint checkpoint) {
        if(checkpoint==null)throw problem("A junction checkpoint is required.");
        for(Map.Entry<String,AlignmentState> entry:checkpoint.states.entrySet()) {
            Node node=nodes.get(entry.getKey());
            if(node!=null)validateAlignment(node,entry.getValue().mask);
        }
        for(Map.Entry<String,AlignmentState> entry:checkpoint.states.entrySet()) {
            Node node=nodes.get(entry.getKey()); if(node==null)continue;
            node.alignmentMask=entry.getValue().mask; node.alignmentOwner=entry.getValue().owner;
            node.alignmentEntered=entry.getValue().entered;
        }
        lockedJunctions.clear(); for(Node node:nodes.values())if(node.alignmentOwner!=null)lockedJunctions.add(node.name);
        alignmentChanged();
    }
    private void alignmentChanged() { alignmentRevision++; cachedJunctions=null; }

    public synchronized Node addNode(String name, int x, int y, int layer) {
        Node node = new Node(name, x, y, layer);
        if (nodeData.containsKey(node.name)) throw problem("A rail named " + node.name + " already exists.");
        for (Node other : nodeData.values())
            if (other.tileX == x && other.tileY == y && other.layer == layer)
                throw problem("This tile already has rail " + other.name + ".");
        nodeData.put(node.name, node); topologyRevision++;alignmentChanged(); return node;
    }
    public synchronized void removeNode(String name) {
        Node n = node(name);
        signalSystem.requireTrackEditable(name);
        if(signalSystem.hasSignal(name))throw problem("Remove the signals on this track first.");
        for (Train t : trainData.values())
            if (t.path.contains(name) || t.route.contains(name)) throw problem("Rail is used by train " + t.name + ".");
        for (String adjacent : new ArrayList<String>(n.links)) disconnect(name, adjacent);
        nodeData.remove(name); lockedJunctions.remove(name);topologyRevision++; alignmentChanged();
    }
    public synchronized void connect(String first, String second) {
        connect(first,second,false);
    }
    /** The native world must verify an open cave mouth before creating this connection. */
    public synchronized void connectEntrance(String first,String second) {
        connect(first,second,true);
    }
    private void connect(String first,String second,boolean entrance) {
        Node a = node(first), b = node(second);
        long dx = Math.abs((long)a.tileX - b.tileX), dy = Math.abs((long)a.tileY - b.tileY);
        if(entrance) {
            if(a.layer==b.layer||dx+dy!=1)
                throw problem("A cave entrance must join adjacent surface and cave tiles across one cardinal tile boundary.");
        } else if (a.layer != b.layer || Math.max(dx, dy) != 1)
            throw problem("Rails must be on adjacent tiles in one of the eight compass directions on the same layer.");
        if(a.links.contains(second))return;
        requireUnusedSide(a,b);requireUnusedSide(b,a);
        signalSystem.requireTrackEditable(first);signalSystem.requireTrackEditable(second);
        int nextA=alignmentAfterConnection(a,b,true),nextB=alignmentAfterConnection(b,a,true);
        if(a.links.size()>=2)requireAlignmentClear(a,nextA,null);
        if(b.links.size()>=2)requireAlignmentClear(b,nextB,null);
        a.linkData.add(second); b.linkData.add(first);
        a.alignmentMask=nextA; b.alignmentMask=nextB;topologyRevision++; alignmentChanged();
        clearChangedRecoveryStop(a);clearChangedRecoveryStop(b);
    }
    private void requireUnusedSide(Node origin,Node next) {
        int intended=direction(next.tileX-origin.tileX,next.tileY-origin.tileY);
        for(String linked:origin.links) {
            Node existing=node(linked);
            if(direction(existing.tileX-origin.tileX,existing.tileY-origin.tileY)==intended)
                throw problem("Rail "+origin.name+" already connects that compass side to "+existing.name+".");
        }
    }
    public synchronized void disconnect(String first, String second) {
        Node a = node(first), b = node(second);
        for (Train t : trainData.values())
            for (int i = 1; i < t.path.size(); i++)
                if (sameEdge(first, second, t.path.get(i-1), t.path.get(i)))
                    throw problem("Track is occupied by train " + t.name + ".");
        if(!a.links.contains(second))return;
        signalSystem.requireTrackEditable(first);signalSystem.requireTrackEditable(second);
        int firstSide=direction(b.tileX-a.tileX,b.tileY-a.tileY),secondSide=(firstSide+4)%8;
        if(signalSystem.hasApproachSignal(first,firstSide)||signalSystem.hasApproachSignal(second,secondSide))
            throw problem("Remove or reconfigure the signal protecting this connection first.");
        int nextA=alignmentAfterConnection(a,b,false),nextB=alignmentAfterConnection(b,a,false);
        if(a.links.size()>2)requireAlignmentClear(a,nextA,null);
        if(b.links.size()>2)requireAlignmentClear(b,nextB,null);
        a.linkData.remove(second); b.linkData.remove(first);
        a.alignmentMask=nextA; b.alignmentMask=nextB;topologyRevision++; alignmentChanged();
        clearChangedRecoveryStop(a);clearChangedRecoveryStop(b);
    }
    /** A track edit can turn a maneuver stop into points or a terminus.
     * Discard only that optional goal; retain the real itinerary and yield. */
    private void clearChangedRecoveryStop(Node node) {
        if(node.links.size()==2)return;
        for(Train train:trainData.values())if(node.name.equals(train.trafficRecoveryRail)) {
            train.trafficRecoveryRail=null;
            train.recoveryStatus="Recovery station track changed; continuing the saved schedule.";
        }
    }
    /** Compatibility only: select a deterministic opposite pair, independent of train heading. */
    @Deprecated
    public synchronized void setSwitch(String rail, String exit) {
        Node n = node(rail);
        if (!n.links.contains(exit)) throw problem("Switch exit must be connected to this rail.");
        setJunctionAlignment(rail,oppositePair(n,exit));
    }

    public synchronized int getJunctionAlignment(String rail) { return node(rail).alignmentMask; }
    public synchronized long getJunctionRevision() { return alignmentRevision; }
    /** New track choices support straight, 45-degree and 90-degree turns.
     * Adjacent outward sides describe a 135-degree hairpin. Existing track
     * geometry remains readable and routable for saved-world compatibility.
     */
    public static boolean isBuildableAlignment(int mask) {
        if(mask<0||mask>255||Integer.bitCount(mask)!=2)return false;
        int first=Integer.numberOfTrailingZeros(mask);
        int second=Integer.numberOfTrailingZeros(mask&~(1<<first));
        int separation=Math.abs(first-second);
        return Math.min(separation,8-separation)>=2;
    }
    public synchronized List<Integer> junctionAlignments(String rail) {
        int connected=trackMask(rail); List<Integer> result=new ArrayList<Integer>();
        for(int mask=1;mask<256;mask++)if(Integer.bitCount(mask)==2&&(mask&connected)==mask)result.add(mask);
        return Collections.unmodifiableList(result);
    }
    public synchronized void setJunctionAlignment(String rail,int mask) {
        Node n=node(rail); validateAlignment(n,mask); requireAlignmentClear(n,mask,null);
        if(n.alignmentMask!=mask) { n.alignmentMask=mask; alignmentChanged(); }
    }
    public synchronized int cycleJunctionAlignment(String rail) {
        Node n=node(rail); List<Integer> choices=junctionAlignments(rail);
        if(choices.isEmpty())throw problem("Connect at least two rail sides before aligning this junction.");
        int at=choices.indexOf(n.alignmentMask),next=choices.get((at+1)%choices.size());
        setJunctionAlignment(rail,next); return next;
    }
    public synchronized boolean requestJunctionAlignment(String name,String rail,int mask) {
        Train t=train(name); Node n=node(rail); validateAlignment(n,mask);
        if(mask==0)throw problem("A train must request two connected junction sides.");
        if(signalSystem.holdsJunction(name,rail)&&n.alignmentMask!=mask)return false;
        if(n.alignmentOwner!=null&&!n.alignmentOwner.equals(t.name))return false;
        try { requireAlignmentClear(n,mask,t.name); }
        catch(IllegalArgumentException occupied) { return false; }
        if(n.alignmentMask!=mask||!t.name.equals(n.alignmentOwner)) {
            n.alignmentMask=mask; n.alignmentOwner=t.name; n.alignmentEntered=occupies(t,n);
            lockedJunctions.add(n.name); alignmentChanged();
        }
        return true;
    }
    public synchronized boolean releaseJunctionAlignment(String name,String rail) {
        Train t=train(name); Node n=node(rail);
        if(signalSystem.holdsJunction(name,rail))return false;
        if(!name.equals(n.alignmentOwner)||occupies(t,n))return false;
        n.alignmentOwner=null; n.alignmentEntered=false; lockedJunctions.remove(n.name); alignmentChanged(); return true;
    }
    private int side(Node n,Node adjacent) { return 1<<direction(adjacent.tileX-n.tileX,adjacent.tileY-n.tileY); }
    private void validateAlignment(Node n,int mask) {
        if(mask<0||mask>255||(mask!=0&&Integer.bitCount(mask)!=2)||(mask&trackMask(n.name))!=mask)
            throw problem("Choose two distinct connected compass sides, or an unset junction.");
    }
    private int alignmentAfterConnection(Node n,Node other,boolean add) {
        int connected=trackMask(n.name); connected=add?connected|side(n,other):connected&~side(n,other);
        int degree=Integer.bitCount(connected);
        if(degree<2)return 0;
        if(degree==2)return connected;
        if(add&&n.links.size()==2)return 0;
        return (n.alignmentMask&connected)==n.alignmentMask?n.alignmentMask:0;
    }
    private void requireAlignmentClear(Node n,int mask,String requester) {
        if(n.alignmentOwner!=null&&!n.alignmentOwner.equals(requester))throw problem("Junction "+n.name+" is reserved by train "+n.alignmentOwner+".");
        if(mask==n.alignmentMask)return;
        for(Train t:trains.values())if(occupies(t,n))throw problem("Junction "+n.name+" is occupied by train "+t.name+".");
    }
    private boolean occupies(Train t,Node n) {
        Geometry g=geometry(t);
        for(int i=0;i<t.path.size();i++)if(t.path.get(i).equals(n.name)
                &&g.ends[i]>rearDistance(t)-BUFFER+EPS&&g.starts[i]<t.locomotiveDistance+BUFFER-EPS)return true;
        return false;
    }
    private int oppositePair(Node n,String selected) {
        Node chosen=node(selected); int bit=side(n,chosen),direction=direction(chosen.tileX-n.tileX,chosen.tileY-n.tileY);
        int best=-1,bestSeparation=-1;
        for(String other:n.links)if(!other.equals(selected)) {
            Node candidate=node(other); int compass=direction(candidate.tileX-n.tileX,candidate.tileY-n.tileY);
            int separation=Math.min((compass-direction+8)%8,(direction-compass+8)%8);
            if(separation>bestSeparation||(separation==bestSeparation&&compass<best)) { best=compass; bestSeparation=separation; }
        }
        if(best<0)throw problem("Connect a second side before aligning this junction.");
        return bit|(1<<best);
    }
    private String pairedExit(Node n,String incoming) {
        if(incoming==null||n.alignmentMask==0)return null;
        int entered=side(n,node(incoming)); if((n.alignmentMask&entered)==0)return null;
        int other=n.alignmentMask&~entered;
        for(String next:n.links)if(side(n,node(next))==other)return next;
        return null;
    }
    private void releaseClearedJunctions() {
        for(String name:new ArrayList<String>(lockedJunctions)) {
            Node n=nodes.get(name); if(n==null){lockedJunctions.remove(name);continue;}
            Train owner=trains.get(n.alignmentOwner);
            if(owner!=null&&signalSystem.holdsJunction(owner.name,name))continue;
            boolean occupied=owner!=null&&occupies(owner,n);
            if(occupied&&!n.alignmentEntered) { n.alignmentEntered=true; alignmentChanged(); }
            if(owner==null||(n.alignmentEntered&&!occupied)||!owner.path.contains(name)) {
                n.alignmentOwner=null; n.alignmentEntered=false; lockedJunctions.remove(name); alignmentChanged();
            }
        }
    }
    private void releasePendingJunctions(Train t) {
        for(String name:new ArrayList<String>(lockedJunctions)) {
            Node n=nodes.get(name);
            if(signalSystem.holdsJunction(t.name,name))continue;
            if(n!=null&&t.name.equals(n.alignmentOwner)&&!occupies(t,n)) {
                n.alignmentOwner=null; n.alignmentEntered=false; lockedJunctions.remove(name); alignmentChanged();
            }
        }
    }
    public synchronized void setStation(String rail, boolean station, double dwellSeconds) {
        finiteRange(dwellSeconds, 0, 3600, "Station dwell");
        Node n = node(rail); n.station = station; n.dwellSeconds = dwellSeconds;
    }

    public synchronized Train createTrain(String name, long ownerId, List<Long> cars) {
        if (nodes.isEmpty()) throw problem("Build some connected rail first.");
        Node start = nodes.values().iterator().next();
        String towards = start.links.isEmpty() ? null : start.links.iterator().next();
        return createTrain(name, ownerId, cars, start.name, towards);
    }
    public synchronized Train createTrain(String name, long ownerId, List<Long> cars, String start, String towards) {
        checkedName(name);
        if (trains.containsKey(name)) throw problem("A train named " + name + " already exists.");
        validateCars(cars, null);
        Node origin = node(start);
        if (towards != null && !origin.links.contains(towards)) throw problem("Facing rail must be connected to the starting rail.");
        if(origin.links.size()>2&&(towards==null||pairedExit(origin,towards)==null))
            throw problem("Align junction "+origin.name+" with the locomotive's connected pair before placing it here.");
        Train t = new Train(name, ownerId, cars);
        t.path.add(start); t.initialTowards = towards;
        seedInitialBend(t, towards);
        extendBehind(t, (cars.size()-1) * CAR_SPACING, towards);
        resolveInitialDeparture(t, towards);
        extendBehind(t, (cars.size()-1) * CAR_SPACING, towards);
        refresh(t);
        if (conflicts(t, t.locomotiveDistance)) throw problem("The train would overlap another train or itself.");
        if (!guard(t.poses)) throw problem("The train cannot safely occupy these rail tiles.");
        trainData.put(name, t); return t;
    }
    public synchronized void removeTrain(String name) {
        Train t = train(name); requireStopped(t);signalSystem.removeTrain(name);trainData.remove(name); releaseClearedJunctions();
    }
    public synchronized void addCar(String name, long carId) {
        Train t = train(name); requireStopped(t);
        signalSystem.traffic().requireConsistExpansionSafe(name);
        List<Long> cars = new ArrayList<Long>(t.carIds); cars.add(carId); validateCars(cars, t);
        List<String> old = new ArrayList<String>(t.path); double oldDistance = t.locomotiveDistance;
        try {
            extendBehind(t, (cars.size()-1)*CAR_SPACING, t.initialTowards);
            resolveInitialDeparture(t, t.initialTowards);
            extendBehind(t, (cars.size()-1)*CAR_SPACING, t.initialTowards);
            t.carData.add(carId); refresh(t);
            if (conflicts(t, t.locomotiveDistance)) throw problem("There is not enough unoccupied track behind the train.");
            if (!guard(t.poses)) throw problem("The coupled cart would occupy an unsafe tile.");
        } catch (RuntimeException failure) {
            t.path.clear(); t.path.addAll(old); t.locomotiveDistance = oldDistance;
            if (t.carData.size() == cars.size()) t.carData.remove(t.carData.size()-1);
            refresh(t); throw failure;
        }
    }
    public synchronized void removeCar(String name, long carId) {
        Train t = train(name); requireStopped(t);
        if (t.carIds.get(0).longValue() == carId) throw problem("The locomotive cannot be uncoupled; disband the train first.");
        if (t.carIds.get(t.carIds.size()-1).longValue() != carId)
            throw problem("Uncouple the last cart first.");
        t.carData.remove(t.carData.size()-1); prune(t); refresh(t);
        t.passengerServed.remove(carId);
        if(t.passengerStopCarId==carId) clearPassengerStop(t);
    }
    public synchronized void start(String name) {
        Train t = train(name); validateTuning(t);
        if(t.serviceStopRail!=null) { t.powered=true;t.status="Freight service";return; }
        int previousDirection=t.direction;
        if(!selectInitialTraction(t))return;
        if(t.direction!=previousDirection) { signalSystem.cancelUnused(name);releasePendingJunctions(t); }
        t.powered = true; t.status = t.passengerStopRail != null ? "Passenger stop" : t.automated ? "Automatic" : "Manual";
    }
    /** Resume a saved service with its actual traction, itinerary cursor and dwell unchanged. */
    public synchronized void resumeScheduled(String name) {
        Train t=train(name);validateTuning(t);
        if(!t.automated||t.route.isEmpty())throw problem("Choose a saved automatic route before resuming this service.");
        // A newly claimed bare engine has no established departure history yet.
        // It still needs the ordinary one-time facing/terminus traction choice.
        if(t.path.size()==1&&t.initialTowards!=null&&!t.recoveringTraffic) { start(name);return; }
        signalSystem.traffic().restoreOccupiedCorridors();
        t.powered=true;
        t.status=t.serviceStopRail!=null?"Freight service":t.passengerStopRail!=null?"Passenger stop":t.dwellRemaining>EPS?"Station dwell":t.recoveringTraffic?"Resuming traffic yield":"Automatic";
    }
    public synchronized void stop(String name) {
        Train t = train(name); t.powered = false; t.speed = 0; t.status = "Stopped";signalSystem.cancelUnused(name); releasePendingJunctions(t);
    }
    public synchronized void reverse(String name) {
        Train t = train(name); requireStopped(t);signalSystem.traffic().requireReversalSafe(name);
        signalSystem.cancelUnused(name); releasePendingJunctions(t);
        resolveInitialOrientation(t);
        t.direction = -t.direction; t.status = "Reversed; stopped"; refresh(t);
        clearRouteServiceStop(t);
        t.stationTurnback=null;
        t.recoveringTraffic=false;t.recoveryStatus=null;t.trafficWaitSeconds=0;t.trafficRecoveryRail=null;t.trafficRecoveryExit=null;
    }
    public synchronized void setRoute(String name, List<String> route, double dwellSeconds) {
        Train t = train(name); requireStopped(t);
        if (route == null || route.size() > 1000) throw problem("Route must contain at most 1000 stops.");
        finiteRange(dwellSeconds, 0, 3600, "Route dwell");
        for (String stop : route) node(stop);
        if (!route.isEmpty() && shortest(t.path.get(0),route.get(0)).isEmpty())
            throw problem("The first route stop is not connected to this train.");
        for (int i=1; i<route.size(); i++)
            if (shortest(route.get(i-1), route.get(i)).isEmpty()) throw problem("All route stops must be connected.");
        signalSystem.cancelUnused(name);releasePendingJunctions(t); t.routeData.clear(); t.routeData.addAll(route); t.routeIndex = 0;
        t.automated = !route.isEmpty(); t.routeDwellSeconds = dwellSeconds; t.dwellRemaining = 0;
        clearRouteServiceStop(t);
        t.stationTurnback=null;
        t.recoveringTraffic=false;t.recoveryStatus=null;t.trafficWaitSeconds=0;t.trafficRecoveryRail=null;t.trafficRecoveryExit=null;
    }
    public synchronized void setRoute(String name, List<String> route) { setRoute(name, route, 5.0); }

    /** Release only the held arrival actually serviced; STOP never powers a train back on. */
    public synchronized boolean releaseRouteServiceStop(String name,String rail,long serial) {
        Train t=train(name);
        if(t.serviceStopRail==null||!t.serviceStopRail.equals(rail)||t.serviceStopSerial!=serial)return false;
        clearRouteServiceStop(t);
        if(t.powered)t.status=t.passengerStopRail!=null?"Passenger stop":t.automated?"Automatic":"Manual";
        return true;
    }
    private static void clearRouteServiceStop(Train t) {
        if(t.serviceStopRail!=null)t.dwellRemaining=0;
        t.serviceStopRail=null;
    }

    /** Release only the arrival that native service actually completed. An operator's STOP wins. */
    public synchronized boolean releasePassengerStop(String name,String expectedRail,long expectedSerial) {
        Train t=train(name);
        if(t.passengerStopRail==null||!t.passengerStopRail.equals(expectedRail)||t.passengerStopSerial!=expectedSerial)
            return false;
        clearPassengerStop(t);
        return true;
    }

    private void clearPassengerStop(Train t) {
        t.passengerStopRail=null; t.passengerStopCarId=-10L;
        if(t.powered) t.status=t.dwellRemaining>0?"Station dwell":t.automated?"Automatic":"Manual";
        else if("Passenger stop".equals(t.status))
            t.status=t.passengerResumeStatus==null?"Stopped":t.passengerResumeStatus;
        t.passengerResumeStatus=null;
    }

    /** Bounded substeps preserve junction and block safety even after a long server tick. */
    public synchronized Map<String, List<Pose>> tick(double seconds) {
        finiteRange(seconds, 0, 60, "Tick duration");
        int count = Math.max(1, (int)Math.ceil(seconds / 0.1)); double dt = seconds / count;
        // Occupied connector directions must be inferred before any resumed train
        // can acquire a fresh authority after a restart.
        signalSystem.traffic().restoreOccupiedCorridors();
        for (int n=0; n<count; n++) {
            signalSystem.traffic().prepareMovement();
            for (Train t : trainData.values()) { advance(t, dt);signalSystem.updateClearance(); releaseClearedJunctions(); }
            for(Train t:trainData.values()) {
                if(trafficWaiting(t))t.trafficWaitSeconds+=dt;
                else t.trafficWaitSeconds=0;
            }
            resolveTrafficJams(null,false);
        }
        Map<String,List<Pose>> result = new LinkedHashMap<String,List<Pose>>();
        for (Train t : trainData.values()) result.put(t.name, t.poses);
        return Collections.unmodifiableMap(result);
    }

    /** Try a safe yield now; permissions can restrict the eligible recovering services. */
    public synchronized int resolveTrafficJams() { return resolveTrafficJams(null,true); }
    public synchronized int resolveTrafficJams(Collection<String> permittedTrains) {
        return resolveTrafficJams(permittedTrains,true);
    }
    public synchronized String trafficRecoveryStatus(String name) {
        Train t=train(name);
        if(t.recoveringTraffic&&!t.powered)return "Traffic recovery paused; Power on to continue yielding along the saved route.";
        return t.recoveryStatus==null?"":t.recoveryStatus;
    }
    String loopExitIntent(Train train) { return train.trafficRecoveryExit; }
    boolean hasRecoveryCircuit(Train train) { return train.trafficRecoveryRail!=null; }
    void loopExitIntent(Train train,String junction) { train.trafficRecoveryExit=junction; }
    boolean isRecoveringTraffic(String name) { Train t=trains.get(name);return t!=null&&t.recoveringTraffic; }
    private boolean trafficWaiting(Train t) {
        if(!t.powered||!t.automated||t.route.isEmpty()||t.speed>EPS||t.dwellRemaining>EPS||t.passengerStopRail!=null)return false;
        return t.status.startsWith("Signal ")||t.status.startsWith("Waiting ")
                ||t.status.startsWith("Track occupied")||t.status.startsWith("Junction ");
    }
    private int resolveTrafficJams(Collection<String> permitted,boolean immediate) {
        boolean eligible=false;
        for(Train t:trains.values())if(t.powered&&t.automated&&!t.route.isEmpty()
                &&t.speed<EPS&&t.dwellRemaining<EPS&&t.passengerStopRail==null
                &&(immediate||t.trafficWaitSeconds>=15)&&(permitted==null||permitted.contains(t.name))) { eligible=true;break; }
        if(!eligible)return 0;
        List<Train> ordered=new ArrayList<Train>(trains.values());
        Collections.sort(ordered,(a,b)->a.name.compareTo(b.name));
        Set<String> attempted=new HashSet<String>();int started=0;
        for(Train a:ordered)for(Train b:ordered) {
            if(a==b||attempted.contains(a.name)||attempted.contains(b.name)||a.speed>EPS||b.speed>EPS
                    ||(!headOn(a,b)&&!mutualHeadway(a,b)&&!signalSystem.traffic().opposingEntered(a.name,b.name)))continue;
            Train first=a.name.compareTo(b.name)>0?a:b,second=first==a?b:a;
            // Clear a train already leaving its served station before turning
            // an incoming station approach into a second backing manoeuvre.
            boolean aClearing=clearingServedStation(a),bClearing=clearingServedStation(b);
            if(aClearing!=bClearing) {first=aClearing?a:b;second=first==a?b:a;}
            // Restore an opposed backing maneuver before undoing a service
            // that has already recovered engine-leading travel. Feasibility
            // still decides whether that train has protected space to yield.
            if(a.direction!=b.direction) {first=a.direction<0?a:b;second=first==a?b:a;}
            boolean recovered=false;
            for(Train candidate:Arrays.asList(first,second)) {
                if(!candidate.powered||!candidate.automated||candidate.route.isEmpty()
                        ||candidate.passengerStopRail!=null||candidate.dwellRemaining>EPS
                        ||(permitted!=null&&!permitted.contains(candidate.name))
                        ||(!immediate&&candidate.trafficWaitSeconds<15))continue;
                Train obstacle=candidate==a?b:a;
                Set<String> chain=new LinkedHashSet<String>();chain.add(obstacle.name);
                if(beginTrafficYieldChain(candidate,obstacle,permitted,immediate,attempted,chain)!=null) {
                    started++;recovered=true;attempted.addAll(chain);break;
                }
            }
            if(recovered) { attempted.add(a.name);attempted.add(b.name); }
        }
        // A pair of inbound receiving-loop queues can prevent the oldest
        // opposite request from entering without ever coming nose to nose.
        // Reverse only one clear rear; ordinary blocks still protect its motion.
        if(started==0)for(Map.Entry<String,String> request:signalSystem.traffic().receivingYieldCandidates().entrySet()) {
            Train candidate=trains.get(request.getKey()),obstacle=trains.get(request.getValue());
            if(candidate==null||obstacle==null||!candidate.powered||!candidate.automated||candidate.route.isEmpty()
                    ||candidate.speed>EPS||candidate.dwellRemaining>EPS||candidate.passengerStopRail!=null
                    ||(permitted!=null&&!permitted.contains(candidate.name))||(!immediate&&candidate.trafficWaitSeconds<15))continue;
            Set<String> chain=new LinkedHashSet<String>();chain.add(obstacle.name);
            if(beginTrafficYieldChain(candidate,obstacle,permitted,immediate,attempted,chain)!=null) { started++;break; }
        }
        return started;
    }
    private boolean clearingServedStation(Train train) {
        if(!train.automated||train.route.size()<2)return false;
        String station=train.route.get((train.routeIndex+train.route.size()-1)%train.route.size());
        return signalOccupiesNode(train,station);
    }
    /**
     * A proven deadlock may need room behind several queued trains. Move only a
     * clear tail now; never reverse the trapped front before that space exists.
     * Ordinary following queues do not enter this search on their own.
     */
    private Train beginTrafficYieldChain(Train candidate,Train obstacle,Collection<String> permitted,boolean immediate,
                                        Set<String> attempted,Set<String> chain) {
        if(chain.size()>=64||attempted.contains(candidate.name)||!chain.add(candidate.name)
                ||!candidate.powered||!candidate.automated||candidate.route.isEmpty()||candidate.speed>EPS||candidate.stationTurnback!=null
                ||candidate.dwellRemaining>EPS||candidate.passengerStopRail!=null
                ||(permitted!=null&&!permitted.contains(candidate.name))||(!immediate&&candidate.trafficWaitSeconds<15))return null;
        if(beginTrafficYield(candidate,obstacle))return candidate;
        List<String> retreat=trafficYieldPath(candidate,false);
        if(!retreat.isEmpty()) {
            Set<String> resources=signalRouteResources(retreat);
            // Follow the physical queue in retreat order, rather than arbitrarily
            // reversing a reservation owner elsewhere on the railway.
            Set<Train> blockers=new LinkedHashSet<Train>();
            for(String rail:retreat)for(Train other:trains.values())if(other!=candidate
                    &&signalOccupiesNode(other,rail)&&intersects(resources,signalOccupiedResources(other)))blockers.add(other);
            for(Train blocker:blockers) {
                if(blocker.recoveringTraffic&&blocker.powered&&blocker.speed>EPS) {
                    candidate.recoveryStatus="Waiting for "+blocker.name+" to clear recovery space before yielding to "+obstacle.name+".";
                    return null;
                }
                Train yielding=beginTrafficYieldChain(blocker,candidate,permitted,immediate,attempted,chain);
                if(yielding!=null) {
                    candidate.recoveryStatus="Waiting for "+yielding.name+" to clear recovery space before yielding to "+obstacle.name+".";
                    return yielding;
                }
            }
        }
        candidate.recoveryStatus="Traffic jam with "+obstacle.name+": no protected space to back out. Clear a receiving track or move the parked train.";
        return null;
    }
    private boolean headOn(Train a,Train b) {
        if(a.poses.isEmpty()||b.poses.isEmpty())return false;
        Pose first=a.poses.get(a.direction>0?0:a.poses.size()-1),second=b.poses.get(b.direction>0?0:b.poses.size()-1);
        if(Math.hypot(first.x-second.x,first.y-second.y)>16||!shortTrackConnection(first,second))return false;
        double ar=Math.toRadians(first.rotation),br=Math.toRadians(second.rotation);
        double dx=second.x-first.x,dy=second.y-first.y,length=Math.hypot(dx,dy);
        if(length<EPS)return false;
        double towardA=(Math.sin(ar)*dx-Math.cos(ar)*dy)*a.direction/length;
        double towardB=(-Math.sin(br)*dx+Math.cos(br)*dy)*b.direction/length;
        return towardA>.35&&towardB>.35&&(Math.cos(ar-br)*a.direction*b.direction<-.5
                ||(approachingRail(a,second.railName)&&approachingRail(b,first.railName)));
    }
    /** Two different approaches can trap each other at the physical clearance
     * before their common junction even though their headings are perpendicular.
     * Ordinary following is one-way and must never trigger this recovery.
     */
    private boolean mutualHeadway(Train a,Train b) {
        if(a.poses.isEmpty()||b.poses.isEmpty())return false;
        List<Pose> first=headwayProbe(a),second=headwayProbe(b);
        return first!=null&&second!=null&&b.name.equals(headwayObstruction(a,first))&&a.name.equals(headwayObstruction(b,second));
    }
    private List<Pose> headwayProbe(Train train) {
        Geometry g=geometry(train);
        double room=train.direction>0?g.total-train.locomotiveDistance:rearDistance(train);
        if(room<EPS)return null;
        return poses(train,train.locomotiveDistance+train.direction*Math.min(.05,room));
    }
    /** A bend can put genuinely opposed trains at right angles in world space. */
    private boolean approachingRail(Train train,String target) {
        String current=signalLeadingRail(train),previous=signalIncomingRail(train);
        if(current==null||target==null)return false;
        double distance=0;Set<String> visited=new HashSet<String>();
        for(int step=0;step<=4&&current!=null&&visited.add(current);step++) {
            if(current.equals(target))return true;
            Node at=node(current);String next=null;
            if(at.links.size()>2) {
                try { next=signalIntendedNext(train,current,previous,train.routeIndex); }
                catch(IllegalArgumentException unavailable) { return false; }
            } else for(String link:at.links)if(!link.equals(previous)) { next=link;break; }
            if(next==null||next.equals(previous))return false;
            distance+=Math.hypot(node(next).worldX()-at.worldX(),node(next).worldY()-at.worldY());
            if(distance>16+EPS)return false;
            previous=current;current=next;
        }
        return false;
    }
    private boolean shortTrackConnection(Pose first,Pose second) {
        if(first.railName==null||second.railName==null)return nearbyConnectedTrack(first,second);
        Set<String> visited=new HashSet<String>(),frontier=new HashSet<String>();frontier.add(first.railName);
        for(int depth=0;depth<=4;depth++) {
            if(frontier.contains(second.railName))return true;
            Set<String> next=new HashSet<String>();
            for(String rail:frontier)if(visited.add(rail))for(String link:node(rail).links)if(!visited.contains(link))next.add(link);
            frontier=next;
        }
        return false;
    }
    /** Pure retreat feasibility, including the occupied-convoy reversal guard. */
    private List<String> trafficYieldPath(Train t,boolean requireClear) {
        if(t.stationTurnback!=null)return Collections.emptyList();
        // Reversing changes traction alone. Retain the complete body geometry and
        // refuse an entered convoy or a switch that the rear would have to alter.
        try { signalSystem.traffic().requireRecoveryReversalSafe(t.name); }
        catch(IllegalArgumentException unsafe) { return Collections.emptyList(); }
        List<String> originalPath=new ArrayList<String>(t.path);double originalDistance=t.locomotiveDistance;
        String originalTowards=t.initialTowards;List<Pose> originalPoses=t.poses;
        resolveInitialOrientation(t);
        int previousDirection=t.direction;t.direction=-previousDirection;
        List<String> path;
        try {
            String from=signalLeadingRail(t),previous=signalIncomingRail(t);
            String recovery=signalSystem.traffic().directLoopExit(t)==null?signalSystem.traffic().recoveryStation(t):null;
            path=engineLeadingPath(t,from,previous,recovery==null?t.route.get(t.routeIndex):recovery,false);
            if(path.size()<2)return Collections.emptyList();
            double required=(t.carIds.size()-1)*CAR_SPACING+12.0,distance=0;
            List<String> clear=new ArrayList<String>();clear.add(path.get(0));
            for(int i=1;i<path.size()&&distance<required-EPS;i++) {
                Node before=node(path.get(i-1)),next=node(path.get(i));
                distance+=Math.hypot(next.worldX()-before.worldX(),next.worldY()-before.worldY());clear.add(next.name);
            }
            if(distance<required-EPS||reversalObstruction(t,clear)!=null)return Collections.emptyList();
            if(requireClear) {
                Set<String> resources=signalRouteResources(clear);
                for(Train other:trains.values())if(other!=t&&intersects(resources,signalOccupiedResources(other)))return Collections.emptyList();
                // The prospective reversed traction is part of corridor safety.
                // Query before restoring it, exactly as for a direct yield.
                if(signalSystem.conflictsBlockResources(t.name,resources)
                        ||signalSystem.traffic().conflictsRecoveryResources(t.name,resources))return Collections.emptyList();
            }
            return clear;
        } finally {
            t.direction=previousDirection;restorePath(t,originalPath,originalDistance,originalTowards);t.poses=originalPoses;
        }
    }
    private boolean beginTrafficYield(Train t,Train obstacle) {
        if(trafficYieldPath(t,true).isEmpty())return false;
        int previousDirection=t.direction;
        resolveInitialOrientation(t);
        signalSystem.prepareTrafficReversal(t.name);releasePendingJunctions(t);
        // The recovery-only guard above also permits escape from an old saved
        // head-on with an opposing owner; ordinary convoy reversal stays barred.
        t.direction=-previousDirection;t.speed=0;t.trafficWaitSeconds=0;t.recoveringTraffic=true;
        t.trafficRecoveryExit=signalSystem.traffic().directLoopExit(t);
        t.trafficRecoveryRail=t.trafficRecoveryExit==null?signalSystem.traffic().recoveryStation(t):null;
        t.recoveryStatus="Yielding to "+obstacle.name+(t.direction<0
                ?"; backing safely to continue the saved schedule.":"; restoring forward travel along the saved schedule.");
        t.status=t.recoveryStatus;
        return true;
    }

    /** Physical history, rather than an absent post-restart reservation, determines corridor direction. */
    String signalCorridorEntry(Train train,List<String> corridor) {
        for(int i=1;i<train.path.size();i++) {
            int first=corridor.indexOf(train.path.get(i-1)),last=corridor.indexOf(train.path.get(i));
            if(first>=0&&last>=0&&Math.abs(first-last)==1)
                return (last-first)*train.direction>0?corridor.get(0):corridor.get(corridor.size()-1);
        }
        String leading=signalLeadingRail(train),towards=train.initialTowards;
        int first=corridor.indexOf(leading),last=corridor.indexOf(towards);
        if(first>=0&&last>=0&&Math.abs(last-first)==1)
            return (last-first)*train.direction>0?corridor.get(0):corridor.get(corridor.size()-1);
        return null;
    }
    /** Query the existing future bend without changing its physical authority. */
    String unenteredExit(Train train,String rail) {
        Geometry g=geometry(train);double leader=leadingDistance(train);
        for(int i=0;i<train.path.size();i++)if(train.path.get(i).equals(rail)&&train.direction*(g.centers[i]-leader)>EPS) {
            int next=i+train.direction;
            return next>=0&&next<train.path.size()?train.path.get(next):null;
        }
        return null;
    }
    /** Discard a stale future bend, never the curve underneath an occupied car. */
    void resetUnenteredExit(String name,String rail,String expectedExit) {
        Train train=train(name);Node junction=node(rail);if(occupies(train,junction))throw problem("The receiving junction is already occupied.");
        Geometry old=geometry(train);double leader=leadingDistance(train);
        for(int i=0;i<train.path.size();i++)if(train.path.get(i).equals(rail)&&train.direction*(old.centers[i]-leader)>EPS) {
            int next=i+train.direction;
            if(next<0||next>=train.path.size()||train.path.get(next).equals(expectedExit))return;
            if(train.direction>0) {
                while(train.path.size()>i+1)train.path.remove(train.path.size()-1);
            } else if(i>0&&i+1<train.path.size()) {
                double reference=old.starts[i+1];
                for(int remove=0;remove<i;remove++)train.path.remove(0);
                train.locomotiveDistance+=geometry(train).starts[1]-reference;
            }
            return;
        }
    }
    private static boolean intersects(Set<String> a,Set<String> b) {
        for(String resource:a)if(b.contains(resource))return true;return false;
    }

    private void advance(Train t, double dt) {
        if(dt<=0) return;
        clearDepartedPassengerStops(t);
        if(t.serviceStopRail!=null) {
            t.speed=0;
            // Existing traffic and station-turnback rules already respect dwell.
            t.dwellRemaining=Math.max(1,t.dwellRemaining);
            holdCurrentPassengerStop(t);
            return;
        }
        if(t.passengerStopRail!=null) {
            t.speed=0;
            if(t.powered&&t.dwellRemaining>0) t.dwellRemaining=Math.max(0,t.dwellRemaining-dt);
            return;
        }
        if(!t.powered) { holdCurrentPassengerStop(t); return; }
        String unsafeAuthority=signalSystem.invalidAuthority(t.name);
        if(unsafeAuthority!=null) { t.speed=0;t.status="Signal RED: "+unsafeAuthority;return; }
        try { validateTuning(t); }
        catch (IllegalArgumentException invalid) { halt(t, invalid.getMessage()); return; }
        if (t.dwellRemaining > 0) {
            t.dwellRemaining = Math.max(0, t.dwellRemaining - dt); t.speed = 0;
            t.status = "Station dwell"; holdCurrentPassengerStop(t); return;
        }
        if(completeRecoveryCircuit(t))return;
        if (t.automated && !t.route.isEmpty() && targetOnTrain(t)) { arrive(t); holdCurrentPassengerStop(t); return; }
        if(holdCurrentPassengerStop(t)) return;
        prepareStationTurnback(t);
        if(advanceStationTurnback(t,true))return;
        if(t.stationTurnback==null&&restoreForwardAfterLoopEntry(t))return;
        if(t.stationTurnback==null&&reverseAtAlignedCentre(t))return;
        t.speed = Math.min(t.maxSpeed, t.speed + t.acceleration*dt);
        double remaining=t.speed*dt;
        // Consume the complete distance budget across tile planning boundaries.
        // A diagonal tile must not run slower simply because its boundary occurs mid-tick.
        for(int crossing=0;crossing<64 && remaining>EPS;crossing++) {
            List<String> originalPath = new ArrayList<String>(t.path);
            double originalDistance = t.locomotiveDistance;
            String originalTowards = t.initialTowards;
            resolveInitialOrientation(t);
            Geometry g = geometry(t);
            double leader = leadingDistance(t);
            SignalGate gate=nextSignalGate(t,g);
            if(gate!=null&&t.direction*(gate.distance-leader)<=EPS&&!signalAuthority(t,gate))return;
            int end = t.direction > 0 ? t.path.size()-1 : 0;
            double boundary = planningBoundary(t,g);
            if (t.direction * (leader-boundary) >= -EPS) {
                String current = t.path.get(end);
                String previous = t.path.size()>1 ? t.path.get(end-t.direction) : null;
                boolean terminal = (!t.automated && node(current).links.size()==(previous==null?0:1)) ||
                    (t.automated && t.route.size()==1 && current.equals(t.route.get(0))) ||
                    (t.stationTurnback!=null&&!t.stationTurnback.departing&&current.equals(t.stationTurnback.waypoint));
                boolean approachingEnd = terminal && Math.abs(leader-g.centers[end])>=EPS;
                String next = approachingEnd&&node(current).links.size()<3 ? null : chooseNext(t, current, previous);
                if (next == null) {
                    // A terminal has a straight half tile ending at its centre.
                    // An unset junction must stop at its entrance before taking a bend.
                    if (!approachingEnd && !t.status.equals("End of track")) return;
                    if (Math.abs(leader-g.centers[end]) < EPS) return;
                    t.powered = true;
                } else if (next.equals(previous)) {
                    if (Math.abs(leader-g.centers[end]) < EPS) {
                        if(!automaticTraction(t,-t.direction))return;
                        t.speed = 0; t.status = "Automatic reversal"; return;
                    }
                    // Continue to the terminal centre before reversing the consist.
                } else {
                    if (t.direction > 0) {
                        t.path.add(next);
                    } else {
                        double oldBoundary = g.ends[0];
                        t.path.add(0, next);
                        Geometry extended = geometry(t);
                        t.locomotiveDistance += extended.ends[1] - oldBoundary;
                    }
                    g = geometry(t);
                    if (conflicts(t, t.locomotiveDistance + t.direction*0.001)) {
                        restorePath(t, originalPath, originalDistance, originalTowards);
                        block(t, "Track occupied"); return;
                    }
                }
            }
            leader = leadingDistance(t);
            // Stop exactly at the next planning boundary or station, even after a long tick.
            double limit = t.direction > 0 ? g.total : 0;
            int last = t.path.size()-1;
            double planning = planningBoundary(t,g);
            if (t.direction*(planning-leader) > EPS) limit = planning;
            if (t.automated && !t.route.isEmpty()) {
                String target = movementTarget(t);
                for (int i=0; i<t.path.size(); i++) if (target.equals(t.path.get(i))) {
                    double station = g.centers[i];
                    if (t.direction*(station-leader)>EPS && t.direction*(limit-station)>0) limit=station;
                }
                for(int i=1;i+1<t.path.size();i++)if(node(t.path.get(i)).links.size()>2) {
                    int behind=i-t.direction;
                    List<String> route=automaticPath(t,t.path.get(i),t.path.get(behind),target);
                    double centre=g.centers[i];
                    if(route.size()>1&&route.get(1).equals(t.path.get(behind))
                            &&t.direction*(centre-leader)>EPS&&t.direction*(limit-centre)>0)limit=centre;
                }
            }
            PassengerEvent passenger=nextPassengerStop(t,g);
            if(passenger!=null) {
                double stopLeader=passenger.engineDistance-(t.direction<0?(t.carIds.size()-1)*CAR_SPACING:0);
                if(t.direction*(limit-stopLeader)>0) limit=stopLeader;
            }
            gate=nextSignalGate(t,g);
            if(gate!=null&&t.direction*(gate.distance-leader)>=-EPS&&!signalAuthority(t,gate)
                    &&t.direction*(limit-gate.distance)>0)limit=gate.distance;
            double distance = Math.min(remaining, Math.max(0, t.direction*(limit-leader)));
            double proposed = t.locomotiveDistance + t.direction*distance;
            List<Pose> candidate = poses(t, proposed);
            String following=headwayObstruction(t,candidate);
            if(following!=null) {
                restorePath(t,originalPath,originalDistance,originalTowards);block(t,"Waiting for space behind train "+following);return;
            }
            if(!alignedConsist(t,proposed)) { restorePath(t,originalPath,originalDistance,originalTowards); return; }
            if (conflicts(t, proposed)) {
                restorePath(t, originalPath, originalDistance, originalTowards); block(t, "Track occupied"); return;
            }
            if (!guard(candidate)) {
                restorePath(t, originalPath, originalDistance, originalTowards); halt(t, "Movement blocked by the world"); return;
            }
            t.locomotiveDistance = proposed; t.poses = candidate; t.status = t.automated ? "Automatic" : "Manual";
            if(!t.recoveringTraffic&&distance>EPS) { t.recoveryStatus=null;t.trafficWaitSeconds=0; }
            if(gate!=null&&Math.abs(leadingDistance(t)-gate.distance)<EPS&&!signalSystem.ownsAuthority(t.name,gate.signal.id)) {
                signalAuthority(t,gate);return;
            }
            prune(t);
            if(advanceStationTurnback(t,false))return;
            if(completeRecoveryCircuit(t))return;
            boolean arrived=t.automated&&!t.route.isEmpty()&&targetOnTrain(t);
            if(arrived) arrive(t);
            if(passenger!=null&&Math.abs(proposed-passenger.engineDistance)<EPS) {
                holdPassengerStop(t,passenger); return;
            }
            if(arrived) return;
            if(t.stationTurnback==null&&reverseAtAlignedCentre(t))return;
            remaining-=distance;
            if(distance<EPS)return;
        }
    }
    private static final class SignalGate {
        final SignalSystem.Signal signal;final double distance;
        SignalGate(SignalSystem.Signal signal,double distance) { this.signal=signal;this.distance=distance; }
    }
    private SignalGate nextSignalGate(Train t,Geometry g) {
        SignalGate nearest=null;int nearestIndex=-1;double leader=leadingDistance(t);
        for(int i=0;i<t.path.size();i++) {
            int behind=i-t.direction;if(behind<0||behind>=t.path.size())continue;
            String rail=t.path.get(i),previous=t.path.get(behind);
            SignalSystem.Signal signal=signalSystem.at(rail,signalSide(rail,previous));if(signal==null)continue;
            double boundary=(t.direction>0?g.starts[i]:g.ends[i])-t.direction*BUFFER;
            if(t.direction*(boundary-leader)<-EPS)continue;
            if(nearest==null||t.direction*(boundary-nearest.distance)<0) {
                nearest=new SignalGate(signal,boundary);nearestIndex=i;
            }
        }
        // Select by original signal order first: an earlier holding line must
        // never hide another signal's RED authority on the curved approach.
        if(nearest!=null&&(signalSystem.traffic().loopEntrance(t.path.get(nearestIndex),t.path.get(nearestIndex-t.direction))
                ||signalSystem.traffic().stationQueueApproach(t,t.path.get(nearestIndex),t.path.get(nearestIndex-t.direction)))) {
            double holding=g.centers[nearestIndex]-t.direction*(TRAIN_CLEARANCE+CAR_SPACING*.5+BUFFER);
            double boundary=t.direction>0?Math.min(nearest.distance,holding):Math.max(nearest.distance,holding);
            // A saved approach may already be beyond the new holding line.
            // Keep its RED gate live without moving it backwards.
            nearest=new SignalGate(nearest.signal,t.direction*(boundary-leader)<0?leader:boundary);
        }
        return nearest;
    }
    private boolean signalAuthority(Train t,SignalGate gate) {
        SignalSystem.RequestResult result=signalSystem.request(t.name,gate.signal.id);
        if(result.granted)return true;
        if(t.direction*(gate.distance-leadingDistance(t))<=EPS) {
            t.speed=0;t.powered=true;t.status="Signal "+gate.signal.id+" RED: "+result.reason;
        }
        return false;
    }
    /** A live, topology-based approach, not an arbitrary remote reservation request. */
    boolean signalApproaching(Train t,String signalId) {
        SignalGate gate=nextSignalGate(t,geometry(t));
        return gate!=null&&gate.signal.id.equals(signalId);
    }
    private boolean alignedConsist(Train t,double position) {
        Geometry g=geometry(t); double low=position-(t.carIds.size()-1)*CAR_SPACING-BUFFER,high=position+BUFFER;
        for(int i=0;i<t.path.size();i++) {
            Node rail=node(t.path.get(i));
            if(rail.links.size()<3||g.ends[i]<=low+EPS||g.starts[i]>=high-EPS)continue;
            int pair=rail.alignmentMask;
            if(i>0&&i+1<t.path.size())pair=side(rail,node(t.path.get(i-1)))|side(rail,node(t.path.get(i+1)));
            else if(pair==0||(i>0&&(pair&side(rail,node(t.path.get(i-1))))==0)
                    ||(i+1<t.path.size()&&(pair&side(rail,node(t.path.get(i+1))))==0)) {
                block(t,"Junction "+rail.name+" is not aligned with this train"); return false;
            }
            if(t.automated) {
                if(!requestJunctionAlignment(t.name,rail.name,pair)) { block(t,"Junction "+rail.name+" is occupied or reserved"); return false; }
            } else if(pair!=rail.alignmentMask) { halt(t,"Junction "+rail.name+" is aligned for different sides"); return false; }
        }
        return true;
    }

    private static final class PassengerEvent {
        final String rail; final long carId; final double engineDistance;
        PassengerEvent(String rail,long carId,double engineDistance) {
            this.rail=rail; this.carId=carId; this.engineDistance=engineDistance;
        }
    }

    private PassengerEvent nextPassengerStop(Train t,Geometry geometry) {
        if(passengerStops==null||passengerStops==NO_PASSENGER_STOPS) return null;
        PassengerEvent closest=null; double nearest=Double.POSITIVE_INFINITY;
        for(int rail=0;rail<t.path.size();rail++) for(int car=0;car<t.carIds.size();car++) {
            long carId=t.carIds.get(car); String name=t.path.get(rail);
            if(name.equals(t.passengerServed.get(carId))) continue;
            double position=geometry.centers[rail]+car*CAR_SPACING;
            if(position<(t.carIds.size()-1)*CAR_SPACING-EPS||position>geometry.total+EPS) continue;
            double ahead=t.direction*(position-t.locomotiveDistance);
            if(ahead<-EPS||ahead>=nearest-EPS) continue;
            if(passengerStops.shouldStop(t,node(name),carId)) {
                closest=new PassengerEvent(name,carId,position); nearest=Math.max(0,ahead);
            }
        }
        return closest;
    }

    private boolean holdCurrentPassengerStop(Train t) {
        PassengerEvent event=nextPassengerStop(t,geometry(t));
        if(event==null||Math.abs(event.engineDistance-t.locomotiveDistance)>=EPS) return false;
        holdPassengerStop(t,event); return true;
    }

    private void holdPassengerStop(Train t,PassengerEvent event) {
        t.passengerResumeStatus=t.status;
        t.passengerStopRail=event.rail; t.passengerStopCarId=event.carId; t.passengerStopSerial++;
        t.passengerServed.put(event.carId,event.rail);
        t.speed=0; t.status="Passenger stop";
    }

    private void clearDepartedPassengerStops(Train t) {
        if(t.passengerServed.isEmpty()) return;
        Geometry geometry=geometry(t);
        Iterator<Map.Entry<Long,String>> entries=t.passengerServed.entrySet().iterator();
        while(entries.hasNext()) {
            Map.Entry<Long,String> served=entries.next();
            int car=t.carIds.indexOf(served.getKey()),rail=t.path.indexOf(served.getValue());
            if(car<0||rail<0||Math.abs(t.locomotiveDistance-car*CAR_SPACING-geometry.centers[rail])>=EPS)
                entries.remove();
        }
    }
    private String chooseNext(Train t, String current, String previous) {
        Node n = node(current);
        String reserved=signalSystem.reservedNext(t.name,current,previous);
        if(reserved!=null)return reserved;
        if (t.automated && !t.route.isEmpty()) {
            t.initialTowards = null;
            String target = t.route.get(t.routeIndex);
            if (current.equals(target) && t.route.size()>1)
                target = t.route.get((t.routeIndex+1)%t.route.size());
            boolean scheduledDeparture=current.equals(t.route.get(t.routeIndex));
            List<String> route = automaticPath(t,current,previous,target,scheduledDeparture||stationaryDeparture(t,current));
            if(n.links.size()>2) {
                String desired=route.size()<2?null:route.get(1);
                if(previous==null) {
                    if(desired==null||pairedExit(n,desired)==null) { block(t,"Align starting junction "+n.name+" before continuing"); return null; }
                    int mask=n.alignmentMask;
                    if(!requestJunctionAlignment(t.name,n.name,mask)) { block(t,"Junction "+n.name+" is occupied or reserved"); return null; }
                    return desired;
                }
                if(desired==null||desired.equals(previous)) {
                    desired=pairedExit(n,previous);
                    if(desired==null) {
                        int pair=oppositePair(n,previous),entered=side(n,node(previous));
                        for(String candidate:n.links)if((pair&~entered)==side(n,node(candidate)))desired=candidate;
                    }
                }
                int pair=side(n,node(previous))|side(n,node(desired));
                if(!requestJunctionAlignment(t.name,n.name,pair)) { block(t,"Junction "+n.name+" is occupied or reserved"); return null; }
                return desired;
            }
            if (route.size() < 2) {
                if (current.equals(target)) { halt(t, "End of track"); return null; }
                halt(t, "Route is disconnected"); return null;
            }
            return route.get(1);
        }
        if(n.links.size()>2) {
            String choice=previous==null?t.initialTowards:pairedExit(n,previous);
            if(choice==null||pairedExit(n,choice)==null) {
                halt(t,n.alignmentMask==0?"Set junction "+n.name+" alignment before continuing":"Junction "+n.name+" is aligned for different sides"); return null;
            }
            t.initialTowards=null; return choice;
        }
        List<String> exits = new ArrayList<String>(n.links); exits.remove(previous);
        if (exits.isEmpty()) { halt(t, "End of track"); return null; }
        // At creation, the nominated facing rail is also the initial departure choice.
        if (t.initialTowards != null && exits.contains(t.initialTowards)) {
            String choice = t.initialTowards; t.initialTowards = null; return choice;
        }
        t.initialTowards = null;
        if (exits.size() == 1) return exits.get(0);
        halt(t, "Set junction " + n.name + " before continuing"); return null;
    }
    private double planningBoundary(Train t,Geometry g) {
        int end=t.direction>0?t.path.size()-1:0;
        double boundary=t.direction>0?g.starts[end]:g.ends[end];
        if(node(t.path.get(end)).links.size()>2)boundary-=t.direction*BUFFER;
        return boundary;
    }
    private boolean reverseAtAlignedCentre(Train t) {
        if(!t.automated||t.route.isEmpty())return false;
        // A body can still cover its previous itinerary station when a
        // head-on yield begins. That is not a newly completed station stop.
        if(signalSystem.traffic().validLoopExitIntent(t,t.trafficRecoveryExit))return false;
        if(t.recoveringTraffic&&(t.direction>0||!stationDeparture(t)))return false;
        if(t.direction<0) {
            if(t.speed<EPS&&stationDeparture(t)&&engineLeadingPath(t,t.route.get(t.routeIndex)).size()>1) {
                if(!automaticTraction(t,1))return true;
                t.speed=0;t.status="Automatic forward departure";
                signalSystem.cancelUnused(t.name);releasePendingJunctions(t);return true;
            }
            // A moving or waiting backing service keeps its acquired direction until
            // a station or an explicit stationary Power On can restore engine-leading travel.
            return false;
        }
        Geometry g=geometry(t); double leader=leadingDistance(t);
        for(int i=0;i<t.path.size();i++)if(Math.abs(leader-g.centers[i])<EPS&&t.speed<EPS) {
            int behind=i-t.direction;
            if(behind<0||behind>=t.path.size())return false;
            List<String> route=automaticPath(t,t.path.get(i),t.path.get(behind),t.route.get(t.routeIndex),true);
            if(route.isEmpty()) {
                String obstruction=backingObstruction(t,t.path.get(i),t.path.get(behind),t.route.get(t.routeIndex));
                if(obstruction!=null) { halt(t,reversalClearanceMessage(obstruction));return true; }
            }
            if(route.size()>1&&route.get(1).equals(t.path.get(behind))) {
                String obstruction=reversalObstruction(t,route);
                if(obstruction!=null) {
                    List<String> forward=automaticPath(t,t.path.get(i),t.path.get(behind),t.route.get(t.routeIndex),false);
                    if(forward.size()>1)return false;
                    halt(t,reversalClearanceMessage(obstruction));return true;
                }
                if(!automaticTraction(t,-t.direction))return true;
                t.speed=0; t.status="Automatic reversal"; return true;
            }
        }
        return false;
    }
    private boolean targetOnTrain(Train t) {
        return railOnTrain(t,t.route.get(t.routeIndex));
    }
    private boolean railOnTrain(Train t,String target) {
        double low = rearDistance(t)-EPS, high=t.locomotiveDistance+EPS;
        Geometry g=geometry(t);
        for (int i=0; i<t.path.size(); i++)
            if (g.centers[i] >= low && g.centers[i] <= high && target.equals(t.path.get(i))) return true;
        return false;
    }
    boolean isTrafficRecoveryStop(Train train,String rail) {
        return train.recoveringTraffic&&rail.equals(train.trafficRecoveryRail)
                ||train.stationTurnback!=null&&!train.stationTurnback.departing&&rail.equals(train.stationTurnback.waypoint);
    }
    private boolean completeRecoveryCircuit(Train train) {
        if(train.trafficRecoveryRail==null||!railOnTrain(train,train.trafficRecoveryRail))return false;
        if(train.trafficRecoveryRail.equals(train.route.get(train.routeIndex))) { train.trafficRecoveryRail=null;return false; }
        train.speed=0;
        if(train.direction<0&&!automaticTraction(train,1))return true;
        signalSystem.prepareTrafficReversal(train.name);releasePendingJunctions(train);
        train.trafficRecoveryRail=null;train.trafficWaitSeconds=0;
        train.recoveryStatus="Recovery circuit complete; continuing the saved schedule in forward travel.";
        train.status=train.recoveryStatus;return true;
    }
    private String movementTarget(Train train) {
        if(train.stationTurnback!=null&&!train.stationTurnback.departing)return train.stationTurnback.waypoint;
        return train.trafficRecoveryRail==null?train.route.get(train.routeIndex):train.trafficRecoveryRail;
    }
    /** Choose a maneuver only at a real stopped station departure. Forecasts
     * never create live state or count its clearance waypoint as an arrival. */
    private void prepareStationTurnback(Train train) {
        if(train.stationTurnback!=null||!train.automated||train.route.size()<2||train.direction<0||train.speed>EPS)return;
        String station=train.route.get((train.routeIndex+train.route.size()-1)%train.route.size());
        Geometry g=geometry(train);int at=train.path.indexOf(station);
        if(at<1||Math.abs(train.locomotiveDistance-g.centers[at])>EPS)return;
        StationTurnback.Plan plan=StationTurnback.find(this,train,station,train.path.get(at-1),train.route.get(train.routeIndex));
        if(plan==null||reversalObstruction(train,plan.backingPath)!=null)return;
        // A parked obstruction is not a usable backing lane. Normal routing
        // may still reach another turnaround without moving that vehicle.
        for(Train other:trains.values())if(other!=train&&!other.powered&&intersects(plan.resources,signalOccupiedResources(other)))return;
        train.stationTurnback=plan;
    }
    private boolean advanceStationTurnback(Train train,boolean mayChangeTraction) {
        StationTurnback.Plan plan=train.stationTurnback;if(plan==null)return false;
        if(!StationTurnback.valid(this,train,plan)) { train.stationTurnback=null;return false; }
        if(plan.departing) {
            boolean clear=!signalOccupiesNode(train,plan.junction);
            for(Pose pose:train.poses)if(plan.resources.contains("N:"+pose.railName))clear=false;
            if(clear)train.stationTurnback=null;
            return false;
        }
        if(train.direction>0) {
            for(Train other:trains.values())if(other!=train&&intersects(plan.resources,signalOccupiedResources(other))) {
                block(train,"Waiting for "+other.name+" to clear the local station turnaround.");return true;
            }
            if(signalSystem.conflictsResources(train.name,plan.resources)) {
                block(train,"Waiting for a reserved route to clear the local station turnaround.");return true;
            }
            try { signalSystem.traffic().requireReversalSafe(train.name); }
            catch(IllegalArgumentException unavailable) { block(train,unavailable.getMessage());return true; }
            signalSystem.prepareTrafficReversal(train.name);releasePendingJunctions(train);
            if(!signalSystem.alignStationTurnback(train.name,false)) { block(train,"Waiting for the local turnaround points to become clear.");return true; }
            if(!automaticTraction(train,-1))return true;
            train.speed=0;train.status="Backing through nearest junction "+plan.junction+" into the turnaround lane.";return true;
        }
        Geometry g=geometry(train);int waypoint=train.path.indexOf(plan.waypoint);
        if(waypoint<0||Math.abs(leadingDistance(train)-g.centers[waypoint])>EPS)return false;
        if(!mayChangeTraction) { train.speed=0;train.status="Stopped clear in the local turnaround lane.";return true; }
        train.speed=0;int plannedJunction=plan.backingPath.indexOf(plan.junction);
        List<Node> plannedNodes=new ArrayList<Node>();for(String rail:plan.backingPath)plannedNodes.add(node(rail));
        Geometry plannedGeometry=new Geometry(plannedNodes);
        double clearance=plannedGeometry.centers[plannedNodes.size()-1]-plannedGeometry.centers[plannedJunction]-(train.carIds.size()-1)*CAR_SPACING;
        boolean clear=!signalOccupiesNode(train,plan.junction)&&clearance>=TRAIN_CLEARANCE-EPS;
        for(Pose pose:train.poses)if(plan.backingPath.indexOf(pose.railName)<=plannedJunction)clear=false;
        if(!clear) { block(train,"Waiting for the whole train to clear the local turnaround points.");return true; }
        try { signalSystem.traffic().requireReversalSafe(train.name); }
        catch(IllegalArgumentException unavailable) { block(train,unavailable.getMessage());return true; }
        signalSystem.prepareTrafficReversal(train.name);releasePendingJunctions(train);
        if(!signalSystem.alignStationTurnback(train.name,true)) { block(train,"Waiting for the local turnaround departure alignment.");return true; }
        if(!automaticTraction(train,1))return true;
        train.stationTurnback=plan.withDeparting();
        int exit=plan.departurePath.indexOf(plan.junction)+1;
        if(exit>0&&exit<plan.departurePath.size())resetUnenteredExit(train.name,plan.junction,plan.departurePath.get(exit));
        train.status="Local turnaround complete; leaving forward through junction "+plan.junction+".";return true;
    }
    private boolean restoreForwardAfterLoopEntry(Train train) {
        List<Train> cohort=signalSystem.traffic().loopForwardCohort(train);if(cohort.isEmpty())return false;
        try { for(Train member:cohort)signalSystem.traffic().requireReversalSafe(member.name); }
        catch(IllegalArgumentException unavailable) { block(train,unavailable.getMessage());return true; }
        for(Train member:cohort) { signalSystem.prepareTrafficReversal(member.name);releasePendingJunctions(member); }
        for(Train member:cohort) {
            member.speed=0;member.direction=1;
            member.recoveringTraffic=true;member.trafficRecoveryRail=null;member.trafficWaitSeconds=0;
            member.recoveryStatus="Turnback complete: every car is clear of the Y points; following the loop forward toward the saved station.";
            member.status=member.recoveryStatus;
        }
        return true;
    }
    private void arrive(Train t) {
        t.stationTurnback=null;
        t.trafficRecoveryRail=null;
        t.trafficRecoveryExit=null;
        // A backing recovery still has to move out of the receiving berth after
        // its station pivot. Keep its outer-lane clearance while it departs
        // forwards; occupied blocks and connector/junction locks remain strict.
        boolean clearingTurn=t.recoveringTraffic&&t.direction<0&&t.route.size()>1;
        t.recoveringTraffic=clearingTurn;
        t.recoveryStatus=clearingTurn?"Restoring forward travel at the station, then clearing the receiving track.":null;
        t.trafficWaitSeconds=0;
        Node station = node(t.route.get(t.routeIndex));
        t.speed = 0;
        t.dwellRemaining = station.station ? station.dwellSeconds : t.routeDwellSeconds;
        if (t.route.size() == 1) { t.powered = false; t.status = "Destination reached"; return; }
        t.routeIndex = (t.routeIndex+1)%t.route.size(); t.status = "Station dwell";
        if(routeStopServices!=null&&routeStopServices.shouldService(t,station)) {
            t.serviceStopRail=station.name;
            t.serviceStopSerial=t.serviceStopSerial==Long.MAX_VALUE?1:t.serviceStopSerial+1;
            t.dwellRemaining=Math.max(1,t.dwellRemaining);t.status="Freight service";
        }
    }
    private void halt(Train t, String reason) { t.speed = 0; t.powered = false; t.status = reason; }
    private void block(Train t, String reason) { t.speed = 0; t.powered = t.automated; t.status = reason; }
    private void setTraction(Train t,int direction) {
        if(t.direction!=direction)signalSystem.traffic().requireReversalSafe(t.name);
        t.direction=direction;
    }
    private boolean automaticTraction(Train t,int direction) {
        try { setTraction(t,direction);return true; }
        catch(IllegalArgumentException blocked) { block(t,blocked.getMessage());return false; }
    }
    private String headwayObstruction(Train train,List<Pose> candidate) {
        for(Train other:trains.values())if(other!=train)for(int i=0;i<candidate.size();i++) {
            Pose next=candidate.get(i),old=train.poses.get(i);
            for(Pose occupied:other.poses)if(nearbyConnectedTrack(next,occupied)) {
                double gap=Math.hypot(next.x-occupied.x,next.y-occupied.y);
                if(gap>=TRAIN_CLEARANCE-EPS)continue;
                double before=Math.hypot(old.x-occupied.x,old.y-occupied.y);
                // Existing worlds can have closer parked trains. Let those move
                // apart, without permitting a following train to close that gap.
                if(before>=TRAIN_CLEARANCE-EPS||gap<before-EPS)return other.name;
            }
        }
        return null;
    }
    private boolean nearbyConnectedTrack(Pose first,Pose second) {
        Node a=nodes.get(first.railName),b=nodes.get(second.railName);
        if(a==null||b==null)return first.layer==second.layer;
        if(a==b||a.links.contains(b.name))return true;
        // Adjacent bends may place close centres two graph edges apart. An
        // unconnected diagonal crossing keeps its existing exclusive X resource.
        for(String neighbor:a.links)if(node(neighbor).links.contains(b.name))return true;
        return false;
    }
    private void restorePath(Train t, List<String> path, double distance, String towards) {
        t.path.clear(); t.path.addAll(path); t.locomotiveDistance = distance; t.initialTowards = towards;
    }
    private boolean guard(List<Pose> poses) {
        try { return movementGuard == null || movementGuard.test(poses); }
        catch (RuntimeException rejected) { return false; }
    }

    /** A claim must start on the same bend that a later coupled cart will use. */
    private void seedInitialBend(Train t,String towards) {
        if(towards==null||t.path.size()!=1)return;
        Node origin=node(t.path.get(0));List<String> behind=new ArrayList<String>(origin.links);behind.remove(towards);
        if(behind.isEmpty())return;
        String previous=origin.links.size()>2?pairedExit(origin,towards):behind.get(0);
        if(previous==null)throw problem("Align junction "+origin.name+" before placing this train.");
        if(!isBend(previous,origin.name,towards))return;
        t.path.add(0,previous);t.locomotiveDistance=geometry(t).total;
    }
    private boolean isBend(String previous,String origin,String towards) {
        Node a=node(previous),b=node(origin),c=node(towards);
        return direction(b.tileX-a.tileX,b.tileY-a.tileY)!=direction(c.tileX-b.tileX,c.tileY-b.tileY);
    }
    private boolean selectInitialTraction(Train t) {
        prepareStationTurnback(t);
        if(t.stationTurnback!=null)return true;
        if(t.recoveringTraffic)return true;
        if(t.speed>EPS||!t.automated||t.route.isEmpty())return true;
        if(t.direction<0) {
            if(engineLeadingPath(t,t.route.get(t.routeIndex)).size()>1)setTraction(t,1);
            return true;
        }
        if(t.path.size()!=1||t.initialTowards==null) {
            String current=signalLeadingRail(t),previous=signalIncomingRail(t);
            if(current==null||previous==null)return true;
            String target=t.route.get(t.routeIndex);
            if(current.equals(target))return true;
            List<String> departure=automaticPath(t,current,previous,target,true);
            if(departure.isEmpty()) {
                String obstruction=backingObstruction(t,current,previous,target);
                if(obstruction!=null) { halt(t,reversalClearanceMessage(obstruction));return false; }
            }
            if(departure.size()>1&&departure.get(1).equals(previous)) {
                String obstruction=reversalObstruction(t,departure);
                if(obstruction!=null) {
                    if(automaticPath(t,current,previous,target,false).size()>1)return true;
                    halt(t,reversalClearanceMessage(obstruction));return false;
                }
                setTraction(t,-t.direction);
            }
            return true;
        }
        String origin=t.path.get(0),facing=t.initialTowards;
        if(t.automated&&!t.route.isEmpty()) {
            String target=t.route.get(t.routeIndex);
            if(origin.equals(target)&&t.route.size()>1)target=t.route.get((t.routeIndex+1)%t.route.size());
            Node originNode=node(origin);String previous=facing;
            if(t.direction>0) {
                previous=originNode.links.size()>2?pairedExit(originNode,facing):null;
                if(originNode.links.size()<=2)for(String neighbor:originNode.links)if(!neighbor.equals(facing))previous=neighbor;
            }
            List<String> departure=automaticPath(t,origin,previous,target,true);
            if(departure.size()>1) {
                int wanted=signalSide(origin,departure.get(1)),front=signalSide(origin,facing);
                // Reverse traction, never the vehicle's body, for a destination behind it.
                if(wanted==front)setTraction(t,1);
                else if(wanted==(front+4)%8)setTraction(t,-1);
            }
        }
        return true;
    }
    /** A coupled rear can already cover a switch that a backing path wants to change. */
    private String reversalObstruction(Train t,List<String> path) {
        for(int i=1;i+1<path.size();i++) {
            Node junction=node(path.get(i));
            if(junction.links.size()<3||!occupies(t,junction))continue;
            int pair=side(junction,node(path.get(i-1)))|side(junction,node(path.get(i+1)));
            if(junction.alignmentMask!=pair)return junction.name;
        }
        return null;
    }
    private String reversalClearanceMessage(String junction) {
        return "Move the whole train clear of junction "+junction+" before reversing toward this destination";
    }
    private String backingObstruction(Train train,String current,String previous,String target) {
        List<String> desired=TrafficRouter.preferredPath(this,train,current,previous,target,false,true);
        return desired.size()>1&&desired.get(1).equals(previous)?reversalObstruction(train,desired):null;
    }
    /** Restore forward traction from the locomotive's actual end, not a shortcut through its own carts. */
    private List<String> engineLeadingPath(Train train,String target) {
        if(train.poses.isEmpty())return Collections.emptyList();
        String current=train.poses.get(0).railName,previous=null;
        Geometry g=geometry(train);
        for(int i=0;i<train.path.size();i++)if(train.path.get(i).equals(current)
                &&train.locomotiveDistance>=g.starts[i]-EPS&&train.locomotiveDistance<=g.ends[i]+EPS) {
            if(i>0)previous=train.path.get(i-1);break;
        }
        if(previous==null&&train.initialTowards!=null) {
            Node origin=node(current);
            if(origin.links.size()>2)previous=pairedExit(origin,train.initialTowards);
            else for(String link:origin.links)if(!link.equals(train.initialTowards))previous=link;
        }
        List<String> route=engineLeadingPath(train,current,previous,target,true);
        return route.isEmpty()?engineLeadingPath(train,current,previous,target,false):route;
    }
    private List<String> engineLeadingPath(Train train,String current,String previous,String target,boolean avoidForeign) {
        Node origin=node(current);
        List<String> route=TrafficRouter.path(this,train,current,previous,target,avoidForeign);
        if(route.size()>1&&origin.links.size()>2&&occupies(train,origin)) {
            String exit=pairedExit(origin,previous);
            if(exit==null)return Collections.emptyList();
            if(!exit.equals(route.get(1))) {
                List<String> tail=TrafficRouter.path(this,train,exit,current,target,avoidForeign);
                if(tail.isEmpty())return Collections.emptyList();
                List<String> aligned=new ArrayList<String>();aligned.add(current);aligned.addAll(tail);return aligned;
            }
        }
        return route;
    }
    private boolean stationDeparture(Train train) {
        String last=train.route.get((train.routeIndex+train.route.size()-1)%train.route.size());
        Geometry g=geometry(train);
        for(int i=0;i<train.path.size();i++)if(last.equals(train.path.get(i))
                &&g.centers[i]>=rearDistance(train)-EPS&&g.centers[i]<=train.locomotiveDistance+EPS)return true;
        return false;
    }
    /** A bare locomotive still has a physical front before it has any movement history. */
    private void resolveInitialOrientation(Train t) {
        if(t.path.size()!=1||t.initialTowards==null)return;
        String facing=t.initialTowards;
        t.path.add(facing);
        refresh(t);
    }
    /** Resolve a departure only while the engine is still at the initial path endpoint. */
    private void resolveInitialDeparture(Train t,String towards) {
        int end=t.path.size()-1;
        if(towards==null||end==0||Math.abs(t.locomotiveDistance-geometry(t).total)>EPS)return;
        String origin=t.path.get(end);
        if(!node(origin).links.contains(towards)||!isBend(t.path.get(end-1),origin,towards))return;
        t.path.add(towards);t.locomotiveDistance=geometry(t).centers[end];
    }

    private void extendBehind(Train t, double required, String excludeFirst) {
        Set<String> visited = new HashSet<String>(t.path);
        while (t.locomotiveDistance + EPS < required) {
            Node tail = node(t.path.get(0));
            String forward = t.path.size()>1 ? t.path.get(1) : excludeFirst;
            List<String> options = new ArrayList<String>();
            for (String adjacent : tail.links) if (!adjacent.equals(forward) && !visited.contains(adjacent)) options.add(adjacent);
            if (options.isEmpty()) throw problem("Not enough connected rail behind the locomotive for every cart.");
            String choice=tail.links.size()>2?pairedExit(tail,forward):options.get(0);
            if(choice==null||!options.contains(choice))throw problem("Align junction "+tail.name+" behind the locomotive before coupling.");
            double oldBoundary = geometry(t).ends[0];
            visited.add(choice); t.path.add(0, choice);
            t.locomotiveDistance += geometry(t).ends[1] - oldBoundary;
        }
    }
    private double rearDistance(Train t) { return t.locomotiveDistance-(t.carIds.size()-1)*CAR_SPACING; }
    private double leadingDistance(Train t) { return t.direction>0 ? t.locomotiveDistance : rearDistance(t); }
    private void refresh(Train t) { t.poses = poses(t,t.locomotiveDistance); }
    private List<Pose> poses(Train t, double distance) {
        List<Pose> result = new ArrayList<Pose>();
        Geometry g=geometry(t);
        for (int c=0;c<t.carIds.size();c++) {
            double s=distance-c*CAR_SPACING;
            if (s < -EPS || s > g.total+EPS) throw problem("Train extends beyond connected rail.");
            double[] point=g.point(Math.max(0,Math.min(s,g.total)));
            double dx=point[2],dy=point[3];
            if (Math.abs(dx)+Math.abs(dy)<EPS && t.initialTowards!=null) {
                Node from=node(t.path.get(0)),to=node(t.initialTowards);
                dx=to.worldX()-from.worldX();dy=to.worldY()-from.worldY();
            }
            float rotation=(float)((Math.toDegrees(Math.atan2(dx,-dy))+360)%360);
            int piece=0;
            while(piece+1<t.path.size()&&s>g.ends[piece]+EPS)piece++;
            float x=(float)point[0],y=(float)point[1];
            // A native cave portal changes layer at the actual shared tile edge.
            // Choose using the committed float coordinates, including an exact
            // boundary approached from the east or south, rather than moving
            // every cart when the locomotive enters the cave.
            Node owner=node(t.path.get(piece));
            int tileX=(int)Math.floor(x/4.0),tileY=(int)Math.floor(y/4.0);
            for(int adjacent:new int[]{piece-1,piece+1})if(adjacent>=0&&adjacent<t.path.size()) {
                Node next=node(t.path.get(adjacent));
                if(next.layer!=owner.layer&&next.tileX==tileX&&next.tileY==tileY) { piece=adjacent;owner=next;break; }
            }
            result.add(new Pose(t.carIds.get(c),x,y,rotation,owner.layer,t.path.get(piece)));
        }
        return Collections.unmodifiableList(result);
    }
    private Set<String> resources(Train t,double locomotiveDistance) {
        double low=locomotiveDistance-(t.carIds.size()-1)*CAR_SPACING-BUFFER,high=locomotiveDistance+BUFFER;
        Set<String> result=new HashSet<String>();
        Geometry g=geometry(t);
        for (int i=0;i<t.path.size();i++) {
            if ((g.ends[i]>low+EPS&&g.starts[i]<high-EPS)
                    ||g.ends[i]-g.starts[i]<EPS&&g.centers[i]>=low-EPS&&g.centers[i]<=high+EPS)result.add("N:"+t.path.get(i));
            if (i>0 && g.centers[i]>low+EPS && g.centers[i-1]<high-EPS) {
                result.add(edge(t.path.get(i-1),t.path.get(i)));
                String crossing=diagonalCrossing(t.path.get(i-1),t.path.get(i));
                if(crossing!=null)result.add(crossing);
            }
        }
        return result;
    }
    private String diagonalCrossing(String first,String second) {
        Node a=node(first),b=node(second);
        return a.tileX!=b.tileX&&a.tileY!=b.tileY ?
            "X:"+a.layer+":"+Math.min(a.tileX,b.tileX)+":"+Math.min(a.tileY,b.tileY) : null;
    }
    private boolean conflicts(Train t,double distance) {
        Set<String> own=resources(t,distance);
        double low=distance-(t.carIds.size()-1)*CAR_SPACING-BUFFER,high=distance+BUFFER;
        Set<String> visited=new HashSet<String>(),crossings=new HashSet<String>();
        Geometry g=geometry(t);
        for (int i=0;i<t.path.size();i++) {
            if (g.centers[i]>=low-EPS && g.centers[i]<=high+EPS && !visited.add(t.path.get(i))) return true;
            if(i>0 && g.centers[i]>low+EPS && g.centers[i-1]<high-EPS) {
                String crossing=diagonalCrossing(t.path.get(i-1),t.path.get(i));
                if(crossing!=null&&!crossings.add(crossing))return true;
            }
        }
        for (Train other:trainData.values()) if (other!=t) {
            Set<String> theirs=resources(other,other.locomotiveDistance);
            for (String resource:own) if (theirs.contains(resource)) return true;
        }
        if(signalSystem.conflictsResources(t.name,own))return true;
        return false;
    }
    private void prune(Train t) {
        // Do not change the curve under a vehicle when shortening its track history.
        Geometry g=geometry(t);
        while(t.path.size()>2 && rearDistance(t)>g.ends[1]+EPS) {
            double oldBoundary=g.ends[1];t.path.remove(0);g=geometry(t);
            t.locomotiveDistance+=g.ends[0]-oldBoundary;
        }
        while(t.path.size()>2 && g.starts[t.path.size()-2]>t.locomotiveDistance+EPS) {
            t.path.remove(t.path.size()-1);g=geometry(t);
        }
    }
    private List<String> shortest(String from,String to) {
        if(!nodes.containsKey(from)||!nodes.containsKey(to)) return Collections.emptyList();
        final Map<String,Double> distances=new HashMap<String,Double>();
        Map<String,String> previous=new HashMap<String,String>();
        PriorityQueue<RouteStep> queue=new PriorityQueue<RouteStep>((a,b)->Double.compare(a.distance,b.distance));
        distances.put(from,0.0);previous.put(from,null);queue.add(new RouteStep(from,0));
        while(!queue.isEmpty()) {
            RouteStep step=queue.remove();
            if(step.distance>distances.get(step.name)+EPS)continue;
            if(step.name.equals(to))break;
            Node current=node(step.name);
            for(String next:current.links) {
                Node target=node(next);
                double distance=step.distance+Math.hypot(target.worldX()-current.worldX(),target.worldY()-current.worldY());
                Double old=distances.get(next);
                if(old==null||distance<old-EPS) {
                    distances.put(next,distance);previous.put(next,step.name);queue.add(new RouteStep(next,distance));
                }
            }
        }
        if(!previous.containsKey(to)) return Collections.emptyList();
        LinkedList<String> result=new LinkedList<String>();
        for(String at=to;at!=null;at=previous.get(at))result.addFirst(at);
        return result;
    }
    private static final class RouteStep {
        final String name;final double distance;
        RouteStep(String name,double distance){this.name=name;this.distance=distance;}
    }

    /** Direction bits used by custom tile models: N, NE, E, SE, S, SW, W, NW. */
    int signalSide(String rail,String adjacent) {
        Node first=node(rail),second=node(adjacent);
        if(!first.links.contains(adjacent))throw problem("Signal route contains a disconnected side.");
        return direction(second.tileX-first.tileX,second.tileY-first.tileY);
    }
    String signalNeighbor(String rail,int approach) {
        Node n=node(rail);
        for(String link:n.links)if(signalSide(rail,link)==approach)return link;
        return null;
    }
    String signalIntendedNext(Train t,String current,String previous,int routeCursor) {
        return signalIntendedNext(t,current,previous,routeCursor,false);
    }
    String signalIntendedNext(Train t,String current,String previous,int routeCursor,boolean scheduledDeparture) {
        Node n=node(current);
        if(t.stationTurnback!=null) {
            List<String> planned=automaticPath(t,current,previous,t.route.get(routeCursor),false);
            if(planned.size()>1)return planned.get(1);
            if(!t.stationTurnback.departing&&current.equals(t.stationTurnback.waypoint))return null;
        }
        if(t.automated&&!t.route.isEmpty()) {
            String target=t.route.get(routeCursor);
            // Arrival ends a traffic yield. Forecast the same engine-leading
            // departure that arrive/reverseAtAlignedCentre will actually use,
            // rather than reserving another backwards circuit past the station.
            if(scheduledDeparture&&(t.direction<0||t.recoveringTraffic)) {
                List<String> departure=TrafficRouter.preferredPath(this,t,current,previous,target,false,true);
                if(departure.size()>1)return departure.get(1);
            }
            List<String> desired=automaticPath(t,current,previous,target,scheduledDeparture||stationaryDeparture(t,current));
            if(desired.size()<2)throw problem("Automatic destination is disconnected from signal route at "+current+".");
            return desired.get(1);
        }
        if(n.links.size()>2) {
            String next=pairedExit(n,previous);
            if(next==null)throw problem("Manual route has no valid junction alignment at "+current+".");
            return next;
        }
        for(String next:n.links)if(!next.equals(previous))return next;
        return null;
    }
    private List<String> automaticPath(Train train,String current,String previous,String target) {
        return automaticPath(train,current,previous,target,stationaryDeparture(train,current)||node(current).links.size()==1);
    }
    private List<String> automaticPath(Train train,String current,String previous,String target,boolean allowReversal) {
        if(train.stationTurnback!=null&&(!train.stationTurnback.departing||target.equals(train.stationTurnback.target))) {
            List<String> route=train.stationTurnback.departing?train.stationTurnback.departurePath:train.stationTurnback.backingPath;
            int at=route.indexOf(current);
            if(!train.stationTurnback.departing&&current.equals(train.stationTurnback.waypoint)) {
                for(String onward:node(current).links)if(!onward.equals(previous))return Arrays.asList(current,onward);
                return previous==null?Collections.singletonList(current):Arrays.asList(current,previous);
            }
            if(at>=0)return route.subList(at,route.size());
        }
        if(train.trafficRecoveryRail!=null&&!current.equals(train.trafficRecoveryRail))target=train.trafficRecoveryRail;
        String escape=signalSystem.trafficRouting(current)?signalSystem.preferredExit(train.name,current,previous):null;
        if(escape!=null) {
            List<String> tail=TrafficRouter.path(this,train,escape,current,target,false);
            if(!tail.isEmpty()) { List<String> path=new ArrayList<String>();path.add(current);path.addAll(tail);return path; }
        }
        if(train.recoveringTraffic&&!current.equals(train.route.get(train.routeIndex))) {
            List<String> route=TrafficRouter.path(this,train,current,previous,target,true);
            return route.isEmpty()?TrafficRouter.path(this,train,current,previous,target,false):route;
        }
        List<String> route=automaticPathCandidate(train,current,previous,target,true,allowReversal);
        if(route.isEmpty())route=automaticPathCandidate(train,current,previous,target,false,allowReversal);
        return route;
    }
    private List<String> automaticPathCandidate(Train train,String current,String previous,String target,boolean avoidForeign,boolean allowReversal) {
        List<String> route=TrafficRouter.preferredPath(this,train,current,previous,target,avoidForeign,allowReversal);
        if(train.speed<EPS&&current.equals(signalLeadingRail(train))&&route.size()>1&&route.get(1).equals(previous)
                &&reversalObstruction(train,route)!=null)
            return TrafficRouter.path(this,train,current,previous,target,avoidForeign);
        return route;
    }
    private boolean stationaryDeparture(Train train,String rail) {
        if(train.speed>EPS)return false;
        Geometry g=geometry(train);double leader=leadingDistance(train);
        for(int i=0;i<train.path.size();i++)if(train.path.get(i).equals(rail)&&Math.abs(leader-g.centers[i])<EPS)return true;
        return false;
    }
    boolean signalOccupiesNode(Train t,String rail) { Node n=nodes.get(rail);return n!=null&&occupies(t,n); }
    boolean signalJunctionAvailable(String trainName,String rail,int mask) {
        Node n=node(rail);
        if(n.alignmentOwner!=null&&!trainName.equals(n.alignmentOwner))return false;
        if(signalSystem.holdsJunction(trainName,rail)&&n.alignmentMask!=mask)return false;
        try { validateAlignment(n,mask);requireAlignmentClear(n,mask,trainName);return mask!=0; }
        catch(IllegalArgumentException unavailable) { return false; }
    }
    Set<String> signalOccupiedResources(Train t) { return resources(t,t.locomotiveDistance); }
    /** Physical engine orientation around a simple loop, independent of traction. */
    int signalLoopOrientation(Train train,List<String> ring) {
        Geometry g=geometry(train);int result=0;double nearest=Double.POSITIVE_INFINITY;
        for(int i=1;i<train.path.size();i++) {
            int before=ring.indexOf(train.path.get(i-1)),after=ring.indexOf(train.path.get(i));
            if(before<0||after<0)continue;
            int delta=(after-before+ring.size())%ring.size();
            int direction=delta==1?1:delta==ring.size()-1?-1:0;if(direction==0)continue;
            double distance=Math.abs((g.centers[i-1]+g.centers[i])*.5-train.locomotiveDistance);
            if(distance<nearest) { nearest=distance;result=direction; }
        }
        if(result==0&&train.initialTowards!=null&&!train.poses.isEmpty()) {
            String current=train.poses.get(0).railName;int from=ring.indexOf(current),to=ring.indexOf(train.initialTowards);
            if(from>=0&&to>=0) { int delta=(to-from+ring.size())%ring.size();result=delta==1?1:delta==ring.size()-1?-1:0; }
            if(result==0&&from>=0) {
                String behind=pairedExit(node(current),train.initialTowards);int prior=ring.indexOf(behind);
                if(prior>=0) { int delta=(from-prior+ring.size())%ring.size();result=delta==1?1:delta==ring.size()-1?-1:0; }
            }
        }
        return result;
    }
    String signalLeadingRail(Train t) { return t.poses.isEmpty()?null:t.poses.get(t.direction>0?0:t.poses.size()-1).railName; }
    String signalIncomingRail(Train t) {
        String rail=signalLeadingRail(t);if(rail==null)return null;
        Geometry g=geometry(t);double leading=leadingDistance(t);
        for(int i=0;i<t.path.size();i++)if(t.path.get(i).equals(rail)&&leading>=g.starts[i]-EPS&&leading<=g.ends[i]+EPS) {
            int behind=i-t.direction;if(behind>=0&&behind<t.path.size())return t.path.get(behind);
        }
        if(t.initialTowards!=null) {
            if(t.direction<0)return t.initialTowards;
            for(String link:node(rail).links)if(!link.equals(t.initialTowards))return link;
        }
        return null;
    }
    Set<String> signalRouteResources(List<String> rails) {
        Set<String> result=new LinkedHashSet<String>();
        for(int i=0;i<rails.size();i++) {
            result.add("N:"+rails.get(i));
            if(i>0) {
                result.add(edge(rails.get(i-1),rails.get(i)));
                String crossing=diagonalCrossing(rails.get(i-1),rails.get(i));if(crossing!=null)result.add(crossing);
            }
        }
        return result;
    }
    void signalAddEntryResources(Set<String> resources,String rail,String previous) {
        resources.add(edge(rail,previous));
        String crossing=diagonalCrossing(rail,previous);if(crossing!=null)resources.add(crossing);
    }
    public static int direction(int deltaX,int deltaY) {
        int dx=Integer.signum(deltaX),dy=Integer.signum(deltaY);
        if(dx==0&&dy==0)throw problem("A rail direction needs two distinct tiles.");
        if(dy<0)return dx<0?7:dx>0?1:0;
        if(dy>0)return dx<0?5:dx>0?3:4;
        return dx>0?2:6;
    }
    /** All connected exits; a model's neutral rotation is zero degrees. */
    public synchronized int trackMask(String name) {
        Node n=node(name);int mask=0;
        for(String link:n.links){Node other=node(link);mask|=1<<direction(other.tileX-n.tileX,other.tileY-n.tileY);}
        return mask;
    }

    private Geometry geometry(Train t) {
        if(t.cachedGeometry!=null&&t.path.equals(t.cachedPath))return t.cachedGeometry;
        List<Node> path=new ArrayList<Node>();for(String name:t.path)path.add(node(name));
        t.cachedGeometry=new Geometry(path);t.cachedPath=new ArrayList<String>(t.path);return t.cachedGeometry;
    }
    /**
     * Each interior tile is one quadratic Bezier: incoming edge midpoint,
     * tile centre control, outgoing edge midpoint. Adjacent tile curves have
     * identical boundary tangents. Sixty-four samples bound arc-length error
     * below a millimetre even for the tightest 135 degree compass bend.
     */
    private static final class Geometry {
        final double[] starts,centers,ends;
        final List<Curve> curves=new ArrayList<Curve>();
        final double total;
        final double originX,originY;
        Geometry(List<Node> nodes) {
            int count=nodes.size();starts=new double[count];centers=new double[count];ends=new double[count];
            originX=nodes.get(0).worldX();originY=nodes.get(0).worldY();
            double distance=0;
            for(int i=0;i<count;i++) {
                Node n=nodes.get(i);double x=n.worldX(),y=n.worldY();
                double ax=i==0?x:(nodes.get(i-1).worldX()+x)*.5;
                double ay=i==0?y:(nodes.get(i-1).worldY()+y)*.5;
                double bx=i==count-1?x:(nodes.get(i+1).worldX()+x)*.5;
                double by=i==count-1?y:(nodes.get(i+1).worldY()+y)*.5;
                // Endpoint halves are straight; the middle control gives uniform parameter speed.
                double cx=i==0||i==count-1?(ax+bx)*.5:x;
                double cy=i==0||i==count-1?(ay+by)*.5:y;
                Curve curve=new Curve(ax,ay,cx,cy,bx,by,distance);
                starts[i]=distance;
                centers[i]=i==0?distance:i==count-1?distance+curve.length:distance+curve.arc[Curve.SAMPLES/2];
                distance+=curve.length;ends[i]=distance;curves.add(curve);
            }
            total=distance;
        }
        double[] point(double distance) {
            for(Curve curve:curves)if(distance<=curve.offset+curve.length+EPS&&curve.length>EPS)
                return curve.point(Math.max(0,Math.min(curve.length,distance-curve.offset)));
            return new double[]{originX,originY,0,0};
        }
    }
    private static final class Curve {
        static final int SAMPLES=64;
        final double ax,ay,cx,cy,bx,by,offset,length;
        final double[] arc=new double[SAMPLES+1];
        Curve(double ax,double ay,double cx,double cy,double bx,double by,double offset) {
            this.ax=ax;this.ay=ay;this.cx=cx;this.cy=cy;this.bx=bx;this.by=by;this.offset=offset;
            double px=ax,py=ay;
            for(int i=1;i<=SAMPLES;i++) {
                double t=i/(double)SAMPLES,u=1-t,x=u*u*ax+2*u*t*cx+t*t*bx,y=u*u*ay+2*u*t*cy+t*t*by;
                arc[i]=arc[i-1]+Math.hypot(x-px,y-py);px=x;py=y;
            }
            length=arc[SAMPLES];
        }
        double[] point(double distance) {
            int index=Arrays.binarySearch(arc,distance);
            double t;
            if(index>=0)t=index/(double)SAMPLES;
            else {int end=-index-1;end=Math.max(1,Math.min(end,SAMPLES));
                double span=arc[end]-arc[end-1];t=(end-1+(span<EPS?0:(distance-arc[end-1])/span))/SAMPLES;}
            double u=1-t;
            return new double[]{u*u*ax+2*u*t*cx+t*t*bx,u*u*ay+2*u*t*cy+t*t*by,
                2*(u*(cx-ax)+t*(bx-cx)),2*(u*(cy-ay)+t*(by-cy))};
        }
    }
    private void validateCars(List<Long> cars,Train except) {
        if(cars==null || cars.isEmpty() || cars.size()>Math.max(1,Math.min(64,maxCars))) throw problem("Train must have 1 to "+maxCars+" vehicles.");
        Set<Long> seen=new HashSet<Long>();
        for(Long car:cars) {
            if(car==null || car.longValue()<=0 || !seen.add(car)) throw problem("Vehicle IDs must be positive and unique.");
            for(Train other:trainData.values()) if(other!=except && other.carIds.contains(car)) throw problem("Vehicle already belongs to train "+other.name+".");
        }
    }
    private void validateTuning(Train t) {
        finiteRange(t.maxSpeed,0.1,8.0,"Train maximum speed");finiteRange(t.acceleration,0.05,4.0,"Train acceleration");
        if(t.direction!=1 && t.direction!=-1) throw problem("Train direction must be 1 or -1.");
    }
    private Node node(String name) { Node n=nodes.get(name);if(n==null)throw problem("Unknown rail: "+name);return n; }
    private Train train(String name) { Train t=trains.get(name);if(t==null)throw problem("Unknown train: "+name);return t; }
    private static String checkedName(String name) {
        if(name==null || !name.matches("[A-Za-z0-9_-]{1,40}")) throw problem("Names must use 1 to 40 letters, numbers, underscores, or hyphens.");
        return name;
    }
    private static void requireStopped(Train t) { if(t.powered || t.speed>EPS) throw problem("Stop the train first."); }
    private static IllegalArgumentException problem(String message) { return new IllegalArgumentException(message); }
    private static void finiteRange(double value,double min,double max,String label) {
        if(Double.isNaN(value)||Double.isInfinite(value)||value<min||value>max) throw problem(label+" must be between "+min+" and "+max+".");
    }
    private static boolean sameEdge(String a,String b,String c,String d) {return(a.equals(c)&&b.equals(d))||(a.equals(d)&&b.equals(c));}
    private static String edge(String a,String b) {return a.compareTo(b)<0?"E:"+a+":"+b:"E:"+b+":"+a;}

    /** Atomic replacement, a previous-state backup, and a checksum prevent silent partial saves. */
    public synchronized void save(Path file) throws IOException {
        Properties p=toProperties();p.setProperty("checksum",checksum(p));
        Path absolute=file.toAbsolutePath();Path parent=absolute.getParent();Files.createDirectories(parent);
        Path temporary=Files.createTempFile(parent,absolute.getFileName().toString()+".tmp.",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temporary,StandardOpenOption.TRUNCATE_EXISTING)) {p.store(out,"Wurm Rail state; all trains restart stopped");}
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {channel.force(true);}
            if(Files.exists(absolute)) {
                // Never overwrite a good backup with a corrupt current state.
                load(absolute);
                Files.copy(absolute,absolute.resolveSibling(absolute.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            }
            try {Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported) {Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    public static RailNetwork load(Path file) throws IOException {
        if(!Files.exists(file)) {
            if(Files.exists(file.resolveSibling(file.getFileName()+".bak")))
                throw new IOException("Rail state file is missing but a backup exists. Restore the checked .bak file before starting rail service.");
            return new RailNetwork();
        }
        Properties p=new Properties();
        try {
            try(InputStream input=Files.newInputStream(file)) {p.load(input);}
            String expected=p.getProperty("checksum");
            if(expected==null || !MessageDigest.isEqual(expected.getBytes("UTF-8"),checksum(p).getBytes("UTF-8"))) throw problem("State checksum does not match.");
            String format=required(p,"format");
            if(!"1".equals(format)&&!"2".equals(format)&&!"3".equals(format)&&!"4".equals(format)) throw problem("Unsupported rail state format.");
            RailNetwork net=new RailNetwork();net.maxCars=integer(p,"maxCars",1,64);
            Map<Train,StationTurnback.Plan> loadedTurnbacks=new LinkedHashMap<Train,StationTurnback.Plan>();
            int count=integer(p,"nodes",0,100000);
            for(int i=0;i<count;i++) {
                String k="node."+i+".";
                Node n=net.addNode(required(p,k+"name"),integer(p,k+"x",0,Integer.MAX_VALUE/4),integer(p,k+"y",0,Integer.MAX_VALUE/4),integer(p,k+"layer",-1,0));
                n.markerId=Long.parseLong(required(p,k+"marker"));n.station=bool(p,k+"station");n.dwellSeconds=decimal(p,k+"dwell",0,3600);
                n.bridgeId=Long.parseLong(p.getProperty(k+"bridge","-10"));
                if(n.bridgeId!=-10L&&n.bridgeId<=0)throw new IllegalArgumentException("Invalid railway bridge ID");
            }
            // Existing format 4 worlds have no portal rows. Cross-layer links
            // require an explicit saved entrance declaration; a corrupt ordinary
            // link must never silently become a new tunnel connection.
            Set<String> entrances=new LinkedHashSet<String>(),loadedEntrances=new HashSet<String>();
            int entranceCount=p.getProperty("entrances")==null?0:integer(p,"entrances",0,100000);
            for(int i=0;i<entranceCount;i++) {
                String k="entrance."+i+".",first=required(p,k+"first"),second=required(p,k+"second");
                Node a=net.node(first),b=net.node(second);
                if(a.layer==b.layer||Math.abs((long)a.tileX-b.tileX)+Math.abs((long)a.tileY-b.tileY)!=1||!entrances.add(edge(first,second)))
                    throw problem("Invalid or duplicate saved cave entrance.");
            }
            for(int i=0;i<count;i++) {
                String k="node."+i+".";Node n=net.node(required(p,k+"name"));
                for(String link:list(p,k+"links")) {
                    if(n.layer==net.node(link).layer)net.connect(n.name,link);
                    else {
                        String connection=edge(n.name,link);
                        if(!entrances.contains(connection))throw problem("Cross-layer rail link has no saved cave entrance.");
                        net.connectEntrance(n.name,link);loadedEntrances.add(connection);
                    }
                }
            }
            if(!loadedEntrances.equals(entrances))throw problem("Saved cave entrance is missing its track connection.");
            for(int i=0;i<count;i++) {
                String k="node."+i+".";Node n=net.node(required(p,k+"name"));
                if("3".equals(format)||"4".equals(format))net.setJunctionAlignment(n.name,integer(p,k+"alignment",0,255));
                else {
                    String selection=required(p,k+"selected");
                    if(!selection.isEmpty()&&n.links.size()>1)net.setSwitch(n.name,selection);
                }
            }
            int trainCount=integer(p,"trains",0,10000);
            for(int i=0;i<trainCount;i++) {
                String k="train."+i+".";List<Long> cars=new ArrayList<Long>();
                for(String id:list(p,k+"cars"))cars.add(Long.parseLong(id));net.validateCars(cars,null);
                Train t=new Train(required(p,k+"name"),Long.parseLong(required(p,k+"owner")),cars);
                if(net.trains.containsKey(t.name))throw problem("Duplicate train.");
                t.path.addAll(list(p,k+"path"));if(t.path.isEmpty())throw problem("Missing train track history.");
                for(int v=0;v<t.path.size();v++) {
                    net.node(t.path.get(v));
                    if(v>0&&!net.node(t.path.get(v-1)).links.contains(t.path.get(v)))throw problem("Train history contains a disconnected edge.");
                }
                Geometry geometry=net.geometry(t);
                if("1".equals(format)) {
                    // Format 1 stored four metres for every edge and sharp corners.
                    // Preserve progress between node passage points on the new curves.
                    double oldDistance=decimal(p,k+"distance",0,(t.path.size()-1)*4.0);
                    int vertex=Math.min((int)Math.floor(oldDistance/4.0),t.path.size()-1);
                    double fraction=(oldDistance-vertex*4.0)/4.0;
                    t.locomotiveDistance=geometry.centers[vertex];
                    if(vertex+1<t.path.size())
                        t.locomotiveDistance+=fraction*(geometry.centers[vertex+1]-geometry.centers[vertex]);
                    if(net.rearDistance(t)<-EPS) {
                        double required=(t.carIds.size()-1)*CAR_SPACING;
                        try { net.extendBehind(t,required,null); }
                        catch(IllegalArgumentException shortBehind) {
                            // A curve is shorter than the old right-angle path. At a
                            // terminus, use spare saved track in front if available.
                            if(net.geometry(t).total+EPS<required)throw shortBehind;
                            t.locomotiveDistance=required;
                        }
                    }
                } else t.locomotiveDistance=decimal(p,k+"distance",0,geometry.total);
                if(net.rearDistance(t)<-EPS)throw problem("Train tail lies beyond the track.");
                t.direction=integer(p,k+"direction",-1,1);if(t.direction==0)throw problem("Invalid direction.");
                t.initialTowards=required(p,k+"towards");if(t.initialTowards.isEmpty())t.initialTowards=null;
                if(t.initialTowards!=null)net.node(t.initialTowards);
                t.maxSpeed=decimal(p,k+"maxSpeed",0.1,8);t.acceleration=decimal(p,k+"acceleration",0.05,4);
                t.routeData.addAll(list(p,k+"route"));for(String stop:t.routeData)net.node(stop);
                t.routeIndex=integer(p,k+"routeIndex",0,Math.max(0,t.route.size()-1));
                t.automated=bool(p,k+"automated");if(t.automated&&t.route.isEmpty())throw problem("Automatic train has no route.");
                if(p.getProperty(k+"trafficYield")!=null) {
                    t.recoveringTraffic=bool(p,k+"trafficYield");
                    if(t.recoveringTraffic&&!t.automated)throw problem("A saved traffic yield requires an automatic itinerary.");
                    if(t.recoveringTraffic)t.recoveryStatus="Resuming a protected traffic yield toward the saved station.";
                }
                if(p.getProperty(k+"recoveryRail")!=null) {
                    t.trafficRecoveryRail=required(p,k+"recoveryRail");net.node(t.trafficRecoveryRail);
                    if(!t.recoveringTraffic||!t.route.contains(t.trafficRecoveryRail))
                        throw problem("Invalid saved recovery circuit station.");
                    if(net.node(t.trafficRecoveryRail).links.size()!=2)t.trafficRecoveryRail=null;
                }
                if(p.getProperty(k+"recoveryExit")!=null) {
                    t.trafficRecoveryExit=required(p,k+"recoveryExit");
                    if(t.trafficRecoveryExit.isEmpty()||!t.recoveringTraffic||!t.automated
                            ||t.direction>=0||p.getProperty(k+"recoveryRail")!=null)
                        throw problem("Invalid saved backing loop exit.");
                }
                t.routeDwellSeconds=decimal(p,k+"routeDwell",0,3600);t.dwellRemaining=0;
                t.powered=false;t.speed=0;t.status="Stopped after server restart";
                net.refresh(t);if(net.conflicts(t,t.locomotiveDistance))throw problem("Saved trains overlap.");
                if(p.getProperty(k+"serviceStop")!=null) {
                    String held=required(p,k+"serviceStop");net.node(held);
                    if(!t.automated||t.route.size()<2||!held.equals(t.route.get((t.routeIndex+t.route.size()-1)%t.route.size()))
                            ||!net.railOnTrain(t,held))throw problem("Invalid saved route service arrival.");
                    long serial=Long.parseLong(required(p,k+"serviceSerial"));
                    if(serial<1)throw problem("Invalid route service serial.");
                    t.serviceStopRail=held;t.serviceStopSerial=serial;t.dwellRemaining=1;
                } else if(p.getProperty(k+"serviceSerial")!=null)throw problem("Missing route service arrival.");
                net.trainData.put(t.name,t);
                if(p.getProperty(k+"turnbackStation")!=null) {
                    String station=required(p,k+"turnbackStation"),junction=required(p,k+"turnbackJunction"),target=required(p,k+"turnbackTarget");
                    List<String> backing=list(p,k+"turnbackBacking"),departure=list(p,k+"turnbackDeparture");
                    boolean departing=bool(p,k+"turnbackDeparting");
                    try {
                        StationTurnback.Plan plan=new StationTurnback.Plan(net,station,junction,target,backing,departure,departing);
                        if(StationTurnback.valid(net,t,plan))loadedTurnbacks.put(t,plan);
                    } catch(IllegalArgumentException staleManeuver) { /* Keep the itinerary when a saved track edit invalidates its maneuver. */ }
                }
            }
            net.validateLoadedAlignments("1".equals(format)||"2".equals(format));
            if("4".equals(format)) {
                int signalCount=integer(p,"signals",0,800000);
                for(int i=0;i<signalCount;i++) {
                    String k="signal."+i+".";
                    net.signalSystem.add(required(p,k+"id"),required(p,k+"rail"),integer(p,k+"approach",0,7),Long.parseLong(required(p,k+"item")));
                }
            }
            for(Map.Entry<Train,StationTurnback.Plan> maneuver:loadedTurnbacks.entrySet())maneuver.getKey().stationTurnback=maneuver.getValue();
            net.signalSystem.traffic().restoreLoopExitIntents();
            return net;
        } catch(RuntimeException invalid) {
            throw new IOException("Rail state is corrupt; no trains were loaded. Restore the .bak file after checking it: "+invalid.getMessage(),invalid);
        }
    }
    private Properties toProperties() {
        Properties p=new Properties();put(p,"format",4);put(p,"maxCars",maxCars);put(p,"nodes",nodes.size());put(p,"trains",trains.size());
        int i=0;
        for(Node n:nodes.values()) {
            String k="node."+(i++)+".";put(p,k+"name",n.name);put(p,k+"x",n.tileX);put(p,k+"y",n.tileY);put(p,k+"layer",n.layer);
            put(p,k+"marker",n.markerId);put(p,k+"station",n.station);put(p,k+"dwell",n.dwellSeconds);put(p,k+"links",join(n.links));put(p,k+"alignment",n.alignmentMask);
            if(n.bridgeId!=-10L)put(p,k+"bridge",n.bridgeId);
        }
        i=0;
        for(Node n:nodes.values())for(String linked:n.links) {
            Node other=node(linked);
            if(n.layer!=other.layer&&n.name.compareTo(other.name)<0) {
                String k="entrance."+(i++)+".";put(p,k+"first",n.name);put(p,k+"second",other.name);
            }
        }
        if(i>0)put(p,"entrances",i);
        i=0;
        for(Train t:trains.values()) {
            String k="train."+(i++)+".";put(p,k+"name",t.name);put(p,k+"owner",t.ownerId);put(p,k+"cars",join(t.carIds));put(p,k+"path",join(t.path));
            put(p,k+"distance",t.locomotiveDistance);put(p,k+"direction",t.direction);put(p,k+"towards",t.initialTowards==null?"":t.initialTowards);
            put(p,k+"maxSpeed",t.maxSpeed);put(p,k+"acceleration",t.acceleration);put(p,k+"route",join(t.route));put(p,k+"routeIndex",t.routeIndex);
            put(p,k+"automated",t.automated);put(p,k+"routeDwell",t.routeDwellSeconds);
            if(t.serviceStopRail!=null) { put(p,k+"serviceStop",t.serviceStopRail);put(p,k+"serviceSerial",t.serviceStopSerial); }
            if(t.recoveringTraffic)put(p,k+"trafficYield",true);
            if(t.trafficRecoveryRail!=null)put(p,k+"recoveryRail",t.trafficRecoveryRail);
            if(signalSystem.traffic().validLoopExitIntent(t,t.trafficRecoveryExit))put(p,k+"recoveryExit",t.trafficRecoveryExit);
            if(t.stationTurnback!=null&&StationTurnback.valid(this,t,t.stationTurnback)) {
                StationTurnback.Plan plan=t.stationTurnback;
                put(p,k+"turnbackStation",plan.station);put(p,k+"turnbackJunction",plan.junction);put(p,k+"turnbackTarget",plan.target);
                put(p,k+"turnbackBacking",join(plan.backingPath));put(p,k+"turnbackDeparture",join(plan.departurePath));put(p,k+"turnbackDeparting",plan.departing);
            }
        }
        put(p,"signals",signalSystem.definitions().size());i=0;
        for(SignalSystem.Signal signal:signalSystem.definitions()) {
            String k="signal."+(i++)+".";put(p,k+"id",signal.id);put(p,k+"rail",signal.rail);
            put(p,k+"approach",signal.approachDirection);put(p,k+"item",signal.itemId);
        }
        return p;
    }
    private void validateLoadedAlignments(boolean legacy) {
        Map<String,Integer> occupied=new HashMap<String,Integer>();
        for(Train t:trains.values()) for(int i=0;i<t.path.size();i++) {
            Node n=node(t.path.get(i)); if(n.links.size()<3||!occupies(t,n))continue;
            int pair;
            if(i>0&&i+1<t.path.size())pair=side(n,node(t.path.get(i-1)))|side(n,node(t.path.get(i+1)));
            else {
                String neighbor=i>0?t.path.get(i-1):i+1<t.path.size()?t.path.get(i+1):null;
                if(neighbor==null)throw problem("Saved train "+t.name+" has an ambiguous occupied junction "+n.name+".");
                pair=legacy?oppositePair(n,neighbor):n.alignmentMask;
                if(pair==0||(pair&side(n,node(neighbor)))==0)throw problem("Saved train "+t.name+" is on a wrongly aligned junction "+n.name+".");
                int first=Integer.numberOfTrailingZeros(pair);
                boolean straight=(pair&(1<<((first+4)%8)))!=0;
                Geometry g=geometry(t);
                boolean inside=i==0?rearDistance(t)<g.ends[i]-EPS&&t.locomotiveDistance>=g.starts[i]-EPS:
                        t.locomotiveDistance>g.starts[i]+EPS&&rearDistance(t)<=g.ends[i]+EPS;
                if(!straight&&inside)
                    throw problem("Saved train "+t.name+" has no concrete second side at occupied junction "+n.name+"; its alignment is ambiguous.");
            }
            Integer previous=occupied.put(n.name,pair);
            if(previous!=null&&previous!=pair)throw problem("Saved trains disagree about occupied junction "+n.name+".");
            if(legacy)n.alignmentMask=pair;
            else if(n.alignmentMask!=pair)throw problem("Saved junction "+n.name+" does not match the train occupying its two sides.");
        }
        alignmentChanged();
    }
    private static String checksum(Properties p) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");List<String> keys=new ArrayList<String>(p.stringPropertyNames());Collections.sort(keys);
            for(String key:keys)if(!key.equals("checksum")) {
                byte[] k=key.getBytes("UTF-8"),v=p.getProperty(key).getBytes("UTF-8");
                digest.update((byte)(k.length>>>24));digest.update((byte)(k.length>>>16));digest.update((byte)(k.length>>>8));digest.update((byte)k.length);digest.update(k);
                digest.update((byte)(v.length>>>24));digest.update((byte)(v.length>>>16));digest.update((byte)(v.length>>>8));digest.update((byte)v.length);digest.update(v);
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
    private static void put(Properties p,String key,Object value){p.setProperty(key,String.valueOf(value));}
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null)throw problem("Missing "+key);return value;}
    private static int integer(Properties p,String key,int min,int max){int v=Integer.parseInt(required(p,key));if(v<min||v>max)throw problem("Invalid "+key);return v;}
    private static double decimal(Properties p,String key,double min,double max){double v=Double.parseDouble(required(p,key));finiteRange(v,min,max,key);return v;}
    private static boolean bool(Properties p,String key){String v=required(p,key);if(!v.equals("true")&&!v.equals("false"))throw problem("Invalid "+key);return Boolean.parseBoolean(v);}
    private static List<String> list(Properties p,String key){String v=required(p,key);return v.isEmpty()?Collections.<String>emptyList():Arrays.asList(v.split(",",-1));}
    private static String join(Collection<?> values){StringBuilder b=new StringBuilder();for(Object value:values){if(b.length()>0)b.append(',');b.append(value);}return b.toString();}
}
