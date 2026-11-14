package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Serialized native-liquid operations. Call only from the server poll thread. */
public final class LiquidTankTransfers {
    private static final Logger LOG=Logger.getLogger(LiquidTankTransfers.class.getName());
    private static final byte FAULT_FLAG=99;
    private static final Set<Long> FAULTED=Collections.newSetFromMap(new ConcurrentHashMap<Long,Boolean>());
    private static final ThreadLocal<Map<Long,Integer>> RESTORES=new ThreadLocal<Map<Long,Integer>>() {
        @Override protected Map<Long,Integer> initialValue(){return new HashMap<Long,Integer>();}
    };
    private static final ThreadLocal<Map<Long,long[]>> RESTORE_PARENTS=new ThreadLocal<Map<Long,long[]>>() {
        @Override protected Map<Long,long[]> initialValue(){return new HashMap<Long,long[]>();}
    };
    private LiquidTankTransfers() { }
    /** Native aux data survives restart; memory also blocks use if a flag write fails. */
    public static boolean isFaulted(Item tank) {
        return LiquidTankPolicy.isLiquidTank(tank)&&(tank.getAuxData()==FAULT_FLAG||FAULTED.contains(tank.getWurmId()));
    }
    private static void quarantine(Item tank) {
        if(!LiquidTankPolicy.isLiquidTank(tank))return;
        FAULTED.add(tank.getWurmId());
        try {tank.setAuxData(FAULT_FLAG);}
        catch(RuntimeException failure){LOG.log(Level.SEVERE,"Could not persist liquid recovery flag for tank "+tank.getWurmId(),failure);}
    }
    /** Native producers use the same durable stop after uncertain liquid accounting. */
    public static void stopForRecovery(Item tank) { quarantine(tank); }
    /** Emptying is manual native recovery; clearing a flag never deletes liquid. */
    public static void clearFault(Creature actor,Item tank) {
        if(actor==null||!LiquidTankPolicy.isLiquidTank(tank)||!MachineStorageLinks.placed(tank)
                ||actor.isOnSurface()!=tank.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-tank.getPosX(),actor.getPosY()-tank.getPosY())>8)
            throw new IllegalArgumentException("Stand within eight metres of the empty liquid tank on the same ground level.");
        if(actor.getPower()<2&&(!tank.isOwner(actor.getWurmId())||!tank.mayAccessHold(actor)
                ||!Methods.isActionAllowed(actor,Actions.OPEN,tank)||!Methods.isActionAllowed(actor,Actions.TAKE,tank)))
            throw new IllegalArgumentException("Only this liquid tank's owner or a GM may clear its recovery stop.");
        if(!tank.getItems().isEmpty())throw new IllegalArgumentException("Empty and inspect the tank before clearing its recovery stop.");
        tank.setAuxData((byte)0);
        if(tank.getAuxData()!=0)throw new IllegalStateException("The liquid recovery flag could not be cleared.");
        FAULTED.remove(tank.getWurmId());
    }
    public static final class Result {
        public final long transferredMillilitres;
        public final boolean requiresOperatorIntervention;
        public final String status;
        public Result(long amount,boolean fault,String status) {
            if(amount<0)throw new IllegalArgumentException("Liquid volume cannot be negative.");
            transferredMillilitres=amount;requiresOperatorIntervention=fault;this.status=status==null?"":status;
        }
    }
    private static final class State {
        final Item parent,water;
        final int weight;
        final float quality,damage,currentQuality;
        State(Item parent) {
            this.parent=parent;water=LiquidTankPolicy.contents(parent);weight=water==null?0:water.getWeightGrams();
            quality=water==null?0:water.getQualityLevel();damage=water==null?0:water.getDamage();
            currentQuality=water==null?0:water.getCurrentQualityLevel();
        }
    }
    /** Only a verified snapshot restoration can grow a transient zero-weight stack. */
    static boolean isRestoring(Item item,int requested) {
        Integer expected=RESTORES.get().get(item.getWurmId());return expected!=null&&expected==requested;
    }
    /** Only the saved source and exact saved amount may bypass a newly reduced capacity. */
    static boolean isRestoringInsertion(Item parent,Item water) {
        long[] expected=RESTORE_PARENTS.get().get(water.getWurmId());
        return expected!=null&&expected[0]==parent.getWurmId()&&expected[1]==water.getWeightGrams();
    }
    public static Result transfer(Item source,Item destination,long maxMillilitres) {
        if(maxMillilitres<0)throw new IllegalArgumentException("A non-negative transfer allowance is required.");
        if(source==destination||source!=null&&destination!=null&&source.getWurmId()==destination.getWurmId())
            return new Result(0,false,"Choose a different destination tank.");
        String problem=LiquidTankPolicy.sourceContentsProblem(source);
        if(problem==null)problem=LiquidTankPolicy.contentsProblem(destination);
        if(problem!=null)return new Result(0,false,problem);
        State from=new State(source),to=new State(destination);
        if(from.water==null)return new Result(0,false,"The source tank is empty.");
        if(to.water!=null&&!LiquidTankPolicy.sameLiquid(from.water,to.water))
            return new Result(0,false,"The destination contains a different liquid or incompatible water.");
        int amount=(int)Math.min(maxMillilitres,Math.min(from.weight,LiquidTankPolicy.remainingMillilitres(destination)));
        if(amount<=0)return new Result(0,false,"The destination tank is full or no transfer allowance remains.");
        if(to.water==null&&amount==from.weight)return moveWhole(from,destination);
        Item staged=null;boolean changed=false;
        try {
            if(to.water==null)staged=copyLiquid(from.water,amount);
            changed=true;
            weight(from.water,from.weight-amount);
            if(to.water==null) {
                try {destination.insertItem(staged,false,true);}
                catch(RuntimeException notice){if(!contains(destination,staged))throw notice;}
                if(!contains(destination,staged)||source.getItems().contains(staged))
                    throw new IllegalStateException("Native destination insertion was refused.");
            } else {
                weight(to.water,to.weight+amount);
                mixedQuality(to.water,(to.currentQuality*to.weight+from.currentQuality*amount)/(to.weight+amount));
            }
            if(from.water.getWeightGrams()+LiquidTankPolicy.storedMillilitres(destination)!=from.weight+to.weight)
                throw new IllegalStateException("Native liquid conservation could not be verified.");
            if(from.water.getWeightGrams()==0)deleteEmpty(from.parent,from.water);
            if(LiquidTankPolicy.sourceContentsProblem(source)!=null||LiquidTankPolicy.contentsProblem(destination)!=null)
                throw new IllegalStateException("Native liquid links need inspection.");
            return new Result(amount,false,"Water transferred.");
        } catch(Exception failure) {
            // Source deletion is the final commit. Never roll back the credited
            // destination after the original source item has ceased to exist.
            if(amount==from.weight&&!Items.getItemOptional(from.water.getWurmId()).isPresent()
                    &&LiquidTankPolicy.storedMillilitres(destination)==to.weight+amount) {
                LOG.log(Level.SEVERE,"Committed liquid transfer needs native-link inspection",failure);
                quarantine(source);quarantine(destination);
                return new Result(amount,true,"Water reached the destination; inspect native source links before restarting.");
            }
            // One side's quality or notification failure must not prevent the
            // other side's mass restoration. Inspect actual destination mass
            // before restoring the source: retained credited liquid must never
            // also be restored in its source.
            try {
                if(staged!=null)cancelCopy(destination,staged);
                else if(to.water!=null)restore(to);
            } catch(Exception rollback){failure.addSuppressed(rollback);}
            long retained=retainedDestination(to,staged);
            try {
                if(changed&&retained>=0&&retained<=amount)restore(from,from.weight-(int)retained);
            }catch(Exception rollback){failure.addSuppressed(rollback);}
            boolean restored=matches(from)&&matches(to)&&(staged==null||!Items.getItemOptional(staged.getWurmId()).isPresent());
            if(!restored){quarantine(source);quarantine(destination);}
            String splitDetail=!restored&&staged!=null?" Split item "+staged.getWurmId()+" (parent "+staged.getParentId()+") needs inspection.":"";
            LOG.log(Level.SEVERE,"Liquid transfer stopped between "+source.getWurmId()+" and "+destination.getWurmId()+splitDetail,failure);
            long actual=restored?0:Math.max(0,LiquidTankPolicy.storedMillilitres(destination)-to.weight);
            return new Result(actual,true,restored?"Water transfer stopped; original inventories were restored. Inspect the server log before restarting."
                    :"Water transfer requires operator inspection of both tanks; native rollback could not be confirmed."+splitDetail);
        } finally { RESTORES.remove(); }
    }
    private static Result moveWhole(State from,Item destination) {
        try {
            try {destination.insertItem(from.water,false,true);}
            catch(RuntimeException notice){if(!contains(destination,from.water)||from.parent.getItems().contains(from.water))throw notice;}
            if(!contains(destination,from.water)||from.parent.getItems().contains(from.water))
                throw new IllegalStateException("The destination refused the native liquid item.");
            return new Result(from.weight,false,"Water transferred.");
        } catch(Exception failure) {
            try {
                if(destination.getItems().contains(from.water))destination.dropItem(from.water.getWurmId(),false);
                restoreParent(from,from.weight);
                if(!matches(from)||destination.getItems().contains(from.water))throw new IllegalStateException("Native liquid parent rollback failed.");
                return new Result(0,true,"Water transfer stopped; its original liquid item was restored.");
            } catch(Exception rollback) {
                failure.addSuppressed(rollback);LOG.log(Level.SEVERE,"Liquid item requires operator inspection: "+from.water.getWurmId(),failure);
                quarantine(from.parent);quarantine(destination);
                return new Result(contains(destination,from.water)?from.weight:0,true,"Native liquid links need operator inspection before restarting.");
            }
        }
    }
    /** Exact requests are all-or-nothing when capacity is insufficient; failures are reported explicitly. */
    public static Result withdrawExact(Item tank,long requestedMillilitres) {
        if(requestedMillilitres<0)throw new IllegalArgumentException("A non-negative water request is required.");
        String problem=LiquidTankPolicy.sourceContentsProblem(tank);
        if(problem!=null)return new Result(0,false,problem);
        State state=new State(tank);
        if(requestedMillilitres==0)return new Result(0,false,"No water requested.");
        if(state.weight<requestedMillilitres)return new Result(0,false,"Insufficient water; nothing was removed.");
        try {
            weight(state.water,state.weight-(int)requestedMillilitres);
            if(state.water.getWeightGrams()==0)deleteEmpty(tank,state.water);
            return new Result(requestedMillilitres,false,"Water supplied.");
        } catch(Exception failure) {
            try {restore(state);if(matches(state))return new Result(0,true,"Water draw stopped; the original quantity was restored.");}
            catch(Exception rollback){failure.addSuppressed(rollback);}
            LOG.log(Level.SEVERE,"Water draw needs inspection for tank "+tank.getWurmId(),failure);
            quarantine(tank);
            long remaining=LiquidTankPolicy.storedMillilitres(tank);
            return new Result(Math.max(0,state.weight-remaining),true,"Water draw needs operator inspection before another request.");
        } finally {RESTORES.remove();}
    }
    private static Item copyLiquid(Item source,int amount) throws Exception {
        Item copy=null;
        try {
            copy=ItemFactory.createItem(source.getTemplateId(),source.getQualityLevel(),source.getMaterial(),source.getRarity(),source.getCreatorName());
            copy.setSizes(1,1,1);copy.setLastOwnerId(source.getLastOwnerId());copy.setData(source.getData1(),source.getData2());
            copy.setRealTemplate(source.getRealTemplateId());copy.setTemperature(source.getTemperature());
            copy.setAuxData(source.getAuxData());copy.setName(source.getActualName());copy.setCreator(source.getCreatorName());
            copy.setDescription(source.getDescription());
            if(source.getColor()!=-1)copy.setColor(source.getColor());if(source.getColor2()!=-1)copy.setColor2(source.getColor2());
            copy.setIsSalted(source.isSalted());copy.setDamage(source.getDamage());weight(copy,amount);
            if(!LiquidTankPolicy.sameLiquid(source,copy))throw new IllegalStateException("Native liquid identity could not be copied.");
            return copy;
        } catch(Exception failure) {
            if(copy!=null)try{cancelCopy(null,copy);}catch(Exception cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    private static void weight(Item item,int requested) {
        try {item.setWeight(requested,false,true);}
        catch(RuntimeException notice){if(item.getWeightGrams()!=requested)throw notice;}
        if(item.getWeightGrams()!=requested)throw new IllegalStateException("Native liquid weight change was refused.");
    }
    private static void restore(State state) throws Exception {
        restore(state,state.weight);
    }
    private static void restore(State state,int expectedWeight) throws Exception {
        if(state.water==null)return;
        if(!Items.getItemOptional(state.water.getWurmId()).isPresent())throw new IllegalStateException("Original liquid item is unavailable.");
        RESTORES.get().put(state.water.getWurmId(),expectedWeight);
        try {weight(state.water,expectedWeight);}finally {RESTORES.get().remove(state.water.getWurmId());}
        state.water.setQualityLevel(state.quality);state.water.setDamage(state.damage);
        restoreParent(state,expectedWeight);
    }
    private static void restoreParent(State state,int expectedWeight) throws Exception {
        if(contains(state.parent,state.water))return;
        if(state.parent.getItems().contains(state.water))state.parent.dropItem(state.water.getWurmId(),false);
        RESTORE_PARENTS.get().put(state.water.getWurmId(),new long[]{state.parent.getWurmId(),expectedWeight});
        try {state.parent.insertItem(state.water,true,true);}
        finally {RESTORE_PARENTS.get().remove(state.water.getWurmId());}
    }
    /** Includes a surviving detached staged copy, so rollback cannot duplicate it. */
    private static long retainedDestination(State destination,Item staged) {
        if(staged!=null)return Items.getItemOptional(staged.getWurmId()).isPresent()?Math.max(0,staged.getWeightGrams()):0;
        if(destination.water==null)return 0;
        if(!Items.getItemOptional(destination.water.getWurmId()).isPresent())return -1;
        return (long)destination.water.getWeightGrams()-destination.weight;
    }
    private static boolean matches(State state) {
        return state.water==null?state.parent.getItems().isEmpty():contains(state.parent,state.water)
                &&state.parent.getItems().size()==1&&state.water.getWeightGrams()==state.weight
                &&Math.abs(state.water.getQualityLevel()-state.quality)<.001&&Math.abs(state.water.getDamage()-state.damage)<.001;
    }
    private static void mixedQuality(Item water,float quality) {
        water.setQualityLevel(quality);water.setDamage(0f);
        if(Math.abs(water.getCurrentQualityLevel()-quality)>.001)throw new IllegalStateException("Native mixed liquid quality could not be verified.");
    }
    private static void cancelCopy(Item parent,Item copy) throws Exception {
        try {
            if(parent!=null&&parent.getItems().contains(copy))parent.dropItem(copy.getWurmId(),false);
            Items.destroyItem(copy.getWurmId());
        }catch(Exception failure) {
            throw new IllegalStateException("Uncommitted liquid item "+copy.getWurmId()+" (parent "+copy.getParentId()+") could not be cleaned up.",failure);
        }
        if(Items.getItemOptional(copy.getWurmId()).isPresent()||parent!=null&&parent.getItems().contains(copy))
            throw new IllegalStateException("Uncommitted liquid cleanup failed for item "+copy.getWurmId()+" (parent "+copy.getParentId()+").");
    }
    private static void deleteEmpty(Item parent,Item water) {
        if(water.getWeightGrams()!=0)throw new IllegalStateException("Only an empty liquid item may be removed.");
        try {Items.destroyItem(water.getWurmId());}
        catch(RuntimeException notice){if(Items.getItemOptional(water.getWurmId()).isPresent()||parent.getItems().contains(water))throw notice;}
        if(Items.getItemOptional(water.getWurmId()).isPresent()||parent.getItems().contains(water))throw new IllegalStateException("Empty liquid cleanup failed.");
    }
    private static boolean contains(Item parent,Item water){return water!=null&&water.getParentId()==parent.getWurmId()&&parent.getItems().contains(water);}
}
