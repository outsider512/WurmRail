package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.WaterUnloadStops.Stop;
import org.wurmrail.core.WaterUnloadStops.Operation;

/** Existing service hold, real native liquid inventories, one shared transfer allowance. */
public final class RailwayWaterUnloadWorld implements RailwayWaterUnloadService.World {
    public static final double REACH=8;
    private final RailNetwork network;
    public RailwayWaterUnloadWorld(RailNetwork network){this.network=Objects.requireNonNull(network);}
    public static String storageProblem(Node rail,Item tank,long owner) {
        return rail==null?"The Water freight stop rail is missing.":MachineWaterSources.storageProblem(
                rail.worldX(),rail.worldY(),rail.layer,tank,owner,REACH);
    }
    public static void validateStorage(Creature actor,Node rail,Item tank,long owner) {
        String reason=storageProblem(rail,tank,owner);if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor==null||actor.isOnSurface()!=(rail.layer==0)||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-rail.worldX(),actor.getPosY()-rail.worldY())>REACH)
            throw new IllegalArgumentException("Stand within eight metres of the stop on the same ground level.");
        if(actor.getPower()<2&&(actor.getWurmId()!=owner||!tank.mayAccessHold(actor)
                ||!Methods.isActionAllowed(actor,Actions.OPEN,tank)||!Methods.isActionAllowed(actor,Actions.TAKE,tank)))
            throw new IllegalArgumentException("Wurm ownership or storage permissions deny this tank link.");
    }
    public static List<Item> nearbyStorage(Creature actor,Node rail,long owner) {
        List<Item> result=new ArrayList<Item>();if(actor==null||rail==null)return result;
        scan:for(int x=Math.max(0,rail.tileX-2);x<=Math.min(Zones.worldTileSizeX-1,rail.tileX+2);x++)
            for(int y=Math.max(0,rail.tileY-2);y<=Math.min(Zones.worldTileSizeY-1,rail.tileY+2);y++) {
                VolaTile tile=Zones.getTileOrNull(x,y,rail.layer==0);if(tile==null)continue;
                for(Item tank:tile.getItems())if(LiquidTankPolicy.isIndustrialTank(tank))try {
                    validateStorage(actor,rail,tank,owner);result.add(tank);if(result.size()>=512)break scan;
                }catch(IllegalArgumentException unavailable){ }
            }
        result.sort(Comparator.comparing((Item item)->item.getName()).thenComparingLong(Item::getWurmId));return result;
    }
    @Override public String unavailableReason(Train train,Stop stop) {
        if(train.ownerId!=stop.ownerId)return "The stop and train must share an owner.";
        Node rail=network.nodes.get(stop.rail);Item marker=Items.getItemOptional(stop.markerId).orElse(null);
        if(rail==null||rail.markerId!=stop.markerId||marker==null||marker.getTemplateId()!=WurmRailWorld.trackTemplateId
                ||!MachineStorageLinks.placed(marker)||marker.getTileX()!=rail.tileX||marker.getTileY()!=rail.tileY
                ||marker.isOnSurface()!=(rail.layer==0))return "The Water freight stop marker is missing or misplaced.";
        boolean arrived=false;
        for(Pose pose:train.poses) {
            Item car=Items.getItemOptional(pose.carId).orElse(null);
            if(car==null||car.isOnSurface()!=(pose.layer==0)||Math.hypot(car.getPosX()-pose.x,car.getPosY()-pose.y)>.51)
                return "A train vehicle is missing or moved away from its stopped position.";
            if(pose.layer==rail.layer&&Math.hypot(pose.x-rail.worldX(),pose.y-rail.worldY())<.51)arrived=true;
        }
        if(!arrived)return "The train is no longer at this Water freight stop.";
        String reason=storageProblem(rail,Items.getItemOptional(stop.storageId).orElse(null),stop.ownerId);
        if(reason!=null)return reason;
        for(long id:train.carIds)if(RailwayLiquidCars.acceptsCar(Items.getItemOptional(id).orElse(null),stop.ownerId))return null;
        return "No eligible unlocked Tank Car belonging to this train's owner is attached.";
    }
    /** Both operations consume stored native water; track infrastructure never creates it. */
    @Override public LiquidServiceTransfer unload(Train train,Stop stop,long maxMillilitres) {
        if(maxMillilitres<0)throw new IllegalArgumentException("A non-negative liquid freight allowance is required.");
        String verb=stop.operation==Operation.LOAD?"loading":"unloading";
        String reason=unavailableReason(train,stop);
        if(reason!=null)return LiquidServiceTransfer.completed(0,"Water "+verb+" unavailable: "+reason);
        Item storage=Items.getItemOptional(stop.storageId).orElse(null);long transferred=0;
        for(long id:train.carIds) {
            Item car=Items.getItemOptional(id).orElse(null);
            if(!RailwayLiquidCars.acceptsCar(car,stop.ownerId))continue;
            Item source=stop.operation==Operation.LOAD?storage:car;
            Item destination=stop.operation==Operation.LOAD?car:storage;
            if(LiquidTankPolicy.sourceContentsProblem(source)!=null||LiquidTankPolicy.contentsProblem(destination)!=null)continue;
            Item liquid=LiquidTankPolicy.contents(source),existing=LiquidTankPolicy.contents(destination);
            if(liquid==null||liquid.getTemplateId()!=com.wurmonline.server.items.ItemList.water
                    ||existing!=null&&!LiquidTankPolicy.sameLiquid(existing,liquid))continue;
            if(transferred>=maxMillilitres)break;
            LiquidTankTransfers.Result result=LiquidTankTransfers.transfer(source,destination,maxMillilitres-transferred);
            transferred+=result.transferredMillilitres;
            if(result.requiresOperatorIntervention)return LiquidServiceTransfer.fault(transferred,result.status);
            if(stop.operation==Operation.LOAD&&LiquidTankPolicy.storedMillilitres(storage)==0
                    ||stop.operation==Operation.UNLOAD&&LiquidTankPolicy.remainingMillilitres(storage)==0)break;
        }
        for(long id:train.carIds) {
            Item car=Items.getItemOptional(id).orElse(null);
            if(!RailwayLiquidCars.acceptsCar(car,stop.ownerId))continue;
            Item source=stop.operation==Operation.LOAD?storage:car;
            Item destination=stop.operation==Operation.LOAD?car:storage;
            if(LiquidTankPolicy.sourceContentsProblem(source)!=null||LiquidTankPolicy.contentsProblem(destination)!=null)continue;
            Item water=LiquidTankPolicy.contents(source),existing=LiquidTankPolicy.contents(destination);
            if(water!=null&&water.getTemplateId()==com.wurmonline.server.items.ItemList.water
                    &&LiquidTankPolicy.remainingMillilitres(destination)>0
                    &&(existing==null||LiquidTankPolicy.sameLiquid(existing,water)))
                return LiquidServiceTransfer.ongoing(transferred,"Water "+verb+" through the linked Industrial Water Tank.");
        }
        return LiquidServiceTransfer.completed(transferred,"Water "+verb+" complete: source empty, destination full, or no compatible water can move.");
    }
}
