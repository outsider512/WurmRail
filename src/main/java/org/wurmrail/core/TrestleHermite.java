package org.wurmrail.core;

import org.wurmrail.core.TrestleAlignment.Point;
import java.util.List;

/** One interpolating segment, parameterized externally by horizontal distance. */
final class TrestleHermite extends TrestleAlignment.Section {
    private static final int LENGTH_STEPS = 32;
    private static final double[] GAUSS_X = {0, .5384693101056831, .9061798459386640};
    private static final double[] GAUSS_W = {.5688888888888889, .4786286704993665, .2369268850561891};

    private final Point start, end;
    private final double[] first, middle, last;
    private final double[] chainage = new double[LENGTH_STEPS + 1];

    TrestleHermite(Point start, Point end, double[] incoming, double[] outgoing) {
        this.start = start;
        this.end = end;
        double x = end.x - start.x, y = end.y - start.y;
        double cosine = Math.max(-1, Math.min(1, dot(incoming, outgoing)));
        // The circular-arc handle length also works for unequal turns; radius is checked separately.
        double scale = 2 * Math.hypot(x, y) / (1 + Math.sqrt((1 + cosine) / 2));
        first = new double[]{scale * incoming[0], scale * incoming[1]};
        last = new double[]{scale * outgoing[0], scale * outgoing[1]};
        middle = new double[]{3 * x - first[0] - last[0], 3 * y - first[1] - last[1]};
    }

    boolean withinRadius(double minimum) {
        return checkRadius(first, middle, last, 1, (1 + 1e-6) / minimum, 0);
    }

    double peakCurvature() {
        double peak = 0;
        for (int i = 0; i <= LENGTH_STEPS; i++) {
            peak = Math.max(peak, curvatureAt(i / (double)LENGTH_STEPS));
        }
        return peak;
    }

    double curvatureAt(double t) {
        double u = 1 - t;
        double vx = derivative(t, 0), vy = derivative(t, 1);
        double ax = 2 * (u * (middle[0] - first[0]) + t * (last[0] - middle[0]));
        double ay = 2 * (u * (middle[1] - first[1]) + t * (last[1] - middle[1]));
        double speed = Math.hypot(vx, vy);
        return speed < 1e-8 ? Double.POSITIVE_INFINITY : Math.abs(vx * ay - vy * ax) / (speed * speed * speed);
    }

    Point planPoint(double t) {
        return new Point(coordinate(t, 0), coordinate(t, 1), 0);
    }

    private static boolean checkRadius(double[] a, double[] b, double[] c, double span,
                                       double limit, int depth) {
        double[] ab = blend(a, b), bc = blend(b, c), center = blend(ab, bc);
        double[] left = {2 * (b[0] - a[0]) / span, 2 * (b[1] - a[1]) / span};
        double[] right = {2 * (c[0] - b[0]) / span, 2 * (c[1] - b[1]) / span};
        if (tooSharp(a, left, limit) || tooSharp(center, blend(left, right), limit)
                || tooSharp(c, right, limit)) return false;

        // Bernstein bounds on |velocity × acceleration| and on speed. Subdivide
        // where the interval cannot prove the whole curve respects the radius.
        double numerator = Math.max(Math.max(Math.abs(cross(a, left)), Math.abs(cross(c, right))),
                Math.max(Math.abs((cross(a, right) + 2 * cross(b, left)) / 3),
                        Math.abs((2 * cross(b, right) + cross(c, left)) / 3)));
        double speed = Math.min(dot(a, center), Math.min(dot(b, center), dot(c, center))) / norm(center);
        if (speed > 0 && numerator <= limit * speed * speed * speed) return true;
        return depth < 22 && checkRadius(a, ab, center, span / 2, limit, depth + 1)
                && checkRadius(center, bc, c, span / 2, limit, depth + 1);
    }

    private static boolean tooSharp(double[] velocity, double[] acceleration, double limit) {
        double speed = norm(velocity);
        return speed <= 1e-8 || Math.abs(cross(velocity, acceleration)) > limit * speed * speed * speed;
    }

    @Override double length() {
        return chainage[LENGTH_STEPS];
    }

    void measure() {
        for (int i = 1; i <= LENGTH_STEPS; i++)
            chainage[i] = chainage[i - 1] + integrate((i - 1d) / LENGTH_STEPS, (double)i / LENGTH_STEPS);
    }

    @Override Point at(double distance, double z, double lateral) {
        double t = parameter(distance);
        double x = coordinate(t, 0), y = coordinate(t, 1);
        if (lateral == 0) return new Point(x, y, z);
        double[] tangent = unitTangent(t);
        return new Point(x - lateral * tangent[1], y + lateral * tangent[0], z);
    }

    @Override double[] tangent(double distance) {
        return unitTangent(parameter(distance));
    }

    private double[] unitTangent(double t) {
        double x = derivative(t, 0), y = derivative(t, 1), speed = Math.hypot(x, y);
        return new double[]{x / speed, y / speed};
    }

    private double coordinate(double t, int axis) {
        double from = axis == 0 ? start.x : start.y;
        double to = axis == 0 ? end.x : end.y;
        if (t == 0) return from;
        if (t == 1) return to;
        double reverse = 1 - t;
        return from + t * (t * (3 - 2 * t) * (to - from)
                + reverse * (reverse * first[axis] - t * last[axis]));
    }

    private double derivative(double t, int axis) {
        double reverse = 1 - t;
        return reverse * reverse * first[axis] + 2 * reverse * t * middle[axis] + t * t * last[axis];
    }

    private double speed(double t) {
        return Math.hypot(derivative(t, 0), derivative(t, 1));
    }

    private double integrate(double lo, double hi) {
        double mid = (lo + hi) / 2, half = (hi - lo) / 2;
        double sum = GAUSS_W[0] * speed(mid);
        for (int i = 1; i < GAUSS_X.length; i++)
            sum += GAUSS_W[i] * (speed(mid - half * GAUSS_X[i]) + speed(mid + half * GAUSS_X[i]));
        return sum * half;
    }

    private double distance(double t) {
        int i = Math.min(LENGTH_STEPS - 1, (int)(t * LENGTH_STEPS));
        return chainage[i] + integrate((double)i / LENGTH_STEPS, t);
    }

    private double parameter(double distance) {
        if (distance <= 0) return 0;
        if (distance >= length()) return 1;
        int i = java.util.Arrays.binarySearch(chainage, distance);
        if (i >= 0) return (double)i / LENGTH_STEPS;
        i = -i - 2;
        double origin = (double)i / LENGTH_STEPS, lo = origin, hi = (i + 1d) / LENGTH_STEPS;
        double target = distance - chainage[i];
        double t = origin + (hi - lo) * target / (chainage[i + 1] - chainage[i]);
        for (int step = 0; step < 24; step++) {
            double error = integrate(origin, t) - target;
            if (Math.abs(error) < 1e-10) break;
            if (error > 0) hi = t;
            else lo = t;
            double next = t - error / speed(t);
            t = next > lo && next < hi ? next : (lo + hi) / 2;
        }
        return t;
    }

    @Override void addGridCrossings(List<Double> crossings, boolean east) {
        int axis = east ? 0 : 1;
        double a = first[axis] - 2 * middle[axis] + last[axis];
        double b = 2 * (middle[axis] - first[axis]), c = first[axis];
        double[] limits = {0, 1, 1, 1};
        if (Math.abs(a) < 1e-12) {
            if (Math.abs(b) >= 1e-12) limits[1] = Math.max(0, Math.min(1, -c / b));
        } else if (b * b >= 4 * a * c) {
            double discriminant = Math.sqrt(b * b - 4 * a * c);
            double q = -.5 * (b + Math.copySign(discriminant, b));
            limits[1] = Math.max(0, Math.min(1, q / a));
            if (q != 0) limits[2] = Math.max(0, Math.min(1, c / q));
            java.util.Arrays.sort(limits);
        }
        for (int i = 1; i < limits.length; i++) {
            double begin = limits[i - 1], end = limits[i];
            double from = coordinate(begin, axis), to = coordinate(end, axis);
            if (Math.abs(to - from) < 1e-10) continue;
            double min = Math.min(from, to), max = Math.max(from, to);
            for (double boundary = Math.ceil(min / TrestleAlignment.TILE) * TrestleAlignment.TILE;
                 boundary <= max; boundary += TrestleAlignment.TILE) {
                double lo = begin, hi = end;
                for (int step = 0; step < 48; step++) {
                    double mid = (lo + hi) / 2;
                    if ((coordinate(mid, axis) < boundary) == (from < to)) lo = mid;
                    else hi = mid;
                }
                addCrossing(crossings, distance((lo + hi) / 2));
            }
        }
    }

    private static double[] blend(double[] a, double[] b) {
        return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
    }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1]; }
    private static double cross(double[] a, double[] b) { return a[0] * b[1] - a[1] * b[0]; }
    private static double norm(double[] a) { return Math.hypot(a[0], a[1]); }
}
