import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.Servers;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.ItemMaterials;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import org.wurmrail.*;

/** Actual cave maps, native vein counts/quality and original item inventories; no mining mocks. */
public final class SteamDrillMiningRuntimeSmoke {
    private static int assertions;
    private static final long OWNER=998465792L,NO_ID=-10L;
    private static final int X=174,Y=194;
    private static final int[] DX={0,1,0,-1},DY={-1,0,1,0};
    private static final List<Item> created=new ArrayList<Item>();
    private static long faultDrill=NO_ID,faultHopper=NO_ID,faultOutput=NO_ID;
    private static int faultMode;
    private SteamDrillMiningRuntimeSmoke() { }

    public static void run() throws Exception {
        MeshIO previous=Server.resourceMesh;
        boolean[][] protectedBefore=Zones.protectedTiles;
        int[] caveBefore=new int[25];for(int at=0;at<25;at++)caveBefore[at]=Server.caveMesh.getTile(X-2+at%5,Y-2+at/5);
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            int[] resources=new int[256*256];Arrays.fill(resources,0xffff0000|1234);
            Server.resourceMesh=MeshIO.createMap(Paths.get("steam_drill_resources.map").toAbsolutePath().toString(),8,resources);
            Zones.protectedTiles=new boolean[256][256];
            Item drill=placed(RailwaySteamRockDrill.templateId,X,Y,false);
            Item hopper=placed(RailwayBulkHopper.templateId,X-1,Y-1,false);
            check(drill.isHollow()&&drill.getMaxItemCount()==1&&drill.getContainerVolume()>=20000,
                    "the actual craftable drill has one real-item safety buffer");
            check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwaySteamRockDrill.templateId)!=null,
                    "the actual Steam Rock Drill is available through native player construction");
            cardinalFaces(drill,hopper);
            nativeResourceTypes(drill,hopper);
            normalRockLimit(drill,hopper);
            safety(drill,hopper);
            settlementPermissions(drill,hopper);
            stagingFailures(drill,hopper);
            retainedOutput(drill,hopper);
            System.out.println("PASS native Steam Rock Drill mining: four cardinal faces, untouched diagonals, native material/weight/quality and vein depletion, finite rock without tunnel advancement, protected/unsafe faces, hopper preflight and retained real output ("+assertions+" assertions)");
        } finally {
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            for(int at=0;at<25;at++)Server.caveMesh.setTile(X-2+at%5,Y-2+at/5,caveBefore[at]);
            for(int face=0;face<4;face++)Zones.setMiningState(X+DX[face],Y+DY[face],(byte)0,false);
            Server.resourceMesh.close();Server.resourceMesh=previous;Zones.protectedTiles=protectedBefore;
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void cardinalFaces(Item drill,Item hopper)throws Exception {
        openFloor();
        for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1}) {
            Server.caveMesh.setTile(X+dx,Y+dy,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE_WALL_ORE_GOLD.id,(byte)50));
            Server.setCaveResource(X+dx,Y+dy,17);
        }
        for(int face=0;face<4;face++)wall(face,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,3,0);
        for(int face=0;face<4;face++) {
            RailwayDrillMining.FaceInfo info=RailwayDrillMining.inspect(drill,OWNER,face);
            check(info.available&&info.templateId==ItemList.ironOre,"each immediate cardinal face is independently available");
            RailwayDrillMining.MiningResult result=RailwayDrillMining.mine(drill,OWNER,face,hopper.getWurmId());
            check(result.mined&&!result.depleted&&result.reason==null&&result.itemId>0,"a single native mining action succeeds directly into the linked hopper");
            Item ore=Items.getItem(result.itemId);created.add(ore);
            check(ore.getTemplateId()==ItemList.ironOre&&ore.getWeightGrams()==ItemTemplateFactory.getInstance().getTemplate(ItemList.ironOre).getWeightGrams(),
                    "native ItemFactory produces a normal real ore item and its ordinary weight");
            check(ore.getParentId()==hopper.getWurmId()&&hopper.getItems().contains(ore)&&drill.getItems().isEmpty(),
                    "the same exact item ID goes straight from the safety buffer into hopper storage");
            check(ore.getDataX()==X+DX[face]&&ore.getDataY()==Y+DY[face]&&ore.getAuxData()==0,
                    "successful native output retains the mined face location and clears its pending marker");
            check(ore.getQualityLevel()>=1&&ore.getQualityLevel()<=drill.getCurrentQualityLevel(),"native dry-run mining skill quality is bounded by the drill quality proxy");
            check(Server.getCaveResource(X+DX[face],Y+DY[face])==2,"only the selected native vein is depleted by exactly one action");
            check((Server.resourceMesh.getTile(X+DX[face],Y+DY[face])&65535)==1234,"depletion preserves the native packed surface-resource half of the tile");
            DbItem reloaded=new DbItem(ore.getWurmId());
            check(reloaded.getParentId()==hopper.getWurmId()&&reloaded.getWeightGrams()==ore.getWeightGrams(),"the native database persists the actual hopper item and real parent");
        }
        for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1})check(Server.getCaveResource(X+dx,Y+dy)==17,
                "diagonal faces remain completely untouched");
    }
    private static void nativeResourceTypes(Item drill,Item hopper)throws Exception {
        byte[] walls={Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,Tiles.Tile.TILE_CAVE_WALL_ORE_COPPER.id,
                Tiles.Tile.TILE_CAVE_WALL_ORE_GOLD.id,Tiles.Tile.TILE_CAVE_WALL_ORE_LEAD.id,Tiles.Tile.TILE_CAVE_WALL_ORE_SILVER.id,
                Tiles.Tile.TILE_CAVE_WALL_ORE_TIN.id,Tiles.Tile.TILE_CAVE_WALL_ORE_ZINC.id,Tiles.Tile.TILE_CAVE_WALL_ORE_ADAMANTINE.id,
                Tiles.Tile.TILE_CAVE_WALL_ORE_GLIMMERSTEEL.id,Tiles.Tile.TILE_CAVE_WALL_SLATE.id,Tiles.Tile.TILE_CAVE_WALL_MARBLE.id,
                Tiles.Tile.TILE_CAVE_WALL_SANDSTONE.id};
        int[] templates={ItemList.ironOre,ItemList.copperOre,ItemList.goldOre,ItemList.leadOre,ItemList.silverOre,
                ItemList.tinOre,ItemList.zincOre,ItemList.adamantineOre,ItemList.glimmerSteelOre,ItemList.slateShard,ItemList.marbleShard,ItemList.sandstone};
        for(int i=0;i<walls.length;i++) {
            wall(0,walls[i],1,0);int encoded=Server.caveMesh.getTile(X,Y-1);
            RailwayDrillMining.FaceInfo info=RailwayDrillMining.inspect(drill,OWNER,0);
            check(info.available&&info.templateId==templates[i],"native rock helper maps the actual vein to its correct bulk template");
            RailwayDrillMining.MiningResult result=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
            check(result.mined&&result.depleted&&result.reason==null,"the last native vein action produces one item and marks the face depleted");
            Item ore=Items.getItem(result.itemId);created.add(ore);
            check(ore.getTemplateId()==templates[i]&&ore.getParentId()==hopper.getWurmId(),"each actual mineral wall yields its original native resource item");
            check(Server.getCaveResource(X,Y-1)==0&&Server.caveMesh.getTile(X,Y-1)==encoded,"depleted veins never turn into new tunnels or renewable rock");
            check(!RailwayDrillMining.inspect(drill,OWNER,0).available,"a zero-resource mineral face is automatically unavailable");
            int count=hopper.getItems().size();
            check(!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined&&hopper.getItems().size()==count,"an exhausted vein cannot generate another real resource item");
        }
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_ADAMANTINE.id,600,0);
        RailwayDrillMining.MiningResult rare=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());created.add(Items.getItem(rare.itemId));
        check(rare.mined&&Server.getCaveResource(X,Y-1)==49,"native adamantine vein action uses its fifty-action resource cap");
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,65535,0);
        check(RailwayDrillMining.inspect(drill,OWNER,0).available&&Server.getCaveResource(X,Y-1)==65535,"inspection does not initialize or reroll an untouched native vein");
        Server.rand.setSeed(181811L);RailwayDrillMining.MiningResult fresh=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
        check(fresh.mined&&Server.getCaveResource(X,Y-1)<10000,"first completed action initializes the normal finite native vein resource value");created.add(Items.getItem(fresh.itemId));
        long emptySeed=0;while(new java.util.Random(emptySeed).nextInt(10000)!=0)emptySeed++;
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,65535,0);Server.rand.setSeed(emptySeed);int count=hopper.getItems().size();
        RailwayDrillMining.MiningResult empty=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
        check(!empty.mined&&empty.depleted&&Server.getCaveResource(X,Y-1)==0,"a legitimately empty initialized native vein is marked depleted rather than rerolled");
        check(hopper.getItems().size()==count&&drill.getItems().isEmpty()&&!RailwayDrillMining.inspect(drill,OWNER,0).available,
                "zero initialization creates no resource item and makes this face skippable");
    }
    private static void normalRockLimit(Item drill,Item hopper)throws Exception {
        int limit=Math.max(1,Math.min(56,Servers.localServer.getTunnelingHits()));
        for(byte kind:new byte[]{Tiles.Tile.TILE_CAVE_WALL.id,Tiles.Tile.TILE_CAVE_WALL_ROCKSALT.id}) {
            wall(1,kind,200,limit-1);int encoded=Server.caveMesh.getTile(X+1,Y);
            RailwayDrillMining.MiningResult result=RailwayDrillMining.mine(drill,OWNER,1,hopper.getWurmId());
            check(result.mined&&result.depleted&&result.reason==null,"normal rock/salt performs its last safe native mining-state action");created.add(Items.getItem(result.itemId));
            check(Zones.getMiningState(X+1,Y)==limit&&Server.caveMesh.getTile(X+1,Y)==encoded,"rock advances native mining state but never creates a tunnel");
            check(!RailwayDrillMining.inspect(drill,OWNER,1).available,"the drill refuses further rock production at its excavation limit");
        }
    }
    private static void safety(Item drill,Item hopper)throws Exception {
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);
        int before=hopper.getItems().size();
        check(!RailwayDrillMining.mine(drill,OWNER,0,NO_ID).mined&&Server.getCaveResource(X,Y-1)==9&&drill.getItems().isEmpty(),"missing hopper stops before depletion or resource creation");
        Item wrong=placed(ItemList.chestLarge,X-1,Y-1,false);
        check(!RailwayDrillMining.mine(drill,OWNER,0,wrong.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==9,"ordinary containers are not accepted as drill output");
        hopper.setLastOwnerId(OWNER+256);
        check(!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==9,"another owner's hopper cannot consume mine resources");hopper.setLastOwnerId(OWNER);
        Item full=placed(RailwayBulkHopper.templateId,X+1,Y+1,false),heavy=resource(ItemList.clay);
        heavy.setWeight((int)BulkHopperPolicy.MAX_WEIGHT_GRAMS,false);heavy.setSizes(1,1,1);check(full.insertItem(heavy,false,true),"real native hopper can reach its configured exact weight capacity");
        check(!RailwayDrillMining.mine(drill,OWNER,0,full.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==9&&drill.getItems().isEmpty(),"full hopper preflight stops before native vein depletion or creating any output");
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,65535,0);
        check(!RailwayDrillMining.mine(drill,OWNER,0,full.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==65535,
                "a full hopper never initializes, randomizes or consumes an untouched vein");
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);
        Zones.protectedTiles[X][Y-1]=true;
        check(!RailwayDrillMining.inspect(drill,OWNER,0).available&&!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined,"protected mining faces are refused even for an automated machine");Zones.protectedTiles[X][Y-1]=false;
        for(byte wall:new byte[]{Tiles.Tile.TILE_CAVE.id,Tiles.Tile.TILE_CAVE_WALL_REINFORCED.id,Tiles.Tile.TILE_CAVE_WALL_LAVA.id,Tiles.Tile.TILE_CAVE_WALL_STONE_REINFORCED.id}) {
            wall(0,wall,9,0);check(!RailwayDrillMining.inspect(drill,OWNER,0).available,"open, reinforced, lava and clad walls are not mined");
        }
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);
        Server.caveMesh.setTile(X,Y-1,Tiles.encode((short)-30,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,(byte)50));
        check(!RailwayDrillMining.inspect(drill,OWNER,0).available,"native too-deep mining faces are refused");
        check(hopper.getItems().size()==before,"every rejected mining action conserves existing hopper items");
    }
    private static void retainedOutput(Item drill,Item hopper)throws Exception {
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);
        Item ore=resource(ItemList.ironOre);ore.setDataXY(X,Y-1);ore.setQualityLevel(53f);ore.setAuxData((byte)0);
        check(drill.insertItem(ore,true),"a real failed-output item can remain inside the one-item native drill buffer");
        check(RailwayDrillMining.flushBuffered(drill,OWNER,NO_ID)!=null&&ore.getParentId()==drill.getWurmId(),"a missing linked hopper retains the exact output rather than dropping or deleting it");
        check(RailwayDrillMining.flushBuffered(drill,OWNER,hopper.getWurmId())==null&&ore.getParentId()==hopper.getWurmId()
                &&ore.getQualityLevel()==53f&&Items.getItem(ore.getWurmId())==ore,"output recovery transfers the same native item and properties without extra face depletion");
        check(Server.getCaveResource(X,Y-1)==9,"flushing old output does not mine another resource");
        Item pending=resource(ItemList.ironOre);pending.setAuxData((byte)99);pending.setDataXY(X,Y-1);check(drill.insertItem(pending,true),"an uncertain native commit is retained as a real pending output");
        String blocked=RailwayDrillMining.flushBuffered(drill,OWNER,hopper.getWurmId());
        check(blocked!=null&&blocked.contains("unconfirmed")&&pending.getParentId()==drill.getWurmId(),"unconfirmed staged output requires operator review instead of producing free ore");
        check(!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined&&Server.getCaveResource(X,Y-1)==9,
                "pending uncertain output blocks all later mining and duplicate resource production");
    }
    /** Test-only native insert fault, installed solely by the isolated integration harness. */
    public static boolean rejectStage(Item target,Item output) {
        if(faultMode==5||faultMode==6) {
            if(target.getWurmId()!=faultHopper||output.getParentId()!=faultDrill)return false;
            faultOutput=output.getWurmId();
            if(faultMode==5)return true;
            throw new IllegalStateException("Injected native hopper output failure after face depletion");
        }
        if(faultMode==0||target.getWurmId()!=faultDrill)return false;
        faultOutput=output.getWurmId();
        if(faultMode==1)return true;
        if(faultMode==2)throw new IllegalStateException("Injected native staging failure before a buffer link");
        if(faultMode==3) {
            // Native loading insertion creates the exact half-linked condition:
            // real item in the buffer set, but parent still has no inventory.
            ((DbItem)target).addItem(output,true);return true;
        }
        int saved=faultMode;faultMode=0;
        try {target.insertItem(output,true);}finally{faultMode=saved;}
        throw new IllegalStateException("Injected notification failure after native buffer commit");
    }
    private static void stagingFailures(Item drill,Item hopper)throws Exception {
        try {
            for(int mode=1;mode<=3;mode++) {
                wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);int before=hopper.getItems().size();
                faultDrill=drill.getWurmId();faultOutput=NO_ID;faultMode=mode;
                RailwayDrillMining.MiningResult result=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
                check(faultOutput>0,"native failure injection reaches an actual newly created staging item");
                check(!result.mined&&result.itemId==NO_ID&&result.reason.contains("before changing the face")&&result.reason.contains("cancelled"),
                        "pre-depletion staging refusal/exception cancels only an unearned factory artifact");
                check(Server.getCaveResource(X,Y-1)==9&&Zones.getMiningState(X,Y-1)==0,
                        "rejected native staging never decrements the vein or advances native rock state");
                check(!Items.getItemOptional(faultOutput).isPresent()&&drill.getItems().isEmpty()&&hopper.getItems().size()==before,
                        "failed staging leaves no orphan, partial buffer link, duplicate item or changed hopper inventory");
            }
            wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);int before=hopper.getItems().size();
            faultDrill=drill.getWurmId();faultOutput=NO_ID;faultMode=4;
            RailwayDrillMining.MiningResult committed=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
            check(committed.mined&&committed.reason==null&&committed.itemId==faultOutput&&Server.getCaveResource(X,Y-1)==8,
                    "a notification error after genuine buffer insertion still mines exactly one normal resource");
            Item ore=Items.getItem(faultOutput);created.add(ore);
            check(ore.getParentId()==hopper.getWurmId()&&hopper.getItems().size()==before+1&&drill.getItems().isEmpty(),
                    "post-insertion notification failure preserves and delivers the earned native item exactly once");
            for(int mode=5;mode<=6;mode++) {
                wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9,0);before=hopper.getItems().size();
                faultDrill=drill.getWurmId();faultHopper=hopper.getWurmId();faultOutput=NO_ID;faultMode=mode;
                RailwayDrillMining.MiningResult retained=RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId());
                check(retained.mined&&retained.reason!=null&&retained.reason.contains("Output retained")&&retained.itemId==faultOutput,
                        "a native hopper refusal/exception after preflight reports one completed action with retained output");
                Item earned=Items.getItem(faultOutput);created.add(earned);
                check(Server.getCaveResource(X,Y-1)==8&&earned.getParentId()==drill.getWurmId()&&drill.getItems().contains(earned)
                        &&earned.getAuxData()==0&&hopper.getItems().size()==before,
                        "post-depletion output failure conserves the exact earned item in the drill and consumes exactly one vein hit");
                faultMode=0;
                check(RailwayDrillMining.flushBuffered(drill,OWNER,hopper.getWurmId())==null&&earned.getParentId()==hopper.getWurmId()
                        &&Items.getItem(earned.getWurmId())==earned&&hopper.getItems().size()==before+1&&drill.getItems().isEmpty(),
                        "restarting output delivery moves the same conserved item after a native output failure");
                check(Server.getCaveResource(X,Y-1)==8,"retrying retained native output never consumes another mining action");
            }
        } finally {faultMode=0;faultDrill=NO_ID;faultHopper=NO_ID;faultOutput=NO_ID;}
    }
    private static void settlementPermissions(Item drill,Item hopper)throws Exception {
        com.wurmonline.server.zones.Zone zone=Zones.getZone(X,Y-1,false);
        java.lang.reflect.Field villages=com.wurmonline.server.zones.Zone.class.getDeclaredField("villages");villages.setAccessible(true);
        Object previous=villages.get(zone);
        com.wurmonline.server.villages.Village village=(com.wurmonline.server.villages.Village)allocate(Class.forName("com.wurmonline.server.villages.DbVillage"));
        com.wurmonline.server.villages.VillageRole owner=(com.wurmonline.server.villages.VillageRole)allocate(Class.forName("com.wurmonline.server.villages.DbVillageRole"));
        com.wurmonline.server.villages.VillageRole everybody=(com.wurmonline.server.villages.VillageRole)allocate(Class.forName("com.wurmonline.server.villages.DbVillageRole"));
        set(com.wurmonline.server.villages.VillageRole.class,owner,"moreSettings",new com.wurmonline.server.players.Permissions());
        set(com.wurmonline.server.villages.VillageRole.class,everybody,"moreSettings",new com.wurmonline.server.players.Permissions());
        set(com.wurmonline.server.villages.VillageRole.class,owner,"playerAppliedTo",OWNER);
        set(com.wurmonline.server.villages.Village.class,village,"everybody",everybody);
        set(com.wurmonline.server.villages.Village.class,village,"citizens",new HashMap<Long,com.wurmonline.server.villages.Citizen>());
        HashMap<Integer,com.wurmonline.server.villages.VillageRole> roles=new HashMap<Integer,com.wurmonline.server.villages.VillageRole>();roles.put(1,owner);
        set(com.wurmonline.server.villages.Village.class,village,"roles",roles);
        village.startx=X-2;village.endx=X+2;village.starty=Y-2;village.endy=Y+2;
        HashSet<com.wurmonline.server.villages.Village> set=new HashSet<com.wurmonline.server.villages.Village>();set.add(village);villages.set(zone,set);
        try {
            wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,7,0);
            check(!RailwayDrillMining.inspect(drill,OWNER,0).available&&!RailwayDrillMining.mine(drill,OWNER,0,hopper.getWurmId()).mined
                    &&Server.getCaveResource(X,Y-1)==7,"native settlement mining denial stops an offline owner's machine before depletion");
            owner.setCanMineIron(true);
            check(RailwayDrillMining.inspect(drill,OWNER,0).available,"the native owner-specific iron-mining role allows an offline machine");
            wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_COPPER.id,7,0);
            check(!RailwayDrillMining.inspect(drill,OWNER,0).available,"iron-only permission cannot mine another ore type");
            everybody.setCanMineOther(true);
            check(RailwayDrillMining.inspect(drill,OWNER,0).available,"the native everybody-role grant is honored alongside owner-specific permissions");
            wall(0,Tiles.Tile.TILE_CAVE_WALL.id,7,0);
            check(!RailwayDrillMining.inspect(drill,OWNER,0).available,"ore permission does not grant ordinary rock mining");
            owner.setCanMineRock(true);
            check(RailwayDrillMining.inspect(drill,OWNER,0).available,"the native owner rock-mining role independently permits normal rock");
        } finally {villages.set(zone,previous);}
    }
    private static Object allocate(Class<?> type)throws Exception {
        java.lang.reflect.Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }
    private static void set(Class<?> type,Object object,String name,Object value)throws Exception {
        java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void openFloor() { Server.caveMesh.setTile(X,Y,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50)); }
    private static void wall(int face,byte kind,int resource,int state) {
        Server.caveMesh.setTile(X+DX[face],Y+DY[face],Tiles.encode((short)20,kind,(byte)50));
        Server.setCaveResource(X+DX[face],Y+DY[face],resource);Zones.setMiningState(X+DX[face],Y+DY[face],(byte)state,false);
    }
    private static Item placed(int template,int x,int y,boolean surface)throws Exception {
        Item item=ItemFactory.createItem(template,65f,x*4+2,y*4+2,0,surface,ItemMaterials.MATERIAL_IRON,NO_ID,"Steam drill fixture");item.setLastOwnerId(OWNER);created.add(item);return item;
    }
    private static Item resource(int template)throws Exception { Item item=ItemFactory.createItem(template,50f,"Steam drill fixture");created.add(item);return item; }
    private static void check(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
