import javassist.Loader;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;

/** Loads actual game and modloader bytecode without launching a server or opening network sockets. */
public final class IntegrationSmoke {
    public static void main(String[] args) {
        try {
            Loader loader = HookManager.getInstance().getLoader();
            loader.delegateLoadingOf("javafx.");
            loader.delegateLoadingOf("com.sun.");
            loader.delegateLoadingOf("org.controlsfx.");
            loader.delegateLoadingOf("impl.org.controlsfx");
            loader.delegateLoadingOf("com.mysql.");
            loader.delegateLoadingOf("org.sqlite.");
            loader.delegateLoadingOf("org.gotti.wurmunlimited.modloader.classhooks.");
            loader.delegateLoadingOf("javassist.");
            Thread.currentThread().setContextClassLoader(loader);
            // Keep game-dependent Checks unverified until genuine discovery and all
            // modloader bytecode transformations have completed.
            loader.run("IntegrationSmoke$Startup", args);
            System.exit(0); // Wurm may create helper executors while initializing templates.
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Mirrors DelegatedLauncher before loading any game-dependent test code. */
    public static final class Startup {
        public static org.gotti.wurmunlimited.modloader.server.ServerHook hooks;

        public static void main(String[] args) throws Throwable {
            System.out.println("Runtime Java " + System.getProperty("java.version"));
            // Install fixture-only fault injection before callbacks freeze Item.
            // This edits bytecode without loading native game classes.
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.Item")
                    .getMethod("insertItem", "(Lcom/wurmonline/server/items/Item;ZZZ)Z")
                    .insertBefore("{ if (TankCarCapacityRuntimeSmoke.rejectInsertion(this,$1)||SteamWaterPumpNativeRuntimeSmoke.rejectInsertion(this,$1)||SteamDrillMiningRuntimeSmoke.rejectStage(this,$1)||WaterFillNativeRuntimeSmoke.rejectInsertion(this,$1)||IndustrialWaterRuntimeSmoke.rejectInsertion(this,$1)||SteamYarderNativeRuntimeSmoke.rejectInsertion(this,$1)) return false; }");
            javassist.CtMethod fixtureWaterWeight=HookManager.getInstance().getClassPool()
                    .get("com.wurmonline.server.items.DbItem").getMethod("setWeight", "(IZZ)Z");
            fixtureWaterWeight.insertBefore("{ if (SteamWaterPumpNativeRuntimeSmoke.rejectGrowth(this,$1)||WaterFillNativeRuntimeSmoke.rejectGrowth(this,$1)||IndustrialWaterRuntimeSmoke.rejectGrowth(this,$1)) return false; }");
            fixtureWaterWeight.insertAfter("{ SteamWaterPumpNativeRuntimeSmoke.afterGrowth(this,$1);WaterFillNativeRuntimeSmoke.afterGrowth(this,$1);IndustrialWaterRuntimeSmoke.afterGrowth(this,$1); }");
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.items.DbItem")
                    .getMethod("setQualityLevel", "(F)Z")
                    .insertAfter("{ IndustrialWaterRuntimeSmoke.afterQuality(this,$1); }");
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.Server")
                    .getMethod("setWorldResource", "(III)V")
                    .insertAfter("{ SteamYarderNativeRuntimeSmoke.afterTreeWrite($1,$2,$3); }");
            final java.util.List<String> earlyLoads = new java.util.ArrayList<String>();
            java.util.logging.Logger earlyLogger = java.util.logging.Logger.getLogger(
                    "org.gotti.wurmunlimited.modloader.EarlyLoadingChecker");
            java.util.logging.Handler earlyLoadWatcher = new java.util.logging.Handler() {
                @Override public void publish(java.util.logging.LogRecord record) {
                    if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                            && record.getMessage().startsWith("Mod wurmrail loaded server class "))
                        earlyLoads.add(record.getMessage());
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            earlyLogger.addHandler(earlyLoadWatcher);
            org.gotti.wurmunlimited.modloader.ModLoader modLoader =
                    new org.gotti.wurmunlimited.modloader.ModLoader();
            java.util.List<? extends org.gotti.wurmunlimited.modloader.interfaces.ModEntry<
                    org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod>> mods;
            try { mods = modLoader.loadModsFromModDir(java.nio.file.Paths.get("mods")); }
            finally { earlyLogger.removeHandler(earlyLoadWatcher); }
            if (mods.size() != 1 || !"wurmrail".equals(mods.get(0).getName()))
                throw new AssertionError("isolated fixture must discover exactly WurmRail");
            if (!earlyLoads.isEmpty())
                throw new AssertionError("WurmRail loaded game classes before launcher hooks: " + earlyLoads);
            System.out.println("PASS genuine mod discovery, construction, configuration, ModComm, preInit and init");
            hooks = org.gotti.wurmunlimited.modloader.server.ServerHook.createServerHook();
            hooks.addMods(mods);
            hooks.addVersionHandler(modLoader.getVersion(), modLoader.getGameVersion(), mods);
            HookManager.getInstance().initCallbacks();
            System.out.println("PASS real server lifecycle and communication bytecode hooks");
            // Capture moving-item packets at the native communicator boundary.
            // This isolated fixture never has a client socket.
            javassist.CtClass fixtureCommunicator = HookManager.getInstance().getClassPool()
                    .get("com.wurmonline.server.creatures.CreatureCommunicator");
            fixtureCommunicator.getDeclaredMethod("sendNormalServerMessage").setBody(
                    "{ ScheduledRuntimeSmoke.captureMessage($1); }");
            fixtureCommunicator.getDeclaredMethod("sendMoveMovingItemAndSetZ").setBody(
                    "{ IntegrationSmoke$Checks.captureMovement($1, $2, $3, $4, $5); }");
            fixtureCommunicator.getDeclaredMethod("sendMoveMovingItem").setBody(
                    "{ IntegrationSmoke$Checks.captureMovement($1, $2, $3, 5.0f, $4); }");
            fixtureCommunicator.getDeclaredMethod("sendNewMovingItem").setBody(
                    "{ IntegrationSmoke$Checks.captureMovingSpawn($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14); }");
            fixtureCommunicator.getDeclaredMethod("sendItem").setBody(
                    "{ IntegrationSmoke$Checks.captureGroundSpawn($1,$2,$3); }");
            fixtureCommunicator.getDeclaredMethod("sendRemoveItem").setBody(
                    "{ IntegrationSmoke$Checks.captureGroundRemoval($1); }");
            fixtureCommunicator.getDeclaredMethod("sendAvailableActions")
                    .setBody("{ RailwayBridgeRuntimeSmoke.captureMenu($2); }");
            // CreatureCommunicator normally auto-acknowledges NPC teleports. The
            // Player fixtures have no socket or real server port queue; preserve
            // native seat/position work but stop at this outgoing packet boundary.
            fixtureCommunicator.getMethod("sendTeleport", "(Z)V").setBody("{ }");
            fixtureCommunicator.getMethod("sendTeleport", "(ZZB)V").setBody("{ }");
            // UI fixtures have no socket. Preserve native Questions and BML generation,
            // stopping only at the communicator's outgoing window boundary.
            javassist.ClassPool fixturePool = HookManager.getInstance().getClassPool();
            fixturePool.get("com.wurmonline.server.creatures.Communicator")
                    .getMethod("sendBml", "(IIZZLjava/lang/String;IIILjava/lang/String;)V")
                    .setBody("{ RailwayUiSmoke.captureSurveyBml($5,$9); }");
            fixturePool.get("com.wurmonline.server.players.PlayerCommunicatorQueued")
                    .getDeclaredMethod("sendBml").insertBefore("{ RailwayUiSmoke.captureSurveyBml($5,$9); }");
            fixturePool.get("com.wurmonline.server.players.PlayerCommunicatorQueued")
                    .getMethod("sendAddEffect","(JSFFFB)V")
                    .insertBefore("{ RailwayUiSmoke.captureSurveyMarker($1,$2,$3,$4,$5,$6); }");
            fixturePool.get("com.wurmonline.server.players.PlayerCommunicatorQueued")
                    .getMethod("sendRemoveEffect","(J)V")
                    .insertBefore("{ RailwayUiSmoke.captureSurveyMarkerRemoval($1); }");
            for (javassist.CtMethod m : fixturePool.get("com.wurmonline.server.players.PlayerCommunicatorQueued")
                    .getDeclaredMethods("sendNormalServerMessage"))
                m.insertBefore("{ RailwayUiSmoke.captureSurveyMessage($1); }");
            // Synthetic actors are Creatures with real status/inventory, rather than
            // logged-in Players. Native Player registration remains unchanged.
            fixturePool.get("com.wurmonline.server.questions.Questions")
                    .getDeclaredMethod("addQuestion").insertBefore(
                            "{ if (!($1.getResponder() instanceof com.wurmonline.server.players.Player)) {"
                            + " questions.put(new Integer($1.getId()), $1); return; } }");
            // The native communicator caches Server.getInstance().getExternalIp()
            // during static initialization. Supply only that unused address without
            // constructing a server or launching Steam networking in the fixture.
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.Communicator")
                    .getClassInitializer().instrument(new javassist.expr.ExprEditor() {
                        @Override public void edit(javassist.expr.MethodCall call)
                                throws javassist.CannotCompileException {
                            if ("com.wurmonline.server.Server".equals(call.getClassName())) {
                                String name = call.getMethodName();
                                if ("getInstance".equals(name)) call.replace("{ $_ = null; }");
                                else if ("getExternalIp".equals(name))
                                    call.replace("{ $_ = new byte[] {127,0,0,1}; }");
                            }
                        }
                    });
            HookManager.getInstance().getLoader().run("IntegrationSmoke$Checks", args);
        }
    }

    /** Loaded only after the actual launcher has finished transforming game classes. */
    public static final class Checks {
        private static long capturedRotationItem;
        private static int capturedRotation;
        private static int capturedRotations;
        private static float capturedX, capturedY, capturedZ;
        private static final java.util.Map<Long, MovingAnnouncement> movingAnnouncements =
                new java.util.HashMap<Long, MovingAnnouncement>();
        private static final java.util.Map<Long, GroundAnnouncement> groundAnnouncements =
                new java.util.HashMap<Long, GroundAnnouncement>();
        private static final java.util.Map<Long, Integer> groundSpawnCounts = new java.util.HashMap<Long, Integer>();
        private static final java.util.Map<Long, Integer> groundRemovalCounts = new java.util.HashMap<Long, Integer>();

        public static void captureMovingSpawn(long id, String name, String model, float x,
                float y, float z, long bridge, float heading, byte layer, boolean onGround,
                boolean floating, boolean selectable, byte material, byte rarity) {
            movingAnnouncements.put(id, new MovingAnnouncement(name, model, x, y, z, bridge,
                    heading, layer, onGround, floating, selectable, material, rarity));
        }

        public static void captureGroundSpawn(com.wurmonline.server.items.Item item,
                long parent, boolean onGround) {
            groundAnnouncements.put(item.getWurmId(), new GroundAnnouncement(item, parent, onGround));
            groundSpawnCounts.put(item.getWurmId(),groundSpawns(item.getWurmId())+1);
        }

        public static void captureGroundRemoval(com.wurmonline.server.items.Item item) {
            groundRemovalCounts.put(item.getWurmId(),groundRemovals(item.getWurmId())+1);
        }
        public static int groundSpawns(long id) { Integer count=groundSpawnCounts.get(id); return count==null?0:count; }
        public static int groundRemovals(long id) { Integer count=groundRemovalCounts.get(id); return count==null?0:count; }
        public static String groundModel(long id) { GroundAnnouncement item=groundAnnouncements.get(id); return item==null?null:item.model; }

        private static final class MovingAnnouncement {
            final String name, model;
            final float x, y, z, heading;
            final long bridge;
            final byte layer, material, rarity;
            final boolean onGround, floating, selectable;
            MovingAnnouncement(String name, String model, float x, float y, float z, long bridge,
                    float heading, byte layer, boolean onGround, boolean floating,
                    boolean selectable, byte material, byte rarity) {
                this.name=name; this.model=model; this.x=x; this.y=y; this.z=z;
                this.bridge=bridge; this.heading=heading; this.layer=layer;
                this.onGround=onGround; this.floating=floating; this.selectable=selectable;
                this.material=material; this.rarity=rarity;
            }
        }

        private static final class GroundAnnouncement {
            final com.wurmonline.server.items.Item item;
            final long parent;
            final boolean onGround;
            final String model;
            GroundAnnouncement(com.wurmonline.server.items.Item item, long parent, boolean onGround) {
                this.item=item; this.parent=parent; this.onGround=onGround; this.model=item.getModelName();
            }
        }

        public static void captureMovement(long item, float x, float y, float z, int rotation) {
            capturedRotationItem = item;
            capturedRotation = rotation;
            capturedX = x; capturedY = y; capturedZ = z;
            capturedRotations++;
        }

        public static void main(String[] args) throws Exception {
            java.nio.file.Path isolatedWorld = java.nio.file.Paths.get(args[0]).toAbsolutePath();
            java.nio.file.Files.createDirectories(isolatedWorld.resolve("sqlite"));
            com.wurmonline.server.ServerDirInfo.setFileDBPath(isolatedWorld.toString()
                    + java.io.File.separator);
            com.wurmonline.server.Constants.dbHost = isolatedWorld.toString();
            com.wurmonline.server.Constants.meshSize = 8;
            com.wurmonline.server.DbConnector.setUseSqlite(true);
            com.wurmonline.server.Servers.localServer = new com.wurmonline.server.ServerEntry();
            com.wurmonline.server.Servers.localServer.testServer = true;
            com.wurmonline.server.Servers.loginServer = com.wurmonline.server.Servers.localServer;
            require("org.gotti.wurmunlimited.modcomm.PlayerModConnection".equals(
                    com.wurmonline.server.players.Player.class.getField("modConnection").getType().getName()),
                    "ModComm must add its field to the transformed Player before loading it");
            System.out.println("PASS ModComm Player transformation survives real WurmRail discovery");
            com.wurmonline.server.items.ItemTemplateCreator.initialiseItemTemplates();
            java.util.Set<String> registeredActions = new java.util.HashSet<String>();
            for (com.wurmonline.server.behaviours.ActionEntry entry
                    : com.wurmonline.server.behaviours.Actions.actionEntrys)
                if (entry != null) registeredActions.add(entry.getActionString());
            require(registeredActions.containsAll(java.util.Arrays.asList("Build railway", "Railway track",
                    "Train controls", "Couple rail wagon",
                    "Plan Railway Trestle", "Mark Trestle Destination", "Review Trestle Survey",
                    "Build station", "Station", "Choose station destination", "Cycle junction alignment", "Railway signals", "Freight stop", "Bulk hopper status",
                    "Steam rock drill status", "Select drill Bulk Hopper", "Select drill mining faces", "Start drill mining", "Stop drill mining",
                    "Industrial water tank status", "Water freight stop", "Clear empty liquid tank fault",
                    "Log bunk status", "Steam Yarder controls", "Start forestry", "Stop forestry",
                    "Steam Water Pump controls", "Start pumping", "Stop pumping")),
                    "all rail actions must register through the discovered mod lifecycle");
            require(!registeredActions.contains("Board rail vehicle")&&!registeredActions.contains("Open rail cargo"),
                    "native embark and Open must have no redundant railway menu actions");
            require(!registeredActions.contains("Water fill station")&&!registeredActions.contains("Water unload stop"),
                    "deprecated natural-water rail filling must not register actions");
            require(com.wurmonline.server.behaviours.Actions.actionEntrys[332]!=null
                            &&com.wurmonline.server.behaviours.Actions.actionEntrys[333]!=null
                            &&com.wurmonline.server.behaviours.Actions.actionEntrys[3]!=null,
                    "native passenger embark, disembark and Open action identities remain available");
            System.out.println("PASS all twenty-six railway context-menu actions registered, with liquid freight and pump controls; deprecated water filling absent");
            org.wurmrail.WurmRailWorld.createRecipes();
            checkTemplate(org.wurmrail.WurmRailWorld.locomotiveTemplateId);
            checkTemplate(org.wurmrail.WurmRailWorld.cartTemplateId);
            checkTemplate(org.wurmrail.RailwayTankCar.templateId);
            checkTemplate(org.wurmrail.RailwayLogCar.templateId);
            checkRecipe(org.wurmrail.RailwayTankCar.templateId,5);
            checkPassengerPlatformTemplate(org.wurmrail.WurmRailWorld.pickupTemplateId,
                    "wurmrail.pickup.v1", "model.wurmrail.platform.pickup.");
            checkPassengerPlatformTemplate(org.wurmrail.WurmRailWorld.dropoffTemplateId,
                    "wurmrail.dropoff.v1", "model.wurmrail.platform.pickup.");
            checkJunctionIndicatorTemplate();
            com.wurmonline.server.items.ItemTemplate vanillaCart = com.wurmonline.server.items.ItemTemplateFactory
                    .getInstance().getTemplate(com.wurmonline.server.items.ItemList.cartLarge);
            com.wurmonline.server.behaviours.Vehicle vanillaVehicle = com.wurmonline.server.behaviours.Vehicles
                    .createVehicle(new com.wurmonline.server.items.TempItem("vanilla cart", vanillaCart, 50.0f, "Smoke test"));
            require(vanillaVehicle.getPilotSeat() != null, "normal carts must retain native steering");
            require(vanillaVehicle.hitched.length > 0, "normal carts must retain native hitch seats");
            com.wurmonline.server.items.Item vanillaItem = new com.wurmonline.server.items.TempItem(
                    "vanilla cart model", vanillaCart, 50.0f, "Smoke test");
            require(!vanillaItem.getModelName().startsWith("model.wurmrail."),
                    "ordinary carts must keep their native artwork");
            System.out.println("PASS native steering and hitching remain available on ordinary carts");
            checkRecipe(org.wurmrail.WurmRailWorld.locomotiveTemplateId, 4);
            checkRecipe(org.wurmrail.WurmRailWorld.cartTemplateId, 4);
            com.wurmonline.server.items.ItemTemplate marker = com.wurmonline.server.items.ItemTemplateFactory
                    .getInstance().getTemplate(org.wurmrail.WurmRailWorld.trackTemplateId);
            require(marker.isNoMove, "track markers must be fixed");
            require(!marker.isTransportable(), "track markers must not be loadable into vehicles");
            com.wurmonline.server.items.Item railModelItem = new com.wurmonline.server.items.TempItem(
                    "track model test", marker, 50.0f, "Smoke test");
            require(!railModelItem.isMovingItem(), "track models must use the native ground-item renderer");
            railModelItem.setAuxData((byte)128);
            railModelItem.setData1(0);
            require("model.wurmrail.track.128.".equals(railModelItem.getModelName()),
                    "native item model hook must use unsigned eighth-direction mask");
            railModelItem.setData1(1);
            require("model.wurmrail.station.128.".equals(railModelItem.getModelName()),
                    "native station model variant");
            railModelItem.setData1(2);
            require("model.wurmrail.junction.128.".equals(railModelItem.getModelName()),
                    "native junction model variant");
            railModelItem.setAuxData((byte)255);
            require("model.wurmrail.junction.255.".equals(railModelItem.getModelName()),
                    "all eight direction bits must survive native byte storage");
            System.out.println("PASS native custom model hook, unsigned diagonal mask, station and junction variants");
            require(org.gotti.wurmunlimited.modsupport.IdFactory.getExistingIdFor("wurmrail.locomotive.v1",
                    org.gotti.wurmunlimited.modsupport.IdType.ITEMTEMPLATE)
                    == org.wurmrail.WurmRailWorld.locomotiveTemplateId, "persistent template identity");
            System.out.println("PASS real vanilla/custom templates, persistent template IDs and crafting recipes");
            com.wurmonline.server.creatures.MovementScheme.class.getMethod("movePassengers",
                    com.wurmonline.server.behaviours.Vehicle.class,
                    com.wurmonline.server.creatures.Creature.class, boolean.class);
            require(java.lang.reflect.Modifier.isStatic(com.wurmonline.server.creatures.MovementScheme.class
                    .getField("itemVehicle").getModifiers()), "native passenger item reference must be static");
            com.wurmonline.server.zones.VolaTile.class.getMethod("moveItem",
                    com.wurmonline.server.items.Item.class, float.class, float.class, float.class,
                    float.class, boolean.class, float.class);
            System.out.println("PASS native passenger and zone movement API signatures");
            Startup.hooks.fireOnServerStarted();
            Startup.hooks.fireOnServerPoll();
            Startup.hooks.fireOnServerShutdown();
            require(java.nio.file.Files.isRegularFile(isolatedWorld.resolve("wurmrail/network.properties")),
                    "root lifecycle must persist its isolated empty network");
            System.out.println("PASS start, poll and shutdown lifecycle with isolated network persistence");
            checkSyntheticWorld(isolatedWorld);
            System.out.println("INTEGRATION SMOKE PASSED (isolated synthetic world; no server socket or player session started)");
        }

        private static void checkJunctionIndicatorTemplate() throws Exception {
            int id=org.wurmrail.WurmRailWorld.junctionAlignmentTemplateId;
            require(org.gotti.wurmunlimited.modsupport.IdFactory.getExistingIdFor("wurmrail.junctionalignment.v1",
                    org.gotti.wurmunlimited.modsupport.IdType.ITEMTEMPLATE)==id,
                    "junction indicators must retain their persistent dedicated template identity");
            com.wurmonline.server.items.ItemTemplate template=com.wurmonline.server.items.ItemTemplateFactory
                    .getInstance().getTemplate(id);
            require(template.isNoMove&&!template.isTransportable(),"alignment indicators must be fixed ground scenery");
            com.wurmonline.server.items.Item item=new com.wurmonline.server.items.TempItem(
                    "junction model",template,50f,"Smoke test");
            require(!item.isMovingItem()&&com.wurmonline.server.behaviours.Vehicles.getVehicle(item)==null,
                    "alignment indicators must never create a native moving vehicle");
            for(int mask=0;mask<256;mask++) {
                item.setAuxData((byte)mask);
                int expected=Integer.bitCount(mask)==2?mask:0;
                require(("model.wurmrail.alignment."+expected+".").equals(item.getModelName()),
                        "indicator model hook must map unsigned valid pairs and safely fall back for other masks: "+mask);
            }
            System.out.println("PASS primitive indicator registry, persistent native ground template and all unsigned valid/invalid model masks");
        }

        private static void checkTemplate(int id) throws Exception {
            com.wurmonline.server.items.ItemTemplate template = com.wurmonline.server.items.ItemTemplateFactory
                    .getInstance().getTemplate(id);
            require(template.isCart, "rail car must accept native cargo");
            require(template.isNoMove, "rail car must block native pushing and dragging");
            require(!template.isTransportable(), "whole rail cars must not be loadable");
            boolean tank=org.wurmrail.TankCarRegistry.isTankCar(id);
            boolean timber=org.wurmrail.LogCarRegistry.isLogCar(id);
            require(template.getContainerVolume() == (tank?org.wurmrail.LiquidTankPolicy.MAX_VOLUME_CC:timber?org.wurmrail.LogCarPolicy.MAX_VOLUME_CC:120 * 120 * 410), "cargo volume");
            com.wurmonline.server.items.Item item = new com.wurmonline.server.items.TempItem(
                    "smoke " + template.getName(), template, 50.0f, "Smoke test");
            String model = timber?"model.wurmrail.logcar.":tank?"model.wurmrail.tankcar.":id == org.wurmrail.WurmRailWorld.locomotiveTemplateId
                    ? "model.wurmrail.locomotive." : "model.wurmrail.cart.";
            require(model.equals(item.getModelName()), "custom vehicle artwork must bypass vanilla material suffixes");
            require(item.isMovingItem(), "rail vehicles must use the native moving-item renderer");
            com.wurmonline.server.behaviours.Vehicle vehicle = com.wurmonline.server.behaviours.Vehicles
                    .createVehicle(item);
            require(vehicle != null && vehicle.seats.length == 4, "four native passenger seats");
            require(vehicle.getMaxPassengers() == 4, "native passenger capacity");
            require(vehicle.hitched.length == 0, "rail cars must have no hitch seats");
            for (com.wurmonline.server.behaviours.Seat seat : vehicle.seats)
                require(seat.type == com.wurmonline.server.behaviours.Seat.TYPE_PASSENGER,
                        "rail car must not expose a native driver seat");
            require(vehicle.getPilotSeat() == null, "native free steering must be unavailable");
            checkSeatFrames(vehicle, id == org.wurmrail.WurmRailWorld.locomotiveTemplateId,tank||timber);
            System.out.println("PASS " + template.getName() + " native vehicle creation and four passenger-only seats");
        }

        private static void checkSeatFrames(com.wurmonline.server.behaviours.Vehicle vehicle,
                boolean engine,boolean tank) {
            // The joint is the character root, not the bench/cushion height.
            double lateral = engine ? .48 : .55, height = engine ? .55 : .95;
            double first = engine ? .65 : tank?-1.45:-.70, second = engine ? 1.25 : tank?1.45:.70;
            double[][] artworkMounts = {{-lateral,first,height},{lateral,first,height},
                    {-lateral,second,height},{lateral,second,height}};
            for (int index = 0; index < artworkMounts.length; index++) {
                com.wurmonline.server.behaviours.Seat seat = vehicle.seats[index];
                double[] mount = artworkMounts[index];
                for (int heading = 0; heading < 360; heading += 45) {
                    // Actual MovementScheme.movePassengers item branch uses this
                    // different seat frame (native bytecode 1044-1139).
                    double seatAngle = Math.toRadians(180 - heading);
                    double worldX = -Math.sin(seatAngle)*seat.offx + Math.cos(seatAngle)*seat.offy;
                    double worldY = -Math.cos(seatAngle)*seat.offx - Math.sin(seatAngle)*seat.offy;
                    // Moving-item artwork exports (-lateral,height,-longitudinal),
                    // then the client applies yaw=180-heading, unlike static rails.
                    double angle = Math.toRadians(heading);
                    double expectedX = Math.cos(angle)*mount[0] - Math.sin(angle)*mount[1];
                    double expectedY = Math.sin(angle)*mount[0] + Math.cos(angle)*mount[1];
                    require(Math.abs(worldX-expectedX)<.00001 && Math.abs(worldY-expectedY)<.00001
                            && Math.abs(seat.offz-mount[2])<.00001,
                            "server passenger position must match native model mount: seat "
                                    + index + " heading " + heading);
                }
            }
        }

        private static void checkRecipe(int templateId, int requirements) throws Exception {
            com.wurmonline.server.items.AdvancedCreationEntry entry = com.wurmonline.server.items.CreationMatrix
                    .getInstance().getAdvancedCreationEntry(templateId);
            require(entry != null && entry.getRequirements().length == requirements, "complete crafting recipe");
        }

        private static void checkPassengerPlatformTemplate(int templateId,String identifier,String model) throws Exception {
            com.wurmonline.server.items.ItemTemplate template=com.wurmonline.server.items.ItemTemplateFactory
                    .getInstance().getTemplate(templateId);
            require(template.isNoMove&&!template.isTransportable(),"passenger platforms must stay fixed on their tile");
            com.wurmonline.server.items.Item platform=new com.wurmonline.server.items.TempItem(
                    "passenger platform fixture",template,50.0f,"Smoke test");
            require(!platform.isMovingItem()&&!org.wurmrail.WurmRailWorld.isRailCar(platform)
                            &&org.wurmrail.WurmRailWorld.isPassengerPlatform(platform),
                    "passenger platforms must remain ground scenery, separate from rideable rail vehicles");
            require(model.equals(platform.getModelName()),"native platform artwork must bypass vanilla material suffixes");
            require(org.gotti.wurmunlimited.modsupport.IdFactory.getExistingIdFor(identifier,
                    org.gotti.wurmunlimited.modsupport.IdType.ITEMTEMPLATE)==templateId,
                    "passenger platform template identity must survive restarts");
            System.out.println("PASS fixed passenger platform template, model mapping and persistent identity: "+identifier);
        }

        private static void checkSyntheticWorld(java.nio.file.Path isolatedWorld) throws Exception {
            int[] surface = new int[256 * 256];
            int[] cave = new int[256 * 256];
            int[] rock = new int[256 * 256];
            java.util.Arrays.fill(surface, com.wurmonline.mesh.Tiles.encode((short)50,
                    com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id, (byte)0));
            java.util.Arrays.fill(cave, com.wurmonline.mesh.Tiles.encode((short)20,
                    com.wurmonline.mesh.Tiles.Tile.TILE_CAVE.id, (byte)50));
            java.util.Arrays.fill(rock, com.wurmonline.mesh.Tiles.encode((short)40,
                    com.wurmonline.mesh.Tiles.Tile.TILE_ROCK.id, (byte)0));
            com.wurmonline.mesh.MeshIO.setAllocateDirectBuffers(false);
            com.wurmonline.server.Server.surfaceMesh = com.wurmonline.mesh.MeshIO.createMap(
                    isolatedWorld.resolve("top_layer.map").toString(), 8, surface);
            com.wurmonline.server.Server.caveMesh = com.wurmonline.mesh.MeshIO.createMap(
                    isolatedWorld.resolve("map_cave.map").toString(), 8, cave);
            com.wurmonline.server.Server.rockMesh = com.wurmonline.mesh.MeshIO.createMap(
                    isolatedWorld.resolve("rock_layer.map").toString(), 8, rock);
            require(org.wurmrail.WurmRailWorld.validateEdge(63, 10, 64, 10, 0) == null,
                    "flat dry cross-zone rail connection");
            require(org.wurmrail.WurmRailWorld.validateEdge(63, 10, 64, 11, 0) == null,
                    "flat dry diagonal rail connection");
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                require(org.wurmrail.WurmRailWorld.validateEdge(40, 40, 40 + dx, 40 + dy, 0) == null,
                        "flat native world edge in compass direction " + dx + "," + dy);
            }
            com.wurmonline.server.Server.surfaceMesh.setTile(61, 20,
                    com.wurmonline.mesh.Tiles.encode((short)50,
                            com.wurmonline.mesh.Tiles.Tile.TILE_LAVA.id, (byte)0));
            require(org.wurmrail.WurmRailWorld.validateEdge(60, 20, 61, 21, 0) != null,
                    "diagonal cannot cut the corner of unsafe side terrain");
            com.wurmonline.server.Server.surfaceMesh.setTile(61, 20,
                    com.wurmonline.mesh.Tiles.encode((short)50,
                            com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id, (byte)0));
            System.out.println("PASS all eight native world directions and blocked diagonal corner checks");
            checkSpawnAnnouncements();
            com.wurmonline.server.items.Item engine = org.wurmrail.WurmRailWorld
                    .spawnLocomotive(null, 254.0, 42.0, 0);
            com.wurmonline.server.items.Item cargo = com.wurmonline.server.items.ItemFactory
                    .createItem(com.wurmonline.server.items.ItemList.plank, 50.0f, "Smoke test");
            require(engine.insertItem(cargo), "native cargo insertion");
            org.wurmrail.WurmRailWorld.moveCar(engine.getWurmId(), 255.0, 42.0, 90.0, 0);
            org.wurmrail.WurmRailWorld.moveCar(engine.getWurmId(), 258.0, 42.0, 90.0, 0);
            org.wurmrail.WurmRailWorld.saveCar(engine.getWurmId());
            require(engine.getTileX() == 64 && Math.abs(engine.getPosX() - 258.0f) < 0.01f,
                    "native cross-zone vehicle position");
            require(engine.getZoneId() == com.wurmonline.server.zones.Zones.getZone(64, 10, true).getId(),
                    "native zone index updated");
            require(cargo.getParentId() == engine.getWurmId(), "cargo remains in the moved vehicle");
            require(Math.abs(engine.getRotation() - 90.0f) < 0.01f, "native vehicle rotation");
            checkStationaryRotations(engine);
            require(org.wurmrail.WurmRailWorld.validateEdge(63, 10, 65, 10, 0) != null,
                    "nonadjacent track connection rejected");
            com.wurmonline.server.Server.surfaceMesh.setTile(65, 10,
                    com.wurmonline.mesh.Tiles.encode((short)50,
                            com.wurmonline.mesh.Tiles.Tile.TILE_LAVA.id, (byte)0));
            require(org.wurmrail.WurmRailWorld.validateTile(65, 10, 0) != null,
                    "unsafe rail terrain rejected");
            System.out.println("PASS real item spawn, cargo, zone crossing, motion, rotation, save and terrain rejection on synthetic meshes");
            checkNativeDiagonalOwner();
            UsabilitySmoke.run();
            TrainUiAnswersSmoke.run();
            RailwayUiSmoke.run();
            RailwayUiSmoke.runTrestleSurvey();
            RailwayUiSmoke.runElevatedMotion();
            RailwayUiSmoke.runCurvedMotion();
            TrackMaterialsSmoke.run();
            RailwayRuntimeSmoke.run();
            TrackCornerRuntimeSmoke.run();
            TrackSlopeRuntimeSmoke.run();
            RailVegetationRuntimeSmoke.run();
            PassengerWorldSmoke.run();
            PassengerServiceSmoke.run();
            PassengerRuntimeSmoke.run();
            CombinedStationRuntimeSmoke.run();
            JunctionWorldSmoke.run();
            JunctionRuntimeSmoke.run();
            SignalWorldSmoke.run();
            SignalRuntimeSmoke.run();
            StationTurnbackRuntimeSmoke.run();
            PlatformRouteSmoke.run();
            JunctionSignalLoopSmoke.run();
            TrafficRuntimeSmoke.run();
            ConvoyRuntimeSmoke.run();
            QueueTurnRuntimeSmoke.run();
            CaveEntranceRuntimeSmoke.run();
            RailVehicleTerrainServerSmoke.run();
            RailwayBridgeRuntimeSmoke.run();
            CaveBuilderRuntimeSmoke.run();
            ScheduledRuntimeSmoke.run();
            JamRecoveryRuntimeSmoke.run();
            YLoopRuntimeSmoke.run();
            OpposedYLoopRuntimeSmoke.run();
            StationQueueHoldRuntimeSmoke.run();
            FreightRuntimeSmoke.run();
            BulkHopperRuntimeSmoke.run();
            FreightFillRuntimeSmoke.run();
            SteamDrillMiningRuntimeSmoke.run();
            SteamDrillRuntimeSmoke.run();
            CoalVeinNativeRuntimeSmoke.run();
            CoalDrillRuntimeSmoke.run();
            CoalLogisticsRuntimeSmoke.run();
            IndustrialWaterRuntimeSmoke.run();
            IndustrialWaterRecoveryRuntimeSmoke.run();
            LiquidFreightRuntimeSmoke.run();
            TankCarCapacityRuntimeSmoke.run();
            SteamWaterPumpNativeRuntimeSmoke.run();
            DeprecatedWaterFillRuntimeSmoke.run();
            LogBunkRuntimeSmoke.run();
            SteamYarderNativeRuntimeSmoke.run();
            SteamYarderRuntimeSmoke.run();
            SteamConsumptionRuntimeSmoke.run();
            LogCarRuntimeSmoke.run();
            LogCarFreightRuntimeSmoke.run();
            checkQuestionReplay();
            com.wurmonline.server.Server.surfaceMesh.close();
            com.wurmonline.server.Server.caveMesh.close();
            com.wurmonline.server.Server.rockMesh.close();
        }

        /** Exercise unmodified ItemFactory -> Zone -> VolaTile -> VirtualZone dispatch.
         * Only the socket-free CreatureCommunicator endpoint is captured. A successful
         * server registry lookup alone does not prove a player was sent the vehicle. */
        private static void checkSpawnAnnouncements() throws Exception {
            int tileX=30, tileY=30;
            com.wurmonline.server.zones.VolaTile tile = com.wurmonline.server.zones.Zones
                    .getOrCreateTile(tileX,tileY,true);
            java.lang.reflect.Field watchers = com.wurmonline.server.zones.VolaTile.class
                    .getDeclaredField("watchers");
            watchers.setAccessible(true);
            Object original=watchers.get(tile);
            java.lang.reflect.Field unsafeField=Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object allocator=unsafeField.get(null);
            FixtureWatcher observer=(FixtureWatcher)allocator.getClass()
                    .getMethod("allocateInstance",Class.class).invoke(allocator,FixtureWatcher.class);
            observer.fixtureCommunicator=new com.wurmonline.server.creatures.CreatureCommunicator(observer);
            java.util.Set<com.wurmonline.server.zones.VirtualZone> fixture=
                    new java.util.HashSet<com.wurmonline.server.zones.VirtualZone>();
            fixture.add(new com.wurmonline.server.zones.VirtualZone(observer,0,0,tileX,tileY,4,true));
            watchers.set(tile,fixture);
            movingAnnouncements.clear(); groundAnnouncements.clear();
            try {
                com.wurmonline.server.items.Item engine=org.wurmrail.WurmRailWorld
                        .spawnLocomotive(null,122.0,122.0,0);
                com.wurmonline.server.items.Item cart=org.wurmrail.WurmRailWorld
                        .spawnCart(null,121.0,122.0,0);
                com.wurmonline.server.items.Item track=org.wurmrail.WurmRailWorld
                        .spawnTrackMarker(null,123.0,122.0,0);
                checkMovingAnnouncement(engine,"model.wurmrail.locomotive.");
                checkMovingAnnouncement(cart,"model.wurmrail.cart.");
                require(!movingAnnouncements.containsKey(track.getWurmId()),
                        "static track must not be dispatched as a native moving item");
                GroundAnnouncement ground=groundAnnouncements.get(track.getWurmId());
                require(ground!=null && ground.item==track && ground.parent==-10L && ground.onGround,
                        "native ground-item announcement must contain the spawned track");
                require("model.wurmrail.track.0.".equals(ground.item.getModelName()),
                        "native ground-item path must use the graph-selected track model");
            } finally { watchers.set(tile,original); }
            System.out.println("PASS real spawn visibility dispatch announces selectable locomotive/cart with custom models, terrain positions and native moving flags; tracks use ground items");
        }

        private static void checkMovingAnnouncement(com.wurmonline.server.items.Item item,
                String expectedModel) {
            MovingAnnouncement announcement=movingAnnouncements.get(item.getWurmId());
            require(announcement!=null,"native nearby watcher must receive spawned "+item.getName());
            require(expectedModel.equals(announcement.model) && item.getName().equals(announcement.name),
                    "moving vehicle announcement must use its custom model and name");
            require(announcement.selectable && announcement.onGround && !announcement.floating,
                    "moving vehicle announcement must be selectable on dry terrain");
            require(announcement.layer==0 && announcement.bridge==-10L
                    && announcement.material==item.getMaterial() && announcement.rarity==item.getRarity(),
                    "moving vehicle announcement must retain layer, bridge, material and rarity");
            require(Math.abs(announcement.x-item.getPosX())<.00001
                    && Math.abs(announcement.y-item.getPosY())<.00001
                    && Math.abs(announcement.z-item.getPosZ())<.00001
                    && Math.abs(announcement.z-5.0)<.00001 && announcement.heading==0.0f,
                    "moving vehicle announcement must contain the actual terrain position and heading");
            require(!groundAnnouncements.containsKey(item.getWurmId()),
                    "custom vehicle must never use the static ground-item path");
        }

        private static void checkStationaryRotations(com.wurmonline.server.items.Item engine)
                throws Exception {
            com.wurmonline.server.zones.VolaTile tile = com.wurmonline.server.zones.Zones
                    .getOrCreateTile(engine.getTileX(), engine.getTileY(), true);
            java.lang.reflect.Field watchers = com.wurmonline.server.zones.VolaTile.class
                    .getDeclaredField("watchers");
            watchers.setAccessible(true);
            Object original = watchers.get(tile);
            java.util.Set<com.wurmonline.server.zones.VirtualZone> fixture = new java.util.HashSet<
                    com.wurmonline.server.zones.VirtualZone>();
            java.lang.reflect.Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object allocator = unsafeField.get(null);
            FixtureWatcher observer = (FixtureWatcher)allocator.getClass()
                    .getMethod("allocateInstance", Class.class).invoke(allocator, FixtureWatcher.class);
            observer.fixtureCommunicator = new com.wurmonline.server.creatures.CreatureCommunicator(observer);
            fixture.add(new com.wurmonline.server.zones.VirtualZone(observer,0,0,
                    engine.getTileX(),engine.getTileY(),1,true));
            watchers.set(tile, fixture);
            try {
                for (int heading = 0; heading < 360; heading += 45) {
                    int before = capturedRotations;
                    org.wurmrail.WurmRailWorld.moveCar(engine.getWurmId(), engine.getPosX(),
                            engine.getPosY(), heading, 0);
                    require(capturedRotations == before+1 && capturedRotationItem == engine.getWurmId()
                            && capturedRotation == (int)(heading*256.0/360.0)
                            && Math.abs(capturedX-engine.getPosX())<.00001
                            && Math.abs(capturedY-engine.getPosY())<.00001
                            && Math.abs(capturedZ-engine.getPosZ())<.00001,
                            "stationary turn must reach the native moving-item packet: " + heading);
                    require(Math.abs(engine.getRotation()-heading)<.00001,
                            "stored server heading must retain compass convention");
                }
                for (int heading = 0; heading < 360; heading += 45) {
                    int before = capturedRotations;
                    org.wurmrail.WurmRailWorld.moveCar(engine.getWurmId(), engine.getPosX()+.02,
                            engine.getPosY(), heading, 0);
                    require(capturedRotations == before+2 && capturedRotationItem == engine.getWurmId()
                            && capturedRotation == (int)(heading*256.0/360.0)
                            && Math.abs(capturedX-engine.getPosX())<.00001
                            && Math.abs(capturedY-engine.getPosY())<.00001
                            && Math.abs(capturedZ-engine.getPosZ())<.00001,
                            "native movement plus final authoritative floor update must retain heading and height: " + heading);
                }
            } finally { watchers.set(tile, original); }
            System.out.println("PASS eight stationary turns and eight moving updates use native moving-item packets; seat frames match artwork");
        }

        /** No constructor or session is started: only the native communicator boundary is needed. */
        private static final class FixtureWatcher extends com.wurmonline.server.creatures.Creature {
            private com.wurmonline.server.creatures.Communicator fixtureCommunicator;
            private FixtureWatcher() throws Exception { super(); }
            @Override public boolean hasLink() { return true; }
            @Override public com.wurmonline.server.creatures.Communicator getCommunicator() {
                return fixtureCommunicator;
            }
        }

        private static void checkNativeDiagonalOwner() throws Exception {
            org.wurmrail.core.RailNetwork network = new org.wurmrail.core.RailNetwork();
            org.wurmrail.core.RailNetwork.Node a = network.addNode("cornerA", 10, 10, 0);
            org.wurmrail.core.RailNetwork.Node b = network.addNode("cornerB", 11, 9, 0);
            a.markerId = org.wurmrail.WurmRailWorld.spawnTrackMarker(null, 42, 42, 0).getWurmId();
            b.markerId = org.wurmrail.WurmRailWorld.spawnTrackMarker(null, 46, 38, 0).getWurmId();
            network.connect(a.name, b.name);
            com.wurmonline.server.items.Item engine = org.wurmrail.WurmRailWorld.spawnLocomotive(null, 42, 42, 0);
            network.createTrain("CornerTest", 0, java.util.Collections.singletonList(engine.getWurmId()), a.name, b.name);
            org.wurmrail.WurmRailRuntime runtime = new org.wurmrail.WurmRailRuntime();
            java.lang.reflect.Field graph = org.wurmrail.WurmRailRuntime.class.getDeclaredField("network");
            graph.setAccessible(true);
            graph.set(runtime, network);
            java.lang.reflect.Method validate = org.wurmrail.WurmRailRuntime.class.getDeclaredMethod(
                    "validateMotion", java.util.List.class);
            validate.setAccessible(true);
            org.wurmrail.core.RailNetwork.Pose sideTilePose = new org.wurmrail.core.RailNetwork.Pose(
                    engine.getWurmId(), 44, 40, 45, 0);
            require(!((Boolean)validate.invoke(runtime, java.util.Collections.singletonList(sideTilePose))),
                    "fixture must expose the side tile that has no track at an exact northeast corner");
            org.wurmrail.core.RailNetwork.Pose ownedPose = new org.wurmrail.core.RailNetwork.Pose(
                    engine.getWurmId(), 44, 40, 45, 0, a.name);
            require((Boolean)validate.invoke(runtime, java.util.Collections.singletonList(ownedPose)),
                    "native motion validation must use the rail owner at exact diagonal corners");
            org.wurmrail.WurmRailWorld.moveCar(engine.getWurmId(), 44, 40, 45, 0);
            require(Math.abs(engine.getPosX() - 44) < .001 && Math.abs(engine.getPosY() - 40) < .001,
                    "native movement must accept the exact diagonal corner after owner validation");
            System.out.println("PASS native runtime diagonal corner ownership, validation and movement");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }

        /** Native ModQuestionImpl must not turn legacy form replays into repeated queued work. */
        private static void checkQuestionReplay() throws Exception {
            final com.wurmonline.server.creatures.Creature actor=TrackMaterialsSmoke.actor(720004032L,true,0);
            final int[] submitted={0};
            org.wurmrail.RailwayBuilderUi.Api builder=new org.wurmrail.RailwayBuilderUi.Api() {
                @Override public org.wurmrail.RailwayBuilderUi.View snapshot(com.wurmonline.server.creatures.Creature p,int x,int y,int layer) {
                    return new org.wurmrail.RailwayBuilderUi.View("Replay fixture",null,-10L,false,false,"",5,
                            java.util.Collections.<org.wurmrail.RailwayBuilderUi.Neighbor>emptyList(),0,3,false);
                }
                @Override public void submit(com.wurmonline.server.creatures.Creature p,int x,int y,int layer,org.wurmrail.RailwayBuilderUi.Request request) {
                    submitted[0]++;
                }
            };
            org.wurmrail.RailwayBuilderUi.show(actor,40,120,0,builder);
            com.wurmonline.server.questions.Question builderQuestion=latestQuestion(actor);
            java.util.Properties placement=new java.util.Properties();
            placement.setProperty("place","true"); placement.setProperty("direction","2"); placement.setProperty("count","1");
            builderQuestion.answer(placement); builderQuestion.answer(placement);
            require(submitted[0]==1,"native builder question replay must queue only one construction request");

            final org.wurmrail.RailwayTrainUi.View view=new org.wurmrail.RailwayTrainUi.View();
            view.carId=987654321017L; view.engine=false; view.canControl=true;
            view.cars.add(new org.wurmrail.RailwayTrainUi.Choice(Long.toString(view.carId),"Replay wagon"));
            org.wurmrail.RailwayTrainUi.Api train=new org.wurmrail.RailwayTrainUi.Api() {
                @Override public org.wurmrail.RailwayTrainUi.View snapshot(com.wurmonline.server.creatures.Creature p,long id) { return view; }
                @Override public void submit(com.wurmonline.server.creatures.Creature p,long id,org.wurmrail.core.RailNetwork.Train expected,org.wurmrail.RailwayTrainUi.Request request) {
                    submitted[0]++;
                }
            };
            org.wurmrail.RailwayTrainUi.show(actor,view.carId,train);
            com.wurmonline.server.questions.Question trainQuestion=latestQuestion(actor);
            java.util.Properties boarding=new java.util.Properties();
            boarding.setProperty("board","true"); boarding.setProperty("car","0");
            trainQuestion.answer(boarding); trainQuestion.answer(boarding);
            require(submitted[0]==2,"native train question replay must queue only one boarding request");
            org.wurmrail.RailwayPassengerUi.Api passenger=new org.wurmrail.RailwayPassengerUi.Api() {
                @Override public org.wurmrail.RailwayPassengerUi.View snapshot(com.wurmonline.server.creatures.Creature p,long id) {
                    return new org.wurmrail.RailwayPassengerUi.View(-10L,0L,991L,null,"Journey","","Riding",false,-10L,0,4,
                            java.util.Collections.singletonList(new org.wurmrail.RailwayPassengerUi.Choice(992L,"Harbor")));
                }
                @Override public void submit(com.wurmonline.server.creatures.Creature p,long id,org.wurmrail.RailwayPassengerUi.Request request) {
                    require(request.destinationId==992L&&request.trainEngineId==991L,"journey answer must retain captured destination and train");
                    submitted[0]++;
                }
            };
            org.wurmrail.RailwayPassengerUi.show(actor,-10L,passenger);
            com.wurmonline.server.questions.Question passengerQuestion=latestQuestion(actor);
            java.util.Properties destination=new java.util.Properties();
            destination.setProperty("destination","true"); destination.setProperty("destination_choice","0");
            passengerQuestion.answer(destination); passengerQuestion.answer(destination);
            require(submitted[0]==3,"native passenger journey replay must queue only one destination selection");
            org.wurmrail.RailwayPassengerUi.BuilderApi passengerBuilder=new org.wurmrail.RailwayPassengerUi.BuilderApi() {
                @Override public org.wurmrail.RailwayPassengerUi.BuildView buildSnapshot(com.wurmonline.server.creatures.Creature p,int x,int y,int layer) {
                    return new org.wurmrail.RailwayPassengerUi.BuildView("Replay fixture",
                            java.util.Collections.singletonList(new org.wurmrail.RailwayPassengerUi.RailChoice("captured_rail","Harbor railway")),
                            org.wurmrail.RailwayPassengerUi.Mode.PICKUP,1,false);
                }
                @Override public void build(com.wurmonline.server.creatures.Creature p,int x,int y,int layer,org.wurmrail.RailwayPassengerUi.BuildRequest request) {
                    require(request.trackKey.equals("captured_rail")&&request.mode==org.wurmrail.RailwayPassengerUi.Mode.STATION,
                            "station construction must retain captured adjacent rail and normalize obsolete mode");
                    submitted[0]++;
                }
            };
            org.wurmrail.RailwayPassengerUi.showBuilder(actor,40,120,0,passengerBuilder);
            com.wurmonline.server.questions.Question passengerBuildQuestion=latestQuestion(actor);
            java.util.Properties buildStop=new java.util.Properties();
            buildStop.setProperty("build","true"); buildStop.setProperty("rail","0"); buildStop.setProperty("mode","0"); buildStop.setProperty("stop_name","Harbor pickup");
            passengerBuildQuestion.answer(buildStop); passengerBuildQuestion.answer(buildStop);
            require(submitted[0]==4,"native passenger construction replay must queue only one paid build request");
            System.out.println("PASS native builder, train, journey and passenger-stop ModQuestionImpl answer replay is single-use");
        }

        @SuppressWarnings("unchecked")
        private static com.wurmonline.server.questions.Question latestQuestion(com.wurmonline.server.creatures.Creature actor) throws Exception {
            java.lang.reflect.Field registry=com.wurmonline.server.questions.Questions.class.getDeclaredField("questions");
            registry.setAccessible(true);
            com.wurmonline.server.questions.Question latest=null;
            for(com.wurmonline.server.questions.Question question:((java.util.Map<Integer,com.wurmonline.server.questions.Question>)registry.get(null)).values())
                if(question.getResponder()==actor&&(latest==null||question.getId()>latest.getId())) latest=question;
            require(latest!=null,"a native question must have been registered for the fixture actor");
            return latest;
        }
    }
}
