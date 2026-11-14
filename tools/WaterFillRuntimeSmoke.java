import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.players.PlayerInfo;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.questions.Questions;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.ItemMaterials;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.RailNetwork.*;
import org.wurmrail.core.WaterFillStops.Stop;

/** Real player controls and native liquid inventories through the existing train route service. */
public final class WaterFillRuntimeSmoke {
    private static final int BASE=171,Y=226,SOURCE_X=BASE+4,SOURCE_Y=Y-2;
    private static final long NO_ID=-10L;
    private static int assertions;
    private static final List<Item> created=new ArrayList<Item>();
    private WaterFillRuntimeSmoke() { }
    public static void run()throws Exception {
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        int[] previous=new int[4];for(int i=0;i<4;i++)previous[i]=Server.surfaceMesh.getTile(SOURCE_X+i%2,SOURCE_Y+i/2);
        Player owner=null,other=null;
        try {
            ensureFixtureServer();
            water(true);owner=player(998469632L,BASE+4,Y);other=player(998469888L,BASE+4,Y);
            RailNetwork net=line(owner);Node loader=net.nodes.get("water4"),depot=net.nodes.get("water13");
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-water-route");Files.createDirectories(folder);
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"stateFile",folder.resolve("network.properties"));
            call(runtime,"initService",new Class<?>[0]);
            RailwayWaterFillUi.View initial=snapshot(runtime,owner,loader.markerId);
            check(owner.getPower()==0&&initial.canEdit&&initial.rateLitresPerSecond==100,"an ordinary real player can configure the default100L/s Water Fill Station");
            check(sourceIndex(initial)>0,"native configuration offers the exact nearby natural water tile");
            saveThroughQuestion(runtime,owner,loader.markerId,"Lake Water",5000);
            WaterFillStops stops=(WaterFillStops)get(runtime,"waterFillStops");Stop stop=stops.get(loader.name);
            check(stop!=null&&stop.ownerId==owner.getWurmId()&&stop.sourceX==SOURCE_X&&stop.sourceY==SOURCE_Y&&stop.rateLitresPerSecond==5000,
                    "captured native controls save the exact owner/source/rail identities and configured litre rate");
            check(WaterFillStops.load(folder.resolve("waterfill.properties")).get(loader.name).markerId==loader.markerId&&loader.station,
                    "station configuration uses durable checked state and the existing station arrival flag");

            Item engine=keep(WurmRailWorld.spawnLocomotive(owner,loader.worldX(),loader.worldY(),0));
            WurmRailWorld.moveCar(engine.getWurmId(),loader.worldX(),loader.worldY(),90,0);
            Item tankA=keep(WurmRailWorld.spawnTankCar(owner,net.nodes.get("water3").worldX(),loader.worldY(),0));
            Item wagon=keep(WurmRailWorld.spawnCart(owner,net.nodes.get("water2").worldX(),loader.worldY(),0));
            Item tankB=keep(WurmRailWorld.spawnTankCar(owner,net.nodes.get("water1").worldX(),loader.worldY(),0));
            RailwayTrainService trains=(RailwayTrainService)get(runtime,"trainService");Train train=trains.ensureTrain(owner,engine);
            position(owner,BASE+3,Y);trains.couple(owner,train,tankA);
            position(owner,BASE+2,Y);trains.couple(owner,train,wagon);
            position(owner,BASE+1,Y);trains.couple(owner,train,tankB);position(owner,loader.tileX,loader.tileY);
            check(train.carIds.equals(Arrays.asList(engine.getWurmId(),tankA.getWurmId(),wagon.getWurmId(),tankB.getWurmId())),
                    "normal native train setup couples Tank Cars and cargo wagons into one unchanged consist");
            Item shard=keep(ItemFactory.createItem(ItemList.rock,54f,"Water route cargo fixture"));
            check(wagon.insertItem(shard,false,true),"ordinary cargo wagon retains real solid cargo beside the liquid cars");
            Item depotStorage=keep(ItemFactory.createItem(ItemList.chestLarge,70f,depot.worldX(),depot.worldY()+4,0,true,
                    ItemMaterials.MATERIAL_WOOD_BIRCH,NO_ID,"Water route depot"));depotStorage.setLastOwnerId(owner.getWurmId());
            FreightStops freightStops=(FreightStops)get(runtime,"freightStops");
            freightStops.put(new FreightStops.Stop(depot.name,depot.markerId,owner.getWurmId(),freightStops.nextRevision(),"Goods Depot",
                    FreightStops.Operation.UNLOAD,depotStorage.getWurmId(),ItemList.rock,100));net.setStation(depot.name,true,1);
            RailwayTrainUi.View choices=(RailwayTrainUi.View)call(runtime,"trainSnapshot",new Class<?>[]{Creature.class,long.class},owner,engine.getWurmId());
            boolean waterOffered=false,freightOffered=false;
            for(RailwayTrainUi.Choice choice:choices.stations) {
                waterOffered|=choice.key.equals(loader.name)&&choice.label.contains("Lake Water")&&choice.label.contains("Water fill");
                freightOffered|=choice.key.equals(depot.name)&&choice.label.contains("Goods Depot");
            }
            check(waterOffered&&freightOffered,"the same normal route editor lists both named water filling and freight stops");
            RailwayWaterFillService waterService=(RailwayWaterFillService)get(runtime,"waterFillService");
            RailwayFreightService freightService=(RailwayFreightService)get(runtime,"freightService");
            net.setRoute(train.name,Arrays.asList(loader.name,depot.name),1);net.start(train.name);
            tick(net,waterService,freightService,.1);
            check(loader.name.equals(train.serviceStopRail)&&LiquidTankPolicy.storedMillilitres(tankA)==0&&LiquidTankPolicy.storedMillilitres(tankB)==0,
                    "native arrival holds the ordinary route without instant water and the freight handler leaves its hold alone");
            for(int i=0;i<10;i++)tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==5000000&&LiquidTankPolicy.storedMillilitres(tankB)==0,
                    "one earned second transfers one5000L allowance shared across all Tank Cars");
            Item first=LiquidTankPolicy.contents(tankA);check(first!=null&&first.isLiquid()&&first.getTemplateId()==ItemList.water&&first.getParentId()==tankA.getWurmId(),
                    "natural source creates one original native water item inside its Tank Car");
            long firstId=first.getWurmId();net.stop(train.name);for(int i=0;i<20;i++)tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==5000000&&!train.powered&&loader.name.equals(train.serviceStopRail),
                    "operator STOP pauses filling and retains the physical route stop");

            net.save(folder.resolve("mid-fill.properties"));waterService.close();freightService.close();
            net=RailNetwork.load(folder.resolve("mid-fill.properties"));train=net.trains.get(train.name);
            loader=net.nodes.get(loader.name);depot=net.nodes.get(depot.name);
            check(!train.powered&&loader.name.equals(train.serviceStopRail)&&train.carIds.size()==4,"mid-fill restart preserves the Tank Car consist and pending arrival");
            set(runtime,"network",net);call(runtime,"initService",new Class<?>[0]);
            waterService=(RailwayWaterFillService)get(runtime,"waterFillService");freightService=(RailwayFreightService)get(runtime,"freightService");
            net.resumeScheduled(train.name);tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==5000000&&LiquidTankPolicy.contents(tankA).getWurmId()==firstId,
                    "restart retains actual native water identity with zero catch-up allowance");
            for(int i=0;i<9;i++)tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==5000000,"resumed visit earns a full new transfer interval");
            tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==10000000&&LiquidTankPolicy.storedMillilitres(tankB)==0,
                    "resumed allowance grows the same native water stack rather than creating item-per-unit cargo");
            for(int i=0;i<20;i++)tick(net,waterService,freightService,.1);
            check(LiquidTankPolicy.storedMillilitres(tankA)==10000000&&LiquidTankPolicy.storedMillilitres(tankB)==10000000&&train.serviceStopRail==null,
                    "all compatible Tank Cars fill to their real10000L capacity before normal departure");
            check(tankA.getItems().size()==1&&tankB.getItems().size()==1&&LiquidTankPolicy.contents(tankA).getWurmId()==firstId,
                    "industrial liquid volume uses exactly one native item per Tank Car, retaining the original stack");
            check(shard.getParentId()==wagon.getWurmId()&&engine.getItems().isEmpty(),"water filling neither alters solids nor puts water into locomotives/cargo wagons");
            int index=train.routeIndex,visits=0;boolean unloaded=false;
            for(int i=0;i<1600&&visits<3;i++) {
                tick(net,waterService,freightService,.1);
                unloaded|=shard.getParentId()==depotStorage.getWurmId();
                if(index!=train.routeIndex){index=train.routeIndex;visits++;}
            }
            check(unloaded&&visits>=3&&train.powered&&train.automated,"the same train services freight, returns to water and continues its normal repeating itinerary");
            check(LiquidTankPolicy.storedMillilitres(tankA)==10000000&&LiquidTankPolicy.storedMillilitres(tankB)==10000000,
                    "ordinary freight unloading never unloads, duplicates or discards Tank Car liquids");
            // Wait for the next actual water arrival and remove the source before its next transfer.
            for(int i=0;i<1600&&!loader.name.equals(train.serviceStopRail);i++)tick(net,waterService,freightService,.1);
            check(loader.name.equals(train.serviceStopRail),"normal repeating route reaches the Water Fill Station again");
            water(false);for(int i=0;i<10;i++)tick(net,waterService,freightService,.1);
            check(train.serviceStopRail==null&&waterService.statusForRail(loader.name).contains("natural water source")
                    &&LiquidTankPolicy.storedMillilitres(tankA)==10000000,"dry source gives a clear reason and contributes no phantom liquid");
            net.stop(train.name);net.setRoute(train.name,Collections.<String>emptyList());water(true);position(owner,loader.tileX,loader.tileY);
            controls(runtime,owner,other,net,loader,train,folder);
            System.out.println("PASS native Water Fill Station runtime: ordinary-player captured controls, normal mixed-consist coupling/routes, native timed liquid stacks, STOP/restart, shared multi-tank allowance, water/freight isolation, natural-source failure and station edit/repair/removal guards ("+assertions+" assertions)");
        }finally {
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            for(Player player:new Player[]{owner,other})if(player!=null)removePlayer(player);
            for(int i=0;i<4;i++)Server.surfaceMesh.setTile(SOURCE_X+i%2,SOURCE_Y+i/2,previous[i]);
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void controls(WurmRailRuntime runtime,Player owner,Player other,RailNetwork net,Node loader,Train train,Path folder)throws Exception {
        RailwayWaterFillUi.View view=snapshot(runtime,owner,loader.markerId);
        RailwayWaterFillUi.Request save=RailwayWaterFillUi.fromAnswers(view,answers(view,"Lake Water",5000));
        check(!snapshot(runtime,other,loader.markerId).canEdit,"another ordinary player sees a read-only Water Fill Station");
        denied(()->apply(runtime,other,loader.markerId,save),"backend owner checks reject another player even with a captured owner's request");
        position(owner,loader.tileX+4,loader.tileY);denied(()->apply(runtime,owner,loader.markerId,save),"queued water settings revalidate current actor distance");position(owner,loader.tileX,loader.tileY);
        apply(runtime,owner,loader.markerId,save);denied(()->apply(runtime,owner,loader.markerId,save),"a repeated stale settings revision cannot overwrite a newer station configuration");
        view=snapshot(runtime,owner,loader.markerId);Properties remove=new Properties();remove.setProperty("remove","true");remove.setProperty("confirm_remove","true");
        final RailwayWaterFillUi.Request removal=RailwayWaterFillUi.fromAnswers(view,remove);
        net.setRoute(train.name,Arrays.asList(loader.name,"water13"),1);
        denied(()->apply(runtime,owner,loader.markerId,removal),"a Water Fill Station cannot be removed while any train route still refers to it");
        net.setRoute(train.name,Collections.<String>emptyList());
        denied(()->call(runtime,"removeAndRecover",new Class<?>[]{Creature.class,Node.class},owner,loader),"the configured station must be removed before recovering its underlying track");
        long oldMarker=loader.markerId;Items.destroyItem(oldMarker);
        call(runtime,"repairTrack",new Class<?>[]{Creature.class,Node.class},owner,loader);keep(Items.getItem(loader.markerId));
        WaterFillStops repaired=(WaterFillStops)get(runtime,"waterFillStops");
        check(loader.markerId!=oldMarker&&repaired.get(loader.name).markerId==loader.markerId
                &&WaterFillStops.load(folder.resolve("waterfill.properties")).get(loader.name).markerId==loader.markerId,
                "repairing missing native track preserves the station source/name and durably remaps its marker identity");
        check(Items.getItem(loader.markerId).getName().startsWith("Water:"),"repaired nearby marker retains its clear Water Fill Station name");
        denied(()->apply(runtime,owner,loader.markerId,removal),"old marker/revision answers cannot remove a repaired station");
        view=snapshot(runtime,owner,loader.markerId);RailwayWaterFillUi.Request currentRemoval=RailwayWaterFillUi.fromAnswers(view,remove);apply(runtime,owner,loader.markerId,currentRemoval);
        check(((WaterFillStops)get(runtime,"waterFillStops")).get(loader.name)==null&&!loader.station
                &&WaterFillStops.load(folder.resolve("waterfill.properties")).get(loader.name)==null,
                "confirmed unreferenced removal clears only the liquid definition and native station flag");
    }
    private static void saveThroughQuestion(final WurmRailRuntime runtime,final Player owner,long markerId,String name,double rate)throws Exception {
        final int[] submitted={0};RailwayWaterFillUi.Api api=new RailwayWaterFillUi.Api() {
            @Override public RailwayWaterFillUi.View snapshot(Creature actor,long marker)throws Exception{return WaterFillRuntimeSmoke.snapshot(runtime,actor,marker);}
            @Override public void submit(Creature actor,long marker,RailwayWaterFillUi.Request request){submitted[0]++;try{apply(runtime,actor,marker,request);}catch(Exception failure){throw new IllegalStateException(failure);}}
        };
        RailwayWaterFillUi.View view=snapshot(runtime,owner,markerId);RailwayWaterFillUi.show(owner,markerId,api);Question question=latest(owner);
        Properties answer=answers(view,name,rate);question.answer(answer);question.answer(answer);
        check(submitted[0]==1,"native ModQuestion replay cannot duplicate water configuration or source selection");
    }
    private static RailwayWaterFillUi.View snapshot(WurmRailRuntime runtime,Creature actor,long marker)throws Exception{return (RailwayWaterFillUi.View)call(runtime,"waterFillSnapshot",new Class<?>[]{Creature.class,long.class},actor,marker);}
    private static void apply(WurmRailRuntime runtime,Creature actor,long marker,RailwayWaterFillUi.Request request)throws Exception{call(runtime,"applyWaterFillRequest",new Class<?>[]{Creature.class,long.class,RailwayWaterFillUi.Request.class},actor,marker,request);}
    private static Properties answers(RailwayWaterFillUi.View view,String name,double rate){Properties p=new Properties();p.setProperty("save","true");p.setProperty("water_name",name);p.setProperty("water_rate",Double.toString(rate));p.setProperty("source_choice",Integer.toString(sourceIndex(view)));return p;}
    private static int sourceIndex(RailwayWaterFillUi.View view){for(int i=0;i<view.sources.size();i++){RailwayWaterFillUi.SourceChoice s=view.sources.get(i);if(s.x==SOURCE_X&&s.y==SOURCE_Y&&s.layer==0)return i+1;}return 0;}
    private static RailNetwork line(Creature actor)throws Exception{RailNetwork net=new RailNetwork();for(int i=0;i<=18;i++){Node n=net.addNode("water"+i,BASE+i,Y,0);Item marker=keep(WurmRailWorld.spawnTrackMarker(actor,n.worldX(),n.worldY(),0));n.markerId=marker.getWurmId();if(i>0)net.connect("water"+(i-1),n.name);}return net;}
    private static void tick(RailNetwork net,RailwayWaterFillService water,RailwayFreightService freight,double elapsed)throws Exception{net.tick(elapsed);for(Train train:net.trains.values())for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);freight.serviceStops(elapsed);water.serviceStops(elapsed);}
    private static void water(boolean wet){for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)Server.surfaceMesh.setTile(SOURCE_X+dx,SOURCE_Y+dy,Tiles.encode((short)(wet?-10:50),Tiles.Tile.TILE_DIRT.id,(byte)0));}
    private static Item keep(Item item){created.add(item);return item;}
    private static void ensureFixtureServer()throws Exception {
        // Native ordinary-container insertion resolves owners through Server;
        // provide only that lookup host without starting Steam or a socket.
        Field instance=Server.class.getDeclaredField("instance");instance.setAccessible(true);
        if(instance.get(null)==null){Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");field.setAccessible(true);Object allocator=field.get(null);instance.set(null,allocator.getClass().getMethod("allocateInstance",Class.class).invoke(allocator,Server.class));}
    }
    private static Player player(long id,int x,int y)throws Exception{Method method=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);method.setAccessible(true);Player player=(Player)method.invoke(null,id,x*4+2f,y*4+2f);Field power=PlayerInfo.class.getDeclaredField("power");power.setAccessible(true);power.setByte(player.getSaveFile(),(byte)0);player.getStatus().setLayer(0);player.getStatus().getPosition().setPosZ(5f,false);player.currentTile=Zones.getOrCreateTile(x,y,true);return player;}
    private static void removePlayer(Player player)throws Exception{Method method=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",Player.class);method.setAccessible(true);method.invoke(null,player);}
    private static void position(Creature actor,int x,int y){actor.getStatus().getPosition().setPosX(x*4+2f);actor.getStatus().getPosition().setPosY(y*4+2f);}
    @SuppressWarnings("unchecked") private static Question latest(Creature actor)throws Exception{Field field=Questions.class.getDeclaredField("questions");field.setAccessible(true);Question latest=null;for(Question q:((Map<Integer,Question>)field.get(null)).values())if(q.getResponder()==actor&&(latest==null||q.getId()>latest.getId()))latest=q;if(latest==null)throw new AssertionError("native Water Fill controls not registered");return latest;}
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception)throw(Exception)failure.getCause();throw failure;}}
    private interface Work{void run()throws Exception;}
    private static void denied(Work work,String message)throws Exception{boolean denied=false;try{work.run();}catch(IllegalArgumentException expected){denied=true;}check(denied,message);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
