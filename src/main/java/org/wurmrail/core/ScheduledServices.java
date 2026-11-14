package org.wurmrail.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.wurmrail.core.RailNetwork.Train;

/** Durable operator intent, separate from transient movement authority and power. */
public final class ScheduledServices {
    private final Map<String,Entry> entries=new LinkedHashMap<String,Entry>();

    private static final class Entry {
        final String name;
        final long locomotive, owner;
        final List<String> route;
        final int routeIndex;
        final double dwell;
        final float x,y;
        final int layer;
        Entry(Train train) {
            this(train.name,train.carIds.get(0),train.ownerId,train.route,train.routeIndex,train.dwellRemaining,
                    train.poses.get(0).x,train.poses.get(0).y,train.poses.get(0).layer);
        }
        Entry(String name,long locomotive,long owner,List<String> route,int routeIndex,double dwell,float x,float y,int layer) {
            if(name==null||!name.matches("[A-Za-z0-9_-]{1,40}")||locomotive<=0||route==null||route.size()<2||route.size()>1000
                    ||routeIndex<0||routeIndex>=route.size()||!Double.isFinite(dwell)||dwell<0||dwell>3600
                    ||!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||(layer!=0&&layer!=-1))
                throw new IllegalArgumentException("Invalid scheduled service intent.");
            for(String stop:route)if(stop==null||!stop.matches("[A-Za-z0-9_-]{1,40}"))
                throw new IllegalArgumentException("Invalid service route stop.");
            this.name=name; this.locomotive=locomotive; this.owner=owner;
            this.route=Collections.unmodifiableList(new ArrayList<String>(route));
            this.routeIndex=routeIndex; this.dwell=dwell;this.x=x;this.y=y;this.layer=layer;
        }
        boolean matches(Train train) {
            return scheduled(train)&&name.equals(train.name)&&locomotive==train.carIds.get(0)&&owner==train.ownerId&&route.equals(train.route);
        }
    }

    public static final class Checkpoint {
        private final Map<String,Entry> entries;
        private Checkpoint(Map<String,Entry> entries) { this.entries=new LinkedHashMap<String,Entry>(entries); }
    }
    public synchronized Checkpoint checkpoint() { return new Checkpoint(entries); }
    public synchronized void restore(Checkpoint checkpoint) {
        if(checkpoint==null)throw new IllegalArgumentException("A scheduled service checkpoint is required.");
        entries.clear(); entries.putAll(checkpoint.entries);
    }
    public static boolean scheduled(Train train) { return train!=null&&train.automated&&train.route.size()>=2&&!train.carIds.isEmpty(); }

    /** Call only for an explicit, successfully applied operator start/stop/change. */
    public synchronized void record(Train train,boolean running) {
        if(train==null)throw new IllegalArgumentException("A registered train is required.");
        if(running&&scheduled(train)) entries.put(train.name,new Entry(train));
        else entries.remove(train.name);
    }
    public synchronized boolean runningIntent(Train train) {
        Entry entry=train==null?null:entries.get(train.name); return entry!=null&&entry.matches(train);
    }
    public synchronized int size() { return entries.size(); }

    /** Progress is refreshed without inferring intent from transient power, including during shutdown. */
    public void refresh(RailNetwork network) {
        synchronized(network) { synchronized(this) {
            for(Iterator<Map.Entry<String,Entry>> i=entries.entrySet().iterator();i.hasNext();) {
                Map.Entry<String,Entry> saved=i.next(); Train train=network.trains.get(saved.getKey());
                if(!saved.getValue().matches(train))i.remove(); else saved.setValue(new Entry(train));
            }
        } }
    }

    /** Query only. Native world validation and safe dispatcher startup must precede actual starting. */
    public List<String> resumeCandidates(RailNetwork network) {
        synchronized(network) { synchronized(this) {
            List<String> result=new ArrayList<String>();
            for(Entry entry:entries.values())if(entry.matches(network.trains.get(entry.name)))result.add(entry.name);
            return Collections.unmodifiableList(result);
        } }
    }
    /** Restore dwell only when the checked rail file agrees on the current route cursor. */
    public synchronized boolean restoreProgress(Train train) {
        Entry entry=train==null?null:entries.get(train.name);
        if(entry==null||!entry.matches(train)||entry.routeIndex!=train.routeIndex||train.poses.isEmpty()
                ||entry.x!=train.poses.get(0).x||entry.y!=train.poses.get(0).y||entry.layer!=train.poses.get(0).layer)return false;
        train.dwellRemaining=entry.dwell; return true;
    }

    public static ScheduledServices load(Path file) throws IOException {
        if(!Files.exists(file)) {
            if(Files.exists(backup(file)))throw new IOException("Scheduled service state is missing but its backup exists. Automatic restart is disabled until the checked state is restored.");
            return new ScheduledServices();
        }
        return read(file);
    }
    private static ScheduledServices read(Path file) throws IOException {
        try {
            Properties p=new Properties();try(InputStream in=Files.newInputStream(file)){p.load(in);}
            if(!"1".equals(p.getProperty("format"))||!checksum(p).equals(p.getProperty("checksum")))
                throw new IOException("Scheduled service checksum/version is invalid: "+file);
            int count=Integer.parseInt(required(p,"services"));
            if(count<0||count>100000)throw new IllegalArgumentException("Invalid scheduled service count.");
            ScheduledServices services=new ScheduledServices();
            Set<Long> locomotives=new HashSet<Long>();
            for(int i=0;i<count;i++) {
                String k="service."+i+".";
                Entry entry=new Entry(required(p,k+"name"),Long.parseLong(required(p,k+"locomotive")),Long.parseLong(required(p,k+"owner")),
                        Arrays.asList(required(p,k+"route").split(",",-1)),Integer.parseInt(required(p,k+"routeIndex")),Double.parseDouble(required(p,k+"dwell")),
                        Float.parseFloat(required(p,k+"x")),Float.parseFloat(required(p,k+"y")),Integer.parseInt(required(p,k+"layer")));
                if(services.entries.put(entry.name,entry)!=null||!locomotives.add(entry.locomotive))
                    throw new IllegalArgumentException("Duplicate scheduled train or locomotive.");
            }
            // Reject hidden rows as well as incomplete ones; the checksum alone is not a schema check.
            if(p.size()!=3+count*9)throw new IllegalArgumentException("Unexpected scheduled service state fields.");
            return services;
        } catch(IllegalArgumentException failure) { throw new IOException("Invalid scheduled service state: "+file,failure); }
    }

    public void save(Path file,RailNetwork network) throws IOException {
        refresh(network);
        final Properties p=new Properties();
        synchronized(this) {
            p.setProperty("format","1"); p.setProperty("services",Integer.toString(entries.size())); int i=0;
            for(Entry entry:entries.values()) {
                String k="service."+(i++)+".";
                p.setProperty(k+"name",entry.name);p.setProperty(k+"locomotive",Long.toString(entry.locomotive));p.setProperty(k+"owner",Long.toString(entry.owner));
                p.setProperty(k+"route",String.join(",",entry.route));p.setProperty(k+"routeIndex",Integer.toString(entry.routeIndex));p.setProperty(k+"dwell",Double.toString(entry.dwell));
                p.setProperty(k+"x",Float.toString(entry.x));p.setProperty(k+"y",Float.toString(entry.y));p.setProperty(k+"layer",Integer.toString(entry.layer));
            }
        }
        p.setProperty("checksum",checksum(p));
        Path absolute=file.toAbsolutePath(); Files.createDirectories(absolute.getParent());
        Path temporary=Files.createTempFile(absolute.getParent(),"services-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temporary)){p.store(out,"WurmRail scheduled service run intent");}
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            if(Files.exists(absolute)) {
                boolean valid=true;try {read(absolute);}catch(IOException invalid){valid=false;}
                if(valid)Files.copy(absolute,backup(absolute),StandardCopyOption.REPLACE_EXISTING);
            }
            try {Files.move(temporary,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,absolute,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
    private static Path backup(Path file) { return file.resolveSibling(file.getFileName()+".bak"); }
    private static String required(Properties p,String key) {
        String value=p.getProperty(key);if(value==null)throw new IllegalArgumentException("Missing "+key);return value;
    }
    private static String checksum(Properties p) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<String>(p.stringPropertyNames()))if(!key.equals("checksum"))
                digest.update((key.length()+":"+key+p.getProperty(key).length()+":"+p.getProperty(key)+"\n").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
