package org.wurmrail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.wurmrail.RailwaySignalUi.Action;
import org.wurmrail.RailwaySignalUi.Request;
import org.wurmrail.RailwaySignalUi.Signal;
import org.wurmrail.RailwaySignalUi.View;

/** Captured topology and identities survive hostile, stale and forged signal form answers. */
public final class RailwaySignalUiSelfTest {
    private static int assertions;
    private RailwaySignalUiSelfTest() { }

    public static void main(String[] args) {
        run();
        System.out.println("Signal UI input checks passed: " + assertions + " assertions.");
    }

    public static void run() {
        assertions = 0;
        List<Integer> sourceSides = new ArrayList<Integer>(Arrays.asList(4, 0, 2, 7));
        List<Signal> sourceSignals = new ArrayList<Signal>(Arrays.asList(
                new Signal("hidden_S", 4, "GREEN", "Harbor express", "Route reserved", "Harbor to mine"),
                new Signal("hidden_N", 0, "RED", "", "Waiting for a train", "")));
        View managed = view(sourceSides, sourceSignals, true);
        sourceSides.clear(); sourceSignals.clear();
        check(managed.approachSides.equals(Arrays.asList(0, 2, 4, 7)) && managed.signals.get(0).id.equals("hidden_N"),
                "captured topology and configured signals have stable absolute compass order");
        check(managed.signals.size() == 2, "caller mutations cannot change the opened signal list");
        rejected(() -> managed.approachSides.clear(), "captured approach sides are immutable");
        rejected(() -> managed.signals.clear(), "captured configured signals are immutable");

        Request east = RailwaySignalUi.fromAnswers(managed, answer("place", "approach_choice", "0",
                "approach", "6", "nodeKey", "forged", "markerId", "999", "revision", "777", "signalId", "forged"));
        check(east.action == Action.PLACE && east.approach == 2 && east.signalId.isEmpty(),
                "placement resolves only an unused connected absolute side, ignoring forged identity fields");
        check(east.expectedNodeKey.equals("hidden_rail") && east.expectedMarkerId == 301L && east.revision == 12L,
                "placement preserves the captured track identity and signal definition revision");
        Request northwest = RailwaySignalUi.fromAnswers(managed, answer("place", "approach_choice", "1"));
        check(northwest.approach == 7, "sparse dropdown index maps to Northwest rather than a hard-coded cardinal index");
        for (String index : Arrays.asList("-1", "2", "7", "2.0", "North", "999999999999999999999999"))
            rejected(() -> RailwaySignalUi.fromAnswers(managed, answer("place", "approach_choice", index)),
                    "forged approach index rejected");
        rejected(() -> RailwaySignalUi.fromAnswers(managed, answer("place")), "placement requires an explicit captured choice");

        Request removeSouth = RailwaySignalUi.fromAnswers(managed, answer("remove", "signal_choice", "1", "confirm_remove", "true",
                "signalId", "hidden_N", "approach", "0"));
        check(removeSouth.action == Action.REMOVE && removeSouth.signalId.equals("hidden_S") && removeSouth.approach == 4,
                "removal captures the selected signal's actual identity and approach");
        check(removeSouth.expectedMarkerId == 301L && removeSouth.revision == 12L,
                "removal preserves exact parent identity and definition revision for runtime stale checks");
        rejected(() -> RailwaySignalUi.fromAnswers(managed, answer("remove", "signal_choice", "0")),
                "removal needs an explicit confirmation");
        for (String index : Arrays.asList("-1", "2", "hidden_N", "1.5", "999999999999999999999999"))
            rejected(() -> RailwaySignalUi.fromAnswers(managed, answer("remove", "signal_choice", index, "confirm_remove", "true")),
                    "forged configured signal index rejected");
        check(RailwaySignalUi.fromAnswers(managed, new Properties()) == null
                        && RailwaySignalUi.fromAnswers(managed, answer("close")) == null,
                "closing a signal window queues no mutation");
        Request refresh = RailwaySignalUi.fromAnswers(managed, answer("refresh", "signal_choice", "bad", "approach_choice", "bad"));
        check(refresh.action == Action.REFRESH && refresh.approach == -1 && refresh.signalId.isEmpty(),
                "refresh ignores stale input controls and grants no signal authority");
        for (String first : Arrays.asList("place", "remove", "refresh", "close"))
            for (String second : Arrays.asList("place", "remove", "refresh", "close")) if (!first.equals(second)) {
                Properties multiple = answer(first); multiple.setProperty(second, "true");
                rejected(() -> RailwaySignalUi.fromAnswers(managed, multiple), "multiple simultaneous signal actions rejected");
            }
        View visitor = view(managed.approachSides, managed.signals, false);
        rejected(() -> RailwaySignalUi.fromAnswers(visitor, answer("place", "approach_choice", "0")),
                "visitor cannot forge construction");
        rejected(() -> RailwaySignalUi.fromAnswers(visitor, answer("remove", "signal_choice", "0", "confirm_remove", "true")),
                "visitor cannot forge signal removal");
        check(RailwaySignalUi.fromAnswers(visitor, answer("refresh")).action == Action.REFRESH,
                "visitors can inspect live signal status without build permissions");
        String visitorText = RailwaySignalUi.content(visitor);
        check(!visitorText.contains("id='place'") && !visitorText.contains("id='remove'")
                        && !visitorText.contains("id='approach_choice'") && !visitorText.contains("id='signal_choice'"),
                "read-only signal status contains no management controls");
        check(visitorText.contains("South approach - GREEN") && visitorText.contains("Harbor express")
                        && visitorText.contains("Harbor to mine") && visitorText.contains("Waiting for a train"),
                "signal inspection includes absolute approach, aspect, owner, route and red reason");
        check(visitorText.contains("Approach means the side the train arrives FROM")
                        && visitorText.contains("Player facing does not affect signals")
                        && visitorText.contains("travelling east arrives from West"),
                "direction text describes absolute entry side rather than player orientation");
        check(visitorText.indexOf("North approach - RED") < visitorText.indexOf("Automatic operation")
                        && visitorText.contains("South approach - GREEN | Train: Harbor express")
                        && visitorText.contains("North approach - RED | No reservation"),
                "live red/green aspect and owner appear before setup help");
        check(visitorText.contains("Save a train route and power on") && visitorText.contains("no route requested"),
                "signal help states the operating workflow and explains an idle red aspect");
        String managerText = RailwaySignalUi.content(managed);
        check(managerText.contains("default='1';options='East approach,Northwest approach'")
                        && !managerText.contains("hidden_rail") && !managerText.contains("hidden_N") && !managerText.contains("hidden_S"),
                "creation defaults to an available side and hides all internal track/signal identifiers");
        check(managerText.contains("one crafted rail track section") && managerText.contains("Removing a signal returns"),
                "ordinary players are told the construction cost and removal refund");

        List<Integer> all = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
        List<Signal> full = new ArrayList<Signal>();
        for (int side = 0; side < 8; side++) {
            View oneSide = view(Collections.singletonList(side), Collections.<Signal>emptyList(), true);
            check(RailwaySignalUi.fromAnswers(oneSide, answer("place", "approach_choice", "0")).approach == side,
                    "all eight absolute directions survive typed signal placement");
            full.add(new Signal("signal_" + side, side, "RED", "", "", ""));
        }
        View occupied = view(all, full, true);
        rejected(() -> RailwaySignalUi.fromAnswers(occupied, answer("place", "approach_choice", "0")),
                "a rail cannot place a second signal for an already configured approach");
        check(!RailwaySignalUi.content(occupied).contains("id='place'"), "a rail with all approaches signalled has no placement button");
        View isolated = view(Collections.<Integer>emptyList(), Collections.<Signal>emptyList(), true);
        check(RailwaySignalUi.content(isolated).contains("Connect this track to a neighbor")
                        && !RailwaySignalUi.content(isolated).contains("Every connected approach already"),
                "an isolated rail gives a useful connection step rather than claiming all approaches already have signals");
        rejected(() -> RailwaySignalUi.fromAnswers(isolated, answer("place", "approach_choice", "0")),
                "an isolated rail cannot forge a signal approach");
        rejected(() -> RailwaySignalUi.fromAnswers(isolated, answer("remove", "signal_choice", "0", "confirm_remove", "true")),
                "an empty signal list cannot remove arbitrary definitions");
        View staleConnection = view(Collections.singletonList(0), Collections.singletonList(new Signal("orphan", 7, "RED", "", "Connection missing", "")), true);
        check(RailwaySignalUi.fromAnswers(staleConnection, answer("remove", "signal_choice", "0", "confirm_remove", "true")).signalId.equals("orphan"),
                "a configured signal whose approach connection disappeared remains removable");

        rejected(() -> view(Arrays.asList(0, 0), Collections.<Signal>emptyList(), true), "duplicate available sides rejected");
        rejected(() -> view(Arrays.asList(0, 8), Collections.<Signal>emptyList(), true), "out-of-range approach rejected");
        rejected(() -> view(Arrays.asList(0, null), Collections.<Signal>emptyList(), true), "missing available approach rejected");
        rejected(() -> view(all, Arrays.asList(new Signal("same", 0, "RED", "", "", ""), new Signal("same", 4, "RED", "", "", "")), true),
                "duplicate configured identities rejected");
        rejected(() -> view(all, Arrays.asList(new Signal("one", 0, "RED", "", "", ""), new Signal("two", 0, "RED", "", "", "")), true),
                "duplicate configured approach sides rejected");
        rejected(() -> new Signal("", 0, "RED", "", "", ""), "missing signal identity rejected");
        rejected(() -> new Signal("bad", -1, "RED", "", "", ""), "invalid configured approach rejected");
        rejected(() -> new View("", 301L, 0L, "Track", all, full, true, 1, false, 0), "missing parent node identity rejected");
        rejected(() -> new View("track", -10L, 0L, "Track", all, full, true, 1, false, 0), "missing parent marker rejected");
        rejected(() -> new View("track", 301L, -1L, "Track", all, full, true, 1, false, 0), "invalid definition revision rejected");
        rejected(() -> RailwaySignalUi.fromAnswers(managed, null), "missing form answers rejected");
        Signal failSafe = new Signal("failsafe", 0, null, null, null, null);
        check(failSafe.aspect.equals("RED") && failSafe.owner.isEmpty(), "missing presentation status defaults to red with no owner");
        String hostile = "Depot, East;'};button{id='injected'};{\\\"\n";
        View escaped = new View("hidden_track", 301L, 0L, hostile, all,
                Collections.singletonList(new Signal("hidden_signal", 0, hostile, hostile, hostile, hostile)), true, -1, false, 0);
        String safe = RailwaySignalUi.content(escaped);
        check(!safe.contains("id='injected'") && !safe.contains("hidden_signal") && !safe.contains("hidden_track"),
                "all signal display strings are escaped without exposing internal identities");
        check(escaped.availableSections == 0, "missing inventory count cannot advertise negative track sections");
    }

    private static View view(List<Integer> sides, List<Signal> signals, boolean manage) {
        return new View("hidden_rail", 301L, 12L, "Harbor railway", sides, signals, manage, 2, false, 7);
    }
    private static Properties answer(String button, String... pairs) {
        Properties answer = new Properties(); answer.setProperty(button, "true");
        for (int i = 0; i < pairs.length; i += 2) answer.setProperty(pairs[i], pairs[i + 1]);
        return answer;
    }
    private interface Checked { void run(); }
    private static void rejected(Checked action, String reason) {
        try { action.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }
    private static void check(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
}
