package org.wurmrail.core;

import org.wurmrail.shared.TrestlePanel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.awt.geom.Line2D;

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
    private final List<Section> sections;
    private final double[] origins;
    private final boolean waypointProfile;
    private final List<Point> surveyPoints;
    private final double surveyRadius;
    final int startHeading, endHeading;
    public final double length, gradient;
    public final boolean curved;

    private TrestleAlignment(Point start, Point end, double length, List<Section> sections, boolean curved,
                             List<Point> surveyPoints, double surveyRadius, int startHeading, int endHeading) {
        this(start,end,length,sections,curved,surveyPoints,surveyRadius,startHeading,endHeading,false);
    }

    private TrestleAlignment(Point start, Point end, double length, List<Section> sections, boolean curved,
                             List<Point> surveyPoints, double surveyRadius, int startHeading, int endHeading,
                             boolean waypointProfile) {
        this.start = start;
        this.end = end;
        this.sections = sections;
        this.origins = new double[sections.size() + 1];
        for (int i = 0; i < sections.size(); i++) origins[i + 1] = origins[i] + sections.get(i).length();
        this.length = length;
        this.gradient = (end.z - start.z) / length;
        this.curved = curved;
        this.surveyPoints = Collections.unmodifiableList(new ArrayList<>(surveyPoints));
        this.surveyRadius = surveyRadius;
        this.startHeading = startHeading;
        this.endHeading = endHeading;
        this.waypointProfile = waypointProfile;
    }

    public static TrestleAlignment straight(Point start, Point end, double maxLength, double maxGradient) {
        double length = Math.hypot(end.x - start.x, end.y - start.y);
        return checked(start, end, length, Collections.singletonList(Section.line(start, end)), false,
                maxLength, maxGradient, Collections.emptyList(), 0);
    }

    /** Reconstruct the tangent arcs used by existing saved trestles. */
    public static TrestleAlignment rounded(List<Point> points, double radius, double maxLength, double maxGradient) {
        if (points.size() < 3) throw new IllegalArgumentException("Mark an intermediate range pole for a curve.");
        if (!Double.isFinite(radius) || radius < TILE)
            throw new IllegalArgumentException("The curve radius must be at least one tile.");
        int legs = points.size() - 1;
        double[] dx = new double[legs], dy = new double[legs], run = new double[legs];
        double[] trims = new double[points.size()], turns = new double[points.size()];
        for (int i = 0; i < legs; i++) {
            Point from = points.get(i), to = points.get(i + 1);
            double x = to.x - from.x, y = to.y - from.y;
            run[i] = Math.hypot(x, y);
            if (run[i] <= 1e-9) throw new IllegalArgumentException("Survey points must be at different positions.");
            dx[i] = x / run[i];
            dy[i] = y / run[i];
        }
        boolean curved = false;
        for (int i = 1; i < legs; i++) {
            double turn = Math.atan2(dx[i-1]*dy[i]-dy[i-1]*dx[i], dx[i-1]*dx[i]+dy[i-1]*dy[i]);
            if (Math.abs(turn) < 1e-9) continue;
            if (Math.PI - Math.abs(turn) < 1e-9)
                throw new IllegalArgumentException("The route doubles back at intermediate pole " + i + ".");
            turns[i] = turn;
            trims[i] = radius * Math.tan(Math.abs(turn) * .5);
            curved = true;
        }
        for (int i = 0; i < legs; i++) {
            if (trims[i] + trims[i+1] > run[i] + 1e-9)
                throw new IllegalArgumentException("The " + radius / TILE + "-tile curve radius needs more room between survey points "
                        + (i+1) + " and " + (i+2) + ". Move a pole or choose a gentler turn.");
        }
        Point start = points.get(0), end = points.get(legs), previous = start;
        List<Section> sections = new ArrayList<>();
        double length = 0;
        for (int i = 1; i <= legs; i++) {
            Point corner = points.get(i);
            Point entry = shifted(corner, -dx[i-1]*trims[i], -dy[i-1]*trims[i]);
            Section line = Section.line(previous, entry);
            if (line.length() > 1e-9) { sections.add(line); length += line.length(); }
            if (turns[i] == 0) { previous = entry; continue; }
            double side = Math.signum(turns[i]);
            Point center = shifted(entry, -dy[i-1]*radius*side, dx[i-1]*radius*side);
            Section arc = Section.arc(entry, center, radius, turns[i]);
            sections.add(arc);
            length += arc.length();
            previous = shifted(corner, dx[i]*trims[i], dy[i]*trims[i]);
        }
        TrestleAlignment alignment = checked(start,end,length,sections,curved,maxLength,maxGradient,points,radius);
        alignment.rejectCrossing();
        return alignment;
    }

    /**
     * Interpolate each marked pole position, keeping the ground rails fixed at
     * their compass headings. Vertical grade is measured along the resulting route.
     */
    public static TrestleAlignment waypoints(List<Point> points, double radius, double maxLength,
                                              double maxGradient, int startHeading, int endHeading) {
        if (points.size() < 3) throw new IllegalArgumentException("Mark an intermediate range pole for a curve.");
        if (!Double.isFinite(radius) || radius < 3 * TILE)
            throw new IllegalArgumentException("The minimum trestle curve radius is 3 tiles.");
        int legs = points.size() - 1;
        double[][] tangents = new double[points.size()][], directions = new double[legs][];
        double[] chords = new double[legs];
        double lowerLength = 0;
        for (int i = 0; i < legs; i++) {
            Point a = points.get(i), b = points.get(i + 1);
            double dx = b.x - a.x, dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (length < 1e-8) throw new IllegalArgumentException("Survey points must be at different positions.");
            lowerLength += length;
            chords[i] = Math.sqrt(length);
            directions[i] = new double[]{dx / length, dy / length};
        }
        if (lowerLength > maxLength) throw new IllegalArgumentException("The surveyed crossing exceeds " + maxLength / TILE + " tiles.");
        tangents[0] = compassTangent(startHeading);
        tangents[legs] = compassTangent(endHeading);
        for (int i = 1; i < legs; i++) {
            double[] before = directions[i - 1], after = directions[i];
            double x = before[0] * chords[i] + after[0] * chords[i - 1];
            double y = before[1] * chords[i] + after[1] * chords[i - 1];
            double length = Math.hypot(x, y);
            if (Math.hypot(before[0] + after[0], before[1] + after[1]) < 1e-8)
                throw new IllegalArgumentException("The route doubles back at pole " + i + ". Place several poles around the turn.");
            tangents[i] = new double[]{x / length, y / length};
        }
        List<Section> sections = new ArrayList<>();
        double length = 0;
        for (int i = 0; i < legs; i++) {
            TrestleHermite section = new TrestleHermite(points.get(i), points.get(i + 1), tangents[i], tangents[i + 1]);
            if (!section.withinRadius(radius))
                throw new IllegalArgumentException("The curve between " + markName(i, legs) + " and " + markName(i + 1, legs)
                        + " is too sharp for the " + (int)(radius / TILE)
                        + "-tile minimum radius. Move a pole or place more poles around the turn.");
            section.measure();
            length += section.length();
            sections.add(section);
        }
        Point start = points.get(0), end = points.get(legs);
        checkLimits(start, end, length, maxLength, maxGradient);
        TrestleAlignment alignment = new TrestleAlignment(start, end, length, sections, true, points, radius,
                startHeading, endHeading);
        alignment.rejectCrossing();
        return alignment;
    }

    private static String markName(int mark, int last) {
        return mark == 0 ? "the start" : mark == last ? "the destination" : "pole " + mark;
    }

    private static double[] compassTangent(int heading) {
        if (heading < 0 || heading > 7) throw new IllegalArgumentException("Invalid trestle approach heading.");
        double angle = heading * Math.PI / 4;
        double x = Math.rint(Math.sin(angle)), y = -Math.rint(Math.cos(angle));
        double length = Math.hypot(x, y);
        return new double[]{x / length, y / length};
    }

    public List<Double> waypointDistances() {
        List<Double> distances = new ArrayList<>();
        if (startHeading >= 0)
            for (int i = 1; i < sections.size(); i++) distances.add(origins[i]);
        return Collections.unmodifiableList(distances);
    }

    /** Blank heights inherit the grade between the nearest explicit elevations. */
    public TrestleAlignment withWaypointElevations(List<Double> elevations, double maxGradient) {
        if (startHeading < 0 || elevations.size() != surveyPoints.size() - 2)
            throw new IllegalArgumentException("The elevations must match the intermediate survey poles.");
        if (elevations.stream().allMatch(java.util.Objects::isNull)) {
            checkLimits(start, end, length, length, maxGradient);
            return this;
        }
        List<Point> marks = new ArrayList<>(surveyPoints);
        int last = marks.size() - 1, previous = 0;
        double previousZ = start.z;
        for (int mark = 1; mark <= last; mark++) {
            Double height = mark == last ? Double.valueOf(end.z) : elevations.get(mark - 1);
            if (height == null) continue;
            double run = origins[mark] - origins[previous];
            if (!Double.isFinite(height) || height < -3277 || height > 3277)
                throw new IllegalArgumentException("Enter a finite track elevation within the Wurm height range.");
            double slope = (height - previousZ) / run;
            if (Math.abs(height - previousZ) > maxGradient * run + 1e-9)
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "Grade from %s to %s is %+.2f%%. The limit is %.1f%%. Adjust the track elevations.",
                        markName(previous,last), markName(mark,last), slope * 100, maxGradient * 100));
            for (int i = previous + 1; i <= mark; i++) {
                Point point = marks.get(i);
                marks.set(i, new Point(point.x, point.y, i == mark ? height : previousZ + (origins[i]-origins[previous])*slope));
            }
            previous = mark;
            previousZ = height;
        }
        return new TrestleAlignment(start,end,length,sections,curved,marks,surveyRadius,startHeading,endHeading,true);
    }

    public boolean hasWaypointElevations() { return waypointProfile; }

    public double sectionGradient(int section) {
        if (!waypointProfile) return gradient;
        return (surveyPoints.get(section+1).z - surveyPoints.get(section).z)
                / (origins[section+1] - origins[section]);
    }

    double maximumAbsoluteGradient() {
        double maximum = Math.abs(gradient);
        if (waypointProfile)
            for (int i = 0; i < sections.size(); i++) maximum = Math.max(maximum, Math.abs(sectionGradient(i)));
        return maximum;
    }

    public double elevationAt(double distance) {
        if (distance == length) return end.z;
        if (!waypointProfile) return start.z + distance * gradient;
        int section = sectionAt(distance);
        return surveyPoints.get(section).z + (distance - origins[section]) * sectionGradient(section);
    }

    private static Point shifted(Point point, double x, double y) {
        return new Point(point.x+x, point.y+y, point.z);
    }

    private static TrestleAlignment checked(Point start, Point end, double length, List<Section> sections,
                                            boolean curved, double maxLength, double maxGradient,
                                            List<Point> surveyPoints,double surveyRadius) {
        checkLimits(start, end, length, maxLength, maxGradient);
        return new TrestleAlignment(start,end,length,sections,curved,surveyPoints,surveyRadius,-1,-1);
    }

    private static void checkLimits(Point start, Point end, double length, double maxLength, double maxGradient) {
        if (length < TILE) throw new IllegalArgumentException("Choose survey endpoints at least one tile apart.");
        if (length > maxLength) throw new IllegalArgumentException("The surveyed crossing exceeds " + maxLength / TILE + " tiles.");
        // Decimal terrain elevations can round in double precision. Allow
        // sub-nanometre rise error at the inclusive grade boundary.
        if (Math.abs(end.z - start.z) > maxGradient * length + 1e-9)
            throw new IllegalArgumentException("The proposed gradient exceeds " + maxGradient * 100 + "%. Adjust an endpoint elevation.");
    }

    List<Point> surveyPoints() { return surveyPoints; }
    double surveyRadius() { return surveyRadius; }

    /** The deck interval owned by one tile, in metres along the original survey. */
    public static final class TileSection {
        public final int x,y;
        public final double entry,exit;

        private TileSection(int x,int y,double entry,double exit) {
            this.x=x;
            this.y=y;
            this.entry=entry;
            this.exit=exit;
        }

        public double center() { return (entry+exit)*.5; }
    }

    /** Split the alignment at actual grid crossings, including short corner crossings. */
    public List<TileSection> tileSections() {
        List<TileSection> tiles = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        double origin=0;
        for (Section section : sections) {
            List<Double> crossings = new ArrayList<>();
            crossings.add(0d);
            section.addGridCrossings(crossings,true);
            section.addGridCrossings(crossings,false);
            crossings.add(section.length());
            Collections.sort(crossings);
            double previous=0;
            for (double crossing : crossings) {
                if (crossing-previous<=1e-8) continue;
                Point middle=section.at((previous+crossing)*.5,0,0);
                int x=(int)Math.floor(middle.x/TILE), y=(int)Math.floor(middle.y/TILE);
                TileSection last=tiles.isEmpty()?null:tiles.get(tiles.size()-1);
                if (last!=null && last.x==x && last.y==y) {
                    tiles.set(tiles.size()-1,new TileSection(x,y,last.entry,origin+crossing));
                } else {
                    long key=(long)x<<32 | (y&0xffffffffL);
                    if (!visited.add(key))
                        throw new IllegalArgumentException("The surveyed deck returns to tile "+x+", "+y+". Move a pole.");
                    tiles.add(new TileSection(x,y,origin+previous,origin+crossing));
                }
                previous=crossing;
            }
            origin+=section.length();
        }
        return Collections.unmodifiableList(tiles);
    }

    /** Horizontal unit tangent, in the direction in which the survey was marked. */
    double[] tangentAt(double distance) {
        int index = sectionAt(distance);
        return sections.get(index).tangent(distance - origins[index]);
    }

    public Point at(double distance) {
        return offsetAt(distance,0);
    }

    public TrestlePanel panel(TileSection tile) {
        double x = tile.x * TILE + TILE / 2, y = tile.y * TILE + TILE / 2;
        double z = (float)at(tile.center()).z;
        double[][] rows = new double[TrestlePanel.INTERVALS + 1][4];
        double pole = Double.NaN;
        int poleIndex = Arrays.binarySearch(origins, tile.entry);
        poleIndex = poleIndex < 0 ? -poleIndex - 1 : poleIndex + 1;
        if (startHeading >= 0 && poleIndex < sections.size() && origins[poleIndex] < tile.exit) pole = origins[poleIndex];
        int middle = Double.isNaN(pole) ? TrestlePanel.INTERVALS
                : Math.max(1, Math.min(TrestlePanel.INTERVALS - 1,
                        (int)Math.round((pole - tile.entry) / (tile.exit - tile.entry) * TrestlePanel.INTERVALS)));
        for (int i = 0; i < rows.length; i++) {
            double distance = tile.entry + (tile.exit - tile.entry) * i / TrestlePanel.INTERVALS;
            if (!Double.isNaN(pole)) {
                if (i < middle) distance = tile.entry + (pole - tile.entry) * i / middle;
                else distance = pole + (tile.exit - pole) * (i - middle) / (TrestlePanel.INTERVALS - middle);
            }
            if (i == TrestlePanel.INTERVALS) distance = tile.exit;
            Point point = at(distance);
            double[] tangent = tangentAt(distance);
            rows[i] = new double[]{point.x - x, point.y - y, point.z - z, Math.atan2(tangent[1], tangent[0])};
        }
        return TrestlePanel.of(rows);
    }

    public float headingAt(double distance) {
        double[] tangent = tangentAt(distance);
        return (float)((Math.toDegrees(Math.atan2(tangent[0], -tangent[1])) + 360) % 360);
    }

    /** A point across the deck, measured perpendicular to the local direction of travel. */
    public Point offsetAt(double distance,double lateral) {
        if (!Double.isFinite(distance) || distance < 0 || distance > length)
            throw new IllegalArgumentException("Distance is outside the surveyed alignment.");
        if (lateral == 0 && distance == 0) return start;
        if (lateral == 0 && distance == length) return end;
        double elevation = elevationAt(distance);
        int index = sectionAt(distance);
        return sections.get(index).at(distance - origins[index],elevation,lateral);
    }

    private int sectionAt(double distance) {
        int index = Arrays.binarySearch(origins, distance);
        return index < 0 ? Math.max(0, -index - 2) : Math.min(index, sections.size() - 1);
    }

    private void rejectCrossing() {
        int count = (int)Math.ceil(length*2);
        Point[] samples = new Point[count+1];
        for (int i = 0; i <= count; i++) samples[i] = at(length*((double)i/count));
        for (int i = 0; i < count; i++) {
            Point a = samples[i], b = samples[i+1];
            for (int j = i+2; j < count; j++) {
                Point c = samples[j], d = samples[j+1];
                if (Line2D.linesIntersect(a.x,a.y,b.x,b.y,c.x,c.y,d.x,d.y))
                    throw new IllegalArgumentException("The alignment crosses itself. Move an intermediate pole.");
            }
        }
    }

    abstract static class Section {
        abstract double length();
        abstract Point at(double distance, double z, double lateral);
        abstract double[] tangent(double distance);
        abstract void addGridCrossings(List<Double> crossings, boolean east);

        void addCrossing(List<Double> crossings, double distance) {
            if (distance > 1e-8 && distance < length() - 1e-8) crossings.add(distance);
        }

        static Section line(Point start, Point end) {
            return new CircularSection(start, end, 0, 0, 0, Math.hypot(end.x-start.x, end.y-start.y));
        }

        static Section arc(Point entry, Point center, double radius, double sweep) {
            return new CircularSection(entry, center, radius, Math.atan2(entry.y-center.y,entry.x-center.x),
                    sweep, radius*Math.abs(sweep));
        }
    }

    private static final class CircularSection extends Section {
        final Point start, endOrCenter;
        final double radius, angle, sweep, length;

        private CircularSection(Point start, Point endOrCenter, double radius, double angle, double sweep, double length) {
            this.start = start;
            this.endOrCenter = endOrCenter;
            this.radius = radius;
            this.angle = angle;
            this.sweep = sweep;
            this.length = length;
        }

        @Override double length() { return length; }

        @Override Point at(double offset, double z, double lateral) {
            double fraction = offset / length;
            if (radius == 0) {
                double dx = endOrCenter.x-start.x, dy = endOrCenter.y-start.y;
                return new Point(start.x+dx*fraction-dy*lateral/length,
                        start.y+dy*fraction+dx*lateral/length,z);
            }
            double heading = angle+sweep*fraction;
            double offsetRadius = radius-Math.signum(sweep)*lateral;
            return new Point(endOrCenter.x+offsetRadius*Math.cos(heading),
                    endOrCenter.y+offsetRadius*Math.sin(heading),z);
        }

        @Override double[] tangent(double offset) {
            if (radius==0) return new double[]{(endOrCenter.x-start.x)/length,(endOrCenter.y-start.y)/length};
            double heading=angle+sweep*(offset/length), direction=Math.signum(sweep);
            return new double[]{-Math.sin(heading)*direction,Math.cos(heading)*direction};
        }

        @Override void addGridCrossings(List<Double> crossings,boolean east) {
            double from=east?start.x:start.y, to=east?endOrCenter.x:endOrCenter.y;
            double low=radius==0?Math.min(from,to):to-radius;
            double high=radius==0?Math.max(from,to):to+radius;
            if (low==high) return;
            for (long tile=(long)Math.ceil(low/TILE);tile*TILE<=high;tile++) {
                double boundary=tile*TILE;
                if (radius==0) {
                    addCrossing(crossings,(boundary-from)/(to-from)*length);
                } else {
                    double coordinate=Math.max(-1,Math.min(1,(boundary-to)/radius));
                    double heading=east?Math.acos(coordinate):Math.asin(coordinate);
                    addArcCrossing(crossings,heading);
                    addArcCrossing(crossings,east?-heading:Math.PI-heading);
                }
            }
        }

        private void addArcCrossing(List<Double> crossings,double heading) {
            double delta=(heading-angle)*Math.signum(sweep);
            double circle=2*Math.PI;
            delta-=Math.floor(delta/circle)*circle;
            addCrossing(crossings,delta*radius);
        }

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
