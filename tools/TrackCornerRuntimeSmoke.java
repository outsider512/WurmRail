import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;

/** Native construction entry points, including preflight before multi-end edits. */
public final class TrackCornerRuntimeSmoke {
    private static final int[][] DIRECTIONS={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
    private static int assertions;
    private TrackCornerRuntimeSmoke() { }

    public static void run() throws Exception {
        newConnections();
        affectedNeighborPreflight();
        finalShapeTransaction();
        nativeCycle();
        System.out.println("PASS native track corner construction: all compass pairs, both-end preflight, automatic connection choices, unchanged legacy links, atomic final shapes and gentle-only direct junction cycling ("+assertions+" assertions)");
    }

    private static void newConnections() throws Exception {
        int allowed=0,rejected=0;
        for(int first=0;first<8;first++)for(int second=first+1;second<8;second++) {
            RailNetwork net=new RailNetwork();Node hub=net.addNode("hub",168,160,0);
            Node existing=net.addNode("existing",168+DIRECTIONS[first][0],160+DIRECTIONS[first][1],0);
            Node addition=net.addNode("addition",168+DIRECTIONS[second][0],160+DIRECTIONS[second][1],0);
            net.connect(hub.name,existing.name);
            WurmRailRuntime runtime=runtime(net,"pair-"+first+"-"+second);
            boolean buildable=RailNetwork.isBuildableAlignment((1<<first)|(1<<second));
            check((Boolean)call(runtime,"constructionConnectionAllowed",new Class<?>[]{Node.class,Node.class},hub,addition)==buildable,
                    "automatic connection candidate agrees with the allowed physical bend at "+first+"/"+second);
            if(buildable) {
                call(runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},hub,addition);allowed++;
                check(hub.links.containsAll(Arrays.asList(existing.name,addition.name))&&addition.links.contains(hub.name),
                        "explicit construction accepts straight, 45-degree and 90-degree bends");
            } else {
                Map<String,List<String>> before=links(net);
                fail(()->call(runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},hub,addition),
                        "explicit construction rejects a new 135-degree corner");rejected++;
                check(links(net).equals(before),"rejected corner leaves both endpoints untouched");
                // Existing world data remains supported. Repeating its exact
                // link does not attempt to reconstruct or destroy that track.
                net.connect(hub.name,addition.name);
                before=links(net);
                check((Boolean)call(runtime,"constructionConnectionAllowed",new Class<?>[]{Node.class,Node.class},hub,addition),
                        "an unchanged legacy corner remains compatible with construction inspection");
                call(runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},hub,addition);
                check(links(net).equals(before),"an unchanged legacy connection remains a no-op");
                Path saved=state("legacy-"+first+"-"+second);net.save(saved);
                check(RailNetwork.load(saved).trackMask(hub.name)==((1<<first)|(1<<second)),
                        "legacy sharp track remains readable after persistence");
            }
        }
        check(allowed==20&&rejected==8,"all compass orientations retain exactly 20 supported pairs and omit eight tight corners");
        RailNetwork net=new RailNetwork();Node a=net.addNode("a",168,160,0),b=net.addNode("b",169,160,0);
        Node old=net.addNode("old",168,159,0);net.connect(b.name,old.name);
        WurmRailRuntime runtime=runtime(net,"second-end");Map<String,List<String>> before=links(net);
        check(!(Boolean)call(runtime,"constructionConnectionAllowed",new Class<?>[]{Node.class,Node.class},a,b),
                "automatic construction checks the existing neighbor's new shape too");
        fail(()->call(runtime,"connectNewNodes",new Class<?>[]{Node.class,Node.class},a,b),
                "explicit construction rejects a corner formed only at the second endpoint");
        check(links(net).equals(before),"second-end rejection cannot leave a half-created connection");
    }

    private static void affectedNeighborPreflight() throws Exception {
        Fixture f=new Fixture("neighbor-preflight",168,160);
        try {
            Node hub=f.add("hub",168,160),north=f.add("north",168,159),west=f.add("west",167,160);
            Node east=f.add("east",169,160),eastNorth=f.add("eastNorth",169,159),eastNortheast=f.add("eastNortheast",170,159);
            for(Node neighbor:Arrays.asList(north,west,east))f.net.connect(hub.name,neighbor.name);
            f.net.connect(east.name,eastNorth.name);f.net.connect(east.name,eastNortheast.name);
            Map<String,List<String>> before=links(f.net);
            int alignment=hub.getAlignmentMask(),otherAlignment=east.getAlignmentMask();
            fail(()->call(f.runtime,"applyConnections",new Class<?>[]{Creature.class,Node.class,int.class},f.actor,hub,65),
                    "connection edit rejects removal that would leave a 135-degree corner on another track");
            check(links(f.net).equals(before)&&hub.getAlignmentMask()==alignment&&east.getAlignmentMask()==otherAlignment,
                    "all affected final shapes are checked before any graph or junction state mutates");
            for(Node rail:f.net.nodes.values())check(Items.getItemOptional(rail.markerId).isPresent(),
                    "failed connection preflight preserves every native track marker");
        } finally { f.close(); }
    }

    private static void finalShapeTransaction() throws Exception {
        Fixture f=new Fixture("final-shape",168,160);
        try {
            Node hub=f.add("hub",168,160),north=f.add("north",168,159),northeast=f.add("northeast",169,159);
            Node south=f.add("south",168,161),west=f.add("west",167,160);
            f.net.connect(hub.name,north.name);f.net.connect(hub.name,northeast.name);f.net.connect(hub.name,south.name);
            // Removing South temporarily leaves the old N/NE pair before West
            // is added. Only the transaction's committed final shape matters.
            call(f.runtime,"applyConnections",new Class<?>[]{Creature.class,Node.class,int.class},f.actor,hub,67);
            check(hub.links.containsAll(Arrays.asList(north.name,northeast.name,west.name))&&!hub.links.contains(south.name),
                    "valid final junction edit is not rejected for a temporary intermediate two-ended shape");
            check(!south.links.contains(hub.name)&&west.links.contains(hub.name),"atomic edit updates both ends of removed and added connections");
            check(RailNetwork.load(f.state).trackMask(hub.name)==67,"valid final junction shape is saved intact");
        } finally { f.close(); }
    }

    private static void nativeCycle() throws Exception {
        Fixture f=new Fixture("cycle",168,160);
        try {
            Node hub=f.add("hub",168,160);
            for(int direction=0;direction<8;direction++) {
                Node side=f.add("side"+direction,168+DIRECTIONS[direction][0],160+DIRECTIONS[direction][1]);
                f.net.connect(hub.name,side.name);
            }
            f.net.setJunctionAlignment(hub.name,3);
            List<Integer> choices=new ArrayList<Integer>();
            for(int mask:f.net.junctionAlignments(hub.name))if(RailNetwork.isBuildableAlignment(mask))choices.add(mask);
            check(choices.size()==20,"native eight-way junction has exactly 20 construction switch choices");
            for(int cycle=0;cycle<=choices.size();cycle++) {
                call(f.runtime,"cycleJunction",new Class<?>[]{Creature.class,long.class},f.actor,hub.markerId);
                check(hub.getAlignmentMask()==choices.get(cycle%choices.size())&&RailNetwork.isBuildableAlignment(hub.getAlignmentMask()),
                        "direct interaction skips all tight pairs, cycles every supported choice and wraps");
            }
            check(RailNetwork.load(f.state).getJunctionAlignment(hub.name)==hub.getAlignmentMask(),
                    "direct switch interaction persists the actual current alignment");
            int before=hub.getAlignmentMask();
            fail(()->call(f.runtime,"setJunction",new Class<?>[]{Creature.class,Node.class,int.class},f.actor,hub,3),
                    "explicit new manual junction alignment cannot reintroduce a 135-degree turn");
            check(hub.getAlignmentMask()==before,"rejected alignment keeps the previous switch state");
            f.net.setJunctionAlignment(hub.name,3);
            call(f.runtime,"setJunction",new Class<?>[]{Creature.class,Node.class,int.class},f.actor,hub,3);
            check(hub.getAlignmentMask()==3,"unchanged legacy junction data remains usable without migration damage");
        } finally { f.close(); }
    }

    private static final class Fixture {
        final RailNetwork net=new RailNetwork();
        final TrackMaterialsSmoke.InventoryActor actor;
        final WurmRailRuntime runtime;
        final Path state;
        Fixture(String name,int x,int y) throws Exception {
            actor=TrackMaterialsSmoke.actor(998800032L,true,2);
            actor.getStatus().getPosition().setPosX(x*4+2);actor.getStatus().getPosition().setPosY(y*4+2);
            runtime=runtime(net,name);state=state(name);
        }
        Node add(String name,int x,int y) throws Exception {
            Node rail=net.addNode(name,x,y,0);
            Item marker=WurmRailWorld.spawnTrackMarker(actor,rail.worldX(),rail.worldY(),0);
            rail.markerId=marker.getWurmId();return rail;
        }
        void close() {
            for(Item item:Items.getAllItems())if(WurmRailWorld.isJunctionIndicator(item))
                for(Node rail:net.nodes.values())if(item.getData()==rail.markerId) { Items.destroyItem(item.getWurmId());break; }
            for(Node rail:net.nodes.values())if(Items.getItemOptional(rail.markerId).isPresent())Items.destroyItem(rail.markerId);
            if(actor.inventory!=null&&Items.getItemOptional(actor.inventory.getWurmId()).isPresent())Items.destroyItem(actor.inventory.getWurmId());
        }
    }
    private static Path state(String name) {
        return Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("track-corners/"+name+"/network.properties");
    }
    private static WurmRailRuntime runtime(RailNetwork net,String name) throws Exception {
        WurmRailRuntime runtime=new WurmRailRuntime();runtime.configure(new Properties());
        set(runtime,"network",net);set(runtime,"ready",true);set(runtime,"stateFile",state(name));return runtime;
    }
    private static Map<String,List<String>> links(RailNetwork net) {
        Map<String,List<String>> result=new LinkedHashMap<String,List<String>>();
        for(Node rail:net.nodes.values())result.put(rail.name,new ArrayList<String>(rail.links));return result;
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(target,args); }
        catch(InvocationTargetException wrapped) {
            Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw wrapped;
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void fail(Checked action,String message) throws Exception {
        boolean rejected=false;try { action.run(); }catch(IllegalArgumentException expected) { rejected=true; }check(rejected,message);
    }
    private static void check(boolean okay,String message) { assertions++;if(!okay)throw new AssertionError(message); }
}
