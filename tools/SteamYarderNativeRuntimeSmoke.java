import com.wurmonline.mesh.FoliageAge;
import com.wurmonline.mesh.GrassData;
import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.mesh.TreeData;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.zones.Zones;
import java.nio.file.Paths;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.SteamYarders.HarvestAge;

/** Actual tree meshes, native timber and inventories, isolated from any player world. */
public final class SteamYarderNativeRuntimeSmoke {
    private static final int X=192,Y=172;
    private static final long OWNER=998465791L,NO_ID=-10L;
    private static final List<Item> created=new ArrayList<Item>();
    private static int assertions,fault;
    private static long faultMachine=NO_ID,faultBunk=NO_ID;
    private SteamYarderNativeRuntimeSmoke() { }

    public static void run()throws Exception {
        MeshIO resource=Server.resourceMesh;boolean[][] protectedBefore=Zones.protectedTiles;
        java.lang.reflect.Field flagsField=Server.class.getDeclaredField("flagsMesh");flagsField.setAccessible(true);Object flagsBefore=flagsField.get(null);
        MeshIO flags=MeshIO.createMap(Paths.get("steam_yarder_flags.map").toAbsolutePath().toString(),8,new int[256*256]);flagsField.set(null,flags);
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        int[] surface=new int[169],rock=new int[169];
        for(int at=0;at<169;at++){surface[at]=Server.surfaceMesh.getTile(X-6+at%13,Y-6+at/13);rock[at]=Server.rockMesh.getTile(X-6+at%13,Y-6+at/13);}
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            int[] resources=new int[256*256];Arrays.fill(resources,0x1234ffff);
            Server.resourceMesh=MeshIO.createMap(Paths.get("steam_yarder_resources.map").toAbsolutePath().toString(),8,resources);
            Zones.protectedTiles=new boolean[256][256];
            Item machine=placed(RailwaySteamYarder.templateId,X,Y);
            Item bunk=placed(RailwayLogBunk.templateId,X-1,Y-1);
            check(machine.isHollow()&&machine.getMaxItemCount()==1,"the actual Yarder provides one persistent native timber staging slot");
            nativeTimber(machine,bunk);
            boundsAndGrowth(machine,bunk);
            safety(machine,bunk);
            permissions(machine);
            nativeFailures(machine,bunk);
            System.out.println("PASS native Steam Yarder forestry: species/age/material/weight/QL, real native timber and persistent parents, circular bounds and untouched young trees, replanting without growth acceleration, protected/deed/rail/wet/rock exclusions, full-storage preflight and exact-output recovery ("+assertions+" assertions)");
        } finally {
            fault=0;
            for(int at=created.size()-1;at>=0;at--)if(Items.getItemOptional(created.get(at).getWurmId()).isPresent())Items.destroyItem(created.get(at).getWurmId());
            for(int at=0;at<169;at++){Server.surfaceMesh.setTile(X-6+at%13,Y-6+at/13,surface[at]);Server.rockMesh.setTile(X-6+at%13,Y-6+at/13,rock[at]);}
            Server.resourceMesh.close();Server.resourceMesh=resource;Zones.protectedTiles=protectedBefore;
            flags.close();flagsField.set(null,flagsBefore);
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void nativeTimber(Item machine,Item bunk)throws Exception {
        check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwaySteamYarder.templateId)!=null,"the actual Steam Yarder has its native player construction recipe");
        check(RailModelRegistry.isCustomTemplate(RailwaySteamYarder.templateId)
                &&"model.wurmrail.steamyarder.".equals(RailModelRegistry.modelName(RailwaySteamYarder.templateId,(byte)0,0))
                &&"model.wurmrail.steamyarder.".equals(machine.getModelName()),"native item model and discovery-safe model registry both identify the custom Yarder artwork");
        check(RailwayYarderWorld.speciesChoices().size()==TreeData.TreeType.values().length,"all native tree species are offered through stable native IDs");
        for(TreeData.TreeType tree:TreeData.TreeType.values()) {
            clear(bunk);tree(X+1,Y,tree,FoliageAge.OLD_ONE,false,true);
            Server.setWorldResource(X+1,Y,45);
            RailwayYarderWorld.HarvestResult result=RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,TreeData.TreeType.FIR.getTypeId(),HarvestAge.OLD,bunk.getWurmId());
            check(result.harvested&&result.reason==null&&result.itemId>0,"a native species harvest finishes and delivers its real output: "+tree.getName());
            Item timber=Items.getItem(result.itemId);created.add(timber);
            int template=tree.isFruitTree()?ItemList.log:ItemList.logHuge;
            int weight=(int)Math.max(1000,8.0/15*ItemTemplateFactory.getInstance().getTemplate(template).getWeightGrams()*(tree.isFruitTree()?.25:1));
            check(timber.getTemplateId()==template&&timber.getMaterial()==tree.getMaterial()&&timber.getWeightGrams()==weight,"native felling template, material and age/species size are preserved for "+tree.getName()+": expected "+template+"/"+tree.getMaterial()+"/"+weight+", actual "+timber.getTemplateId()+"/"+timber.getMaterial()+"/"+timber.getWeightGrams());
            check(timber.getQualityLevel()>=1&&timber.getQualityLevel()<=machine.getCurrentQualityLevel()&&LogBunkPolicy.eligible(timber),"native skill-proxy quality is bounded by machine quality and creates valid timber");
            check(timber.getRarity()==0&&timber.getAuxData()==(template==ItemList.logHuge?8:0),"tree material never becomes rarity and committed felled timber restores its native age aux data");
            check(timber.getParentId()==bunk.getWurmId()&&bunk.getItems().contains(timber)&&machine.getItems().isEmpty(),"the exact staged item ID reaches the Log Bunk with one parent");
            DbItem reloaded=new DbItem(timber.getWurmId());
            check(reloaded.getParentId()==bunk.getWurmId()&&reloaded.getMaterial()==tree.getMaterial()&&reloaded.getWeightGrams()==weight,"actual native database preserves the output ID, properties and storage parent");
            int planted=Server.surfaceMesh.getTile(X+1,Y);
            check(Tiles.getTile(Tiles.decodeType(planted)).getTreeType(Tiles.decodeData(planted))==TreeData.TreeType.FIR
                    &&FoliageAge.getAgeAsByte(Tiles.decodeData(planted))==0&&TreeData.isCentre(Tiles.decodeData(planted)),"only the harvested tile is replanted with the selected species as a young-one native tree");
            check(Server.getWorldResource(X+1,Y)==0&&(Server.resourceMesh.getTile(X+1,Y)>>>16)==0x1234,"native tree damage resets while the packed cave resource remains unchanged");
            check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"the replacement sprout is not immediately harvestable");
        }
        clear(bunk);tree(X,Y+1,TreeData.TreeType.PINE,FoliageAge.MATURE_ONE,true,false);
        RailwayYarderWorld.HarvestResult mature=RailwayYarderWorld.harvest(machine,OWNER,X,Y+1,TreeData.TreeType.CEDAR.getTypeId(),HarvestAge.MATURE,bunk.getWurmId());
        check(mature.harvested&&Items.getItem(mature.itemId).getTemplateId()==ItemList.log,"mature nonfruit trees produce normal native logs");created.add(Items.getItem(mature.itemId));
        int planted=Server.surfaceMesh.getTile(X,Y+1);
        check(Tiles.getTile(Tiles.decodeType(planted)).isMycelium()&&!TreeData.isCentre(Tiles.decodeData(planted)),"replanting retains mycelium ground and original tree positioning mode");
        clear(bunk);tree(X+1,Y,TreeData.TreeType.OAK,FoliageAge.SHRIVELLED,false,true);
        RailwayYarderWorld.HarvestResult dead=RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.OVERAGED,bunk.getWurmId());
        check(dead.harvested&&Items.getItem(dead.itemId).getTemplateId()==ItemList.log,"shrivelled trees use native reduced-age log output");created.add(Items.getItem(dead.itemId));
        tree(X+1,Y,TreeData.TreeType.APPLE,FoliageAge.SHRIVELLED,false,true);int smallDead=Server.surfaceMesh.getTile(X+1,Y);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.OVERAGED,bunk.getWurmId()).harvested
                &&Server.surfaceMesh.getTile(X+1,Y)==smallDead,"shrivelled fruit trees that natively yield kindling are left intact rather than inventing a log");
    }
    private static void boundsAndGrowth(Item machine,Item bunk)throws Exception {
        clear(bunk);
        for(int dx=-6;dx<=6;dx++)for(int dy=-6;dy<=6;dy++)tree(X+dx,Y+dy,TreeData.TreeType.PINE,FoliageAge.YOUNG_FOUR,false,true);
        tree(X,Y,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);
        check(!RailwayYarderWorld.inspect(machine,OWNER,X,Y,HarvestAge.MATURE).available,"machine tile is never harvested");
        int before=Server.surfaceMesh.getTile(X+1,Y);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId()).harvested
                &&Server.surfaceMesh.getTile(X+1,Y)==before,"young trees remain unchanged, with no artificial age advancement");
        tree(X+2,Y+2,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);
        check(RailwayYarderWorld.inspect(machine,OWNER,X+2,Y+2,HarvestAge.OVERAGED).available,"diagonal tile centre inside the circle is managed");
        tree(X+5,Y+1,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);before=Server.surfaceMesh.getTile(X+5,Y+1);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+5,Y+1,0,HarvestAge.MATURE,bunk.getWurmId()).harvested&&Server.surfaceMesh.getTile(X+5,Y+1)==before,"the square's corner outside the radius is never harvested");
        tree(X+4,Y+4,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);before=Server.surfaceMesh.getTile(X+4,Y+4);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+4,Y+4,0,HarvestAge.MATURE,bunk.getWurmId()).harvested&&Server.surfaceMesh.getTile(X+4,Y+4)==before,"a fully diagonal four-by-four corner remains outside the circular woodlot");
        tree(X+5,Y,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);
        check(RailwayYarderWorld.inspect(machine,OWNER,X+5,Y,HarvestAge.OVERAGED).available,"the exact cardinal radius boundary is included");
        tree(X+4,Y+3,TreeData.TreeType.OAK,FoliageAge.OVERAGED,false,true);
        check(RailwayYarderWorld.harvest(machine,OWNER,X+4,Y+3,0,HarvestAge.OVERAGED,bunk.getWurmId()).harvested
                &&FoliageAge.getAgeAsByte(Tiles.decodeData(Server.surfaceMesh.getTile(X+4,Y+3)))==0,"newly included four-three boundary harvests and replants normally without extending beyond radius five");
        Server.surfaceMesh.setTile(X+1,Y,Tiles.encode((short)50,Tiles.Tile.TILE_GRASS.id,(byte)0));before=Server.surfaceMesh.getTile(X+1,Y);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId()).harvested&&Server.surfaceMesh.getTile(X+1,Y)==before,"bare ground is not silently afforested");
        FoliageAge[] ages={FoliageAge.MATURE_ONE,FoliageAge.OLD_ONE,FoliageAge.VERY_OLD,FoliageAge.OVERAGED};
        for(int threshold=0;threshold<ages.length;threshold++)for(int age=0;age<ages.length;age++) {
            tree(X+1,Y,TreeData.TreeType.PINE,ages[age],false,true);
            check(RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.values()[threshold]).available==(age>=threshold),"all four harvest thresholds follow the native foliage age ordering");
        }
    }
    private static void safety(Item machine,Item bunk)throws Exception {
        clear(bunk);tree(X+1,Y,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);int before=Server.surfaceMesh.getTile(X+1,Y);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,0).harvested&&Server.surfaceMesh.getTile(X+1,Y)==before,"missing output storage cannot mutate a tree");
        Item ballast=ItemFactory.createItem(ItemList.log,40,"Yarder capacity fixture");created.add(ballast);ballast.setWeight((int)LogBunkPolicy.MAX_WEIGHT_GRAMS,true);bunk.insertItem(ballast,true);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId()).harvested&&Server.surfaceMesh.getTile(X+1,Y)==before&&machine.getItems().isEmpty(),"full storage is rejected before staging or cutting the tree");
        clear(bunk);Zones.protectedTiles[X+1][Y]=true;
        check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"god-protected tree is excluded");Zones.protectedTiles[X+1][Y]=false;
        java.lang.reflect.Field rails=RailVegetationHooks.class.getDeclaredField("surfaceRails");rails.setAccessible(true);Object previous=rails.get(null);
        try { rails.set(null,Collections.singleton(RailVegetationHooks.tileKey(X+1,Y)));check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"railway right of way is excluded from the managed woodlot"); } finally { rails.set(null,previous); }
        Server.surfaceMesh.setTile(X+1,Y,Tiles.encode((short)50,TreeData.TreeType.PINE.asEnchantedTree(),Tiles.encodeTreeData(FoliageAge.OVERAGED,false,true,GrassData.GrowthTreeStage.SHORT)));
        check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"enchanted trees are not automatically destroyed");
        tree(X+1,Y,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);
        for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)Server.rockMesh.setTile(X+1+dx,Y+dy,Tiles.encode((short)50,Tiles.Tile.TILE_ROCK.id,(byte)0));
        check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"a tree with exposed rock beneath every corner is skipped because it cannot be replanted");
        for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)Server.rockMesh.setTile(X+1+dx,Y+dy,Tiles.encode((short)40,Tiles.Tile.TILE_ROCK.id,(byte)0));
        Server.surfaceMesh.setTile(X+2,Y,Tiles.encode((short)-1,Tiles.Tile.TILE_GRASS.id,(byte)0));
        check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"an underwater replant corner prevents harvesting");
        tree(X+2,Y,TreeData.TreeType.PINE,FoliageAge.OVERAGED,false,true);
        check(!RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,255,HarvestAge.MATURE,bunk.getWurmId()).harvested,"invalid persisted species cannot create a replacement tree");
    }
    private static void permissions(Item machine)throws Exception {
        com.wurmonline.server.zones.Zone zone=Zones.getZone(X,Y,true);
        java.lang.reflect.Field villages=com.wurmonline.server.zones.Zone.class.getDeclaredField("villages");villages.setAccessible(true);Object previous=villages.get(zone);
        com.wurmonline.server.villages.Village village=(com.wurmonline.server.villages.Village)allocate(Class.forName("com.wurmonline.server.villages.DbVillage"));
        com.wurmonline.server.villages.VillageRole role=(com.wurmonline.server.villages.VillageRole)allocate(Class.forName("com.wurmonline.server.villages.DbVillageRole"));
        com.wurmonline.server.villages.VillageRole everybody=(com.wurmonline.server.villages.VillageRole)allocate(Class.forName("com.wurmonline.server.villages.DbVillageRole"));
        set(com.wurmonline.server.villages.VillageRole.class,role,"moreSettings",new com.wurmonline.server.players.Permissions());set(com.wurmonline.server.villages.VillageRole.class,everybody,"moreSettings",new com.wurmonline.server.players.Permissions());
        set(com.wurmonline.server.villages.VillageRole.class,role,"settings",new com.wurmonline.server.players.Permissions());set(com.wurmonline.server.villages.VillageRole.class,everybody,"settings",new com.wurmonline.server.players.Permissions());
        set(com.wurmonline.server.villages.VillageRole.class,role,"extraSettings",new com.wurmonline.server.players.Permissions());set(com.wurmonline.server.villages.VillageRole.class,everybody,"extraSettings",new com.wurmonline.server.players.Permissions());
        set(com.wurmonline.server.villages.VillageRole.class,role,"playerAppliedTo",OWNER);set(com.wurmonline.server.villages.Village.class,village,"everybody",everybody);
        set(com.wurmonline.server.villages.Village.class,village,"citizens",new HashMap<Long,com.wurmonline.server.villages.Citizen>());
        HashMap<Integer,com.wurmonline.server.villages.VillageRole> roles=new HashMap<Integer,com.wurmonline.server.villages.VillageRole>();roles.put(1,role);set(com.wurmonline.server.villages.Village.class,village,"roles",roles);
        village.startx=X-3;village.endx=X+3;village.starty=Y-3;village.endy=Y+3;villages.set(zone,new HashSet<com.wurmonline.server.villages.Village>(Arrays.asList(village)));
        try {
            tree(X+1,Y,TreeData.TreeType.PINE,FoliageAge.OLD_ONE,false,true);
            check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"offline owner-specific settlement forestry denial is enforced");
            role.setCanChopDownAllTrees(true);
            check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"cutting permission alone cannot authorize replanting");
            everybody.setCanPlantSprouts(true);
            check(RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"native everybody replant grant combines with the offline owner's cutting grant");
            tree(X+1,Y,TreeData.TreeType.PINE,FoliageAge.VERY_OLD,false,true);
            check(!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"native very-old tree permission is independent from all-tree permission");
            role.setCanChopDownOldTrees(true);
            check(RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"native old-tree permission allows very-old forestry");
            java.lang.reflect.Field globalVillages=com.wurmonline.server.villages.Villages.class.getDeclaredField("villages");globalVillages.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Integer,com.wurmonline.server.villages.Village> globals=(Map<Integer,com.wurmonline.server.villages.Village>)globalVillages.get(null);
            int fixtureKey=2147481200;com.wurmonline.server.villages.Village prior=globals.put(fixtureKey,village);
            try {
                village.startx=X+4;village.endx=X+5;role.setCanChopDownOldTrees(false);
                check(Zones.getVillage(X+1,Y,true)==null&&!RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"an offline owner's denied forestry is also blocked inside a neighbouring deed perimeter");
                role.setCanChopDownOldTrees(true);
                check(RailwayYarderWorld.inspect(machine,OWNER,X+1,Y,HarvestAge.MATURE).available,"explicit native forestry and planting grants allow work within that perimeter");
            } finally { if(prior==null)globals.remove(fixtureKey);else globals.put(fixtureKey,prior); }
        } finally {villages.set(zone,previous);}
    }
    private static void nativeFailures(Item machine,Item bunk)throws Exception {
        clear(bunk);tree(X+1,Y,TreeData.TreeType.OAK,FoliageAge.OVERAGED,false,true);int before=Server.surfaceMesh.getTile(X+1,Y);
        faultMachine=machine.getWurmId();faultBunk=bunk.getWurmId();fault=1;
        RailwayYarderWorld.HarvestResult refused=RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId());
        check(!refused.harvested&&refused.itemId==NO_ID&&Server.surfaceMesh.getTile(X+1,Y)==before&&machine.getItems().isEmpty()&&bunk.getItems().isEmpty(),"a native machine insertion refusal cancels its unearned artifact and leaves the tree unchanged");
        fault=2;
        RailwayYarderWorld.HarvestResult retained=RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId());
        check(retained.harvested&&retained.reason!=null&&retained.itemId>0&&machine.getItems().size()==1&&bunk.getItems().isEmpty(),"post-harvest storage refusal retains the exact real timber safely inside the Yarder");
        Item timber=Items.getItem(retained.itemId);created.add(timber);DbItem reloaded=new DbItem(timber.getWurmId());
        check(timber.getParentId()==machine.getWurmId()&&reloaded.getParentId()==machine.getWurmId()&&timber.getAuxData()!=99,"committed native buffer contents persist and remain recoverable");
        int sprout=Server.surfaceMesh.getTile(X+1,Y);fault=0;
        check(RailwayYarderWorld.flushBuffered(machine,OWNER,bunk.getWurmId())==null&&bunk.getItems().contains(timber)&&machine.getItems().isEmpty()&&Server.surfaceMesh.getTile(X+1,Y)==sprout,"retry delivers the same item once without harvesting or modifying another tree");
        clear(bunk);tree(X+1,Y,TreeData.TreeType.OAK,FoliageAge.OVERAGED,false,true);fault=3;
        RailwayYarderWorld.HarvestResult uncertain=RailwayYarderWorld.harvest(machine,OWNER,X+1,Y,0,HarvestAge.MATURE,bunk.getWurmId());
        check(!uncertain.harvested&&uncertain.reason!=null&&uncertain.itemId>0&&machine.getItems().size()==1&&bunk.getItems().isEmpty(),"an exception after native replant/resource write retains its real timber and stops the machine");
        Item unconfirmed=Items.getItem(uncertain.itemId);created.add(unconfirmed);
        check(unconfirmed.getAuxData()==99&&new DbItem(unconfirmed.getWurmId()).getAuxData()==99
                &&FoliageAge.getAgeAsByte(Tiles.decodeData(Server.surfaceMesh.getTile(X+1,Y)))==0,"uncertain tree commit has a durable quarantine marker alongside the actual new sprout");
        check(RailwayYarderWorld.flushBuffered(machine,OWNER,bunk.getWurmId())!=null&&machine.getItems().contains(unconfirmed)&&bunk.getItems().isEmpty(),"unconfirmed native harvest output cannot be delivered or duplicated by retrying");
        machine.dropItem(unconfirmed.getWurmId(),false);Items.destroyItem(unconfirmed.getWurmId());
        clear(bunk);Item quarantined=ItemFactory.createItem(ItemList.log,40,"Yarder unconfirmed fixture");created.add(quarantined);quarantined.setAuxData((byte)99);machine.insertItem(quarantined,true);
        check(RailwayYarderWorld.flushBuffered(machine,OWNER,bunk.getWurmId())!=null&&machine.getItems().contains(quarantined)&&bunk.getItems().isEmpty(),"unconfirmed staged timber is never delivered as an earned harvest");
        machine.dropItem(quarantined.getWurmId(),false);Items.destroyItem(quarantined.getWurmId());faultMachine=NO_ID;faultBunk=NO_ID;
    }
    /** Fixture-only injection installed before native Item loads; production has no test flags. */
    public static boolean rejectInsertion(Item parent,Item child) { return fault==1&&parent.getWurmId()==faultMachine||fault==2&&parent.getWurmId()==faultBunk; }
    public static void afterTreeWrite(int x,int y,int resource) {
        if(fault==3&&x==X+1&&y==Y&&resource==0){fault=0;throw new IllegalStateException("Yarder native fixture: notification failure after tree/resource commit");}
    }
    private static void tree(int x,int y,TreeData.TreeType tree,FoliageAge age,boolean mycelium,boolean centre) { Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,mycelium?tree.asMyceliumTree():tree.asNormalTree(),Tiles.encodeTreeData(age,false,centre,GrassData.GrowthTreeStage.SHORT))); }
    private static Item placed(int template,int x,int y)throws Exception { Item item=ItemFactory.createItem(template,65f,x*4+2,y*4+2,0,true,(byte)0,NO_ID,"Steam Yarder fixture");item.setLastOwnerId(OWNER);created.add(item);return item; }
    private static void clear(Item container)throws Exception { for(Item item:new ArrayList<Item>(container.getItems())){container.dropItem(item.getWurmId(),false);Items.destroyItem(item.getWurmId());} }
    private static Object allocate(Class<?> type)throws Exception { java.lang.reflect.Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type); }
    private static void set(Class<?> type,Object object,String name,Object value)throws Exception { java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value); }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
