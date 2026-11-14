package org.wurmrail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.wurmrail.RailwayPassengerUi.Action;
import org.wurmrail.RailwayPassengerUi.BuildAction;
import org.wurmrail.RailwayPassengerUi.BuildRequest;
import org.wurmrail.RailwayPassengerUi.BuildView;
import org.wurmrail.RailwayPassengerUi.Choice;
import org.wurmrail.RailwayPassengerUi.Mode;
import org.wurmrail.RailwayPassengerUi.RailChoice;
import org.wurmrail.RailwayPassengerUi.Request;
import org.wurmrail.RailwayPassengerUi.View;

/** Captured passenger identities and hostile form answers; no native world is mutated. */
public final class RailwayPassengerUiSelfTest {
    private static int assertions;
    private RailwayPassengerUiSelfTest() { }

    public static void main(String[] args) {
        run();
        System.out.println("Passenger stop UI input checks passed: " + assertions + " assertions.");
    }

    public static void run() {
        assertions = 0;
        List<Choice> source = new ArrayList<Choice>(Arrays.asList(new Choice(101L, "Harbor"), new Choice(202L, "Mine")));
        View pickup = view(71L, Mode.PICKUP, true, source);
        check(pickup.mode == Mode.STATION, "legacy pickup view is normalized to a combined station");
        source.clear();
        Request destination = RailwayPassengerUi.fromAnswers(pickup, answer("destination", "destination_choice", "1", "destinationId", "999999"));
        check(destination.action == Action.CHOOSE_DESTINATION && destination.destinationId == 202L,
                "dropdown selection resolves only a destination captured in the window");
        check(destination.expectedPlatformItemId == 71L && destination.revision == 3L && destination.trainEngineId == 99L,
                "queued journey request carries captured platform revision and train identity");
        check(pickup.destinations.size() == 2, "source choice mutations cannot change the opened window");
        rejected(() -> pickup.destinations.clear(), "captured destination choices are immutable");
        for (String index : Arrays.asList("-1", "2", "202", "0.5", "garbage", "999999999999999999999999"))
            rejected(() -> RailwayPassengerUi.fromAnswers(pickup, answer("destination", "destination_choice", index)), "forged destination index rejected");
        check(RailwayPassengerUi.fromAnswers(pickup, new Properties()) == null
                        && RailwayPassengerUi.fromAnswers(pickup, answer("close")) == null,
                "closing a window queues no journey mutation");
        Request cancel = RailwayPassengerUi.fromAnswers(pickup, answer("cancel_destination", "destination_choice", "garbage", "stop_name", "bad\nname"));
        check(cancel.action == Action.CANCEL_DESTINATION && cancel.destinationId == -10L,
                "clearing a destination ignores unrelated malformed form fields");
        check(RailwayPassengerUi.fromAnswers(pickup, answer("refresh", "destination_choice", "garbage")).action == Action.REFRESH,
                "refresh ignores unrelated form fields");
        Properties multiple = answer("destination", "destination_choice", "0"); multiple.setProperty("remove", "true");
        rejected(() -> RailwayPassengerUi.fromAnswers(pickup, multiple), "forged multiple pressed buttons rejected");
        Request rename = RailwayPassengerUi.fromAnswers(pickup, answer("rename", "stop_name", " Harbor pickup "));
        check(rename.action == Action.RENAME && rename.name.equals("Harbor pickup") && rename.expectedPlatformItemId == 71L,
                "rename uses printable friendly text and captured stop identity");
        for (String name : Arrays.asList("", "  ", "Hidden\nname", "Hidden\rname", "Hidden\tname", longName()))
            rejected(() -> RailwayPassengerUi.fromAnswers(pickup, answer("rename", "stop_name", name)), "invalid stop name rejected");
        rejected(() -> RailwayPassengerUi.fromAnswers(pickup, answer("remove")), "removal requires explicit confirmation");
        check(RailwayPassengerUi.fromAnswers(pickup, answer("remove", "confirm_remove", "true")).action == Action.REMOVE,
                "confirmed removal creates one captured typed request");

        View visitor = view(71L, Mode.DROPOFF, false, pickup.destinations);
        check(visitor.mode == Mode.STATION, "legacy drop-off view is normalized to a combined station");
        rejected(() -> RailwayPassengerUi.fromAnswers(visitor, answer("rename", "stop_name", "Stolen")), "visitor cannot forge rename");
        rejected(() -> RailwayPassengerUi.fromAnswers(visitor, answer("remove", "confirm_remove", "true")), "visitor cannot forge removal");
        check(RailwayPassengerUi.fromAnswers(visitor, answer("destination", "destination_choice", "0")).destinationId == 101L,
                "passengers can choose their own destination without stop-management permissions");
        View personal = view(-10L, null, true, pickup.destinations);
        check(personal.personal() && !personal.canManage, "onboard journey is independent of stop-management permissions");
        String pickupText = RailwayPassengerUi.content(pickup);
        check(pickupText.contains("Linked track: Harbor railway") && pickupText.contains("Train route stop")
                        && pickupText.contains("Train controls > Automatic service") && pickupText.contains("No extra named stop is needed"),
                "existing passenger tile clearly advertises its linked track and immediate availability in train routes");
        check(pickupText.contains("My destination") && pickupText.contains("does not change the driver")
                        && pickupText.contains("Set my destination"),
                "personal rider destination is distinct from the operator's train route");
        check(pickupText.contains("Confirm removal of this station tile") && pickupText.contains("the railway remains in place"),
                "destructive management is clearly scoped and separated from riding controls");
        check(!RailwayPassengerUi.content(personal).contains("Train route stop")
                        && !RailwayPassengerUi.content(personal).contains("stop_name"),
                "onboard personal window contains no platform management or misleading platform-specific route setup");
        check(RailwayPassengerUi.fromAnswers(personal, answer("destination", "destination_choice", "1")).trainEngineId == 99L,
                "onboard destination preserves expected train identity");
        rejected(() -> RailwayPassengerUi.fromAnswers(personal, answer("remove", "confirm_remove", "true")), "personal journey cannot forge stop removal");
        View empty = view(72L, Mode.PICKUP, false, Collections.<Choice>emptyList());
        check(RailwayPassengerUi.content(empty).contains("Build a station beside connected track")
                        && !RailwayPassengerUi.content(empty).contains("id='destination'"),
                "empty destinations explain connectivity and do not display an unusable choice button");
        rejected(() -> RailwayPassengerUi.fromAnswers(empty, answer("destination", "destination_choice", "0")), "empty destination list cannot select arbitrary world ID");
        check(RailwayPassengerUi.fromAnswers(empty, answer("cancel_destination")).action == Action.CANCEL_DESTINATION,
                "stale unavailable destination can still be cleared");
        rejected(() -> view(71L, Mode.PICKUP, false, Arrays.asList(new Choice(1L, "A"), new Choice(1L, "B"))),
                "duplicate destination identities cannot create ambiguous selections");

        List<RailChoice> rails = new ArrayList<RailChoice>(Arrays.asList(new RailChoice("hidden_A", "North railway"), new RailChoice("hidden_B", "East railway")));
        BuildView construction = new BuildView("Beside the railway", rails, Mode.DROPOFF, 1, false);
        rails.clear();
        BuildRequest build = RailwayPassengerUi.buildFromAnswers(construction,
                answer("build", "rail", "1", "mode", "0", "stop_name", " Harbor pickup ", "trackKey", "forged", "count", "999"));
        check(build.action == BuildAction.BUILD && build.trackKey.equals("hidden_B") && build.mode == Mode.STATION
                        && build.name.equals("Harbor pickup"), "build uses captured neighboring rail and only the combined station type");
        check(construction.rails.size() == 2, "source rail-list mutations cannot alter captured construction choices");
        rejected(() -> construction.rails.clear(), "captured rail choices are immutable");
        check(RailwayPassengerUi.buildFromAnswers(construction,
                answer("build", "rail", "0", "mode", "1", "stop_name", "Mine")).mode == Mode.STATION,
                "legacy drop-off choice cannot restore a separate station type");
        for (String index : Arrays.asList("-1", "2", "hidden_A", "999999999999999999999999"))
            rejected(() -> RailwayPassengerUi.buildFromAnswers(construction,
                    answer("build", "rail", index, "mode", "0", "stop_name", "Stop")), "forged neighboring rail index rejected");
        for (String mode : Arrays.asList("-1", "2", "PICKUP", "DROPOFF", "0.5", "garbage"))
            check(RailwayPassengerUi.buildFromAnswers(construction,
                    answer("build", "rail", "0", "mode", mode, "stop_name", "Stop")).mode == Mode.STATION,
                    "forged obsolete mode cannot select separate pickup or drop-off behavior");
        check(RailwayPassengerUi.buildFromAnswers(construction,
                answer("build", "rail", "0", "stop_name", "Stop")).mode == Mode.STATION,
                "current builder needs no obsolete mode field");
        rejected(() -> RailwayPassengerUi.buildFromAnswers(new BuildView("Empty", Collections.<RailChoice>emptyList(), Mode.PICKUP, 1, false),
                answer("build", "rail", "0", "mode", "0", "stop_name", "Stop")), "construction requires captured adjacent rail");
        check(RailwayPassengerUi.buildFromAnswers(construction, answer("refresh", "rail", "bad", "mode", "bad")).action == BuildAction.REFRESH,
                "construction refresh does not parse stale settings");
        check(RailwayPassengerUi.buildFromAnswers(construction, answer("close")) == null, "construction close makes no changes");
        Properties badBuild = answer("build", "rail", "0", "mode", "0", "stop_name", "Stop"); badBuild.setProperty("refresh", "true");
        rejected(() -> RailwayPassengerUi.buildFromAnswers(construction, badBuild), "construction rejects multiple simultaneous actions");
        String fragment = RailwayPassengerUi.buildContent(construction);
        check(!fragment.contains("id='mode'") && !fragment.contains("Tile type:") && !fragment.contains("hidden_A") && !fragment.contains("hidden_B"),
                "construction shows friendly rail names and has no separate tile-type choice or internal keys");
        check(fragment.contains("Linked track:") && fragment.contains("drops off riders and picks up waiting players")
                        && fragment.contains("also appears in the train route list"),
                "construction labels track control and explains the combined station workflow");
        check(construction.defaultMode == Mode.STATION, "legacy constructor defaults cannot create a separate station type");
        check(pickupText.contains("STATION | Drop off and pick up") && pickupText.contains("drop off riders first")
                        && pickupText.contains("leave the station tile before returning") && pickupText.contains("Waiting: 2"),
                "station panel explains shared service, per-player return rule and waiting count");
        View recent = new View(72L,3L,99L,Mode.STATION,"Harbor","Track",
                "Recently dropped off: leave station area before boarding again",false,-10L,0,-1,pickup.destinations);
        check(RailwayPassengerUi.content(recent).contains("label{type='bold';text='Recently dropped off: leave station area before boarding again'"),
                "individual waiting or recent-drop status is prominent rather than inferred from shared station mode");
        View noDestination = new View(72L, 3L, 99L, Mode.PICKUP, "Harbor", "Track", "", false,
                -10L, 0, -1, pickup.destinations);
        check(!RailwayPassengerUi.content(noDestination).contains("id='cancel_destination'")
                        && RailwayPassengerUi.content(noDestination).contains("id='destination'"),
                "clearing a personal drop-off is shown only when one is set");
    }

    private static View view(long id, Mode mode, boolean manage, List<Choice> choices) {
        return new View(id, 3L, 99L, mode, "Harbor pickup", "Harbor railway", "Waiting", manage, 202L, 2, 4, choices);
    }

    private static Properties answer(String button, String... pairs) {
        Properties values = new Properties(); values.setProperty(button, "true");
        for (int i = 0; i < pairs.length; i += 2) values.setProperty(pairs[i], pairs[i + 1]);
        return values;
    }

    private static String longName() { char[] text = new char[49]; Arrays.fill(text, 'x'); return new String(text); }
    private interface Checked { void run(); }
    private static void rejected(Checked action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
