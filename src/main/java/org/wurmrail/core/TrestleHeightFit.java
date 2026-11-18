package org.wurmrail.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.TreeSet;

/** Fits flexible waypoint heights to the same terrain samples used to check the deck. */
public final class TrestleHeightFit {
    public static final double TERRAIN_TOLERANCE = .05;
    private static final double CLEARANCE = .02;
    private static final double MAX_WURM_HEIGHT = 3276.7;

    public static final class Sample {
        public final double distance, ground;

        public Sample(double distance, double ground) {
            this.distance = distance;
            this.ground = ground;
        }
    }

    private TrestleHeightFit() { }

    public static TrestleAlignment fit(TrestleAlignment baseline, List<Double> overrides, double maxGrade,
                                       List<Sample> ground) {
        return fit(baseline, overrides, maxGrade, ground, null);
    }

    public static TrestleAlignment fit(TrestleAlignment baseline, List<Double> overrides, double maxGrade,
                                       List<Sample> ground, List<TrestleAlignment.TileSection> tiles) {
        if (overrides.stream().noneMatch(java.util.Objects::isNull)
                || ground.stream().noneMatch(sample -> baseline.elevationAt(sample.distance) + TERRAIN_TOLERANCE < sample.ground))
            return baseline;

        List<Double> poles = baseline.waypointDistances();
        NavigableSet<Double> marks = new TreeSet<>(poles);
        marks.add(0d);
        marks.add(baseline.length);
        NavigableSet<Double> fixedMarks = new TreeSet<>();
        fixedMarks.add(0d);
        fixedMarks.add(baseline.length);
        for (int i = 0; i < poles.size(); i++)
            if (overrides.get(i) != null) fixedMarks.add(poles.get(i));
        try {
            double[] heights = fitHeights(baseline, distances(marks), fixedMarks, maxGrade, ground, false);
            List<Double> fitted = new ArrayList<>();
            for (int i = 1; i < heights.length - 1; i++) fitted.add(heights[i]);
            return baseline.withWaypointElevations(fitted,maxGrade);
        } catch (ClearanceFailure blocked) {
            if (tiles == null) throw blocked;
        }

        // Use deck mesh rows as additional grade points so the running surface
        // and the train's height profile can follow the same changes in grade.
        for (TrestleAlignment.TileSection tile : tiles)
            for (double distance : baseline.panelDistances(tile)) {
                Double previous = marks.floor(distance), next = marks.ceiling(distance);
                if (distance - previous > 1e-7 && next - distance > 1e-7) marks.add(distance);
            }
        double[] distances = distances(marks);
        double[] heights = fitHeights(baseline, distances, fixedMarks, maxGrade, ground, true);
        return baseline.withDeckGrades(distances, heights, maxGrade);
    }

    private static double[] distances(NavigableSet<Double> marks) {
        return marks.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double[] fitHeights(TrestleAlignment baseline, double[] distances, NavigableSet<Double> fixedMarks,
                                       double maxGrade, List<Sample> ground, boolean useDeckGrades) {
        int last = distances.length - 1;
        double[] preferred = new double[last + 1], heights = new double[last + 1];
        boolean[] fixed = new boolean[last + 1];
        Arrays.fill(heights, MAX_WURM_HEIGHT);
        for (int i = 0; i <= last; i++) {
            preferred[i] = baseline.elevationAt(distances[i]);
            fixed[i] = fixedMarks.contains(distances[i]);
            if (fixed[i]) heights[i] = preferred[i];
        }

        // A fixed height limits how high every other pole can rise at the configured grade.
        for (int i = 1; i < last; i++)
            if (!fixed[i]) heights[i] = Math.min(heights[i], heights[i-1] + maxGrade*(distances[i]-distances[i-1]));
        for (int i = last-1; i > 0; i--)
            if (!fixed[i]) heights[i] = Math.min(heights[i], heights[i+1] + maxGrade*(distances[i+1]-distances[i]));

        List<List<Constraint>> adjacent = new ArrayList<>();
        double[] required = preferred.clone();
        if (!useDeckGrades)
            for (int i = 0; i <= last; i++) adjacent.add(new ArrayList<>());
        for (Sample sample : ground) {
            // Poles only rise above the baseline. Ground already within clearance
            // therefore needs no constraint, especially immediately at an approach.
            if (baseline.elevationAt(sample.distance) + TERRAIN_TOLERANCE >= sample.ground) continue;
            int result = Arrays.binarySearch(distances, sample.distance);
            int section = result < 0 ? -result-2 : Math.min(result,last-1);
            double fraction = (sample.distance-distances[section])/(distances[section+1]-distances[section]);
            double highest = interpolate(heights, section, fraction);
            if (highest + TERRAIN_TOLERANCE < sample.ground - 1e-9)
                throw new ClearanceFailure(String.format(Locale.ROOT,
                        "Auto fit cannot clear the ground %.1f m from the start. Ground %.2f m, highest reachable rail %.2f m. "
                                + "%s",
                        sample.distance, sample.ground, highest, useDeckGrades
                                ? "Check the ground approach, fixed heights and grade limit."
                                : "Adjust a fixed height or move or add a pole."));
            double target = Math.min(highest, sample.ground+CLEARANCE);
            if (useDeckGrades) {
                double original = interpolate(preferred, section, fraction);
                if (target <= original) continue;
                double share = (target - original) / (highest - original);
                for (int i = section; i <= section + 1; i++)
                    required[i] = Math.max(required[i], preferred[i] + share * (heights[i] - preferred[i]));
            } else {
                Constraint constraint = new Constraint(section, fraction, target);
                adjacent.get(section).add(constraint);
                adjacent.get(section+1).add(constraint);
            }
        }

        if (useDeckGrades) {
            // Propagate only the elevation needed to reach each raised point
            // at the grade limit. This leaves the rest of the profile low.
            for (int i = 1; i < last; i++)
                required[i] = Math.min(heights[i], Math.max(required[i],
                        required[i-1] - maxGrade * (distances[i] - distances[i-1])));
            for (int i = last-1; i > 0; i--)
                required[i] = Math.min(heights[i], Math.max(required[i],
                        required[i+1] - maxGrade * (distances[i+1] - distances[i])));
            return required;
        }

        // Start with a feasible profile and lower free poles toward the original survey.
        // Each change preserves the constraints on both sections touching that pole.
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < last; i++)
                if (!fixed[i]) lower(i, heights, distances, preferred, maxGrade, adjacent.get(i));
            for (int i = last-1; i > 0; i--)
                if (!fixed[i]) lower(i, heights, distances, preferred, maxGrade, adjacent.get(i));
        }
        return heights;
    }

    private static final class ClearanceFailure extends IllegalArgumentException {
        ClearanceFailure(String message) { super(message); }
    }

    private static void lower(int pole, double[] heights, double[] distances, double[] preferred, double maxGrade,
                              List<Constraint> samples) {
        double minimum = Math.max(preferred[pole],
                Math.max(heights[pole-1] - maxGrade*(distances[pole]-distances[pole-1]),
                        heights[pole+1] - maxGrade*(distances[pole+1]-distances[pole])));
        for (Constraint sample : samples) {
            double share = sample.section == pole ? 1-sample.fraction : sample.fraction;
            if (share < 1e-12) continue;
            double existing = interpolate(heights,sample.section,sample.fraction);
            minimum = Math.max(minimum, heights[pole]+(sample.minimum-existing)/share);
        }
        heights[pole] = Math.min(heights[pole],minimum);
    }

    private static double interpolate(double[] heights, int section, double fraction) {
        return heights[section]+(heights[section+1]-heights[section])*fraction;
    }

    private static final class Constraint {
        final int section;
        final double fraction, minimum;

        Constraint(int section, double fraction, double minimum) {
            this.section = section;
            this.fraction = fraction;
            this.minimum = minimum;
        }
    }
}
