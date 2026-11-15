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
        System.out.println("TrestleAlignmentSelfTest: 50% grade limits, decimal elevations, diagonal run, configured limits, coupled poses and restart passed.");
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
