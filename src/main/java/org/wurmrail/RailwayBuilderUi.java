package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;
import org.wurmrail.core.RailNetwork;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native, command-free construction window. The runtime owns and revalidates all mutations. */
public final class RailwayBuilderUi {
    public static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String DIRECTION_OPTIONS =
            "North,Northeast,East,Southeast,South,Southwest,West,Northwest";
    private static final String[] DIRECTION_NAMES = {
            "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"
    };
    private static final String[] BUTTONS = {
            "place", "connections", "alignment", "station", "station_off", "remove", "repair", "refresh",
            "spawn_engine", "spawn_cart", "spawn_logcar"
    };

    private RailwayBuilderUi() { }

    public interface Api {
        /** Called only while the runtime processes the request on the server poll. */
        View snapshot(Creature actor, int tileX, int tileY, int layer) throws Exception;

        /** Must queue the request and recheck identity, distance, level, permissions and costs. */
        void submit(Creature actor, int tileX, int tileY, int layer, Request request);
    }

    public enum Action { PLACE, CONNECTIONS, ALIGNMENT, STATION, REMOVE, REPAIR, REFRESH, SPAWN_ENGINE, SPAWN_CART, SPAWN_LOGCAR }

    public static final class Neighbor {
        public final int direction;
        public final String label;
        public final boolean connected;
        /** Retained for old snapshot callers; junctions use the view's two-ended alignment mask. */
        @Deprecated public final boolean selected;

        public Neighbor(int direction, String label, boolean connected, boolean selected) {
            if (direction < 0 || direction > 7) throw new IllegalArgumentException("Invalid direction.");
            this.direction = direction;
            this.label = label == null ? "Track" : label;
            this.connected = connected;
            this.selected = selected;
        }
    }

    public static final class View {
        public final String location, nodeName, stationName, alignmentOwner;
        public final long markerId;
        public final boolean markerPresent, station, isGM;
        public final double dwellSeconds;
        public final List<Neighbor> neighbors;
        public final int defaultDirection, availableSections, alignmentMask;

        public View(String location, String nodeName, long markerId, boolean markerPresent,
                    boolean station, String stationName, double dwellSeconds,
                    List<Neighbor> neighbors, int defaultDirection, int availableSections, boolean isGM) {
            this(location,nodeName,markerId,markerPresent,station,stationName,dwellSeconds,
                    neighbors,defaultDirection,availableSections,isGM,0);
        }

        public View(String location, String nodeName, long markerId, boolean markerPresent,
                    boolean station, String stationName, double dwellSeconds,
                    List<Neighbor> neighbors, int defaultDirection, int availableSections, boolean isGM,
                    int alignmentMask) {
            this(location,nodeName,markerId,markerPresent,station,stationName,dwellSeconds,
                    neighbors,defaultDirection,availableSections,isGM,alignmentMask,"");
        }

        public View(String location, String nodeName, long markerId, boolean markerPresent,
                    boolean station, String stationName, double dwellSeconds,
                    List<Neighbor> neighbors, int defaultDirection, int availableSections, boolean isGM,
                    int alignmentMask, String alignmentOwner) {
            this.location = location == null ? "Railway" : location;
            this.nodeName = nodeName;
            this.markerId = markerId;
            this.markerPresent = markerPresent;
            this.station = station;
            this.stationName = stationName == null ? "" : stationName;
            this.dwellSeconds = dwellSeconds;
            List<Neighbor> copy = new ArrayList<Neighbor>(neighbors);
            Collections.sort(copy, Comparator.comparingInt(n -> n.direction));
            int seen = 0;
            for (Neighbor n : copy) {
                if ((seen & (1 << n.direction)) != 0) throw new IllegalArgumentException("Repeated neighboring track.");
                seen |= 1 << n.direction;
            }
            this.neighbors = Collections.unmodifiableList(copy);
            this.defaultDirection = Math.max(0, Math.min(7, defaultDirection));
            this.availableSections = Math.max(0, availableSections);
            this.isGM = isGM;
            this.alignmentMask = alignmentMask;
            this.alignmentOwner = alignmentOwner == null ? "" : alignmentOwner;
        }

        public boolean existing() { return nodeName != null; }
    }

    /** The node identity comes from the server's captured view, never a client text field. */
    public static final class Request {
        public final Action action;
        public final String nodeName, stationName;
        public final long markerId;
        /** Direction applies only to new track lines; alignment is an absolute pair of compass bits. */
        public final int direction, count, connectionMask, alignmentMask;
        public final double dwellSeconds;
        public final boolean stationEnabled;

        private Request(Action action, View view, int direction, int count, int connectionMask,
                        String stationName, double dwellSeconds, boolean stationEnabled) {
            this(action,view,direction,count,connectionMask,stationName,dwellSeconds,stationEnabled,0);
        }

        private Request(Action action, View view, int direction, int count, int connectionMask,
                        String stationName, double dwellSeconds, boolean stationEnabled, int alignmentMask) {
            this.action = action;
            this.nodeName = view.nodeName;
            this.markerId = view.markerId;
            this.direction = direction;
            this.count = count;
            this.connectionMask = connectionMask;
            this.stationName = stationName;
            this.dwellSeconds = dwellSeconds;
            this.stationEnabled = stationEnabled;
            this.alignmentMask = alignmentMask;
        }
    }

    /** This entry point is opened from a queued native action, not directly from Question.answer. */
    public static void show(final Creature actor, final int tileX, final int tileY,
                            final int layer, final Api api) throws Exception {
        final View view = api.snapshot(actor, tileX, tileY, layer);
        ModQuestions.createQuestion(actor, view.existing() ? "Railway track" : "Build railway track", "", view.markerId, new ModQuestion() {
            private final AtomicBoolean answered = new AtomicBoolean();

            @Override public void sendQuestion(Question question) {
                String bml = ModQuestions.getBmlHeaderNoQuestion(question)
                        + content(view) + "}};null;null;}";
                sendBml(question, 680, view.existing() ? 710 : 440, true, true, bml);
            }

            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() != actor || !answered.compareAndSet(false, true)) return;
                try {
                    Request request = fromAnswers(view, answers);
                    if (request != null) api.submit(actor, tileX, tileY, layer, request);
                } catch (IllegalArgumentException badInput) {
                    actor.getCommunicator().sendNormalServerMessage(badInput.getMessage());
                    api.submit(actor, tileX, tileY, layer,
                            new Request(Action.REFRESH, view, 0, 0, 0, "", 5, false));
                }
            }
        }).sendQuestion();
    }

    /** Exposed for native BML and answer-decoding regression checks. */
    public static String content(View view) {
        StringBuilder b = new StringBuilder();
        label(b, view.location, true);
        if (!view.existing()) {
            label(b, "Build a railway line", true);
            label(b, "Length includes this tile. Choose a compass direction and the number of tiles.", false);
            label(b, "Neighboring tracks connect automatically. Trains attach when you use them.", false);
            label(b, "Lines continue through open cave entrances into tunnels.", false);
            label(b, "At an entrance, connect north, east, south or west.", false);
            label(b, "Ground: maximum along-track slope 20, side-to-side slope 0. Bends and junctions need flat ground.", false);
            label(b, "Completed solid bridges use the deck slope. Connect through a ground-level bridge exit.", false);
            label(b, view.isGM ? "GM building: no track sections consumed."
                    : "Uses one crafted railway track section per tile. In your inventory: " + view.availableSections + ".", false);
            b.append("harray{label{text=\"Direction:\"};dropdown{id=\"direction\";default=\"")
                    .append(view.defaultDirection).append("\";options=\"")
                    .append(DIRECTION_OPTIONS).append("\"};};");
            b.append("harray{label{text=\"Length in tiles (1-100):\"};input{id=\"count\";maxchars=\"3\";text=\"1\"};};");
            b.append("harray{");
            button(b, "place", "Build track");
            button(b, "refresh", "Refresh");
            b.append("};");
            return b.toString();
        }

        List<Neighbor> connected = connected(view);
        if (connected.size() >= 3) {
            int mask = view.alignmentMask;
            int connectedMask = 0;
            for (Neighbor neighbor : connected) connectedMask |= 1 << neighbor.direction;
            label(b, Integer.bitCount(mask) == 2 && mask > 0 && (mask & ~connectedMask) == 0
                    ? "Active alignment: " + alignmentLabel(mask) : "Active alignment: not aligned - trains must stop", true);
            label(b, view.alignmentOwner.isEmpty() ? "Junction available for switching."
                    : "Locked for train: " + view.alignmentOwner + ". It unlocks after every wagon clears.", false);
        } else label(b, view.markerPresent ? "Track ready" : "Track model missing - repair it before running trains", true);

        label(b, "Track connections", true);
        label(b, "Choose the neighboring tracks this tile joins, then apply. Bends follow these connections.", false);
        label(b, "New bends support 45 or 90 degrees. Straight track is also supported.", false);
        label(b, "Slope: up to 20 along straight rails; 0 across rails. Bends and junctions must be flat.", false);
        if (view.neighbors.isEmpty()) {
            label(b, "There are no neighboring tracks yet. Build on an adjacent tile to extend this railway.", false);
        } else {
            for (Neighbor neighbor : view.neighbors) {
                b.append("checkbox{id=\"exit_").append(neighbor.direction)
                        .append("\";text=\"").append(safe(COMPASS[neighbor.direction] + " - " + neighbor.label))
                        .append("\";selected=\"").append(neighbor.connected).append("\"};");
            }
            button(b, "connections", "Apply connections");
        }

        if (connected.size() >= 3) {
            List<Integer> alignments = alignments(connected);
            int selected = alignments.indexOf(view.alignmentMask);
            label(b, "Set junction alignment", true);
            b.append("harray{dropdown{id=\"alignment_choice\";default=\"")
                    .append(Math.max(0, selected)).append("\";options=\"Not aligned (stop all approaches)");
            for (int index = 1; index < alignments.size(); index++)
                b.append(',').append(safe(alignmentLabel(alignments.get(index))));
            b.append("\"};");
            button(b, "alignment", "Set alignment");
            b.append("};");
            label(b, "Each pair works in both directions. Other approaches stop.", false);
            label(b, "135-degree turns are excluded from alignment choices.", false);
            label(b, "Automatic routes set and lock the required pair. Not aligned stops all traffic.", false);
            label(b, "Quick change: right-click this track > Cycle junction alignment.", false);
        } else if (connected.size() == 2) {
            label(b, "Rail path: " + alignmentLabel((1 << connected.get(0).direction) | (1 << connected.get(1).direction)), false);
        }

        label(b, "Named train stop (optional)", true);
        label(b, "Name this track tile to add a stop to the train route list.", false);
        label(b, "Station tiles already appear in that list; they need no extra named stop.", false);
        b.append("harray{label{text=\"Stop name:\"};input{id=\"station_name\";maxchars=\"48\";text=\"")
                .append(safe(view.stationName)).append("\"};};");
        b.append("harray{label{text=\"Stop for seconds (1-3600):\"};input{id=\"dwell\";maxchars=\"6\";text=\"")
                .append(dwellText(view.dwellSeconds)).append("\"};};");
        b.append("harray{");
        button(b, "station", view.station ? "Save named stop" : "Create named stop");
        if (view.station) button(b, "station_off", "Remove stop name");
        b.append("};");
        label(b, "For automatic boarding and unloading, use Build station on a ground tile beside the track.", false);

        label(b, "Track maintenance", true);
        if (!view.markerPresent) {
            label(b, "This track's model is missing. Restore it before running trains here.", false);
            button(b, "repair", "Repair track model");
        }
        if (view.isGM) {
            label(b, "GM vehicles", true);
            b.append("harray{");
            button(b, "spawn_engine", "Place locomotive here");
            button(b, "spawn_cart", "Place wagon here");
            button(b, "spawn_logcar", "Place Log Car here");
            b.append("};");
        }
        label(b, "Removing track recovers one section. A track in use by a train cannot be removed.", false);
        b.append("checkbox{id=\"confirm_remove\";selected=\"false\";text=\"I want to remove this track tile\"};");
        b.append("harray{");
        button(b, "remove", "Remove track and recover section");
        button(b, "refresh", "Refresh");
        b.append("};");
        return b.toString();
    }

    /** Decode only the button that was actually pressed; unrelated fields cannot change settings. */
    public static Request fromAnswers(View view, Properties answers) {
        String pressed = null;
        for (String button : BUTTONS) if ("true".equals(answers.getProperty(button))) {
            if (pressed != null) throw new IllegalArgumentException("Choose one railway action at a time.");
            pressed = button;
        }
        if (pressed == null) return null; // Closing the window makes no changes.
        if (pressed.equals("refresh")) return new Request(Action.REFRESH, view, 0, 0, 0, "", 5, false);
        if (!view.existing()) {
            if (!pressed.equals("place")) throw new IllegalArgumentException("Refresh the builder before editing this track.");
            int direction = boundedInteger(answers.getProperty("direction"), 0, 7, "Choose a compass direction.");
            int count = boundedInteger(answers.getProperty("count"), 1, 100, "Track length must be a whole number from 1 to 100.");
            return new Request(Action.PLACE, view, direction, count, 0, "", 5, false);
        }
        if (pressed.equals("place")) throw new IllegalArgumentException("Track already exists here. Build on an adjacent tile.");
        if (pressed.equals("spawn_engine") || pressed.equals("spawn_cart") || pressed.equals("spawn_logcar")) {
            if (!view.isGM) throw new IllegalArgumentException("Only a GM can create railway vehicles this way. Players can craft them.");
            return new Request(pressed.equals("spawn_engine") ? Action.SPAWN_ENGINE : pressed.equals("spawn_logcar") ? Action.SPAWN_LOGCAR : Action.SPAWN_CART,
                    view, 0, 0, 0, "", 5, false);
        }
        if (pressed.equals("connections")) {
            int mask = 0;
            for (Neighbor neighbor : view.neighbors)
                if ("true".equals(answers.getProperty("exit_" + neighbor.direction))) mask |= 1 << neighbor.direction;
            for (int direction = 0; direction < 8; direction++)
                if ("true".equals(answers.getProperty("exit_" + direction)) && !hasNeighbor(view, direction))
                    throw new IllegalArgumentException("Build a neighboring track in that direction first.");
            int previousMask=0;
            for(Neighbor neighbor:view.neighbors)if(neighbor.connected)previousMask|=1<<neighbor.direction;
            if(Integer.bitCount(mask)==2&&!RailNetwork.isBuildableAlignment(mask)&&mask!=previousMask)
                throw new IllegalArgumentException("135-degree corners are not supported. Choose a 45-degree bend, 90-degree bend or straight track.");
            return new Request(Action.CONNECTIONS, view, 0, 0, mask, "", 5, false);
        }
        if (pressed.equals("alignment")) {
            List<Neighbor> connected = connected(view);
            if (connected.size() < 3) throw new IllegalArgumentException("Only junctions have a selectable alignment.");
            List<Integer> alignments = alignments(connected);
            int selection = boundedInteger(answers.getProperty("alignment_choice"), 0, alignments.size() - 1,
                    "Choose one of this junction's connected pairs.");
            return new Request(Action.ALIGNMENT, view, 0, 0, 0, "", 5, false, alignments.get(selection));
        }
        if (pressed.equals("station") || pressed.equals("station_off")) {
            boolean enabled = pressed.equals("station");
            String name = enabled ? answers.getProperty("station_name", "").trim() : "";
            if (enabled && (name.isEmpty() || name.length() > 48 || hasControl(name)))
                throw new IllegalArgumentException("Enter a station name of 1 to 48 characters.");
            double dwell = enabled ? boundedNumber(answers.getProperty("dwell"), 1, 3600,
                    "Station stop time must be from 1 to 3600 seconds.") : 5;
            return new Request(Action.STATION, view, 0, 0, 0, name, dwell, enabled);
        }
        if (pressed.equals("remove")) {
            if (!"true".equals(answers.getProperty("confirm_remove")))
                throw new IllegalArgumentException("Tick the removal confirmation first.");
            return new Request(Action.REMOVE, view, 0, 0, 0, "", 5, false);
        }
        if (pressed.equals("repair")) {
            if (view.markerPresent) throw new IllegalArgumentException("The track model is already present.");
            return new Request(Action.REPAIR, view, 0, 0, 0, "", 5, false);
        }
        throw new IllegalArgumentException("Unknown railway action.");
    }

    /** Keep BML control delimiters out of every displayed value. */
    public static String safe(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) result.append(' ');
            else if (c == '"') result.append('\u201d');
            else if (c == '\'') result.append('\u2019');
            else if (c == '\\') result.append('/');
            else if (c == '{') result.append('(');
            else if (c == '}') result.append(')');
            else result.append(c);
        }
        return result.toString();
    }

    private static List<Neighbor> connected(View view) {
        List<Neighbor> result = new ArrayList<Neighbor>();
        for (Neighbor neighbor : view.neighbors) if (neighbor.connected) result.add(neighbor);
        return result;
    }

    private static List<Integer> alignments(List<Neighbor> connected) {
        List<Integer> result = new ArrayList<Integer>();
        result.add(0);
        for (int first = 0; first < connected.size(); first++)
            for (int second = first + 1; second < connected.size(); second++) {
                int mask=(1 << connected.get(first).direction) | (1 << connected.get(second).direction);
                if(RailNetwork.isBuildableAlignment(mask))result.add(mask);
            }
        return result;
    }

    private static String alignmentLabel(int mask) {
        int first = Integer.numberOfTrailingZeros(mask);
        int second = Integer.numberOfTrailingZeros(mask & ~(1 << first));
        return COMPASS[first] + " \u2194 " + COMPASS[second] + " (" + DIRECTION_NAMES[first] + " - " + DIRECTION_NAMES[second] + ")";
    }

    private static boolean hasNeighbor(View view, int direction) {
        for (Neighbor neighbor : view.neighbors) if (neighbor.direction == direction) return true;
        return false;
    }

    private static int boundedInteger(String text, int low, int high, String message) {
        try {
            int result = Integer.parseInt(text == null ? "" : text.trim());
            if (result >= low && result <= high) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(message);
    }

    private static double boundedNumber(String text, double low, double high, String message) {
        try {
            double result = Double.parseDouble(text == null ? "" : text.trim());
            if (Double.isFinite(result) && result >= low && result <= high) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(message);
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return true;
        return false;
    }

    private static String dwellText(double value) {
        if (!Double.isFinite(value) || value < 1 || value > 3600) return "5";
        return value == Math.rint(value) ? Long.toString((long)value) : Double.toString(value);
    }

    private static void label(StringBuilder b, String text, boolean bold) {
        b.append("label{");
        if (bold) b.append("type=\"bold\";");
        b.append("text=\"").append(safe(text)).append("\"};");
    }

    private static void button(StringBuilder b, String id, String text) {
        b.append("button{id=\"").append(id).append("\";text=\"").append(safe(text)).append("\"};");
    }
}
