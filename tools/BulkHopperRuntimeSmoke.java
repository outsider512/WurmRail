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

/** Real native storage, insertion paths and freight inventories; no virtual bulk resource values. */
public final class BulkHopperRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private BulkHopperRuntimeSmoke() { }
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998462101L,true,2);
        List<Item> created=new ArrayList<Item>();
        boolean scheduledParents=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            Item hopper=hopper(actor,154*4+2,166*4+2,created);
            check(hopper.isHollow()&&!hopper.isBulkContainer()&&!hopper.isBulkItem()&&hopper.isNoTake(),
                    "the placeable hopper is a real hollow item inventory, never a virtual native bulk bin");
            check(hopper.getContainerVolume()==BulkHopperPolicy.MAX_VOLUME_CC&&hopper.getMaxItemCount()==BulkHopperPolicy.MAX_ITEMS,
                    "native container metadata agrees with the industrial policy limits");
            check(hopper.getContainerVolume()>item(ItemList.chestLarge,created).getContainerVolume()*20,
                    "industrial storage has substantially more real volume than a normal chest");
            check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwayBulkHopper.templateId)!=null,
                    "the hopper is available through ordinary player construction");

            for(int template:BulkHopperPolicy.allowedTemplateIds()) {
                Item raw=item(template,created);
                check(!raw.isFood()&&!raw.isLiquid(),"whitelisted raw resource is neither food nor liquid: "+raw.getName());
                check(hopper.testInsertItem(raw)&&hopper.canHold(raw)&&hopper.insertItem(raw,false,true),
                        "approved raw resource passes actual native inventory gates: "+raw.getName());
                check(raw.getParentId()==hopper.getWurmId()&&hopper.getItems().contains(raw),"approved item retains its actual native parent link");
            }
            RailwayBulkHopper.View status=RailwayBulkHopper.snapshot(hopper);
            check(status.itemCount==BulkHopperPolicy.allowedTemplateIds().size()&&status.weightGrams==BulkHopperPolicy.storedWeightGrams(hopper),
                    "inspect reports all real stored resource rows and weight");
            check(status.remainingWeightGrams+status.weightGrams==BulkHopperPolicy.MAX_WEIGHT_GRAMS
                    &&status.remainingVolume+status.volume==BulkHopperPolicy.MAX_VOLUME_CC,"inspect reports remaining native weight and volume capacity");
            hopper.setName("Hopper '};button{id='injected';text='bad'}");
            check(!RailwayBulkHopper.content(RailwayBulkHopper.snapshot(hopper)).contains("id='injected'"),"inspection safely escapes hostile item names");

            for(int template:new int[]{ItemList.hatchet,ItemList.swordLong,ItemList.plateJacket,ItemList.ironBand,
                    ItemList.ironBar,ItemList.backPack,ItemList.barrelSmall,ItemList.crateSmall,
                    ItemList.meat,ItemList.water,ItemList.log,ItemList.logHuge,ItemList.plank}) {
                Item bad=item(template,created);long parent=bad.getParentId();
                check(!hopper.testInsertItem(bad)&&!hopper.canHold(bad)&&!hopper.itemCanBeInserted(bad),"non-bulk template is rejected by all native read gates: "+bad.getName());
                check(!hopper.insertItem(bad)&&!hopper.insertItem(bad,true)&&!hopper.insertItem(bad,false,true)
                        &&!hopper.insertItem(bad,true,true,true),"even unconditional and placed insertion cannot bypass the whitelist");
                check(bad.getParentId()==parent&&!hopper.getItems().contains(bad)&&Items.getItem(bad.getWurmId())==bad,
                        "rejection conserves the exact item and original inventory parent");
                boolean rejected=false;try{((DbItem)hopper).addItem(bad,false);}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected&&!hopper.getItems().contains(bad)&&bad.getParentId()==parent,"new low-level native addItem cannot bypass the whitelist");
            }
            playerMove(actor,hopper,created);
            capacity(actor,created);
            freight(actor,created);
            System.out.println("PASS native Bulk Hopper: explicit raw-resource whitelist, ordinary and unconditional native insertion, player moves, industrial capacity/count, real item properties and database parent reload, inspect and freight LOAD/UNLOAD ("+assertions+" assertions)");
        } finally {
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduledParents;
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
        }
    }
    private static void playerMove(TrackMaterialsSmoke.InventoryActor actor,Item hopper,List<Item> created) throws Exception {
        actor.getStatus().getPosition().setPosX(hopper.getPosX());actor.getStatus().getPosition().setPosY(hopper.getPosY());
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
        Item ore=item(ItemList.ironOre,created);ore.setQualityLevel(73f);ore.setDamage(4f);ore.setColor(0x135724);ore.setRarity((byte)1);
        check(actor.getInventory().insertItem(ore,true),"the actual actor holds an ore item before loading");
        check(ore.moveToItem(actor,hopper.getWurmId(),false)&&ore.getParentId()==hopper.getWurmId(),"normal player moveToItem loads approved cargo through native permissions and parent operations");
        check(ore.getQualityLevel()==73f&&ore.getDamage()==4f&&ore.getColor()==0x135724&&ore.getRarity()==1
                &&Items.getItem(ore.getWurmId())==ore,"storage preserves quality, damage, color, rarity and exact identity");
        DbItem loaded=new DbItem(ore.getWurmId());
        check(loaded.getParentId()==hopper.getWurmId()&&loaded.getTemplateId()==ItemList.ironOre
                &&loaded.getWeightGrams()==ore.getWeightGrams(),"native database reload retains the real item and persistent hopper parent");
        Item tool=item(ItemList.hatchet,created);check(actor.getInventory().insertItem(tool,true),"the actual actor holds the rejected tool");
        check(!tool.moveToItem(actor,hopper.getWurmId(),false)&&tool.getParentId()==actor.getInventory().getWurmId()
                &&actor.getInventory().getItems().contains(tool),"a rejected normal player move cannot detach or orphan its source item");
        Item ordinary=item(ItemList.chestLarge,created),ordinaryTool=item(ItemList.hatchet,created);
        check(ordinary.insertItem(ordinaryTool,false,true),"ordinary Wurm containers retain their original unrestricted cargo behavior");
    }
    private static void capacity(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        Item weightHopper=hopper(actor,154*4+2,170*4+2,created),heavy=item(ItemList.clay,created),extra=item(ItemList.clay,created);
        heavy.setWeight((int)BulkHopperPolicy.MAX_WEIGHT_GRAMS,false);heavy.setSizes(1,1,1);extra.setWeight(1,false);extra.setSizes(1,1,1);
        check(weightHopper.insertItem(heavy,false,true)&&BulkHopperPolicy.remainingWeightGrams(weightHopper)==0,"the exact industrial weight boundary is usable");
        check(!weightHopper.insertItem(extra,true)&&extra.getParentId()==NO_ID,"the weight boundary also applies to unconditional insertions");
        Item volumeHopper=hopper(actor,158*4+2,170*4+2,created),large=item(ItemList.rock,created);
        long nativeVolume=large.getVolume();int fit=(int)(BulkHopperPolicy.MAX_VOLUME_CC/nativeVolume);
        check(fit>0&&fit<BulkHopperPolicy.MAX_ITEMS,"volume test reaches native storage volume before the item-count limit");
        for(int i=0;i<fit;i++)check(volumeHopper.insertItem(i==0?large:item(ItemList.rock,created),false,true),"native resource volume fills the industrial hopper up to its real fit boundary");
        Item oversize=item(ItemList.rock,created);
        check(!volumeHopper.insertItem(oversize,true)&&oversize.getParentId()==NO_ID
                &&BulkHopperPolicy.remainingVolume(volumeHopper)<nativeVolume,"native volume remains bounded even when inserted unconditionally");
        Item countHopper=hopper(actor,162*4+2,170*4+2,created);
        for(int i=0;i<BulkHopperPolicy.MAX_ITEMS;i++) {
            Item grain=item(ItemList.ash,created);grain.setWeight(1,false);grain.setSizes(1,1,1);
            check(countHopper.insertItem(grain,false,true),"real hopper item capacity beyond the ordinary one hundred item limit");
        }
        check(countHopper.getItems().size()==BulkHopperPolicy.MAX_ITEMS&&!countHopper.mayCreatureInsertItem()
                &&!countHopper.insertItem(extra,true),"the bounded 4096-item native hopper limit is enforced");
    }
    private static void freight(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        RailNetwork net=new RailNetwork();
        for(int i=0;i<7;i++){Node rail=net.addNode("hopperRail"+i,154+i,176,0);Item marker=WurmRailWorld.spawnTrackMarker(actor,rail.worldX(),rail.worldY(),0);created.add(marker);rail.markerId=marker.getWurmId();if(i>0)net.connect("hopperRail"+(i-1),rail.name);}
        Node stopRail=net.nodes.get("hopperRail3");Item source=hopper(actor,stopRail.worldX(),stopRail.worldY()-4,created),destination=hopper(actor,stopRail.worldX(),stopRail.worldY()+4,created);
        Item engine=WurmRailWorld.spawnLocomotive(actor,stopRail.worldX(),stopRail.worldY(),0),wagon=WurmRailWorld.spawnCart(actor,stopRail.worldX()-4,stopRail.worldY(),0);created.add(engine);created.add(wagon);
        Train train=net.createTrain("HopperFreight",actor.getWurmId(),Arrays.asList(engine.getWurmId(),wagon.getWurmId()),stopRail.name,"hopperRail4");
        for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);
        actor.getStatus().getPosition().setPosX(stopRail.worldX());actor.getStatus().getPosition().setPosY(stopRail.worldY());
        java.lang.reflect.Method make=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);make.setAccessible(true);
        com.wurmonline.server.players.Player owner=(com.wurmonline.server.players.Player)make.invoke(null,998463104L,stopRail.worldX(),stopRail.worldY());
        java.lang.reflect.Field power=com.wurmonline.server.players.PlayerInfo.class.getDeclaredField("power");power.setAccessible(true);power.setByte(owner.getSaveFile(),(byte)0);
        source.setLastOwnerId(owner.getWurmId());destination.setLastOwnerId(owner.getWurmId());engine.setLastOwnerId(owner.getWurmId());wagon.setLastOwnerId(owner.getWurmId());
        net.removeTrain(train.name);train=net.createTrain("HopperFreight",owner.getWurmId(),Arrays.asList(engine.getWurmId(),wagon.getWurmId()),stopRail.name,"hopperRail4");
        RailwayFreightWorld.validateStorage(owner,stopRail,source);RailwayFreightWorld.validateStorage(owner,stopRail,destination);
        check(owner.getPower()==0&&RailwayFreightWorld.nearbyStorage(owner,stopRail).contains(source),"an ordinary non-GM owner can discover and configure a fixed hopper without TAKE-ing the whole object");
        Item ore=item(ItemList.ironOre,created);ore.setWeight(5000,false);ore.setQualityLevel(81f);check(source.insertItem(ore,false,true),"real ore enters the freight source hopper");
        RailwayFreightWorld world=new RailwayFreightWorld(net);
        Stop load=new Stop(stopRail.name,stopRail.markerId,owner.getWurmId(),1,"Hopper loader",Operation.LOAD,source.getWurmId(),0,100);
        FreightTransfers.Session loading=new FreightTransfers.Session(load);
        FreightTransfers.advance(loading,.01,world.inventory(train,load));check(ore.getParentId()==source.getWurmId(),"hopper loading still honors earned freight weight allowance");
        FreightTransfers.advance(loading,.05,world.inventory(train,load));check(ore.getParentId()==wagon.getWurmId()&&!source.getItems().contains(ore),"native freight moves the exact hopper item into an existing cargo wagon");
        Stop unload=new Stop(stopRail.name,stopRail.markerId,owner.getWurmId(),2,"Hopper unloader",Operation.UNLOAD,destination.getWurmId(),0,100);
        FreightTransfers.Session unloading=new FreightTransfers.Session(unload);
        FreightTransfers.advance(unloading,1,world.inventory(train,unload));
        check(ore.getParentId()==destination.getWurmId()&&!wagon.getItems().contains(ore)&&ore.getQualityLevel()==81f&&Items.getItem(ore.getWurmId())==ore,
                "native freight unloads the same unchanged real item into its destination hopper");
        Item nonbulk=item(ItemList.ironBar,created);check(wagon.insertItem(nonbulk,false,true),"generic cargo wagon may hold non-bulk freight");
        FreightTransfers.Session rejected=new FreightTransfers.Session(unload);FreightTransfers.advance(rejected,1,world.inventory(train,unload));
        check(rejected.finished()&&nonbulk.getParentId()==wagon.getWurmId()&&!destination.getItems().contains(nonbulk),"freight cannot bypass the hopper whitelist or detach rejected finished cargo");
        java.lang.reflect.Method remove=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",com.wurmonline.server.players.Player.class);remove.setAccessible(true);remove.invoke(null,owner);
    }
    private static Item hopper(TrackMaterialsSmoke.InventoryActor actor,float x,float y,List<Item> created) throws Exception {
        Item hopper=ItemFactory.createItem(RailwayBulkHopper.templateId,65f,x,y,0,true,ItemMaterials.MATERIAL_IRON,NO_ID,"Hopper fixture");hopper.setLastOwnerId(actor.getWurmId());created.add(hopper);return hopper;
    }
    private static Item item(int template,List<Item> created) throws Exception {Item item=ItemFactory.createItem(template,50f,"Hopper fixture");created.add(item);return item;}
    private static Object allocate(Class<?> type) throws Exception {
        java.lang.reflect.Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }
    private static void field(Class<?> owner,Object object,String name,Object value) throws Exception {
        java.lang.reflect.Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
