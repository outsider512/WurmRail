import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.DbConnector;
import com.wurmonline.server.Items;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.players.PlayerCommunicatorQueued;
import com.wurmonline.server.players.PlayerCommunicator;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.VisionArea;
import com.wurmonline.server.structures.DbFence;
import com.wurmonline.server.structures.Fence;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.StructureConstantsEnum;
import com.wurmonline.shared.constants.StructureStateEnum;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;

/** Native mesh portals, individual vehicle zones, real passengers and persistent rail paths. */
public final class CaveEntranceRuntimeSmoke {
    private static int assertions;
    private static final Map<Long,PassengerWorldSmoke.MemoryConnection> connections=new HashMap<Long,PassengerWorldSmoke.MemoryConnection>();
    private CaveEntranceRuntimeSmoke() { }

    public static void run() throws Exception {
        Map<Integer,Integer> surface=new HashMap<Integer,Integer>(),cave=new HashMap<Integer,Integer>();
        Field waterField=com.wurmonline.server.zones.Water.class.getDeclaredField("waterMesh");waterField.setAccessible(true);
        Object savedWater=waterField.get(null);
        com.wurmonline.mesh.MeshIO water=com.wurmonline.mesh.MeshIO.createMap(Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-tunnel-water.map").toString(),8,new int[256*256]);
        waterField.set(null,water);
        Field flagsField=Server.class.getDeclaredField("flagsMesh");flagsField.setAccessible(true);Object savedFlags=flagsField.get(null);
        com.wurmonline.mesh.MeshIO flags=com.wurmonline.mesh.MeshIO.createMap(Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-tunnel-flags.map").toString(),8,new int[256*256]);flagsField.set(null,flags);
        Field distantField=com.wurmonline.mesh.MeshIO.class.getDeclaredField("distantTerrainTypes");distantField.setAccessible(true);Object savedDistant=distantField.get(Server.surfaceMesh);
        try {
            for(int x=192;x<=218;x++)for(int y=92;y<=99;y++) {
                int key=x+y*256;surface.put(key,Server.surfaceMesh.getTile(x,y));cave.put(key,Server.caveMesh.getTile(x,y));
                Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
                Server.caveMesh.setTile(x,y,Tiles.encode((short)50,x>=200&&x<=210?Tiles.Tile.TILE_CAVE.id:Tiles.Tile.TILE_CAVE_WALL.id,(byte)50));
            }
            entrance(200,95);entrance(210,95);
            validation();
            // A flat, identical surface/cave mesh cannot expose client terrain
            // sampling the roof while its XY interpolator trails a layer packet.
            for(int x=201;x<=210;x++)for(int y=92;y<=99;y++)
                Server.surfaceMesh.setTile(x,y,Tiles.encode((short)110,Tiles.decodeType(Server.surfaceMesh.getTile(x,y)),(byte)0));
            for(int x=202;x<=208;x++)for(int y=92;y<=99;y++)
                Server.caveMesh.setTile(x,y,Tiles.encode((short)(50+4-Math.abs(x-205)),Tiles.Tile.TILE_CAVE.id,(byte)50));
            Server.surfaceMesh.calcDistantTerrain();
            runCase(1,.1);runCase(3,.1);runCase(1,.25);runCase(3,.25);
        } finally {
            waterField.set(null,savedWater);water.close();flagsField.set(null,savedFlags);flags.close();
            distantField.set(Server.surfaceMesh,savedDistant);
            for(Map.Entry<Integer,Integer> tile:surface.entrySet())Server.surfaceMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
            for(Map.Entry<Integer,Integer> tile:cave.entrySet())Server.caveMesh.setTile(tile.getKey()%256,tile.getKey()/256,tile.getValue());
        }
        System.out.println("PASS native cave entrance placement, raised roof and sloped floor, 1/3-car travel at 0.1/0.25 second polls, both directions, connected seat zero/three riders, continuous floor packets without duplicate object/terrain resets, new observers, cargo and SQL zone persistence ("+assertions+" assertions)");
    }

    private static void entrance(int x,int y) {
        Server.surfaceMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_HOLE.id,(byte)0));
        // Native exits have no usable ceiling byte: this is an opening, not a low tunnel.
        Server.caveMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_CAVE_EXIT.id,(byte)0));
    }

    private static void validation() throws Exception {
        check(WurmRailWorld.resolveBuildLayer(200,95,0)==-1&&WurmRailWorld.isEntranceTile(200,95),"surface builder chooses the single canonical cave-floor entrance tile");
        check(WurmRailWorld.validateTile(200,95,-1)==null&&WurmRailWorld.validateTile(200,95,0)!=null,"open entrance accepts cave-floor track without creating a second surface rail");
        for(int[] delta:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
            int x=200+delta[0],y=95+delta[1];
            int outside=Server.caveMesh.getTile(x,y);
            Server.caveMesh.setTile(x,y,Tiles.encode((short)50,Tiles.Tile.TILE_CAVE_WALL.id,(byte)50));
            check(WurmRailWorld.validateEdge(x,y,0,200,95,-1)==null&&WurmRailWorld.validateEdge(200,95,-1,x,y,0)==null,"real level portal permits either travel direction without requiring excavated rock underneath the surface "+x+","+y);
            double borderX=delta[0]<0?800:delta[0]>0?804:802;
            double borderY=delta[1]<0?380:delta[1]>0?384:382;
            double outsideX=borderX+delta[0]*.4167,outsideY=borderY+delta[1]*.4167;
            double insideX=borderX-delta[0]*.8333,insideY=borderY-delta[1]*.8333;
            WurmRailWorld.validateSegment(outsideX,outsideY,0,insideX,insideY,-1);
            WurmRailWorld.validateSegment(insideX,insideY,-1,outsideX,outsideY,0);
            check(true,"short native poll crosses a solid-backed portal without float boundary rounding in cardinal direction "+Arrays.toString(delta));
            Server.caveMesh.setTile(x,y,outside);
        }
        for(boolean surfaced:new boolean[]{true,false}) {
            com.wurmonline.server.zones.VolaTile tile=Zones.getOrCreateTile(200,95,surfaced);
            Fence fence=new DbFence(70000+(surfaced?0:1),StructureConstantsEnum.FENCE_STONEWALL_HIGH,StructureStateEnum.FINISHED,
                    0,200,95,0,50f,50f,0L,Tiles.TileBorderDirection.DIR_DOWN,Zones.getZone(200,95,surfaced).getId(),surfaced,0f,surfaced?0:-1,0);
            tile.addFence(fence);
            try {
                check(WurmRailWorld.validateEdge(199,95,0,200,95,-1)!=null&&WurmRailWorld.validateEdge(200,95,-1,199,95,0)!=null,
                        "native "+(surfaced?"surface":"cave")+" fence on the shared portal lip blocks both travel directions");
            } finally { tile.removeFence(fence); }
        }
        check(WurmRailWorld.validateEdge(199,94,0,200,95,-1)!=null,"portal cannot cut diagonally through a cave wall");
        check(WurmRailWorld.validateEdge(199,95,0,199,95,-1)!=null,"portal cannot teleport vertically on one tile");
        check(WurmRailWorld.validateEdge(201,95,0,202,95,-1)!=null,"ordinary underground track cannot link through intact ground");
        int saved=Server.surfaceMesh.getTile(200,95);
        Server.surfaceMesh.setTile(200,95,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
        check(WurmRailWorld.validateTile(200,95,-1)!=null&&WurmRailWorld.validateEdge(199,95,0,200,95,-1)!=null,"a sealed cave mouth cannot be used as a portal");
        Server.surfaceMesh.setTile(200,95,saved);
        saved=Server.caveMesh.getTile(200,95);
        Server.caveMesh.setTile(200,95,Tiles.encode((short)47,Tiles.Tile.TILE_CAVE_EXIT.id,(byte)0));
        check(WurmRailWorld.validateEdge(199,95,0,200,95,-1)!=null,"portal rejects mismatched surface and cave lip heights");
        Server.caveMesh.setTile(200,95,saved);
        saved=Server.caveMesh.getTile(201,95);
        Server.caveMesh.setTile(201,95,Tiles.encode((short)50,Tiles.Tile.TILE_CAVE.id,(byte)29));
        check(WurmRailWorld.validateEdge(200,95,-1,201,95,-1)!=null,"exit does not bypass tunnel headroom validation");
        Server.caveMesh.setTile(201,95,saved);
        boolean rejected=false;
        try { WurmRailWorld.validateSegment(798,382,0,814,382,-1); }catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"cross-layer native movement cannot jump past an entrance into a distant cave tile");
        Item engine=WurmRailWorld.spawnLocomotive(null,798,382,0);
        Item cargo=ItemFactory.createItem(ItemList.plank,67f,"Blocked portal fixture");
        try {
            check(engine.insertItem(cargo),"blocked entrance fixture accepts native cargo");
            int zone=engine.getZoneId();float rotation=engine.getRotation();
            saved=Server.surfaceMesh.getTile(200,95);
            Server.surfaceMesh.setTile(200,95,Tiles.encode((short)50,Tiles.Tile.TILE_DIRT.id,(byte)0));
            rejected=false;
            try { WurmRailWorld.moveCar(engine.getWurmId(),801,382,90,-1); }catch(IllegalArgumentException expected) { rejected=true; }
            Server.surfaceMesh.setTile(200,95,saved);
            check(rejected&&engine.isOnSurface()&&engine.getZoneId()==zone&&engine.getPosX()==798
                    &&engine.getPosY()==382&&engine.getRotation()==rotation&&cargo.getParentId()==engine.getWurmId()&&cargo.isOnSurface(),
                    "invalid native portal movement changes no vehicle pose, zone, layer or cargo");
        } finally { Items.destroyItem(engine.getWurmId()); }
    }

    private static void runCase(int count,double stepSeconds) throws Exception {
        RailNetwork network=new RailNetwork();List<Item> cars=new ArrayList<Item>();List<Player> players=new ArrayList<Player>();
        WurmRailRuntime runtime=new WurmRailRuntime();String prefix="nativeTunnel"+count+"_";Player newcomer=null;
        try {
            for(int x=194;x<=216;x++) {
                int layer=x>=200&&x<=210?-1:0;
                Node node=network.addNode(prefix+x,x,95,layer);
                node.markerId=WurmRailWorld.spawnTrackMarker(null,node.worldX(),node.worldY(),layer).getWurmId();
                if(x>194) {
                    Node previous=network.nodes.get(prefix+(x-1));
                    check(WurmRailWorld.validateEdge(previous.tileX,previous.tileY,previous.layer,x,95,layer)==null,"native world verifies each physical tunnel link");
                    if(previous.layer==layer)network.connect(previous.name,node.name);else network.connectEntrance(previous.name,node.name);
                }
            }
            network.setStation(prefix+213,true,.25);network.setStation(prefix+197,true,.25);
            network.signals().add(prefix+"outsideWest",prefix+199,6,-10L);
            network.signals().add(prefix+"insideEast",prefix+205,6,-10L);
            network.signals().add(prefix+"insideWest",prefix+205,2,-10L);
            network.signals().add(prefix+"outsideEast",prefix+211,2,-10L);
            List<Long> ids=new ArrayList<Long>();
            for(int index=0;index<count;index++) {
                Node node=network.nodes.get(prefix+(198-index));
                Item car=index==0?WurmRailWorld.spawnLocomotive(null,node.worldX(),node.worldY(),0):WurmRailWorld.spawnCart(null,node.worldX(),node.worldY(),0);
                cars.add(car);ids.add(car.getWurmId());
            }
            Train train=network.createTrain(prefix+"service",-10L,ids,prefix+198,prefix+199);
            network.setRoute(train.name,Arrays.asList(prefix+213,prefix+197),.25);
            Item cargo=ItemFactory.createItem(ItemList.plank,83f,"Native cave fixture");
            check(cars.get(count-1).insertItem(cargo),"tunnel fixture cargo inserts in last car");
            Path state=Paths.get(com.wurmonline.server.ServerDirInfo.getFileDBPath()).resolve("native-tunnel-"+count+".properties");
            runtime.configure(new Properties());set(runtime,"network",network);set(runtime,"ready",true);set(runtime,"stateFile",state);
            call(runtime,"initService",new Class<?>[0]);call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
            Player first=player(998500000L+count*2048L,cars.get(0));players.add(first);
            check(WurmRailWorld.board(first,cars.get(0))&&Vehicles.getVehicle(cars.get(0)).seats[0].occupant==first.getWurmId(),"actual passenger boards locomotive seat zero");
            Item last=cars.get(count-1);Vehicle vehicle=Vehicles.getVehicle(last);
            List<Integer> fake=new ArrayList<Integer>();
            for(int seat=0;seat<3;seat++)if(!vehicle.seats[seat].isOccupied()) { vehicle.seats[seat].occupant=123L;fake.add(seat); }
            Player fourth=player(998500256L+count*2048L,last);players.add(fourth);
            check(WurmRailWorld.board(fourth,last)&&vehicle.seats[3].occupant==fourth.getWurmId(),"actual passenger boards last car seat three");
            for(int seat:fake)vehicle.seats[seat].occupant=-10L;
            network.movementGuard=poses->{
                try { return (Boolean)call(runtime,"validateMotion",new Class<?>[]{List.class},poses); }
                catch(Exception failed) { throw new IllegalStateException(failed); }
            };
            Map<Long,Integer> changes=new HashMap<Long,Integer>();boolean mixed=false,savedMixed=false,rollbackChecked=false,preparationChecked=false;int arrivals=0,routeIndex=train.routeIndex;
            network.start(train.name);
            for(int step=0;step<2200&&arrivals<2;step++) {
                List<Pose> previousPoses=new ArrayList<Pose>(train.poses);
                Map<Long,Boolean> oldSurface=new HashMap<Long,Boolean>();for(Item car:cars)oldSurface.put(car.getWurmId(),car.isOnSurface());
                Map<Long,Float> oldZ=new HashMap<Long,Float>();for(Item car:cars)oldZ.put(car.getWurmId(),car.getPosZ());
                double oldSpeed=train.speed;
                network.tick(stepSeconds);clearPackets();
                Field errorField=WurmRailRuntime.class.getDeclaredField("validationError");errorField.setAccessible(true);
                Field errorsField=WurmRailRuntime.class.getDeclaredField("motionErrors");errorsField.setAccessible(true);
                check(train.powered,"tunnel service count="+count+", step="+step+" retains power after core movement: "+train.status
                        +"; native error="+errorField.get(runtime)+"; motion errors="+errorsField.get(runtime)+"; "+describe(train.poses));
                if(!preparationChecked)for(Pose pose:train.poses)if(oldSurface.get(pose.carId).booleanValue()!=(pose.layer==0)) {
                    checkPreparationRollback(pose,players);preparationChecked=true;break;
                }
                call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
                check(train.powered,"tunnel service retains power during every portal approach: "+train.status);
                Set<Integer> layers=new HashSet<Integer>();boolean transitioned=false;
                for(Pose pose:train.poses) {
                    Item car=Items.getItem(pose.carId);layers.add(pose.layer);
                    check(car.isOnSurface()==(pose.layer==0)&&Math.hypot(car.getPosX()-pose.x,car.getPosY()-pose.y)<.001,"native vehicle follows its own per-car layer and position");
                    Pose before=previousPoses.get(train.poses.indexOf(pose));
                    check(Math.hypot(car.getPosX()-before.x,car.getPosY()-before.y)<=Math.max(oldSpeed,train.speed)*stepSeconds+.8*stepSeconds*stepSeconds+.002,"native portal movement never consumes more than one poll of train travel; step="+step+" status="+train.status);
                    check(Math.abs(car.getPosZ()-oldZ.get(car.getWurmId()))<=.13,"raised tunnel roof never causes a vertical vehicle jump");
                    if(Math.hypot(car.getPosX()-before.x,car.getPosY()-before.y)>.001)for(Player viewer:players)
                        check(lastMove(viewer,car),"known rider receives continuous native packet 72 with exact rail floor height, including portal approaches; step="+step+" viewer="+viewer.getWurmId()+" car="+car.getWurmId()+" packets="+packetDescription(viewer,car));
                    check(car.getZoneId()==Zones.getZone(car.getTileX(),car.getTileY(),pose.layer==0).getId(),"native zone index matches each individual car layer");
                    if(oldSurface.get(car.getWurmId()).booleanValue()!=car.isOnSurface()) {
                        transitioned=true;
                        Integer changeCount=changes.get(car.getWurmId());changes.put(car.getWurmId(),changeCount==null?1:changeCount+1);
                        check(savedZone(car)==car.getZoneId(),"native layer transition immediately persists the zone in SQLite");
                        for(Player viewer:players) {
                            check(noReannounce(viewer,car),"native portal transfer preserves existing client car instead of NEW/delete interpolation reset");
                            check(lastLayer(viewer,car),"real observer receives the actual native command 30 for the vehicle destination layer");
                            check(!fullCaveResend(viewer),"entering a loaded cave does not force another full 48 by 48 terrain strip");
                            check(destinationMembership(viewer,car),"portal view transfer leaves the existing car in its native destination view only");
                        }
                        if(newcomer==null) {
                            newcomer=player(998520000L+count*2048L,car);
                            check(wasAnnounced(newcomer,car),"a new observer still receives the normal native moving-item announcement");
                        }
                    }
                }
                mixed|=layers.size()>1;
                for(Player passenger:players) {
                    Item car=passenger==first?cars.get(0):last;
                    check(passenger.getVehicle()==car.getWurmId()&&passenger.getLayer()==(car.isOnSurface()?0:-1),"both native seat zero and seat three retain their attachment and vehicle layer");
                    check(passenger.getCurrentTile()!=null&&passenger.getCurrentTile().isOnSurface()==car.isOnSurface(),"native passenger tile registration changes layers with its car");
                    Seat occupied=Vehicles.getVehicle(car).getSeatFor(passenger.getWurmId());
                    check(Math.abs(passenger.getPositionZ()-car.getPosZ()-occupied.offz)<.001,"rider camera remains at the real seat height over the rail floor");
                }
                check(cargo.getParentId()==last.getWurmId()&&cargo.isOnSurface()==last.isOnSurface(),"cargo stays inside its car and follows native layer flags");
                if(!rollbackChecked&&transitioned&&(count==1||layers.size()>1)) {
                    // Runtime's whole-tick recovery restores native cars in this
                    // same engine-to-tail order. Exercise that real reverse
                    // portal transfer after an actual mixed-layer commit.
                    for(Pose previous:previousPoses)
                        WurmRailWorld.moveCar(previous.carId,previous.x,previous.y,previous.rotation,previous.layer);
                    checkNativeState(previousPoses,players,first,last,cargo,"portal rollback");
                    for(Item car:cars)for(Player viewer:players)check(noReannounce(viewer,car),"reverse portal rollback preserves already visible native cars without resetting interpolation");
                    for(Item car:cars)check(savedZone(car)==car.getZoneId(),"portal rollback immediately restores each native SQL zone");
                    call(runtime,"applyPoses",new Class<?>[]{Train.class},train);
                    checkNativeState(train.poses,players,first,last,cargo,"portal reapply");
                    rollbackChecked=true;
                }
                if(layers.size()>1&&!savedMixed) {
                    network.save(state);RailNetwork restored=RailNetwork.load(state);Train loaded=restored.trains.get(train.name);
                    check(loaded.poses.size()==count&&restored.nodes.get(prefix+199).links.contains(prefix+200),"mixed-layer saved train and explicit physical portal edge restore");
                    for(int index=0;index<count;index++)check(loaded.poses.get(index).layer==train.poses.get(index).layer,"each saved car restores its own entrance layer");
                    savedMixed=true;
                }
                if(routeIndex!=train.routeIndex) { arrivals++;routeIndex=train.routeIndex; }
            }
            check(arrivals==2,"native signalled station service reaches both ends through the tunnel: "+train.status);
            check(rollbackChecked,"native portal restoration and reapply ran after a real per-car layer transition");
            check(preparationChecked,"injected native view preparation failure restores all known-observer memberships before car movement");
            check(count==1||mixed&&savedMixed,"a long train straddles portal layers without moving all cars at once");
            for(Item car:cars)check(changes.containsKey(car.getWurmId())&&changes.get(car.getWurmId())>=4,"every car crosses both entrances in both directions");
            check(cargo.getQualityLevel()==83f,"cave travel preserves exact cargo quality");
        } finally {
            network.movementGuard=null;
            if(newcomer!=null) { newcomer.destroyVisionArea();connections.remove(newcomer.getWurmId());callStatic(PassengerWorldSmoke.class,"removePlayer",new Class<?>[]{Player.class},newcomer); }
            for(Player passenger:players) { passenger.disembark(false);passenger.destroyVisionArea();connections.remove(passenger.getWurmId());callStatic(PassengerWorldSmoke.class,"removePlayer",new Class<?>[]{Player.class},passenger); }
            for(Train train:new ArrayList<Train>(network.trains.values())) { network.stop(train.name);network.removeTrain(train.name); }
            for(Item car:cars)if(Items.getItemOptional(car.getWurmId()).isPresent())Items.destroyItem(car.getWurmId());
            for(Node node:network.nodes.values())if(node.markerId>0&&Items.getItemOptional(node.markerId).isPresent())Items.destroyItem(node.markerId);
        }
    }

    private static String describe(List<Pose> poses) throws Exception {
        StringBuilder result=new StringBuilder();
        for(Pose pose:poses) {
            Item car=Items.getItem(pose.carId);
            result.append("car ").append(pose.carId).append(" target ").append(pose.x).append(',').append(pose.y).append('/').append(pose.layer)
                    .append(" native ").append(car.getPosX()).append(',').append(car.getPosY()).append('/').append(car.isOnSurface()?0:-1).append(';');
        }
        return result.toString();
    }

    private static void checkNativeState(List<Pose> expected,List<Player> players,Player first,Item last,Item cargo,String operation) throws Exception {
        Map<Long,Pose> poses=new HashMap<Long,Pose>();
        for(Pose pose:expected) {
            poses.put(pose.carId,pose);Item car=Items.getItem(pose.carId);
            check(car.isOnSurface()==(pose.layer==0)&&Math.hypot(car.getPosX()-pose.x,car.getPosY()-pose.y)<.001
                    &&Math.abs(car.getRotation()-pose.rotation)<.001,"native vehicle position, heading and layer match "+operation);
            check(car.getZoneId()==Zones.getZone(car.getTileX(),car.getTileY(),pose.layer==0).getId(),"native vehicle zone matches "+operation);
        }
        for(Player passenger:players) {
            long carId=passenger==first?expected.get(0).carId:last.getWurmId();Pose pose=poses.get(carId);
            Vehicle vehicle=Vehicles.getVehicle(Items.getItem(carId));int seat=passenger==first?0:3;
            check(passenger.getVehicle()==carId&&vehicle.seats[seat].occupant==passenger.getWurmId()
                    &&passenger.getLayer()==pose.layer,"native seat "+seat+" attachment and player layer match "+operation);
            check(passenger.getCurrentTile()!=null&&passenger.getCurrentTile().isOnSurface()==(pose.layer==0)
                    &&passenger.getCurrentTile().getTileX()==passenger.getTileX()
                    &&passenger.getCurrentTile().getTileY()==passenger.getTileY(),"native passenger tile registration matches "+operation);
        }
        check(cargo.getParentId()==last.getWurmId()&&cargo.isOnSurface()==(poses.get(last.getWurmId()).layer==0)
                &&cargo.getQualityLevel()==83f,"native cargo parent, quality and layer match "+operation);
    }

    private static Player player(long id,Item car) throws Exception {
        Player passenger=(Player)callStatic(PassengerWorldSmoke.class,"player",new Class<?>[]{long.class,float.class,float.class},id,car.getPosX(),car.getPosY());
        // Native layer registration runs Player.addTileMoved, which uses loaded
        // achievement bit sets absent from the socket-free Unsafe fixture.
        for(String name:new String[]{"flagBits","flag2Bits","sessionFlagBits","abilityBits"}) {
            Field field=com.wurmonline.server.players.PlayerInfo.class.getDeclaredField(name);field.setAccessible(true);field.set(passenger.getSaveFile(),new BitSet());
        }
        Field model=com.wurmonline.server.players.PlayerInfo.class.getDeclaredField("modelName");model.setAccessible(true);model.set(passenger.getSaveFile(),"model.creature.humanoid.human.player.male");
        Field statusTemplate=com.wurmonline.server.creatures.CreatureStatus.class.getDeclaredField("template");statusTemplate.setAccessible(true);statusTemplate.set(passenger.getStatus(),passenger.getTemplate());
        com.wurmonline.server.skills.Skills skills=com.wurmonline.server.skills.SkillsFactory.createSkills("Tunnel passenger");
        Field skillsField=Creature.class.getDeclaredField("skills");skillsField.setAccessible(true);skillsField.set(passenger,skills);
        passenger.getBody().createBodyParts();
        // The legacy queued communicator has incomplete packet sizes for native
        // movement/attachment. Use the production communicator with the existing
        // socket-free byte buffer so the real wire format is exercised.
        PassengerWorldSmoke.MemoryConnection connection=(PassengerWorldSmoke.MemoryConnection)passenger.getCommunicator().getConnection();
        Communicator communicator=new PlayerCommunicator(passenger,connection);
        Field justLogged=Communicator.class.getDeclaredField("justLoggedIn");justLogged.setAccessible(true);justLogged.set(communicator,false);
        Field communicatorField=Creature.class.getDeclaredField("communicator");communicatorField.setAccessible(true);communicatorField.set(passenger,communicator);
        connections.put(passenger.getWurmId(),connection);
        Constructor<VisionArea> constructor=VisionArea.class.getDeclaredConstructor(Creature.class,int.class);constructor.setAccessible(true);
        VisionArea vision=constructor.newInstance(passenger,4);
        Field visionField=Creature.class.getDeclaredField("visionArea");visionField.setAccessible(true);visionField.set(passenger,vision);
        vision.getSurface().initialize();vision.getUnderGround().initialize();
        return passenger;
    }
    private static void clearPackets() throws Exception {
        Field queue=PlayerCommunicatorQueued.class.getDeclaredField("MESSAGES_TO_PLAYERS");queue.setAccessible(true);((Queue<?>)queue.get(null)).clear();
        for(PassengerWorldSmoke.MemoryConnection connection:connections.values())connection.packets.clear();
    }
    private static List<byte[]> packets(Player viewer) throws Exception {
        return connections.get(viewer.getWurmId()).packets;
    }
    private static boolean lastMove(Player viewer,Item car) throws Exception {
        byte[] last=null;for(byte[] packet:packets(viewer))if(packet.length>=18&&(packet[0]==36||packet[0]==72)&&ByteBuffer.wrap(packet,1,8).getLong()==car.getWurmId())last=packet;
        if(last==null||last[0]!=72)return false;ByteBuffer data=ByteBuffer.wrap(last);data.get();data.getLong();
        float z=data.getFloat(),x=data.getFloat();data.get();float y=data.getFloat();
        return Math.abs(x-car.getPosX())<.001&&Math.abs(y-car.getPosY())<.001&&Math.abs(z-car.getPosZ())<.001;
    }
    private static boolean noReannounce(Player viewer,Item car) throws Exception {
        for(byte[] packet:packets(viewer))if(packet.length>=9&&(packet[0]==108||packet[0]==14)&&ByteBuffer.wrap(packet,1,8).getLong()==car.getWurmId())return false;
        return true;
    }
    private static boolean wasAnnounced(Player viewer,Item car) throws Exception {
        for(byte[] packet:packets(viewer))if(packet.length>=9&&packet[0]==108&&ByteBuffer.wrap(packet,1,8).getLong()==car.getWurmId())return true;
        return false;
    }
    private static boolean lastLayer(Player viewer,Item car) throws Exception {
        byte[] last=null;for(byte[] packet:packets(viewer))if(packet.length==10&&packet[0]==30&&ByteBuffer.wrap(packet,1,8).getLong()==car.getWurmId())last=packet;
        return last!=null&&last[9]==(byte)(car.isOnSurface()?0:-1);
    }
    private static boolean fullCaveResend(Player viewer) throws Exception {
        for(byte[] packet:packets(viewer))if(packet.length>=11&&packet[0]==102&&ByteBuffer.wrap(packet,7,4).getShort()==48&&ByteBuffer.wrap(packet,9,2).getShort()==48)return true;
        return false;
    }
    @SuppressWarnings("unchecked")
    private static Set<Item> nativeItems(com.wurmonline.server.zones.VirtualZone zone) throws Exception {
        Field items=com.wurmonline.server.zones.VirtualZone.class.getDeclaredField("items");items.setAccessible(true);
        Set<Item> result=(Set<Item>)items.get(zone);return result==null?Collections.<Item>emptySet():result;
    }
    private static boolean destinationMembership(Player viewer,Item car) throws Exception {
        boolean surface=nativeItems(viewer.getVisionArea().getSurface()).contains(car),cave=nativeItems(viewer.getVisionArea().getUnderGround()).contains(car);
        return car.isOnSurface()?surface&&!cave:cave&&!surface;
    }
    private static void checkPreparationRollback(Pose target,List<Player> viewers) throws Exception {
        Item car=Items.getItem(target.carId);float oldX=car.getPosX(),oldY=car.getPosY(),oldZ=car.getPosZ();int oldZone=car.getZoneId();boolean oldSurface=car.isOnSurface();
        Map<Player,boolean[]> oldMembership=new HashMap<Player,boolean[]>();
        for(Player viewer:viewers)oldMembership.put(viewer,new boolean[]{nativeItems(viewer.getVisionArea().getSurface()).contains(car),nativeItems(viewer.getVisionArea().getUnderGround()).contains(car)});
        Player chosen=viewers.get(viewers.size()-1);
        com.wurmonline.server.zones.VirtualZone destination=target.layer==0?chosen.getVisionArea().getSurface():chosen.getVisionArea().getUnderGround();
        Field items=com.wurmonline.server.zones.VirtualZone.class.getDeclaredField("items");items.setAccessible(true);Object saved=items.get(destination);
        items.set(destination,new FailOnceItems(nativeItems(destination),car.getWurmId()));boolean rejected=false;
        try { WurmRailWorld.moveCar(car.getWurmId(),target.x,target.y,target.rotation,target.layer); }
        catch(IllegalStateException expected) { if(!"Injected native view preparation failure".equals(expected.getMessage()))throw expected;rejected=true; }
        finally { items.set(destination,saved); }
        check(rejected,"real portal observer preparation surfaces a native view failure before moving the vehicle");
        check(car.getPosX()==oldX&&car.getPosY()==oldY&&car.getPosZ()==oldZ&&car.getZoneId()==oldZone&&car.isOnSurface()==oldSurface,"failed view preparation leaves the actual native car pose and zone unchanged");
        for(Player viewer:viewers) {
            boolean[] before=oldMembership.get(viewer);
            check(nativeItems(viewer.getVisionArea().getSurface()).contains(car)==before[0]&&nativeItems(viewer.getVisionArea().getUnderGround()).contains(car)==before[1],"partially prepared native observer memberships are restored on failure");
        }
    }
    private static final class FailOnceItems extends HashSet<Item> {
        private long rejectedId;
        FailOnceItems(Set<Item> original,long rejectedId) { super(original);this.rejectedId=rejectedId; }
        @Override public boolean add(Item item) {
            if(item.getWurmId()==rejectedId) { rejectedId=0;throw new IllegalStateException("Injected native view preparation failure"); }
            return super.add(item);
        }
    }
    private static String packetDescription(Player viewer,Item car) throws Exception {
        StringBuilder result=new StringBuilder("link=").append(viewer.hasLink()).append(" native=").append(car.getPosX()).append(',').append(car.getPosY()).append(',').append(car.getPosZ());
        for(byte[] packet:packets(viewer))if(packet.length>=9&&ByteBuffer.wrap(packet,1,8).getLong()==car.getWurmId())result.append(" ").append(Arrays.toString(packet));
        return result.toString();
    }
    private static int savedZone(Item item) throws Exception {
        Connection connection=DbConnector.getItemDbCon();
        try(PreparedStatement statement=connection.prepareStatement("SELECT ZONEID FROM ITEMS WHERE WURMID=?")) {
            statement.setLong(1,item.getWurmId());try(ResultSet row=statement.executeQuery()) { check(row.next(),"moved native item remains in the item database");return row.getInt(1); }
        } finally { DbConnector.returnConnection(connection); }
    }
    private static void set(Object instance,String name,Object value) throws Exception { Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);field.set(instance,value); }
    private static Object call(Object instance,String name,Class<?>[] types,Object... arguments) throws Exception {
        Method method=instance.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try { return method.invoke(instance,arguments); } catch(InvocationTargetException failure) { if(failure.getCause() instanceof Exception)throw (Exception)failure.getCause();throw failure; }
    }
    private static Object callStatic(Class<?> owner,String name,Class<?>[] types,Object... arguments) throws Exception {
        Method method=owner.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,arguments);
    }
    private static void check(boolean success,String message) { assertions++;if(!success)throw new AssertionError(message); }
}
