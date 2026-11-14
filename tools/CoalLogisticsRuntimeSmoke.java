import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.*;
import com.wurmonline.shared.constants.ItemMaterials;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.FreightStops.*;
import org.wurmrail.core.RailNetwork.*;

/** Actual mineral Coal items through existing bulk storage, filters and railway service. */
public final class CoalLogisticsRuntimeSmoke {
    private static int checks;
    private static final long NO_ID=-10L;
    private CoalLogisticsRuntimeSmoke(){ }
    public static void run()throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(998494104L,true,2);
        List<Item> made=new ArrayList<Item>();RailNetwork net=new RailNetwork();RailwayFreightService service=null;
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            ItemTemplate coal=ItemTemplateFactory.getInstance().getTemplate(RailwayCoalItem.templateId);
            ItemTemplate charcoal=ItemTemplateFactory.getInstance().getTemplate(ItemList.charcoal);
            check(CoalItemRegistry.isCoal(coal.getTemplateId())&&coal.getTemplateId()!=ItemList.charcoal&&coal.getName().equals("coal"),"mineral Coal has a distinct persistent native template and name");
            check(coal.getWeightGrams()==20000&&coal.getVolume()==20000,"one mined Coal action is a physical20kg/20litre resource");
            check(charcoal.getTemplateId()==204&&charcoal.getName().equals("charcoal")&&charcoal.getWeightGrams()==500
                    &&charcoal.getSizeX()==5&&charcoal.getSizeY()==5&&charcoal.getSizeZ()==5
                    &&charcoal.getMaterial()==ItemMaterials.MATERIAL_COAL&&charcoal.getModelName().equals("model.resource.coal.")
                    &&charcoal.getDescriptionLong().equals("Black, sooty charcoal."),"vanilla charcoal identity, physics, appearance and description remain untouched");
            check(BulkHopperPolicy.allows(coal.getTemplateId())&&BulkHopperPolicy.allowedTemplateIds().contains(coal.getTemplateId())
                    &&BulkHopperPolicy.allows(ItemList.charcoal),"dynamic hopper whitelist includes Coal while retaining charcoal");
            List<String> ring=new ArrayList<String>();int index=0;
            for(int x=75;x<=85;x++)ring.add(node(net,actor,made,index++,x,220));
            for(int y=221;y<=224;y++)ring.add(node(net,actor,made,index++,85,y));
            for(int x=84;x>=75;x--)ring.add(node(net,actor,made,index++,x,224));
            for(int y=223;y>220;y--)ring.add(node(net,actor,made,index++,75,y));
            for(int i=0;i<ring.size();i++)net.connect(ring.get(i),ring.get((i+1)%ring.size()));
            Node load=net.nodes.get(ring.get(5)),unload=net.nodes.get(ring.get(19));
            Item source=placed(RailwayBulkHopper.templateId,actor,load.worldX(),load.worldY()-4,made);
            Item destination=placed(RailwayBulkHopper.templateId,actor,unload.worldX(),unload.worldY()+4,made);
            Item engine=WurmRailWorld.spawnLocomotive(actor,load.worldX(),load.worldY(),0);made.add(engine);
            Node rear=net.nodes.get(ring.get(4));Item logCar=WurmRailWorld.spawnLogCar(actor,rear.worldX(),rear.worldY(),0);made.add(logCar);
            rear=net.nodes.get(ring.get(3));Item cargo=WurmRailWorld.spawnCart(actor,rear.worldX(),rear.worldY(),0);made.add(cargo);
            rear=net.nodes.get(ring.get(2));Item tank=WurmRailWorld.spawnTankCar(actor,rear.worldX(),rear.worldY(),0);made.add(tank);
            Train train=net.createTrain("CoalFreight",actor.getWurmId(),Arrays.asList(engine.getWurmId(),logCar.getWurmId(),cargo.getWurmId(),tank.getWurmId()),load.name,ring.get(6));apply(train);
            Item heavy=item(coal.getTemplateId(),250000,81f,made),light=item(coal.getTemplateId(),50000,63f,made),other=item(ItemList.charcoal,500,55f,made);
            heavy.setDamage(7f);heavy.setRarity((byte)1);heavy.setColor(0x123456);heavy.setDataXY(21,47);
            String heavyProperties=fingerprint(heavy),lightProperties=fingerprint(light);long heavyId=heavy.getWurmId(),lightId=light.getWurmId();
            check(!heavy.isBulkItem()&&!heavy.isFood()&&!heavy.isLiquid()&&BulkHopperPolicy.eligible(heavy),"Coal remains an ordinary real physical bulk-compatible item");
            check(source.insertItem(heavy,false,true)&&source.insertItem(light,false,true)&&source.insertItem(other,false,true),"actual Bulk Hopper receives Coal and separately identified charcoal");
            Item removed=source.dropItem(lightId,false);
            check(removed==light&&!source.getItems().contains(light)&&light.getParentId()!=source.getWurmId(),"Coal can be removed from a Bulk Hopper without conversion");
            check(source.insertItem(light,false,true),"the identical removed Coal item can re-enter normal bulk storage");
            check(!logCar.testInsertItem(heavy)&&!logCar.insertItem(heavy,false,true)&&heavy.getParentId()==source.getWurmId()
                    &&!tank.testInsertItem(heavy),"timber and liquid cars reject Coal without disturbing the source parent");
            check(cargo.canHold(heavy)&&cargo.testInsertItem(heavy),"the existing general cargo wagon accepts mineral Coal");
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-coal-logistics");Files.createDirectories(folder);
            WurmRailRuntime runtime=new WurmRailRuntime();set(runtime,"network",net);set(runtime,"stateFile",folder.resolve("network.properties"));call(runtime,"initService",new Class<?>[0]);
            configure(runtime,actor,load,source,coal.getTemplateId(),Operation.LOAD);configure(runtime,actor,unload,destination,coal.getTemplateId(),Operation.UNLOAD);
            FreightStops stops=(FreightStops)get(runtime,"freightStops");
            check(stops.get(load.name).templateId==coal.getTemplateId()&&stops.get(unload.name).templateId==coal.getTemplateId(),"captured native freight controls accept an exact Coal template filter for both operations");
            service=new RailwayFreightService(net,()->stops,new RailwayFreightWorld(net));RailwayFreightService active=service;net.routeStopServices=active::shouldService;
            net.setRoute(train.name,Arrays.asList(load.name,unload.name),1);net.resumeScheduled(train.name);tick(net,service,.1);
            check(load.name.equals(train.serviceStopRail)&&heavy.getParentId()==source.getWurmId(),"Coal route uses the normal arrival hold without instant loading");
            for(int i=0;i<10;i++)tick(net,service,.1);
            check(heavy.getParentId()==source.getWurmId(),"a250kg Coal stack cannot move after only100kg of earned allowance");
            net.stop(train.name);int sourceCount=source.getItems().size();long lightParent=light.getParentId();
            for(int i=0;i<20;i++)tick(net,service,.1);
            check(source.getItems().size()==sourceCount&&light.getParentId()==lightParent&&!train.powered,"operator STOP conserves Coal inventories and pauses freight");
            Path networkFile=folder.resolve("pending.properties");net.save(networkFile);service.close();net=RailNetwork.load(networkFile);train=net.trains.get("CoalFreight");
            check(train.carIds.equals(Arrays.asList(engine.getWurmId(),logCar.getWurmId(),cargo.getWurmId(),tank.getWurmId()))&&!train.powered,"restart preserves native mixed-consist item IDs and stopped intent");
            service=new RailwayFreightService(net,()->stops,new RailwayFreightWorld(net));active=service;net.routeStopServices=active::shouldService;net.resumeScheduled(train.name);tick(net,service,.1);
            for(int i=0;i<20;i++)tick(net,service,.1);
            check(heavy.getParentId()==source.getWurmId(),"restarted Coal loading earns fresh weight credit");
            for(int i=0;i<20;i++)tick(net,service,.1);
            check(heavy.getParentId()==cargo.getWurmId()&&light.getParentId()==cargo.getWurmId()&&source.getItems().contains(other),"exact Coal filtering loads both original stacks into general freight while charcoal stays behind");
            check(logCar.getItems().isEmpty()&&tank.getItems().isEmpty()&&train.serviceStopRail==null,"Coal does not enter specialized timber/liquid cars and completed loading resumes the ordinary route");
            check(new DbItem(heavyId).getParentId()==cargo.getWurmId()&&new DbItem(lightId).getParentId()==cargo.getWurmId(),"native Coal cargo parent links persist in the Wurm database");
            int visits=0,cursor=train.routeIndex;boolean delivered=false;
            for(int i=0;i<2600&&visits<4;i++){tick(net,service,.1);if(cursor!=train.routeIndex){visits++;cursor=train.routeIndex;}delivered|=heavy.getParentId()==destination.getWurmId()&&light.getParentId()==destination.getWurmId();}
            check(delivered&&visits>=4&&train.powered,"repeating existing routes unload Coal to destination Bulk Hopper and continue normally");
            check(Items.getItem(heavyId)==heavy&&Items.getItem(lightId)==light&&destination.getItems().size()==2
                    &&heavyProperties.equals(fingerprint(heavy))&&lightProperties.equals(fingerprint(light)),"both original Coal IDs, weights, material, quality, damage, rarity and metadata survive transport unchanged");
            check(new DbItem(heavyId).getParentId()==destination.getWurmId()&&new DbItem(lightId).getParentId()==destination.getWurmId()
                    &&heavy.getWeightGrams()+light.getWeightGrams()==300000,"unloading persists exact destination parents and conserves total physical Coal mass");
            check(other.getTemplateId()==ItemList.charcoal&&other.getParentId()==source.getWurmId(),"charcoal is never relabelled, converted or matched as Coal");
            System.out.println("PASS native Coal logistics: distinct physical Coal and untouched charcoal, Bulk Hopper insertion/removal, exact filters, timed general cargo transport, specialized-car exclusion, STOP/restart, repeated route and unchanged item/DB properties ("+checks+" assertions)");
        }finally{if(service!=null)service.close();com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;for(int i=made.size()-1;i>=0;i--)if(Items.getItemOptional(made.get(i).getWurmId()).isPresent())Items.destroyItem(made.get(i).getWurmId());}
    }
    private static void configure(WurmRailRuntime runtime,Creature actor,Node rail,Item storage,int template,Operation operation)throws Exception {
        actor.getStatus().getPosition().setPosX(rail.worldX());actor.getStatus().getPosition().setPosY(rail.worldY());
        RailwayFreightUi.View view=(RailwayFreightUi.View)call(runtime,"freightSnapshot",new Class<?>[]{Creature.class,long.class},actor,rail.markerId);
        int choice=0;for(int i=0;i<view.storages.size();i++)if(view.storages.get(i).id==storage.getWurmId())choice=i+1;check(choice>0,"Coal stop discovers its owned native Bulk Hopper");
        Properties answers=new Properties();answers.setProperty("save","true");answers.setProperty("freight_name",operation==Operation.LOAD?"Coal source":"Coal works");answers.setProperty("operation_choice",operation==Operation.LOAD?"0":"1");answers.setProperty("storage_choice",Integer.toString(choice));answers.setProperty("filter_choice",operation==Operation.LOAD?"2":"1");answers.setProperty("template_id",operation==Operation.LOAD?"ignored stale input":Integer.toString(template));answers.setProperty("transfer_rate","100");
        RailwayFreightUi.Request request=RailwayFreightUi.fromAnswers(view,answers);
        check(request.templateId==template,"named Coal filter captures its stable template independently of stale numeric input; explicit template selection also works");
        call(runtime,"applyFreightRequest",new Class<?>[]{Creature.class,long.class,RailwayFreightUi.Request.class},actor,rail.markerId,request);
    }
    private static Item item(int template,int weight,float quality,List<Item> made)throws Exception{Item item=ItemFactory.createItem(template,quality,"Coal native logistics");item.setWeight(weight,true);made.add(item);return item;}
    private static Item placed(int template,Creature owner,double x,double y,List<Item> made)throws Exception{Item item=ItemFactory.createItem(template,65f,(float)x,(float)y,0,true,(byte)0,NO_ID,"Coal native logistics");item.setLastOwnerId(owner.getWurmId());made.add(item);return item;}
    private static String node(RailNetwork net,Creature owner,List<Item> made,int i,int x,int y)throws Exception{Node node=net.addNode("coalFreight"+i,x,y,0);Item marker=WurmRailWorld.spawnTrackMarker(owner,node.worldX(),node.worldY(),0);made.add(marker);node.markerId=marker.getWurmId();return node.name;}
    private static void apply(Train train)throws Exception{for(Pose pose:train.poses)WurmRailWorld.moveCar(pose.carId,pose.x,pose.y,pose.rotation,pose.layer);}
    private static void tick(RailNetwork net,RailwayFreightService service,double dt)throws Exception{net.tick(dt);for(Train t:net.trains.values())apply(t);service.serviceStops(dt);}
    private static String fingerprint(Item item){return item.getTemplateId()+":"+item.getMaterial()+":"+item.getWeightGrams()+":"+item.getQualityLevel()+":"+item.getDamage()+":"+item.getRarity()+":"+item.getColor()+":"+item.getAuxData()+":"+item.getDataX()+":"+item.getDataY()+":"+item.getCreatorName();}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
