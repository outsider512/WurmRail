import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.gotti.wurmunlimited.modsupport.actions.ActionPerformer;
import org.gotti.wurmunlimited.modsupport.actions.BehaviourProvider;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.wurmrail.RailwayBml;
import org.wurmrail.RailwayMenuActions;
import org.wurmrail.RailwaySignalActions;
import org.wurmrail.RailwaySignalUi;
import org.wurmrail.RailModelRegistry;
import org.wurmrail.TrestleWalkingWorld;
import org.wurmrail.WurmRailRuntime;
import org.wurmrail.WurmRailWorld;

/** Real native context-menu interfaces after loader startup; no sockets or player sessions. */
public final class RailwayUiSmoke {
    private RailwayUiSmoke() { }

    private static String surveyBml;
    private static String surveyMessage;
    private static final Map<Long,float[]> surveyMarkers = new java.util.HashMap<>();

    public static void captureSurveyMarker(long id, short type, float x, float y, float z, byte layer) {
        if (type==2 && layer==0) surveyMarkers.put(id,new float[]{x,y,z});
    }

    public static void captureSurveyMarkerRemoval(long id) { surveyMarkers.remove(id); }

    public static void captureSurveyMessage(String message) { surveyMessage = message; }

    public static void captureSurveyBml(String bml, String title) {
        if ("Railway trestle survey".equals(title)) surveyBml = bml;
    }

    public static void runElevatedMotion() throws Exception {
        int x = 160, y = 95, count = 14;
        org.wurmrail.core.RailNetwork network = new org.wurmrail.core.RailNetwork();
        java.util.List<String> span = new java.util.ArrayList<>();
        java.util.List<Item> items = new java.util.ArrayList<>();
        com.wurmonline.mesh.MeshIO surface = com.wurmonline.server.Server.surfaceMesh;
        int[][] original = new int[count + 2][2];
        for (int col = 0; col < original.length; col++) for (int row = 0; row < 2; row++) {
            original[col][row] = surface.getTile(x + col, y + row);
            surface.setTile(x + col, y + row, com.wurmonline.mesh.Tiles.encode(
                    (short)50, com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id, (byte)0));
        }
        try {
            for (int i = 0; i < count; i++) {
                org.wurmrail.core.RailNetwork.Node node = network.addNode("T" + i, x + i, y, 0);
                Item marker = WurmRailWorld.spawnTrackMarker(null, node.worldX(), node.worldY(), 0);
                items.add(marker);
                node.markerId = marker.getWurmId();
                if (i > 0) network.connect("T" + (i - 1), node.name);
                if (i >= 2 && i <= 10) span.add(node.name);
            }
            for (int col = 4; col <= 9; col++) for (int row = 0; row < 2; row++)
                surface.setTile(x + col, y + row, com.wurmonline.mesh.Tiles.encode(
                        (short)-160, com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id, (byte)0));
            double worldY = y * 4 + 2;
            org.wurmrail.core.TrestleAlignment alignment = org.wurmrail.core.TrestleAlignment.straight(
                    new org.wurmrail.core.TrestleAlignment.Point((x + 2) * 4 + 2, worldY, 5),
                    new org.wurmrail.core.TrestleAlignment.Point((x + 10) * 4 + 2, worldY, 5), 100, .05);
            network.setStraightElevation(span, alignment);
            Item engine = WurmRailWorld.spawnLocomotive(null, (x + 2) * 4 + 2, worldY, 0);
            Item cart = WurmRailWorld.spawnCart(null, (x + 1) * 4 + 2, worldY, 0);
            Item logs = WurmRailWorld.spawnLogCar(null, x * 4 + 2, worldY, 0);
            items.add(engine);
            items.add(cart);
            items.add(logs);
            org.wurmrail.core.RailNetwork.Train train = network.createTrain("Elevated", 1L,
                    java.util.Arrays.asList(engine.getWurmId(), cart.getWurmId(), logs.getWurmId()), "T2", "T3");
            org.wurmrail.WurmRailRuntime runtime = new org.wurmrail.WurmRailRuntime();
            Field graph = runtime.getClass().getDeclaredField("network");
            graph.setAccessible(true);
            graph.set(runtime, network);
            java.lang.reflect.Method place = runtime.getClass().getDeclaredMethod("restoreTrainPoses", List.class);
            place.setAccessible(true);
            java.lang.reflect.Method validate = runtime.getClass().getDeclaredMethod("validateMotion", List.class);
            validate.setAccessible(true);
            for (int direction : new int[]{1, -1}) {
                if (direction < 0) network.reverse(train.name);
                network.start(train.name);
                for (int step = 0; step < 180; step++) {
                    network.tick(.1);
                    check((Boolean) validate.invoke(runtime, train.poses), "native guard accepts surveyed elevation");
                    place.invoke(runtime, train.poses);
                    check(Math.abs(engine.getPosZ() - 5) < .001 && Math.abs(cart.getPosZ() - 5) < .001
                            && Math.abs(logs.getPosZ() - 5) < .001, "every native car remains at rail elevation");
                }
                network.stop(train.name);
                check(direction > 0 ? logs.getTileX() >= x + 10 : engine.getTileX() <= x + 2,
                        "coupled consist clears the span in both directions");
            }
        } finally {
            for (Item item : items) com.wurmonline.server.Items.destroyItem(item.getWurmId());
            for (int col = 0; col < original.length; col++) for (int row = 0; row < 2; row++)
                surface.setTile(x + col, y + row, original[col][row]);
        }
        System.out.println("PASS native straight elevated movement, three-car consist, terrain-independent height and both approach seams");
    }

    public static void runCurvedMotion() throws Exception {
        int x=180,y=130,returnRow=y+12;
        float centerX=x*4+2,centerY=y*4+2,south=returnRow*4+2;
        java.util.List<org.wurmrail.core.TrestleAlignment.Point> marks=new java.util.ArrayList<>();
        for(int[] offset:new int[][]{{2,0,5},{40,0,0},{56,8,0},{64,24,0},{56,40,0},{40,48,0},{2,48,9}})
            marks.add(new org.wurmrail.core.TrestleAlignment.Point(centerX+offset[0],centerY+offset[1],offset[2]));
        org.wurmrail.core.TrestleAlignment alignment=org.wurmrail.core.TrestleAlignment.waypoints(marks,12,1000,.5,2,6);
        com.wurmonline.mesh.MeshIO mesh=com.wurmonline.server.Server.surfaceMesh;
        int[][] original=new int[23][17];
        for(int col=0;col<original.length;col++) for(int row=0;row<original[col].length;row++) {
            original[col][row]=mesh.getTile(x+col-3,y+row-2);
            int plateau=Math.min(6-col,3-Math.abs(y+row-2-returnRow));
            int dirtHeight=50+20*Math.max(0,Math.min(2,plateau));
            mesh.setTile(x+col-3,y+row-2,com.wurmonline.mesh.Tiles.encode((short)dirtHeight,
                    com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
        }
        java.util.List<Item> items=new java.util.ArrayList<>();
        try {
            org.wurmrail.core.RailNetwork network=new org.wurmrail.core.RailNetwork();
            java.util.List<org.wurmrail.core.RailNetwork.Node> rails=new java.util.ArrayList<>();
            for(int i=-2;i<=0;i++) rails.add(network.addNode("West"+(i+2),x+i,y,0));
            for(org.wurmrail.core.TrestleAlignment.TileSection tile:alignment.tileSections())
                rails.add(network.addNode("Curve"+rails.size(),tile.x,tile.y,0));
            int deckEnd=rails.size();
            for(int i=0;i<=2;i++) rails.add(network.addNode("Return"+i,x-i,returnRow,0));
            for(int i=0;i<rails.size();i++) {
                org.wurmrail.core.RailNetwork.Node rail=rails.get(i);
                Item marker=WurmRailWorld.spawnTrackMarker(null,rail.worldX(),rail.worldY(),0);
                items.add(marker);
                rail.markerId=marker.getWurmId();
                if(i>0)network.connect(rails.get(i-1).name,rail.name);
            }
            java.util.List<String> span=new java.util.ArrayList<>();
            for(int i=2;i<=deckEnd;i++)span.add(rails.get(i).name);
            network.setCurvedTrestleElevation(span,alignment);
            Item engine=WurmRailWorld.spawnLocomotive(null,centerX,centerY,0);
            Item cart=WurmRailWorld.spawnCart(null,centerX-4,centerY,0);
            items.add(engine);items.add(cart);
            org.wurmrail.core.RailNetwork.Train train=network.createTrain("NativeCurve",1,
                    java.util.Arrays.asList(engine.getWurmId(),cart.getWurmId()),span.get(0),span.get(1));
            org.wurmrail.WurmRailRuntime runtime=new org.wurmrail.WurmRailRuntime();
            Field graph=runtime.getClass().getDeclaredField("network");graph.setAccessible(true);graph.set(runtime,network);
            java.lang.reflect.Method apply=runtime.getClass().getDeclaredMethod("applyPoses",train.getClass());apply.setAccessible(true);
            for(int direction:new int[]{1,-1}) {
                if(direction<0)network.reverse(train.name);
                network.start(train.name);
                boolean onArc=false;
                for(int step=0;step<650;step++) {
                    network.tick(.1);
                    apply.invoke(runtime,train);
                    for(int i=0;i<2;i++) {
                        org.wurmrail.core.RailNetwork.Pose pose=train.poses.get(i);
                        Item item=i==0?engine:cart;
                        check(Math.abs(item.getPosX()-pose.x)<.001 && Math.abs(item.getPosY()-pose.y)<.001,
                                "the native vehicle follows the curve coordinates");
                        if(Float.isFinite(pose.elevation))
                            check(Math.abs(item.getPosZ()-pose.elevation)<.001,"the native vehicle follows the curved deck elevation");
                    }
                    org.wurmrail.core.RailNetwork.Pose lead=train.poses.get(0), wagon=train.poses.get(1);
                    if(Float.isFinite(lead.elevation) && Float.isFinite(wagon.elevation))
                        check(Math.abs((lead.elevation-wagon.elevation)/alignment.gradient-4)<.002,
                                "the curved consist holds its four-metre chainage spacing");
                    onArc|=engine.getPosY()>centerY+16 && engine.getPosY()<south-16 && engine.getPosX()>centerX+60;
                }
                network.stop(train.name);
                check(onArc && (direction>0?cart.getPosX()<=centerX && cart.getPosY()==south:engine.getPosX()<=centerX),
                        "the native consist crosses both curve joins");
            }
            System.out.println("PASS native locomotive and coupled cart cross a three-tile-radius waypoint hairpin in both directions");
        } finally {
            for(Item item:items)com.wurmonline.server.Items.destroyItem(item.getWurmId());
            for(int col=0;col<original.length;col++) for(int row=0;row<original[col].length;row++)
                mesh.setTile(x+col-3,y+row-2,original[col][row]);
        }
    }

    @SuppressWarnings("unchecked")
    public static void runTrestleSurvey() throws Exception {
        org.wurmrail.WurmRailRuntime runtime = new org.wurmrail.WurmRailRuntime();
        runtime.configure(new java.util.Properties());
        org.wurmrail.core.RailNetwork network = new org.wurmrail.core.RailNetwork();
        Field graph = runtime.getClass().getDeclaredField("network");
        graph.setAccessible(true);
        graph.set(runtime, network);
        Field ready = runtime.getClass().getDeclaredField("ready");
        ready.setAccessible(true);
        ready.set(runtime, true);
        Field queue = runtime.getClass().getDeclaredField("requests");
        queue.setAccessible(true);
        java.util.Queue<Runnable> requests = (java.util.Queue<Runnable>) queue.get(runtime);
        int first = ModActions.getNextActionId();
        runtime.init();
        short start = (short) first, mark = (short) (first + 1), review = (short) (first + 2),
                waypoint = (short)(first+3), removeTrestle = (short)(first+4);
        com.wurmonline.server.players.Player actor = PassengerWorldSmoke.player(714002032L,202,202);
        Field possessions=Creature.class.getDeclaredField("possessions");
        possessions.setAccessible(true);
        possessions.set(actor,new com.wurmonline.server.items.Possessions(actor));
        int surface = com.wurmonline.server.Server.surfaceMesh.getTile(50, 50);
        Item dioptra = com.wurmonline.server.items.ItemFactory.createItem(
                com.wurmonline.server.items.ItemList.dioptra, 50f, "Trestle fixture");
        check(actor.getInventory().insertItem(dioptra, true), "stock dioptra is held");
        Item pole = com.wurmonline.server.items.ItemFactory.createItem(
                com.wurmonline.server.items.ItemList.rangePole, 50f, 222, 202, 0, true, (byte)0, -10L, "Trestle fixture");
        try {
            BehaviourProvider startMenu = (BehaviourProvider) performer(start);
            check(!has(startMenu.getBehavioursFor(actor, 50, 50, true, surface), start),
                    "survey requires an active dioptra");
            check(has(startMenu.getBehavioursFor(actor, dioptra, 50, 50, true, surface), start),
                    "held dioptra offers native terrain survey");
            long ground=com.wurmonline.mesh.Tiles.getTileId(50,50,0);
            dispatchSurvey(actor, null, ground, start);
            check(requests.isEmpty() && surveyMessage!=null && surveyMessage.contains("Activate a dioptra"),
                    "native stale click without the active tool returns a useful message");
            dispatchSurvey(actor, dioptra, ground, start);
            check(requests.size() == 1, "native terrain dispatch must preserve the active dioptra, action "
                    +start+", feedback "+surveyMessage);
            requests.remove().run();
            check(surveyMessage!=null && surveyMessage.contains("Trestle survey started"),
                    "native survey sends confirmation even if the question window is missed");
            com.wurmonline.server.questions.Question firstWindow = latestQuestion(actor);
            check("Railway trestle survey".equals(firstWindow.getTitle()), "start opens survey question");
            check(!has(((BehaviourProvider) performer(mark)).getBehavioursFor(actor, dioptra, pole), mark),
                    "distant range pole is not selectable");
            actor.getStatus().setPositionXYZ(222, 202, 5);
            check(has(((BehaviourProvider) performer(mark)).getBehavioursFor(actor, dioptra, pole), mark),
                    "same player can mark the installed range pole");
            dispatchSurvey(actor, dioptra, pole.getWurmId(), mark);
            check(requests.size() == 1, "native range-pole dispatch must preserve the active dioptra");
            requests.remove().run();
            com.wurmonline.server.questions.Question marked = latestQuestion(actor);
            check(marked.getId() > firstWindow.getId(), "destination opens an updated survey");
            check(surveyBml != null && surveyBml.contains("Survey clear") && surveyBml.contains("proposed bents"),
                    "valid survey previews its proposed bents");
            check(surveyMarkers.size() == 4 && surveyMarkers.values().stream().anyMatch(
                    position -> position[0] == 204f && position[1] == 202f && Math.abs(position[2]-5f) < .001),
                    "in-world preview sends both joins and each bent at the surveyed elevation");
            Item curvePole = com.wurmonline.server.items.ItemFactory.createItem(
                    com.wurmonline.server.items.ItemList.rangePole,50f,214,210,0,true,(byte)0,-10L,"Curve fixture");
            try {
                actor.getStatus().setPositionXYZ(214,210,5);
                dispatchSurvey(actor,dioptra,curvePole.getWurmId(),waypoint);
                check(requests.size()==1,"native intermediate pole dispatch preserves the activated dioptra");
                requests.remove().run();
                check(surveyBml.contains("3-tile minimum radius") && surveyMarkers.isEmpty(),
                        "a tight curve displays its radius error and clears the previous in-world markers: "+surveyBml);
                java.util.Properties undoPole = new java.util.Properties();
                undoPole.setProperty("undoPole","true");
                latestQuestion(actor).answer(undoPole);
                requests.remove().run();
                check(surveyBml.contains("Survey clear") && surveyMarkers.size()==4,
                        "removing the intermediate pole restores the valid straight preview");
                marked = latestQuestion(actor);
                actor.getStatus().setPositionXYZ(222,202,5);
            } finally {
                com.wurmonline.server.Items.destroyItem(curvePole.getWurmId());
            }
            java.util.Properties edit = new java.util.Properties();
            edit.setProperty("preview", "true");
            edit.setProperty("start", "5");
            edit.setProperty("end", "15.1");
            marked.answer(edit);
            marked.answer(edit);
            check(requests.size() == 1, "survey answer is single use");
            requests.remove().run();
            check(surveyBml != null && surveyBml.contains("proposed gradient exceeds 50.0%"),
                    "native preview rejects a route one dirt above the default rise limit");
            check(surveyMarkers.isEmpty(), "an invalid elevation removes the previous preview markers");
            java.util.Properties cancel = new java.util.Properties();
            cancel.setProperty("cancel", "true");
            latestQuestion(actor).answer(cancel);
            requests.remove().run();
            check(!has(((BehaviourProvider) performer(review)).getBehavioursFor(actor, dioptra, 55, 50, true, surface), review),
                    "cancellation removes the player's draft");
            Field state = runtime.getClass().getDeclaredField("stateFile");
            state.setAccessible(true);
            java.nio.file.Path file = java.nio.file.Paths.get("trestle-test","network.properties");
            state.set(runtime,file);
            checkRoundedSurvey(actor,dioptra,requests,start,waypoint,mark,removeTrestle,network,file,runtime);
            check(network.nodes.isEmpty(), "curved trestle removal leaves the test corridor clear");
            com.wurmonline.mesh.MeshIO mesh = com.wurmonline.server.Server.surfaceMesh;
            int[][] original = new int[7][2];
            int startTile=70, surveyRow=72;
            for (int col = 0; col < original.length; col++) for (int row = 0; row < 2; row++) {
                original[col][row] = mesh.getTile(startTile+col,surveyRow+row);
                int dirtHeight = col < 2 ? 131 : col < 5 ? -80 : 51;
                mesh.setTile(startTile+col,surveyRow+row,com.wurmonline.mesh.Tiles.encode(
                        (short)dirtHeight,com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
            }
            Item buildPole=com.wurmonline.server.items.ItemFactory.createItem(
                    com.wurmonline.server.items.ItemList.rangePole,50f,(startTile+5)*4+2,surveyRow*4+2,
                    0,true,(byte)0,-10L,"Trestle construction fixture");
            try {
                actor.getStatus().setPositionXYZ(startTile*4+2,surveyRow*4+2,13.1f);
                org.wurmrail.core.RailNetwork.Node approach=network.addNode("SurveyApproach",startTile,surveyRow,0);
                Item approachItem=WurmRailWorld.spawnTrackMarker(actor,startTile*4+2,surveyRow*4+2,0);
                approach.markerId=approachItem.getWurmId();
                dispatchSurvey(actor,dioptra,approachItem.getWurmId(),start);
                check(requests.size()==1,"native track-item dispatch must preserve the active dioptra");
                requests.remove().run();
                actor.getStatus().setPositionXYZ((startTile+5)*4+2,surveyRow*4+2,5.1f);
                dispatchSurvey(actor,dioptra,buildPole.getWurmId(),mark);
                requests.remove().run();
                check(surveyBml.contains("-50.00%") && surveyBml.contains("GM build straight trestle"),
                        "exactly 50% downhill from a flat approach presents GM build");
                int[][] obstruction = new int[2][2];
                java.util.Properties preview = new java.util.Properties();
                preview.setProperty("preview","true");
                for(int col=0;col<2;col++) for(int row=0;row<2;row++) {
                    obstruction[col][row]=mesh.getTile(startTile+3+col,surveyRow+row);
                    mesh.setTile(startTile+3+col,surveyRow+row,com.wurmonline.mesh.Tiles.encode(
                            (short)180,com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
                }
                latestQuestion(actor).answer(preview);
                requests.remove().run();
                check(surveyBml.contains("above the planned rail") && !surveyBml.contains("GM build straight trestle"),
                        "a real obstruction on the deck reports its height and blocks construction");
                for(int col=0;col<2;col++) for(int row=0;row<2;row++)
                    mesh.setTile(startTile+3+col,surveyRow+row,obstruction[col][row]);
                latestQuestion(actor).answer(preview);
                requests.remove().run();
                java.util.Properties build = new java.util.Properties();
                build.setProperty("instant","true");
                latestQuestion(actor).answer(build);
                requests.remove().run();
                check(network.nodes.size()==6,"GM survey action creates the whole linked span");
                org.wurmrail.core.RailNetwork loaded=org.wurmrail.core.RailNetwork.load(file);
                check(loaded.nodes.size()==6 && loaded.nodeAt(startTile+2,surveyRow,0).links.size()==2,"built span survives core reload");
                org.wurmrail.core.RailNetwork.Train savedApproach = loaded.createTrain("ReloadApproach",actor.getWurmId(),
                        java.util.Collections.singletonList(441L),approach.name,loaded.nodeAt(startTile+1,surveyRow,0).name);
                loaded.start(savedApproach.name);
                loaded.tick(.1);
                org.wurmrail.core.RailNetwork.Pose approachPose=savedApproach.poses.get(0);
                check(Float.isNaN(approachPose.elevation) && approachPose.approachSeam!=null
                        && approachPose.approachSeam.x==(startTile+1)*4 && Math.abs(approachPose.approachSeam.z-13.1)<.001,
                        "restart preserves the ground half of the approach and its deck seam");
                Item deck=com.wurmonline.server.Items.getItem(loaded.nodeAt(startTile+2,surveyRow,0).markerId);
                check(deck.getTemplateId()==WurmRailWorld.trestleDeckTemplateId && Math.abs(deck.getPosZ()-10.1)<.001,
                        "deck uses surveyed absolute height over the valley");
                check("model.wurmrail.trestledeck.2.-500000.".equals(deck.getModelName()),"deck advertises axis and 50% grade");
                checkWalking(actor, startTile, surveyRow);
                check(loaded.nodes.values().stream().anyMatch(node->!node.timber().isEmpty()),
                        "timber is linked in the authoritative save");
                Item reloaded=new com.wurmonline.server.items.DbItem(deck.getWurmId());
                check(Math.abs(reloaded.getPosZ()-deck.getPosZ())<.001,"absolute height survives native item reload");
                Item firstDeck=com.wurmonline.server.Items.getItem(loaded.nodeAt(startTile+1,surveyRow,0).markerId);
                firstDeck.setData1(-1);
                deck.setData1(-1);
                Field networkField=runtime.getClass().getDeclaredField("network");
                networkField.setAccessible(true);
                networkField.set(runtime,loaded);
                try {
                    java.lang.reflect.Method refresh=runtime.getClass().getDeclaredMethod("refreshTrackVisuals");
                    refresh.setAccessible(true);
                    refresh.invoke(runtime);
                    check("model.wurmrail.trestledeck.2.-500000.".equals(firstDeck.getModelName())
                                    && "model.wurmrail.trestledeck.2.-500000.".equals(deck.getModelName()),
                            "startup restores deck packet grades from the saved half-edge and full-edge profiles");
                } finally {
                    networkField.set(runtime,network);
                }
                String span=loaded.nodeAt(startTile+2,surveyRow,0).trestle();
                long timber=loaded.nodes.values().stream().flatMap(node->node.timber().stream()).findFirst().get();
                Item engine=WurmRailWorld.spawnLocomotive(actor,startTile*4+2,surveyRow*4+2,0);
                try {
                    WurmRailWorld.moveCar(engine.getWurmId(),engine.getPosX(),engine.getPosY(),90,0);
                    java.lang.reflect.Method init=runtime.getClass().getDeclaredMethod("initService");
                    init.setAccessible(true);
                    init.invoke(runtime);
                    Field service=runtime.getClass().getDeclaredField("trainService");
                    service.setAccessible(true);
                    Object trainService=service.get(runtime);
                    java.lang.reflect.Method register=trainService.getClass().getDeclaredMethod("register",Item.class,long.class);
                    register.setAccessible(true);
                    org.wurmrail.core.RailNetwork.Train train=(org.wurmrail.core.RailNetwork.Train)
                            register.invoke(trainService,engine,actor.getWurmId());
                    check(network.trains.get(train.name)==train,"loco registration accepts the completed trestle departure");
                    java.lang.reflect.Method apply=runtime.getClass().getDeclaredMethod("applyPoses",train.getClass());
                    apply.setAccessible(true);
                    network.start(train.name);
                    boolean crossedValley=false;
                    for(int tick=0;tick<100;tick++) {
                        network.tick(.1);
                        apply.invoke(runtime,train);
                        if(engine.getTileX()==startTile+2) {
                            crossedValley=true;
                            double expectedHeight=13.1-(engine.getPosX()-((startTile+1)*4))*0.5;
                            check(Math.abs(engine.getPosZ()-expectedHeight)<.001,"locomotive follows the built 50% grade above the valley");
                        }
                    }
                    check(crossedValley && engine.getTileX()==startTile+5,"locomotive reaches the far approach on the built span");
                    network.stop(train.name);
                    network.reverse(train.name);
                    network.start(train.name);
                    boolean announced=false;
                    for(int tick=0;tick<100;tick++) {
                        network.tick(.1);
                        apply.invoke(runtime,train);
                        if (!announced && engine.getTileX()==startTile+2) {
                            actor.getCommunicator().sendNewMovingItem(engine.getWurmId(),engine.getName(),
                                    engine.getModelName(),engine.getPosX(),engine.getPosY(),engine.getPosZ()+3,
                                    -10L,engine.getRotation(),(byte)0,true,false,false,(byte)0,(byte)0);
                            check(PassengerWorldSmoke.queuedMovingAnnouncementAt(actor,engine.getWurmId(),engine.getPosZ()),
                                    "a native reannouncement while backing publishes the surveyed height");
                            announced=true;
                        }
                    }
                    check(announced && engine.getTileX()==startTile && Math.abs(engine.getPosZ()-13.1)<.001,
                            "locomotive returns uphill across the join to the flat starting tile");
                    network.stop(train.name);
                    network.removeTrain(train.name);
                } finally {
                    com.wurmonline.server.Items.destroyItem(engine.getWurmId());
                }
                com.wurmonline.server.Items.destroyItem(firstDeck.getWurmId());
                removeTrestleAt(actor,dioptra,requests,removeTrestle,startTile+1,surveyRow);
                check(network.nodes.size()==1 && network.nodes.get(approach.name)==approach && approach.links.isEmpty()
                        && org.wurmrail.core.RailNetwork.load(file).nodes.size()==1,
                        "GM removal clears the live and persisted span and preserves its preexisting approach");
                check(!com.wurmonline.server.Items.getItemOptional(deck.getWurmId()).isPresent()
                        && !com.wurmonline.server.Items.getItemOptional(timber).isPresent(), "GM removal clears deck and timber");

                actor.getStatus().setPositionXYZ(startTile*4+2,surveyRow*4+2,13.1f);
                dispatchSurvey(actor,dioptra,approach.markerId,start);
                requests.remove().run();
                actor.getStatus().setPositionXYZ((startTile+5)*4+2,surveyRow*4+2,5.1f);
                dispatchSurvey(actor,dioptra,buildPole.getWurmId(),mark);
                requests.remove().run();
                latestQuestion(actor).answer(build);
                requests.remove().run();
                check(network.nodes.size()==6 && !span.equals(network.nodeAt(startTile+1,surveyRow,0).trestle()),
                        "straight construction can reuse the cleared footprint");
                for (org.wurmrail.core.RailNetwork.Node piece : network.nodes.values()) {
                    com.wurmonline.server.Items.destroyItem(piece.markerId);
                    for (long id : piece.timber()) com.wurmonline.server.Items.destroyItem(id);
                }
                removeTrestleAt(actor,dioptra,requests,removeTrestle,startTile,surveyRow);
                check(network.nodes.isEmpty() && org.wurmrail.core.RailNetwork.load(file).nodes.isEmpty(),
                        "removal clears a fully destroyed span and its missing unused original approach");
            } finally {
                com.wurmonline.server.Items.destroyItem(buildPole.getWurmId());
                for (org.wurmrail.core.RailNetwork.Node node:network.nodes.values()) {
                    com.wurmonline.server.Items.destroyItem(node.markerId);
                    for (long timber:node.timber()) com.wurmonline.server.Items.destroyItem(timber);
                }
                for (int col = 0; col < original.length; col++) for (int row = 0; row < 2; row++)
                    mesh.setTile(startTile+col,surveyRow+row,original[col][row]);
            }
        } finally {
            com.wurmonline.server.Items.destroyItem(pole.getWurmId());
            com.wurmonline.server.Items.destroyItem(dioptra.getWurmId());
            PassengerWorldSmoke.removePlayer(actor);
        }
        System.out.println("PASS native trestle survey, 50% grade boundary, GM construction, locomotive crossing and saved deck and timber");
    }

    public static void runTrestleOverpasses() throws Exception {
        for (boolean stacked : new boolean[]{false,true}) {
            org.wurmrail.WurmRailRuntime runtime = new org.wurmrail.WurmRailRuntime();
            runtime.configure(new java.util.Properties());
            org.wurmrail.core.RailNetwork network = new org.wurmrail.core.RailNetwork();
            java.nio.file.Path save = java.nio.file.Paths.get("trestle-test",stacked ? "stacked.properties" : "over-rail.properties");
            for (String field : new String[]{"network","ready","stateFile"}) {
                Field member = runtime.getClass().getDeclaredField(field);
                member.setAccessible(true);
                member.set(runtime,field.equals("network") ? network : field.equals("ready") ? true : save);
            }
            Field queue = runtime.getClass().getDeclaredField("requests");
            queue.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Queue<Runnable> requests = (java.util.Queue<Runnable>)queue.get(runtime);
            int first = ModActions.getNextActionId();
            runtime.init();
            short start = (short)first, mark = (short)(first+1), remove = (short)(first+4);
            int low = 116, high = 123, crossing = 120;
            float mid = center(crossing);
            com.wurmonline.mesh.MeshIO mesh = com.wurmonline.server.Server.surfaceMesh;
            int[][] original = new int[10][10];
            for (int dx=0;dx<10;dx++) for (int dy=0;dy<10;dy++) {
                original[dx][dy]=mesh.getTile(low-1+dx,low-1+dy);
                overpassTerrain(mesh,low-1+dx,low-1+dy,50);
            }
            com.wurmonline.server.players.Player actor = PassengerWorldSmoke.player(stacked ? 714002448L : 714002192L,mid,mid);
            Field possessions = Creature.class.getDeclaredField("possessions");
            possessions.setAccessible(true);
            possessions.set(actor,new com.wurmonline.server.items.Possessions(actor));
            Item tool = com.wurmonline.server.items.ItemFactory.createItem(
                    com.wurmonline.server.items.ItemList.dioptra,50f,"Overpass fixture");
            check(actor.getInventory().insertItem(tool,true),"overpass survey has a dioptra");
            java.util.List<Item> cars = new java.util.ArrayList<>();
            try {
                float lowerHeight = stacked ? 12 : 5;
                if (stacked) {
                    for (int edge : new int[]{low,low+1,high,high+1})
                        for (int x : new int[]{crossing,crossing+1}) overpassTerrain(mesh,x,edge,120);
                    previewOverpass(actor,tool,requests,start,mark,crossing,low,crossing,high,lowerHeight);
                    buildOverpass(actor,requests);
                } else {
                    org.wurmrail.core.RailNetwork.Node previous = null;
                    for (int y=low;y<=high;y++) {
                        org.wurmrail.core.RailNetwork.Node rail = network.addNode("under_"+y,crossing,y,0);
                        rail.markerId = WurmRailWorld.spawnTrackMarker(actor,mid,center(y),0).getWurmId();
                        if (previous != null) network.connect(previous.name,rail.name);
                        previous=rail;
                    }
                }
                org.wurmrail.core.RailNetwork.Node lower = network.nodeAt(crossing,crossing,0);
                float tooLow = lowerHeight+2;
                setOverpassBank(mesh,low,high,crossing,(int)(tooLow*10));
                previewOverpass(actor,tool,requests,start,mark,low,crossing,high,crossing,tooLow);
                check(surveyBml.contains("4.0 m clearance") && !surveyBml.contains("GM build straight trestle"),
                        "survey rejects an upper deck too close to "+(stacked ? "a lower trestle" : "ground rail"));

                float upperHeight = 20;
                setOverpassBank(mesh,low,high,crossing,(int)(upperHeight*10));
                if (!stacked) {
                    org.wurmrail.core.RailNetwork.Node obstruction = network.addNode("under_bent",low+2,crossing,0);
                    obstruction.markerId=WurmRailWorld.spawnTrackMarker(actor,center(low+2),mid,0).getWurmId();
                    previewOverpass(actor,tool,requests,start,mark,low,crossing,high,crossing,upperHeight);
                    check(surveyBml.contains("timber bent conflicts with under_bent"),
                            "survey rejects a ground-founded support through the lower rail");
                    com.wurmonline.server.Items.destroyItem(obstruction.markerId);
                    network.removeNode(obstruction.name);
                }
                // Leave a locomotive on the lower line while the GM constructs the upper span.
                Item parked = WurmRailWorld.spawnLocomotive(actor,mid,mid,0);
                cars.add(parked);
                if (stacked) parked.setPosZ(lowerHeight);
                previewOverpass(actor,tool,requests,start,mark,low,crossing,high,crossing,upperHeight);
                buildOverpass(actor,requests);
                com.wurmonline.server.Items.destroyItem(parked.getWurmId());
                cars.remove(parked);
                java.util.List<org.wurmrail.core.RailNetwork.Node> rails = network.nodesAt(crossing,crossing,0);
                check(rails.size()==2 && rails.contains(lower),"GM build preserves the lower line and adds an upper node");
                org.wurmrail.core.RailNetwork.Node upper = rails.get(0)==lower ? rails.get(1) : rails.get(0);
                check(!lower.links.contains(upper.name),"overlapping rails stay disconnected");
                if (!stacked) {
                    // A failed ground-track removal restores this row even with a deck above it.
                    List<String> links = new java.util.ArrayList<>(lower.links);
                    long marker = lower.markerId;
                    network.removeNode(lower.name);
                    lower = network.addNode(lower.name,crossing,crossing,0);
                    lower.markerId = marker;
                    for (String link : links) network.connect(lower.name,link);
                    check(network.nodeAt(crossing,crossing,0)==lower && network.nodesAt(crossing,crossing,0).contains(upper),
                            "restoring the ordinary track beneath the overpass preserves both routes");
                }
                check(Math.abs(org.wurmrail.TrestleWalkingWorld.height(mid,mid,upperHeight)-upperHeight)<.001,
                        "native walking resolves the upper deck");
                float walkBelow = org.wurmrail.TrestleWalkingWorld.height(mid,mid,lowerHeight);
                check(stacked ? Math.abs(walkBelow-lowerHeight)<.001 : Float.isNaN(walkBelow),
                        "native walking resolves the lower deck or leaves ordinary terrain in control");
                checkOverpassWalking(actor,mid,upperHeight,90);
                if (stacked) checkOverpassWalking(actor,mid,lowerHeight,180);

                java.lang.reflect.Method apply = runtime.getClass().getDeclaredMethod("applyPoses",org.wurmrail.core.RailNetwork.Train.class);
                apply.setAccessible(true);
                org.wurmrail.core.RailNetwork.Train below = overpassTrain(actor,network,cars,"Below",crossing,low,crossing,low+1);
                org.wurmrail.core.RailNetwork.Train above = overpassTrain(actor,network,cars,"Above",low,crossing,low+1,crossing);
                apply.invoke(runtime,below);
                apply.invoke(runtime,above);
                network.start(below.name);
                network.start(above.name);
                boolean simultaneous = false;
                for (int tick=0;tick<160;tick++) {
                    network.tick(.1);
                    apply.invoke(runtime,below);
                    apply.invoke(runtime,above);
                    if (Math.abs(cars.get(0).getPosY()-mid)<.65 && Math.abs(cars.get(1).getPosX()-mid)<.65) {
                        check(Math.abs(cars.get(0).getPosZ()-lowerHeight)<.001 && Math.abs(cars.get(1).getPosZ()-upperHeight)<.001,
                                "native vehicles keep their own height while crossing simultaneously");
                        simultaneous = true;
                        network.save(save);
                        org.wurmrail.core.RailNetwork loaded = org.wurmrail.core.RailNetwork.load(save);
                        check(loaded.nodesAt(crossing,crossing,0).size()==2 && loaded.trains.size()==2
                                        && loaded.nodes.get(upper.name).markerId==upper.markerId,
                                "save and reload preserves both crossing routes and their trains");
                    }
                }
                check(simultaneous && cars.get(0).getPosY()>mid+4 && cars.get(1).getPosX()>mid+4,
                        "both native trains run through the crossing");
                for (org.wurmrail.core.RailNetwork.Train train : new org.wurmrail.core.RailNetwork.Train[]{below,above})
                    network.removeTrain(train.name);
                for (Item car : cars) com.wurmonline.server.Items.destroyItem(car.getWurmId());
                cars.clear();

                actor.getStatus().setPositionXYZ(mid,mid,lowerHeight);
                if (stacked) {
                    Item lowerDeck = com.wurmonline.server.Items.getItem(lower.markerId);
                    check(has(((BehaviourProvider)performer(remove)).getBehavioursFor(actor,tool,lowerDeck),remove),
                            "the lower deck offers its own removal action at the crossing");
                    dispatchSurvey(actor,tool,lower.markerId,remove);
                    check(requests.size()==1,"the lower marker selects its own span on the shared tile. "+surveyMessage);
                    requests.remove().run();
                    confirmOverpassRemoval(actor,requests);
                    check(network.nodesAt(crossing,crossing,0).get(0)==upper
                                    && Math.abs(org.wurmrail.TrestleWalkingWorld.height(mid,mid,upperHeight)-upperHeight)<.001,
                            "removing the lower trestle leaves the upper track and walking surface intact");
                }
                actor.getStatus().setPositionXYZ(mid,mid,upperHeight);
                dispatchSurvey(actor,tool,upper.markerId,remove);
                check(requests.size()==1,"the upper marker selects its own span on the shared tile");
                requests.remove().run();
                confirmOverpassRemoval(actor,requests);
                check(stacked ? network.nodes.isEmpty() : network.nodeAt(crossing,crossing,0)==lower && lower.links.size()==2,
                        "removing the upper trestle preserves unrelated track");
            } finally {
                for (Item car : cars) com.wurmonline.server.Items.destroyItem(car.getWurmId());
                for (org.wurmrail.core.RailNetwork.Node rail : network.nodes.values()) {
                    com.wurmonline.server.Items.destroyItem(rail.markerId);
                    for (long id : rail.timber()) com.wurmonline.server.Items.destroyItem(id);
                }
                com.wurmonline.server.Items.destroyItem(tool.getWurmId());
                PassengerWorldSmoke.removePlayer(actor);
                for (int dx=0;dx<10;dx++) for (int dy=0;dy<10;dy++)
                    mesh.setTile(low-1+dx,low-1+dy,original[dx][dy]);
            }
            System.out.println("PASS native trestle over "+(stacked ? "trestle" : "ordinary rail")
                    +": clearance, concurrent trains, walking, save and independent removal");
        }
    }

    private static float center(int tile) { return tile*4+2; }

    private static void checkOverpassWalking(Creature actor,float middle,float height,int heading) throws Exception {
        com.wurmonline.shared.util.MovementChecker movement = actor.getMovementScheme();
        float x = middle-(heading==90 ? 1 : 0), y = middle-(heading==180 ? 1 : 0);
        movement.setPosition(x,y,height,heading,0);
        movement.started = true;
        for (int i=0;i<100 && (heading==90 ? movement.getX() : movement.getY())<middle+.5;i++) {
            movement.movestep(.4f,movement.getX(),movement.getZ(),movement.getY(),-2500f,2500f,heading,(byte)1,0);
            check(Math.abs(movement.getZ()-height)<.005,"native walker stays on their chosen deck through the crossing");
        }
        check((heading==90 ? movement.getX() : movement.getY())>=middle+.5,"native walker passes the intersection");
        actor.getStatus().setPositionXYZ(middle,middle,height);
        check(org.wurmrail.TrestleWalkingWorld.needsExplicitHeight(actor),"observers receive the elevated walking height");
    }

    private static void overpassTerrain(com.wurmonline.mesh.MeshIO mesh,int x,int y,int dirt) {
        mesh.setTile(x,y,com.wurmonline.mesh.Tiles.encode((short)dirt,com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
    }

    private static void setOverpassBank(com.wurmonline.mesh.MeshIO mesh,int low,int high,int row,int dirt) {
        for (int edge : new int[]{low,low+1,high,high+1})
            for (int y : new int[]{row,row+1}) overpassTerrain(mesh,edge,y,dirt);
    }

    private static void previewOverpass(Creature actor,Item tool,java.util.Queue<Runnable> requests,short start,short mark,
                                        int x,int y,int endX,int endY,float height) throws Exception {
        Item pole = com.wurmonline.server.items.ItemFactory.createItem(
                com.wurmonline.server.items.ItemList.rangePole,50f,center(endX),center(endY),0,true,(byte)0,-10L,"Overpass fixture");
        try {
            actor.getStatus().setPositionXYZ(center(x),center(y),height);
            dispatchSurvey(actor,tool,com.wurmonline.mesh.Tiles.getTileId(x,y,0),start);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(center(endX),center(endY),height);
            dispatchSurvey(actor,tool,pole.getWurmId(),mark);
            requests.remove().run();
        } finally {
            com.wurmonline.server.Items.destroyItem(pole.getWurmId());
        }
    }

    private static void buildOverpass(Creature actor,java.util.Queue<Runnable> requests) throws Exception {
        check(surveyBml.contains("GM build straight trestle"),"overpass survey can build. "+surveyBml);
        java.util.Properties build = new java.util.Properties();
        build.setProperty("instant","true");
        latestQuestion(actor).answer(build);
        requests.remove().run();
    }

    private static void confirmOverpassRemoval(Creature actor,java.util.Queue<Runnable> requests) throws Exception {
        java.util.Properties confirmation = new java.util.Properties();
        confirmation.setProperty("remove","true");
        latestQuestion(actor).answer(confirmation);
        requests.remove().run();
    }

    private static org.wurmrail.core.RailNetwork.Train overpassTrain(Creature actor,org.wurmrail.core.RailNetwork network,
            java.util.List<Item> cars,String name,int x,int y,int nextX,int nextY) throws Exception {
        Item engine = WurmRailWorld.spawnLocomotive(actor,center(x),center(y),0);
        cars.add(engine);
        return network.createTrain(name,actor.getWurmId(),java.util.Collections.singletonList(engine.getWurmId()),
                network.nodeAt(x,y,0).name,network.nodeAt(nextX,nextY,0).name);
    }

    private static void removeTrestleAt(Creature actor,Item dioptra,java.util.Queue<Runnable> requests,
                                       short action,int x,int y) throws Exception {
        actor.getStatus().setPositionXYZ(x*4+2,y*4+2,0);
        int tile=com.wurmonline.server.Server.surfaceMesh.getTile(x,y);
        check(has(((BehaviourProvider)performer(action)).getBehavioursFor(actor,dioptra,x,y,true,tile),action),
                "GM removal menu uses the saved track registration on empty ground");
        dispatchSurvey(actor,dioptra,com.wurmonline.mesh.Tiles.getTileId(x,y,0),action);
        check(requests.size()==1,"native ground target queues the removal confirmation");
        requests.remove().run();
        com.wurmonline.server.questions.Question form = latestQuestion(actor);
        check("Remove railway trestle".equals(form.getTitle()),"the named span must be confirmed");
        java.util.Properties answer = new java.util.Properties();
        answer.setProperty("remove","true");
        form.answer(answer);
        form.answer(answer);
        check(requests.size()==1,"the captured removal answer is single use");
        requests.remove().run();
        check(!has(((BehaviourProvider)performer(action)).getBehavioursFor(actor,dioptra,x,y,true,tile),action),
                "removal clears the selected tile's trestle menu");
    }

    private static void checkRoundedSurvey(Creature actor, Item dioptra, java.util.Queue<Runnable> requests,
                                           short start, short waypoint, short mark, short remove,
                                           org.wurmrail.core.RailNetwork network, java.nio.file.Path file,
                                           WurmRailRuntime runtime) throws Exception {
        int x=180,y=130,leg=30;
        float cornerX=(x+leg)*4+2, startY=y*4+2, poleX=cornerX+.25f, poleY=startY+12.5f, endY=(y+leg)*4+2;
        org.wurmrail.core.TrestleAlignment curve=org.wurmrail.core.TrestleAlignment.waypoints(java.util.Arrays.asList(
                new org.wurmrail.core.TrestleAlignment.Point(x*4+4,startY,5),
                new org.wurmrail.core.TrestleAlignment.Point(poleX,poleY,0),
                new org.wurmrail.core.TrestleAlignment.Point(cornerX,endY-2,6)),20,1000,.5,2,4);
        com.wurmonline.mesh.MeshIO mesh=com.wurmonline.server.Server.surfaceMesh;
        int[][] original=new int[leg+6][leg+6];
        for(int a=0;a<original.length;a++) for(int b=0;b<original.length;b++) {
            original[a][b]=mesh.getTile(x+a,y+b-3);
            mesh.setTile(x+a,y+b-3,com.wurmonline.mesh.Tiles.encode((short)(b-3 < leg ? 50 : 60),
                    com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
        }
        Item corner = null, end = null;
        try {
            corner=com.wurmonline.server.items.ItemFactory.createItem(com.wurmonline.server.items.ItemList.rangePole,
                    50f,poleX,poleY,0,true,(byte)0,-10L,"Curve fixture");
            end=com.wurmonline.server.items.ItemFactory.createItem(com.wurmonline.server.items.ItemList.rangePole,
                    50f,cornerX,endY,0,true,(byte)0,-10L,"Curve fixture");
            checkTightTurnPreview(actor,dioptra,requests,start,waypoint,mark,x,y,end);
            actor.getStatus().setPositionXYZ(x*4+2,startY,5);
            dispatchSurvey(actor,dioptra,com.wurmonline.mesh.Tiles.getTileId(x,y,0),start);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(cornerX,poleY,5);
            dispatchSurvey(actor,dioptra,corner.getWurmId(),waypoint);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(cornerX,endY,6);
            dispatchSurvey(actor,dioptra,end.getWurmId(),mark);
            requests.remove().run();
            check(surveyBml.contains("Curved alignment") && surveyBml.contains("rail tiles")
                            && surveyBml.contains("GM build curved trestle"),
                    "a valid curve has its own GM build action");
            check(surveyMarkers.size()<=15 && surveyMarkers.values().stream().anyMatch(
                    p -> p[0]==poleX && p[1]==poleY && p[2]>5 && p[2]<6),
                    "the off-axis pole is included at its exact placed position in the preview");
            int[] bank = {mesh.getTile(x,y), mesh.getTile(x,y+1),
                    mesh.getTile(x+1,y), mesh.getTile(x+1,y+1)};
            try {
                for (int column = x; column <= x+1; column++) {
                    mesh.setTile(column,y,com.wurmonline.mesh.Tiles.encode((short)46,
                            com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
                    mesh.setTile(column,y+1,com.wurmonline.mesh.Tiles.encode((short)54,
                            com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
                }
                surveyAnswer(actor,requests,"preview",null,null);
                check(surveyBml.contains("GM build curved trestle"),
                        "a cross-sloped bank meets the deck at its centreline: "+surveyBml);
            } finally {
                for (int i = 0; i < bank.length; i++) mesh.setTile(x+i/2,y+i%2,bank[i]);
            }
            checkCurveEdge(actor,requests,mesh,curve);
            java.util.Properties cancel = new java.util.Properties();
            cancel.setProperty("cancel","true");
            latestQuestion(actor).answer(cancel);
            requests.remove().run();
            check(surveyMarkers.isEmpty(),"cancelling a curve clears its in-world beams");
            actor.getStatus().setPositionXYZ(x*4+2,startY,5);
            dispatchSurvey(actor,dioptra,com.wurmonline.mesh.Tiles.getTileId(x,y,0),start);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(cornerX,poleY,5);
            dispatchSurvey(actor,dioptra,corner.getWurmId(),waypoint);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(cornerX,endY,6);
            dispatchSurvey(actor,dioptra,end.getWurmId(),mark);
            requests.remove().run();
            String originalMap = mapPayload();
            int ridgeX=(int)(poleX/4), ridgeY=(int)(poleY/4);
            for (int ox=0;ox<2;ox++) for (int oy=0;oy<2;oy++)
                mesh.setTile(ridgeX+ox,ridgeY+oy,com.wurmonline.mesh.Tiles.encode((short)75,
                        com.wurmonline.mesh.Tiles.Tile.TILE_DIRT.id,(byte)0));
            surveyAnswer(actor,requests,"preview",null,null);
            check(surveyBml.contains("Automatic pole heights clear") && surveyBml.contains("GM build curved trestle")
                            && mapPayload().equals(originalMap),
                    "blank pole fits the ridge and retains the original horizontal curve: "+surveyBml);
            check(surveyBml.contains("Pole 1  |  Tile ") && surveyBml.contains("Ground 7.50 m  |  Planned rail")
                            && surveyBml.indexOf("Update preview") < surveyBml.indexOf("Intermediate pole elevations")
                            && surveyBml.matches("(?s).*Ground 7\\.50 m.*?\"};harray\\{input\\{id=\"pole0\".*"),
                    "pole labels occupy full-width lines above their fields and main controls come first");
            surveyAnswer(actor,requests,"preview","5",null);
            check(surveyBml.contains("above the planned rail") && !surveyBml.contains("Automatic pole heights clear"),
                    "a manually fixed elevation remains fixed even when terrain blocks the deck");
            surveyAnswer(actor,requests,"preview","150",null);
            check(surveyBml.contains("Grade from the start to pole 1") && !surveyBml.contains("GM build curved trestle"),
                    "grades are checked separately for edited sections");
            surveyAnswer(actor,requests,"zoomIn","9","2");
            String zoomed = mapPayload();
            check(!originalMap.equals(zoomed) && surveyBml.contains("Planned rail 9.00 m") &&
                            surveyBml.contains("Grade into destination -") && surveyBml.contains("GM build curved trestle"),
                    "zooming on an edited pole preserves its elevation and clears the raised ground: "+surveyBml);
            java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(java.util.Base64.getUrlDecoder().decode(zoomed));
            int routePoints=data.getShort() & 65535, marks=data.getShort() & 65535;
            data.position(4+routePoints*4+4);
            check(routePoints>3 && marks==3 && data.getShort()==256 && data.getShort()==96,
                    "zoom centers on the selected pole and carries the sampled curve");
            surveyAnswer(actor,requests,"zoomFit",null,null);
            check(originalMap.equals(mapPayload()) && surveyBml.contains("Planned rail 9.00 m"),"fit restores the map without resetting elevations");
            surveyAnswer(actor,requests,"preview","",null);
            float fittedHeight = surveyMarkers.values().stream().filter(p -> p[0]==poleX && p[1]==poleY)
                    .findFirst().get()[2];
            check(fittedHeight>7.5 && fittedHeight<9, "clearing the override restores terrain fitting at "+fittedHeight);
            curve = curve.withWaypointElevations(java.util.Collections.singletonList((double)fittedHeight),.5);
            java.util.Properties build = new java.util.Properties();
            build.setProperty("instant","true");
            latestQuestion(actor).answer(build);
            requests.remove().run();
            check(surveyMarkers.isEmpty() && network.nodes.size()>leg,
                    "native GM build installs the curved corridor and clears its preview: " + (network.nodes.isEmpty() ? surveyBml : ""));
            org.wurmrail.core.RailNetwork reloaded = org.wurmrail.core.RailNetwork.load(file);
            org.wurmrail.core.TrestleAlignment.TileSection first = curve.tileSections().get(0);
            java.lang.reflect.Method refresh = WurmRailRuntime.class.getDeclaredMethod("refreshTrackVisuals");
            refresh.setAccessible(true);
            java.lang.reflect.Method forget = RailModelRegistry.class.getDeclaredMethod("forgetCurvedDeck",long.class);
            forget.setAccessible(true);
            for (org.wurmrail.core.TrestleAlignment.TileSection section : curve.tileSections()) {
                org.wurmrail.core.RailNetwork.Node node = network.nodeAt(section.x,section.y,0);
                Item deck = com.wurmonline.server.Items.getItem(node.markerId);
                org.wurmrail.shared.TrestlePanel expected = reloaded.surveyedPanel(node.name);
                check(deck.getModelName().equals(expected.modelName()), "native deck publishes the saved panel geometry");
                forget.invoke(null,node.markerId);
            }
            refresh.invoke(runtime);
            float walking = TrestleWalkingWorld.height(poleX,poleY,fittedHeight);
            check(Math.abs(walking-fittedHeight)<.002,"the built deck walks at the automatic preview height above the ridge");
            for (org.wurmrail.core.TrestleAlignment.TileSection section : curve.tileSections()) {
                org.wurmrail.core.TrestleAlignment.Point seam = curve.at(section.entry);
                float height = TrestleWalkingWorld.height((float)seam.x,(float)seam.y,(float)seam.z);
                check(Math.abs(height-seam.z)<.002,"native server walking surface is continuous at curved seams");
            }
            for (org.wurmrail.core.RailNetwork.Node node : network.nodes.values()) {
                com.wurmonline.server.Items.destroyItem(node.markerId);
                for (long timber : node.timber()) com.wurmonline.server.Items.destroyItem(timber);
            }
            removeTrestleAt(actor,dioptra,requests,remove,first.x,first.y);
            check(org.wurmrail.core.RailNetwork.load(file).nodes.isEmpty(),"destroyed curved span can be removed from storage");
            System.out.println("PASS curved trestle survey: terrain-fitted and fixed heights, unclipped pole labels, route map and zoom, GM construction, saved walking surface and removal");
        } finally {
            if(corner!=null)com.wurmonline.server.Items.destroyItem(corner.getWurmId());
            if(end!=null)com.wurmonline.server.Items.destroyItem(end.getWurmId());
            for(int a=0;a<original.length;a++) for(int b=0;b<original.length;b++)
                mesh.setTile(x+a,y+b-3,original[a][b]);
        }
    }

    private static void checkTightTurnPreview(Creature actor,Item dioptra,java.util.Queue<Runnable> requests,
                                             short start,short waypoint,short mark,int x,int y,Item end) throws Exception {
        float poleX=x*4+5, poleY=y*4+2.8f;
        Item tight=com.wurmonline.server.items.ItemFactory.createItem(com.wurmonline.server.items.ItemList.rangePole,
                50f,poleX,poleY,0,true,(byte)0,-10L,"Tight approach fixture");
        try {
            actor.getStatus().setPositionXYZ(x*4+2,y*4+2,5);
            dispatchSurvey(actor,dioptra,com.wurmonline.mesh.Tiles.getTileId(x,y,0),start);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(poleX,poleY,5);
            dispatchSurvey(actor,dioptra,tight.getWurmId(),waypoint);
            requests.remove().run();
            actor.getStatus().setPositionXYZ(end.getPosX(),end.getPosY(),6);
            dispatchSurvey(actor,dioptra,end.getWurmId(),mark);
            requests.remove().run();
            check(surveyBml.contains("Zoom to tight turn") && surveyBml.contains("Tightest sampled point")
                            && surveyBml.contains("Minimum curve radius") && surveyBml.contains("Align the nearby pole")
                            && !surveyBml.contains("GM build curved trestle"),
                    "a rejected survey draws and explains the specific curve without allowing construction: "+surveyBml);
            String original = mapPayload();
            surveyAnswer(actor,requests,"zoomIssue",null,null);
            check(!original.equals(mapPayload()),"zoom to tight turn changes the map projection");
            java.util.regex.Matcher issue = java.util.regex.Pattern.compile("trestlemapissue=\"([a-zA-Z0-9_-]+)\"").matcher(surveyBml);
            check(issue.find(),"the rejected curve has a diagnostic map payload");
            java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(java.util.Base64.getUrlDecoder().decode(issue.group(1)));
            check(data.getShort()==0 && data.getShort()==256 && data.getShort()==96,
                    "zoom centres the actual tight spot on the rejected first section");
        } finally {
            com.wurmonline.server.Items.destroyItem(tight.getWurmId());
        }
    }

    private static String mapPayload() {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("trestlemap=\"([a-zA-Z0-9_-]+)\"").matcher(surveyBml);
        check(match.find(),"the survey supplies a map payload");
        return match.group(1);
    }

    private static void surveyAnswer(Creature actor,java.util.Queue<Runnable> requests,String action,String elevation,String focus) throws Exception {
        java.util.Properties answer=new java.util.Properties();
        answer.setProperty(action,"true");
        if (elevation!=null) answer.setProperty("pole0",elevation);
        if (focus!=null) answer.setProperty("mapFocus",focus);
        latestQuestion(actor).answer(answer);
        requests.remove().run();
    }

    private static void checkCurveEdge(Creature actor,java.util.Queue<Runnable> requests,
                                       com.wurmonline.mesh.MeshIO mesh,org.wurmrail.core.TrestleAlignment alignment)
            throws Exception {
        int samples=(int)Math.ceil(alignment.length*2);
        java.util.Set<org.wurmrail.core.TrestleAlignment.Point> edgeSamples=new java.util.LinkedHashSet<>();
        java.util.Set<String> centerTiles=new java.util.HashSet<>();
        for(int i=0;i<=samples;i++) {
            double distance=alignment.length*((double)i/samples);
            org.wurmrail.core.TrestleAlignment.Point center=alignment.at(distance);
            centerTiles.add((int)(center.x/4)+", "+(int)(center.y/4));
            edgeSamples.add(alignment.offsetAt(distance,-.96));
        }
        for(org.wurmrail.core.TrestleAlignment.Point side:edgeSamples) {
            int x=(int)(side.x/4),y=(int)(side.y/4);
            String tile=x+", "+y;
            if(centerTiles.contains(tile))continue;
            int original=mesh.getTile(x,y);
            try {
                mesh.setTile(x,y,com.wurmonline.mesh.Tiles.encode((short)50,
                        com.wurmonline.mesh.Tiles.Tile.TILE_LAVA.id,(byte)0));
                java.util.Properties preview=new java.util.Properties();
                preview.setProperty("preview","true");
                latestQuestion(actor).answer(preview);
                requests.remove().run();
                check(surveyBml.contains("lava at "+tile) && surveyMarkers.isEmpty(),
                        "the curve checks an obstruction at the deck edge outside all centreline tiles");
                return;
            } finally {
                mesh.setTile(x,y,original);
            }
        }
        throw new AssertionError("The test deck should overhang a neighbouring tile.");
    }

    private static void checkWalking(Creature actor, int startTile, int row) throws Exception {
        com.wurmonline.shared.util.MovementChecker movement = actor.getMovementScheme();
        float start = startTile * 4 + 2, end = start + 20, y = row * 4 + 2;
        float seam = start + 2;
        float midpoint = start + 10;
        float surface = org.wurmrail.TrestleWalkingWorld.height(midpoint, y, 9.1f);
        check(Math.abs(surface - 9.1f) < .001f, "the installed deck supplies its saved walking height (" + surface + ")");
        check(Float.isNaN(org.wurmrail.TrestleWalkingWorld.height(midpoint, y, -8f)),
                "the elevated deck is unreachable from the valley floor");
        check(Float.isNaN(org.wurmrail.TrestleWalkingWorld.height(midpoint, y + 1.1f, 10f)),
                "the ground beside the deck retains its own height");
        movement.setPosition(start, y, 13.1f, 90, 0);
        boolean wasStarted = movement.started;
        movement.started = true;
        for (int direction : new int[]{90, 270}) {
            boolean crossedDeck = false;
            for (int tick = 0; tick < 900; tick++) {
                movement.movestep(0.4f, movement.getX(), movement.getZ(), movement.getY(),
                        -2500f, 2500f, direction, (byte)1, 0);
                float x = movement.getX();
                if (x >= seam && x <= end - 2) {
                    crossedDeck = true;
                    check(Math.abs(movement.getZ() - (13.1f - (x - seam) * .5f)) < .005f,
                            "native walker follows the timber deck over the valley (x=" + x
                                    + ", y=" + movement.getY() + ", z=" + movement.getZ()
                                    + ", heading=" + direction + ")");
                }
                if (direction == 90 && x >= end || direction == 270 && x <= start) break;
            }
            float target = direction == 90 ? end : start;
            check(crossedDeck && Math.abs(movement.getX() - target) < .4f
                            && Math.abs(movement.getZ() - (direction == 90 ? 5.1f : 13.1f)) < .3f,
                    "native walking crosses both joins and reaches the ground approach, heading " + direction
                            + " (x=" + movement.getX() + ", z=" + movement.getZ() + ")");
        }
        movement.started = wasStarted;
        actor.getStatus().setPositionXYZ(midpoint, y, 9.1f);
        java.lang.reflect.Method level = com.wurmonline.server.zones.VirtualZone.class
                .getDeclaredMethod("isMovingSameLevel", Creature.class);
        level.setAccessible(true);
        check(!(Boolean)level.invoke(null, actor), "observers receive the walking player's explicit deck elevation");
        actor.getStatus().setPositionXYZ(end, y, 5.1f);
        System.out.println("PASS native player walking on both trestle approaches and the 50% deck, ground below and observer height");
    }

    private static void dispatchSurvey(Creature actor, Item tool, long target, short action) throws Exception {
        actor.getActions().clear();
        surveyMessage=null;
        com.wurmonline.server.behaviours.BehaviourDispatcher.action(
                actor,actor.getCommunicator(),tool == null ? -1L : tool.getWurmId(),target,action);
        actor.getActions().poll(actor);
    }

    private static final class Calls implements RailwayMenuActions.Api {
        boolean builder;
        int builds, vehicles, cycles, x, y, layer;
        long target;
        String verb;
        Creature actor;
        @Override public boolean canBuild(Creature p) { return builder; }
        @Override public void openBuilder(Creature p, int x, int y, int layer) {
            builds++; this.actor=p; this.x=x; this.y=y; this.layer=layer;
        }
        @Override public void openVehicle(Creature p, Item target, String verb) {
            vehicles++; this.actor=p; this.target=target.getWurmId(); this.verb=verb;
        }
        @Override public void cycleAlignment(Creature p, Item target) {
            cycles++; this.actor=p; this.target=target.getWurmId();
        }
    }

    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        Creature actor=UsabilitySmoke.actor(7741L,202,202);
        Item engine=WurmRailWorld.spawnLocomotive(null,202,202,0);
        Item closestCart=WurmRailWorld.spawnCart(null,203,202,0);
        Item clickedCart=WurmRailWorld.spawnCart(null,206,202,0);
        Item marker=WurmRailWorld.spawnTrackMarker(null,202,202,0);
        Item active=com.wurmonline.server.items.ItemFactory.createItem(
                com.wurmonline.server.items.ItemList.plank,50.0f,"Railway UI fixture");
        int firstId=ModActions.getNextActionId();
        Field providersField=ModActions.class.getDeclaredField("behaviourProviders");
        providersField.setAccessible(true);
        List<BehaviourProvider> providers=(List<BehaviourProvider>)providersField.get(null);
        int firstProvider=providers.size();
        Calls calls=new Calls();
        RailwayMenuActions.register(calls);
        check(ModActions.getNextActionId()==firstId+5 && providers.size()==firstProvider+5,
                "native menu registration keeps five rail controls and delegates boarding/storage to native actions");
        BehaviourProvider terrain=providers.get(firstProvider);
        BehaviourProvider track=providers.get(firstProvider+1);
        short groundId=(short)firstId;
        ActionPerformer ground=performer(groundId);
        int surface=com.wurmonline.server.Server.surfaceMesh.getTile(50,50);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null,
                "builder ACL must hide terrain entry");
        check(track.getBehavioursFor(actor,marker)==null,"builder ACL must hide track edits");
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        check(calls.builds==0,"forged terrain action must recheck builder ACL");
        calls.builder=true;
        check(has(terrain.getBehavioursFor(actor,50,50,true,surface),groundId),
                "surface tile menu without active source");
        check(has(terrain.getBehavioursFor(actor,active,50,50,true,surface),groundId),
                "surface tile menu with active source");
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        check(calls.builds==1 && calls.x==50 && calls.y==50 && calls.layer==0 && calls.actor==actor,
                "ground action must preserve clicked coordinates and actor");
        ground.action(null,actor,active,50,50,true,0,surface,groundId,1f);
        check(calls.builds==2,"active ground action uses native heightOffset,tile argument order");
        ground.action(null,actor,active,50,50,true,30,surface,groundId,1f);
        ground.action(null,actor,50,50,true,surface,(short)(groundId+1),1f);
        check(calls.builds==2,"elevated and mismatched action IDs cannot invoke builder");
        check(terrain.getBehavioursFor(actor,50,50,true,true,surface,0)==null,
                "tile corner menus must remain separate from ground railway construction");
        check(has(track.getBehavioursFor(actor,marker),(short)(firstId+1))
                        && has(track.getBehavioursFor(actor,active,marker),(short)(firstId+1)),
                "fixed track menu must work with or without active source");
        performer((short)(firstId+1)).action(null,actor,active,marker,(short)(firstId+1),1f);
        check(calls.builds==3 && calls.x==marker.getTileX() && calls.y==marker.getTileY(),
                "track menu must anchor editing at the clicked track");

        BehaviourProvider cycle=providers.get(firstProvider+4);
        short cycleId=(short)(firstId+4);
        ActionPerformer cycleAction=performer(cycleId);
        check(cycle.getBehavioursFor(actor,marker)==null&&cycle.getBehavioursFor(actor,engine)==null,
                "ordinary track and vehicles have no physical junction cycle action");
        marker.setAuxData((byte)21); // N, E, S form a three-ended junction.
        check(has(cycle.getBehavioursFor(actor,marker),cycleId)
                        &&has(cycle.getBehavioursFor(actor,active,marker),cycleId),
                "three-ended junction exposes a direct cycle with and without active item");
        cycleAction.action(null,actor,marker,cycleId,1f);
        cycleAction.action(null,actor,active,marker,cycleId,1f);
        check(calls.cycles==2&&calls.target==marker.getWurmId()&&calls.actor==actor,
                "direct cycle preserves the clicked junction identity without opening a window");
        calls.builder=false;
        check(cycle.getBehavioursFor(actor,marker)==null,"builder ACL hides direct alignment control");
        cycleAction.action(null,actor,marker,cycleId,1f);
        calls.builder=true;
        cycleAction.action(null,actor,marker,(short)(cycleId-1),1f);
        check(calls.cycles==2,"forged ACL or mismatched action cannot change junction alignment");
        marker.setAuxData((byte)17);
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.cycles==2&&cycle.getBehavioursFor(actor,marker)==null,
                "a two-ended straight cannot be treated as a junction");
        marker.setAuxData((byte)255);
        check(has(cycle.getBehavioursFor(actor,marker),cycleId),"unsigned eight-direction junction mask remains selectable");
        marker.setAuxData((byte)21);
        org.wurmrail.core.RailNetwork graph=new org.wurmrail.core.RailNetwork();
        org.wurmrail.core.RailNetwork.Node node=graph.addNode("uiJunction",50,50,0);
        node.markerId=marker.getWurmId();
        graph.addNode("uiNorth",50,49,0); graph.addNode("uiEast",51,50,0); graph.addNode("uiSouth",50,51,0);
        graph.connect(node.name,"uiNorth"); graph.connect(node.name,"uiEast"); graph.connect(node.name,"uiSouth");
        Item indicator=WurmRailWorld.spawnJunctionIndicator(node);
        indicator.setAuxData((byte)5); // The physical N-to-E pair has only two visible ends.
        check(has(cycle.getBehavioursFor(actor,indicator),cycleId)
                        &&has(cycle.getBehavioursFor(actor,active,indicator),cycleId),
                "the separate physical alignment indicator exposes the same direct cycle");
        cycleAction.action(null,actor,active,indicator,cycleId,1f);
        check(calls.cycles==3&&calls.target==indicator.getWurmId(),"indicator cycle keeps its own clicked item identity");
        check(has(track.getBehavioursFor(actor,indicator),(short)(firstId+1)),"physical indicator also opens its track builder");
        performer((short)(firstId+1)).action(null,actor,indicator,(short)(firstId+1),1f);
        check(calls.builds==4&&calls.x==50&&calls.y==50,"indicator builder anchors at its junction tile");

        // Cave requests have an additional direction: zero is floor, one is ceiling.
        actor.getStatus().getPosition().setLayer(-1);
        actor.getStatus().getPosition().setPosZ(2,false);
        int cave=com.wurmonline.server.Server.caveMesh.getTile(50,50);
        check(has(terrain.getBehavioursFor(actor,50,50,false,cave,0),groundId)
                        && has(terrain.getBehavioursFor(actor,active,50,50,false,cave,0),groundId),
                "both native cave floor request overloads must expose the builder");
        check(terrain.getBehavioursFor(actor,50,50,false,cave,1)==null
                        && terrain.getBehavioursFor(actor,active,50,50,false,cave,2)==null,
                "cave ceilings and walls must not expose floor construction");
        ground.action(null,actor,50,50,false,cave,0,groundId,1f);
        ground.action(null,actor,active,50,50,false,0,cave,0,groundId,1f);
        check(calls.builds==6 && calls.layer==-1,"both cave floor action overloads must preserve cave layer");
        ground.action(null,actor,50,50,false,cave,1,groundId,1f);
        ground.action(null,actor,active,50,50,false,0,cave,2,groundId,1f);
        check(calls.builds==6,"forged cave ceiling/wall actions cannot open the builder");
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.cycles==3&&cycle.getBehavioursFor(actor,marker)==null,
                "direct alignment cannot cross surface and cave layers");
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null,
                "surface construction must be unavailable from cave layer");

        actor.getStatus().getPosition().setLayer(0);
        actor.getStatus().getPosition().setPosZ(5,false);
        for(int offset=2;offset<4;offset++) {
            short id=(short)(firstId+offset);
            BehaviourProvider provider=providers.get(firstProvider+offset);
            ActionPerformer performer=performer(id);
            String expected=new String[]{"panel","couple"}[offset-2];
            check(has(provider.getBehavioursFor(actor,engine),id)
                            && has(provider.getBehavioursFor(actor,active,clickedCart),id),
                    "vehicle menu with and without active source: "+expected);
            check(provider.getBehavioursFor(actor,active)==null
                            && provider.getBehavioursFor(actor,marker)==null,
                    "vanilla items and tracks must not expose vehicle actions: "+expected);
            int before=calls.vehicles;
            performer.action(null,actor,active,clickedCart,id,1f);
            check(calls.vehicles==before+1 && calls.target==clickedCart.getWurmId()
                            && calls.target!=closestCart.getWurmId() && expected.equals(calls.verb),
                    "active-item action must retain the clicked wagon instead of nearest wagon: "+expected);
            performer.action(null,actor,engine,id,1f);
            check(calls.vehicles==before+2 && calls.target==engine.getWurmId(),
                    "no-source action must retain the clicked engine: "+expected);
            performer.action(null,actor,active,id,1f);
            check(calls.vehicles==before+2,"forged vanilla target must not invoke callback");
        }

        actor.getStatus().getPosition().setPosX(230);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,clickedCart)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "distant terrain and vehicles must not expose railway actions");
        int beforeBuilds=calls.builds, beforeVehicles=calls.vehicles;
        ground.action(null,actor,50,50,true,surface,groundId,1f);
        performer((short)(firstId+2)).action(null,actor,engine,(short)(firstId+2),1f);
        cycleAction.action(null,actor,marker,cycleId,1f);
        check(calls.builds==beforeBuilds && calls.vehicles==beforeVehicles,
                "forged out-of-range actions must not invoke callbacks");
        check(calls.cycles==3,"out-of-range direct alignment is rejected before the callback");
        actor.getStatus().getPosition().setPosX(202);
        actor.getStatus().getPosition().setPosZ(9,false);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,engine)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "different floor must not expose ground actions");
        actor.getStatus().getPosition().setPosZ(5,false);
        actor.getStatus().getPosition().setBridgeId(987654321L);
        check(terrain.getBehavioursFor(actor,50,50,true,surface)==null
                        && providers.get(firstProvider+2).getBehavioursFor(actor,engine)==null
                        && cycle.getBehavioursFor(actor,marker)==null,
                "different bridge must not expose ground actions");
        check(engine.getPosX()==202 && clickedCart.getPosX()==206,
                "menu callbacks must not move world items");
        String hostile="Depot,one;'};button{id='start'};{\\\"\n";
        String safe=RailwayBml.text(hostile), option=RailwayBml.option(hostile);
        check(safe.indexOf('{')<0 && safe.indexOf('}')<0 && safe.indexOf(';')<0
                        && safe.indexOf('"')<0 && safe.indexOf('\'')<0 && safe.indexOf('\\')<0
                        && safe.indexOf('\n')<0 && option.indexOf(',')<0,
                "hostile display names cannot change BML structure or dropdown indexes");
        signalActions(marker,indicator,engine,active,providers);
        signalQuestionReplay();
        System.out.println("PASS native railway menus and direct physical junction cycling: clicked tracks/indicators, surface/cave active and inactive source overloads, ACL, range, level, bridge and forged actions");
    }

    private static void signalActions(Item marker,Item indicator,Item engine,Item active,List<BehaviourProvider> providers) throws Exception {
        Creature actor=TrackMaterialsSmoke.actor(720004544L,true,0);
        actor.getStatus().getPosition().setPosX(202); actor.getStatus().getPosition().setPosY(202);
        Item signal=WurmRailWorld.spawnSignal(marker.getWurmId(),202,202,0,0);
        final int[] calls={0}; final long[] clicked={-10L};
        int first=ModActions.getNextActionId(),providerIndex=providers.size();
        RailwaySignalActions.register(new RailwaySignalActions.Api() {
            @Override public void open(Creature p,long clickedItemId) {
                check(p==actor,"signal action retains the real actor"); calls[0]++; clicked[0]=clickedItemId;
            }
        });
        check(ModActions.getNextActionId()==first+1 && providers.size()==providerIndex+1,
                "signals register exactly one additional inspect/configure action");
        short id=(short)first; BehaviourProvider provider=providers.get(providerIndex); ActionPerformer action=performer(id);
        for(Item target:new Item[]{marker,indicator,signal}) {
            check(has(provider.getBehavioursFor(actor,target),id) && has(provider.getBehavioursFor(actor,active,target),id),
                    "track, junction indicator and signal expose both native item action overloads");
            action.action(null,actor,target,id,1f); action.action(null,actor,active,target,id,1f);
            check(clicked[0]==target.getWurmId(),"signal menu queues the exact clicked item rather than the nearest post");
        }
        check(calls[0]==6,"one signal action callback is issued for each valid direct interaction");
        action.action(null,actor,engine,id,1f); action.action(null,actor,active,id,1f);
        action.action(null,actor,signal,(short)(id+1),1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,engine)==null && provider.getBehavioursFor(actor,active)==null,
                "wrong templates and forged action IDs cannot open a signal window");
        actor.getStatus().getPosition().setPosX(211);
        check(provider.getBehavioursFor(actor,signal)==null,"signal inspection uses eight metre native item reach");
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6,"forged distant signal action rechecks reach");
        actor.getStatus().getPosition().setPosX(202);
        actor.getStatus().getPosition().setLayer(-1);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"signal inspection cannot cross surface/cave layers");
        actor.getStatus().getPosition().setLayer(0);
        actor.getStatus().getPosition().setPosZ(9,false);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"elevated performers cannot inspect a ground signal");
        actor.getStatus().getPosition().setPosZ(5,false);
        actor.getStatus().getPosition().setBridgeId(987654321L);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"bridge performers cannot target a ground signal");
        actor.getStatus().getPosition().setBridgeId(-10L);
        // Native DbItem.setParentId's boolean is the item's surface layer, not a save flag.
        signal.setParentId(actor.getInventory().getWurmId(),true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"contained signal items cannot invoke configuration");
        signal.setParentId(-10L,true);
        check(signal.isOnSurface() && has(provider.getBehavioursFor(actor,signal),id),
                "restoring the contained fixture preserves its original surface layer and readable ground context");
        signal.setBanked(true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"banked signal items cannot invoke configuration");
        signal.setBanked(false); signal.setMailed(true);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"mailed signal items cannot invoke configuration");
        signal.setMailed(false);
        signal.setOwnerId(actor.getWurmId());
        action.action(null,actor,signal,id,1f);
        check(calls[0]==6 && provider.getBehavioursFor(actor,signal)==null,"owned signal items cannot invoke ground configuration");
        signal.setOwnerId(-10L);
        action.action(null,actor,signal,id,1f);
        check(calls[0]==7,"ordinary players may inspect signal diagnostics without builder permissions");
        com.wurmonline.server.Items.destroyItem(signal.getWurmId());
        System.out.println("PASS native signal inspect action: exact clicked track/indicator/post, active/inactive source overloads, visitor access, forged IDs, range, layer, floor, bridge and contained/banked/mailed/owned guards");
    }

    private static void signalQuestionReplay() throws Exception {
        final Creature actor=TrackMaterialsSmoke.actor(720004288L,true,0);
        final RailwaySignalUi.View view=new RailwaySignalUi.View("capturedSignalTrack",987L,4L,"Replay fixture",
                java.util.Arrays.asList(0,4),java.util.Collections.<RailwaySignalUi.Signal>emptyList(),true,1,false,0);
        final int[] submitted={0};
        RailwaySignalUi.Api api=new RailwaySignalUi.Api() {
            @Override public RailwaySignalUi.View snapshot(Creature p,long id) { return view; }
            @Override public void submit(Creature p,long id,RailwaySignalUi.Request request) {
                check(p==actor && id==987L && request.expectedMarkerId==987L && request.revision==4L
                                && request.expectedNodeKey.equals("capturedSignalTrack") && request.approach==4,
                        "native signal form retains captured clicked identity, definition revision and absolute approach");
                submitted[0]++;
            }
        };
        RailwaySignalUi.show(actor,987L,api);
        com.wurmonline.server.questions.Question question=latestQuestion(actor);
        java.util.Properties place=new java.util.Properties();
        place.setProperty("place","true"); place.setProperty("approach_choice","1");
        place.setProperty("approach","0"); place.setProperty("expectedMarkerId","999");
        question.answer(place); question.answer(place);
        check(submitted[0]==1,"native signal ModQuestionImpl repeated answer queues only one construction request");
        System.out.println("PASS native signal ModQuestionImpl captured approach and single-use answer replay");
    }

    @SuppressWarnings("unchecked")
    private static com.wurmonline.server.questions.Question latestQuestion(Creature actor) throws Exception {
        Field registry=com.wurmonline.server.questions.Questions.class.getDeclaredField("questions"); registry.setAccessible(true);
        com.wurmonline.server.questions.Question latest=null;
        for(com.wurmonline.server.questions.Question question:((Map<Integer,com.wurmonline.server.questions.Question>)registry.get(null)).values())
            if(question.getResponder()==actor && (latest==null || question.getId()>latest.getId())) latest=question;
        check(latest!=null,"native signal question is registered for its real responder"); return latest;
    }

    @SuppressWarnings("unchecked")
    private static ActionPerformer performer(short id) throws Exception {
        Field registry=ModActions.class.getDeclaredField("actionPerformers");
        registry.setAccessible(true);
        Object chain=((Map<Short,?>)registry.get(null)).get(id);
        Field entries=chain.getClass().getDeclaredField("actionPerformers");
        entries.setAccessible(true);
        List<ActionPerformer> performers=(List<ActionPerformer>)entries.get(chain);
        check(performers.size()==1,"fixture action ID must resolve exactly one performer");
        return performers.get(0);
    }

    private static boolean has(List<ActionEntry> entries, short id) {
        if(entries==null) return false;
        for(ActionEntry entry:entries) if(entry.getNumber()==id) return true;
        return false;
    }

    private static void check(boolean condition,String reason) {
        if(!condition) throw new AssertionError(reason);
    }
}
