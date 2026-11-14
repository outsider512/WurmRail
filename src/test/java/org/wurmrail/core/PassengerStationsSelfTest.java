package org.wurmrail.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.wurmrail.core.PassengerStations.Mode;
import org.wurmrail.core.PassengerStations.Platform;

/** Dependency-free platform, destination and durable-state regression checks. */
public final class PassengerStationsSelfTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        namesAndCoordinates(); destinationsAndCopies(); recentArrivalState(); persistence(); legacyMigration(); recentArrivalPersistence();
        System.out.println("PassengerStationsSelfTest: "+assertions+" assertions passed.");
    }

    private static void namesAndCoordinates() {
        Platform origin=platform(101,0,0,0,"Home",Mode.PICKUP);
        check(origin.mode==Mode.STATION,"legacy pickup construction produces a combined station");
        check(origin.worldX()==2&&origin.worldY()==2,"tile origin maps to its world centre");
        Platform cave=platform(102,10,11,-1,"Mine",Mode.DROPOFF);
        check(cave.mode==Mode.STATION,"legacy drop-off construction produces a combined station");
        check(platform(105,1,2,0,"New Station",Mode.STATION).mode==Mode.STATION,"new stations use the combined mode");
        check(cave.worldX()==42&&cave.worldY()==46,"platform world positions use four metre tiles");
        check(PassengerStations.label("  Mountain Express  ").equals("Mountain Express"),"names trim surrounding whitespace and retain internal spaces");
        String maximum=String.join("",Collections.nCopies(48,"x"));
        check(PassengerStations.label(maximum).equals(maximum),"48 character platform names are valid");
        String punctuation="Dépôt, East; 'Goods' {2}";
        check(PassengerStations.label(punctuation).equals(punctuation),"display punctuation and Unicode are retained for safe UI rendering");
        for(String bad:new String[]{null,"","  ",maximum+"x","two\nlines","two\tcolumns","bad\u0000name"})
            invalid(() -> PassengerStations.label(bad),"invalid display name rejected");
        invalid(() -> platform(0,1,1,0,"No item",Mode.PICKUP),"item identity must be positive");
        invalid(() -> platform(-1,1,1,0,"No item",Mode.PICKUP),"negative item identity rejected");
        invalid(() -> platform(110,-1,1,0,"West of map",Mode.PICKUP),"negative tile X rejected");
        invalid(() -> platform(110,1,-1,0,"North of map",Mode.PICKUP),"negative tile Y rejected");
        invalid(() -> platform(110,1,1,1,"Wrong layer",Mode.PICKUP),"positive underground layer rejected");
        invalid(() -> platform(110,1,1,-2,"Wrong layer",Mode.PICKUP),"unsupported cave layer rejected");
        invalid(() -> platform(110,1,1,0,"No mode",null),"platform mode required");
        invalid(() -> new Platform(110,900,0,1,1,0,"rail","Bad revision",Mode.PICKUP),"revision zero rejected");
        invalid(() -> new Platform(110,900,1,1,1,0,null,"Missing rail",Mode.PICKUP),"linked rail required");
        invalid(() -> new Platform(110,900,1,1,1,0,"bad rail","Bad rail",Mode.PICKUP),"linked rail identity has the core name grammar");
        invalid(() -> new Platform(110,900,1,1,1,0,String.join("",Collections.nCopies(41,"x")),"Long rail",Mode.PICKUP),"rail name upper bound enforced");

        PassengerStations stations=new PassengerStations(); stations.add(origin); stations.add(cave);
        check(stations.at(0,0,0)==origin&&stations.at(10,11,-1)==cave,"coordinate lookup preserves layer");
        check(stations.at(10,11,0)==null,"surface lookup does not select cave platform");
        invalid(() -> stations.add(platform(101,3,3,0,"Another identity",Mode.PICKUP)),"duplicate native item identity rejected");
        invalid(() -> stations.add(platform(103,0,0,0,"Duplicate tile",Mode.PICKUP)),"two platforms cannot share a tile on one layer");
        invalid(() -> stations.add(platform(103,4,4,0,"hOmE",Mode.PICKUP)),"platform names are globally unique without case distinctions");
        stations.add(platform(104,0,0,-1,"Cave under home",Mode.PICKUP));
        check(stations.platforms.size()==3,"same horizontal tile on another layer is valid");
        Platform renamed=stations.rename(101,"  Home Platform  ");
        check(renamed.revision==2&&renamed.name.equals("Home Platform"),"renaming produces a new revision and trimmed display name");
        check(renamed.itemId==origin.itemId&&renamed.ownerId==origin.ownerId&&renamed.rail.equals(origin.rail)
                &&renamed.x==origin.x&&renamed.y==origin.y&&renamed.layer==origin.layer&&renamed.mode==origin.mode,
                "rename preserves native item, ownership, linked rail and geometry");
        check(origin.name.equals("Home")&&origin.revision==1,"previous immutable platform snapshot is unchanged by rename");
        invalid(() -> stations.rename(101,"MINE"),"rename cannot duplicate another platform name");
        check(stations.get(101)==renamed,"failed rename leaves the last good platform intact");
        invalid(() -> stations.get(999),"missing platform lookup is rejected");
        try { stations.platforms.clear(); throw new AssertionError("platform map allowed outside mutation"); }
        catch(UnsupportedOperationException expected) { assertions++; }
    }

    private static void destinationsAndCopies() {
        PassengerStations stations=new PassengerStations();
        stations.add(platform(201,5,5,0,"Pickup",Mode.PICKUP));
        stations.add(platform(202,6,5,0,"Drop-off",Mode.DROPOFF));
        stations.add(platform(203,7,5,0,"Other drop-off",Mode.DROPOFF));
        check(stations.destination(1001)==-10L,"a player without a selection has no automatic destination");
        invalid(() -> stations.choose(0,202),"destination selection requires a positive player identity");
        invalid(() -> stations.choose(-10,202),"invalid player cannot clear or select a journey");
        stations.choose(1001,201);
        check(stations.destination(1001)==201,"a former pickup platform can now be selected as a destination");
        invalid(() -> stations.choose(1001,999),"missing drop-off cannot be selected");
        stations.choose(1001,202); stations.choose(1002,202); stations.choose(1003,203);
        check(stations.destination(1001)==202&&stations.destination(1003)==203,"personal destinations are independent");
        stations.choose(1001,-10L); check(stations.destination(1001)==-10L,"player may clear a destination explicitly");
        stations.choose(1001,202);
        PassengerStations copy=stations.copy();
        copy.rename(202,"Renamed copy"); copy.choose(1001,203); copy.add(platform(204,8,5,0,"Only in copy",Mode.PICKUP));
        check(stations.get(202).name.equals("Drop-off")&&stations.destination(1001)==202&&!stations.platforms.containsKey(204L),
                "copy changes do not affect original platform names, destinations or membership");
        stations.rename(203,"Original only");
        check(copy.get(203).name.equals("Other drop-off"),"original changes do not affect a copied platform snapshot");
        List<Long> cleared=stations.remove(202);
        check(new HashSet<Long>(cleared).equals(new HashSet<Long>(Arrays.asList(1001L,1002L))),"removing a drop-off identifies every affected waiting rider");
        check(stations.destination(1001)==-10L&&stations.destination(1002)==-10L,"removed drop-off clears all destinations to that platform");
        check(stations.destination(1003)==203,"removing one platform leaves other journeys intact");
        check(copy.platforms.containsKey(202L)&&copy.destination(1002)==202,"original removal leaves copied platform and trips intact");
        invalid(() -> stations.choose(1001,202),"removed destination cannot be selected again");
        invalid(() -> stations.remove(202),"removing a stale platform identity is rejected");
        check(stations.remove(201).isEmpty(),"removing a pickup does not clear unrelated journeys");
    }

    private static void persistence() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-passengers-test-").toAbsolutePath();
        Path file=directory.resolve("passengers.properties"), backup=directory.resolve("passengers.properties.bak");
        try {
            check(PassengerStations.load(file).platforms.isEmpty(),"a new world starts with no passenger platforms");
            PassengerStations stations=new PassengerStations();
            Platform pickup=platform(301,12,13,0,"Home pickup",Mode.PICKUP);
            Platform dropoff=new Platform(302,901,7,20,21,-1,"caveRail_21","Dépôt, East",Mode.DROPOFF);
            stations.add(pickup); stations.add(dropoff); stations.choose(1101,302); stations.choose(1102,302);
            stations.save(file);
            PassengerStations restored=PassengerStations.load(file);
            check(restored.platforms.size()==2,"all platforms survive round-trip persistence");
            Platform read=restored.get(302);
            check(read.itemId==302&&read.ownerId==901&&read.revision==7&&read.x==20&&read.y==21&&read.layer==-1
                    &&read.rail.equals("caveRail_21")&&read.name.equals("Dépôt, East")&&read.mode==Mode.STATION,
                    "round trip preserves item identity, owner, revision, cave coordinates, rail, Unicode name and combined mode");
            check(restored.destination(1101)==302&&restored.destination(1102)==302,"personal destination selections survive round trip");
            stations.rename(302,"Current drop-off"); stations.save(file);
            check(Files.isRegularFile(backup),"second save retains the previous valid passenger state");
            check(PassengerStations.load(file).get(302).name.equals("Current drop-off"),"latest valid primary is preferred");
            String primary=new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1);
            Files.write(file,primary.replace("Current drop-off","Tampered platform").getBytes(StandardCharsets.ISO_8859_1));
            check(PassengerStations.load(file).get(302).name.equals("Dépôt, East"),"checksum tampering recovers the previous valid backup");
            byte[] preservedBackup=Files.readAllBytes(backup);
            stations.save(file);
            check(Arrays.equals(Files.readAllBytes(backup),preservedBackup),"repairing a corrupt primary never replaces the good backup with corrupt bytes");
            check(PassengerStations.load(file).get(302).name.equals("Current drop-off"),"explicit save repairs the corrupt primary with current in-memory state");
            Files.delete(file);
            check(PassengerStations.load(file).get(302).name.equals("Dépôt, East"),"missing primary recovers from a valid backup");
            Files.write(file,"format=1\nplatforms=0\njourneys=0\nchecksum=invalid\n".getBytes(StandardCharsets.ISO_8859_1));
            Files.write(backup,"not a valid backup".getBytes(StandardCharsets.ISO_8859_1));
            try { PassengerStations.load(file); throw new AssertionError("corrupt primary and backup were accepted"); }
            catch(IOException expected) { check(expected.getSuppressed().length==1,"failed backup read is retained as diagnostic evidence"); }
            Files.delete(backup);
            ioInvalid(() -> PassengerStations.load(file),"corrupt primary cannot silently become an empty platform network");
            stations.remove(302); stations.save(file);
            PassengerStations removed=PassengerStations.load(file);
            check(!removed.platforms.containsKey(302L)&&removed.destination(1101)==-10L&&removed.destination(1102)==-10L,
                    "removed destination and cleared personal journeys remain removed after restart");
            try(java.nio.file.DirectoryStream<Path> temps=Files.newDirectoryStream(directory,"passengers-*.tmp")) {
                check(!temps.iterator().hasNext(),"successful state saves leave no abandoned temporary files");
            }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                Iterator<Path> deepest=paths.sorted(Comparator.reverseOrder()).iterator();
                while(deepest.hasNext()) Files.delete(deepest.next());
            }
        }
    }

    private static void recentArrivalState() {
        PassengerStations stations=new PassengerStations();
        stations.add(platform(501,30,40,0,"Arrival home",Mode.STATION));
        stations.add(platform(502,31,40,0,"Arrival mine",Mode.STATION));
        stations.choose(2001,502);
        check(!stations.recentlyDropped(2001,501)&&stations.recentStations(2001).isEmpty(),"players begin without recent station arrival protections");
        invalid(() -> stations.markDropped(0,501),"recent arrivals require a positive player identity");
        invalid(() -> stations.markDropped(-10L,501),"invalid player cannot gain arrival protection");
        invalid(() -> stations.markDropped(2001,999),"arrival protection cannot reference a missing station");
        stations.markDropped(2001,501);stations.markDropped(2001,502);stations.markDropped(2002,501);stations.markDropped(2001,501);
        check(stations.recentlyDropped(2001,501)&&stations.recentlyDropped(2001,502)&&stations.recentlyDropped(2002,501),
                "one player can retain protection at multiple stations independently of another player");
        check(stations.recentStations(2001).size()==2,"marking the same arrival twice is idempotent");
        Set<Long> snapshot=stations.recentStations(2001);
        try { snapshot.clear();throw new AssertionError("recent station snapshot allowed outside mutation"); }
        catch(UnsupportedOperationException expected) { assertions++; }
        check(stations.clearArrival(2001,501)&&!stations.recentlyDropped(2001,501)&&stations.recentlyDropped(2001,502)
                &&stations.recentlyDropped(2002,501),"observing one player leave one station clears only that player and station pair");
        check(!stations.clearArrival(2001,501)&&!stations.clearArrival(999,501),"clearing absent arrival protection reports no durable change");
        check(snapshot.contains(501L)&&snapshot.contains(502L),"arrival snapshots remain unchanged after later state changes");
        check(stations.destination(2001)==502,"arrival marking and clearing never changes the player's selected destination");
        PassengerStations copy=stations.copy();copy.clearArrival(2001,502);copy.markDropped(2003,502);
        check(stations.recentlyDropped(2001,502)&&!stations.recentlyDropped(2003,502),"a copied state's arrival changes never mutate the original");
        stations.clearArrival(2002,501);
        check(copy.recentlyDropped(2002,501),"original arrival changes do not mutate a copied state");
        stations.markDropped(2002,501);stations.rename(501,"Arrival home renamed");
        check(stations.recentlyDropped(2002,501),"renaming a station preserves arrival protection by stable station identity");
        stations.remove(501);
        check(stations.recentStations(2002).isEmpty()&&!stations.recentlyDropped(2002,501)&&stations.recentlyDropped(2001,502),
                "removing a station clears only protections for its identity across all players");
        check(copy.recentlyDropped(2002,501),"station removal leaves copied arrival state unchanged");
        stations.remove(502);
        check(stations.recentStations(2001).isEmpty()&&stations.destination(2001)==-10L,"removing a player's last station clears arrival and selected destination together");
    }

    private static void legacyMigration() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-station-migration-").toAbsolutePath();
        Path file=directory.resolve("passengers.properties"), backup=directory.resolve("passengers.properties.bak");
        try {
            Properties legacy=new Properties();
            legacy.setProperty("format","1"); legacy.setProperty("platforms","2"); legacy.setProperty("journeys","2");
            savedPlatform(legacy,0,401,950,11,5,6,0,"Home_5","Home Pickup","PICKUP");
            savedPlatform(legacy,1,402,951,19,17,18,-1,"Mine-17","Mine Drop-off","DROPOFF");
            legacy.setProperty("j.0.player","1201"); legacy.setProperty("j.0.destination","402");
            legacy.setProperty("j.1.player","1202"); legacy.setProperty("j.1.destination","402");
            writeSigned(file,legacy);
            byte[] legacyBytes=Files.readAllBytes(file);
            PassengerStations migrated=PassengerStations.load(file);
            check(Arrays.equals(legacyBytes,Files.readAllBytes(file)),"loading legacy stations never rewrites the world file");
            check(migrated.platforms.size()==2&&migrated.get(401).mode==Mode.STATION&&migrated.get(402).mode==Mode.STATION,
                    "legacy pickup and drop-off stations both migrate to combined stations");
            check(migrated.recentStations(1201).isEmpty()&&migrated.recentStations(1202).isEmpty(),"legacy format 1 loads without inventing recent arrival protection");
            Platform home=migrated.get(401), mine=migrated.get(402);
            check(home.itemId==401&&home.ownerId==950&&home.revision==11&&home.x==5&&home.y==6&&home.layer==0
                    &&home.rail.equals("Home_5")&&home.name.equals("Home Pickup"),"legacy pickup migration preserves every identity and location field");
            check(mine.itemId==402&&mine.ownerId==951&&mine.revision==19&&mine.x==17&&mine.y==18&&mine.layer==-1
                    &&mine.rail.equals("Mine-17")&&mine.name.equals("Mine Drop-off"),"legacy drop-off migration preserves every identity and location field");
            check(migrated.destination(1201)==402&&migrated.destination(1202)==402,"migration preserves multiple players' existing drop-off destinations");
            migrated.choose(1203,401); migrated.choose(1201,401);
            check(migrated.destination(1203)==401&&migrated.destination(1201)==401&&migrated.destination(1202)==402,
                    "former pickup stations accept destinations independently for each player");
            PassengerStations snapshot=migrated.copy();
            migrated.rename(401,"Home Station");
            check(migrated.get(401).revision==12&&snapshot.get(401)==home&&home.name.equals("Home Pickup"),
                    "renaming a migrated station increments revision and preserves immutable copied snapshots");
            migrated.save(file);
            check(Arrays.equals(legacyBytes,Files.readAllBytes(backup)),"the first migration save backs up the exact valid legacy state");
            Properties current=readProperties(file);
            check(current.getProperty("format").equals("2")&&current.getProperty("p.0.mode").equals("STATION")
                    &&current.getProperty("p.1.mode").equals("STATION"),"migration saves format 2 with only combined station modes");
            PassengerStations restored=PassengerStations.load(file);
            check(restored.get(401).itemId==401&&restored.get(401).ownerId==950&&restored.get(401).revision==12
                    &&restored.get(401).rail.equals("Home_5")&&restored.get(401).name.equals("Home Station"),
                    "migrated identity, owner, linked rail, revision and name survive format 2 reload");
            check(restored.destination(1201)==401&&restored.destination(1202)==402&&restored.destination(1203)==401,
                    "all old and new personal destinations survive format 2 reload");
            migrated.choose(1201,-10L);
            check(migrated.destination(1201)==-10L&&migrated.destination(1202)==402&&migrated.destination(1203)==401,
                    "clearing one player's migrated destination leaves all other selections unchanged");
            List<Long> cleared=migrated.remove(401);
            check(cleared.equals(Collections.singletonList(1203L))&&migrated.destination(1202)==402,
                    "removing a combined station clears only players who still selected that station");
            migrated.save(file);
            check(!PassengerStations.load(file).platforms.containsKey(401L)&&PassengerStations.load(file).destination(1202)==402,
                    "migrated station removal and unrelated journeys remain correct after restart");

            Files.delete(backup);
            for(String format:Arrays.asList("1","2")) {
                Properties invalidMode=new Properties(); invalidMode.putAll(legacy);
                invalidMode.setProperty("format",format); invalidMode.setProperty("p.0.mode","UNKNOWN");
                writeSigned(file,invalidMode);
                ioInvalid(() -> PassengerStations.load(file),"a checksummed unknown mode fails closed in format "+format);
            }
            Properties invalidVersion=new Properties(); invalidVersion.putAll(legacy); invalidVersion.setProperty("format","3");
            writeSigned(file,invalidVersion);
            ioInvalid(() -> PassengerStations.load(file),"an unsupported checksummed station format fails closed");
            Files.write(backup,legacyBytes);
            PassengerStations recovered=PassengerStations.load(file);
            check(recovered.get(401).mode==Mode.STATION&&recovered.destination(1202)==402,
                    "an invalid newer primary safely recovers and migrates a valid legacy backup");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                Iterator<Path> deepest=paths.sorted(Comparator.reverseOrder()).iterator();
                while(deepest.hasNext()) Files.delete(deepest.next());
            }
        }
    }

    private static void recentArrivalPersistence() throws Exception {
        Path directory=Files.createTempDirectory("wurmrail-arrival-state-").toAbsolutePath();
        Path file=directory.resolve("passengers.properties"),backup=directory.resolve("passengers.properties.bak");
        try {
            PassengerStations stations=new PassengerStations();
            stations.add(platform(601,40,50,0,"Protected home",Mode.STATION));stations.add(platform(602,41,50,0,"Protected mine",Mode.STATION));
            stations.choose(3001,602);stations.markDropped(3001,601);stations.markDropped(3001,602);stations.markDropped(3002,601);
            stations.save(file);
            Properties valid=readProperties(file);
            check(valid.getProperty("format").equals("2")&&valid.getProperty("arrivals").equals("3"),"format 2 persists one durable row per player and station arrival pair");
            PassengerStations restored=PassengerStations.load(file);
            check(restored.recentlyDropped(3001,601)&&restored.recentlyDropped(3001,602)&&restored.recentlyDropped(3002,601)
                    &&restored.destination(3001)==602,"arrival protection survives a full reload for multiple players and stations without changing destinations");
            restored.clearArrival(3001,601);restored.save(file);
            PassengerStations left=PassengerStations.load(file);
            check(!left.recentlyDropped(3001,601)&&left.recentlyDropped(3001,602)&&left.recentlyDropped(3002,601),
                    "a physically cleared arrival remains cleared after restart while unrelated protections survive");
            byte[] goodBackup=Files.readAllBytes(backup);
            Properties tampered=readProperties(file);tampered.setProperty("a.0.station","999");
            try(OutputStream out=Files.newOutputStream(file)) { tampered.store(out,"Unsigned arrival tamper"); }
            PassengerStations recovered=PassengerStations.load(file);
            check(recovered.recentlyDropped(3001,601)&&recovered.recentlyDropped(3002,601),"arrival checksum tampering recovers the previous durable protections from backup");
            restored.save(file);
            check(Arrays.equals(goodBackup,Files.readAllBytes(backup)),"repairing an arrival-state primary never overwrites a valid backup with corrupt bytes");
            Files.delete(backup);

            Properties earlier=new Properties();earlier.putAll(valid);earlier.remove("arrivals");
            for(int i=0;i<3;i++) { earlier.remove("a."+i+".player");earlier.remove("a."+i+".station"); }
            writeSigned(file,earlier);
            PassengerStations earlierRead=PassengerStations.load(file);
            check(earlierRead.platforms.size()==2&&earlierRead.destination(3001)==602&&earlierRead.recentStations(3001).isEmpty(),
                    "earlier format 2 files with no arrivals field remain compatible");

            for(String problem:Arrays.asList("negative count","too many","nonnumeric count","missing player","invalid player","missing station","unknown station","duplicate pair","missing count")) {
                Properties invalid=new Properties();invalid.putAll(valid);
                if(problem.equals("negative count"))invalid.setProperty("arrivals","-1");
                else if(problem.equals("too many"))invalid.setProperty("arrivals","100001");
                else if(problem.equals("nonnumeric count"))invalid.setProperty("arrivals","three");
                else if(problem.equals("missing player"))invalid.remove("a.0.player");
                else if(problem.equals("invalid player"))invalid.setProperty("a.0.player","0");
                else if(problem.equals("missing station"))invalid.remove("a.0.station");
                else if(problem.equals("unknown station"))invalid.setProperty("a.0.station","999");
                else if(problem.equals("duplicate pair")) { invalid.setProperty("a.1.player",invalid.getProperty("a.0.player"));invalid.setProperty("a.1.station",invalid.getProperty("a.0.station")); }
                else invalid.remove("arrivals");
                writeSigned(file,invalid);
                ioInvalid(() -> PassengerStations.load(file),"checksummed malformed arrival state fails closed: "+problem);
            }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                Iterator<Path> deepest=paths.sorted(Comparator.reverseOrder()).iterator();
                while(deepest.hasNext()) Files.delete(deepest.next());
            }
        }
    }

    private static void savedPlatform(Properties p,int index,long id,long owner,long revision,int x,int y,int layer,String rail,String name,String mode) {
        String k="p."+index+".";
        p.setProperty(k+"id",Long.toString(id)); p.setProperty(k+"owner",Long.toString(owner)); p.setProperty(k+"revision",Long.toString(revision));
        p.setProperty(k+"x",Integer.toString(x)); p.setProperty(k+"y",Integer.toString(y)); p.setProperty(k+"layer",Integer.toString(layer));
        p.setProperty(k+"rail",rail); p.setProperty(k+"name",name); p.setProperty(k+"mode",mode);
    }
    private static Properties readProperties(Path file) throws IOException {
        Properties p=new Properties(); try(InputStream in=Files.newInputStream(file)) { p.load(in); } return p;
    }
    /** Legacy format's documented canonical checksum, used to create old-world and signed-invalid fixtures. */
    private static void writeSigned(Path file,Properties p) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        for(String key:new TreeSet<String>(p.stringPropertyNames())) if(!key.equals("checksum"))
            sha.update((key.length()+":"+key+p.getProperty(key).length()+":"+p.getProperty(key)+"\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(); for(byte b:sha.digest()) hex.append(String.format(Locale.ROOT,"%02x",b&255));
        p.setProperty("checksum",hex.toString());
        try(OutputStream out=Files.newOutputStream(file)) { p.store(out,"Legacy WurmRail passenger fixture"); }
    }

    private static Platform platform(long id,int x,int y,int layer,String name,Mode mode) {
        return new Platform(id,900,1,x,y,layer,"rail_"+x+"_"+y,name,mode);
    }
    private interface Checked { void run(); }
    private interface IoChecked { void run() throws IOException; }
    private static void invalid(Checked action,String message) {
        try { action.run(); } catch(IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void ioInvalid(IoChecked action,String message) throws IOException {
        try { action.run(); } catch(IOException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
}
