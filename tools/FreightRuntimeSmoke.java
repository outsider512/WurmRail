import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.FreightStops.*;
import org.wurmrail.core.FreightTransfers.Cargo;
import org.wurmrail.core.RailNetwork.*;

/** Native items and route arrivals, without sockets, virtual cargo or a second routing system. */
public final class FreightRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private FreightRuntimeSmoke() { }
    public static void run() throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998461104L,true,2);
        List<Item> created=new ArrayList<Item>();RailNetwork net=new RailNetwork();
        try {
            List<String> ring=new ArrayList<String>();int index=0;
            for(int x=175;x<=185;x++)ring.add(node(net,actor,created,index++,x,160));
            for(int y=161;y<=164;y++)ring.add(node(net,actor,created,index++,185,y));
            for(int x=184;x>=175;x--)ring.add(node(net,actor,created,index++,x,164));
            for(int y=163;y>160;y--)ring.add(node(net,actor,created,index++,175,y));
            for(int i=0;i<ring.size();i++)net.connect(ring.get(i),ring.get((i+1)%ring.size()));
            Node mine=net.nodes.get(ring.get(3)),foundry=net.nodes.get(ring.get(17));
            Item source=storage(actor,mine,created),destination=storage(actor,foundry,created);
            Path directory=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-freight");
            Files.createDirectories(directory);
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"stateFile",directory.resolve("network.properties"));
            call(runtime,"initService",new Class<?>[0]);
            configure(runtime,actor,mine,source,"Mine Loader",Operation.LOAD);
            configure(runtime,actor,foundry,destination,"Foundry Unloader",Operation.UNLOAD);
            FreightStops stops=(FreightStops)get(runtime,"freightStops");
            check(stops.get(mine.name).storageId==source.getWurmId()&&stops.get(foundry.name).operation==Operation.UNLOAD,
                    "native freight configuration uses stable real storage IDs and normal rail identities");
            check(FreightStops.load(directory.resolve("freight.properties")).stops.size()==2,"GUI configuration durably saves both actual freight stops");

            Item engine=WurmRailWorld.spawnLocomotive(actor,mine.worldX(),mine.worldY(),0);created.add(engine);
            Node rear=net.nodes.get(ring.get(2));Item wagon=WurmRailWorld.spawnCart(actor,rear.worldX(),rear.worldY(),0);created.add(wagon);
            Train train=net.createTrain("NativeFreight",actor.getWurmId(),Arrays.asList(engine.getWurmId(),wagon.getWurmId()),mine.name,ring.get(4));
            apply(train);
            actor.getStatus().getPosition().setPosX(mine.worldX());actor.getStatus().getPosition().setPosY(mine.worldY());
            RailwayTrainUi.View routeChoices=(RailwayTrainUi.View)call(runtime,"trainSnapshot",new Class<?>[]{Creature.class,long.class},actor,engine.getWurmId());
            boolean loadOffered=false,unloadOffered=false;
            for(RailwayTrainUi.Choice choice:routeChoices.stations) {
                loadOffered|=choice.key.equals(mine.name)&&choice.label.contains("Mine Loader");
                unloadOffered|=choice.key.equals(foundry.name)&&choice.label.contains("Foundry Unloader");
            }
            check(loadOffered&&unloadOffered,"the existing train-route window lists the named loader and unloader without a separate freight route system");
            Item heavy=cargo(ItemList.ironBar,250000,79f,created),light=cargo(ItemList.ironBar,50000,63f,created),unmatched=cargo(ItemList.ironOre,2000,42f,created);
            heavy.setDamage(7f);heavy.setColor(0x123456);heavy.setRarity((byte)1);
            byte material=heavy.getMaterial();String creator=heavy.getCreatorName();
            boolean insertedHeavy=source.insertItem(heavy),insertedLight=source.insertItem(light),insertedUnmatched=source.insertItem(unmatched);
            check(insertedHeavy&&insertedLight&&insertedUnmatched,"real native source inventory receives unchanged cargo items; insertion="+insertedHeavy+","+insertedLight+","+insertedUnmatched+" storage="+source.getContainerVolume()+" sizes="+source.getContainerSizeX()+","+source.getContainerSizeY()+","+source.getContainerSizeZ()+" heavy volume="+heavy.getVolume()+" heavy sizes="+heavy.getSizeX()+","+heavy.getSizeY()+","+heavy.getSizeZ());
            FreightStops activeStops=stops;CountingWorld world=new CountingWorld(net);RailwayFreightService service=new RailwayFreightService(net,()->activeStops,world);
            net.routeStopServices=service::shouldService;
            for(int i=0;i<100;i++) {service.shouldService(train,mine);service.serviceStops(.1);}
            check(world.lookups==0&&world.inventories==0,"passive stop definitions and passing/non-arrived trains do not poll storage inventories");
            net.setRoute(train.name,Arrays.asList(mine.name,foundry.name),1);net.resumeScheduled(train.name);
            tick(net,service,.1);check(mine.name.equals(train.serviceStopRail),"the existing station arrival creates a freight hold at the scheduled loader");
            check(heavy.getParentId()==source.getWurmId()&&service.activeVisits()==1,"arrival starts a visit with no instant cargo credit");
            nativeFailures(net,train,stops.get(mine.name),source,wagon,heavy,created);
            for(int i=0;i<10;i++)tick(net,service,.1);
            check(heavy.getParentId()==source.getWurmId()&&train.serviceStopRail!=null,"250 kilogram whole item waits after the first 100 kilograms of loading allowance");
            net.stop(train.name);for(int i=0;i<30;i++)tick(net,service,.1);
            check(!train.powered&&heavy.getParentId()==source.getWurmId()&&train.serviceStopRail!=null,"operator STOP pauses freight without transfer or departure");

            Path railFile=directory.resolve("mid-load.properties");net.save(railFile);service.close();net=RailNetwork.load(railFile);
            train=net.trains.get("NativeFreight");check(!train.powered&&mine.name.equals(train.serviceStopRail),"a mid-load restart retains the exact pending freight arrival while stopped");
            world=new CountingWorld(net);RailwayFreightService resumed=new RailwayFreightService(net,()->activeStops,world);service=resumed;net.routeStopServices=resumed::shouldService;
            net.resumeScheduled(train.name);tick(net,service,.1);for(int i=0;i<20;i++)tick(net,service,.1);
            check(heavy.getParentId()==source.getWurmId(),"restart discards elapsed loading credit rather than performing an instant or duplicated cargo move");
            for(int i=0;i<10;i++)tick(net,service,.1);
            check(heavy.getParentId()==wagon.getWurmId()&&light.getParentId()==wagon.getWurmId(),"three earned seconds load exactly 300 kilograms into the existing cargo wagon");
            check(unmatched.getParentId()==source.getWurmId(),"exact-template filtering leaves unmatched native cargo untouched");
            check(train.serviceStopRail==null&&train.powered,"completed loading releases the ordinary route hold without changing scheduled power");
            int cursor=train.routeIndex,arrivals=0;boolean unloaded=false;
            for(int step=0;step<2400&&arrivals<5;step++) {
                tick(net,service,.1);
                if(train.routeIndex!=cursor) {arrivals++;cursor=train.routeIndex;}
                unloaded|=heavy.getParentId()==destination.getWurmId()&&light.getParentId()==destination.getWurmId();
                check(train.powered&&train.automated,"normal freight visits retain the running repeating itinerary");
            }
            check(unloaded&&arrivals>=5,"the actual scheduled train unloads at the foundry and keeps repeating through empty-source/empty-train visits");
            check(Items.getItem(heavy.getWurmId())==heavy&&heavy.getQualityLevel()==79f&&heavy.getDamage()==7f&&heavy.getColor()==0x123456
                    &&heavy.getRarity()==1&&heavy.getMaterial()==material&&Objects.equals(creator,heavy.getCreatorName())&&heavy.getWeightGrams()==250000,
                    "physical transfer preserves the exact item identity, creator, quality, damage, color, rarity, material and full weight");
            check(unmatched.getParentId()==source.getWurmId()&&countReferences(source,heavy)==0&&countReferences(wagon,heavy)==0&&countReferences(destination,heavy)==1,
                    "repeating freight transfers cannot duplicate an item or remove unmatched source cargo");
            check(world.inventories<120,"cargo work runs on its servicing interval rather than every movement tick");
            unsafeFailure(net,train,stops,heavy,wagon,destination,directory);
            System.out.println("PASS native freight stops: real GUI configuration and route catalog, physical whole-item loading/unloading, 100 kg/s timing, template filter, STOP and mid-service restart, capacity/access failures and rollback conservation, repeated ordinary station routes ("+assertions+" assertions)");
        } finally {
            for(Train train:new ArrayList<Train>(net.trains.values())) {net.stop(train.name);net.removeTrain(train.name);}
            for(Item item:created)if(Items.getItemOptional(item.getWurmId()).isPresent())Items.destroyItem(item.getWurmId());
        }
    }
    private static void nativeFailures(RailNetwork net,Train train,Stop stop,Item source,Item wagon,Item heavy,List<Item> created) throws Exception {
        RailwayFreightWorld world=new RailwayFreightWorld(net);FreightTransfers.Inventory inventory=world.inventory(train,stop);
        Cargo candidate=null;for(Cargo item:inventory.candidates())if(item.itemId==heavy.getWurmId())candidate=item;
        check(candidate!=null&&candidate.weightGrams==250000&&inventory.canMove(candidate),"native adapter measures the real whole cargo weight and compatibility");
        source.setLastOwnerId(stop.ownerId+256);
        check(world.unavailableReason(train,stop).contains("belong")&&!inventory.move(candidate),"changing storage ownership revokes automatic cargo access without detaching anything");source.setLastOwnerId(stop.ownerId);
        Item lock=ItemFactory.createItem(ItemList.padLockSmall,50f,"Freight fixture");created.add(lock);lock.setLocked(true);source.setLockId(lock.getWurmId());
        check(world.unavailableReason(train,stop).contains("Unlock")&&!inventory.move(candidate),"a locked source refuses automatic loading and preserves every item");source.setLockId(NO_ID);
        List<Item> fillers=new ArrayList<Item>();
        for(int i=0;i<100;i++) {Item filler=cargo(ItemList.plank,2000,30f,created);check(wagon.insertItem(filler,true),"capacity fixture item inserted");fillers.add(filler);}
        check(!inventory.canMove(candidate)&&!inventory.move(candidate)&&heavy.getParentId()==source.getWurmId(),"the native wagon item-count capacity blocks a transfer without removing source cargo");
        for(Item filler:fillers)Items.destroyItem(filler.getWurmId());
        Set<Item> original=new HashSet<Item>(source.getItems());set(source,"items",new ThrowOnceRemove(original));
        boolean failed=false;try {world.inventory(train,stop).move(candidate);}catch(RuntimeException expected){failed=true;}
        check(failed&&heavy.getParentId()==source.getWurmId()&&source.getItems().contains(heavy)&&!wagon.getItems().contains(heavy),
                "an injected native failure after destination insertion restores the original physical parent and both inventory memberships");
        set(source,"items",new HashSet<Item>(source.getItems()));
        check(Items.getItem(heavy.getWurmId())==heavy&&countReferences(source,heavy)==1,"failed native transfer conserves the one original item instance");
        Item pack=cargo(ItemList.backPack,2000,51f,created),inside=cargo(ItemList.ironOre,5000,55f,created);check(pack.insertItem(inside,true),"nested native cargo inserted");
        check(RailwayFreightWorld.cargoWeight(pack)==7000,"generic cargo weight includes contained actual items without unpacking them");
        check(source.insertItem(pack,true),"nested cargo stored");
        Stop any=new Stop(stop.rail,stop.markerId,stop.ownerId,stop.revision,"Any cargo",Operation.LOAD,stop.storageId,0,100);
        FreightTransfers.Inventory anyInventory=world.inventory(train,any);Cargo nested=null;for(Cargo item:anyInventory.candidates())if(item.itemId==pack.getWurmId())nested=item;
        check(nested!=null&&anyInventory.move(nested)&&pack.getParentId()==wagon.getWurmId()&&inside.getParentId()==pack.getWurmId(),"generic loading moves the original cargo container and preserves its actual child hierarchy");
        check(source.insertItem(pack,true),"nested fixture restored before the filtered repeating route");
        Item car=Items.getItem(train.carIds.get(1));float originalX=car.getPosX();WurmRailWorld.moveCar(car.getWurmId(),originalX+8,car.getPosY(),car.getRotation(),0);
        check(world.unavailableReason(train,stop).contains("moved away")&&!world.inventory(train,stop).move(candidate),"a physically displaced attached wagon cannot receive remote cargo");apply(train);
        Item storage=Items.getItem(stop.storageId);storage.setMailed(true);check(world.unavailableReason(train,stop)!=null,"mailed associated storage is unavailable");storage.setMailed(false);
    }
    private static final class ThrowOnceRemove extends HashSet<Item> {
        boolean armed=true;ThrowOnceRemove(Set<Item> initial){super(initial);}
        @Override public boolean remove(Object value){if(armed){armed=false;throw new IllegalStateException("Injected freight source-detach failure");}return super.remove(value);}
    }
    private static void unsafeFailure(RailNetwork net,Train train,FreightStops stops,Item cargo,Item wagon,Item storage,Path directory) throws Exception {
        Stop stop=stops.get(train.serviceStopRail);
        check(stop!=null&&stop.operation==Operation.UNLOAD&&stop.storageId==storage.getWurmId(),"fatal rollback fixture begins at a genuine scheduled unloader hold");
        check(wagon.insertItem(cargo),"fatal rollback fixture returns the same actual cargo to its wagon");
        ScheduledServices intents=new ScheduledServices();intents.record(train,true);int[] faults={0};
        Path intentFile=directory.resolve("fatal-services.properties");
        RailwayFreightService faultService=new RailwayFreightService(net,()->stops,new RailwayFreightWorld(net),(service,reason)-> {
            faults[0]++;intents.record(service,false);
            try {intents.save(intentFile,net);}catch(Exception failure){throw new IllegalStateException(failure);}
        });
        faultService.serviceStops(.1);
        set(wagon,"items",new ThrowOnceRemove(new HashSet<Item>(wagon.getItems())));
        set(storage,"items",new ThrowOnceRemove(new HashSet<Item>(storage.getItems())));
        faultService.serviceStops(3);
        check(!train.powered&&train.serviceStopRail!=null&&faults[0]==1&&train.status.contains("operator attention"),
                "an uncertain native rollback retains the route hold, powers off and explicitly requests an operator");
        check(intents.resumeCandidates(net).isEmpty()&&ScheduledServices.load(intentFile).resumeCandidates(net).isEmpty(),
                "fatal freight failure durably revokes automatic restart intent");
        faultService.serviceStops(10);
        check(faults[0]==1&&!train.powered,"an operator-stopped fatal visit cannot continually retry or repeatedly poll the damaged inventories");
        net.start(train.name);intents.record(train,true);faultService.serviceStops(.1);
        check(!train.powered&&faults[0]==2&&intents.resumeCandidates(net).isEmpty(),
                "manual power alone cannot restart an unresolved fatal visit or accidentally restore automatic running intent");
        // Model an explicit operator repair of the one original item, then close
        // this fault-injection service. No production workaround is involved.
        set(wagon,"items",new HashSet<Item>(wagon.getItems()));set(storage,"items",new HashSet<Item>(storage.getItems()));
        if(storage.getItems().contains(cargo))storage.dropItem(cargo.getWurmId(),false);
        check(wagon.insertItem(cargo,true)&&cargo.getParentId()==wagon.getWurmId()&&!storage.getItems().contains(cargo),
                "operator repair conserves the original item while restoring one consistent parent after the injected rollback failure");
        check(storage.insertItem(cargo)&&Items.getItem(cargo.getWurmId())==cargo&&countReferences(storage,cargo)==1&&countReferences(wagon,cargo)==0,
                "the same repaired cargo can return to the actual destination without duplication or property changes");
        faultService.close();
    }
    private static final class CountingWorld implements RailwayFreightService.World {
        final RailwayFreightWorld delegate;int lookups,inventories;
        CountingWorld(RailNetwork network){delegate=new RailwayFreightWorld(network);}
        public String unavailableReason(Train train,Stop stop){lookups++;return delegate.unavailableReason(train,stop);}
        public FreightTransfers.Inventory inventory(Train train,Stop stop){inventories++;return delegate.inventory(train,stop);}
    }
    private static void configure(WurmRailRuntime runtime,Creature actor,Node rail,Item storage,String name,Operation operation) throws Exception {
        actor.getStatus().getPosition().setPosX(rail.worldX());actor.getStatus().getPosition().setPosY(rail.worldY());
        RailwayFreightUi.View view=(RailwayFreightUi.View)call(runtime,"freightSnapshot",new Class<?>[]{Creature.class,long.class},actor,rail.markerId);
        int choice=0;for(int i=0;i<view.storages.size();i++)if(view.storages.get(i).id==storage.getWurmId())choice=i+1;
        check(choice>0,"configuration discovers nearby native owned ground storage");
        Properties answers=new Properties();answers.setProperty("save","true");answers.setProperty("freight_name",name);answers.setProperty("operation_choice",operation==Operation.LOAD?"0":"1");
        answers.setProperty("storage_choice",Integer.toString(choice));answers.setProperty("filter_choice","1");answers.setProperty("template_id",Integer.toString(ItemList.ironBar));answers.setProperty("transfer_rate","100");
        RailwayFreightUi.Request request=RailwayFreightUi.fromAnswers(view,answers);
        call(runtime,"applyFreightRequest",new Class<?>[]{Creature.class,long.class,RailwayFreightUi.Request.class},actor,rail.markerId,request);
        boolean stale=false;try {call(runtime,"applyFreightRequest",new Class<?>[]{Creature.class,long.class,RailwayFreightUi.Request.class},actor,rail.markerId,request);}catch(IllegalArgumentException expected){stale=true;}
        check(stale,"replaying a stale freight configuration form cannot change a newer definition");
    }
    private static String node(RailNetwork net,Creature actor,List<Item> created,int index,int x,int y) throws Exception {
        Node node=net.addNode("nativeFreight"+index,x,y,0);Item marker=WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0);created.add(marker);node.markerId=marker.getWurmId();return node.name;
    }
    private static Item storage(Creature actor,Node rail,List<Item> created) throws Exception {
        Item item=ItemFactory.createItem(ItemList.chestLarge,50f,rail.worldX(),rail.worldY()+(rail.tileY==160?-4:4),0,true,ItemMaterials.MATERIAL_WOOD_BIRCH,NO_ID,"Freight fixture");
        item.setLastOwnerId(actor.getWurmId());created.add(item);return item;
    }
    private static Item cargo(int template,int grams,float quality,List<Item> created) throws Exception {
        Item item=ItemFactory.createItem(template,quality,"Freight fixture");item.setWeight(grams,true);created.add(item);return item;
    }
    private static void tick(RailNetwork net,RailwayFreightService service,double elapsed) throws Exception {net.tick(elapsed);for(Train train:net.trains.values())apply(train);service.serviceStops(elapsed);}
    private static void apply(Train train) throws Exception {for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    private static int countReferences(Item container,Item item){int count=0;for(Item cargo:container.getItems())if(cargo.getWurmId()==item.getWurmId())count++;return count;}
    private static Object get(Object object,String name) throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static void set(Object object,String name,Object value) throws Exception {for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);return;}catch(NoSuchFieldException missing){}throw new NoSuchFieldException(name);}
    private static Object call(Object object,String name,Class<?>[] types,Object... args) throws Exception {Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(object,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;throw failure;}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
