package org.wurmrail.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Survey coordinates and chainage in metres, independent of the terrain mesh. */
public final class TrestleAlignment {
    public static final double TILE = 4.0;

    public static final class Point {
        public final double x, y, z;

        public Point(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Survey coordinates and elevation must be finite.");
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final Point start, end;
    public final double length, gradient;

    private TrestleAlignment(Point start, Point end, double length) {
        this.start = start;
        this.end = end;
        this.length = length;
        this.gradient = (end.z - start.z) / length;
    }

    public static TrestleAlignment straight(Point start, Point end, double maxLength, double maxGradient) {
        double length = Math.hypot(end.x - start.x, end.y - start.y);
        if (length < TILE) throw new IllegalArgumentException("Choose survey endpoints at least one tile apart.");
        if (length > maxLength) throw new IllegalArgumentException("The surveyed crossing exceeds " + maxLength / TILE + " tiles.");
        TrestleAlignment alignment = new TrestleAlignment(start, end, length);
        // Decimal terrain elevations can round in double precision. Allow
        // sub-nanometre rise error at the inclusive grade boundary.
        if (Math.abs(end.z - start.z) > maxGradient * length + 1e-9)
            throw new IllegalArgumentException("The proposed gradient exceeds " + maxGradient * 100 + "%. Adjust an endpoint elevation.");
        return alignment;
    }

    public Point at(double distance) {
        if (!Double.isFinite(distance) || distance < 0 || distance > length)
            throw new IllegalArgumentException("Distance is outside the surveyed alignment.");
        double fraction = distance / length;
        return new Point(start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction, start.z + (end.z - start.z) * fraction);
    }

    /** Even spacing, with neither bent placed directly on an approach. */
    public List<Double> supportDistances(double spacing) {
        if (!Double.isFinite(spacing) || spacing < TILE)
            throw new IllegalArgumentException("Timber bents must be at least one tile apart.");
        int count = (int) Math.ceil(length / spacing);
        List<Double> distances = new ArrayList<>(count);
        for (int index = 0; index < count; index++) distances.add(length * (index + 0.5) / count);
        return Collections.unmodifiableList(distances);
    }
}
