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

/** Shared native machine-to-storage checks. Discovery occurs only on explicit configuration. */
public final class MachineTimberLinks {
    public static final double REACH=8.0;
    private static final long NO_ID=-10L;
    private MachineTimberLinks() { }

    public static Item resolve(Item machine,long ownerId,long bunkId) {
        if(bunkId<=0)throw new IllegalArgumentException("No Log Bunk is linked. Select a nearby Log Bunk.");
        Item bunk=Items.getItemOptional(bunkId).orElse(null);
        String reason=problem(machine,ownerId,bunk);
        if(reason!=null)throw new IllegalArgumentException(reason);
        return bunk;
    }

    /** Durable ownership is independent of whether the owner is currently logged in. */
    public static String problem(Item machine,long ownerId,Item bunk) {
        if(machine==null||ownerId<=0)return "The machine or its owner is unavailable.";
        if(!placed(machine))return "The machine must remain on the ground, outside inventories and trades.";
        if(!machine.isOwner(ownerId))return "The machine's ownership has changed. Configure it again.";
        if(machine.isLocked()||machine.isSealedByPlayer())return "Unlock and unseal the machine before operating it.";
        if(bunk==null)return "The linked Log Bunk no longer exists. Select another Log Bunk.";
        if(!LogBunkPolicy.isBunk(bunk))return "Choose a WurmRail Log Bunk for this machine's output.";
        if(!placed(bunk))return "The linked Log Bunk must remain on the ground, outside inventories and trades.";
        if(!bunk.isOwner(ownerId))return "The machine and linked Log Bunk must belong to the same owner.";
        if(bunk.isLocked()||bunk.isSealedByPlayer())return "Unlock and unseal the linked Log Bunk.";
        if(machine.isOnSurface()!=bunk.isOnSurface()||machine.getFloorLevel()!=bunk.getFloorLevel()
                ||machine.getBridgeId()!=bunk.getBridgeId()
                ||Math.hypot(machine.getPosX()-bunk.getPosX(),machine.getPosY()-bunk.getPosY())>REACH)
            return "The linked Log Bunk must remain within eight metres on the machine's ground level.";
        return null;
    }

    public static boolean placed(Item item) {
        return item!=null&&item.getParentId()==NO_ID&&item.getOwnerId()==NO_ID
                &&!item.isBanked()&&!item.isMailed()&&!item.isTraded()
                &&item.getFloorLevel()==0&&item.getBridgeId()==NO_ID;
    }

    public static void validateSelection(Creature actor,Item machine,long ownerId,Item bunk) {
        String reason=problem(machine,ownerId,bunk);
        if(reason!=null)throw new IllegalArgumentException(reason);
        if(actor==null||actor.isOnSurface()!=machine.isOnSurface()||actor.getFloorLevel()!=0
                ||actor.getBridgeId()!=NO_ID||Math.hypot(actor.getPosX()-machine.getPosX(),actor.getPosY()-machine.getPosY())>REACH)
            throw new IllegalArgumentException("Stand within eight metres of the machine on the same ground level.");
        if(actor.getPower()<2&&(actor.getWurmId()!=ownerId||!bunk.mayAccessHold(actor)
                ||!Methods.isActionAllowed(actor,Actions.OPEN,bunk)||!Methods.isActionAllowed(actor,Actions.TAKE,bunk)))
            throw new IllegalArgumentException("Wurm ownership or storage permissions deny this bunk link.");
    }

    public static List<Item> nearbyBunks(Creature actor,Item machine,long ownerId) {
        List<Item> result=new ArrayList<Item>();
        if(actor==null||machine==null)return result;
        scan: for(int x=Math.max(0,machine.getTileX()-2);x<=Math.min(Zones.worldTileSizeX-1,machine.getTileX()+2);x++)
            for(int y=Math.max(0,machine.getTileY()-2);y<=Math.min(Zones.worldTileSizeY-1,machine.getTileY()+2);y++) {
                VolaTile tile=Zones.getTileOrNull(x,y,machine.isOnSurface());
                if(tile==null)continue;
                for(Item item:tile.getItems()) {
                    if(!LogBunkPolicy.isBunk(item))continue;
                    try { validateSelection(actor,machine,ownerId,item);result.add(item); }
                    catch(IllegalArgumentException denied) { continue; }
                    if(result.size()>=512)break scan;
                }
            }
        result.sort(Comparator.comparing((Item item)->item.getName()).thenComparingLong(Item::getWurmId));
        return result;
    }
}
