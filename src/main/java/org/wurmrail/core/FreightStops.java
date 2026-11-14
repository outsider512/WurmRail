package org.wurmrail.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Passive freight-stop definitions. Train routes continue to refer to their existing rail identities. */
public final class FreightStops {
    public enum Operation { LOAD, UNLOAD }

    public static final class Stop {
        public final String rail, name;
        public final long markerId, ownerId, revision, storageId;
        public final Operation operation;
        /** Zero accepts any eligible item; a positive value is an exact Wurm template ID. */
        public final int templateId;
        public final double rateKgPerSecond;

        public Stop(String rail,long markerId,long ownerId,long revision,String name,
                Operation operation,long storageId,int templateId,double rateKgPerSecond) {
            if(rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")||markerId<=0||ownerId<=0
                    ||revision<0||storageId<=0||operation==null||templateId<0
                    ||!Double.isFinite(rateKgPerSecond)||rateKgPerSecond<.1||rateKgPerSecond>10000)
                throw new IllegalArgumentException("Invalid freight stop configuration.");
            this.rail=rail; this.markerId=markerId; this.ownerId=ownerId; this.revision=revision;
            this.name=label(name); this.operation=operation; this.storageId=storageId;
            this.templateId=templateId; this.rateKgPerSecond=rateKgPerSecond;
        }
    }

    private final Map<String,Stop> data=new LinkedHashMap<String,Stop>();
    public final Map<String,Stop> stops=Collections.unmodifiableMap(data);
    // Retain consumed generations even when the last stop is removed. A stale
    // configuration window must not match a later stop on the same marker.
    private long revision;

    public long nextRevision() {
        if(revision==Long.MAX_VALUE)throw new IllegalStateException("Freight stop revision limit reached.");
        return ++revision;
    }

    /** Ordinary passenger/rail stops have no freight configuration and return null. */
    public Stop get(String rail) { return data.get(rail); }

    /** Replace a configuration as one immutable value; reject ambiguous names and marker identities. */
    public void put(Stop stop) {
        if(stop==null)throw new IllegalArgumentException("A freight stop is required.");
        if(data.size()>=10000&&!data.containsKey(stop.rail))throw new IllegalArgumentException("The freight stop limit has been reached.");
        for(Stop other:data.values())if(!other.rail.equals(stop.rail)) {
            if(other.name.equalsIgnoreCase(stop.name))throw new IllegalArgumentException("Choose a different freight stop name.");
            if(other.markerId==stop.markerId)throw new IllegalArgumentException("This rail marker already belongs to another freight stop.");
        }
        data.put(stop.rail,stop);
        revision=Math.max(revision,stop.revision);
    }

    public Stop remove(String rail) { return data.remove(rail); }
    public FreightStops copy() { FreightStops copy=new FreightStops();copy.data.putAll(data);copy.revision=revision;return copy; }

    public static String label(String text) {
        String value=text==null?"":text.trim();
        if(value.isEmpty()||value.length()>48)throw new IllegalArgumentException("Freight stop names must contain 1 to 48 characters.");
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Freight stop names must be on one line.");
        return value;
    }

    public static FreightStops load(Path file) throws IOException {
        if(!Files.exists(file))return Files.exists(backup(file))?read(backup(file)):new FreightStops();
        try { return read(file); }
        catch(IOException original) {
            if(Files.exists(backup(file)))try { return read(backup(file)); }
            catch(IOException failed) { original.addSuppressed(failed); }
            throw original;
        }
    }

    private static FreightStops read(Path file) throws IOException {
        try {
            Properties p=new Properties();
            try(InputStream in=Files.newInputStream(file)) { p.load(in); }
            if(!"1".equals(p.getProperty("format"))||!checksum(p).equals(p.getProperty("checksum")))
                throw new IOException("Freight stop state checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"stops"));
            if(count<0||count>10000)throw new IllegalArgumentException("Invalid freight stop count.");
            FreightStops result=new FreightStops();
            for(int i=0;i<count;i++) {
                String key="s."+i+".";
                Stop stop=new Stop(required(p,key+"rail"),Long.parseLong(required(p,key+"marker")),
                        Long.parseLong(required(p,key+"owner")),Long.parseLong(required(p,key+"revision")),
                        required(p,key+"name"),Operation.valueOf(required(p,key+"operation")),
                        Long.parseLong(required(p,key+"storage")),Integer.parseInt(required(p,key+"template")),
                        Double.parseDouble(required(p,key+"rate")));
                if(result.data.containsKey(stop.rail))throw new IllegalArgumentException("Duplicate freight stop rail.");
                result.put(stop);
            }
            // Early development files had only per-stop revisions. Derive their
            // high-water mark; current files retain the mark through removals.
            if(p.getProperty("revision")!=null) {
                long generation=Long.parseLong(p.getProperty("revision"));
                if(generation<result.revision)throw new IllegalArgumentException("Freight stop revision is behind its records.");
                result.revision=generation;
            }
            // A valid checksum must not hide orphaned or truncated definition rows.
            Set<String> expected=new HashSet<String>(Arrays.asList("format","stops","checksum"));
            if(p.getProperty("revision")!=null)expected.add("revision");
            for(int i=0;i<count;i++)for(String field:new String[]{"rail","marker","owner","revision","name","operation","storage","template","rate"})
                expected.add("s."+i+"."+field);
            if(!expected.equals(p.stringPropertyNames()))throw new IllegalArgumentException("Unexpected freight stop state fields.");
            return result;
        } catch(IllegalArgumentException failure) { throw new IOException("Invalid freight stop state: "+file,failure); }
    }

    public void save(Path file) throws IOException {
        Properties p=new Properties();p.setProperty("format","1");p.setProperty("stops",Integer.toString(data.size()));
        p.setProperty("revision",Long.toString(revision));
        int index=0;
        for(Stop stop:data.values()) {
            String key="s."+(index++)+".";
            p.setProperty(key+"rail",stop.rail);p.setProperty(key+"marker",Long.toString(stop.markerId));
            p.setProperty(key+"owner",Long.toString(stop.ownerId));p.setProperty(key+"revision",Long.toString(stop.revision));
            p.setProperty(key+"name",stop.name);p.setProperty(key+"operation",stop.operation.name());
            p.setProperty(key+"storage",Long.toString(stop.storageId));p.setProperty(key+"template",Integer.toString(stop.templateId));
            p.setProperty(key+"rate",Double.toString(stop.rateKgPerSecond));
        }
        p.setProperty("checksum",checksum(p));
        Path absolute=file.toAbsolutePath();Files.createDirectories(absolute.getParent());
        Path temp=Files.createTempFile(absolute.getParent(),"freight-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)) { p.store(out,"WurmRail freight stop definitions; cargo remains in native inventories"); }
            if(Files.exists(file)) {
                boolean valid=true;try { read(file); }catch(IOException invalid) { valid=false; }
                if(valid)Files.copy(file,backup(file),StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private static Path backup(Path file) { return file.resolveSibling(file.getFileName()+".bak"); }
    private static String required(Properties properties,String key) {
        String value=properties.getProperty(key);if(value==null)throw new IllegalArgumentException("Missing "+key);return value;
    }
    private static String checksum(Properties properties) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<String>(properties.stringPropertyNames()))if(!key.equals("checksum")) {
                String value=properties.getProperty(key);
                digest.update((key.length()+":"+key+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
