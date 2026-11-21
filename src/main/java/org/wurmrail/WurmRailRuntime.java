package org.wurmrail;

import com.wurmonline.server.Items;
import com.wurmonline.server.ServerDirInfo;
import com.wurmonline.server.behaviours.*;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.structures.BridgePart;
import com.wurmonline.server.questions.Question;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zones;
import org.gotti.wurmunlimited.modloader.interfaces.*;
import org.gotti.wurmunlimited.modsupport.actions.*;
import org.gotti.wurmunlimited.modsupport.questions.*;
import org.wurmrail.core.RailNetwork;
import org.wurmrail.core.RailNetwork.*;
import org.wurmrail.core.PassengerStations;
import org.wurmrail.core.PassengerStations.Platform;
import org.wurmrail.core.SignalSystem;
import org.wurmrail.core.ScheduledServices;
import org.wurmrail.core.FreightStops;
import org.wurmrail.core.FreightStops.Stop;
import org.wurmrail.core.WaterFillStops;
import org.wurmrail.core.WaterUnloadStops;
import org.wurmrail.core.TrestleAlignment;
import org.wurmrail.shared.TrestlePanel;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.*;

/** All world mutations run from the server poll, including requests received from clients. */
public final class WurmRailRuntime implements WurmServerMod, Configurable,
        Initable, ItemTemplatesCreatedListener, ServerStartedListener, ServerPollListener,
        ServerShutdownListener, PlayerMessageListener {
    private static final Logger LOG = Logger.getLogger(WurmRailRuntime.class.getName());
    private final ConcurrentLinkedQueue<Runnable> requests = new ConcurrentLinkedQueue<Runnable>();
    private RailNetwork network;
    private Path stateFile;
    private boolean ready;
    private ScheduledServices scheduledServices=new ScheduledServices();
    private boolean autoResumeScheduled=true;
    private String builders = "*", operators = "*";
    private int maxCars = 8, maxNodes = 10000;
    private double speedLimit = 5.0;
    private RailwayTrestleSurvey trestleSurvey;
    private long lastTick, lastSave;
    private String validationError;
    private boolean trackVisualsDirty;
    private long junctionVisualRevision=-1,lastJunctionRepair;
    private long signalVisualRevision=-1,lastSignalRepair;
    private static final String[] COMPASS={"N","NE","E","SE","S","SW","W","NW"};
    private static final int[][] DIRECTIONS={{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};
    private final Map<Long,Pose> appliedPoses = new HashMap<Long,Pose>();
    private final Map<Long,String> motionErrors = new HashMap<Long,String>();
    private RailwayTrainService trainService;
    private PassengerStations passengerStations=new PassengerStations();
    private RailwayPassengerService passengerService;
    private FreightStops freightStops=new FreightStops();
    private RailwayFreightService freightService;
    private double freightTransferKgPerSecond=100;
    private WaterFillStops waterFillStops=new WaterFillStops();
    private final Set<String> deprecatedWaterFillRails=new HashSet<String>();
    private WaterUnloadStops waterUnloadStops=new WaterUnloadStops();
    private RailwayWaterUnloadService waterUnloadService;
    private double waterUnloadLitresPerSecond=100;
    private final RailwayIndustrialWaterTank.Api industrialTankApi=new RailwayIndustrialWaterTank.Api() {
        @Override public void open(Creature actor,long id){queue(actor,()->RailwayIndustrialWaterTank.show(actor,id,industrialTankApi));}
        @Override public void recover(Creature actor,long id) {
            queue(actor,()-> {
                Item tank=Items.getItem(id);LiquidTankTransfers.clearFault(actor,tank);tell(actor,"Empty liquid tank fault cleared after inspection.");
                if(LiquidTankPolicy.isIndustrialTank(tank))RailwayIndustrialWaterTank.show(actor,id,industrialTankApi);
            });
        }
    };
    private final RailwayWaterUnloadUi.Api waterUnloadApi=new RailwayWaterUnloadUi.Api() {
        @Override public RailwayWaterUnloadUi.View snapshot(Creature actor,long marker) throws Exception{return waterUnloadSnapshot(actor,marker);}
        @Override public void submit(Creature actor,long marker,RailwayWaterUnloadUi.Request request) {
            queue(actor,()->{try{applyWaterUnloadRequest(actor,marker,request);}catch(Exception error){tellFailure(actor,error);}
                RailwayWaterUnloadUi.show(actor,marker,waterUnloadApi);});
        }
    };
    private double steamRockDrillIntervalSeconds=30;
    private RailwayDrillController drillController;
    private SteamSupplyConfig steamSupplyConfig=SteamSupplyConfig.defaults();
    private SteamSupplyController steamSupplies;
    private double steamYarderIntervalSeconds=30;
    private RailwayYarderController yarderController;
    private double steamWaterPumpLitresPerSecond=100;
    private RailwayPumpController pumpController;
    private final RailwaySteamWaterPump.Api pumpApi=new RailwaySteamWaterPump.Api() {
        @Override public RailwaySteamWaterPump.View snapshot(Creature actor,long id){return pumps().snapshot(actor,id);}
        @Override public void submit(Creature actor,long id,RailwaySteamWaterPump.Request request) {
            queue(actor,()->{try{pumps().apply(actor,id,request);}catch(Exception error){tellFailure(actor,error);}
                RailwaySteamWaterPump.show(actor,id,pumpApi);});
        }
        @Override public void interact(Creature actor,long id,RailwaySteamWaterPump.Interaction interaction) {
            queue(actor,()->{try {
                if(interaction==RailwaySteamWaterPump.Interaction.START)pumps().start(actor,id);
                else if(interaction==RailwaySteamWaterPump.Interaction.STOP)pumps().stop(actor,id);
            }catch(Exception error){tellFailure(actor,error);}
                RailwaySteamWaterPump.show(actor,id,pumpApi);});
        }
    };
    private RailwayPumpController pumps() {
        if(pumpController==null)throw new IllegalArgumentException("Steam Water Pumps are unavailable. Check the server's pump state log.");
        return pumpController;
    }
    private final RailwaySteamYarder.Api yarderApi=new RailwaySteamYarder.Api() {
        @Override public RailwaySteamYarder.View snapshot(Creature actor,long id){return yarders().snapshot(actor,id);}
        @Override public void submit(Creature actor,long id,RailwaySteamYarder.Request request) {
            queue(actor,()->{try{yarders().apply(actor,id,request);}catch(Exception error){tellFailure(actor,error);}
                RailwaySteamYarder.show(actor,id,yarderApi);});
        }
        @Override public void interact(Creature actor,long id,RailwaySteamYarder.Interaction interaction) {
            queue(actor,()->{try {
                if(interaction==RailwaySteamYarder.Interaction.START)yarders().start(actor,id);
                else if(interaction==RailwaySteamYarder.Interaction.STOP)yarders().stop(actor,id);
            }catch(Exception error){tellFailure(actor,error);}
                RailwaySteamYarder.show(actor,id,yarderApi);});
        }
    };
    private RailwayYarderController yarders() {
        if(yarderController==null)throw new IllegalArgumentException("Steam Yarders are unavailable. Check the server's yarder state log.");
        return yarderController;
    }
    private final RailwayLogBunk.Api logBunkApi=new RailwayLogBunk.Api() {
        @Override public void open(Creature actor,long id){queue(actor,()->RailwayLogBunk.show(actor,id,logBunkApi));}
    };
    private long lastDrillTick;
    private final RailwaySteamRockDrill.Api drillApi=new RailwaySteamRockDrill.Api() {
        @Override public RailwaySteamRockDrill.View snapshot(Creature actor,long id) {
            return drills().snapshot(actor,id);
        }
        @Override public void submit(Creature actor,long id,RailwaySteamRockDrill.Request request) {
            queue(actor,()-> {
                try { drills().apply(actor,id,request); }
                catch(Exception error) { tellFailure(actor,error); }
                RailwaySteamRockDrill.show(actor,id,drillApi);
            });
        }
        @Override public void interact(Creature actor,long id,RailwaySteamRockDrill.Interaction interaction) {
            queue(actor,()-> {
                try {
                    if(interaction==RailwaySteamRockDrill.Interaction.START)drills().start(actor,id);
                    else if(interaction==RailwaySteamRockDrill.Interaction.STOP)drills().stop(actor,id);
                } catch(Exception error) { tellFailure(actor,error); }
                RailwaySteamRockDrill.show(actor,id,drillApi);
            });
        }
    };
    private RailwayDrillController drills() {
        if(drillController==null)throw new IllegalArgumentException("Steam Rock Drills are unavailable. Check the server's drill state log.");
        return drillController;
    }
    private final RailwayBulkHopper.Api hopperApi=new RailwayBulkHopper.Api() {
        @Override public void open(Creature actor,long id) {
            queue(actor,()->RailwayBulkHopper.show(actor,id,hopperApi));
        }
    };
    private long lastDiscovery;
    private final RailwayBuilderUi.Api builderApi = new RailwayBuilderUi.Api() {
        @Override public RailwayBuilderUi.View snapshot(Creature p,int x,int y,int z) throws Exception { return builderSnapshot(p,x,y,z); }
        @Override public void submit(Creature p,int x,int y,int z,RailwayBuilderUi.Request request) {
            queue(p,()-> {
                try { applyBuilder(p,x,y,z,request); }
                catch(Exception failure) {
                    tellFailure(p,failure);
                    try { RailwayBuilderUi.show(p,x,y,z,builderApi); } catch(Exception unavailable) { }
                }
            });
        }
    };
    private final RailwayTrainUi.Api trainApi = new RailwayTrainUi.Api() {
        @Override public RailwayTrainUi.View snapshot(Creature p,long car) throws Exception { return trainSnapshot(p,car); }
        @Override public void submit(Creature p,long car,Train expected,RailwayTrainUi.Request request) {
            queue(p,()-> {
                try { applyTrainRequest(p,car,expected,request); }
                catch(Exception failure) {
                    tellFailure(p,failure);
                    try { RailwayTrainUi.show(p,car,trainApi); } catch(Exception unavailable) { }
                }
            });
        }
    };
    private final RailwayPassengerUi.Api passengerApi=new RailwayPassengerUi.Api() {
        @Override public RailwayPassengerUi.View snapshot(Creature p,long id) throws Exception { return passengerSnapshot(p,id); }
        @Override public void submit(Creature p,long id,RailwayPassengerUi.Request request) {
            queue(p,()-> {
                try { applyPassengerRequest(p,id,request); }
                catch(Exception error) { tellFailure(p,error); try { RailwayPassengerUi.show(p,id,passengerApi); } catch(Exception unavailable) { } }
            });
        }
    };
    private final RailwayPassengerUi.BuilderApi passengerBuilderApi=new RailwayPassengerUi.BuilderApi() {
        @Override public RailwayPassengerUi.BuildView buildSnapshot(Creature p,int x,int y,int z) { return passengerBuildSnapshot(p,x,y,z); }
        @Override public void build(Creature p,int x,int y,int z,RailwayPassengerUi.BuildRequest request) {
            queue(p,()-> {
                try { buildPassengerPlatform(p,x,y,z,request); }
                catch(Exception error) { tellFailure(p,error); try { RailwayPassengerUi.showBuilder(p,x,y,z,passengerBuilderApi); } catch(Exception unavailable) { } }
            });
        }
    };
    private final RailwaySignalUi.Api signalApi=new RailwaySignalUi.Api() {
        @Override public RailwaySignalUi.View snapshot(Creature p,long clicked) throws Exception { return signalSnapshot(p,clicked); }
        @Override public void submit(Creature p,long clicked,RailwaySignalUi.Request request) {
            queue(p,()-> {
                try { applySignalRequest(p,clicked,request); }
                catch(Exception error) {
                    tellFailure(p,error);
                    try { RailwaySignalUi.show(p,clicked,signalApi); } catch(Exception unavailable) { }
                }
            });
        }
    };
    private final RailwayFreightUi.Api freightApi=new RailwayFreightUi.Api() {
        @Override public RailwayFreightUi.View snapshot(Creature actor,long marker) throws Exception {
            return freightSnapshot(actor,marker);
        }
        @Override public void submit(Creature actor,long marker,RailwayFreightUi.Request request) {
            queue(actor,()-> {
                try { applyFreightRequest(actor,marker,request); }
                catch(Exception error) { tellFailure(actor,error); }
                RailwayFreightUi.show(actor,marker,freightApi);
            });
        }
    };

    @Override public void configure(Properties p) {
        builders = p.getProperty("builders", "*");
        operators = p.getProperty("operators", "*");
        maxCars = integer(p.getProperty("maxCars", "8"), 1, 32);
        maxNodes = integer(p.getProperty("maxNodes", "10000"), 10, 100000);
        speedLimit = number(p.getProperty("maxSpeed", "5"), 0.25, 6);
        autoResumeScheduled=Boolean.parseBoolean(p.getProperty("autoResumeScheduled","true"));
        freightTransferKgPerSecond=number(p.getProperty("freightTransferKgPerSecond","100"),.1,10000);
        waterUnloadLitresPerSecond=number(p.getProperty("waterFreightLitresPerSecond",p.getProperty("waterUnloadLitresPerSecond","100")),.1,10000);
        steamWaterPumpLitresPerSecond=number(p.getProperty("steamWaterPumpLitresPerSecond","100"),.1,10000);
        steamRockDrillIntervalSeconds=number(p.getProperty("steamRockDrillIntervalSeconds","30"),.1,86400);
        steamSupplyConfig=SteamSupplyConfig.fromProperties(p);
        steamYarderIntervalSeconds=number(p.getProperty("steamYarderIntervalSeconds","30"),.1,86400);
        trestleSurvey = new RailwayTrestleSurvey(new RailwayTrestleSurvey.Api() {
            @Override public Node railAt(int x, int y) { return nodeAt(x, y, 0); }
            @Override public boolean hasDeck(int x,int y) {
                return network.nodesAt(x,y,0).stream().anyMatch(WurmRailRuntime.this::isTrestleDeck);
            }
            @Override public Node rail(String name) { return network.nodes.get(name); }
            @Override public boolean trackPresent(Node rail) { return trackMarkerPresent(rail); }
            @Override public String trestleAt(int x,int y,long marker) { return WurmRailRuntime.this.trestleAt(x,y,marker); }
            @Override public void validateCrossings(RailwayTrestleSurvey.Plan plan) throws Exception {
                RailwayTrestleCrossings.validate(network,plan);
            }
            @Override public void remove(Creature actor,String span) throws Exception {
                removeStraightTrestle(actor,span);
            }
            @Override public void build(Creature actor, RailwayTrestleSurvey.Plan plan) throws Exception {
                buildTrestle(actor, plan);
            }
            @Override public void submit(Creature actor, RailwayTrestleSurvey.Response response) {
                queue(actor, () -> { requireBuilder(actor); response.apply(); });
            }
        }, integer(p.getProperty("trestleMaxTiles", "250"), 1, 500),
                number(p.getProperty("trestleMaxGradientPercent", "50"), 0, 100) / 100,
                integer(p.getProperty("trestleBentSpacingTiles", "3"), 1, 50),
                Math.max(3, integer(p.getProperty("trestleMinCurveRadiusTiles", "3"), 1, 500)));
    }

    @Override public void init() {
        RailConsistTransport.register();
        RailwayTrestleActions.register(new RailwayTrestleActions.Api() {
            @Override public boolean canSurvey(Creature actor) {
                return actor.getPower() >= 2 || allowed(builders, actor.getName());
            }
            @Override public boolean hasSurvey(Creature actor) { return trestleSurvey.hasSurvey(actor); }
            @Override public String trestleAt(int x,int y,long marker) { return WurmRailRuntime.this.trestleAt(x,y,marker); }
            @Override public void submit(Creature actor, RailwayTrestleActions.Operation operation, long tool,
                                         int x, int y, long target) {
                queue(actor, () -> {
                    requireBuilder(actor);
                    trestleSurvey.handle(actor, operation, tool, x, y, target);
                });
            }
        });
        RailwayMenuActions.register(new RailwayMenuActions.Api() {
            @Override public boolean canBuild(Creature p) { return p.getPower()>=2||allowed(builders,p.getName()); }
            @Override public void openBuilder(Creature p,int x,int y,int z) {
                queue(p,()-> { RailwayBuilderUi.show(p,x,y,z,builderApi); });
            }
            @Override public void openVehicle(Creature p,Item item,String operation) {
                final long id=item.getWurmId();
                queue(p,()-> { WurmRailRuntime.this.openVehicle(p,id,operation); });
            }
            @Override public void cycleAlignment(Creature p,Item item) {
                final long id=item.getWurmId(); queue(p,()->cycleJunction(p,id));
            }
        });
        RailwayPassengerActions.register(new RailwayPassengerActions.Api() {
            @Override public boolean canBuild(Creature p) { return p.getPower()>=2||allowed(builders,p.getName()); }
            @Override public void build(Creature p,int x,int y,int z) { queue(p,()-> {
                Platform existing=passengerStations.at(x,y,z);
                if(existing==null)RailwayPassengerUi.showBuilder(p,x,y,z,passengerBuilderApi);
                else RailwayPassengerUi.show(p,existing.itemId,passengerApi);
            }); }
            @Override public void platform(Creature p,long id) { queue(p,()->RailwayPassengerUi.show(p,id,passengerApi)); }
            @Override public void journey(Creature p,long car) { queue(p,()-> {
                Item clicked=Items.getItem(car); checkNear(p,clicked);
                Train mounted=findTrain(p.getVehicle());
                if(mounted==null||findTrain(car)!=mounted) throw new IllegalArgumentException("Board this train first, or choose your destination from a station.");
                RailwayPassengerUi.show(p,-10L,passengerApi);
            }); }
        });
        RailwaySignalActions.register((p,clicked)->queue(p,()->RailwaySignalUi.show(p,clicked,signalApi)));
        RailwayFreightActions.register(new RailwayFreightActions.Api() {
            @Override public boolean canConfigure(Creature actor) { return actor.getPower()>=2||allowed(builders,actor.getName()); }
            @Override public void open(Creature actor,Item marker) {
                long id=marker.getWurmId();queue(actor,()->RailwayFreightUi.show(actor,id,freightApi));
            }
        });
        RailwayBulkHopper.registerActions(hopperApi);
        RailwayLogBunk.registerActions(logBunkApi);
        RailwayIndustrialWaterTank.registerActions(industrialTankApi);
        RailwayWaterUnloadActions.register(new RailwayWaterUnloadActions.Api() {
            @Override public boolean canConfigure(Creature actor){return actor.getPower()>=2||allowed(builders,actor.getName());}
            @Override public void open(Creature actor,Item marker){long id=marker.getWurmId();queue(actor,()->RailwayWaterUnloadUi.show(actor,id,waterUnloadApi));}
        });
        RailwaySteamRockDrill.registerActions(drillApi);
        RailwaySteamYarder.registerActions(yarderApi);
        RailwaySteamWaterPump.registerActions(pumpApi);
    }
    @Override public void onItemTemplatesCreated() {
        try { WurmRailWorld.createTemplates(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Unable to create rail templates",e); }
    }

    @Override public void onServerStarted() {
        stateFile = Paths.get(ServerDirInfo.getFileDBPath()).toAbsolutePath()
                .resolve("wurmrail").resolve("network.properties");
        try {
            WurmRailWorld.createRecipes();
            network = RailNetwork.load(stateFile);
            scheduledServices=ScheduledServices.load(servicesFile());
            passengerStations=PassengerStations.load(passengerFile());
            freightStops=FreightStops.load(freightFile());
            loadDeprecatedWaterFillStops();
            waterUnloadStops=WaterUnloadStops.load(waterUnloadFile());
            try {
                steamSupplies=new SteamSupplyController(stateFile.resolveSibling("steamSupplies.properties"),steamSupplyConfig);
            }catch(Exception supplyError) {
                steamSupplies=null;
                LOG.log(Level.SEVERE,"Steam machines disabled: preserve and repair wurmrail/steamSupplies.properties. No unpaid production is enabled.",supplyError);
            }
            try {
                drillController=new RailwayDrillController(stateFile.resolveSibling("drills.properties"),steamRockDrillIntervalSeconds,steamSupplies);
            } catch(Exception machineStateError) {
                drillController=null;
                LOG.log(Level.SEVERE,"Steam Rock Drills disabled: preserve and repair wurmrail/drills.properties. Railway services remain available.",machineStateError);
            }
            network.maxCars = maxCars;
            try {
                yarderController=new RailwayYarderController(stateFile.resolveSibling("yarders.properties"),steamYarderIntervalSeconds,steamSupplies);
            } catch(Exception machineStateError) {
                yarderController=null;
                LOG.log(Level.SEVERE,"Steam Yarders disabled: preserve and repair wurmrail/yarders.properties. Railway services remain available.",machineStateError);
            }
            for (Train t : network.trains.values()) {
                // Retain existing consists when a server lowers its new-coupling limit.
                network.maxCars = Math.max(network.maxCars,t.carIds.size());
                t.maxSpeed = Math.min(t.maxSpeed, speedLimit);
                for (Pose pose : t.poses)
                    RailModelRegistry.setElevatedCarHeight(pose.carId,pose.elevation);
            }
            refreshTrackVisuals();
            try {
                pumpController=new RailwayPumpController(stateFile.resolveSibling("pumps.properties"),steamWaterPumpLitresPerSecond,steamSupplies);
            }catch(Exception machineStateError) {
                pumpController=null;
                LOG.log(Level.SEVERE,"Steam Water Pumps disabled: preserve and repair wurmrail/pumps.properties. Railway services remain available.",machineStateError);
            }
            network.movementGuard = poses -> validateMotion(poses);
            initService();
            trainService.synchronizeExistingNames();
            refreshStationMarkers();
            refreshFreightMarkers();
            retireDeprecatedWaterFillStops();
            refreshWaterUnloadMarkers();
            resumeScheduledServices();
            if(!trainService.discoverGroundEngines().isEmpty()) save();
            lastTick = System.nanoTime(); lastSave = System.currentTimeMillis(); ready = true;
            lastDrillTick=lastTick;
            LOG.info("WurmRail ready: " + network.nodes.size() + " track nodes, "
                    + network.trains.size() + " trains. State: " + stateFile);
        } catch (Exception e) {
            ready = false;
            LOG.log(Level.SEVERE, "WurmRail disabled: state could not be loaded. Preserve and repair " + stateFile, e);
        }
    }

    @Override public void onServerPoll() {
        if (!ready) return;
        int budget = 64;
        Runnable request;
        while (budget-- > 0 && (request = requests.poll()) != null) {
            try { request.run(); } catch (Exception e) { LOG.log(Level.WARNING, "Rail request failed", e); }
        }
        long now = System.nanoTime();
        if (trestleSurvey != null) trestleSurvey.pollPreviews(now);
        // Machines use their own wall clock; train substeps and rollback do not
        // alter a mining interval or undo an already committed real resource.
        double machineElapsed=lastDrillTick==0?0:Math.max(0,(now-lastDrillTick)/1.0e9);
        lastDrillTick=now;
        if(drillController!=null)try { drillController.tick(machineElapsed); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Steam Rock Drill service stopped after a machine-state error",machineFailure); }
        if(yarderController!=null)try { yarderController.tick(machineElapsed); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Steam Yarder service stopped after a machine-state error",machineFailure); }
        if(pumpController!=null)try { pumpController.tick(machineElapsed); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Steam Water Pump service stopped after a machine-state error",machineFailure); }
        if(now-lastDiscovery>=5000000000L) {
            lastDiscovery=now;
            try { if(!trainService.discoverGroundEngines().isEmpty()) save(); }
            catch(Exception e) { LOG.log(Level.WARNING,"Could not save automatic train setup",e); }
        }
        double elapsed = Math.min(0.25, (now - lastTick) / 1.0e9);
        if (elapsed < 0.05) return;
        lastTick = now;
        Map<String,Checkpoint> checkpoints=new LinkedHashMap<String,Checkpoint>();
        for(Train t:network.trains.values()) checkpoints.put(t.name,network.checkpoint(t.name));
        boolean movementCommitted=false;
        passengerService.refreshPlayers();
        try {
            Map<String, List<Pose>> moved = network.tick(elapsed);
            WurmRailWorld.MotionBatch batch = new WurmRailWorld.MotionBatch(now);
            for (Map.Entry<String,List<Pose>> entry : moved.entrySet()) {
                Train t = network.trains.get(entry.getKey());
                if(t.status.equals("Movement blocked by the world")&&motionErrors.containsKey(t.carIds.get(0)))
                    t.status="Blocked: "+motionErrors.get(t.carIds.get(0));
                boolean restoring=false;
                for(Pose pose:entry.getValue()) if(!appliedPoses.containsKey(pose.carId)) restoring=true;
                if(restoring&&!validateMotion(entry.getValue())) {
                    network.stop(t.name); t.status="Restore blocked: "+validationError; continue;
                }
                boolean changed = false;
                for (Pose pose : entry.getValue()) {
                    Pose old=appliedPoses.get(pose.carId);
                    if(old!=null&&old.x==pose.x&&old.y==pose.y&&old.rotation==pose.rotation&&old.layer==pose.layer
                            &&Float.compare(old.elevation,pose.elevation)==0) continue;
                    WurmRailWorld.moveCar(pose,old,batch);
                    appliedPoses.put(pose.carId,pose);
                    changed = true;
                }
                if (changed) batch.addConsist(entry.getValue());
            }
            if (System.currentTimeMillis() - lastSave >= 5000) save();
            batch.send();
            movementCommitted=true;
        } catch (Exception e) {
            // A following train may have entered a block released earlier this tick.
            // Restore the entire tick before returning the world items to those poses.
            for(Checkpoint snapshot:checkpoints.values()) network.restore(snapshot);
            for(Train t:network.trains.values()) {
                network.stop(t.name);scheduledServices.record(t,false);t.status="Stopped after a world error; check server log";
            }
            WurmRailWorld.MotionBatch restored = new WurmRailWorld.MotionBatch();
            for(Checkpoint snapshot:checkpoints.values()) {
                boolean complete = true;
                for(Pose pose:snapshot.poses) {
                    try {
                        WurmRailWorld.moveCar(pose,appliedPoses.get(pose.carId),restored);
                        appliedPoses.put(pose.carId,pose);
                    } catch(Exception restoreError) {
                        complete = false;
                        appliedPoses.remove(pose.carId);
                        LOG.log(Level.WARNING,"Could not restore rail vehicle "+pose.carId+"; its train remains stopped",restoreError);
                    }
                }
                if (complete) restored.addConsist(snapshot.poses);
            }
            restored.send();
            LOG.log(Level.SEVERE, "Rail poll failed; restored the previous tick and stopped all trains", e);
        }
        if(movementCommitted) {
            // Transfers cannot be rolled back by a later world-movement failure.
            try {
                passengerService.serviceStops();
                if(passengerService.dirty())savePassengers();
            } catch(Exception e) { LOG.log(Level.WARNING,"Passenger station service could not complete",e); }
            try { freightService.serviceStops(elapsed); }
            catch(Exception e) { LOG.log(Level.WARNING,"Freight station service could not complete",e); }
            try {waterUnloadService.serviceStops(elapsed);}
            catch(Exception e){LOG.log(Level.WARNING,"Liquid freight service could not complete",e);}
            // Definitions can disappear in an older saved world. A hold that
            // belongs to neither service must not strand its train forever.
            for(Train train:network.trains.values())if(train.serviceStopRail!=null
                    &&freightStops.get(train.serviceStopRail)==null
                    &&waterUnloadStops.get(train.serviceStopRail)==null) {
                String rail=train.serviceStopRail;long serial=train.serviceStopSerial;
                if(network.releaseRouteServiceStop(train.name,rail,serial)&&train.powered)
                    train.status="Service stop unavailable: its configuration was removed.";
            }
        }
        refreshJunctionIndicators(false);
        refreshSignalVisuals(false);
    }

    @Override public void onServerShutdown() {
        if(steamSupplies!=null)try {steamSupplies.save();}
        catch(Exception suppliesError){LOG.log(Level.SEVERE,"Could not save steam supply buffers",suppliesError);}
        if(pumpController!=null)try { pumpController.save(); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Could not save Steam Water Pump settings",machineFailure); }
        if(yarderController!=null)try { yarderController.save(); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Could not save Steam Yarder settings",machineFailure); }
        if(drillController!=null)try { drillController.save(); }
        catch(Exception machineFailure) { LOG.log(Level.SEVERE,"Could not save Steam Rock Drill settings",machineFailure); }
        if(passengerService!=null)passengerService.close();
        if(freightService!=null)freightService.close();
        if(waterUnloadService!=null)waterUnloadService.close();
        if (ready) {
            for (Train t : network.trains.values()) network.stop(t.name);
            try { save(); } catch (Exception e) { LOG.log(Level.SEVERE, "Rail shutdown save failed", e); }
            try { savePassengers(); } catch(Exception e) { LOG.log(Level.SEVERE,"Passenger station shutdown save failed",e); }
        }
        ready = false;
    }

    /** Restore every stationary consist before any saved service can request movement. */
    private void resumeScheduledServices() {
        Set<String> restored=new HashSet<String>();
        for(Train train:network.trains.values()) {
            scheduledServices.restoreProgress(train);
            try { applyPoses(train);restored.add(train.name); }
            catch(Exception blocked) {
                network.stop(train.name);train.status="Restore blocked: "+blocked.getMessage();
                LOG.log(Level.WARNING,"Scheduled train remains stopped: "+train.name+": "+blocked.getMessage());
            }
        }
        network.signals().traffic().restoreOccupiedCorridors();
        if(!autoResumeScheduled)return;
        int resumed=0;
        for(String name:scheduledServices.resumeCandidates(network)) {
            Train train=network.trains.get(name);if(train==null||!restored.contains(name))continue;
            try {
                validateStationRoute(train,train.route);checkTrainWorld(train);network.resumeScheduled(name);
                if(train.powered)resumed++;
            }catch(Exception blocked) {
                network.stop(name);train.status="Scheduled restart held: "+blocked.getMessage();
                LOG.log(Level.WARNING,"Scheduled restart held for "+name+": "+blocked.getMessage());
            }
        }
        if(resumed>0)LOG.info("Resumed "+resumed+" saved scheduled railway services.");
    }

    @Override public boolean onPlayerMessage(Communicator c, String message) {
        String s = message.trim();
        if (!s.equalsIgnoreCase("/rail") && !s.toLowerCase(Locale.ROOT).startsWith("/rail ")) return false;
        enqueue(c.getPlayer(), s.substring(5).trim()); return true;
    }
    @Override public MessagePolicy onPlayerMessage(Communicator c, String message, String title) {
        return onPlayerMessage(c,message) ? MessagePolicy.DISCARD : MessagePolicy.PASS;
    }

    private void enqueue(final Creature player, final String command) {
        if (!ready) { tell(player, "The rail system is unavailable; check the server log."); return; }
        if (requests.size() > 256) { tell(player, "Rail request queue is busy. Try again shortly."); return; }
        requests.add(() -> {
            try { execute(player, command); }
            catch (Exception e) { tell(player, e.getMessage() == null ? e.toString() : e.getMessage()); }
        });
    }

    private interface RailRequest { void run() throws Exception; }
    private void queue(final Creature p,final RailRequest request) {
        if(!ready) { tell(p,"The railway is unavailable. Check the server log."); return; }
        if(requests.size()>256) { tell(p,"The railway is busy. Try again shortly."); return; }
        requests.add(()-> { try { if(!ready) throw new IllegalStateException("The railway is unavailable."); request.run(); }
            catch(Exception e) { tell(p,e.getMessage()==null?"The railway action could not be completed.":e.getMessage()); } });
    }

    private void initService() {
        if(passengerService!=null)passengerService.close();
        if(freightService!=null)freightService.close();
        if(waterUnloadService!=null)waterUnloadService.close();
        trainService=new RailwayTrainService(network,speedLimit,maxCars,new RailwayTrainService.Motion() {
            @Override public void apply(List<Pose> poses) throws Exception {
                if(!validateMotion(poses)) throw new IllegalArgumentException(validationError);
                restore(poses);
            }
            @Override public void restore(List<Pose> poses) throws Exception {
                restoreTrainPoses(poses);
            }
        });
        passengerService=new RailwayPassengerService(network,()->passengerStations,new RailwayPassengerService.World() {
            @Override public Creature[] players() { return RailwayPassengerWorld.onlinePlayers(); }
            @Override public boolean present(Platform p) { return platformPresent(p); }
            @Override public boolean inArea(Creature player,Platform p) { return RailwayPassengerWorld.inStationArea(player,p); }
            @Override public boolean waiting(Creature player,Platform p) {
                return RailwayPassengerWorld.eligible(player)&&inArea(player,p);
            }
            @Override public void waitingIndicator(Creature player,Platform p,boolean waiting) {
                RailwayPassengerWorld.waitingIndicator(player,p,waiting);
            }
            @Override public boolean freeSeat(Creature player,long id) {
                return Items.getItemOptional(id).map(car->RailwayPassengerWorld.hasFreeSeat(player,car)).orElse(false);
            }
            @Override public boolean board(Creature player,long id) throws Exception { return RailwayPassengerWorld.board(player,Items.getItem(id)); }
            @Override public boolean drop(Creature player,long id,Platform p) throws Exception {
                return RailwayPassengerWorld.disembarkTo(player,Items.getItem(id),p.x,p.y,p.layer);
            }
            @Override public void message(Creature player,String text) { tell(player,text); }
        });
        network.passengerStops=(train,rail,car)->passengerService.shouldStop(train,rail,car);
        freightService=new RailwayFreightService(network,()->freightStops,new RailwayFreightWorld(network),this::freightOperatorFault);
        waterUnloadService=new RailwayWaterUnloadService(network,()->waterUnloadStops,new RailwayWaterUnloadWorld(network),this::waterOperatorFault);
        network.routeStopServices=(train,rail)->freightService.shouldService(train,rail)||waterUnloadService.shouldService(train,rail);
    }
    private void waterOperatorFault(Train train,String reason) {
        scheduledServices.record(train,false);
        LOG.severe("Liquid freight service stopped for operator attention: "+train.name+": "+reason);
        try { save(); }catch(Exception failure) { LOG.log(Level.SEVERE,"Could not save the liquid freight safety stop",failure); }
    }
    private void freightOperatorFault(Train train,String reason) {
        scheduledServices.record(train,false);
        LOG.severe("Freight service stopped for operator attention: "+train.name+": "+reason);
        try { save(); }catch(Exception failure) { LOG.log(Level.SEVERE,"Could not save the freight safety stop",failure); }
    }

    private void execute(Creature p, String command) throws Exception {
        String[] a = command.isEmpty() ? new String[]{"help"} : command.split("\\s+");
        String cmd = a[0].toLowerCase(Locale.ROOT);
        if (cmd.equals("help")) {
            tell(p, "Layout: /rail track NAME | line PREFIX N/NE/E/SE/S/SW/W/NW COUNT | link A B | unlink A B | remove NODE | repair NODE | align NODE SIDE_A SIDE_B | cycle NODE");
            tell(p, "Track details: /rail inspect [NODE] | station NODE SECONDS/off. Track bends and junction models follow the actual connections.");
            tell(p, "Trains: /rail claim NAME TOWARDS [ENGINE_ID] | couple TRAIN [CART_ID] | uncouple TRAIN | forget TRAIN");
            tell(p, "Drive: /rail panel [TRAIN] | start [TRAIN] | stop [TRAIN] | reverse [TRAIN] | speed TRAIN METRES_PER_SECOND");
            tell(p, "Automation: /rail route TRAIN NODE,NODE,... | manual TRAIN | dwell TRAIN SECONDS");
            tell(p, "Signals: /rail signals [NODE] inspects aspects, route owners and reasons. Right-click track or a signal to configure.");
            tell(p, "Cargo: /rail load TRAIN ITEM_ID [CAR_NUMBER] | unload TRAIN ITEM_ID; or Open the wagon and drag cargo normally.");
            tell(p, "Other: /rail list | status [TRAIN] | board [TRAIN] [CAR_NUMBER] | id | spawn engine/cart/logcar (GM)");
            return;
        }
        if (cmd.equals("list")) {
            tell(p, "Tracks ("+network.nodes.size()+"): "+abbreviate(String.join(", ",network.nodes.keySet())));
            tell(p, "Trains: "+abbreviate(String.join(", ",network.trains.keySet()))); return;
        }
        if (cmd.equals("id")) {
            for (Item i : nearby(p)) tell(p, i.getName()+" ID "+i.getWurmId()); return;
        }
        if(cmd.equals("signals")) {
            Node selected=a.length>1?node(a[1]):null;
            boolean found=false;
            for(SignalSystem.Signal signal:network.signals().definitions()) if(selected==null||selected.name.equals(signal.rail)) {
                tell(p,signalDescription(signal)); found=true;
            }
            if(!found)tell(p,"No railway signals"+(selected==null?" have been placed.":" on this track."));
            return;
        }
        if(cmd.equals("inspect")) {
            Node n=a.length>1?node(a[1]):nodeAt(p.getTileX(),p.getTileY(),layer(p));
            if(n==null) throw new IllegalArgumentException("Stand on a track tile or use /rail inspect NODE.");
            tell(p,n.name+" at tile "+n.tileX+","+n.tileY+" ("+(n.layer==0?"surface":"cave")+"). Connected exits: "+(n.links.isEmpty()?"none":String.join(", ",n.links))+". Shape mask "+trackMask(n)+".");
            if(n.station) tell(p,"Station dwell: "+n.dwellSeconds+" seconds.");
            tell(p,"Junction alignment: "+RailwayJunctionWorld.alignmentName(n.getAlignmentMask())
                    +(n.getAlignmentOwner()==null?"":" (locked by "+n.getAlignmentOwner()+")"));
            for(SignalSystem.Signal signal:network.signals().definitions())if(signal.rail.equals(n.name))tell(p,signalDescription(signal));
            if(n.trestle()!=null)tell(p,"Trestle "+n.trestle()+" | "+n.timber().size()+" timber stages assigned to this tile.");
            return;
        }
        if(cmd.equals("removetrestle")) {
            require(a,2,"removetrestle NAME");
            removeStraightTrestle(p,a[1]);
            tell(p,"Removed trestle "+a[1]+".");
            return;
        }
        if (cmd.equals("track")) {
            require(a,2,"track NAME"); requireBuilder(p); buildLine(p,a[1],p.getTileX(),p.getTileY(),layer(p),0,1,false);
            tell(p,"Track " + a[1] + " laid. Clear adjacent nodes connect automatically; use /rail inspect "+a[1]+" to check exits."); return;
        }
        if (cmd.equals("line")) {
            require(a,4,"line PREFIX N/NE/E/SE/S/SW/W/NW COUNT"); requireBuilder(p);
            int count=integer(a[3],1,100); String d=a[2].toUpperCase(Locale.ROOT);
            int direction=Arrays.asList(COMPASS).indexOf(d);
            if(direction<0) throw new IllegalArgumentException("Direction must be N, NE, E, SE, S, SW, W, or NW.");
            int dx=DIRECTIONS[direction][0],dy=DIRECTIONS[direction][1];
            buildLine(p,a[1],p.getTileX(),p.getTileY(),layer(p),direction,count,true);
            tell(p,"Laid "+count+" track sections."); return;
        }
        if(cmd.equals("link")||cmd.equals("unlink")) {
            require(a,3,cmd+" A B"); requireBuilder(p); Node from=node(a[1]), to=node(a[2]);
            if(cmd.equals("link")) {
                String error=WurmRailWorld.validateEdge(from.tileX,from.tileY,from.layer,to.tileX,to.tileY,to.layer);
                if(error!=null) throw new IllegalArgumentException(error); connectNewNodes(from,to);
            } else {
                requireConstructionShape(from,trackMask(from)&~connectionBit(from,to));
                requireConstructionShape(to,trackMask(to)&~connectionBit(to,from));
                network.disconnect(a[1],a[2]);
            }
            trackVisualsDirty=true; save(); tell(p,"Track connection and model updated."); return;
        }
        if(cmd.equals("remove")) { require(a,2,"remove NODE"); removeAndRecover(p,node(a[1])); save(); tell(p,"Track removed; its section was recovered."); return; }
        if(cmd.equals("repair")) {
            require(a,2,"repair NODE"); repairTrack(p,node(a[1])); tell(p,"Track repaired."); return;
        }
        if(cmd.equals("align")||cmd.equals("alignment")||cmd.equals("switch")) {
            require(a,3,"align NODE SIDE_A SIDE_B (or off)"); Node n=node(a[1]); requireBuildContext(p,n.tileX,n.tileY,n.layer);
            if(a[2].equalsIgnoreCase("off"))setJunction(p,n,0);
            else if(a.length>=4)setJunction(p,n,(1<<alignmentSide(n,a[2]))|(1<<alignmentSide(n,a[3])));
            else if(cmd.equals("switch")) {
                JunctionCheckpoint before=network.checkpointJunctions();
                try {
                    int previous=n.getAlignmentMask();
                    network.setSwitch(n.name,a[2]);
                    requireConstructionAlignment(n.getAlignmentMask(),previous);
                    trackVisualsDirty=true; save();
                }
                catch(Exception failed) { network.restoreJunctions(before); trackVisualsDirty=true; throw failed; }
                tell(p,"Legacy exit converted to alignment: "+RailwayJunctionWorld.alignmentName(n.getAlignmentMask()));
            } else throw new IllegalArgumentException("Choose both connected sides, for example /rail align "+n.name+" N S.");
            return;
        }
        if(cmd.equals("cycle")) { require(a,2,"cycle NODE"); cycleJunction(p,node(a[1]).markerId); return; }
        if(cmd.equals("station")) {
            require(a,3,"station NODE SECONDS/off"); requireBuilder(p); Node n=node(a[1]);
            if(freightStops.get(n.name)!=null||waterUnloadStops.get(n.name)!=null)
                throw new IllegalArgumentException("Use Freight stop or Water freight stop to configure or remove this stop.");
            boolean enabled=!a[2].equalsIgnoreCase("off");
            if(!enabled)requireStopCanBeRemoved(n,-10L,true);
            network.setStation(n.name,enabled,enabled?number(a[2],1,3600):5);
            trackVisualsDirty=true; save(); tell(p,enabled?"Station platform enabled at "+n.name+".":"Station platform removed at "+n.name+"."); return;
        }
        if(cmd.equals("spawn")) {
            require(a,2,"spawn engine/cart/logcar"); if(p.getPower()<2) throw new IllegalArgumentException("GM power 2 required. Players can craft rail engines and wagons.");
            validateBuildTile(p,p.getTileX(),p.getTileY());
            Item i;
            if(a[1].equalsIgnoreCase("engine")) i=WurmRailWorld.spawnLocomotive(p,p.getPosX(),p.getPosY(),layer(p));
            else if(a[1].equalsIgnoreCase("cart")) i=WurmRailWorld.spawnCart(p,p.getPosX(),p.getPosY(),layer(p));
            else if(a[1].equalsIgnoreCase("logcar")) i=WurmRailWorld.spawnLogCar(p,p.getPosX(),p.getPosY(),layer(p));
            else throw new IllegalArgumentException("Use spawn engine, spawn cart or spawn logcar.");
            tell(p,"Created "+i.getName()+" ("+i.getWurmId()+")."); return;
        }
        if(cmd.equals("claim")) {
            require(a,3,"claim NAME TOWARDS [ENGINE_ID]"); checkName(a[1]);
            Item engine=a.length>3?Items.getItem(Long.parseLong(a[3])):findNearby(p,true);
            checkCar(p,engine,true); if(engine.getTemplateId()!=WurmRailWorld.locomotiveTemplateId) throw new IllegalArgumentException("Choose a rail engine.");
            if(findTrain(engine.getWurmId())!=null) throw new IllegalArgumentException("That engine already belongs to a train.");
            Node start=WurmRailWorld.railAtVehicle(network,engine);
            if(start==null) throw new IllegalArgumentException("Place the engine on a track tile first.");
            if(!start.links.contains(a[2])) throw new IllegalArgumentException("Engine "+engine.getWurmId()+" is on "+start.name+". Choose a directly connected facing node: "+(start.links.isEmpty()?"none; inspect and link the track first":String.join(", ",start.links))+". NAME and TOWARDS in the help are placeholders.");
            Train t=network.createTrain(a[1],p.getWurmId(),Collections.singletonList(engine.getWurmId()),start.name,a[2]);
            t.maxSpeed=speedLimit; applyPoses(t); RailwayTrainNames.synchronize(t,engine);
            save(); tell(p,"Registered train "+t.name+". Couple wagons, board, then start."); return;
        }
        Train t=train(p,a.length>1?a[1]:null);
        if(cmd.equals("status")) { status(p,t); return; }
        if(cmd.equals("board")) {
            int index=a.length>2?integer(a[2],1,t.carIds.size())-1:0;
            if(t.speed>0.01) throw new IllegalArgumentException("Stop the train before boarding.");
            Item car=Items.getItem(t.carIds.get(index)); checkNear(p,car); WurmRailWorld.board(p,car); return;
        }
        requireOperator(p,t);
        if(cmd.equals("panel")) { RailwayTrainUi.show(p,t.carIds.get(0),trainApi); return; }
        if(cmd.equals("start")) { startService(t);status(p,t);return; }
        else if(cmd.equals("stop")) {network.stop(t.name);scheduledServices.record(t,false);scheduledServices.save(servicesFile(),network);}
        else if(cmd.equals("reverse")) reverseForOperator(p,t);
        else if(cmd.equals("speed")) { require(a,3,"speed TRAIN METRES_PER_SECOND"); t.maxSpeed=number(a[2],0.25,speedLimit); }
        else if(cmd.equals("route")) {
            require(a,3,"route TRAIN NODE,NODE,...");stopForControl(t);
            network.setRoute(t.name,Arrays.asList(a[2].split(",")),t.routeDwellSeconds);
            scheduledServices.save(servicesFile(),network);
        }
        else if(cmd.equals("manual")) {
            stopForControl(t);network.setRoute(t.name,Collections.<String>emptyList(),t.routeDwellSeconds);
            scheduledServices.save(servicesFile(),network);
        }
        else if(cmd.equals("dwell")) { require(a,3,"dwell TRAIN SECONDS"); stopped(t); t.routeDwellSeconds=number(a[2],1,3600); }
        else if(cmd.equals("couple")) {
            stopped(t); emptyConsist(t); if(t.carIds.size()>=maxCars) throw new IllegalArgumentException("Maximum "+maxCars+" vehicles per train.");
            Item car=a.length>2?Items.getItem(Long.parseLong(a[2])):findNearby(p,false);
            checkCar(p,car,true); if(!WurmRailWorld.isWagon(car)) throw new IllegalArgumentException("Only rail wagons can be coupled.");
            if(findTrain(car.getWurmId())!=null) throw new IllegalArgumentException("That wagon already belongs to a train.");
            Item tail=Items.getItem(t.carIds.get(t.carIds.size()-1));
            if(distance(car.getPosX(),car.getPosY(),tail.getPosX(),tail.getPosY())>8||car.isOnSurface()!=tail.isOnSurface())
                throw new IllegalArgumentException("The wagon must be within 8 metres of the train's rear vehicle.");
            network.addCar(t.name,car.getWurmId()); applyPoses(t);
        }
        else if(cmd.equals("uncouple")) { stopped(t); emptyConsist(t); if(t.carIds.size()<2) throw new IllegalArgumentException("No trailing wagon.");
            Item car=Items.getItem(t.carIds.get(t.carIds.size()-1)); checkCar(p,car,true); network.removeCar(t.name,car.getWurmId()); }
        else if(cmd.equals("forget")) { stopped(t); network.removeTrain(t.name); tell(p,"Train registration removed. Its vehicles and cargo remain in the world."); save(); return; }
        else if(cmd.equals("load")||cmd.equals("unload")) {
            require(a,3,cmd+" TRAIN ITEM_ID [CAR_NUMBER]"); stopped(t);
            Item cargo=Items.getItem(Long.parseLong(a[2]));
            if(WurmRailWorld.isRailCar(cargo)) throw new IllegalArgumentException("Rail vehicles cannot be loaded as cargo.");
            Item car;
            if(cmd.equals("load")) {
                int index=a.length>3?integer(a[3],1,t.carIds.size())-1:t.carIds.size()-1;
                car=Items.getItem(t.carIds.get(index)); checkCar(p,car,false);
                if(cargo.getTopParent()!=p.getInventory().getWurmId()) throw new IllegalArgumentException("Put the cargo in your inventory first, or use Wurm's normal Load action for ground cargo.");
                if(!cargo.moveToItem(p,car.getWurmId(),false)) throw new IllegalArgumentException("Wurm refused the transfer. Check capacity, locks, and item restrictions.");
            } else {
                if(!t.carIds.contains(cargo.getTopParent())) throw new IllegalArgumentException("That item is not cargo in this train.");
                car=Items.getItem(cargo.getTopParent()); checkCar(p,car,false);
                if(!cargo.moveToItem(p,p.getInventory().getWurmId(),false)) throw new IllegalArgumentException("Wurm refused the transfer. Check weight and permissions.");
            }
            tell(p,"Cargo transferred."); return;
        } else throw new IllegalArgumentException("Unknown command. Use /rail help.");
        save(); status(p,t);
    }

    private void requireBuildContext(Creature p,int x,int y,int z) {
        requireBuilder(p);
        if((layer(p)!=z&&!(z==-1&&WurmRailWorld.isEntranceTile(x,y)))||!RailwayBridgeDeck.isAtRailLevel(p))
            throw new IllegalArgumentException("Build railway on the same layer at ground or bridge deck level.");
        if(distance(p.getPosX(),p.getPosY(),x*4.0+2,y*4.0+2)>16)
            throw new IllegalArgumentException("Stand within 16 metres of this track tile.");
        validateBuildTile(p,x,y,WurmRailWorld.resolveBuildLayer(x,y,z));
    }

    private RailwayBuilderUi.View builderSnapshot(Creature p,int x,int y,int z) {
        requireBuildContext(p,x,y,z);
        z=WurmRailWorld.resolveBuildLayer(x,y,z);
        Node n=nodeAt(x,y,z);
        List<RailwayBuilderUi.Neighbor> neighbors=new ArrayList<RailwayBuilderUi.Neighbor>();
        if(n!=null) for(int direction=0;direction<8;direction++) {
            Node adjacent=connectionNeighbor(n,direction);
            if(adjacent!=null) neighbors.add(new RailwayBuilderUi.Neighbor(direction,railLabel(adjacent)+(adjacent.layer==n.layer?"":adjacent.layer==0?" (surface exit)":" (tunnel entrance)"),
                    n.links.contains(adjacent.name),false));
        }
        boolean present=n!=null&&trackMarkerPresent(n);
        int facing=(int)Math.floor((((p.getStatus().getRotation()%360)+360)%360+22.5)/45)%8;
        boolean deck=RailwayBridgeDeck.idAt(x,y,z)>0;
        return new RailwayBuilderUi.View((deck?"Bridge deck":WurmRailWorld.isEntranceTile(x,y)?"Cave entrance":z==0?"Surface":"Cave")+" track tile "+x+", "+y,
                n==null?null:n.name,n==null?-10L:n.markerId,present,n!=null&&n.station,
                n!=null&&n.station?stationLabel(n):"",n==null?5:n.dwellSeconds,neighbors,facing,
                RailwayTrackMaterials.count(p),p.getPower()>=2,n==null?0:n.getAlignmentMask(),n==null?null:ownerLabel(n.getAlignmentOwner()));
    }

    private String stationLabel(Node n) {
        Item marker=Items.getItemOptional(n.markerId).orElse(null);
        if(marker!=null&&marker.getName().startsWith("Station: ")) return marker.getName().substring(9);
        return "Station at "+n.tileX+", "+n.tileY;
    }

    private String railLabel(Node n) {
        return n.station?stationLabel(n):"Track at "+n.tileX+", "+n.tileY;
    }

    private String automaticNodeName(int x,int y,int z) {
        String base="Rail_"+x+"_"+y+(z==0?"_S":"_C");
        String name=base;
        for(int sequence=2;network.nodes.containsKey(name);sequence++) name=base+"_"+sequence;
        checkName(name); return name;
    }

    private void buildLine(Creature p,String prefix,int x,int y,int z,int direction,int count,boolean indexed) throws Exception {
        requireBuildContext(p,x,y,z);
        z=WurmRailWorld.resolveBuildLayer(x,y,z);
        if(direction<0||direction>7||count<1||count>100) throw new IllegalArgumentException("Choose a compass direction and 1 to 100 track tiles.");
        if(network.nodes.size()+count>maxNodes) throw new IllegalArgumentException("The railway track limit has been reached.");
        int dx=DIRECTIONS[direction][0],dy=DIRECTIONS[direction][1];
        List<String> names=new ArrayList<String>();
        List<Integer> layers=new ArrayList<Integer>();
        for(int index=0;index<count;index++) {
            int tx=x+dx*index,ty=y+dy*index;
            int trackLayer=index==0?z:nextBuildLayer(tx-dx,ty-dy,layers.get(index-1),tx,ty);
            validateBuildTile(p,tx,ty,trackLayer);
            // The selected direction also defines a single piece's rail bed.
            // Reject an unsuitable paid line before creating any world items.
            if(indexed)requireConstructionTerrain(tx,ty,trackLayer,(1<<direction)|(1<<((direction+4)%8)));
            if(!network.nodesAt(tx,ty,trackLayer).isEmpty()
                    || WurmRailWorld.isEntranceTile(tx,ty)&&!network.nodesAt(tx,ty,0).isEmpty())
                throw new IllegalArgumentException("Track already exists at tile "+tx+", "+ty+".");
            if(passengerStations.at(tx,ty,trackLayer)!=null) throw new IllegalArgumentException("A station occupies tile "+tx+", "+ty+".");
            String name=prefix==null?automaticNodeName(tx,ty,trackLayer):prefix+(indexed?Integer.toString(index):"");
            checkName(name);
            if(network.nodes.containsKey(name)||names.contains(name)) throw new IllegalArgumentException("That track name is already used.");
            names.add(name);
            layers.add(trackLayer);
            if(index>0) {
                String error=WurmRailWorld.validateEdge(tx-dx,ty-dy,layers.get(index-1),tx,ty,trackLayer);
                if(error!=null) throw new IllegalArgumentException(error);
            }
        }
        RailwayTrackMaterials.Payment payment=RailwayTrackMaterials.reserve(p,count);
        List<String> added=new ArrayList<String>();
        JunctionCheckpoint switches=network.checkpointJunctions();
        try {
            for(int index=0;index<count;index++) {
                String name=names.get(index);
                lay(p,name,x+dx*index,y+dy*index,layers.get(index)); added.add(name);
                Items.getItem(node(name).markerId).setQualityLevel(payment.qualityAt(index));
                // The requested line is explicit, even beside existing bends.
                if(index>0) connectNewNodes(node(names.get(index-1)),node(name));
            }
            payment.consume(); save();
        } catch(Exception failure) {
            for(int index=added.size()-1;index>=0;index--) {
                try { removeTrack(added.get(index)); } catch(Exception rollback) { failure.addSuppressed(rollback); }
            }
            try { network.restoreJunctions(switches); } catch(Exception rollback) { failure.addSuppressed(rollback); }
            try { payment.refund(); } catch(Exception rollback) { failure.addSuppressed(rollback); }
            trackVisualsDirty=true;
            throw failure;
        }
    }

    private void repairTrack(Creature p,Node n) throws Exception {
        requireBuildContext(p,n.tileX,n.tileY,n.layer);
        if (n.trestle()!=null)
            throw new IllegalArgumentException("Rebuild the trestle as a complete span.");
        if(!RailwayBridgeDeck.matches(n.tileX,n.tileY,n.layer,n.bridgeId))
            throw new IllegalArgumentException("The original rail surface is missing or has changed. Remove the old track before rebuilding.");
        Item previous=Items.getItemOptional(n.markerId).orElse(null);
        if(previous!=null) throw new IllegalArgumentException("The track model is already present.");
        long oldId=n.markerId;
        FreightStops freightBefore=freightStops.copy();Stop freight=freightStops.get(n.name);
        WaterUnloadStops unloadBefore=waterUnloadStops.copy();WaterUnloadStops.Stop unload=waterUnloadStops.get(n.name);
        Item marker=WurmRailWorld.spawnTrackMarker(p,n.worldX(),n.worldY(),n.layer);
        try {
            n.markerId=marker.getWurmId();
            if(freight!=null) {
                freightStops.put(new Stop(freight.rail,n.markerId,freight.ownerId,freightStops.nextRevision(),freight.name,
                        freight.operation,freight.storageId,freight.templateId,freight.rateKgPerSecond));
                marker.setName(freightItemName(freight.name));freightStops.save(freightFile());
            } else if(unload!=null) {
                waterUnloadStops.put(new WaterUnloadStops.Stop(unload.rail,n.markerId,unload.ownerId,waterUnloadStops.nextRevision(),unload.name,unload.operation,unload.storageId,unload.rateLitresPerSecond));
                marker.setName(waterUnloadItemName(unload));waterUnloadStops.save(waterUnloadFile());
            } else marker.setName(n.station?"Station: "+stationLabel(n):"rail "+n.name);
            trackVisualsDirty=true; save();
        } catch(Exception failure) {
            freightStops=freightBefore;waterUnloadStops=unloadBefore;n.markerId=oldId;
            if(freight!=null)try{freightStops.save(freightFile());}catch(Exception rollback){failure.addSuppressed(rollback);}
            if(unload!=null)try{waterUnloadStops.save(waterUnloadFile());}catch(Exception rollback){failure.addSuppressed(rollback);}
            Items.destroyItem(marker.getWurmId());trackVisualsDirty=true;
            try{save();}catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }

    private void removeAndRecover(Creature p,Node n) throws Exception {
        requireBuildContext(p,n.tileX,n.tileY,n.layer);
        if(freightStops.get(n.name)!=null)throw new IllegalArgumentException("Remove this freight stop before removing its track.");
        if(waterUnloadStops.get(n.name)!=null)throw new IllegalArgumentException("Remove this Water freight stop before removing its track.");
        for(Platform platform:passengerStations.platforms.values()) if(platform.rail.equals(n.name))
            throw new IllegalArgumentException("Remove the stations linked to this track first.");
        final List<String> links=new ArrayList<String>(n.links);
        final JunctionCheckpoint switches=network.checkpointJunctions();
        Item candidateMarker=Items.getItemOptional(n.markerId).orElse(null);
        final Item marker=candidateMarker!=null&&candidateMarker.getTemplateId()==WurmRailWorld.trackTemplateId
                &&candidateMarker.getTileX()==n.tileX&&candidateMarker.getTileY()==n.tileY
                &&candidateMarker.isOnSurface()==(n.layer==0)?candidateMarker:null;
        final float quality=marker==null?50f:marker.getQualityLevel();
        final String markerName=marker==null?"rail "+n.name:marker.getName();
        // Core checks both occupied history and every configured station route before changing anything.
        network.removeNode(n.name);
        Item recovered=null;
        try {
            recovered=RailwayTrackMaterials.recover(p,quality);
            if(marker!=null) Items.destroyItem(marker.getWurmId());
            trackVisualsDirty=true; save();
        } catch(Exception failure) {
            if(recovered!=null&&Items.getItemOptional(recovered.getWurmId()).isPresent()) Items.destroyItem(recovered.getWurmId());
            try {
                Node restored=network.addNode(n.name,n.tileX,n.tileY,n.layer);
                restored.station=n.station; restored.dwellSeconds=n.dwellSeconds; restored.bridgeId=n.bridgeId;
                Item restoredMarker=marker!=null&&Items.getItemOptional(marker.getWurmId()).isPresent()?marker:
                        WurmRailWorld.spawnTrackMarker(p,n.worldX(),n.worldY(),n.layer);
                restored.markerId=restoredMarker.getWurmId();
                restoredMarker.setName(markerName); restoredMarker.setQualityLevel(quality);
                for(String adjacent:links) connectNodes(restored,node(adjacent));
                network.restoreJunctions(switches);
                trackVisualsDirty=true;
            } catch(Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private void applyConnections(Creature p,Node n,int mask) throws Exception {
        if(mask<0||mask>255) throw new IllegalArgumentException("Choose the neighboring tracks to connect.");
        requireBuildContext(p,n.tileX,n.tileY,n.layer);
        requireConstructionShape(n,mask);
        List<String> desired=new ArrayList<String>();
        for(int direction=0;direction<8;direction++) if((mask&(1<<direction))!=0) {
            Node adjacent=connectionNeighbor(n,direction);
            if(adjacent==null) throw new IllegalArgumentException("A neighboring track is missing. Refresh this window.");
            validateBuildTile(p,adjacent.tileX,adjacent.tileY,adjacent.layer);
            String error=WurmRailWorld.validateEdge(n.tileX,n.tileY,n.layer,adjacent.tileX,adjacent.tileY,adjacent.layer);
            if(error!=null) throw new IllegalArgumentException(error);
            desired.add(adjacent.name);
        }
        List<String> previous=new ArrayList<String>(n.links);
        // Validate the final shape at every affected end, before disconnecting
        // anything. Intermediate two-exit shapes in a junction edit are not
        // committed geometry and may still contain a legacy tight pair.
        Set<String> affected=new LinkedHashSet<String>(previous); affected.addAll(desired);
        for(String adjacent:affected) {
            Node neighbor=node(adjacent); int next=trackMask(neighbor);
            if(desired.contains(adjacent))next|=connectionBit(neighbor,n);
            else next&=~connectionBit(neighbor,n);
            requireConstructionShape(neighbor,next);
        }
        JunctionCheckpoint switches=network.checkpointJunctions();
        for(String adjacent:previous) {
            Node neighbor=node(adjacent); validateBuildTile(p,neighbor.tileX,neighbor.tileY,neighbor.layer);
        }
        try {
            for(String adjacent:previous) if(!desired.contains(adjacent)) network.disconnect(n.name,adjacent);
            for(String adjacent:desired) if(!n.links.contains(adjacent)) connectNodes(n,node(adjacent));
            trackVisualsDirty=true; save();
        } catch(Exception failure) {
            try {
                for(String adjacent:new ArrayList<String>(n.links)) if(!previous.contains(adjacent)) network.disconnect(n.name,adjacent);
                for(String adjacent:previous) if(!n.links.contains(adjacent)) connectNodes(n,node(adjacent));
                network.restoreJunctions(switches);
                trackVisualsDirty=true;
            } catch(Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private void applyBuilder(Creature p,int x,int y,int z,RailwayBuilderUi.Request request) throws Exception {
        requireBuildContext(p,x,y,z);
        z=WurmRailWorld.resolveBuildLayer(x,y,z);
        Node n=nodeAt(x,y,z);
        if(request.action!=RailwayBuilderUi.Action.REFRESH && (n==null?request.nodeName!=null:
                !n.name.equals(request.nodeName)||n.markerId!=request.markerId))
            throw new IllegalArgumentException("This track changed. Open the railway builder again.");
        switch(request.action) {
            case REFRESH: break;
            case PLACE:
                if(n!=null) throw new IllegalArgumentException("Track already exists here.");
                buildLine(p,null,x,y,z,request.direction,request.count,true); break;
            case CONNECTIONS: applyConnections(p,n,request.connectionMask); break;
            case ALIGNMENT: setJunction(p,n,request.alignmentMask); break;
            case STATION:
                if(freightStops.get(n.name)!=null||waterUnloadStops.get(n.name)!=null)
                    throw new IllegalArgumentException("Use Freight stop or Water freight stop to configure or remove this stop.");
                String stationName=request.stationName==null?"":request.stationName.trim();
                if(request.stationEnabled&&(stationName.isEmpty()||stationName.length()>48))
                    throw new IllegalArgumentException("Enter a station name from 1 to 48 characters.");
                for(int index=0;index<stationName.length();index++) if(Character.isISOControl(stationName.charAt(index)))
                    throw new IllegalArgumentException("Station names must be on one line.");
                if(request.stationEnabled&&(!Double.isFinite(request.dwellSeconds)||request.dwellSeconds<1||request.dwellSeconds>3600))
                    throw new IllegalArgumentException("Choose a station stop time from 1 to 3600 seconds.");
                if(!request.stationEnabled) for(Train train:network.trains.values()) if(train.route.contains(n.name))
                    throw new IllegalArgumentException("Remove this station from train routes before removing the station.");
                Item stationMarker=Items.getItemOptional(n.markerId).orElse(null);
                if(stationMarker==null||stationMarker.getTemplateId()!=WurmRailWorld.trackTemplateId)
                    throw new IllegalArgumentException("Repair this track model first.");
                boolean previousStation=n.station; double previousDwell=n.dwellSeconds; String previousName=stationMarker.getName();
                try {
                    if(!request.stationEnabled)requireStopCanBeRemoved(n,-10L,true);
                    network.setStation(n.name,request.stationEnabled,request.stationEnabled?request.dwellSeconds:5);
                    stationMarker.setName(request.stationEnabled?"Station: "+stationName:"rail "+n.name);
                    trackVisualsDirty=true; save();
                } catch(Exception failure) {
                    n.station=previousStation; n.dwellSeconds=previousDwell; stationMarker.setName(previousName);
                    trackVisualsDirty=true; throw failure;
                }
                break;
            case REPAIR: repairTrack(p,n); break;
            case REMOVE: removeAndRecover(p,n); break;
            case SPAWN_ENGINE: case SPAWN_CART: case SPAWN_LOGCAR:
                if(p.getPower()<2) throw new IllegalArgumentException("Only a GM can create vehicles this way. Players can craft them.");
                if(n==null) throw new IllegalArgumentException("Build track here first.");
                if(request.action==RailwayBuilderUi.Action.SPAWN_ENGINE&&n.links.isEmpty())
                    throw new IllegalArgumentException("Connect this track to another tile before placing a locomotive.");
                VolaTile tile=Zones.getTileOrNull(x,y,z==0);
                if(tile!=null) for(Item other:tile.getItems()) if(WurmRailWorld.isRailCar(other))
                    throw new IllegalArgumentException("A railway vehicle already occupies this tile.");
                Item car=request.action==RailwayBuilderUi.Action.SPAWN_ENGINE?
                        WurmRailWorld.spawnLocomotive(p,n.worldX(),n.worldY(),z):request.action==RailwayBuilderUi.Action.SPAWN_LOGCAR?
                        WurmRailWorld.spawnLogCar(p,n.worldX(),n.worldY(),z):WurmRailWorld.spawnCart(p,n.worldX(),n.worldY(),z);
                try {
                    WurmRailWorld.moveCar(car.getWurmId(),car.getPosX(),car.getPosY(),p.getStatus().getRotation(),z);
                    if(WurmRailWorld.isLocomotive(car)) trainService.ensureTrain(p,car);
                    save();
                } catch(Exception failure) {
                    Train created=findTrain(car.getWurmId());
                    if(created!=null) network.removeTrain(created.name);
                    Items.destroyItem(car.getWurmId()); throw failure;
                }
                RailwayTrainUi.show(p,car.getWurmId(),trainApi); return;
            default: throw new IllegalArgumentException("Choose a railway building action.");
        }
        RailwayBuilderUi.show(p,x,y,z,builderApi);
    }

    private void lay(Creature p,String name,int x,int y) throws Exception {
        lay(p,name,x,y,WurmRailWorld.resolveBuildLayer(x,y,layer(p)));
    }

    private void buildTrestle(Creature actor, RailwayTrestleSurvey.Plan plan) throws Exception {
        if (actor.getPower() < 2) throw new IllegalArgumentException("Instant construction requires GM power 2.");
        TrestleAlignment alignment = plan.alignment;
        List<TrestleAlignment.TileSection> sections = alignment.curved ? alignment.tileSections() : Collections.emptyList();
        int deltaX = plan.end.x - plan.start.x, deltaY = plan.end.y - plan.start.y;
        int steps = alignment.curved ? sections.size() + 1 : Math.max(Math.abs(deltaX), Math.abs(deltaY));
        if (steps < 2 || !alignment.curved && deltaX != 0 && deltaY != 0 && Math.abs(deltaX) != Math.abs(deltaY))
            throw new IllegalArgumentException("Choose a straight compass alignment with at least one tile between approaches.");
        int dx = Integer.signum(deltaX), dy = Integer.signum(deltaY);
        int direction = RailNetwork.direction(dx,dy), axis = direction & 3;
        int grade = (int)Math.round(plan.alignment.gradient * (direction < 4 ? 1 : -1) * 1000000);
        if (network.nodes.size() + steps - 1 + (plan.start.rail == null ? 1 : 0)
                + (plan.end.rail == null ? 1 : 0) > maxNodes)
            throw new IllegalArgumentException("Track node limit reached.");
        RailwayTrestleSurvey.validateApproaches(plan.start,plan.end,alignment);
        RailwayTrestleCrossings.validate(network,plan);
        List<int[]> tiles = new ArrayList<>();
        tiles.add(new int[]{plan.start.x, plan.start.y});
        for (int i = 1; i < steps; i++) {
            TrestleAlignment.TileSection section = alignment.curved ? sections.get(i - 1) : null;
            tiles.add(section == null ? new int[]{plan.start.x + i * dx, plan.start.y + i * dy}
                    : new int[]{section.x, section.y});
        }
        tiles.add(new int[]{plan.end.x, plan.end.y});
        for (int index = 0; index <= steps; index++) {
            int x = tiles.get(index)[0], y = tiles.get(index)[1];
            VolaTile tile=Zones.getTileOrNull(x,y,true);
            boolean approach = index == 0 || index == steps;
            double deckHeight = approach ? (index == 0 ? plan.start.point.z : plan.end.point.z)
                    : alignment.elevationAt(alignment.curved ? sections.get(index-1).center()
                            : alignment.length*(index-.5)/(steps-1));
            if(tile!=null) for(Item item:tile.getItems()) if(WurmRailWorld.isRailCar(item)
                    && RailwayTrestleCrossings.vehicleLevelsOverlap((float)deckHeight,item.getPosZ()))
                throw new IllegalArgumentException("Move parked rail vehicles clear of the surveyed crossing first.");
            if(!alignment.curved && index>0 && dx!=0 && dy!=0) {
                for (int[] side : new int[][]{{x-dx,y},{x,y-dy}}) {
                    VolaTile adjacent=Zones.getTileOrNull(side[0],side[1],true);
                    if(RailwayBridgeDeck.partAt(side[0],side[1],0)!=null
                            || adjacent!=null && (adjacent.getStructure()!=null || adjacent.getFences().length!=0))
                        throw new IllegalArgumentException("The diagonal deck overlaps existing infrastructure at "+side[0]+", "+side[1]+".");
                }
            }
        }
        double stride = alignment.length / (steps - 1);
        String prefix = "trestle_" + UUID.randomUUID().toString().replace("-","").substring(0,16);
        List<String> rails = new ArrayList<>(), added = new ArrayList<>();
        List<Item> pieces = new ArrayList<>();
        Map<String,List<Long>> timber = new HashMap<>();
        JunctionCheckpoint switches = network.checkpointJunctions();
        boolean profiled = false;
        try {
            for (int i = 0; i <= steps; i++) {
                int x = tiles.get(i)[0], y = tiles.get(i)[1];
                boolean endpoint = i == 0 || i == steps;
                Node node = endpoint ? nodeAt(x,y,0) : null;
                if (node == null) {
                    node = endpoint ? network.addNode(prefix+"_"+i,x,y,0)
                            : network.addTrestleDeckNode(prefix+"_"+i,x,y);
                    added.add(node.name);
                    Item item;
                    if (endpoint) {
                        item = WurmRailWorld.spawnTrackMarker(actor,node.worldX(),node.worldY(),0);
                    } else {
                        TrestleAlignment.TileSection section = alignment.curved ? sections.get(i - 1) : null;
                        double distance = section == null ? stride * (i - .5) : section.center();
                        item = WurmRailWorld.placeTrestlePiece(WurmRailWorld.trestleDeckTemplateId,actor,
                                node.worldX(),node.worldY(),(float)alignment.at(distance).z,0,axis,grade,
                                section == null ? null : alignment.panel(section));
                    }
                    pieces.add(item);
                    node.markerId = item.getWurmId();
                }
                if (i > 0) network.connect(rails.get(i-1),node.name);
                rails.add(node.name);
            }
            int curvedOwner = 0;
            for (double distance : alignment.supportDistances(plan.spacing)) {
                TrestleAlignment.Point point = alignment.at(distance);
                float top = (float)point.z-.11f, x = (float)point.x, y = (float)point.y;
                float floor = Zones.calculateHeight(x,y,true);
                int height = (int)Math.ceil(top-floor);
                int ownerIndex = Math.min(steps-1,1+(int)(distance/stride));
                if (alignment.curved) {
                    while (curvedOwner + 1 < sections.size() && distance >= sections.get(curvedOwner).exit)
                        curvedOwner++;
                    ownerIndex = curvedOwner + 1;
                }
                String owner = rails.get(ownerIndex);
                float rotation = alignment.curved ? alignment.headingAt(distance) : axis * 45;
                while (height > 0) {
                    int size = Math.min(32,Integer.highestOneBit(height));
                    Item bent = WurmRailWorld.placeTrestlePiece(WurmRailWorld.trestleBentTemplateId,actor,
                            x,y,top,rotation,size,0);
                    pieces.add(bent);
                    timber.computeIfAbsent(owner,key->new ArrayList<>()).add(bent.getWurmId());
                    top -= size;
                    height -= size;
                }
            }
            if (alignment.curved) network.setCurvedTrestleElevation(rails,alignment);
            else network.setTrestleElevation(rails,alignment);
            profiled = true;
            for (String name : added) network.assignTrestleNode(name,prefix);
            for (Map.Entry<String,List<Long>> entry : timber.entrySet())
                network.setTrestleTimber(entry.getKey(),entry.getValue());
            network.save(stateFile);
        } catch(Exception failure) {
            try {
                if (profiled) network.clearTrestleElevation(rails);
                for (int i = added.size()-1; i >= 0; i--) network.removeNode(added.get(i));
                network.restoreJunctions(switches);
                for (Item piece : pieces) {
                    RailModelRegistry.forgetCurvedDeck(piece.getWurmId());
                    Items.destroyItem(piece.getWurmId());
                }
            } catch(Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        trackVisualsDirty = true;
        refreshTrackVisuals();
        tell(actor,"Trestle "+prefix+" | "+steps+" linked sections.");
    }

    private boolean isTrestleDeck(Node rail) {
        return network.isTrestleDeck(rail.name);
    }

    private String trestleAt(int x,int y,long marker) {
        RailNetwork current = network;
        if (current == null) return null;
        synchronized (current) {
            String span = null;
            for (Node rail : current.nodesAt(x,y,0)) {
                if (marker != -10L && marker != rail.markerId) continue;
                Set<String> spans = new HashSet<>();
                if (rail.trestle() != null) spans.add(rail.trestle());
                for (String link : rail.links) {
                    if (!current.isElevatedEdge(rail.name,link)) continue;
                    String attached = current.nodes.get(link).trestle();
                    if (attached != null) spans.add(attached);
                }
                if (spans.size() > 1) return null;
                if (spans.isEmpty()) continue;
                String attached = spans.iterator().next();
                if (span != null && !span.equals(attached)) return null;
                span = attached;
            }
            return span;
        }
    }

    private void removeStraightTrestle(Creature actor, String name) throws Exception {
        if (actor.getPower() < 2) throw new IllegalArgumentException("Removing a trestle requires GM power 2.");
        List<Node> span = network.checkTrestleRemoval(name);
        Set<String> missingApproaches = new LinkedHashSet<>();
        for (Node n : span) {
            VolaTile tile=Zones.getTileOrNull(n.tileX,n.tileY,true);
            Item marker = Items.getItemOptional(n.markerId).orElse(null);
            if(tile!=null) for(Item item:tile.getItems()) if(WurmRailWorld.isRailCar(item)
                    && (marker == null || RailwayTrestleCrossings.vehicleLevelsOverlap(marker.getPosZ(),item.getPosZ())))
                throw new IllegalArgumentException("Move parked rail vehicles clear of the trestle first.");
            if(freightStops.get(n.name)!=null || waterUnloadStops.get(n.name)!=null)
                throw new IllegalArgumentException("Remove the freight stop before removing this trestle.");
            for(Platform platform:passengerStations.platforms.values()) if(n.name.equals(platform.rail))
                throw new IllegalArgumentException("Remove the platform before removing this trestle.");
            for (String link : n.links) {
                Node approach = network.nodes.get(link);
                if (approach.trestle() == null && network.isElevatedEdge(n.name,link)
                        && !approach.station && freightStops.get(link) == null && waterUnloadStops.get(link) == null
                        && passengerStations.platforms.values().stream().noneMatch(platform -> link.equals(platform.rail))
                        && !Items.getItemOptional(approach.markerId).isPresent())
                    missingApproaches.add(link);
            }
        }
        // Preserve the live graph and its callbacks if storage rejects the edit.
        network.save(stateFile);
        RailNetwork replacement = RailNetwork.load(stateFile);
        replacement.removeTrestle(name);
        List<String> removedApproaches = new ArrayList<>();
        for (String missing : missingApproaches) {
            // Leave a missing approach in place if another line or service still uses it.
            if (!replacement.nodes.get(missing).links.isEmpty()) continue;
            try {
                replacement.removeNode(missing);
                removedApproaches.add(missing);
            } catch (IllegalArgumentException inUse) {
                // The normal track guards keep train and signal references intact.
            }
        }
        replacement.save(stateFile);
        List<Node> removed = network.removeTrestle(name);
        for (String missing : removedApproaches) network.removeNode(missing);
        for (Node n : removed) {
            RailModelRegistry.forgetCurvedDeck(n.markerId);
            Items.getItemOptional(n.markerId).ifPresent(item -> Items.destroyItem(item.getWurmId()));
            for (long timber : n.timber()) Items.getItemOptional(timber).ifPresent(item -> Items.destroyItem(item.getWurmId()));
        }
        trackVisualsDirty=true;
        refreshTrackVisuals();
    }
    private void lay(Creature p,String name,int x,int y,int trackLayer) throws Exception {
        checkName(name); if(network.nodes.size()>=maxNodes) throw new IllegalArgumentException("Track node limit reached.");
        validateBuildTile(p,x,y,trackLayer);
        if(!network.nodesAt(x,y,trackLayer).isEmpty()
                || WurmRailWorld.isEntranceTile(x,y) && !network.nodesAt(x,y,trackLayer==0?-1:0).isEmpty())
            throw new IllegalArgumentException("Track already exists on this tile.");
        Node n=network.addNode(name,x,y,trackLayer);
        trackVisualsDirty=true;
        try {
            Item marker=WurmRailWorld.spawnTrackMarker(p,x*4+2,y*4+2,n.layer); n.markerId=marker.getWurmId();
            n.bridgeId=marker.getBridgeId();
            marker.setName("rail "+name);
            List<Node> neighbors=new ArrayList<Node>();
            for(Node other:network.nodes.values()) {
                if(other!=n&&Math.max(Math.abs(other.tileX-x),Math.abs(other.tileY-y))==1
                        && !isTrestleDeck(other)) neighbors.add(other);
            }
            // Join cardinal neighbors first. Do not create a diagonal shortcut
            // across a two-edge bend that is already connected through a corner.
            neighbors.sort(Comparator.comparingInt(o->Math.abs(o.tileX-x)+Math.abs(o.tileY-y)));
            for(Node other:neighbors) {
                boolean diagonal=other.tileX!=x&&other.tileY!=y;
                if(diagonal&&hasOrthogonalPath(n,other)) continue;
                boolean used=connectionSideUsed(n,other)||connectionSideUsed(other,n);
                if(!used&&constructionConnectionAllowed(n,other)
                        &&WurmRailWorld.validateEdge(x,y,n.layer,other.tileX,other.tileY,other.layer)==null)connectNodes(n,other);
            }
        } catch(Exception e) { removeTrack(name); throw e; }
    }
    private void validateBuildTile(Creature p,int x,int y) {
        validateBuildTile(p,x,y,WurmRailWorld.resolveBuildLayer(x,y,layer(p)));
    }
    private void validateBuildTile(Creature p,int x,int y,int trackLayer) {
        if(!RailwayBridgeDeck.isAtRailLevel(p)) throw new IllegalArgumentException("Build track at ground or bridge deck level.");
        String error=WurmRailWorld.validateTile(x,y,trackLayer);
        if(error!=null) throw new IllegalArgumentException("Cannot build "+(trackLayer==0?"surface":"cave")+" track at "+x+", "+y+": "+error);
        if(p.getPower()<2&&!Methods.isActionAllowed(p,Actions.BUILD,trackLayer==0,x,y,0,0))
            throw new IllegalArgumentException("You do not have construction permission on this tile.");
        BridgePart deck=RailwayBridgeDeck.partAt(x,y,trackLayer);
        if(deck!=null&&p.getPower()<2) {
            VolaTile tile=Zones.getTileOrNull(x,y,trackLayer==0);
            if(!MethodsStructure.mayModifyStructure(p,tile.getStructure(),tile,Actions.BUILD))
                throw new IllegalArgumentException("You do not have construction permission on this bridge.");
        }
    }
    private int nextBuildLayer(int ax,int ay,int fromLayer,int bx,int by) {
        int preferred=WurmRailWorld.resolveBuildLayer(bx,by,fromLayer);
        String preferredError=WurmRailWorld.validateEdge(ax,ay,fromLayer,bx,by,preferred);
        if(preferredError==null)return preferred;
        int alternate=preferred==0?-1:0;
        String alternateError=WurmRailWorld.validateEdge(ax,ay,fromLayer,bx,by,alternate);
        if(alternateError==null)return alternate;
        String reason=preferred==fromLayer&&(WurmRailWorld.isEntranceTile(ax,ay)||WurmRailWorld.isEntranceTile(bx,by))?alternateError:preferredError;
        throw new IllegalArgumentException("Cannot connect track between "+ax+", "+ay+" and "+bx+", "+by+": "+reason);
    }
    private void connectNodes(Node first,Node second) {
        if(first.layer==second.layer)network.connect(first.name,second.name);
        else network.connectEntrance(first.name,second.name);
    }
    private int connectionBit(Node origin,Node next) {
        return 1<<RailNetwork.direction(next.tileX-origin.tileX,next.tileY-origin.tileY);
    }
    private boolean constructionConnectionAllowed(Node first,Node second) {
        int a=trackMask(first)|connectionBit(first,second),b=trackMask(second)|connectionBit(second,first);
        try { requireConstructionShape(first,a);requireConstructionShape(second,b);return true; }
        catch(IllegalArgumentException unsuitable) { return false; }
    }
    private void requireConstructionShape(Node rail,int mask) {
        if (mask != trackMask(rail) && isTrestleDeck(rail))
            throw new IllegalArgumentException("Change a trestle deck as a complete span.");
        if(freightStops.get(rail.name)!=null&&Integer.bitCount(mask)>2)
            throw new IllegalArgumentException("Remove the freight stop before turning its rail into a junction.");
        if(waterUnloadStops.get(rail.name)!=null&&Integer.bitCount(mask)>2)
            throw new IllegalArgumentException("Remove the Water freight stop before turning its rail into a junction.");
        if(Integer.bitCount(mask)==2)requireConstructionAlignment(mask,trackMask(rail));
        if(mask!=trackMask(rail))requireConstructionTerrain(rail.tileX,rail.tileY,rail.layer,mask);
    }
    private void requireConstructionTerrain(int x,int y,int layer,int mask) {
        String error=WurmRailWorld.validateTrackShape(x,y,layer,mask);
        if(error!=null)throw new IllegalArgumentException("Cannot build "+(layer==0?"surface":"cave")
                +" track at "+x+", "+y+": "+error);
    }
    private static void requireConstructionAlignment(int mask,int previous) {
        if(mask!=0&&mask!=previous&&!RailNetwork.isBuildableAlignment(mask))
            throw new IllegalArgumentException("Use straight track or a 45 or 90 degree bend. 135 degree corners are not available.");
    }
    private void connectNewNodes(Node first,Node second) {
        requireConstructionShape(first,trackMask(first)|connectionBit(first,second));
        requireConstructionShape(second,trackMask(second)|connectionBit(second,first));
        connectNodes(first,second);
    }
    private boolean connectionSideUsed(Node origin,Node next) {
        int side=RailNetwork.direction(next.tileX-origin.tileX,next.tileY-origin.tileY);
        for(String name:origin.links) { Node linked=node(name);if(RailNetwork.direction(linked.tileX-origin.tileX,linked.tileY-origin.tileY)==side)return true; }
        return false;
    }
    private Node connectionNeighbor(Node rail,int direction) {
        int x=rail.tileX+DIRECTIONS[direction][0],y=rail.tileY+DIRECTIONS[direction][1];
        for(String name:rail.links) { Node linked=node(name);if(linked.tileX==x&&linked.tileY==y)return linked; }
        for(int candidateLayer:new int[]{rail.layer,rail.layer==0?-1:0}) {
            Node candidate=nodeAt(x,y,candidateLayer);
            if(candidate!=null&&!isTrestleDeck(candidate)
                    && WurmRailWorld.validateEdge(rail.tileX,rail.tileY,rail.layer,x,y,candidateLayer)==null)return candidate;
        }
        return null;
    }
    private void removeTrack(String name) {
        Node n=node(name); long marker=n.markerId; network.removeNode(name);
        trackVisualsDirty=true;
        if(marker!=-10L) Items.getItemOptional(marker).ifPresent(i->{ if(i.getTemplateId()==WurmRailWorld.trackTemplateId) Items.destroyItem(marker); });
    }
    private boolean hasOrthogonalPath(Node a,Node b) {
        Node side=nodeAt(a.tileX,b.tileY,a.layer);
        if(side!=null&&a.links.contains(side.name)&&side.links.contains(b.name)) return true;
        side=nodeAt(b.tileX,a.tileY,a.layer);
        return side!=null&&a.links.contains(side.name)&&side.links.contains(b.name);
    }
    private int alignmentSide(Node junction,String value) {
        if(junction.links.contains(value)) {
            Node adjacent=node(value); return RailNetwork.direction(adjacent.tileX-junction.tileX,adjacent.tileY-junction.tileY);
        }
        String side=value.toUpperCase(Locale.ROOT).replace("-","");
        int direction=Arrays.asList(COMPASS).indexOf(side);
        if(direction<0)direction=Arrays.asList("NORTH","NORTHEAST","EAST","SOUTHEAST","SOUTH","SOUTHWEST","WEST","NORTHWEST").indexOf(side);
        if(direction<0||(trackMask(junction)&(1<<direction))==0)
            throw new IllegalArgumentException("Choose a compass side actually connected to this junction.");
        return direction;
    }
    private void setJunction(Creature p,Node junction,int mask) throws Exception {
        requireBuildContext(p,junction.tileX,junction.tileY,junction.layer);
        if(junction.links.size()<3)throw new IllegalArgumentException("This track has no switchable junction; its connected pair is fixed.");
        requireConstructionAlignment(mask,junction.getAlignmentMask());
        JunctionCheckpoint before=network.checkpointJunctions();
        try { network.setJunctionAlignment(junction.name,mask); trackVisualsDirty=true; save(); }
        catch(Exception failure) { network.restoreJunctions(before); trackVisualsDirty=true; throw failure; }
        tell(p,"Junction alignment: "+RailwayJunctionWorld.alignmentName(junction.getAlignmentMask()));
    }
    private void cycleJunction(Creature p,long itemId) throws Exception {
        Item clicked=Items.getItem(itemId);
        if(clicked.getParentId()!=-10L||(!WurmRailWorld.isJunctionIndicator(clicked)&&clicked.getTemplateId()!=WurmRailWorld.trackTemplateId))
            throw new IllegalArgumentException("Choose a ground railway junction or its alignment marker.");
        Node junction=nodeAt(clicked.getTileX(),clicked.getTileY(),clicked.isOnSurface()?0:-1);
        if(junction==null||junction.links.size()<3||(WurmRailWorld.isJunctionIndicator(clicked)?clicked.getData()!=junction.markerId:clicked.getWurmId()!=junction.markerId))
            throw new IllegalArgumentException("This junction changed. Select its current track or alignment marker.");
        requireBuildContext(p,junction.tileX,junction.tileY,junction.layer);
        JunctionCheckpoint before=network.checkpointJunctions();
        try {
            List<Integer> choices=new ArrayList<Integer>();
            for(int mask:network.junctionAlignments(junction.name))if(RailNetwork.isBuildableAlignment(mask))choices.add(mask);
            if(choices.isEmpty())throw new IllegalArgumentException("This junction needs a straight, 45 or 90 degree alignment.");
            int at=choices.indexOf(junction.getAlignmentMask());
            network.setJunctionAlignment(junction.name,choices.get((at+1)%choices.size()));
            trackVisualsDirty=true; save();
        }
        catch(Exception failure) { network.restoreJunctions(before); trackVisualsDirty=true; throw failure; }
        tell(p,"Junction alignment: "+RailwayJunctionWorld.alignmentName(junction.getAlignmentMask()));
    }
    private int trackMask(Node n) {
        int mask=0;
        for(String exit:n.links) {
            Node other=node(exit); int dx=Integer.signum(other.tileX-n.tileX),dy=Integer.signum(other.tileY-n.tileY);
            for(int i=0;i<DIRECTIONS.length;i++) if(DIRECTIONS[i][0]==dx&&DIRECTIONS[i][1]==dy) mask|=1<<i;
        }
        return mask;
    }
    private void refreshTrackVisuals() {
        Set<Long> surfaceRails=new HashSet<Long>();
        for(Node n:network.nodes.values())if(n.layer==0&&n.bridgeId==RailwayBridgeDeck.NONE
                && !(n.trestle()!=null
                    && Items.getItemOptional(n.markerId).map(i->i.getTemplateId()==WurmRailWorld.trestleDeckTemplateId).orElse(true)))
            surfaceRails.add(RailVegetationHooks.tileKey(n.tileX,n.tileY));
        RailVegetationHooks.replaceSurfaceRails(surfaceRails);
        for(Node n:network.nodes.values()) {
            // Repair old saves where vegetation grew through an existing rail.
            // New construction still requires a clear tile before it is paid for.
            WurmRailWorld.clearRailVegetation(n.tileX,n.tileY,n.layer);
            Item track=Items.getItemOptional(n.markerId).orElse(null);
            if(track!=null&&track.getTemplateId()==WurmRailWorld.trackTemplateId)
                WurmRailWorld.updateTrackVisual(track,trackMask(n),n.station?1:n.links.size()>=3?2:0);
            else if(track!=null&&track.getTemplateId()==WurmRailWorld.trestleDeckTemplateId&&n.trestle()!=null)
                restoreTrestleGrade(n,track);
        }
        trackVisualsDirty=false;
        refreshJunctionIndicators(true);
        refreshSignalVisuals(true);
    }

    private void restoreTrestleGrade(Node deck, Item marker) {
        if (RailModelRegistry.curvedDeck(marker.getWurmId()) != null) return;
        TrestlePanel panel = network.surveyedPanel(deck.name);
        if (panel != null) {
            if (RailModelRegistry.registerCurvedDeck(marker.getWurmId(), panel)) marker.updateIfGroundItem();
            return;
        }
        // Native ground-item reload can leave Data1 at -1 even with a grade
        // saved in the item database. The network owns the actual deck profile.
        for (String link : deck.links) {
            double slope = network.elevatedGradient(deck.name,link);
            if (!Double.isFinite(slope)) continue;
            Node neighbor = network.nodes.get(link);
            int direction = RailNetwork.direction(neighbor.tileX-deck.tileX,neighbor.tileY-deck.tileY);
            byte axis = (byte)(direction & 3);
            int grade = (int)Math.round(slope * (direction < 4 ? 1 : -1) * 1000000);
            if (marker.getAuxData() != axis || marker.getData1() != grade) {
                marker.setAuxData(axis);
                marker.setData1(grade);
                marker.updateIfGroundItem();
            }
            return;
        }
    }
    private void refreshJunctionIndicators(boolean force) {
        long now=System.currentTimeMillis(),revision=network.getJunctionRevision();
        if(force||revision!=junctionVisualRevision||now-lastJunctionRepair>=5000) {
            RailwayJunctionWorld.sync(network); junctionVisualRevision=revision; lastJunctionRepair=now;
        }
    }
    private void refreshSignalVisuals(boolean force) {
        long now=System.currentTimeMillis(),revision=network.signals().revision();
        if(!force&&revision==signalVisualRevision&&now-lastSignalRepair<5000)return;
        List<RailwaySignalWorld.DisplayState> displays=new ArrayList<RailwaySignalWorld.DisplayState>();
        for(SignalSystem.Signal signal:network.signals().definitions()) {
            Node rail=network.nodes.get(signal.rail); if(rail==null)continue;
            SignalSystem.Status state=network.signals().status(signal.id);
            displays.add(new RailwaySignalWorld.DisplayState(signal.id,rail.markerId,rail.tileX,rail.tileY,rail.layer,
                    signal.approachDirection,state.aspect==SignalSystem.Aspect.GREEN,ownerLabel(state.ownerTrain),state.reason));
        }
        RailwaySignalWorld.sync(displays); signalVisualRevision=revision; lastSignalRepair=now;
    }
    private boolean validateMotion(List<Pose> poses) {
        try {
            Set<Long> consist=new HashSet<Long>();
            for(Pose pose:poses) consist.add(pose.carId);
            for(Pose pose:poses) {
                Item car=Items.getItem(pose.carId);
                if(!WurmRailWorld.isRailCar(car)||car.getParentId()!=-10L||car.isBanked()||car.isMailed()||car.isTraded())
                    throw new IllegalArgumentException("Missing, stored, or traded rail vehicle.");
                int x=(int)(pose.x/4),y=(int)(pose.y/4);
                if(!Float.isFinite(pose.elevation)) {
                    String error=WurmRailWorld.validateTile(x,y,pose.layer);
                    if(error!=null) throw new IllegalArgumentException(error);
                }
                // At diagonal tile corners floor(x/4),floor(y/4) may name
                // an orthogonal side tile. The curve identifies its owning rail.
                Node track=pose.railName==null?nodeAt(x,y,pose.layer):network.nodes.get(pose.railName);
                if(track==null||!trackMarkerPresent(track))
                    throw new IllegalArgumentException("Track marker is missing; repair the track before continuing.");
                Pose applied=appliedPoses.get(car.getWurmId());
                Node source=applied==null?WurmRailWorld.railAtVehicle(network,car)
                        :network.nodes.get(applied.railName);
                if(source!=null&&source.bridgeId!=RailwayBridgeDeck.NONE
                        &&!RailwayBridgeDeck.matches(source.tileX,source.tileY,source.layer,source.bridgeId))
                    throw new IllegalArgumentException("The rail vehicle's bridge deck is missing or has changed.");
                WurmRailWorld.validateCarStep(car,pose,applied);
                float height = Float.isFinite(pose.elevation) ? pose.elevation : WurmRailWorld.groundHeight(pose.x,pose.y,pose.layer);
                for(int candidateLayer:new int[]{pose.layer,pose.layer==0?-1:0})for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) {
                    VolaTile tile=Zones.getTileOrNull(x+dx,y+dy,candidateLayer==0);
                    if(tile==null) continue;
                    for(Item other:tile.getItems()) if(WurmRailWorld.isRailCar(other)&&!consist.contains(other.getWurmId())) {
                        Pose projected=managedCollisionPose(other);
                        float otherX=projected==null?other.getPosX():projected.x,otherY=projected==null?other.getPosY():projected.y;
                        int otherLayer=projected==null?(other.isOnSurface()?0:-1):projected.layer;
                        if(distance(pose.x,pose.y,otherX,otherY)>=3.5
                                || !physicallyConnectedLayers(pose.x,pose.y,pose.layer,otherX,otherY,otherLayer)) continue;
                        float otherHeight = projected == null ? other.getPosZ() : Float.isFinite(projected.elevation)
                                ? projected.elevation : WurmRailWorld.groundHeight(otherX,otherY,otherLayer);
                        if(pose.layer != otherLayer || RailwayTrestleCrossings.vehicleLevelsOverlap(height,otherHeight))
                            throw new IllegalArgumentException("A parked rail vehicle blocks the track.");
                    }
                }
            }
            validationError=null; if(!poses.isEmpty()) motionErrors.remove(poses.get(0).carId); return true;
        } catch(Exception e) {
            validationError=e.getMessage();
            if(!poses.isEmpty()) motionErrors.put(poses.get(0).carId,validationError);
            return false;
        }
    }
    private boolean physicallyConnectedLayers(float ax,float ay,int firstLayer,float bx,float by,int secondLayer) {
        if(firstLayer==secondLayer)return true;
        if(!WurmRailWorld.isEntranceTile((int)Math.floor(ax/4),(int)Math.floor(ay/4))
                &&!WurmRailWorld.isEntranceTile((int)Math.floor(bx/4),(int)Math.floor(by/4)))return false;
        try { WurmRailWorld.validateSegment(ax,ay,firstLayer,bx,by,secondLayer);return true; }
        catch(Exception blocked) { return false; }
    }
    /** Core trains advance together before their native items are committed for this poll. */
    private Pose managedCollisionPose(Item item) {
        Pose applied=appliedPoses.get(item.getWurmId());
        if(applied==null||item.getParentId()!=-10L||item.isBanked()||item.isMailed()||item.isTraded()
                ||item.getFloorLevel()!=0||(item.isOnSurface()?0:-1)!=applied.layer
                ||distance(applied.x,applied.y,item.getPosX(),item.getPosY())>.02)return null;
        Train owner=findTrain(item.getWurmId());
        if(owner!=null)for(Pose pose:owner.poses)if(pose.carId==item.getWurmId())return pose;
        return null;
    }
    private void checkTrainWorld(Train t) {
        if(!validateMotion(t.poses)) throw new IllegalArgumentException(validationError);
    }
    private void applyPoses(Train t) throws Exception {
        checkTrainWorld(t);
        restoreTrainPoses(t.poses);
    }
    private void checkCar(Creature p,Item car,boolean emptySeats) {
        checkNear(p,car);
        if(!WurmRailWorld.canManage(p,car)) throw new IllegalArgumentException("You do not have permission to use this vehicle.");
        if(car.getParentId()!=-10L||car.isBanked()||car.isMailed()||car.isTraded()) throw new IllegalArgumentException("The vehicle must be on the ground and available.");
        Vehicle v=Vehicles.getVehicle(car);
        if(emptySeats&&v!=null&&v.isAnySeatOccupied()) throw new IllegalArgumentException("Everyone must disembark before changing couplings.");
    }
    private void requireOperator(Creature p,Train t) throws Exception {
        if(p.getPower()<2&&p.getWurmId()!=t.ownerId&&!allowed(operators,p.getName())) throw new IllegalArgumentException("Only the owner or a configured rail operator can control this train.");
        Item engine=Items.getItemOptional(t.carIds.get(0)).orElse(null);
        if(engine==null) {
            if(p.getPower()<2&&!t.poses.isEmpty()) {
                Pose expected=t.poses.get(0);
                if(layer(p)!=expected.layer||distance(p.getPosX(),p.getPosY(),expected.x,expected.y)>8)
                    throw new IllegalArgumentException("Stand near the missing engine's saved position to recover this train.");
            }
            return;
        }
        if(p.getPower()<2&&!WurmRailWorld.canManage(p,engine)) throw new IllegalArgumentException("The engine is locked or its permissions deny access.");
        if(!t.carIds.contains(p.getVehicle())) {
            boolean near=false;
            for(long id:t.carIds) {
                Item car=Items.getItemOptional(id).orElse(null);
                if(car!=null&&car.isOnSurface()==p.isOnSurface()
                        &&RailwayBridgeDeck.isAtItemLevel(p,car.getBridgeId(),car.getFloorLevel())
                        &&distance(p.getPosX(),p.getPosY(),car.getPosX(),car.getPosY())<=8) { near=true; break; }
            }
            if(!near) throw new IllegalArgumentException("Stand beside a vehicle in this train, or board it first.");
        }
    }
    /** Fleet controls intentionally omit distance, while retaining ownership, role and vehicle permissions. */
    private void requireFleetOperator(Creature p,Train train) {
        if(p.getPower()<2&&p.getWurmId()!=train.ownerId&&!allowed(operators,p.getName()))
            throw new IllegalArgumentException("Only the owner or a configured rail operator can control this service.");
        Item engine=Items.getItemOptional(train.carIds.get(0)).orElse(null);
        if(engine!=null&&p.getPower()<2&&!WurmRailWorld.canManage(p,engine))
            throw new IllegalArgumentException("The locomotive's lock or permissions deny access.");
    }
    private void applyServices(Creature actor,List<RailwayTrainUi.Service> captured,RailwayTrainUi.Action action) throws Exception {
        if(captured==null||captured.isEmpty())throw new IllegalArgumentException("Refresh railway services before controlling scheduled trains.");
        Set<String> names=new LinkedHashSet<String>();List<Train> targets=new ArrayList<Train>();
        for(RailwayTrainUi.Service service:captured) {
            Train train=service==null?null:network.trains.get(service.name);
            if(train==null||train!=service.train||!ScheduledServices.scheduled(train)
                    ||train.carIds.get(0)!=service.locomotiveId||!names.add(train.name))
                throw new IllegalArgumentException("A scheduled train changed. Refresh railway services before continuing.");
            requireFleetOperator(actor,train);targets.add(train);
        }
        if(action!=RailwayTrainUi.Action.SERVICES_START&&action!=RailwayTrainUi.Action.SERVICES_STOP&&action!=RailwayTrainUi.Action.SERVICES_RESOLVE)
            throw new IllegalArgumentException("Choose a scheduled-service control.");
        List<Checkpoint> before=new ArrayList<Checkpoint>();for(Train train:network.trains.values())before.add(network.checkpoint(train.name));
        ScheduledServices.Checkpoint intentBefore=scheduledServices.checkpoint();
        int started=0,already=0,stopped=0,resolving=0;List<String> held=new ArrayList<String>();
        try {
            network.signals().traffic().restoreOccupiedCorridors();
            if(action==RailwayTrainUi.Action.SERVICES_RESOLVE)resolving=network.resolveTrafficJams(names);
            else for(Train train:targets) {
                if(action==RailwayTrainUi.Action.SERVICES_STOP) {
                    network.stop(train.name);scheduledServices.record(train,false);stopped++;continue;
                }
                if(train.powered) { scheduledServices.record(train,true);already++;continue; }
                Checkpoint trainBefore=network.checkpoint(train.name);
                try {
                    validateStationRoute(train,train.route);checkTrainWorld(train);network.resumeScheduled(train.name);
                    scheduledServices.record(train,train.powered);
                    if(train.powered)started++;else held.add(trainLabel(train)+": "+train.status);
                }catch(IllegalArgumentException unavailable) {
                    network.restore(trainBefore);train.status="Start held: "+unavailable.getMessage();
                    held.add(trainLabel(train)+": "+unavailable.getMessage());
                }
            }
            if(action==RailwayTrainUi.Action.SERVICES_STOP)scheduledServices.save(servicesFile(),network);
            save();
        }catch(Exception failed) {
            if(action!=RailwayTrainUi.Action.SERVICES_STOP) {
                for(Checkpoint snapshot:before)network.restore(snapshot);scheduledServices.restore(intentBefore);
            }
            throw failed;
        }
        if(action==RailwayTrainUi.Action.SERVICES_STOP)tell(actor,"Stopped "+stopped+" scheduled trains. They will stay stopped after restart.");
        else if(action==RailwayTrainUi.Action.SERVICES_RESOLVE)reportTrafficRecovery(actor,targets,resolving);
        else tell(actor,"Started "+started+" scheduled trains; "+already+" already running; "+held.size()+" held.");
        for(String reason:held)tell(actor,reason);
    }
    private RailwayTrainUi.Service serviceSnapshot(Train train) {
        return new RailwayTrainUi.Service(train,trainLabel(train),train.powered,trainStatusLabel(train.status),network.signals().trafficStatus(train.name));
    }
    /** Recovery explains each held service without changing operator STOP or flooding chat. */
    private void reportTrafficRecovery(Creature actor,List<Train> trains,int resolving) {
        if(resolving>0) {tell(actor,resolving+" scheduled train(s) are clearing traffic safely.");return;}
        int powered=0;for(Train train:trains)if(train.powered)powered++;
        if(powered==0)tell(actor,"All scheduled trains in this window are powered OFF. Use Start all scheduled first. Clear traffic jams does not restart stopped trains.");
        else tell(actor,"No safe traffic recovery could start. "+powered+" of "+trains.size()+" scheduled trains are powered ON. Current reasons:");
        for(int i=0;i<Math.min(4,trains.size());i++) {
            RailwayTrainUi.Service service=serviceSnapshot(trains.get(i));
            String status=service.status.isEmpty()?"No status available":service.status;
            String traffic=service.traffic.isEmpty()?"No dispatcher wait or reservation":service.traffic;
            String line=service.label+" | Power "+(service.powered?"ON":"OFF")+" | Status: "+status+" | Traffic: "+traffic;
            tell(actor,line.length()>480?line.substring(0,477)+"...":line);
        }
        tell(actor,"Railway services shows full Power, Status and Traffic for every included train. Refresh services to check progress."
                +(trains.size()>4?" "+(trains.size()-4)+" more trains are listed there.":""));
    }
    private void requireBuilder(Creature p) { if(p.getPower()<2&&!allowed(builders,p.getName())) throw new IllegalArgumentException("Only configured builders or GMs may edit track."); }
    private static boolean allowed(String list,String name) { for(String s:list.split(",")) if(s.trim().equals("*")||s.trim().equalsIgnoreCase(name)) return true; return false; }
    private static void stopped(Train t) { if(t.powered||t.speed>0.001) throw new IllegalArgumentException("Power off and stop the train first."); }
    private static void emptyConsist(Train t) throws Exception {
        for(long id:t.carIds) {
            Vehicle v=Vehicles.getVehicle(Items.getItem(id));
            if(v!=null&&v.isAnySeatOccupied()) throw new IllegalArgumentException("Everyone must disembark before changing couplings.");
        }
    }
    private static void checkNear(Creature p,Item i) { if(p.isOnSurface()!=i.isOnSurface()
            ||!RailwayBridgeDeck.isAtItemLevel(p,i.getBridgeId(),i.getFloorLevel())
            ||distance(p.getPosX(),p.getPosY(),i.getPosX(),i.getPosY())>8)
        throw new IllegalArgumentException("Stand within 8 metres on the same level."); }
    private static void checkTrainPanelReach(Creature p,Item clicked,Train train) {
        // A native passenger seat remains authoritative while mounted position/floor
        // packets catch up, including rear wagons more than 8m from the engine.
        if(train!=null&&train.carIds.contains(p.getVehicle())) {
            Item mounted=Items.getItemOptional(p.getVehicle()).orElse(null);
            Vehicle vehicle=mounted==null?null:Vehicles.getVehicle(mounted);
            if(mounted!=null&&WurmRailWorld.isRailCar(mounted)&&vehicle!=null&&vehicle.seats!=null)
                for(Seat seat:vehicle.seats)if(seat.getOccupant()==p.getWurmId())return;
        }
        checkNear(p,clicked);
    }
    private static double distance(double x,double y,double a,double b) { return Math.hypot(x-a,y-b); }
    private static int layer(Creature p) { return p.isOnSurface()?0:-1; }
    private Node node(String name) { Node n=network.nodes.get(name); if(n==null) throw new IllegalArgumentException("Unknown track node: "+name); return n; }
    private Node nodeAt(int x,int y,int layer) { return network.nodeAt(x,y,layer); }
    private Train findTrain(long car) { for(Train t:network.trains.values()) if(t.carIds.contains(car)) return t; return null; }
    private Train train(Creature p,String name) {
        Train t=name==null?findTrain(p.getVehicle()):network.trains.get(name);
        if(t==null&&name==null) { double nearest=8; for(Train candidate:network.trains.values()) for(Pose pose:candidate.poses) {
            double d=distance(p.getPosX(),p.getPosY(),pose.x,pose.y); if(pose.layer==layer(p)&&d<nearest) { t=candidate;nearest=d; }
        } }
        if(t==null) throw new IllegalArgumentException("Specify a train name, or stand beside/board a train. Use /rail list."); return t;
    }
    private List<Item> nearby(Creature p) {
        List<Item> result=new ArrayList<Item>();
        for(Item i:Items.getAllItems()) if(WurmRailWorld.isRailCar(i)&&i.isOnSurface()==p.isOnSurface()&&distance(p.getPosX(),p.getPosY(),i.getPosX(),i.getPosY())<=8) result.add(i);
        result.sort(Comparator.comparingDouble(i->distance(p.getPosX(),p.getPosY(),i.getPosX(),i.getPosY()))); return result;
    }
    private Item findNearby(Creature p,boolean engine) {
        for(Item i:nearby(p)) if((engine?WurmRailWorld.isLocomotive(i):WurmRailWorld.isWagon(i))&&findTrain(i.getWurmId())==null) return i;
        throw new IllegalArgumentException("No unlinked rail "+(engine?"engine":"wagon")+" within 8 metres. Specify its item ID.");
    }
    private void status(Creature p,Train t) { tell(p,t.name+": "+t.status+", power "+(t.powered?"on":"off")+", "+String.format(Locale.ROOT,"%.2f",t.speed)+" m/s, "+t.carIds.size()+" vehicles, "+(t.automated?"route "+t.route:"manual")+". Engine ID "+t.carIds.get(0)); }
    private void save() throws Exception {
        if(trackVisualsDirty) refreshTrackVisuals();
        for(Train t:network.trains.values()) for(long car:t.carIds) {
            // A missing/deleted car must not prevent the track network being saved.
            if(Items.getItemOptional(car).isPresent()) WurmRailWorld.saveCar(car);
        }
        network.save(stateFile);scheduledServices.save(servicesFile(),network);lastSave=System.currentTimeMillis();
    }
    private static void tell(Creature p,String s) { p.getCommunicator().sendNormalServerMessage("[Rail] "+s); }
    private static void tellFailure(Creature p,Exception failure) {
        tell(p,failure.getMessage()==null?"The railway action could not be completed.":failure.getMessage());
    }
    private static String abbreviate(String s) { return s.length()>700?s.substring(0,700)+"...":s; }
    private static void require(String[] a,int count,String usage) { if(a.length<count) throw new IllegalArgumentException("Usage: /rail "+usage); }
    private static void checkName(String s) { if(!s.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Names must be 1-40 letters, numbers, underscores, or hyphens."); }
    private static int integer(String s,int min,int max) { int v=Integer.parseInt(s); if(v<min||v>max) throw new IllegalArgumentException("Enter an integer from "+min+" to "+max+"."); return v; }
    private static double number(String s,double min,double max) { double v=Double.parseDouble(s); if(!Double.isFinite(v)||v<min||v>max) throw new IllegalArgumentException("Enter a number from "+min+" to "+max+"."); return v; }


    private String trainLabel(Train t) {
        Item engine=Items.getItemOptional(t.carIds.get(0)).orElse(null);
        return RailwayTrainNames.label(t,engine);
    }
    private String ownerLabel(String key) {
        Train train=key==null?null:network.trains.get(key); return train==null?key:trainLabel(train);
    }
    private static String trainStatusLabel(String status) {
        if(status.startsWith("Signal ")) {
            int reason=status.indexOf(" RED: ");
            if(reason>=0)return "Waiting at RED: "+status.substring(reason+6);
        }
        return status;
    }
    private void requireSetupAccess(Creature p,Item car) {
        if(p.getPower()<2&&p.getWurmId()!=car.getLastOwnerId()&&!allowed(operators,p.getName()))
            throw new IllegalArgumentException("Only the owner or an allowed rail operator may set up this vehicle.");
        checkCar(p,car,false);
    }
    private void openVehicle(Creature p,long id,String operation) throws Exception {
        Item car=Items.getItem(id);
        if(!WurmRailWorld.isRailCar(car)) throw new IllegalArgumentException("Choose a railway vehicle.");
        Train t=findTrain(id);
        if(operation.equals("board")||operation.equals("cargo"))checkNear(p,car);
        else checkTrainPanelReach(p,car,t);
        if(operation.equals("board")) {
            if(t==null&&WurmRailWorld.isLocomotive(car)) {
                requireSetupAccess(p,car);
                try { t=trainService.ensureTrain(p,car); save(); }
                catch(IllegalArgumentException waiting) { tell(p,waiting.getMessage()); RailwayTrainUi.show(p,id,trainApi); return; }
            }
            if(t!=null&&t.speed>.01) throw new IllegalArgumentException("Wait for the train to stop before boarding.");
            WurmRailWorld.board(p,car);
        } else if(operation.equals("cargo")) { openCargo(p,car,t); return; }
        RailwayTrainUi.show(p,id,trainApi);
    }
    private void openCargo(Creature p,Item car,Train t) throws Exception {
        checkNear(p,car); if(t!=null) stopped(t);
        if(!MethodsItems.mayUseInventoryOfVehicle(p,car)) throw new IllegalArgumentException("The vehicle's lock or permissions deny cargo access.");
        BehaviourDispatcher.action(p,p.getCommunicator(),-1L,car.getWurmId(),Actions.OPEN);
    }
    private RailwayTrainUi.View trainSnapshot(Creature p,long id) throws Exception {
        Item car=Items.getItem(id);
        if(!WurmRailWorld.isRailCar(car)) throw new IllegalArgumentException("This rail vehicle is no longer available.");
        Train t=findTrain(id); checkTrainPanelReach(p,car,t); String setup="";
        if(t==null&&WurmRailWorld.isLocomotive(car)) {
            try { requireSetupAccess(p,car); t=trainService.ensureTrain(p,car); save(); }
            catch(IllegalArgumentException waiting) { setup=waiting.getMessage(); }
        }
        RailwayTrainUi.View v=new RailwayTrainUi.View(); v.carId=id; v.train=t;v.serverResumeEnabled=autoResumeScheduled;
        v.engine=WurmRailWorld.isLocomotive(car); v.speedLimit=speedLimit; v.aboard=p.getVehicle()!=-10L;
        v.title=t==null?car.getName():trainLabel(t); v.status=t==null?(setup.isEmpty()?"Ready":setup):trainStatusLabel(t.status);
        try { if(t==null) requireSetupAccess(p,car); else requireOperator(p,t); v.canControl=true; }
        catch(Exception denied) { v.canControl=false; }
        Vehicle seats=Vehicles.getVehicle(car); v.seats=seats==null?4:seats.seats.length;
        if(seats!=null) for(Seat seat:seats.seats) if(seat.isOccupied()) v.occupiedSeats++;
        v.cargoItems=car.getItems().size();
        if(TankCarRegistry.isTankCar(car.getTemplateId()))v.cargoStatus=LiquidTankPolicy.summary(car);
        if(LogCarPolicy.isCar(car))v.cargoStatus=LogCarPolicy.summary(car);
        if(t!=null) {
            v.cargoStatus=RailwayFreightWorld.cargoCapacitySummary(t);
            String liquidCapacity=RailwayLiquidCars.capacitySummary(t);
            if(!liquidCapacity.isEmpty())v.cargoStatus+="; "+liquidCapacity;
            v.resumeEnabled=autoResumeScheduled&&scheduledServices.runningIntent(t);
            v.powered=t.powered; v.speed=t.speed; v.maxSpeed=t.maxSpeed;
            v.mode=(t.automated?"Automatic":"Manual")+(t.direction>0?" | Forward":" | Backing");
            v.route=new ArrayList<String>(t.route);
            Map<String,String> routeStops=routeStopCatalog(t);
            v.trafficStatus=network.signals().trafficStatus(t.name);
            if(t.automated&&!t.route.isEmpty()) {
                String target=t.route.get(t.routeIndex);
                v.nextStop=routeStops.containsKey(target)?routeStops.get(target):"Unavailable stop";
            }
            List<String> labels=new ArrayList<String>(); for(String stop:t.route) labels.add(routeStops.containsKey(stop)?routeStops.get(stop):"Unavailable stop");
            String route=String.join(" -> ",labels); v.routeSummary=route.isEmpty()?"No automatic route":route.length()>230?route.substring(0,230)+"...":route;
            v.passengerStopWaitSeconds=t.routeDwellSeconds;
            int index=0;
            for(long member:t.carIds) {
                Item other=Items.getItemOptional(member).orElse(null);
                if(other!=null) v.cars.add(new RailwayTrainUi.Choice(Long.toString(member),(index==0?"Locomotive":"Wagon "+index)+" - "+other.getName()));
                index++;
            }
            if(v.canControl) {
                for(Train candidate:network.trains.values())if(ScheduledServices.scheduled(candidate)) {
                    try {
                        requireFleetOperator(p,candidate);
                        v.services.add(serviceSnapshot(candidate));
                        if(candidate.powered)v.runningServices++;
                    }catch(IllegalArgumentException denied) { }
                }
                Item tail=Items.getItemOptional(t.carIds.get(t.carIds.size()-1)).orElse(null);
                for(Item wagon:nearby(p)) if(WurmRailWorld.isWagon(wagon)&&findTrain(wagon.getWurmId())==null
                        &&tail!=null&&distance(wagon.getPosX(),wagon.getPosY(),tail.getPosX(),tail.getPosY())<=8&&WurmRailWorld.canManage(p,wagon))
                    v.wagons.add(new RailwayTrainUi.Choice(Long.toString(wagon.getWurmId()),"Nearby wagon "+(v.wagons.size()+1)+" - "+wagon.getName()));
            }
            for(Map.Entry<String,String> stop:routeStops.entrySet())if(v.stations.size()<512)
                v.stations.add(new RailwayTrainUi.Choice(stop.getKey(),stop.getValue()));
            v.routeHelp=routeStops.isEmpty()?routeStopEmptyReason(t):routeStops.size()+" connected route stop"+(routeStops.size()==1?"":"s")+" available. Stations are included.";
        } else {
            v.cars.add(new RailwayTrainUi.Choice(Long.toString(id),car.getName()));
            if(v.canControl&&v.engine) for(Node n:network.nodes.values()) {
                if(n.layer==layer(p)&&!n.links.isEmpty()&&distance(n.worldX(),n.worldY(),p.getPosX(),p.getPosY())<=8
                        &&distance(n.worldX(),n.worldY(),car.getPosX(),car.getPosY())<=8)
                    v.rails.add(new RailwayTrainUi.Choice(n.name,railLabel(n)));
            }
            if(v.canControl&&!v.engine) for(Train candidate:trainService.nearbyCouplingTrains(p,car)) {
                try { requireOperator(p,candidate); v.trains.add(new RailwayTrainUi.Choice(candidate.name,trainLabel(candidate))); }
                catch(Exception denied) { }
            }
        }
        return v;
    }
    private void applyTrainRequest(Creature p,long id,Train expected,RailwayTrainUi.Request request) throws Exception {
        Item clicked=Items.getItem(id);
        if(!WurmRailWorld.isRailCar(clicked)) throw new IllegalArgumentException("This railway vehicle changed.");
        Train current=findTrain(id);
        checkTrainPanelReach(p,clicked,current);
        if(request.action!=RailwayTrainUi.Action.REFRESH&&current!=expected)
            throw new IllegalArgumentException("The train changed while the window was open. Open its controls again.");
        if(request.action==RailwayTrainUi.Action.REFRESH) { RailwayTrainUi.show(p,id,trainApi); return; }
        if(request.action==RailwayTrainUi.Action.PASSENGER_STOPS) {
            if(current==null||findTrain(p.getVehicle())!=current)throw new IllegalArgumentException("Board this train first, or choose your destination from a station.");
            RailwayPassengerUi.show(p,-10L,passengerApi); return;
        }
        if(request.action==RailwayTrainUi.Action.BOARD||request.action==RailwayTrainUi.Action.CARGO) {
            long selected=Long.parseLong(request.choice);
            if(current==null?selected!=id:!current.carIds.contains(selected)) throw new IllegalArgumentException("Choose a vehicle in this train.");
            openVehicle(p,selected,request.action==RailwayTrainUi.Action.BOARD?"board":"cargo"); return;
        }
        if(request.action==RailwayTrainUi.Action.PLACE) {
            requireSetupAccess(p,clicked); trainService.placeOnTrack(p,clicked,node(request.choice)); save();
            RailwayTrainUi.show(p,id,trainApi); return;
        }
        if(request.action==RailwayTrainUi.Action.COUPLE&&current==null) {
            requireSetupAccess(p,clicked); Train target=network.trains.get(request.choice);
            if(target==null) throw new IllegalArgumentException("That train is no longer available.");
            requireOperator(p,target); trainService.couple(p,target,clicked); save(); RailwayTrainUi.show(p,id,trainApi); return;
        }
        if(current==null) throw new IllegalArgumentException("Place the locomotive on connected track first.");
        requireOperator(p,current);
        switch(request.action) {
            case START:
                startService(current);RailwayTrainUi.show(p,id,trainApi);return;
            case STOP:
                network.stop(current.name);scheduledServices.record(current,false);scheduledServices.save(servicesFile(),network);
                tell(p,trainLabel(current)+" stopped. Power is off.");break;
            case SERVICES: RailwayTrainUi.showServices(p,id,current,trainApi);return;
            case SERVICES_START: case SERVICES_STOP: case SERVICES_RESOLVE:
                applyServices(p,request.services,request.action);RailwayTrainUi.showServices(p,id,current,trainApi);return;
            case REVERSE: reverseForOperator(p,current); break;
            case MANAGE: RailwayTrainUi.showManagement(p,id,trainApi); return;
            case SPEED:
                if(!Double.isFinite(request.speed)||request.speed<.25||request.speed>speedLimit) throw new IllegalArgumentException("Choose a speed from 0.25 to "+speedLimit+" m/s.");
                current.maxSpeed=request.speed; break;
            case RENAME:
                String label=friendlyName(request.text);
                for(Train other:network.trains.values()) if(other!=current&&trainLabel(other).equalsIgnoreCase(label))
                    throw new IllegalArgumentException("Choose a different train name; that name is already in use.");
                Item engine=Items.getItem(current.carIds.get(0)); engine.setName(label);
                RailwayTrainNames.synchronize(current,engine); break;
            case COUPLE: trainService.couple(p,current,Items.getItem(Long.parseLong(request.choice))); break;
            case UNCOUPLE: trainService.uncoupleRear(p,current); break;
            case MANUAL:
                stopForControl(current); network.setRoute(current.name,Collections.<String>emptyList(),current.routeDwellSeconds);
                scheduledServices.save(servicesFile(),network);
                tell(p,"Manual driving selected. Power is off; choose Reverse or Power on when ready."); break;
            case ROUTE_EDIT:
                stopForControl(current);
                scheduledServices.save(servicesFile(),network);
                save();
                RailwayTrainUi.routeEditor(p,id,current,request.route,request.selectedRouteStop,trainApi); return;
            case ROUTE_SAVE:
            case ROUTE_START:
                commitTrainRoute(current,request.route,request.action==RailwayTrainUi.Action.ROUTE_START);
                RailwayTrainUi.show(p,id,trainApi); return;
            default: throw new IllegalArgumentException("This action is unavailable for the selected vehicle.");
        }
        save(); RailwayTrainUi.show(p,id,trainApi);
    }
    private void stopForControl(Train train) {
        if(train.speed>0.001)throw new IllegalArgumentException("Press STOP before changing a moving train.");
        network.stop(train.name);
        scheduledServices.record(train,false);
    }
    private void startService(Train train) throws Exception {
        Checkpoint before=network.checkpoint(train.name);
        ScheduledServices.Checkpoint intentBefore=scheduledServices.checkpoint();
        try {
            if(train.automated)validateStationRoute(train,train.route);
            checkTrainWorld(train);network.start(train.name);scheduledServices.record(train,train.powered);save();
        }catch(Exception failed) {
            network.restore(before);scheduledServices.restore(intentBefore);throw failed;
        }
    }
    private void reverseForOperator(Creature actor,Train train) throws Exception {
        if(train.speed>0.001)throw new IllegalArgumentException("Press STOP before reversing a moving train.");
        Checkpoint before=network.checkpoint(train.name);
        ScheduledServices.Checkpoint servicesBefore=scheduledServices.checkpoint();
        try {
            stopForControl(train);
            network.setRoute(train.name,Collections.<String>emptyList(),train.routeDwellSeconds);
            network.reverse(train.name);
        }catch(RuntimeException failure) { network.restore(before);scheduledServices.restore(servicesBefore);throw failure; }
        // A successful manual reversal remains stopped even if the later network save fails.
        // Persist after the reversible mutation: a rejected reversal must retain its prior intent.
        scheduledServices.save(servicesFile(),network);
        // Reversing changes travel direction, not any native car position or seat.
        tell(actor,"Direction reversed in manual driving. Power is off. Power on to back away; choose a station route to resume automatic service.");
    }
    private void restoreTrainPoses(List<Pose> poses) throws Exception {
        WurmRailWorld.MotionBatch batch = new WurmRailWorld.MotionBatch();
        for (Pose pose : poses) {
            WurmRailWorld.moveCar(pose,appliedPoses.get(pose.carId),batch);
            appliedPoses.put(pose.carId,pose);
        }
        batch.addConsist(poses);
        batch.send();
    }
    private void commitTrainRoute(Train train,List<String> stops,boolean start) throws Exception {
        if(train.speed>0.001)throw new IllegalArgumentException("Press STOP before changing a moving train's route.");
        validateStationRoute(train,stops);
        if(start&&stops.size()<2)throw new IllegalArgumentException("Add at least two stops for a repeating automatic service.");
        if(start)checkTrainWorld(train);
        Checkpoint before=network.checkpoint(train.name);
        ScheduledServices.Checkpoint servicesBefore=scheduledServices.checkpoint();
        try {
            network.stop(train.name); network.setRoute(train.name,stops,train.routeDwellSeconds);
            if(start)network.start(train.name);
            scheduledServices.record(train,start&&train.powered);
            save();
        }catch(Exception failure) { network.restore(before);scheduledServices.restore(servicesBefore);throw failure; }
    }
    private void validateStationRoute(Train train,List<String> stops) {
        if(stops.size()>1000) throw new IllegalArgumentException("Choose no more than 1000 station stops.");
        Map<String,String> available=routeStopCatalog(train); String previous=null;
        for(String stop:stops) {
            if(!available.containsKey(stop))throw new IllegalArgumentException("A selected stop is missing, disconnected or needs repair. Open the route editor and remove or replace it.");
            if(stop.equals(previous))throw new IllegalArgumentException("Remove consecutive duplicate stops before saving the route.");
            previous=stop;
        }
    }
    private boolean trackMarkerPresent(Node rail) {
        Item marker=Items.getItemOptional(rail.markerId).orElse(null);
        for (long id : rail.timber()) {
            Item bent = Items.getItemOptional(id).orElse(null);
            if (bent == null || bent.getTemplateId() != WurmRailWorld.trestleBentTemplateId
                    || bent.getParentId() != -10L || !bent.isOnSurface() || bent.getDamage() >= 100) return false;
        }
        return marker!=null&&(marker.getTemplateId()==WurmRailWorld.trackTemplateId
                ||marker.getTemplateId()==WurmRailWorld.trestleDeckTemplateId)&&marker.getParentId()==-10L&&marker.getOwnerId()==-10L
                &&!marker.isBanked()&&!marker.isMailed()&&!marker.isTraded()&&marker.getTileX()==rail.tileX&&marker.getTileY()==rail.tileY
                &&marker.isOnSurface()==(rail.layer==0)&&marker.getFloorLevel()==0&&marker.getBridgeId()==rail.bridgeId
                &&RailwayBridgeDeck.matches(rail.tileX,rail.tileY,rail.layer,rail.bridgeId);
    }
    private Set<String> trainConnectedRails(Train train) {
        Node origin=train.poses.isEmpty()?null:network.nodes.get(train.poses.get(0).railName);
        if(origin==null&&!train.poses.isEmpty()) {
            Pose first=train.poses.get(0); origin=nodeAt((int)(first.x/4),(int)(first.y/4),first.layer);
        }
        return connectedRails(origin==null?null:origin.name);
    }
    /** Every named railway stop is catalogued once per physical rail, using its existing saved key. */
    private Map<String,String> routeStopCatalog(Train train) {
        Set<String> reached=trainConnectedRails(train);
        Map<String,String> result=new LinkedHashMap<String,String>();
        Map<String,List<Platform>> byRail=new HashMap<String,List<Platform>>();
        for(Platform platform:passengerStations.platforms.values()) {
            List<Platform> list=byRail.get(platform.rail);
            if(list==null) { list=new ArrayList<Platform>();byRail.put(platform.rail,list); }
            list.add(platform);
        }
        Set<String> previousTargets=new HashSet<String>(train.route);
        for(String key:reached) {
            Node rail=node(key); if(!trackMarkerPresent(rail))continue;
            List<String> names=new ArrayList<String>(); List<Platform> platforms=byRail.get(key);
            Stop freight=freightStops.get(key);
            boolean retired=isDeprecatedWaterFillRail(key);
            WaterUnloadStops.Stop unload=waterUnloadStops.get(key);
            if(freight!=null)names.add(freight.name+" [Freight "+freight.operation+"]");
            else if(retired) { if(previousTargets.contains(key))names.add("Deprecated water fill [Inactive waypoint]");else continue; }
            else if(unload!=null)names.add(unload.name+" [Water "+unload.operation+"]");
            else if(rail.station)names.add(stationLabel(rail)+" [Train stop]");
            if(platforms!=null)for(Platform platform:platforms) {
                if(platformPresent(platform))names.add(platform.name+" [Station]");
            }
            // Older command-built itineraries can contain unnamed waypoints. Retain
            // those existing targets without offering arbitrary new track as stops.
            if(names.isEmpty()&&!rail.station&&freight==null&&!retired&&platforms==null&&previousTargets.contains(key))
                names.add("Track at "+rail.tileX+", "+rail.tileY+" [Saved waypoint]");
            if(!names.isEmpty())result.put(key,String.join(" / ",names));
        }
        return result;
    }
    private String routeStopEmptyReason(Train train) {
        Set<String> reached=trainConnectedRails(train);Set<String> healthy=new HashSet<String>(),damaged=new HashSet<String>();
        for(Node rail:network.nodes.values())if(rail.station) {
            if(trackMarkerPresent(rail))healthy.add(rail.name);else damaged.add(rail.name);
        }
        for(Platform platform:passengerStations.platforms.values()) {
            Node rail=network.nodes.get(platform.rail);
            if(rail!=null&&trackMarkerPresent(rail)&&platformPresent(platform))healthy.add(rail.name);else damaged.add(platform.rail);
        }
        int disconnected=0;for(String rail:healthy)if(!reached.contains(rail))disconnected++;
        if(disconnected>0)return disconnected+" stop"+(disconnected==1?" is":"s are")+" on disconnected track. Connect the railway to this train first.";
        if(!damaged.isEmpty())return "Stop or track markers need repair. Repair them, then reopen this route window.";
        return "No route stops built yet. Build a Station, configure Freight stop or Water freight stop on connected track, or create a named train stop.";
    }
    private void requireStopCanBeRemoved(Node rail,long excludedPlatform,boolean removingNamedStop) {
        if(freightStops.get(rail.name)!=null||waterUnloadStops.get(rail.name)!=null)return;
        if(!removingNamedStop&&rail.station&&trackMarkerPresent(rail))return;
        for(Platform platform:passengerStations.platforms.values())
            if(platform.itemId!=excludedPlatform&&platform.rail.equals(rail.name)&&platformPresent(platform))return;
        for(Train train:network.trains.values())if(train.route.contains(rail.name))
            throw new IllegalArgumentException("This stop is used by "+trainLabel(train)+". Remove it from that train's route before removing its last station or platform.");
    }
    private Node signalNode(Creature p,long clickedId) throws Exception {
        Item clicked=Items.getItem(clickedId); checkNear(p,clicked);
        if(clicked.getParentId()!=-10L||clicked.getOwnerId()!=-10L||clicked.isBanked()||clicked.isMailed()||clicked.isTraded()
                ||clicked.getFloorLevel()!=0||clicked.getBridgeId()!=-10L||p.getFloorLevel()!=0||p.getBridgeId()!=-10L)
            throw new IllegalArgumentException("Inspect railway signals from the ground on the same level.");
        Node n=nodeAt(clicked.getTileX(),clicked.getTileY(),clicked.isOnSurface()?0:-1);
        if(n==null)throw new IllegalArgumentException("This railway was removed. Open the track again.");
        if(clicked.getTemplateId()==WurmRailWorld.trackTemplateId) {
            if(clickedId!=n.markerId)throw new IllegalArgumentException("This is an old track marker. Open the current track.");
        } else if(WurmRailWorld.isJunctionIndicator(clicked)) {
            if(clicked.getData()!=n.markerId)throw new IllegalArgumentException("This alignment indicator belongs to another track.");
        } else if(WurmRailWorld.isSignal(clicked)) {
            if(clicked.getData()!=n.markerId||(clicked.getAuxData()&255)>15)
                throw new IllegalArgumentException("This signal marker is invalid. Open its track to repair the display.");
            boolean configured=false;
            for(SignalSystem.Signal signal:network.signals().definitions())
                if(signal.rail.equals(n.name)&&signal.approachDirection==(clicked.getAuxData()&7))configured=true;
            if(!configured)throw new IllegalArgumentException("This signal was removed. Open its track to inspect the current signals.");
        } else throw new IllegalArgumentException("Choose a railway track, alignment indicator or signal.");
        Item marker=Items.getItemOptional(n.markerId).orElse(null);
        if(marker==null||marker.getTemplateId()!=WurmRailWorld.trackTemplateId||marker.getParentId()!=-10L
                ||marker.getOwnerId()!=-10L||marker.isBanked()||marker.isMailed()||marker.isTraded()
                ||marker.getTileX()!=n.tileX||marker.getTileY()!=n.tileY||marker.isOnSurface()!=(n.layer==0)
                ||marker.getFloorLevel()!=0||marker.getBridgeId()!=-10L)
            throw new IllegalArgumentException("Repair this track's ground marker before configuring signals.");
        return n;
    }
    private RailwaySignalUi.View signalSnapshot(Creature p,long clickedId) throws Exception {
        Node n=signalNode(p,clickedId);
        List<Integer> approaches=new ArrayList<Integer>(); int connected=trackMask(n);
        for(int direction=0;direction<8;direction++)if((connected&(1<<direction))!=0)approaches.add(direction);
        List<RailwaySignalUi.Signal> signals=new ArrayList<RailwaySignalUi.Signal>();
        int defaultApproach=approaches.isEmpty()?0:approaches.get(0);
        Item clicked=Items.getItem(clickedId);
        if(WurmRailWorld.isSignal(clicked))defaultApproach=clicked.getAuxData()&7;
        for(SignalSystem.Signal signal:network.signals().definitions())if(signal.rail.equals(n.name)) {
            SignalSystem.Status status=network.signals().status(signal.id);
            signals.add(new RailwaySignalUi.Signal(signal.id,signal.approachDirection,status.aspect.name(),ownerLabel(status.ownerTrain),status.reason,
                    status.reservation==null?"":signalRouteLabel(status.reservation)));
        }
        boolean canManage=false;
        try { requireBuildContext(p,n.tileX,n.tileY,n.layer); canManage=true; }catch(IllegalArgumentException denied) { }
        return new RailwaySignalUi.View(n.name,n.markerId,network.signals().definitionRevision(),railLabel(n),approaches,signals,
                canManage,RailwayTrackMaterials.count(p),p.getPower()>=2,defaultApproach);
    }
    private String signalRouteLabel(SignalSystem.Reservation route) {
        Train owner=network.trains.get(route.trainName);
        Map<String,String> stops=owner==null?Collections.<String,String>emptyMap():routeStopCatalog(owner);
        String first=route.path.isEmpty()?"Entry":route.path.get(0),last=route.path.isEmpty()?"End":route.path.get(route.path.size()-1);
        String from=stops.containsKey(first)?stops.get(first):network.nodes.containsKey(first)?railLabel(node(first)):"Entry";
        String to=stops.containsKey(last)?stops.get(last):network.nodes.containsKey(last)?railLabel(node(last)):"End";
        StringBuilder text=new StringBuilder(from).append(" -> ").append(to).append(" | ").append(route.nodes.size()).append(" track tiles");
        if(route.state==SignalSystem.ReservationState.CLEARING)
            return text.append(" | previous path, awaiting rear clearance").toString();
        int count=0;
        for(Map.Entry<String,Integer> point:route.junctionAlignments.entrySet()) {
            if(count++>=2) { text.append(" | ").append(route.junctionAlignments.size()-2).append(" more junctions");break; }
            Node rail=network.nodes.get(point.getKey());
            if(rail!=null)text.append(" | ").append(rail.tileX).append(',').append(rail.tileY).append(": ").append(RailwayJunctionWorld.alignmentName(point.getValue()));
        }
        return text.toString();
    }
    private void applySignalRequest(Creature p,long clickedId,RailwaySignalUi.Request request) throws Exception {
        RailwaySignalUi.View view=signalSnapshot(p,clickedId);
        if(request.action==RailwaySignalUi.Action.REFRESH) { RailwaySignalUi.show(p,clickedId,signalApi); return; }
        if(!request.expectedNodeKey.equals(view.nodeKey)||request.expectedMarkerId!=view.markerId||request.revision!=view.revision)
            throw new IllegalArgumentException("The track or signal configuration changed. Refresh this window.");
        Node n=node(view.nodeKey); requireBuildContext(p,n.tileX,n.tileY,n.layer);
        if(request.action==RailwaySignalUi.Action.PLACE) {
            if(network.signals().definitions().size()>=maxNodes)throw new IllegalArgumentException("The railway signal limit has been reached.");
            if(request.approach<0||request.approach>7||(trackMask(n)&(1<<request.approach))==0)
                throw new IllegalArgumentException("Choose an approach side that is connected to this track.");
            String id="sig_"+n.markerId+"_"+request.approach;
            RailwayTrackMaterials.Payment payment=RailwayTrackMaterials.reserve(p,1); Item marker=null;
            boolean added=false;
            try {
                network.signals().add(id,n.name,request.approach,-10L); added=true;
                marker=WurmRailWorld.spawnSignal(n.markerId,n.worldX(),n.worldY(),n.layer,request.approach);
                marker.setQualityLevel(payment.qualityAt(0)); network.signals().setItemId(id,marker.getWurmId());
                payment.consume(); save();
            } catch(Exception failure) {
                if(added)try { network.signals().remove(id); }catch(Exception rollback) { failure.addSuppressed(rollback); }
                if(marker!=null)Items.destroyItem(marker.getWurmId());
                try { payment.refund(); }catch(Exception rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            tell(p,"Signal placed for trains approaching from "+COMPASS[request.approach]+". It clears only for its reserved train.");
        } else if(request.action==RailwaySignalUi.Action.REMOVE) {
            SignalSystem.Signal signal=network.signals().get(request.signalId);
            if(signal==null||!signal.rail.equals(n.name))throw new IllegalArgumentException("That signal no longer belongs to this track.");
            Item original=null;
            for(Item item:Items.getAllItems())if(WurmRailWorld.isSignal(item)&&item.getData()==n.markerId
                    &&(item.getAuxData()&7)==signal.approachDirection) { original=item; break; }
            Item recovered=null; network.signals().remove(signal.id);
            try {
                recovered=RailwayTrackMaterials.recover(p,original==null?50:original.getQualityLevel()); save();
            }catch(Exception failure) {
                network.signals().add(signal.id,signal.rail,signal.approachDirection,signal.itemId);
                if(recovered!=null)Items.destroyItem(recovered.getWurmId());
                try { save(); }catch(Exception rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            tell(p,"Signal removed; its crafted section was recovered.");
        } else throw new IllegalArgumentException("Choose a signal configuration action.");
        refreshSignalVisuals(true); RailwaySignalUi.show(p,n.markerId,signalApi);
    }
    private String signalDescription(SignalSystem.Signal signal) {
        SignalSystem.Status status=network.signals().status(signal.id);
        return "Signal "+signal.id+" on "+signal.rail+", approach "+COMPASS[signal.approachDirection]+": "+status.aspect
                +(status.ownerTrain==null?"":" for "+status.ownerTrain)+". "+status.reason
                +(status.reservation==null?"":" "+reservationDescription(status.reservation));
    }
    private static String reservationDescription(SignalSystem.Reservation reservation) {
        return "Reservation "+reservation.id+" ("+reservation.state+"): "+String.join(" > ",reservation.path)
                +"; route junctions "+reservation.junctionAlignments+"; retained physical locks "+reservation.junctionLocks()+"; created #"+reservation.createdSequence+".";
    }
    private Path waterUnloadFile(){return stateFile.resolveSibling("waterunload.properties");}
    /** Legacy definitions are read solely to retire their service holds safely. */
    private void loadDeprecatedWaterFillStops() {
        try {waterFillStops=WaterFillStops.load(stateFile.resolveSibling("waterfill.properties"));}
        catch(Exception invalid) {
            waterFillStops=new WaterFillStops();
            LOG.log(Level.WARNING,"Ignoring deprecated waterfill.properties; preserve it for inspection. No direct water filling is enabled.",invalid);
        }
        deprecatedWaterFillRails.clear();deprecatedWaterFillRails.addAll(waterFillStops.stops.keySet());
        // A damaged obsolete file must not leave a labelled old station active.
        for(Node rail:network.nodes.values()) {
            Item marker=Items.getItemOptional(rail.markerId).orElse(null);
            if(marker!=null&&(marker.getName().startsWith("Water: ")||marker.getName().startsWith("Deprecated water fill")))
                deprecatedWaterFillRails.add(rail.name);
        }
    }
    private boolean isDeprecatedWaterFillRail(String rail) {
        if(!deprecatedWaterFillRails.contains(rail)||freightStops.get(rail)!=null||waterUnloadStops.get(rail)!=null)return false;
        Node node=network.nodes.get(rail);Item marker=node==null?null:Items.getItemOptional(node.markerId).orElse(null);
        // An explicit named-stop replacement also retires the obsolete identity.
        return marker==null||!marker.getName().startsWith("Station: ");
    }
    private void retireDeprecatedWaterFillStops() {
        for(String key:deprecatedWaterFillRails)if(isDeprecatedWaterFillRail(key)) {
            Node rail=network.nodes.get(key);if(rail==null)continue;
            // Keep graph nodes, ordinary station flags and itineraries intact.
            // Existing visits become harmless dwell/waypoints, with no producer.
            Item marker=Items.getItemOptional(rail.markerId).orElse(null);
            if(marker!=null&&marker.getTemplateId()==WurmRailWorld.trackTemplateId) {
                marker.setName("Deprecated water fill (inactive)");marker.updateIfGroundItem();
            }
            for(Train train:network.trains.values())if(key.equals(train.serviceStopRail))
                network.releaseRouteServiceStop(train.name,key,train.serviceStopSerial);
        }
        for(Train train:network.trains.values())if(train.serviceStopRail!=null
                &&freightStops.get(train.serviceStopRail)==null&&waterUnloadStops.get(train.serviceStopRail)==null)
            network.releaseRouteServiceStop(train.name,train.serviceStopRail,train.serviceStopSerial);
    }
    private RailwayWaterUnloadUi.View waterUnloadSnapshot(Creature actor,long markerId) throws Exception {
        Node rail=freightNode(actor,markerId);WaterUnloadStops.Stop stop=waterUnloadStops.get(rail.name);
        long owner=stop==null?actor.getWurmId():stop.ownerId;
        boolean edit=(actor.getPower()>=2||allowed(builders,actor.getName()))&&(actor.getPower()>=2||owner==actor.getWurmId());
        List<RailwayWaterUnloadUi.StorageChoice> tanks=new ArrayList<RailwayWaterUnloadUi.StorageChoice>();
        for(Item tank:RailwayWaterUnloadWorld.nearbyStorage(actor,rail,owner))tanks.add(new RailwayWaterUnloadUi.StorageChoice(tank.getWurmId(),tank.getName()+" ["+tank.getWurmId()+"]"));
        String status=stop==null?"Choose an Industrial Water Tank and name, save, then add this stop to a train route.":
                waterUnloadService==null?"Ready for a scheduled train.":waterUnloadService.statusForRail(rail.name);
        if(stop!=null){String reason=RailwayWaterUnloadWorld.storageProblem(rail,Items.getItemOptional(stop.storageId).orElse(null),owner);if(reason!=null)status="Unavailable: "+reason;}
        return new RailwayWaterUnloadUi.View(rail.name,rail.markerId,stop==null?-1:stop.revision,stop==null?"":stop.name,
                stop==null?WaterUnloadStops.Operation.UNLOAD:stop.operation,stop==null?0:stop.storageId,stop==null?waterUnloadLitresPerSecond:stop.rateLitresPerSecond,status,tanks,edit);
    }
    private void applyWaterUnloadRequest(Creature actor,long markerId,RailwayWaterUnloadUi.Request request) throws Exception {
        if(request==null)throw new IllegalArgumentException("Choose a Water freight stop action.");
        Node rail=freightNode(actor,markerId);if(request.action==RailwayWaterUnloadUi.Action.REFRESH)return;
        WaterUnloadStops.Stop previous=waterUnloadStops.get(rail.name);
        if(!rail.name.equals(request.expectedRail)||rail.markerId!=request.expectedMarkerId||request.revision!=(previous==null?-1:previous.revision))
            throw new IllegalArgumentException("This Water freight stop changed. Refresh its controls.");
        requireBuilder(actor);requireBuildContext(actor,rail.tileX,rail.tileY,rail.layer);
        if(previous!=null&&actor.getPower()<2&&previous.ownerId!=actor.getWurmId())throw new IllegalArgumentException("Only this stop's owner or a GM may change it.");
        WaterUnloadStops before=waterUnloadStops.copy();boolean wasStation=rail.station;double dwell=rail.dwellSeconds;
        Item marker=Items.getItem(rail.markerId);String oldName=marker.getName();
        if(request.action==RailwayWaterUnloadUi.Action.REMOVE) {
            if(previous==null)throw new IllegalArgumentException("No Water freight stop is configured here.");
            for(Train train:network.trains.values())if(train.route.contains(rail.name))throw new IllegalArgumentException("Remove this stop from "+trainLabel(train)+"'s route first.");
        } else if(request.action==RailwayWaterUnloadUi.Action.SAVE) {
            if(rail.links.size()>2)throw new IllegalArgumentException("Place water freight stops away from junction points.");
            if(freightStops.get(rail.name)!=null)throw new IllegalArgumentException("Use separate rail tiles for solid and liquid freight.");
            if(previous==null&&rail.station&&!isDeprecatedWaterFillRail(rail.name))throw new IllegalArgumentException("Remove the named stop before configuring a dedicated water freight stop.");
            for(Platform platform:passengerStations.platforms.values())if(platform.rail.equals(rail.name))throw new IllegalArgumentException("Use a separate rail tile for passenger stations and liquid freight.");
            long owner=previous==null?actor.getWurmId():previous.ownerId;Item tank=Items.getItemOptional(request.storageId).orElse(null);
            RailwayWaterUnloadWorld.validateStorage(actor,rail,tank,owner);
            waterUnloadStops.put(new WaterUnloadStops.Stop(rail.name,rail.markerId,owner,waterUnloadStops.nextRevision(),request.name,request.operation,request.storageId,request.rateLitresPerSecond));
        } else throw new IllegalArgumentException("Choose a water freight configuration action.");
        try {
            if(request.action==RailwayWaterUnloadUi.Action.REMOVE){waterUnloadStops.remove(rail.name);network.setStation(rail.name,false,5);marker.setName("rail "+rail.name);}
            else {network.setStation(rail.name,true,1);marker.setName(waterUnloadItemName(waterUnloadStops.get(rail.name)));}
            waterUnloadStops.save(waterUnloadFile());trackVisualsDirty=true;save();marker.updateIfGroundItem();
        }catch(Exception failure) {
            waterUnloadStops=before;rail.station=wasStation;rail.dwellSeconds=dwell;marker.setName(oldName);trackVisualsDirty=true;
            try{waterUnloadStops.save(waterUnloadFile());save();}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;
        }
        tell(actor,request.action==RailwayWaterUnloadUi.Action.REMOVE?"Water freight stop removed.":"Water freight stop saved. Add it to the train's normal route.");
    }
    private static String waterUnloadItemName(WaterUnloadStops.Stop stop){String label=(stop.operation==WaterUnloadStops.Operation.LOAD?"Load water: ":"Unload water: ")+stop.name;return label.length()>39?label.substring(0,39):label;}
    private void refreshWaterUnloadMarkers() {
        for(WaterUnloadStops.Stop stop:waterUnloadStops.stops.values()) {
            Node rail=network.nodes.get(stop.rail);if(rail==null||rail.markerId!=stop.markerId||!trackMarkerPresent(rail))continue;
            if(freightStops.get(rail.name)!=null)throw new IllegalArgumentException("Overlapping logistics stop definitions at "+rail.name);
            if(!rail.station){network.setStation(rail.name,true,1);trackVisualsDirty=true;}
            Item marker=Items.getItemOptional(stop.markerId).orElse(null);
            if(marker!=null&&!marker.getName().equals(waterUnloadItemName(stop))){marker.setName(waterUnloadItemName(stop));marker.updateIfGroundItem();}
        }
        if(trackVisualsDirty)refreshTrackVisuals();
    }
    private Path freightFile() { return stateFile.resolveSibling("freight.properties"); }
    private Node freightNode(Creature actor,long markerId) throws Exception {
        Node rail=signalNode(actor,markerId);
        if(rail.markerId!=markerId)throw new IllegalArgumentException("Choose the track itself to configure a freight stop.");
        return rail;
    }
    private RailwayFreightUi.View freightSnapshot(Creature actor,long markerId) throws Exception {
        Node rail=freightNode(actor,markerId);Stop stop=freightStops.get(rail.name);
        long owner=stop==null?actor.getWurmId():stop.ownerId;
        boolean edit=(actor.getPower()>=2||allowed(builders,actor.getName()))
                &&(actor.getPower()>=2||owner==actor.getWurmId());
        List<RailwayFreightUi.StorageChoice> storage=new ArrayList<RailwayFreightUi.StorageChoice>();
        for(Item item:RailwayFreightWorld.nearbyStorage(actor,rail,owner)) {
            if(storage.size()>=512)break;
            storage.add(new RailwayFreightUi.StorageChoice(item.getWurmId(),item.getName()+" ["+item.getWurmId()+"]"));
        }
        String status=stop==null?"Choose nearby unlocked storage, an operation and a name, then save. Add this stop to a train route.":
                freightService==null?"Ready for a scheduled train.":freightService.statusForRail(rail.name);
        if(status.isEmpty())status="Ready for a scheduled train.";
        if(stop!=null) {
            String unavailable=RailwayFreightWorld.storageProblem(rail,Items.getItemOptional(stop.storageId).orElse(null),owner);
            if(unavailable!=null)status="Unavailable: "+unavailable;
        }
        return new RailwayFreightUi.View(rail.name,rail.markerId,stop==null?-1:stop.revision,stop==null?"":stop.name,
                stop==null?FreightStops.Operation.LOAD:stop.operation,stop==null?-10L:stop.storageId,
                stop==null?0:stop.templateId,stop==null?freightTransferKgPerSecond:stop.rateKgPerSecond,status,storage,edit);
    }
    private void applyFreightRequest(Creature actor,long markerId,RailwayFreightUi.Request request) throws Exception {
        if(request==null)throw new IllegalArgumentException("Choose a freight action.");
        Node rail=freightNode(actor,markerId);
        if(request.action==RailwayFreightUi.Action.REFRESH)return;
        if(waterUnloadStops.get(rail.name)!=null)throw new IllegalArgumentException("Use separate rail tiles for solid and liquid freight.");
        Stop previous=freightStops.get(rail.name);
        if(!rail.name.equals(request.expectedRail)||rail.markerId!=request.expectedMarkerId
                ||request.revision!=(previous==null?-1:previous.revision))
            throw new IllegalArgumentException("This freight stop changed. Refresh the window.");
        requireBuilder(actor);requireBuildContext(actor,rail.tileX,rail.tileY,rail.layer);
        if(previous!=null&&actor.getPower()<2&&previous.ownerId!=actor.getWurmId())
            throw new IllegalArgumentException("Only this freight stop's owner or a GM may change it.");
        FreightStops before=freightStops.copy();boolean wasStation=rail.station;double dwell=rail.dwellSeconds;
        Item marker=Items.getItem(rail.markerId);String oldName=marker.getName();
        if(request.action==RailwayFreightUi.Action.REMOVE) {
            if(previous==null)throw new IllegalArgumentException("No freight stop is configured here.");
            for(Train train:network.trains.values())if(train.route.contains(rail.name))
                throw new IllegalArgumentException("Remove this stop from "+trainLabel(train)+"'s route first.");
        } else if(request.action==RailwayFreightUi.Action.SAVE) {
            if(rail.links.size()>2)throw new IllegalArgumentException("Place freight stops on a straight, bend or terminal rail, away from junction points.");
            if(previous==null&&rail.station&&!isDeprecatedWaterFillRail(rail.name))throw new IllegalArgumentException("Remove the named train stop here before configuring a dedicated freight stop.");
            for(Platform platform:passengerStations.platforms.values())if(platform.rail.equals(rail.name))
                throw new IllegalArgumentException("Use a separate rail tile for freight and passenger stations.");
            long owner=previous==null?actor.getWurmId():previous.ownerId;
            Item storage=Items.getItem(request.storageId);
            RailwayFreightWorld.validateStorage(actor,rail,storage,owner);
            if(request.templateId>0)com.wurmonline.server.items.ItemTemplateFactory.getInstance().getTemplate(request.templateId);
            Stop replacement=new Stop(rail.name,rail.markerId,owner,freightStops.nextRevision(),
                    request.name,request.operation,storage.getWurmId(),request.templateId,request.rateKgPerSecond);
            freightStops.put(replacement);
        } else throw new IllegalArgumentException("Choose a freight configuration action.");
        try {
            if(request.action==RailwayFreightUi.Action.REMOVE) {
                freightStops.remove(rail.name);network.setStation(rail.name,false,5);marker.setName("rail "+rail.name);
            } else {
                network.setStation(rail.name,true,1);marker.setName(freightItemName(freightStops.get(rail.name).name));
            }
            freightStops.save(freightFile());trackVisualsDirty=true;save();marker.updateIfGroundItem();
        } catch(Exception failure) {
            freightStops=before;rail.station=wasStation;rail.dwellSeconds=dwell;marker.setName(oldName);trackVisualsDirty=true;
            try { freightStops.save(freightFile());save(); }catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        tell(actor,request.action==RailwayFreightUi.Action.REMOVE?"Freight stop removed.":"Freight stop saved. Add it to the train's normal route.");
    }
    private static String freightItemName(String name) {
        String value="Freight: "+name;return value.length()>39?value.substring(0,39):value;
    }
    private void refreshFreightMarkers() {
        for(Stop stop:freightStops.stops.values()) {
            Node rail=network.nodes.get(stop.rail);
            if(rail==null||rail.markerId!=stop.markerId||!trackMarkerPresent(rail))continue;
            if(!rail.station){network.setStation(rail.name,true,1);trackVisualsDirty=true;}
            Item marker=Items.getItemOptional(stop.markerId).orElse(null);
            if(marker!=null&&!marker.getName().equals(freightItemName(stop.name))) {
                marker.setName(freightItemName(stop.name));marker.updateIfGroundItem();
            }
        }
        if(trackVisualsDirty)refreshTrackVisuals();
    }
    private Path passengerFile() { return stateFile.resolveSibling("passengers.properties"); }
    private Path servicesFile() { return stateFile.resolveSibling("services.properties"); }
    private void savePassengers() throws Exception {
        passengerStations.save(passengerFile()); if(passengerService!=null)passengerService.saved();
    }
    private static String stationItemName(String name) {
        String value="Station: "+name;
        return value.length()>39?value.substring(0,39):value;
    }
    private void refreshStationMarkers() {
        for(Platform p:passengerStations.platforms.values())if(platformPresent(p)) {
            Item marker=Items.getItemOptional(p.itemId).orElse(null);
            String name=stationItemName(p.name);
            if(marker!=null&&!name.equals(marker.getName())) {
                marker.setName(name); marker.updateIfGroundItem();
            }
        }
    }
    private boolean platformPresent(Platform p) {
        Node rail=network.nodes.get(p.rail); Item marker=Items.getItemOptional(p.itemId).orElse(null);
        return rail!=null&&rail.layer==p.layer&&Math.abs(rail.tileX-p.x)+Math.abs(rail.tileY-p.y)==1
                &&WurmRailWorld.isPassengerPlatform(marker)
                &&marker.getParentId()==-10L&&marker.getOwnerId()==-10L&&!marker.isBanked()&&!marker.isMailed()&&!marker.isTraded()
                &&marker.getTileX()==p.x&&marker.getTileY()==p.y
                &&marker.isOnSurface()==(p.layer==0)&&marker.getFloorLevel()==0&&marker.getBridgeId()==-10L;
    }
    private void checkPlatformReach(Creature p,Platform platform) {
        if(layer(p)!=platform.layer||p.getFloorLevel()!=0||p.getBridgeId()!=-10L
                ||distance(p.getPosX(),p.getPosY(),platform.worldX(),platform.worldY())>8)
            throw new IllegalArgumentException("Stand within eight metres of this station on the same ground level.");
    }
    private void requirePlatformManager(Creature p,Platform platform) {
        checkPlatformReach(p,platform); requireBuilder(p);
        if(p.getPower()<2&&p.getWurmId()!=platform.ownerId) throw new IllegalArgumentException("Only this station's builder or a GM may change it.");
        validateBuildTile(p,platform.x,platform.y);
    }
    private Set<String> connectedRails(String origin) {
        Set<String> seen=new LinkedHashSet<String>(); ArrayDeque<String> pending=new ArrayDeque<String>();
        if(origin!=null&&network.nodes.containsKey(origin))pending.add(origin);
        while(!pending.isEmpty()) { String key=pending.removeFirst(); if(seen.add(key)) for(String adjacent:node(key).links)if(!seen.contains(adjacent))pending.addLast(adjacent); }
        return seen;
    }
    private String passengerOrigin(Creature p,Platform platform,Train train) {
        if(train!=null) for(Pose pose:train.poses)if(pose.carId==p.getVehicle()) {
            if(pose.railName!=null&&network.nodes.containsKey(pose.railName))return pose.railName;
            Node n=nodeAt((int)(pose.x/4),(int)(pose.y/4),pose.layer); if(n!=null)return n.name;
        }
        return platform==null?null:platform.rail;
    }
    private RailwayPassengerUi.View passengerSnapshot(Creature p,long id) throws Exception {
        Platform platform=id>0?passengerStations.get(id):null;
        if(platform!=null)checkPlatformReach(p,platform);
        Train train=findTrain(p.getVehicle());
        if(platform==null&&train==null) throw new IllegalArgumentException("Board a rail vehicle first, or choose your destination from a station.");
        Set<String> reached=connectedRails(passengerOrigin(p,platform,train));
        List<RailwayPassengerUi.Choice> choices=new ArrayList<RailwayPassengerUi.Choice>();
        for(Platform target:passengerStations.platforms.values()) if(reached.contains(target.rail)&&platformPresent(target)&&choices.size()<512)
            choices.add(new RailwayPassengerUi.Choice(target.itemId,target.name));
        boolean manager=false;
        if(platform!=null)try { requirePlatformManager(p,platform); manager=true; } catch(Exception denied) { }
        int available=-1;
        if(train!=null) { available=0; for(long car:train.carIds) {
            Item item=Items.getItemOptional(car).orElse(null); Vehicle vehicle=item==null?null:Vehicles.getVehicle(item);
            if(vehicle!=null)for(Seat seat:vehicle.seats)if(seat.type==Seat.TYPE_PASSENGER&&!seat.isOccupied())available++;
        } }
        String status=platform!=null&&!platformPresent(platform)?"This station model is missing or misplaced. Its builder can remove and rebuild it.":
                "Stops are served when this train passes them. Your destination does not change the train's route.";
        if(platform!=null&&platformPresent(platform)) {
            if(passengerService.recentlyDropped(p.getWurmId(),platform))
                status="Recently dropped off here. Leave this station tile before waiting to board again.";
            else if(passengerService.isWaiting(p.getWurmId(),platform))
                status="Waiting for train at "+platform.name+". Your waiting badge is visible in the status bar. Step off this tile to stop waiting.";
            else status="Stand on this station tile to wait for a train. Arriving passengers leave before waiting passengers board.";
        }
        return new RailwayPassengerUi.View(id,platform==null?0:platform.revision,train==null?-10L:train.carIds.get(0),
                platform==null?null:RailwayPassengerUi.Mode.valueOf(platform.mode.name()),
                platform==null?"Your passenger journey":platform.name,
                platform==null?trainLabel(train):network.nodes.containsKey(platform.rail)?railLabel(node(platform.rail)):"Missing track",
                status,manager,passengerStations.destination(p.getWurmId()),platform==null?0:passengerService.waitingCount(platform),available,choices);
    }
    private RailwayPassengerUi.BuildView passengerBuildSnapshot(Creature p,int x,int y,int z) {
        requireBuildContext(p,x,y,z);
        if(nodeAt(x,y,z)!=null||passengerStations.at(x,y,z)!=null) throw new IllegalArgumentException("Choose an empty ground tile beside the track.");
        String blocked=RailwayPassengerWorld.validatePlatformTile(p,x,y,z); if(blocked!=null)throw new IllegalArgumentException(blocked);
        List<RailwayPassengerUi.RailChoice> rails=new ArrayList<RailwayPassengerUi.RailChoice>();
        for(int direction:new int[]{0,2,4,6}) {
            Node n=nodeAt(x+DIRECTIONS[direction][0],y+DIRECTIONS[direction][1],z);
            if(n!=null)rails.add(new RailwayPassengerUi.RailChoice(n.name,COMPASS[direction]+" - "+railLabel(n)));
        }
        return new RailwayPassengerUi.BuildView("Station tile "+x+", "+y,rails,RailwayPassengerUi.Mode.STATION,RailwayTrackMaterials.count(p),p.getPower()>=2);
    }
    private void buildPassengerPlatform(Creature p,int x,int y,int z,RailwayPassengerUi.BuildRequest request) throws Exception {
        if(request.action==RailwayPassengerUi.BuildAction.CLOSE)return;
        if(request.action==RailwayPassengerUi.BuildAction.REFRESH) { RailwayPassengerUi.showBuilder(p,x,y,z,passengerBuilderApi); return; }
        passengerBuildSnapshot(p,x,y,z);
        if(passengerStations.platforms.size()>=maxNodes)throw new IllegalArgumentException("The station limit has been reached.");
        Node rail=node(request.trackKey);
        if(freightStops.get(rail.name)!=null)throw new IllegalArgumentException("Use a separate rail tile for passenger stations and freight stops.");
        if(rail.layer!=z||Math.abs(rail.tileX-x)+Math.abs(rail.tileY-y)!=1)throw new IllegalArgumentException("Choose the track immediately north, east, south or west of this tile.");
        validateBuildTile(p,rail.tileX,rail.tileY);
        Item track=Items.getItemOptional(rail.markerId).orElse(null);
        if(track==null||track.getTemplateId()!=WurmRailWorld.trackTemplateId)throw new IllegalArgumentException("Repair the linked track model first.");
        String name=PassengerStations.label(request.name);
        RailwayTrackMaterials.Payment payment=RailwayTrackMaterials.reserve(p,1);
        PassengerStations previous=passengerStations.copy(); Item marker=null;
        try {
            marker=WurmRailWorld.spawnStation(p,x,y,z);
            marker.setName(stationItemName(name));
            marker.setQualityLevel(payment.qualityAt(0));
            passengerStations.add(new Platform(marker.getWurmId(),p.getWurmId(),1,x,y,z,rail.name,name,PassengerStations.Mode.STATION));
            payment.consume(); savePassengers();
        } catch(Exception failure) {
            passengerStations=previous;
            if(marker!=null)Items.destroyItem(marker.getWurmId());
            try { payment.refund(); } catch(Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
        passengerService.refreshPlayers();
        RailwayPassengerUi.show(p,marker.getWurmId(),passengerApi);
    }
    private void applyPassengerRequest(Creature p,long id,RailwayPassengerUi.Request request) throws Exception {
        if(request.action==RailwayPassengerUi.Action.CLOSE)return;
        RailwayPassengerUi.View view=passengerSnapshot(p,id);
        if(request.action==RailwayPassengerUi.Action.REFRESH) { RailwayPassengerUi.show(p,id,passengerApi); return; }
        if(request.expectedPlatformItemId!=id||request.revision!=view.revision||request.trainEngineId!=view.trainEngineId)
            throw new IllegalArgumentException("This station or train changed. Refresh your passenger window.");
        Platform platform=id>0?passengerStations.get(id):null;
        PassengerStations previous=passengerStations.copy(); Item marker=platform==null?null:Items.getItemOptional(id).orElse(null);
        String oldName=marker==null?null:marker.getName();
        switch(request.action) {
            case CHOOSE_DESTINATION:
                boolean offered=false; for(RailwayPassengerUi.Choice choice:view.destinations)if(choice.id==request.destinationId)offered=true;
                if(!offered)throw new IllegalArgumentException("That station is no longer connected to this stop or train.");
                passengerStations.choose(p.getWurmId(),request.destinationId); break;
            case CANCEL_DESTINATION: passengerStations.choose(p.getWurmId(),-10L); break;
            case RENAME:
                requirePlatformManager(p,platform);
                if(marker==null)throw new IllegalArgumentException("Remove and rebuild the missing station model.");
                Platform renamed=passengerStations.rename(id,request.name);
                marker.setName(stationItemName(renamed.name)); marker.updateIfGroundItem(); break;
            case REMOVE:
                requirePlatformManager(p,platform); removePassengerPlatform(p,platform); return;
            default: throw new IllegalArgumentException("Choose a passenger action.");
        }
        try { savePassengers(); }
        catch(Exception failed) { passengerStations=previous; if(marker!=null&&oldName!=null)marker.setName(oldName); throw failed; }
        RailwayPassengerUi.show(p,id,passengerApi);
    }
    private void removePassengerPlatform(Creature p,Platform platform) throws Exception {
        requirePlatformManager(p,platform);
        Node linked=network.nodes.get(platform.rail); if(linked!=null)requireStopCanBeRemoved(linked,platform.itemId,false);
        PassengerStations previous=passengerStations.copy(); Item marker=Items.getItemOptional(platform.itemId).orElse(null),recovered=null;
        float quality=marker==null?50:marker.getQualityLevel();
        try {
            passengerStations.remove(platform.itemId);
            recovered=RailwayTrackMaterials.recover(p,quality);
            savePassengers();
            if(marker!=null&&WurmRailWorld.isPassengerPlatform(marker))Items.destroyItem(marker.getWurmId());
        } catch(Exception failed) {
            passengerStations=previous;
            if(recovered!=null)Items.destroyItem(recovered.getWurmId());
            try { savePassengers(); } catch(Exception rollback) { failed.addSuppressed(rollback); }
            throw failed;
        }
        passengerService.refreshPlayers();
        tell(p,"Station removed; its section was recovered. Destinations for this stop were cleared.");
    }
    private static String friendlyName(String text) {
        String result=text==null?"":text.trim();
        if(result.isEmpty()||result.length()>40) throw new IllegalArgumentException("Choose a name from 1 to 40 characters.");
        for(int i=0;i<result.length();i++) if(Character.isISOControl(result.charAt(i))) throw new IllegalArgumentException("Names cannot contain control characters.");
        return result;
    }
}
