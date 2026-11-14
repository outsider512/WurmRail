import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.FreightStops.*;
import org.wurmrail.core.FreightTransfers.Cargo;
import org.wurmrail.core.RailNetwork.*;

/** Real timber, native wagons and ordinary route arrivals; no timber-specific routing engine. */
public final class LogCarFreightRuntimeSmoke {
    private static final long NO_ID=-10L;
    private static int assertions;
    private LogCarFreightRuntimeSmoke(){ }
    public static void run()throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998468104L,true,2);
        List<Item> created=new ArrayList<Item>();RailNetwork net=new RailNetwork();
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        RailwayFreightService service=null;
        try {
            List<String> ring=new ArrayList<String>();int index=0;
            for(int x=105;x<=115;x++)ring.add(node(net,actor,created,index++,x,195));
            for(int y=196;y<=199;y++)ring.add(node(net,actor,created,index++,115,y));
            for(int x=114;x>=105;x--)ring.add(node(net,actor,created,index++,x,199));
            for(int y=198;y>195;y--)ring.add(node(net,actor,created,index++,105,y));
            for(int i=0;i<ring.size();i++)net.connect(ring.get(i),ring.get((i+1)%ring.size()));
            Node loader=net.nodes.get(ring.get(6)),unloader=net.nodes.get(ring.get(20));
            net.setStation(loader.name,true,1);net.setStation(unloader.name,true,1);
            Item source=placed(RailwayLogBunk.templateId,actor,loader.worldX(),loader.worldY()-4,created);
            Item destination=placed(RailwayLogBunk.templateId,actor,unloader.worldX(),unloader.worldY()+4,created);
            Item engine=WurmRailWorld.spawnLocomotive(actor,loader.worldX(),loader.worldY(),0);created.add(engine);
            Node rear=net.nodes.get(ring.get(5));Item general=WurmRailWorld.spawnCart(actor,rear.worldX(),rear.worldY(),0);created.add(general);
            rear=net.nodes.get(ring.get(4));Item logCar=placed(RailwayLogCar.templateId,actor,rear.worldX(),rear.worldY(),created);
            rear=net.nodes.get(ring.get(3));Item tank=WurmRailWorld.spawnTankCar(actor,rear.worldX(),rear.worldY(),0);created.add(tank);
            Train train=net.createTrain("NativeLogFreight",actor.getWurmId(),Arrays.asList(engine.getWurmId(),general.getWurmId()),loader.name,ring.get(7));apply(train);
            RailwayTrainService consists=new RailwayTrainService(net,5,8,poses->{for(Pose pose:poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);});
            position(actor,logCar);consists.couple(actor,train,logCar);position(actor,tank);consists.couple(actor,train,tank);
            check(train.carIds.equals(Arrays.asList(engine.getWurmId(),general.getWurmId(),logCar.getWurmId(),tank.getWurmId()))
                    &&WurmRailWorld.isRailCar(logCar)&&WurmRailWorld.isWagon(logCar),"native coupling accepts the new Log Car in an ordered mixed rolling-stock consist");
            check(RailwayFreightWorld.acceptsCar(logCar,actor.getWurmId())&&RailwayFreightWorld.acceptsCar(general,actor.getWurmId())
                    &&!RailwayFreightWorld.acceptsCar(tank,actor.getWurmId()),"freight accepts timber and general wagons but keeps Tank Cars isolated");
            Item timber=cargo(ItemList.logHuge,650000,81f,ItemMaterials.MATERIAL_WOOD_OAK,created);
            timber.setAuxData((byte)12);timber.setDamage(7f);timber.setColor(0x123456);timber.setRarity((byte)1);timber.setDataXY(41,73);
            Item logs=cargo(ItemList.log,50000,63f,ItemMaterials.MATERIAL_WOOD_PINE,created);
            String originalTimber=fingerprint(timber),originalLogs=fingerprint(logs);
            check(source.insertItem(timber,false,true)&&source.insertItem(logs,false,true),"the real source Bunk accepts both felled trees and ordinary logs");
            check(RailwayFreightWorld.cargoWeight(timber)==650000&&RailwayFreightWorld.cargoWeight(logs)==50000,
                    "both round-timber forms use actual whole-item weights without weakening generic cargo guards");
            FreightStops stops=new FreightStops();
            Stop load=new Stop(loader.name,loader.markerId,actor.getWurmId(),1,"Woodlot Loader",Operation.LOAD,source.getWurmId(),0,100);
            Stop unload=new Stop(unloader.name,unloader.markerId,actor.getWurmId(),2,"Timber Destination",Operation.UNLOAD,destination.getWurmId(),0,100);
            stops.put(load);stops.put(unload);
            blockedLoads(net,train,load,source,general,logCar,timber,actor,created);
            train=legacyGeneral(net,train,load,source,general,logs,ring.get(7));
            RailwayFreightWorld world=new RailwayFreightWorld(net);
            FreightTransfers.Inventory loading=world.inventory(train,load);
            Cargo tree=candidate(loading,timber),small=candidate(loading,logs);
            check(loading.canMove(tree)&&loading.canMove(small),"both large felled trees and small directly-produced logs target the Log Car");
            service=new RailwayFreightService(net,()->stops,world);RailwayFreightService active=service;net.routeStopServices=active::shouldService;
            net.setRoute(train.name,Arrays.asList(loader.name,unloader.name),1);net.resumeScheduled(train.name);tick(net,service,.1);
            check(loader.name.equals(train.serviceStopRail)&&source.getItems().size()==2&&logCar.getItems().isEmpty(),"normal route arrival holds the mixed train with no instantaneous timber credit");
            for(int i=0;i<20;i++)tick(net,service,.1);
            check(timber.getParentId()==source.getWurmId()&&(logs.getParentId()==source.getWurmId()||logs.getParentId()==logCar.getWurmId()),
                    "two seconds cannot move a 650 kg felled tree; a smaller item may be serviced first according to native ID order");
            int pendingBeforeStop=source.getItems().size();long logParentBeforeStop=logs.getParentId();
            net.stop(train.name);for(int i=0;i<30;i++)tick(net,service,.1);
            check(!train.powered&&loader.name.equals(train.serviceStopRail)&&source.getItems().size()==pendingBeforeStop
                    &&timber.getParentId()==source.getWurmId()&&logs.getParentId()==logParentBeforeStop,"operator STOP pauses a pending timber load without detaching or departing");
            Path directory=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-logcar-freight");Files.createDirectories(directory);
            Path file=directory.resolve("mid-load.properties");net.save(file);service.close();net=RailNetwork.load(file);train=net.trains.get("NativeLogFreight");
            check(train.carIds.equals(Arrays.asList(engine.getWurmId(),general.getWurmId(),logCar.getWurmId(),tank.getWurmId()))
                    &&!train.powered&&loader.name.equals(train.serviceStopRail),"restart preserves all original mixed-consist IDs and the paused ordinary freight hold");
            world=new RailwayFreightWorld(net);service=new RailwayFreightService(net,()->stops,world);active=service;net.routeStopServices=active::shouldService;
            net.resumeScheduled(train.name);tick(net,service,.1);
            for(int i=0;i<60;i++)tick(net,service,.1);
            check(timber.getParentId()==source.getWurmId(),"restart grants no old or offline timber credit; six earned seconds cannot move 650 kg");
            for(int i=0;i<10;i++)tick(net,service,.1);
            check(logCar.getItems().contains(timber)&&logCar.getItems().contains(logs)&&source.getItems().isEmpty(),"seven new seconds at 100 kg/s load both actual timber forms into the specialized Log Car");
            check(general.getItems().isEmpty()&&tank.getItems().isEmpty()&&train.powered&&train.serviceStopRail==null,
                    "raw timber bypasses the earlier general wagon and liquid car while completed loading releases the shared route hold");
            check(originalTimber.equals(fingerprint(timber))&&originalLogs.equals(fingerprint(logs)),"loading preserves native timber material, template, quality, damage, colour, rarity, age, coordinates and weight");
            check(new DbItem(timber.getWurmId()).getParentId()==logCar.getWurmId()&&new DbItem(logs.getWurmId()).getParentId()==logCar.getWurmId(),"both original item parents persist in the native inventory database");
            blockedUnloads(net,train,unload,destination,logCar,timber,actor,created);
            int arrivals=0,cursor=train.routeIndex;boolean delivered=false;
            for(int step=0;step<2800&&arrivals<5;step++) {
                tick(net,service,.1);
                if(cursor!=train.routeIndex){arrivals++;cursor=train.routeIndex;}
                delivered|=timber.getParentId()==destination.getWurmId()&&logs.getParentId()==destination.getWurmId();
                check(train.powered&&train.automated,"normal timber freight visits preserve the running repeating route");
            }
            check(delivered&&arrivals>=5&&logCar.getItems().isEmpty()&&source.getItems().isEmpty(),"the actual mixed train unloads into its destination Bunk and keeps repeating empty visits");
            check(originalTimber.equals(fingerprint(timber))&&originalLogs.equals(fingerprint(logs))&&Items.getItem(timber.getWurmId())==timber&&Items.getItem(logs.getWurmId())==logs,
                    "a complete repeating route conserves both unique actual item objects and all original timber properties");
            check(references(timber,source,destination,general,logCar,tank)==1&&references(logs,source,destination,general,logCar,tank)==1,
                    "each delivered timber item appears in exactly one native inventory");
            check(new DbItem(timber.getWurmId()).getParentId()==destination.getWurmId()&&new DbItem(logs.getWurmId()).getParentId()==destination.getWurmId(),"unloaded original timber inventories survive native database reload");
            net.stop(train.name);net.setRoute(train.name,Collections.emptyList(),1);service.close();
            check(logCar.insertItem(timber,false,true)&&logCar.insertItem(logs,false,true),"manual native timber loading uses the same two original items for the reversal test");
            List<Pose> before=new ArrayList<Pose>(train.poses);net.reverse(train.name);apply(train);
            for(int i=0;i<before.size();i++)check(before.get(i).x==train.poses.get(i).x&&before.get(i).y==train.poses.get(i).y,
                    "reversing a loaded Log Car changes traction without spinning or teleporting any consist vehicle");
            int direction=train.direction;net.start(train.name);for(int i=0;i<20;i++){net.tick(.1);apply(train);}net.stop(train.name);
            check(train.direction==direction&&Math.hypot(before.get(0).x-train.poses.get(0).x,before.get(0).y-train.poses.get(0).y)>.1,
                    "the loaded mixed consist actually moves under reverse traction through the existing movement engine");
            net.save(file);net=RailNetwork.load(file);train=net.trains.get("NativeLogFreight");apply(train);
            check(train.direction==direction&&train.carIds.contains(logCar.getWurmId())&&timber.getParentId()==logCar.getWurmId()&&logs.getParentId()==logCar.getWurmId()
                    &&originalTimber.equals(fingerprint(timber))&&originalLogs.equals(fingerprint(logs)),"saved reverse traction, consist identity and unchanged physical timber survive restart");
            System.out.println("PASS native Log Car freight: real Bunk load/unload of logs and felled trees, mixed-stock specialization, full/locked/foreign storage and cars, legacy general wagon compatibility, accumulated 100 kg/s service, STOP/restart, native coupling, loaded reversal and exact item/DB preservation ("+assertions+" assertions)");
        } finally {
            if(service!=null)service.close();
            for(Train train:new ArrayList<Train>(net.trains.values())){net.stop(train.name);net.removeTrain(train.name);}
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void blockedLoads(RailNetwork net,Train train,Stop load,Item source,Item general,Item car,Item timber,Creature owner,List<Item> created)throws Exception {
        RailwayFreightWorld world=new RailwayFreightWorld(net);Cargo candidate=candidate(world.inventory(train,load),timber);
        Item fill=cargo(ItemList.log,(int)LogCarPolicy.MAX_WEIGHT_GRAMS,50,ItemMaterials.MATERIAL_WOOD_BIRCH,created);
        check(car.insertItem(fill,false,true),"native Log Car capacity fixture reaches its exact twenty-tonne weight boundary");
        finishedUnmoved(world.inventory(train,load),load,timber,source,"a full specialized car finishes the visit without putting round timber into the earlier general wagon");
        check(general.getItems().isEmpty(),"full Log Cars cannot redirect raw timber into a general wagon");Items.destroyItem(fill.getWurmId());
        Item lock=ItemFactory.createItem(ItemList.padLockSmall,50,"Log Car freight fixture");created.add(lock);lock.setLocked(true);car.setLockId(lock.getWurmId());
        finishedUnmoved(world.inventory(train,load),load,timber,source,"a locked attached Log Car cannot receive timber or silently change its cargo class");car.setLockId(NO_ID);
        car.setLastOwnerId(owner.getWurmId()+256);finishedUnmoved(world.inventory(train,load),load,timber,source,"foreign ownership revokes Log Car access without falling back to general cargo");car.setLastOwnerId(owner.getWurmId());
        source.setLockId(lock.getWurmId());finishedUnmoved(world.inventory(train,load),load,timber,source,"locked trackside Bunk ends loading with every original item unchanged");source.setLockId(NO_ID);
        source.setLastOwnerId(owner.getWurmId()+256);finishedUnmoved(world.inventory(train,load),load,timber,source,"foreign Bunk ownership cannot be bypassed by automation");source.setLastOwnerId(owner.getWurmId());
        float x=car.getPosX();WurmRailWorld.moveCar(car.getWurmId(),x+4,car.getPosY(),car.getRotation(),0);
        check(world.unavailableReason(train,load).contains("moved away")&&!world.inventory(train,load).move(candidate)&&timber.getParentId()==source.getWurmId(),"a displaced Log Car cannot receive remote timber");apply(train);
    }
    private static Train legacyGeneral(RailNetwork net,Train train,Stop load,Item source,Item general,Item logs,String towards)throws Exception {
        net.removeTrain(train.name);Train legacy=net.createTrain("LegacyLogFreight",train.ownerId,Arrays.asList(train.carIds.get(0),general.getWurmId()),load.rail,towards);apply(legacy);
        RailwayFreightWorld world=new RailwayFreightWorld(net);Cargo candidate=candidate(world.inventory(legacy,load),logs);
        check(world.inventory(legacy,load).move(candidate)&&logs.getParentId()==general.getWurmId(),"existing consists without a Log Car retain general-wagon log transport compatibility");
        check(source.insertItem(logs,false,true),"the same legacy log item returns to its Bunk without cloning");net.removeTrain(legacy.name);
        Train restored=net.createTrain(train.name,train.ownerId,train.carIds,load.rail,towards);apply(restored);return restored;
    }
    private static void blockedUnloads(RailNetwork net,Train train,Stop unload,Item destination,Item car,Item timber,Creature owner,List<Item> created)throws Exception {
        // These inventories are evaluated at the current native loading rail; the
        // destination is temporarily associated with that same ordinary stop.
        Node rail=net.nodes.get(train.serviceStopRail==null?train.poses.get(0).railName:train.serviceStopRail);
        if(rail==null)throw new AssertionError("current Log Car rail absent");
        Item other=placed(RailwayLogBunk.templateId,owner,rail.worldX(),rail.worldY()+4,created);
        Stop local=new Stop(rail.name,rail.markerId,owner.getWurmId(),100,"Capacity test",Operation.UNLOAD,other.getWurmId(),0,100);
        RailwayFreightWorld world=new RailwayFreightWorld(net);
        Item fill=cargo(ItemList.log,(int)LogBunkPolicy.MAX_WEIGHT_GRAMS,50,ItemMaterials.MATERIAL_WOOD_BIRCH,created);check(other.insertItem(fill,false,true),"destination Bunk reaches its real weight capacity");
        finishedUnmoved(world.inventory(train,local),local,timber,car,"a full unloading Bunk leaves the exact timber aboard and completes service cleanly");
        Item hopper=placed(RailwayBulkHopper.templateId,owner,rail.worldX(),rail.worldY()-4,created);
        Stop incompatible=new Stop(rail.name,rail.markerId,owner.getWurmId(),101,"Wrong storage",Operation.UNLOAD,hopper.getWurmId(),0,100);
        finishedUnmoved(world.inventory(train,incompatible),incompatible,timber,car,"incompatible industrial storage cannot receive or destroy raw timber");
        Item lock=ItemFactory.createItem(ItemList.padLockSmall,50,"Log Car destination fixture");created.add(lock);lock.setLocked(true);other.setLockId(lock.getWurmId());
        finishedUnmoved(world.inventory(train,local),local,timber,car,"locked unload storage releases freight cleanly without removing cargo");other.setLockId(NO_ID);
        other.setLastOwnerId(owner.getWurmId()+256);finishedUnmoved(world.inventory(train,local),local,timber,car,"unload automation respects durable destination ownership");
        check(destination.getItems().isEmpty(),"capacity probes cannot populate the separate scheduled destination");
    }
    private static void finishedUnmoved(FreightTransfers.Inventory inventory,Stop stop,Item item,Item parent,String message)throws Exception {
        FreightTransfers.Session session=new FreightTransfers.Session(stop);FreightTransfers.advance(session,5,inventory);
        check(session.finished()&&session.transferredItems()==0&&item.getParentId()==parent.getWurmId()&&parent.getItems().contains(item),message);
    }
    private static Cargo candidate(FreightTransfers.Inventory inventory,Item target)throws Exception {for(Cargo cargo:inventory.candidates())if(cargo.itemId==target.getWurmId())return cargo;throw new AssertionError("native cargo candidate missing: "+target.getName());}
    private static int references(Item item,Item... parents){int n=0;for(Item parent:parents)if(parent.getItems().contains(item))n++;return n;}
    private static String fingerprint(Item item){return item.getWurmId()+"/"+item.getTemplateId()+"/"+item.getWeightGrams()+"/"+item.getQualityLevel()+"/"+item.getDamage()+"/"+item.getColor()+"/"+item.getRarity()+"/"+item.getMaterial()+"/"+item.getAuxData()+"/"+item.getDataX()+"/"+item.getDataY()+"/"+item.getCreatorName();}
    private static Item cargo(int template,int grams,float quality,byte material,List<Item> created)throws Exception {Item item=ItemFactory.createItem(template,quality,material,(byte)0,"Log Car freight fixture");item.setWeight(grams,true);created.add(item);return item;}
    private static Item placed(int template,Creature owner,float x,float y,List<Item> created)throws Exception {Item item=ItemFactory.createItem(template,65f,x,y,0,true,(byte)0,NO_ID,"Log Car freight fixture");item.setLastOwnerId(owner.getWurmId());created.add(item);return item;}
    private static String node(RailNetwork net,Creature actor,List<Item> created,int index,int x,int y)throws Exception {Node node=net.addNode("logCarFreight"+index,x,y,0);Item marker=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0);created.add(marker);node.markerId=marker.getWurmId();return node.name;}
    private static void position(Creature actor,Item car){actor.getStatus().getPosition().setPosX(car.getPosX());actor.getStatus().getPosition().setPosY(car.getPosY());}
    private static void tick(RailNetwork net,RailwayFreightService service,double elapsed)throws Exception {net.tick(elapsed);for(Train train:net.trains.values())apply(train);service.serviceStops(elapsed);}
    private static void apply(Train train)throws Exception {for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
