package org.wurmrail.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.wurmrail.core.TrestleAlignment.Point;

public final class TrestleAlignmentSelfTest {
    public static void main(String[] args) throws Exception {
        double maxGrade = (20 / 10.0) / TrestleAlignment.TILE;
        for (int tiles : new int[] {50, 100, 250, 500}) {
            double length = tiles * TrestleAlignment.TILE;
            TrestleAlignment alignment = TrestleAlignment.straight(
                    new Point(2, 2, 1100), new Point(2 + length, 2, 1100 - length * maxGrade), length, maxGrade);
            check(alignment.length == length);
            check(alignment.gradient == -maxGrade);
            check(alignment.at(length / 2).z == 1100 - length * maxGrade / 2);
            check(alignment.at(length).x == 2 + length);
            check(alignment.supportDistances(12).size() == (int) Math.ceil(length / 12));
            double previous = 0;
            for (double distance : alignment.supportDistances(12)) {
                check(distance > previous && distance < length && distance - previous <= 12.000001);
                previous = distance;
            }
            elevatedConsist(tiles, alignment);
        }
        Point start = new Point(2, 2, 10);
        Point twentyDirtUp = new Point(6, 2, 12);
        check(TrestleAlignment.straight(start, twentyDirtUp, 1000, maxGrade).gradient == 0.50);
        rejects(() -> TrestleAlignment.straight(start, new Point(6, 2, 12.1), 1000, maxGrade));
        rejects(() -> TrestleAlignment.straight(twentyDirtUp, new Point(2, 2, 9.9), 1000, maxGrade));
        rejects(() -> TrestleAlignment.straight(start, twentyDirtUp, 1000, 0.25));
        check(TrestleAlignment.straight(start, twentyDirtUp, 1000, 0.60).gradient == 0.50);
        check(Math.abs(TrestleAlignment.straight(start, new Point(6, 6, 12), 1000, maxGrade).gradient
                - maxGrade / Math.sqrt(2)) < 1e-12);
        TrestleAlignment.straight(new Point(2, 2, 1000.3), new Point(6, 2, 1002.3), 1000, maxGrade);
        rejects(() -> TrestleAlignment.straight(start, start, 1000, 0.05));
        rejects(() -> TrestleAlignment.straight(start, new Point(1006, 2, 10), 1000, 0.05));
        rejects(() -> TrestleAlignment.straight(start, new Point(42, 2, 13), 1000, 0.05));
        rejects(() -> new Point(2, 2, Double.NaN));
        roundedSurveys();
        curvedConsist();
        waypointHairpin();
        variableGrades();
        automaticTerrainFit();
        System.out.println("TrestleAlignmentSelfTest: exact waypoint and tile layout, coupled hairpin and straight poses, radius limits and legacy/new restart passed.");
    }

    private static void automaticTerrainFit() {
        TrestleAlignment horizontal = TrestleAlignment.waypoints(Arrays.asList(new Point(44,42,30),
                new Point(66,42,0),new Point(86,42,0),new Point(106,42,0),new Point(124,42,34)),20,500,.5,2,2);
        List<Double> overrides = Arrays.asList(null,39d,null);
        TrestleAlignment baseline = horizontal.withWaypointElevations(overrides,.5);
        List<TrestleHeightFit.Sample> ground = Arrays.asList(new TrestleHeightFit.Sample(0,30),
                new TrestleHeightFit.Sample(.05,30),new TrestleHeightFit.Sample(11,34),new TrestleHeightFit.Sample(52,40.2),
                new TrestleHeightFit.Sample(baseline.length,34));
        TrestleAlignment fitted = TrestleHeightFit.fit(baseline,overrides,.5,ground);
        List<Double> poles = fitted.waypointDistances();
        check(fitted != baseline && fitted.elevationAt(poles.get(1))==39);
        check(Math.abs(fitted.elevationAt(poles.get(0))-38.04)<1e-6);
        check(fitted.elevationAt(0)==30 && fitted.elevationAt(fitted.length)==34);
        for (TrestleHeightFit.Sample sample : ground)
            check(fitted.elevationAt(sample.distance) >= sample.ground-1e-8);
        check(fitted.maximumAbsoluteGradient()<=.5+1e-9);
        check(TrestleHeightFit.fit(fitted,overrides,.5,ground)==fitted);
        rejects(() -> TrestleHeightFit.fit(baseline,overrides,.5,
                Arrays.asList(new TrestleHeightFit.Sample(1,47))));
        check(TrestleHeightFit.fit(baseline,Arrays.asList(35d,39d,37d),.5,ground)==baseline);
    }

    private static void variableGrades() throws Exception {
        TrestleAlignment horizontal = TrestleAlignment.waypoints(Arrays.asList(new Point(44,42,30),
                new Point(66,42,0),new Point(86,42,0),new Point(106,42,0),new Point(124,42,34)),20,500,.5,2,2);
        check(horizontal.withWaypointElevations(Arrays.asList(null,null,null),.5)==horizontal);
        rejects(() -> horizontal.withWaypointElevations(Arrays.asList(42d,null,41d),.5));
        TrestleAlignment profile = horizontal.withWaypointElevations(Arrays.asList(38d,null,41d),.5);
        List<Double> distances = profile.waypointDistances();
        check(profile.at(distances.get(0)).z==38 && Math.abs(profile.at(distances.get(1)).z-39.5)<1e-9);
        check(profile.at(distances.get(2)).z==41 && profile.sectionGradient(3)<0);
        RailNetwork network = new RailNetwork();
        List<String> rails = linkSurveyedSpan(network,"profile",profile,10,10,31,10);
        network.connect(network.addNode("back",9,10,0).name,rails.get(0));
        network.connect(rails.get(rails.size()-1),network.addNode("ahead",32,10,0).name);
        network.connect("ahead",network.addNode("far",33,10,0).name);
        RailNetwork.Train train=network.createTrain("GradeTrain",1,Arrays.asList(841L,842L),rails.get(0),rails.get(1));
        Path directory=Files.createTempDirectory("wurmrail-profile-");
        Path file=directory.resolve("network.properties");
        try {
            network.save(file);
            RailNetwork restored=RailNetwork.load(file);
            for (String name : rails.subList(1,rails.size()-1))
                check(network.surveyedPanel(name).modelName().equals(restored.surveyedPanel(name).modelName()));
            RailNetwork.Train resumed=restored.trains.get(train.name);
            for (int direction : new int[]{1,-1}) {
                if (direction < 0) restored.reverse(train.name);
                restored.start(train.name);
                int climbing=0, descending=0;
                for (int tick=0;tick<220;tick++) {
                    restored.tick(.2);
                    for (RailNetwork.Pose pose : resumed.poses) {
                        if (!Float.isFinite(pose.elevation)) continue;
                        double estimated = Math.max(0, Math.min(profile.length,pose.x-44));
                        check(Math.abs(pose.elevation-profile.elevationAt(estimated))<.0001);
                        if (pose.x>45 && pose.x<66) climbing++;
                        if (pose.x>106 && pose.x<123) descending++;
                    }
                }
                check(climbing>0 && descending>0);
                restored.stop(train.name);
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void waypointHairpin() throws Exception {
        List<Point> marks = Arrays.asList(new Point(44,42,30), new Point(82,42,0),
                new Point(98,50,0), new Point(106,66,0), new Point(98,82,0),
                new Point(82,90,0), new Point(44,90,34));
        TrestleAlignment curve = TrestleAlignment.waypoints(marks,12,500,.5,2,6);
        rejects(() -> TrestleAlignment.waypoints(marks,20,500,.5,2,6));
        rejects(() -> TrestleAlignment.waypoints(marks,8,500,.5,2,6));
        rejects(() -> TrestleAlignment.waypoints(Arrays.asList(new Point(44,42,5), new Point(82,42,0),
                new Point(46,42,5)),12,500,.5,2,6));
        List<TrestleAlignment.TileSection> tiles = curve.tileSections();
        List<Double> distances = curve.waypointDistances();
        check(distances.size()==marks.size()-2);
        for (int i = 0; i < distances.size(); i++) {
            double distance = distances.get(i);
            Point actual = curve.at(distance), pole = marks.get(i+1);
            check(actual.x==pole.x && actual.y==pole.y);
            check(Math.abs(curve.headingAt(distance-.001)-curve.headingAt(distance+.001))<.012);
            TrestleAlignment.TileSection owner = tiles.stream()
                    .filter(tile -> distance > tile.entry && distance < tile.exit).findFirst().get();
            org.wurmrail.shared.TrestlePanel panel = curve.panel(owner);
            boolean reachesPole = false;
            for (int row = 0; row <= org.wurmrail.shared.TrestlePanel.INTERVALS; row++) {
                float[] center = panel.vertex(row / (float)org.wurmrail.shared.TrestlePanel.INTERVALS, 0);
                reachesPole |= Math.hypot(center[0]+owner.x*4+2-pole.x,center[1]+owner.y*4+2-pole.y)<.0001;
            }
            check(reachesPole);
        }
        double maxCurvature = 0;
        for (double d = .01; d < curve.length-.01; d += .01) {
            double[] before = curve.tangentAt(d-.005), after = curve.tangentAt(d+.005);
            double turn = Math.atan2(before[0]*after[1]-before[1]*after[0], before[0]*after[0]+before[1]*after[1]);
            maxCurvature = Math.max(maxCurvature,Math.abs(turn)/.01);
        }
        check(maxCurvature < 1/12.0 && maxCurvature > 1/20.0);

        RailNetwork network = new RailNetwork();
        List<String> rails = linkSurveyedSpan(network,"hairpin",curve,10,10,10,22);
        network.connect(network.addNode("west",9,10,0).name,rails.get(0));
        network.connect(network.addNode("west2",8,10,0).name,"west");
        network.connect(network.addNode("west3",7,10,0).name,"west2");
        network.connect(rails.get(rails.size()-1),network.addNode("return1",9,22,0).name);
        network.connect("return1",network.addNode("return2",8,22,0).name);
        network.connect("return2",network.addNode("return3",7,22,0).name);
        TrestleAlignment legacy = TrestleAlignment.rounded(Arrays.asList(new Point(204,202,15),
                new Point(322,202,0),new Point(322,320,15)),60,500,.5);
        List<String> legacyRails = linkSurveyedSpan(network,"legacy",legacy,50,50,80,80);
        String oldPanel = network.surveyedPanel(legacyRails.get(6)).modelName();
        RailNetwork.Train train = network.createTrain("Logging",17,Arrays.asList(771L,772L,773L),rails.get(0),rails.get(1));
        network.start(train.name);
        for (int tick=0; tick<190; tick++) {
            network.tick(.2);
            checkCurvedPoses(train,curve);
            if (train.poses.get(0).x>100 && train.poses.get(0).y>60) break;
        }
        check(train.poses.get(0).x>100 && train.poses.get(0).y>53);
        Path directory=Files.createTempDirectory("wurmrail-waypoints-");
        Path file=directory.resolve("network.properties");
        try {
            network.save(file);
            RailNetwork restored=RailNetwork.load(file);
            RailNetwork.Train resumed=restored.trains.get(train.name);
            check(oldPanel.equals(restored.surveyedPanel(legacyRails.get(6)).modelName()));
            for (String rail : rails.subList(1,rails.size()-1))
                check(network.surveyedPanel(rail).modelName().equals(restored.surveyedPanel(rail).modelName()));
            for (int i=0;i<train.poses.size();i++) {
                RailNetwork.Pose before=train.poses.get(i), after=resumed.poses.get(i);
                check(before.x==after.x && before.y==after.y && before.elevation==after.elevation);
            }
            restored.start(resumed.name);
            for (int i=0;i<400 && resumed.poses.get(2).x>42;i++) {
                restored.tick(.2);
                checkCurvedPoses(resumed,curve);
            }
            check(resumed.poses.get(2).x<=42 && resumed.poses.get(2).y==90);
            restored.stop(resumed.name);
            restored.reverse(resumed.name);
            restored.start(resumed.name);
            for (int i=0;i<500 && (resumed.poses.get(0).x>42 || resumed.poses.get(0).y!=42);i++) {
                restored.tick(.2);
                checkCurvedPoses(resumed,curve);
            }
            check(resumed.poses.get(0).x<=42 && resumed.poses.get(0).y==42);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static List<String> linkSurveyedSpan(RailNetwork network, String name, TrestleAlignment alignment,
                                                  int startX, int startY, int endX, int endY) {
        List<String> rails = new ArrayList<>();
        rails.add(network.addNode(name+"Start",startX,startY,0).name);
        for (TrestleAlignment.TileSection tile : alignment.tileSections())
            rails.add(network.addNode(name+rails.size(),tile.x,tile.y,0).name);
        rails.add(network.addNode(name+"End",endX,endY,0).name);
        for (int i=1;i<rails.size();i++) network.connect(rails.get(i-1),rails.get(i));
        network.setCurvedTrestleElevation(rails,alignment);
        return rails;
    }

    private static void curvedConsist() throws Exception {
        TrestleAlignment alignment=TrestleAlignment.rounded(Arrays.asList(
                new Point(44,42,30),new Point(162,42,0),new Point(162,160,50)),60,400,.5);
        RailNetwork network=new RailNetwork();
        network.addNode("West7",7,10,0);
        network.addNode("West8",8,10,0);
        network.addNode("West9",9,10,0);
        network.addNode("West10",10,10,0);
        network.connect("West7","West8");
        network.connect("West8","West9");
        network.connect("West9","West10");
        List<String> rails=new ArrayList<>();
        rails.add("West10");
        double entry=0;
        for (TrestleAlignment.TileSection section:alignment.tileSections()) {
            check(Math.abs(section.entry-entry)<1e-8);
            Point point=alignment.at(section.center());
            check((int)(point.x/4)==section.x && (int)(point.y/4)==section.y);
            String name="Curve_"+rails.size();
            network.addNode(name,section.x,section.y,0);
            network.connect(rails.get(rails.size()-1),name);
            rails.add(name);
            entry=section.exit;
        }
        check(Math.abs(entry-alignment.length)<1e-8);
        network.addNode("South40",40,40,0);
        network.connect(rails.get(rails.size()-1),"South40");
        rails.add("South40");
        for (int y=41;y<=43;y++) {
            network.addNode("South"+y,40,y,0);
            network.connect("South"+(y-1),"South"+y);
        }
        network.setCurvedTrestleElevation(rails,alignment);
        for (String name:rails.subList(1,rails.size()-1)) network.assignTrestleNode(name,"Curve");
        RailNetwork.Train train=network.createTrain("CurveTrain",1,Arrays.asList(700L,701L,702L),
                rails.get(0),rails.get(1));
        network.start(train.name);
        for (int i=0;i<185;i++) {
            network.tick(.2);
            checkCurvedPoses(train,alignment);
        }
        check(train.poses.get(0).y>50 && Float.isFinite(train.poses.get(0).elevation));
        Path directory=Files.createTempDirectory("wurmrail-curved-");
        Path file=directory.resolve("network.properties");
        try {
            network.save(file);
            RailNetwork reloaded=RailNetwork.load(file);
            RailNetwork.Train resumed=reloaded.trains.get(train.name);
            for (int i=0;i<train.poses.size();i++) {
                RailNetwork.Pose a=train.poses.get(i), b=resumed.poses.get(i);
                check(a.x==b.x && a.y==b.y && a.elevation==b.elevation);
            }
            reloaded.start(resumed.name);
            for (int i=0;i<500 && resumed.poses.get(2).y<162;i++) {
                reloaded.tick(.2);
                checkCurvedPoses(resumed,alignment);
            }
            check(resumed.poses.get(2).y>=162);
            reloaded.stop(resumed.name);
            reloaded.reverse(resumed.name);
            reloaded.start(resumed.name);
            for (int i=0;i<500 && resumed.poses.get(0).x>42;i++) {
                reloaded.tick(.2);
                checkCurvedPoses(resumed,alignment);
            }
            check(resumed.poses.get(0).x<=42 && Float.isNaN(resumed.poses.get(0).elevation));
            reloaded.stop(resumed.name);
            reloaded.removeTrain(resumed.name);
            reloaded.removeTrestle("Curve");
            check(reloaded.nodes.size()==8);
            reloaded.save(file);
            check(RailNetwork.load(file).nodes.size()==8);
        } finally {
            Files.deleteIfExists(file.resolveSibling("network.properties.bak"));
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void checkCurvedPoses(RailNetwork.Train train,TrestleAlignment alignment) {
        double previous=Double.NaN;
        for (RailNetwork.Pose pose:train.poses) {
            if (!Float.isFinite(pose.elevation)) { previous=Double.NaN; continue; }
            double distance=Math.max(0,Math.min(alignment.length,(pose.elevation-30)/alignment.gradient));
            Point point=alignment.at(distance);
            check(Math.hypot(pose.x-point.x,pose.y-point.y)<.0002);
            if (Double.isFinite(previous)) check(Math.abs(previous-distance-4)<.0002);
            previous=distance;
        }
    }

    private static void roundedSurveys() {
        Point start = new Point(0,0,30), corner = new Point(120,0,200), end = new Point(120,120,50);
        List<Point> route = Arrays.asList(start,corner,end);
        TrestleAlignment alignment = TrestleAlignment.rounded(route,60,1000,.5);
        check(alignment.curved && Math.abs(alignment.length - (120+30*Math.PI)) < 1e-9);
        check(alignment.at(0) == start && alignment.at(alignment.length) == end);
        Point middle = alignment.at(alignment.length*.5);
        check(Math.abs(middle.x-(60+60/Math.sqrt(2))) < 1e-9);
        check(Math.abs(middle.y-(60-60/Math.sqrt(2))) < 1e-9);
        check(Math.abs(middle.z-40) < 1e-9);
        Point inside = alignment.offsetAt(alignment.length*.5,.96);
        Point outside = alignment.offsetAt(alignment.length*.5,-.96);
        check(Math.abs(Math.hypot(inside.x-60,inside.y-60)-59.04) < 1e-9);
        check(Math.abs(Math.hypot(outside.x-60,outside.y-60)-60.96) < 1e-9);
        check(inside.z == middle.z && outside.z == middle.z);
        Point before = alignment.at(60-.001), after = alignment.at(60+.001);
        check(Math.abs((after.x-before.x)-.002) < 1e-8 && Math.abs(after.y-before.y) < 1e-8);
        for (double distance : alignment.supportDistances(12)) {
            Point bent = alignment.at(distance);
            check(Math.abs(bent.z-(start.z+alignment.gradient*distance)) < 1e-9);
        }
        rejects(() -> TrestleAlignment.rounded(route,121,1000,.5));
        rejects(() -> TrestleAlignment.rounded(route,60,200,.5));
        rejects(() -> TrestleAlignment.rounded(route,60,1000,.05));
        rejects(() -> TrestleAlignment.rounded(Arrays.asList(start,corner,new Point(30,0,25)),60,1000,.5));
        rejects(() -> TrestleAlignment.rounded(Arrays.asList(start,new Point(160,0,30),new Point(160,160,30),
                new Point(80,160,30),new Point(80,-80,30)),16,1000,.5));
    }

    private static void elevatedConsist(int tiles, TrestleAlignment alignment) throws Exception {
        RailNetwork network = new RailNetwork();
        List<String> span = new ArrayList<>();
        for (int x = 0; x <= tiles + 4; x++) {
            String rail = "R" + x;
            network.addNode(rail, x, 0, 0);
            if (x > 0) network.connect("R" + (x - 1), rail);
            if (x <= tiles) span.add(rail);
        }
        network.setStraightElevation(span, alignment);
        rejects(() -> network.disconnect("R0", "R1"));
        RailNetwork.Train train = network.createTrain("Logging", 1L, Arrays.asList(100L, 200L, 300L), "R2", "R3");
        network.start(train.name);
        boolean sawOrdinaryApproach = false;
        for (int i = 0; i < tiles * 12 && train.poses.get(2).x < tiles * 4 + 6; i++) {
            network.tick(0.25);
            checkPoses(train, alignment);
            sawOrdinaryApproach |= Float.isNaN(train.poses.get(0).elevation);
        }
        check(sawOrdinaryApproach && train.poses.get(2).x >= tiles * 4 + 6);
        network.stop(train.name);
        network.reverse(train.name);
        network.start(train.name);
        for (int i = 0; i < 75; i++) {
            network.tick(0.25);
            checkPoses(train, alignment);
        }
        check(Float.isFinite(train.poses.get(0).elevation));
        Path file = Files.createTempFile("wurmrail-trestle-", ".properties");
        Files.delete(file);
        try {
            network.save(file);
            RailNetwork.Train reloaded = RailNetwork.load(file).trains.get(train.name);
            check(reloaded.direction == -1 && !reloaded.powered);
            for (int i = 0; i < train.poses.size(); i++)
                check(reloaded.poses.get(i).elevation == train.poses.get(i).elevation);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void checkPoses(RailNetwork.Train train, TrestleAlignment alignment) {
        for (RailNetwork.Pose pose : train.poses) {
            double distance = pose.x - 2;
            if (distance <= alignment.length) check(Math.abs(pose.elevation - alignment.at(distance).z) < 0.0002);
            else check(Float.isNaN(pose.elevation));
        }
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid survey was accepted.");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected survey geometry.");
    }
}
