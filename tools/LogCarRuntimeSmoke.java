import com.wurmonline.server.Items;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.util.*;
import org.wurmrail.*;

/** Actual native rolling-stock inventories and item mutation gates; no virtual timber. */
public final class LogCarRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private LogCarRuntimeSmoke() { }
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998472101L,true,2);
        List<Item> created=new ArrayList<Item>();
        boolean scheduledParents=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            Item car=car(actor,154*4+2,166*4+2,created);
            check(car.isHollow()&&!car.isBulkContainer()&&!car.isBulkItem()&&car.isNoTake()&&car.isVehicle()&&car.isCart(),
                    "the dedicated Log Car is a real hollow native rail vehicle inventory");
            check(car.getContainerVolume()==LogCarPolicy.MAX_VOLUME_CC&&car.getMaxItemCount()==LogCarPolicy.MAX_ITEMS,
                    "native container metadata agrees with the industrial policy limits");
            check(car.getContainerVolume()>item(ItemList.chestLarge,created).getContainerVolume()*20,
                    "industrial storage has substantially more real volume than a normal chest");
            check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwayLogCar.templateId)!=null,
                    "the car is available through ordinary player construction");
            check(RailModelRegistry.isCustomTemplate(RailwayLogCar.templateId)&&RailModelRegistry.modelName(RailwayLogCar.templateId,(byte)0,0).equals("model.wurmrail.logcar."),"the real native template resolves to the dedicated model");
            check(RawTimberCargo.allowedTemplateIds().equals(LogCarPolicy.allowedTemplateIds())&&RawTimberCargo.allowedTemplateIds().equals(LogBunkPolicy.allowedTemplateIds()),"storage and rolling stock share one exact raw-timber classification");

            for(int template:LogCarPolicy.allowedTemplateIds()) {
                Item raw=item(template,created);
                System.out.println("Native raw timber capacity: template="+template+" volume="+raw.getVolume()+" cc weight="+raw.getWeightGrams()+" g; car volume fit="+(LogCarPolicy.MAX_VOLUME_CC/raw.getVolume()));
                check(!raw.isFood()&&!raw.isLiquid(),"whitelisted timber is neither food nor liquid: "+raw.getName());
                check(car.testInsertItem(raw)&&car.canHold(raw)&&car.insertItem(raw,false,true),
                        "approved timber passes actual native inventory gates: "+raw.getName());
                check(raw.getParentId()==car.getWurmId()&&car.getItems().contains(raw),"approved item retains its actual native parent link");
            }
            for(int template:new int[]{ItemList.hatchet,ItemList.swordLong,ItemList.plateJacket,ItemList.ironBand,
                    ItemList.ironBar,ItemList.backPack,ItemList.barrelSmall,ItemList.crateSmall,
                    ItemList.meat,ItemList.water,ItemList.ironOre,ItemList.dirtPile,ItemList.charcoal,ItemList.plank}) {
                Item bad=item(template,created);long parent=bad.getParentId();
                check(!car.testInsertItem(bad)&&!car.canHold(bad)&&!car.itemCanBeInserted(bad),"non-timber template is rejected by all native read gates: "+bad.getName());
                check(!car.insertItem(bad)&&!car.insertItem(bad,true)&&!car.insertItem(bad,false,true)
                        &&!car.insertItem(bad,true,true,true),"even unconditional and placed insertion cannot bypass the whitelist");
                check(bad.getParentId()==parent&&!car.getItems().contains(bad)&&Items.getItem(bad.getWurmId())==bad,
                        "rejection conserves the exact item and original inventory parent");
                boolean rejected=false;try{((DbItem)car).addItem(bad,false);}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected&&!car.getItems().contains(bad)&&bad.getParentId()==parent,"new low-level native addItem cannot bypass the whitelist");
            }
            playerMove(actor,car,created);
            capacity(actor,created);
            mutations(actor,created);
            System.out.println("PASS native Log Car: exact felled-tree/log whitelist, insertion and parent-mutation gates, player moves, industrial weight/volume/count, real item properties and database parent reload, native recipe and dedicated model registration ("+assertions+" assertions)");
        } finally {
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduledParents;
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
        }
    }
    private static void playerMove(TrackMaterialsSmoke.InventoryActor actor,Item car,List<Item> created) throws Exception {
        actor.getStatus().getPosition().setPosX(car.getPosX());actor.getStatus().getPosition().setPosY(car.getPosY());
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
        Item timber=item(ItemList.log,created);timber.setQualityLevel(73f);timber.setDamage(4f);timber.setColor(0x135724);timber.setRarity((byte)1);
        check(actor.getInventory().insertItem(timber,true),"the actual actor holds a timber item before loading");
        check(timber.moveToItem(actor,car.getWurmId(),false)&&timber.getParentId()==car.getWurmId(),"normal player moveToItem loads approved timber through native permissions and parent operations");
        check(timber.getQualityLevel()==73f&&timber.getDamage()==4f&&timber.getColor()==0x135724&&timber.getRarity()==1
                &&Items.getItem(timber.getWurmId())==timber,"storage preserves quality, damage, color, rarity and exact identity");
        DbItem loaded=new DbItem(timber.getWurmId());
        check(loaded.getParentId()==car.getWurmId()&&loaded.getTemplateId()==ItemList.log
                &&loaded.getWeightGrams()==timber.getWeightGrams(),"native database reload retains the real item and persistent car parent");
        Item tool=item(ItemList.hatchet,created);check(actor.getInventory().insertItem(tool,true),"the actual actor holds the rejected tool");
        check(!tool.moveToItem(actor,car.getWurmId(),false)&&tool.getParentId()==actor.getInventory().getWurmId()
                &&actor.getInventory().getItems().contains(tool),"a rejected normal player move cannot detach or orphan its source item");
        Item ordinary=item(ItemList.chestLarge,created),ordinaryTool=item(ItemList.hatchet,created);
        check(ordinary.insertItem(ordinaryTool,false,true),"ordinary Wurm containers retain their original unrestricted cargo behavior");
    }
    private static void capacity(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        Item weightCar=car(actor,154*4+2,170*4+2,created),heavy=item(ItemList.log,created),extra=item(ItemList.log,created);
        heavy.setWeight((int)LogCarPolicy.MAX_WEIGHT_GRAMS,false);heavy.setSizes(1,1,1);extra.setWeight(1,false);extra.setSizes(1,1,1);
        check(weightCar.insertItem(heavy,false,true)&&LogCarPolicy.remainingWeightGrams(weightCar)==0,"the exact industrial weight boundary is usable");
        check(!weightCar.insertItem(extra,true)&&extra.getParentId()==NO_ID,"the weight boundary also applies to unconditional insertions");
        Item volumeCar=car(actor,158*4+2,170*4+2,created),large=item(ItemList.logHuge,created);
        long nativeVolume=large.getVolume();int fit=(int)(LogCarPolicy.MAX_VOLUME_CC/nativeVolume);
        check(fit>0&&fit<LogCarPolicy.MAX_ITEMS,"volume test reaches native storage volume before the item-count limit");
        for(int i=0;i<fit;i++)check(volumeCar.insertItem(i==0?large:item(ItemList.logHuge,created),false,true),"native resource volume fills the industrial car up to its real fit boundary");
        Item oversize=item(ItemList.logHuge,created);
        check(!volumeCar.insertItem(oversize,true)&&oversize.getParentId()==NO_ID
                &&LogCarPolicy.remainingVolume(volumeCar)<nativeVolume,"native volume remains bounded even when inserted unconditionally");
        Item countCar=car(actor,162*4+2,170*4+2,created);
        for(int i=0;i<LogCarPolicy.MAX_ITEMS;i++) {
            Item grain=item(ItemList.log,created);grain.setWeight(1,false);grain.setSizes(1,1,1);
            check(countCar.insertItem(grain,false,true),"real car item capacity beyond the ordinary one hundred item limit");
        }
        check(countCar.getItems().size()==LogCarPolicy.MAX_ITEMS&&!countCar.mayCreatureInsertItem()
                &&!countCar.insertItem(extra,true),"the bounded 4096-item native car limit is enforced");
    }
    private static void mutations(TrackMaterialsSmoke.InventoryActor actor,List<Item> created) throws Exception {
        Item car=car(actor,150*4+2,170*4+2,created),log=item(ItemList.log,created),other=item(ItemList.logHuge,created);
        log.setWeight(10000000,false);other.setWeight(10000000,false);
        check(!log.isCombine()&&!other.isCombine(),"native logs and felled trees cannot enter the native partial-combine path");
        check(car.insertItem(log,false,true)&&car.insertItem(other,false,true),"real timber fills the weight boundary");
        log.setWeight(10000001,false);
        check(log.getWeightGrams()==10000000&&LogCarPolicy.remainingWeightGrams(car)==0,"in-place native growth cannot exceed the weight boundary");
        log.setWeight(9999999,false);
        check(log.getWeightGrams()==9999999,"ordinary Wurm timber consumption and weight reduction remains allowed");
        boolean rejected=false;try{log.setTemplateId(ItemList.plank);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&log.getTemplateId()==ItemList.log&&log.getParentId()==car.getWurmId(),"native template changes cannot turn stored logs into unrelated crafted freight");
        other.setTemplateId(ItemList.log);
        check(other.getTemplateId()==ItemList.log&&other.getParentId()==car.getWurmId(),"normal felled-tree to log conversion preserves the storage parent");
        int x=log.getSizeX(),y=log.getSizeY(),z=log.getSizeZ();
        rejected=false;try{log.setSizes(500,500,500);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected&&log.getSizeX()==x&&log.getSizeY()==y&&log.getSizeZ()==z,"over-capacity native resizing is rejected before any dimension changes");
        Item pending=item(ItemList.logHuge,created);pending.setAuxData((byte)99);
        check(!LogCarPolicy.eligible(pending)&&!car.insertItem(pending,true),"unconfirmed forestry output stays quarantined in the machine safety compartment");
        pending.setAuxData((byte)12);check(LogCarPolicy.eligible(pending),"normal native felled-tree age metadata remains eligible");
    }
    private static Item car(TrackMaterialsSmoke.InventoryActor actor,float x,float y,List<Item> created) throws Exception {
        Item car=ItemFactory.createItem(RailwayLogCar.templateId,65f,x,y,0,true,ItemMaterials.MATERIAL_IRON,NO_ID,"Car fixture");car.setLastOwnerId(actor.getWurmId());created.add(car);return car;
    }
    private static Item item(int template,List<Item> created) throws Exception {Item item=ItemFactory.createItem(template,50f,"Car fixture");created.add(item);return item;}
    private static Object allocate(Class<?> type) throws Exception {
        java.lang.reflect.Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }
    private static void field(Class<?> owner,Object object,String name,Object value) throws Exception {
        java.lang.reflect.Field field=owner.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
