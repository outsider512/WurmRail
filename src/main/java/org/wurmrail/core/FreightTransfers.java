package org.wurmrail.core;

import java.util.*;
import org.wurmrail.core.FreightStops.Stop;

/** Weight-credit scheduler for whole native items. It never creates, splits, clones or destroys cargo. */
public final class FreightTransfers {
    /** A poll remains bounded even when one arrival contains many tiny items. */
    public static final int MAX_MOVES_PER_PASS=64;
    /** A server pause cannot create an unlimited instant transfer allowance. */
    public static final double MAX_ELAPSED_SECONDS=5;

    public static final class Cargo {
        public final long itemId, weightGrams;
        public final int templateId;
        /** Include all contained cargo in the weight of a transferable container. */
        public Cargo(long itemId,int templateId,long weightGrams) {
            if(itemId<=0||templateId<=0||weightGrams<=0)throw new IllegalArgumentException("Invalid physical cargo.");
            this.itemId=itemId;this.templateId=templateId;this.weightGrams=weightGrams;
        }
    }

    public interface Inventory {
        /** Complete, bounded snapshot of direct eligible source items; never a world scan. */
        List<Cargo> candidates() throws Exception;
        /** Read-only native compatibility/capacity/access check against the item's current parent and weight. */
        boolean canMove(Cargo cargo) throws Exception;
        /** Move this exact item ID atomically. False or an exception must leave its native parent unchanged. */
        boolean move(Cargo cargo) throws Exception;
        /** Null means the configured storage/car/access is available for this servicing visit. */
        default String unavailableReason() { return null; }
        /** An uncertain native rollback must keep the train stopped for an operator. */
        default boolean requiresOperatorIntervention() { return false; }
        /** Optional native destination limits, requested only when a visit cannot fit more cargo. */
        default String capacityDescription() { return ""; }
    }

    public static final class Session {
        public final Stop stop;
        private boolean finished;
        private String status;
        private double credit;
        private long grams,items;
        private final Set<Long> moved=new HashSet<Long>();
        public Session(Stop stop) {
            if(stop==null)throw new IllegalArgumentException("A freight stop is required.");
            this.stop=stop;this.status=verb(stop)+": preparing cargo transfer.";
        }
        public boolean finished() { return finished; }
        public String status() { return status; }
        public long transferredGrams() { return grams; }
        public long transferredItems() { return items; }
        public double creditGrams() { return credit; }
        public void cancel(String reason) { finish("Freight cancelled: "+reason); }
        private void finish(String reason) {
            finished=true;status=reason+" Transferred "+items+" items ("+
                    String.format(Locale.ROOT,"%,.1f kg",grams/1000.0)+").";
        }
    }

    private FreightTransfers() { }

    /** Invoke only for an arrived, stationary, still-authorized train on its server thread. */
    public static void advance(Session session,double elapsedSeconds,Inventory inventory) {
        if(session==null||inventory==null||!Double.isFinite(elapsedSeconds)||elapsedSeconds<0)
            throw new IllegalArgumentException("A freight session, inventory and non-negative elapsed time are required.");
        if(session.finished)return;
        try {
            String unavailable=inventory.unavailableReason();
            if(unavailable!=null) { session.finish("Freight unavailable: "+unavailable);return; }
            session.credit=Math.min((double)Long.MAX_VALUE,session.credit+
                    Math.min(elapsedSeconds,MAX_ELAPSED_SECONDS)*session.stop.rateKgPerSecond*1000);
            List<Cargo> candidates=inventory.candidates();
            if(candidates==null)throw new IllegalStateException("No native cargo inventory snapshot.");
            Set<Long> seen=new HashSet<Long>();
            boolean eligible=false,movable=false,capacityBlocked=false;
            int moved=0;
            for(Cargo cargo:candidates) {
                if(cargo==null)throw new IllegalStateException("Invalid native cargo snapshot.");
                if(!seen.add(cargo.itemId)||session.moved.contains(cargo.itemId)
                        ||session.stop.templateId>0&&cargo.templateId!=session.stop.templateId)continue;
                eligible=true;
                if(!inventory.canMove(cargo)) { capacityBlocked=true;continue; }
                movable=true;
                if(session.credit+0.000001<cargo.weightGrams) {
                    session.status=verb(session.stop)+": waiting for transfer allowance ("+
                            String.format(Locale.ROOT,"%.1f / %.1f kg",session.credit/1000,cargo.weightGrams/1000.0)+").";
                    return;
                }
                if(!inventory.move(cargo)) { session.finish("Freight transfer stopped: cargo or inventory changed.");return; }
                session.credit=Math.max(0,session.credit-cargo.weightGrams);
                session.grams=session.grams>Long.MAX_VALUE-cargo.weightGrams?Long.MAX_VALUE:session.grams+cargo.weightGrams;
                session.items++;session.moved.add(cargo.itemId);
                if(++moved>=MAX_MOVES_PER_PASS) {
                    session.status=verb(session.stop)+": "+session.items+" items transferred.";
                    return;
                }
            }
            session.finish(!eligible?"Freight complete: source empty or no matching cargo."
                    :!movable||capacityBlocked?"Freight complete: destination full or cargo incompatible. "+inventory.capacityDescription()
                    :"Freight complete: eligible cargo transferred.");
        } catch(Exception failure) {
            String detail=failure.getMessage();
            session.finish("Freight transfer stopped: "+(detail==null?failure.getClass().getSimpleName():detail));
        }
    }

    private static String verb(Stop stop) { return stop.operation==FreightStops.Operation.LOAD?"Loading":"Unloading"; }
}
