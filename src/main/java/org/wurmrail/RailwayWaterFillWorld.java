package org.wurmrail;

import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.behaviours.Terraforming;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.zones.WaterType;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.WaterFillStops.Stop;
import org.wurmrail.RailwayWaterFillService.Transfer;

/** Native natural water and one variable-weight liquid item per tank; no virtual contents. */
public final class RailwayWaterFillWorld implements RailwayWaterFillService.World {
    private static final Logger LOG=Logger.getLogger(RailwayWaterFillWorld.class.getName());
    private static final long NO_ID=-10L;
    public static final double SOURCE_REACH=8;
    private final RailNetwork network;
    public RailwayWaterFillWorld(RailNetwork network) { this.network=Objects.requireNonNull(network); }
    public static final class Source {
        public final int x,y,layer;
        public final String label;
        public Source(int x,int y,int layer,String label) {this.x=x;this.y=y;this.layer=layer;this.label=label;}
    }

    /** Exact native fill predicate, including shoreline corners and open flooded caves. */
    public static String sourceProblem(Node rail,int x,int y,int layer) {
        if(rail==null)return "The Water Fill Station rail is missing.";
        if(layer!=rail.layer)return "The natural water source must be on the station's ground level.";
        if(x<0||y<0||x>=Zones.worldTileSizeX-1||y>=Zones.worldTileSizeY-1)
            return "The selected natural water tile is outside the usable world.";
        if(Math.hypot(x*4.0+2-rail.worldX(),y*4.0+2-rail.worldY())>SOURCE_REACH)
            return "The natural water source must remain within eight metres of the station.";
        if(layer==0?Server.surfaceMesh==null:Server.caveMesh==null)return "The natural water map is unavailable.";
        int tile=(layer==0?Server.surfaceMesh:Server.caveMesh).getTile(x,y);
        byte type=Tiles.decodeType(tile);
        if(type==Tiles.Tile.TILE_LAVA.id||type==Tiles.Tile.TILE_TAR.id||type==Tiles.Tile.TILE_CAVE_WALL_LAVA.id)
            return "Lava and tar are not water sources.";
        try {
            if(!Native.isWater(tile,x,y,layer==0))return "The selected tile is no longer a natural water source.";
        } catch(Exception failure) {return "Native water information is unavailable.";}
        return null;
    }
    /** Bounded local configuration lookup only; active visits use their saved source identity. */
    public static List<Source> nearbySources(Node rail) {
        List<Source> result=new ArrayList<Source>();if(rail==null)return result;
        for(int x=Math.max(0,rail.tileX-2);x<=Math.min(Zones.worldTileSizeX-2,rail.tileX+2);x++)
            for(int y=Math.max(0,rail.tileY-2);y<=Math.min(Zones.worldTileSizeY-2,rail.tileY+2);y++)
                if(sourceProblem(rail,x,y,rail.layer)==null)result.add(new Source(x,y,rail.layer,
                        (WaterType.isBrackish(x,y,rail.layer==0)?"Salt water":"Fresh water")+" at "+x+", "+y));
        return result;
    }
    public static void validateSource(Creature actor,Node rail,int x,int y,int layer,long owner) {
        String reason=sourceProblem(rail,x,y,layer);if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor==null||actor.isOnSurface()!=(rail.layer==0)||actor.getFloorLevel()!=0||actor.getBridgeId()!=NO_ID
                ||Math.hypot(actor.getPosX()-rail.worldX(),actor.getPosY()-rail.worldY())>SOURCE_REACH)
            throw new IllegalArgumentException("Stand within eight metres of the Water Fill Station on the same ground level.");
        if(actor.getPower()<2&&(actor.getWurmId()!=owner||!Methods.isActionAllowed(actor,Actions.FILL,layer==0,x,y,0,0)))
            throw new IllegalArgumentException("Wurm permissions deny access to this natural water source.");
    }
    public static boolean acceptsCar(Item car,long owner) {
        return LiquidTankPolicy.isTankCar(car)&&car.isOwner(owner)&&car.getParentId()==NO_ID&&car.getOwnerId()==NO_ID
                &&!car.isBanked()&&!car.isMailed()&&!car.isTraded()&&!car.isLocked()&&!car.isSealedByPlayer()
                &&car.getFloorLevel()==0&&car.getBridgeId()==NO_ID;
    }
    private static List<Item> tanks(Train train,long owner) {
        List<Item> result=new ArrayList<Item>();
        for(long id:train.carIds) {Item car=Items.getItemOptional(id).orElse(null);if(acceptsCar(car,owner))result.add(car);}
        return result;
    }
    private static boolean compatibleWater(Item tank,boolean salted) {
        if(LiquidTankPolicy.contentsProblem(tank)!=null)return false;
        Item water=LiquidTankPolicy.contents(tank);
        return water==null||water.getTemplateId()==ItemList.water&&water.isSalted()==salted&&water.getRarity()==0
                &&water.getLeftAuxData()==0&&(water.getRealTemplateId()==NO_ID||water.getRealTemplateId()==ItemList.water);
    }
    @Override public String unavailableReason(Train train,Stop stop) {
        if(train.ownerId!=stop.ownerId)return "Only trains belonging to this Water Fill Station's owner may use it.";
        Node rail=network.nodes.get(stop.rail);Item marker=Items.getItemOptional(stop.markerId).orElse(null);
        if(rail==null||rail.markerId!=stop.markerId||marker==null||marker.getTemplateId()!=WurmRailWorld.trackTemplateId
                ||marker.getParentId()!=NO_ID||marker.getOwnerId()!=NO_ID||marker.getTileX()!=rail.tileX||marker.getTileY()!=rail.tileY
                ||marker.isOnSurface()!=(rail.layer==0)||marker.getFloorLevel()!=0||marker.getBridgeId()!=NO_ID
                ||marker.isBanked()||marker.isMailed()||marker.isTraded())return "The Water Fill Station rail marker is missing or misplaced.";
        boolean arrived=false;
        for(Pose pose:train.poses) {
            Item car=Items.getItemOptional(pose.carId).orElse(null);
            if(car==null||car.isOnSurface()!=(pose.layer==0)||Math.hypot(car.getPosX()-pose.x,car.getPosY()-pose.y)>.51)
                return "A train vehicle is missing or no longer at its stopped rail position.";
            if(pose.layer==rail.layer&&Math.hypot(pose.x-rail.worldX(),pose.y-rail.worldY())<.51)arrived=true;
        }
        if(!arrived)return "The train is no longer at this Water Fill Station.";
        String problem=sourceProblem(rail,stop.sourceX,stop.sourceY,stop.sourceLayer);if(problem!=null)return problem;
        boolean salted=WaterType.isBrackish(stop.sourceX,stop.sourceY,stop.sourceLayer==0);
        List<Item> cars=tanks(train,stop.ownerId);
        if(cars.isEmpty())return "No eligible unlocked Tank Car belonging to this train's owner is attached.";
        for(Item car:cars)if(compatibleWater(car,salted))return null;
        return "No Tank Car can accept this source's water. Empty incompatible, rare or different-salinity contents first.";
    }
    /** Compatibility-only: deprecated railway stops can never produce water. */
    @Override public Transfer fill(Train train,Stop stop,long maxMillilitres) {
        if(maxMillilitres<0)throw new IllegalArgumentException("A non-negative transfer allowance is required.");
        return Transfer.completed(0,"Water Fill Stations are deprecated. Use a Steam Water Pump, Industrial Water Tank and liquid freight stop.");
    }
    public static String capacitySummary(Train train) {
        if(train==null)return "";long stored=0,capacity=0;int count=0;boolean found=false;
        Set<String> types=new LinkedHashSet<String>();
        for(long id:train.carIds) {
            Item tank=Items.getItemOptional(id).orElse(null);if(!LiquidTankPolicy.isTankCar(tank))continue;found=true;
            if(!acceptsCar(tank,train.ownerId))continue;
            stored+=LiquidTankPolicy.storedMillilitres(tank);capacity+=LiquidTankPolicy.MAX_VOLUME_CC;count++;
            Item liquid=LiquidTankPolicy.contents(tank);if(liquid!=null)types.add(liquid.getName());
        }
        return !found?"":count==0?"Tank liquid: no eligible unlocked Tank Cars.":String.format(Locale.ROOT,
                "Tank liquid: %,.1f / %,.1f litres across %d Tank Cars (%s).",stored/1000.0,capacity/1000.0,count,
                types.isEmpty()?"empty":String.join(", ",types));
    }
    private static final class Native {
        private static final Method WATER;
        static {
            try {WATER=Terraforming.class.getDeclaredMethod("isWater",int.class,int.class,int.class,boolean.class);WATER.setAccessible(true);}
            catch(Exception unavailable) {throw new ExceptionInInitializerError(unavailable);}
        }
        static boolean isWater(int tile,int x,int y,boolean surface) throws Exception {return ((Boolean)WATER.invoke(null,tile,x,y,surface)).booleanValue();}
    }
}
