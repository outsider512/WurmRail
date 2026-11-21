package org.wurmrail;

import org.wurmrail.core.TrestleAlignment;
import org.wurmrail.core.TrestleAlignment.Point;
import org.wurmrail.core.TrestleAlignment.RadiusFailure;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TreeSet;

/** Projects the surveyed centreline north-up for the native planning window. */
final class TrestleSurveyMap {
    static final int MAX_ZOOM = 5;
    private TrestleSurveyMap() { }

    static void appendPayload(StringBuilder label, List<Point> marks, TrestleAlignment alignment,
                              int zoom, int focus, RadiusFailure issue) {
        List<Point> route = new ArrayList<>();
        if (alignment != null) {
            TreeSet<Double> distances = new TreeSet<>(alignment.waypointDistances());
            int samples = Math.min(320, (int)Math.ceil(alignment.length / 2));
            for (int i = 0; i <= samples; i++) distances.add(alignment.length * ((double)i / samples));
            route.add(marks.get(0));
            for (double distance : distances) route.add(alignment.at(distance));
            route.add(marks.get(marks.size() - 1));
        }
        double left = Double.POSITIVE_INFINITY, right = Double.NEGATIVE_INFINITY;
        double top = left, bottom = right;
        List<Point> footprint = new ArrayList<>(route.isEmpty() ? marks : route);
        if (issue != null) footprint.addAll(issue.preview);
        for (Point point : footprint) {
            left = Math.min(left, point.x);
            right = Math.max(right, point.x);
            top = Math.min(top, point.y);
            bottom = Math.max(bottom, point.y);
        }
        Point centre = null;
        if (zoom > 0 && focus > 0)
            centre = focus <= marks.size() ? marks.get(focus-1) : issue == null ? null : issue.tightest;
        double x = centre == null ? (left+right)/2 : centre.x;
        double y = centre == null ? (top+bottom)/2 : centre.y;
        double scale = Math.min(448 / Math.max(4, right-left), 144 / Math.max(4, bottom-top))
                * Math.pow(1.5,zoom);
        ByteBuffer data = ByteBuffer.allocate(4 + (marks.size()+route.size())*4);
        data.putShort((short)route.size()).putShort((short)marks.size());
        writePoints(data, route, x, y, scale);
        writePoints(data, marks, x, y, scale);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        label.append("trestlemap=\"").append(encoder.encodeToString(data.array())).append("\";");
        if (issue != null) {
            ByteBuffer diagnostic = ByteBuffer.allocate(2 + 4*(issue.preview.size()+1));
            diagnostic.putShort((short)issue.section);
            writePoints(diagnostic, java.util.Collections.singletonList(issue.tightest), x, y, scale);
            writePoints(diagnostic, issue.preview, x, y, scale);
            label.append("trestlemapissue=\"").append(encoder.encodeToString(diagnostic.array())).append("\";");
        }
    }

    private static void writePoints(ByteBuffer data, List<Point> points, double x, double y, double scale) {
        for (Point point : points)
            data.putShort((short)Math.round(256+(point.x-x)*scale))
                    .putShort((short)Math.round(96+(point.y-y)*scale));
    }
}
