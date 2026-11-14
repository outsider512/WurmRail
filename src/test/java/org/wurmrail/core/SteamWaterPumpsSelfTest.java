package org.wurmrail.core;

import java.nio.file.*;
import java.util.*;
import org.wurmrail.core.SteamWaterPumps.Pump;

public final class SteamWaterPumpsSelfTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("wurmrail-pump-state-");Path file=dir.resolve("pumps.properties");
        try {
            SteamWaterPumps state=SteamWaterPumps.load(file);check(state.pumps.isEmpty(),"new worlds have no pump settings");
            Pump pump=pump(1,1,true,"Pumping water.");state.put(pump);state.save(file);
            SteamWaterPumps loaded=SteamWaterPumps.load(file);Pump saved=loaded.get(1);
            check(saved.itemId==1&&saved.ownerId==2&&saved.revision==1&&saved.tankId==3,"stable native item and durable owner/tank IDs survive save");
            check(saved.tileX==40&&saved.tileY==50&&saved.layer==0&&saved.sourceX==41&&saved.sourceY==50&&saved.sourceLayer==0,"machine anchor and native source identity survive save");
            check(saved.running&&saved.status.equals("Pumping water."),"running intent and status persist without virtual water amounts");
            check(loaded.nextRevision()==2,"saved revision high-water mark survives restart");
            loaded.put(saved.withRevision(3).withProgress(false,"Stopped by operator."));loaded.save(file);
            check(!SteamWaterPumps.load(file).get(1).running,"explicit STOP is durable");
            Files.write(file,Arrays.asList("broken=true"));check(SteamWaterPumps.load(file).get(1).running,"a checked previous snapshot recovers an interrupted/corrupt primary write");
            Files.delete(file.resolveSibling("pumps.properties.bak"));boolean rejected=false;try{SteamWaterPumps.load(file);}catch(java.io.IOException expected){rejected=true;}check(rejected,"corrupt state without checked backup cannot silently start machines");
            SteamWaterPumps copy=state.copy();copy.remove(1);check(state.get(1)!=null&&copy.get(1)==null,"copies preserve isolated configuration");
            rejected=false;try{state.pumps.clear();}catch(UnsupportedOperationException expected){rejected=true;}check(rejected,"public pump registry is read-only");
            for(String bad:new String[]{"","bad\nstatus"}){rejected=false;try{pump(1,1,false,bad);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"saved status must be a bounded one-line reason");}
            rejected=false;try{new Pump(1,2,1,1,1,0,3,-1,0,0,false,"Stopped");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"partially absent sources are rejected");
            rejected=false;try{new Pump(1,2,1,1,1,1,3,1,1,0,false,"Stopped");}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"only real surface/cave layers are accepted");
            state.put(new Pump(2,2,5,42,50,-1,4,42,51,-1,true,"Waiting for capacity."));state.save(file);loaded=SteamWaterPumps.load(file);
            check(loaded.pumps.size()==2&&loaded.get(2).sourceLayer==-1&&loaded.get(2).running,"multiple machines and flooded cave source settings remain independent");
            Properties props=new Properties();try(java.io.InputStream in=Files.newInputStream(file)){props.load(in);}props.setProperty("p.0.tank","999");try(java.io.OutputStream out=Files.newOutputStream(file)){props.store(out,"tampered");}Files.deleteIfExists(file.resolveSibling("pumps.properties.bak"));rejected=false;try{SteamWaterPumps.load(file);}catch(java.io.IOException expected){rejected=true;}check(rejected,"tampered native links fail checksum validation");
            System.out.println("SteamWaterPumpsSelfTest: "+assertions+" assertions passed.");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(dir)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
    private static Pump pump(long id,long revision,boolean running,String status){return new Pump(id,2,revision,40,50,0,3,41,50,0,running,status);}
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
