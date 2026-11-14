package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.wurmrail.core.FreightStops.Operation;
import org.wurmrail.core.FreightStops.Stop;

/** Dependency-free freight definition, ownership, identity and durable-state checks. */
public final class FreightStopsSelfTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        validation(); registry(); revisions(); persistence();
        System.out.println("FreightStopsSelfTest: "+assertions+" assertions passed.");
    }

    private static Stop stop(String rail,long marker,String name) {
        return new Stop(rail,marker,900,1,name,Operation.LOAD,800,0,100);
    }
    private static void validation() {
        Stop stop=stop("Mine_Loader",100,"  Mine Loader  ");
        check(stop.name.equals("Mine Loader")&&stop.rail.equals("Mine_Loader"),"route identity and readable trimmed name stay separate");
        check(stop.markerId==100&&stop.ownerId==900&&stop.storageId==800&&stop.revision==1,"native identities and revision retained");
        check(stop.templateId==0&&stop.rateKgPerSecond==100&&stop.operation==Operation.LOAD,"generic loading defaults represent actual units");
        Stop precise=new Stop("Foundry",101,901,0,"Foundry Unloader",Operation.UNLOAD,801,38,.1);
        check(precise.revision==0&&precise.templateId==38&&precise.operation==Operation.UNLOAD,"revision zero and exact-template unload definitions supported");
        check(new Stop("A",1,1,0,String.join("",Collections.nCopies(48,"a")),Operation.LOAD,2,0,10000).name.length()==48,"maximum documented name and rate accepted");
        for(String name:new String[]{null,"","  ","two\nlines","bad\tname",String.join("",Collections.nCopies(49,"a"))})
            invalid(()->stop("A",1,name),"invalid display name rejected");
        for(String rail:new String[]{null,"","two words","x/y",String.join("",Collections.nCopies(41,"x"))})
            invalid(()->stop(rail,1,"Valid"),"rail grammar matches existing route identities");
        for(long id:new long[]{0,-1,-10}) {
            invalid(()->new Stop("A",id,1,0,"A",Operation.LOAD,2,0,100),"positive native marker required");
            invalid(()->new Stop("A",1,id,0,"A",Operation.LOAD,2,0,100),"positive owner identity required");
            invalid(()->new Stop("A",1,2,0,"A",Operation.LOAD,id,0,100),"positive storage identity required");
        }
        invalid(()->new Stop("A",1,2,-1,"A",Operation.LOAD,3,0,100),"negative revision rejected");
        invalid(()->new Stop("A",1,2,0,"A",null,3,0,100),"operation required");
        invalid(()->new Stop("A",1,2,0,"A",Operation.LOAD,3,-1,100),"negative cargo template rejected");
        for(double rate:new double[]{0,.099,10000.01,-1,Double.NaN,Double.POSITIVE_INFINITY})
            invalid(()->new Stop("A",1,2,0,"A",Operation.LOAD,3,0,rate),"unsafe rate rejected");
    }

    private static void registry() {
        FreightStops store=new FreightStops();Stop mine=stop("Mine",100,"Mine Loader");
        store.put(mine);store.put(stop("Foundry",101,"Foundry Unloader"));
        check(store.get("Mine")==mine&&store.stops.size()==2,"rail keyed lookup preserves immutable definitions");
        invalid(()->store.put(stop("Other",102,"mINE lOADER")),"display names remain unambiguous");
        invalid(()->store.put(stop("Other",100,"Another")),"one marker cannot attach to two rail identities");
        invalid(()->store.put(null),"null stop rejected");
        check(store.get("Missing")==null,"ordinary rail arrivals have no freight configuration");
        try { store.stops.clear();throw new AssertionError("mutable freight registry"); }
        catch(UnsupportedOperationException expected) { assertions++; }
        Stop updated=new Stop("Mine",100,900,2,"Mine Revised",Operation.UNLOAD,810,7,250);
        store.put(updated);
        check(store.get("Mine")==updated&&mine.operation==Operation.LOAD&&mine.name.equals("Mine Loader"),"editing replaces one snapshot without mutating old UI/session values");
        FreightStops copy=store.copy();copy.remove("Mine");copy.put(stop("Third",103,"Third Stop"));
        check(store.stops.size()==2&&store.stops.containsKey("Mine")&&!store.stops.containsKey("Third"),"transactional registry copy is independent");
        check(copy.remove("missing")==null&&copy.remove("Third").markerId==103,"removing absent definitions is harmless and returns the removed snapshot");
    }

    private static void revisions() throws Exception {
        FreightStops store=new FreightStops();
        long first=store.nextRevision();
        Stop original=new Stop("Mine",100,900,first,"Mine",Operation.LOAD,800,0,100);
        store.put(original);store.remove("Mine");
        long second=store.nextRevision();
        Stop recreated=new Stop("Mine",100,900,second,"Mine",Operation.LOAD,800,0,100);store.put(recreated);
        check(first==1&&second==2&&original.markerId==recreated.markerId&&original.revision!=recreated.revision,
                "remove and recreate on the same physical marker cannot match an old configuration form");
        FreightStops copy=store.copy();
        check(copy.nextRevision()==3&&store.nextRevision()==3,"transactional copies preserve but independently allocate the next generation");
        store.put(new Stop("Other",101,900,20,"Other",Operation.LOAD,800,0,100));
        check(store.nextRevision()==21,"importing a higher record revision raises the allocator high-water mark");
        invalid(()->store.put(new Stop("Duplicate",102,900,999,"Other",Operation.LOAD,800,0,100)),"invalid insertion rejected before allocating its revision");
        check(store.nextRevision()==22,"failed validation cannot advance the committed revision allocator");
        FreightStops exhausted=new FreightStops();exhausted.put(new Stop("Max",1,1,Long.MAX_VALUE,"Max",Operation.LOAD,2,0,100));
        try {exhausted.nextRevision();throw new AssertionError("revision overflow accepted");}catch(IllegalStateException expected){assertions++;}

        Path directory=Files.createTempDirectory("wurmrail-freight-revisions-");
        try {
            Path file=directory.resolve("freight.properties");
            store.remove("Mine");store.remove("Other");store.save(file);
            FreightStops empty=FreightStops.load(file);
            check(empty.stops.isEmpty()&&empty.nextRevision()==23,"saving and reloading an empty registry preserves all removed and allocated generations");
            Properties p=read(file);p.setProperty("revision","-1");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"negative global generation rejected even with no records");
            FreightStops legacy=new FreightStops();legacy.put(new Stop("Old",1,2,30,"Old",Operation.LOAD,3,0,100));legacy.save(file);
            p=read(file);p.remove("revision");writeChecked(file,p);
            check(FreightStops.load(file).nextRevision()==31,"prototype files without a global counter derive it from their existing stop revisions");
            p.setProperty("revision","29");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"global generation below a record revision rejected");
        } finally {
            final Path boundary=directory.toRealPath();
            List<Path> paths=new ArrayList<Path>();try(java.util.stream.Stream<Path> stream=Files.walk(boundary)){stream.forEach(paths::add);}
            paths.sort(Comparator.reverseOrder());
            for(Path path:paths){if(!path.toAbsolutePath().normalize().startsWith(boundary))throw new AssertionError("cleanup outside test fixture");Files.delete(path);}
        }
    }

    private static void persistence() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-freight-stops-");
        try {
            Path file=directory.resolve("freight.properties"),backup=directory.resolve("freight.properties.bak");
            check(FreightStops.load(file).stops.isEmpty(),"old worlds without freight state load unchanged");
            FreightStops store=new FreightStops();
            store.put(new Stop("Mine",100,900,0,"Dépôt {Ore} 'Mine'",Operation.LOAD,800,38,100.5));store.save(file);
            Stop loaded=FreightStops.load(file).get("Mine");
            check(loaded.name.equals("Dépôt {Ore} 'Mine'")&&loaded.rateKgPerSecond==100.5&&loaded.templateId==38,"name punctuation, Unicode, filter and fractional rate round-trip");
            check(loaded.ownerId==900&&loaded.revision==0&&loaded.markerId==100&&loaded.storageId==800,"ownership and native storage association survive restart");
            check(!Files.exists(backup),"first save does not invent a backup");
            store.put(stop("Foundry",101,"Foundry"));store.save(file);
            check(Files.exists(backup)&&FreightStops.load(file).stops.size()==2,"subsequent save keeps last validated state");
            Files.write(file,new byte[]{1,2,3});
            check(FreightStops.load(file).stops.size()==1,"corrupt primary safely recovers the known-good backup");
            store.save(file);
            Files.delete(file);
            check(FreightStops.load(file).stops.size()==1,"saving over corruption never overwrites the known-good backup with corrupt data");
            Files.write(backup,new byte[]{1});
            ioFailure(()->FreightStops.load(file),"corrupt sole backup does not silently delete freight definitions");

            store.save(file);Files.deleteIfExists(backup);
            Properties p=read(file);
            p.setProperty("s.0.rate","NaN");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"valid checksum cannot bypass finite rate validation");
            store.save(file);p=read(file);p.setProperty("stops","1");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"orphan rows cannot be hidden behind a smaller count");
            store.save(file);p=read(file);p.setProperty("s.1.rail","Mine");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"duplicate rail definitions rejected on load");
            store.save(file);p=read(file);p.remove("s.0.storage");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"truncated record does not lose the storage association silently");
            store.save(file);p=read(file);p.setProperty("format","999");writeChecked(file,p);
            ioFailure(()->FreightStops.load(file),"future unknown state format is preserved for operator repair");
        } finally {
            final Path boundary=directory.toRealPath();
            List<Path> paths=new ArrayList<Path>();try(java.util.stream.Stream<Path> stream=Files.walk(boundary)) { stream.forEach(paths::add); }
            paths.sort(Comparator.reverseOrder());
            for(Path path:paths) { if(!path.toAbsolutePath().normalize().startsWith(boundary))throw new AssertionError("cleanup outside test fixture");Files.delete(path); }
        }
    }

    private static Properties read(Path file) throws Exception { Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p; }
    private static void writeChecked(Path file,Properties p) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")) {
            String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));p.setProperty("checksum",hex.toString());
        try(OutputStream out=Files.newOutputStream(file)){p.store(out,"deliberate malformed state fixture");}
    }
    private interface Work { void run() throws Exception; }
    private static void invalid(Work work,String message) { try {work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);} }
    private static void ioFailure(Work work,String message) { try {work.run();throw new AssertionError(message);}catch(IOException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);} }
    private static void check(boolean value,String message) {assertions++;if(!value)throw new AssertionError(message);}
}
