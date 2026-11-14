import com.wurmonline.server.Items;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.*;
import java.util.List;
import org.wurmrail.*;

/** Actual supply inventories for integration fixtures; production has no free-supply path. */
public final class SteamTestSupplies {
    public static void provision(SteamSupplyController supplies,Creature actor,Item machine,boolean water,List<Item> made)throws Exception {
        Item fuel=ItemFactory.createItem(ItemList.chestLarge,70,machine.getPosX()+.4f,machine.getPosY()+.4f,0,machine.isOnSurface(),(byte)0,-10L,"Steam supply fixture");
        long owner=machine.getLastOwnerId();fuel.setLastOwnerId(owner);made.add(fuel);
        Item coal=ItemFactory.createItem(RailwayCoalItem.templateId,70,"Steam supply fixture");coal.setWeight(200000,false);made.add(coal);
        if(!fuel.insertItem(coal,true))throw new AssertionError("test fuel insertion");
        long tankId=0;
        if(water) {
            Item tank=ItemFactory.createItem(RailwayIndustrialWaterTank.templateId,70,machine.getPosX()-.4f,machine.getPosY()-.4f,0,machine.isOnSurface(),(byte)0,-10L,"Steam supply fixture");tank.setLastOwnerId(owner);made.add(tank);tankId=tank.getWurmId();
            Item liquid=ItemFactory.createItem(ItemList.water,100,"Steam supply fixture");liquid.setSizes(1,1,1);liquid.setWeight(100000000,false);made.add(liquid);
            if(!tank.insertItem(liquid,true))throw new AssertionError("test water insertion");
        }
        SteamSupplyUi.View current=supplies.snapshot(actor,machine,owner,water);
        supplies.configure(actor,machine,owner,water,new SteamSupplyUi.Selection(current.revision,fuel.getWurmId(),tankId));
    }
    public static void provision(SteamSupplyController supplies,long owner,Item machine,boolean water,List<Item> made)throws Exception {
        TrackMaterialsSmoke.InventoryActor actor=TrackMaterialsSmoke.actor(owner,machine.isOnSurface(),2);
        actor.getStatus().setLayer(machine.isOnSurface()?0:-1);
        actor.getStatus().getPosition().setPosX(machine.getPosX());actor.getStatus().getPosition().setPosY(machine.getPosY());
        actor.getStatus().getPosition().setPosZ(com.wurmonline.server.zones.Zones.calculateHeight(machine.getPosX(),machine.getPosY(),machine.isOnSurface()),false);
        provision(supplies,actor,machine,water,made);
    }
}
