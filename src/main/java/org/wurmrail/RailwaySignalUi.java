package org.wurmrail;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.questions.Question;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestion;
import org.gotti.wurmunlimited.modsupport.questions.ModQuestions;

/** Signal configuration and diagnostics. The runtime owns topology, authority and all mutations. */
public final class RailwaySignalUi {
    private static final String[] DIRECTIONS = {
            "North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest"
    };
    private static final String[] BUTTONS = {"place", "remove", "refresh", "close"};

    private RailwaySignalUi() { }

    public enum Action { PLACE, REMOVE, REFRESH }

    public interface Api {
        View snapshot(Creature actor, long clickedItemId) throws Exception;
        /** Queue, then recheck clicked identity, captured node/marker/revision, reach, permissions and cost. */
        void submit(Creature actor, long clickedItemId, Request request);
    }

    /** Presentation data only: no live core object, reservation or authority is exposed to the form. */
    public static final class Signal {
        public final String id, aspect, owner, reason, route;
        public final int approach;
        public Signal(String id, int approach, String aspect, String owner, String reason, String route) {
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("A configured signal identity is required.");
            direction(approach);
            this.id = id; this.approach = approach;
            this.aspect = aspect == null || aspect.isEmpty() ? "RED" : aspect;
            this.owner = owner == null ? "" : owner;
            this.reason = reason == null ? "" : reason;
            this.route = route == null ? "" : route;
        }
    }

    public static final class View {
        public final String nodeKey, trackLabel;
        public final long markerId;
        /** Definition changes only; live aspects must not invalidate an otherwise current form. */
        public final long revision;
        public final List<Integer> approachSides;
        public final List<Signal> signals;
        public final boolean canManage, isGM;
        public final int availableSections, defaultApproach;

        public View(String nodeKey, long markerId, long revision, String trackLabel,
                    List<Integer> approachSides, List<Signal> signals, boolean canManage,
                    int availableSections, boolean isGM, int defaultApproach) {
            if (nodeKey == null || nodeKey.isEmpty() || markerId <= 0 || revision < 0)
                throw new IllegalArgumentException("A current railway track identity is required.");
            direction(defaultApproach);
            this.nodeKey = nodeKey; this.markerId = markerId; this.revision = revision;
            this.trackLabel = trackLabel == null ? "Railway track" : trackLabel;
            this.approachSides = sides(approachSides); this.signals = signals(signals);
            this.canManage = canManage; this.availableSections = Math.max(0, availableSections);
            this.isGM = isGM; this.defaultApproach = defaultApproach;
        }
    }

    public static final class Request {
        public final Action action;
        public final String expectedNodeKey, signalId;
        public final long expectedMarkerId, revision;
        /** Absolute side from which the train enters; never the player's facing direction. */
        public final int approach;
        private Request(View view, Action action, int approach, String signalId) {
            this.action = action; this.expectedNodeKey = view.nodeKey;
            this.expectedMarkerId = view.markerId; this.revision = view.revision;
            this.approach = approach; this.signalId = signalId;
        }
        public static Request refresh(View view) { return new Request(view, Action.REFRESH, -1, ""); }
    }

    public static void show(final Creature actor, final long clickedItemId, final Api api) throws Exception {
        if (actor == null || api == null || clickedItemId <= 0)
            throw new IllegalArgumentException("Choose a nearby railway track or signal.");
        final View view = api.snapshot(actor, clickedItemId);
        final AtomicBoolean answered = new AtomicBoolean();
        ModQuestions.createQuestion(actor, "Railway signals", "", clickedItemId, new ModQuestion() {
            @Override public void sendQuestion(Question question) {
                sendBml(question, 700, 660, true, true,
                        ModQuestions.getBmlHeaderNoQuestion(question) + content(view) + "}};null;null;}");
            }
            @Override public void answer(Question question, Properties answers) {
                if (question.getResponder() != actor || !answered.compareAndSet(false, true)) return;
                try {
                    Request request = fromAnswers(view, answers);
                    if (request != null) api.submit(actor, clickedItemId, request);
                } catch (IllegalArgumentException invalid) {
                    actor.getCommunicator().sendNormalServerMessage("[Rail] " + invalid.getMessage());
                    api.submit(actor, clickedItemId, Request.refresh(view));
                }
            }
        }).sendQuestion();
    }

    public static String content(View view) {
        StringBuilder b = new StringBuilder();
        heading(b, view.trackLabel);
        heading(b, "Signal status");
        if (view.signals.isEmpty()) label(b, view.canManage && !view.approachSides.isEmpty()
                ? "No signals on this track. Choose a connected approach below to build one."
                : "No signals on this track.");
        for (Signal signal : view.signals) {
            heading(b, DIRECTIONS[signal.approach] + " approach - " + signal.aspect
                    + (signal.owner.isEmpty() ? " | No reservation" : " | Train: " + signal.owner));
            label(b, "Status: " + (signal.reason.isEmpty() ? "No route requested." : signal.reason));
            if (!signal.route.isEmpty()) label(b, "Protected route: " + signal.route);
        }
        label(b, "Refresh status to see current lights, reservations and stop reasons.");

        heading(b, "Automatic operation");
        label(b, "Save a train route and power on. The train requests its route through each signal.");
        label(b, "RED = stop or no route requested. GREEN = reserved for the named train only.");
        label(b, "Approach means the side the train arrives FROM. Opposite travel needs its own signal.");
        label(b, "On a junction: place one signal for each connected approach side.");
        label(b, "A returning train can align its next route after every car clears the points.");
        label(b, "Two-way service needs both directions at each block boundary, including beyond a junction.");
        label(b, "Automatic dispatch queues shared connections and waits for room for the whole train beyond them.");
        label(b, "Opposing trains need passing track. A parked train can keep its track occupied.");
        label(b, "Waiting trains retry automatically. Train controls shows the next stop and traffic status.");
        label(b, "Example: a train travelling east arrives from West. Player facing does not affect signals.");
        if (view.canManage) {
            List<Integer> free = freeSides(view);
            heading(b, "Build a signal");
            label(b, view.isGM ? "GM construction: no track section consumed."
                    : "Placing a signal uses one crafted rail track section. In your inventory: " + view.availableSections + ".");
            if (free.isEmpty()) label(b, view.approachSides.isEmpty()
                    ? "Connect this track to a neighbor before placing a signal."
                    : "Every connected approach already has a signal.");
            else {
                b.append("harray{label{text='Trains arrive from:'};dropdown{id='approach_choice';default='")
                        .append(defaultIndex(view, free)).append("';options='");
                for (int i = 0; i < free.size(); i++) {
                    if (i > 0) b.append(',');
                    b.append(DIRECTIONS[free.get(i)]).append(" approach");
                }
                b.append("'};"); button(b, "place", "Place signal"); b.append("};");
            }
            if (!view.signals.isEmpty()) {
                heading(b, "Remove a signal");
                label(b, "Removing a signal returns one crafted rail track section.");
                b.append("harray{label{text='Signal:'};dropdown{id='signal_choice';default='0';options='");
                for (int i = 0; i < view.signals.size(); i++) {
                    if (i > 0) b.append(',');
                    Signal signal = view.signals.get(i);
                    b.append(DIRECTIONS[signal.approach]).append(" approach - ").append(RailwayBml.option(signal.aspect));
                }
                b.append("'};};checkbox{id='confirm_remove';selected='false';text='Confirm removal of the selected signal'};");
                button(b, "remove", "Remove signal");
            }
        }
        b.append("harray{"); button(b, "refresh", "Refresh status"); button(b, "close", "Close"); b.append("};");
        return b.toString();
    }

    public static Request fromAnswers(View view, Properties answers) {
        String button = oneButton(answers);
        if (button == null || button.equals("close")) return null;
        if (button.equals("refresh")) return Request.refresh(view);
        if (!view.canManage) throw new IllegalArgumentException("You do not have permission to configure railway signals.");
        if (button.equals("place")) {
            List<Integer> free = freeSides(view);
            int selected = index(answers.getProperty("approach_choice"), free.size(), "Choose an unused connected approach side.");
            return new Request(view, Action.PLACE, free.get(selected), "");
        }
        if (button.equals("remove")) {
            if (!"true".equals(answers.getProperty("confirm_remove")))
                throw new IllegalArgumentException("Tick the signal removal confirmation first.");
            int selected = index(answers.getProperty("signal_choice"), view.signals.size(), "Choose a configured signal.");
            Signal signal = view.signals.get(selected);
            return new Request(view, Action.REMOVE, signal.approach, signal.id);
        }
        throw new IllegalArgumentException("Choose an available signal action.");
    }

    private static List<Integer> freeSides(View view) {
        Set<Integer> configured = new HashSet<Integer>();
        for (Signal signal : view.signals) configured.add(signal.approach);
        List<Integer> free = new ArrayList<Integer>();
        for (Integer side : view.approachSides) if (!configured.contains(side)) free.add(side);
        return free;
    }

    private static int defaultIndex(View view, List<Integer> free) {
        int index = free.indexOf(view.defaultApproach);
        return index < 0 ? 0 : index;
    }

    private static List<Integer> sides(List<Integer> source) {
        if (source == null || source.size() > 8) throw new IllegalArgumentException("Invalid signal approach sides.");
        List<Integer> copy = new ArrayList<Integer>(source); Set<Integer> seen = new HashSet<Integer>();
        for (Integer side : copy) {
            if (side == null) throw new IllegalArgumentException("A signal approach side is missing.");
            direction(side);
            if (!seen.add(side)) throw new IllegalArgumentException("Repeated signal approach side.");
        }
        Collections.sort(copy);
        return Collections.unmodifiableList(copy);
    }

    private static List<Signal> signals(List<Signal> source) {
        if (source == null || source.size() > 8) throw new IllegalArgumentException("Invalid configured signal list.");
        List<Signal> copy = new ArrayList<Signal>(source);
        Set<String> ids = new HashSet<String>(); Set<Integer> sides = new HashSet<Integer>();
        for (Signal signal : copy) if (signal == null || !ids.add(signal.id) || !sides.add(signal.approach))
            throw new IllegalArgumentException("Repeated configured signal identity or approach side.");
        Collections.sort(copy, new Comparator<Signal>() {
            @Override public int compare(Signal a, Signal b) { return Integer.compare(a.approach, b.approach); }
        });
        return Collections.unmodifiableList(copy);
    }

    private static void direction(int side) {
        if (side < 0 || side > 7) throw new IllegalArgumentException("Choose an absolute compass approach side.");
    }

    private static String oneButton(Properties answers) {
        if (answers == null) throw new IllegalArgumentException("Signal answers are missing.");
        String result = null;
        for (String button : BUTTONS) if ("true".equals(answers.getProperty(button))) {
            if (result != null) throw new IllegalArgumentException("Choose one signal action at a time.");
            result = button;
        }
        return result;
    }

    private static int index(String text, int size, String error) {
        try {
            int value = Integer.parseInt(text == null ? "" : text);
            if (value >= 0 && value < size) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(error);
    }

    private static void label(StringBuilder b, String text) { b.append("label{text='").append(RailwayBml.text(text)).append("'};"); }
    private static void heading(StringBuilder b, String text) { b.append("label{type='bold';text='").append(RailwayBml.text(text)).append("'};"); }
    private static void button(StringBuilder b, String id, String text) { b.append("button{id='").append(id).append("';text='").append(RailwayBml.text(text)).append("'};"); }
}
