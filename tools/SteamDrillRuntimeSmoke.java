import com.wurmonline.mesh.MeshIO;
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
import org.wurmrail.core.SteamRockDrills;
import org.wurmrail.core.SteamRockDrills.Drill;

/** Ordinary-player GUI controls through native cave depletion and durable real-item output. */
public final class SteamDrillRuntimeSmoke {
    private static int assertions;
    private static final int X=211,Y=184;
    private static final long NO_ID=-10L;
    private static final int[] DX={0,1,0,-1},DY={-1,0,1,0};
    private static final List<Item> created=new ArrayList<Item>();
    private SteamDrillRuntimeSmoke() { }
    public static void run()throws Exception {
        MeshIO oldResources=Server.resourceMesh;boolean[][] oldProtected=Zones.protectedTiles;
        int[] oldCave=new int[49];for(int at=0;at<49;at++)oldCave[at]=Server.caveMesh.getTile(X-3+at%7,Y-3+at/7);
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        Player owner=null,other=null;
        try {
            int[] resources=new int[256*256];Arrays.fill(resources,0xffff0000|4321);
            Server.resourceMesh=MeshIO.createMap(Paths.get("steam_drill_controller_resources.map").toAbsolutePath().toString(),8,resources);
            Zones.protectedTiles=new boolean[256][256];
            for(int x=X-3;x<=X+3;x++)for(int y=Y-3;y<=Y+3;y++)Server.caveMesh.setTile(x,y,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50));
            owner=player(998467072L);other=player(998467328L);
            Item machine=placed(RailwaySteamRockDrill.templateId,X,Y,owner.getWurmId());
            Item hopper=placed(RailwayBulkHopper.templateId,X-1,Y,owner.getWurmId());
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-steam-drill");Files.createDirectories(folder);
            Path file=folder.resolve("drills.properties");
            RailwayDrillController controller=new RailwayDrillController(file,30);
            SteamTestSupplies.provision(controller.supplies(),owner,machine,true,created);
            wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,3);wall(1,Tiles.Tile.TILE_CAVE_WALL_ORE_COPPER.id,1);wall(2,Tiles.Tile.TILE_CAVE_WALL_ORE_GOLD.id,3);
            for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1}) {
                Server.caveMesh.setTile(X+dx,Y+dy,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE_WALL_ORE_TIN.id,(byte)50));Server.setCaveResource(X+dx,Y+dy,17);
            }
            RailwaySteamRockDrill.View view=controller.snapshot(owner,machine.getWurmId());
            check(owner.getPower()==0&&view.canControl&&view.availableMask==7,"an ordinary actual player owns and configures the three available cardinal faces");
            check(view.hoppers.size()==1&&view.hoppers.get(0).id==hopper.getWurmId(),"explicit configuration discovers the compatible real hopper through normal native access checks");
            startThroughQuestion(controller,owner,machine,hopper,7);
            check(controller.state().get(machine.getWurmId()).running&&SteamRockDrills.load(file).get(machine.getWurmId()).running,"one captured native GUI Start persists the running machine setting");
            int count=hopper.getItems().size();seconds(controller,29);check(hopper.getItems().size()==count,"GUI-started drill produces nothing before thirty earned seconds");
            controller.tick(1);newResource(hopper,count++,ItemList.ironOre,X,Y-1);
            seconds(controller,30);newResource(hopper,count++,ItemList.copperOre,X+1,Y);
            check(Server.getCaveResource(X+1,Y)==0&&(controller.snapshot(owner,machine.getWurmId()).availableMask&2)==0,"a depleted East vein becomes unavailable without opening a tunnel");
            seconds(controller,30);newResource(hopper,count++,ItemList.goldOre,X,Y+1);
            check(controller.state().get(machine.getWurmId()).nextFace==3,"native output actions preserve the round-robin cursor");
            seconds(controller,24);long revision=controller.state().get(machine.getWurmId()).revision;
            controller=new RailwayDrillController(file,30);seconds(controller,29);
            check(hopper.getItems().size()==count&&controller.state().get(machine.getWurmId()).revision==revision,"restart retains controls and cursor but grants no previous or offline production time");
            controller.tick(1);newResource(hopper,count++,ItemList.ironOre,X,Y-1);
            seconds(controller,30);newResource(hopper,count++,ItemList.goldOre,X,Y+1);
            seconds(controller,30);newResource(hopper,count++,ItemList.ironOre,X,Y-1);
            seconds(controller,30);newResource(hopper,count++,ItemList.goldOre,X,Y+1);
            Drill exhausted=controller.state().get(machine.getWurmId());
            check(exhausted.running&&exhausted.nextFace==3&&exhausted.status.contains("No enabled mineable faces remain"),"the final native resource action waits for valid work with its advanced cursor");
            seconds(controller,90);check(hopper.getItems().size()==count,"exhausted native drills stay stopped rather than producing infinite resources");
            for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1})check(Server.getCaveResource(X+dx,Y+dy)==17,"scheduled production leaves all diagonal veins untouched");
            controls(controller,owner,other,machine,hopper,file);
            failures(controller,owner,machine,hopper,file);
            System.out.println("PASS native Steam Rock Drill lifecycle: actual non-GM player and captured GUI answers, thirty-second real ore output, cardinal round-robin and depletion, hopper linking, durable running/cursor restart, STOP/stale controls, ownership/range/anchor guards and persistence-fault item conservation ("+assertions+" assertions)");
        }finally {
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            for(Player player:new Player[]{owner,other})if(player!=null)removePlayer(player);
            for(int at=0;at<49;at++)Server.caveMesh.setTile(X-3+at%7,Y-3+at/7,oldCave[at]);
            for(int face=0;face<4;face++)Zones.setMiningState(X+DX[face],Y+DY[face],(byte)0,false);
            Server.resourceMesh.close();Server.resourceMesh=oldResources;Zones.protectedTiles=oldProtected;
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void startThroughQuestion(final RailwayDrillController controller,final Player owner,Item machine,Item hopper,int mask)throws Exception {
        final int[] submitted={0};
        RailwaySteamRockDrill.Api api=new RailwaySteamRockDrill.Api() {
            @Override public RailwaySteamRockDrill.View snapshot(Creature actor,long id){return controller.snapshot(actor,id);}
            @Override public void submit(Creature actor,long id,RailwaySteamRockDrill.Request request) {
                submitted[0]++;try{controller.apply(actor,id,request);}catch(Exception failure){throw new IllegalStateException(failure);}
            }
            @Override public void interact(Creature actor,long id,RailwaySteamRockDrill.Interaction interaction){throw new AssertionError("unexpected direct interaction");}
        };
        RailwaySteamRockDrill.View view=controller.snapshot(owner,machine.getWurmId());
        RailwaySteamRockDrill.show(owner,machine.getWurmId(),api);Question question=latestQuestion(owner);
        Properties answer=answers(view,hopper.getWurmId(),mask,"start");question.answer(answer);question.answer(answer);
        check(submitted[0]==1,"a native ModQuestion answer replay cannot restart the timer or duplicate configuration commits");
    }
    private static void controls(RailwayDrillController controller,Player owner,Player other,Item machine,Item hopper,Path file)throws Exception {
        wall(0,Tiles.Tile.TILE_CAVE_WALL_ORE_IRON.id,9);wall(1,Tiles.Tile.TILE_CAVE.id,0);wall(2,Tiles.Tile.TILE_CAVE.id,0);
        configure(controller,owner,machine,hopper,1,"start");seconds(controller,29);
        RailwaySteamRockDrill.View beforeStop=controller.snapshot(owner,machine.getWurmId());
        RailwaySteamRockDrill.Request stale=RailwaySteamRockDrill.fromAnswers(beforeStop,answers(beforeStop,hopper.getWurmId(),1,"save"));
        controller.stop(owner,machine.getWurmId());int count=hopper.getItems().size();seconds(controller,35);
        check(!controller.state().get(machine.getWurmId()).running&&hopper.getItems().size()==count&&Server.getCaveResource(X,Y-1)==9,"operator STOP clears earned work and cannot mine after its former due time");
        denied(()->controller.apply(owner,machine.getWurmId(),stale),"stale settings cannot overwrite a later operator STOP");
        check(!controller.snapshot(other,machine.getWurmId()).canControl,"another real non-GM player receives a read-only machine view");
        denied(()->controller.start(other,machine.getWurmId()),"another player cannot bypass controls by invoking Start");
        RailwaySteamRockDrill.View beforeOwner=controller.snapshot(owner,machine.getWurmId());
        RailwaySteamRockDrill.Request ownerRequest=RailwaySteamRockDrill.fromAnswers(beforeOwner,answers(beforeOwner,hopper.getWurmId(),1,"start"));
        hopper.setLastOwnerId(other.getWurmId());denied(()->controller.apply(owner,machine.getWurmId(),ownerRequest),"storage ownership is rechecked after the configuration window was opened");hopper.setLastOwnerId(owner.getWurmId());
        position(owner,X+4,Y);denied(()->controller.start(owner,machine.getWurmId()),"a player beyond eight metres cannot start the drill");position(owner,X,Y);
        owner.getStatus().setLayer(0);denied(()->controller.start(owner,machine.getWurmId()),"a surface player cannot control a drill through the cave floor");owner.getStatus().setLayer(-1);
        controller.start(owner,machine.getWurmId());seconds(controller,10);
        float oldX=machine.getPosX(),oldY=machine.getPosY();Server.caveMesh.setTile(X+1,Y+1,Tiles.encode((short)20,Tiles.Tile.TILE_CAVE.id,(byte)50));
        machine.setPosXY(oldX+4,oldY+4);position(owner,X+1,Y+1);
        seconds(controller,20);
        check(!controller.state().get(machine.getWurmId()).running&&controller.state().get(machine.getWurmId()).status.contains("moved")&&Server.getCaveResource(X,Y-1)==9,"a moved active drill stops before touching its former anchored face");
        controller.start(owner,machine.getWurmId());
        check(!controller.state().get(machine.getWurmId()).running,"a relocated drill cannot silently continue with old mining faces");
        RailwaySteamRockDrill.View moved=controller.snapshot(owner,machine.getWurmId());
        check(moved.enabledMask==0&&moved.hopperId==0,"a moved drill's configuration view clears old face and hopper selections");
        RailwaySteamRockDrill.Request startMoved=RailwaySteamRockDrill.fromAnswers(moved,answers(moved,0,0,"start"));
        denied(()->controller.apply(owner,machine.getWurmId(),startMoved),"Start cannot implicitly reanchor the moved drill without an explicit stopped Save");
        RailwaySteamRockDrill.Request saveMoved=RailwaySteamRockDrill.fromAnswers(moved,answers(moved,0,0,"save"));controller.apply(owner,machine.getWurmId(),saveMoved);
        check(controller.state().get(machine.getWurmId()).tileX==X+1&&controller.state().get(machine.getWurmId()).tileY==Y+1
                &&controller.state().get(machine.getWurmId()).enabledMask==0&&!controller.state().get(machine.getWurmId()).running,"an explicit stopped Save safely anchors new settings at the moved location");
        machine.setPosXY(oldX,oldY);position(owner,X,Y);configure(controller,owner,machine,hopper,1,"save");
        check(SteamRockDrills.load(file).get(machine.getWurmId()).tileX==X,"the restored physical anchor is durably configured again");
    }
    private static void failures(RailwayDrillController controller,Player owner,Item machine,Item hopper,Path file)throws Exception {
        Item wrong=placed(ItemList.chestLarge,X,Y-2,owner.getWurmId());
        check(MachineStorageLinks.problem(machine,owner.getWurmId(),wrong)!=null,"an ordinary chest cannot replace a linked Bulk Hopper");
        hopper.setMailed(true);check(MachineStorageLinks.problem(machine,owner.getWurmId(),hopper)!=null,"mailed storage is not a valid machine output");hopper.setMailed(false);
        Item full=placed(RailwayBulkHopper.templateId,X-2,Y,owner.getWurmId());Item heavy=ItemFactory.createItem(ItemList.clay,50f,"Drill capacity fixture");created.add(heavy);
        heavy.setWeight((int)BulkHopperPolicy.MAX_WEIGHT_GRAMS,false);heavy.setSizes(1,1,1);check(full.insertItem(heavy,false,true),"native test hopper reaches its actual maximum bulk weight");
        configure(controller,owner,machine,full,1,"start");int resource=Server.getCaveResource(X,Y-1);seconds(controller,30);
        check(controller.state().get(machine.getWurmId()).running&&Server.getCaveResource(X,Y-1)==resource&&machine.getItems().isEmpty(),"a full linked hopper pauses the enabled drill without consuming the vein or dropping output");
        configure(controller,owner,machine,hopper,1,"start");int count=hopper.getItems().size();
        Path original=file.resolveSibling("saved-before-fault.properties");Files.move(file,original);Files.createDirectory(file);Files.write(file.resolve("blocker"),new byte[]{1});
        try {
            boolean failed=false;try{seconds(controller,30);}catch(java.io.IOException expected){failed=true;}
            check(failed&&!controller.state().get(machine.getWurmId()).running&&hopper.getItems().size()==count+1,"a failed machine-state save stops production after conserving the one successfully mined real output");
            seconds(controller,90);check(hopper.getItems().size()==count+1&&Server.getCaveResource(X,Y-1)==resource-1,"a persistence fault cannot keep producing or repeat the last successful vein action");
        }finally{Files.delete(file.resolve("blocker"));Files.delete(file);Files.move(original,file);}
        controller.start(owner,machine.getWurmId());seconds(controller,29);check(hopper.getItems().size()==count+1,"recovering a state-save fault starts with a fresh mining interval");
        controller.tick(1);check(hopper.getItems().size()==count+2&&Server.getCaveResource(X,Y-1)==resource-2,"an explicit restart after storage repair continues without losing or duplicating real resources");
        controller.stop(owner,machine.getWurmId());
    }
    private static void configure(RailwayDrillController controller,Player actor,Item machine,Item hopper,int mask,String action)throws Exception {
        RailwaySteamRockDrill.View view=controller.snapshot(actor,machine.getWurmId());
        controller.apply(actor,machine.getWurmId(),RailwaySteamRockDrill.fromAnswers(view,answers(view,hopper.getWurmId(),mask,action)));
    }
    private static Properties answers(RailwaySteamRockDrill.View view,long hopperId,int mask,String action) {
        Properties p=new Properties();p.setProperty(action,"true");int choice=0;
        for(int i=0;i<view.hoppers.size();i++)if(view.hoppers.get(i).id==hopperId)choice=i+1;
        if(hopperId>0&&choice==0)throw new AssertionError("configured real hopper not offered to this native owner");
        p.setProperty("hopper_choice",Integer.toString(choice));for(int face=0;face<4;face++)if((mask&(1<<face))!=0)p.setProperty("face_"+face,"true");return p;
    }
    private static final Set<Long> seenOutput=new HashSet<Long>();
    private static void newResource(Item hopper,int previous,int template,int x,int y)throws Exception {
        check(hopper.getItems().size()==previous+1,"one earned action adds exactly one original real item");Item newest=null;
        for(Item item:hopper.getItems())if(!seenOutput.contains(item.getWurmId())) {check(newest==null,"only one new native item ID appears per action");newest=item;}
        check(newest!=null&&newest.getTemplateId()==template&&newest.getDataX()==x&&newest.getDataY()==y,"round-robin service selected the expected physical vein and native resource template");
        check(Items.getItem(newest.getWurmId())==newest&&newest.getParentId()==hopper.getWurmId()&&newest.getWeightGrams()>0&&newest.getQualityLevel()>=1,"real output keeps native identity, parent, weight and mining quality");
        seenOutput.add(newest.getWurmId());created.add(newest);
    }
    private static Player player(long id)throws Exception {
        Method create=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);create.setAccessible(true);
        Player player=(Player)create.invoke(null,id,X*4+2f,Y*4+2f);Field power=PlayerInfo.class.getDeclaredField("power");power.setAccessible(true);power.setByte(player.getSaveFile(),(byte)0);
        player.getStatus().setLayer(-1);player.getStatus().getPosition().setPosZ(2f,false);player.currentTile=Zones.getOrCreateTile(X,Y,false);return player;
    }
    private static void removePlayer(Player player)throws Exception {Method remove=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",Player.class);remove.setAccessible(true);remove.invoke(null,player);}
    @SuppressWarnings("unchecked") private static Question latestQuestion(Creature actor)throws Exception {
        Field field=Questions.class.getDeclaredField("questions");field.setAccessible(true);Question latest=null;
        for(Question question:((Map<Integer,Question>)field.get(null)).values())if(question.getResponder()==actor&&(latest==null||question.getId()>latest.getId()))latest=question;
        if(latest==null)throw new AssertionError("native drill controls were not registered");return latest;
    }
    private static void position(Creature actor,int x,int y){actor.getStatus().getPosition().setPosX(x*4+2f);actor.getStatus().getPosition().setPosY(y*4+2f);}
    private static Item placed(int template,int x,int y,long owner)throws Exception {Item item=ItemFactory.createItem(template,65f,x*4+2f,y*4+2f,0,false,ItemMaterials.MATERIAL_IRON,NO_ID,"Drill lifecycle fixture");item.setLastOwnerId(owner);created.add(item);return item;}
    private static void wall(int face,byte kind,int resources){Server.caveMesh.setTile(X+DX[face],Y+DY[face],Tiles.encode((short)20,kind,(byte)50));Server.setCaveResource(X+DX[face],Y+DY[face],resources);Zones.setMiningState(X+DX[face],Y+DY[face],(byte)0,false);}
    private static void seconds(RailwayDrillController controller,int seconds)throws Exception {for(int i=0;i<seconds;i++)controller.tick(1);}
    private interface Work {void run()throws Exception;}
    private static void denied(Work work,String message)throws Exception {boolean denied=false;try{work.run();}catch(IllegalArgumentException expected){denied=true;}check(denied,message);}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
