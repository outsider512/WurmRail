import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;

/** Real command-free, paid construction through two portal tiles and into daylight again. */
public final class CaveBuilderRuntimeSmoke {
    private static int assertions;
    public static void run() throws Exception {
        Map<Integer,Integer> surface=new LinkedHashMap<Integer,Integer>(),cave=new LinkedHashMap<Integer,Integer>();
        RailNetwork net=new RailNetwork();
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(999700032L,true,0);
        try {
            for(int x=36;x<=57;x++)for(int y=198;y<=208;y++) {
                int key=x+y*256;surface.put(key,Server.surfaceMesh.getTile(x,y));cave.put(key,Server.caveMesh.getTile(x,y));
                Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
                Server.caveMesh.setTile(x,y,Tiles.encode((short)50,x>=40&&x<=50?Tiles.Tile.TILE_CAVE.id:Tiles.Tile.TILE_CAVE_WALL.id,(byte)50));
            }
            for(int y:new int[]{200,206})for(int x:new int[]{40,50}) {
                Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_HOLE.id,(byte)0));
                Server.caveMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_CAVE_EXIT.id,(byte)0));
            }
            for(int i=0;i<30;i++) {
                Item section=ItemFactory.createItem(WurmRailWorld.trackSectionTemplateId,50f+i,"Cave builder fixture");
                check(actor.inventory.insertItem(section,true),"normal builder owns a crafted section");
            }
            WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());
            Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("cave-builder/network.properties");
            set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state);
            call(runtime,"initService",new Class<?>[0]);position(actor,38,200);
            RailwayBuilderUi.View blank=snapshot(runtime,actor,38,200,0);
            RailwayBuilderUi.Request build=RailwayBuilderUi.fromAnswers(blank,answers("place","direction","2","count","17"));
            apply(runtime,actor,38,200,0,build);
            check(net.nodes.size()==17&&RailwayTrackMaterials.count(actor)==13,"one ordinary paid builder request lays the complete seventeen-tile surface/tunnel/surface line");
            for(int x=38;x<=54;x++) {
                int layer=x>=40&&x<=50?-1:0;Node rail=at(net,x,200,layer);
                check(rail!=null&&Items.getItem(rail.markerId).isOnSurface()==(layer==0),"each rail marker is created on its real native floor");
                if(x>38) {
                    int priorLayer=x-1>=40&&x-1<=50?-1:0;
                    check(rail.links.contains(at(net,x-1,200,priorLayer).name),"the requested line automatically joins each physical entrance edge");
                }
            }
            RailNetwork loaded=RailNetwork.load(state);
            check(loaded.nodes.size()==17&&at(loaded,39,200,0).links.contains(at(loaded,40,200,-1).name)
                    &&at(loaded,50,200,-1).links.contains(at(loaded,51,200,0).name),"normal construction saves both explicit cave-entrance links and reloads them");
            position(actor,39,200);
            RailwayBuilderUi.View entrance=snapshot(runtime,actor,40,200,0);
            check(entrance.existing()&&entrance.location.startsWith("Cave entrance")&&entrance.neighbors.size()==2
                    &&entrance.neighbors.stream().anyMatch(n->n.label.contains("surface exit")),"surface ground menu resolves the one canonical entrance rail and clearly labels its outside connection");
            Node portal=at(net,40,200,-1);long originalMarker=portal.markerId;
            RailwayBuilderUi.Request unlink=RailwayBuilderUi.fromAnswers(entrance,answers("connections","exit_2","true"));
            apply(runtime,actor,40,200,0,unlink);
            check(!portal.links.contains(at(net,39,200,0).name),"ordinary connection checkboxes can unlink an entrance from the surface");
            apply(runtime,actor,40,200,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,40,200,0),answers("connections","exit_2","true","exit_6","true")));
            check(portal.links.contains(at(net,39,200,0).name),"ordinary connection checkboxes can restore a validated surface entrance");
            Items.destroyItem(originalMarker);
            apply(runtime,actor,40,200,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,40,200,0),answers("repair")));
            check(portal.markerId!=originalMarker&&!Items.getItem(portal.markerId).isOnSurface(),"surface player can repair the native cave-floor entrance marker");
            boolean duplicate=false;
            try { call(runtime,"buildLine",new Class<?>[]{Creature.class,String.class,int.class,int.class,int.class,int.class,int.class,boolean.class},actor,null,40,200,0,2,1,true); }
            catch(IllegalArgumentException expected) { duplicate=true; }
            check(duplicate&&net.nodes.size()==17&&RailwayTrackMaterials.count(actor)==13,"placing on the visible entrance cannot create a duplicate rail or consume a section");

            // Simulate an unwritable save after every native marker and payment succeeds.
            position(actor,38,206);int before=RailwayTrackMaterials.count(actor);Set<Long> markers=markerIds(net);
            Path blocked=state.resolveSibling("save-is-directory");Files.createDirectories(blocked);set(runtime,"stateFile",blocked);
            boolean rolledBack=false;
            try { apply(runtime,actor,38,206,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,38,206,0),answers("place","direction","2","count","8"))); }
            catch(Exception expected) { rolledBack=true; }
            set(runtime,"stateFile",state);
            check(rolledBack&&net.nodes.size()==17&&RailwayTrackMaterials.count(actor)==before&&markers.equals(markerIds(net)),"a failed save rolls back portal links, all native markers and the complete paid material cost");
            for(Item item:Items.getAllItems())if(item.getTemplateId()==WurmRailWorld.trackTemplateId)
                check(item.getTileY()!=206||item.getTileX()<38||item.getTileX()>45,"failed tunnel construction leaves no native rail scenery behind");

            position(actor,39,200);
            apply(runtime,actor,40,200,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,40,200,0),answers("remove","confirm_remove","true")));
            check(at(net,40,200,-1)==null&&RailwayTrackMaterials.count(actor)==before+1,"surface player can remove the entrance and recover exactly one crafted section");
            position(actor,39,206);
            apply(runtime,actor,39,206,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,39,206,0),answers("place","direction","2","count","1")));
            apply(runtime,actor,40,206,0,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,40,206,0),answers("place","direction","2","count","1")));
            check(at(net,39,206,0).links.contains(at(net,40,206,-1).name),"individually placed entrance rail automatically joins its existing outside rail without a link command");
            actor.getStatus().setLayer(-1);position(actor,40,206);
            apply(runtime,actor,41,206,-1,RailwayBuilderUi.fromAnswers(snapshot(runtime,actor,41,206,-1),answers("place","direction","2","count","1")));
            check(at(net,40,206,-1).links.contains(at(net,41,206,-1).name)&&RailwayTrackMaterials.count(actor)==before-2,"player inside the cave extends an individually placed entrance with normal paid construction");
            System.out.println("PASS native paid cave builder: automatic surface/tunnel/surface lines, entrance menus/connections/repair, canonical duplicates, save rollback and section recovery ("+assertions+" assertions)");
        } finally {
            actor.getStatus().setLayer(0);
            for(Node rail:net.nodes.values())if(Items.getItemOptional(rail.markerId).isPresent())Items.destroyItem(rail.markerId);
            for(Item item:actor.inventory.getItemsAsArray())if(item.getTemplateId()==WurmRailWorld.trackSectionTemplateId)Items.destroyItem(item.getWurmId());
            for(Map.Entry<Integer,Integer> tile:surface.entrySet())Server.surfaceMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
            for(Map.Entry<Integer,Integer> tile:cave.entrySet())Server.caveMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
        }
    }
    private static Set<Long> markerIds(RailNetwork net) { Set<Long> result=new HashSet<Long>();for(Node rail:net.nodes.values())result.add(rail.markerId);return result; }
    private static Node at(RailNetwork net,int x,int y,int layer) { for(Node n:net.nodes.values())if(n.tileX==x&&n.tileY==y&&n.layer==layer)return n;return null; }
    private static void position(Creature actor,int x,int y) { actor.getStatus().getPosition().setPosX(x*4+2);actor.getStatus().getPosition().setPosY(y*4+2); }
    private static Properties answers(String button,String...pairs) { Properties p=new Properties();p.setProperty(button,"true");for(int i=0;i<pairs.length;i+=2)p.setProperty(pairs[i],pairs[i+1]);return p; }
    private static RailwayBuilderUi.View snapshot(WurmRailRuntime runtime,Creature p,int x,int y,int layer) throws Exception { return (RailwayBuilderUi.View)call(runtime,"builderSnapshot",new Class<?>[]{Creature.class,int.class,int.class,int.class},p,x,y,layer); }
    private static void apply(WurmRailRuntime runtime,Creature p,int x,int y,int layer,RailwayBuilderUi.Request request) throws Exception { call(runtime,"applyBuilder",new Class<?>[]{Creature.class,int.class,int.class,int.class,RailwayBuilderUi.Request.class},p,x,y,layer,request); }
    private static void set(Object object,String name,Object value) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value); }
    private static Object call(Object object,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(object,args); }
        catch(InvocationTargetException wrapped) { if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause();throw wrapped; }
    }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
