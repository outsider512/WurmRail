package org.wurmrail.shared;

/** Walking surface of the same timber deck that the client renders. */
public final class TrestleDeckSurface {
    private static final String MODEL_PREFIX = "model.wurmrail.trestledeck.";
    private static final float HALF_WIDTH = .96f;
    private static final float DIAGONAL_COMPONENT = (float)(1 / Math.sqrt(2));
    private static final float CORNER_OVERHANG = HALF_WIDTH * DIAGONAL_COMPONENT;
    private static final float STEP_UP = .75f;
    private static final int[] AXIS_X = {0, 1, 1, 1};
    private static final int[] AXIS_Y = {-1, -1, 0, 1};

    private TrestleDeckSurface() { }

    @FunctionalInterface
    public interface TileHeight {
        float sample(int tileX, int tileY, float x, float y, float feet);
    }

    public static float height(float x, float y, float feet, float centerX, float centerY, float centerZ,
                               int axis, int grade) {
        float unit = (axis & 1) == 0 ? 1f : DIAGONAL_COMPONENT;
        float dx = x - centerX, dy = y - centerY;
        float along = dx * AXIS_X[axis] + dy * AXIS_Y[axis];
        float across = dy * AXIS_X[axis] - dx * AXIS_Y[axis];
        if (Math.abs(along) > ((axis & 1) == 0 ? 2f : 4f) || Math.abs(across * unit) > HALF_WIDTH)
            return Float.NaN;
        float surface = centerZ + along * unit * (grade / 1000000f);
        return surface <= feet + STEP_UP ? surface : Float.NaN;
    }

    /** Include the neighboring tile where a diagonal plank overhangs a corner. */
    public static float find(float x, float y, float feet, TileHeight lookup) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(feet)) return Float.NaN;
        int tileX = (int)Math.floor(x / 4), tileY = (int)Math.floor(y / 4);
        int nextX = neighbor(tileX, x), nextY = neighbor(tileY, y);
        float height = lookup.sample(tileX, tileY, x, y, feet);
        if (nextX != tileX) height = higher(height, lookup.sample(nextX, tileY, x, y, feet));
        if (nextY != tileY) height = higher(height, lookup.sample(tileX, nextY, x, y, feet));
        if (nextX != tileX && nextY != tileY)
            height = higher(height, lookup.sample(nextX, nextY, x, y, feet));
        return height;
    }

    public static float higher(float first, float second) {
        return Float.isNaN(first) ? second : Float.isNaN(second) ? first : Math.max(first, second);
    }

    public static int modelAxis(String model) {
        int start = MODEL_PREFIX.length();
        if (!model.startsWith(MODEL_PREFIX) || model.length() <= start + 2 || model.charAt(start + 1) != '.')
            return -1;
        int axis = model.charAt(start) - '0';
        return axis >= 0 && axis < 4 ? axis : -1;
    }

    public static int modelGrade(String model) {
        int start = MODEL_PREFIX.length() + 2;
        int end = model.indexOf('.', start);
        return Integer.parseInt(model.substring(start, end));
    }

    public static float slopeX(int axis, int grade) {
        return AXIS_X[axis] * component(axis, grade);
    }

    public static float slopeY(int axis, int grade) {
        return AXIS_Y[axis] * component(axis, grade);
    }

    private static float component(int axis, int grade) {
        return (grade / 1000000f) * ((axis & 1) == 0 ? 1f : DIAGONAL_COMPONENT);
    }

    private static int neighbor(int tile, float position) {
        float offset = position - tile * 4;
        if (offset <= CORNER_OVERHANG) return tile - 1;
        if (offset >= 4 - CORNER_OVERHANG) return tile + 1;
        return tile;
    }
}
