package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.wurmrail.core.SteamYarders.HarvestAge;
import org.wurmrail.core.SteamYarders.Yarder;

/** Surface-circle safety and independently checked durable forestry controls. */
public final class SteamYardersSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        geometry();validation();persistence();legacyMigration();System.out.println("SteamYardersSelfTest: "+assertions+" assertions passed.");
    }
    private static Yarder yarder(long id,long revision){return new Yarder(id,900,revision,12,14,0,800,3,HarvestAge.OLD,5,true,"Waiting for natural growth â€” ready.");}
    private static void geometry() {
        Yarder yarder=yarder(100,1);check(SteamYarders.WOODLOT.size()==81,"radius-five centre circle has exactly81 tile centres");
        Set<String> coordinates=new HashSet<String>();Map<Integer,Integer> rows=new TreeMap<Integer,Integer>();
        for(SteamYarders.Offset offset:SteamYarders.WOODLOT) {
            check(coordinates.add(offset.dx+":"+offset.dy)&&SteamYarders.contains(yarder,12+offset.dx,14+offset.dy),"every immutable candidate is unique and inside the anchored circle");
            rows.put(offset.dy,rows.containsKey(offset.dy)?rows.get(offset.dy)+1:1);
        }
        check(new ArrayList<Integer>(rows.values()).equals(Arrays.asList(1,7,9,9,9,11,9,9,9,7,1)),"managed circle is not a square woodlot");
        for(int[] outside:new int[][]{{5,1},{1,5},{-5,-1},{4,4},{6,0},{0,-6},{-6,0},{0,6}})
            check(!SteamYarders.contains(yarder,12+outside[0],14+outside[1]),"outside/corner trees are not managed");
        check(SteamYarders.contains(yarder,16,17)&&SteamYarders.contains(yarder,17,14),"new five-tile circle includes four-three diagonal and exact cardinal boundary");
        check(!SteamYarders.contains(yarder,Integer.MIN_VALUE,Integer.MAX_VALUE)&&!SteamYarders.contains(null,12,14),"overflow coordinates and missing machine are outside the woodlot");
        try{SteamYarders.WOODLOT.clear();throw new AssertionError("Mutable woodlot geometry");}catch(UnsupportedOperationException expected){assertions++;}
        check(SteamYarders.WOODLOT.get(0).dx==0&&SteamYarders.WOODLOT.get(0).dy==-5,"persistent tile cursors use deterministic row-major order");
    }
    private static void validation() {
        invalid(()->new Yarder(0,900,1,1,1,0,1,0,HarvestAge.MATURE,0,false,"Stopped"),"missing item");
        invalid(()->new Yarder(1,0,1,1,1,0,1,0,HarvestAge.MATURE,0,false,"Stopped"),"missing owner");
        invalid(()->new Yarder(1,900,-1,1,1,0,1,0,HarvestAge.MATURE,0,false,"Stopped"),"negative generation");
        invalid(()->new Yarder(1,900,1,-1,1,0,1,0,HarvestAge.MATURE,0,false,"Stopped"),"negative anchor");
        invalid(()->new Yarder(1,900,1,1,1,-1,1,0,HarvestAge.MATURE,0,false,"Stopped"),"underground forestry");
        invalid(()->new Yarder(1,900,1,1,1,0,-1,0,HarvestAge.MATURE,0,false,"Stopped"),"invalid bunk identity");
        invalid(()->new Yarder(1,900,1,1,1,0,1,256,HarvestAge.MATURE,0,false,"Stopped"),"invalid native species ordinal");
        invalid(()->new Yarder(1,900,1,1,1,0,1,0,null,0,false,"Stopped"),"missing harvest age");
        invalid(()->new Yarder(1,900,1,1,1,0,1,0,HarvestAge.MATURE,81,false,"Stopped"),"cursor outside the managed circle");
        invalid(()->new Yarder(1,900,1,1,1,0,1,0,HarvestAge.MATURE,0,false,"bad\nstatus"),"multiline diagnostics");
        SteamYarders registry=new SteamYarders();Yarder y=yarder(100,registry.nextRevision());registry.put(y);
        Yarder progress=y.withProgress(false,8,"Stopped by operator.");
        check(progress.revision==y.revision&&progress.species==y.species&&progress.harvestAge==y.harvestAge,"progress preserves immutable configuration");
        SteamYarders copied=registry.copy();copied.remove(y.itemId);
        check(registry.get(y.itemId)==y&&copied.yarders.isEmpty(),"transactional registry copy cannot mutate original records");
        try{registry.yarders.clear();throw new AssertionError("Mutable yarder view");}catch(UnsupportedOperationException expected){assertions++;}
    }
    private static void persistence()throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-yarders-");Path file=folder.resolve("yarders.properties"),backup=folder.resolve("yarders.properties.bak");
        try {
            check(SteamYarders.load(file).yarders.isEmpty(),"existing worlds start with no invented forestry machines");
            SteamYarders registry=new SteamYarders();Yarder original=yarder(100,registry.nextRevision());registry.put(original);registry.save(file);
            Yarder loaded=SteamYarders.load(file).get(100);
            check(loaded.ownerId==900&&loaded.bunkId==800&&loaded.tileX==12&&loaded.tileY==14&&loaded.layer==0,"owner, output and surface anchor persist");
            check(loaded.species==3&&loaded.harvestAge==HarvestAge.OLD&&loaded.nextTile==5&&loaded.running&&loaded.status.equals(original.status),"species, age, cursor, running intent and Unicode status persist");
            check(read(file).stringPropertyNames().stream().noneMatch(key->key.contains("elapsed")||key.contains("credit")),"production time and offline growth are never serialized");
            check(!Files.exists(backup),"first save does not invent a stale backup");
            registry.put(yarder(200,registry.nextRevision()));registry.save(file);Files.write(file,new byte[]{1,2});
            check(SteamYarders.load(file).yarders.size()==1,"corrupt main state recovers validated backup");
            registry.save(file);Files.delete(file);check(SteamYarders.load(file).yarders.size()==1,"saving after corrupt state does not overwrite the known-good backup");
            Files.write(backup,new byte[]{1});ioFailure(()->SteamYarders.load(file),"corrupt sole backup is preserved rather than reset");
            registry.save(file);Files.deleteIfExists(backup);
            for(String[] change:new String[][]{{"y.0.layer","-1"},{"y.0.species","256"},{"y.0.age","YOUNG"},{"y.0.next","81"},{"y.0.running","yes"},{"yarders","1"},{"y.1.item","100"},{"revision","0"},{"format","999"},{"extra","orphan"}}) {
                registry.save(file);Files.deleteIfExists(backup);Properties p=read(file);p.setProperty(change[0],change[1]);writeChecked(file,p);
                ioFailure(()->SteamYarders.load(file),"checked malformed field cannot bypass forestry invariants: "+change[0]);
            }
            registry.save(file);Files.deleteIfExists(backup);Properties truncated=read(file);truncated.remove("y.0.bunk");writeChecked(file,truncated);ioFailure(()->SteamYarders.load(file),"truncated bunk association is rejected");
            registry.remove(100);registry.remove(200);long generation=registry.nextRevision();registry.save(file);
            SteamYarders empty=SteamYarders.load(file);check(empty.yarders.isEmpty()&&empty.nextRevision()==generation+1,"revision high-water survives removal of all yarders");
        }finally{cleanup(folder);}
    }
    private static Properties read(Path file)throws Exception{Properties p=new Properties();try(InputStream input=Files.newInputStream(file)){p.load(input);}return p;}
    private static void legacyMigration()throws Exception {
        Path folder=Files.createTempDirectory("wurmrail-yarders-radius-");Path file=folder.resolve("yarders.properties");
        try {
            SteamYarders original=new SteamYarders();original.put(yarder(100,original.nextRevision()));original.save(file);Properties legacy=read(file);legacy.setProperty("format","1");
            int oldCursor=0;
            for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++)if(dx*dx+dy*dy<=9) {
                legacy.setProperty("y.0.next",Integer.toString(oldCursor++));writeChecked(file,legacy);
                SteamYarders migrated=SteamYarders.load(file);Yarder state=migrated.get(100);SteamYarders.Offset next=SteamYarders.WOODLOT.get(state.nextTile);
                check(next.dx==dx&&next.dy==dy,"every old cursor migrates to the same physical next tile in the enlarged circle");
                check(state.running&&state.bunkId==800&&state.ownerId==900&&state.species==3&&state.harvestAge==HarvestAge.OLD&&state.revision==1,
                        "old placed Yarders preserve running intent, storage, ownership, species, harvest age and captured revision");
                migrated.save(file);check("2".equals(read(file).getProperty("format"))&&SteamYarders.load(file).get(100).nextTile==state.nextTile,"the new format records enlarged-circle cursors without remigrating them");
            }
            Files.deleteIfExists(folder.resolve("yarders.properties.bak"));legacy.setProperty("y.0.next","29");writeChecked(file,legacy);
            ioFailure(()->SteamYarders.load(file),"a signed old-format cursor beyond its29 original tiles is rejected");
        }finally{cleanup(folder);}
    }
    private static void writeChecked(Path file,Properties p)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")){String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));p.setProperty("checksum",hex.toString());
        try(OutputStream output=Files.newOutputStream(file)){p.store(output,"deliberate malformed forestry fixture");}
    }
    static void cleanup(Path directory)throws Exception {
        Path boundary=directory.toRealPath();List<Path> paths=new ArrayList<Path>();try(java.util.stream.Stream<Path> stream=Files.walk(boundary)){stream.forEach(paths::add);}paths.sort(Comparator.reverseOrder());
        for(Path path:paths){if(!path.toAbsolutePath().normalize().startsWith(boundary))throw new AssertionError("cleanup outside temporary forestry fixture");Files.delete(path);}
    }
    private interface Work{void run()throws Exception;}
    private static void invalid(Work work,String message){try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void ioFailure(Work work,String message){try{work.run();throw new AssertionError(message);}catch(IOException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
