package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.*;
import org.wurmrail.core.RailNetwork.Train;

/** Native Wurm windows. Answers contain choices from this window, never chat commands. */
public final class RailwayTrainUi {
    private RailwayTrainUi() { }
    public interface Api {
        View snapshot(Creature actor, long carId) throws Exception;
        void submit(Creature actor, long carId, Train expected, Request request);
    }
    public enum Action { REFRESH, START, STOP, REVERSE, SPEED, BOARD, CARGO, RENAME,
        COUPLE, UNCOUPLE, PLACE, MANUAL, ROUTE_EDIT, ROUTE_SAVE, PASSENGER_STOPS,
        ROUTE_START, MANAGE, SERVICES, SERVICES_START, SERVICES_STOP, SERVICES_RESOLVE }
    public static final class Choice {
        public final String key, label;
        public Choice(String key,String label) { this.key=key; this.label=label; }
    }
    /** A fleet capability captured from a permitted, registered train, never from form text. */
    public static final class Service {
        public final Train train;
        public final String name,label,status,traffic;
        public final boolean powered;
        public final long locomotiveId;
        public Service(Train train,String label) {
            this(train,label,train!=null&&train.powered,train==null?"":train.status,"");
        }
        public Service(Train train,String label,boolean powered,String status,String traffic) {
            if(train==null||train.carIds.isEmpty()||label==null)
                throw new IllegalArgumentException("A registered scheduled train is required.");
            this.train=train;this.name=train.name;this.locomotiveId=train.carIds.get(0);this.label=label;
            this.powered=powered;this.status=status==null?"":status;this.traffic=traffic==null?"":traffic;
        }
    }
    public static final class View {
        public long carId;
        public Train train;
        public String title="Railway", status="Ready", mode="Manual", routeSummary="No automatic route", routeHelp="",
                trafficStatus="", nextStop="";
        public boolean engine, canControl, powered, resumeEnabled, aboard;
        public boolean serverResumeEnabled=true;
        public double speed, maxSpeed=5, speedLimit=5, passengerStopWaitSeconds=5;
        public int occupiedSeats, seats=4, cargoItems;
        public String cargoStatus="";
        public List<Choice> cars=new ArrayList<Choice>(), wagons=new ArrayList<Choice>(),
                trains=new ArrayList<Choice>(), rails=new ArrayList<Choice>(), stations=new ArrayList<Choice>();
        public List<String> route=new ArrayList<String>();
        public List<Service> services=new ArrayList<Service>();
        public int runningServices;
    }
    public static final class Request {
        public final Action action;
        public final String choice, text;
        public final double speed;
        public final List<String> route;
        public final List<Service> services;
        /** Presentation-only draft row, never an object identity or a committed route change. */
        public final int selectedRouteStop;
        private Request(Action action,String choice,String text,double speed,List<String> route) {
            this(action,choice,text,speed,route,-1);
        }
        private Request(Action action,String choice,String text,double speed,List<String> route,int selectedRouteStop) {
            this(action,choice,text,speed,route,selectedRouteStop,Collections.<Service>emptyList());
        }
        private Request(Action action,String choice,String text,double speed,List<String> route,int selectedRouteStop,List<Service> services) {
            this.action=action; this.choice=choice; this.text=text; this.speed=speed;
            this.route=Collections.unmodifiableList(new ArrayList<String>(route));
            this.selectedRouteStop=routeSelection(this.route,selectedRouteStop);
            this.services=serviceCopy(services);
        }
        public static Request of(Action a) { return choose(a,""); }
        public static Request choose(Action a,String choice) { return new Request(a,choice,"",0,Collections.<String>emptyList()); }
        public static Request speed(double v) { return new Request(Action.SPEED,"","",v,Collections.<String>emptyList()); }
        public static Request rename(String v) { return new Request(Action.RENAME,"",v,0,Collections.<String>emptyList()); }
        public static Request route(Action a,List<String> v) { return new Request(a,"","",0,v); }
        public static Request route(Action a,List<String> v,int selectedRouteStop) { return new Request(a,"","",0,v,selectedRouteStop); }
        public static Request services(Action a,List<Service> services) {
            if(a!=Action.SERVICES_START&&a!=Action.SERVICES_STOP&&a!=Action.SERVICES_RESOLVE)
                throw new IllegalArgumentException("Choose an available railway service action.");
            return new Request(a,"","",0,Collections.<String>emptyList(),-1,services);
        }
    }

    public static void show(final Creature actor,final long carId,final Api api) throws Exception {
        showWindow(actor,carId,api,false);
    }

    public static void showManagement(final Creature actor,final long carId,final Api api) throws Exception {
        showWindow(actor,carId,api,true);
    }

    private static void showWindow(final Creature actor,final long carId,final Api api,final boolean manage) throws Exception {
        final View v=freeze(api.snapshot(actor,carId));
        if(manage&&(v.train==null||!v.canControl))
            throw new IllegalArgumentException("Only the owner or an allowed operator can manage this train.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,(manage?"Manage train - ":"Train controls - ")+v.title,"",carId,new ModQuestion() {
            @Override public void sendQuestion(Question q) {
                sendBml(q,650,590,true,true,ModQuestions.getBmlHeaderNoQuestion(q)
                        +(manage?managementContent(v):content(v))+"}};null;null;}");
            }
            @Override public void answer(Question q,Properties a) {
                if(q.getResponder()!=actor || !answered.compareAndSet(false,true)) return;
                try {
                    Request request=fromAnswers(v,a);
                    if(request!=null) api.submit(actor,carId,v.train,request);
                } catch(Exception bad) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+bad.getMessage());
                    api.submit(actor,carId,v.train,Request.of(Action.REFRESH));
                }
            }
        }).sendQuestion();
    }

    /** Native BML fragment, without the question/layout wrapper, for parser regression checks. */
    public static String content(View view) {
        View v=freeze(view);
        StringBuilder b=new StringBuilder();
        heading(b,v.title);
        label(b,"Status: "+v.status);
        label(b,String.format(Locale.ROOT,"%s | Power %s | %.1f m/s | %d/%d seats occupied",
                v.mode,v.powered?"on":"off",v.speed,v.occupiedSeats,v.seats));
        if(v.train!=null) {
            if(v.canControl) {
                heading(b,"Drive");
                b.append("harray{"); button(b,"start",v.powered?"Keep power on":"Power on");
                button(b,"stop","STOP"); button(b,"reverse","Reverse manually"); b.append("};");
                label(b,"Reverse backs up without turning the locomotive. It clears the route and switches power off.");
                heading(b,"Automatic service");
                label(b,"Automatic trains use forward routes around available loops. Backing is reserved for routes that need it.");
                label(b,v.routeSummary);
                serviceStatus(b,v);
                if(!v.route.isEmpty())label(b,v.resumeEnabled?"This service will resume after server restart.":"This service will stay stopped after server restart until powered on.");
                b.append("harray{"); button(b,"route",v.route.isEmpty()?"Set up train route":"Edit train route");
                button(b,"manual","Manual driving"); b.append("};");
                if(!v.services.isEmpty())button(b,"services","Railway services ("+v.services.size()+")");
                if(v.route.isEmpty()) label(b,"Add two stops and choose Save and start to run automatic service.");
                heading(b,"Speed limit");
                label(b,"The same speed limit and acceleration apply in forward and reverse.");
                label(b,String.format(Locale.ROOT,"Full selects the server limit: %.2f m/s.",v.speedLimit));
                b.append("harray{"); button(b,"speedCrawl","Crawl"); button(b,"speedCruise","Cruise");
                button(b,"speedFull","Full"); b.append("};");
                b.append("harray{label{text='m/s'};input{id='speed';maxchars='5';text='").append(v.maxSpeed).append("'};");
                button(b,"speedApply","Set speed"); b.append("};");
            } else {
                heading(b,"Your journey"); label(b,v.routeSummary); serviceStatus(b,v);
                label(b,"Passenger view. The owner or an allowed operator controls this train.");
            }
        } else if(v.canControl) {
            heading(b,"Get ready");
            if(v.engine) {
                label(b,"Place the locomotive on connected track to start using it.");
                if(!v.rails.isEmpty()) { choices(b,"rail",v.rails,0); button(b,"place","Place on track"); }
                else label(b,"Build connected track within eight metres first.");
            } else {
                label(b,"This wagon is not attached to a train.");
                if(!v.trains.isEmpty()) { choices(b,"train",v.trains,0); button(b,"attach","Attach to train"); }
                else label(b,"Park a locomotive on connected track, then bring this wagon near its rear.");
            }
        }
        heading(b,"Ride and cargo");
        if(v.cargoStatus!=null&&!v.cargoStatus.isEmpty())for(String capacity:v.cargoStatus.split("; "))label(b,capacity);
        if(!v.cars.isEmpty()) choices(b,"car",v.cars,indexOf(v.cars,Long.toString(v.carId)));
        b.append("harray{");
        if(!v.aboard) button(b,"board","Board selected vehicle");
        button(b,"cargo","Open cargo");
        if(v.train!=null) button(b,"passenger_stops","My destination"); b.append("};");
        b.append("harray{");
        if(v.train!=null&&v.canControl) button(b,"manage","Manage train");
        button(b,"refresh","Refresh status"); button(b,"close","Close"); b.append("};");
        return b.toString();
    }

    /** Coupling and naming stay outside the everyday driving panel. */
    public static String managementContent(View view) {
        View v=freeze(view);
        if(v.train==null||!v.canControl) throw new IllegalArgumentException("Only the owner or an allowed operator can manage this train.");
        StringBuilder b=new StringBuilder();
        heading(b,v.title); label(b,"Status: "+v.status);
        label(b,"Press STOP in Train controls before changing wagons or the train name.");
        heading(b,"Train name");
        b.append("harray{input{id='name';maxchars='40';text='").append(RailwayBml.text(v.title)).append("'};");
        button(b,"rename","Rename"); b.append("};");
        heading(b,"Wagons");
        label(b,"Attach a wagon parked near the rear. Its cargo and seats stay with it.");
        if(!v.wagons.isEmpty()) {
            choices(b,"wagon",v.wagons,0); button(b,"couple","Attach selected wagon");
        } else label(b,"No nearby wagons. Park a wagon at the rear and stand beside it.");
        button(b,"uncouple","Detach rear wagon");
        label(b,"Occupied wagons cannot be attached or detached.");
        b.append("harray{"); button(b,"refresh","Back to train controls"); button(b,"close","Close"); b.append("};");
        return b.toString();
    }

    /** A submitted answer can select only a control and object offered by this snapshot. */
    public static Request fromAnswers(View view,Properties a) {
        View v=freeze(view);
        String key=oneButton(a,"start","stop","reverse","speedApply","speedCrawl","speedCruise","speedFull","route","manual","rename",
                "couple","uncouple","place","attach","board","cargo","refresh","close","passenger_stops","manage","services");
        if(key==null||key.equals("close")) return null;
        if(key.equals("refresh")) return Request.of(Action.REFRESH);
        if(key.equals("board")) {
            if(v.aboard) throw new IllegalArgumentException("You are already aboard a vehicle. Disembark before boarding another.");
            return Request.choose(Action.BOARD,selected(a,"car",v.cars));
        }
        if(key.equals("cargo")) return Request.choose(Action.CARGO,selected(a,"car",v.cars));
        if(key.equals("passenger_stops")) {
            if(v.train==null)throw new IllegalArgumentException("Board a registered rail train first.");
            return Request.of(Action.PASSENGER_STOPS);
        }
        if(!v.canControl) throw new IllegalArgumentException("Only the owner or an allowed operator can control this train.");
        if(key.equals("place")) {
            if(v.train!=null||!v.engine) throw new IllegalArgumentException("Choose an unattached locomotive to place on track.");
            return Request.choose(Action.PLACE,selected(a,"rail",v.rails));
        }
        if(key.equals("attach")) {
            if(v.train!=null||v.engine) throw new IllegalArgumentException("Choose an unattached wagon to couple.");
            return Request.choose(Action.COUPLE,selected(a,"train",v.trains));
        }
        if(v.train==null) throw new IllegalArgumentException("Place the locomotive on connected track or attach this wagon first.");
        Request request;
        if(key.equals("start")) request=Request.of(Action.START);
        else if(key.equals("stop")) request=Request.of(Action.STOP);
        else if(key.equals("reverse")) request=Request.of(Action.REVERSE);
        else if(key.equals("speedApply")) request=Request.speed(number(a.getProperty("speed"),.25,v.speedLimit));
        else if(key.equals("speedCrawl")) request=Request.speed(number(Double.toString(Math.min(.5,v.speedLimit)),.25,v.speedLimit));
        else if(key.equals("speedCruise")) request=Request.speed(number(Double.toString(Math.min(1.5,v.speedLimit)),.25,v.speedLimit));
        else if(key.equals("speedFull")) request=Request.speed(number(Double.toString(v.speedLimit),.25,v.speedLimit));
        else if(key.equals("rename")) request=Request.rename(displayName(a.getProperty("name","")));
        else if(key.equals("route")) request=Request.route(Action.ROUTE_EDIT,v.route);
        else if(key.equals("manual")) request=Request.of(Action.MANUAL);
        else if(key.equals("couple")) request=Request.choose(Action.COUPLE,selected(a,"wagon",v.wagons));
        else if(key.equals("uncouple")) request=Request.of(Action.UNCOUPLE);
        else if(key.equals("manage")) request=Request.of(Action.MANAGE);
        else if(key.equals("services")) {
            if(v.services.isEmpty())throw new IllegalArgumentException("No scheduled trains were offered in this window. Configure a route first.");
            request=Request.of(Action.SERVICES);
        }
        else request=Request.of(Action.REFRESH);
        return request;
    }

    public static void showServices(final Creature actor,final long carId,final Train expected,final Api api) throws Exception {
        final View v=freeze(api.snapshot(actor,carId));
        if(v.train!=expected||expected==null||!v.canControl)
            throw new IllegalArgumentException("The train or your operating permission changed. Open its controls again.");
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Railway services","",carId,new ModQuestion() {
            @Override public void sendQuestion(Question q) {
                sendBml(q,650,590,true,true,ModQuestions.getBmlHeaderNoQuestion(q)+servicesContent(v)+"}};null;null;}");
            }
            @Override public void answer(Question q,Properties a) {
                if(q.getResponder()!=actor||!answered.compareAndSet(false,true))return;
                try {
                    Request request=servicesFromAnswers(v,a);
                    if(request!=null)api.submit(actor,carId,expected,request);
                } catch(IllegalArgumentException bad) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+bad.getMessage());
                    api.submit(actor,carId,expected,Request.of(Action.SERVICES));
                }
            }
        }).sendQuestion();
    }

    public static String servicesContent(View view) {
        View v=freeze(view);
        if(v.train==null||!v.canControl)throw new IllegalArgumentException("Only an allowed operator can manage scheduled trains.");
        StringBuilder b=new StringBuilder();heading(b,"Railway services");
        label(b,v.services.size()+" scheduled trains you can operate | "+v.runningServices+" powered on");
        label(b,"Start and stop apply to the scheduled trains listed in this window.");
        label(b,v.serverResumeEnabled?"Running scheduled services resume after server restart. STOP keeps them stopped."
                :"Automatic restart is disabled by this server. Use Start all scheduled after a restart.");
        label(b,"Clear traffic jams asks running scheduled trains to yield safely. Stopped and manual trains stay stopped.");
        label(b,"Blocked queues clear in stages: a train at the rear can back out first to make room.");
        label(b,"Backing station arrivals have priority; followers wait and use the compatible loop arm.");
        if(v.services.isEmpty())label(b,"No scheduled trains are available. Save a route with at least two stops first.");
        else {
            b.append("harray{");button(b,"servicesStart","Start all scheduled");button(b,"servicesStop","Stop all scheduled");b.append("};");
            button(b,"servicesResolve","Clear traffic jams");
            heading(b,"Included trains");
            for(int i=0;i<Math.min(50,v.services.size());i++) {
                Service service=v.services.get(i);heading(b,service.label+" | Power "+(service.powered?"ON":"OFF"));
                serviceDetail(b,"Status: "+(service.status.isEmpty()?"No status available":service.status));
                serviceDetail(b,"Traffic: "+(service.traffic.isEmpty()?"No dispatcher wait or reservation":service.traffic));
            }
            if(v.services.size()>50)label(b,"... "+v.services.size()+" scheduled trains included.");
        }
        b.append("harray{");button(b,"servicesRefresh","Refresh services");button(b,"back","Back to train controls");button(b,"close","Close");b.append("};");
        return b.toString();
    }

    public static Request servicesFromAnswers(View view,Properties a) {
        View v=freeze(view);
        if(v.train==null||!v.canControl)throw new IllegalArgumentException("Only an allowed operator can manage scheduled trains.");
        String key=oneButton(a,"servicesStart","servicesStop","servicesResolve","servicesRefresh","back","close");
        if(key==null||key.equals("close"))return null;
        if(key.equals("back"))return Request.of(Action.REFRESH);
        if(key.equals("servicesRefresh"))return Request.of(Action.SERVICES);
        if(v.services.isEmpty())throw new IllegalArgumentException("No scheduled trains were offered in this window. Refresh railway services.");
        return Request.services(key.equals("servicesStart")?Action.SERVICES_START:key.equals("servicesStop")?Action.SERVICES_STOP:Action.SERVICES_RESOLVE,v.services);
    }

    public static void routeEditor(final Creature actor,final long carId,final Train expected,
            List<String> pending,final Api api) throws Exception {
        routeEditor(actor,carId,expected,pending,-1,api);
    }

    public static void routeEditor(final Creature actor,final long carId,final Train expected,
            List<String> pending,int selectedRouteStop,final Api api) throws Exception {
        final View v=freeze(api.snapshot(actor,carId));
        if(v.train!=expected || expected==null) throw new IllegalArgumentException("The train changed. Open its controls again.");
        if(!v.canControl) throw new IllegalArgumentException("Only the owner or an allowed operator can edit this route.");
        final List<String> draft=draft(pending);
        final int selection=routeSelection(draft,selectedRouteStop);
        final AtomicBoolean answered=new AtomicBoolean();
        ModQuestions.createQuestion(actor,"Train route - "+v.title,"",carId,new ModQuestion() {
            @Override public void sendQuestion(Question q) {
                sendBml(q,650,610,true,true,ModQuestions.getBmlHeaderNoQuestion(q)+routeContent(v,draft,selection)+"}};null;null;}");
            }
            @Override public void answer(Question q,Properties a) {
                if(q.getResponder()!=actor||!answered.compareAndSet(false,true)) return;
                try {
                    Request request=routeFromAnswers(v,draft,a);
                    if(request!=null) api.submit(actor,carId,expected,request);
                } catch(IllegalArgumentException bad) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] "+bad.getMessage());
                    api.submit(actor,carId,expected,Request.route(Action.ROUTE_EDIT,draft,selection));
                }
            }
        }).sendQuestion();
    }

    /** Draft edits are local to this window; Save and Save and start are explicit commit actions. */
    public static String routeContent(View view,List<String> pending) {
        return routeContent(view,pending,-1);
    }

    public static String routeContent(View view,List<String> pending,int selectedRouteStop) {
        View v=freeze(view);
        List<String> draft=draft(pending);
        StringBuilder b=new StringBuilder();
        heading(b,"Train route - "+v.title);
        label(b,"Add stops in travel order. Two or more stops repeat until you stop the train.");
        label(b,"The train follows track curves. At a reversing stop it backs up without turning its body.");
        label(b,"Opposing services need passing track and enough clear room for each complete train.");
        label(b,"Station tiles pick up and drop off passengers. Add them to this service just like named track stops.");
        label(b,"Freight stops load or unload general cargo wagons and timber Log Cars before continuing along this same route.");
        label(b,String.format(Locale.ROOT,"Station tiles: %.1f s wait. Named track stops use their configured wait.",v.passengerStopWaitSeconds));
        heading(b,"Available stops ("+v.stations.size()+")");
        if(!v.routeHelp.isEmpty()) label(b,v.routeHelp);
        if(v.stations.isEmpty()) {
            label(b,"No stops are connected to this train's track network.");
            label(b,"Build a station, configure Freight stop on connected track, or name a stop in Railway track.");
            label(b,"Check that track reaches the stop, then reopen this route window.");
        } else {
            choices(b,"station",v.stations,0); button(b,"add","Add selected stop");
        }
        heading(b,"Travel order ("+draft.size()+" stops)");
        if(draft.isEmpty()) label(b,"Your route is empty. Add at least two stops for automatic service.");
        else {
            List<Choice> ordered=new ArrayList<Choice>();
            for(int i=0;i<draft.size();i++) ordered.add(new Choice(Integer.toString(i),(i+1)+". "+choiceLabel(v.stations,draft.get(i))));
            for(int i=0;i<Math.min(50,ordered.size());i++) label(b,ordered.get(i).label);
            if(ordered.size()>50) label(b,"... "+ordered.size()+" total stops; select any stop below.");
            choices(b,"route_stop",ordered,routeSelection(draft,selectedRouteStop));
            b.append("harray{"); button(b,"moveUp","Move earlier"); button(b,"moveDown","Move later");
            button(b,"removeSelected","Remove selected"); b.append("};");
            button(b,"clear","Clear draft");
        }
        label(b,"Changes apply only when saved. Saving an empty route enables manual driving.");
        label(b,"Stop the train before saving. Save and start also switches power on.");
        b.append("harray{"); button(b,"saveStart","Save and start"); button(b,"save","Save route");
        button(b,"back","Cancel / back"); b.append("};");
        return b.toString();
    }

    public static Request routeFromAnswers(View view,List<String> pending,Properties a) {
        View v=freeze(view);
        if(v.train==null||!v.canControl) throw new IllegalArgumentException("Only the owner or an allowed operator can edit this route.");
        List<String> draft=draft(pending);
        String key=oneButton(a,"add","remove","removeSelected","moveUp","moveDown","clear","save","saveStart","back");
        if(key==null) return null;
        List<String> next=new ArrayList<String>(draft);
        int selection=routeSelection(next,-1);
        if(key.equals("back")) return Request.of(Action.REFRESH);
        if(key.equals("add")) {
            if(next.size()>=1000) throw new IllegalArgumentException("This route already has the maximum number of stops.");
            String stop=selected(a,"station",v.stations);
            if(!next.isEmpty()&&next.get(next.size()-1).equals(stop))
                throw new IllegalArgumentException("That stop is already last in the route. Choose a different next stop.");
            next.add(stop);
            selection=next.size()-1;
        } else if(key.equals("remove")&&!next.isEmpty()) {
            next.remove(next.size()-1); selection=next.size()-1;
        }
        else if(key.equals("removeSelected")||key.equals("moveUp")||key.equals("moveDown")) {
            int index=selectedRouteStop(a,next);
            if(key.equals("removeSelected")) {
                next.remove(index); selection=Math.min(index,next.size()-1);
            }
            else {
                int destination=index+(key.equals("moveUp")?-1:1);
                selection=index;
                if(destination>=0&&destination<next.size()) {
                    Collections.swap(next,index,destination); selection=destination;
                }
            }
        }
        else if(key.equals("clear")) { next.clear(); selection=-1; }
        if(key.equals("save")||key.equals("saveStart")) validateAdjacentStops(next);
        if(key.equals("saveStart")&&next.size()<2)
            throw new IllegalArgumentException("Add at least two different stops before starting automatic service.");
        return Request.route(key.equals("saveStart")?Action.ROUTE_START:key.equals("save")?Action.ROUTE_SAVE:Action.ROUTE_EDIT,next,selection);
    }

    private static int routeSelection(List<String> route,int selection) {
        if(route.isEmpty()) return -1;
        return selection<0||selection>=route.size()?route.size()-1:selection;
    }

    private static int selectedRouteStop(Properties a,List<String> route) {
        int index;
        try { index=Integer.parseInt(a.getProperty("route_stop","-1")); }
        catch(NumberFormatException bad) { throw new IllegalArgumentException("Choose a stop from the travel order first."); }
        if(index<0||index>=route.size()) throw new IllegalArgumentException("Choose a stop from the travel order first.");
        return index;
    }

    private static void validateAdjacentStops(List<String> route) {
        for(int i=1;i<route.size();i++) if(route.get(i).equals(route.get(i-1)))
            throw new IllegalArgumentException("Stops "+i+" and "+(i+1)+" are the same. Remove one or put a different stop between them.");
    }

    private static View freeze(View source) {
        if(source==null) throw new IllegalArgumentException("The railway window is unavailable. Try again shortly.");
        View copy=new View();
        copy.carId=source.carId; copy.train=source.train; copy.title=source.title; copy.status=source.status;
        copy.mode=source.mode; copy.routeSummary=source.routeSummary;
        copy.trafficStatus=source.trafficStatus==null?"":source.trafficStatus;
        copy.nextStop=source.nextStop==null?"":source.nextStop;
        copy.routeHelp=source.routeHelp==null?"":source.routeHelp; copy.engine=source.engine;
        copy.canControl=source.canControl; copy.powered=source.powered; copy.resumeEnabled=source.resumeEnabled;copy.serverResumeEnabled=source.serverResumeEnabled;copy.speed=source.speed;
        copy.aboard=source.aboard;
        copy.maxSpeed=source.maxSpeed; copy.speedLimit=source.speedLimit;
        copy.passengerStopWaitSeconds=source.passengerStopWaitSeconds;
        copy.occupiedSeats=source.occupiedSeats; copy.seats=source.seats; copy.cargoItems=source.cargoItems;
        copy.cargoStatus=source.cargoStatus==null?"":source.cargoStatus;
        copy.cars=choices(source.cars); copy.wagons=choices(source.wagons); copy.trains=choices(source.trains);
        copy.rails=choices(source.rails); copy.stations=choices(source.stations); copy.route=draft(source.route);
        copy.services=serviceCopy(source.services);copy.runningServices=Math.max(0,Math.min(source.runningServices,copy.services.size()));
        return copy;
    }

    private static List<Service> serviceCopy(List<Service> source) {
        if(source==null)throw new IllegalArgumentException("The available scheduled trains changed. Open the window again.");
        List<Service> copy=new ArrayList<Service>(source);Set<String> names=new HashSet<String>();Set<Long> locomotives=new HashSet<Long>();
        for(Service service:copy)if(service==null||service.train==null||!names.add(service.name)||!locomotives.add(service.locomotiveId))
            throw new IllegalArgumentException("The available scheduled trains changed. Open the window again.");
        return Collections.unmodifiableList(copy);
    }

    private static List<Choice> choices(List<Choice> source) {
        if(source==null) throw new IllegalArgumentException("The available railway choices changed. Open the window again.");
        List<Choice> result=new ArrayList<Choice>(source);
        for(Choice choice:result) if(choice==null||choice.key==null)
            throw new IllegalArgumentException("The available railway choices changed. Open the window again.");
        return Collections.unmodifiableList(result);
    }

    private static List<String> draft(List<String> source) {
        if(source==null||source.size()>1000) throw new IllegalArgumentException("A route can contain at most 1000 stops.");
        List<String> result=new ArrayList<String>(source);
        for(String name:result) if(name==null) throw new IllegalArgumentException("A route stop is no longer available.");
        return Collections.unmodifiableList(result);
    }

    private static String displayName(String source) {
        String name=source.trim();
        if(name.isEmpty()||name.length()>40) throw new IllegalArgumentException("Train names must contain 1 to 40 characters.");
        for(int i=0;i<name.length();i++) if(Character.isISOControl(name.charAt(i)))
            throw new IllegalArgumentException("Train names must use printable characters.");
        return name;
    }

    public static String oneButton(Properties a,String... names) {
        String result=null;
        for(String name:names) if("true".equals(a.getProperty(name))) {
            if(result!=null) throw new IllegalArgumentException("Choose one action at a time."); result=name;
        }
        return result;
    }
    private static double number(String text,double min,double max) {
        double value;
        try { value=Double.parseDouble(text==null?"":text); }
        catch(NumberFormatException bad) { throw new IllegalArgumentException("Choose a speed between "+min+" and "+max+" m/s."); }
        if(!Double.isFinite(value)||value<min||value>max) throw new IllegalArgumentException("Choose a speed between "+min+" and "+max+" m/s.");
        return value;
    }
    private static String selected(Properties a,String name,List<Choice> values) {
        int index;
        try { index=Integer.parseInt(a.getProperty(name,"-1")); }
        catch(NumberFormatException bad) { throw new IllegalArgumentException("Choose an available "+name+"."); }
        if(index<0||index>=values.size()) throw new IllegalArgumentException("Choose an available "+name+".");
        return values.get(index).key;
    }
    private static int indexOf(List<Choice> choices,String key) { for(int i=0;i<choices.size();i++) if(choices.get(i).key.equals(key)) return i; return 0; }
    private static String choiceLabel(List<Choice> choices,String key) { for(Choice c:choices) if(c.key.equals(key)) return c.label; return "Unavailable stop - remove it or reconnect the track"; }
    private static void serviceStatus(StringBuilder b,View view) {
        if(!view.nextStop.isEmpty()) label(b,"Next stop: "+view.nextStop);
        if(!view.trafficStatus.isEmpty()) label(b,"Traffic: "+view.trafficStatus);
    }
    /** Native labels do not wrap; keep the end of a long stop reason visible in the fleet panel. */
    private static void serviceDetail(StringBuilder b,String text) {
        String remaining=text.replace('\n',' ').replace('\r',' ');
        while(remaining.length()>84) {
            int split=remaining.lastIndexOf(' ',84);if(split<16)split=84;
            label(b,remaining.substring(0,split));remaining="  "+remaining.substring(split).trim();
        }
        label(b,remaining);
    }
    private static void choices(StringBuilder b,String id,List<Choice> values,int selected) {
        b.append("dropdown{id='").append(id).append("';default='").append(selected).append("';options='");
        for(int i=0;i<values.size();i++) { if(i>0)b.append(','); b.append(RailwayBml.option(values.get(i).label)); }
        b.append("'};");
    }
    private static void label(StringBuilder b,String text) { b.append("label{text='").append(RailwayBml.text(text)).append("'};"); }
    private static void heading(StringBuilder b,String text) { b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};"); }
    private static void button(StringBuilder b,String id,String text) { b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};"); }
}
