package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.wurmrail.core.WaterUnloadStops.Stop;

/** Checked stop identity, destination tank selection and durable revision/backup behavior. */
public final class WaterUnloadStopsSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        validation();registry();persistence();
        System.out.println("WaterUnloadStopsSelfTest: "+assertions+" assertions passed.");
    }
    private static Stop stop(String rail,long marker,long revision,String name) {
        return new Stop(rail,marker,900,revision,name,800,100);
    }
    private static void validation() {
        Stop value=stop("Lake_01",100,0,"  Lakeside Water  ");
        check(value.name.equals("Lakeside Water")&&value.rail.equals("Lake_01"),"display name is trimmed separately from the stable route identity");
        check(value.markerId==100&&value.ownerId==900&&value.revision==0,"native marker, owner and zero revision are retained");
        check(value.storageId==800&&value.rateLitresPerSecond==100,"destination tank ID and physical litre rate are exact");
        check(value.operation==WaterUnloadStops.Operation.UNLOAD,"old constructor remains an unloading definition");
        check(new Stop("Load",1,1,0,"Load",WaterUnloadStops.Operation.LOAD,800,100).operation==WaterUnloadStops.Operation.LOAD,"stored-water loading has an explicit operation");
        invalid(()->new Stop("A",1,1,0,"A",null,800,100),"a physical transfer operation is required");
        check(new Stop("Cave",1,1,1,"Cave Water",999,.1).storageId==999,"a destination tank and fractional rate can be represented");
        check(new Stop("A",1,1,0,String.join("",Collections.nCopies(48,"a")),800,10000).name.length()==48,"maximum name and rate accepted");
        for(String name:new String[]{null,"","  ","two\nlines","bad\tname",String.join("",Collections.nCopies(49,"a"))})
            invalid(()->stop("A",1,0,name),"invalid readable station name rejected");
        for(String rail:new String[]{null,"","two words","a/b",String.join("",Collections.nCopies(41,"x"))})
            invalid(()->stop(rail,1,0,"Water"),"route identity grammar matches existing rail nodes");
        for(long id:new long[]{0,-1,-10}) {
            invalid(()->new Stop("A",id,1,0,"A",800,100),"positive native marker required");
            invalid(()->new Stop("A",1,id,0,"A",800,100),"positive durable owner required");
        }
        invalid(()->stop("A",1,-1,"A"),"negative revision rejected");
        for(long id:new long[]{0,-1,-10})invalid(()->new Stop("A",1,1,0,"A",id,100),"positive native destination tank required");
        for(double rate:new double[]{0,.099,10000.01,-1,Double.NaN,Double.POSITIVE_INFINITY})
            invalid(()->new Stop("A",1,1,0,"A",800,rate),"unsafe transfer rate rejected");
    }
    private static void registry() {
        WaterUnloadStops store=new WaterUnloadStops();Stop lake=stop("Lake",100,store.nextRevision(),"Lakeside");
        store.put(lake);store.put(stop("Cave",101,store.nextRevision(),"Cave Water"));
        check(store.get("Lake")==lake&&store.stops.size()==2,"immutable definitions are keyed by existing route rail names");
        check(store.get("Ordinary")==null,"ordinary and freight stops have no liquid definition");
        invalid(()->store.put(stop("Other",102,3,"lAKESIDE")),"case-insensitive names remain unambiguous");
        invalid(()->store.put(stop("Other",100,3,"Other")),"one physical marker cannot identify two Water Unload Stops");
        invalid(()->store.put(null),"null definitions rejected");
        try{store.stops.clear();throw new AssertionError("mutable registry");}catch(UnsupportedOperationException expected){assertions++;}
        Stop replacement=new Stop("Lake",100,900,3,"Lake Revised",801,250.5);store.put(replacement);
        check(store.get("Lake")==replacement&&lake.rateLitresPerSecond==100&&lake.storageId==800,"editing replaces the snapshot without mutating old arrival/form data");
        WaterUnloadStops copy=store.copy();copy.remove("Lake");copy.put(stop("Third",102,copy.nextRevision(),"Third"));
        check(store.get("Lake")==replacement&&store.get("Third")==null,"registry copies are independent for runtime rollback");
        check(copy.remove("Missing")==null,"missing removal is harmless");
        store.remove("Lake");long newer=store.nextRevision();store.put(stop("Lake",100,newer,"Lakeside"));
        check(newer==4&&store.get("Lake").revision!=lake.revision,"remove/recreate cannot match old captured controls on the same marker");
        store.put(stop("Higher",103,50,"Higher"));check(store.nextRevision()==51,"imported higher revision advances the allocator");
        WaterUnloadStops max=new WaterUnloadStops();max.put(stop("Max",1,Long.MAX_VALUE,"Max"));
        try{max.nextRevision();throw new AssertionError("revision overflow");}catch(IllegalStateException expected){assertions++;}
    }
    private static void persistence()throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-water-unload-stops-");
        try {
            Path file=directory.resolve("water.properties"),backup=directory.resolve("water.properties.bak");
            check(WaterUnloadStops.load(file).stops.isEmpty(),"old worlds without water definitions remain unchanged");
            WaterUnloadStops store=new WaterUnloadStops();
            store.put(new Stop("Lake",100,900,7,"Dépôt {Water} 'Lake'",802,100.5));store.save(file);
            Stop loaded=WaterUnloadStops.load(file).get("Lake");
            check(loaded.name.equals("Dépôt {Water} 'Lake'")&&loaded.rateLitresPerSecond==100.5,"Unicode name, punctuation and fractional rate round-trip");
            check(loaded.markerId==100&&loaded.ownerId==900&&loaded.revision==7&&loaded.storageId==802,"all native destination and ownership identities survive restart");
            check(!Files.exists(backup),"first save does not invent a backup");
            Properties legacy=read(file);legacy.setProperty("format","1");legacy.remove("s.0.operation");writeChecked(file,legacy);
            loaded=WaterUnloadStops.load(file).get("Lake");
            check(loaded.operation==WaterUnloadStops.Operation.UNLOAD&&loaded.storageId==802&&loaded.revision==7,"checked v1 unloading definitions migrate without changing native identities");
            store.put(new Stop("Lake",100,900,7,"Dépôt {Water} 'Lake'",WaterUnloadStops.Operation.LOAD,802,100.5));store.save(file);
            check(WaterUnloadStops.load(file).get("Lake").operation==WaterUnloadStops.Operation.LOAD,"v2 retains the chosen stored-water loading operation");
            Files.deleteIfExists(backup);
            store.put(stop("Cave",101,8,"Cave Water"));store.save(file);
            check(Files.exists(backup)&&WaterUnloadStops.load(file).stops.size()==2,"a later save retains a validated backup");
            Files.write(file,new byte[]{1,2});check(WaterUnloadStops.load(file).stops.size()==1,"corrupt primary recovers the last valid backup");
            store.save(file);Files.delete(file);check(WaterUnloadStops.load(file).stops.size()==1,"saving over corrupt primary preserves its good backup");
            Files.write(backup,new byte[]{1});ioFailure(()->WaterUnloadStops.load(file),"corrupt sole backup is not silently replaced by empty definitions");
            store.save(file);Files.deleteIfExists(backup);
            for(String[] change:new String[][]{{"s.0.storage","0"},{"s.0.storage","-1"},{"s.0.rate","NaN"},
                    {"s.0.storage",null},{"s.0.operation",null},{"s.0.operation","CREATE"},{"stops","1"},{"s.1.rail","Lake"},{"revision","6"},{"format","99"},{"unexpected","value"}}) {
                store.save(file);Files.deleteIfExists(backup);Properties p=read(file);
                if(change[1]==null)p.remove(change[0]);else p.setProperty(change[0],change[1]);writeChecked(file,p);
                ioFailure(()->WaterUnloadStops.load(file),"a valid checksum cannot conceal malformed, truncated or unsupported water definitions");
            }
            store.remove("Lake");store.remove("Cave");long high=store.nextRevision();store.save(file);Files.deleteIfExists(backup);
            WaterUnloadStops empty=WaterUnloadStops.load(file);
            check(empty.stops.isEmpty()&&empty.nextRevision()==high+1,"empty saved registry keeps removed and allocated revision generations");
        }finally{cleanup(directory);}
    }
    private static Properties read(Path file)throws Exception{Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void writeChecked(Path file,Properties p)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")){
            String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));p.setProperty("checksum",hex.toString());
        try(OutputStream out=Files.newOutputStream(file)){p.store(out,"checked malformed water definition fixture");}
    }
    private static void cleanup(Path directory)throws Exception {
        Path boundary=directory.toRealPath();List<Path> paths=new ArrayList<Path>();try(java.util.stream.Stream<Path> stream=Files.walk(boundary)){stream.forEach(paths::add);}
        paths.sort(Comparator.reverseOrder());for(Path path:paths){if(!path.toAbsolutePath().normalize().startsWith(boundary))throw new AssertionError("cleanup outside fixture");Files.delete(path);}
    }
    private interface Work{void run()throws Exception;}
    private static void invalid(Work work,String message){try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void ioFailure(Work work,String message){try{work.run();throw new AssertionError(message);}catch(IOException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
