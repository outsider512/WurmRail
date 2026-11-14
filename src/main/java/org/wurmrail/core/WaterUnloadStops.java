package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Passive stored-water freight definitions keyed by existing route rail identities. */
public final class WaterUnloadStops {
    public static final int MAX_STOPS=10000;
    public enum Operation {LOAD,UNLOAD}
    public static final class Stop {
        public final String rail,name;
        public final long markerId,ownerId,revision,storageId;
        public final double rateLitresPerSecond;
        public final Operation operation;

        public Stop(String rail,long markerId,long ownerId,long revision,String name,
                long storageId,double rateLitresPerSecond) {
            this(rail,markerId,ownerId,revision,name,Operation.UNLOAD,storageId,rateLitresPerSecond);
        }
        public Stop(String rail,long markerId,long ownerId,long revision,String name,Operation operation,
                long storageId,double rateLitresPerSecond) {
            if(rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")||markerId<=0||ownerId<=0||revision<0
                    ||storageId<=0||operation==null
                    ||!Double.isFinite(rateLitresPerSecond)||rateLitresPerSecond<.1||rateLitresPerSecond>10000)
                throw new IllegalArgumentException("Invalid Water freight stop configuration.");
            this.rail=rail;this.markerId=markerId;this.ownerId=ownerId;this.revision=revision;this.name=label(name);
            this.storageId=storageId;this.rateLitresPerSecond=rateLitresPerSecond;this.operation=operation;
        }
    }

    private final Map<String,Stop> data=new LinkedHashMap<String,Stop>();
    public final Map<String,Stop> stops=Collections.unmodifiableMap(data);
    private long revision;
    public long nextRevision() {
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Water freight stop revision limit reached.");
        return ++revision;
    }
    public Stop get(String rail) {return data.get(rail);}
    public void put(Stop stop) {
        if(stop==null)throw new IllegalArgumentException("A Water freight stop is required.");
        if(data.size()>=MAX_STOPS&&!data.containsKey(stop.rail))throw new IllegalArgumentException("The Water freight stop limit has been reached.");
        for(Stop other:data.values())if(!other.rail.equals(stop.rail)) {
            if(other.name.equalsIgnoreCase(stop.name))throw new IllegalArgumentException("Choose a different Water freight stop name.");
            if(other.markerId==stop.markerId)throw new IllegalArgumentException("This rail marker already belongs to another Water freight stop.");
        }
        data.put(stop.rail,stop);revision=Math.max(revision,stop.revision);
    }
    public Stop remove(String rail) {return data.remove(rail);}
    public WaterUnloadStops copy() {WaterUnloadStops copy=new WaterUnloadStops();copy.data.putAll(data);copy.revision=revision;return copy;}
    public static String label(String text) {
        String value=text==null?"":text.trim();
        if(value.isEmpty()||value.length()>48)throw new IllegalArgumentException("Water freight stop names must contain 1 to 48 characters.");
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Water freight stop names must be on one line.");
        return value;
    }

    public static WaterUnloadStops load(Path file) throws IOException {
        if(!Files.exists(file))return Files.exists(backup(file))?read(backup(file)):new WaterUnloadStops();
        try{return read(file);}catch(IOException original) {
            if(Files.exists(backup(file)))try{return read(backup(file));}catch(IOException failed){original.addSuppressed(failed);}
            throw original;
        }
    }
    private static WaterUnloadStops read(Path file) throws IOException {
        try {
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            boolean legacy="1".equals(p.getProperty("format"));
            if((!legacy&&!"2".equals(p.getProperty("format")))||!checksum(p).equals(p.getProperty("checksum")))
                throw new IOException("Water freight stop state checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"stops"));
            if(count<0||count>MAX_STOPS)throw new IllegalArgumentException("Invalid Water freight stop count.");
            WaterUnloadStops result=new WaterUnloadStops();
            Set<String> expected=new HashSet<String>(Arrays.asList("format","stops","revision","checksum"));
            for(int i=0;i<count;i++) {
                String key="s."+i+".";
                Stop stop=new Stop(required(p,key+"rail"),Long.parseLong(required(p,key+"marker")),
                        Long.parseLong(required(p,key+"owner")),Long.parseLong(required(p,key+"revision")),required(p,key+"name"),
                        legacy?Operation.UNLOAD:Operation.valueOf(required(p,key+"operation")),Long.parseLong(required(p,key+"storage")),Double.parseDouble(required(p,key+"rate")));
                if(result.get(stop.rail)!=null)throw new IllegalArgumentException("Duplicate Water freight stop rail.");
                result.put(stop);
                for(String field:new String[]{"rail","marker","owner","revision","name","storage","rate"})expected.add(key+field);
                if(!legacy)expected.add(key+"operation");
            }
            long generation=Long.parseLong(required(p,"revision"));
            if(generation<result.revision)throw new IllegalArgumentException("Water freight stop revision is behind its records.");
            result.revision=generation;
            if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected Water freight stop state fields.");
            return result;
        }catch(IllegalArgumentException failure){throw new IOException("Invalid Water freight stop state: "+file,failure);}
    }
    public void save(Path file) throws IOException {
        Properties p=new Properties();p.setProperty("format","2");p.setProperty("stops",Integer.toString(data.size()));
        p.setProperty("revision",Long.toString(revision));int index=0;
        for(Stop stop:data.values()) {
            String key="s."+(index++)+".";
            p.setProperty(key+"rail",stop.rail);p.setProperty(key+"marker",Long.toString(stop.markerId));
            p.setProperty(key+"owner",Long.toString(stop.ownerId));p.setProperty(key+"revision",Long.toString(stop.revision));
            p.setProperty(key+"name",stop.name);p.setProperty(key+"storage",Long.toString(stop.storageId));
            p.setProperty(key+"rate",Double.toString(stop.rateLitresPerSecond));
            p.setProperty(key+"operation",stop.operation.name());
        }
        p.setProperty("checksum",checksum(p));Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());
        Path temp=Files.createTempFile(absolute.getParent(),"water-unload-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)){p.store(out,"WurmRail Water freight stops; tank contents remain native liquid items");}
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
