import com.wurmonline.client.bml.BNode;
import com.wurmonline.client.bml.BParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.wurmrail.RailwayBuilderUi;
import org.wurmrail.RailwayPassengerUi;
import org.wurmrail.RailwaySignalUi;
import org.wurmrail.RailwayTrainUi;
import org.wurmrail.core.RailNetwork;

/** Parse actual UI output with the shipped Wurm client BML parser, without opening OpenGL. */
public final class RailwayBmlClientSmoke {
    private RailwayBmlClientSmoke() { }

    public static void main(String[] args) throws Exception {
        String hostile="East,Goods;\"'};button{id='injected';text='oops'};{\\\n";
        RailwayBuilderUi.View blank=new RailwayBuilderUi.View("Surface tile 50,50",null,-10L,
                false,false,"",5,Collections.<RailwayBuilderUi.Neighbor>emptyList(),7,12,false);
        Map<String,BNode> empty=parse(wrap(RailwayBuilderUi.content(blank)));
        check("7".equals(empty.get("direction").getAttribute("default")),"default compass index must survive native parsing");
        check(empty.get("direction").getAttribute("options").split(",",-1).length==8,
                "native compass dropdown must contain exactly eight choices");
        check(empty.containsKey("place") && empty.containsKey("count") && !empty.containsKey("remove"),
                "blank construction window must expose only appropriate actions");
        List<RailwayBuilderUi.Neighbor> neighbors=new ArrayList<RailwayBuilderUi.Neighbor>();
        for(int dir=0;dir<8;dir++) neighbors.add(new RailwayBuilderUi.Neighbor(dir,hostile,true,dir==7));
        RailwayBuilderUi.View junction=new RailwayBuilderUi.View(hostile,"rail50_50",42L,
                true,true,hostile,12.5,neighbors,0,12,true,(1<<6)|(1<<7));
        Map<String,BNode> edit=parse(wrap(RailwayBuilderUi.content(junction)));
        check(!edit.containsKey("injected"),"display text must not inject native buttons");
        String[] pairs=edit.get("alignment_choice").getAttribute("options").split(",",-1);
        check(pairs.length==21 && pairs[0].startsWith("Not aligned"),
                "unset plus the 20 straight, 45 and 90 degree alignments retain native dropdown indices");
        check("0".equals(edit.get("alignment_choice").getAttribute("default")),
                "a saved 135 degree alignment is not offered as a new selectable corner");
        String[] compass={"N","NE","E","SE","S","SW","W","NW"};
        int pairIndex=1;
        for(int first=0;first<8;first++)for(int second=first+1;second<8;second++) {
            int span=second-first;
            if(Math.min(span,8-span)<2)continue;
            check(pairs[pairIndex].contains(compass[first]+" \u2194 "+compass[second]),
                    "native dropdown labels retain the two actual connected sides at index "+pairIndex);
            java.util.Properties answer=new java.util.Properties();answer.setProperty("alignment","true");
            answer.setProperty("alignment_choice",Integer.toString(pairIndex));
            check(RailwayBuilderUi.fromAnswers(junction,answer).alignmentMask==((1<<first)|(1<<second)),
                    "native client dropdown index maps back to the exact supported server alignment");
            pairIndex++;
        }
        check(pairIndex==21,"every supported pair appears once in the parsed native dropdown");
        check(edit.containsKey("alignment") && !edit.containsKey("switch") && !edit.containsKey("switch_direction"),
                "junction window must offer one physical alignment shared by all trains");
        for(int dir=0;dir<8;dir++) check("true".equals(edit.get("exit_"+dir).getAttribute("selected")),
                "connected exit checkbox must survive native parsing: "+dir);
        check(edit.containsKey("station") && edit.containsKey("station_off")
                        && edit.containsKey("remove") && edit.containsKey("spawn_engine") && edit.containsKey("spawn_cart"),
                "native builder must retain station, maintenance and GM controls");
        check(edit.get("station_name").getAttribute("text").equals(RailwayBuilderUi.safe(hostile)),
                "native input must retain the safe station label");
        RailwayBuilderUi.View broken=new RailwayBuilderUi.View("Broken section","broken",42L,
                false,false,"",5,neighbors,0,0,false);
        Map<String,BNode> repair=parse(wrap(RailwayBuilderUi.content(broken)));
        check(repair.containsKey("repair") && !repair.containsKey("spawn_engine"),
                "repair must be available to normal builders without GM controls");
        List<RailwayBuilderUi.Neighbor> sparse=new ArrayList<RailwayBuilderUi.Neighbor>();
        sparse.add(new RailwayBuilderUi.Neighbor(0,"North rail",true,false));
        sparse.add(new RailwayBuilderUi.Neighbor(4,"South rail",true,false));
        sparse.add(new RailwayBuilderUi.Neighbor(7,hostile,true,false));
        sparse.add(new RailwayBuilderUi.Neighbor(2,"Disconnected east",false,false));
        Map<String,BNode> three=parse(wrap(RailwayBuilderUi.content(new RailwayBuilderUi.View(
                "Three-sided junction","sparse",43L,true,false,"",5,sparse,0,0,false,17))));
        String[] sparsePairs=three.get("alignment_choice").getAttribute("options").split(",",-1);
        check(sparsePairs.length==3 && "1".equals(three.get("alignment_choice").getAttribute("default"))
                        && sparsePairs[1].contains("N \u2194 S") && !three.containsKey("injected"),
                "native sparse junction choices must contain only connected unordered side pairs");
        Map<String,BNode> bend=parse(wrap(RailwayBuilderUi.content(new RailwayBuilderUi.View(
                "Two-sided curve","bend",44L,true,false,"",5,sparse.subList(0,2),0,0,false,17))));
        check(!bend.containsKey("alignment_choice") && !bend.containsKey("alignment"),
                "two-sided rails must not expose a junction alignment control");
        trainWindows(hostile);
        passengerWindows(hostile);
        signalWindows(hostile);
        for(String path:args) parse(new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8));
        System.out.println("PASS shipped Wurm client BML parser: builder, train controls, passenger view, locomotive placement, wagon attachment, station routes, combined stations, journey selection, absolute signal approaches/status, hostile names and "+args.length+" additional windows");
    }

    private static void signalWindows(String hostile) throws Exception {
        List<Integer> sides=new ArrayList<Integer>();
        for(int side=0;side<8;side++) sides.add(side);
        RailwaySignalUi.View empty=new RailwaySignalUi.View("hidden_signal_rail",5001L,7L,hostile,sides,
                Collections.<RailwaySignalUi.Signal>emptyList(),true,3,false,7);
        String emptyText=RailwaySignalUi.content(empty);
        Map<String,BNode> creation=parse(wrap(emptyText));
        check(creation.containsKey("place") && !creation.containsKey("remove")
                        && "7".equals(creation.get("approach_choice").getAttribute("default"))
                        && creation.get("approach_choice").getAttribute("options").split(",",-1).length==8,
                "native signal construction retains all eight absolute approach choices and the captured default");
        check(!creation.containsKey("injected") && !emptyText.contains("hidden_signal_rail"),
                "signal track labels cannot inject native controls or reveal hidden track identity");

        List<RailwaySignalUi.Signal> configured=new ArrayList<RailwaySignalUi.Signal>();
        configured.add(new RailwaySignalUi.Signal("hidden_signal_S",4,"GREEN",hostile,hostile,hostile));
        configured.add(new RailwaySignalUi.Signal("hidden_signal_N",0,"RED","","Occupied by Harbor express","Mine railway"));
        RailwaySignalUi.View managed=new RailwaySignalUi.View("hidden_signal_rail",5001L,7L,hostile,
                java.util.Arrays.asList(0,2,4,7),configured,true,3,false,7);
        String managedText=RailwaySignalUi.content(managed);
        Map<String,BNode> fields=parse(wrap(managedText));
        check(fields.containsKey("place") && fields.containsKey("remove") && fields.containsKey("confirm_remove")
                        && fields.get("signal_choice").getAttribute("options").split(",",-1).length==2,
                "configured signals retain independent create/remove controls and exact captured signal selection indices");
        check("East approach,Northwest approach".equals(fields.get("approach_choice").getAttribute("options"))
                        && "1".equals(fields.get("approach_choice").getAttribute("default")),
                "configured sides are skipped automatically without changing absolute side meanings");
        check(!fields.containsKey("injected") && !managedText.contains("hidden_signal_S")
                        && !managedText.contains("hidden_signal_N") && !managedText.contains("hidden_signal_rail"),
                "aspect, owner, reason and route labels remain safe while captured internal identities stay off the form");
        RailwaySignalUi.View visitor=new RailwaySignalUi.View("hidden_signal_rail",5001L,7L,"Harbor railway",sides,
                configured,false,0,false,0);
        Map<String,BNode> readOnly=parse(wrap(RailwaySignalUi.content(visitor)));
        check(!readOnly.containsKey("place") && !readOnly.containsKey("remove")
                        && !readOnly.containsKey("approach_choice") && !readOnly.containsKey("signal_choice")
                        && readOnly.containsKey("refresh") && readOnly.containsKey("close"),
                "visitors may refresh absolute signal diagnostics without management controls");
        List<RailwaySignalUi.Signal> full=new ArrayList<RailwaySignalUi.Signal>();
        for(int side=0;side<8;side++) full.add(new RailwaySignalUi.Signal("hidden_signal_"+side,side,"RED","","No authority",""));
        Map<String,BNode> occupied=parse(wrap(RailwaySignalUi.content(new RailwaySignalUi.View(
                "hidden_signal_rail",5001L,7L,"Eight-sided railway",sides,full,true,1,false,0))));
        check(!occupied.containsKey("place") && occupied.containsKey("remove")
                        && occupied.get("signal_choice").getAttribute("options").split(",",-1).length==8,
                "all-signalled rails offer removal without a duplicate approach placement control");
        Map<String,BNode> isolated=parse(wrap(RailwaySignalUi.content(new RailwaySignalUi.View(
                "hidden_signal_rail",5001L,7L,"Isolated railway",Collections.<Integer>emptyList(),
                Collections.<RailwaySignalUi.Signal>emptyList(),true,1,false,0))));
        check(!isolated.containsKey("place") && !isolated.containsKey("remove") && isolated.containsKey("refresh"),
                "a rail without connections offers safe refresh instead of invalid signal construction");
    }

    private static void passengerWindows(String hostile) throws Exception {
        List<RailwayPassengerUi.Choice> destinations=new ArrayList<RailwayPassengerUi.Choice>();
        destinations.add(new RailwayPassengerUi.Choice(91001001L,"Harbor, East"));
        destinations.add(new RailwayPassengerUi.Choice(91001002L,hostile));
        RailwayPassengerUi.View pickup=new RailwayPassengerUi.View(91001003L,7L,-10L,RailwayPassengerUi.Mode.PICKUP,
                hostile,"Railway, beside harbor",hostile,true,91001002L,2,6,destinations);
        String pickupText=RailwayPassengerUi.content(pickup);
        Map<String,BNode> manager=parse(wrap(pickupText));
        check(manager.containsKey("destination") && manager.containsKey("cancel_destination")
                        && manager.containsKey("rename") && manager.containsKey("remove") && manager.containsKey("confirm_remove"),
                "pickup stop must offer personal destination and authorized management controls");
        check("1".equals(manager.get("destination_choice").getAttribute("default"))
                        && manager.get("destination_choice").getAttribute("options").split(",",-1).length==2,
                "chosen drop-off and commas in stop labels must preserve native dropdown indices");
        check(!manager.containsKey("injected") && !pickupText.contains("9100100"),
                "passenger stop labels cannot inject buttons or expose internal platform IDs");
        check(manager.get("stop_name").getAttribute("text").equals(org.wurmrail.RailwayBml.text(hostile)),
                "stop rename input must preserve safe friendly label text");

        RailwayPassengerUi.View dropoff=new RailwayPassengerUi.View(91001004L,0L,-10L,RailwayPassengerUi.Mode.DROPOFF,
                "Mine", "Northern railway", "No train present",false,-10L,0,-1,destinations);
        Map<String,BNode> visitor=parse(wrap(RailwayPassengerUi.content(dropoff)));
        check(visitor.containsKey("destination") && !visitor.containsKey("rename") && !visitor.containsKey("remove")
                        && !visitor.containsKey("stop_name"),
                "drop-off visitor may choose a personal journey without managing the stop");
        RailwayPassengerUi.View personal=new RailwayPassengerUi.View(-10L,0L,91001005L,null,
                "Aboard the express", "", "Riding",true,91001002L,0,4,destinations);
        String personalText=RailwayPassengerUi.content(personal);
        Map<String,BNode> journey=parse(wrap(personalText));
        check(journey.containsKey("destination_choice") && journey.containsKey("cancel_destination")
                        && !journey.containsKey("rename") && !journey.containsKey("remove") && !personalText.contains("91001005"),
                "onboard journey window must remain independent of platform-management controls and hidden train IDs");
        RailwayPassengerUi.View unavailable=new RailwayPassengerUi.View(-10L,0L,91001005L,null,
                "Journey", "", "Waiting for a connected stop",false,91001002L,0,-1,Collections.<RailwayPassengerUi.Choice>emptyList());
        Map<String,BNode> empty=parse(wrap(RailwayPassengerUi.content(unavailable)));
        check(!empty.containsKey("destination_choice") && !empty.containsKey("destination") && empty.containsKey("cancel_destination"),
                "unavailable destination can be cleared without a forged empty dropdown selection");

        List<RailwayPassengerUi.RailChoice> rails=new ArrayList<RailwayPassengerUi.RailChoice>();
        rails.add(new RailwayPassengerUi.RailChoice("hidden_passenger_rail_A",hostile));
        rails.add(new RailwayPassengerUi.RailChoice("hidden_passenger_rail_B","East railway, beside mine"));
        RailwayPassengerUi.BuildView build=new RailwayPassengerUi.BuildView(hostile,rails,RailwayPassengerUi.Mode.DROPOFF,1,false);
        String construction=RailwayPassengerUi.buildContent(build);
        Map<String,BNode> platformBuilder=parse(wrap(construction));
        check(platformBuilder.containsKey("build") && platformBuilder.containsKey("stop_name")
                        && !platformBuilder.containsKey("mode")
                        && platformBuilder.get("rail").getAttribute("options").split(",",-1).length==2,
                "combined station construction needs a name and captured adjacent rail without a pickup/drop-off choice");
        check(!platformBuilder.containsKey("injected") && !construction.contains("hidden_passenger_rail_"),
                "construction shows safe friendly rail labels rather than internal node keys");
        Map<String,BNode> noRail=parse(wrap(RailwayPassengerUi.buildContent(new RailwayPassengerUi.BuildView(
                "Empty tile",Collections.<RailwayPassengerUi.RailChoice>emptyList(),RailwayPassengerUi.Mode.PICKUP,0,false))));
        check(!noRail.containsKey("build") && noRail.containsKey("refresh") && noRail.containsKey("close"),
                "construction with no adjacent rail must offer refresh instead of an invalid build action");
    }

    private static void trainWindows(String hostile) throws Exception {
        RailNetwork network=new RailNetwork();
        network.addNode("hidden_origin_941",10,10,0);
        network.addNode("hidden_destination_746",11,10,0);
        network.connect("hidden_origin_941","hidden_destination_746");
        RailNetwork.Train train=network.createTrain("hidden_train_263",98765L,
                Collections.singletonList(987654321001L),"hidden_origin_941","hidden_destination_746");
        RailwayTrainUi.View full=new RailwayTrainUi.View();
        full.carId=987654321002L; full.train=train; full.engine=false; full.canControl=true;
        full.title=hostile; full.status="Stopped"; full.mode="Manual";
        full.cars.add(new RailwayTrainUi.Choice("987654321001","Locomotive, front"));
        full.cars.add(new RailwayTrainUi.Choice("987654321002",hostile));
        full.wagons.add(new RailwayTrainUi.Choice("987654321003",hostile));
        full.wagons.add(new RailwayTrainUi.Choice("987654321004","Timber wagon, rear"));
        full.stations.add(new RailwayTrainUi.Choice("hidden_origin_941",hostile));
        full.stations.add(new RailwayTrainUi.Choice("hidden_destination_746","Goods yard, East"));
        full.services.add(new RailwayTrainUi.Service(train,hostile,true,hostile,"Queued: "+hostile));full.runningServices=1;full.resumeEnabled=true;
        String controls=RailwayTrainUi.content(full);
        Map<String,BNode> driver=parse(wrap(controls));
        check(driver.containsKey("start") && driver.containsKey("stop") && driver.containsKey("reverse")
                        && driver.containsKey("route") && driver.containsKey("manage") && driver.containsKey("services")
                        && !driver.containsKey("rename") && !driver.containsKey("couple") && !driver.containsKey("uncouple"),
                "operator driving window keeps direct controls and opens separate train management");
        Map<String,BNode> management=parse(wrap(RailwayTrainUi.managementContent(full)));
        check(management.containsKey("name") && management.containsKey("rename") && management.containsKey("couple")
                        && management.containsKey("uncouple") && management.containsKey("refresh") && !management.containsKey("injected"),
                "management keeps safe names and wagon coupling outside the driving panel");
        check(driver.containsKey("speedCrawl") && driver.containsKey("speedCruise") && driver.containsKey("speedFull"),
                "operator must have direct crawl, cruise and full speed buttons");
        check(driver.containsKey("passenger_stops"),"registered train controls must offer a personal drop-off window");
        check("1".equals(driver.get("car").getAttribute("default")),
                "clicked trailing wagon must remain selected in train controls");
        check(driver.get("car").getAttribute("options").split(",",-1).length==2
                        && management.get("wagon").getAttribute("options").split(",",-1).length==2,
                "commas in friendly wagon labels must not alter native dropdown mappings");
        check(!driver.containsKey("injected") && !controls.contains("98765432100")
                        && !controls.contains("hidden_train_") && !controls.contains("hidden_origin_"),
                "train controls must render safe friendly labels without internal IDs");
        String serviceText=RailwayTrainUi.servicesContent(full);
        Map<String,BNode> services=parse(wrap(serviceText));
        check(services.containsKey("servicesStart")&&services.containsKey("servicesStop")&&services.containsKey("servicesResolve")
                        &&services.containsKey("servicesRefresh")&&services.containsKey("back")&&!services.containsKey("injected"),
                "fleet window parses native start/stop/jam actions and safely renders hostile friendly labels");
        check(!serviceText.contains("hidden_train_")&&!serviceText.contains("987654321001"),
                "fleet window never exposes captured registration or locomotive identity as editable form values");
        check(serviceText.contains("Power ON")&&serviceText.contains("Status:")&&serviceText.contains("Traffic:"),
                "native parsed fleet diagnostics include power and both precise reason fields with hostile text escaped");
        full.services.clear();Map<String,BNode> noServices=parse(wrap(RailwayTrainUi.servicesContent(full)));
        check(!noServices.containsKey("servicesStart")&&!noServices.containsKey("servicesStop")&&!noServices.containsKey("servicesResolve")
                        &&noServices.containsKey("servicesRefresh")&&noServices.containsKey("back"),
                "empty fleet window retains navigation without offering controls for uncaptured trains");
        full.services.add(new RailwayTrainUi.Service(train,hostile));

        full.canControl=false;
        Map<String,BNode> passenger=parse(wrap(RailwayTrainUi.content(full)));
        check(passenger.containsKey("board") && passenger.containsKey("cargo")
                        && !passenger.containsKey("start") && !passenger.containsKey("stop")
                        && !passenger.containsKey("reverse") && !passenger.containsKey("route")
                        && !passenger.containsKey("rename") && !passenger.containsKey("couple")
                        && !passenger.containsKey("uncouple") && !passenger.containsKey("speedCrawl")
                        && !passenger.containsKey("speedCruise") && !passenger.containsKey("speedFull") && !passenger.containsKey("services"),
                "passenger window must retain boarding and cargo without operator actions");
        check(passenger.containsKey("passenger_stops"),"passenger can choose a drop-off without driving permissions");
        full.aboard=true;
        Map<String,BNode> aboard=parse(wrap(RailwayTrainUi.content(full)));
        check(!aboard.containsKey("board")&&aboard.containsKey("cargo")&&aboard.containsKey("passenger_stops"),
                "already-mounted passenger retains cargo and journey controls without a Board button");
        full.aboard=false;

        RailwayTrainUi.View placement=new RailwayTrainUi.View();
        placement.carId=987654321005L; placement.title=hostile; placement.engine=true; placement.canControl=true;
        placement.cars.add(new RailwayTrainUi.Choice("987654321005",hostile));
        placement.rails.add(new RailwayTrainUi.Choice("hidden_origin_941",hostile));
        placement.rails.add(new RailwayTrainUi.Choice("hidden_destination_746","East track, beside platform"));
        String unlinkedEngine=RailwayTrainUi.content(placement);
        Map<String,BNode> place=parse(wrap(unlinkedEngine));
        check(place.containsKey("place") && place.containsKey("rail") && !place.containsKey("start")
                        && !place.containsKey("attach") && !place.containsKey("passenger_stops")
                        && place.get("rail").getAttribute("options").split(",",-1).length==2,
                "unlinked locomotive must offer native placement with stable track choices");
        check(!unlinkedEngine.contains("hidden_origin_") && !unlinkedEngine.contains("987654321005"),
                "locomotive placement must hide internal track and item identifiers");

        placement.engine=false; placement.rails.clear();
        placement.trains.add(new RailwayTrainUi.Choice("hidden_train_263",hostile));
        placement.trains.add(new RailwayTrainUi.Choice("hidden_train_693","Harbor express, second engine"));
        Map<String,BNode> attach=parse(wrap(RailwayTrainUi.content(placement)));
        check(attach.containsKey("attach") && attach.containsKey("train") && !attach.containsKey("place")
                        && !attach.containsKey("start") && attach.get("train").getAttribute("options").split(",",-1).length==2,
                "unlinked wagon must offer native attachment to an explicitly selected train");

        List<String> draft=new ArrayList<String>();
        draft.add("hidden_origin_941"); draft.add("hidden_destination_746");
        full.canControl=true; full.routeHelp="2 connected platform stops available";
        String routeText=RailwayTrainUi.routeContent(full,draft);
        Map<String,BNode> route=parse(wrap(routeText));
        check(route.containsKey("add") && route.containsKey("removeSelected") && route.containsKey("clear")
                        && route.containsKey("route_stop") && route.containsKey("moveUp") && route.containsKey("moveDown")
                        && route.containsKey("saveStart") && route.containsKey("save") && route.containsKey("back")
                        && route.get("station").getAttribute("options").split(",",-1).length==2,
                "route editor retains native stop indices, ordering controls and save/start actions");
        Map<String,BNode> selected=parse(wrap(RailwayTrainUi.routeContent(full,draft,0)));
        check("0".equals(selected.get("route_stop").getAttribute("default")),"route selection follows a moved stop between captured draft windows");
        check(!route.containsKey("injected") && !routeText.contains("hidden_origin_")
                        && !routeText.contains("hidden_destination_"),
                "route editor must render friendly station labels and ignore BML injection");
        full.stations.clear();
        full.routeHelp="2 stops exist on disconnected track. Connect their rails first.";
        Map<String,BNode> noStations=parse(wrap(RailwayTrainUi.routeContent(full,Collections.<String>emptyList())));
        check(!noStations.containsKey("station") && !noStations.containsKey("add") && noStations.containsKey("save"),
                "empty station list must still allow clearing an existing automatic route");
        serviceScrolling(6);serviceScrolling(50);
    }

    /** Parse the actual long fleet body under the exact shipped native scrolling header. */
    private static void serviceScrolling(int count) throws Exception {
        RailNetwork network=new RailNetwork();RailwayTrainUi.View view=new RailwayTrainUi.View();view.canControl=true;
        String repeated=String.join(" ",Collections.nCopies(40,"occupied"));
        for(int i=0;i<count;i++) {
            String first="fleet_scroll_a"+i,second="fleet_scroll_b"+i;int x=10+i*3;
            network.addNode(first,x,10,0);network.addNode(second,x+1,10,0);network.connect(first,second);
            RailNetwork.Train train=network.createTrain("fleetScroll"+i,99L,Collections.singletonList(200000L+i),first,second);
            if(i==0)view.train=train;
            view.services.add(new RailwayTrainUi.Service(train,"Fleet train "+i,true,
                    "Signal RED: "+repeated+" FINALSTATUS"+i,"Queued: "+repeated+" FINALTRAFFIC"+i));
        }
        view.runningServices=count;String window=wrap(RailwayTrainUi.servicesContent(view));Map<String,BNode> controls=parse(window);
        BNode root=BParser.parse(window),scroll=root.getNodes()[0].getNodes()[2];
        check("scroll".equals(scroll.getType())&&"true".equals(scroll.getAttribute("vertical"))
                        &&"false".equals(scroll.getAttribute("horizontal")),
                "native fleet window retains vertical scroll for all "+count+" long service diagnostics");
        StringBuilder visible=new StringBuilder();textBelow(scroll,visible);
        for(int i=0;i<count;i++)check(visible.indexOf("FINALSTATUS"+i)>=0&&visible.indexOf("FINALTRAFFIC"+i)>=0,
                "both final wait details for fleet train "+i+" are reachable inside native scroll content");
        check(controls.containsKey("servicesRefresh")&&controls.containsKey("back")&&controls.containsKey("servicesResolve"),
                "long scrolling fleet retains refresh, navigation and recovery controls");
    }
    private static void textBelow(BNode node,StringBuilder result) {
        String text=node.getAttribute("text");if(text!=null)result.append(text).append('\n');
        for(BNode child:node.getNodes())textBelow(child,result);
    }

    public static Map<String,BNode> parse(String bml) throws Exception {
        BNode root=BParser.parse(bml);
        check(root.getNodes().length==1 && "border".equals(root.getNodes()[0].getType()),
                "window must have one complete native border root");
        check(root.getNodes()[0].getNodes().length==5,"native border window must contain five layout panes");
        Map<String,BNode> fields=new HashMap<String,BNode>();
        collect(root,fields);
        check(fields.containsKey("id"),"window must contain a native question ID");
        return fields;
    }

    private static String wrap(String content) {
        return "border{null;null;scroll{vertical='true';horizontal='false';varray{rescale='true';passthrough{id='id';text='42'}"
                +content+"}};null;null;}";
    }

    private static void collect(BNode node,Map<String,BNode> fields) {
        String id=node.getAttribute("id");
        if(id!=null) check(fields.put(id,node)==null,"window must not contain repeated field ID: "+id);
        for(BNode child:node.getNodes()) collect(child,fields);
    }

    private static void check(boolean condition,String reason) {
        if(!condition) throw new AssertionError(reason);
    }
}
