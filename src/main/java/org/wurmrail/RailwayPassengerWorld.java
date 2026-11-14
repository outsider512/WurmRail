package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.Players;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.MovementScheme;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.creatures.SpellEffectsEnum;
import com.wurmonline.server.structures.Blocking;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;

import java.util.ArrayList;
import java.util.List;
import org.wurmrail.core.PassengerStations.Platform;

/** Native passenger operations, called only after a train stops with this car beside a platform. */
public final class RailwayPassengerWorld {
    private static final long NO_ID=-10L;
    /** A private HUD entry, independent of real spell effects or their identifiers. */
    public static final long WAITING_STATUS_ID=-0x5755524d5241494cL;
    private RailwayPassengerWorld() { }

    /** A snapshot of real connected player objects; riders remain included for drop-off checks. */
    public static Creature[] onlinePlayers() {
        List<Creature> result=new ArrayList<Creature>();
        for(Player player:Players.getInstance().getPlayers()) if(player!=null&&player.hasLink()) result.add(player);
        return result.toArray(new Creature[result.size()]);
    }

    /** Automatic pickups never interrupt mounted, trading, fighting or teleporting players. */
    public static boolean eligible(Creature player) {
        return ready(player)&&player.getVehicle()==NO_ID&&player.getDraggedItem()==null;
    }

    /** Physical area membership must not depend on eligibility to board. */
    public static boolean inStationArea(Creature player,Platform station) {
        return player!=null&&player.getTileX()==station.x&&player.getTileY()==station.y
                &&player.isOnSurface()==(station.layer==0)&&player.getFloorLevel()==0&&player.getBridgeId()==NO_ID;
    }

    /** A visible native status badge with a station tooltip; no gameplay buff is created. */
    public static void waitingIndicator(Creature player,Platform station,boolean waiting) {
        if(player==null||!player.isPlayer()||!player.hasLink())return;
        if(waiting)player.getCommunicator().sendAddStatusEffect(WAITING_STATUS_ID,SpellEffectsEnum.LINKS,-1,
                "Waiting for train at "+station.name);
        else player.getCommunicator().sendRemoveFromStatusEffectBar(WAITING_STATUS_ID);
    }

    /** Pure preflight: native boarding rechecks permissions, range and each seat's wall crossing. */
    public static boolean hasFreeSeat(Creature player,Item car) {
        try {
            if(!eligible(player)||!availableCar(car)||player.isOnSurface()!=car.isOnSurface()
                    ||player.getFloorLevel()!=car.getFloorLevel()
                    ||!player.isWithinDistanceTo(car.getPosX(),car.getPosY(),car.getPosZ(),8.0f)) return false;
            if(!WurmRailWorld.canManage(player,car)&&!car.mayPassenger(player)) return false;
            return freeSeatIndex(car)>=0;
        } catch(RuntimeException unavailable) { return false; }
    }

    public static int freeSeatIndex(Item car) {
        if(!WurmRailWorld.isRailCar(car)) return -1;
        Vehicle vehicle=Vehicles.getVehicle(car);
        if(vehicle!=null) for(int index=0;index<vehicle.seats.length;index++) {
            Seat seat=vehicle.seats[index];
            if(seat.type==Seat.TYPE_PASSENGER&&!seat.isOccupied()) return index;
        }
        return -1;
    }

    public static boolean board(Creature player,Item car) throws Exception {
        if(!hasFreeSeat(player,car)) return false;
        return WurmRailWorld.boardFromPlatform(player,car)&&player.getVehicle()==car.getWurmId();
    }

    /** Shared configuration/service check. A platform is a clear dry ground or cave-floor tile. */
    public static String validatePlatformTile(Creature player,int tileX,int tileY,int layer) {
        String terrain=WurmRailWorld.validateTile(tileX,tileY,layer);
        if(terrain!=null) return terrain;
        if(player!=null&&(player.isOnSurface()!=(layer==0)||player.getFloorLevel()!=0||player.getBridgeId()!=NO_ID))
            return "Use a platform on the ground on the same layer.";
        VolaTile tile=Zones.getTileOrNull(tileX,tileY,layer==0);
        if(tile!=null) for(Item item:tile.getItems())
            if(item.isVehicle()) return "A parked vehicle blocks this platform tile.";
        return null;
    }

    /** Pure check: a blocked destination never detaches a passenger or consumes their destination. */
    public static boolean canDropoff(Creature player,Item expectedCar,int tileX,int tileY,int layer) {
        try { return dropoffError(player,expectedCar,tileX,tileY,layer)==null; }
        catch(Exception unsafe) { return false; }
    }

    /** Native disembarking clears seats and mount control; native intraTeleport moves zones and camera. */
    public static boolean disembarkTo(Creature player,Item expectedCar,int tileX,int tileY,int layer) throws Exception {
        if(!canDropoff(player,expectedCar,tileX,tileY,layer)) return false;
        final float x=tileX*4f+2f,y=tileY*4f+2f,z=Zones.calculateHeight(x,y,layer==0);
        final float oldX=player.getPosX(),oldY=player.getPosY(),oldZ=player.getPositionZ(),rotation=player.getStatus().getRotation();
        try {
            player.disembark(false);
            if(player.getVehicle()!=NO_ID) throw new IllegalStateException("The native vehicle could not release this passenger.");
            player.setMountAction(null);
            player.intraTeleport(x,y,z,rotation,layer,"left vehicle at railway platform");
            if(player.getVehicle()!=NO_ID||player.getTileX()!=tileX||player.getTileY()!=tileY
                    ||player.isOnSurface()!=(layer==0)||Math.abs(player.getPosX()-x)>.01f||Math.abs(player.getPosY()-y)>.01f)
                throw new IllegalStateException("The passenger could not reach the drop-off tile.");
            player.savePosition(0);
            return true;
        } catch(Exception failed) {
            // An exceptional native move must not silently leave a rider detached from a train.
            if(player.getVehicle()==NO_ID) {
                try {
                    player.intraTeleport(oldX,oldY,oldZ,rotation,layer,"left vehicle: restoring railway passenger");
                    WurmRailWorld.boardFromPlatform(player,expectedCar);
                    if(player.getVehicle()!=expectedCar.getWurmId())
                        throw new IllegalStateException("Native passenger boarding rollback failed.");
                } catch(Exception rollback) { failed.addSuppressed(rollback); }
            }
            throw failed;
        }
    }

    private static String dropoffError(Creature player,Item car,int tileX,int tileY,int layer) throws Exception {
        if(!ready(player)||!availableCar(car)||player.getVehicle()!=car.getWurmId()) return "This passenger is unavailable.";
        if(player.isOnSurface()!=(layer==0)||car.isOnSurface()!=(layer==0)||car.getFloorLevel()!=0)
            return "The drop-off must be on the same ground layer.";
        if(Math.abs(car.getTileX()-tileX)+Math.abs(car.getTileY()-tileY)!=1)
            return "Stop beside this platform before leaving the train.";
        Vehicle vehicle=Vehicles.getVehicle(car);
        Seat seat=vehicle==null?null:vehicle.getSeatFor(player.getWurmId());
        if(seat==null||seat.type!=Seat.TYPE_PASSENGER) return "The native passenger seat is unavailable.";
        String terrain=validatePlatformTile(player,tileX,tileY,layer);
        if(terrain!=null) return terrain;
        float x=tileX*4f+2f,y=tileY*4f+2f,z=Zones.calculateHeight(x,y,layer==0);
        WurmRailWorld.validateSegment(car.getPosX(),car.getPosY(),x,y,layer);
        if(Blocking.getBlockerBetween(player,player.getPosX(),player.getPosY(),x,y,player.getPositionZ(),z,
                layer==0,layer==0,false,4,-1L,player.getBridgeId(),NO_ID,false)!=null)
            return "A wall, fence or door blocks the drop-off.";
        return null;
    }

    private static boolean availableCar(Item car) {
        return WurmRailWorld.isRailCar(car)&&Items.getItemOptional(car.getWurmId()).orElse(null)==car
                &&car.getParentId()==NO_ID&&car.getOwnerId()==NO_ID&&!car.isMailed()&&!car.isTraded()
                &&!car.isBanked()
                &&car.getBridgeId()==NO_ID&&car.getFloorLevel()==0;
    }

    private static boolean ready(Creature player) {
        try {
            if(player==null||!player.isPlayer()||!player.hasLink()||player.isDead()||player.isTeleporting()
                    ||player.isTransferring()||player.isTrading()||player.isFighting()
                    ||player.getFloorLevel()!=0||player.getBridgeId()!=NO_ID) return false;
            MovementScheme movement=player.getMovementScheme();
            return movement!=null&&!movement.isIntraTeleporting();
        } catch(RuntimeException unavailable) { return false; }
    }
}
