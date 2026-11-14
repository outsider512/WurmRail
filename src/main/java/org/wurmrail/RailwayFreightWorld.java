package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.FreightStops.Operation;
import org.wurmrail.core.FreightStops.Stop;
import org.wurmrail.core.FreightTransfers;
import org.wurmrail.core.FreightTransfers.Cargo;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Real Wurm inventories. No actor, temporary cargo copy or virtual commodity is created. */
public final class RailwayFreightWorld implements RailwayFreightService.World {
    private static final Logger LOG=Logger.getLogger(RailwayFreightWorld.class.getName());
    private static final long NO_ID=-10L;
    public static final double STORAGE_REACH=8.0;
    private static final int MAX_ITEMS=4096;
    private final RailNetwork network;
    public RailwayFreightWorld(RailNetwork network) { this.network=Objects.requireNonNull(network); }

    /** Configuration alone visits nearby tiles; passive freight stops never poll the world. */
    public static List<Item> nearbyStorage(Creature actor,Node rail) {
        return nearbyStorage(actor,rail,actor==null?-10L:actor.getWurmId());
    }
    public static List<Item> nearbyStorage(Creature actor,Node rail,long ownerId) {
        List<Item> result=new ArrayList<Item>();
        if(actor==null||rail==null)return result;
        for(int x=Math.max(0,rail.tileX-2);x<=rail.tileX+2;x++)for(int y=Math.max(0,rail.tileY-2);y<=rail.tileY+2;y++) {
            VolaTile tile=Zones.getTileOrNull(x,y,rail.layer==0);if(tile==null)continue;
            for(Item item:tile.getItems())try { validateStorage(actor,rail,item,ownerId);result.add(item); }
            catch(IllegalArgumentException unavailable) { }
        }
        result.sort(Comparator.comparing((Item item)->item.getName()).thenComparingLong(Item::getWurmId));
        return result;
    }
    public static void validateStorage(Creature actor,Node rail,Item storage) {
        validateStorage(actor,rail,storage,actor==null?-10L:actor.getWurmId());
    }
    public static void validateStorage(Creature actor,Node rail,Item storage,long ownerId) {
        if(actor==null)throw new IllegalArgumentException("A freight stop builder is required.");
        String problem=storageProblem(rail,storage,ownerId);
        if(problem!=null)throw new IllegalArgumentException(problem);
        if(actor.isOnSurface()!=(rail.layer==0)||actor.getFloorLevel()!=0||actor.getBridgeId()!=NO_ID
                ||Math.hypot(actor.getPosX()-rail.worldX(),actor.getPosY()-rail.worldY())>STORAGE_REACH)
            throw new IllegalArgumentException("Stand within eight metres of the freight rail on the same ground level.");
        if(actor.getPower()<2&&(!Methods.isActionAllowed(actor,Actions.OPEN,storage)
                ||!Methods.isActionAllowed(actor,Actions.TAKE,storage)))
            throw new IllegalArgumentException("Wurm permissions deny access to this trackside storage.");
    }
    /** Ownership is checked by durable player ID, so authorized services also work offline. */
    public static String storageProblem(Node rail,Item storage,long ownerId) {
        if(rail==null||storage==null)return "The freight rail or associated storage is missing.";
        if(!ordinaryContainer(storage)||WurmRailWorld.isRailCar(storage))
            return "Choose an ordinary container, Bulk Hopper or Log Bunk, not a vehicle, native bulk bin or crate.";
        if(!storage.isOwner(ownerId))return "The associated storage must belong to the freight stop's owner.";
        if(storage.getParentId()!=NO_ID||storage.getOwnerId()!=NO_ID||storage.isBanked()||storage.isMailed()||storage.isTraded())
            return "Associated storage must remain on the ground, outside inventories and trades.";
        if(storage.isLocked()||storage.isSealedByPlayer())return "Unlock and unseal the associated storage before automatic freight service.";
        if(storage.isOnSurface()!=(rail.layer==0)||storage.getFloorLevel()!=0||storage.getBridgeId()!=NO_ID
                ||Math.hypot(storage.getPosX()-rail.worldX(),storage.getPosY()-rail.worldY())>STORAGE_REACH)
            return "Associated storage must remain within eight metres of its freight rail on the same ground level.";
        return null;
    }
    private static boolean ordinaryContainer(Item item) {
        return item!=null&&!SteamWaterPumpRegistry.isPump(item.getTemplateId())&&!SteamYarderRegistry.isYarder(item.getTemplateId())&&!SteamRockDrillRegistry.isDrill(item.getTemplateId())&&!LiquidTankPolicy.isIndustrialTank(item)
                &&(BulkHopperPolicy.isHopper(item)||item.isHollow())&&!item.isInventory()&&!item.isInventoryGroup()
                &&!item.isBodyPart()&&!item.isBulkContainer()&&!item.isCrate()&&!item.isVehicle()
                &&!item.isCorpse()&&!item.isMailBox();
    }
    @Override public String unavailableReason(Train train,Stop stop) {
        if(train.ownerId!=stop.ownerId)return "Only trains belonging to this stop's owner may use its storage.";
        Node rail=network.nodes.get(stop.rail);
        Item marker=rail==null?null:Items.getItemOptional(stop.markerId).orElse(null);
        if(rail==null||rail.markerId!=stop.markerId||marker==null||marker.getTemplateId()!=WurmRailWorld.trackTemplateId
                ||marker.getParentId()!=NO_ID||marker.getTileX()!=rail.tileX||marker.getTileY()!=rail.tileY
                ||marker.isOnSurface()!=(rail.layer==0)||marker.getOwnerId()!=NO_ID||marker.getFloorLevel()!=0
                ||marker.getBridgeId()!=NO_ID||marker.isBanked()||marker.isMailed()||marker.isTraded())
            return "The freight rail marker is missing or misplaced.";
        boolean atStop=false;
        for(Pose pose:train.poses) {
            Item actual=Items.getItemOptional(pose.carId).orElse(null);
            if(actual==null||actual.isOnSurface()!=(pose.layer==0)
                    ||Math.hypot(actual.getPosX()-pose.x,actual.getPosY()-pose.y)>.51)
                return "A train vehicle is missing or moved away from its stopped rail position.";
            if(pose.layer==rail.layer&&Math.hypot(pose.x-rail.worldX(),pose.y-rail.worldY())<.51)atStop=true;
        }
        if(!atStop)return "The train is no longer at this freight stop.";
        String problem=storageProblem(rail,Items.getItemOptional(stop.storageId).orElse(null),stop.ownerId);
        if(problem!=null)return problem;
        if(cars(train,stop.ownerId).isEmpty())return "No eligible unlocked cargo wagon belonging to this train's owner is attached.";
        return null;
    }
    /** Loader compatibility is an independent seam for future dedicated car/loader types. */
    public static boolean acceptsCar(Item car,long ownerId) {
        return car!=null&&(car.getTemplateId()==WurmRailWorld.cartTemplateId||LogCarRegistry.isLogCar(car.getTemplateId()))&&car.isHollow()
                &&car.isOwner(ownerId)&&car.getParentId()==NO_ID&&car.getOwnerId()==NO_ID
                &&!car.isBanked()&&!car.isMailed()&&!car.isTraded()&&!car.isLocked()&&!car.isSealedByPlayer()
                &&car.getFloorLevel()==0&&car.getBridgeId()==NO_ID;
    }
    private static List<Item> cars(Train train,long owner) {
        List<Item> result=new ArrayList<Item>();
        for(long id:train.carIds) { Item item=Items.getItemOptional(id).orElse(null);if(acceptsCar(item,owner))result.add(item); }
        return result;
    }
    /** Read-only controls diagnostic; the native inventory remains the capacity authority. */
    public static String cargoCapacitySummary(Train train) {
        if(train==null)return "No registered train cargo inventory.";
        List<Item> general=new ArrayList<Item>(),timber=new ArrayList<Item>();
        for(Item car:cars(train,train.ownerId))(LogCarPolicy.isCar(car)?timber:general).add(car);
        String result=general.isEmpty()?"":capacitySummary(general,"General wagon cargo");
        if(!timber.isEmpty())result+=(result.isEmpty()?"":"; ")+capacitySummary(timber,"Log Car timber (logs and felled trees only)");
        return result.isEmpty()?"Wagon cargo: no eligible unlocked cargo inventory.":result;
    }
    private static String capacitySummary(List<Item> inventories,String label) {
        if(inventories.isEmpty())return label+": no eligible unlocked cargo inventory.";
        long grams=0,limitGrams=0,count=0,limitCount=0,volume=0,limitVolume=0;
        boolean unlimitedWeight=false,unlimitedCount=false;
        for(Item inventory:inventories) {
            grams+=Math.max(0,(long)inventory.getFullWeight()-inventory.getWeightGrams());
            count+=inventory.getItemCount();
            long capacity=Math.max(0,inventory.getContainerVolume());
            volume+=Math.max(0,capacity-inventory.getFreeVolume());limitVolume+=capacity;
            int weight=inventory.getMaxItemWeight(),items=inventory.getMaxItemCount();
            if(BulkHopperPolicy.isHopper(inventory))weight=(int)BulkHopperPolicy.MAX_WEIGHT_GRAMS;
            if(LogBunkPolicy.isBunk(inventory))weight=(int)LogBunkPolicy.MAX_WEIGHT_GRAMS;
            if(LogCarPolicy.isCar(inventory))weight=(int)LogCarPolicy.MAX_WEIGHT_GRAMS;
            if(weight<0)unlimitedWeight=true;else limitGrams+=weight;
            if(items<0)unlimitedCount=true;else limitCount+=items;
        }
        return label+": "+String.format(Locale.ROOT,"%,.1f",grams/1000.0)
                +(unlimitedWeight?" kg (no weight limit)":" / "+String.format(Locale.ROOT,"%,.1f kg",limitGrams/1000.0))
                +"; "+count+(unlimitedCount?" stacks":" / "+limitCount+" stacks")
                +"; "+String.format(Locale.ROOT,"%,.1f / %,.1f litres",volume/1000.0,limitVolume/1000.0)+".";
    }
    @Override public FreightTransfers.Inventory inventory(Train train,Stop stop) { return new NativeInventory(train,stop); }

    private final class NativeInventory implements FreightTransfers.Inventory {
        private final Train train;
        private final Stop stop;
        private boolean operatorIntervention;
        private boolean checkedLayout;
        private String layoutProblem;
        NativeInventory(Train train,Stop stop) { this.train=train;this.stop=stop; }
        @Override public String capacityDescription() { return capacitySummary(destinations(),"Destination capacity"); }
        @Override public String unavailableReason() {
            String unavailable=RailwayFreightWorld.this.unavailableReason(train,stop);
            if(unavailable!=null)return unavailable;
            if(!checkedLayout) {
                checkedLayout=true;List<Item> inventories=new ArrayList<Item>(sources());inventories.addAll(destinations());
                for(Item inventory:inventories)for(Item cargo:inventory.getItems())
                    if(cargo.getParentId()!=inventory.getWurmId()||Items.getItemOptional(cargo.getWurmId()).orElse(null)!=cargo) {
                        operatorIntervention=true;layoutProblem="Cargo inventory links require operator attention.";return layoutProblem;
                    }
            }
            return layoutProblem;
        }
        private List<Item> sources() {
            if(stop.operation==Operation.UNLOAD)return cars(train,stop.ownerId);
            Item storage=Items.getItemOptional(stop.storageId).orElse(null);
            return storage==null?Collections.emptyList():Collections.singletonList(storage);
        }
        private List<Item> destinations() {
            if(stop.operation==Operation.LOAD)return cars(train,stop.ownerId);
            Item storage=Items.getItemOptional(stop.storageId).orElse(null);
            return storage==null?Collections.emptyList():Collections.singletonList(storage);
        }
        /** A dedicated car carries both timber forms; legacy general-only trains remain usable. */
        private List<Item> destinationsFor(Item cargo) {
            List<Item> available=destinations();
            if(stop.operation!=Operation.LOAD||!RawTimberCargo.allows(cargo.getTemplateId()))return available;
            boolean dedicated=false;
            for(long id:train.carIds)if(LogCarPolicy.isCar(Items.getItemOptional(id).orElse(null))){dedicated=true;break;}
            if(!dedicated)return available;
            List<Item> timber=new ArrayList<Item>();
            for(Item car:available)if(LogCarPolicy.isCar(car))timber.add(car);
            return timber;
        }
        @Override public List<Cargo> candidates() {
            List<Cargo> result=new ArrayList<Cargo>();
            for(Item source:sources()) {
                List<Item> items=new ArrayList<Item>(source.getItems());items.sort(Comparator.comparingLong(Item::getWurmId));
                // The guard applies to each bounded inventory. A configured long
                // train must not lose access to cargo spread over several cars.
                if(items.size()>MAX_ITEMS)throw new IllegalStateException("Too many direct freight items in one source inventory.");
                for(Item item:items) {
                    long grams=cargoWeight(item);
                    if(item.getParentId()==source.getWurmId()&&grams>0)result.add(new Cargo(item.getWurmId(),item.getTemplateId(),grams));
                }
            }
            return result;
        }
        private Item current(Cargo cargo) {
            Item item=Items.getItemOptional(cargo.itemId).orElse(null);
            if(item==null||item.getTemplateId()!=cargo.templateId||cargoWeight(item)!=cargo.weightGrams
                    ||stop.templateId>0&&item.getTemplateId()!=stop.templateId)return null;
            for(Item source:sources())if(item.getParentId()==source.getWurmId()&&source.getItems().contains(item))return item;
            return null;
        }
        @Override public boolean canMove(Cargo cargo) {
            if(unavailableReason()!=null)return false;
            Item item=current(cargo);if(item==null)return false;
            for(Item destination:destinationsFor(item))if(canInsert(destination,item))return true;
            return false;
        }
        @Override public boolean move(Cargo cargo) {
            if(unavailableReason()!=null)return false;
            Item item=current(cargo);if(item==null)return false;
            Item source=item.getParentOrNull();if(source==null)return false;
            for(Item destination:destinationsFor(item))if(canInsert(destination,item))try { return transfer(source,destination,item); }
            catch(UnsafeTransferException unsafe) { operatorIntervention=true;throw unsafe; }
            return false;
        }
        @Override public boolean requiresOperatorIntervention() { return operatorIntervention; }
    }
    private static boolean canInsert(Item destination,Item cargo) {
        return destination!=cargo&&cargo.getParentId()!=destination.getWurmId()&&destination.canHold(cargo)
                &&destination.mayCreatureInsertItem()&&destination.testInsertItem(cargo);
    }
    /** Safe generic cargo only; sealed, traded, virtual bulk and live/rail objects need dedicated loaders. */
    public static long cargoWeight(Item root) {
        if(root==null)return -1;
        Set<Long> seen=new HashSet<Long>();ArrayDeque<Item> pending=new ArrayDeque<Item>();pending.add(root);long grams=0;
        while(!pending.isEmpty()) {
            Item item=pending.removeFirst();
            if(!seen.add(item.getWurmId())||seen.size()>MAX_ITEMS||item.isInventory()||item.isInventoryGroup()
                    ||item.isBodyPart()||item.isArtifact()||item.isBulkItem()||item.isBulkContainer()||item.isCrate()
                    ||item.isLiquid()||item.isVehicle()||WurmRailWorld.isRailCar(item)||item.isNoTake()||item.isNoDrop()||item.isNoTrade()
                    ||item.isBanked()||item.isMailed()||item.isTraded()||item.isPlanted()||item.isLocked()||item.isSealedByPlayer()
                    ||item.getWeightGrams()<0)return -1;
            grams+=item.getWeightGrams();
            for(Item contained:item.getItems()) {
                if(contained.getParentId()!=item.getWurmId())return -1;
                pending.addLast(contained);
            }
        }
        return grams>0?grams:-1;
    }
    /** Native insertion validates before detaching and updates original IDs, ownership and watchers itself. */
    private static boolean transfer(Item source,Item destination,Item cargo) {
        boolean inserted;
        try {
            inserted=destination.insertItem(cargo,false,true);
        } catch(RuntimeException failure) {
            // A notification may fail after the native inventory move committed.
            if(committed(source,destination,cargo))return true;
            if(!unchanged(source,destination,cargo))try { restore(source,destination,cargo); }
            catch(UnsafeTransferException rollback) { rollback.addSuppressed(failure);throw rollback; }
            throw failure;
        }
        if(committed(source,destination,cargo))return true;
        if(!inserted&&unchanged(source,destination,cargo))return false;
        restore(source,destination,cargo);
        return false;
    }
    private static boolean unchanged(Item source,Item destination,Item item) {
        return item.getParentId()==source.getWurmId()&&source.getItems().contains(item)&&!destination.getItems().contains(item);
    }
    private static boolean committed(Item source,Item destination,Item item) {
        return item.getParentId()==destination.getWurmId()&&destination.getItems().contains(item)&&!source.getItems().contains(item);
    }
    private static void restore(Item source,Item destination,Item item) {
        try {
            if(destination.getItems().contains(item))destination.dropItem(item.getWurmId(),false);
            if(!source.insertItem(item,true))throw new IllegalStateException("Cannot restore the original cargo inventory.");
            if(!unchanged(source,destination,item))throw new IllegalStateException("The cargo parent could not be restored.");
        } catch(Exception failure) {
            LOG.log(Level.SEVERE,"Freight rollback requires operator attention for item "+item.getWurmId(),failure);
            throw new UnsafeTransferException("Freight rollback requires operator attention for item "+item.getWurmId(),failure);
        }
    }
    private static final class UnsafeTransferException extends IllegalStateException {
        UnsafeTransferException(String message,Throwable cause) { super(message,cause); }
    }
}
