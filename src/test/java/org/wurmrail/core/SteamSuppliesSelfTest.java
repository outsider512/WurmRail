package org.wurmrail.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.SteamSupplies.*;

/** Durable paid resources must never fall back to an older, larger balance. */
public final class SteamSuppliesSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-paid-supplies-"),file=folder.resolve("supplies.properties"),backup=folder.resolve("supplies.properties.bak");
        try {
            SteamSupplies state=SteamSupplies.load(file);check(state.supplies.isEmpty(),"upgrade starts without invented supplies");
            Supply first=SteamSupplies.initial(100,900,state.nextRevision(),800,801);
            state.put(first.balances(60000000,100000,0,0,0,0,false,"Paid buffer",Collections.<Debit>emptyList()));state.save(file);
            long token=state.nextToken();state.put(first.balances(50000000,90000,token,10000000,10000,0,false,"Work reserved",Collections.<Debit>emptyList()));state.save(file);
            SteamSupplies loaded=SteamSupplies.load(file);Supply pending=loaded.get(100);
            check(pending.energyMicro==50000000&&pending.waterMillilitres==90000&&pending.workEnergy==10000000&&pending.workWater==10000&&pending.workToken==token,"restart retains reduced balance and reserved work together");
            check(pending.fuelId==800&&pending.waterId==801&&pending.ownerId==900&&pending.revision==first.revision,"explicit links and owner retain identity");
            check(loaded.nextToken()>token&&loaded.nextRevision()>first.revision,"restart cannot reuse work receipts or configuration revisions");
            List<Debit> journal=new ArrayList<Debit>(Arrays.asList(new Debit(201,800,204,20000,19000,3000,false),new Debit(202,801,128,100000,90000,0,true)));
            loaded.put(first.balances(0,0,0,0,0,1,false,"Refill in progress",journal));journal.clear();loaded.save(file);
            pending=SteamSupplies.load(file).get(100);check(pending.phase==1&&pending.debits.size()==2&&pending.debits.get(0).microPerGram==3000&&pending.debits.get(1).water,"unconfirmed native item deltas persist without pre-crediting supplies");
            try{pending.debits.clear();throw new AssertionError("mutable journal");}catch(UnsupportedOperationException expected){assertions++;}
            byte[] valid=Files.readAllBytes(file);Files.write(file,new byte[]{1,2});rejectIo(()->SteamSupplies.load(file),"corrupt primary cannot replay an older backup balance");
            check(Files.exists(backup),"the backup remains available for inspection");Files.delete(file);rejectIo(()->SteamSupplies.load(file),"missing primary with a backup cannot silently mint a fresh registry");Files.write(file,valid);
            invalid(()->new Debit(1,2,3,100,100,1000,false),"zero debit");invalid(()->new Debit(1,2,3,100,0,1000,true),"water cannot contain fuel energy");
            invalid(()->first.balances(-1,0,0,0,0,0,false,"Bad",Collections.<Debit>emptyList()),"negative balance");
            invalid(()->first.balances(0,0,0,1,0,0,false,"Bad",Collections.<Debit>emptyList()),"reserved work needs a receipt");
            System.out.println("SteamSuppliesSelfTest: "+assertions+" assertions passed.");
        }finally{try(java.util.stream.Stream<Path> files=Files.walk(folder)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
    private interface Task{void run()throws Exception;}
    private static void rejectIo(Task t,String message)throws Exception{try{t.run();throw new AssertionError(message);}catch(IOException expected){assertions++;}}
    private static void invalid(Task t,String message)throws Exception{try{t.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
