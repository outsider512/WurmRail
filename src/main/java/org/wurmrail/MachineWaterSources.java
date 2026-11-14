package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.behaviours.Methods;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Optional future-consumer API. Existing drills and trains never request water here. */
public final class MachineWaterSources {
    public static final double LINK_REACH=16.0;
    private MachineWaterSources() { }
    /** Future equipment saves these IDs with its own settings, never a cached water quantity. */
    public static final class Link {
        public final long consumerId,tankId,ownerId;
        public Link(long consumerId,long tankId,long ownerId) {
            if(consumerId<=0||tankId<=0||ownerId<=0||consumerId==tankId)throw new IllegalArgumentException("Choose a separate owned water tank.");
            this.consumerId=consumerId;this.tankId=tankId;this.ownerId=ownerId;
        }
    }
    public static final class Availability {
        public final long millilitres;
        public final boolean salted;
        public final String reason;
        Availability(long amount,boolean salted,String reason){millilitres=amount;this.salted=salted;this.reason=reason;}
        public boolean ready(){return reason==null;}
    }
    public static String storageProblem(double x,double y,int layer,Item tank,long owner,double reach) {
        if(!LiquidTankPolicy.isIndustrialTank(tank))return "The linked Industrial Water Tank is missing or incompatible.";
        if(!MachineStorageLinks.placed(tank))return "The Industrial Water Tank must remain on the ground, outside inventories and trades.";
        if(!tank.isOwner(owner))return "The Industrial Water Tank must belong to the same owner.";
        if(tank.isLocked()||tank.isSealedByPlayer())return "Unlock and unseal the Industrial Water Tank.";
        if(tank.isOnSurface()!=(layer==0)||Math.hypot(tank.getPosX()-x,tank.getPosY()-y)>reach)
            return "The Industrial Water Tank is outside the linking range or on a different level.";
        return LiquidTankPolicy.contentsProblem(tank);
    }
    public static void validateSelection(Creature actor,Item consumer,Item tank,long owner) {
        if(actor==null||consumer==null||!MachineStorageLinks.placed(consumer)||!consumer.isOwner(owner)
                ||actor.isOnSurface()!=consumer.isOnSurface()||actor.getFloorLevel()!=0||actor.getBridgeId()!=-10L
                ||Math.hypot(actor.getPosX()-consumer.getPosX(),actor.getPosY()-consumer.getPosY())>8)
            throw new IllegalArgumentException("Stand beside the owned consumer on the same ground level.");
        String reason=storageProblem(consumer.getPosX(),consumer.getPosY(),consumer.isOnSurface()?0:-1,tank,owner,LINK_REACH);
        if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor.getPower()<2&&(actor.getWurmId()!=owner||!consumer.mayAccessHold(actor)||!tank.mayAccessHold(actor)
                ||!Methods.isActionAllowed(actor,Actions.OPEN,consumer)||!Methods.isActionAllowed(actor,Actions.OPEN,tank)
                ||!Methods.isActionAllowed(actor,Actions.TAKE,tank)))
            throw new IllegalArgumentException("Wurm ownership or water-storage permissions deny this link.");
    }
    public static Link link(Creature actor,Item consumer,Item tank,long owner) {
        validateSelection(actor,consumer,tank,owner);return new Link(consumer.getWurmId(),tank.getWurmId(),owner);
    }
    public static List<Item> nearbyTanks(Creature actor,Item consumer,long owner) {
        List<Item> result=new ArrayList<Item>();if(consumer==null||actor==null)return result;
        scan:for(int x=Math.max(0,consumer.getTileX()-4);x<=Math.min(Zones.worldTileSizeX-1,consumer.getTileX()+4);x++)
            for(int y=Math.max(0,consumer.getTileY()-4);y<=Math.min(Zones.worldTileSizeY-1,consumer.getTileY()+4);y++) {
                VolaTile tile=Zones.getTileOrNull(x,y,consumer.isOnSurface());if(tile==null)continue;
                for(Item tank:tile.getItems())if(LiquidTankPolicy.isIndustrialTank(tank))try {
                    validateSelection(actor,consumer,tank,owner);result.add(tank);if(result.size()>=512)break scan;
                }catch(IllegalArgumentException unavailable){ }
            }
        result.sort(Comparator.comparing((Item item)->item.getName()).thenComparingLong(Item::getWurmId));return result;
    }
    public static Availability available(Link link) {
        if(link==null)return new Availability(0,false,"No Industrial Water Tank is linked.");
        Item consumer=Items.getItemOptional(link.consumerId).orElse(null),tank=Items.getItemOptional(link.tankId).orElse(null);
        if(consumer==null||!MachineStorageLinks.placed(consumer)||!consumer.isOwner(link.ownerId))
            return new Availability(0,false,"The consumer is missing, moved into inventory or changed owner.");
        if(consumer.isLocked()||consumer.isSealedByPlayer())return new Availability(0,false,"Unlock and unseal the consumer.");
        String reason=storageProblem(consumer.getPosX(),consumer.getPosY(),consumer.isOnSurface()?0:-1,tank,link.ownerId,LINK_REACH);
        if(reason!=null)return new Availability(0,false,reason);
        Item water=LiquidTankPolicy.contents(tank);
        return new Availability(LiquidTankPolicy.storedMillilitres(tank),water!=null&&water.isSalted(),null);
    }
    /** Server-thread exact draw; callers must halt on an operator-intervention receipt. */
    public static LiquidTankTransfers.Result request(Link link,long millilitres) {
        if(millilitres<0)throw new IllegalArgumentException("Water requests must be non-negative millilitres.");
        Availability state=available(link);
        if(!state.ready())return new LiquidTankTransfers.Result(0,false,state.reason);
        return LiquidTankTransfers.withdrawExact(Items.getItemOptional(link.tankId).orElse(null),millilitres);
    }
}
