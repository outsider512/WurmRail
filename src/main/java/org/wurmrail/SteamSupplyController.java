package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.wurmrail.core.SteamSupplies;
import org.wurmrail.core.SteamSupplies.Debit;
import org.wurmrail.core.SteamSupplies.Supply;

/** Server-thread resource journal and paid buffers shared by all steam equipment. */
public final class SteamSupplyController {
    private static final long NONE=-10L;
    private final Path file;
    private final SteamSupplyConfig config;
    private final SteamSupplies store;
    private boolean ioFault;

    public SteamSupplyController(Path file,SteamSupplyConfig config)throws IOException {
        this.file=Objects.requireNonNull(file);this.config=Objects.requireNonNull(config);store=SteamSupplies.load(file);
        for(Supply supply:new ArrayList<Supply>(store.supplies.values())) {
            if(supply.phase!=0)recover(supply);
            Supply current=store.get(supply.machineId);
            if(current.workToken!=0) {
                store.put(current.balances(current.energyMicro,current.waterMillilitres,0,0,0,0,true,
                        "SUPPLY RECOVERY: work was interrupted. Inspect output, then Save supply settings.",Collections.<Debit>emptyList()));
            }
        }
        if(!store.supplies.isEmpty())store.save(file);
    }
    public SteamSupplyConfig config(){return config;}
    public SteamSupplies state(){return store;}
    public void save()throws IOException{store.save(file);ioFault=false;}
    private void persist(Supply supply) {
        store.put(supply);
        try{save();}catch(IOException failure){ioFault=true;throw new IllegalStateException("Steam supply state could not be saved; production is stopped.",failure);}
    }
    private static String machineProblem(Item machine,long owner) {
        if(machine==null||!MachineStorageLinks.placed(machine))return "Place the steam machine on ground level, outside inventories and trades.";
        if(owner<=0||!machine.isOwner(owner))return "Steam machine ownership changed; save its settings again.";
        if(machine.isLocked()||machine.isSealedByPlayer())return "Unlock and unseal the steam machine.";
        return null;
    }
    private String storageProblem(Item machine,Item storage,long owner,boolean water) {
        String reason=machineProblem(machine,owner);if(reason!=null)return reason;
        if(storage==null)return water?"NO WATER: linked Industrial Water Tank is missing.":"NO FUEL: linked Fuel Supply is missing.";
        if(water&&!LiquidTankPolicy.isIndustrialTank(storage))return "NO WATER: choose an Industrial Water Tank.";
        if(!water&&(!storage.isHollow()||storage.isVehicle()||storage.isInventory()||storage.isInventoryGroup()
                ||storage.isBulkContainer()||storage.isCrate()||storage.isCorpse()||LiquidTankPolicy.isLiquidTank(storage)
                ||SteamRockDrillRegistry.isDrill(storage.getTemplateId())||SteamYarderRegistry.isYarder(storage.getTemplateId())
                ||SteamWaterPumpRegistry.isPump(storage.getTemplateId())))return "NO FUEL: choose a normal container, Bulk Hopper or Log Bunk.";
        if(!MachineStorageLinks.placed(storage)||!storage.isOwner(owner))return (water?"NO WATER: ":"NO FUEL: ")+"supply storage must be placed and have the same owner as the machine.";
        if(storage.isLocked()||storage.isSealedByPlayer())return (water?"NO WATER: ":"NO FUEL: ")+"unlock and unseal supply storage.";
        if(storage.isOnSurface()!=machine.isOnSurface()||Math.hypot(storage.getPosX()-machine.getPosX(),storage.getPosY()-machine.getPosY())>config.linkMetres)
            return (water?"NO WATER: ":"NO FUEL: ")+"supply storage is outside the linking range or on another level.";
        return null;
    }
    private void selection(Creature actor,Item machine,long owner,Item storage,boolean water) {
        String reason=storageProblem(machine,storage,owner,water);if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor.getPower()<2&&(!storage.mayAccessHold(actor)||!Methods.isActionAllowed(actor,Actions.OPEN,storage)||!Methods.isActionAllowed(actor,Actions.TAKE,storage)))
            throw new IllegalArgumentException("Wurm permissions deny this supply link.");
    }
    private static void control(Creature actor,Item machine,long owner) {
        if(actor==null||machine==null||actor.isOnSurface()!=machine.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=NONE
                ||Math.hypot(actor.getPosX()-machine.getPosX(),actor.getPosY()-machine.getPosY())>8)
            throw new IllegalArgumentException("Stand within eight metres of the steam machine.");
        if(actor.getPower()<2&&(actor.getWurmId()!=owner||!machine.isOwner(owner)||!machine.mayAccessHold(actor)||!Methods.isActionAllowed(actor,Actions.OPEN,machine)))
            throw new IllegalArgumentException("Only the machine's owner or a GM may change steam supplies.");
    }
    public SteamSupplyUi.View snapshot(Creature actor,Item machine,long owner,boolean waterRequired) {
        Supply s=store.get(machine.getWurmId());List<SteamSupplyUi.Choice> fuel=new ArrayList<SteamSupplyUi.Choice>(),water=new ArrayList<SteamSupplyUi.Choice>();
        boolean can=actor!=null&&(actor.getPower()>=2||actor.getWurmId()==owner&&machine.isOwner(owner));
        if(can) {
            int reach=(int)Math.ceil(config.linkMetres/4)+1;
            for(int x=Math.max(0,machine.getTileX()-reach);x<=Math.min(Zones.worldTileSizeX-1,machine.getTileX()+reach);x++)
                for(int y=Math.max(0,machine.getTileY()-reach);y<=Math.min(Zones.worldTileSizeY-1,machine.getTileY()+reach);y++) {
                    VolaTile tile=Zones.getTileOrNull(x,y,machine.isOnSurface());if(tile==null)continue;
                    for(Item item:tile.getItems()) {
                        try{selection(actor,machine,owner,item,false);if(fuel.size()<512)fuel.add(new SteamSupplyUi.Choice(item.getWurmId(),item.getName()+" ["+item.getWurmId()+"]"));}catch(IllegalArgumentException denied){ }
                        if(waterRequired)try{selection(actor,machine,owner,item,true);if(water.size()<512)water.add(new SteamSupplyUi.Choice(item.getWurmId(),item.getName()+" ["+item.getWurmId()+"]"));}catch(IllegalArgumentException denied){ }
                    }
                }
        }
        Comparator<SteamSupplyUi.Choice> order=Comparator.comparing((SteamSupplyUi.Choice c)->c.label).thenComparingLong(c->c.id);fuel.sort(order);water.sort(order);
        String buffers=s==null?"Buffers empty":String.format(Locale.ROOT,"Paid buffer: %.3f energy / %.3f configured; %.3f / %.3f litres",s.energyMicro/1000000.0,config.fuelBufferMicro/1000000.0,s.waterMillilitres/1000.0,config.waterBufferMillilitres/1000.0);
        return new SteamSupplyUi.View(s==null?-1:s.revision,s==null?0:s.fuelId,!waterRequired||s==null?0:s.waterId,waterRequired,
                s==null?"NO FUEL: link supply storage.":s.status,buffers,fuel,water);
    }
    public void configure(Creature actor,Item machine,long owner,boolean waterRequired,SteamSupplyUi.Selection selected) {
        Objects.requireNonNull(selected);control(actor,machine,owner);String problem=machineProblem(machine,owner);if(problem!=null)throw new IllegalArgumentException(problem);
        Supply old=store.get(machine.getWurmId());if(old!=null&&old.phase!=0){recover(old);old=store.get(machine.getWurmId());}
        if((old==null?-1:old.revision)!=selected.revision)throw new IllegalArgumentException("Steam supply settings changed. Refresh the controls.");
        if(old!=null&&(old.phase!=0||old.workToken!=0))throw new IllegalArgumentException("Inspect the interrupted native supply transaction before changing its links.");
        if(selected.fuelId>0)selection(actor,machine,owner,Items.getItemOptional(selected.fuelId).orElse(null),false);
        if(waterRequired&&selected.waterId>0)selection(actor,machine,owner,Items.getItemOptional(selected.waterId).orElse(null),true);
        if(!waterRequired&&selected.waterId!=0)throw new IllegalArgumentException("The pump uses natural boiler water.");
        persist(new Supply(machine.getWurmId(),owner,store.nextRevision(),selected.fuelId,waterRequired?selected.waterId:0,
                old==null?0:old.energyMicro,old==null?0:old.waterMillilitres,0,0,0,0,false,"Supply links saved. Buffers are replenished only when work is available.",Collections.<Debit>emptyList()));
    }
    public SteamInputs.Work work(final Item machine,final long owner,final double energyPerUnit,final long waterPerUnit) {
        return new SteamInputs.Work(){
            @Override public double limitUnits(double units){
                if(!Double.isFinite(units)||units<0)return 0;
                if(energyPerUnit>0)units=Math.min(units,config.fuelBufferMicro/(1000000.0*energyPerUnit));
                if(waterPerUnit>0)units=Math.min(units,config.waterBufferMillilitres/(double)waterPerUnit);
                return units;
            }
            @Override public SteamInputs.Payment reserve(double units){return reserveWork(machine,owner,SteamSupplyConfig.micro(units*energyPerUnit),SteamSupplyConfig.millilitres(units*waterPerUnit/1000.0));}
        };
    }
    private SteamInputs.Payment denied(String reason,boolean fault){return new SteamInputs.Payment(){public boolean ready(){return false;}public String problem(){return reason;}public boolean faulted(){return fault;}public void commit(){ }public void refund(){ }public void uncertain(){ }public void complete(double fraction){ }};}
    private SteamInputs.Payment reserveWork(Item machine,long owner,long energy,long water) {
        if(ioFault)throw new IllegalStateException("Steam supply storage is unavailable; inspect server storage before restarting.");
        String bad=machineProblem(machine,owner);if(bad!=null)return denied(bad,true);
        Supply s=store.get(machine.getWurmId());if(s==null)return denied("NO FUEL: select Fuel Supply"+(water>0?" and Water Supply":"")+".",false);
        if(s.ownerId!=owner)return denied("Steam supply ownership changed. Save supply settings again.",true);
        if(s.fault||s.phase!=0||s.workToken!=0)return denied(s.status,true);
        if(energy>config.fuelBufferMicro||water>config.waterBufferMillilitres)return denied("SUPPLY CONFIG: the work cost exceeds its configured buffer capacity.",false);
        if(s.energyMicro<energy||s.waterMillilitres<water) {
            List<Debit> planned=new ArrayList<Debit>();
            if(s.energyMicro<energy) {
                Item storage=Items.getItemOptional(s.fuelId).orElse(null);bad=storageProblem(machine,storage,owner,false);if(bad!=null)return denied(status(s,bad),false);
                List<Item> fuels=new ArrayList<Item>(storage.getItems());fuels.sort(Comparator.comparingLong((Item i)->SteamFuelPolicy.eligible(i)?-SteamFuelPolicy.microPerGram(i,config):0).thenComparingLong(Item::getWurmId));
                long needed=config.fuelBufferMicro-s.energyMicro,gathered=0;
                if(fuels.size()>4096)return denied(status(s,"NO FUEL: supply inventory exceeds its safe item bound."),false);
                for(Item fuel:fuels)if(SteamFuelPolicy.eligible(fuel)&&fuel.getParentId()==storage.getWurmId()&&fuel.getWeightGrams()==fuel.getWeightGrams(false)) {
                    long value=SteamFuelPolicy.microPerGram(fuel,config);int take=(int)Math.min(fuel.getWeightGrams(),(needed-gathered+value-1)/value);
                    if(take>0){planned.add(new Debit(fuel.getWurmId(),storage.getWurmId(),fuel.getTemplateId(),fuel.getWeightGrams(),fuel.getWeightGrams()-take,value,false));gathered+=take*value;}
                    if(needed-gathered<=0)break;
                }
                if(s.energyMicro+gathered<energy)return denied(status(s,"NO FUEL: stock linked storage with usable wood, charcoal or Coal."),false);
            }
            if(s.waterMillilitres<water) {
                Item tank=Items.getItemOptional(s.waterId).orElse(null);bad=storageProblem(machine,tank,owner,true);if(bad==null)bad=LiquidTankPolicy.sourceContentsProblem(tank);
                if(bad!=null)return denied(status(s,"NO WATER: "+bad),false);
                Item liquid=LiquidTankPolicy.contents(tank);long available=liquid==null?0:liquid.getWeightGrams();
                if(s.waterMillilitres+available<water)return denied(status(s,"NO WATER: refill the linked Industrial Water Tank."),false);
                int take=(int)Math.min(available,config.waterBufferMillilitres-s.waterMillilitres);
                planned.add(new Debit(liquid.getWurmId(),tank.getWurmId(),liquid.getTemplateId(),liquid.getWeightGrams(),liquid.getWeightGrams()-take,0,true));
            }
            if(!planned.isEmpty()) {
                persist(s.balances(s.energyMicro,s.waterMillilitres,0,0,0,1,false,"Replenishing paid steam buffers.",planned));
                try {
                    for(Debit d:planned) {
                        Item item=Items.getItemOptional(d.itemId).orElse(null),parent=Items.getItemOptional(d.parentId).orElse(null);
                        if(item==null||parent==null||item.getTemplateId()!=d.templateId||item.getParentId()!=d.parentId||!parent.getItems().contains(item)||item.getWeightGrams()!=d.before)throw new IllegalStateException("A supply item changed before its native debit.");
                        if(d.water) {
                            LiquidTankTransfers.Result drawn=LiquidTankTransfers.withdrawExact(parent,d.before-d.after);
                            if(drawn.requiresOperatorIntervention||drawn.transferredMillilitres!=d.before-d.after)throw new IllegalStateException(drawn.status);
                        }else {
                            try{item.setWeight(d.after,false,true);}catch(RuntimeException notification){if(item.getWeightGrams()!=d.after)throw notification;}
                            if(item.getWeightGrams()!=d.after||item.getParentId()!=d.parentId)throw new IllegalStateException("Native fuel debit was refused.");
                        }
                    }
                }catch(RuntimeException failure){recover(store.get(s.machineId));Supply failed=store.get(s.machineId);if(failed.phase==0)persist(failed.balances(failed.energyMicro,failed.waterMillilitres,0,0,0,0,true,"SUPPLY RECOVERY: native input debit failed; inspect supplies, then Save settings.",Collections.<Debit>emptyList()));return denied(store.get(s.machineId).status,true);}
                recover(store.get(s.machineId));s=store.get(s.machineId);
                if(s.fault||s.phase!=0)return denied(s.status,true);
            }
        }
        if(s.energyMicro<energy||s.waterMillilitres<water)return denied(status(s,"Supplies changed before work could begin."),false);
        long token=store.nextToken();persist(s.balances(s.energyMicro-energy,s.waterMillilitres-water,token,energy,water,0,false,"RUNNING: work supplies reserved.",Collections.<Debit>emptyList()));
        final long machineId=s.machineId;final long receipt=token;
        return new SteamInputs.Payment(){boolean done;
            public boolean ready(){return true;}public String problem(){return null;}public boolean faulted(){return false;}
            private void settle(double fraction,boolean fault) {
                if(done)return;Supply current=store.get(machineId);if(current==null||current.workToken!=receipt)throw new IllegalStateException("Steam work receipt changed unexpectedly.");
                if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid paid work fraction.");
                long usedEnergy=(long)Math.ceil(current.workEnergy*fraction-0.0000001),usedWater=(long)Math.ceil(current.workWater*fraction-0.0000001);
                persist(current.balances(current.energyMicro+current.workEnergy-usedEnergy,current.waterMillilitres+current.workWater-usedWater,0,0,0,0,fault,
                        fault?"SUPPLY RECOVERY: work commit is unconfirmed. Inspect output, then Save supply settings.":"Ready. Paid buffers retained for the next work action.",Collections.<Debit>emptyList()));done=true;
            }
            public void commit(){settle(1,false);}public void refund(){settle(0,false);}public void uncertain(){settle(1,true);}public void complete(double fraction){settle(fraction,false);}
        };
    }
    private String status(Supply supply,String text){if(!supply.status.equals(text))persist(supply.balances(supply.energyMicro,supply.waterMillilitres,0,0,0,0,false,text,Collections.<Debit>emptyList()));return text;}
    /** Reconcile only recorded item deltas; replaying a stale balance file is forbidden. */
    private void recover(Supply original) {
        Supply s=original;
        if(s.phase==1) {
            long energy=s.energyMicro,water=s.waterMillilitres;List<Debit> observed=new ArrayList<Debit>();
            for(Debit d:s.debits) {
                Item item=Items.getItemOptional(d.itemId).orElse(null);int actual;
                if(item==null){if(d.after!=0){faultJournal(s);return;}actual=d.before;}
                else {
                    if(item.getTemplateId()!=d.templateId||item.getParentId()!=d.parentId||item.getWeightGrams()<d.after||item.getWeightGrams()>d.before){faultJournal(s);return;}
                    actual=d.before-item.getWeightGrams();
                }
                if(d.water)water+=actual;else energy+=actual*d.microPerGram;
                if(actual>0)observed.add(new Debit(d.itemId,d.parentId,d.templateId,d.before,d.before-actual,d.microPerGram,d.water));
            }
            s=s.balances(energy,water,0,0,0,observed.isEmpty()?0:2,s.fault,"Native supplies recorded; finishing empty-item cleanup.",observed);persist(s);
        }
        if(s.phase==2) {
            try {
                for(Debit d:s.debits)if(d.after==0) {
                    Item item=Items.getItemOptional(d.itemId).orElse(null);if(item==null)continue;
                    if(item.getTemplateId()!=d.templateId||item.getParentId()!=d.parentId||item.getWeightGrams()!=0)throw new IllegalStateException("An empty debit item changed before cleanup.");
                    Items.destroyItem(item.getWurmId());if(Items.getItemOptional(d.itemId).isPresent())throw new IllegalStateException("Empty supply cleanup was not confirmed.");
                }
                persist(s.balances(s.energyMicro,s.waterMillilitres,0,0,0,0,s.fault,"Paid buffers ready.",Collections.<Debit>emptyList()));
            }catch(RuntimeException failure){faultJournal(s);}
        }
    }
    private void faultJournal(Supply s){persist(s.balances(s.energyMicro,s.waterMillilitres,s.workToken,s.workEnergy,s.workWater,s.phase,true,"SUPPLY RECOVERY: native refill state is ambiguous; inspect the recorded source items.",s.debits));}
}
