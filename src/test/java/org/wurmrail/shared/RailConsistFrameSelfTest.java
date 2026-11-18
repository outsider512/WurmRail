package org.wurmrail.shared;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;

/** The client and server share a bounded, byte-exact consist frame. */
public final class RailConsistFrameSelfTest {
    public static void main(String[] args) {
        RailConsistFrame.Pose locomotive = new RailConsistFrame.Pose(17, 54, 28, 5.25f, 90.5f, 0);
        RailConsistFrame.Pose wagon = new RailConsistFrame.Pose(24, 50, 28, -6.5f, 180, -1);
        ByteBuffer encoded = RailConsistFrame.encode(-100, Arrays.asList(locomotive, wagon));
        check(encoded.remaining() == 60, "two poses occupy 10 + 2 x 25 bytes");
        ByteBuffer wire = encoded.duplicate();
        check(wire.getLong() == -100 && wire.getShort() == 2, "header follows wire order");
        check(wire.getLong() == 17 && wire.getFloat() == 54 && wire.getFloat() == 28
                && wire.getFloat() == 5.25f && wire.getFloat() == 90.5f && wire.get() == 0,
                "vehicle record uses XYZ, compass yaw and a signed layer");

        ByteBuffer envelope = ByteBuffer.allocate(61);
        envelope.put((byte) 5).put(encoded.duplicate());
        envelope.flip();
        envelope.position(1);
        ByteBuffer payload = envelope.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        RailConsistFrame frame = RailConsistFrame.decode(payload);
        check(payload.position() == 1, "parsing leaves the enclosing message cursor alone");
        check(frame.serverNanos == -100 && frame.poses.size() == 2, "timestamp and count round trip");
        RailConsistFrame.Pose decoded = frame.poses.get(1);
        check(decoded.id == 24 && decoded.x == 50 && decoded.y == 28
                && decoded.z == -6.5f && decoded.yaw == 180 && decoded.layer == -1, "cave pose round trips");

        ByteBuffer truncated = encoded.duplicate();
        truncated.limit(59);
        rejects(() -> RailConsistFrame.decode(truncated));
        ByteBuffer extra = ByteBuffer.allocate(61);
        extra.put(encoded.duplicate());
        extra.position(0);
        rejects(() -> RailConsistFrame.decode(extra));
        rejects(() -> RailConsistFrame.decode(ByteBuffer.allocate(9)));
        rejects(() -> RailConsistFrame.decode(ByteBuffer.allocate(10)));
        ByteBuffer excessive = ByteBuffer.allocate(10);
        excessive.putShort(8, (short) 65535);
        rejects(() -> RailConsistFrame.decode(excessive));
        rejects(() -> RailConsistFrame.encode(0, Collections.nCopies(RailConsistFrame.MAX_POSES + 1, wagon)));
        ByteBuffer nonFinite = RailConsistFrame.encode(0, Collections.singletonList(locomotive));
        nonFinite.putFloat(18, Float.NaN);
        rejects(() -> RailConsistFrame.decode(nonFinite));
        ByteBuffer invalidLayer = RailConsistFrame.encode(0, Collections.singletonList(locomotive));
        invalidLayer.put(34, (byte) 1);
        rejects(() -> RailConsistFrame.decode(invalidLayer));
        System.out.println("PASS consist frame byte order, bounds, finite poses and cursor isolation");
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Illegal rail frame was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected for invalid input.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
