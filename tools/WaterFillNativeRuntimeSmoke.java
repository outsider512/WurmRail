import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.zones.WaterType;
import java.lang.reflect.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;
import org.wurmrail.core.WaterFillStops.Stop;

/** Real liquid insertion, player pours, native source checks and inventory fault conservation. */
public final class WaterFillNativeRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private static final int X=215,Y=135;
    private static final List<Item> created=new ArrayList<Item>();
    private static long insertionTarget,growthTarget,unparentedOwner;
    private static int insertionMode,growthMode;
    private WaterFillNativeRuntimeSmoke() { }
    public static boolean rejectInsertion(Item parent,Item child) {
        if(parent.getWurmId()!=insertionTarget||insertionMode==0)return false;
        int mode=insertionMode;insertionMode=0;
        if(mode==2)((DbItem)parent).addItem(child,false);
        if(mode==3) {parent.insertItem(child,false,true);throw new IllegalStateException("Forced committed water notification failure");}
        return true;
    }
    public static boolean rejectGrowth(Item item,int requested) {
        if(growthMode==3&&item.getParentId()==NO_ID&&item.getLastOwnerId()==unparentedOwner
                &&item.getTemplateId()==ItemList.water&&requested==2000) {
            growthMode=0;throw new IllegalStateException("Forced unparented water metadata failure");
        }
        if(item.getWurmId()==growthTarget&&growthMode==1) {growthMode=0;return true;}
        return false;
    }
    public static void afterGrowth(Item item,int requested) {
        if(item.getWurmId()==growthTarget&&growthMode==2) {growthMode=0;throw new IllegalStateException("Forced committed water growth notification failure");}
    }
    public static void run() throws Exception {
        int[] surface=new int[121];for(int i=0;i<121;i++)surface[i]=Server.surfaceMesh.getTile(X-5+i%11,Y-5+i/11);
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            for(int x=X-5;x<=X+5;x++)for(int y=Y-5;y<=Y+5;y++)Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
            TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998472192L,true,2);
            actor.getStatus().getPosition().setPosX(X*4+2);actor.getStatus().getPosition().setPosY(Y*4+2);
            Item tank=WurmRailWorld.spawnTankCar(actor,X*4+2,Y*4+2,0);created.add(tank);
            check(tank.isVehicle()&&tank.isHollow()&&tank.isContainerLiquid()&&!tank.isLiquid(),"actual Tank Car is a native liquid container and rail vehicle, not a liquid itself");
            check(tank.getContainerVolume()==5000000&&tank.getMaxItemWeight()==5000000&&tank.getMaxItemCount()==1,"native capacity is five thousand litres with one liquid stack");
            check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(RailwayTankCar.templateId)!=null,"Tank Car has a normal native crafting recipe");
            guards(tank);
            playerPour(actor,tank);
            sources(actor);
            nativeFill(actor);
            System.out.println("PASS native Tank Car liquids: actual player split/merge pours, solid/mixed liquid rejection, native growth and capacity guards, real liquid identity and database persistence, natural source validation and insertion/growth failure conservation ("+assertions+" assertions)");
        } finally {
            insertionMode=0;growthMode=0;insertionTarget=0;growthTarget=0;unparentedOwner=0;
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            for(int i=0;i<121;i++)Server.surfaceMesh.setTile(X-5+i%11,Y-5+i/11,surface[i]);
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void guards(Item tank) throws Exception {
        for(int template:new int[]{ItemList.rock,ItemList.hatchet,ItemList.chestLarge,ItemList.plank,ItemList.lye,ItemList.tar,ItemList.oliveOil}) {
            Item cargo=item(template,1000);
            check(!tank.testInsertItem(cargo)&&!tank.itemCanBeInserted(cargo)&&!tank.canHold(cargo),"native insertion/pour checks reject unsupported liquid or solid template "+template);
            check(!tank.insertItem(cargo,true)&&cargo.getParentId()==NO_ID&&tank.getItems().isEmpty(),"unconditional native insertion cannot bypass liquid-only policy");
            boolean denied=false;try {((DbItem)tank).addItem(cargo,false);}catch(IllegalArgumentException expected){denied=true;}
            check(denied&&tank.getItems().isEmpty(),"low-level DbItem.addItem cannot bypass liquid-only policy");
        }
        Item tooMuch=item(ItemList.water,5000001);
        check(!tank.insertItem(tooMuch,true)&&tooMuch.getParentId()==NO_ID,"oversize initial liquid insertion preserves its source item");
        Item water=item(ItemList.water,1000);check(tank.insertItem(water,false,true),"one actual native water item enters an empty Tank Car");
        Item more=item(ItemList.water,1000);check(!tank.insertItem(more,true)&&more.getParentId()==NO_ID,"a second native stack cannot bypass the one-liquid-stack inventory rule");
        water.setWeight(5000000,true);check(water.getWeightGrams()==5000000&&tank.getFreeVolume()==0,"native weight growth reaches exactly the five-thousand-litre capacity");
        water.setWeight(5000001,true);check(water.getWeightGrams()==5000000,"native setWeight cannot overfill an existing liquid item");
        boolean denied=false;try{water.setTemplateId(ItemList.rock);}catch(IllegalArgumentException expected){denied=true;}
        check(denied&&water.getTemplateId()==ItemList.water,"in-place template changes cannot turn tank contents into solid cargo");
        DbItem loaded=new DbItem(water.getWurmId());check(loaded.getParentId()==tank.getWurmId()&&loaded.getWeightGrams()==5000000&&loaded.isLiquid(),"native database reload preserves one real liquid item and its exact stored amount");
        water.setWeight(0,true);check(tank.getItems().isEmpty()&&!Items.getItemOptional(water.getWurmId()).isPresent(),"native liquid emptying/draining remains available");
        Item consumed=item(ItemList.water,1);tank.insertItem(consumed,false,true);consumed.setWeight(-1,true);
        check(tank.getItems().isEmpty()&&!Items.getItemOptional(consumed.getWurmId()).isPresent(),"native subtract-to-below-zero consumption still destroys the exhausted stack");
    }
    private static void playerPour(TrackMaterialsSmoke.InventoryActor actor,Item tank) throws Exception {
        Object human=allocate(Class.forName("com.wurmonline.server.creatures.DbCreatureTemplate"));
        field(com.wurmonline.server.creatures.CreatureTemplate.class,human,"id",com.wurmonline.server.creatures.CreatureTemplateIds.HUMAN_CID);
        field(com.wurmonline.server.creatures.CreatureTemplate.class,human,"name","human");
        field(com.wurmonline.server.creatures.Creature.class,actor,"template",human);
        field(com.wurmonline.server.creatures.CreatureStatus.class,actor.getStatus(),"body",com.wurmonline.server.bodys.BodyFactory.getBody(actor,com.wurmonline.server.bodys.BodyTemplate.TYPE_HUMAN,(short)180,(short)40,(short)40));
        actor.getBody().createBodyParts();Object possessions=allocate(com.wurmonline.server.items.Possessions.class);
        field(com.wurmonline.server.items.Possessions.class,possessions,"inventory",actor.getInventory());field(com.wurmonline.server.items.Possessions.class,possessions,"owner",actor);
        field(com.wurmonline.server.creatures.Creature.class,actor,"possessions",possessions);
        Item first=item(ItemList.water,2000);check(actor.getInventory().insertItem(first,true),"actual actor holds water before a native player pour");
        Item oversizedUnsupported=item(ItemList.lye,LiquidTankPolicy.MAX_WEIGHT_GRAMS+1000);actor.getInventory().insertItem(oversizedUnsupported,true);
        int objects=Items.getAllItems().length;long sourceParent=oversizedUnsupported.getParentId();
        com.wurmonline.server.behaviours.MethodsItems.fillContainer(null,tank,oversizedUnsupported,actor,false);
        check(oversizedUnsupported.getWeightGrams()==LiquidTankPolicy.MAX_WEIGHT_GRAMS+1000&&oversizedUnsupported.getParentId()==sourceParent
                &&tank.getItems().isEmpty()&&Items.getAllItems().length==objects,"explicit native fill action rejects an oversized unsupported liquid before splitting or orphaning its source");
        Item ordinary=item(ItemList.barrelSmall,2000);
        com.wurmonline.server.behaviours.Action ordinaryAction=(com.wurmonline.server.behaviours.Action)allocate(com.wurmonline.server.behaviours.Action.class);
        field(com.wurmonline.server.behaviours.Action.class,ordinaryAction,"action",(short)345);
        int ordinaryBefore=oversizedUnsupported.getWeightGrams();
        com.wurmonline.server.behaviours.MethodsItems.fillContainer(ordinaryAction,ordinary,oversizedUnsupported,actor,true);
        check(ordinary.getItems().size()==1&&ordinary.getFirstContainedItem().getTemplateId()==ItemList.lye
                &&oversizedUnsupported.getWeightGrams()+ordinary.getFirstContainedItem().getWeightGrams()==ordinaryBefore,
                "the tank-only explicit-fill guard preserves ordinary native container partial pours and liquid conservation");
        check(first.moveToItem(actor,tank.getWurmId(),false)&&first.getParentId()==tank.getWurmId(),"native player moveToItem pours the first real water stack into the Tank Car");
        Item second=item(ItemList.water,3000);check(actor.getInventory().insertItem(second,true),"actor holds the matching refill");
        second.moveToItem(actor,tank.getWurmId(),false);
        check(tank.getItems().size()==1&&first.getWeightGrams()==5000&&!Items.getItemOptional(second.getWurmId()).isPresent(),"native player pour merges into the same water ID without a second stack");
        first.setWeight(4999500,true);Item last=item(ItemList.water,1000);actor.getInventory().insertItem(last,true);
        last.moveToItem(actor,tank.getWurmId(),false);
        check(first.getWeightGrams()==5000000&&last.getWeightGrams()==500&&last.getParentId()==actor.getInventory().getWurmId(),"native partial pour fills the last half-litre and preserves the exact remaining source water");
        Item salted=item(ItemList.water,2000);salted.setIsSalted(true);actor.getInventory().insertItem(salted,true);first.setWeight(4999000,true);
        int beforeMismatch=Items.getAllItems().length;
        com.wurmonline.server.behaviours.MethodsItems.fillContainer(null,tank,salted,actor,false);
        check(salted.getWeightGrams()==2000&&first.getWeightGrams()==4999000&&Items.getAllItems().length==beforeMismatch,
                "explicit native fill action refuses a mismatched salinity before any split or weight mutation");
        check(!salted.moveToItem(actor,tank.getWurmId(),false)&&salted.getWeightGrams()==2000&&first.getWeightGrams()==4999000,"native player cannot mix salty and fresh water or lose the rejected source");
        Item solid=item(ItemList.hatchet,1000);actor.getInventory().insertItem(solid,true);
        check(!solid.moveToItem(actor,tank.getWurmId(),false)&&solid.getParentId()==actor.getInventory().getWurmId(),"ordinary player solid-cargo rejection preserves the original inventory item");
    }
    private static void sources(TrackMaterialsSmoke.InventoryActor actor) throws Exception {
        RailNetwork net=new RailNetwork();Node rail=net.addNode("nativeWaterSource",X,Y,0);
        int sx=X,sy=Y-2;waterTile(sx,sy);
        check(RailwayWaterFillWorld.sourceProblem(rail,sx,sy,0)==null,"native natural water tile within eight metres is an eligible pump source");
        check(RailwayWaterFillWorld.nearbySources(rail).stream().anyMatch(source->source.x==sx&&source.y==sy&&source.layer==0),"explicit local source discovery captures exact natural tile coordinates");
        RailwayWaterFillWorld.validateSource(actor,rail,sx,sy,0,actor.getWurmId());assertions++;
        check(RailwayWaterFillWorld.sourceProblem(rail,sx,sy,-1)!=null,"surface and cave water cannot be linked across layers");
        check(RailwayWaterFillWorld.sourceProblem(rail,sx-3,sy,0)!=null,"a distant water source outside eight metres is rejected");
        for(int x=sx;x<=sx+1;x++)for(int y=sy;y<=sy+1;y++)Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
        check(RailwayWaterFillWorld.sourceProblem(rail,sx,sy,0)!=null,"terraforming the selected tile dry invalidates the saved source");
        waterTile(sx,sy);Server.surfaceMesh.setTile(sx,sy,Tiles.encode((short)-20,Tiles.Tile.TILE_LAVA.id,(byte)0));
        check(RailwayWaterFillWorld.sourceProblem(rail,sx,sy,0)!=null,"lava never qualifies as natural water even below sea level");
        waterTile(sx,sy);
        Node cave=net.addNode("caveWaterSource",X,Y,-1);int[] oldCave=new int[4];
        for(int i=0;i<4;i++)oldCave[i]=Server.caveMesh.getTile(sx+i%2,sy+i/2);
        try {
            for(int i=0;i<4;i++)Server.caveMesh.setTile(sx+i%2,sy+i/2,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50));
            check(RailwayWaterFillWorld.sourceProblem(cave,sx,sy,-1)!=null,"dry cave floors are not natural water sources");
            Server.caveMesh.setTile(sx,sy,Tiles.encode((short)-20,Tiles.Tile.TILE_CAVE.id,(byte)50));
            check(RailwayWaterFillWorld.sourceProblem(cave,sx,sy,-1)==null,"an open flooded cave uses the same native natural-water predicate");
            Server.caveMesh.setTile(sx,sy,Tiles.encode((short)-20,Tiles.Tile.TILE_CAVE_WALL.id,(byte)50));
            check(RailwayWaterFillWorld.sourceProblem(cave,sx,sy,-1)!=null,"solid cave walls cannot supply water even below water level");
        } finally {for(int i=0;i<4;i++)Server.caveMesh.setTile(sx+i%2,sy+i/2,oldCave[i]);}
    }
    private static void nativeFill(TrackMaterialsSmoke.InventoryActor actor) throws Exception {
        RailNetwork net=new RailNetwork();for(int i=0;i<8;i++) {
            Node node=net.addNode("tankNative"+i,X-4+i,Y,0);Item marker=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0);created.add(marker);node.markerId=marker.getWurmId();if(i>0)net.connect("tankNative"+(i-1),node.name);
        }
        Node rail=net.nodes.get("tankNative4");Item engine=WurmRailWorld.spawnLocomotive(actor,rail.worldX(),rail.worldY(),0);created.add(engine);
        Item tank=WurmRailWorld.spawnTankCar(actor,rail.worldX()-4,rail.worldY(),0);created.add(tank);
        Train train=net.createTrain("NativeTankFill",actor.getWurmId(),Arrays.asList(engine.getWurmId(),tank.getWurmId()),rail.name,"tankNative5");
        for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);
        Stop stop=new Stop(rail.name,rail.markerId,actor.getWurmId(),1,"Native water",X,Y-2,0,100);
        RailwayWaterFillWorld world=new RailwayWaterFillWorld(net);check(world.unavailableReason(train,stop)==null,"real owned stationary consist at the marker is compatible with natural water filling");
        for(int mode:new int[]{1,2}) {
            int before=countWater();insertionTarget=tank.getWurmId();insertionMode=mode;
            RailwayWaterFillService.Transfer failed=world.fill(train,stop,1000);insertionTarget=0;
            check(failed.transferredMillilitres==0&&tank.getItems().isEmpty()&&countWater()==before,"initial native liquid refusal/half-link cancels uncommitted water without phantom contents");
        }
        int beforeMetadata=countWater();unparentedOwner=actor.getWurmId();growthMode=3;
        RailwayWaterFillService.Transfer metadata=world.fill(train,stop,2000);unparentedOwner=0;
        check(metadata.transferredMillilitres==0&&tank.getItems().isEmpty()&&countWater()==beforeMetadata,
                "new-water metadata failure cancels the exact uncommitted native item before insertion");
        insertionTarget=tank.getWurmId();insertionMode=3;
        RailwayWaterFillService.Transfer committed=world.fill(train,stop,1000);insertionTarget=0;
        check(committed.transferredMillilitres==1000&&!committed.requiresOperatorIntervention&&tank.getItems().size()==1,"native post-insert notification failure keeps the one committed real water item");
        Item water=LiquidTankPolicy.contents(tank);created.add(water);long id=water.getWurmId();
        water.setQualityLevel(80f);water.setDamage(50f);float refusedBase=water.getQualityLevel(),refusedDamage=water.getDamage(),priorCurrent=water.getCurrentQualityLevel();
        growthTarget=id;growthMode=1;RailwayWaterFillService.Transfer refused=world.fill(train,stop,1000);
        check(refused.requiresOperatorIntervention&&refused.transferredMillilitres==0&&water.getWeightGrams()==1000,"native existing-stack growth refusal grants no phantom volume and stops for inspection");
        check(water.getQualityLevel()==refusedBase&&water.getDamage()==refusedDamage,"a rejected growth cannot improve or repair unchanged old water");
        growthMode=2;RailwayWaterFillService.Transfer fault=world.fill(train,stop,1000);growthTarget=0;
        check(fault.requiresOperatorIntervention&&fault.transferredMillilitres==1000&&water.getWeightGrams()==2000&&water.getWurmId()==id,"post-growth notification failure reports actual committed volume without recreating or rolling away real water");
        check(Math.abs(water.getQualityLevel()-(priorCurrent+100f)/2)<.001&&water.getDamage()==0f,"an already committed growth fault mixes only the actual added water's quality before stopping");
        RailwayWaterFillService.Transfer normal=world.fill(train,stop,1500);
        check(normal.transferredMillilitres==1500&&water.getWeightGrams()==3500&&water.getWurmId()==id&&tank.getItems().size()==1,"timed native refill grows the same liquid ID by exactly its granted millilitres");
        water.setQualityLevel(80f);water.setDamage(50f);float oldQuality=water.getCurrentQualityLevel();
        RailwayWaterFillService.Transfer damaged=world.fill(train,stop,3500);
        check(damaged.transferredMillilitres==3500&&Math.abs(water.getQualityLevel()-(oldQuality+100f)/2)<.001&&water.getDamage()==0f,
                "natural refilling weights damaged water's current quality exactly like native filling before clearing damage");
        tank.setLastOwnerId(actor.getWurmId()+256);check(world.fill(train,stop,1000).transferredMillilitres==0&&water.getWeightGrams()==7000,"ownership changes revoke automatic tank filling before mutation");tank.setLastOwnerId(actor.getWurmId());
        water.setWeight(LiquidTankPolicy.MAX_WEIGHT_GRAMS-500,true);RailwayWaterFillService.Transfer full=world.fill(train,stop,1000);
        check(full.finished&&full.transferredMillilitres==500&&water.getWeightGrams()==LiquidTankPolicy.MAX_WEIGHT_GRAMS,"the final transfer is bounded by exact native remaining capacity");
        DbItem loaded=new DbItem(id);check(loaded.getParentId()==tank.getWurmId()&&loaded.getWeightGrams()==LiquidTankPolicy.MAX_WEIGHT_GRAMS&&loaded.getQualityLevel()==water.getQualityLevel(),"native water filling persists the same single real liquid stack and weighted quality");
        Field waterTypes=WaterType.class.getDeclaredField("waterSurface");waterTypes.setAccessible(true);byte[][] types=(byte[][])waterTypes.get(null);byte priorType=types[X][Y-2];
        try {
            types[X][Y-2]=WaterType.SEA;
            check(world.fill(train,stop,1000).transferredMillilitres==0&&!water.isSalted()&&water.getWeightGrams()==LiquidTankPolicy.MAX_WEIGHT_GRAMS,"a sea source cannot silently salt an existing fresh-water tank");
            water.setWeight(0,true);RailwayWaterFillService.Transfer sea=world.fill(train,stop,1000);Item salty=LiquidTankPolicy.contents(tank);created.add(salty);
            check(sea.transferredMillilitres==1000&&salty.isSalted()&&salty.getTemplateId()==ItemList.water,"native sea water retains its salted flag in one real water item");
            types[X][Y-2]=WaterType.LAKE;
            check(world.fill(train,stop,1000).transferredMillilitres==0&&salty.isSalted()&&salty.getWeightGrams()==1000,"fresh sources likewise preserve existing incompatible salted water");
        } finally {types[X][Y-2]=priorType;}
    }
    private static void waterTile(int x,int y) {for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)Server.surfaceMesh.setTile(x+dx,y+dy,Tiles.encode((short)-20,Tiles.Tile.TILE_DIRT.id,(byte)0));}
    private static Item item(int template,int weight) throws Exception {Item item=ItemFactory.createItem(template,60f,"Tank liquid fixture");item.setWeight(weight,false);created.add(item);return item;}
    private static int countWater() {int count=0;for(Item item:Items.getAllItems())if(item.getTemplateId()==ItemList.water)count++;return count;}
    private static Object allocate(Class<?> type) throws Exception {Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);}
    private static void field(Class<?> type,Object object,String name,Object value) throws Exception {Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
