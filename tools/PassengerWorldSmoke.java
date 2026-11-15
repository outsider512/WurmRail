import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Players;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.ActionStack;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreatureStatus;
import com.wurmonline.server.creatures.MovementScheme;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.players.DbPlayerInfo;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.players.PlayerInfo;
import com.wurmonline.server.players.PlayerCommunicatorQueued;
import com.wurmonline.server.zones.Zones;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.nio.ByteBuffer;
import java.util.Queue;
import org.wurmrail.RailwayPassengerWorld;
import org.wurmrail.WurmRailWorld;

/** Actual Player, native seat/boarding and disembarking checks in the isolated synthetic world. */
public final class PassengerWorldSmoke {
    private static int assertions;
    private PassengerWorldSmoke() { }

    public static void run() throws Exception {
        cargoShortcut();
        Player player=player(800000000L,130*4+3.9f,150*4+3.9f);
        Item engine=WurmRailWorld.spawnLocomotive(player,130*4+2,149*4+2,0);
        WurmRailWorld.moveCar(engine.getWurmId(),130*4+2.5f,149*4+1.5f,45,0);
        Vehicle vehicle=Vehicles.getVehicle(engine);
        require(RailwayPassengerWorld.eligible(player),"connected unmounted living player is eligible for pickup");
        require(Arrays.asList(RailwayPassengerWorld.onlinePlayers()).contains(player),"online lookup returns the actual native Player object");
        require(RailwayPassengerWorld.hasFreeSeat(player,engine),"cardinal adjacent pickup is inside native boarding range");
        double pickupDistance=Math.hypot(player.getPosX()-engine.getPosX(),player.getPosY()-engine.getPosY());
        require(pickupDistance>6&&pickupDistance<8&&player.getTileX()==130&&player.getTileY()==150,
                "far corner of the pickup tile remains reachable beside a curved rail");
        require(RailwayPassengerWorld.freeSeatIndex(engine)==0,"first native passenger seat available");
        require(nativeMenuCount(player,engine,332)==1&&nativeMenuCount(player,engine,333)==0,
                "one native Embark Passenger action is offered to a nearby unmounted player");
        for(Seat seat:vehicle.seats) seat.occupant=123L;
        require(!RailwayPassengerWorld.hasFreeSeat(player,engine)&&RailwayPassengerWorld.freeSeatIndex(engine)<0,
                "full vehicle cannot pick up additional riders");
        require(nativeMenuCount(player,engine,332)==0,"native boarding action disappears when all passenger seats are occupied");
        vehicle.seats[3].occupant=-10L;
        require(RailwayPassengerWorld.freeSeatIndex(engine)==3,"all four passenger seats are searched");
        require(nativeMenuCount(player,engine,332)==1,"one free rear seat restores exactly one native boarding action");
        for(Seat seat:vehicle.seats) seat.occupant=-10L;
        PlayerInfo info=player.getSaveFile();
        info.dead=true;
        require(!RailwayPassengerWorld.eligible(player),"dead players are never picked up");
        info.dead=false;
        player.setTeleporting(true);
        require(!RailwayPassengerWorld.eligible(player),"teleporting players are never picked up");
        player.setTeleporting(false);
        player.opponent=UsabilitySmoke.actor(8881,player.getPosX(),player.getPosY());
        require(!RailwayPassengerWorld.eligible(player),"combat is never interrupted by an automatic pickup");
        player.opponent=null;
        require(RailwayPassengerWorld.validatePlatformTile(player,130,150,0)==null,"clear dry platform is valid");
        int original=Server.surfaceMesh.getTile(130,150);
        Server.surfaceMesh.setTile(130,150,Tiles.encode((short)50,Tiles.Tile.TILE_LAVA.id,(byte)0));
        require(RailwayPassengerWorld.validatePlatformTile(player,130,150,0)!=null,"lava platform rejected");
        Server.surfaceMesh.setTile(130,150,original);

        require(RailwayPassengerWorld.board(player,engine),"native railway boarding succeeds");
        require(player.getVehicle()==engine.getWurmId()&&vehicle.getSeatFor(player.getWurmId())!=null,
                "native boarding assigns both vehicle identity and real seat occupancy");
        require(!RailwayPassengerWorld.eligible(player),"already mounted passenger cannot be picked up a second time");
        require(nativeMenuCount(player,engine,332)==0&&nativeMenuCount(player,engine,333)==1,
                "mounted passenger sees native Disembark instead of another boarding action");
        require(!RailwayPassengerWorld.canDropoff(player,engine,132,149,0),"distant drop-off rejected before native detachment");
        require(player.getVehicle()==engine.getWurmId(),"failed drop-off preflight keeps passenger on their train");
        require(RailwayPassengerWorld.canDropoff(player,engine,130,150,0),"adjacent clear drop-off passes native terrain and seat checks");
        require(RailwayPassengerWorld.disembarkTo(player,engine,130,150,0),"native disembarking and exact platform placement succeed");
        require(player.getVehicle()==-10L&&vehicle.getSeatFor(player.getWurmId())==null,"native disembarking clears vehicle and occupied seat");
        require(nativeMenuCount(player,engine,332)==1&&nativeMenuCount(player,engine,333)==0,
                "disembarking restores native boarding without a duplicate rail action");
        require(player.getTileX()==130&&player.getTileY()==150&&player.getPosX()==522f&&player.getPosY()==602f,
                "passenger reaches the exact center of the configured destination tile");
        require(Math.abs(player.getPositionZ()-Zones.calculateHeight(522f,602f,true))<.01f,
                "drop-off uses destination ground height rather than the original seat height");
        require(queuedTeleportAt(player,522f,602f,player.getPositionZ()),
                "actual player communicator queues a native teleport packet with final platform coordinates before acknowledgement");
        Items.destroyItem(engine.getWurmId());
        removePlayer(player);
        System.out.println("PASS actual-player native passenger eligibility, boarding, seat cleanup and platform placement ("
                +assertions+" assertions)");
        System.out.println("PASS native contextual passenger menu: one Embark when a seat is free, no Board when mounted, Disembark and remount");
    }

    private static int nativeMenuCount(Player actor,Item car,int action) throws Exception {
        java.lang.reflect.Method menu=com.wurmonline.server.behaviours.VehicleBehaviour.class
                .getDeclaredMethod("getVehicleBehaviours",Creature.class,Item.class);
        menu.setAccessible(true);
        Object behaviour=com.wurmonline.server.behaviours.Behaviours.getInstance().getBehaviour((short)41);
        java.util.List<?> entries=(java.util.List<?>)menu.invoke(behaviour,actor,car);
        int count=0;
        for(Object entry:entries)if(((com.wurmonline.server.behaviours.ActionEntry)entry).getNumber()==action)count++;
        return count;
    }

    /** Real Runtime -> dispatcher -> native Open -> queued player inventory window. */
    private static void cargoShortcut() throws Exception {
        Player actor=player(800000768L,146*4+2,156*4+2);
        org.wurmrail.WurmRailRuntime runtime=new org.wurmrail.WurmRailRuntime();
        java.lang.reflect.Method open=org.wurmrail.WurmRailRuntime.class.getDeclaredMethod("openCargo",
                Creature.class,Item.class,org.wurmrail.core.RailNetwork.Train.class);
        open.setAccessible(true);
        try {
            for(boolean engine:new boolean[]{true,false}) {
                Item car=engine?WurmRailWorld.spawnLocomotive(actor,146*4+2,156*4+2,0)
                        :WurmRailWorld.spawnCart(actor,146*4+2,156*4+2,0);
                Item cargo=com.wurmonline.server.items.ItemFactory.createItem(
                        com.wurmonline.server.items.ItemList.plank,47f,"Cargo shortcut fixture");
                try {
                    require(car.insertItem(cargo),"native rail cargo fixture is inserted in its container");
                    require(queuedInventoryWindows(actor,car.getWurmId())==0,"cargo window starts closed");
                    // -10 is Wurm's no-object ID, but dispatcher subject selection
                    // uses -1 for no active item. The old shortcut tried to use
                    // a nonexistent -10 item before reaching native Open.
                    try {
                        com.wurmonline.server.behaviours.BehaviourDispatcher.action(actor,actor.getCommunicator(),
                                -10L,car.getWurmId(),com.wurmonline.server.behaviours.Actions.OPEN);
                        actor.getActions().poll(actor);
                    } catch(com.wurmonline.server.NoSuchItemException expectedOldSource) { }
                    require(queuedInventoryWindows(actor,car.getWurmId())==0,"old invalid active-item sentinel cannot open cargo");
                    actor.getActions().clear();
                    open.invoke(runtime,actor,car,null);
                    actor.getActions().poll(actor);
                    require(queuedInventoryWindows(actor,car.getWurmId())==1,
                            "control-window cargo shortcut emits one real native inventory-window packet for "+(engine?"locomotive":"wagon"));
                    require(Arrays.asList(car.getWatchers()).contains(actor),"native cargo opening attaches the actual player inventory watcher");
                    require(cargo.getParentId()==car.getWurmId()&&cargo.getQualityLevel()==47f,"opening native cargo retains its item identity and contents");
                } finally { Items.destroyItem(car.getWurmId()); }
            }
        } finally { removePlayer(actor); }
        System.out.println("PASS native rail cargo shortcut: empty-hand dispatcher source, locomotive/wagon inventory packets, native watchers and preserved contents");
    }

    private static int queuedInventoryWindows(Player player,long carId) throws Exception {
        Field queueField=PlayerCommunicatorQueued.class.getDeclaredField("MESSAGES_TO_PLAYERS");queueField.setAccessible(true);
        int count=0;
        for(Object message:(Queue<?>)queueField.get(null)) {
            java.lang.reflect.Method owner=message.getClass().getDeclaredMethod("getPlayerId");owner.setAccessible(true);
            if(((Long)owner.invoke(message)).longValue()!=player.getWurmId())continue;
            java.lang.reflect.Method bytes=message.getClass().getDeclaredMethod("getMessageBytes");bytes.setAccessible(true);
            byte[] packet=(byte[])bytes.invoke(message);
            if(packet.length>=10&&packet[0]==116&&ByteBuffer.wrap(packet,1,8).getLong()==carId)count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    static Player player(long id,float x,float y) throws Exception {
        id=id&~255L;
        Player player=(Player)allocate(Player.class);
        Creature base=UsabilitySmoke.actor(id,x,y);
        set(Creature.class,player,"id",id);
        set(Creature.class,player,"name","Rail passenger "+id);
        set(Creature.class,player,"petName","");
        // A real constructor initializes these to no logger. Dispatcher sends
        // action diagnostics through them; zero would try to resolve a server
        // creature and construct a server in this socket-free fixture.
        set(Creature.class,player,"loggerCreature1",-10L);
        set(Creature.class,player,"loggerCreature2",-10L);
        Object template=allocate(Class.forName("com.wurmonline.server.creatures.DbCreatureTemplate"));
        set(com.wurmonline.server.creatures.CreatureTemplate.class,template,"id",
                com.wurmonline.server.creatures.CreatureTemplateIds.HUMAN_CID);
        set(com.wurmonline.server.creatures.CreatureTemplate.class,template,"name","human");
        set(com.wurmonline.server.creatures.CreatureTemplate.class,template,"modelName","model.creature.humanoid.human.player.");
        set(Creature.class,player,"template",template);
        set(Creature.class,player,"status",base.getStatus());
        set(CreatureStatus.class,player.getStatus(),"statusHolder",player);
        set(CreatureStatus.class,player.getStatus(),"body",com.wurmonline.server.bodys.BodyFactory.getBody(
                player,com.wurmonline.server.bodys.BodyTemplate.TYPE_HUMAN,(short)180,(short)40,(short)40));
        PlayerInfo info=(PlayerInfo)allocate(DbPlayerInfo.class);
        info.wurmId=id;
        set(PlayerInfo.class,info,"name","Rail passenger "+id);
        set(PlayerInfo.class,info,"highwayPathDestination","");
        set(PlayerInfo.class,info,"power",(byte)2);
        set(Player.class,player,"saveFile",info);
        Constructor<MovementScheme> movement=MovementScheme.class.getDeclaredConstructor(Creature.class);
        movement.setAccessible(true);
        set(Creature.class,player,"movementScheme",movement.newInstance(player));
        MemoryConnection connection=(MemoryConnection)allocate(MemoryConnection.class);
        connection.buffer=ByteBuffer.allocate(65536);
        connection.packets=new java.util.ArrayList<byte[]>();
        com.wurmonline.server.creatures.Communicator communicator=new PlayerCommunicatorQueued(player,connection);
        set(com.wurmonline.server.creatures.Communicator.class,communicator,"justLoggedIn",false);
        set(Creature.class,player,"communicator",communicator);
        set(Creature.class,player,"actions",new ActionStack());
        set(Creature.class,player,"moveslow",Integer.MAX_VALUE);
        set(Creature.class,player,"encumbered",Integer.MAX_VALUE);
        set(Creature.class,player,"cantmove",Integer.MAX_VALUE);
        player.currentTile=Zones.getOrCreateTile((int)x/4,(int)y/4,true);
        player.vehicle=-10L;
        Field byId=Players.class.getDeclaredField("playersById"); byId.setAccessible(true);
        ((Map<Long,Player>)byId.get(null)).put(id,player);
        Field byName=Players.class.getDeclaredField("players"); byName.setAccessible(true);
        ((Map<String,Player>)byName.get(null)).put(player.getName(),player);
        return player;
    }

    @SuppressWarnings("unchecked")
    static void removePlayer(Player player) throws Exception {
        Field byId=Players.class.getDeclaredField("playersById"); byId.setAccessible(true);
        ((Map<Long,Player>)byId.get(null)).remove(player.getWurmId());
        Field byName=Players.class.getDeclaredField("players"); byName.setAccessible(true);
        ((Map<String,Player>)byName.get(null)).remove(player.getName());
    }

    private static Object allocate(Class<?> type) throws Exception {
        Field field=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe=field.get(null);
        return unsafe.getClass().getMethod("allocateInstance",Class.class).invoke(unsafe,type);
    }
    private static boolean queuedTeleportAt(Player player,float x,float y,float z) throws Exception {
        Field queueField=PlayerCommunicatorQueued.class.getDeclaredField("MESSAGES_TO_PLAYERS");
        queueField.setAccessible(true);
        for(Object message:(Queue<?>)queueField.get(null)) {
            java.lang.reflect.Method owner=message.getClass().getDeclaredMethod("getPlayerId"); owner.setAccessible(true);
            if(((Long)owner.invoke(message)).longValue()!=player.getWurmId()) continue;
            java.lang.reflect.Method bytes=message.getClass().getDeclaredMethod("getMessageBytes"); bytes.setAccessible(true);
            byte[] packet=(byte[])bytes.invoke(message);
            if(packet.length<21||packet[0]!=51) continue;
            ByteBuffer buffer=ByteBuffer.wrap(packet); buffer.get();
            if(buffer.getFloat()==x&&buffer.getFloat()==y&&Math.abs(buffer.getFloat()-z)<.01f) return true;
        }
        return false;
    }
    /** Packet buffer only; Unsafe allocation bypasses every socket-opening constructor. */
    public static final class MemoryConnection extends com.wurmonline.communication.SocketConnection {
        ByteBuffer buffer;
        public java.util.List<byte[]> packets;
        private MemoryConnection() throws java.io.IOException { super("127.0.0.1",0,false); }
        @Override public ByteBuffer getBuffer() { buffer.clear(); return buffer; }
        @Override public void flush() { packets.add(Arrays.copyOf(buffer.array(),buffer.position())); buffer.clear(); }
        @Override public String getIp() { return "127.0.0.1"; }
    }
    private static void set(Class<?> owner,Object instance,String name,Object value) throws Exception {
        Field field=owner.getDeclaredField(name); field.setAccessible(true); field.set(instance,value);
    }
    private static void require(boolean success,String message) {
        assertions++;
        if(!success) throw new AssertionError(message);
    }
}
