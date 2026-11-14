package org.wurmrail;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

/** User-input and BML-injection regressions; no game state is instantiated. */
public final class RailwayBuilderUiSelfTest {
    private static int assertions;

    public static void main(String[] args) {
        run();
        System.out.println("Railway builder input checks passed: " + assertions + " assertions.");
    }

    public static void run() {
        assertions = 0;
        RailwayBuilderUi.View blank = view(null, false, false, Collections.<RailwayBuilderUi.Neighbor>emptyList());
        require(RailwayBuilderUi.fromAnswers(blank, new Properties()) == null, "closing makes no changes");
        for (int direction = 0; direction < 8; direction++) {
            RailwayBuilderUi.Request request = RailwayBuilderUi.fromAnswers(blank,
                    answers("place", "direction", Integer.toString(direction), "count", "100"));
            require(request.action == RailwayBuilderUi.Action.PLACE && request.direction == direction
                    && request.count == 100 && request.nodeName == null, "eight compass choices build captured tile");
        }
        reject(blank, answers("place", "direction", "8", "count", "1"));
        reject(blank, answers("place", "direction", "-1", "count", "1"));
        reject(blank, answers("place", "direction", "0", "count", "101"));
        reject(blank, answers("place", "direction", "0", "count", "0"));
        reject(blank, answers("place", "direction", "0", "count", "1.5"));
        Properties multiple = answers("place", "direction", "0", "count", "1");
        multiple.setProperty("remove", "true");
        multiple.setProperty("confirm_remove", "true");
        reject(blank, multiple);

        RailwayBuilderUi.View track = view("internal_track_42", true, false, Arrays.asList(
                new RailwayBuilderUi.Neighbor(6, "West platform", true, true),
                new RailwayBuilderUi.Neighbor(0, "North platform", true, false),
                new RailwayBuilderUi.Neighbor(3, "Southeast", true, false)),65);
        RailwayBuilderUi.Request unaligned = RailwayBuilderUi.fromAnswers(track, answers("alignment", "alignment_choice", "0"));
        require(unaligned.action == RailwayBuilderUi.Action.ALIGNMENT && unaligned.alignmentMask == 0,
                "unaligned state explicitly stops every junction approach");
        RailwayBuilderUi.Request selected = RailwayBuilderUi.fromAnswers(track, answers("alignment", "alignment_choice", "2"));
        require(selected.alignmentMask == 65, "sparse dropdown indices resolve an absolute connected compass pair");
        require(selected.nodeName.equals("internal_track_42") && selected.markerId == 42,
                "identity comes from server view");
        require(RailwayBuilderUi.content(track).contains("id=\"alignment_choice\";default=\"2\""),
                "physical pair survives refresh");
        require(RailwayBuilderUi.content(track).contains("N \u2194 W (North - West)"),
                "pair labels describe both ends in absolute compass terms");
        String trackText = RailwayBuilderUi.content(track);
        require(trackText.indexOf("Active alignment: N \u2194 W") < trackText.indexOf("Track connections"),
                "current alignment is immediately visible before any editing controls");
        require(trackText.contains("Named train stop (optional)") && trackText.contains("Station tiles already appear")
                        && trackText.contains("Build station") && !trackText.contains("pickup or drop-off"),
                "track naming is an optional route stop rather than a prerequisite for passenger platforms");
        RailwayBuilderUi.View reserved = new RailwayBuilderUi.View("Surface tile", "reserved", 42, true,
                false, "", 5, track.neighbors, 0, 7, false, 65, "Harbor express");
        require(RailwayBuilderUi.content(reserved).contains("Locked for train: Harbor express"),
                "reservation owner explains why the junction cannot currently be changed");
        RailwayBuilderUi.View invalidPair = new RailwayBuilderUi.View("Surface tile", "invalid", 42, true,
                false, "", 5, track.neighbors, 0, 7, false, 17, null);
        require(RailwayBuilderUi.content(invalidPair).contains("Active alignment: not aligned")
                        && invalidPair.alignmentOwner.isEmpty(),
                "missing owner is safe and a disconnected pair is never advertised as passable");
        require(!RailwayBuilderUi.content(track).contains("Automatic routing")
                        && !RailwayBuilderUi.content(track).contains("switch_direction"),
                "junction controls have no legacy single-exit or automatic routing mode");
        reject(track, answers("alignment", "alignment_choice", "4"));
        reject(track, answers("alignment", "alignment_choice", "-1"));
        reject(track, answers("alignment", "alignment_choice", "NaN"));
        reject(track, answers("connections", "exit_1", "true"));
        RailwayBuilderUi.Request connections = RailwayBuilderUi.fromAnswers(track,
                answers("connections", "exit_0", "true", "exit_3", "true", "station_name", "", "dwell", "garbage"));
        require(connections.connectionMask == 9, "unrelated station form fields cannot affect connection action");
        require(connections.action == RailwayBuilderUi.Action.CONNECTIONS, "connections are one typed request");

        RailwayBuilderUi.Neighbor[] allNeighbors = new RailwayBuilderUi.Neighbor[8];
        Properties allExits = answers("connections");
        for (int direction = 0; direction < 8; direction++) {
            allNeighbors[direction] = new RailwayBuilderUi.Neighbor(direction, "Track", true, false);
            allExits.setProperty("exit_" + direction, "true");
        }
        RailwayBuilderUi.View junction = view("junction", true, false, Arrays.asList(allNeighbors));
        require(RailwayBuilderUi.fromAnswers(junction, allExits).connectionMask == 255,
                "all eight exits retain unsigned mask");
        int option=1;
        for(int first=0;first<8;first++) for(int second=first+1;second<8;second++) {
            int expectedMask=(1<<first)|(1<<second);
            boolean buildable=org.wurmrail.core.RailNetwork.isBuildableAlignment(expectedMask);
            Properties pairConnections=answers("connections","exit_"+first,"true","exit_"+second,"true");
            if(!buildable) {
                reject(junction,pairConnections);
                continue;
            }
            require(RailwayBuilderUi.fromAnswers(junction,pairConnections).connectionMask==expectedMask,
                    "straight, 45-degree and 90-degree connections remain available at every heading");
            int mask=RailwayBuilderUi.fromAnswers(junction,
                    answers("alignment","alignment_choice",Integer.toString(option++))).alignmentMask;
            require(mask==expectedMask,"all buildable track pairs preserve their exact absolute directions");
        }
        require(option==21,"eight compass exits offer 20 gentle/straight alignments and omit all eight 135-degree turns");
        reject(junction,answers("alignment","alignment_choice","21"));
        String dropdown=RailwayBuilderUi.content(junction);
        dropdown=dropdown.substring(dropdown.indexOf("id=\"alignment_choice\""));
        dropdown=dropdown.substring(0,dropdown.indexOf('}'));
        require(!dropdown.contains("N \u2194 NE")&&!dropdown.contains("N \u2194 NW")
                        &&dropdown.contains("N \u2194 E")&&dropdown.contains("N \u2194 SE")&&dropdown.contains("N \u2194 S"),
                "the visible alignment list excludes tight turns, including the north wrap, while retaining 45/90/straight options");
        for(int invalidMask:new int[]{-1,0,1,7,255,256})
            require(!org.wurmrail.core.RailNetwork.isBuildableAlignment(invalidMask),"buildable alignments require exactly two valid compass sides");
        RailwayBuilderUi.View legacyCorner=view("legacy",true,false,
                Arrays.asList(allNeighbors[0],allNeighbors[1]),3);
        require(RailwayBuilderUi.fromAnswers(legacyCorner,answers("connections","exit_0","true","exit_1","true")).connectionMask==3,
                "an unchanged legacy corner remains readable and saving its unchanged connections does not break the world");
        RailwayBuilderUi.View legacyJunction=view("legacy_junction",true,false,Arrays.asList(allNeighbors),3);
        require(RailwayBuilderUi.content(legacyJunction).contains("Active alignment: N \u2194 NE")
                        &&RailwayBuilderUi.content(legacyJunction).contains("id=\"alignment_choice\";default=\"0\""),
                "a saved legacy alignment is reported accurately without offering it as a new switch choice");
        RailwayBuilderUi.View fixed=view("fixed",true,false,Arrays.asList(allNeighbors[0],allNeighbors[4]),17);
        require(!RailwayBuilderUi.content(fixed).contains("alignment_choice")
                        &&RailwayBuilderUi.content(fixed).contains("N \u2194 S (North - South)"),
                "ordinary two-ended track shows its fixed physical path without a junction control");
        reject(fixed,answers("alignment","alignment_choice","1"));
        RailwayBuilderUi.View unset=view("unset",true,false,track.neighbors,0);
        require(RailwayBuilderUi.content(unset).contains("id=\"alignment_choice\";default=\"0\""),
                "legacy single selected neighbor cannot silently choose a physical pair");
        require(RailwayBuilderUi.fromAnswers(track,answers("switch","switch_direction","1"))==null,
                "old single-exit form controls cannot mutate a physical junction");
        reject(track, answers("remove"));
        require(RailwayBuilderUi.fromAnswers(track, answers("remove", "confirm_remove", "true")).action
                == RailwayBuilderUi.Action.REMOVE, "removal requires explicit confirmation");
        reject(track, answers("repair"));
        RailwayBuilderUi.View broken = view("broken", false, false, track.neighbors);
        require(RailwayBuilderUi.fromAnswers(broken, answers("repair")).action == RailwayBuilderUi.Action.REPAIR,
                "missing model repair remains available");

        RailwayBuilderUi.Request station = RailwayBuilderUi.fromAnswers(track,
                answers("station", "station_name", " Riverside goods ", "dwell", "12.5"));
        require(station.stationEnabled && station.stationName.equals("Riverside goods") && station.dwellSeconds == 12.5,
                "friendly station labels and fractional dwell survive");
        for (String invalidDwell : Arrays.asList("NaN", "Infinity", "-Infinity", "0", "3601", "bad"))
            reject(track, answers("station", "station_name", "Riverside", "dwell", invalidDwell));
        reject(track, answers("station", "station_name", "", "dwell", "5"));
        reject(track, answers("station", "station_name", "Hidden\ncontrol", "dwell", "5"));
        RailwayBuilderUi.Request removeStation = RailwayBuilderUi.fromAnswers(track,
                answers("station_off", "station_name", "", "dwell", "invalid"));
        require(!removeStation.stationEnabled, "removing station ignores unrelated stale input");
        reject(track, answers("spawn_engine"));
        RailwayBuilderUi.View gm = view("track", true, true, track.neighbors);
        require(RailwayBuilderUi.fromAnswers(gm, answers("spawn_cart")).action == RailwayBuilderUi.Action.SPAWN_CART,
                "GM vehicle button emits typed action");
        require(RailwayBuilderUi.fromAnswers(gm, answers("spawn_logcar")).action == RailwayBuilderUi.Action.SPAWN_LOGCAR,
                "GM Log Car button emits the dedicated typed action");
        reject(track,answers("spawn_logcar"));
        reject(blank,answers("spawn_logcar"));
        reject(gm,answers("spawn_logcar","spawn_cart","true"));
        require(RailwayBuilderUi.content(gm).contains("id=\"spawn_logcar\""),"GM Log Car placement is discoverable");
        require(!RailwayBuilderUi.content(track).contains("id=\"spawn_logcar\""),"ordinary players craft Log Cars");
        require(!RailwayBuilderUi.content(track).contains("id=\"spawn_engine\""), "player window has no GM controls");
        require(RailwayBuilderUi.content(gm).contains("id=\"spawn_engine\""), "GM controls are discoverable");
        require(RailwayBuilderUi.content(blank).contains("In your inventory: 7"), "crafting cost visible before building");
        require(!RailwayBuilderUi.content(blank).contains("station_name"), "empty-tile builder shows only the available construction workflow");

        String attack = "Station\"};button{id='remove';text='injected'};\\\n";
        String safe = RailwayBuilderUi.safe(attack);
        require(safe.indexOf('"') < 0 && safe.indexOf('\'') < 0 && safe.indexOf('{') < 0
                && safe.indexOf('}') < 0 && safe.indexOf('\\') < 0 && safe.indexOf('\n') < 0,
                "user labels cannot terminate BML attributes or inject controls");
        RailwayBuilderUi.View malicious = new RailwayBuilderUi.View(attack, "internal", 42, true, true,
                attack, Double.NaN, Arrays.asList(new RailwayBuilderUi.Neighbor(2, attack, true, false)), 99, -1, false);
        String bml = RailwayBuilderUi.content(malicious);
        require(!bml.contains("text=\"" + attack), "all user labels sanitized at render boundary");
        require(bml.contains("id=\"dwell\";maxchars=\"6\";text=\"5\""), "nonfinite persisted dwell renders safe default");
        require(malicious.defaultDirection == 7 && malicious.availableSections == 0, "display ranges are bounded");
        require(!bml.contains("internal"), "internal rail identity stays out of player text");
    }

    private static RailwayBuilderUi.View view(String node, boolean markerPresent, boolean gm,
                                               java.util.List<RailwayBuilderUi.Neighbor> neighbors) {
        return new RailwayBuilderUi.View("Surface tile 12,34", node, 42, markerPresent,
                false, "", 5, neighbors, 0, 7, gm);
    }

    private static RailwayBuilderUi.View view(String node, boolean markerPresent, boolean gm,
                                               java.util.List<RailwayBuilderUi.Neighbor> neighbors,int alignmentMask) {
        return new RailwayBuilderUi.View("Surface tile 12,34",node,42,markerPresent,
                false,"",5,neighbors,0,7,gm,alignmentMask);
    }

    private static Properties answers(String button, String... fields) {
        Properties answers = new Properties();
        answers.setProperty(button, "true");
        for (int index = 0; index < fields.length; index += 2) answers.setProperty(fields[index], fields[index + 1]);
        return answers;
    }

    private static void reject(RailwayBuilderUi.View view, Properties answers) {
        boolean rejected = false;
        try { RailwayBuilderUi.fromAnswers(view, answers); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "malformed or unauthorized answer must be rejected: " + answers);
    }

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
