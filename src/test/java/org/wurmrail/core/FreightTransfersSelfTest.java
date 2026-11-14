package org.wurmrail.core;

import java.util.*;
import org.wurmrail.core.FreightStops.Operation;
import org.wurmrail.core.FreightStops.Stop;
import org.wurmrail.core.FreightTransfers.Cargo;
import org.wurmrail.core.FreightTransfers.Inventory;
import org.wurmrail.core.FreightTransfers.Session;

/** Whole-item throughput and conservation checks independent of native inventory permissions. */
public final class FreightTransfersSelfTest {
    private static int assertions;
    public static void main(String[] args) {
        throughput(); filtersAndCapacity(); failures(); boundedWork(); physicalLoop();
        System.out.println("FreightTransfersSelfTest: "+assertions+" assertions passed.");
    }
    private static Stop stop(Operation op,int filter,double rate) { return new Stop("Rail",10,20,1,"Freight",op,30,filter,rate); }
    private static Session session() { return new Session(stop(Operation.LOAD,0,100)); }

    private static final class Item {
        final long id,grams;final int template;final String properties;
        Item(long id,int template,long grams) {this.id=id;this.template=template;this.grams=grams;this.properties="ql=87;damage=3;rarity=2;enchants=preserve;name="+id;}
    }
    private static class Store implements Inventory {
        final Map<Long,Item> from,to;
        long capacity=Long.MAX_VALUE;
        int reads,moves;
        boolean refuse,throwMove,throwCheck,duplicate;
        String unavailable;
        Store(Item... items) {from=new LinkedHashMap<Long,Item>();to=new LinkedHashMap<Long,Item>();for(Item item:items)from.put(item.id,item);}
        Store(Map<Long,Item> from,Map<Long,Item> to) {this.from=from;this.to=to;}
        public List<Cargo> candidates() {
            reads++;List<Cargo> list=new ArrayList<Cargo>();
            for(Item item:from.values()){list.add(new Cargo(item.id,item.template,item.grams));if(duplicate)list.add(new Cargo(item.id,item.template,item.grams));}return list;
        }
        public boolean canMove(Cargo cargo) {
            if(throwCheck)throw new IllegalStateException("storage changed");
            Item item=from.get(cargo.itemId);long used=0;for(Item stored:to.values())used+=stored.grams;
            return item!=null&&item.grams==cargo.weightGrams&&used<=capacity&&cargo.weightGrams<=capacity-used;
        }
        public boolean move(Cargo cargo) {
            if(throwMove)throw new IllegalStateException("native transfer rejected");
            if(refuse||!canMove(cargo))return false;
            Item item=from.remove(cargo.itemId);if(to.put(item.id,item)!=null)throw new AssertionError("duplicated physical identity");moves++;return true;
        }
        public String unavailableReason() {return unavailable;}
    }

    private static void throughput() {
        Item light=new Item(1,7,100000),heavy=new Item(2,7,250000);
        Store store=new Store(light,heavy);Session visit=session();
        FreightTransfers.advance(visit,.5,store);
        check(store.to.isEmpty()&&!visit.finished()&&visit.creditGrams()==50000,"100kg/s accumulates half an item's allowance without partial or instant cargo");
        FreightTransfers.advance(visit,.5,store);
        check(store.to.get(1L)==light&&store.from.get(2L)==heavy&&visit.transferredGrams()==100000,"one elapsed second transfers the original 100kg item object");
        check(!visit.finished()&&visit.creditGrams()==0,"heavy next item remains present while fresh allowance accumulates");
        FreightTransfers.advance(visit,2.49,store);check(store.to.size()==1&&!visit.finished(),"whole heavy item waits for its complete weight allowance");
        FreightTransfers.advance(visit,.01,store);
        check(visit.finished()&&store.from.isEmpty()&&store.to.get(2L)==heavy,"3.5 seconds transfers exactly350kg and finishes when source is exhausted");
        check(visit.transferredItems()==2&&visit.transferredGrams()==350000&&visit.creditGrams()<.000001,"credit is charged only for completed native moves");
        check(store.to.get(2L).properties.equals(heavy.properties),"quality, damage, rarity, enchantment and named-item properties stay on their original physical object");
        int reads=store.reads;FreightTransfers.advance(visit,5,store);check(store.reads==reads&&store.moves==2,"finished visit cannot poll or duplicate inventory work");
        Session slower=new Session(stop(Operation.UNLOAD,0,10));Store unloaded=new Store(new Item(3,8,20000));
        FreightTransfers.advance(slower,1,unloaded);check(unloaded.to.isEmpty(),"per-stop loader rate can be independently slower");
        FreightTransfers.advance(slower,1,unloaded);check(slower.finished()&&slower.transferredGrams()==20000,"unloading shares the same kg/s whole-item framework");
    }

    private static void filtersAndCapacity() {
        Item selected=new Item(1,7,10000),other=new Item(2,8,10000);Store filter=new Store(other,selected);
        Session visit=new Session(stop(Operation.LOAD,7,100));FreightTransfers.advance(visit,1,filter);
        check(visit.finished()&&filter.to.get(1L)==selected&&filter.from.get(2L)==other,"exact template filter leaves unmatched native objects untouched");
        Session noMatch=new Session(stop(Operation.LOAD,9,100));FreightTransfers.advance(noMatch,1,filter);
        check(noMatch.finished()&&noMatch.transferredGrams()==0&&filter.from.size()==1,"no matching cargo ends service instead of blocking the route indefinitely");
        Store limited=new Store(new Item(3,7,100000),new Item(4,7,100000));limited.capacity=150000;
        Session load=session();FreightTransfers.advance(load,2,limited);
        check(load.finished()&&limited.from.size()==1&&limited.to.size()==1&&load.transferredGrams()==100000,"destination full completes the visit after the portion that physically fits");
        check(load.status().contains("destination full")&&load.status().contains("Transferred 1 items (100.0 kg)"),
                "the final partly successful batch reports its actual transfer and the remaining capacity blocker");
        Store uneven=new Store(new Item(5,7,200000),new Item(6,7,50000));uneven.capacity=100000;
        Session skip=session();FreightTransfers.advance(skip,1,uneven);
        check(skip.finished()&&uneven.from.containsKey(5L)&&uneven.to.containsKey(6L),"oversized cargo does not hide a later item that fits another available capacity slot");
        Store empty=new Store();Session emptyVisit=session();FreightTransfers.advance(emptyVisit,0,empty);
        check(emptyVisit.finished()&&empty.moves==0,"empty source completes without requiring an artificial minimum transfer");
        Store full=new Store(new Item(7,7,1));full.capacity=0;Session fullVisit=session();FreightTransfers.advance(fullVisit,0,full);
        check(fullVisit.finished()&&full.from.size()==1,"already-full destination does not consume or destroy source items");
    }

    private static void failures() {
        for(int failure=0;failure<4;failure++) {
            Store store=new Store(new Item(1,7,1000));Session visit=session();
            if(failure==0)store.refuse=true;
            if(failure==1)store.throwMove=true;
            if(failure==2)store.throwCheck=true;
            if(failure==3)store.unavailable="Storage moved or access revoked.";
            FreightTransfers.advance(visit,1,store);
            check(visit.finished()&&visit.transferredGrams()==0&&visit.transferredItems()==0,"native failure/unavailable state terminates the visit without counted transfer");
            check(store.from.size()==1&&store.to.isEmpty()&&store.moves==0,"refused or unavailable moves preserve exact physical parent");
            check(failure==3?store.reads==0:visit.creditGrams()==100000,"unavailable storage is never scanned and rejected moves never spend allowance");
        }
        Session cancelled=session();Store untouched=new Store(new Item(1,7,1));cancelled.cancel("Operator STOP.");FreightTransfers.advance(cancelled,1,untouched);
        check(cancelled.finished()&&untouched.reads==0,"cancelled sessions cannot resume cargo movement");
        for(double bad:new double[]{-.1,Double.NaN,Double.POSITIVE_INFINITY}) {
            Store store=new Store();Session visit=session();
            try {FreightTransfers.advance(visit,bad,store);throw new AssertionError("bad elapsed accepted");}catch(IllegalArgumentException expected){assertions++;}
            check(store.reads==0&&visit.creditGrams()==0,"invalid scheduler elapsed has no inventory side effects");
        }
        for(Runnable invalid:new Runnable[]{()->new Cargo(0,1,1),()->new Cargo(1,0,1),()->new Cargo(1,1,0),()->new Cargo(1,1,-1),()->new Session(null)})
            try {invalid.run();throw new AssertionError("invalid physical cargo accepted");}catch(IllegalArgumentException expected){assertions++;}
    }

    private static void boundedWork() {
        Store heavy=new Store(new Item(1,7,1000000));Session visit=session();
        FreightTransfers.advance(visit,3600,heavy);
        check(!visit.finished()&&visit.creditGrams()==500000&&heavy.to.isEmpty(),"long server stalls are capped to five seconds of transfer allowance");
        FreightTransfers.advance(visit,5,heavy);check(visit.finished()&&heavy.to.size()==1,"heavy items can accumulate allowance across normal transfer intervals");
        Item[] tiny=new Item[65];for(int i=0;i<tiny.length;i++)tiny[i]=new Item(i+1,7,1);
        Store large=new Store(tiny);Session many=session();FreightTransfers.advance(many,1,large);
        check(!many.finished()&&large.moves==FreightTransfers.MAX_MOVES_PER_PASS&&large.from.size()==1,"one train cannot monopolize a poll with thousands of tiny item operations");
        FreightTransfers.advance(many,0,large);check(many.finished()&&large.moves==65,"remaining earned allowance is retained between bounded batches");
        Store duplicates=new Store(new Item(1,7,1000));duplicates.duplicate=true;Session distinct=session();FreightTransfers.advance(distinct,1,duplicates);
        check(distinct.finished()&&distinct.transferredItems()==1&&duplicates.moves==1,"duplicate candidate rows cannot transfer or charge the same item twice");
    }

    private static void physicalLoop() {
        Map<Long,Item> mine=new LinkedHashMap<Long,Item>(),train=new LinkedHashMap<Long,Item>(),foundry=new LinkedHashMap<Long,Item>();
        Set<Long> identities=new HashSet<Long>();long totalWeight=0;
        for(int i=0;i<12;i++){Item item=new Item(i+1,38,10000+i*1000);mine.put(item.id,item);identities.add(item.id);totalWeight+=item.grams;}
        for(int trip=0;trip<3;trip++) {
            Session load=new Session(stop(Operation.LOAD,38,100));Store loader=new Store(mine,train);
            for(int interval=0;interval<100&&!load.finished();interval++)FreightTransfers.advance(load,.25,loader);
            check(load.finished()&&mine.isEmpty()&&train.size()==12,"Mine Loader fills the existing generic cargo inventory over time");
            Session unload=new Session(stop(Operation.UNLOAD,38,100));Store unloader=new Store(train,foundry);
            for(int interval=0;interval<100&&!unload.finished();interval++)FreightTransfers.advance(unload,.25,unloader);
            check(unload.finished()&&train.isEmpty()&&foundry.size()==12,"Foundry Unloader empties the same actual items before normal departure");
            Set<Long> actual=new HashSet<Long>();long weight=0;for(Item item:foundry.values()){actual.add(item.id);weight+=item.grams;check(item.properties.contains("ql=87;damage=3;rarity=2"),"all transferred physical properties survive repeated visits");}
            check(actual.equals(identities)&&weight==totalWeight,"repeating load/unload visits conserve every identity and gram without virtual cargo");
            mine.putAll(foundry);foundry.clear();
        }
    }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
