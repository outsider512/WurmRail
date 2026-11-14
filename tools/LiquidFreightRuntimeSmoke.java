import com.wurmonline.server.Items;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.RailNetwork.*;
import org.wurmrail.core.WaterUnloadStops.Operation;
import org.wurmrail.core.WaterUnloadStops.Stop;

/** Real stored-water freight, with no natural water tile or railway producer. */
public final class LiquidFreightRuntimeSmoke {
    private static final long OWNER=(998488102L&~255L)|1L;
    private static final List<Item> made=new ArrayList<Item>();
    private static int checks;
    private LiquidFreightRuntimeSmoke(){ }
    public static void run()throws Exception {
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(OWNER,true,2);
            RailNetwork network=new RailNetwork();
            for(int i=0;i<=18;i++) {Node node=network.addNode("liquid"+i,190+i,202,0);node.markerId=keep(WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0)).getWurmId();if(i>0)network.connect("liquid"+(i-1),node.name);}
            Node loading=network.nodes.get("liquid6"),unloading=network.nodes.get("liquid14");
            network.setStation(loading.name,true,1);network.setStation(unloading.name,true,1);
            Item source=placed(RailwayIndustrialWaterTank.templateId,loading.tileX,loading.tileY-1);
            Item destination=placed(RailwayIndustrialWaterTank.templateId,unloading.tileX,unloading.tileY+1);
            Item reserve=water(10000000,79);reserve.setDamage(6);reserve.setName("Stored reserve");reserve.setDescription("Native water supply");reserve.setColor(0x112233);
            check(source.insertItem(reserve,false,true),"a real stored-water reserve starts the supply chain");
            Item engine=keep(WurmRailWorld.spawnLocomotive(actor,loading.worldX(),loading.worldY(),0));
            Item general=keep(WurmRailWorld.spawnCart(actor,loading.worldX()-4,loading.worldY(),0));
            Item log=keep(WurmRailWorld.spawnLogCar(actor,loading.worldX()-8,loading.worldY(),0));
            Item tank=keep(WurmRailWorld.spawnTankCar(actor,loading.worldX()-12,loading.worldY(),0));
            Train train=network.createTrain("StoredWaterSupply",OWNER,Arrays.asList(engine.getWurmId(),general.getWurmId(),log.getWurmId(),tank.getWurmId()),loading.name,"liquid7");apply(train);
            WaterUnloadStops stops=new WaterUnloadStops();
            Stop load=new Stop(loading.name,loading.markerId,OWNER,stops.nextRevision(),"Stored Water Load",Operation.LOAD,source.getWurmId(),100);
            Stop unload=new Stop(unloading.name,unloading.markerId,OWNER,stops.nextRevision(),"Stored Water Unload",Operation.UNLOAD,destination.getWurmId(),100);
            stops.put(load);stops.put(unload);
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-liquid-freight");Files.createDirectories(folder);
            Path definitions=folder.resolve("stops.properties");stops.save(definitions);
            check(WaterUnloadStops.load(definitions).get(load.rail).operation==Operation.LOAD&&WaterUnloadStops.load(definitions).get(unload.rail).operation==Operation.UNLOAD,"stored water LOAD and UNLOAD operations persist with their original route identities");
            RailwayWaterUnloadWorld world=new RailwayWaterUnloadWorld(network);
            check(world.unavailableReason(train,load)==null,"stored-water loading validates the actual stopped mixed consist and nearby owned tank: "+world.unavailableReason(train,load));
            blocked(world,train,load,source,tank);
            RailwayWaterUnloadService service=new RailwayWaterUnloadService(network,()->stops,world);network.routeStopServices=service::shouldService;
            network.setRoute(train.name,Arrays.asList(loading.name,unloading.name),1);network.resumeScheduled(train.name);tick(network,service,.1);
            check(train.serviceStopRail!=null&&volume(tank)==0&&volume(source)==10000000,"arrival begins with no instantly generated or transferred water");
            for(int i=0;i<20;i++)tick(network,service,.1);
            check(volume(tank)>0&&volume(tank)<=300000&&volume(source)+volume(tank)==10000000,"loading earns one shared100L/s budget and debits actual industrial storage");
            long paused=volume(tank);network.stop(train.name);for(int i=0;i<30;i++)tick(network,service,.1);
            check(volume(tank)==paused,"manual STOP pauses stored-water loading");
            Path saved=folder.resolve("paused.properties");network.save(saved);service.close();RailNetwork restarted=RailNetwork.load(saved);Train restored=restarted.trains.get(train.name);
            check(restored.carIds.equals(train.carIds)&&!restored.powered&&restored.serviceStopRail.equals(loading.name),"native mixed-consist IDs and paused liquid service hold survive restart");
            network.resumeScheduled(train.name);service=new RailwayWaterUnloadService(network,()->stops,world);network.routeStopServices=service::shouldService;service.serviceStops(5);
            check(volume(tank)==paused,"a restarted liquid visit receives no old/offline transfer allowance");
            boolean delivered=false,refilled=false;long total=10000000;
            for(int i=0;i<6500&&volume(destination)<total;i++) {
                tick(network,service,.1);
                check(volume(source)+volume(tank)+volume(destination)==total,"each committed rail-service step conserves the original finite water reserve");
                check(volume(tank)<=5000000,"every newly loaded Tank Car respects the reduced5,000L capacity");
                if(volume(destination)>0)delivered=true;
                if(delivered&&volume(tank)>0&&train.serviceStopRail!=null&&train.serviceStopRail.equals(loading.name))refilled=true;
            }
            check(delivered&&refilled&&volume(source)==0&&volume(tank)==0&&volume(destination)==total,"normal repeating routes complete storedtank→5,000Lcar→destination tank over multiple visits");
            check(general.getItems().isEmpty()&&log.getItems().isEmpty(),"solid-cargo wagons never receive native water");
            Item deliveredWater=LiquidTankPolicy.contents(destination);
            check(deliveredWater.getActualName().equals("Stored reserve")&&deliveredWater.getDescription().equals("Native water supply")&&deliveredWater.getColor()==0x112233,"native liquid identity metadata survives stored-water rail logistics");
            check(Math.abs(deliveredWater.getCurrentQualityLevel()-reserve.getCurrentQualityLevel())<.05,"native water quality remains consistent across conservative splits and merges");
            check(new DbItem(deliveredWater.getWurmId()).getParentId()==destination.getWurmId()&&new DbItem(deliveredWater.getWurmId()).getWeightGrams()==total,"final native destination parent and actual liquid weight persist");
            service.close();
            legacyOverfull(actor);
            System.out.println("PASS native liquid freight: finite stored Industrial Water Tank LOAD→5,000L Tank Car→UNLOAD chain, real quality/metadata and mass conservation, owned mixed consist, timed service, STOP/restart, full/locked/missing storage and safe legacy overcapacity draining ("+checks+" assertions)");
        }finally {
            for(int i=made.size()-1;i>=0;i--)if(Items.getItemOptional(made.get(i).getWurmId()).isPresent())Items.destroyItem(made.get(i).getWurmId());
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void blocked(RailwayWaterUnloadWorld world,Train train,Stop stop,Item source,Item tank)throws Exception {
        source.setLastOwnerId(OWNER+256);check(world.unavailableReason(train,stop)!=null,"foreign linked storage is unavailable");source.setLastOwnerId(OWNER);
        tank.setLastOwnerId(OWNER+256);check(world.unavailableReason(train,stop)!=null,"foreign Tank Cars are unavailable");tank.setLastOwnerId(OWNER);
        source.setPosXY(source.getPosX()+40,source.getPosY());check(world.unavailableReason(train,stop)!=null,"moved linked storage is unavailable");source.setPosXY(source.getPosX()-40,source.getPosY());
        Stop missing=new Stop(stop.rail,stop.markerId,OWNER,999,"Missing",Operation.LOAD,999999999999L,100);
        check(world.unavailableReason(train,missing)!=null&&world.unload(train,missing,100000).transferredMillilitres==0,"missing tanks create no water and fail safely");
    }
    private static void legacyOverfull(TrackMaterialsSmoke.InventoryActor actor)throws Exception {
        RailNetwork network=new RailNetwork();Node node=network.addNode("legacy",213,198,0);node.markerId=keep(WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0)).getWurmId();
        Node rear=network.addNode("legacyRear",212,198,0);rear.markerId=keep(WurmRailWorld.spawnTrackMarker(actor,rear.worldX(),rear.worldY(),0)).getWurmId();network.connect(node.name,rear.name);
        Node ahead=network.addNode("legacyAhead",214,198,0);ahead.markerId=keep(WurmRailWorld.spawnTrackMarker(actor,ahead.worldX(),ahead.worldY(),0)).getWurmId();network.connect(node.name,ahead.name);
        Item engine=keep(WurmRailWorld.spawnLocomotive(actor,node.worldX(),node.worldY(),0)),car=keep(WurmRailWorld.spawnTankCar(actor,rear.worldX(),rear.worldY(),0));
        Item tank=placed(RailwayIndustrialWaterTank.templateId,213,199),old=water(10000000,71);
        ((DbItem)car).addItem(old,true);old.setParentId(car.getWurmId(),false);
        check(volume(car)==10000000&&LiquidTankPolicy.contentsProblem(car)!=null&&LiquidTankPolicy.sourceContentsProblem(car)==null,"legacy10,000L cargo is retained and remains valid only as a drainable source");
        Train train=network.createTrain("LegacyWater",OWNER,Arrays.asList(engine.getWurmId(),car.getWurmId()),node.name,ahead.name);apply(train);
        Stop unload=new Stop(node.name,node.markerId,OWNER,1,"Legacy drain",Operation.UNLOAD,tank.getWurmId(),100);
        LiquidServiceTransfer result=new RailwayWaterUnloadWorld(network).unload(train,unload,10000000);
        check(result.finished&&!result.requiresOperatorIntervention&&result.transferredMillilitres==10000000&&volume(car)==0&&volume(tank)==10000000,"legacy overfilled Tank Cars unload every existing litre without truncation or duplication");
    }
    private static void tick(RailNetwork network,RailwayWaterUnloadService service,double elapsed)throws Exception{network.tick(elapsed);for(Train train:network.trains.values())apply(train);service.serviceStops(elapsed);}
    private static void apply(Train train)throws Exception{for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    private static Item keep(Item item){made.add(item);return item;}
    private static Item placed(int template,int x,int y)throws Exception{Item item=keep(ItemFactory.createItem(template,70,x*4+2f,y*4+2f,0,true,(byte)0,-10L,"Stored-water freight fixture"));item.setLastOwnerId(OWNER);return item;}
    private static Item water(int grams,float quality)throws Exception{Item water=keep(ItemFactory.createItem(ItemList.water,quality,(byte)26,(byte)0,"Stored-water freight fixture"));water.setWeight(grams,false);water.setLastOwnerId(OWNER);return water;}
    private static long volume(Item tank){return LiquidTankPolicy.storedMillilitres(tank);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
