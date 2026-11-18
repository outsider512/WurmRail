package org.wurmrail.shared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A server-timed set of vehicle poses committed in one movement update. */
public final class RailConsistFrame {
    public static final String CHANNEL = "wurmrail.consist.v1";
    public static final int MAX_POSES = 64;
    private static final int HEADER_BYTES = Long.BYTES + Short.BYTES;
    private static final int POSE_BYTES = Long.BYTES + 4 * Float.BYTES + Byte.BYTES;

    public final long serverNanos;
    public final List<Pose> poses;

    private RailConsistFrame(long serverNanos, List<Pose> poses) {
        this.serverNanos = serverNanos;
        this.poses = Collections.unmodifiableList(poses);
    }

    public static final class Pose {
        public final long id;
        public final float x, y, z, yaw;
        public final int layer;

        public Pose(long id, float x, float y, float z, float yaw, int layer) {
            if (id <= 0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(yaw) || (layer != 0 && layer != -1)) {
                throw new IllegalArgumentException("Invalid rail vehicle pose");
            }
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.layer = layer;
        }
    }

    public static ByteBuffer encode(long serverNanos, List<Pose> poses) {
        int count = poses.size();
        if (count < 1 || count > MAX_POSES) {
            throw new IllegalArgumentException("Invalid rail pose count");
        }
        ByteBuffer payload = ByteBuffer.allocate(HEADER_BYTES + count * POSE_BYTES);
        payload.putLong(serverNanos).putShort((short) count);
        for (Pose pose : poses) {
            payload.putLong(pose.id).putFloat(pose.x).putFloat(pose.y).putFloat(pose.z)
                    .putFloat(pose.yaw).put((byte) pose.layer);
        }
        payload.flip();
        return payload;
    }

    /** Validates and decodes exactly one payload without changing the source cursor. */
    public static RailConsistFrame decode(ByteBuffer payload) {
        ByteBuffer data = payload.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (data.remaining() < HEADER_BYTES) {
            throw new IllegalArgumentException("Incomplete rail frame header");
        }
        long serverNanos = data.getLong();
        int count = Short.toUnsignedInt(data.getShort());
        if (count < 1 || count > MAX_POSES || data.remaining() != count * POSE_BYTES) {
            throw new IllegalArgumentException("Invalid rail frame length");
        }
        List<Pose> poses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            poses.add(new Pose(data.getLong(), data.getFloat(), data.getFloat(), data.getFloat(),
                    data.getFloat(), data.get()));
        }
        return new RailConsistFrame(serverNanos, poses);
    }
}
