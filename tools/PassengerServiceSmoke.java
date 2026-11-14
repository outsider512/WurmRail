import com.wurmonline.server.creatures.Creature;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.wurmrail.RailwayPassengerService;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;

/** Deterministic stop/transfer orchestration; PassengerWorldSmoke separately exercises native boarding/exit. */
public final class PassengerServiceSmoke {
    private static int assertions;
    public static void run() throws Exception {
        RailNetwork network=line();
        PassengerStations stations=new PassengerStations();
        Platform pickup=new Platform(9001,91,1,5,11,0,"r5","Village pickup",Mode.PICKUP);
        Platform dropoff=new Platform(9002,91,1,10,11,0,"r10","Mine drop-off",Mode.DROPOFF);
        stations.add(pickup); stations.add(dropoff);
        Rider first=rider(91),second=rider(92); first.waiting=pickup.itemId; second.waiting=pickup.itemId;
        stations.choose(first.id,dropoff.itemId); stations.choose(second.id,dropoff.itemId);
        FakeWorld world=new FakeWorld(first,second); world.capacity.put(101L,0); world.capacity.put(102L,2);
        Train train=network.createTrain("PassengerTest",91,Arrays.asList(101L,102L),"r3","r4");
        RailwayPassengerService service=new RailwayPassengerService(network,()->stations,world);
        network.passengerStops=service::shouldStop; service.refreshPlayers(); network.start(train.name);
        for(int i=0;i<1000&&train.passengerStopRail==null;i++)network.tick(.1);
        require("r5".equals(train.passengerStopRail)&&train.passengerStopCarId==102L,"full engine is skipped; free attached wagon aligns at pickup");
        require(world.boards==0&&first.vehicle==-10L,"core simulation never performs native boarding before world commit");
        double x=train.poses.get(1).x;
        network.tick(10);
        require(train.poses.get(1).x==x&&train.speed==0,"entire train remains held pending native service");
        service.serviceStops();
        require(world.boards==2&&first.vehicle==102L&&second.vehicle==102L,"all waiting riders use available seats on stopped wagon");
        require(train.passengerStopRail==null&&train.powered,"pickup releases hold and preserves power");
        for(int i=0;i<1000&&train.passengerStopRail==null;i++)network.tick(.1);
        require("r10".equals(train.passengerStopRail)&&train.passengerStopCarId==102L,"selected drop-off stops the actual rider wagon");
        world.blockDrop=true; service.serviceStops();
        require(first.vehicle==102L&&stations.destination(first.id)==dropoff.itemId,"blocked landing retains passenger and destination");
        require(train.powered&&train.passengerStopRail==null,"blocked transfer does not strand the train or clear its power");
        network.stop(train.name); world.blockDrop=false;
        service.serviceStops();
        require(first.vehicle==-10L&&second.vehicle==-10L&&world.drops==2,"parked train retries safe drop-off and releases both riders");
        require(stations.destination(first.id)==-10L&&stations.destination(second.id)==-10L&&service.dirty(),"successful native exit clears only completed destinations and marks persistence dirty");
        require(!train.powered,"operator STOP is never undone by automatic transfers");
        service.saved(); require(!service.dirty(),"dirty state is acknowledged only after saving");

        RailNetwork parked=line(); PassengerStations waiting=new PassengerStations();
        Platform pad=new Platform(9101,91,1,3,11,0,"r3","Parked pickup",Mode.PICKUP); waiting.add(pad);
        Rider later=rider(93); later.waiting=pad.itemId;
        FakeWorld pw=new FakeWorld(later); pw.capacity.put(201L,1);
        Train pt=parked.createTrain("Parked",91,Collections.singletonList(201L),"r3","r4");
        RailwayPassengerService ps=new RailwayPassengerService(parked,()->waiting,pw); ps.refreshPlayers(); ps.serviceStops();
        require(later.vehicle==201L&&!pt.powered,"a later arrival can use a parked train without starting it");
        require(!ps.shouldStop(pt,parked.nodes.get("r3"),201L),"already boarded passenger is not collected twice");
        require(!ps.isWaiting(later.id,pad)&&ps.waitingCount(pad)==0,"boarding immediately clears waiting state and count");
        combinedStation();
        adjacentWaitingBadges();
        durableArrivalProtection();
        System.out.println("PASS passenger service combined stations, independent waiting and arrival protection, full-engine/rear-wagon pickup, blocked exits, parked arrivals and STOP precedence ("+assertions+" assertions)");
    }
    private static void adjacentWaitingBadges() throws Exception {
        RailNetwork network=line(); PassengerStations stations=new PassengerStations();
        Platform next=new Platform(9301,91,1,4,11,0,"r4","Next station",Mode.STATION);
        Platform previous=new Platform(9302,91,1,3,11,0,"r3","Previous station",Mode.STATION);
        stations.add(next); stations.add(previous); // Deliberately opposite to walking order.
        Rider player=rider(291); player.waiting=previous.itemId;
        FakeWorld world=new FakeWorld(player);
        RailwayPassengerService service=new RailwayPassengerService(network,()->stations,world);
        service.refreshPlayers(); require(world.activeWaiting.get(player.id)==previous.itemId,"previous station owns the single client waiting badge");
        world.indicatorEvents.clear(); player.waiting=next.itemId; service.refreshPlayers();
        require(world.indicatorEvents.equals(Arrays.asList("off:"+previous.itemId,"on:"+next.itemId)),"old station badge is removed before adding adjacent station badge regardless of station insertion order");
        require(world.activeWaiting.get(player.id)==next.itemId&&service.isWaiting(player.id,next)&&!service.isWaiting(player.id,previous),"adjacent move leaves the new waiting badge visible and only the new station waiting");
        world.indicatorEvents.clear(); Platform renamed=stations.rename(next.itemId,"Renamed station"); service.refreshPlayers();
        require(world.indicatorEvents.equals(Arrays.asList("off:"+next.itemId,"on:"+next.itemId))&&"Renamed station".equals(world.indicatorNames.get(player.id)),"renaming the current station refreshes the visible badge tooltip");
        require(service.isWaiting(player.id,renamed)&&service.waitingCount(renamed)==1,"badge rename preserves waiting state and count");
        Rider replacement=rider(player.id); replacement.waiting=next.itemId; world.people[0]=replacement; world.indicatorEvents.clear(); service.refreshPlayers();
        require(world.indicatorEvents.equals(Arrays.asList("off:"+next.itemId,"on:"+next.itemId))&&world.activeWaiting.get(player.id)==next.itemId,"reconnected player object receives its waiting badge even without an intervening offline poll");
        service.close(); require(!world.activeWaiting.containsKey(player.id),"closing service removes the single client waiting badge");
    }
    private static void durableArrivalProtection() throws Exception {
        RailNetwork network=line(); PassengerStations stations=new PassengerStations();
        Platform station=new Platform(9401,91,1,3,11,0,"r3","Durable station",Mode.STATION); stations.add(station);
        Rider player=rider(391); player.vehicle=401L; player.waiting=station.itemId;
        stations.choose(player.id,station.itemId);
        FakeWorld world=new FakeWorld(player); world.capacity.put(401L,0);
        network.createTrain("DurableArrival",91,Collections.singletonList(401L),"r3","r4");
        RailwayPassengerService service=new RailwayPassengerService(network,()->stations,world);
        service.refreshPlayers(); service.serviceStops(); service.saved();
        require(stations.recentlyDropped(player.id,station.itemId)&&player.vehicle==-10L,"successful native transfer records per-player durable arrival protection");
        player.online=false; service.refreshPlayers();
        require(service.recentlyDropped(player.id,station)&&stations.recentlyDropped(player.id,station.itemId)&&!service.dirty(),"disconnect clears no durable arrival and does not pretend the player walked away");
        service.close(); require(stations.recentlyDropped(player.id,station.itemId),"service shutdown keeps durable recent-drop protection");
        Path directory=Files.createTempDirectory("wurmrail-arrival-service-"); Path file=directory.resolve("passengers.properties");
        try {
            stations.save(file); PassengerStations restored=PassengerStations.load(file);
            RailwayPassengerService restarted=new RailwayPassengerService(network,()->restored,world);
            restarted.refreshPlayers(); require(restarted.recentlyDropped(player.id,station),"restarted service restores arrival protection while the player is offline");
            player.online=true; player.eligible=false; restarted.refreshPlayers();
            require(restarted.recentlyDropped(player.id,station)&&!restarted.isWaiting(player.id,station),"reconnected player within the area remains protected even while ineligible");
            player.eligible=true; restarted.refreshPlayers(); restarted.serviceStops();
            require(player.vehicle==-10L&&restarted.recentlyDropped(player.id,station)&&!restarted.isWaiting(player.id,station),"logging back in at the station cannot silently reboard a parked train");
            player.waiting=-10L; restarted.refreshPlayers();
            require(!restarted.recentlyDropped(player.id,station)&&restarted.dirty(),"observing the online player outside station clears durable protection and marks persistence dirty");
            restored.save(file); require(!PassengerStations.load(file).recentlyDropped(player.id,station.itemId),"observed departure persists across another reload");
            player.waiting=station.itemId; restarted.refreshPlayers();
            require(restarted.isWaiting(player.id,station)&&world.indicator(player.id,station),"return after observed departure can wait again after restart");
            restarted.close();
        } finally { Files.deleteIfExists(file.resolveSibling("passengers.properties.bak"));Files.deleteIfExists(file);Files.deleteIfExists(directory); }
    }
    private static void combinedStation() throws Exception {
        RailNetwork network=line(); PassengerStations stations=new PassengerStations();
        Platform station=new Platform(9201,91,1,3,11,0,"r3","Village station",Mode.STATION); stations.add(station);
        Rider arrived=rider(191),waiting=rider(192),unavailable=rider(193);
        arrived.vehicle=301L; arrived.waiting=station.itemId; waiting.waiting=station.itemId;
        unavailable.waiting=station.itemId; unavailable.eligible=false;
        stations.choose(arrived.id,station.itemId);
        FakeWorld world=new FakeWorld(arrived,waiting,unavailable); world.capacity.put(301L,0);
        Train train=network.createTrain("Combined",91,Collections.singletonList(301L),"r3","r4");
        RailwayPassengerService service=new RailwayPassengerService(network,()->stations,world);
        service.refreshPlayers();
        require(service.waitingCount(station)==1&&service.isWaiting(waiting.id,station),"only the eligible waiting player is counted");
        require(!service.isWaiting(arrived.id,station)&&!service.isWaiting(unavailable.id,station),"aboard and unavailable players have independent non-waiting state");
        require(world.indicator(waiting.id,station),"waiting player receives visible waiting indicator");
        require(service.shouldStop(train,network.nodes.get("r3"),301L),"combined station requests drop-off even when the only seat is occupied");
        service.serviceStops();
        require(world.transfers.equals(Arrays.asList("drop:191","board:192")),"drop-off runs before pickup and releases its seat for a different waiting player");
        require(arrived.vehicle==-10L&&waiting.vehicle==301L,"arriving player remains at station while independent waiting player boards");
        require(service.recentlyDropped(arrived.id,station)&&!service.isWaiting(arrived.id,station),"successful drop immediately protects only that player against reboarding");
        require(stations.destination(arrived.id)==-10L,"combined station clears completed destination");
        require(!world.indicator(waiting.id,station)&&service.waitingCount(station)==0,"boarding clears waiting indicator in the same poll");
        service.serviceStops(); service.refreshPlayers(); service.serviceStops();
        require(arrived.vehicle==-10L&&world.boards==1,"recent arrival cannot be picked up again during repeated polls");

        stations.choose(waiting.id,station.itemId); world.blockDrop=true; service.serviceStops();
        require(waiting.vehicle==301L&&stations.destination(waiting.id)==station.itemId&&!service.recentlyDropped(waiting.id,station),"failed drop leaves player aboard with destination and no false arrival protection");
        world.blockDrop=false; service.serviceStops(); service.refreshPlayers(); service.serviceStops();
        require(waiting.vehicle==-10L&&service.recentlyDropped(waiting.id,station),"successful retry drops player once and prevents immediate pickup");
        network.removeTrain(train.name);
        Train next=network.createTrain("NextService",91,Collections.singletonList(302L),"r3","r4"); world.capacity.put(302L,1);
        require(!service.shouldStop(next,network.nodes.get("r3"),302L),"next train does not stop to reboard a recently dropped player");
        arrived.eligible=false; service.refreshPlayers();
        require(service.recentlyDropped(arrived.id,station),"unavailability within station area retains arrival protection");
        arrived.eligible=true; service.refreshPlayers(); service.serviceStops();
        require(service.recentlyDropped(arrived.id,station)&&arrived.vehicle==-10L,"becoming eligible again in the same area cannot reboard");

        arrived.waiting=-10L; service.refreshPlayers();
        require(!service.recentlyDropped(arrived.id,station)&&!service.isWaiting(arrived.id,station),"leaving station area clears arrival protection");
        arrived.waiting=station.itemId; world.blockBoard=true; service.refreshPlayers();
        require(service.isWaiting(arrived.id,station)&&world.indicator(arrived.id,station),"returning after leaving starts a new visible waiting state");
        service.serviceStops();
        require(arrived.vehicle==-10L&&service.isWaiting(arrived.id,station)&&world.indicator(arrived.id,station),"failed boarding preserves waiting state and indicator");
        arrived.waiting=-10L; service.refreshPlayers();
        require(!service.isWaiting(arrived.id,station)&&!world.indicator(arrived.id,station),"walking away cancels waiting indicator");

        unavailable.eligible=true; service.refreshPlayers();
        require(service.isWaiting(unavailable.id,station)&&!service.isWaiting(arrived.id,station),"another player becomes waiting independently");
        unavailable.online=false; service.refreshPlayers();
        require(!service.isWaiting(unavailable.id,station)&&!world.indicator(unavailable.id,station),"disconnecting cancels waiting and its indicator");
        unavailable.online=true; service.refreshPlayers();
        world.blockBoard=false; service.serviceStops();
        require(unavailable.vehicle==302L&&!service.isWaiting(unavailable.id,station),"later train boards the independent remaining waiting player");

        require(!train.powered&&!next.powered,"combined station transfers preserve independent train power");

        arrived.waiting=station.itemId; world.blockBoard=true; service.refreshPlayers();
        require(world.indicator(arrived.id,station),"returning waiting player indicator is active before station removal");
        stations.remove(station.itemId); service.refreshPlayers();
        require(!service.isWaiting(arrived.id,station)&&!service.recentlyDropped(waiting.id,station)&&!world.indicator(arrived.id,station),"removed station clears every player's waiting and arrival state");
        stations.add(station); service.refreshPlayers();
        require(world.indicator(arrived.id,station),"waiting indicator returns with a valid station");
        service.close();
        require(!world.indicator(arrived.id,station)&&service.waitingCount(station)==0,"world unload clears live waiting indicators");
    }
    private static RailNetwork line() {
        RailNetwork n=new RailNetwork(); for(int i=0;i<18;i++){n.addNode("r"+i,i,10,0);if(i>0)n.connect("r"+(i-1),"r"+i);} return n;
    }
    private static Rider rider(long id) throws Exception {
        Field f=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); f.setAccessible(true); Object u=f.get(null);
        Rider r=(Rider)u.getClass().getMethod("allocateInstance",Class.class).invoke(u,Rider.class); r.id=id; r.vehicle=-10L; r.waiting=-10L; r.eligible=true; r.online=true; return r;
    }
    public static final class Rider extends Creature {
        long id,vehicle,waiting;
        boolean eligible,online;
        private Rider() throws Exception { super(); }
        @Override public long getWurmId() { return id; }
        @Override public long getVehicle() { return vehicle; }
    }
    private static final class FakeWorld implements RailwayPassengerService.World {
        final Rider[] people; final Map<Long,Integer> capacity=new HashMap<Long,Integer>(); final Map<String,Boolean> indicators=new HashMap<String,Boolean>();
        final Map<Long,Long> activeWaiting=new HashMap<Long,Long>(); final Map<Long,String> indicatorNames=new HashMap<Long,String>();
        final List<String> indicatorEvents=new ArrayList<String>();
        final List<String> transfers=new ArrayList<String>(); int boards,drops; boolean blockDrop,blockBoard;
        FakeWorld(Rider...people) { this.people=people; }
        @Override public Creature[] players() { List<Creature> online=new ArrayList<Creature>(); for(Rider p:people)if(p.online)online.add(p); return online.toArray(new Creature[online.size()]); }
        @Override public boolean present(Platform p) { return true; }
        @Override public boolean waiting(Creature p,Platform station) { return ((Rider)p).eligible&&inArea(p,station); }
        @Override public boolean inArea(Creature p,Platform station) { Rider r=(Rider)p; return r.vehicle==-10L&&r.waiting==station.itemId; }
        @Override public void waitingIndicator(Creature p,Platform station,boolean waiting) {
            indicators.put(p.getWurmId()+":"+station.itemId,waiting); indicatorEvents.add((waiting?"on:":"off:")+station.itemId);
            if(waiting) { activeWaiting.put(p.getWurmId(),station.itemId); indicatorNames.put(p.getWurmId(),station.name); }
            else { activeWaiting.remove(p.getWurmId()); indicatorNames.remove(p.getWurmId()); }
        }
        boolean indicator(long player,Platform station) { return Boolean.TRUE.equals(indicators.get(player+":"+station.itemId)); }
        @Override public boolean freeSeat(Creature p,long car) { return p.getVehicle()==-10L&&capacity.getOrDefault(car,0)>0; }
        @Override public boolean board(Creature p,long car) { if(blockBoard)return false; ((Rider)p).vehicle=car; capacity.put(car,capacity.get(car)-1); boards++; transfers.add("board:"+p.getWurmId()); return true; }
        @Override public boolean drop(Creature p,long car,Platform target) { if(blockDrop)return false; ((Rider)p).vehicle=-10L; ((Rider)p).waiting=target.itemId; capacity.put(car,capacity.getOrDefault(car,0)+1); drops++; transfers.add("drop:"+p.getWurmId()); return true; }
        @Override public void message(Creature p,String text) { }
    }
    private static void require(boolean condition,String message) { assertions++; if(!condition)throw new AssertionError(message); }
}
