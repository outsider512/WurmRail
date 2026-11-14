import com.wurmonline.mesh.*;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.*;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.players.PlayerInfo;
import com.wurmonline.server.questions.*;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.*;
import org.wurmrail.core.SteamYarders;
import org.wurmrail.core.SteamYarders.Yarder;
import org.wurmrail.core.SteamYarders.HarvestAge;

/** Actual ordinary-player GUI and durable timed forestry, isolated from any player world. */
public final class SteamYarderRuntimeSmoke {
    private static final int X=218,Y=202;
    private static final long NO_ID=-10L;
    private static int assertions;
    private static final List<Item> created=new ArrayList<Item>();
    private static final Set<Long> seen=new HashSet<Long>();
    private SteamYarderRuntimeSmoke() { }
    public static void run()throws Exception {
        MeshIO resourcesBefore=Server.resourceMesh;boolean[][] protectedBefore=Zones.protectedTiles;
        Field flagsField=Server.class.getDeclaredField("flagsMesh");flagsField.setAccessible(true);Object flagsBefore=flagsField.get(null);MeshIO flags=null;
        boolean scheduled=com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase;
        int[] surface=new int[169],rock=new int[169];
        for(int at=0;at<169;at++){surface[at]=Server.surfaceMesh.getTile(X-6+at%13,Y-6+at/13);rock[at]=Server.rockMesh.getTile(X-6+at%13,Y-6+at/13);}
        Player owner=null,other=null;
        com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=false;
        try {
            int[] resourceData=new int[256*256];Arrays.fill(resourceData,0x2468ffff);
            Server.resourceMesh=MeshIO.createMap(Paths.get("steam_yarder_controller_resources.map").toAbsolutePath().toString(),8,resourceData);
            flags=MeshIO.createMap(Paths.get("steam_yarder_controller_flags.map").toAbsolutePath().toString(),8,new int[256*256]);flagsField.set(null,flags);
            Zones.protectedTiles=new boolean[256][256];
            for(int dx=-6;dx<=6;dx++)for(int dy=-6;dy<=6;dy++) {
                Server.surfaceMesh.setTile(X+dx,Y+dy,Tiles.encode((short)50,Tiles.Tile.TILE_GRASS.id,(byte)0));
                Server.rockMesh.setTile(X+dx,Y+dy,Tiles.encode((short)40,Tiles.Tile.TILE_ROCK.id,(byte)0));
            }
            owner=player(998469120L);other=player(998469376L);
            Item machine=placed(RailwaySteamYarder.templateId,X,Y,owner.getWurmId());
            Item bunk=placed(RailwayLogBunk.templateId,X-1,Y,owner.getWurmId());
            Path folder=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-steam-yarders");Files.createDirectories(folder);
            Path file=folder.resolve("yarders.properties");
            RailwayYarderController controller=new RailwayYarderController(file,30);
            SteamTestSupplies.provision(controller.supplies(),owner,machine,true,created);
            treeAt(0,TreeData.TreeType.OAK,FoliageAge.OLD_ONE);treeAt(1,TreeData.TreeType.PINE,FoliageAge.OVERAGED);treeAt(2,TreeData.TreeType.MAPLE,FoliageAge.VERY_OLD);
            tree(X+5,Y+1,TreeData.TreeType.BIRCH,FoliageAge.OVERAGED);int outside=Server.surfaceMesh.getTile(X+5,Y+1);
            RailwaySteamYarder.View view=controller.snapshot(owner,machine.getWurmId());
            check(owner.getPower()==0&&view.canControl,"an actual ordinary player can configure their placed Yarder");
            check(view.bunks.size()==1&&view.bunks.get(0).id==bunk.getWurmId(),"explicit native configuration discovers the actual owned compatible Log Bunk");
            startThroughQuestion(controller,owner,machine,bunk);
            check(controller.state().get(machine.getWurmId()).running&&SteamYarders.load(file).get(machine.getWurmId()).running,"captured GUI Start durably saves running forestry intent");
            seconds(controller,29);check(bunk.getItems().isEmpty(),"GUI-started Yarder cannot produce timber before thirty newly earned seconds");
            controller.tick(1);output(bunk,0,0,TreeData.TreeType.OAK.getMaterial());
            seconds(controller,30);output(bunk,1,1,TreeData.TreeType.PINE.getMaterial());
            check(controller.state().get(machine.getWurmId()).nextTile==2,"real native harvests persist the circular round-robin cursor");
            seconds(controller,24);long revision=controller.state().get(machine.getWurmId()).revision;
            controller=new RailwayYarderController(file,30);seconds(controller,29);
            check(bunk.getItems().size()==2&&controller.state().get(machine.getWurmId()).revision==revision,"server restart retains settings/cursor but no earlier or offline harvest time");
            controller.tick(1);output(bunk,2,2,TreeData.TreeType.MAPLE.getMaterial());
            seconds(controller,90);Yarder waiting=controller.state().get(machine.getWurmId());
            check(waiting.running&&waiting.status.contains("Waiting")&&bunk.getItems().size()==3,"young replants leave the Yarder running while ordinary Wurm growth catches up");
            for(int i=0;i<3;i++){SteamYarders.Offset at=SteamYarders.WOODLOT.get(i);int tile=Server.surfaceMesh.getTile(X+at.dx,Y+at.dy);check(FoliageAge.getAgeAsByte(Tiles.decodeData(tile))==0&&Tiles.getTile(Tiles.decodeType(tile)).getTreeType(Tiles.decodeData(tile))==TreeData.TreeType.FIR,"each cut tile is replanted with the configured native species at its normal young age");}
            check(Server.surfaceMesh.getTile(X+5,Y+1)==outside,"scheduled native forestry cannot touch a mature tree outside the managed circle");
            controls(controller,owner,other,machine,bunk,file);
            failures(controller,owner,machine,bunk,file);
            System.out.println("PASS native Steam Yarder lifecycle: actual ordinary-player captured GUI/replay, timed real timber and natural-growth waiting, circular cursor/restart, STOP/stale settings, ownership/range/anchor guards and persistence-fault item conservation ("+assertions+" assertions)");
        }finally {
            for(int i=created.size()-1;i>=0;i--)if(Items.getItemOptional(created.get(i).getWurmId()).isPresent())Items.destroyItem(created.get(i).getWurmId());
            for(Player player:new Player[]{owner,other})if(player!=null)removePlayer(player);
            for(int at=0;at<169;at++){Server.surfaceMesh.setTile(X-6+at%13,Y-6+at/13,surface[at]);Server.rockMesh.setTile(X-6+at%13,Y-6+at/13,rock[at]);}
            Server.resourceMesh.close();Server.resourceMesh=resourcesBefore;Zones.protectedTiles=protectedBefore;
            if(flags!=null)flags.close();flagsField.set(null,flagsBefore);
            com.wurmonline.server.Constants.useScheduledExecutorToUpdateItemParentInDatabase=scheduled;
        }
    }
    private static void startThroughQuestion(final RailwayYarderController controller,final Player owner,Item machine,Item bunk)throws Exception {
        final int[] submitted={0};
        RailwaySteamYarder.Api api=new RailwaySteamYarder.Api(){
            @Override public RailwaySteamYarder.View snapshot(Creature actor,long id){return controller.snapshot(actor,id);}
            @Override public void submit(Creature actor,long id,RailwaySteamYarder.Request request){submitted[0]++;try{controller.apply(actor,id,request);}catch(Exception failure){throw new IllegalStateException(failure);}}
            @Override public void interact(Creature actor,long id,RailwaySteamYarder.Interaction interaction){throw new AssertionError("unexpected direct interaction");}
        };
        RailwaySteamYarder.View view=controller.snapshot(owner,machine.getWurmId());RailwaySteamYarder.show(owner,machine.getWurmId(),api);
        Question question=latestQuestion(owner);Properties answer=answers(view,bunk.getWurmId(),"start");question.answer(answer);question.answer(answer);
        check(submitted[0]==1,"native ModQuestion replay cannot commit configuration twice or reset a running timer");
    }
    private static void controls(RailwayYarderController controller,Player owner,Player other,Item machine,Item bunk,Path file)throws Exception {
        treeAt(0,TreeData.TreeType.OAK,FoliageAge.OLD_ONE);configure(controller,owner,machine,bunk,"start");seconds(controller,29);
        RailwaySteamYarder.View beforeStop=controller.snapshot(owner,machine.getWurmId());
        RailwaySteamYarder.Request stale=RailwaySteamYarder.fromAnswers(beforeStop,answers(beforeStop,bunk.getWurmId(),"save"));
        controller.stop(owner,machine.getWurmId());int count=bunk.getItems().size();int treeBefore=Server.surfaceMesh.getTile(X,Y-5);seconds(controller,35);
        check(!controller.state().get(machine.getWurmId()).running&&bunk.getItems().size()==count&&Server.surfaceMesh.getTile(X,Y-5)==treeBefore,"STOP clears previously earned time and cannot cut at its former due time");
        denied(()->controller.apply(owner,machine.getWurmId(),stale),"stale settings cannot overwrite a later STOP");
        check(!controller.snapshot(other,machine.getWurmId()).canControl,"another ordinary player can inspect but receives no control permission");
        denied(()->controller.start(other,machine.getWurmId()),"another player cannot invoke Start outside the captured GUI");
        RailwaySteamYarder.View beforeOwner=controller.snapshot(owner,machine.getWurmId());
        RailwaySteamYarder.Request ownerRequest=RailwaySteamYarder.fromAnswers(beforeOwner,answers(beforeOwner,bunk.getWurmId(),"start"));
        bunk.setLastOwnerId(other.getWurmId());denied(()->controller.apply(owner,machine.getWurmId(),ownerRequest),"bunk ownership is revalidated after opening configuration");bunk.setLastOwnerId(owner.getWurmId());
        position(owner,X+4,Y);denied(()->controller.start(owner,machine.getWurmId()),"an actor beyond eight metres cannot start forestry");position(owner,X,Y);
        owner.getStatus().setLayer(-1);denied(()->controller.start(owner,machine.getWurmId()),"an underground actor cannot control surface forestry through the floor");owner.getStatus().setLayer(0);
        controller.start(owner,machine.getWurmId());seconds(controller,10);float oldX=machine.getPosX(),oldY=machine.getPosY();
        machine.setPosXY(oldX+4,oldY+4);position(owner,X+1,Y+1);seconds(controller,20);
        check(!controller.state().get(machine.getWurmId()).running&&controller.state().get(machine.getWurmId()).status.contains("moved")&&Server.surfaceMesh.getTile(X,Y-5)==treeBefore,"a moved active Yarder stops before harvesting its old anchored woodlot");
        controller.start(owner,machine.getWurmId());check(!controller.state().get(machine.getWurmId()).running,"Start cannot silently reanchor an already configured moved Yarder");
        RailwaySteamYarder.View moved=controller.snapshot(owner,machine.getWurmId());check(moved.bunkId==0,"moved configuration clears the old storage selection");
        RailwaySteamYarder.Request startMoved=RailwaySteamYarder.fromAnswers(moved,answers(moved,0,"start"));denied(()->controller.apply(owner,machine.getWurmId(),startMoved),"Start cannot reanchor without an explicit stopped Save");
        RailwaySteamYarder.Request saveMoved=RailwaySteamYarder.fromAnswers(moved,answers(moved,0,"save"));controller.apply(owner,machine.getWurmId(),saveMoved);
        check(controller.state().get(machine.getWurmId()).tileX==X+1&&controller.state().get(machine.getWurmId()).tileY==Y+1&&!controller.state().get(machine.getWurmId()).running,"explicit stopped Save safely anchors new forestry settings at the new position");
        machine.setPosXY(oldX,oldY);position(owner,X,Y);configure(controller,owner,machine,bunk,"save");
        check(SteamYarders.load(file).get(machine.getWurmId()).tileX==X,"restoring physical placement requires a new durable configuration");
    }
    private static void failures(RailwayYarderController controller,Player owner,Item machine,Item bunk,Path file)throws Exception {
        Item chest=placed(ItemList.chestLarge,X,Y-2,owner.getWurmId());check(MachineTimberLinks.problem(machine,owner.getWurmId(),chest)!=null,"an arbitrary chest cannot replace specialized forestry storage");
        bunk.setMailed(true);check(MachineTimberLinks.problem(machine,owner.getWurmId(),bunk)!=null,"mailed storage is rejected before native harvesting");bunk.setMailed(false);
        Item full=placed(RailwayLogBunk.templateId,X-2,Y,owner.getWurmId());Item heavy=ItemFactory.createItem(ItemList.log,50,"Yarder capacity fixture");created.add(heavy);heavy.setWeight((int)LogBunkPolicy.MAX_WEIGHT_GRAMS,true);full.insertItem(heavy,true);
        treeAt(0,TreeData.TreeType.OAK,FoliageAge.OLD_ONE);int treeBefore=Server.surfaceMesh.getTile(X,Y-5);configure(controller,owner,machine,full,"start");seconds(controller,30);
        check(controller.state().get(machine.getWurmId()).running&&Server.surfaceMesh.getTile(X,Y-5)==treeBefore&&machine.getItems().isEmpty(),"a full linked Bunk pauses before native tree mutation or timber staging");
        configure(controller,owner,machine,bunk,"start");int count=bunk.getItems().size();
        Path original=file.resolveSibling("saved-before-fault.properties");Files.move(file,original);Files.createDirectory(file);Files.write(file.resolve("blocker"),new byte[]{1});
        try {
            boolean failed=false;try{seconds(controller,30);}catch(java.io.IOException expected){failed=true;}
            check(failed&&!controller.state().get(machine.getWurmId()).running&&bunk.getItems().size()==count+1,"a failed control-state save stops production after conserving the one completed real harvest");
            int sprout=Server.surfaceMesh.getTile(X,Y-5);seconds(controller,90);
            check(bunk.getItems().size()==count+1&&Server.surfaceMesh.getTile(X,Y-5)==sprout,"a persistence failure cannot repeat or continue harvesting");
        }finally{Files.delete(file.resolve("blocker"));Files.delete(file);Files.move(original,file);}
        treeAt(1,TreeData.TreeType.PINE,FoliageAge.OLD_ONE);controller.start(owner,machine.getWurmId());seconds(controller,29);
        check(bunk.getItems().size()==count+1,"explicit persistence recovery earns a fresh interval");controller.tick(1);
        check(bunk.getItems().size()==count+2,"storage repair resumes forestry once without duplicating earlier timber");controller.stop(owner,machine.getWurmId());
    }
    private static void configure(RailwayYarderController controller,Player actor,Item machine,Item bunk,String action)throws Exception {RailwaySteamYarder.View view=controller.snapshot(actor,machine.getWurmId());controller.apply(actor,machine.getWurmId(),RailwaySteamYarder.fromAnswers(view,answers(view,bunk.getWurmId(),action)));}
    private static Properties answers(RailwaySteamYarder.View view,long bunk,String action){Properties p=new Properties();p.setProperty(action,"true");int choice=0;for(int i=0;i<view.bunks.size();i++)if(view.bunks.get(i).id==bunk)choice=i+1;if(bunk>0&&choice==0)throw new AssertionError("real owned bunk absent from choices");p.setProperty("bunk_choice",Integer.toString(choice));int species=-1;for(int i=0;i<view.speciesChoices.size();i++)if(view.speciesChoices.get(i).id==TreeData.TreeType.FIR.getTypeId())species=i;if(species<0)throw new AssertionError("native FIR replant species absent");p.setProperty("species_choice",Integer.toString(species));p.setProperty("age_choice",Integer.toString(HarvestAge.OLD.ordinal()));return p;}
    private static void output(Item bunk,int before,int index,byte material)throws Exception {check(bunk.getItems().size()==before+1,"each earned native forestry action inserts exactly one timber item");Item newest=null;for(Item item:bunk.getItems())if(!seen.contains(item.getWurmId())){check(newest==null,"only one original native timber ID appears per harvest");newest=item;}SteamYarders.Offset at=SteamYarders.WOODLOT.get(index);check(newest!=null&&newest.getTemplateId()==ItemList.logHuge&&newest.getMaterial()==material&&newest.getDataX()==X+at.dx&&newest.getDataY()==Y+at.dy,"round-robin timber retains native template, material and harvested tile");check(newest.getParentId()==bunk.getWurmId()&&newest.getWeightGrams()>0&&newest.getQualityLevel()>=1&&new DbItem(newest.getWurmId()).getParentId()==bunk.getWurmId(),"real timber weight, quality and unique storage parent persist in the native database");seen.add(newest.getWurmId());created.add(newest);}
    private static void treeAt(int index,TreeData.TreeType species,FoliageAge age){SteamYarders.Offset at=SteamYarders.WOODLOT.get(index);tree(X+at.dx,Y+at.dy,species,age);}
    private static void tree(int x,int y,TreeData.TreeType species,FoliageAge age){Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,species.asNormalTree(),Tiles.encodeTreeData(age,false,true,GrassData.GrowthTreeStage.SHORT)));}
    private static Player player(long id)throws Exception {Method create=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class);create.setAccessible(true);Player player=(Player)create.invoke(null,id,X*4+2f,Y*4+2f);Field power=PlayerInfo.class.getDeclaredField("power");power.setAccessible(true);power.setByte(player.getSaveFile(),(byte)0);player.getStatus().setLayer(0);player.getStatus().getPosition().setPosZ(5f,false);player.currentTile=Zones.getOrCreateTile(X,Y,true);return player;}
    private static void removePlayer(Player player)throws Exception{Method remove=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",Player.class);remove.setAccessible(true);remove.invoke(null,player);}
    @SuppressWarnings("unchecked") private static Question latestQuestion(Creature actor)throws Exception {Field field=Questions.class.getDeclaredField("questions");field.setAccessible(true);Question latest=null;for(Question question:((Map<Integer,Question>)field.get(null)).values())if(question.getResponder()==actor&&(latest==null||question.getId()>latest.getId()))latest=question;if(latest==null)throw new AssertionError("native Yarder question absent");return latest;}
    private static void position(Creature actor,int x,int y){actor.getStatus().getPosition().setPosX(x*4+2f);actor.getStatus().getPosition().setPosY(y*4+2f);}
    private static Item placed(int template,int x,int y,long owner)throws Exception {Item item=ItemFactory.createItem(template,65f,x*4+2f,y*4+2f,0,true,(byte)0,NO_ID,"Yarder lifecycle fixture");item.setLastOwnerId(owner);created.add(item);return item;}
    private static void seconds(RailwayYarderController controller,int seconds)throws Exception{for(int i=0;i<seconds;i++)controller.tick(1);}
    private interface Work{void run()throws Exception;}
    private static void denied(Work work,String message)throws Exception{boolean denied=false;try{work.run();}catch(IllegalArgumentException expected){denied=true;}check(denied,message);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
