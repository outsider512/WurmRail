import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.DbItem;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.questions.Questions;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.RailNetwork.*;

/** Real inventories, two-ended transfer failures and an actual fill/unload route. */
public final class IndustrialWaterRuntimeSmoke {
    private static final long OWNER=998478592L;
    private static final List<Item> made=new ArrayList<Item>();
    private static int checks,insertMode,growthMode,qualityMode,qualityCalls;private static long insertTarget,growthTarget,rejectedId,qualityTarget;
    public static boolean rejectInsertion(Item parent,Item water) {
        if(parent.getWurmId()!=insertTarget||insertMode==0)return false;
        insertMode=0;rejectedId=water.getWurmId();return true;
    }
    public static boolean rejectGrowth(Item water,int requested) {
        if(water.getWurmId()!=growthTarget||growthMode!=1)return false;
        growthMode=0;return true;
    }
    public static void afterGrowth(Item water,int requested) {
        if(water.getWurmId()==growthTarget&&growthMode==2){growthMode=0;throw new IllegalStateException("Synthetic committed transfer notification failure");}
    }
    public static void afterQuality(Item water,float requested) {
        if(water.getWurmId()!=qualityTarget||qualityMode==0)return;
        qualityCalls++;
        if(qualityMode==1&&qualityCalls<=2)throw new IllegalStateException("Synthetic quality notification failure during transfer and rollback");
        if(qualityMode==2&&qualityCalls==1) {
            growthTarget=water.getWurmId();growthMode=1;
            throw new IllegalStateException("Synthetic quality failure before a refused destination rollback");
        }
    }
    public static void run()throws Exception {
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {storageAndTransfers();route();System.out.println("PASS native Industrial Water Tank logistics: real native storage and database contents, conservative two-ended transfers and rollback, exact optional consumer draws, ownership/range, captured controls and timed fill/unload route ("+checks+" assertions)");}
        finally {insertMode=0;growthMode=0;qualityMode=0;for(int i=made.size()-1;i>=0;i--)if(Items.getItemOptional(made.get(i).getWurmId()).isPresent())Items.destroyItem(made.get(i).getWurmId());com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;}
    }
    private static Item keep(Item item){made.add(item);return item;}
    private static Item placed(int template,int x,int y)throws Exception {
        Item item=keep(ItemFactory.createItem(template,70f,x*4+2f,y*4+2f,0f,true,(byte)0,-10L,"Industrial water fixture"));item.setLastOwnerId(OWNER);return item;
    }
    private static Item water(int grams,float q)throws Exception {
        Item water=keep(ItemFactory.createItem(ItemList.water,q,(byte)26,(byte)0,"Industrial water fixture"));water.setWeight(grams,false);water.setLastOwnerId(OWNER);return water;
    }
    private static long volume(Item tank){return LiquidTankPolicy.storedMillilitres(tank);}
    private static void storageAndTransfers()throws Exception {
        Item car=placed(RailwayTankCar.templateId,200,232),tank=placed(RailwayIndustrialWaterTank.templateId,201,232);
        check(tank.isContainerLiquid()&&tank.getContainerVolume()==100000000&&tank.getMaxItemCount()==1,"industrial storage uses one real liquid item and100000L native capacity");
        check(RailModelRegistry.modelName(tank.getTemplateId(),(byte)0,0).equals("model.wurmrail.industrialwatertank."),"industrial water tank has a selectable custom native model");
        check(com.wurmonline.server.items.CreationMatrix.getInstance().getAdvancedCreationEntry(tank.getTemplateId())!=null,"normal native construction recipe exists");
        Item shard=keep(ItemFactory.createItem(ItemList.rock,50f,"Water fixture"));check(!tank.insertItem(shard,false,true),"industrial tank rejects solid cargo");
        Item source=water(1000000,60);source.setDamage(10f);check(car.insertItem(source,false,true),"source car accepts one native water stack");
        long sourceId=source.getWurmId();
        LiquidTankTransfers.Result partial=LiquidTankTransfers.transfer(car,tank,200000);
        check(!partial.requiresOperatorIntervention&&partial.transferredMillilitres==200000&&volume(car)==800000&&volume(tank)==200000,"partial transfer conserves exact mass across both real inventories");
        Item stored=LiquidTankPolicy.contents(tank);check(stored.getWurmId()!=sourceId&&Math.abs(stored.getCurrentQualityLevel()-54)<.001,"native partial split preserves effective quality");
        DbItem alias=new DbItem(tank.getWurmId());LiquidTankTransfers.Result self=LiquidTankTransfers.transfer(tank,alias,1000);
        check(self.transferredMillilitres==0&&!self.requiresOperatorIntervention&&volume(tank)==200000&&volume(car)==800000,
                "separate DbItem objects for one native tank ID cannot self-transfer or change liquid mass");
        LiquidTankTransfers.Result rest=LiquidTankTransfers.transfer(car,tank,Long.MAX_VALUE);
        check(!rest.requiresOperatorIntervention&&rest.transferredMillilitres==800000&&car.getItems().isEmpty()&&volume(tank)==1000000,"merge drains source fully into the existing destination stack");
        check(LiquidTankPolicy.contents(tank)==stored&&!Items.getItemOptional(sourceId).isPresent(),"merge preserves the destinationID and removes only the exhausted source item");
        Item spare=placed(RailwayIndustrialWaterTank.templateId,202,232),whole=water(123456,77);
        check(car.insertItem(whole,false,true),"empty car accepts a new real water item");
        check(LiquidTankTransfers.transfer(car,spare,Long.MAX_VALUE).transferredMillilitres==123456&&LiquidTankPolicy.contents(spare)==whole,"whole-stack transfer to empty tank preserves original item identity");
        Item refill=water(1000,35);car.insertItem(refill,false,true);
        growthTarget=stored.getWurmId();growthMode=1;
        LiquidTankTransfers.Result refused=LiquidTankTransfers.transfer(car,tank,1000);
        check(refused.requiresOperatorIntervention&&refused.transferredMillilitres==0&&volume(car)==1000&&volume(tank)==1000000,"refused destination growth restores a transient zero-weight source without duplication or loss");
        check(Math.abs(stored.getCurrentQualityLevel()-54)<.001&&refill.getWeightGrams()==1000,"rollback restores original quality and exact source item");
        growthMode=2;
        LiquidTankTransfers.Result notified=LiquidTankTransfers.transfer(car,tank,500);
        check(notified.transferredMillilitres==500&&volume(car)+volume(tank)==1001000,"postcommit native notification cannot repeat or lose water");
        Item empty=placed(RailwayIndustrialWaterTank.templateId,203,232);
        insertTarget=empty.getWurmId();insertMode=1;
        LiquidTankTransfers.Result rejected=LiquidTankTransfers.transfer(car,empty,100);
        check(rejected.requiresOperatorIntervention&&rejected.transferredMillilitres==0&&volume(car)==500&&volume(empty)==0,"initial destination refusal restores source and cancels uncommitted split");
        check(rejectedId>0&&!Items.getItemOptional(rejectedId).isPresent(),"cancelled split leaves no orphan native water item");
        Item huge=water(80000000,80);check(empty.insertItem(huge,false,true)&&volume(empty)>LiquidTankPolicy.MAX_VOLUME_CC,"industrial tank stores substantially more water than a Tank Car");
        check(!huge.setWeight(100000001,false)&&volume(empty)==80000000,"industrial growth guard enforces its own100000L capacity");
        DbItem loaded=new DbItem(huge.getWurmId());check(loaded.getParentId()==empty.getWurmId()&&loaded.getWeightGrams()==80000000,"native database reload preserves industrial liquid amount and parent");
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(OWNER,true,2);
        Item consumer=placed(RailwaySteamRockDrill.templateId,205,232);actor.getStatus().getPosition().setPosX(consumer.getPosX());actor.getStatus().getPosition().setPosY(consumer.getPosY());
        MachineWaterSources.Link link=MachineWaterSources.link(actor,consumer,empty,OWNER);
        check(MachineWaterSources.available(link).millilitres==80000000,"optional consumer link reads actual water across nearby tiles");
        check(MachineWaterSources.request(link,90000000).transferredMillilitres==0&&volume(empty)==80000000,"insufficient exact draw consumes nothing");
        check(MachineWaterSources.request(link,1000000).transferredMillilitres==1000000&&volume(empty)==79000000,"authorized exact consumer draw removes only the requested real water");
        growthTarget=huge.getWurmId();growthMode=1;
        check(MachineWaterSources.request(link,1000).requiresOperatorIntervention&&volume(empty)==79000000,"refused consumer draw leaves original water intact");
        consumer.setPosXY(consumer.getPosX()+40,consumer.getPosY());
        check(!MachineWaterSources.available(link).ready()&&MachineWaterSources.request(link,1000).transferredMillilitres==0,"moved consumers cannot draw beyond link range");
        empty.setLastOwnerId(OWNER+256);check(MachineWaterSources.request(link,1000).transferredMillilitres==0,"ownership changes revoke consumer access");empty.setLastOwnerId(OWNER);
        RailwayIndustrialWaterTank.View status=RailwayIndustrialWaterTank.snapshot(empty);
        check(status.storedMillilitres==79000000&&status.capacityMillilitres==100000000&&RailwayIndustrialWaterTank.content(status).contains("Remaining capacity"),"status reports current quantity and remaining native capacity");
        rollbackAndMetadata();
    }
    private static void rollbackAndMetadata()throws Exception {
        Item car=placed(RailwayTankCar.templateId,206,232),tank=placed(RailwayIndustrialWaterTank.templateId,207,232);
        Item source=water(2000,90),destination=water(1000,50);source.setDamage(10f);destination.setDamage(20f);
        check(car.insertItem(source,false,true)&&tank.insertItem(destination,false,true),"two-ended failure fixture contains two actual liquid inventories");
        qualityTarget=destination.getWurmId();qualityMode=1;qualityCalls=0;
        LiquidTankTransfers.Result restored=LiquidTankTransfers.transfer(car,tank,1000);qualityMode=0;
        check(qualityCalls==2&&restored.requiresOperatorIntervention&&restored.transferredMillilitres==0,
                "quality notification faults execute both destination and source rollback work");
        check(volume(car)==2000&&volume(tank)==1000&&source.getDamage()==10f&&destination.getDamage()==20f
                &&source.getQualityLevel()==90f&&destination.getQualityLevel()==50f,
                "destination quality fault after its weight rollback cannot leave the source debited or lose water");
        check(!LiquidTankTransfers.isFaulted(car)&&!LiquidTankTransfers.isFaulted(tank),"fully verified rollback does not quarantine otherwise consistent tanks");
        qualityMode=2;qualityCalls=0;
        LiquidTankTransfers.Result retained=LiquidTankTransfers.transfer(car,tank,1000);qualityMode=0;
        check(retained.requiresOperatorIntervention&&retained.transferredMillilitres==1000&&volume(car)==1000&&volume(tank)==2000,
                "refused destination rollback never restores already credited water into its source");
        check(volume(car)+volume(tank)==3000&&LiquidTankTransfers.isFaulted(car)&&LiquidTankTransfers.isFaulted(tank),
                "unconfirmed two-ended recovery preserves total mass and quarantines both native tanks");
        DbItem persisted=new DbItem(tank.getWurmId());
        check(persisted.getAuxData()==99&&LiquidTankTransfers.isFaulted(persisted),"native recovery quarantine survives database reload");
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(OWNER,true,2);
        actor.getStatus().getPosition().setPosX(tank.getPosX());actor.getStatus().getPosition().setPosY(tank.getPosY());
        Item consumer=placed(RailwaySteamRockDrill.templateId,208,232);
        MachineWaterSources.Link link=new MachineWaterSources.Link(consumer.getWurmId(),tank.getWurmId(),OWNER);
        check(!MachineWaterSources.available(link).ready()&&MachineWaterSources.request(link,1).transferredMillilitres==0,
                "independent future consumers cannot draw from a quarantined destination");
        Item extra=water(1,70);
        check(!tank.canHold(extra)&&!tank.insertItem(extra,false,true),"quarantine blocks new pours and direct insertion");
        boolean refused=false;try{LiquidTankTransfers.clearFault(actor,tank);}catch(IllegalArgumentException expected){refused=true;}
        check(refused&&volume(tank)==2000,"recovery action never deletes or clears nonempty liquid storage");
        source.setWeight(0,true);destination.setWeight(0,true);
        TrackMaterialsSmoke.InventoryActor stranger=TrackMaterialsSmoke.actor(OWNER+256,true,0);
        stranger.getStatus().getPosition().setPosX(tank.getPosX());stranger.getStatus().getPosition().setPosY(tank.getPosY());
        refused=false;try{LiquidTankTransfers.clearFault(stranger,tank);}catch(IllegalArgumentException expected){refused=true;}
        check(refused&&LiquidTankTransfers.isFaulted(tank),"another player cannot clear a tank's recovery quarantine");
        actor.getStatus().getPosition().setPosX(tank.getPosX()+40);
        refused=false;try{LiquidTankTransfers.clearFault(actor,tank);}catch(IllegalArgumentException expected){refused=true;}
        check(refused,"even a GM must stand beside the empty tank to clear recovery");
        actor.getStatus().getPosition().setPosX(tank.getPosX());
        LiquidTankTransfers.clearFault(actor,tank);LiquidTankTransfers.clearFault(actor,car);
        check(!LiquidTankTransfers.isFaulted(tank)&&!LiquidTankTransfers.isFaulted(car)
                &&new DbItem(tank.getWurmId()).getAuxData()==0,"authorized empty recovery clears persistent flags without creating water");
        Item metadata=water(2000,73);metadata.setDamage(11f);metadata.setLeftAuxData(2);metadata.setName("Well reserve");
        metadata.setCreator("Fixture Maker");metadata.setDescription("Retained native water");metadata.setColor(0x112233);metadata.setColor2(0x334455);metadata.setTemperature((short)1234);
        check(car.insertItem(metadata,false,true),"supported native water with existing identity metadata enters the source car");
        LiquidTankTransfers.Result copied=LiquidTankTransfers.transfer(car,tank,1000);Item copy=LiquidTankPolicy.contents(tank);
        check(copied.transferredMillilitres==1000&&!copied.requiresOperatorIntervention&&LiquidTankPolicy.sameLiquid(metadata,copy),
                "partial water transfer copies the native auxiliary liquid identity");
        check(copy.getActualName().equals(metadata.getActualName())&&copy.getCreatorName().equals(metadata.getCreatorName())
                &&copy.getDescription().equals(metadata.getDescription())&&copy.getColor()==metadata.getColor()
                &&copy.getColor2()==metadata.getColor2()&&copy.getTemperature()==metadata.getTemperature()
                &&copy.getQualityLevel()==metadata.getQualityLevel()&&copy.getDamage()==metadata.getDamage(),
                "partial native split preserves name, creator, description, colors, temperature, quality and damage");
    }
    private static void route()throws Exception {
        final int X=201,Y=218;int[] before=new int[4];for(int i=0;i<4;i++)before[i]=Server.surfaceMesh.getTile(X+3+i%2,Y-2+i/2);
        Player actor=null;
        try {
            callStatic(WaterFillRuntimeSmoke.class,"ensureFixtureServer",new Class<?>[0]);
            actor=(Player)callStatic(WaterFillRuntimeSmoke.class,"player",new Class<?>[]{long.class,int.class,int.class},OWNER,X+12,Y);
            for(int i=0;i<4;i++)Server.surfaceMesh.setTile(X+3+i%2,Y-2+i/2,Tiles.encode((short)-5,Tiles.Tile.TILE_DIRT.id,(byte)0));
            RailNetwork net=new RailNetwork();for(int i=0;i<=16;i++){Node n=net.addNode("supply"+i,X+i,Y,0);n.markerId=keep(WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0)).getWurmId();if(i>0)net.connect("supply"+(i-1),n.name);}
            Node fill=net.nodes.get("supply3"),unload=net.nodes.get("supply12");net.setStation(fill.name,true,1);
            Item destination=placed(RailwayIndustrialWaterTank.templateId,X+12,Y+1);
            Item reserve=placed(RailwayIndustrialWaterTank.templateId,X+3,Y+1);check(reserve.insertItem(water(20000000,83),false,true),"stored native reserve supplies the route without a water-generating rail station");
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-industrial-water");Files.createDirectories(folder);
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"stateFile",folder.resolve("network.properties"));
            WaterUnloadStops liquidStops=(WaterUnloadStops)get(runtime,"waterUnloadStops");liquidStops.put(new WaterUnloadStops.Stop(fill.name,fill.markerId,OWNER,liquidStops.nextRevision(),"Stored supply",WaterUnloadStops.Operation.LOAD,reserve.getWurmId(),1000));
            call(runtime,"initService",new Class<?>[0]);
            RailwayWaterUnloadUi.View view=(RailwayWaterUnloadUi.View)call(runtime,"waterUnloadSnapshot",new Class<?>[]{com.wurmonline.server.creatures.Creature.class,long.class},actor,unload.markerId);
            check(view.canEdit&&!view.storages.isEmpty(),"ordinary owner can select a nearby industrial destination tank");
            Properties answer=new Properties();answer.setProperty("save","true");answer.setProperty("unload_name","Works water");answer.setProperty("storage_choice","1");answer.setProperty("unload_rate","1000");answer.setProperty("operation_choice","1");
            final RailwayWaterUnloadUi.Request[] captured={null};final int[] submissions={0};final RailwayWaterUnloadUi.View shown=view;
            RailwayWaterUnloadUi.Api api=new RailwayWaterUnloadUi.Api(){public RailwayWaterUnloadUi.View snapshot(com.wurmonline.server.creatures.Creature p,long id){return shown;}public void submit(com.wurmonline.server.creatures.Creature p,long id,RailwayWaterUnloadUi.Request r){submissions[0]++;captured[0]=r;}};
            RailwayWaterUnloadUi.show(actor,unload.markerId,api);Question question=latest(actor);question.answer(answer);question.answer(answer);
            check(submissions[0]==1,"native unload form answers are replay-safe");
            call(runtime,"applyWaterUnloadRequest",new Class<?>[]{com.wurmonline.server.creatures.Creature.class,long.class,RailwayWaterUnloadUi.Request.class},actor,unload.markerId,captured[0]);
            WaterUnloadStops stops=(WaterUnloadStops)get(runtime,"waterUnloadStops");
            check(stops.get(unload.name).storageId==destination.getWurmId()&&WaterUnloadStops.load(folder.resolve("waterunload.properties")).get(unload.name)!=null,"unload settings persist with captured real tank identity");
            Item engine=keep(WurmRailWorld.spawnLocomotive(actor,fill.worldX(),fill.worldY(),0)),car=keep(WurmRailWorld.spawnTankCar(actor,fill.worldX()-4,fill.worldY(),0));
            Train train=net.createTrain("WaterSupply",OWNER,Arrays.asList(engine.getWurmId(),car.getWurmId()),fill.name,"supply4");
            apply(train);
            @SuppressWarnings("unchecked") Map<String,String> catalog=(Map<String,String>)call(runtime,"routeStopCatalog",new Class<?>[]{Train.class},train);
            check(catalog.get(unload.name).contains("Works water"),"Water freight stop appears in the existing route catalog");
            net.setRoute(train.name,Arrays.asList(fill.name,unload.name),1);net.start(train.name);
            RailwayFreightService freight=(RailwayFreightService)get(runtime,"freightService");RailwayWaterUnloadService unloader=(RailwayWaterUnloadService)get(runtime,"waterUnloadService");
            boolean partial=false,restarted=false;
            for(int tick=0;tick<4000&&volume(destination)<20000000;tick++) {
                net.tick(.1);apply(train);freight.serviceStops(.1);unloader.serviceStops(.1);
                long quantity=volume(destination);
                if(quantity>0&&quantity<10000000)partial=true;
                if(partial&&!restarted) {
                    long quantityBefore=quantity;net.stop(train.name);unloader.serviceStops(3);
                    check(volume(destination)==quantityBefore,"STOP freezes unloading");
                    net.save(folder.resolve("pending.properties"));RailNetwork saved=RailNetwork.load(folder.resolve("pending.properties"));
                    check(saved.trains.get(train.name).serviceStopRail.equals(unload.name),"pending unload hold survives native rail-state persistence");
                    net.start(train.name);unloader.close();unloader=new RailwayWaterUnloadService(net,()->stops,new RailwayWaterUnloadWorld(net));unloader.serviceStops(5);
                    check(volume(destination)==quantityBefore,"restarted unload visits receive no instant credit");restarted=true;
                }
            }
            check(partial&&restarted&&volume(destination)==20000000,"normal route repeatedly transfers20,000L from stored water through a5,000L Tank Car into the same industrial tank");
            check(destination.getItems().size()==1&&LiquidTankPolicy.contents(destination).getWeightGrams()==20000000,"repeated route cycles grow one real destination water item");
            check(volume(reserve)==0,"stored source is depleted exactly once with no railway-generated water");
            check(LiquidTankPolicy.contentsProblem(destination)==null,"completed route leaves consistent native liquid links");
        } finally {
            for(int i=0;i<4;i++)Server.surfaceMesh.setTile(X+3+i%2,Y-2+i/2,before[i]);
            if(actor!=null)callStatic(PassengerWorldSmoke.class,"removePlayer",new Class<?>[]{Player.class},actor);
        }
    }
    private static void apply(Train train)throws Exception{for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    @SuppressWarnings("unchecked") private static Question latest(Player actor)throws Exception{Field f=Questions.class.getDeclaredField("questions");f.setAccessible(true);Question latest=null;for(Question q:((Map<Integer,Question>)f.get(null)).values())if(q.getResponder()==actor&&(latest==null||q.getId()>latest.getId()))latest=q;return latest;}
    private static void set(Object o,String n,Object value)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}
    private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw e;}}
    private static Object callStatic(Class<?> c,String n,Class<?>[] types,Object...args)throws Exception{Method m=c.getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(null,args);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
