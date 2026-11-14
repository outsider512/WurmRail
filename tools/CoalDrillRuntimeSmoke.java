import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.*;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.SteamRockDrills;
import org.wurmrail.shared.CoalVeinTiles;

/** Timed native Coal Vein drilling, safe final depletion and original-item output recovery. */
public final class CoalDrillRuntimeSmoke {
    private static final int X=224,Y=184;
    private static final int[] DX={0,1,0,-1},DY={-1,0,1,0};
    private static final long OWNER=998492160L,NO_ID=-10L;
    private static int checks;
    private static final List<Item> made=new ArrayList<Item>();
    private static final Set<Long> seen=new HashSet<Long>();
    public static void run()throws Exception {
        MeshIO oldCave=Server.caveMesh,oldResource=Server.resourceMesh;boolean[][] oldProtected=Zones.protectedTiles;
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            int[] cave=new int[256*256],resources=new int[cave.length];Arrays.fill(resources,0x000012ab);
            for(int y=0;y<256;y++)for(int x=0;x<256;x++)cave[x+y*256]=oldCave.getTile(x,y);
            Server.caveMesh=MeshIO.createMap(Paths.get("coal_drill_cave.map").toAbsolutePath().toString(),8,cave);
            Server.resourceMesh=MeshIO.createMap(Paths.get("coal_drill_resources.map").toAbsolutePath().toString(),8,resources);
            Zones.protectedTiles=new boolean[256][256];
            Server.caveMesh.setTile(X,Y,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50));
            Item drill=placed(RailwaySteamRockDrill.templateId,X,Y),hopper=placed(RailwayBulkHopper.templateId,X-1,Y);
            for(int face=0;face<4;face++)wall(face,2);
            for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1}){Server.caveMesh.setTile(X+dx,Y+dy,Tiles.encode((short)25,CoalVeinTiles.TILE_TYPE,(byte)55));Server.setCaveResource(X+dx,Y+dy,8);}
            Path file=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("coal-drill-controls.properties");
            RailwayDrillController controller=new RailwayDrillController(file,.1);
            SteamTestSupplies.provision(controller.supplies(),OWNER,drill,true,made);
            controller.state().put(new SteamRockDrills.Drill(drill.getWurmId(),OWNER,controller.state().nextRevision(),X,Y,-1,hopper.getWurmId(),15,0,true,"Running coal drill fixture."));
            controller.tick(0);controller.tick(.05);check(hopper.getItems().isEmpty(),"automatic drilling earns a complete mining interval before producing Coal");
            controller.tick(.05);delivered(hopper,0);
            for(int face=1;face<4;face++){controller.tick(.1);delivered(hopper,face);}
            for(int face=0;face<4;face++)check(Server.getCaveResource(X+DX[face],Y+DY[face])==1,"the first timed round touches each configured cardinal face exactly once");
            Server.caveMesh.saveAll();Server.resourceMesh.saveAll();Server.caveMesh.close();Server.resourceMesh.close();
            Server.caveMesh=MeshIO.open(Paths.get("coal_drill_cave.map").toAbsolutePath().toString());Server.resourceMesh=MeshIO.open(Paths.get("coal_drill_resources.map").toAbsolutePath().toString());
            controller=new RailwayDrillController(file,.1);controller.tick(0);controller.tick(.099);
            check(hopper.getItems().size()==4,"native map and controller restart retain Coal without offline transfer credit");
            controller.tick(.001);delivered(hopper,0);
            for(int face=1;face<4;face++){controller.tick(.1);delivered(hopper,face);}
            for(int face=0;face<4;face++){
                int tile=Server.caveMesh.getTile(X+DX[face],Y+DY[face]);
                check(Tiles.decodeType(tile)==Tiles.Tile.TILE_CAVE_WALL.id&&Tiles.decodeHeight(tile)==20+face&&Tiles.decodeData(tile)==(byte)(50+face)
                        &&Server.getCaveResource(X+DX[face],Y+DY[face])==0,"last Coal extraction converts only its exhausted wall to normal rock, preserving physical height/clearance");
                check((Server.resourceMesh.getTile(X+DX[face],Y+DY[face])&65535)==0x12ab,"automatic depletion preserves unrelated packed world-resource data");
            }
            for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1})check(Server.getCaveResource(X+dx,Y+dy)==8&&CoalVeinTiles.isCoal(Tiles.decodeType(Server.caveMesh.getTile(X+dx,Y+dy))),"diagonal Coal Veins are never part of the four-face drilling range");
            check(hopper.getItems().size()==8,"two finite resources on four faces yield exactly eight original Coal items");
            safety(drill,hopper);faults(drill,hopper);
            wall(0,65534);RailwayDrillMining.MiningResult high=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());made.add(Items.getItem(high.itemId));
            check(high.mined&&Server.getCaveResource(X,Y-1)==65533,"maximum finite map count decrements without signed overflow or uninitialized-sentinel randomization");
            Server.resourceMesh.saveAll();Server.resourceMesh.close();Server.resourceMesh=MeshIO.open(Paths.get("coal_drill_resources.map").toAbsolutePath().toString());
            check(Server.getCaveResource(X,Y-1)==65533&&(Server.resourceMesh.getTile(X,Y-1)&65535)==0x12ab,"high native resource count persists through exact map reload");
            System.out.println("PASS native Coal drilling: timed cardinal round-robin, exact Coal output, safe rock depletion, untouched diagonals, finite counts and map/controller restart, Hopper backpressure and exact-item fault recovery ("+checks+" assertions)");
        } finally {
            fault(0,NO_ID,NO_ID);
            for(int i=made.size()-1;i>=0;i--)if(Items.getItemOptional(made.get(i).getWurmId()).isPresent())Items.destroyItem(made.get(i).getWurmId());
            if(Server.caveMesh!=oldCave){Server.caveMesh.close();Server.caveMesh=oldCave;}
            if(Server.resourceMesh!=oldResource){Server.resourceMesh.close();Server.resourceMesh=oldResource;}
            Zones.protectedTiles=oldProtected;com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void delivered(Item hopper,int face)throws Exception {
        Item fresh=null;for(Item item:hopper.getItems())if(!seen.contains(item.getWurmId())){check(fresh==null,"one timed opportunity creates only one physical output");fresh=item;}
        check(fresh!=null&&CoalItemRegistry.isCoal(fresh.getTemplateId())&&fresh.getTemplateId()!=ItemList.charcoal
                &&fresh.getWeightGrams()==20000&&fresh.getDataX()==X+DX[face]&&fresh.getDataY()==Y+DY[face],"both physical coal identity and round-robin source coordinates survive timed production");
        check(fresh.getParentId()==hopper.getWurmId()&&new DbItem(fresh.getWurmId()).getParentId()==hopper.getWurmId()&&fresh.getQualityLevel()>=1&&fresh.getAuxData()==0,"the original mined item and persistent Hopper parent are confirmed before another action");
        seen.add(fresh.getWurmId());made.add(fresh);
    }
    private static void safety(Item drill,Item hopper)throws Exception {
        wall(0,5);int count=hopper.getItems().size();
        check(!RailwayDrillMining.mine(drill,OWNER,0,NO_ID).mined&&Server.getCaveResource(X,Y-1)==5&&drill.getItems().isEmpty(),"missing storage never consumes Coal or creates an output artifact");
        hopper.setLastOwnerId(OWNER+1024);
        check(!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==5&&hopper.getItems().size()==count,"foreign storage blocks mining before Coal depletion");hopper.setLastOwnerId(OWNER);
        Item full=placed(RailwayBulkHopper.templateId,X-1,Y-1),heavy=ItemFactory.createItem(CoalItemRegistry.templateId(),40f,"Coal capacity fixture");made.add(heavy);heavy.setWeight((int)BulkHopperPolicy.MAX_WEIGHT_GRAMS,true);heavy.setSizes(1,1,1);
        check(full.insertItem(heavy,false,true),"a real Hopper can reach its guarded industrial weight boundary");
        check(!RailwayDrillMining.mine(drill,OWNER,0,full.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==5&&drill.getItems().isEmpty(),"full Hopper capacity stops Coal mining before changing the map");
    }
    private static void faults(Item drill,Item hopper)throws Exception {
        wall(0,1);int before=hopper.getItems().size();fault(1,drill.getWurmId(),hopper.getWurmId());
        RailwayDrillMining.MiningResult refused=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());long artifact=field("faultOutput").getLong(null);
        check(!refused.mined&&Server.getCaveResource(X,Y-1)==1&&CoalVeinTiles.isCoal(Tiles.decodeType(Server.caveMesh.getTile(X,Y-1)))&&drill.getItems().isEmpty()
                &&!Items.getItemOptional(artifact).isPresent(),"native staging refusal cancels unearned Coal and leaves the final vein unit intact");
        fault(5,drill.getWurmId(),hopper.getWurmId());RailwayDrillMining.MiningResult retained=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
        Item coal=Items.getItem(retained.itemId);made.add(coal);
        check(retained.mined&&retained.depleted&&retained.reason!=null&&coal.getParentId()==drill.getWurmId()&&hopper.getItems().size()==before
                &&Server.getCaveResource(X,Y-1)==0&&Tiles.decodeType(Server.caveMesh.getTile(X,Y-1))==Tiles.Tile.TILE_CAVE_WALL.id,"failed final-unit delivery retains its exact earned Coal after safe rock depletion");
        check(new DbItem(coal.getWurmId()).getParentId()==drill.getWurmId()&&coal.getAuxData()==0,"confirmed final Coal persists in the real safety buffer without an uncertain-commit marker");
        fault(0,NO_ID,NO_ID);int tile=Server.caveMesh.getTile(X,Y-1);
        check(RailwayDrillMining.flushBuffered(drill,OWNER,hopper.getWurmId())==null&&Items.getItem(coal.getWurmId())==coal&&coal.getParentId()==hopper.getWurmId()
                &&hopper.getItems().size()==before+1&&drill.getItems().isEmpty()&&Server.caveMesh.getTile(X,Y-1)==tile&&Server.getCaveResource(X,Y-1)==0,"retry moves the same final Coal item exactly once without mining new rock or duplicating resources");
    }
    private static Field field(String name)throws Exception {Field field=SteamDrillMiningRuntimeSmoke.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void fault(int mode,long drill,long hopper)throws Exception {field("faultMode").setInt(null,mode);field("faultDrill").setLong(null,drill);field("faultHopper").setLong(null,hopper);field("faultOutput").setLong(null,NO_ID);}
    private static void wall(int face,int count){Server.caveMesh.setTile(X+DX[face],Y+DY[face],Tiles.encode((short)(20+face),CoalVeinTiles.TILE_TYPE,(byte)(50+face)));Server.setCaveResource(X+DX[face],Y+DY[face],count);Zones.setMiningState(X+DX[face],Y+DY[face],(byte)0,false);}
    private static Item placed(int template,int x,int y)throws Exception {Item item=ItemFactory.createItem(template,65f,x*4+2f,y*4+2f,0,false,(byte)0,NO_ID,"Coal drill native fixture");item.setLastOwnerId(OWNER);made.add(item);return item;}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
