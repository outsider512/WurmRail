import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.zones.Zones;
import java.util.HashMap;
import java.util.Map;
import org.wurmrail.WurmRailWorld;

/** Native centre-floor motion on a crest, independent of client terrain rotation. */
public final class RailVehicleTerrainServerSmoke {
    private static int assertions;
    private static final int FIRST=210,LAST=230,CENTRE=220;
    private RailVehicleTerrainServerSmoke() { }

    public static void run() throws Exception {
        Map<Integer,Integer> surface=new HashMap<Integer,Integer>(),cave=new HashMap<Integer,Integer>();
        try {
            for(int x=FIRST;x<=LAST;x++)for(int y=FIRST;y<=LAST;y++) {
                int key=x+y*256;
                surface.put(key,Server.surfaceMesh.getTile(x,y));cave.put(key,Server.caveMesh.getTile(x,y));
                Server.surfaceMesh.setTile(x,y,Tiles.encode(height(x,y,0),Tiles.Tile.TILE_DIRT.id,(byte)0));
                Server.caveMesh.setTile(x,y,Tiles.encode(height(x,y,-1),Tiles.Tile.TILE_CAVE.id,(byte)50));
            }
            for(int layer:new int[]{0,-1})for(boolean engine:new boolean[]{true,false})runCar(layer,engine);
        } finally {
            for(Map.Entry<Integer,Integer> tile:surface.entrySet())Server.surfaceMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
            for(Map.Entry<Integer,Integer> tile:cave.entrySet())Server.caveMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
        }
        System.out.println("PASS native engine/cart centre-floor height over surface/cave crests at all eight headings in forward/reverse, stationary yaw, cargo and zone ownership ("+assertions+" assertions)");
    }

    private static short height(int x,int y,int layer) {
        return (short)((layer==0?80:40)+2*(x-FIRST)+3*(y-FIRST)+Math.max(0,8-2*Math.abs(x-CENTRE)));
    }

    /** Separable piecewise-linear terrain has this exact height on either native tile triangle. */
    private static double expected(float x,float y,int layer) {
        double tileX=x/4.0,tileY=y/4.0;
        return ((layer==0?80:40)+2*(tileX-FIRST)+3*(tileY-FIRST)+Math.max(0,8-2*Math.abs(tileX-CENTRE)))/10.0;
    }

    private static void runCar(int layer,boolean engine) throws Exception {
        float centre=CENTRE*4+2;
        Item car=engine?WurmRailWorld.spawnLocomotive(null,centre,centre,layer):WurmRailWorld.spawnCart(null,centre,centre,layer);
        Item cargo=ItemFactory.createItem(ItemList.plank,73f,"Native terrain fixture");
        try {
            check(car.insertItem(cargo),"native rail vehicle accepts cargo on the tested layer");
            for(int heading=0;heading<360;heading+=45) {
                WurmRailWorld.moveCar(car.getWurmId(),centre,centre,heading,layer);
                checkPose(car,cargo,centre,centre,heading,layer);
                double radians=Math.toRadians(heading);
                // A quarter-metre step also checks slope changes within one tile.
                for(int step=1;step<=16;step++) {
                    float x=(float)(centre+Math.sin(radians)*step*.25);
                    float y=(float)(centre-Math.cos(radians)*step*.25);
                    WurmRailWorld.moveCar(car.getWurmId(),x,y,heading,layer);
                    checkPose(car,cargo,x,y,heading,layer);
                }
                // Reverse traction keeps the model facing unchanged.
                for(int step=15;step>=0;step--) {
                    float x=(float)(centre+Math.sin(radians)*step*.25);
                    float y=(float)(centre-Math.cos(radians)*step*.25);
                    WurmRailWorld.moveCar(car.getWurmId(),x,y,heading,layer);
                    checkPose(car,cargo,x,y,heading,layer);
                }
            }
        } finally { Items.destroyItem(car.getWurmId()); }
    }

    private static void checkPose(Item car,Item cargo,float x,float y,int heading,int layer) throws Exception {
        check(car.getPosX()==x&&car.getPosY()==y,"native movement retains the requested terrain XY");
        check(Math.abs(car.getPosZ()-expected(x,y,layer))<.0001,"native car remains on the analytical rail-floor height, including both sides of the crest");
        check(Math.abs(car.getRotation()-heading)<.0001,"native movement and stationary yaw preserve exact heading");
        check(car.isOnSurface()==(layer==0)&&car.getZoneId()==Zones.getZone(car.getTileX(),car.getTileY(),layer==0).getId(),"native layer and zone remain authoritative on uneven ground");
        check(cargo.getParentId()==car.getWurmId()&&cargo.isOnSurface()==(layer==0)&&cargo.getQualityLevel()==73f,"terrain pose updates retain native cargo and quality");
    }

    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
