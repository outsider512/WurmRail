package org.wurmrail;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.WaterUnloadStops;
import org.wurmrail.core.WaterUnloadStops.Stop;


/** Arrival-triggered stored-water freight through the existing committed route-service hold. */
public final class RailwayWaterUnloadService {
    public static final double INTERVAL_SECONDS=1.0,MAX_ELAPSED_SECONDS=5.0;
    private static final Logger LOG=Logger.getLogger(RailwayWaterUnloadService.class.getName());
    public interface World {
        /** Revalidate the exact destination tank, native stop marker, train and compatible owned cars. */
        String unavailableReason(Train train,Stop stop);
        /** One shared volume allowance for the complete consist; report actual committed native liquid. */
        LiquidServiceTransfer unload(Train train,Stop stop,long maxMillilitres);
    }
    private static final class Visit {
        final Train train;
        final Stop stop;
        final String rail;
        final long serial;
        double elapsed,creditMillilitres;
        long transferred;
        boolean faulted;
        String status="Water freight: preparing transfer.";
        Visit(Train train,Stop stop){this.train=train;this.stop=stop;rail=train.serviceStopRail;serial=train.serviceStopSerial;}
    }
    private final RailNetwork network;
    private final Supplier<WaterUnloadStops> store;
    private final World world;
    private final BiConsumer<Train,String> fatalHook;
    private final Map<String,Visit> visits=new HashMap<String,Visit>();
    private final Map<String,String> recentStatus=new LinkedHashMap<String,String>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> eldest){return size()>512;}
    };
    public RailwayWaterUnloadService(RailNetwork network,Supplier<WaterUnloadStops> store,World world) {
        this(network,store,world,(train,reason)->{});
    }
    public RailwayWaterUnloadService(RailNetwork network,Supplier<WaterUnloadStops> store,World world,BiConsumer<Train,String> fatalHook) {
        this.network=Objects.requireNonNull(network);this.store=Objects.requireNonNull(store);
        this.world=Objects.requireNonNull(world);this.fatalHook=Objects.requireNonNull(fatalHook);
    }
    /** A pure arrival query. Passive stations never search for trains or destination tanks. */
    public boolean shouldService(Train train,Node rail) {
        Stop stop=rail==null?null:store.get().get(rail.name);
        return stop!=null&&stop.markerId==rail.markerId;
    }
    public void serviceStops(double elapsed) {
        if(!Double.isFinite(elapsed)||elapsed<0)throw new IllegalArgumentException("A finite non-negative liquid service interval is required.");
        for(Iterator<Map.Entry<String,Visit>> it=visits.entrySet().iterator();it.hasNext();) {
            Visit visit=it.next().getValue();Train current=network.trains.get(visit.train.name);
            if(current!=visit.train||current.serviceStopRail==null||current.serviceStopSerial!=visit.serial
                    ||!visit.rail.equals(current.serviceStopRail))it.remove();
        }
        for(Train train:network.trains.values()) {
            if(train.serviceStopRail==null)continue;
            String rail=train.serviceStopRail;long serial=train.serviceStopSerial;
            Stop stop=store.get().get(rail);Visit visit=visits.get(train.name);
            if(stop==null) {
                // Another handler owns an unknown hold. Only cancel a visit we
                // actually created before this unload definition was removed.
                if(visit!=null)finish(train,rail,serial,"Water freight unavailable: this station was removed.");
                continue;
            }
            if(visit!=null&&visit.stop!=stop) {
                finish(train,rail,serial,"Water freight cancelled: this station was reconfigured.");continue;
            }
            if(visit==null) {
                visit=new Visit(train,stop);visits.put(train.name,visit);
                if(train.powered)train.status=visit.status;
                // Arrival and restart begin with zero earned volume/time.
                continue;
            }
            if(visit.faulted) {
                if(train.powered)fault(train,visit,visit.status);else train.status=visit.status;
                continue;
            }
            if(!train.powered){visit.elapsed=0;continue;}
            if(train.speed>.001){finish(train,rail,serial,"Water freight cancelled: the train is no longer stationary.");continue;}
            visit.elapsed+=Math.min(elapsed,MAX_ELAPSED_SECONDS);
            if(visit.elapsed+0.000001<INTERVAL_SECONDS)continue;
            double earned=visit.elapsed;visit.elapsed=0;
            try {
                String unavailable=world.unavailableReason(train,stop);
                if(unavailable!=null){finish(train,rail,serial,"Water freight unavailable: "+unavailable);continue;}
                // Liquids are divisible. Backpressure must not accumulate a
                // large instant unloading burst when a native tank becomes free.
                visit.creditMillilitres=Math.min(MAX_ELAPSED_SECONDS*stop.rateLitresPerSecond*1000,
                        visit.creditMillilitres+earned*stop.rateLitresPerSecond*1000);
                long allowance=(long)Math.floor(visit.creditMillilitres+0.000001);
                if(allowance<=0){visit.status="Water freight: waiting for transfer allowance.";train.status=visit.status;continue;}
                LiquidServiceTransfer transfer=world.unload(train,stop,allowance);
                if(transfer==null||transfer.transferredMillilitres>allowance) {
                    fault(train,visit,"Water freight stopped: native liquid accounting requires operator attention.");continue;
                }
                visit.creditMillilitres=Math.max(0,visit.creditMillilitres-transfer.transferredMillilitres);
                visit.transferred=visit.transferred>Long.MAX_VALUE-transfer.transferredMillilitres?Long.MAX_VALUE:visit.transferred+transfer.transferredMillilitres;
                visit.status=transfer.status.isEmpty()?(transfer.finished?"Water freight complete.":"Water freight in progress."):transfer.status;
                if(transfer.requiresOperatorIntervention){fault(train,visit,visit.status);continue;}
                if(transfer.finished){finish(train,rail,serial,visit.status+" "+total(visit));continue;}
                train.status=visit.status;
            } catch(RuntimeException failure) {
                LOG.log(Level.WARNING,"Water freight stopped for "+train.name+" at "+stop.name,failure);
                // A native exception may follow a mutation. Retain the hold and
                // never silently depart with uncertain liquid accounting.
                fault(train,visit,"Water freight stopped: operator attention required. "+detail(failure));
            }
        }
    }
    private void fault(Train train,Visit visit,String reason) {
        network.stop(train.name);visit.faulted=true;visit.elapsed=0;
        String detail=line(reason);
        visit.status=detail.startsWith("Water freight stopped:")?detail:"Water freight stopped: operator attention required. "+detail;
        train.status=visit.status;
        try{fatalHook.accept(train,visit.status);}catch(RuntimeException failure){LOG.log(Level.SEVERE,"Could not persist water freight operator stop for "+train.name,failure);}
    }
    private void finish(Train train,String rail,long serial,String reason) {
        visits.remove(train.name);String status=line(reason);
        if(store.get().get(rail)!=null)recentStatus.put(rail,status);
        if(network.releaseRouteServiceStop(train.name,rail,serial)&&train.powered)train.status=status;
    }
    public String status(String trainName){Visit visit=visits.get(trainName);return visit==null?"":visit.status;}
    public String trainStatus(String trainName){return status(trainName);}
    public String statusForRail(String rail) {
        for(Visit visit:visits.values())if(visit.rail.equals(rail))return visit.train.name+": "+(visit.train.powered?"":"Paused: ")+visit.status;
        String status=recentStatus.get(rail);return status==null?"Ready. A scheduled train arriving here starts water freight.":status;
    }
    public int activeVisits(){return visits.size();}
    public String availability(Train train,Stop stop){return world.unavailableReason(train,stop);}
    public void close(){visits.clear();recentStatus.clear();}
    private static String total(Visit visit){return String.format(Locale.ROOT,"Transferred %,.1f litres.",visit.transferred/1000.0);}
    private static String detail(RuntimeException failure){String text=line(failure.getMessage());return text.isEmpty()?failure.getClass().getSimpleName():text;}
    private static String line(String text) {
        if(text==null)return "";StringBuilder result=new StringBuilder();
        for(int i=0;i<text.length()&&result.length()<600;i++){char c=text.charAt(i);result.append(Character.isISOControl(c)?' ':c);}
        return result.toString().trim();
    }
}
