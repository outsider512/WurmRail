package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Durable machine controls; water itself remains in native Wurm item inventories. */
public final class SteamWaterPumps {
    public static final int MAX_PUMPS=10000;
    public static final class Pump {
        public final long itemId,ownerId,revision,tankId;
        public final int tileX,tileY,layer,sourceX,sourceY,sourceLayer;
        /** Running is operator intent, including automatic waits for storage. */
        public final boolean running;
        public final String status;
        public Pump(long itemId,long ownerId,long revision,int tileX,int tileY,int layer,long tankId,int sourceX,int sourceY,int sourceLayer,boolean running,String status) {
            if(itemId<=0||ownerId<=0||revision<0||tileX<0||tileY<0||(layer!=0&&layer!=-1)||tankId<0
                    ||sourceX< -1||sourceY< -1||(sourceX==-1)!=(sourceY==-1)||(sourceLayer!=0&&sourceLayer!=-1))
                throw new IllegalArgumentException("Invalid Steam Water Pump configuration.");
            this.itemId=itemId;this.ownerId=ownerId;this.revision=revision;this.tileX=tileX;this.tileY=tileY;this.layer=layer;
            this.tankId=tankId;this.sourceX=sourceX;this.sourceY=sourceY;this.sourceLayer=sourceLayer;this.running=running;this.status=line(status);
        }
        public Pump withProgress(boolean running,String status){return new Pump(itemId,ownerId,revision,tileX,tileY,layer,tankId,sourceX,sourceY,sourceLayer,running,status);}
        public Pump withRevision(long revision){return new Pump(itemId,ownerId,revision,tileX,tileY,layer,tankId,sourceX,sourceY,sourceLayer,running,status);}
    }
    private final Map<Long,Pump> data=new LinkedHashMap<Long,Pump>();
    public final Map<Long,Pump> pumps=Collections.unmodifiableMap(data);
    private long revision,epoch;
    public long nextRevision(){if(revision==Long.MAX_VALUE)throw new IllegalStateException("Pump revision limit reached.");return ++revision;}
    public Pump get(long id){return data.get(id);}
    public void put(Pump pump){if(pump==null)throw new IllegalArgumentException("Choose a pump.");if(data.size()>=MAX_PUMPS&&!data.containsKey(pump.itemId))throw new IllegalArgumentException("The pump limit has been reached.");Pump before=data.put(pump.itemId,pump);revision=Math.max(revision,pump.revision);if(before==null||before.revision!=pump.revision||before.running!=pump.running)epoch++;}
    public Pump remove(long id){Pump old=data.remove(id);if(old!=null)epoch++;return old;}
    long epoch(){return epoch;}
    public SteamWaterPumps copy(){SteamWaterPumps result=new SteamWaterPumps();result.data.putAll(data);result.revision=revision;result.epoch=epoch;return result;}
    private static String line(String value){if(value==null||value.trim().isEmpty()||value.length()>512)throw new IllegalArgumentException("A one-line pump status is required.");for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))throw new IllegalArgumentException("A one-line pump status is required.");return value;}
    public static SteamWaterPumps load(Path file)throws IOException {
        if(!Files.exists(file))return Files.exists(backup(file))?read(backup(file)):new SteamWaterPumps();
        try{return read(file);}catch(IOException original){if(Files.exists(backup(file)))try{return read(backup(file));}catch(IOException failed){original.addSuppressed(failed);}throw original;}
    }
    private static SteamWaterPumps read(Path file)throws IOException {
        try {
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            if(!"1".equals(p.getProperty("format"))||!checksum(p).equals(p.getProperty("checksum")))throw new IOException("Pump state checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"pumps"));if(count<0||count>MAX_PUMPS)throw new IllegalArgumentException("Invalid pump count.");
            SteamWaterPumps result=new SteamWaterPumps();Set<String> expected=new HashSet<String>(Arrays.asList("format","pumps","revision","checksum"));
            for(int i=0;i<count;i++) {
                String k="p."+i+".";String running=required(p,k+"running");if(!running.equals("true")&&!running.equals("false"))throw new IllegalArgumentException("Invalid pump running state.");
                Pump pump=new Pump(Long.parseLong(required(p,k+"item")),Long.parseLong(required(p,k+"owner")),Long.parseLong(required(p,k+"revision")),Integer.parseInt(required(p,k+"x")),Integer.parseInt(required(p,k+"y")),Integer.parseInt(required(p,k+"layer")),Long.parseLong(required(p,k+"tank")),Integer.parseInt(required(p,k+"sourceX")),Integer.parseInt(required(p,k+"sourceY")),Integer.parseInt(required(p,k+"sourceLayer")),Boolean.parseBoolean(running),required(p,k+"status"));
                if(result.get(pump.itemId)!=null)throw new IllegalArgumentException("Duplicate pump item.");result.put(pump);
                for(String f:new String[]{"item","owner","revision","x","y","layer","tank","sourceX","sourceY","sourceLayer","running","status"})expected.add(k+f);
            }
            long generation=Long.parseLong(required(p,"revision"));if(generation<result.revision)throw new IllegalArgumentException("Pump revision is behind its records.");result.revision=generation;
            if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected pump state fields.");return result;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid Steam Water Pump state: "+file,invalid);}
    }
    public void save(Path file)throws IOException {
        Properties p=new Properties();p.setProperty("format","1");p.setProperty("pumps",Integer.toString(data.size()));p.setProperty("revision",Long.toString(revision));int index=0;
        for(Pump pump:data.values()) {String k="p."+(index++)+".";p.setProperty(k+"item",Long.toString(pump.itemId));p.setProperty(k+"owner",Long.toString(pump.ownerId));p.setProperty(k+"revision",Long.toString(pump.revision));p.setProperty(k+"x",Integer.toString(pump.tileX));p.setProperty(k+"y",Integer.toString(pump.tileY));p.setProperty(k+"layer",Integer.toString(pump.layer));p.setProperty(k+"tank",Long.toString(pump.tankId));p.setProperty(k+"sourceX",Integer.toString(pump.sourceX));p.setProperty(k+"sourceY",Integer.toString(pump.sourceY));p.setProperty(k+"sourceLayer",Integer.toString(pump.sourceLayer));p.setProperty(k+"running",Boolean.toString(pump.running));p.setProperty(k+"status",pump.status);}
        p.setProperty("checksum",checksum(p));Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());Path temporary=Files.createTempFile(absolute.getParent(),"pumps-",".tmp");
        try {try(OutputStream out=Files.newOutputStream(temporary)){p.store(out,"WurmRail Steam Water Pump controls; water remains native Wurm items");}if(Files.exists(file)){boolean valid=true;try{read(file);}catch(IOException invalid){valid=false;}if(valid)Files.copy(file,backup(file),StandardCopyOption.REPLACE_EXISTING);}try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temporary);}
    }
    private static Path backup(Path file){return file.resolveSibling(file.getFileName()+".bak");}
    private static String required(Properties p,String key){String v=p.getProperty(key);if(v==null)throw new IllegalArgumentException("Missing "+key);return v;}
    private static String checksum(Properties p){try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String k:new TreeSet<String>(p.stringPropertyNames()))if(!k.equals("checksum")){String v=p.getProperty(k);d.update((k.length()+":"+k+v.length()+":"+v+"\n").getBytes(StandardCharsets.UTF_8));}StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
