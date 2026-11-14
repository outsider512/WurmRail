package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import java.util.*;
import java.util.function.Supplier;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;

/** Native transfers happen after the complete simulation/world movement transaction commits. */
public final class RailwayPassengerService {
    public interface World {
        Creature[] players();
        boolean present(Platform platform);
        boolean waiting(Creature player,Platform platform);
        /** Physical presence only: eligibility changes must not clear arrival protection. */
        default boolean inArea(Creature player,Platform platform) { return waiting(player,platform); }
        default void waitingIndicator(Creature player,Platform platform,boolean waiting) { }
        boolean freeSeat(Creature player,long carId);
        boolean board(Creature player,long carId) throws Exception;
        boolean drop(Creature player,long carId,Platform platform) throws Exception;
        void message(Creature player,String text);
    }
    private final RailNetwork network;
    private final Supplier<PassengerStations> store;
    private final World world;
    private Creature[] online=new Creature[0];
    private final Map<Long,Map<Long,Presence>> presence=new HashMap<Long,Map<Long,Presence>>();
    private boolean dirty;
    private final Map<Long,Long> lastFailure=new HashMap<Long,Long>();
    public RailwayPassengerService(RailNetwork network,Supplier<PassengerStations> store,World world) {
        this.network=Objects.requireNonNull(network); this.store=Objects.requireNonNull(store); this.world=Objects.requireNonNull(world);
    }
    private static final class Presence {
        Creature player;
        Platform platform;
        boolean waiting;
        Presence(Creature player,Platform platform) { this.player=player; this.platform=platform; }
    }
    /** Update live station presence once per tick, before the simulation asks its read-only stop predicate. */
    public void refreshPlayers() {
        online=world.players(); if(online==null) online=new Creature[0];
        Map<Long,Creature> connected=new HashMap<Long,Creature>();
        for(Creature player:online)if(player!=null)connected.put(player.getWurmId(),player);
        PassengerStations stations=store.get();
        // An absent player has not been observed leaving. Retain durable arrival protection
        // through disconnects and server restarts until their physical position is known.
        for(Creature player:connected.values())for(long id:stations.recentStations(player.getWurmId())) {
            Platform p=stations.platforms.get(id);
            if(p!=null&&!world.inArea(player,p)&&stations.clearArrival(player.getWurmId(),id))dirty=true;
        }
        Map<Long,Platform> available=new LinkedHashMap<Long,Platform>();
        for(Platform p:stations.platforms.values())if(world.present(p))available.put(p.itemId,p);
        // One HUD identity is used for a player's waiting badge. Remove every old badge
        // before adding any new badge, including when adjacent stations are stored backwards.
        for(Iterator<Map.Entry<Long,Map<Long,Presence>>> it=presence.entrySet().iterator();it.hasNext();) {
            Map.Entry<Long,Map<Long,Presence>> e=it.next();
            Platform p=available.get(e.getKey());
            for(Iterator<Map.Entry<Long,Presence>> players=e.getValue().entrySet().iterator();players.hasNext();) {
                Map.Entry<Long,Presence> entry=players.next(); Presence state=entry.getValue();
                Creature player=connected.get(entry.getKey());
                if(p==null||player==null||!world.inArea(player,p)) { setWaiting(state,false); players.remove(); continue; }
                boolean renamed=!state.platform.name.equals(p.name),reconnected=state.player!=player;
                state.player=player;
                if(renamed||reconnected||stations.recentlyDropped(player.getWurmId(),p.itemId)||!world.waiting(player,p))setWaiting(state,false);
                state.platform=p;
            }
            if(e.getValue().isEmpty())it.remove();
        }
        for(Platform p:available.values())for(Creature player:connected.values())if(world.inArea(player,p)) {
            Presence state=state(player,p);
            setWaiting(state,!stations.recentlyDropped(player.getWurmId(),p.itemId)&&world.waiting(player,p));
        }
        lastFailure.keySet().retainAll(connected.keySet());
    }
    /** Read-only predicate, also called for proposed future centres by the simulation. */
    public boolean shouldStop(Train train,Node rail,long carId) {
        for(Platform p:store.get().platforms.values()) if(p.rail.equals(rail.name)&&p.layer==rail.layer&&world.present(p)) {
            for(Creature player:online) {
                if(player==null)continue;
                if(player.getVehicle()==carId&&store.get().destination(player.getWurmId())==p.itemId) return true;
                if(eligibleWaiting(player,p)&&world.freeSeat(player,carId)) return true;
            }
        }
        return false;
    }
    /** A stop remains powered exactly as it was; STOP or end-of-track is never overridden. */
    public void serviceStops() {
        for(Train train:new ArrayList<Train>(network.trains.values())) {
            Set<Long> handled=new HashSet<Long>();
            if(train.passengerStopRail!=null) {
                String rail=train.passengerStopRail; long serial=train.passengerStopSerial,car=train.passengerStopCarId;
                try { serviceCar(rail,car); handled.add(car); }
                finally { network.releasePassengerStop(train.name,rail,serial); }
            }
            // New arrivals can use a train that remains parked after its first service.
            if(!train.powered&&train.speed<.001) for(Pose pose:train.poses)
                if(pose.railName!=null&&!handled.contains(pose.carId))serviceCar(pose.railName,pose.carId);
        }
    }
    private void serviceCar(String rail,long car) {
                for(Platform p:new ArrayList<Platform>(store.get().platforms.values()))
                    if(p.rail.equals(rail)&&world.present(p)) {
                        for(Creature player:online) if(player!=null&&player.getVehicle()==car&&store.get().destination(player.getWurmId())==p.itemId) {
                            try {
                                if(world.drop(player,car,p)) {
                                    store.get().markDropped(player.getWurmId(),p.itemId);
                                    Presence state=state(player,p);
                                    setWaiting(state,false);
                                    store.get().choose(player.getWurmId(),-10L); dirty=true;
                                    world.message(player,"Arrived at "+p.name+". Your destination has been cleared.");
                                } else failure(player,new IllegalStateException("The drop-off is blocked or you are unavailable. You remain aboard with your destination selected."));
                            } catch(Exception failure) { failure(player,failure); }
                        }
                    }
                for(Platform p:store.get().platforms.values()) if(p.rail.equals(rail)&&world.present(p)) {
                    for(Creature player:online) if(player!=null&&eligibleWaiting(player,p)&&world.freeSeat(player,car)) {
                        try {
                            if(world.board(player,car)) {
                                clearWaiting(player.getWurmId());
                                world.message(player,"Boarded at "+p.name+". Use Choose station destination on a rail vehicle to select your station.");
                            }
                        } catch(Exception failure) { failure(player,failure); }
                    }
                }
    }
    public boolean dirty() { return dirty; }
    public void saved() { dirty=false; }
    /** Remove live indicators before a world unload, shutdown or service replacement. */
    public void close() {
        for(Map<Long,Presence> at:presence.values())for(Presence state:at.values())setWaiting(state,false);
        presence.clear(); lastFailure.clear(); online=new Creature[0];
    }
    public boolean isWaiting(long playerId,Platform p) { Presence state=find(playerId,p); return state!=null&&state.waiting; }
    public boolean recentlyDropped(long playerId,Platform p) { return store.get().recentlyDropped(playerId,p.itemId); }
    public int waitingCount(Platform p) { int n=0; Map<Long,Presence> at=presence.get(p.itemId); if(at!=null)for(Presence state:at.values())if(state.waiting)n++; return n; }
    private boolean eligibleWaiting(Creature player,Platform p) { return isWaiting(player.getWurmId(),p)&&!recentlyDropped(player.getWurmId(),p)&&world.waiting(player,p); }
    private Presence find(long playerId,Platform p) { Map<Long,Presence> at=presence.get(p.itemId); return at==null?null:at.get(playerId); }
    private Presence state(Creature player,Platform p) {
        Map<Long,Presence> at=presence.get(p.itemId);
        if(at==null) { at=new HashMap<Long,Presence>(); presence.put(p.itemId,at); }
        Presence state=at.get(player.getWurmId());
        if(state==null) { state=new Presence(player,p); at.put(player.getWurmId(),state); }
        return state;
    }
    private void clearWaiting(long playerId) { for(Map<Long,Presence> at:presence.values()) { Presence state=at.get(playerId); if(state!=null)setWaiting(state,false); } }
    private void setWaiting(Presence state,boolean waiting) {
        if(state.waiting!=waiting) { state.waiting=waiting; world.waitingIndicator(state.player,state.platform,waiting); }
    }
    private void failure(Creature player,Exception error) {
        long now=System.currentTimeMillis(); Long previous=lastFailure.get(player.getWurmId());
        if(previous==null||now-previous>15000) {
            lastFailure.put(player.getWurmId(),now);
            world.message(player,error.getMessage()==null?"The passenger transfer was blocked; your destination is unchanged.":error.getMessage());
        }
    }
}
