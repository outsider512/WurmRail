import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.WurmId;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.BehaviourDispatcher;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.structures.BridgePart;
import com.wurmonline.server.structures.DbBridgePart;
import com.wurmonline.server.structures.Structure;
import com.wurmonline.server.structures.Structures;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.BridgeConstants.BridgeMaterial;
import com.wurmonline.shared.constants.BridgeConstants.BridgeState;
import com.wurmonline.shared.constants.BridgeConstants.BridgeType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.wurmrail.RailwayBuilderUi;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork.Train;

/** Native bridge parts, builder placement, item motion and saved bridge identity. */
public final class RailwayBridgeRuntimeSmoke {
    private static final int FIRST = 218, LAST = 221;
    private static final int[] ARCH = {50, 85, 110, 85, 50};
    private static List<ActionEntry> capturedMenu;

    public static void captureMenu(List<ActionEntry> menu) { capturedMenu=menu; }

    public static void run() throws Exception {
        int y = 230;
        for (BridgeMaterial material : BridgeMaterial.values()) {
            int[] heights = material == BridgeMaterial.WOOD ? ARCH
                    : material == BridgeMaterial.BRICK ? new int[]{50, 65, 80, 95, 110}
                    : new int[]{50, 50, 50, 50, 50};
            runBridge(y++, material, heights);
        }
        System.out.println("PASS native bridge tracks and vehicles on every solid material, flat/graded/arched decks, both directions, three-car consist, saved bridge IDs, incomplete and rope rejection");
    }

    private static void runBridge(int y, BridgeMaterial material, int[] heights) throws Exception {
        for (int x = FIRST - 4; x <= LAST + 4; x++) for (int row = y; row <= y + 1; row++) {
            short ground = (short)(x <= FIRST ? heights[0] : x > LAST ? heights[4] : -50);
            Server.surfaceMesh.setTile(x, row, Tiles.encode(ground, Tiles.Tile.TILE_DIRT.id, (byte)0));
        }
        Creature builder = TrackMaterialsSmoke.actor(720014032L + y * 256L, true, 2);
        builder.getStatus().setPositionXYZ((FIRST-1)*4+2,y*4+2,heights[0]/10f);
        Structure bridge = Structures.createStructure((byte)1, "Rail bridge " + material, WurmId.getNextPlanId(), FIRST, y, true);
        List<BridgePart> parts = new ArrayList<BridgePart>();
        List<Item> items = new ArrayList<Item>();
        try {
            for (int x = FIRST; x <= LAST; x++) {
                VolaTile tile = Zones.getOrCreateTile(x, y, true);
                bridge.addBuildTile(tile, false);
                tile.addBridge(bridge);
            }
            bridge.makeFinal(builder, "Rail bridge " + material);
            for (int x = FIRST; x <= LAST; x++) {
                int index = x - FIRST;
                BridgeType type = x == FIRST || x == LAST ? BridgeType.ABUTMENT_NARROW : BridgeType.CROWN_NARROW;
                BridgePart part = new DbBridgePart(type, x, y, heights[index], 80f, bridge.getWurmId(), material,
                        (byte)2, (byte)(heights[index+1] - heights[index]), -1, x == LAST ? 0 : -1,
                        -1, x == FIRST ? 0 : -1, (byte)0, 0);
                Zones.getOrCreateTile(x, y, true).addBridgePart(part);
                parts.add(part);
            }
            check(WurmRailWorld.validateTile(FIRST,y,0)!=null,"unbuilt deck rejected");
            for (BridgePart part : parts) part.setBridgePartState(BridgeState.COMPLETED);
            check(bridge.updateStructureFinishFlag(),"native bridge completion");
            String error = WurmRailWorld.validateTile(FIRST,y,0);
            if (material == BridgeMaterial.ROPE) {
                check(error!=null&&error.contains("rope"),"completed rope deck rejected explicitly");
                return;
            }
            check(error==null,"completed solid deck accepted: "+error);
            Path state = Paths.get("bridge-fixtures",material.name(),"network.properties");
            Files.createDirectories(state.getParent());
            RailNetwork network = new RailNetwork();
            WurmRailRuntime runtime = new WurmRailRuntime();
            runtime.configure(new Properties());
            set(runtime,"network",network);
            set(runtime,"stateFile",state);
            Method build = WurmRailRuntime.class.getDeclaredMethod("buildLine", Creature.class, String.class,
                    int.class,int.class,int.class,int.class,int.class,boolean.class);
            build.setAccessible(true);
            build.invoke(runtime,builder,"Rail",FIRST-1,y,0,2,6,true);
            RailNetwork reloaded = RailNetwork.load(state);
            for (int i = 0; i < 6; i++) {
                Node node = reloaded.nodes.get("Rail"+i);
                Item marker = Items.getItem(node.markerId);
                items.add(marker);
                check(marker.getBridgeId()==node.bridgeId,"native marker and saved node agree");
                if (i>0&&i<5) {
                    check(node.bridgeId==bridge.getWurmId(),"node retains the native deck after reload");
                    check(Math.abs(marker.getPosZ()-(heights[i-1]+heights[i])/20f)<.001f,"track marker at native deck height");
                }
                if (i>0) check(node.links.contains("Rail"+(i-1)),"rail link across adjacent tiles");
            }
            for (boolean engine : new boolean[]{true,false}) {
                float x = (FIRST-1)*4+2, yy = y*4+2;
                Item car = engine ? WurmRailWorld.spawnLocomotive(null,x,yy,0) : WurmRailWorld.spawnCart(null,x,yy,0);
                items.add(car);
                for (int direction : new int[]{1,-1}) for (int p=0; p<80; p++) {
                    x += direction*.25f;
                    WurmRailWorld.moveCar(car.getWurmId(),x,yy,direction==1?90:270,0);
                    int tile=(int)(x/4);
                    boolean onDeck=tile>=FIRST&&tile<=LAST;
                    check(car.getBridgeId()==(onDeck?bridge.getWurmId():-10L),"vehicle bridge ID at "+x);
                    float expected=onDeck?Zones.calculatePosZ(x,yy,null,true,false,0,null,bridge.getWurmId())
                            :Zones.calculateHeight(x,yy,true);
                    check(Math.abs(car.getPosZ()-expected)<.001f,"vehicle follows native deck height at "+x);
                }
                Items.destroyItem(car.getWurmId());
                items.remove(car);
            }
            if(material==BridgeMaterial.WOOD) {
                deckMenus(y,bridge,builder,runtime,network,parts.get(1),build,items);
                runConsist(y,bridge,builder,runtime,network,state,items);
            }
            parts.get(1).setBridgePartState(BridgeState.PLANNED);
            check(WurmRailWorld.validateEdge(FIRST,y,FIRST+1,y,0)!=null,"unfinished replacement breaks movement");
        } finally {
            for (Item item : items) Items.destroyItem(item.getWurmId());
            for (BridgePart part : parts) part.destroy();
            bridge.totallyDestroy();
        }
    }

    private static void deckMenus(int y,Structure bridge,Creature builder,WurmRailRuntime runtime,
            RailNetwork network,BridgePart part,Method build,List<Item> items) throws Exception {
        int x=part.getTileX();
        Node node=network.nodes.get("Rail2");
        Item marker=Items.getItem(node.markerId);
        float deckHeight=marker.getPosZ();
        Method snapshot=WurmRailRuntime.class.getDeclaredMethod("builderSnapshot",Creature.class,int.class,int.class,int.class);
        snapshot.setAccessible(true);
        Method remove=WurmRailRuntime.class.getDeclaredMethod("removeAndRecover",Creature.class,Node.class);
        remove.setAccessible(true);
        Creature deckActor=TrackMaterialsSmoke.actor(720030017L,true,2);
        deckActor.getStatus().setPositionXYZ(x*4+2,y*4+2,deckHeight);
        deckActor.getStatus().getPosition().setBridgeId(bridge.getWurmId());
        check(deckActor.getFloorLevel()>0&&marker.getFloorLevel()==0,"fixture reproduces native deck floor mismatch");
        for(long source:new long[]{-1L,deckActor.getInventory().getWurmId()}) {
            check(menuHas(deckActor,source,part.getId(),"Build railway"),"native bridge target advertises Build railway");
            check(menuHas(deckActor,source,marker.getWurmId(),"Railway track"),"native elevated marker advertises Railway track");
        }
        deckActor.getStatus().getPosition().setBridgeId(-10L);
        check(!menuHas(deckActor,-1L,marker.getWurmId(),"Railway track"),"track menu requires the actor's bridge ID");
        deckActor.getStatus().getPosition().setBridgeId(bridge.getWurmId());
        RailwayBuilderUi.View existing=(RailwayBuilderUi.View)snapshot.invoke(runtime,deckActor,x,y,0);
        check(existing.markerPresent&&existing.location.startsWith("Bridge deck"),"existing bridge track opens the builder");
        remove.invoke(runtime,deckActor,node);
        items.remove(marker);
        check(!Items.getItemOptional(marker.getWurmId()).isPresent(),"standing on the deck can remove existing track");
        build.invoke(runtime,deckActor,"Rail2",x,y,0,2,1,false);
        Node replaced=network.nodes.get("Rail2");
        check(replaced.bridgeId==bridge.getWurmId()&&replaced.links.size()==2,"standing on the deck can rebuild and reconnect track");
        items.add(Items.getItem(replaced.markerId));
        System.out.println("PASS native bridge deck and track right-click menus with empty and active source, positive player floor level, direct removal and rebuild");
    }

    private static boolean menuHas(Creature actor,long source,long target,String action) throws Exception {
        capturedMenu=null;
        BehaviourDispatcher.requestActions(actor,actor.getCommunicator(),(byte)1,source,target);
        if(capturedMenu!=null)for(ActionEntry entry:capturedMenu)
            if(action.equals(entry.getActionString()))return true;
        return false;
    }

    private static void runConsist(int y,Structure bridge,Creature builder,WurmRailRuntime runtime,
            RailNetwork network,Path state,List<Item> items) throws Exception {
        Method lay=WurmRailRuntime.class.getDeclaredMethod("lay",Creature.class,String.class,int.class,int.class,int.class);
        lay.setAccessible(true);
        for(int x:new int[]{FIRST-3,FIRST-2,LAST+2,LAST+3}) {
            String name="Approach"+x;
            lay.invoke(runtime,builder,name,x,y,0);
            items.add(Items.getItem(network.nodes.get(name).markerId));
        }
        float centreY=y*4+2;
        Item engine=WurmRailWorld.spawnLocomotive(null,(FIRST-1)*4+2,centreY,0);
        Item wagon=WurmRailWorld.spawnCart(null,(FIRST-2)*4+2,centreY,0);
        Item logCar=WurmRailWorld.spawnLogCar(null,(FIRST-3)*4+2,centreY,0);
        items.addAll(Arrays.asList(engine,wagon,logCar));
        for(Item car:Arrays.asList(engine,wagon,logCar))
            WurmRailWorld.moveCar(car.getWurmId(),car.getPosX(),centreY,90,0);
        Method validate=WurmRailRuntime.class.getDeclaredMethod("validateMotion",List.class);
        validate.setAccessible(true);
        network.movementGuard=poses->{
            try { return (Boolean)validate.invoke(runtime,poses); }
            catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        };
        Train train=network.createTrain("BridgeConsist",builder.getWurmId(),
                Arrays.asList(engine.getWurmId(),wagon.getWurmId(),logCar.getWurmId()),"Rail0","Rail1");
        network.start(train.name);
        boolean sawAllOnDeck=false;
        for(int step=0;step<800 && logCar.getTileX()<=LAST;step++) {
            network.tick(.1);
            for(Pose pose:train.poses)
                WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);
            sawAllOnDeck|=engine.getBridgeId()==bridge.getWurmId()&&wagon.getBridgeId()==bridge.getWurmId()
                    &&logCar.getBridgeId()==bridge.getWurmId();
        }
        check(sawAllOnDeck&&logCar.getTileX()>LAST,"the locomotive and both wagons cross together");
        network.save(state);
        check(RailNetwork.load(state).trains.get(train.name).carIds.equals(train.carIds),"reloaded consist retains coupling");
        Item marker=Items.getItem(network.nodes.get("Rail2").markerId);
        DbItem diskMarker=new DbItem(marker.getWurmId());
        check(diskMarker.getBridgeId()==bridge.getWurmId()&&diskMarker.getPosZ()==marker.getPosZ(),
                "native item reload retains the bridge ID and deck elevation");
    }

    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object,value);
    }

    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
}
