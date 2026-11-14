import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreaturePos;
import com.wurmonline.server.creatures.CreatureStatus;
import com.wurmonline.server.creatures.DbCreatureStatus;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.wurmrail.RailwayTrainService;
import org.wurmrail.RailwayTrainNames;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Invoked by IntegrationSmoke only after real mod discovery and synthetic terrain setup. */
public final class UsabilitySmoke {
    private static int assertions;
    private static final long OWNER = 700000032L;

    public static void run() throws Exception {
        headings();
        automaticSetupAndCoupling();
        failedSetupRollsBack();
        placement();
        System.out.println("PASS native numbered train names, existing generic-name migration and preserved custom names");
        System.out.println("PASS commandless train setup, exact clicked wagon coupling, permission/occupancy "
                + "guards and native movement rollback (" + assertions + " assertions)");
    }

    private static void headings() throws Exception {
        RailNetwork net = new RailNetwork();
        Node origin = net.addNode("origin", 20, 20, 0);
        int[][] directions = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
        require(RailwayTrainService.facingExit(net, origin, 90) == null, "isolated rail has no automatic departure");
        for (int index = 0; index < directions.length; index++) {
            net.addNode("exit" + index, 20 + directions[index][0], 20 + directions[index][1], 0);
            net.connect(origin.name, "exit" + index);
        }
        for (int index = 0; index < directions.length; index++) {
            net.setJunctionAlignment(origin.name,(1<<index)|(1<<((index+4)%8)));
            require(("exit" + index).equals(RailwayTrainService.facingExit(net, origin, index * 45)),
                    "visible heading chooses connected exit in direction " + index);
            require(("exit" + index).equals(RailwayTrainService.facingExit(net, origin, index * 45 + 720)),
                    "positive wrapped heading " + index);
            require(("exit" + index).equals(RailwayTrainService.facingExit(net, origin, index * 45 - 720)),
                    "negative wrapped heading " + index);
        }
        net.setJunctionAlignment(origin.name,3);
        require("exit0".equals(RailwayTrainService.facingExit(net, origin, 22.5)), "equal angle retains stable connection order");
        net.setJunctionAlignment(origin.name,17);
        require("exit0".equals(RailwayTrainService.facingExit(net, origin, 359)), "north wrap chooses north");
        net.setJunctionAlignment(origin.name,68);
        require("exit2".equals(RailwayTrainService.facingExit(net, origin, 90)), "east-facing engine chooses east within the physical east-west pair");
        require("exit6".equals(RailwayTrainService.facingExit(net, origin, 270)), "west-facing engine chooses west within the same physical pair");
        require(origin.getAlignmentMask()==68,"new engine orientation never alters the junction's physical pair");
        net.setJunctionAlignment(origin.name,0);
        expectFailure(()->RailwayTrainService.facingExit(net,origin,90),"unconfigured junction requires physical alignment before engine setup");
    }

    private static void automaticSetupAndCoupling() throws Exception {
        RailNetwork net = new RailNetwork();
        NativeMotion mover = new NativeMotion();
        RailwayTrainService service = new RailwayTrainService(net, 2.25, 3, mover);
        line(net, "ready", 96, 104, 100);
        Item engine = engine(100, 100, 90, OWNER);
        require(engine.getActualName().equals(ItemFactory.generateName(engine.getTemplate(),engine.getMaterial())),
                "fresh native locomotive starts with the template's generated size-prefixed name");
        line(net, "unknown", 118, 122, 100);
        Item ownerUnknown = engine(120, 100, 0, -10L);
        line(net, "mounted", 138, 142, 100);
        Item occupied = engine(140, 100, 180, OWNER);
        Vehicle mountedSeats = Vehicles.getVehicle(occupied);
        long previousOccupant = mountedSeats.seats[0].occupant;
        mountedSeats.seats[0].occupant = 777L;
        line(net, "broken", 158, 162, 100);
        Item broken = engine(160, 100, 45, OWNER);
        net.nodes.get("broken160").markerId = -10L;
        List<Train> created;
        try { created = service.discoverGroundEngines(); }
        finally { mountedSeats.seats[0].occupant = previousOccupant; }
        require(created.size() == 1, "automatic scan sets up only a safe, known-owner, empty locomotive");
        Train train = created.get(0);
        require(train.name.equals("Train_1"), "automatic unique name");
        require("Train 1".equals(engine.getActualName()) && "Train 1".equals(engine.getName())
                && "Train 1".equals(RailwayTrainNames.label(train,engine))
                && "Train 1".equals(Vehicles.getVehicle(engine).name),
                "automatic registration gives the physical locomotive, controls and native seats its numbered train name");
        require(service.synchronizeExistingNames()==0,"numbered train naming is idempotent");
        engine.setName(ItemFactory.generateName(engine.getTemplate(),engine.getMaterial()));
        Vehicles.getVehicle(engine).name="stale vehicle name";
        require("Train 1".equals(RailwayTrainNames.label(train,engine)),
                "an older large rail locomotive name falls back to its existing numbered identity");
        require(service.synchronizeExistingNames()==1 && "Train 1".equals(engine.getActualName())
                && "Train 1".equals(Vehicles.getVehicle(engine).name),
                "existing generic locomotive names migrate without changing the train identity");
        engine.setName("rail locomotive");
        require(service.synchronizeExistingNames()==1 && "Train 1".equals(engine.getActualName()),
                "older unprefixed generic locomotive names migrate too");
        engine.setName("Northern Express");
        require(service.synchronizeExistingNames()==1 && "Northern Express".equals(engine.getActualName())
                && "Northern Express".equals(Vehicles.getVehicle(engine).name)
                && "Northern Express".equals(RailwayTrainNames.label(train,engine)),
                "custom player names survive migration while their native vehicle label refreshes");
        require(service.synchronizeExistingNames()==0,"custom train naming is idempotent");
        require(train.ownerId == OWNER, "discovery retains the real vehicle owner");
        require(train.maxSpeed == 2.25, "automatic train respects configured speed cap");
        require(Math.abs(engine.getRotation() - 90) < .001, "visible east-facing locomotive departs east");
        require(service.findTrain(ownerUnknown.getWurmId()) == null, "unowned engine waits for player interaction");
        require(service.findTrain(occupied.getWurmId()) == null, "mounted engine is never repositioned or registered");
        require(service.findTrain(broken.getWurmId()) == null, "broken track marker prevents registration");
        require(occupied.getRotation() == 180 && broken.getRotation() == 45, "skipped engines keep physical orientation");

        Creature actor = actor(OWNER, engine.getPosX(), engine.getPosY());
        require(service.ensureTrain(actor, engine) == train, "opening controls twice retains one train identity");
        require(net.trains.size() == 1, "existing engine does not receive a second registration");
        Item selected = cart(99, 100, OWNER);
        Item untouched = cart(97, 100, OWNER);
        Item cargo = ItemFactory.createItem(ItemList.plank, 50f, "Usability fixture");
        require(selected.insertItem(cargo), "native cargo accepted before coupling");
        require(service.nearestCouplingTrain(actor, selected) == train, "wagon offers the nearby train rear");
        service.couple(actor, train, selected);
        require(train.carIds.size() == 2 && train.carIds.get(1) == selected.getWurmId(),
                "coupling uses the exact wagon clicked by the player");
        require(service.findTrain(untouched.getWurmId()) == null, "other parked wagon stays uncoupled");
        require(cargo.getParentId() == selected.getWurmId(), "coupling preserves native cargo");
        List<Pose> oldPoses = new ArrayList<Pose>(train.poses);
        Item second = cart(98, 100, OWNER);
        WurmRailWorld.moveCar(second.getWurmId(), second.getPosX() + .4, second.getPosY() + .3, 27, 0);
        float oldSecondX = second.getPosX(), oldSecondY = second.getPosY(), oldSecondRotation = second.getRotation();
        mover.failAfterCarId = second.getWurmId();
        expectFailure(() -> service.couple(actor, train, second), "native failure after first move is reported");
        require(train.carIds.size() == 2 && service.findTrain(second.getWurmId()) == null,
                "failed coupling restores the original consist and frees the wagon");
        require(samePoses(oldPoses, train.poses), "failed coupling restores the core path and poses");
        require(second.getPosX() == oldSecondX && second.getPosY() == oldSecondY
                && second.getRotation() == oldSecondRotation, "failed coupling restores the parked wagon's native position");
        require(cargo.getParentId() == selected.getWurmId(), "failed coupling preserves cargo identity");
        Vehicle seats = Vehicles.getVehicle(engine);
        long oldOccupant = seats.seats[0].occupant;
        seats.seats[0].occupant = 888L;
        try { expectFailure(() -> service.couple(actor, train, second), "occupied consist refuses coupling"); }
        finally { seats.seats[0].occupant = oldOccupant; }
        require(train.carIds.size() == 2, "occupied coupling leaves the train untouched");
        net.start(train.name);
        expectFailure(() -> service.couple(actor, train, second), "powered train refuses coupling");
        net.stop(train.name);
        Item detached = service.uncoupleRear(actor, train);
        require(detached == selected && train.carIds.size() == 1, "rear uncoupling preserves the same wagon item");
        require(cargo.getParentId() == selected.getWurmId(), "uncoupling preserves cargo");

        line(net, "second", 178, 182, 100);
        Item next = engine(180, 100, 270, -10L);
        next.setName("Eastern Flyer");
        Creature nextActor = actor(OWNER, next.getPosX(), next.getPosY());
        next.setLastOwnerId(OWNER);
        Train secondTrain = service.ensureTrain(nextActor, next);
        require(secondTrain.name.equals("Train_2"), "interacting with another engine generates a different name");
        require("Eastern Flyer".equals(next.getActualName()) && "Eastern Flyer".equals(Vehicles.getVehicle(next).name)
                && "Eastern Flyer".equals(RailwayTrainNames.label(secondTrain,next)),
                "registering an engine retains a name the player gave it before setup");
        require(secondTrain.ownerId == OWNER && secondTrain.carIds.get(0) == next.getWurmId(),
                "interactive setup uses the clicked engine and authorized player");
        Creature far = actor(OWNER, next.getPosX() + 9, next.getPosY());
        expectFailure(() -> service.ensureTrain(far, next), "remote setup rejected");
        expectFailure(() -> service.ensureTrain(null, next), "setup requires an actual player interaction");
    }

    private static void failedSetupRollsBack() throws Exception {
        RailNetwork net = new RailNetwork();
        line(net, "rollback", 198, 202, 100);
        Item engine = engine(200, 100, 13, OWNER);
        String originalName=engine.getActualName(),originalVehicleName=Vehicles.getVehicle(engine).name;
        // The normal setup aligns an unoccupied vehicle with its rail curve.
        WurmRailWorld.moveCar(engine.getWurmId(), engine.getPosX() - .7, engine.getPosY() + .3, 13, 0);
        float x = engine.getPosX(), y = engine.getPosY(), rotation = engine.getRotation();
        NativeMotion mover = new NativeMotion(); mover.failNextApply = true;
        RailwayTrainService service = new RailwayTrainService(net, 3, 8, mover);
        require(service.discoverGroundEngines().isEmpty(), "failed native setup is not reported as successful");
        require(net.trains.isEmpty(), "failed native setup removes partial train registration");
        require(originalName.equals(engine.getActualName()) && originalVehicleName.equals(Vehicles.getVehicle(engine).name),
                "failed setup leaves the previous item and vehicle names intact");
        require(engine.getPosX() == x && engine.getPosY() == y && engine.getRotation() == rotation,
                "failed native setup returns the engine to its original native position and heading");
        require(service.discoverGroundEngines().size() == 1, "a repaired candidate can be discovered on a later scan");
    }

    private static void placement() throws Exception {
        RailNetwork net = new RailNetwork();
        line(net, "placement", 218, 222, 100);
        Item engine = engine(220, 101, 90, OWNER);
        Creature actor = actor(OWNER, engine.getPosX(), engine.getPosY());
        NativeMotion mover = new NativeMotion();
        RailwayTrainService service = new RailwayTrainService(net, 3, 8, mover);
        Node target = net.nodes.get("placement220");
        Train placed = service.placeOnTrack(actor, engine, target);
        require(placed.carIds.get(0) == engine.getWurmId(), "placement sets up the exact clicked locomotive");
        require(engine.getTileX() == 220 && engine.getTileY() == 100,
                "placement moves an off-track fixed locomotive onto nearby rail");
        require(placed.ownerId == OWNER && placed.name.equals("Train_1"), "placement creates an automatic owned train");
        expectFailure(() -> service.placeOnTrack(actor, engine, target), "registered train cannot be repositioned");

        RailNetwork rollbackNet = new RailNetwork();
        line(rollbackNet, "placementRollback", 238, 242, 100);
        Item rollbackEngine = engine(240, 101, 27, OWNER);
        Creature rollbackActor = actor(OWNER, rollbackEngine.getPosX(), rollbackEngine.getPosY());
        NativeMotion rollbackMotion = new NativeMotion();
        rollbackMotion.failOnApply = 2; // Move onto rail, then fail while committing automatic setup.
        RailwayTrainService rollbackService = new RailwayTrainService(rollbackNet, 3, 8, rollbackMotion);
        float x = rollbackEngine.getPosX(), y = rollbackEngine.getPosY(), rotation = rollbackEngine.getRotation();
        expectFailure(() -> rollbackService.placeOnTrack(rollbackActor, rollbackEngine,
                rollbackNet.nodes.get("placementRollback240")), "failed placement reports the world failure");
        require(rollbackNet.trains.isEmpty(), "failed placement removes its partial automatic registration");
        require(rollbackEngine.getPosX() == x && rollbackEngine.getPosY() == y && rollbackEngine.getRotation() == rotation,
                "failed placement restores the locomotive's original off-track native position: expected "
                        + x + "," + y + "," + rotation + "; actual " + rollbackEngine.getPosX() + ","
                        + rollbackEngine.getPosY() + "," + rollbackEngine.getRotation());
        require(rollbackService.placeOnTrack(rollbackActor, rollbackEngine,
                rollbackNet.nodes.get("placementRollback240")) != null, "failed placement can be retried safely");
    }

    private static void line(RailNetwork net, String prefix, int startX, int endX, int y) throws Exception {
        String previous = null;
        for (int x = startX; x <= endX; x++) {
            Node node = net.addNode(prefix + x, x, y, 0);
            node.markerId = WurmRailWorld.spawnTrackMarker(null, node.worldX(), node.worldY(), 0).getWurmId();
            if (previous != null) net.connect(previous, node.name);
            previous = node.name;
        }
    }

    private static Item engine(int tileX, int tileY, float rotation, long owner) throws Exception {
        Item engine = WurmRailWorld.spawnLocomotive(null, tileX * 4 + 2, tileY * 4 + 2, 0);
        engine.setLastOwnerId(owner);
        WurmRailWorld.moveCar(engine.getWurmId(), engine.getPosX(), engine.getPosY(), rotation, 0);
        return engine;
    }

    private static Item cart(int tileX, int tileY, long owner) throws Exception {
        Item cart = WurmRailWorld.spawnCart(null, tileX * 4 + 2, tileY * 4 + 2, 0);
        cart.setLastOwnerId(owner);
        return cart;
    }

    /** Avoid constructing an actual server creature, network session or player database record. */
    public static Creature actor(long id, float x, float y) throws Exception {
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true); Object unsafe = unsafeField.get(null);
        java.lang.reflect.Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        Creature actor = (Creature)allocate.invoke(unsafe, Creature.class);
        DbCreatureStatus status = (DbCreatureStatus)allocate.invoke(unsafe, DbCreatureStatus.class);
        Field identity = Creature.class.getDeclaredField("id"); identity.setAccessible(true); identity.setLong(actor, id);
        Field statusField = Creature.class.getDeclaredField("status"); statusField.setAccessible(true); statusField.set(actor, status);
        Field holder = CreatureStatus.class.getDeclaredField("statusHolder"); holder.setAccessible(true); holder.set(status, actor);
        Field position = CreatureStatus.class.getDeclaredField("position"); position.setAccessible(true);
        position.set(status, new CreaturePos(id, x, y, 5f, 0f, 0, 0, -10L, false));
        actor.vehicle = -10L;
        return actor;
    }

    private static final class NativeMotion implements RailwayTrainService.Motion {
        boolean failNextApply;
        int failOnApply;
        long failAfterCarId;
        @Override public void apply(List<Pose> poses) throws Exception {
            boolean failAfterFirstMove = failNextApply || (failOnApply > 0 && --failOnApply == 0);
            for (Pose pose : poses) {
                move(pose);
                if (failAfterFirstMove || pose.carId == failAfterCarId) {
                    failNextApply = false; failAfterCarId = 0;
                    throw new IllegalStateException("Injected native world failure");
                }
            }
        }
        @Override public void restore(List<Pose> poses) throws Exception { for (Pose pose : poses) move(pose); }
        private void move(Pose pose) throws Exception {
            WurmRailWorld.moveCar(pose.carId, pose.x, pose.y, pose.rotation, pose.layer);
        }
    }

    private static boolean samePoses(List<Pose> first, List<Pose> second) {
        if (first.size() != second.size()) return false;
        for (int index = 0; index < first.size(); index++) {
            Pose a = first.get(index), b = second.get(index);
            if (a.carId != b.carId || a.x != b.x || a.y != b.y || a.rotation != b.rotation || a.layer != b.layer) return false;
        }
        return true;
    }

    private interface CheckedAction { void run() throws Exception; }
    private static void expectFailure(CheckedAction action, String label) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        catch (IllegalStateException expected) { assertions++; return; }
        throw new AssertionError(label);
    }

    private static void require(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
