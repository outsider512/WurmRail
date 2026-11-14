import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.shared.constants.ItemMaterials;
import java.lang.reflect.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.FreightStops.*;
import org.wurmrail.core.RailNetwork.*;

/** Native rock-shard fills, including the last batch that reaches actual wagon capacity. */
public final class FreightFillRuntimeSmoke {
    private static int assertions;
    private static final long NO_ID=-10L;
    private FreightFillRuntimeSmoke() { }
    public static void run() throws Exception {
        fill(1);fill(2);
        System.out.println("PASS native freight wagon fill: ordinary 20 kg rock shards, more than 15 items, timed 100 kg/s batches, full 100-stack wagons, multiple wagons, actual capacity diagnostics and exact item conservation ("+assertions+" assertions)");
    }
    private static void fill(int wagons) throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998463306L+wagons,true,2);
        List<Item> created=new ArrayList<Item>();RailNetwork net=new RailNetwork();
        try {
            for(int i=0;i<=15;i++) {
                Node rail=net.addNode("fill"+i,220+i,210+wagons,0);
                Item marker=WurmRailWorld.spawnTrackMarker(actor,rail.worldX(),rail.worldY(),0);
                created.add(marker);rail.markerId=marker.getWurmId();
                if(i>0)net.connect("fill"+(i-1),rail.name);
            }
            Node loader=net.nodes.get("fill3"),unloader=net.nodes.get("fill12");
            net.setStation(loader.name,true,1);net.setStation(unloader.name,true,1);
            Item hopper=ItemFactory.createItem(RailwayBulkHopper.templateId,70f,loader.worldX(),loader.worldY()+4,0f,true,
                    ItemMaterials.MATERIAL_IRON,NO_ID,"Freight capacity fixture");
            hopper.setLastOwnerId(actor.getWurmId());created.add(hopper);
            FreightStops stops=new FreightStops();
            stops.put(new Stop(loader.name,loader.markerId,actor.getWurmId(),1,"Shard Loader",Operation.LOAD,hopper.getWurmId(),ItemList.rock,100));
            Item engine=WurmRailWorld.spawnLocomotive(actor,loader.worldX(),loader.worldY(),0);created.add(engine);
            List<Long> cars=new ArrayList<Long>();cars.add(engine.getWurmId());List<Item> cargoCars=new ArrayList<Item>();
            for(int i=1;i<=wagons;i++) {
                Node rear=net.nodes.get("fill"+(3-i));Item car=WurmRailWorld.spawnCart(actor,rear.worldX(),rear.worldY(),0);
                created.add(car);cars.add(car.getWurmId());cargoCars.add(car);
            }
            Train train=net.createTrain("ShardFreight"+wagons,actor.getWurmId(),cars,loader.name,"fill4");
            apply(train);
            List<Item> shards=new ArrayList<Item>();
            for(int i=0;i<wagons*100+5;i++) {
                Item shard=ItemFactory.createItem(ItemList.rock,54f,"Freight capacity fixture");
                shard.setWeight(20000,true);created.add(shard);shards.add(shard);
                check(hopper.insertItem(shard,false,true),"the real source hopper accepts each ordinary 20 kg rock shard");
            }
            RailwayFreightService service=new RailwayFreightService(net,()->stops,new RailwayFreightWorld(net));
            net.routeStopServices=service::shouldService;
            net.setRoute(train.name,Arrays.asList(loader.name,unloader.name),1);net.start(train.name);
            tick(net,service,.1);
            check(loader.name.equals(train.serviceStopRail)&&count(cargoCars)==0,"arrival creates a normal freight hold without instant loading");
            for(int i=0;i<30;i++)tick(net,service,.1);
            int afterThree=count(cargoCars);
            System.out.println("SHARD FILL after 3 seconds: wagons="+wagons+", loaded="+afterThree+", powered="+train.powered+", held="+train.serviceStopRail
                    +", payloadLimit="+cargoCars.get(0).getMaxItemWeight()+", shardVolume="+shards.get(0).getVolume()+", wagonVolume="+cargoCars.get(0).getContainerVolume());
            check(afterThree==15,"100 kg/s transfers exactly fifteen ordinary 20 kg shards after three earned seconds");
            check(train.serviceStopRail!=null,"fifteen shards are an intermediate batch rather than a full wagon or completed visit");
            for(int i=0;i<10;i++)tick(net,service,.1);
            check(count(cargoCars)==20&&train.serviceStopRail!=null,"loading continues beyond fifteen shards while capacity remains");
            for(int i=0;i<700&&train.serviceStopRail!=null;i++)tick(net,service,.1);
            int loaded=count(cargoCars);String completion=service.statusForRail(loader.name);
            System.out.println("SHARD FILL completed: "+completion);
            check(loaded==wagons*100,"all native wagon slots fill before the train departs");
            check(train.powered&&train.serviceStopRail==null,"actual capacity completion releases the existing repeating route hold");
            check(hopper.getItems().size()==5,"only cargo beyond native wagon capacity remains in the source");
            for(Item car:cargoCars) {
                check(car.getMaxItemWeight()==WurmRailWorld.CARGO_WAGON_PAYLOAD_GRAMS&&car.getItems().size()==100,
                        "each generic wagon has the explicit ten-tonne payload and unchanged hundred-stack cap");
                check(car.getContainerVolume()==120*120*410&&car.getFullWeight()-car.getWeightGrams()==2000000,
                        "the original wagon volume is retained and its cargo is exactly two tonnes of real shards");
            }
            check(engine.getMaxItemWeight()==300000,"the locomotive retains its original native cargo limit");
            check(completion.contains("destination full")&&completion.contains((wagons*100)+" / "+(wagons*100)+" stacks")
                    &&completion.contains("Transferred "+(wagons*100)+" items"),"completion reports the actual slot blocker and transferred item count");
            Set<Long> ids=new HashSet<Long>();
            for(Item shard:shards) {
                check(Items.getItem(shard.getWurmId())==shard&&shard.getWeightGrams()==20000&&shard.getQualityLevel()==54f,
                        "the same native shard IDs retain exact weight and quality");
                boolean source=hopper.getItems().contains(shard);int parents=source?1:0;
                for(Item car:cargoCars)if(car.getItems().contains(shard))parents++;
                check(parents==1&&ids.add(shard.getWurmId()),"every shard occurs in exactly one original source/destination inventory");
            }
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"freightStops",stops);
            call(runtime,"initService",new Class<?>[0]);
            actor.getStatus().getPosition().setPosX(loader.worldX());actor.getStatus().getPosition().setPosY(loader.worldY());
            RailwayTrainUi.View view=(RailwayTrainUi.View)call(runtime,"trainSnapshot",new Class<?>[]{Creature.class,long.class},actor,engine.getWurmId());
            String expectedWeight=String.format(Locale.ROOT,"%,.1f kg",wagons*10000.0);
            check(view.cargoStatus.contains(expectedWeight)&&view.cargoStatus.contains((wagons*100)+" / "+(wagons*100)+" stacks")
                    &&view.cargoStatus.contains("litres"),"the actual train-controls snapshot reports payload, used slots and real volume");
            String bml=RailwayTrainUi.content(view);
            check(bml.contains(expectedWeight)&&bml.contains("stacks")&&bml.contains("litres"),"train controls render readable cargo limits instead of a hidden fifteen-shard stop");
            service.close();
        } finally {
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
        }
    }
    private static int count(List<Item> cars){int n=0;for(Item car:cars)n+=car.getItems().size();return n;}
    private static void tick(RailNetwork net,RailwayFreightService service,double elapsed)throws Exception{net.tick(elapsed);for(Train train:net.trains.values())apply(train);service.serviceStops(elapsed);}
    private static void apply(Train train)throws Exception{for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    private static void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static Object call(Object object,String name,Class<?>[] types,Object... values)throws Exception{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(object,values);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw(Exception)cause;throw failure;}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
