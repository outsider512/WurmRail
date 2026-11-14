import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Server;
import com.wurmonline.server.zones.TilePoller;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.wurmrail.RailVegetationHooks;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;

/** Exercise real native sprout destinations, spontaneous bushes and loaded overgrowth. */
public final class RailVegetationRuntimeSmoke {
    private static int assertions;
    private RailVegetationRuntimeSmoke() { }

    public static void run() throws Exception {
        Field protectedTiles=RailVegetationHooks.class.getDeclaredField("surfaceRails");
        protectedTiles.setAccessible(true);
        Object oldProtected=protectedTiles.get(null);
        boolean oldSurface=TilePoller.pollingSurface;
        MeshIO oldMesh=TilePoller.currentMesh;
        Field flagsField=Server.class.getDeclaredField("flagsMesh");flagsField.setAccessible(true);
        Object oldFlags=flagsField.get(null);MeshIO oldResources=Server.resourceMesh;
        Path world=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath());
        MeshIO flags=MeshIO.createMap(world.resolve("native-rail-vegetation-flags.map").toString(),8,new int[256*256]);
        MeshIO resources=MeshIO.createMap(world.resolve("native-rail-vegetation-resources.map").toString(),8,new int[256*256]);
        flagsField.set(null,flags);Server.resourceMesh=resources;
        Map<Long,Integer> original=new LinkedHashMap<Long,Integer>();
        int x=166,y=181;
        for(int tx=x-12;tx<=x+12;tx++)for(int ty=y-12;ty<=y+12;ty++) {
            original.put(RailVegetationHooks.tileKey(tx,ty),Server.surfaceMesh.getTile(tx,ty));
            set(tx,ty,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
        }
        try {
            RailNetwork network=new RailNetwork();
            network.addNode("protected",x,y,0);
            network.addNode("underground",x+2,y,-1);
            Path saved=world.resolve("native-rail-vegetation/network.properties");
            network.save(saved);network=RailNetwork.load(saved);
            WurmRailRuntime runtime=new WurmRailRuntime();
            Field net=WurmRailRuntime.class.getDeclaredField("network");net.setAccessible(true);net.set(runtime,network);
            refresh(runtime);
            check(RailVegetationHooks.hasSurfaceRail(x,y),"loaded surface rails are protected");
            check(!RailVegetationHooks.hasSurfaceRail(x+1,y),"neighboring land is not reserved");
            check(!RailVegetationHooks.hasSurfaceRail(x+2,y),"underground rails do not reserve surface forests");
            TilePoller.pollingSurface=true;TilePoller.currentMesh=Server.surfaceMesh;

            // The source is eight tiles away, outside the native tree-density
            // exclusion radius. Seed only the destination selection, then run
            // the unmodified native tree and bush creation code on controls.
            int sx=x-8,sy=y;
            for(byte parent:new byte[]{Tiles.Tile.TILE_TREE_BIRCH.id,Tiles.Tile.TILE_BUSH_ROSE.id,
                    Tiles.Tile.TILE_MYCELIUM_TREE_BIRCH.id,Tiles.Tile.TILE_MYCELIUM_BUSH_ROSE.id,
                    Tiles.Tile.TILE_ENCHANTED_TREE_BIRCH.id,Tiles.Tile.TILE_ENCHANTED_BUSH_ROSE.id}) {
                set(sx,sy,parent,(short)50,(byte)0x90);
                sprout(sx,sy,parent,x,y);
                check(type(x,y)==Tiles.Tile.TILE_GRASS.id,"natural tree/bush sprouts cannot land on rail");
                sprout(sx,sy,parent,x+1,y);
                check(Tiles.isTree(type(x+1,y))||Tiles.isBush(type(x+1,y)),"native sprouts still grow immediately beside the rail");
                set(x+1,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
                sprout(sx,sy,parent,x+2,y);
                check(Tiles.isTree(type(x+2,y))||Tiles.isBush(type(x+2,y)),"surface sprouts still grow above cave-only rail");
                set(x+2,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
            }
            set(sx,sy,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
            call(TilePoller.class,"checkForLingonberryStart",new Class<?>[]{int.class,int.class,int.class},tile(x,y),x,y);
            check(type(x,y)==Tiles.Tile.TILE_GRASS.id,"spontaneous lingonberry start also respects rail");
            call(TilePoller.class,"checkForLingonberryStart",new Class<?>[]{int.class,int.class,int.class},tile(x+1,y),x+1,y);
            check(type(x+1,y)==Tiles.Tile.TILE_BUSH_LINGONBERRY.id,"spontaneous bushes still grow beside rail");
            set(x+1,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);

            // Loading the next version repairs only registered rail overgrowth.
            for(byte[] pair:new byte[][]{
                    {Tiles.Tile.TILE_TREE_BIRCH.id,Tiles.Tile.TILE_GRASS.id},
                    {Tiles.Tile.TILE_BUSH_LINGONBERRY.id,Tiles.Tile.TILE_GRASS.id},
                    {Tiles.Tile.TILE_MYCELIUM_TREE_BIRCH.id,Tiles.Tile.TILE_MYCELIUM.id},
                    {Tiles.Tile.TILE_ENCHANTED_BUSH_ROSE.id,Tiles.Tile.TILE_ENCHANTED_GRASS.id}}) {
                set(x,y,pair[0],(short)57,(byte)0x91);
                set(x+1,y,Tiles.Tile.TILE_TREE_BIRCH.id,(short)58,(byte)0x91);
                set(x+2,y,Tiles.Tile.TILE_TREE_BIRCH.id,(short)59,(byte)0x91);
                int beforeNeighbor=tile(x+1,y),beforeCave=tile(x+2,y);
                Server.resourceMesh.setTile(x,y,0x43210064);
                check(WurmRailWorld.validateTile(x,y,0)!=null,"legacy foliage blocks old train terrain validation");
                refresh(runtime);
                check(type(x,y)==pair[1]&&Tiles.decodeHeight(tile(x,y))==57,"rail cleanup preserves height and correct substrate");
                check(Server.resourceMesh.getTile(x,y)==0x43210000,"cleanup clears the obsolete tree resource but preserves cave resources on the same tile");
                check(tile(x+1,y)==beforeNeighbor&&tile(x+2,y)==beforeCave,"cleanup preserves nearby and cave-overhead trees exactly");
                check(WurmRailWorld.validateTile(x,y,0)==null,"loaded rail terrain is usable again");
                check(!WurmRailWorld.clearRailVegetation(x+1,y,0)&&!WurmRailWorld.clearRailVegetation(x+2,y,-1),"cleanup refuses nonrail and cave coordinates");
                set(x+1,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
                set(x+2,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
            }
            // Index follows track removal; its terrain becomes ordinary land again.
            network.removeNode("protected");refresh(runtime);
            check(!RailVegetationHooks.hasSurfaceRail(x,y),"removing rail releases vegetation protection");
            set(x,y,Tiles.Tile.TILE_GRASS.id,(short)50,(byte)0);
            sprout(sx,sy,Tiles.Tile.TILE_TREE_BIRCH.id,x,y);
            check(Tiles.isTree(type(x,y)),"a removed rail does not permanently sterilize the tile");
            System.out.println("PASS native railway vegetation protection: blocked tree/bush sprout destinations and spontaneous bushes, nearby growth, cave separation, precise legacy cleanup and track removal ("+assertions+" assertions)");
        } finally {
            protectedTiles.set(null,oldProtected);TilePoller.pollingSurface=oldSurface;TilePoller.currentMesh=oldMesh;
            flagsField.set(null,oldFlags);Server.resourceMesh=oldResources;flags.close();resources.close();
            for(Map.Entry<Long,Integer> entry:original.entrySet())
                Server.surfaceMesh.setTile((int)(entry.getKey()>>32),(int)(long)entry.getKey(),entry.getValue());
        }
    }

    private static void sprout(int sx,int sy,byte parent,int targetX,int targetY) throws Exception {
        long seed=seed(targetX-sx+10,targetY-sy+10);
        Server.rand.setSeed(seed);
        call(TilePoller.class,"checkForTreeSprout",new Class<?>[]{int.class,int.class,int.class,byte.class},sx,sy,(int)parent,(byte)0x90);
    }
    private static long seed(int first,int second) {
        Random r=new Random();
        for(long candidate=0;candidate<100000;candidate++) {
            r.setSeed(candidate);if(r.nextInt(21)==first&&r.nextInt(21)==second)return candidate;
        }
        throw new AssertionError("native sprout destination seed unavailable");
    }
    private static void refresh(WurmRailRuntime runtime) throws Exception {
        call(WurmRailRuntime.class,runtime,"refreshTrackVisuals",new Class<?>[0]);
    }
    private static int tile(int x,int y) { return Server.surfaceMesh.getTile(x,y); }
    private static byte type(int x,int y) { return Tiles.decodeType(tile(x,y)); }
    private static void set(int x,int y,byte type,short height,byte data) { Server.surfaceMesh.setTile(x,y,Tiles.encode(height,type,data)); }
    private static Object call(Class<?> owner,String method,Class<?>[] types,Object... args) throws Exception { return call(owner,null,method,types,args); }
    private static Object call(Class<?> owner,Object receiver,String method,Class<?>[] types,Object... args) throws Exception {
        Method m=owner.getDeclaredMethod(method,types);m.setAccessible(true);
        try { return m.invoke(receiver,args); }
        catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;
            if(cause instanceof Error)throw (Error)cause;throw failure;
        }
    }
    private static void check(boolean value,String message) { assertions++;if(!value)throw new AssertionError(message); }
}
