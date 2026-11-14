import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.HitNamesData;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cave.CaveRender;
import com.wurmonline.client.renderer.cave.CaveWallPicker;
import com.wurmonline.mesh.Tiles;
import java.lang.reflect.*;
import java.nio.IntBuffer;
import java.util.*;
import org.wurmrail.shared.CoalVeinTiles;

/** Real enum registry, cave tessellation and native wall picking, without a display. */
public final class CoalVeinClientSmoke {
    private static int checks;
    private CoalVeinClientSmoke(){ }
    public static void run()throws Exception {
        Tiles.Tile coal=Tiles.getTile(CoalVeinTiles.TILE_ID);
        check(coal!=null&&coal.getIntId()==208&&coal.id==(byte)208,"unsigned/signed tile208 resolves to a real native tile");
        check(coal==Tiles.getTile((byte)208)&&coal==Tiles.Tile.valueOf(CoalVeinTiles.ENUM_NAME),"wire ID, enum lookup and registry share one identity");
        check(CoalVeinTiles.DISPLAY_NAME.equals(coal.getName())&&CoalVeinTiles.TEXTURE.equals(coal.getTextureResource()),"coal has its own name with a valid existing dark wall material");
        check(coal.isCave()&&coal.isSolidCave()&&coal.isOreCave()&&!coal.isReinforcedCave(),"native instance flags match mineral walls rather than reinforced walls");
        check(Tiles.isSolidCave(coal.id)&&Tiles.isOreCave(coal.id)&&Tiles.isReinforcedCave(coal.id)&&!Tiles.isReinforcedCaveWall(coal.id),"all native byte-based mineral classifiers include coal correctly");
        for(int side=2;side<=5;side++)check("Coal Vein".equals(coal.getTileName((byte)side)),"every wall approach reports Coal Vein");
        Set<Integer> ids=new HashSet<Integer>();Tiles.Tile[] values=Tiles.Tile.values();
        for(int i=0;i<values.length;i++){Tiles.Tile tile=values[i];check(tile.ordinal()==i&&ids.add(tile.getIntId())&&Tiles.getTile(tile.id)==tile,"all vanilla ordinals, unique IDs and registry instances remain coherent");}
        check(values[values.length-1]==coal,"coal is appended after every vanilla enum constant");
        check(Tiles.getTile(222)==Tiles.Tile.TILE_CAVE_WALL_ORE_IRON&&"Iron vein".equals(Tiles.getTile(222).getName()),"vanilla iron is neither renamed nor replaced");
        check(Tiles.getTile(205)==Tiles.Tile.TILE_CAVE_WALL_SLATE&&"Slate wall".equals(Tiles.getTile(205).getName()),"the reused dark texture does not replace the vanilla slate resource");
        World previous=CellRenderable.world;
        try{nativeCaveWall();}finally{CellRenderable.world=previous;}
        System.out.println("PASS native Coal Vein client: real appended tile208 enum and classifiers, unchanged vanilla IDs, rendered cave-wall triangles and exact wall-pick name ("+checks+" assertions)");
    }
    private static void nativeCaveWall()throws Exception {
        World world=RailVehicleTerrainClientSmoke.setupWorld(0,0,12,0,0,1);
        set(world,"worldRenderer",allocate(Class.forName("com.wurmonline.client.renderer.WorldRender")));
        CaveDataBuffer cave=world.getCaveBuffer();byte[] types=(byte[])get(cave,"types");short[] ceilings=(short[])get(cave,"ceilings");
        Arrays.fill(ceilings,(short)40);int x=11,y=11;
        types[cave.getOffset(x,y-1)]=CoalVeinTiles.TILE_TYPE;
        types[cave.getOffset(x+1,y)]=Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id;
        check(cave.getTileType(x,y-1)==Tiles.getTile(208),"the real client cave buffer decodes coal's network tile byte");
        Class<?> dataClass=Class.forName("com.wurmonline.client.renderer.cave.CaveTileData");Object tile=allocate(dataClass);
        set(tile,"world",world);set(tile,"caveBuffer",cave);set(tile,"nearTerrainBuffer",world.getNearTerrainBuffer());
        set(tile,"xTilePos",x);set(tile,"yTilePos",y);set(tile,"corners",new HashMap<Long,Object>());set(tile,"heights",new float[8]);
        List<Object> drawn=new ArrayList<Object>();call(tile,"tesselate",new Class<?>[]{List.class,boolean.class},drawn,false);
        int coalTriangles=0,ironTriangles=0;
        for(Object triangle:drawn){int side=(Integer)get(triangle,"wallSide");String texture=(String)get(triangle,"texture");
            if(side==3){coalTriangles++;check(CoalVeinTiles.TEXTURE.equals(texture),"native north-wall triangles use coal's dark material");}
            if(side==4){ironTriangles++;check(Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.getTextureResource().equals(texture),"neighboring vanilla iron retains its original material");}}
        check(coalTriangles>=2&&ironTriangles>=2,"real cave tessellation emits both the new coal wall and untouched vanilla ore");
        List<Object> picked=new ArrayList<Object>();call(tile,"tesselate",new Class<?>[]{List.class,boolean.class},picked,true);set(tile,"pickTriangles",picked);
        CaveRender render=(CaveRender)allocate(CaveRender.class);set(render,"world",world);set(render,"caveBuffer",cave);
        Object cells=Array.newInstance(dataClass,1024);Array.set(cells,((y&31)<<5)|(x&31),tile);set(render,"caveTiles",cells);
        Constructor<CaveWallPicker> constructor=CaveWallPicker.class.getDeclaredConstructor(World.class);constructor.setAccessible(true);set(render,"caveWallPicker",constructor.newInstance(world));
        CaveWallPicker coalPick=(CaveWallPicker)render.getPickedWall(names(x,y,3));
        check("Coal Vein".equals(coalPick.getHoverName())&&coalPick.getXNeighbor()==x&&coalPick.getYNeighbor()==y-1,"native cave picking identifies the actual new wall as Coal Vein");
        CaveWallPicker ironPick=(CaveWallPicker)render.getPickedWall(names(x,y,4));
        check("Iron vein".equals(ironPick.getHoverName()),"native selection still identifies neighboring vanilla iron independently");
    }
    private static HitNamesData names(int x,int y,int side)throws Exception{HitNamesData names=(HitNamesData)allocate(HitNamesData.class);set(names,"hitBuffer",IntBuffer.wrap(new int[]{x,y,side}));set(names,"remaining",3);return names;}
    private static Object allocate(Class<?> type)throws Exception{Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
