package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Checkpoint;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Automatic train setup and explicit wagon coupling. Call only on the server poll thread. */
public final class RailwayTrainService {
    private static final long NO_ID = -10L;
    private static final Logger LOG = Logger.getLogger(RailwayTrainService.class.getName());
    private static final double REACH = 8.0;
    private final RailNetwork network;
    private final double speedLimit;
    private final int maxCars;
    private final Motion motion;

    /** Runtime supplies its normal native mover and its pose-cache update together. */
    public interface Motion {
        void apply(List<Pose> poses) throws Exception;
        /** Restoring a parked wagon may return it to valid ground beside the rail. */
        default void restore(List<Pose> poses) throws Exception { apply(poses); }
    }

    public RailwayTrainService(RailNetwork network, double speedLimit, int maxCars, Motion motion) {
        if (network == null || motion == null) throw new IllegalArgumentException("A rail network and world mover are required.");
        if (!Double.isFinite(speedLimit) || speedLimit <= 0 || maxCars < 1)
            throw new IllegalArgumentException("Invalid train limits.");
        this.network = network; this.speedLimit = speedLimit; this.maxCars = maxCars; this.motion = motion;
    }

    public Train findTrain(long vehicleId) {
        for (Train train : network.trains.values()) if (train.carIds.contains(vehicleId)) return train;
        return null;
    }

    /** Upgrade loaded engines with their existing train identity, retaining custom names. */
    public int synchronizeExistingNames() {
        int changed=0;
        for(Train train:network.trains.values()) {
            if(train.carIds.isEmpty()) continue;
            Item engine=Items.getItemOptional(train.carIds.get(0)).orElse(null);
            if(RailwayTrainNames.synchronize(train,engine)) changed++;
        }
        return changed;
    }

    /** Called when a player opens a locomotive's controls; no names or facing nodes are needed. */
    public Train ensureTrain(Creature actor, Item clicked) throws Exception {
        requireCar(actor, clicked, false);
        Train existing = findTrain(clicked.getWurmId());
        if (existing != null) return existing;
        if (!WurmRailWorld.isLocomotive(clicked))
            throw new IllegalArgumentException("Couple this wagon to a nearby locomotive first.");
        requireEmpty(clicked);
        return register(clicked, actor.getWurmId());
    }

    /** Reversible placement for fixed rail vehicles crafted on the ground beside a line. */
    public Train placeOnTrack(Creature actor, Item engine, Node target) throws Exception {
        requireCar(actor, engine, true);
        if (!WurmRailWorld.isLocomotive(engine))
            throw new IllegalArgumentException("Choose a locomotive to place on the track.");
        if (findTrain(engine.getWurmId()) != null)
            throw new IllegalArgumentException("This locomotive is already on a train. Use its controls.");
        if (target == null || network.nodes.get(target.name) != target)
            throw new IllegalArgumentException("This rail section is no longer available. Open the placement window again.");
        if (target.layer != (engine.isOnSurface() ? 0 : -1)
                || Math.hypot(target.worldX() - actor.getPosX(), target.worldY() - actor.getPosY()) > REACH
                || Math.hypot(target.worldX() - engine.getPosX(), target.worldY() - engine.getPosY()) > REACH)
            throw new IllegalArgumentException("Choose connected rail within 8 metres of you and the locomotive on the same level.");
        requireMarker(target);
        if (actor.getPower() < 2 && !Methods.isActionAllowed(actor, Actions.BUILD,
                target.layer == 0, target.tileX, target.tileY, 0, 0))
            throw new IllegalArgumentException("You do not have construction permission to place the locomotive on this tile.");
        String blocked = WurmRailWorld.validateTile(target.tileX, target.tileY, target.layer);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        String towards = facingExit(network, target, engine.getRotation());
        if (towards == null) throw new IllegalArgumentException("Connect another rail section before placing the locomotive here.");
        Node exit = network.nodes.get(towards);
        requireMarker(exit);
        blocked = WurmRailWorld.validateEdge(target.tileX, target.tileY, target.layer, exit.tileX, exit.tileY, exit.layer);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        double rotation = (Math.toDegrees(Math.atan2(exit.tileX - target.tileX,
                target.tileY - exit.tileY)) + 360) % 360;
        List<Pose> original = Collections.singletonList(physicalPose(engine));
        try {
            motion.apply(Collections.singletonList(new Pose(engine.getWurmId(), target.worldX(), target.worldY(),
                    (float)rotation, target.layer, target.name)));
            return register(engine, actor.getWurmId());
        } catch (Exception failed) {
            Train partial = findTrain(engine.getWurmId());
            if (partial != null) {
                try { network.removeTrain(partial.name); }
                catch (Exception rollback) { failed.addSuppressed(rollback); }
            }
            restorePhysical(original, failed);
            throw failed;
        }
    }

    /** Engines completed or placed on connected rail become ready without a registration step.
     * Unknown ownership, occupied seats and invalid positions wait for player interaction.
     * One failed candidate cannot prevent independent engines being discovered. */
    public List<Train> discoverGroundEngines() {
        List<Train> added = new ArrayList<Train>();
        for (Item item : Items.getAllItems()) {
            if (!WurmRailWorld.isLocomotive(item) || findTrain(item.getWurmId()) != null
                    || item.getLastOwnerId() <= 0) continue;
            try {
                requireGroundCar(item);
                requireEmpty(item);
                added.add(register(item, item.getLastOwnerId()));
            } catch (Exception unavailable) {
                // The next scan retries after terrain, track, occupants or placement changes.
                if (unavailable.getSuppressed().length > 0)
                    LOG.log(Level.SEVERE, "Could not fully restore automatic setup of locomotive " + item.getWurmId(), unavailable);
            }
        }
        return added;
    }

    private Train register(Item engine, long ownerId) throws Exception {
        requireGroundCar(engine);
        Node start = nodeAt(engine.getTileX(), engine.getTileY(), engine.isOnSurface() ? 0 : -1);
        if (start == null) throw new IllegalArgumentException("Place the locomotive on connected rail first.");
        requireMarker(start);
        String towards = facingExit(network, start, engine.getRotation());
        if (towards == null) throw new IllegalArgumentException("Add a connected rail section beside the locomotive first.");
        Node departure = network.nodes.get(towards);
        requireMarker(departure);
        String blocked = WurmRailWorld.validateEdge(start.tileX, start.tileY, start.layer,
                departure.tileX, departure.tileY, departure.layer);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        List<Pose> original = Collections.singletonList(physicalPose(engine));
        Train created = network.createTrain(nextTrainName(), ownerId,
                Collections.singletonList(engine.getWurmId()), start.name, towards);
        created.maxSpeed = speedLimit;
        created.status = "Ready. Board the locomotive and power on.";
        String originalName=engine.getActualName();
        Vehicle originalVehicle=Vehicles.getVehicle(engine);
        String originalVehicleName=originalVehicle==null?null:originalVehicle.name;
        try {
            motion.apply(created.poses);
            RailwayTrainNames.synchronize(created,engine);
            return created;
        } catch (Exception failed) {
            try {
                if(!originalName.equals(engine.getActualName()))engine.setName(originalName);
                Vehicle currentVehicle=Vehicles.getVehicle(engine);
                if(currentVehicle!=null)currentVehicle.name=originalVehicleName==null?engine.getName():originalVehicleName;
            } catch(Exception rollback) { failed.addSuppressed(rollback); }
            try { network.removeTrain(created.name); }
            catch (Exception rollback) { failed.addSuppressed(rollback); }
            restorePhysical(original, failed);
            throw failed;
        }
    }

    /** Closest connected exit to the locomotive's visible heading: north=0, east=90.
     * Equal-angle choices retain graph insertion order so saved layouts stay deterministic. */
    public static String facingExit(RailNetwork network, Node origin, double rotation) {
        if (network == null || origin == null || !Double.isFinite(rotation))
            throw new IllegalArgumentException("A valid rail and locomotive heading are required.");
        int alignment=origin.getAlignmentMask();
        if(origin.links.size()>=3&&alignment==0)
            throw new IllegalArgumentException("Align the junction before placing a locomotive on it.");
        String best = null; double bestAngle = Double.POSITIVE_INFINITY;
        for (String name : origin.links) {
            Node exit = network.nodes.get(name);
            if (exit == null) continue;
            int side=RailNetwork.direction(exit.tileX-origin.tileX,exit.tileY-origin.tileY);
            if(origin.links.size()>=3&&(alignment&(1<<side))==0)continue;
            double heading = Math.toDegrees(Math.atan2(exit.tileX - origin.tileX,
                    origin.tileY - exit.tileY));
            double difference = Math.abs(((heading - rotation) % 360 + 540) % 360 - 180);
            if (difference < bestAngle - 0.000001) { best = name; bestAngle = difference; }
        }
        return best;
    }

    /** Explicit clicked-wagon operation; never silently chooses a different nearby wagon. */
    public void couple(Creature actor, Train train, Item wagon) throws Exception {
        requireKnown(train); requireStopped(train);
        requireCar(actor, wagon, true);
        if (!WurmRailWorld.isWagon(wagon))
            throw new IllegalArgumentException("Only rail wagons can be coupled.");
        if (findTrain(wagon.getWurmId()) != null)
            throw new IllegalArgumentException("That wagon is already coupled to a train.");
        if (train.carIds.size() >= maxCars)
            throw new IllegalArgumentException("This train already has its maximum of " + maxCars + " vehicles.");
        Item engine = Items.getItem(train.carIds.get(0));
        if (!WurmRailWorld.canManage(actor, engine))
            throw new IllegalArgumentException("You do not have permission to use this locomotive.");
        requireEmptyConsist(train);
        Item tail = Items.getItem(train.carIds.get(train.carIds.size() - 1));
        if (tail.isOnSurface() != wagon.isOnSurface()
                || distance(tail, wagon) > REACH)
            throw new IllegalArgumentException("Move the wagon within 8 metres of the train's rear vehicle.");
        Checkpoint checkpoint = network.checkpoint(train.name);
        List<Pose> original = physicalPoses(train);
        original.add(physicalPose(wagon));
        boolean added = false;
        try {
            network.addCar(train.name, wagon.getWurmId()); added = true;
            motion.apply(train.poses);
        } catch (Exception failed) {
            try {
                if (added) network.removeCar(train.name, wagon.getWurmId());
                network.restore(checkpoint);
            } catch (Exception rollback) { failed.addSuppressed(rollback); }
            restorePhysical(original, failed);
            throw failed;
        }
    }

    /** Suitable choices for a wagon's Couple window, ordered by distance to the train rear. */
    public List<Train> nearbyCouplingTrains(Creature actor, Item wagon) throws Exception {
        requireCar(actor, wagon, false);
        if (!WurmRailWorld.isWagon(wagon))
            throw new IllegalArgumentException("Choose a rail wagon to couple.");
        List<Train> result = new ArrayList<Train>();
        for (Train train : network.trains.values()) {
            if (train.powered || train.speed > 0.001 || train.carIds.size() >= maxCars) continue;
            Item tail = Items.getItemOptional(train.carIds.get(train.carIds.size() - 1)).orElse(null);
            Item engine = Items.getItemOptional(train.carIds.get(0)).orElse(null);
            if (tail != null && engine != null && tail.isOnSurface() == wagon.isOnSurface()
                    && distance(tail, wagon) <= REACH && WurmRailWorld.canManage(actor, engine)) result.add(train);
        }
        result.sort(Comparator.comparingDouble(train -> {
            Item tail = Items.getItemOptional(train.carIds.get(train.carIds.size() - 1)).orElse(null);
            return tail == null ? Double.POSITIVE_INFINITY : distance(tail, wagon);
        }));
        return result;
    }

    public Train nearestCouplingTrain(Creature actor, Item wagon) throws Exception {
        List<Train> nearby = nearbyCouplingTrains(actor, wagon);
        if (nearby.isEmpty()) throw new IllegalArgumentException("No stopped locomotive has room within 8 metres of this wagon.");
        return nearby.get(0);
    }

    /** Detach only the trailing wagon. Its item, position and cargo remain intact. */
    public Item uncoupleRear(Creature actor, Train train) throws Exception {
        requireKnown(train); requireStopped(train);
        if (train.carIds.size() < 2) throw new IllegalArgumentException("This train has no trailing wagon.");
        Item wagon = Items.getItem(train.carIds.get(train.carIds.size() - 1));
        requireCar(actor, wagon, true);
        Item engine = Items.getItem(train.carIds.get(0));
        if (!WurmRailWorld.canManage(actor, engine))
            throw new IllegalArgumentException("You do not have permission to use this locomotive.");
        requireEmptyConsist(train);
        network.removeCar(train.name, wagon.getWurmId());
        return wagon;
    }

    private void requireKnown(Train train) {
        if (train == null || network.trains.get(train.name) != train)
            throw new IllegalArgumentException("This train is no longer available. Open the controls again.");
    }

    private static void requireStopped(Train train) {
        if (train.powered || train.speed > 0.001)
            throw new IllegalArgumentException("Power off and stop the train before changing wagons.");
    }

    private static void requireCar(Creature actor, Item item, boolean empty) {
        requireGroundCar(item);
        if (actor == null || actor.isOnSurface() != item.isOnSurface()
                || !RailwayBridgeDeck.isAtItemLevel(actor,item.getBridgeId(),item.getFloorLevel())
                || Math.hypot(actor.getPosX() - item.getPosX(), actor.getPosY() - item.getPosY()) > REACH)
            throw new IllegalArgumentException("Stand within 8 metres of the rail vehicle on the same level.");
        if (!WurmRailWorld.canManage(actor, item))
            throw new IllegalArgumentException("You do not have permission to use this rail vehicle.");
        if (empty) requireEmpty(item);
    }

    private static void requireGroundCar(Item item) {
        if (!WurmRailWorld.isRailCar(item) || item.getParentId() != NO_ID
                || item.getOwnerId() != NO_ID || item.isBanked() || item.isMailed() || item.isTraded()
                || item.getFloorLevel() != 0
                || !RailwayBridgeDeck.matches(item.getTileX(),item.getTileY(),item.isOnSurface()?0:-1,item.getBridgeId()))
            throw new IllegalArgumentException("The rail vehicle must be available on the ground or a completed bridge.");
        String blocked = WurmRailWorld.validateTile(item.getTileX(), item.getTileY(), item.isOnSurface() ? 0 : -1);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        if (Vehicles.getVehicle(item) == null)
            throw new IllegalArgumentException("The rail vehicle's seats are unavailable. Try again shortly.");
    }

    private static void requireEmpty(Item item) {
        Vehicle seats = Vehicles.getVehicle(item);
        if (seats != null && seats.isAnySeatOccupied())
            throw new IllegalArgumentException("Everyone must disembark before setting up the train or changing wagons.");
    }

    private static void requireEmptyConsist(Train train) throws Exception {
        for (long id : train.carIds) { Item item = Items.getItem(id); requireGroundCar(item); requireEmpty(item); }
    }

    private static void requireMarker(Node node) {
        Item marker = node == null ? null : Items.getItemOptional(node.markerId).orElse(null);
        if (marker == null || marker.getTemplateId() != WurmRailWorld.trackTemplateId
                || marker.getTileX() != node.tileX || marker.getTileY() != node.tileY
                || marker.isOnSurface() != (node.layer == 0) || marker.getParentId() != NO_ID
                || marker.getBridgeId()!=node.bridgeId
                || !RailwayBridgeDeck.matches(node.tileX,node.tileY,node.layer,node.bridgeId))
            throw new IllegalArgumentException("This rail needs repair before a train can use it.");
    }

    private Node nodeAt(int x, int y, int layer) {
        for (Node node : network.nodes.values())
            if (node.tileX == x && node.tileY == y && node.layer == layer) return node;
        return null;
    }

    private String nextTrainName() {
        for (long suffix = 1; ; suffix++) {
            String name = "Train_" + suffix;
            if (!network.trains.containsKey(name)) return name;
        }
    }

    private static List<Pose> physicalPoses(Train train) throws Exception {
        List<Pose> poses = new ArrayList<Pose>();
        for (long id : train.carIds) poses.add(physicalPose(Items.getItem(id)));
        return poses;
    }

    private static Pose physicalPose(Item item) {
        return new Pose(item.getWurmId(), item.getPosX(), item.getPosY(), item.getRotation(), item.isOnSurface() ? 0 : -1);
    }

    private void restorePhysical(List<Pose> original, Exception failed) {
        // A later car may have failed after earlier cars already reached the new poses.
        // Restore each independently so one damaged/deleted item does not strand the rest.
        for (Pose pose : original) {
            try { motion.restore(Collections.singletonList(pose)); }
            catch (Exception rollback) { failed.addSuppressed(rollback); }
        }
    }

    private static double distance(Item first, Item second) {
        return Math.hypot(first.getPosX() - second.getPosX(), first.getPosY() - second.getPosY());
    }
}
