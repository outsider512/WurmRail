import com.wurmonline.server.Items;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.FreightStops.*;
import org.wurmrail.core.RailNetwork.*;

/** Real native storage, insertion paths and freight inventories; no virtual timber resource values. */
public final class LogBunkRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private LogBunkRuntimeSmoke() { }
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998462101L,true,2);
        List<Item> created=new ArrayList<Item>();
        boolean scheduledParents=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            Item bunk=bunk(actor,154*4+2,166*4+2,created);
            check(bunk.isHollow()&&!bunk.isBulkContainer()&&!bunk.isBulkItem()&&bunk.isNoTake(),
                    "the placeable bunk is a real hollow item inventory, never a virtual native bulk bin");
            check(bunk.getContainerVolume()==LogBunkPolicy.MAX_VOLUME_CC&&bunk.getMaxItemCount()==LogBunkPolicy.MAX_ITEMS,
                    "native container metadata agrees with the industrial policy limits");
            check(bunk.getContainerVolume()>item(ItemList.chestLarge,created).getContainerVolume()*20,
                    "industrial storage has substantially more real volume than a normal chest");
            check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwayLogBunk.templateId)!=null,
                    "the bunk is available through ordinary player construction");

            for(int template:LogBunkPolicy.allowedTemplateIds()) {
                Item raw=item(template,created);
                check(!raw.isFood()&&!raw.isLiquid(),"whitelisted timber is neither food nor liquid: "+raw.getName());
                check(bunk.testInsertItem(raw)&&bunk.canHold(raw)&&bunk.insertItem(raw,false,true),
                        "approved timber passes actual native inventory gates: "+raw.getName());
                check(raw.getParentId()==bunk.getWurmId()&&bunk.getItems().contains(raw),"approved item retains its actual native parent link");
            }
            RailwayLogBunk.View status=RailwayLogBunk.snapshot(bunk);
            check(status.itemCount==LogBunkPolicy.allowedTemplateIds().size()&&status.weightGrams==LogBunkPolicy.storedWeightGrams(bunk),
                    "inspect reports all real stored resource rows and weight");
            check(status.remainingWeightGrams+status.weightGrams==LogBunkPolicy.MAX_WEIGHT_GRAMS
                    &&status.remainingVolume+status.volume==LogBunkPolicy.MAX_VOLUME_CC,"inspect reports remaining native weight and volume capacity");
            bunk.setName("Bunk '};button{id='injected';text='bad'}");
            check(!RailwayLogBunk.content(RailwayLogBunk.snapshot(bunk)).contains("id='injected'"),"inspection safely escapes hostile item names");

            for(int template:new int[]{ItemList.hatchet,ItemList.swordLong,ItemList.plateJacket,ItemList.ironBand,
                    ItemList.ironBar,ItemList.backPack,ItemList.barrelSmall,ItemList.crateSmall,
                    ItemList.meat,ItemList.water,ItemList.ironOre,ItemList.dirtPile,ItemList.plank}) {
                Item bad=item(template,created);long parent=bad.getParentId();
                check(!bunk.testInsertItem(bad)&&!bunk.canHold(bad)&&!bunk.itemCanBeInserted(bad),"non-timber template is rejected by all native read gates: "+bad.getName());
                check(!bunk.insertItem(bad)&&!bunk.insertItem(bad,true)&&!bunk.insertItem(bad,false,true)
                        &&!bunk.insertItem(bad,true,true,true),"even unconditional and placed insertion cannot bypass the whitelist");
                check(bad.getParentId()==parent&&!bunk.getItems().contains(bad)&&Items.getItem(bad.getWurmId())==bad,
                        "rejection conserves the exact item and original inventory parent");
                boolean rejected=false;try{((DbItem)bunk).addItem(bad,false);}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected&&!bunk.getItems().contains(bad)&&bad.getParentId()==parent,"new low-level native addItem cannot bypass the whitelist");
            }
            playerMove(actor,bunk,created);
            capacity(actor,created);
            mutations(actor,created);
            freight(actor,created);
            System.out.println("PASS native Log Bunk: exact felled-tree/log whitelist, insertion and parent-mutation gates, player moves, industrial weight/volume/count, real item properties and database parent reload, inspect and timed freight LOAD/UNLOAD ("+assertions+" assertions)");
        } finally {
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduledParents;
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
        }
    }
    private static void playerMove(TrackMaterialsSmoke.InventoryActor actor,Item bunk,List<Item> created) throws Exception {
        actor.getStatus().getPosition().setPosX(bunk.getPosX());actor.getStatus().getPosition().setPosY(bunk.getPosY());
        // This inventory actor normally only exercises insertion. Native
        // moveToItem also requires the real body and possessions it checks.
        Object human=allocate(Class.forName("com.wurmonline.server.creatures.DbCreatureTemplate"));
        field(com.wurmonline.server.creatures.CreatureTemplate.class,human,"id",com.wurmonline.server.creatures.CreatureTemplateIds.HUMAN_CID);
        field(com.wurmonline.server.creatures.CreatureTemplate.class,human,"name","human");
        field(com.wurmonline.server.creatures.Creature.class,actor,"template",human);
        field(com.wurmonline.server.creatures.CreatureStatus.class,actor.getStatus(),"body",com.wurmonline.server.bodys.BodyFactory.getBody(
                actor,com.wurmonline.server.bodys.BodyTemplate.TYPE_HUMAN,(short)180,(short)40,(short)40));
        actor.getBody().createBodyParts();
        Object possessions=allocate(com.wurmonline.server.items.Possessions.class);
        field(com.wurmonline.server.items.Possessions.class,possessions,"inventory",actor.getInventory());
        field(com.wurmonline.server.items.Possessions.class,possessions,"owner",actor);
        field(com.wurmonline.server.creatures.Creature.class,actor,"possessions",possessions);
        Item ore=item(ItemList.log,created);ore.setQualityLevel(73f);ore.setDamage(4f);ore.setColor(0x135724);ore.setRarity((byte)1);
        check(actor.getInventory().insertItem(ore,true),"the actual actor holds an timber item before loading");
        check(ore.moveToItem(actor,bunk.getWurmId(),false)&&ore.getParentId()==bunk.getWurmId(),"normal player moveToItem loads approved timber through native permissions and parent operations");
        check(ore.getQualityLevel()==73f&&ore.getDamage()==4f&&ore.getColor()==0x135724&&ore.getRarity()==1
                &&Items.getItem(ore.getWurmId())==ore,"storage preserves quality, damage, color, rarity and exact identity");
        DbItem loaded=new DbItem(ore.getWurmId());
        check(loaded.getParentId()==bunk.getWurmId()&&loaded.getTemplateId()==ItemList.log
                &&loaded.getWeightGrams()==ore.getWeightGrams(),"native database reload retains the real item and persistent bunk parent");
        Item tool=item(ItemList.hatchet,created);check(actor.getInventory().insertItem(tool,true),"the actual actor holds the rejected tool");
        check(!tool.moveToItem(actor,bunk.getWurmId(),false)&&tool.getParentId()==actor.getInventory().getWurmId()
                &&actor.getInventory().getItems().contains(tool),"a rejected normal player move cannot detach or orphan its source item");
        Item ordinary=item(ItemList.chestLarge,created),ordinaryTool=item(ItemList.hatchet,created);
        check(ordinary.insertItem(ordinaryTool,false,true),"ordinary Wurm containers retain their original unrestricted cargo behavior");
    }
    private static void capacity(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        Item weightBunk=bunk(actor,154*4+2,170*4+2,created),heavy=item(ItemList.log,created),extra=item(ItemList.log,created);
        heavy.setWeight((int)LogBunkPolicy.MAX_WEIGHT_GRAMS,false);heavy.setSizes(1,1,1);extra.setWeight(1,false);extra.setSizes(1,1,1);
        check(weightBunk.insertItem(heavy,false,true)&&LogBunkPolicy.remainingWeightGrams(weightBunk)==0,"the exact industrial weight boundary is usable");
        check(!weightBunk.insertItem(extra,true)&&extra.getParentId()==NO_ID,"the weight boundary also applies to unconditional insertions");
        Item volumeBunk=bunk(actor,158*4+2,170*4+2,created),large=item(ItemList.logHuge,created);
        long nativeVolume=large.getVolume();int fit=(int)(LogBunkPolicy.MAX_VOLUME_CC/nativeVolume);
        check(fit>0&&fit<LogBunkPolicy.MAX_ITEMS,"volume test reaches native storage volume before the item-count limit");
        for(int i=0;i<fit;i++)check(volumeBunk.insertItem(i==0?large:item(ItemList.logHuge,created),false,true),"native resource volume fills the industrial bunk up to its real fit boundary");
        Item oversize=item(ItemList.logHuge,created);
        check(!volumeBunk.insertItem(oversize,true)&&oversize.getParentId()==NO_ID
                &&LogBunkPolicy.remainingVolume(volumeBunk)<nativeVolume,"native volume remains bounded even when inserted unconditionally");
        Item countBunk=bunk(actor,162*4+2,170*4+2,created);
        for(int i=0;i<LogBunkPolicy.MAX_ITEMS;i++) {
            Item grain=item(ItemList.log,created);grain.setWeight(1,false);grain.setSizes(1,1,1);
            check(countBunk.insertItem(grain,false,true),"real bunk item capacity beyond the ordinary one hundred item limit");
        }
        check(countBunk.getItems().size()==LogBunkPolicy.MAX_ITEMS&&!countBunk.mayCreatureInsertItem()
                &&!countBunk.insertItem(extra,true),"the bounded 4096-item native bunk limit is enforced");
    }
    private static void mutations(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        Item bunk=bunk(actor,150*4+2,170*4+2,created),log=item(ItemList.log,created),other=item(ItemList.logHuge,created);
        log.setWeight(50000000,false);other.setWeight(50000000,false);
        check(!log.isCombine()&&!other.isCombine(),"native logs and felled trees cannot enter the native partial-combine path");
        check(bunk.insertItem(log,false,true)&&bunk.insertItem(other,false,true),"real timber fills the weight boundary");
        log.setWeight(50000001,false);
        check(log.getWeightGrams()==50000000&&LogBunkPolicy.remainingWeightGrams(bunk)==0,"in-place native growth cannot exceed the weight boundary");
        log.setWeight(49999999,false);
        check(log.getWeightGrams()==49999999,"ordinary Wurm timber consumption and weight reduction remains allowed");
        boolean rejected=false;try{log.setTemplateId(ItemList.plank);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&log.getTemplateId()==ItemList.log&&log.getParentId()==bunk.getWurmId(),"native template changes cannot turn stored logs into unrelated crafted freight");
        other.setTemplateId(ItemList.log);
        check(other.getTemplateId()==ItemList.log&&other.getParentId()==bunk.getWurmId(),"normal felled-tree to log conversion preserves the storage parent");
        int x=log.getSizeX(),y=log.getSizeY(),z=log.getSizeZ();
        rejected=false;try{log.setSizes(500,500,500);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&log.getSizeX()==x&&log.getSizeY()==y&&log.getSizeZ()==z,"over-capacity native resizing is rejected before any dimension changes");
        Item pending=item(ItemList.logHuge,created);pending.setAuxData((byte)99);
        check(!LogBunkPolicy.eligible(pending)&&!bunk.insertItem(pending,true),"unconfirmed forestry output stays quarantined in the machine safety compartment");
        pending.setAuxData((byte)12);check(LogBunkPolicy.eligible(pending),"normal native felled-tree age metadata remains eligible");
    }
    private static void freight(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        RailNetwork net=new RailNetwork();
        for(int i=0;i<7;i++){Node rail=net.addNode("bunkRail"+i,154+i,176,0);Item marker=WurmRailWorld.spawnTrackMarker(actor,rail.worldX(),rail.worldY(),0);created.add(marker);rail.markerId=marker.getWurmId();if(i>0)net.connect("bunkRail"+(i-1),rail.name);}
        Node stopRail=net.nodes.get("bunkRail3");Item source=bunk(actor,stopRail.worldX(),stopRail.worldY()-4,created),destination=bunk(actor,stopRail.worldX(),stopRail.worldY()+4,created);
        Item engine=WurmRailWorld.spawnLocomotive(actor,stopRail.worldX(),stopRail.worldY(),0),wagon=WurmRailWorld.spawnCart(actor,stopRail.worldX()-4,stopRail.worldY(),0);created.add(engine);created.add(wagon);
        Train train=net.createTrain("BunkFreight",actor.getWurmId(),Arrays.asList(engine.getWurmId(),wagon.getWurmId()),stopRail.name,"bunkRail4");
        for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);
        actor.getStatus().getPosition().setPosX(stopRail.worldX());actor.getStatus().getPosition().setPosY(stopRail.worldY());
        java.lang.reflect.Method make=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);make.setAccessible(true);
        com.wurmonline.server.players.Player owner=(com.wurmonline.server.players.Player)make.invoke(null,998463104L,stopRail.worldX(),stopRail.worldY());
        java.lang.reflect.Field power=com.wurmonline.server.players.PlayerInfo.class.getDeclaredField("power");power.setAccessible(true);power.setByte(owner.getSaveFile(),(byte)0);
        source.setLastOwnerId(owner.getWurmId());destination.setLastOwnerId(owner.getWurmId());engine.setLastOwnerId(owner.getWurmId());wagon.setLastOwnerId(owner.getWurmId());
        net.removeTrain(train.name);train=net.createTrain("BunkFreight",owner.getWurmId(),Arrays.asList(engine.getWurmId(),wagon.getWurmId()),stopRail.name,"bunkRail4");
        RailwayFreightWorld.validateStorage(owner,stopRail,source);RailwayFreightWorld.validateStorage(owner,stopRail,destination);
        check(owner.getPower()==0&&RailwayFreightWorld.nearbyStorage(owner,stopRail).contains(source),"an ordinary non-GM owner can discover and configure a fixed bunk without TAKE-ing the whole object");
        Item ore=item(ItemList.log,created);ore.setWeight(5000,false);ore.setQualityLevel(81f);check(source.insertItem(ore,false,true),"real ore enters the freight source bunk");
        RailwayFreightWorld world=new RailwayFreightWorld(net);
        Stop load=new Stop(stopRail.name,stopRail.markerId,owner.getWurmId(),1,"Bunk loader",Operation.LOAD,source.getWurmId(),0,100);
        FreightTransfers.Session loading=new FreightTransfers.Session(load);
        FreightTransfers.advance(loading,.01,world.inventory(train,load));check(ore.getParentId()==source.getWurmId(),"bunk loading still honors earned freight weight allowance");
        FreightTransfers.advance(loading,.05,world.inventory(train,load));check(ore.getParentId()==wagon.getWurmId()&&!source.getItems().contains(ore),"native freight moves the exact bunk item into an existing cargo wagon");
        Stop unload=new Stop(stopRail.name,stopRail.markerId,owner.getWurmId(),2,"Bunk unloader",Operation.UNLOAD,destination.getWurmId(),0,100);
        FreightTransfers.Session unloading=new FreightTransfers.Session(unload);
        FreightTransfers.advance(unloading,1,world.inventory(train,unload));
        check(ore.getParentId()==destination.getWurmId()&&!wagon.getItems().contains(ore)&&ore.getQualityLevel()==81f&&Items.getItem(ore.getWurmId())==ore,
                "native freight unloads the same unchanged real item into its destination bunk");
        Item nonbulk=item(ItemList.ironBar,created);check(wagon.insertItem(nonbulk,false,true),"generic cargo wagon may hold non-timber freight");
        FreightTransfers.Session rejected=new FreightTransfers.Session(unload);FreightTransfers.advance(rejected,1,world.inventory(train,unload));
        check(rejected.finished()&&nonbulk.getParentId()==wagon.getWurmId()&&!destination.getItems().contains(nonbulk),"freight cannot bypass the bunk whitelist or detach rejected finished cargo");
        java.lang.reflect.Method remove=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",com.wurmonline.server.players.Player.class);remove.setAccessible(true);remove.invoke(null,owner);
    }
    private static Item bunk(TrackMaterialsSmoke.InventoryActor actor,float x,float y,List<Item> created) throws Exception {
        Item bunk=ItemFactory.createItem(RailwayLogBunk.templateId,65f,x,y,0,true,ItemMaterials.MATERIAL_IRON,NO_ID,"Bunk fixture");bunk.setLastOwnerId(actor.getWurmId());created.add(bunk);return bunk;
    }
    private static Item item(int template,List<Item> created) throws Exception {Item item=ItemFactory.createItem(template,50f,"Bunk fixture");created.add(item);return item;}
    private static Object allocate(Class<?> type) throws Exception {
        java.lang.reflect.Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }
    private static void field(Class<?> owner,Object object,String name,Object value) throws Exception {
        java.lang.reflect.Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
