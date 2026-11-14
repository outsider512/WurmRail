import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Action;
import com.wurmonline.server.behaviours.CaveWallBehaviour;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.players.PlayerCommunicatorQueued;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.server.skills.Skills;
import com.wurmonline.server.skills.SkillsFactory;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.shared.CoalVeinTiles;

/** Real native tile registry, manual cave actions and persistent finite resources. */
public final class CoalVeinNativeRuntimeSmoke {
    private static final int X=208,Y=150;
    private static int checks;
    private static final List<Item> made=new ArrayList<Item>();
    public static void run()throws Exception {
        MeshIO oldCave=Server.caveMesh,oldResource=Server.resourceMesh;
        boolean[][] oldProtected=Zones.protectedTiles;Player player=null;
        try {
            int[] cave=new int[256*256],resources=new int[cave.length];Arrays.fill(resources,0x00003456);
            for(int y=0;y<256;y++)for(int x=0;x<256;x++)cave[x+y*256]=oldCave.getTile(x,y);
            Server.caveMesh=MeshIO.createMap(Paths.get("coal_native_cave.map").toAbsolutePath().toString(),8,cave);
            Server.resourceMesh=MeshIO.createMap(Paths.get("coal_native_resources.map").toAbsolutePath().toString(),8,resources);
            Zones.protectedTiles=new boolean[256][256];
            registry();
            for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++)Server.caveMesh.setTile(X+dx,Y+dy,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE_WALL.id,(byte)50));
            Server.caveMesh.setTile(X,Y,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50));
            Server.caveMesh.setTile(X,Y-1,Tiles.encode((short)20,CoalVeinTiles.TILE_TYPE,(byte)50));Server.setCaveResource(X,Y-1,4);
            player=player();Item pick=ItemFactory.createItem(ItemList.pickAxe,90f,"Native coal action fixture");made.add(pick);
            Field serverInstance=Server.class.getDeclaredField("instance");serverInstance.setAccessible(true);if(serverInstance.get(null)==null)serverInstance.set(null,allocate(Server.class));
            Skills skills=SkillsFactory.createSkills("Native Coal Vein mining");skills.learn(SkillList.MINING,100f);skills.learn(SkillList.PROSPECT,100f);skills.learn(pick.getPrimarySkill(),100f);set(Creature.class,player,"skills",skills);
            Constructor<CaveWallBehaviour> constructor=CaveWallBehaviour.class.getDeclaredConstructor();constructor.setAccessible(true);CaveWallBehaviour wall=constructor.newInstance();
            Action action=(Action)allocate(Action.class);action.setTimeLeft(0);action.setRarity((byte)0);
            set(Action.class,action,"action",(short)145);set(Action.class,action,"performer",player);
            Field slow=com.wurmonline.server.behaviours.ActionStack.class.getDeclaredField("slowActions");slow.setAccessible(true);
            @SuppressWarnings("unchecked") List<Action> completedAction=(List<Action>)slow.get(player.getActions());completedAction.add(action);
            int tile=Server.caveMesh.getTile(X,Y-1);
            check(wall.action(action,player,X,Y-1,false,tile,3,(short)1,1f),"native examining completes on a genuine Coal Vein wall");
            check(messages(player).contains("Coal Vein")&&!messages(player).contains("Unknown vein!"),"native examine identifies the distinct resource instead of a vanilla ore");
            wall.action(action,player,pick,X,Y-1,false,0,tile,3,(short)156,1000f);
            check(messages(player).contains("You would mine coal here.")&&!messages(player).contains("charcoal coal"),"ordinary native prospecting identifies Coal independently from charcoal");
            check(messages(player).contains("4 more times"),"native skilled prospecting reports the actual finite resource count");
            Set<Long> before=ids();wall.action(action,player,pick,X,Y-1,false,0,tile,3,(short)145,1000f);
            List<Item> output=addedCoal(before);
            check(Server.getCaveResource(X,Y-1)==3&&output.size()==1,"one completed manual mining action produces one real Coal item and consumes one native resource unit");
            Item coal=output.get(0);made.add(coal);
            check(CoalItemRegistry.isCoal(coal.getTemplateId())&&coal.getTemplateId()!=ItemList.charcoal&&coal.getWeightGrams()>0&&coal.getQualityLevel()>=1,"manual output preserves the dedicated physical Coal identity and native quality/weight");
            check((Server.resourceMesh.getTile(X,Y-1)&65535)==0x3456,"cave depletion never changes packed surface resource bits");
            Server.caveMesh.saveAll();Server.resourceMesh.saveAll();Server.caveMesh.close();Server.resourceMesh.close();
            Server.caveMesh=MeshIO.open(Paths.get("coal_native_cave.map").toAbsolutePath().toString());Server.resourceMesh=MeshIO.open(Paths.get("coal_native_resources.map").toAbsolutePath().toString());
            check(CoalVeinTiles.isCoal(Tiles.decodeType(Server.caveMesh.getTile(X,Y-1)))&&Server.getCaveResource(X,Y-1)==3,"Coal Vein tile and exact resource quantity survive real native map save/reopen");
            Server.setCaveResource(X,Y-1,1);before=ids();tile=Server.caveMesh.getTile(X,Y-1);
            wall.action(action,player,pick,X,Y-1,false,0,tile,3,(short)145,1000f);List<Item> finalOutput=addedCoal(before);made.addAll(finalOutput);
            byte depleted=Tiles.decodeType(Server.caveMesh.getTile(X,Y-1));
            check(Server.getCaveResource(X,Y-1)==0&&!CoalVeinTiles.isCoal(depleted)
                    &&(depleted==Tiles.Tile.TILE_CAVE_WALL.id||depleted==Tiles.Tile.TILE_CAVE.id),"native final depletion leaves ordinary cave/rock rather than a renewable Coal Vein");
            check(finalOutput.size()==(depleted==Tiles.Tile.TILE_CAVE.id?1:0),"final output follows vanilla ore tunnel-success versus safe-rock-fallback mechanics without duplication");
            check((Server.resourceMesh.getTile(X,Y-1)&65535)==0x3456,"native final depletion preserves unrelated packed resource data");
            System.out.println("PASS native Coal Vein: distinct registry/flags, vanilla ore preservation, genuine manual examine/prospect/mine, finite count/depletion, original Coal identity and map restart persistence ("+checks+" assertions)");
        }finally {
            for(Item item:made)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
            if(player!=null){Method remove=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",Player.class);remove.setAccessible(true);remove.invoke(null,player);}
            if(Server.caveMesh!=oldCave){Server.caveMesh.close();Server.caveMesh=oldCave;}
            if(Server.resourceMesh!=oldResource){Server.resourceMesh.close();Server.resourceMesh=oldResource;}
            Zones.protectedTiles=oldProtected;
        }
    }
    private static void registry()throws Exception {
        Tiles.Tile tile=Tiles.getTile(CoalVeinTiles.TILE_ID);
        check(tile!=null&&tile.getIntId()==208&&tile.getName().equals("Coal Vein")&&tile.isCave()&&tile.isSolidCave()&&tile.isOreCave(),"the unused208 slot registers a true named, solid cave ore tile");
        check(Tiles.Tile.valueOf(CoalVeinTiles.ENUM_NAME)==tile&&Arrays.asList(Tiles.Tile.values()).contains(tile),"the real enum field and values list contain the distinct Coal Vein");
        check(Tiles.isSolidCave(CoalVeinTiles.TILE_TYPE)&&Tiles.isOreCave(CoalVeinTiles.TILE_TYPE)&&Tiles.isReinforcedCave(CoalVeinTiles.TILE_TYPE)&&!Tiles.isReinforcedCaveWall(CoalVeinTiles.TILE_TYPE),"static classification agrees with native ore flags without treating Coal as clad reinforcement");
        check(Tiles.getTile(222)==Tiles.Tile.TILE_CAVE_WALL_ORE_IRON&&Tiles.getTile(229)==Tiles.Tile.TILE_CAVE_WALL_ROCKSALT&&Tiles.getTile(230)==Tiles.Tile.TILE_CAVE_WALL_SANDSTONE,"vanilla mineral and salt identities remain unchanged");
        Method template=Class.forName("com.wurmonline.server.behaviours.TileRockBehaviour").getDeclaredMethod("getItemTemplateForTile",byte.class);template.setAccessible(true);
        check(((Integer)template.invoke(null,CoalVeinTiles.TILE_TYPE)).intValue()==CoalItemRegistry.templateId()
                &&((Integer)template.invoke(null,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id)).intValue()==ItemList.ironOre,"native mining resolves Coal while vanilla iron retains its actual template");
    }
    private static Player player()throws Exception {Method create=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);create.setAccessible(true);Player p=(Player)create.invoke(null,998491136L,X*4+2f,Y*4+2f);p.getStatus().setLayer(-1);p.getStatus().getPosition().setPosZ(2f,false);p.currentTile=Zones.getOrCreateTile(X,Y,false);return p;}
    private static Set<Long> ids(){Set<Long> result=new HashSet<Long>();for(Item item:Items.getAllItems())result.add(item.getWurmId());return result;}
    private static List<Item> addedCoal(Set<Long> before){List<Item> result=new ArrayList<Item>();for(Item item:Items.getAllItems())if(!before.contains(item.getWurmId())&&CoalItemRegistry.isCoal(item.getTemplateId()))result.add(item);return result;}
    private static String messages(Player p)throws Exception {Field field=PlayerCommunicatorQueued.class.getDeclaredField("MESSAGES_TO_PLAYERS");field.setAccessible(true);StringBuilder out=new StringBuilder();for(Object message:(Queue<?>)field.get(null)){Method owner=message.getClass().getDeclaredMethod("getPlayerId");owner.setAccessible(true);if(((Long)owner.invoke(message)).longValue()!=p.getWurmId())continue;Method bytes=message.getClass().getDeclaredMethod("getMessageBytes");bytes.setAccessible(true);out.append(new String((byte[])bytes.invoke(message),StandardCharsets.UTF_8)).append('\n');}return out.toString();}
    private static Object allocate(Class<?> type)throws Exception {Field f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);}
    private static void set(Class<?> type,Object instance,String name,Object value)throws Exception {Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(instance,value);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
