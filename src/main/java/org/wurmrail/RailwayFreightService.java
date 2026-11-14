package org.wurmrail;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.FreightStops;
import org.wurmrail.core.FreightStops.Stop;
import org.wurmrail.core.FreightTransfers;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;

/** Arrival-triggered native cargo work, after the complete world movement commits. */
public final class RailwayFreightService {
    private static final Logger LOG=Logger.getLogger(RailwayFreightService.class.getName());
    private static final double INTERVAL=1.0;
    public interface World {
        /** Recheck ownership, physical storage, loader and car compatibility for this visit. */
        String unavailableReason(Train train,Stop stop);
        FreightTransfers.Inventory inventory(Train train,Stop stop);
    }
    private final RailNetwork network;
    private final Supplier<FreightStops> store;
    private final World world;
    private final BiConsumer<Train,String> fatalHook;
    private final Map<String,Visit> visits=new HashMap<String,Visit>();
    private final Map<String,String> recentStatus=new LinkedHashMap<String,String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> eldest) { return size()>512; }
    };
    private static final class Visit {
        final Train train;
        final String rail;
        final long serial;
        final FreightTransfers.Session session;
        double elapsed;
        boolean faulted;
        String fault;
        Visit(Train train,Stop stop) {
            this.train=train;rail=train.serviceStopRail;serial=train.serviceStopSerial;
            session=new FreightTransfers.Session(stop);
        }
    }
    public RailwayFreightService(RailNetwork network,Supplier<FreightStops> store,World world) {
        this(network,store,world,(train,reason)->{});
    }
    public RailwayFreightService(RailNetwork network,Supplier<FreightStops> store,World world,BiConsumer<Train,String> fatalHook) {
        this.network=Objects.requireNonNull(network);this.store=Objects.requireNonNull(store);this.world=Objects.requireNonNull(world);
        this.fatalHook=Objects.requireNonNull(fatalHook);
    }
    /** Pure routing hook: no inventory, world or nearby-train scan. */
    public boolean shouldService(Train train,Node rail) {
        Stop stop=rail==null?null:store.get().get(rail.name);
        return stop!=null&&stop.markerId==rail.markerId;
    }
    /** elapsed is the committed simulation interval, never time spent approaching the stop. */
    public void serviceStops(double elapsed) {
        if(!Double.isFinite(elapsed)||elapsed<0)throw new IllegalArgumentException("A non-negative freight interval is required.");
        for(Iterator<Map.Entry<String,Visit>> it=visits.entrySet().iterator();it.hasNext();) {
            Visit visit=it.next().getValue();Train current=network.trains.get(visit.train.name);
            if(current!=visit.train||current.serviceStopRail==null||current.serviceStopSerial!=visit.serial
                    ||!current.serviceStopRail.equals(visit.rail))it.remove();
        }
        for(Train train:network.trains.values()) {
            if(train.serviceStopRail==null)continue;
            String rail=train.serviceStopRail;long serial=train.serviceStopSerial;
            Stop stop=store.get().get(rail);
            Visit visit=visits.get(train.name);
            // Another arrival-triggered service can own this same native hold.
            if(stop==null&&visit==null)continue;
            if(stop==null) { finish(train,rail,serial,"Freight unavailable: this stop was removed.");continue; }
            if(visit!=null&&visit.session.stop!=stop) {
                finish(train,rail,serial,"Freight cancelled: this stop was reconfigured.");continue;
            }
            if(visit==null) {
                visit=new Visit(train,stop);visits.put(train.name,visit);
                if(train.powered)train.status=visit.session.status();
                // A fresh arrival/restart has earned no loading time yet.
                continue;
            }
            if(visit.faulted) {
                if(train.powered)fault(train,visit,visit.fault);
                else train.status=visit.fault;
                continue;
            }
            if(!train.powered) { visit.elapsed=0;continue; }
            if(train.speed>.001) {
                finish(train,rail,serial,"Freight cancelled: the train is no longer stationary.");continue;
            }
            visit.elapsed+=Math.min(elapsed,FreightTransfers.MAX_ELAPSED_SECONDS);
            if(visit.elapsed+0.000001<INTERVAL)continue;
            double earned=visit.elapsed;visit.elapsed=0;
            try {
                String unavailable=world.unavailableReason(train,stop);
                if(unavailable!=null) { finish(train,rail,serial,"Freight unavailable: "+unavailable);continue; }
                FreightTransfers.Inventory inventory=world.inventory(train,stop);
                FreightTransfers.advance(visit.session,earned,inventory);
                if(inventory.requiresOperatorIntervention()) {
                    fault(train,visit,"Freight stopped: operator attention required. "+visit.session.status());continue;
                }
                if(visit.session.finished())finish(train,rail,serial,visit.session.status());
                else train.status=visit.session.status();
            } catch(RuntimeException failure) {
                LOG.log(Level.WARNING,"Freight visit stopped for "+train.name+" at "+stop.name,failure);
                finish(train,rail,serial,"Freight unavailable: "+(failure.getMessage()==null?"native inventory error.":failure.getMessage()));
            }
        }
    }
    private void fault(Train train,Visit visit,String reason) {
        network.stop(train.name);visit.faulted=true;visit.fault=reason;visit.elapsed=0;train.status=reason;
        try { fatalHook.accept(train,reason); }
        catch(RuntimeException failure) { LOG.log(Level.SEVERE,"Could not persist freight operator stop for "+train.name,failure); }
    }
    private void finish(Train train,String rail,long serial,String status) {
        visits.remove(train.name);
        if(store.get().get(rail)!=null)recentStatus.put(rail,status);
        if(network.releaseRouteServiceStop(train.name,rail,serial)&&train.powered)train.status=status;
    }
    public String trainStatus(String trainName) {
        Visit visit=visits.get(trainName);return visit==null?"":visit.session.status();
    }
    public String status(String trainName) { return trainStatus(trainName); }
    public String statusForRail(String rail) {
        for(Visit visit:visits.values())if(visit.rail.equals(rail))return visit.train.name+": "+(visit.faulted?visit.fault:
                (visit.train.powered?"":"Paused: ")+visit.session.status());
        String result=recentStatus.get(rail);return result==null?"Ready. A scheduled train arriving here starts freight service.":result;
    }
    public String availability(Train train,Stop stop) { return world.unavailableReason(train,stop); }
    public int activeVisits() { return visits.size(); }
    public void close() { visits.clear();recentStatus.clear(); }
}
