package org.wurmrail;

import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.behaviours.Terraforming;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.WaterType;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Native natural-water validation shared by resource-producing equipment. */
public final class NaturalWaterSources {
    public static final double SOURCE_REACH=8;
    private NaturalWaterSources(){ }
    public static final class Source {
        public final int x,y,layer;public final String label;
        public Source(int x,int y,int layer,String label){this.x=x;this.y=y;this.layer=layer;this.label=label==null?"":label;}
    }
    public static String problem(double originX,double originY,int originLayer,int x,int y,int layer) {
        if(layer!=originLayer)return "The natural water source must be on the pump's ground level.";
        if(x<0||y<0||x>=Zones.worldTileSizeX-1||y>=Zones.worldTileSizeY-1)return "The selected natural water tile is outside the usable world.";
        if(Math.hypot(x*4.0+2-originX,y*4.0+2-originY)>SOURCE_REACH)return "The natural water source must remain within eight metres of the pump.";
        if(layer==0?Server.surfaceMesh==null:Server.caveMesh==null)return "The native water map is unavailable.";
        int tile=(layer==0?Server.surfaceMesh:Server.caveMesh).getTile(x,y);byte type=Tiles.decodeType(tile);
        if(type==Tiles.Tile.TILE_LAVA.id||type==Tiles.Tile.TILE_TAR.id||type==Tiles.Tile.TILE_CAVE_WALL_LAVA.id)return "Lava and tar are not water sources.";
        try {if(!Native.isWater(tile,x,y,layer==0))return "The selected tile is no longer a natural water source.";}
        catch(Exception unavailable){return "Native water information is unavailable.";}
        return null;
    }
    public static String problem(Item machine,int x,int y,int layer){return machine==null?"The pump no longer exists.":problem(machine.getPosX(),machine.getPosY(),machine.isOnSurface()?0:-1,x,y,layer);}
    /** Local lookup runs on explicit configuration, never as a world scan. */
    public static List<Source> nearby(Item machine) {
        List<Source> result=new ArrayList<Source>();if(machine==null)return result;int layer=machine.isOnSurface()?0:-1;
        for(int x=Math.max(0,machine.getTileX()-2);x<=Math.min(Zones.worldTileSizeX-2,machine.getTileX()+2);x++)
            for(int y=Math.max(0,machine.getTileY()-2);y<=Math.min(Zones.worldTileSizeY-2,machine.getTileY()+2);y++)
                if(problem(machine,x,y,layer)==null)result.add(new Source(x,y,layer,(WaterType.isBrackish(x,y,layer==0)?"Salt water":"Fresh water")+" at "+x+", "+y));
        return result;
    }
    public static void validateSelection(Creature actor,Item machine,long owner,int x,int y,int layer) {
        String reason=problem(machine,x,y,layer);if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor==null||actor.isOnSurface()!=machine.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-machine.getPosX(),actor.getPosY()-machine.getPosY())>8)
            throw new IllegalArgumentException("Stand within eight metres of the pump on the same ground level.");
        if(actor.getPower()<2&&(actor.getWurmId()!=owner||!Methods.isActionAllowed(actor,Actions.FILL,layer==0,x,y,0,0)))
            throw new IllegalArgumentException("Wurm permissions deny access to this natural water source.");
    }
    public static boolean salted(int x,int y,int layer){return WaterType.isBrackish(x,y,layer==0);}
    private static final class Native {
        private static final Method WATER;
        static {try{WATER=Terraforming.class.getDeclaredMethod("isWater",int.class,int.class,int.class,boolean.class);WATER.setAccessible(true);}catch(Exception missing){throw new ExceptionInInitializerError(missing);}}
        static boolean isWater(int tile,int x,int y,boolean surface)throws Exception{return ((Boolean)WATER.invoke(null,tile,x,y,surface)).booleanValue();}
    }
}
