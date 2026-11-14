package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** Native station windows. The runtime queues and revalidates every submitted request. */
public final class RailwayPassengerUi {
    private static final long NO_ID = -10L;
    private static final String[] BUTTONS = {"destination", "cancel_destination", "rename", "remove", "refresh", "close"};

    private RailwayPassengerUi() { }

    /** Legacy values remain accepted by callers; every station now provides both services. */
    public enum Mode { STATION, PICKUP, DROPOFF }
    public enum Action { CHOOSE_DESTINATION, CANCEL_DESTINATION, RENAME, REMOVE, REFRESH, CLOSE }
    public enum BuildAction { BUILD, REFRESH, CLOSE }

    public interface Api {
        View snapshot(Creature actor, long platformItemId) throws Exception;
        /** Must queue work, then recheck captured identity, reach, permissions and destination connectivity. */
        void submit(Creature actor, long platformItemId, Request request);
    }

    public interface BuilderApi {
        BuildView buildSnapshot(Creature actor, int tileX, int tileY, int layer) throws Exception;
        /** Must queue work and recheck the tile, adjacent rail, permissions and one-section construction cost. */
        void build(Creature actor, int tileX, int tileY, int layer, BuildRequest request);
    }

    public static final class Choice {
        public final long id;
        public final String label;
        public Choice(long id, String label) {
            if (id <= 0) throw new IllegalArgumentException("A passenger destination must identify a world stop.");
            this.id = id; this.label = label == null ? "Station" : label;
        }
    }

    public static final class RailChoice {
        public final String key, label;
        public RailChoice(String key, String label) {
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("An adjacent railway is required.");
            this.key = key; this.label = label == null ? "Adjacent railway" : label;
        }
    }

    public static final class View {
        public final long platformItemId, revision, trainEngineId, selectedDestinationId;
        public final Mode mode;
        public final String name, linkedTrackLabel, status;
        public final boolean canManage;
        /** availableSeats=-1 means there is no current train capacity to report. */
        public final int waitingCount, availableSeats;
        public final List<Choice> destinations;

        public View(long platformItemId, long revision, long trainEngineId, Mode mode,
                    String name, String linkedTrackLabel, String status, boolean canManage,
                    long selectedDestinationId, int waitingCount, int availableSeats, List<Choice> destinations) {
            if (platformItemId > 0 && mode == null) throw new IllegalArgumentException("A station type is required.");
            if (revision < 0) throw new IllegalArgumentException("A station revision cannot be negative.");
            this.platformItemId = platformItemId; this.revision = revision; this.trainEngineId = trainEngineId;
            this.mode = platformItemId > 0 ? Mode.STATION : mode; this.name = name == null ? "Station" : name;
            this.linkedTrackLabel = linkedTrackLabel == null ? "" : linkedTrackLabel;
            this.status = status == null ? "" : status;
            this.canManage = platformItemId > 0 && canManage;
            this.selectedDestinationId = selectedDestinationId > 0 ? selectedDestinationId : NO_ID;
            this.waitingCount = Math.max(0, waitingCount); this.availableSeats = Math.max(-1, availableSeats);
            this.destinations = destinationChoices(destinations);
        }

        public boolean personal() { return platformItemId <= 0; }
    }

    public static final class Request {
        public final Action action;
        public final long expectedPlatformItemId, revision, trainEngineId, destinationId;
        public final String name;
        private Request(View view, Action action, long destinationId, String name) {
            this.action = action; this.expectedPlatformItemId = view.platformItemId;
            this.revision = view.revision; this.trainEngineId = view.trainEngineId;
            this.destinationId = destinationId; this.name = name;
        }
        public static Request refresh(View view) { return new Request(view, Action.REFRESH, NO_ID, ""); }
    }

    public static final class BuildView {
        public final String location;
        public final List<RailChoice> rails;
        public final Mode defaultMode;
        public final int availableSections;
        public final boolean isGM;
        public BuildView(String location, List<RailChoice> rails, Mode defaultMode, int availableSections, boolean isGM) {
            this.location = location == null ? "Beside the railway" : location;
            this.rails = railChoices(rails);
            this.defaultMode = Mode.STATION;
            this.availableSections = Math.max(0, availableSections); this.isGM = isGM;
        }
    }

    public static final class BuildRequest {
        public final BuildAction action;
        public final String trackKey, name;
        public final Mode mode;
        private BuildRequest(BuildAction action, String trackKey, Mode mode, String name) {
            this.action = action; this.trackKey = trackKey; this.mode = Mode.STATION; this.name = name;
        }
        public static BuildRequest refresh() { return new BuildRequest(BuildAction.REFRESH, "", Mode.STATION, ""); }
    }

    public static void show(final Creature actor, final long platformItemId, final Api api) throws Exception {
        final View view = api.snapshot(actor, platformItemId);
        if (view.platformItemId != platformItemId)
            throw new IllegalArgumentException("This station changed. Open the window again.");
        final AtomicBoolean answered = new AtomicBoolean();
        ModQuestions.createQuestion(actor, view.personal() ? "My railway destination" : "Railway station", "", platformItemId, new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question, 680, view.canManage ? 660 : 490, true, true,
                        ModQuestions.getBmlHeaderNoQuestion(question) + content(view) + "}};null;null;}");
            }
            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() != actor || !answered.compareAndSet(false, true)) return;
                try {
                    Request request = fromAnswers(view, answers);
                    if (request != null) api.submit(actor, platformItemId, request);
                } catch (IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] " + invalid.getMessage());
                    api.submit(actor, platformItemId, Request.refresh(view));
                }
            }
        }).sendQuestion();
    }

    public static void showBuilder(final Creature actor, final int tileX, final int tileY, final int layer,
                                   final BuilderApi api) throws Exception {
        final BuildView view = api.buildSnapshot(actor, tileX, tileY, layer);
        final AtomicBoolean answered = new AtomicBoolean();
        ModQuestions.createQuestion(actor, "Build station", "", NO_ID, new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question, 680, 450, true, true,
                        ModQuestions.getBmlHeaderNoQuestion(question) + buildContent(view) + "}};null;null;}");
            }
            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() != actor || !answered.compareAndSet(false, true)) return;
                try {
                    BuildRequest request = buildFromAnswers(view, answers);
                    if (request != null) api.build(actor, tileX, tileY, layer, request);
                } catch (IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] " + invalid.getMessage());
                    api.build(actor, tileX, tileY, layer, BuildRequest.refresh());
                }
            }
        }).sendQuestion();
    }

    public static String content(View view) {
        StringBuilder b = new StringBuilder();
        heading(b, view.personal() ? "My railway journey" : view.name);
        if (!view.personal()) {
            label(b, "STATION | Drop off and pick up | Linked track: " + view.linkedTrackLabel);
            if (!view.status.isEmpty()) heading(b, view.status);
            label(b, "Waiting: " + view.waitingCount + (view.availableSeats < 0 ? ""
                    : " | Free seats on current train: " + view.availableSeats));
            label(b, "Stand on this tile to wait for a train with a free seat. Step off the tile to stop waiting.");
            label(b, "While waiting, look for the Links icon in your status bar. Hover over it for the station name.");
            label(b, "Trains drop off riders first, then board waiting players.");
            label(b, "After being dropped off here, leave the station tile before returning to wait again.");
            heading(b, "Train route stop");
            label(b, "For connected trains: Train controls > Automatic service.");
            label(b, "Use Set up train route or Edit train route to include this stop.");
            label(b, "This station appears automatically. No extra named stop is needed.");
        }
        if (view.personal() && !view.status.isEmpty()) heading(b, view.status);
        heading(b, "My destination");
        label(b, "Choose where you leave the train while waiting or riding. This is optional.");
        label(b, "Your choice does not change the driver's train route.");
        if (view.selectedDestinationId > 0)
            heading(b, "Selected: " + destinationLabel(view));
        else label(b, "Selected: none - ride without an automatic drop-off.");
        if (view.destinations.isEmpty()) {
            label(b, "No stations are connected to this railway.");
            label(b, "Build a station beside connected track, then choose Refresh.");
        }
        else {
            b.append("dropdown{id='destination_choice';default='").append(destinationIndex(view)).append("';options='");
            for (int i = 0; i < view.destinations.size(); i++) {
                if (i > 0) b.append(',');
                b.append(RailwayBml.option(view.destinations.get(i).label));
            }
            b.append("'};harray{"); button(b, "destination", "Set my destination");
            if (view.selectedDestinationId > 0) button(b, "cancel_destination", "Clear my destination");
            b.append("};");
        }
        if (view.destinations.isEmpty() && view.selectedDestinationId > 0) button(b, "cancel_destination", "Clear my destination");
        if (view.canManage) {
            heading(b, "Manage this tile");
            b.append("harray{label{text='Station name:'};input{id='stop_name';maxchars='48';text='")
                    .append(RailwayBml.text(view.name)).append("'};");
            button(b, "rename", "Save name"); b.append("};");
            label(b, "Remove this station tile only; the railway remains in place.");
            b.append("checkbox{id='confirm_remove';selected='false';text='Confirm removal of this station tile'};");
            button(b, "remove", "Remove station tile");
        }
        b.append("harray{"); button(b, "refresh", "Refresh"); button(b, "close", "Close"); b.append("};");
        return b.toString();
    }

    public static String buildContent(BuildView view) {
        StringBuilder b = new StringBuilder();
        heading(b, view.location);
        heading(b, "Build a station");
        label(b, "Place a marked tile beside the track. It also appears in the train route list.");
        label(b, "One station tile drops off riders and picks up waiting players.");
        label(b, "Leave track beyond the stop so rear wagons can reach it before the train turns around.");
        label(b, view.isGM ? "GM construction: no track section consumed."
                : "Uses one crafted rail track section. In your inventory: " + view.availableSections + ".");
        if (view.rails.isEmpty()) label(b, "Build an adjacent rail section first, then refresh this window.");
        else {
            b.append("harray{label{text='Linked track:'};dropdown{id='rail';default='0';options='");
            for (int i = 0; i < view.rails.size(); i++) {
                if (i > 0) b.append(','); b.append(RailwayBml.option(view.rails.get(i).label));
            }
            b.append("'};};");
            b.append("harray{label{text='Station name:'};input{id='stop_name';maxchars='48';text=''};};");
            button(b, "build", "Build station");
        }
        b.append("harray{"); button(b, "refresh", "Refresh"); button(b, "close", "Close"); b.append("};");
        return b.toString();
    }

    public static Request fromAnswers(View view, Properties answers) {
        String button = oneButton(answers, BUTTONS);
        if (button == null || button.equals("close")) return null;
        if (button.equals("refresh")) return Request.refresh(view);
        if (button.equals("destination")) {
            int index = index(answers.getProperty("destination_choice"), view.destinations.size(), "Choose an available station.");
            return new Request(view, Action.CHOOSE_DESTINATION, view.destinations.get(index).id, "");
        }
        if (button.equals("cancel_destination")) return new Request(view, Action.CANCEL_DESTINATION, NO_ID, "");
        if (!view.canManage) throw new IllegalArgumentException("You do not have permission to manage this station.");
        if (button.equals("rename")) return new Request(view, Action.RENAME, NO_ID, name(answers.getProperty("stop_name", "")));
        if (button.equals("remove")) {
            if (!"true".equals(answers.getProperty("confirm_remove"))) throw new IllegalArgumentException("Tick the stop removal confirmation first.");
            return new Request(view, Action.REMOVE, NO_ID, "");
        }
        throw new IllegalArgumentException("Choose an available passenger action.");
    }

    public static BuildRequest buildFromAnswers(BuildView view, Properties answers) {
        String button = oneButton(answers, "build", "refresh", "close");
        if (button == null || button.equals("close")) return null;
        if (button.equals("refresh")) return BuildRequest.refresh();
        int rail = index(answers.getProperty("rail"), view.rails.size(), "Choose an adjacent rail section.");
        // Old clients may submit a stale or forged mode. It cannot select a separate service.
        return new BuildRequest(BuildAction.BUILD, view.rails.get(rail).key, Mode.STATION,
                name(answers.getProperty("stop_name", "")));
    }

    private static List<Choice> destinationChoices(List<Choice> source) {
        if (source == null || source.size() > 512) throw new IllegalArgumentException("Too many passenger destinations.");
        List<Choice> copy = new ArrayList<Choice>(source); Set<Long> seen = new HashSet<Long>();
        for (Choice choice : copy) if (choice == null || !seen.add(choice.id)) throw new IllegalArgumentException("Repeated passenger destination.");
        return Collections.unmodifiableList(copy);
    }

    private static List<RailChoice> railChoices(List<RailChoice> source) {
        if (source == null || source.size() > 8) throw new IllegalArgumentException("Choose a neighboring rail section.");
        List<RailChoice> copy = new ArrayList<RailChoice>(source); Set<String> seen = new HashSet<String>();
        for (RailChoice choice : copy) if (choice == null || !seen.add(choice.key)) throw new IllegalArgumentException("Repeated adjacent railway.");
        return Collections.unmodifiableList(copy);
    }

    private static String oneButton(Properties answers, String... buttons) {
        String result = null;
        for (String button : buttons) if ("true".equals(answers.getProperty(button))) {
            if (result != null) throw new IllegalArgumentException("Choose one passenger action at a time.");
            result = button;
        }
        return result;
    }

    private static int index(String text, int size, String error) {
        try { int value = Integer.parseInt(text == null ? "" : text); if (value >= 0 && value < size) return value; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(error);
    }

    private static String name(String text) {
        String value = text.trim();
        if (value.isEmpty() || value.length() > 48) throw new IllegalArgumentException("Enter a stop name from 1 to 48 characters.");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i)))
            throw new IllegalArgumentException("Stop names must fit on one line.");
        return value;
    }

    private static int destinationIndex(View view) {
        for (int i = 0; i < view.destinations.size(); i++) if (view.destinations.get(i).id == view.selectedDestinationId) return i;
        return 0;
    }

    private static String destinationLabel(View view) {
        for (Choice choice : view.destinations) if (choice.id == view.selectedDestinationId) return choice.label;
        return "No longer available; choose another stop";
    }

    private static void label(StringBuilder b, String text) { b.append("label{text='").append(RailwayBml.text(text)).append("'};"); }
    private static void heading(StringBuilder b, String text) { b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};"); }
    private static void button(StringBuilder b, String id, String text) { b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};"); }
}
