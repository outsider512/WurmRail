package org.wurmrail.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Combined passenger stations and personal destinations, separate from routes and native seat ownership. */
public final class PassengerStations {
    /** Old values remain readable and source compatible; every platform now serves both purposes. */
    public enum Mode { PICKUP, DROPOFF, STATION }
    public static final class Platform {
        public final long itemId, ownerId, revision;
        public final int x,y,layer;
        public final String rail,name;
        public final Mode mode;
        public Platform(long itemId,long ownerId,long revision,int x,int y,int layer,String rail,String name,Mode mode) {
            if(itemId<=0||revision<1||x<0||y<0||(layer!=0&&layer!=-1)||mode==null
                    ||rail==null||!rail.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid passenger station.");
            this.itemId=itemId; this.ownerId=ownerId; this.revision=revision; this.x=x; this.y=y; this.layer=layer;
            this.rail=rail; this.name=label(name); this.mode=Mode.STATION;
        }
        public float worldX() { return x*4f+2; }
        public float worldY() { return y*4f+2; }
    }
    private final Map<Long,Platform> data=new LinkedHashMap<Long,Platform>();
    public final Map<Long,Platform> platforms=Collections.unmodifiableMap(data);
    private final Map<Long,Long> destinations=new LinkedHashMap<Long,Long>();
    // A disconnect or server restart is not evidence that a player left the station area.
    private final Map<Long,Set<Long>> recentArrivals=new LinkedHashMap<Long,Set<Long>>();
    public long destination(long player) { Long id=destinations.get(player); return id==null?-10L:id; }
    public void choose(long player,long platform) {
        if(player<=0) throw new IllegalArgumentException("A player is required.");
        if(platform==-10L) { destinations.remove(player); return; }
        Platform target=data.get(platform);
        if(target==null) throw new IllegalArgumentException("That station is no longer available.");
        destinations.put(player,platform);
    }
    /** Protect an automatically dropped-off player from boarding again until observed outside this station. */
    public void markDropped(long player,long station) {
        if(player<=0) throw new IllegalArgumentException("A player is required.");
        get(station);
        Set<Long> arrivals=recentArrivals.get(player);
        if(arrivals==null) { arrivals=new LinkedHashSet<Long>(); recentArrivals.put(player,arrivals); }
        arrivals.add(station);
    }
    public boolean recentlyDropped(long player,long station) {
        Set<Long> arrivals=recentArrivals.get(player); return arrivals!=null&&arrivals.contains(station);
    }
    /** Call only after observing this player outside the station area. Returns whether durable state changed. */
    public boolean clearArrival(long player,long station) {
        Set<Long> arrivals=recentArrivals.get(player);
        if(arrivals==null||!arrivals.remove(station)) return false;
        if(arrivals.isEmpty()) recentArrivals.remove(player);
        return true;
    }
    /** Immutable snapshot for checking which protected station areas this online player has physically left. */
    public Set<Long> recentStations(long player) {
        Set<Long> arrivals=recentArrivals.get(player);
        return arrivals==null?Collections.<Long>emptySet():Collections.unmodifiableSet(new LinkedHashSet<Long>(arrivals));
    }
    public void add(Platform p) {
        if(data.containsKey(p.itemId)) throw new IllegalArgumentException("This station already exists.");
        for(Platform old:data.values()) if(old.x==p.x&&old.y==p.y&&old.layer==p.layer)
            throw new IllegalArgumentException("A station already occupies this tile.");
        for(Platform old:data.values()) if(old.name.equalsIgnoreCase(p.name))
            throw new IllegalArgumentException("Choose a different station name.");
        data.put(p.itemId,p);
    }
    public Platform rename(long id,String name) {
        Platform p=get(id); String checked=label(name);
        for(Platform other:data.values()) if(other.itemId!=id&&other.name.equalsIgnoreCase(checked))
            throw new IllegalArgumentException("Choose a different station name.");
        Platform updated=new Platform(id,p.ownerId,p.revision+1,p.x,p.y,p.layer,p.rail,checked,p.mode);
        data.put(id,updated); return updated;
    }
    public Platform get(long id) {
        Platform p=data.get(id); if(p==null) throw new IllegalArgumentException("This station is no longer available."); return p;
    }
    public Platform at(int x,int y,int layer) {
        for(Platform p:data.values()) if(p.x==x&&p.y==y&&p.layer==layer) return p; return null;
    }
    public List<Long> remove(long id) {
        get(id); data.remove(id); List<Long> cleared=new ArrayList<Long>();
        for(Iterator<Map.Entry<Long,Long>> it=destinations.entrySet().iterator();it.hasNext();) {
            Map.Entry<Long,Long> trip=it.next(); if(trip.getValue()==id) { cleared.add(trip.getKey()); it.remove(); }
        }
        for(Iterator<Map.Entry<Long,Set<Long>>> it=recentArrivals.entrySet().iterator();it.hasNext();) {
            Set<Long> arrivals=it.next().getValue(); arrivals.remove(id); if(arrivals.isEmpty()) it.remove();
        }
        return cleared;
    }
    public PassengerStations copy() {
        PassengerStations copy=new PassengerStations(); copy.data.putAll(data); copy.destinations.putAll(destinations);
        for(Map.Entry<Long,Set<Long>> arrivals:recentArrivals.entrySet())
            copy.recentArrivals.put(arrivals.getKey(),new LinkedHashSet<Long>(arrivals.getValue()));
        return copy;
    }
    public static String label(String text) {
        String value=text==null?"":text.trim();
        if(value.isEmpty()||value.length()>48) throw new IllegalArgumentException("Station names must contain 1 to 48 characters.");
        for(int i=0;i<value.length();i++) if(Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("Station names must be on one line.");
        return value;
    }
    public static PassengerStations load(Path file) throws IOException {
        if(!Files.exists(file)) {
            if(Files.exists(backup(file))) return read(backup(file));
            return new PassengerStations();
        }
        try { return read(file); }
        catch(IOException original) {
            if(Files.exists(backup(file))) try { return read(backup(file)); } catch(IOException failed) { original.addSuppressed(failed); }
            throw original;
        }
    }
    private static PassengerStations read(Path file) throws IOException {
        try {
            Properties p=new Properties(); try(InputStream in=Files.newInputStream(file)) { p.load(in); }
            String format=p.getProperty("format");
            if((!"1".equals(format)&&!"2".equals(format))||!checksum(p).equals(p.getProperty("checksum"))) throw new IOException("Passenger station state checksum/version is invalid: "+file);
            PassengerStations result=new PassengerStations();
            int count=count(p,"platforms",10000);
            for(int i=0;i<count;i++) {
                String k="p."+i+".";
                result.add(new Platform(Long.parseLong(required(p,k+"id")),Long.parseLong(required(p,k+"owner")),Long.parseLong(required(p,k+"revision")),
                        Integer.parseInt(required(p,k+"x")),Integer.parseInt(required(p,k+"y")),Integer.parseInt(required(p,k+"layer")),
                        required(p,k+"rail"),required(p,k+"name"),Mode.valueOf(required(p,k+"mode"))));
            }
            int trips=count(p,"journeys",100000);
            for(int i=0;i<trips;i++) result.choose(Long.parseLong(required(p,"j."+i+".player")),Long.parseLong(required(p,"j."+i+".destination")));
            // Earlier format 2 files had no durable arrival rows. Their absence means no protections.
            int arrivals=p.getProperty("arrivals")==null?0:count(p,"arrivals",100000);
            if(p.getProperty("arrivals")==null)for(String key:p.stringPropertyNames())if(key.startsWith("a."))
                throw new IllegalArgumentException("Missing arrivals count.");
            for(int i=0;i<arrivals;i++) {
                long player=Long.parseLong(required(p,"a."+i+".player")),station=Long.parseLong(required(p,"a."+i+".station"));
                if(result.recentlyDropped(player,station)) throw new IllegalArgumentException("Duplicate station arrival.");
                result.markDropped(player,station);
            }
            return result;
        } catch(IllegalArgumentException failure) { throw new IOException("Invalid passenger station state: "+file,failure); }
    }
    public void save(Path file) throws IOException {
        Properties p=new Properties(); p.setProperty("format","2"); p.setProperty("platforms",Integer.toString(data.size())); int i=0;
        for(Platform v:data.values()) {
            String k="p."+(i++)+".";
            p.setProperty(k+"id",Long.toString(v.itemId)); p.setProperty(k+"owner",Long.toString(v.ownerId)); p.setProperty(k+"revision",Long.toString(v.revision));
            p.setProperty(k+"x",Integer.toString(v.x)); p.setProperty(k+"y",Integer.toString(v.y)); p.setProperty(k+"layer",Integer.toString(v.layer));
            p.setProperty(k+"rail",v.rail); p.setProperty(k+"name",v.name); p.setProperty(k+"mode",v.mode.name());
        }
        p.setProperty("journeys",Integer.toString(destinations.size())); i=0;
        for(Map.Entry<Long,Long> trip:destinations.entrySet()) {
            p.setProperty("j."+i+".player",Long.toString(trip.getKey())); p.setProperty("j."+i+".destination",Long.toString(trip.getValue())); i++;
        }
        i=0;
        for(Map.Entry<Long,Set<Long>> player:recentArrivals.entrySet())for(Long station:player.getValue()) {
            p.setProperty("a."+i+".player",Long.toString(player.getKey())); p.setProperty("a."+i+".station",Long.toString(station)); i++;
        }
        p.setProperty("arrivals",Integer.toString(i));
        p.setProperty("checksum",checksum(p));
        Files.createDirectories(file.toAbsolutePath().getParent()); Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"passengers-",".tmp");
        try {
            try(OutputStream out=Files.newOutputStream(temp)) { p.store(out,"WurmRail stations and personal destinations"); }
            if(Files.exists(file)) {
                boolean valid=true; try { read(file); } catch(IOException invalid) { valid=false; }
                if(valid) Files.copy(file,backup(file),StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static Path backup(Path p) { return p.resolveSibling(p.getFileName()+".bak"); }
    private static String required(Properties p,String key) { String value=p.getProperty(key); if(value==null) throw new IllegalArgumentException("Missing "+key); return value; }
    private static int count(Properties p,String key,int max) { int n=Integer.parseInt(required(p,key)); if(n<0||n>max) throw new IllegalArgumentException("Invalid "+key); return n; }
    private static String checksum(Properties p) {
        try {
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            for(String key:new TreeSet<String>(p.stringPropertyNames())) if(!key.equals("checksum")) {
                sha.update((key.length()+":"+key+p.getProperty(key).length()+":"+p.getProperty(key)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            StringBuilder hex=new StringBuilder(); for(byte b:sha.digest()) hex.append(String.format(Locale.ROOT,"%02x",b&255)); return hex.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
