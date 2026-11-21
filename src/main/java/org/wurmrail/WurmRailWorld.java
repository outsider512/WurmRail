package org.wurmrail;

import com.wurmonline.mesh.CaveTile;
import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Items;
import com.wurmonline.server.Players;
import com.wurmonline.server.Server;
import com.wurmonline.server.behaviours.Seat;
import com.wurmonline.server.behaviours.Vehicle;
import com.wurmonline.server.behaviours.VehicleBehaviour;
import com.wurmonline.server.behaviours.Vehicles;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.MovementScheme;
import com.wurmonline.server.creatures.VisionArea;
import com.wurmonline.server.items.AdvancedCreationEntry;
import com.wurmonline.server.items.CreationCategories;
import com.wurmonline.server.items.CreationEntryCreator;
import com.wurmonline.server.items.CreationRequirement;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemFactory;
import com.wurmonline.server.items.ItemList;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.items.ItemTypes;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.server.structures.Blocking;
import com.wurmonline.server.structures.BridgePart;
import com.wurmonline.server.zones.VirtualZone;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.ItemMaterials;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gotti.wurmunlimited.modsupport.ItemTemplateBuilder;
import org.gotti.wurmunlimited.modsupport.vehicles.ModVehicleBehaviour;
import org.gotti.wurmunlimited.modsupport.vehicles.ModVehicleBehaviours;
import org.gotti.wurmunlimited.modsupport.vehicles.VehicleFacade;
import org.wurmrail.core.RailNetwork.Pose;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.Node;
import org.wurmrail.core.RailNetwork.ApproachSeam;
import org.wurmrail.shared.RailConsistFrame;

/** Wurm-specific item, collision and passenger integration. Call on the server thread. */
public final class WurmRailWorld {
    private static final Logger LOG = Logger.getLogger(WurmRailWorld.class.getName());
    public static int locomotiveTemplateId;
    public static int cartTemplateId;
    public static int trackTemplateId;
    public static int trackSectionTemplateId;
    public static int trestleDeckTemplateId, trestleBentTemplateId;
    public static int stationTemplateId;
    /** Keep both persistent identifiers so existing worlds retain their physical station items. */
    public static int pickupTemplateId, dropoffTemplateId;
    public static int junctionAlignmentTemplateId;
    public static int signalTemplateId;
    public static final int SEATS_PER_CAR = 4;
    /** Native maxItemWeight is the total payload, not a per-stack restriction. */
    public static final int CARGO_WAGON_PAYLOAD_GRAMS = 10000000;
    /** Wurm terrain heights are in decimetres: 20 is two metres over a four metre tile. */
    public static final int MAX_TILE_SLOPE = 20;
    private static final long NO_ID = -10L;
    private static final Map<Long, Long> lastPositionSave = new HashMap<Long, Long>();
    private static Field visibleItemsField;
    private static boolean templatesCreated;
    private static boolean recipesCreated;
    private static boolean reportedConsistError;

    private WurmRailWorld() { }

    /** Run from ItemTemplatesCreatedListener.onItemTemplatesCreated(). */
    public static void createTemplates() throws IOException {
        if (templatesCreated) return;
        ItemTemplateFactory factory = ItemTemplateFactory.getInstance();
        try {
            ItemTemplate wagon = factory.getTemplate(ItemList.wagon);
            ItemTemplate cart = factory.getTemplate(ItemList.cartLarge);
            ItemTemplate marker = factory.getTemplate(ItemList.catseyeBlind);
            locomotiveTemplateId = createCarTemplate("wurmrail.locomotive.v1", "rail locomotive",
                    "rail locomotives", "A powered rail locomotive. Place it on rails, then use Railway controls to drive or plan a route.",
                    wagon, "model.wurmrail.locomotive.", 400000, 300000, 35.0f).getTemplateId();
            cartTemplateId = createCarTemplate("wurmrail.cart.v1", "rail cart", "rail carts",
                    "A rail cart with four passenger seats and a cargo hold. Couple it to a locomotive.",
                    cart, "model.wurmrail.cart.", 200000, CARGO_WAGON_PAYLOAD_GRAMS, 25.0f).getTemplateId();
            trackTemplateId = new ItemTemplateBuilder("wurmrail.track.v1")
                    .name("rail track", "rail tracks", "Steel rails and wooden sleepers. Connections determine the track shape.")
                    .itemTypes(new short[] {ItemTypes.ITEM_TYPE_NOTAKE, ItemTypes.ITEM_TYPE_NOMOVE,
                            ItemTypes.ITEM_TYPE_DECORATION, ItemTypes.ITEM_TYPE_REPAIRABLE,
                            ItemTypes.ITEM_TYPE_NAMED, ItemTypes.ITEM_TYPE_WOOD})
                    .imageNumber(marker.getImageNumber()).behaviourType((short)1)
                    .dimensions(400, 400, 25).weightGrams(10000).material(ItemMaterials.MATERIAL_WOOD_BIRCH)
                    .modelName("model.wurmrail.track.0.").difficulty(10.0f).decayTime(Long.MAX_VALUE)
                    .build().getTemplateId();
            trackSectionTemplateId = new ItemTemplateBuilder("wurmrail.tracksection.v1")
                    .name("rail track section", "rail track sections", "A portable section of rails and sleepers. Carry it and use Build railway on a ground tile.")
                    .itemTypes(new short[] {ItemTypes.ITEM_TYPE_NAMED, ItemTypes.ITEM_TYPE_WOOD,
                            ItemTypes.ITEM_TYPE_REPAIRABLE})
                    .imageNumber(marker.getImageNumber()).behaviourType((short)1)
                    .dimensions(20, 30, 100).weightGrams(20000).material(ItemMaterials.MATERIAL_WOOD_BIRCH)
                    .modelName("model.wurmrail.track.17.").difficulty(15.0f).decayTime(Long.MAX_VALUE)
                    .build().getTemplateId();
            RailModelRegistry.register(locomotiveTemplateId,cartTemplateId,trackTemplateId);
            trestleDeckTemplateId = createTrestlePiece("deck", "trestle deck", "model.wurmrail.trestledeck.0.", marker);
            trestleBentTemplateId = createTrestlePiece("bent", "timber bent", "model.wurmrail.trestlebent.1.", marker);
            RailModelRegistry.registerTrestle(trestleDeckTemplateId,trestleBentTemplateId);
            RailModelRegistry.registerTrackSection(trackSectionTemplateId);
            pickupTemplateId=createPassengerPlatform("wurmrail.pickup.v1","rail station","model.wurmrail.platform.pickup.",marker);
            dropoffTemplateId=createPassengerPlatform("wurmrail.dropoff.v1","rail station","model.wurmrail.platform.pickup.",marker);
            stationTemplateId=pickupTemplateId;
            RailModelRegistry.registerPassengerPlatforms(pickupTemplateId,dropoffTemplateId);
            junctionAlignmentTemplateId=new ItemTemplateBuilder("wurmrail.junctionalignment.v1")
                    .name("junction alignment","junction alignments","The highlighted path joins two absolute sides in both directions. Use Cycle junction alignment to change the switch.")
                    .itemTypes(new short[]{ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_NOMOVE,ItemTypes.ITEM_TYPE_DECORATION,ItemTypes.ITEM_TYPE_NAMED})
                    .imageNumber(marker.getImageNumber()).behaviourType((short)1).dimensions(400,400,8).weightGrams(1000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.alignment.0.").decayTime(Long.MAX_VALUE).build().getTemplateId();
            RailModelRegistry.registerJunctionAlignment(junctionAlignmentTemplateId);
            signalTemplateId=new ItemTemplateBuilder("wurmrail.signal.v1")
                    .name("railway signal","railway signals","A directional block signal. Red means stop. Green grants passage only to the train owning its route reservation. Use Railway signal for configuration and status.")
                    .itemTypes(new short[]{ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_NOMOVE,ItemTypes.ITEM_TYPE_DECORATION,ItemTypes.ITEM_TYPE_NAMED})
                    .imageNumber(marker.getImageNumber()).behaviourType((short)1).dimensions(55,55,255).weightGrams(15000)
                    .material(ItemMaterials.MATERIAL_IRON).modelName("model.wurmrail.signal.0.red.").decayTime(Long.MAX_VALUE).build().getTemplateId();
            RailModelRegistry.registerSignal(signalTemplateId);
            RailwayTankCar.createTemplate();
            ModVehicleBehaviour passengerCar = new ModVehicleBehaviour() {
                @Override public void setSettingsForVehicle(Creature creature, Vehicle vehicle) { }
                @Override public void setSettingsForVehicle(Item item, Vehicle vehicle) {
                    VehicleFacade facade = wrap(vehicle);
                    // Native createOnlyPassengerSeats does not update maxPassengers.
                    facade.createPassengerSeats(SEATS_PER_CAR);
                    facade.createOnlyPassengerSeats(SEATS_PER_CAR);
                    facade.setCreature(false);
                    facade.setName(item.getName());
                    facade.setEmbarkString("board");
                    facade.setSkillNeeded(0.0f);
                    facade.setMaxDepth(0.0f);
                    facade.setMaxHeightDiff(MAX_TILE_SLOPE / 10.0f);
                    facade.setMaxSpeed(0.0f);
                    facade.setCommandType((byte)2);
                    facade.setCanHaveEquipment(false);
                    // Passenger-only seats prevent native WASD vehicle steering and hitching.
                    boolean engine=item.getTemplateId()==locomotiveTemplateId;
                    boolean endPlatforms=TankCarRegistry.isTankCar(item.getTemplateId())||LogCarRegistry.isLogCar(item.getTemplateId());
                    // Rider offsets locate the character root, below the cushion.
                    // Lower the locomotive rider/camera so the cab roof clears the head.
                    float seatX=engine?0.48f:0.55f, seatZ=engine?0.55f:0.95f;
                    float firstY=engine?0.65f:endPlatforms?-1.45f:-0.7f, secondY=engine?1.25f:endPlatforms?1.45f:0.7f;
                    // MovementScheme's seat frame is longitudinal X, negative lateral Y.
                    // At heading zero it maps (offx,offy) to world (-offy,offx).
                    // The artwork and native mount joints use lateral X, longitudinal Y.
                    facade.setSeatOffset(0,firstY,seatX,seatZ);
                    facade.setSeatOffset(1,firstY,-seatX,seatZ);
                    facade.setSeatOffset(2,secondY,seatX,seatZ);
                    facade.setSeatOffset(3,secondY,-seatX,seatZ);
                }
            };
            ModVehicleBehaviours.addItemVehicle(locomotiveTemplateId, passengerCar);
            ModVehicleBehaviours.addItemVehicle(cartTemplateId, passengerCar);
            ModVehicleBehaviours.addItemVehicle(RailwayTankCar.templateId, passengerCar);
            RailwayBulkHopper.createTemplate();
            RailwaySteamRockDrill.createTemplate();
            RailwayIndustrialWaterTank.createTemplate();
            RailwayLogBunk.createTemplate();
            RailwaySteamYarder.createTemplate();
            RailwayLogCar.createTemplate();
            ModVehicleBehaviours.addItemVehicle(RailwayLogCar.templateId, passengerCar);
            RailwaySteamWaterPump.createTemplate();
            RailwayCoalItem.createTemplate();
            templatesCreated = true;
        } catch (com.wurmonline.server.items.NoSuchTemplateException e) {
            throw new IOException("Required vanilla wagon, cart or rail-marker model template is missing", e);
        }
    }

    private static int createTrestlePiece(String part, String name, String model, ItemTemplate marker) throws IOException {
        return new ItemTemplateBuilder("wurmrail.trestle."+part+".v1")
                .name(name, name+"s", "Fixed timber railway infrastructure.")
                .itemTypes(new short[]{ItemTypes.ITEM_TYPE_NOTAKE, ItemTypes.ITEM_TYPE_NOMOVE,
                        ItemTypes.ITEM_TYPE_DECORATION, ItemTypes.ITEM_TYPE_WOOD, ItemTypes.ITEM_TYPE_HOVER})
                .imageNumber(marker.getImageNumber()).behaviourType((short)1)
                .dimensions(195, 400, 52).weightGrams(10000).material(ItemMaterials.MATERIAL_WOOD_BIRCH)
                .modelName(model).difficulty(15f).decayTime(Long.MAX_VALUE).build().getTemplateId();
    }

    static Item placeTrestlePiece(int template, Creature builder, float x, float y, float z,
                                  float rotation, int variant, int grade) throws Exception {
        return placeTrestlePiece(template, builder, x, y, z, rotation, variant, grade, null);
    }

    static Item placeTrestlePiece(int template, Creature builder, float x, float y, float z,
                                  float rotation, int variant, int grade, org.wurmrail.shared.TrestlePanel panel) throws Exception {
        Item item = ItemFactory.createItem(template, 50f, builder.getName());
        try {
            item.setPosXYZRotation(x, y, z, rotation);
            item.setAuxData((byte)variant);
            item.setData1(grade);
            item.setLastOwnerId(builder.getWurmId());
            if (panel != null) RailModelRegistry.registerCurvedDeck(item.getWurmId(), panel);
            Zones.getZone((int)(x/4), (int)(y/4), true).addItem(item);
            item.savePosition();
            return item;
        } catch(Exception failure) {
            RailModelRegistry.forgetCurvedDeck(item.getWurmId());
            Items.destroyItem(item.getWurmId());
            throw failure;
        }
    }

    private static int createPassengerPlatform(String identifier,String name,String model,ItemTemplate marker) throws IOException {
        return new ItemTemplateBuilder(identifier).name(name,name+"s","A railway station for arriving and waiting passengers. Stand here to wait for a train; use Station to choose a destination or manage this stop.")
                .itemTypes(new short[]{ItemTypes.ITEM_TYPE_NOTAKE,ItemTypes.ITEM_TYPE_NOMOVE,ItemTypes.ITEM_TYPE_DECORATION,
                        ItemTypes.ITEM_TYPE_NAMED,ItemTypes.ITEM_TYPE_WOOD,ItemTypes.ITEM_TYPE_REPAIRABLE})
                .imageNumber(marker.getImageNumber()).behaviourType((short)1).dimensions(380,380,8).weightGrams(20000)
                .material(ItemMaterials.MATERIAL_WOOD_BIRCH).modelName(model).difficulty(15).decayTime(Long.MAX_VALUE).build().getTemplateId();
    }

    public static boolean isPassengerPlatform(Item item) {
        return item!=null&&((pickupTemplateId!=0&&item.getTemplateId()==pickupTemplateId)||(dropoffTemplateId!=0&&item.getTemplateId()==dropoffTemplateId));
    }
    public static Item spawnPassengerPlatform(Creature actor,int x,int y,int layer,boolean pickup) throws Exception {
        return spawn(pickup?pickupTemplateId:dropoffTemplateId,actor,x*4.0+2,y*4.0+2,layer);
    }
    public static Item spawnStation(Creature actor,int x,int y,int layer) throws Exception {
        return spawn(stationTemplateId,actor,x*4.0+2,y*4.0+2,layer);
    }

    private static ItemTemplate createCarTemplate(String identifier, String name, String plural,
            String description, ItemTemplate model, String modelName, int weight, int payloadGrams, float difficulty) throws IOException {
        return new ItemTemplateBuilder(identifier).name(name, plural, description).size(4)
                .itemTypes(new short[] {ItemTypes.ITEM_TYPE_NAMED, ItemTypes.ITEM_TYPE_HOLLOW,
                        ItemTypes.ITEM_TYPE_NOTAKE, ItemTypes.ITEM_TYPE_WOOD,
                        ItemTypes.ITEM_TYPE_DECORATION, ItemTypes.ITEM_TYPE_REPAIRABLE,
                        ItemTypes.ITEM_TYPE_VEHICLE, ItemTypes.ITEM_TYPE_LOCKABLE,
                        ItemTypes.ITEM_TYPE_CART, ItemTypes.ITEM_TYPE_NOMOVE,
                        ItemTypes.ITEM_TYPE_USES_SPECIFIED_CONTAINER_VOLUME,
                        ItemTypes.ITEM_TYPE_NOWORKPARENT})
                .imageNumber(model.getImageNumber()).behaviourType((short)41)
                .dimensions(130, 120, 410).containerSize(120, 120, 410)
                .maxItemCount(100).maxItemWeight(payloadGrams).weightGrams(weight)
                .material(ItemMaterials.MATERIAL_WOOD_BIRCH).modelName(modelName)
                .difficulty(difficulty).decayTime(Long.MAX_VALUE).build();
    }

    /** Run once after templates exist, usually from ServerStartedListener. */
    public static void createRecipes() {
        if (recipesCreated) return;
        if (!templatesCreated) throw new IllegalStateException("Rail item templates have not been created");
        AdvancedCreationEntry locomotive = CreationEntryCreator.createAdvancedEntry(SkillList.CARPENTRY_FINE,
                ItemList.plank, ItemList.ironBand, locomotiveTemplateId, true, true, 0.0f,
                false, false, CreationCategories.CARTS);
        locomotive.setFinalMaterial(ItemMaterials.MATERIAL_WOOD_BIRCH);
        locomotive.addRequirement(new CreationRequirement(1, ItemList.wheelAxleSmall, 2, true));
        locomotive.addRequirement(new CreationRequirement(2, ItemList.plank, 20, true));
        locomotive.addRequirement(new CreationRequirement(3, ItemList.ironBand, 8, true));
        locomotive.addRequirement(new CreationRequirement(4, ItemList.nailsIronLarge, 4, true));
        AdvancedCreationEntry cart = CreationEntryCreator.createAdvancedEntry(SkillList.CARPENTRY_FINE,
                ItemList.plank, ItemList.ironBand, cartTemplateId, true, true, 0.0f,
                false, false, CreationCategories.CARTS);
        cart.setFinalMaterial(ItemMaterials.MATERIAL_WOOD_BIRCH);
        cart.addRequirement(new CreationRequirement(1, ItemList.wheelAxleSmall, 2, true));
        cart.addRequirement(new CreationRequirement(2, ItemList.plank, 12, true));
        cart.addRequirement(new CreationRequirement(3, ItemList.ironBand, 4, true));
        cart.addRequirement(new CreationRequirement(4, ItemList.nailsIronLarge, 2, true));
        AdvancedCreationEntry track = CreationEntryCreator.createAdvancedEntry(SkillList.CARPENTRY,
                ItemList.plank, ItemList.ironBand, trackSectionTemplateId, true, true, 0.0f,
                false, false, CreationCategories.CONSTRUCTION_MATERIAL);
        track.setFinalMaterial(ItemMaterials.MATERIAL_WOOD_BIRCH);
        track.addRequirement(new CreationRequirement(1, ItemList.plank, 4, true));
        track.addRequirement(new CreationRequirement(2, ItemList.ironBand, 2, true));
        track.addRequirement(new CreationRequirement(3, ItemList.nailsIronLarge, 1, true));
        RailwayBulkHopper.createRecipes();
        RailwaySteamRockDrill.createRecipes();
        RailwayTankCar.createRecipes();
        RailwayIndustrialWaterTank.createRecipes();
        RailwayLogBunk.createRecipes();
        RailwaySteamYarder.createRecipes();
        RailwayLogCar.createRecipes();
        RailwaySteamWaterPump.createRecipes();
        recipesCreated = true;
    }

    public static boolean isRailCar(Item item) {
        return item != null && ((locomotiveTemplateId != 0 && item.getTemplateId() == locomotiveTemplateId)||isWagon(item));
    }

    public static boolean isWagon(Item item) {
        return item!=null&&((cartTemplateId!=0&&item.getTemplateId()==cartTemplateId)||TankCarRegistry.isTankCar(item.getTemplateId())||LogCarRegistry.isLogCar(item.getTemplateId()));
    }

    public static boolean isLocomotive(Item item) {
        return item != null && locomotiveTemplateId != 0 && item.getTemplateId() == locomotiveTemplateId;
    }

    public static Item spawnLocomotive(Creature creator, double x, double y, int layer) throws Exception {
        return spawn(locomotiveTemplateId, creator, x, y, layer);
    }

    public static Item spawnCart(Creature creator, double x, double y, int layer) throws Exception {
        return spawn(cartTemplateId, creator, x, y, layer);
    }
    public static Item spawnTankCar(Creature creator,double x,double y,int layer) throws Exception {
        return spawn(RailwayTankCar.templateId,creator,x,y,layer);
    }
    public static Item spawnLogCar(Creature creator,double x,double y,int layer) throws Exception {
        return spawn(RailwayLogCar.templateId,creator,x,y,layer);
    }

    public static Item spawnTrackMarker(Creature creator, double x, double y, int layer) throws Exception {
        return spawn(trackTemplateId, creator, x, y, layer);
    }

    public static boolean isJunctionIndicator(Item item) {
        return item!=null&&junctionAlignmentTemplateId!=0&&item.getTemplateId()==junctionAlignmentTemplateId;
    }
    public static Item spawnJunctionIndicator(org.wurmrail.core.RailNetwork.Node node) throws Exception {
        Item item=spawn(junctionAlignmentTemplateId,null,node.worldX(),node.worldY(),node.layer);
        item.setData(node.markerId); item.setAuxData((byte)node.getAlignmentMask()); return item;
    }

    public static boolean isSignal(Item item) {
        return item!=null&&signalTemplateId!=0&&item.getTemplateId()==signalTemplateId;
    }

    /** Signal geometry includes its absolute entry-side offset and heading; native item yaw stays zero. */
    public static Item spawnSignal(long trackMarkerId,double x,double y,int layer,int aux) throws Exception {
        if(trackMarkerId<=0||aux<0||aux>15) throw new IllegalArgumentException("Invalid railway signal parent or state");
        Item item=spawn(signalTemplateId,null,x,y,layer);
        item.setData(trackMarkerId);
        item.setAuxData((byte)aux);
        return item;
    }

    /** Graph mask bits are clockwise N, NE, E, SE, S, SW, W, NW. */
    public static void updateTrackVisual(Item item,int connections,int kind) {
        if(item.getTemplateId()!=trackTemplateId) throw new IllegalArgumentException("Not a rail track item");
        if(connections<0||connections>255||kind<0||kind>2) throw new IllegalArgumentException("Invalid rail shape");
        boolean changed=(item.getAuxData()&255)!=connections||item.getData1()!=kind||item.getRotation()!=0;
        if(!changed) return;
        item.setAuxData((byte)connections);
        item.setData1(kind);
        item.setRotation(0);
        item.updateModelNameOnGroundItem();
    }

    private static Item spawn(int templateId, Creature creator, double x, double y, int layer) throws Exception {
        if (templateId == 0) throw new IllegalStateException("Rail templates are not ready");
        requireValidPosition(x, y, layer);
        Item item = ItemFactory.createItem(templateId, 50.0f, (float)x, (float)y, 0.0f,
                layer >= 0, (byte)0, RailwayBridgeDeck.idAt((int)(x/4), (int)(y/4), layer),
                creator == null ? "Wurm Rail" : creator.getName());
        if (creator != null) item.setLastOwnerId(creator.getWurmId());
        item.savePosition();
        return item;
    }

    /** Clear legacy overgrowth only on a registered surface railway tile. */
    public static boolean clearRailVegetation(int x,int y,int layer) {
        if(layer!=0||!RailVegetationHooks.hasSurfaceRail(x,y))return false;
        int encoded=Server.surfaceMesh.getTile(x,y);
        byte type=Tiles.decodeType(encoded);
        if(!Tiles.isTree(type)&&!Tiles.isBush(type))return false;
        Tiles.Tile vegetation=Tiles.getTile(type);
        byte ground=vegetation.isMycelium()?Tiles.Tile.TILE_MYCELIUM.id:
                vegetation.isEnchanted()?Tiles.Tile.TILE_ENCHANTED_GRASS.id:Tiles.Tile.TILE_GRASS.id;
        Server.setSurfaceTile(x,y,Tiles.decodeHeight(encoded),ground,(byte)0);
        Server.setWorldResource(x,y,0);
        Players.getInstance().sendChangedTile(x,y,true,false);
        return true;
    }

    /** Returns null on success, otherwise a reason suitable for showing to a player. */
    public static String validateTile(int x, int y, int layer) {
        if (layer != 0 && layer != -1) return "Rails must be on the ground or a cave floor.";
        if (x < 1 || y < 1 || x >= Zones.worldTileSizeX - 2 || y >= Zones.worldTileSizeY - 2)
            return "Rail tile is outside the playable map.";
        BridgePart deck = RailwayBridgeDeck.partAt(x, y, layer);
        if (deck != null) return RailwayBridgeDeck.problem(deck);
        MeshIO mesh = layer == 0 ? Server.surfaceMesh : Server.caveMesh;
        int tile = mesh.getTile(x, y);
        byte type = Tiles.decodeType(tile);
        if (layer == 0) {
            if (Tiles.isTree(type) || Tiles.isBush(type)) return "Clear trees and bushes before laying rails.";
            if (type == Tiles.Tile.TILE_HOLE.id || type == Tiles.Tile.TILE_LAVA.id
                    || type == Tiles.Tile.TILE_CLIFF.id)
                return type == Tiles.Tile.TILE_HOLE.id
                        ? "Lay entrance rails on the cave floor of this entrance."
                        : "Rails cannot cross lava or cliff tiles.";
        } else {
            if (Tiles.isSolidCave(type)) return "Rails require an open cave floor.";
            if (type == Tiles.Tile.TILE_CAVE_EXIT.id && !isEntrance(x,y))
                return "The cave entrance must be open on the surface before laying rails.";
            // An exit is open to the sky; its data byte is not a tunnel ceiling.
            if (type != Tiles.Tile.TILE_CAVE_EXIT.id && CaveTile.decodeCeilingHeight(tile) < 30)
                return "The tunnel must be at least three metres high.";
        }
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int dx = 0; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) {
            int height = Tiles.decodeHeight(mesh.getTile(x + dx, y + dy));
            minimum = Math.min(minimum, height);
            maximum = Math.max(maximum, height);
        }
        if (minimum < 0) return "Rails need dry ground above water level.";
        if (maximum - minimum > MAX_TILE_SLOPE) return "The rail tile is too steep (maximum slope 20).";
        VolaTile vola = Zones.getTileOrNull(x, y, layer == 0);
        if (vola != null && vola.getStructure() != null)
            return "Ground rails cannot run through a building or bridge.";
        return null;
    }

    /** Construction-only rail bed validation. Existing track movement keeps
     * using validateTile so this rule cannot strand trains in older worlds.
     * A changing heading needs a level tile: a single sloping plane cannot
     * remain level across the rails along both ends of a bend or junction. */
    public static String validateTrackShape(int x,int y,int layer,int connectionsMask) {
        String error=validateTile(x,y,layer);
        if(error!=null)return error;
        if(connectionsMask<0||connectionsMask>255)return "Rail connections must use the eight compass directions.";
        if(connectionsMask==0)return null;
        if(RailwayBridgeDeck.partAt(x,y,layer)!=null)return null;
        MeshIO mesh=layer==0?Server.surfaceMesh:Server.caveMesh;
        int nw=Tiles.decodeHeight(mesh.getTile(x,y));
        int ne=Tiles.decodeHeight(mesh.getTile(x+1,y));
        int sw=Tiles.decodeHeight(mesh.getTile(x,y+1));
        int se=Tiles.decodeHeight(mesh.getTile(x+1,y+1));
        int axis=Integer.numberOfTrailingZeros(connectionsMask)%4;
        int straight=(1<<axis)|(1<<(axis+4));
        if((connectionsMask&~straight)!=0) {
            if(nw!=ne||nw!=sw||nw!=se)return Integer.bitCount(connectionsMask)>2
                    ?"Junctions require a completely flat rail tile (slope 0 in every direction)."
                    :"Rail bends require a completely flat rail tile to keep side-to-side slope 0.";
            return null;
        }
        boolean levelAcross;
        if(axis==0)levelAcross=nw==ne&&sw==se;
        else if(axis==2)levelAcross=nw==sw&&ne==se;
        // Matching transverse corners give equal heights at symmetric left
        // and right rail positions on Wurm's triangular terrain. Requiring
        // those corners to also be the uphill/downhill midpoint would reject
        // ordinary diagonal ramps and changes in longitudinal grade.
        else if(axis==1)levelAcross=nw==se;
        else levelAcross=ne==sw;
        if(!levelAcross&&axis==1)return "For NE/SW track, level the northwest and southeast corners across the rails. Maximum slope: 20.";
        if(!levelAcross&&axis==3)return "For NW/SE track, level the northeast and southwest corners across the rails. Maximum slope: 20.";
        return levelAcross?null:"Level the ground across the rails: side-to-side slope must be 0 (along-track slope may be up to 20).";
    }

    /** The same visible entrance has one physical rail, on its native cave floor. */
    public static int resolveBuildLayer(int x,int y,int preferredLayer) {
        if (preferredLayer == 0 && RailwayBridgeDeck.partAt(x,y,0) != null) return 0;
        return isEntrance(x,y) ? -1 : preferredLayer;
    }

    public static boolean isEntranceTile(int x,int y) { return isEntrance(x,y); }

    private static boolean isEntrance(int x,int y) {
        return x>=0&&y>=0&&x<Zones.worldTileSizeX&&y<Zones.worldTileSizeY
                &&Tiles.decodeType(Server.surfaceMesh.getTile(x,y))==Tiles.Tile.TILE_HOLE.id
                &&Tiles.decodeType(Server.caveMesh.getTile(x,y))==Tiles.Tile.TILE_CAVE_EXIT.id;
    }

    public static float groundHeight(double x, double y, int layer) throws Exception {
        requireValidPosition(x, y, layer);
        return railHeight(x, y, layer, RailwayBridgeDeck.partAt((int)(x/4), (int)(y/4), layer));
    }

    static Node railAtVehicle(RailNetwork network,Item vehicle) {
        List<Node> rails = network.nodesAt(vehicle.getTileX(),vehicle.getTileY(),vehicle.isOnSurface() ? 0 : -1);
        if (rails.size() <= 1) return rails.isEmpty() ? null : rails.get(0);
        Node nearest = null;
        float gap = Float.POSITIVE_INFINITY;
        for (Node rail : rails) {
            Item marker = Items.getItemOptional(rail.markerId).orElse(null);
            if (marker == null) continue;
            float difference = Math.abs(marker.getPosZ()-vehicle.getPosZ());
            if (difference < gap) {
                nearest = rail;
                gap = difference;
            }
        }
        return nearest;
    }

    private static float railHeight(double x, double y, int layer, BridgePart deck) throws Exception {
        return deck == null ? Zones.calculateHeight((float)x, (float)y, layer == 0)
                : RailwayBridgeDeck.height(deck, x, y);
    }

    /** Eight-way edges require clear corner approaches as well as a clear centreline. */
    public static String validateEdge(int ax, int ay, int bx, int by, int layer) {
        return validateEdge(ax,ay,layer,bx,by,layer);
    }

    /** Only a real, level entrance can connect surface track to cave track. */
    public static String validateEdge(int ax,int ay,int fromLayer,int bx,int by,int toLayer) {
        int dx=Math.abs(ax-bx),dy=Math.abs(ay-by);
        if(Math.max(dx,dy)!=1) return "Rail connections must join adjacent tiles in one of eight compass directions.";
        try {
            if(fromLayer!=toLayer) requireEntranceEdge(ax,ay,fromLayer,bx,by,toLayer);
            else if(dx==1&&dy==1) validateDiagonalCorner(ax,ay,bx,by,fromLayer);
            validateSegment(ax * 4.0 + 2.0, ay * 4.0 + 2.0,fromLayer,
                    bx * 4.0 + 2.0, by * 4.0 + 2.0,toLayer);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? "Unable to validate the rail connection." : e.getMessage();
        }
    }

    private static void requireEntranceEdge(int ax,int ay,int fromLayer,int bx,int by,int toLayer) throws Exception {
        if(!((fromLayer==0&&toLayer==-1)||(fromLayer==-1&&toLayer==0))
                ||Math.abs(ax-bx)+Math.abs(ay-by)!=1)
            throw new IllegalArgumentException("Cave entrance rails must join adjacent surface and cave tiles straight across the entrance.");
        int cx=fromLayer<0?ax:bx,cy=fromLayer<0?ay:by;
        int sx=fromLayer==0?ax:bx,sy=fromLayer==0?ay:by;
        if(!isEntrance(cx,cy))
            throw new IllegalArgumentException("Surface and cave rails can connect only through an open cave entrance.");
        if(RailwayBridgeDeck.partAt(sx,sy,0)!=null)
            throw new IllegalArgumentException("A bridge deck cannot connect directly to a cave entrance.");
        String error=validateTile(ax,ay,fromLayer);
        if(error==null)error=validateTile(bx,by,toLayer);
        if(error!=null)throw new IllegalArgumentException(error);
        // The two meshes share the lip of a real, walkable portal. Reject a
        // buried side or a height discontinuity instead of teleporting a car.
        int borderX=Math.max(cx,sx),borderY=Math.max(cy,sy);
        for(int corner=0;corner<2;corner++) {
            int x=borderX+(cy!=sy?corner:0),y=borderY+(cx!=sx?corner:0);
            int surface=Tiles.decodeHeight(Server.surfaceMesh.getTile(x,y));
            int cave=Tiles.decodeHeight(Server.caveMesh.getTile(x,y));
            if(Math.abs(surface-cave)>1)
                throw new IllegalArgumentException("The cave entrance floor does not meet the surface at this side.");
        }
    }

    private static void requireValidPosition(double x, double y, int layer) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y))
            throw new IllegalArgumentException("Invalid rail coordinates");
        String error = validateTile((int)Math.floor(x / 4.0), (int)Math.floor(y / 4.0), layer);
        if (error != null) throw new IllegalArgumentException(error);
    }

    /** Collision check shared by movement and track placement. No permissions are bypassed for doors. */
    public static void validateSegment(double fromX, double fromY, double toX, double toY, int layer) throws Exception {
        validateStraightSegment(fromX,fromY,toX,toY,layer);
        double travelX=toX-fromX,travelY=toY-fromY;
        if(Math.abs(travelX)>0.000001&&Math.abs(travelY)>0.000001) {
            // A sample exactly on a corner floors to one of the side tiles.
            // Check the limiting tiles either side of every exact corner too.
            int first=(int)Math.ceil(Math.min(fromX,toX)/4.0),last=(int)Math.floor(Math.max(fromX,toX)/4.0);
            for(int boundary=first;boundary<=last;boundary++) {
                double t=(boundary*4.0-fromX)/travelX;
                double y=(fromY+travelY*t)/4.0;
                if(t>=-0.000001&&t<=1.000001&&Math.abs(y-Math.rint(y))<0.000001) {
                    int ax=(int)Math.floor((fromX+travelX*(t-0.00001))/4.0);
                    int ay=(int)Math.floor((fromY+travelY*(t-0.00001))/4.0);
                    int bx=(int)Math.floor((fromX+travelX*(t+0.00001))/4.0);
                    int by=(int)Math.floor((fromY+travelY*(t+0.00001))/4.0);
                    validateDiagonalCorner(ax,ay,bx,by,layer);
                }
            }
        }
        double distance=Math.hypot(toX-fromX,toY-fromY);
        int samples=Math.max(1,(int)Math.ceil(distance/0.25));
        int oldX=(int)Math.floor(fromX/4),oldY=(int)Math.floor(fromY/4);
        for(int i=1;i<=samples;i++) {
            double t=(double)i/samples;
            int x=(int)Math.floor((fromX+(toX-fromX)*t)/4),y=(int)Math.floor((fromY+(toY-fromY)*t)/4);
            if(x!=oldX&&y!=oldY) validateDiagonalCorner(oldX,oldY,x,y,layer);
            oldX=x;oldY=y;
        }
    }

    /** A car crosses layers at the shared tile boundary, never between arbitrary underground points. */
    public static void validateSegment(double fromX,double fromY,int fromLayer,
            double toX,double toY,int toLayer) throws Exception {
        if(fromLayer==toLayer) { validateSegment(fromX,fromY,toX,toY,fromLayer); return; }
        int ax=(int)Math.floor(fromX/4),ay=(int)Math.floor(fromY/4);
        int bx=(int)Math.floor(toX/4),by=(int)Math.floor(toY/4);
        requireEntranceEdge(ax,ay,fromLayer,bx,by,toLayer);
        double changeX=toX-fromX,changeY=toY-fromY;
        double progress=ax!=bx?(Math.max(ax,bx)*4.0-fromX)/changeX
                :(Math.max(ay,by)*4.0-fromY)/changeY;
        if(progress<0||progress>1||Double.isNaN(progress))
            throw new IllegalArgumentException("Rail movement must cross the actual cave entrance boundary.");
        double boundaryX=fromX+changeX*progress,boundaryY=fromY+changeY*progress;
        // Native collision coordinates are floats. A fraction of a short poll
        // can round back ONTO the boundary, falsely sampling the solid cave
        // wall underneath the surface approach. Stay a representable distance
        // inside each real side, including on large maps.
        double boundary=ax!=bx?boundaryX:boundaryY,normalTravel=ax!=bx?changeX:changeY;
        double margin=Math.max(.001,2.0*Math.ulp((float)boundary));
        double epsilon=margin/Math.abs(normalTravel);
        double before=Math.max(0,progress-epsilon),after=Math.min(1,progress+epsilon);
        validateStraightSegment(fromX,fromY,fromX+changeX*before,fromY+changeY*before,fromLayer);
        validateStraightSegment(fromX+changeX*after,fromY+changeY*after,toX,toY,toLayer);
        float surface=Zones.calculateHeight((float)boundaryX,(float)boundaryY,true);
        float cave=Zones.calculateHeight((float)boundaryX,(float)boundaryY,false);
        if(Math.abs(surface-cave)>.11f)
            throw new IllegalArgumentException("The cave entrance floor changes height at the rail crossing.");
        float fromZ=Zones.calculateHeight((float)fromX,(float)fromY,fromLayer==0);
        float toZ=Zones.calculateHeight((float)toX,(float)toY,toLayer==0);
        // A null native actor does not acquire Blocking's cave-exit exception.
        // Check the physical lip from its surface side in either travel
        // direction: native type 4 still examines walls/fences on BOTH layers,
        // without testing solid rock underneath the outside surface approach.
        boolean fromSurface=fromLayer==0;
        if(Blocking.getBlockerBetween(null,(float)(fromSurface?fromX:toX),(float)(fromSurface?fromY:toY),
                (float)(fromSurface?toX:fromX),(float)(fromSurface?toY:fromY),
                (fromSurface?fromZ:toZ)+.4f,(fromSurface?toZ:fromZ)+.4f,true,false,false,4,-1L,NO_ID,NO_ID,true)!=null)
            throw new IllegalStateException("Rail movement is blocked by a wall, fence or door");
    }

    private static void validateDiagonalCorner(int ax,int ay,int bx,int by,int layer) throws Exception {
        String error=validateTile(ax,by,layer);
        if(error==null) error=validateTile(bx,ay,layer);
        if(error!=null) throw new IllegalArgumentException("The diagonal corner is blocked: "+error);
        double x1=ax*4+2,y1=ay*4+2,x2=bx*4+2,y2=by*4+2;
        validateStraightSegment(x1,y1,x1,y2,layer);
        validateStraightSegment(x1,y2,x2,y2,layer);
        validateStraightSegment(x1,y1,x2,y1,layer);
        validateStraightSegment(x2,y1,x2,y2,layer);
    }

    private static void validateStraightSegment(double fromX,double fromY,double toX,double toY,int layer) throws Exception {
        requireValidPosition(fromX, fromY, layer);
        requireValidPosition(toX, toY, layer);
        double distance = Math.hypot(toX - fromX, toY - fromY);
        if (distance > 16.0) throw new IllegalArgumentException("A rail movement step exceeds 16 metres");
        int samples = Math.max(1, (int)Math.ceil(distance / 0.5));
        double previousX=fromX,previousY=fromY;
        BridgePart first=RailwayBridgeDeck.partAt((int)(fromX/4),(int)(fromY/4),layer), previous=first;
        boolean onBridge=first!=null;
        for (int i = 1; i <= samples; i++) {
            double progress = (double)i / samples;
            double x=fromX+(toX-fromX)*progress,y=fromY+(toY-fromY)*progress;
            requireValidPosition(x,y,layer);
            BridgePart deck=RailwayBridgeDeck.partAt((int)(x/4),(int)(y/4),layer);
            if(previous!=null||deck!=null) {
                if(!onBridge) checkRailCollision(fromX,fromY,previousX,previousY,layer,null,null);
                onBridge=true;
                if(previous!=deck) validateDeckCrossing(previousX,previousY,x,y,layer,previous,deck);
            }
            if(onBridge) checkRailCollision(previousX,previousY,x,y,layer,previous,deck);
            previousX=x;previousY=y;previous=deck;
        }
        if(!onBridge) checkRailCollision(fromX,fromY,toX,toY,layer,null,null);
    }

    private static void validateDeckCrossing(double fromX,double fromY,double toX,double toY,int layer,
            BridgePart from,BridgePart to) throws Exception {
        int ax=(int)(fromX/4),ay=(int)(fromY/4),bx=(int)(toX/4),by=(int)(toY/4);
        if(from!=null&&to!=null) {
            if(from.getStructureId()!=to.getStructureId())
                throw new IllegalArgumentException("Separate bridge decks require a ground approach between them.");
        } else if(from!=null?!RailwayBridgeDeck.hasGroundExit(from,bx-ax,by-ay)
                :!RailwayBridgeDeck.hasGroundExit(to,ax-bx,ay-by)) {
            throw new IllegalArgumentException("Rails can enter or leave a bridge only through its ground-level exits.");
        }
        double progress=ax!=bx?(Math.max(ax,bx)*4.0-fromX)/(toX-fromX)
                :(Math.max(ay,by)*4.0-fromY)/(toY-fromY);
        double x=fromX+(toX-fromX)*progress,y=fromY+(toY-fromY)*progress;
        if(Math.abs(railHeight(x,y,layer,from)-railHeight(x,y,layer,to))>.11f)
            throw new IllegalArgumentException("The rail surfaces do not meet at the bridge edge.");
    }

    private static void checkRailCollision(double fromX,double fromY,double toX,double toY,int layer,
            BridgePart from,BridgePart to) throws Exception {
        if(Math.hypot(toX-fromX,toY-fromY)<=.001) return;
        float fromZ=railHeight(fromX,fromY,layer,from),toZ=railHeight(toX,toY,layer,to);
        long fromBridge=from==null?NO_ID:from.getStructureId(),toBridge=to==null?NO_ID:to.getStructureId();
        if (Blocking.getBlockerBetween(null, (float)fromX, (float)fromY,
                (float)toX, (float)toY, fromZ + 0.4f, toZ + 0.4f,
                layer == 0, layer == 0, false, 4, -1L, fromBridge, toBridge, from==null&&to==null) != null)
            throw new IllegalStateException("Rail movement is blocked by a wall, fence or door");
    }

    /** Move through Wurm's own zone and watcher code, then update every native passenger. */
    public static void moveCar(long itemId, double x, double y, double rotation, int layer) throws Exception {
        moveCar(itemId, x, y, rotation, layer, Float.NaN, false, null, null);
    }

    static void moveCar(Pose pose, Pose previous) throws Exception {
        moveCar(pose,previous,null);
    }

    static void moveCar(Pose pose, Pose previous, MotionBatch batch) throws Exception {
        moveCar(pose.carId, pose.x, pose.y, pose.rotation, pose.layer, pose.elevation,
                previous != null && Float.isFinite(previous.elevation), approachSeam(pose,previous),batch);
    }

    /** Queue positions together after the world finishes moving the consist. */
    static final class MotionBatch {
        private final List<Runnable> packets = new ArrayList<>();
        private final List<List<Pose>> consists = new ArrayList<>();
        private final long timestamp;

        MotionBatch() { this(System.nanoTime()); }

        MotionBatch(long timestamp) { this.timestamp = timestamp; }

        void addConsist(List<Pose> poses) {
            if (!poses.isEmpty() && RailConsistTransport.hasSubscribers()) consists.add(poses);
        }

        void send() {
            for (Runnable packet : packets) packet.run();
            packets.clear();
            for (List<Pose> consist : consists) {
                try { sendConsist(consist,timestamp); }
                catch (Exception error) {
                    if (!reportedConsistError) {
                        reportedConsistError = true;
                        LOG.log(Level.WARNING,"Could not publish a synchronized rail pose. Native movement packets were sent.",error);
                    }
                }
            }
            consists.clear();
        }
    }

    private static void sendConsist(List<Pose> consist, long timestamp) throws Exception {
        List<Item> cars = new ArrayList<>(consist.size());
        Set<Player> mounted = new HashSet<>();
        for (Pose pose : consist) {
            Item item = Items.getItem(pose.carId);
            cars.add(item);
            Vehicle vehicle = Vehicles.getVehicle(item);
            if (vehicle == null) continue;
            for (Seat seat : vehicle.seats) if (seat != null && seat.isOccupied()) {
                Player rider = Players.getInstance().getPlayerOrNull(seat.occupant);
                if (rider != null && rider.getVehicle() == pose.carId && RailConsistTransport.receives(rider)) mounted.add(rider);
            }
        }

        Map<Player,List<RailConsistFrame.Pose>> frames = new HashMap<>();
        for (Item car : cars) {
            VolaTile tile = Zones.getOrCreateTile(car.getTileX(),car.getTileY(),car.isOnSurface());
            Set<Player> observers = new HashSet<>();
            for (VirtualZone watcher : tile.getWatchers()) {
                if (watcher.getWatcher() instanceof Player) {
                    Player player = (Player)watcher.getWatcher();
                    if (RailConsistTransport.receives(player) && knowsVehicle(watcher,car,tile)) observers.add(player);
                }
            }
            for (Player rider : mounted) {
                VisionArea vision = rider.getVisionArea();
                VirtualZone view = vision == null ? null : car.isOnSurface() ? vision.getSurface() : vision.getUnderGround();
                if (rider.getVehicle() == car.getWurmId() || knowsVehicle(view,car,tile)) observers.add(rider);
            }
            if (observers.isEmpty()) continue;
            RailConsistFrame.Pose sample = new RailConsistFrame.Pose(car.getWurmId(),car.getPosX(),car.getPosY(),
                    car.getPosZ(),car.getRotation(),car.isOnSurface() ? 0 : -1);
            for (Player observer : observers) frames.computeIfAbsent(observer, key -> new ArrayList<>()).add(sample);
        }
        for (Map.Entry<Player,List<RailConsistFrame.Pose>> entry : frames.entrySet())
            RailConsistTransport.send(entry.getKey(),timestamp,entry.getValue());
    }

    private static boolean knowsVehicle(VirtualZone view, Item vehicle, VolaTile tile) throws ReflectiveOperationException {
        if (view == null || !view.isVisible(vehicle,tile)) return false;
        initVisibleItemsField();
        Set<?> items = (Set<?>)visibleItemsField.get(view);
        return items != null && items.contains(vehicle);
    }

    static void validateCarStep(Item car, Pose destination, Pose previous) throws Exception {
        boolean elevatedSource = previous != null && Float.isFinite(previous.elevation);
        validateCarStep(car, destination.x, destination.y, destination.layer, destination.elevation, elevatedSource,
                approachSeam(destination,previous));
    }

    private static ApproachSeam approachSeam(Pose destination, Pose previous) {
        return Float.isFinite(destination.elevation) ? destination.approachSeam
                : previous == null ? null : previous.approachSeam;
    }

    static void validateTrestleApproach(org.wurmrail.core.TrestleAlignment.Point approach,
                                        org.wurmrail.core.TrestleAlignment alignment, boolean start) throws Exception {
        float x = (float)approach.x, y = (float)approach.y;
        float ground = groundHeight(x,y,0);
        if (Math.abs(approach.z-ground) > .05f)
            throw new IllegalArgumentException("Set the approach elevation to match the ground rail.");
        double distance = start ? 0 : alignment.length;
        org.wurmrail.core.TrestleAlignment.Point seam = alignment.at(distance);
        float sx = (float)seam.x, sy = (float)seam.y;
        // The ground half stops inside the approach tile. At the exact border,
        // native tile lookup may name the steep terrain underneath the deck.
        float insideX = Math.nextAfter(sx,x), insideY = Math.nextAfter(sy,y);
        validateSegment(x,y,insideX,insideY,0);
        if (Math.abs(seam.z-Zones.calculateHeight(sx,sy,true)) > .05f)
            throw new IllegalArgumentException("The deck and ground rail meet at different elevations.");
        validateElevatedSegment(insideX,insideY,Zones.calculateHeight(insideX,insideY,true),sx,sy,(float)seam.z);
        double deckRun = Math.min(2,alignment.length);
        int steps = alignment.hasDeckGrades() ? (int)Math.ceil(deckRun * 4) : 1;
        org.wurmrail.core.TrestleAlignment.Point previous = seam;
        for (int i = 1; i <= steps; i++) {
            double offset = deckRun * i / steps;
            org.wurmrail.core.TrestleAlignment.Point next = alignment.at(start ? offset : alignment.length - offset);
            validateElevatedSegment((float)previous.x,(float)previous.y,(float)previous.z,
                    (float)next.x,(float)next.y,(float)next.z);
            previous = next;
        }
    }

    private static void validateCarStep(Item car, double x, double y, int layer, float elevation,
                                        boolean elevatedSource, ApproachSeam seam) throws Exception {
        boolean elevatedDestination = Float.isFinite(elevation);
        if (!elevatedSource && !elevatedDestination) {
            validateSegment(car.getPosX(), car.getPosY(), car.isOnSurface() ? 0 : -1, x, y, layer);
            return;
        }
        if (layer != 0 || !car.isOnSurface() || car.getBridgeId() != NO_ID)
            throw new IllegalArgumentException("An elevated rail connection requires a surface approach.");
        if (!elevatedDestination) requireValidPosition(x, y, layer);
        double dx = x - car.getPosX(), dy = y - car.getPosY();
        double distance = Math.hypot(dx, dy);
        if (!Double.isFinite(distance) || distance > 16)
            throw new IllegalArgumentException("An elevated rail movement step exceeds 16 metres.");
        float fromX=car.getPosX(),fromY=car.getPosY(),fromZ=car.getPosZ();
        float toZ = elevatedDestination ? elevation : groundHeight(x, y, 0);
        // The two sides can have different slopes. A chord across their join
        // can cut through the bank even when the railway follows its surface.
        if (seam != null && elevatedSource != elevatedDestination && distance > 0) {
            double throughJoin=Math.hypot(seam.x-fromX,seam.y-fromY)+Math.hypot(x-seam.x,y-seam.y);
            if (throughJoin <= distance + .10) {
                validateElevatedSegment(fromX,fromY,fromZ,seam.x,seam.y,seam.z);
                validateElevatedSegment(seam.x,seam.y,seam.z,(float)x,(float)y,toZ);
                return;
            }
        }
        validateElevatedSegment(fromX,fromY,fromZ,(float)x,(float)y,toZ);
    }

    private static void validateElevatedSegment(float fromX,float fromY,float fromZ,float x,float y,float toZ) throws Exception {
        double dx=x-fromX,dy=y-fromY;
        double distance=Math.hypot(dx,dy);
        int samples = Math.max(1, (int) Math.ceil(distance * 2));
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            float px = (float) (fromX + dx * t), py = (float) (fromY + dy * t);
            int tileX = (int) (px / 4), tileY = (int) (py / 4);
            if (tileX < 1 || tileY < 1 || tileX >= Zones.worldTileSizeX - 2 || tileY >= Zones.worldTileSizeY - 2)
                throw new IllegalArgumentException("Elevated railway is outside the playable map.");
            VolaTile tile = Zones.getTileOrNull(tileX, tileY, true);
            if (RailwayBridgeDeck.partAt(tileX, tileY, 0) != null || tile != null && tile.getStructure() != null)
                throw new IllegalArgumentException("A structure conflicts with the elevated railway.");
            if (fromZ + (toZ - fromZ) * t + 0.05 < Zones.calculateHeight(px, py, true))
                throw new IllegalArgumentException("Terrain obstructs the elevated railway.");
        }
        if (distance > 0 && Blocking.getBlockerBetween(null, fromX, fromY, x, y,
                fromZ + .4f, toZ + .4f, true, true, false, 4, -1L, NO_ID, NO_ID, false) != null)
            throw new IllegalArgumentException("Elevated rail movement is blocked by a wall, fence or door.");
    }

    private static void moveCar(long itemId, double x, double y, double rotation, int layer,
                                float elevation, boolean elevatedSource, ApproachSeam seam, MotionBatch batch) throws Exception {
        Item item = Items.getItem(itemId);
        if (!isRailCar(item)) throw new IllegalArgumentException("Item is not a rail vehicle");
        if (item.getTopParent() != itemId || item.getOwnerId() != NO_ID)
            throw new IllegalStateException("Rail vehicle must be on the ground");
        if (Double.isNaN(rotation) || Double.isInfinite(rotation))
            throw new IllegalArgumentException("Invalid vehicle rotation");
        boolean changedLayer=item.isOnSurface()!=(layer==0);
        if(changedLayer&&(float)x==item.getPosX()&&(float)y==item.getPosY())
            throw new IllegalArgumentException("A cave crossing must move the rail vehicle across the entrance at native coordinate precision");
        validateCarStep(item,x,y,layer,elevation,elevatedSource,seam);
        int sourceLayer=item.isOnSurface()?0:-1;
        if(!RailwayBridgeDeck.matches(item.getTileX(),item.getTileY(),sourceLayer,item.getBridgeId()))
            throw new IllegalStateException("The rail vehicle's bridge deck is missing or has changed.");
        Vehicle vehicle = Vehicles.getVehicle(item);
        if (vehicle == null) throw new IllegalStateException("Rail vehicle seats are not available");
        BridgePart deck=RailwayBridgeDeck.partAt((int)(x/4),(int)(y/4),layer);
        long bridgeId=deck==null?NO_ID:deck.getStructureId();
        boolean changedBridge=item.getBridgeId()!=bridgeId;
        float z = Float.isFinite(elevation) ? elevation : railHeight(x,y,layer,deck);
        RailModelRegistry.setElevatedCarHeight(itemId,deck == null ? elevation : z);
        float facing = (float)((rotation % 360.0 + 360.0) % 360.0);
        VolaTile oldTile = Zones.getOrCreateTile(item.getTileX(), item.getTileY(), item.isOnSurface());
        List<PortalObserver> portalObservers=changedLayer?preparePortalObservers(item,oldTile,(int)x/4,(int)y/4):new ArrayList<PortalObserver>();
        // Register the deck before native tile insertion calculates the item's Z.
        // Native moveItem clears or infers bridge IDs when supplied an old ID;
        // the validated destination already supplies the applicable deck.
        if(changedBridge) item.setOnBridge(bridgeId);
        if ((float)x != item.getPosX() || (float)y != item.getPosY()) {
            RailModelRegistry.beginNativeRailMove(itemId);
            try { oldTile.moveItem(item, (float)x, (float)y, z, facing, layer == 0, z); }
            finally {
                RailModelRegistry.endNativeRailMove();
                for(PortalObserver observer:portalObservers)observer.finish(item);
            }
            // Native tile movement stores a coarse heading. Keep the curve's exact
            // facing for passenger offsets and rollback.
            if (item.getRotation() != facing || item.getPosZ() != z) {
                item.setTempZandRot(z, facing);
            }
        } else if (item.getRotation() != facing || item.getPosZ() != z) {
            // Native moveItem skips stationary changes. Vehicles live in the client's
            // moving-creature map; its ground-item rotate packet cannot reach them.
            item.setTempZandRot(z, facing);
        }
        // Native ground movement asks clients to sample terrain themselves. Their
        // interpolated XY still trails the server at a portal: changing layers can
        // therefore sample the roof above the tunnel. Keep the physical rail-floor
        // height authoritative on every approach poll as well as the crossing.
        Set<Creature> observers=new HashSet<Creature>();
        VolaTile currentTile=Zones.getOrCreateTile(item.getTileX(),item.getTileY(),item.isOnSurface());
        for(VirtualZone watcher:oldTile.getWatchers())if(watcher.isVisible(item,oldTile))observers.add(watcher.getWatcher());
        for(VirtualZone watcher:currentTile.getWatchers())if(watcher.isVisible(item,currentTile))observers.add(watcher.getWatcher());
        for(PortalObserver observer:portalObservers)observers.add(observer.watcher);
        // A mounted client already knows its carrier. Its movement packets
        // cannot depend on the rider remaining in a particular tile's vision list.
        for (Seat seat : vehicle.seats) if (seat != null && seat.isOccupied()) {
            Creature rider = Players.getInstance().getPlayerOrNull(seat.occupant);
            if (rider != null && rider.getVehicle() == itemId) observers.add(rider);
        }
        float packetX = item.getPosX(), packetY = item.getPosY();
        if (!observers.isEmpty()) {
            Runnable publish = () -> {
                for (Creature observer : observers) if (observer.hasLink()) {
                    if (changedLayer) observer.getCommunicator().sendMovingItemChangedLayer(itemId,(byte)layer);
                    if (changedBridge) observer.getCommunicator().sendBridgeId(itemId,bridgeId);
                    observer.getCommunicator().sendMoveMovingItemAndSetZ(itemId,packetX,packetY,z,(int)(facing*256.0f/360.0f));
                }
            };
            if (batch == null) publish.run();
            else batch.packets.add(publish);
        }
        // Move seats before changing their layer, so native newLayer registers a
        // rider at the final seat tile instead of the previous outside tile.
        Item previous = MovementScheme.itemVehicle;
        try {
            if(changedBridge) for(Seat seat:vehicle.seats) if(seat!=null&&seat.isOccupied()) {
                try { Players.getInstance().getPlayer(seat.occupant).setBridgeId(bridgeId); }
                catch(com.wurmonline.server.NoSuchPlayerException missing) { }
            }
            MovementScheme.itemVehicle = item;
            MovementScheme.movePassengers(vehicle, null, false);
        } finally {
            MovementScheme.itemVehicle = previous;
        }
        if(changedLayer) {
            if(item.isOnSurface()!=(layer==0)) throw new IllegalStateException("The native rail vehicle did not enter its destination layer");
            // Wurm's driver-led transition skips seat zero. Rail cars contain
            // passenger seats only, so every occupied seat must move layers.
            for(Seat seat:vehicle.seats) if(seat!=null&&seat.isOccupied()) {
                try {
                    Creature passenger=Players.getInstance().getPlayer(seat.occupant);
                    if(layer<0&&passenger.getVisionArea()!=null)passenger.getVisionArea().checkCaves(false);
                    passenger.getStatus().setLayer(layer);
                    passenger.getMovementScheme().setPosition(passenger.getPosX(),passenger.getPosY(),passenger.getPositionZ(),passenger.getStatus().getRotation(),layer);
                    if(passenger.getCurrentTile()!=null) passenger.getCurrentTile().newLayer(passenger);
                    if(passenger.isPlayer()) {
                        passenger.getCommunicator().sendNormalServerMessage(layer==0?"You leave the cave.":"You enter the cave.");
                    }
                } catch(com.wurmonline.server.NoSuchPlayerException missing) {
                    // Native vehicle movement also tolerates a stale offline seat.
                }
            }
        }
        long now = System.nanoTime();
        Long saved = lastPositionSave.get(itemId);
        if (changedLayer || changedBridge || saved == null || now - saved.longValue() > 20000000000L) {
            item.savePosition();
            lastPositionSave.put(itemId, now);
        }
    }

    /** Preserve the client's existing moving object during a native view transfer.
     * VirtualZone otherwise announces the same item as NEW in its other view,
     * which the shipped client implements with setPosImmediately. New viewers
     * still use the unmodified native announcement and visibility checks. */
    private static List<PortalObserver> preparePortalObservers(Item item,VolaTile oldTile,int x,int y) throws Exception {
        initVisibleItemsField();
        List<PortalObserver> result=new ArrayList<PortalObserver>();Set<Creature> visited=new HashSet<Creature>();
        try { for(VirtualZone zone:oldTile.getWatchers()) {
            Creature watcher=zone.getWatcher();
            if(!visited.add(watcher)||watcher.getVisionArea()==null||!watcher.hasLink())continue;
            VirtualZone surface=watcher.getVisionArea().getSurface(),cave=watcher.getVisionArea().getUnderGround();
            if(surface==null||cave==null||!surface.covers(x,y)||!cave.covers(x,y))continue;
            Set<Item> surfaceItems=visibleItems(surface),caveItems=visibleItems(cave);
            boolean surfaceKnown=surfaceItems.contains(item),caveKnown=caveItems.contains(item);
            if(!surfaceKnown&&!caveKnown)continue;
            result.add(new PortalObserver(watcher,surfaceItems,caveItems,surfaceKnown,caveKnown,item.isOnSurface()));
            surfaceItems.add(item);caveItems.add(item);
        } } catch(Exception failure) { for(PortalObserver observer:result)observer.finish(item);throw failure; }
        return result;
    }

    private static void initVisibleItemsField() {
        if(visibleItemsField==null) {
            try {
                Field field=VirtualZone.class.getDeclaredField("items");
                if(!Set.class.isAssignableFrom(field.getType()))throw new NoSuchFieldException("VirtualZone.items is not a native item set");
                field.setAccessible(true);visibleItemsField=field;
            } catch(ReflectiveOperationException failure) {
                throw new IllegalStateException("This Wurm server version does not expose the native moving-item visibility needed for smooth tunnel crossings",failure);
            }
        }
    }
    @SuppressWarnings("unchecked")
    private static Set<Item> visibleItems(VirtualZone zone) throws Exception {
        Set<Item> items=(Set<Item>)visibleItemsField.get(zone);
        if(items==null) { items=new HashSet<Item>();visibleItemsField.set(zone,items); }
        return items;
    }
    private static final class PortalObserver {
        final Creature watcher;final Set<Item> surface,cave;final boolean surfaceKnown,caveKnown,wasSurface;
        PortalObserver(Creature watcher,Set<Item> surface,Set<Item> cave,boolean surfaceKnown,boolean caveKnown,boolean wasSurface) {
            this.watcher=watcher;this.surface=surface;this.cave=cave;this.surfaceKnown=surfaceKnown;this.caveKnown=caveKnown;this.wasSurface=wasSurface;
        }
        void finish(Item item) {
            if(item.isOnSurface()==wasSurface) {
                if(!surfaceKnown)surface.remove(item);else surface.add(item);
                if(!caveKnown)cave.remove(item);else cave.add(item);
            } else if(item.isOnSurface()) { cave.remove(item);surface.add(item); }
            else { surface.remove(item);cave.add(item); }
        }
    }

    public static void saveCar(long itemId) throws Exception {
        Item item = Items.getItem(itemId);
        if (isRailCar(item)) item.savePosition();
    }

    /** Mirrors normal Wurm unlocked-vehicle, key, deed and explicit permission rules. */
    public static boolean canManage(Creature performer, Item item) {
        if (performer == null || item == null) return false;
        if (performer.getPower() >= 2 || item.getLastOwnerId() == performer.getWurmId()
                || item.mayManage(performer) || item.mayCommand(performer)) return true;
        return VehicleBehaviour.hasKeyForVehicle(performer, item);
    }

    /** Native boarding initializes the mount animation, attachment, seat and server position. */
    public static boolean board(Creature performer, Item item) throws Exception {
        return board(performer,item,6.0f);
    }
    /** A marked adjacent tile can put its far corner over six metres from a curved car centre. */
    public static boolean boardFromPlatform(Creature performer,Item item) throws Exception {
        return board(performer,item,8.0f);
    }
    private static boolean board(Creature performer,Item item,float reach) throws Exception {
        if (!isRailCar(item)) throw new IllegalArgumentException("Item is not a rail vehicle");
        if (!performer.isPlayer()) throw new IllegalArgumentException("Only players can board rail vehicles");
        if (performer.getVehicle() != NO_ID) throw new IllegalStateException("Leave your current vehicle first");
        if (performer.isOnSurface() != item.isOnSurface()
                || !RailwayBridgeDeck.isAtItemLevel(performer,item.getBridgeId(),item.getFloorLevel())
                || !performer.isWithinDistanceTo(item.getPosX(), item.getPosY(), item.getPosZ(), reach))
            throw new IllegalStateException("Move closer to the rail vehicle on the same level");
        if (!canManage(performer, item) && !item.mayPassenger(performer))
            throw new IllegalStateException("You do not have permission to board this vehicle");
        Vehicle vehicle = Vehicles.getVehicle(item);
        if (vehicle == null) throw new IllegalStateException("Rail vehicle seats are unavailable");
        boolean availableSeat = false;
        for (int i = 0; i < vehicle.seats.length; i++) {
            Seat seat = vehicle.seats[i];
            if (seat.type == Seat.TYPE_PASSENGER && !seat.isOccupied()) {
                availableSeat = true;
                // Native addPassenger returns whether a wall blocked boarding, not success.
                boolean wallBlocked = VehicleBehaviour.addPassenger(performer, vehicle, item, i);
                if (!wallBlocked && performer.getVehicle() == item.getWurmId()) return true;
            }
        }
        if (availableSeat) throw new IllegalStateException("A wall or fence blocks boarding this vehicle");
        throw new IllegalStateException("All four passenger seats are occupied");
    }
}
