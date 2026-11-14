import com.wurmonline.mesh.*;
import com.wurmonline.server.*;
import com.wurmonline.server.items.*;
import com.wurmonline.server.zones.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.*;
import org.wurmrail.core.SteamSupplies.*;

/** Paid work, not enabled-time: actual native fuels, tank water and useful machine output. */
public final class SteamConsumptionRuntimeSmoke {
    private static final long OWNER=998498304L;
    private static int checks;
    private static final List<Item> made=new ArrayList<Item>();
    private static TrackMaterialsSmoke.InventoryActor actor;
    private static SteamSupplyController supplies;
    private static Path file;
    public static void run()throws Exception {
        Field flags=Server.class.getDeclaredField("flagsMesh");flags.setAccessible(true);
        MeshIO oldResource=Server.resourceMesh,oldFlags=(MeshIO)flags.get(null);
        boolean parents=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        int[] surface=new int[256*256],cave=new int[surface.length],rock=new int[surface.length];
        Field waterTypes=WaterType.class.getDeclaredField("waterSurface");waterTypes.setAccessible(true);
        Field modifiers=Field.class.getDeclaredField("modifiers");modifiers.setAccessible(true);modifiers.setInt(waterTypes,waterTypes.getModifiers()&~Modifier.FINAL);
        Object oldWaterTypes=waterTypes.get(null);byte[][] lakeTypes=new byte[256][256];for(byte[] row:lakeTypes)Arrays.fill(row,WaterType.LAKE);waterTypes.set(null,lakeTypes);
        for(int i=0;i<surface.length;i++){surface[i]=Server.surfaceMesh.getTile(i%256,i/256);cave[i]=Server.caveMesh.getTile(i%256,i/256);rock[i]=Server.rockMesh.getTile(i%256,i/256);}
        try {
            Server.resourceMesh=MeshIO.createMap("steam_consumption_resources.map",8,new int[256*256]);
            flags.set(null,MeshIO.createMap("steam_consumption_flags.map",8,new int[256*256]));
            actor=TrackMaterialsSmoke.actor(OWNER,true,2);
            file=Paths.get(ServerDirInfo.getFileDBPath()).resolve("steam-consumption/supplies.properties");
            supplies=new SteamSupplyController(file,SteamSupplyConfig.defaults());
            selfFuelDrill();idleYarder();volumePump();recovery();
            System.out.println("PASS native steam consumption: Coal self-fuel shared output, real water/fuel buffers, NO FUEL/NO WATER automatic delivery recovery, idle forestry and output backpressure free, actual-volume fuel-only pump, STOP, receipt/refill restart and strict balance-file recovery ("+checks+" assertions)");
        }finally{
            for(int i=made.size()-1;i>=0;i--)if(Items.getItemOptional(made.get(i).getWurmId()).isPresent())Items.destroyItem(made.get(i).getWurmId());
            for(int i=0;i<surface.length;i++){Server.surfaceMesh.setTile(i%256,i/256,surface[i]);Server.caveMesh.setTile(i%256,i/256,cave[i]);Server.rockMesh.setTile(i%256,i/256,rock[i]);}
            Server.resourceMesh.close();((MeshIO)flags.get(null)).close();Server.resourceMesh=oldResource;flags.set(null,oldFlags);
            waterTypes.set(null,oldWaterTypes);
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=parents;
        }
    }
    private static void selfFuelDrill()throws Exception {
        int x=230,y=60;Server.caveMesh.setTile(x,y,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)60));
        Server.caveMesh.setTile(x,y-1,Tiles.encode((short)20,org.wurmrail.shared.CoalVeinTiles.TILE_TYPE,(byte)60));Server.setCaveResource(x,y-1,20);Zones.setMiningState(x,y-1,(byte)0,false);
        Item machine=place(RailwaySteamRockDrill.templateId,x,y,false),hopper=place(RailwayBulkHopper.templateId,x,y,false),tank=place(RailwayIndustrialWaterTank.templateId,x,y,false);near(machine);
        link(machine,hopper,tank,true);
        RailwayDrillController controller=new RailwayDrillController(file.resolveSibling("drill.properties"),1,supplies);
        controller.state().put(new SteamRockDrills.Drill(machine.getWurmId(),OWNER,controller.state().nextRevision(),x,y,-1,hopper.getWurmId(),1,0,false,"Saved test settings."));controller.start(actor,machine.getWurmId());controller.tick(1);
        check(controller.state().get(machine.getWurmId()).running&&controller.state().get(machine.getWurmId()).status.contains("NO FUEL")&&Server.getCaveResource(x,y-1)==20,"unfueled enabled drill waits without mining");
        Item seed=fuel(hopper,RailwayCoalItem.templateId,20000);controller.tick(1);
        check(controller.state().get(machine.getWurmId()).status.contains("NO WATER")&&seed.getWeightGrams()==20000&&Server.getCaveResource(x,y-1)==20,"missing water cannot burn planned fuel or create free Coal");
        water(tank,100000);controller.tick(1);Supply s=supplies.state().get(machine.getWurmId());
        check(Server.getCaveResource(x,y-1)==19&&s.energyMicro==50000000&&s.waterMillilitres==90000,"one completed action pays10energy and10L from actual native supplies");
        check(seed.getWeightGrams()==10000&&BulkHopperPolicy.storedWeightGrams(hopper)==30000&&tank.getItems().isEmpty(),"Coal output and fuel share the same hopper with positive net production and a paid water buffer");
        for(int i=0;i<9;i++)controller.tick(1);int count=Server.getCaveResource(x,y-1);s=supplies.state().get(machine.getWurmId());long energy=s.energyMicro;
        controller.tick(1);check(Server.getCaveResource(x,y-1)==count&&supplies.state().get(machine.getWurmId()).energyMicro==energy&&controller.state().get(machine.getWurmId()).status.contains("NO WATER"),"empty water buffer pauses with no further fuel cost");
        water(tank,50000);controller.tick(1);check(Server.getCaveResource(x,y-1)==count-1&&controller.state().get(machine.getWurmId()).running,"a water delivery resumes an enabled self-fueling drill without Start");
        controller.stop(actor,machine.getWurmId());count=Server.getCaveResource(x,y-1);controller.tick(100);check(Server.getCaveResource(x,y-1)==count,"manual STOP cannot spend supplies or mine");
    }
    private static void idleYarder()throws Exception {
        int x=220,y=90;for(int dx=-6;dx<=6;dx++)for(int dy=-6;dy<=6;dy++){Server.surfaceMesh.setTile(x+dx,y+dy,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));Server.rockMesh.setTile(x+dx,y+dy,Tiles.encode((short)0,Tiles.Tile.TILE_ROCK.id,(byte)0));}
        Item machine=place(RailwaySteamYarder.templateId,x,y,true),bunk=place(RailwayLogBunk.templateId,x,y,true),chest=place(ItemList.chestLarge,x,y,true),tank=place(RailwayIndustrialWaterTank.templateId,x,y,true);near(machine);
        Item wood=fuel(chest,ItemList.log,20000),wet=water(tank,100000);link(machine,chest,tank,true);
        RailwayYarderController controller=new RailwayYarderController(file.resolveSibling("yarder.properties"),1,supplies);
        controller.state().put(new SteamYarders.Yarder(machine.getWurmId(),OWNER,controller.state().nextRevision(),x,y,0,bunk.getWurmId(),TreeData.TreeType.PINE.getTypeId(),SteamYarders.HarvestAge.OLD,0,false,"Saved test settings."));controller.start(actor,machine.getWurmId());
        for(int i=0;i<20;i++)controller.tick(1);Supply s=supplies.state().get(machine.getWurmId());
        check(wood.getWeightGrams()==20000&&wet.getWeightGrams()==100000&&s.energyMicro==0&&s.waterMillilitres==0&&controller.state().get(machine.getWurmId()).running,"an idle woodlot neither refills nor consumes fuel/water while remaining enabled");
        tree(x,y-1);controller.tick(1);s=supplies.state().get(machine.getWurmId());
        check(bunk.getItems().size()==1&&s.energyMicro==10000000&&s.waterMillilitres==95000,"one actual harvest consumes10wood-energy and5L, leaving paid buffered supplies");
        long energy=s.energyMicro,ml=s.waterMillilitres;for(int i=0;i<20;i++)controller.tick(1);
        check(supplies.state().get(machine.getWurmId()).energyMicro==energy&&supplies.state().get(machine.getWurmId()).waterMillilitres==ml,"waiting for replanted trees never burns buffered supplies");
        Item heavy=ItemFactory.createItem(ItemList.log,50,"Backpressure fixture");made.add(heavy);heavy.setWeight((int)(LogBunkPolicy.MAX_WEIGHT_GRAMS-LogBunkPolicy.storedWeightGrams(bunk)),true);heavy.setSizes(1,1,1);check(bunk.insertItem(heavy,true),"the actual output Bunk reaches its weight limit");
        tree(x+1,y);int before=Server.surfaceMesh.getTile(x+1,y);controller.tick(1);
        check(Server.surfaceMesh.getTile(x+1,y)==before&&supplies.state().get(machine.getWurmId()).energyMicro==energy&&supplies.state().get(machine.getWurmId()).waterMillilitres==ml,"OUTPUT FULL costs no fuel/water and leaves the tree untouched");
        bunk.dropItem(heavy.getWurmId(),false);Items.destroyItem(heavy.getWurmId());controller.tick(1);
        check(Server.surfaceMesh.getTile(x+1,y)!=before&&supplies.state().get(machine.getWurmId()).waterMillilitres==ml-5000,"freed output resumes harvesting automatically and charges one action");controller.stop(actor,machine.getWurmId());
    }
    private static void volumePump()throws Exception {
        int x=240,y=90;for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++)Server.surfaceMesh.setTile(x+dx,y+dy,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));for(int dx=0;dx<=1;dx++)for(int dy=0;dy<=1;dy++)Server.surfaceMesh.setTile(x+dx,y-2+dy,Tiles.encode((short)-20,Tiles.Tile.TILE_DIRT.id,(byte)0));
        Item machine=place(RailwaySteamWaterPump.templateId,x,y,true),chest=place(ItemList.chestLarge,x,y,true),tank=place(RailwayIndustrialWaterTank.templateId,x+1,y,true);near(machine);Item charcoal=fuel(chest,ItemList.charcoal,20000),wet=water(tank,IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS);link(machine,chest,null,false);
        RailwayPumpController controller=new RailwayPumpController(file.resolveSibling("pump.properties"),100,supplies);
        controller.state().put(new SteamWaterPumps.Pump(machine.getWurmId(),OWNER,controller.state().nextRevision(),x,y,0,tank.getWurmId(),x,y-2,0,false,"Saved test settings."));controller.start(actor,machine.getWurmId());controller.tick(1);
        check(charcoal.getWeightGrams()==20000&&supplies.state().get(machine.getWurmId()).energyMicro==0,"full pump output cannot refill or burn fuel");
        wet.setWeight(IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS-25000,true);controller.tick(1);Supply s=supplies.state().get(machine.getWurmId());
        check(wet.getWeightGrams()==IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS&&s.energyMicro==59500000&&s.waterId==0&&s.waterMillilitres==0,"only25delivered litres cost0.5energy, using charcoal and no boiler-water tank");
        controller.tick(30);check(supplies.state().get(machine.getWurmId()).energyMicro==59500000,"enabled full-tank waiting is free and creates no fuel backlog");
        LiquidTankTransfers.withdrawExact(tank,100000);controller.tick(1);check(wet.getWeightGrams()==IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS&&supplies.state().get(machine.getWurmId()).energyMicro==57500000,"pump automatically resumes and pays exactly100delivered litres");
        for(int i=0;i<28;i++){LiquidTankTransfers.withdrawExact(tank,100000);controller.tick(1);}
        LiquidTankTransfers.withdrawExact(tank,100000);controller.tick(1);
        check(controller.state().get(machine.getWurmId()).running&&controller.state().get(machine.getWurmId()).status.contains("NO FUEL")&&wet.getWeightGrams()==IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS-100000&&supplies.state().get(machine.getWurmId()).energyMicro==1500000,"exhausted paid fuel pauses pumping without producing unpaid water");
        fuel(chest,ItemList.charcoal,1000);controller.tick(1);
        check(wet.getWeightGrams()==IndustrialWaterTankRegistry.MAX_WEIGHT_GRAMS&&supplies.state().get(machine.getWurmId()).energyMicro==2500000,"a small fuel delivery resumes the enabled pump automatically and charges only its actual work");
        controller.stop(actor,machine.getWurmId());
        SteamInputs.Payment cancelled=supplies.work(machine,OWNER,1,0).reserve(1);long reserved=supplies.state().get(machine.getWurmId()).energyMicro;cancelled.refund();cancelled.refund();
        check(supplies.state().get(machine.getWurmId()).energyMicro==reserved+1000000,"known cancelled work refunds once without minting duplicate credit");
    }
    private static void recovery()throws Exception {
        Item machine=place(RailwaySteamWaterPump.templateId,240,95,true),chest=place(ItemList.chestLarge,240,95,true);near(machine);Item coal=fuel(chest,RailwayCoalItem.templateId,20000);link(machine,chest,null,false);
        Supply s=supplies.state().get(machine.getWurmId());List<Debit> debits=Collections.singletonList(new Debit(coal.getWurmId(),chest.getWurmId(),coal.getTemplateId(),20000,19000,6000,false));
        supplies.state().put(s.balances(0,0,0,0,0,1,false,"Pending native debit fixture.",debits));supplies.save();coal.setWeight(19000,false,true);
        SteamSupplyController restarted=new SteamSupplyController(file,SteamSupplyConfig.defaults());
        check(restarted.state().get(machine.getWurmId()).energyMicro==6000000&&coal.getWeightGrams()==19000,"restart credits only the exact physical fuel debit already recorded");
        restarted=new SteamSupplyController(file,SteamSupplyConfig.defaults());check(restarted.state().get(machine.getWurmId()).energyMicro==6000000,"reopening a completed refill cannot replay its native fuel credit");
        restarted.work(machine,OWNER,1,0).reserve(1);SteamSupplyController uncertain=new SteamSupplyController(file,SteamSupplyConfig.defaults());
        check(uncertain.state().get(machine.getWurmId()).fault&&uncertain.state().get(machine.getWurmId()).energyMicro==5000000,"an interrupted work reservation never refunds potentially completed work blindly");
        byte[] valid=Files.readAllBytes(file);Files.write(file,"corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));boolean rejected=false;try{new SteamSupplyController(file,SteamSupplyConfig.defaults());}catch(java.io.IOException expected){rejected=true;}finally{Files.write(file,valid);}
        check(rejected,"an older backup balance cannot resurrect fuel or water after primary-file corruption");
    }
    private static void tree(int x,int y){Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,TreeData.TreeType.BIRCH.asNormalTree(),Tiles.encodeTreeData(FoliageAge.OLD_ONE,false,true,GrassData.GrowthTreeStage.SHORT)));}
    private static Item place(int template,int x,int y,boolean surface)throws Exception{Item item=ItemFactory.createItem(template,70,x*4+2f,y*4+2f,0,surface,(byte)0,-10L,"Steam consumption fixture");item.setLastOwnerId(OWNER);made.add(item);return item;}
    private static Item fuel(Item parent,int template,int grams)throws Exception{Item item=ItemFactory.createItem(template,70,"Steam consumption fixture");item.setWeight(grams,false);made.add(item);if(!parent.insertItem(item,true))throw new AssertionError("fuel fixture insertion");return item;}
    private static Item water(Item tank,int ml)throws Exception{Item item=ItemFactory.createItem(ItemList.water,100,"Steam consumption fixture");item.setSizes(1,1,1);item.setWeight(ml,false);made.add(item);if(!tank.insertItem(item,true))throw new AssertionError("water fixture insertion");return item;}
    private static void near(Item machine)throws Exception{actor.getStatus().setLayer(machine.isOnSurface()?0:-1);actor.getStatus().getPosition().setPosX(machine.getPosX());actor.getStatus().getPosition().setPosY(machine.getPosY());actor.getStatus().getPosition().setPosZ(Zones.calculateHeight(machine.getPosX(),machine.getPosY(),machine.isOnSurface()),false);}
    private static void link(Item machine,Item fuel,Item water,boolean required){SteamSupplyUi.View view=supplies.snapshot(actor,machine,OWNER,required);supplies.configure(actor,machine,OWNER,required,new SteamSupplyUi.Selection(view.revision,fuel.getWurmId(),water==null?0:water.getWurmId()));}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
