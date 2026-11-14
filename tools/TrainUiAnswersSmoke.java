import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.RailwayTrainUi.Action;
import org.wurmrail.RailwayTrainUi.Choice;
import org.wurmrail.RailwayTrainUi.Request;
import org.wurmrail.RailwayTrainUi.Service;
import org.wurmrail.RailwayTrainUi.View;
import org.wurmrail.core.RailNetwork;

/** Pure answer-decoding regression cases; no server mutation is performed by UI drafts. */
public final class TrainUiAnswersSmoke {
    private static int assertions;
    public static void main(String[] args) { run(); }

    public static void run() {
        RailNetwork network=new RailNetwork();
        network.addNode("A",10,10,0); network.addNode("B",11,10,0); network.connect("A","B");
        View operator=view();
        operator.train=network.createTrain("Train_1",99L,Collections.singletonList(11L),"A","B");
        operator.canControl=true;
        check(RailwayTrainUi.fromAnswers(operator,answer("board","car","1")).choice.equals("22"),
                "boarding submits the selected item identity, not a caller supplied item ID");
        check(RailwayTrainUi.fromAnswers(operator,answer("cargo","car","0")).choice.equals("11"),
                "cargo uses only a car offered in the original window");
        check(RailwayTrainUi.content(operator).contains("id='board'"),"unmounted operator can board from the control window");
        operator.aboard=true;
        check(!RailwayTrainUi.content(operator).contains("id='board'")&&RailwayTrainUi.content(operator).contains("id='cargo'"),
                "mounted operator retains cargo without a redundant Board control");
        rejected(() -> RailwayTrainUi.fromAnswers(operator,answer("board","car","1")),"mounted operator cannot forge a hidden Board control");
        check(RailwayTrainUi.fromAnswers(operator,answer("cargo","car","0")).action==Action.CARGO,
                "mounted operator retains the selected native cargo shortcut");
        operator.aboard=false;
        check(RailwayTrainUi.fromAnswers(operator,answer("couple","wagon","1")).choice.equals("44"),
                "coupling preserves the exact chosen wagon");
        check(RailwayTrainUi.fromAnswers(operator,answer("speedApply","speed","2.5")).speed==2.5,
                "valid speed is decoded");
        for(String preset:new String[]{"speedCrawl","speedCruise","speedFull"}) {
            double expected=preset.equals("speedCrawl")?.5:preset.equals("speedCruise")?1.5:operator.speedLimit;
            Request selected=RailwayTrainUi.fromAnswers(operator,answer(preset,"speed","NaN"));
            check(selected.action==Action.SPEED&&selected.speed==expected,
                    "preset uses its captured value and ignores unrelated numeric input: "+preset);
        }
        operator.speedLimit=1;
        check(RailwayTrainUi.fromAnswers(operator,answer("speedCrawl","speed","6")).speed==.5,"Crawl uses half a metre per second below the server cap");
        check(RailwayTrainUi.fromAnswers(operator,answer("speedCruise","speed","6")).speed==1,"Cruise respects a reduced server cap");
        check(RailwayTrainUi.fromAnswers(operator,answer("speedFull","speed","6")).speed==1,"Full uses the server cap rather than client input");
        operator.speedLimit=.25;
        for(String preset:new String[]{"speedCrawl","speedCruise","speedFull"})
            check(RailwayTrainUi.fromAnswers(operator,answer(preset,"speed","6")).speed==.25,"preset respects the minimum server cap: "+preset);
        operator.speedLimit=5;
        check(RailwayTrainUi.fromAnswers(operator,answer("speedApply","speed","5")).speed==5,
                "five metres per second is an available forward and reverse speed limit");
        for(String invalid:new String[]{"0.24","5.01","NaN","Infinity","1e999","not a speed"})
            rejected(() -> RailwayTrainUi.fromAnswers(operator,answer("speedApply","speed",invalid)),"invalid speed rejected");
        for(String index:new String[]{"-1","2","22","not an index"})
            rejected(() -> RailwayTrainUi.fromAnswers(operator,answer("board","car",index)),"forged dropdown index rejected");
        Properties stop=answer("stop","speed","NaN"); stop.setProperty("name","bad\nname");
        check(RailwayTrainUi.fromAnswers(operator,stop).action==Action.STOP,
                "unrelated settings cannot obstruct emergency stop or cause hidden settings changes");
        operator.powered=true;operator.speed=0;operator.status="Signal entry RED: Protected route is occupied by train B.";
        Properties recover=answer("reverse","speed","NaN");recover.setProperty("name","bad\nname");
        check(RailwayTrainUi.fromAnswers(operator,recover).action==Action.REVERSE,
                "powered red-waiting train offers a single Reverse recovery without parsing unrelated settings");
        check(RailwayTrainUi.fromAnswers(operator,stop).action==Action.STOP,
                "powered red-waiting train retains the emergency STOP action");
        Properties mixedRecover=answer("reverse","speed","NaN");mixedRecover.setProperty("start","true");
        rejected(()->RailwayTrainUi.fromAnswers(operator,mixedRecover),"red recovery cannot combine Reverse and Start in one form answer");
        operator.powered=false;
        Properties multiple=answer("stop","speed","3"); multiple.setProperty("start","true");
        rejected(() -> RailwayTrainUi.fromAnswers(operator,multiple),"multiple pressed actions rejected");
        Properties multipleSpeeds=answer("speedCrawl","speed","3"); multipleSpeeds.setProperty("speedFull","true");
        rejected(() -> RailwayTrainUi.fromAnswers(operator,multipleSpeeds),"multiple speed presets rejected");
        Properties speedAndStop=answer("speedCruise","speed","3"); speedAndStop.setProperty("stop","true");
        rejected(() -> RailwayTrainUi.fromAnswers(operator,speedAndStop),"preset and a second action cannot be combined");
        check(RailwayTrainUi.fromAnswers(operator,new Properties())==null,"closing the window makes no request");
        check(RailwayTrainUi.fromAnswers(operator,answer("close","speed","NaN"))==null,"close ignores unrelated input");
        Properties unknown=new Properties(); unknown.setProperty("delete_everything","true");
        check(RailwayTrainUi.fromAnswers(operator,unknown)==null,"unknown buttons cannot select an action");
        Properties malformed=new Properties(); malformed.setProperty("start","TRUE");
        check(RailwayTrainUi.fromAnswers(operator,malformed)==null,"button flags require the native exact true value");
        Request rename=RailwayTrainUi.fromAnswers(operator,answer("rename","name","  Mountain Express  "));
        check(rename.action==Action.RENAME&&rename.text.equals("Mountain Express"),"display names retain spaces and trim outer whitespace");
        for(String name:new String[]{"  ","bad\nname",String.join("",Collections.nCopies(41,"x"))})
            rejected(() -> RailwayTrainUi.fromAnswers(operator,answer("rename","name",name)),"invalid display name rejected");
        List<String> originalRoute=new ArrayList<String>(operator.route);
        Request openRoute=RailwayTrainUi.fromAnswers(operator,answer("route","unused","ignored"));
        operator.route.add("later");
        check(openRoute.action==Action.ROUTE_EDIT&&openRoute.route.equals(originalRoute),
                "route request owns an immutable copy of the displayed route");
        immutable(openRoute.route,"request route cannot be changed after queueing");
        operator.route.remove(operator.route.size()-1);
        check(RailwayTrainUi.fromAnswers(operator,answer("manage","unused","ignored")).action==Action.MANAGE,
                "management opens a separate native window without an implicit train mutation");
        operator.nextStop="Mine [Station]";
        operator.trafficStatus="Queued for shared connection; waiting for Harbor express";
        String driving=RailwayTrainUi.content(operator),management=RailwayTrainUi.managementContent(operator);
        check(driving.contains("Next stop: Mine [Station]")
                        &&driving.contains("Traffic: Queued for shared connection: waiting for Harbor express"),
                "driving window shows captured next stop and dispatcher wait reason using safe BML text");
        check(driving.contains("backs up without turning the locomotive")&&driving.contains("switches power off"),
                "reverse help explains backing movement, stable physical facing and power state");
        check(driving.contains("id='manage'")&&!driving.contains("id='rename'")&&!driving.contains("id='couple'"),
                "everyday driving panel keeps naming and wagon management in a secondary window");
        check(management.contains("id='rename'")&&management.contains("id='couple'")&&management.contains("id='uncouple'"),
                "management retains naming and exact selected wagon controls");
        check(driving.indexOf("id='stop'")<driving.indexOf("id='route'")
                        &&driving.indexOf("id='route'")<driving.indexOf("id='cargo'"),
                "driving status and emergency controls lead automatic service and cargo");

        operator.services.add(new Service(operator.train,"Mountain Express",true,"Signal RED: Held for Harbor express","Queued for single track from East"));operator.runningServices=1;operator.resumeEnabled=true;
        check(RailwayTrainUi.content(operator).contains("id='services'")&&RailwayTrainUi.content(operator).contains("will resume after server restart"),
                "operator can reach fleet controls directly and see saved restart intent");
        check(RailwayTrainUi.fromAnswers(operator,answer("services","train","forged")).action==Action.SERVICES,
                "fleet opener ignores caller-supplied train identities");
        String fleet=RailwayTrainUi.servicesContent(operator);
        check(fleet.contains("1 scheduled trains you can operate | 1 powered on")&&fleet.contains("id='servicesStart'")
                        &&fleet.contains("id='servicesStop'")&&fleet.contains("id='servicesResolve'"),
                "fleet view reports included service counts and offers start, stop and safe jam recovery");
        check(fleet.contains("STOP keeps them stopped")&&fleet.contains("Stopped and manual trains stay stopped"),
                "fleet help explains durable stop intent and bounded jam recovery");
        check(fleet.contains("Mountain Express | Power ON")&&fleet.contains("Status: Signal RED: Held for Harbor express")
                        &&fleet.contains("Traffic: Queued for single track from East"),
                "one fleet view exposes captured power, exact train status and dispatcher wait reason");
        String originalStatus=operator.train.status;boolean originalPower=operator.train.powered;
        operator.train.status="Changed after snapshot";operator.train.powered=false;
        check(RailwayTrainUi.servicesContent(operator).contains("Status: Signal RED: Held for Harbor express")
                        &&RailwayTrainUi.servicesContent(operator).contains("Mountain Express | Power ON"),
                "fleet diagnostics are an immutable presentation snapshot rather than live mutable registration fields");
        operator.train.status=originalStatus;operator.train.powered=originalPower;
        Service originalService=operator.services.get(0);
        String longReason=String.join(" ",Collections.nCopies(35,"occupied"))+" FINAL-WAIT-REASON";
        operator.services.set(0,new Service(operator.train,"Mountain Express",true,longReason,longReason));
        String longDiagnostics=RailwayTrainUi.servicesContent(operator);
        check(longDiagnostics.contains("FINAL-WAIT-REASON")&&longDiagnostics.contains("label{text='  occupied")
                        &&longDiagnostics.indexOf("FINAL-WAIT-REASON")!=longDiagnostics.lastIndexOf("FINAL-WAIT-REASON"),
                "both long stop reasons wrap across native labels while retaining their final diagnostic detail");
        operator.services.set(0,originalService);
        for(String button:new String[]{"servicesStart","servicesStop","servicesResolve"}) {
            Action expected=button.equals("servicesStart")?Action.SERVICES_START:button.equals("servicesStop")?Action.SERVICES_STOP:Action.SERVICES_RESOLVE;
            Request request=RailwayTrainUi.servicesFromAnswers(operator,answer(button,"services","attacker_train,another_train"));
            check(request.action==expected&&request.services.size()==1&&request.services.get(0).train==operator.train
                            &&request.services.get(0).locomotiveId==11L&&request.services.get(0).name.equals("Train_1"),
                    "fleet action captures exact offered registration and locomotive, never form text: "+button);
            try {request.services.clear();throw new AssertionError("fleet request is mutable");}catch(UnsupportedOperationException immutable){assertions++;}
        }
        Request captured=RailwayTrainUi.servicesFromAnswers(operator,answer("servicesStop","train","999999"));operator.services.clear();
        check(captured.services.size()==1,"queued fleet request owns an immutable snapshot independent of later UI refresh");
        for(String button:new String[]{"servicesStart","servicesStop","servicesResolve"})
            rejected(() -> RailwayTrainUi.servicesFromAnswers(operator,answer(button,"services","forged")),"empty fleet cannot forge an operation: "+button);
        rejected(() -> RailwayTrainUi.fromAnswers(operator,answer("services","names","forged")),"fleet opener requires captured scheduled choices");
        operator.services.add(new Service(operator.train,"Mountain Express [Running]"));
        Properties mixedServices=answer("servicesStart","unused","");mixedServices.setProperty("servicesStop","true");
        rejected(() -> RailwayTrainUi.servicesFromAnswers(operator,mixedServices),"fleet actions cannot be mixed in one submission");
        check(RailwayTrainUi.servicesFromAnswers(operator,answer("servicesRefresh","unused","")).action==Action.SERVICES,
                "fleet refresh re-queries permissions and offered trains without mutating service intent");
        check(RailwayTrainUi.servicesFromAnswers(operator,answer("back","unused","")).action==Action.REFRESH,
                "fleet back returns to train controls without mutation");
        check(RailwayTrainUi.servicesFromAnswers(operator,answer("close","unused",""))==null
                        &&RailwayTrainUi.servicesFromAnswers(operator,new Properties())==null,
                "closing the fleet window or an empty answer cannot start or stop trains");
        operator.services.add(operator.services.get(0));
        rejected(() -> RailwayTrainUi.servicesContent(operator),"duplicate captured service capabilities are rejected");
        operator.services.remove(operator.services.size()-1);

        View passenger=view(); passenger.train=operator.train;
        passenger.nextStop=operator.nextStop;passenger.trafficStatus=operator.trafficStatus;
        check(RailwayTrainUi.content(passenger).contains("Next stop: Mine [Station]")
                        &&RailwayTrainUi.content(passenger).contains("Traffic: Queued for shared connection"),
                "passengers can inspect the next stop and traffic wait without driving permissions");
        View noTraffic=view();noTraffic.train=operator.train;noTraffic.trafficStatus=null;noTraffic.nextStop=null;
        check(!RailwayTrainUi.content(noTraffic).contains("Traffic:")&&!RailwayTrainUi.content(noTraffic).contains("Next stop:"),
                "optional traffic details are omitted for snapshots without dispatch or route information");
        for(String key:new String[]{"start","stop","reverse","speedApply","speedCrawl","speedCruise","speedFull","route","manual","rename","couple","uncouple","place","attach","manage"})
            rejected(() -> RailwayTrainUi.fromAnswers(passenger,answer(key,"car","0")),"passenger cannot forge operator button "+key);
        check(RailwayTrainUi.fromAnswers(passenger,answer("board","car","1")).choice.equals("22"),"passenger retains boarding choice");
        passenger.aboard=true;
        check(!RailwayTrainUi.content(passenger).contains("id='board'"),"mounted passenger sees no Board control");
        rejected(() -> RailwayTrainUi.fromAnswers(passenger,answer("board","car","1")),"mounted passenger cannot forge a Board request");
        passenger.aboard=false;
        check(RailwayTrainUi.fromAnswers(passenger,answer("cargo","car","1")).action==Action.CARGO,"passenger retains normal cargo access request");
        check(RailwayTrainUi.fromAnswers(passenger,answer("passenger_stops","destinationId","999999")).action==Action.PASSENGER_STOPS,
                "passenger can open personal destination choices without driving permissions or caller-supplied destination ID");
        Properties mixedJourney=answer("passenger_stops","car","0"); mixedJourney.setProperty("stop","true");
        rejected(() -> RailwayTrainUi.fromAnswers(passenger,mixedJourney),"personal destination and driving actions cannot be combined");
        rejected(() -> RailwayTrainUi.routeFromAnswers(passenger,Collections.<String>emptyList(),answer("save","unused","")),
                "passenger cannot forge a route-save window");
        rejected(() -> RailwayTrainUi.managementContent(passenger),"passenger cannot open the operator management view");
        rejected(() -> RailwayTrainUi.routeFromAnswers(passenger,Arrays.asList("A","B"),answer("saveStart","unused","")),
                "passenger cannot forge Save and start");
        passenger.services.add(new Service(operator.train,"Forged fleet"));
        for(String button:new String[]{"servicesStart","servicesStop","servicesResolve","servicesRefresh","back"})
            rejected(() -> RailwayTrainUi.servicesFromAnswers(passenger,answer(button,"services","0")),"passenger cannot forge fleet action: "+button);
        rejected(() -> RailwayTrainUi.servicesContent(passenger),"fleet content requires current train operating capability");
        rejected(() -> RailwayTrainUi.fromAnswers(passenger,answer("services","unused","")),"passenger cannot forge fleet opener");

        View engine=view(); engine.engine=true; engine.canControl=true;
        check(RailwayTrainUi.fromAnswers(engine,answer("place","rail","1")).choice.equals("railB"),
                "off-track engine placement selects an offered rail identity");
        rejected(() -> RailwayTrainUi.fromAnswers(engine,answer("attach","train","0")),"engine cannot use wagon attachment action");
        rejected(() -> RailwayTrainUi.fromAnswers(engine,answer("start","unused","")),"unregistered engine cannot forge driving action");
        rejected(() -> RailwayTrainUi.fromAnswers(engine,answer("manage","unused","")),"unregistered engine cannot forge a registered train management window");
        engine.services.add(new Service(operator.train,"Express"));
        rejected(() -> RailwayTrainUi.servicesFromAnswers(engine,answer("servicesStart","unused","")),"unregistered locomotive cannot supply a fleet control anchor");
        rejected(() -> RailwayTrainUi.fromAnswers(engine,answer("passenger_stops","unused","")),"unregistered locomotive cannot open a mounted journey");
        View wagon=view(); wagon.canControl=true;
        check(RailwayTrainUi.fromAnswers(wagon,answer("attach","train","0")).choice.equals("Train_2"),
                "unlinked wagon can select an offered train");
        rejected(() -> RailwayTrainUi.fromAnswers(wagon,answer("place","rail","0")),"wagon cannot use engine placement action");

        List<String> draft=new ArrayList<String>(Arrays.asList("A"));
        Request added=RailwayTrainUi.routeFromAnswers(operator,draft,answer("add","station","1"));
        check(added.action==Action.ROUTE_EDIT&&added.route.equals(Arrays.asList("A","B")),"Add changes only the local route draft");
        check(added.selectedRouteStop==1,"Add keeps the newly appended row selected in the next draft window");
        check(draft.equals(Arrays.asList("A"))&&operator.train.route.isEmpty(),"draft editing does not mutate source draft or running train route");
        Request save=RailwayTrainUi.routeFromAnswers(operator,added.route,answer("save","unused",""));
        check(save.action==Action.ROUTE_SAVE&&save.route.equals(Arrays.asList("A","B")),"Save is the single route commit action");
        immutable(save.route,"queued saved route cannot change");
        check(RailwayTrainUi.routeFromAnswers(operator,added.route,answer("back","unused","")).action==Action.REFRESH,
                "Back discards draft edits without a route-save action");
        check(RailwayTrainUi.routeFromAnswers(operator,added.route,answer("clear","unused","")).route.isEmpty(),"Clear empties only the local draft");
        check(RailwayTrainUi.routeFromAnswers(operator,added.route,answer("remove","unused","")).route.equals(Arrays.asList("A")),"Remove affects only the last draft stop");
        check(RailwayTrainUi.routeFromAnswers(operator,Collections.<String>emptyList(),answer("remove","unused","")).route.isEmpty(),
                "Remove on an empty draft remains safe");
        List<String> maximum=Collections.nCopies(1000,"A");
        rejected(() -> RailwayTrainUi.routeFromAnswers(operator,maximum,answer("add","station","0")),"adding beyond 1000 draft stops rejected");
        rejected(() -> RailwayTrainUi.routeFromAnswers(operator,Collections.nCopies(1001,"A"),answer("save","unused","")),"oversized route drafts rejected");
        rejected(() -> RailwayTrainUi.routeFromAnswers(operator,draft,answer("add","station","999")),"forged station index rejected");
        Request start=RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B"),answer("saveStart","unused",""));
        check(start.action==Action.ROUTE_START&&start.route.equals(Arrays.asList("A","B")),
                "Save and start is one explicit request containing the immutable ordered draft");
        immutable(start.route,"Save and start request cannot change after queueing");
        for(List<String> shortRoute:Arrays.asList(Collections.<String>emptyList(),Collections.singletonList("A")))
            rejected(() -> RailwayTrainUi.routeFromAnswers(operator,shortRoute,answer("saveStart","unused","")),
                    "automatic service needs at least two draft stops");
        rejected(() -> RailwayTrainUi.routeFromAnswers(operator,draft,answer("add","station","0")),
                "Add cannot append a duplicate consecutive stop");
        for(String commit:new String[]{"save","saveStart"})
            rejected(() -> RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","A"),answer(commit,"unused","")),
                    "a commit cannot save consecutive duplicate stops");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","A"),answer("save","unused","")).route.equals(Arrays.asList("A","B","A")),
                "returning to an earlier stop remains valid when a different stop separates visits");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","A"),answer("removeSelected","route_stop","1")).route.equals(Arrays.asList("A","A")),
                "selected-stop removal edits the exact draft index even while a legacy draft is being repaired");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","A"),answer("moveUp","route_stop","1")).route.equals(Arrays.asList("B","A","A")),
                "Move earlier uses the selected position rather than matching repeated station identities");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","A"),answer("moveDown","route_stop","1")).route.equals(Arrays.asList("A","A","B")),
                "Move later uses the selected draft position");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B"),answer("moveUp","route_stop","0")).route.equals(Arrays.asList("A","B")),
                "moving the first stop earlier is a harmless local draft no-op");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B"),answer("moveDown","route_stop","1")).route.equals(Arrays.asList("A","B")),
                "moving the last stop later is a harmless local draft no-op");
        Request moved=RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","C"),answer("moveUp","route_stop","2"));
        check(moved.selectedRouteStop==1&&moved.route.equals(Arrays.asList("A","C","B")),
                "Move earlier follows the moved row in the next window");
        Request movedAgain=RailwayTrainUi.routeFromAnswers(operator,moved.route,
                answer("moveUp","route_stop",Integer.toString(moved.selectedRouteStop)));
        check(movedAgain.selectedRouteStop==0&&movedAgain.route.equals(Arrays.asList("C","A","B")),
                "repeated Move earlier actions continue moving the same selected stop");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","C"),answer("removeSelected","route_stop","1")).selectedRouteStop==1,
                "removing a middle stop selects the next row now occupying that position");
        check(RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B","C"),answer("removeSelected","route_stop","2")).selectedRouteStop==1,
                "removing the last stop selects the preceding row");
        check(RailwayTrainUi.routeFromAnswers(operator,Collections.singletonList("A"),answer("removeSelected","route_stop","0")).selectedRouteStop==-1,
                "removing the only row leaves no selection");
        check(RailwayTrainUi.Request.route(Action.ROUTE_EDIT,Arrays.asList("A","B"),999).selectedRouteStop==1,
                "stale presentation indices clamp safely without becoming object identities");
        for(String button:new String[]{"removeSelected","moveUp","moveDown"}) {
            for(String index:new String[]{"-1","2","A","999999999999"})
                rejected(() -> RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B"),answer(button,"route_stop",index)),
                        "selected draft controls reject forged indices: "+button);
            rejected(() -> RailwayTrainUi.routeFromAnswers(operator,Collections.<String>emptyList(),answer(button,"route_stop","0")),
                    "an empty draft cannot use a selected stop edit: "+button);
        }
        Properties ambiguous=answer("saveStart","unused","");ambiguous.setProperty("save","true");
        rejected(() -> RailwayTrainUi.routeFromAnswers(operator,Arrays.asList("A","B"),ambiguous),
                "Save and start cannot be combined with another commit action");
        List<String> staleDraft=Arrays.asList("missing","missing","A");
        check(RailwayTrainUi.routeFromAnswers(operator,staleDraft,answer("removeSelected","route_stop","0")).route.equals(Arrays.asList("missing","A")),
                "a stale or legacy route can be repaired locally without resolving removed stop identities");
        check(RailwayTrainUi.routeFromAnswers(operator,staleDraft,answer("clear","unused","")).route.isEmpty(),
                "Clear can recover an invalid legacy draft without saving it");
        View emptyStations=view();emptyStations.stations.clear();
        emptyStations.routeHelp="2 stations exist on disconnected tracks.";
        String noStops=RailwayTrainUi.routeContent(emptyStations,Collections.<String>emptyList());
        check(noStops.contains("Build a station")&&noStops.contains("connected track")&&noStops.contains("Railway track"),
                "empty routes explain passenger-platform and named-track station setup");
        check(noStops.contains("2 stations exist on disconnected tracks."),
                "route window includes captured diagnostics explaining why existing stops are unavailable");
        operator.stations.set(0,new Choice("A","Home [Station]"));operator.stations.set(1,new Choice("B","Mine [Station]"));
        String ordered=RailwayTrainUi.routeContent(operator,Arrays.asList("A","B"));
        check(ordered.contains("1. Home [Station]")&&ordered.contains("2. Mine [Station]")&&ordered.contains("id='route_stop'")
                        &&ordered.contains("id='saveStart'")&&ordered.contains("id='removeSelected'"),
                "route view shows numbered friendly stop types and explicit selected-stop edits plus one-step start");
        check(ordered.contains("Two or more stops repeat")&&ordered.contains("backs up without turning its body")
                        &&ordered.contains("Opposing services need passing track"),
                "route setup explains repeating service, physical reversals and passing-track requirements");
        check(RailwayTrainUi.routeContent(operator,Arrays.asList("A","B"),0).contains("id='route_stop';default='0'"),
                "route view retains the selected row across draft edits");
        check(operator.train.route.isEmpty(),"all decoder tests leave actual train automation unchanged");
        System.out.println("PASS railway controls answer decoding: permissions, chosen identities, bounded settings and immutable uncommitted routes ("+assertions+" assertions)");
    }

    private static View view() {
        View view=new View(); view.carId=22L;
        view.cars.add(new Choice("11","Engine")); view.cars.add(new Choice("22","Clicked wagon"));
        view.wagons.add(new Choice("33","First wagon")); view.wagons.add(new Choice("44","Second wagon"));
        view.trains.add(new Choice("Train_2","Mountain Express"));
        view.rails.add(new Choice("railA","First rail")); view.rails.add(new Choice("railB","Second rail"));
        view.stations.add(new Choice("A","Home")); view.stations.add(new Choice("B","Mine"));
        view.route.add("A"); return view;
    }

    private static Properties answer(String button,String field,String value) {
        Properties properties=new Properties(); properties.setProperty(button,"true"); properties.setProperty(field,value); return properties;
    }
    private interface Checked { void run(); }
    private static void rejected(Checked action,String message) {
        try { action.run(); } catch(IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void immutable(List<String> route,String message) {
        try { route.add("injected"); } catch(UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition,String message) { assertions++; if(!condition) throw new AssertionError(message); }
}
