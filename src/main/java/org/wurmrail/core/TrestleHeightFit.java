package org.wurmrail.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
        if (overrides.stream().noneMatch(java.util.Objects::isNull)
                || ground.stream().noneMatch(sample -> baseline.elevationAt(sample.distance) + TERRAIN_TOLERANCE < sample.ground))
            return baseline;

        List<Double> poles = baseline.waypointDistances();
        int last = poles.size() + 1;
        double[] distances = new double[last + 1], preferred = new double[last + 1], heights = new double[last + 1];
        boolean[] fixed = new boolean[last + 1];
        Arrays.fill(heights, MAX_WURM_HEIGHT);
        for (int i = 0; i <= last; i++) {
            distances[i] = i == 0 ? 0 : i == last ? baseline.length : poles.get(i-1);
            preferred[i] = baseline.elevationAt(distances[i]);
            fixed[i] = i == 0 || i == last || overrides.get(i-1) != null;
            if (fixed[i]) heights[i] = preferred[i];
        }

        // A fixed height limits how high every other pole can rise at the configured grade.
        for (int i = 1; i < last; i++)
            if (!fixed[i]) heights[i] = Math.min(heights[i], heights[i-1] + maxGrade*(distances[i]-distances[i-1]));
        for (int i = last-1; i > 0; i--)
            if (!fixed[i]) heights[i] = Math.min(heights[i], heights[i+1] + maxGrade*(distances[i+1]-distances[i]));

        List<List<Constraint>> adjacent = new ArrayList<>();
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
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "Auto fit cannot clear the ground %.1f m from the start. Ground %.2f m, highest reachable rail %.2f m. "
                                + "Adjust a fixed height or move or add a pole.",
                        sample.distance, sample.ground, highest));
            Constraint constraint = new Constraint(section, fraction, Math.min(highest, sample.ground+CLEARANCE));
            adjacent.get(section).add(constraint);
            adjacent.get(section+1).add(constraint);
        }

        // Start with a feasible profile and lower free poles toward the original survey.
        // Each change preserves the constraints on both sections touching that pole.
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < last; i++)
                if (!fixed[i]) lower(i, heights, distances, preferred, maxGrade, adjacent.get(i));
            for (int i = last-1; i > 0; i--)
                if (!fixed[i]) lower(i, heights, distances, preferred, maxGrade, adjacent.get(i));
        }
        List<Double> fitted = new ArrayList<>();
        for (int i = 1; i < last; i++) fitted.add(heights[i]);
        return baseline.withWaypointElevations(fitted,maxGrade);
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
