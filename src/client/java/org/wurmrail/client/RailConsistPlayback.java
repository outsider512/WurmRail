package org.wurmrail.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;
import org.wurmrail.shared.RailConsistFrame;

/** Plays every car against the same server clock, independently of native mount packets. */
public final class RailConsistPlayback {
    private static final long PLAYBACK_DELAY = 250_000_000L;
    private static final long NATIVE_FALLBACK_AFTER = 1_000_000_000L;
    private static final long EXPIRY = 60_000_000_000L;
    private static final Logger LOG = Logger.getLogger(RailConsistPlayback.class.getName());
    private static final Map<Long,Track> tracks = new HashMap<>();
    private static boolean registered;
    private static long clockOffset = Long.MAX_VALUE;
    private static long lastCleanup;

    private RailConsistPlayback() { }

    public static synchronized void startHandshake() {
        clear();
        if (registered) return;
        ModComm.registerChannel(RailConsistFrame.CHANNEL, new IChannelListener() {
            @Override public void handleMessage(ByteBuffer message) {
                receive(RailConsistFrame.decode(message),System.nanoTime());
            }
        });
        registered = true;
    }

    public static synchronized void clear() {
        tracks.clear();
        clockOffset = Long.MAX_VALUE;
        lastCleanup = 0;
    }

    public static synchronized void forget(long id) {
        tracks.remove(id);
    }

    static synchronized void receive(RailConsistFrame frame,long receivedAt) {
        // TCP delivers whole frames in order. Estimate the clock difference from
        // the fastest observed delivery so queued packets cannot slow one car.
        if (clockOffset == Long.MAX_VALUE) LOG.info("Receiving synchronized WurmRail consist positions.");
        clockOffset = Math.min(clockOffset,receivedAt-frame.serverNanos);
        if (receivedAt-lastCleanup > EXPIRY) {
            tracks.values().removeIf(track -> receivedAt-track.receivedAt > EXPIRY);
            lastCleanup = receivedAt;
        }
        for (RailConsistFrame.Pose pose : frame.poses) {
            Track track = tracks.get(pose.id);
            if (track != null && frame.serverNanos <= track.serverTime) continue;
            if (track == null || track.layer != pose.layer) {
                track = new Track(pose,frame.serverNanos);
                tracks.put(pose.id,track);
            } else {
                track.motion.acceptFrame(pose.x,pose.y,pose.z,pose.yaw,frame.serverNanos);
            }
            track.receivedAt = receivedAt;
            track.serverTime = frame.serverNanos;
        }
    }

    public static synchronized float[] sample(long id,int layer,long now) {
        return sample(id,layer,now,0);
    }

    public static synchronized float[] sample(long id,int layer,long now,long nativeMovement) {
        Track track = tracks.get(id);
        if (track == null || track.layer != layer) return null;
        // A parked car may legitimately see no frames. Resume native playback
        // only when the server is still moving this car via its fallback path.
        if (nativeMovement!=0 && nativeMovement-track.receivedAt > NATIVE_FALLBACK_AFTER) return null;
        return track.motion.sampleAt(now-clockOffset-PLAYBACK_DELAY);
    }

    private static final class Track {
        final int layer;
        final RailVehicleMotion motion;
        long receivedAt,serverTime;

        Track(RailConsistFrame.Pose pose,long time) {
            layer = pose.layer;
            motion = new RailVehicleMotion(pose.x,pose.y,pose.z,pose.yaw,time);
        }
    }
}
