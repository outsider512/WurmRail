package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Durable woodlot controls. Trees and harvested timber remain native world data. */
public final class SteamYarders {
    public static final int RADIUS=5,MAX_YARDERS=10000;
    public enum HarvestAge {
        MATURE("Mature"),OLD("Old"),VERY_OLD("Very old"),OVERAGED("Overaged");
        public final String displayName;
        HarvestAge(String displayName){this.displayName=displayName;}
    }
    public static final class Offset {
        public final int dx,dy;
        private Offset(int dx,int dy){this.dx=dx;this.dy=dy;}
    }
    public static final List<Offset> WOODLOT;
    static {
        List<Offset> offsets=new ArrayList<Offset>();
        for(int dy=-RADIUS;dy<=RADIUS;dy++)for(int dx=-RADIUS;dx<=RADIUS;dx++)
            if(dx*dx+dy*dy<=RADIUS*RADIUS)offsets.add(new Offset(dx,dy));
        WOODLOT=Collections.unmodifiableList(offsets);
    }
    public static final class Yarder {
        public final long itemId,ownerId,revision,bunkId;
        public final int tileX,tileY,layer,species,nextTile;
        public final HarvestAge harvestAge;
        public final boolean running;
        public final String status;
        public Yarder(long itemId,long ownerId,long revision,int tileX,int tileY,int layer,long bunkId,
                int species,HarvestAge harvestAge,int nextTile,boolean running,String status) {
            if(itemId<=0||ownerId<=0||revision<0||tileX<0||tileY<0||layer!=0||bunkId<0||species<0||species>255
                    ||harvestAge==null||nextTile<0||nextTile>=WOODLOT.size())
                throw new IllegalArgumentException("Invalid Steam Yarder configuration.");
            this.itemId=itemId;this.ownerId=ownerId;this.revision=revision;this.tileX=tileX;this.tileY=tileY;this.layer=layer;
            this.bunkId=bunkId;this.species=species;this.harvestAge=harvestAge;this.nextTile=nextTile;this.running=running;this.status=checkedStatus(status);
        }
        /** Harvest progress never invalidates an otherwise current control form. */
        public Yarder withProgress(boolean running,int cursor,String status) {
            return new Yarder(itemId,ownerId,revision,tileX,tileY,layer,bunkId,species,harvestAge,cursor,running,status);
        }
        public Yarder withRevision(long revision) {
            return new Yarder(itemId,ownerId,revision,tileX,tileY,layer,bunkId,species,harvestAge,nextTile,running,status);
        }
    }
    private final Map<Long,Yarder> data=new LinkedHashMap<Long,Yarder>();
    public final Map<Long,Yarder> yarders=Collections.unmodifiableMap(data);
    private long revision,epoch;
    public long nextRevision(){if(revision==Long.MAX_VALUE)throw new IllegalStateException("Steam Yarder revision limit reached.");return ++revision;}
    public Yarder get(long id){return data.get(id);}
    public void put(Yarder yarder) {
        if(yarder==null)throw new IllegalArgumentException("A Steam Yarder is required.");
        if(data.size()>=MAX_YARDERS&&!data.containsKey(yarder.itemId))throw new IllegalArgumentException("The Steam Yarder limit has been reached.");
        Yarder previous=data.put(yarder.itemId,yarder);revision=Math.max(revision,yarder.revision);
        if(previous==null||previous.revision!=yarder.revision||previous.running!=yarder.running)epoch++;
    }
    public Yarder remove(long id){Yarder previous=data.remove(id);if(previous!=null)epoch++;return previous;}
    long epoch(){return epoch;}
    public SteamYarders copy(){SteamYarders copy=new SteamYarders();copy.data.putAll(data);copy.revision=revision;copy.epoch=epoch;return copy;}
    /** Tile centres in the physical circle; machine heading has no influence. */
    public static boolean contains(Yarder yarder,int x,int y) {
        if(yarder==null)return false;long dx=(long)x-yarder.tileX,dy=(long)y-yarder.tileY;
        return Math.abs(dx)<=RADIUS&&Math.abs(dy)<=RADIUS&&dx*dx+dy*dy<=RADIUS*RADIUS;
    }
    private static String checkedStatus(String status) {
        if(status==null||status.trim().isEmpty()||status.length()>512)throw new IllegalArgumentException("A one-line Steam Yarder status is required.");
        for(int i=0;i<status.length();i++)if(Character.isISOControl(status.charAt(i)))throw new IllegalArgumentException("A one-line Steam Yarder status is required.");
        return status;
    }
    public static SteamYarders load(Path file)throws IOException {
        if(!Files.exists(file))return Files.exists(backup(file))?read(backup(file)):new SteamYarders();
        try{return read(file);}catch(IOException original) {
            if(Files.exists(backup(file)))try{return read(backup(file));}catch(IOException failed){original.addSuppressed(failed);}
            throw original;
        }
    }
    private static SteamYarders read(Path file)throws IOException {
        try {
            Properties p=new Properties();try(InputStream input=Files.newInputStream(file)){p.load(input);}
            String format=p.getProperty("format");
            if(!("1".equals(format)||"2".equals(format))||!checksum(p).equals(p.getProperty("checksum")))throw new IOException("Steam Yarder state checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"yarders"));if(count<0||count>MAX_YARDERS)throw new IllegalArgumentException("Invalid Steam Yarder count.");
            SteamYarders result=new SteamYarders();Set<String> expected=new HashSet<String>(Arrays.asList("format","yarders","revision","checksum"));
            for(int i=0;i<count;i++) {
                String key="y."+i+".";String running=required(p,key+"running");
                if(!running.equals("true")&&!running.equals("false"))throw new IllegalArgumentException("Invalid yarder power state.");
                Yarder yarder=new Yarder(Long.parseLong(required(p,key+"item")),Long.parseLong(required(p,key+"owner")),Long.parseLong(required(p,key+"revision")),
                        Integer.parseInt(required(p,key+"x")),Integer.parseInt(required(p,key+"y")),Integer.parseInt(required(p,key+"layer")),
                        Long.parseLong(required(p,key+"bunk")),Integer.parseInt(required(p,key+"species")),HarvestAge.valueOf(required(p,key+"age")),
                        cursor(Integer.parseInt(required(p,key+"next")),"1".equals(format)),Boolean.parseBoolean(running),required(p,key+"status"));
                if(result.get(yarder.itemId)!=null)throw new IllegalArgumentException("Duplicate Steam Yarder item.");result.put(yarder);
                for(String field:new String[]{"item","owner","revision","x","y","layer","bunk","species","age","next","running","status"})expected.add(key+field);
            }
            long generation=Long.parseLong(required(p,"revision"));if(generation<result.revision)throw new IllegalArgumentException("Steam Yarder revision is behind its records.");
            result.revision=generation;if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected Steam Yarder state fields.");return result;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid Steam Yarder state: "+file,invalid);}
    }
    public void save(Path file)throws IOException {
        Properties p=new Properties();p.setProperty("format","2");p.setProperty("yarders",Integer.toString(data.size()));p.setProperty("revision",Long.toString(revision));int index=0;
        for(Yarder yarder:data.values()) {
            String key="y."+(index++)+".";p.setProperty(key+"item",Long.toString(yarder.itemId));p.setProperty(key+"owner",Long.toString(yarder.ownerId));p.setProperty(key+"revision",Long.toString(yarder.revision));
            p.setProperty(key+"x",Integer.toString(yarder.tileX));p.setProperty(key+"y",Integer.toString(yarder.tileY));p.setProperty(key+"layer",Integer.toString(yarder.layer));
            p.setProperty(key+"bunk",Long.toString(yarder.bunkId));p.setProperty(key+"species",Integer.toString(yarder.species));p.setProperty(key+"age",yarder.harvestAge.name());
            p.setProperty(key+"next",Integer.toString(yarder.nextTile));p.setProperty(key+"running",Boolean.toString(yarder.running));p.setProperty(key+"status",yarder.status);
        }
        p.setProperty("checksum",checksum(p));Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());Path temporary=Files.createTempFile(absolute.getParent(),"yarders-",".tmp");
        try {
            try(OutputStream output=Files.newOutputStream(temporary)){p.store(output,"WurmRail Steam Yarder controls; trees/timber remain native world data");}
            if(Files.exists(file)){boolean valid=true;try{read(file);}catch(IOException invalid){valid=false;}if(valid)Files.copy(file,backup(file),StandardCopyOption.REPLACE_EXISTING);}
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    private static Path backup(Path file){return file.resolveSibling(file.getFileName()+".bak");}
    /** Version1 cursors name a physical offset in the old three-tile circle. */
    private static int cursor(int saved,boolean legacy) {
        if(!legacy)return saved;
        if(saved<0||saved>=29)throw new IllegalArgumentException("Invalid legacy Steam Yarder cursor.");
        int index=0;
        for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++)if(x*x+y*y<=9) {
            if(index++!=saved)continue;
            for(int at=0;at<WOODLOT.size();at++){Offset offset=WOODLOT.get(at);if(offset.dx==x&&offset.dy==y)return at;}
        }
        throw new IllegalArgumentException("The legacy Steam Yarder cursor cannot be migrated.");
    }
    private static String required(Properties p,String key){String value=p.getProperty(key);if(value==null)throw new IllegalArgumentException("Missing "+key);return value;}
    private static String checksum(Properties p) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum")){String value=p.getProperty(key);digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
