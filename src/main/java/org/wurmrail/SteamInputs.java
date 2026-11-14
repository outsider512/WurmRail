package org.wurmrail;

/** Native work gates reserve paid internal supplies immediately before a world mutation. */
public final class SteamInputs {
    private SteamInputs(){ }
    public interface Work {
        Payment reserve(double units);
        default double limitUnits(double requestedUnits){return requestedUnits;}
    }
    public interface Payment {
        boolean ready();
        String problem();
        boolean faulted();
        void commit();
        void refund();
        void uncertain();
        /** Complete this fraction of the reservation and return the unspent remainder. */
        void complete(double fraction);
    }
    public static final Work UNMETERED=units->{if(!Double.isFinite(units)||units<0)throw new IllegalArgumentException("Invalid work amount.");return SteamInputs.FREE;};
    private static final Payment FREE=new Payment(){public boolean ready(){return true;}public String problem(){return null;}public boolean faulted(){return false;}public void commit(){ }public void refund(){ }public void uncertain(){ }public void complete(double fraction){if(!Double.isFinite(fraction)||fraction<0||fraction>1)throw new IllegalArgumentException("Invalid work fraction.");}};
}
