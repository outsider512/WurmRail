package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.wurmrail.core.SteamRockDrills.Drill;

/** Durable machine anchors, stale controls and safe state recovery. */
public final class SteamRockDrillsSelfTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        validation();registry();persistence();
        System.out.println("SteamRockDrillsSelfTest: "+assertions+" assertions passed.");
    }
    private static Drill drill(long id,long revision) {return new Drill(id,900,revision,12,14,-1,800,7,0,false,"Stopped.");}
    private static void validation() {
        Drill drill=drill(100,1);
        check(drill.itemId==100&&drill.ownerId==900&&drill.revision==1&&drill.hopperId==800,"native machine, owner and hopper identities retained");
        check(drill.tileX==12&&drill.tileY==14&&drill.layer==-1,"configured cave tile anchor retained");
        check(drill.enabledMask==7&&drill.nextFace==0&&!drill.running,"cardinal face mask, cursor and power retained");
        Drill progress=drill.withProgress(true,3,"Running: mined East.");
        check(progress.revision==drill.revision&&progress.running&&progress.nextFace==3&&!drill.running,"progress replaces snapshots without invalidating configuration generations");
        check(progress.withRevision(12).revision==12&&progress.revision==1,"controls can advance revision independently of work progress");
        check(new Drill(1,2,0,0,0,-1,0,0,3,false,"Not configured").hopperId==0,"unlinked and disabled new machines are representable");
        for(int face=0;face<4;face++)check(SteamRockDrills.faceName(face).equals(new String[]{"North","East","South","West"}[face]),"physical clockwise cardinal names");
        check(SteamRockDrills.selectFace(7,15,0)==0&&SteamRockDrills.selectFace(7,15,1)==1
                &&SteamRockDrills.selectFace(7,15,2)==2&&SteamRockDrills.selectFace(7,15,3)==0,"round-robin cursor wraps through enabled cardinal faces");
        check(SteamRockDrills.selectFace(7,2,0)==1&&SteamRockDrills.selectFace(7,8,0)==-1,"only an enabled and available physical face can be selected");
        invalid(()->SteamRockDrills.selectFace(15,15,4),"invalid cursor rejected");
        invalid(()->SteamRockDrills.faceName(-1),"unselected is not a valid physical face name");
        for(long id:new long[]{0,-1,-10}) {
            invalid(()->new Drill(id,2,0,0,0,-1,0,0,0,false,"Stopped"),"invalid machine identity rejected");
            invalid(()->new Drill(1,id,0,0,0,-1,0,0,0,false,"Stopped"),"invalid owner identity rejected");
        }
        for(int layer:new int[]{0,1,-2})invalid(()->new Drill(1,2,0,0,0,layer,0,0,0,false,"Stopped"),"first machine cannot be configured outside cave layer");
        invalid(()->new Drill(1,2,-1,0,0,-1,0,0,0,false,"Stopped"),"negative control generation rejected");
        invalid(()->new Drill(1,2,0,-1,0,-1,0,0,0,false,"Stopped"),"negative anchor X rejected");
        invalid(()->new Drill(1,2,0,0,-1,-1,0,0,0,false,"Stopped"),"negative anchor Y rejected");
        invalid(()->new Drill(1,2,0,0,0,-1,-1,0,0,false,"Stopped"),"negative hopper ID rejected");
        for(int mask:new int[]{-1,16,255})invalid(()->new Drill(1,2,0,0,0,-1,0,mask,0,false,"Stopped"),"unknown or diagonal face bits rejected");
        for(int face:new int[]{-1,4})invalid(()->new Drill(1,2,0,0,0,-1,0,0,face,false,"Stopped"),"invalid saved cursor rejected");
        for(String status:new String[]{null,"","  ","two\nlines","bad\tstatus",String.join("",Collections.nCopies(513,"x"))})
            invalid(()->new Drill(1,2,0,0,0,-1,0,0,0,false,status),"unbounded or control-character status rejected");
    }
    private static void registry() {
        SteamRockDrills store=new SteamRockDrills();Drill original=drill(100,store.nextRevision());store.put(original);
        check(store.get(100)==original&&store.get(200)==null&&store.drills.size()==1,"one machine record per actual item identity");
        try{store.drills.clear();throw new AssertionError("mutable registry");}catch(UnsupportedOperationException expected){assertions++;}
        SteamRockDrills copy=store.copy();copy.remove(100);copy.put(drill(200,copy.nextRevision()));
        check(store.get(100)==original&&store.get(200)==null&&copy.get(100)==null,"transactional copies isolate control changes");
        store.remove(100);long next=store.nextRevision();store.put(drill(100,next));
        check(next==2&&store.get(100).revision!=original.revision,"remove/recreate cannot match a stale control snapshot");
        invalid(()->store.put(null),"missing record rejected");
        store.put(drill(101,50));check(store.nextRevision()==51,"higher imported generation raises high-water mark");
        store.put(drill(102,Long.MAX_VALUE));
        try{store.nextRevision();throw new AssertionError("revision overflow");}catch(IllegalStateException expected){assertions++;}
        SteamRockDrills full=new SteamRockDrills();
        for(int i=1;i<=SteamRockDrills.MAX_DRILLS;i++)full.put(drill(i,0));
        invalid(()->full.put(drill(SteamRockDrills.MAX_DRILLS+1,0)),"bounded machine registry rejects an extra machine");
        full.put(drill(1,2));check(full.get(1).revision==2,"editing a machine remains possible at configured limit");
    }
    private static void persistence() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-drill-state-");
        try {
            Path file=directory.resolve("drills.properties"),backup=directory.resolve("drills.properties.bak");
            check(SteamRockDrills.load(file).drills.isEmpty(),"old worlds without machine state remain unchanged");
            SteamRockDrills store=new SteamRockDrills();Drill original=new Drill(100,900,store.nextRevision(),12,14,-1,800,13,3,true,"Running: Dépôt 'ore' {test}");
            store.put(original);store.save(file);Drill loaded=SteamRockDrills.load(file).get(100);
            check(loaded.itemId==100&&loaded.ownerId==900&&loaded.hopperId==800&&loaded.revision==1,"native IDs and control generation survive restart");
            check(loaded.tileX==12&&loaded.tileY==14&&loaded.layer==-1&&loaded.enabledMask==13&&loaded.nextFace==3&&loaded.running,"anchor, individual faces, cursor and running setting survive restart");
            check(loaded.status.equals(original.status),"punctuation and Unicode status round-trip");
            Properties p=read(file);
            check(p.stringPropertyNames().stream().noneMatch(key->key.contains("elapsed")||key.contains("credit")),"offline elapsed production is never persisted");
            check(!Files.exists(backup),"initial save needs no invented backup");
            store.put(drill(200,store.nextRevision()));store.save(file);Files.write(file,new byte[]{1,2,3});
            check(SteamRockDrills.load(file).drills.size()==1,"corrupt main file recovers its last validated backup");
            store.save(file);Files.delete(file);
            check(SteamRockDrills.load(file).drills.size()==1,"saving after corruption preserves the known-good backup");
            Files.write(backup,new byte[]{1});ioFailure(()->SteamRockDrills.load(file),"corrupt sole backup fails without silently losing machines");
            store.save(file);Files.deleteIfExists(backup);
            p=read(file);p.setProperty("d.0.layer","0");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"a valid checksum cannot allow surface mining");
            store.save(file);p=read(file);p.setProperty("d.0.running","yes");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"malformed power state is not silently interpreted as stopped");
            store.save(file);p=read(file);p.setProperty("drills","1");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"orphaned rows cannot hide behind an incorrect count");
            store.save(file);p=read(file);p.setProperty("d.1.item","100");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"duplicate machine item is rejected");
            store.save(file);p=read(file);p.remove("d.0.hopper");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"truncated output association is rejected");
            store.save(file);p=read(file);p.setProperty("revision","0");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"generation behind saved controls is rejected");
            store.save(file);p=read(file);p.setProperty("format","999");writeChecked(file,p);ioFailure(()->SteamRockDrills.load(file),"unknown future state format is preserved for repair");
            store.remove(100);store.remove(200);long generation=store.nextRevision();store.save(file);
            SteamRockDrills empty=SteamRockDrills.load(file);
            check(empty.drills.isEmpty()&&empty.nextRevision()==generation+1,"empty registry retains generations of removed machines");
        }finally{cleanup(directory);}
    }
    private static Properties read(Path file)throws Exception {Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void writeChecked(Path file,Properties p)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")) {
            String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));p.setProperty("checksum",hex.toString());
        try(OutputStream out=Files.newOutputStream(file)){p.store(out,"deliberate malformed machine state fixture");}
    }
    static void cleanup(Path directory)throws Exception {
        Path boundary=directory.toRealPath();List<Path> paths=new ArrayList<Path>();
        try(java.util.stream.Stream<Path> stream=Files.walk(boundary)){stream.forEach(paths::add);}paths.sort(Comparator.reverseOrder());
        for(Path path:paths){if(!path.toAbsolutePath().normalize().startsWith(boundary))throw new AssertionError("cleanup outside test fixture");Files.delete(path);}
    }
    private interface Work {void run()throws Exception;}
    private static void invalid(Work work,String detail){try{work.run();throw new AssertionError(detail);}catch(IllegalArgumentException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void ioFailure(Work work,String detail){try{work.run();throw new AssertionError(detail);}catch(IOException expected){assertions++;}catch(Exception failure){throw new AssertionError(failure);}}
    private static void check(boolean value,String detail){assertions++;if(!value)throw new AssertionError(detail);}
}
