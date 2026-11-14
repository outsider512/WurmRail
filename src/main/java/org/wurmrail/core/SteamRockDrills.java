package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Durable machine identities and controls; the resource items remain in Wurm inventories. */
public final class SteamRockDrills {
    public static final int NORTH=0,EAST=1,SOUTH=2,WEST=3,ALL_FACES=15;
    public static final int MAX_DRILLS=10000;

    public static final class Drill {
        public final long itemId,ownerId,revision,hopperId;
        public final int tileX,tileY,layer,enabledMask,nextFace;
        public final boolean running;
        public final String status;

        public Drill(long itemId,long ownerId,long revision,int tileX,int tileY,int layer,
                long hopperId,int enabledMask,int nextFace,boolean running,String status) {
            if(itemId<=0||ownerId<=0||revision<0||tileX<0||tileY<0||layer!=-1||hopperId<0
                    ||enabledMask<0||(enabledMask&~ALL_FACES)!=0||nextFace<0||nextFace>3)
                throw new IllegalArgumentException("Invalid Steam Rock Drill configuration.");
            this.itemId=itemId;this.ownerId=ownerId;this.revision=revision;
            this.tileX=tileX;this.tileY=tileY;this.layer=layer;this.hopperId=hopperId;
            this.enabledMask=enabledMask;this.nextFace=nextFace;this.running=running;
            this.status=checkedStatus(status);
        }
        /** Progress changes do not invalidate an otherwise current configuration form. */
        public Drill withProgress(boolean running,int nextFace,String status) {
            return new Drill(itemId,ownerId,revision,tileX,tileY,layer,hopperId,enabledMask,nextFace,running,status);
        }
        public Drill withRevision(long revision) {
            return new Drill(itemId,ownerId,revision,tileX,tileY,layer,hopperId,enabledMask,nextFace,running,status);
        }
    }

    private final Map<Long,Drill> data=new LinkedHashMap<Long,Drill>();
    public final Map<Long,Drill> drills=Collections.unmodifiableMap(data);
    private long revision,epoch;

    public long nextRevision() {
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Steam Rock Drill revision limit reached.");
        return ++revision;
    }
    public Drill get(long id) {return data.get(id);}
    public void put(Drill drill) {
        if(drill==null)throw new IllegalArgumentException("A Steam Rock Drill is required.");
        if(data.size()>=MAX_DRILLS&&!data.containsKey(drill.itemId))
            throw new IllegalArgumentException("The Steam Rock Drill limit has been reached.");
        Drill previous=data.put(drill.itemId,drill);revision=Math.max(revision,drill.revision);
        if(previous==null||previous.revision!=drill.revision||previous.running!=drill.running)epoch++;
    }
    public Drill remove(long id) {Drill removed=data.remove(id);if(removed!=null)epoch++;return removed;}
    long epoch(){return epoch;}
    public SteamRockDrills copy() {
        SteamRockDrills result=new SteamRockDrills();result.data.putAll(data);result.revision=revision;result.epoch=epoch;return result;
    }
    public static String faceName(int face) {
        switch(face) {case NORTH:return "North";case EAST:return "East";case SOUTH:return "South";case WEST:return "West";
            default:throw new IllegalArgumentException("Choose a cardinal mining face.");}
    }
    /** A deterministic physical compass order, independent of machine or train heading. */
    public static int selectFace(int enabledMask,int availableMask,int cursor) {
        if(cursor<0||cursor>3)throw new IllegalArgumentException("Invalid mining cursor.");
        int mask=enabledMask&availableMask&ALL_FACES;
        for(int offset=0;offset<4;offset++) {int face=(cursor+offset)&3;if((mask&(1<<face))!=0)return face;}
        return -1;
    }
    private static String checkedStatus(String status) {
        if(status==null||status.trim().isEmpty()||status.length()>512)
            throw new IllegalArgumentException("A one-line Steam Rock Drill status is required.");
        for(int i=0;i<status.length();i++)if(Character.isISOControl(status.charAt(i)))
            throw new IllegalArgumentException("A one-line Steam Rock Drill status is required.");
        return status;
    }

    public static SteamRockDrills load(Path file) throws IOException {
        if(!Files.exists(file))return Files.exists(backup(file))?read(backup(file)):new SteamRockDrills();
        try {return read(file);}
        catch(IOException original) {
            if(Files.exists(backup(file)))try{return read(backup(file));}
            catch(IOException failed){original.addSuppressed(failed);}
            throw original;
        }
    }
    private static SteamRockDrills read(Path file) throws IOException {
        try {
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            if(!"1".equals(p.getProperty("format"))||!checksum(p).equals(p.getProperty("checksum")))
                throw new IOException("Steam Rock Drill state checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"drills"));
            if(count<0||count>MAX_DRILLS)throw new IllegalArgumentException("Invalid Steam Rock Drill count.");
            SteamRockDrills result=new SteamRockDrills();
            for(int i=0;i<count;i++) {
                String key="d."+i+".";
                String run=required(p,key+"running");
                if(!run.equals("true")&&!run.equals("false"))throw new IllegalArgumentException("Invalid mining power state.");
                Drill drill=new Drill(Long.parseLong(required(p,key+"item")),Long.parseLong(required(p,key+"owner")),
                        Long.parseLong(required(p,key+"revision")),Integer.parseInt(required(p,key+"x")),
                        Integer.parseInt(required(p,key+"y")),Integer.parseInt(required(p,key+"layer")),
                        Long.parseLong(required(p,key+"hopper")),Integer.parseInt(required(p,key+"faces")),
                        Integer.parseInt(required(p,key+"next")),Boolean.parseBoolean(run),required(p,key+"status"));
                if(result.get(drill.itemId)!=null)throw new IllegalArgumentException("Duplicate Steam Rock Drill item.");
                result.put(drill);
            }
            long generation=Long.parseLong(required(p,"revision"));
            if(generation<result.revision)throw new IllegalArgumentException("Steam Rock Drill revision is behind its records.");
            result.revision=generation;
            Set<String> expected=new HashSet<String>(Arrays.asList("format","drills","revision","checksum"));
            for(int i=0;i<count;i++)for(String field:new String[]{"item","owner","revision","x","y","layer","hopper","faces","next","running","status"})
                expected.add("d."+i+"."+field);
            if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected Steam Rock Drill state fields.");
            return result;
        }catch(IllegalArgumentException failure){throw new IOException("Invalid Steam Rock Drill state: "+file,failure);}
    }
    public void save(Path file) throws IOException {
        Properties p=new Properties();p.setProperty("format","1");p.setProperty("drills",Integer.toString(data.size()));
        p.setProperty("revision",Long.toString(revision));int index=0;
        for(Drill drill:data.values()) {
            String key="d."+(index++)+".";
            p.setProperty(key+"item",Long.toString(drill.itemId));p.setProperty(key+"owner",Long.toString(drill.ownerId));
            p.setProperty(key+"revision",Long.toString(drill.revision));p.setProperty(key+"x",Integer.toString(drill.tileX));
            p.setProperty(key+"y",Integer.toString(drill.tileY));p.setProperty(key+"layer",Integer.toString(drill.layer));
            p.setProperty(key+"hopper",Long.toString(drill.hopperId));p.setProperty(key+"faces",Integer.toString(drill.enabledMask));
            p.setProperty(key+"next",Integer.toString(drill.nextFace));p.setProperty(key+"running",Boolean.toString(drill.running));
            p.setProperty(key+"status",drill.status);
        }
        p.setProperty("checksum",checksum(p));
        Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());
        Path temp=Files.createTempFile(absolute.getParent(),"drills-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"WurmRail Steam Rock Drill controls; resources remain in native items");}
            if(Files.exists(file)) {
                boolean valid=true;try{read(file);}catch(IOException invalid){valid=false;}
                if(valid)Files.copy(file,backup(file),StandardCopyOption.REPLACE_EXISTING);
            }
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    private static Path backup(Path file){return file.resolveSibling(file.getFileName()+".bak");}
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null)throw new IllegalArgumentException("Missing "+key);return value;}
    private static String checksum(Properties p) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")) {
                String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
