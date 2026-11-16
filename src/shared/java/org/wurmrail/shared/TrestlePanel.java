package org.wurmrail.shared;

import java.nio.ByteBuffer;
import java.util.Base64;

/** The rendered and walkable ribbon within one trestle marker's tile. */
public final class TrestlePanel {
    public static final int INTERVALS = 16;
    public static final String MODEL_PREFIX = "model.wurmrail.trestlecurve.";
    private static final int BYTES = (INTERVALS + 1) * 8;
    private static final double ANGLE_UNIT = 2 * Math.PI / 65536;
    private final byte[] encoded;
    private final float[][] centers = new float[INTERVALS + 1][3];
    private final float[][] sides = new float[INTERVALS + 1][2];
    private final float[][] left = new float[INTERVALS + 1][];
    private final float[][] right = new float[INTERVALS + 1][];

    private TrestlePanel(byte[] encoded) {
        this.encoded = encoded;
        ByteBuffer data = ByteBuffer.wrap(encoded);
        for (int row = 0; row <= INTERVALS; row++) {
            for (int axis = 0; axis < 3; axis++) centers[row][axis] = data.getShort() / 10000f;
            double angle = data.getShort() * ANGLE_UNIT;
            sides[row][0] = (float)-Math.sin(angle);
            sides[row][1] = (float)Math.cos(angle);
            left[row] = edge(row, -TrestleDeckSurface.HALF_WIDTH);
            right[row] = edge(row, TrestleDeckSurface.HALF_WIDTH);
        }
    }

    /** Each row contains the local XYZ centre and a horizontal tangent angle in radians. */
    public static TrestlePanel of(double[][] rows) {
        ByteBuffer data = ByteBuffer.allocate(BYTES);
        for (double[] row : rows) {
            for (int axis = 0; axis < 3; axis++) {
                long coordinate = Math.round(row[axis] * 10000);
                if (coordinate < Short.MIN_VALUE || coordinate > Short.MAX_VALUE)
                    throw new IllegalArgumentException("Trestle panel exceeds its tile bounds.");
                data.putShort((short)coordinate);
            }
            data.putShort((short)Math.round(row[3] / ANGLE_UNIT));
        }
        return new TrestlePanel(data.array());
    }

    public String modelName() {
        return MODEL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encoded) + ".";
    }

    public static TrestlePanel fromModel(String model) {
        if (model == null || !model.startsWith(MODEL_PREFIX)) return null;
        int end = model.indexOf('.', MODEL_PREFIX.length());
        if (end != MODEL_PREFIX.length() + (BYTES * 4 + 2) / 3) return null;
        try {
            byte[] data = Base64.getUrlDecoder().decode(model.substring(MODEL_PREFIX.length(), end));
            return data.length == BYTES ? new TrestlePanel(data) : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public float[] vertex(float fraction, float lateral) {
        float f = Math.max(0, Math.min(INTERVALS, fraction * INTERVALS));
        int row = Math.min(INTERVALS - 1, (int)f);
        float blend = f - row;
        float[] a = edge(row, lateral), b = edge(row + 1, lateral);
        return new float[]{a[0] + blend * (b[0] - a[0]), a[1] + blend * (b[1] - a[1]),
                a[2] + blend * (b[2] - a[2])};
    }

    public float height(float x, float y, float feet) {
        float height = Float.NaN;
        for (int row = 0; row < INTERVALS; row++) {
            height = TrestleDeckSurface.higher(height, triangle(x, y, feet, right[row], right[row + 1], left[row + 1]));
            height = TrestleDeckSurface.higher(height, triangle(x, y, feet, right[row], left[row + 1], left[row]));
        }
        return height;
    }

    private float[] edge(int row, float lateral) {
        return new float[]{centers[row][0] + sides[row][0] * lateral,
                centers[row][1] + sides[row][1] * lateral, centers[row][2]};
    }

    private static float triangle(float x, float y, float feet, float[] a, float[] b, float[] c) {
        double bx = b[0] - a[0], by = b[1] - a[1], cx = c[0] - a[0], cy = c[1] - a[1];
        double px = x - a[0], py = y - a[1], area = bx * cy - by * cx;
        if (Math.abs(area) < 1e-9) return Float.NaN;
        double u = (px * cy - py * cx) / area, v = (bx * py - by * px) / area;
        // Tolerate submillimetre world-coordinate and native position rounding at seams.
        double tolerance = .0005 / Math.abs(area);
        if (u < -tolerance * Math.hypot(cx, cy) || v < -tolerance * Math.hypot(bx, by)
                || 1 - u - v < -tolerance * Math.hypot(cx - bx, cy - by)) return Float.NaN;
        float z = (float)(a[2] + u * (b[2] - a[2]) + v * (c[2] - a[2]));
        return z <= feet + TrestleDeckSurface.STEP_UP ? z : Float.NaN;
    }
}
