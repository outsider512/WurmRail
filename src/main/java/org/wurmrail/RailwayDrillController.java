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
import org.wurmrail.core.SteamRockDrills;
import org.wurmrail.core.SteamRockDrills.Drill;
import org.wurmrail.core.SteamRockDrillService;
import org.wurmrail.core.SteamRockDrillService.Result;

/** Server-thread bridge between captured controls, durable machines and native mining. */
public final class RailwayDrillController {
    private final Path file;
    private final double interval;
    private final SteamSupplyController supplies;
    private final SteamRockDrills drills;
    private final SteamRockDrillService service;
    private boolean persistenceFault;

    public RailwayDrillController(Path file,double interval) throws IOException {
        this(file,interval,new SteamSupplyController(file.resolveSibling(file.getFileName()+".steam.properties"),SteamSupplyConfig.defaults()));
    }
    public RailwayDrillController(Path file,double interval,SteamSupplyController supplies) throws IOException {
        this.file=file;this.interval=interval;this.supplies=java.util.Objects.requireNonNull(supplies);this.drills=SteamRockDrills.load(file);
        this.service=new SteamRockDrillService(drills,new SteamRockDrillService.World() {
            @Override public String unavailableReason(Drill state) {
                Item machine=Items.getItemOptional(state.itemId).orElse(null);
                String reason=machineProblem(machine,state);
                if(reason!=null)return reason;
                if(state.hopperId<=0)return "NO OUTPUT STORAGE: select a nearby Bulk Hopper.";
                reason=MachineStorageLinks.problem(machine,state.ownerId,Items.getItemOptional(state.hopperId).orElse(null));
                if(reason!=null)return "NO OUTPUT STORAGE: "+reason;
                return RailwayDrillMining.flushBuffered(machine,state.ownerId,state.hopperId);
            }
            @Override public boolean automaticWaiting(){return true;}
            @Override public boolean automaticWaiting(String reason){return recoverable(reason);}
            @Override public int availableFaces(Drill state) {
                Item machine=Items.getItemOptional(state.itemId).orElse(null);
                return machine==null?0:available(machine,state.ownerId,null);
            }
            @Override public Result mine(Drill state,int face) {
                Item machine=Items.getItemOptional(state.itemId).orElse(null);
                String reason=machineProblem(machine,state);
                if(reason!=null)return Result.stop(reason);
                RailwayDrillMining.FaceInfo faceInfo=RailwayDrillMining.inspect(machine,state.ownerId,face);
                if(!faceInfo.available)return Result.skip(faceInfo.reason);
                RailwayDrillMining.MiningResult result=RailwayDrillMining.mine(machine,state.ownerId,face,state.hopperId,
                        RailwayDrillController.this.supplies.work(machine,state.ownerId,RailwayDrillController.this.supplies.config().drillEnergy,RailwayDrillController.this.supplies.config().drillWaterMillilitres));
                if(!result.mined&&result.depleted)return Result.skip(result.reason);
                if(result.reason!=null)return result.retryable?(result.mined?Result.waitAfterMining(result.reason):Result.waiting(result.reason))
                        :result.mined?Result.stopAfterMining(result.reason):Result.stop(result.reason);
                return result.mined?Result.mined("Delivered the mined resource to the linked Bulk Hopper.")
                        :Result.skip("That face is no longer mineable.");
            }
        },interval);
    }

    public SteamRockDrills state() { return drills; }
    public SteamSupplyController supplies(){return supplies;}

    private static boolean recoverable(String reason) {
        String text=reason==null?"":reason.toLowerCase(java.util.Locale.ROOT);
        return !text.contains("moved")&&!text.contains("ownership changed")&&!text.contains("drill no longer")
                &&!text.contains("unconfirmed")&&!text.contains("invalid")&&!text.contains("inspection")&&!text.contains("operator");
    }
    private static String machineProblem(Item machine,Drill state) {
        if(!RailwaySteamRockDrill.isDrill(machine))return "The Steam Rock Drill no longer exists.";
        if(!MachineStorageLinks.placed(machine))return "Place the drill on an open cave floor, outside inventories and trades.";
        if(!machine.isOwner(state.ownerId))return "The drill's ownership changed. Save its settings again.";
        if(machine.isOnSurface()||state.layer!=-1)return "The drill operates underground on an open cave floor.";
        if(machine.getTileX()!=state.tileX||machine.getTileY()!=state.tileY)
            return "The drill was moved. Stop it and save its hopper and face settings at the new location.";
        return null;
    }

    private static Item nearby(Creature actor,long id) {
        Item machine=Items.getItemOptional(id).orElse(null);
        if(actor==null||!RailwaySteamRockDrill.isDrill(machine)||!MachineStorageLinks.placed(machine)
                ||actor.isOnSurface()!=machine.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-machine.getPosX(),actor.getPosY()-machine.getPosY())>MachineStorageLinks.REACH)
            throw new IllegalArgumentException("Stand within eight metres of the Steam Rock Drill on the same ground level.");
        return machine;
    }
    private static long owner(Item machine,Drill state) {
        long nativeOwner=machine.getLastOwnerId();
        return nativeOwner>0?nativeOwner:state==null?0:state.ownerId;
    }
    private static boolean mayControl(Creature actor,Item machine,long owner) {
        return actor.getPower()>=2||owner>0&&actor.getWurmId()==owner&&machine.isOwner(owner)
                &&machine.mayAccessHold(actor)&&Methods.isActionAllowed(actor,Actions.OPEN,machine);
    }
    private static void requireControl(Creature actor,Item machine,long owner) {
        if(!mayControl(actor,machine,owner))throw new IllegalArgumentException("Only the drill's owner or a GM may control it.");
    }
    private static int available(Item machine,long owner,List<String> details) {
        int mask=0;
        for(int face=0;face<4;face++) {
            RailwayDrillMining.FaceInfo info=RailwayDrillMining.inspect(machine,owner,face);
            if(info.available)mask|=1<<face;
            if(details!=null)details.add(info.available?info.label:info.reason);
        }
        return mask;
    }

    public RailwaySteamRockDrill.View snapshot(Creature actor,long id) {
        Item machine=nearby(actor,id);Drill state=drills.get(id);long owner=owner(machine,state);
        boolean canControl=mayControl(actor,machine,owner);
        boolean current=state!=null&&state.ownerId==owner&&machine.getTileX()==state.tileX&&machine.getTileY()==state.tileY
                &&(machine.isOnSurface()?0:-1)==state.layer;
        List<String> faces=new ArrayList<String>();int available=available(machine,owner,faces);
        List<RailwaySteamRockDrill.StorageChoice> storage=new ArrayList<RailwaySteamRockDrill.StorageChoice>();
        if(canControl)for(Item hopper:MachineStorageLinks.nearbyHoppers(actor,machine,owner))
            storage.add(new RailwaySteamRockDrill.StorageChoice(hopper.getWurmId(),hopper.getName()+" ["+hopper.getWurmId()+"]"));
        long hopperId=current?state.hopperId:0;Item hopper=Items.getItemOptional(hopperId).orElse(null);
        String hopperLabel=hopperId<=0?"None":hopper==null?"Missing Bulk Hopper ["+hopperId+"]":hopper.getName()+" ["+hopperId+"]";
        String status=state==null?"Select an output Bulk Hopper and adjacent faces, then start mining."
                :!current?"The drill's location or ownership changed. Save its settings before starting.":state.status;
        String pending="None";
        if(!machine.getItems().isEmpty()) {
            Item item=machine.getItems().iterator().next();
            pending=item.getName()+" ["+item.getWurmId()+"]"+(item.getAuxData()==99?"; uncertain mining commit: operator inspection required":"; Start retries delivery before mining");
        }
        int selected=current?service.currentFace(id):-1;
        return new RailwaySteamRockDrill.View(id,state==null?-1:state.revision,machine.getName(),current&&state.running,
                hopperId,hopperLabel,current?state.enabledMask:0,available,selected,interval,status,pending,storage,faces,canControl,supplies.snapshot(actor,machine,owner,true));
    }

    public void apply(Creature actor,long id,RailwaySteamRockDrill.Request request) throws IOException {
        if(request==null||request.machineId!=id)throw new IllegalArgumentException("Choose this drill's current controls.");
        if(request.action==RailwaySteamRockDrill.ActionType.REFRESH) { nearby(actor,id);return; }
        Item machine=nearby(actor,id);Drill previous=drills.get(id);long owner=owner(machine,previous);
        requireControl(actor,machine,owner);
        if(request.action==RailwaySteamRockDrill.ActionType.STOP) { stop(actor,id);return; }
        if(machine.isOnSurface())throw new IllegalArgumentException("Place the drill on an open underground cave floor before configuring it.");
        if((previous==null?-1:previous.revision)!=request.revision)
            throw new IllegalArgumentException("The drill settings changed. Refresh its controls before saving.");
        boolean changedContext=previous!=null&&(previous.ownerId!=owner||previous.tileX!=machine.getTileX()
                ||previous.tileY!=machine.getTileY()||previous.layer!=(machine.isOnSurface()?0:-1));
        if(changedContext&&(previous.running||request.action==RailwaySteamRockDrill.ActionType.START))
            throw new IllegalArgumentException("Stop the moved or newly owned drill, then Save settings at its new location before starting.");
        if(owner<=0&&actor.getPower()>=2) { owner=actor.getWurmId();machine.setLastOwnerId(owner); }
        if(owner<=0)throw new IllegalArgumentException("This drill has no owner. A GM must claim it before use.");
        int faces=available(machine,owner,null);
        if((request.enabledMask&~faces)!=0)throw new IllegalArgumentException("One of those mine faces changed. Refresh the drill controls.");
        if(request.hopperId>0)MachineStorageLinks.validateSelection(actor,machine,owner,Items.getItemOptional(request.hopperId).orElse(null));
        supplies.configure(actor,machine,owner,true,request.supplies);
        // All four heads must visually face the same physical compass sides as mining.
        float cardinalRotation=(Math.round(machine.getRotation()/90f)*90f%360f+360f)%360f;
        if(machine.getRotation()!=cardinalRotation) {
            machine.setRotation(cardinalRotation);machine.updateIfGroundItem();
        }
        if(previous!=null&&previous.running)service.stop(id);
        boolean samePlace=previous!=null&&previous.ownerId==owner&&previous.tileX==machine.getTileX()
                &&previous.tileY==machine.getTileY()&&previous.layer==(machine.isOnSurface()?0:-1);
        Drill saved=new Drill(id,owner,drills.nextRevision(),machine.getTileX(),machine.getTileY(),machine.isOnSurface()?0:-1,
                request.hopperId,request.enabledMask,samePlace?previous.nextFace:0,false,"Settings saved. Stopped.");
        drills.put(saved);service.reset(id);
        if(request.action==RailwaySteamRockDrill.ActionType.START)start(actor,id);
        else save();
    }

    public void start(Creature actor,long id) throws IOException {
        Item machine=nearby(actor,id);Drill state=drills.get(id);requireControl(actor,machine,owner(machine,state));
        if(state==null)throw new IllegalArgumentException("Select a Bulk Hopper and mining faces, then save settings first.");
        String reason=machineProblem(machine,state);
        if(reason!=null){drills.put(state.withProgress(false,state.nextFace,reason));service.reset(id);save();return;}
        service.start(id);save();
    }
    public void stop(Creature actor,long id) throws IOException {
        Item machine=nearby(actor,id);Drill state=drills.get(id);requireControl(actor,machine,owner(machine,state));
        if(state==null)return;
        service.stop(id);save();
    }
    public void tick(double elapsed) throws IOException {
        if(persistenceFault)return;
        service.tick(elapsed);
        if(service.dirty())save();
    }
    public void save() throws IOException {
        try { drills.save(file);service.clearDirty();persistenceFault=false; }
        catch(IOException failure) {
            for(Drill state:new ArrayList<Drill>(drills.drills.values()))if(state.running)
                drills.put(state.withProgress(false,state.nextFace,"Stopped: machine state could not be saved. Check server storage, then start again."));
            service.close();persistenceFault=true;throw failure;
        }
    }
}
