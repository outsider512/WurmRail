package org.wurmrail;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;
import org.wurmrail.core.WaterFillStops;
import org.wurmrail.core.WaterFillStops.Stop;

/** Inactive compatibility API. Natural water is produced only by Steam Water Pumps. */
@Deprecated
public final class RailwayWaterFillService {
    public static final double INTERVAL_SECONDS=1,MAX_ELAPSED_SECONDS=5;
    private static final String RETIRED="Water Fill Stations are deprecated. Use a Steam Water Pump, Industrial Water Tank and water freight stop.";
    public interface World {
        String unavailableReason(Train train,Stop stop);
        Transfer fill(Train train,Stop stop,long maxMillilitres);
    }
    /** Legacy return type retained for source-compatible liquid transfer adapters. */
    public static final class Transfer extends LiquidServiceTransfer {
        public Transfer(long volume,boolean finished,boolean fault,String status){super(volume,finished,fault,status);}
        public static Transfer ongoing(long volume,String status){return new Transfer(volume,false,false,status);}
        public static Transfer completed(long volume,String status){return new Transfer(volume,true,false,status);}
        public static Transfer fault(long volume,String status){return new Transfer(volume,false,true,status);}
    }
    public RailwayWaterFillService(RailNetwork network,Supplier<WaterFillStops> store,World world){this(network,store,world,(train,reason)->{});}
    public RailwayWaterFillService(RailNetwork network,Supplier<WaterFillStops> store,World world,BiConsumer<Train,String> fatalHook){
        Objects.requireNonNull(network);Objects.requireNonNull(store);Objects.requireNonNull(world);Objects.requireNonNull(fatalHook);
    }
    public boolean shouldService(Train train,Node rail){return false;}
    public void serviceStops(double elapsed){
        if(!Double.isFinite(elapsed)||elapsed<0)throw new IllegalArgumentException("A finite non-negative interval is required.");
    }
    public String status(String trainName){return RETIRED;}
    public String trainStatus(String trainName){return RETIRED;}
    public String statusForRail(String rail){return RETIRED;}
    public int activeVisits(){return 0;}
    public String availability(Train train,Stop stop){return RETIRED;}
    public void close(){ }
}
