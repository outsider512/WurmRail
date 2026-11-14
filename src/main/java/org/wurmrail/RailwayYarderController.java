package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.wurmrail.core.SteamYarders;
import org.wurmrail.core.SteamYarders.Yarder;
import org.wurmrail.core.SteamYarderService;
import org.wurmrail.core.SteamYarderService.Result;

/** Server-thread bridge for bounded forestry, captured controls and durable settings. */
public final class RailwayYarderController {
    private final Path file;
    private final double interval;
    private final SteamSupplyController supplies;
    private final SteamYarders yarders;
    private final SteamYarderService service;
    private boolean persistenceFault;

    public RailwayYarderController(Path file,double interval) throws IOException {
        this(file,interval,new SteamSupplyController(file.resolveSibling(file.getFileName()+".steam.properties"),SteamSupplyConfig.defaults()));
    }
    public RailwayYarderController(Path file,double interval,SteamSupplyController supplies) throws IOException {
        this.file=file;this.interval=interval;this.supplies=java.util.Objects.requireNonNull(supplies);yarders=SteamYarders.load(file);
        service=new SteamYarderService(yarders,new SteamYarderService.World() {
            @Override public String unavailableReason(Yarder state) {
                Item machine=Items.getItemOptional(state.itemId).orElse(null);
                String reason=machineProblem(machine,state);
                if(reason!=null)return reason;
                reason=MachineTimberLinks.problem(machine,state.ownerId,Items.getItemOptional(state.bunkId).orElse(null));
                if(reason!=null)return "NO OUTPUT STORAGE: "+reason;
                return RailwayYarderWorld.flushBuffered(machine,state.ownerId,state.bunkId);
            }
            @Override public boolean automaticWaiting(){return true;}
            @Override public boolean automaticWaiting(String reason){return recoverable(reason);}
            @Override public Result harvest(Yarder state,int x,int y) {
                Item machine=Items.getItemOptional(state.itemId).orElse(null);
                String reason=machineProblem(machine,state);
                if(reason!=null)return Result.stop(reason);
                RailwayYarderWorld.TileInfo info=RailwayYarderWorld.inspect(machine,state.ownerId,x,y,state.harvestAge);
                if(!info.available)return Result.skip(info.reason);
                RailwayYarderWorld.HarvestResult result=RailwayYarderWorld.harvest(machine,state.ownerId,x,y,state.species,state.harvestAge,state.bunkId,
                        RailwayYarderController.this.supplies.work(machine,state.ownerId,RailwayYarderController.this.supplies.config().yarderEnergy,RailwayYarderController.this.supplies.config().yarderWaterMillilitres));
                if(result.reason!=null)return result.retryable?(result.harvested?Result.waitAfterHarvest(result.reason):Result.waiting(result.reason))
                        :result.harvested?Result.stopAfterHarvest(result.reason):Result.stop(result.reason);
                return result.harvested?Result.harvested("Timber delivered to the linked Log Bunk."):Result.skip("That tree is no longer harvestable.");
            }
        },interval);
    }
    public SteamYarders state(){return yarders;}
    public SteamSupplyController supplies(){return supplies;}
    private static boolean recoverable(String reason) {
        String text=reason==null?"":reason.toLowerCase(java.util.Locale.ROOT);
        return !text.contains("moved")&&!text.contains("ownership changed")&&!text.contains("yarder no longer")
                &&!text.contains("unconfirmed")&&!text.contains("invalid")&&!text.contains("inspection")&&!text.contains("operator");
    }
    private static String machineProblem(Item machine,Yarder state) {
        if(!RailwaySteamYarder.isYarder(machine))return "The Steam Yarder no longer exists.";
        if(!machine.isOwner(state.ownerId))return "The Yarder's ownership changed. Stop and save its settings again.";
        if(!machine.isOnSurface()||state.layer!=0||machine.getTileX()!=state.tileX||machine.getTileY()!=state.tileY)
            return "The Yarder was moved. Stop and save its woodlot settings at the new location.";
        return RailwayYarderWorld.locationProblem(machine,state.ownerId);
    }
    private static Item nearby(Creature actor,long id) {
        Item machine=Items.getItemOptional(id).orElse(null);
        if(actor==null||!RailwaySteamYarder.isYarder(machine)||!MachineTimberLinks.placed(machine)
                ||actor.isOnSurface()!=machine.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-machine.getPosX(),actor.getPosY()-machine.getPosY())>MachineTimberLinks.REACH)
            throw new IllegalArgumentException("Stand within eight metres of the Steam Yarder on the same ground level.");
        return machine;
    }
    private static long owner(Item machine,Yarder state){long id=machine.getLastOwnerId();return id>0?id:state==null?0:state.ownerId;}
    private static boolean mayControl(Creature actor,Item machine,long owner) {
        return actor.getPower()>=2||owner>0&&actor.getWurmId()==owner&&machine.isOwner(owner)
                &&machine.mayAccessHold(actor)&&Methods.isActionAllowed(actor,Actions.OPEN,machine);
    }
    private static void requireControl(Creature actor,Item machine,long owner) {
        if(!mayControl(actor,machine,owner))throw new IllegalArgumentException("Only the Yarder's owner or a GM may control it.");
    }
    private static boolean current(Item machine,Yarder state,long owner) {
        return state!=null&&state.ownerId==owner&&state.tileX==machine.getTileX()&&state.tileY==machine.getTileY()
                &&state.layer==(machine.isOnSurface()?0:-1);
    }
    private static boolean validSpecies(int id) {
        for(RailwayYarderWorld.Species species:RailwayYarderWorld.speciesChoices())if(species.id==id)return true;
        return false;
    }
    public RailwaySteamYarder.View snapshot(Creature actor,long id) {
        Item machine=nearby(actor,id);Yarder state=yarders.get(id);long owner=owner(machine,state);
        boolean control=mayControl(actor,machine,owner),current=current(machine,state,owner);
        List<RailwaySteamYarder.Choice> species=new ArrayList<RailwaySteamYarder.Choice>();
        for(RailwayYarderWorld.Species entry:RailwayYarderWorld.speciesChoices())species.add(new RailwaySteamYarder.Choice(entry.id,entry.label));
        List<RailwaySteamYarder.Choice> bunks=new ArrayList<RailwaySteamYarder.Choice>();
        if(control)for(Item bunk:MachineTimberLinks.nearbyBunks(actor,machine,owner))
            bunks.add(new RailwaySteamYarder.Choice(bunk.getWurmId(),bunk.getName()+" ["+bunk.getWurmId()+"]"));
        SteamYarders.HarvestAge age=current?state.harvestAge:SteamYarders.HarvestAge.VERY_OLD;
        int ready=0,trees=0;
        for(SteamYarders.Offset offset:SteamYarders.WOODLOT) {
            RailwayYarderWorld.TileInfo info=RailwayYarderWorld.inspect(machine,owner,machine.getTileX()+offset.dx,machine.getTileY()+offset.dy,age);
            if(info.templateId>0)trees++;
            if(info.available)ready++;
        }
        String woodlot="Centre "+machine.getTileX()+", "+machine.getTileY()+"; radius 5 tiles; "+ready+" trees ready; "+trees+" candidate trees. Own tile, protected and unsuitable tiles are skipped.";
        String status=state==null?"Select a Log Bunk, replant species and harvest age, then start forestry."
                :!current?"Location or ownership changed. Stop and Save settings before starting.":state.status;
        String pending="None";
        if(!machine.getItems().isEmpty()) {
            Item item=machine.getItems().iterator().next();pending=item.getName()+" ["+item.getWurmId()+"]"
                    +(item.getAuxData()==99?"; uncertain harvest commit: operator inspection required":"; Start retries delivery before harvesting");
        }
        return new RailwaySteamYarder.View(id,state==null?-1:state.revision,machine.getName(),current&&state.running,
                current?state.bunkId:0,current?state.species:(int)species.get(0).id,age,status,woodlot,pending,interval,bunks,species,control,supplies.snapshot(actor,machine,owner,true));
    }
    public void apply(Creature actor,long id,RailwaySteamYarder.Request request) throws IOException {
        if(request==null||request.id!=id)throw new IllegalArgumentException("Choose this Yarder's current controls.");
        if(request.action==RailwaySteamYarder.ActionType.REFRESH){nearby(actor,id);return;}
        Item machine=nearby(actor,id);Yarder previous=yarders.get(id);long owner=owner(machine,previous);requireControl(actor,machine,owner);
        if(request.action==RailwaySteamYarder.ActionType.STOP){stop(actor,id);return;}
        if((previous==null?-1:previous.revision)!=request.revision)throw new IllegalArgumentException("The Yarder settings changed. Refresh its controls before saving.");
        boolean changed=previous!=null&&!current(machine,previous,owner);
        if(changed&&(previous.running||request.action==RailwaySteamYarder.ActionType.START))
            throw new IllegalArgumentException("Stop the moved or newly owned Yarder, then Save settings at its new location before starting.");
        if(owner<=0&&actor.getPower()>=2){owner=actor.getWurmId();machine.setLastOwnerId(owner);}
        if(owner<=0)throw new IllegalArgumentException("This Yarder has no owner. A GM must claim it before use.");
        String location=RailwayYarderWorld.locationProblem(machine,owner);if(location!=null)throw new IllegalArgumentException(location);
        if(!validSpecies(request.species))throw new IllegalArgumentException("Choose an available native tree species.");
        if(request.bunkId>0)MachineTimberLinks.validateSelection(actor,machine,owner,Items.getItemOptional(request.bunkId).orElse(null));
        supplies.configure(actor,machine,owner,true,request.supplies);
        if(previous!=null&&previous.running)service.stop(id);
        Yarder saved=new Yarder(id,owner,yarders.nextRevision(),machine.getTileX(),machine.getTileY(),0,request.bunkId,request.species,request.harvestAge,
                current(machine,previous,owner)?previous.nextTile:0,false,"Settings saved. Stopped.");
        yarders.put(saved);service.reset(id);
        if(request.action==RailwaySteamYarder.ActionType.START)start(actor,id);else save();
    }
    public void start(Creature actor,long id) throws IOException {
        Item machine=nearby(actor,id);Yarder state=yarders.get(id);requireControl(actor,machine,owner(machine,state));
        if(state==null)throw new IllegalArgumentException("Select a Log Bunk, replant species and harvest age, then save settings first.");
        String reason=machineProblem(machine,state);
        if(reason!=null){yarders.put(state.withProgress(false,state.nextTile,reason));service.reset(id);save();return;}
        service.start(id);save();
    }
    public void stop(Creature actor,long id) throws IOException {
        Item machine=nearby(actor,id);Yarder state=yarders.get(id);requireControl(actor,machine,owner(machine,state));
        if(state==null)return;service.stop(id);save();
    }
    public void tick(double elapsed) throws IOException{if(persistenceFault)return;service.tick(elapsed);if(service.dirty())save();}
    public void save() throws IOException {
        try{yarders.save(file);service.clearDirty();persistenceFault=false;}
        catch(IOException failure) {
            for(Yarder state:new ArrayList<Yarder>(yarders.yarders.values()))if(state.running)
                yarders.put(state.withProgress(false,state.nextTile,"Stopped: machine state could not be saved. Check server storage, then start again."));
            service.close();persistenceFault=true;throw failure;
        }
    }
}
