import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.SpellEffectsEnum;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.wurmrail.RailwayPassengerService;
import org.wurmrail.RailwayPassengerUi;
import org.wurmrail.RailwayPassengerWorld;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.Mode;
import org.wurmrail.core.PassengerStations.Platform;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.Train;

/** Native Player transfers and HUD packets for independent states at one combined station. */
public final class CombinedStationRuntimeSmoke {
    private static int assertions;
    public static void run() throws Exception {
        Player arriving=player(1100000000L,160,211),waiting=player(1100000256L,160,211),other=player(1100000512L,160,211);
        RailNetwork network=new RailNetwork();
        for(int x=158;x<=168;x++) {
            Node n=network.addNode("combinedStation"+x,x,210,0);
            n.markerId=WurmRailWorld.spawnTrackMarker(arriving,n.worldX(),n.worldY(),0).getWurmId();
            if(x>158)network.connect("combinedStation"+(x-1),n.name);
        }
        Item engine=WurmRailWorld.spawnLocomotive(arriving,160*4+2,210*4+2,0);
        Train train=network.createTrain("CombinedStation",arriving.getWurmId(),Collections.singletonList(engine.getWurmId()),"combinedStation160","combinedStation161");
        WurmRailRuntime runtime=new WurmRailRuntime();
        Path file=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("combined-station-runtime/network.properties");
        set(runtime,"network",network); set(runtime,"ready",true); set(runtime,"stateFile",file);
        call(runtime,"initService",new Class<?>[0]);
        PassengerStations stations=(PassengerStations)get(runtime,"passengerStations");
        Platform home=platform(stations,arriving,network.nodes.get("combinedStation160"),160,211,"Village",true);
        Platform destination=platform(stations,arriving,network.nodes.get("combinedStation168"),168,211,"Mine",false);
        Items.getItem(home.itemId).setName("Pickup: Village"); Items.getItem(destination.itemId).setName("Drop-off: Mine");
        call(runtime,"refreshStationMarkers",new Class<?>[0]);
        require(Items.getItem(home.itemId).getName().equals("Station: Village")&&Items.getItem(destination.itemId).getName().equals("Station: Mine")
                &&stations.get(home.itemId)==home&&stations.get(destination.itemId)==destination,"startup refresh renames both old physical models as Station without replacing item identities or station definitions");
        RailwayPassengerService service=(RailwayPassengerService)get(runtime,"passengerService");
        require(home.mode==Mode.STATION&&destination.mode==Mode.STATION,"both legacy physical station models now have combined station definitions");
        require(RailwayPassengerWorld.board(arriving,engine),"actual arriving player boards the native engine before the station visit");
        Vehicle vehicle=Vehicles.getVehicle(engine);
        for(Seat seat:vehicle.seats)if(!seat.isOccupied())seat.occupant=1100099900L;
        require(RailwayPassengerWorld.freeSeatIndex(engine)<0,"native engine initially has no free seat for the waiting player");
        stations.choose(arriving.getWurmId(),home.itemId); stations.choose(waiting.getWurmId(),destination.itemId);
        other.getSaveFile().dead=true;
        clearPackets(arriving); clearPackets(waiting); clearPackets(other);
        service.refreshPlayers();
        require(service.isWaiting(waiting.getWurmId(),home)&&service.waitingCount(home)==1,"eligible native player waits while aboard and unavailable players do not");
        require(!service.isWaiting(arriving.getWurmId(),home)&&!service.isWaiting(other.getWurmId(),home),"waiting is tracked separately for each native player");
        require(statusPacket(waiting,0,"Waiting for train at Village"),"waiting player receives native custom status-bar indicator with station name");
        require(!statusPacket(arriving,0,null)&&!statusPacket(other,0,null),"waiting indicator is sent only to the eligible waiting player");
        require(!RailwayPassengerWorld.hasFreeSeat(waiting,engine),"waiting player cannot board before a passenger drops off");
        require(service.shouldStop(train,network.nodes.get(home.rail),engine.getWurmId()),"combined station still requests a stop to drop off from a full train");
        RailwayPassengerUi.View before=snapshot(runtime,waiting,home.itemId);
        require(before.status.contains("Waiting")&&before.waitingCount==1,"station window shows personal waiting state and live independent count");
        require(offers(before,home.itemId)&&offers(before,destination.itemId),"both native station models appear as personal destinations");
        RailwayTrainUi.Api controls=(RailwayTrainUi.Api)get(runtime,"trainApi");
        RailwayTrainUi.View choices=controls.snapshot(arriving,engine.getWurmId());
        require(routeOffers(choices,home.rail)&&routeOffers(choices,destination.rail),"both combined stations remain available as automatic route stops");

        service.serviceStops();
        require(arriving.getVehicle()==-10L&&arriving.getTileX()==home.x&&arriving.getTileY()==home.y,"arriving player leaves native seat and lands on this station tile");
        require(waiting.getVehicle()==engine.getWurmId()&&vehicle.getSeatFor(waiting.getWurmId())!=null,"drop-off frees a native seat before waiting player boards during the same service");
        require(stations.destination(arriving.getWurmId())==-10L&&stations.destination(waiting.getWurmId())==destination.itemId,"completed destination clears while independent boarding player's onward destination remains");
        require(service.recentlyDropped(arriving.getWurmId(),home)&&!service.isWaiting(arriving.getWurmId(),home),"newly dropped player is protected from automatic reboarding");
        require(!service.isWaiting(waiting.getWurmId(),home)&&statusPacket(waiting,1,null),"successful native boarding immediately clears waiting state and sends indicator removal");
        require(snapshot(runtime,arriving,home.itemId).status.contains("Recently dropped"),"arriving player's station window explains arrival protection");
        acknowledgeLanding(arriving);
        require(RailwayPassengerWorld.eligible(arriving),"client landing acknowledgement restores ordinary on-foot eligibility independently of station arrival protection");

        other.getSaveFile().dead=false; service.refreshPlayers();
        require(service.isWaiting(other.getWurmId(),home)&&service.waitingCount(home)==1&&statusPacket(other,0,"Waiting for train at Village"),"third player can independently begin waiting beside a recently dropped player");
        for(int i=0;i<3;i++) { service.refreshPlayers(); service.serviceStops(); }
        require(arriving.getVehicle()==-10L&&service.recentlyDropped(arriving.getWurmId(),home),"repeated polls keep recently dropped player ashore");
        require(other.getVehicle()==-10L&&service.isWaiting(other.getWurmId(),home),"full train preserves another player's waiting state");
        arriving.getSaveFile().dead=true; service.refreshPlayers(); arriving.getSaveFile().dead=false; service.refreshPlayers();
        require(service.recentlyDropped(arriving.getWurmId(),home)&&!service.isWaiting(arriving.getWurmId(),home),"eligibility changes within the area do not erase recent-drop protection");

        // Release the first registered body before another actual locomotive arrives on this rail.
        network.removeTrain(train.name);
        WurmRailWorld.moveCar(engine.getWurmId(),158*4+2,210*4+2,90,0);
        Item next=WurmRailWorld.spawnLocomotive(arriving,160*4+2,210*4+2,0);
        Train nextTrain=network.createTrain("NextStationService",arriving.getWurmId(),Collections.singletonList(next.getWurmId()),home.rail,"combinedStation161");
        service.serviceStops();
        require(other.getVehicle()==next.getWurmId()&&Vehicles.getVehicle(next).getSeatFor(other.getWurmId())!=null,"next train boards the independent waiting player");
        require(arriving.getVehicle()==-10L&&service.recentlyDropped(arriving.getWurmId(),home),"next train cannot immediately collect the recently dropped player");
        require(!service.isWaiting(other.getWurmId(),home)&&statusPacket(other,1,null),"next train's boarding also clears that player's HUD indicator");

        clearPackets(arriving); position(arriving,home.x+1,home.y); service.refreshPlayers();
        require(!service.recentlyDropped(arriving.getWurmId(),home)&&!service.isWaiting(arriving.getWurmId(),home),"walking beyond station tile clears arrival protection");
        position(arriving,home.x,home.y); service.refreshPlayers();
        require(service.isWaiting(arriving.getWurmId(),home)&&statusPacket(arriving,0,"Waiting for train at Village"),"returning to station starts a new visible waiting state");
        require(snapshot(runtime,arriving,home.itemId).status.contains("Waiting"),"returning player's station UI now reports waiting instead of recent arrival");
        clearPackets(arriving); position(arriving,home.x+1,home.y); service.refreshPlayers();
        require(!service.isWaiting(arriving.getWurmId(),home)&&service.waitingCount(home)==0&&statusPacket(arriving,1,null),"walking away immediately clears waiting state, count and client HUD badge");
        position(arriving,home.x,home.y); service.refreshPlayers(); clearPackets(arriving); service.close();
        require(statusPacket(arriving,1,null)&&!service.isWaiting(arriving.getWurmId(),home),"service shutdown removes the player's waiting badge");
        require(!train.powered&&!nextTrain.powered,"passenger exchanges never override operator power state");

        for(Seat seat:vehicle.seats)if(seat.occupant==1100099900L)seat.occupant=-10L;
        for(Player player:new Player[]{waiting,other})if(player.getVehicle()!=-10L)player.disembark(false);
        for(Node node:network.nodes.values())Items.destroyItem(node.markerId);
        Items.destroyItem(engine.getWurmId()); Items.destroyItem(next.getWurmId());
        Items.destroyItem(home.itemId); Items.destroyItem(destination.itemId);
        removePlayer(arriving); removePlayer(waiting); removePlayer(other);
        System.out.println("PASS native combined station drop-before-pickup, independent waiting/recent arrivals, legacy models, route destinations and actual HUD status packets ("+assertions+" assertions)");
    }
    private static Platform platform(PassengerStations stations,Creature actor,Node rail,int x,int y,String name,boolean pickup) throws Exception {
        Item item=WurmRailWorld.spawnPassengerPlatform(actor,x,y,0,pickup);
        item.setName("Station: "+name);
        Platform p=new Platform(item.getWurmId(),actor.getWurmId(),1,x,y,0,rail.name,name,pickup?Mode.PICKUP:Mode.DROPOFF);
        stations.add(p); return p;
    }
    private static Player player(long id,int x,int y) throws Exception {
        Method method=PassengerWorldSmoke.class.getDeclaredMethod("player",long.class,float.class,float.class); method.setAccessible(true);
        return (Player)method.invoke(null,id,x*4f+2,y*4f+2);
    }
    private static void removePlayer(Player player) throws Exception {
        Method method=PassengerWorldSmoke.class.getDeclaredMethod("removePlayer",Player.class); method.setAccessible(true); method.invoke(null,player);
    }
    @SuppressWarnings("unchecked")
    private static List<byte[]> packets(Player player) throws Exception {
        Object connection=player.getCommunicator().getConnection();
        Field packets=connection.getClass().getDeclaredField("packets"); packets.setAccessible(true);
        return (List<byte[]>)packets.get(connection);
    }
    private static void clearPackets(Player player) throws Exception { packets(player).clear(); }
    private static void acknowledgeLanding(Player player) throws Exception {
        // This fixture captures outgoing packets and has no client. A real client's
        // CMD_TELEPORT acknowledgement removes these native intra-teleport counters.
        Field field=player.getMovementScheme().getClass().getDeclaredField("intraports"); field.setAccessible(true);
        for(Object sequence:new java.util.ArrayList<Object>((java.util.Collection<?>)field.get(player.getMovementScheme())))
            player.getMovementScheme().removeIntraTeleport(((Integer)sequence).intValue());
    }
    private static boolean statusPacket(Player player,int subtype,String label) throws Exception {
        long statusId=RailwayPassengerWorld.WAITING_STATUS_ID;
        for(byte[] bytes:packets(player)) {
            if(bytes.length<10||bytes[0]!=(byte)-47||bytes[1]!=(byte)subtype)continue;
            ByteBuffer packet=ByteBuffer.wrap(bytes); packet.position(2);
            if(packet.getLong()!=statusId)continue;
            if(subtype==1)return bytes.length==10;
            if(bytes.length<19||packet.getInt()!=SpellEffectsEnum.LINKS.getTypeId()||packet.getInt()!=-1)continue;
            int length=packet.get()&255; if(length!=packet.remaining())continue;
            byte[] text=new byte[length]; packet.get(text);
            if(label==null||label.equals(new String(text,StandardCharsets.UTF_8)))return true;
        }
        return false;
    }
    private static RailwayPassengerUi.View snapshot(WurmRailRuntime runtime,Creature player,long id) throws Exception {
        return (RailwayPassengerUi.View)call(runtime,"passengerSnapshot",new Class<?>[]{Creature.class,long.class},player,id);
    }
    private static boolean offers(RailwayPassengerUi.View view,long id) { for(RailwayPassengerUi.Choice choice:view.destinations)if(choice.id==id)return true;return false; }
    private static boolean routeOffers(RailwayTrainUi.View view,String rail) { for(RailwayTrainUi.Choice choice:view.stations)if(choice.key.equals(rail))return true;return false; }
    private static void position(Player player,int x,int y) {
        player.getStatus().getPosition().setPosX(x*4f+2); player.getStatus().getPosition().setPosY(y*4f+2);
        player.currentTile=Zones.getOrCreateTile(x,y,true);
    }
    private static Object get(Object object,String name) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
    private static void set(Object object,String name,Object value) throws Exception { Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value); }
    private static Object call(Object object,String name,Class<?>[] types,Object... values) throws Exception {
        Method method=object.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(object,values); }
        catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof Exception)throw (Exception)wrapped.getCause();
            if(wrapped.getCause() instanceof Error)throw (Error)wrapped.getCause(); throw wrapped;
        }
    }
    private static void require(boolean condition,String message) { assertions++;if(!condition)throw new AssertionError(message); }
}
