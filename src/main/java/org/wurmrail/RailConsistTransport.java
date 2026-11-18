package org.wurmrail;

import com.wurmonline.server.players.Player;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;
import org.wurmrail.shared.RailConsistFrame;

/** Publishes a complete visible consist through a negotiated client channel. */
public final class RailConsistTransport {
    private static final Logger LOG = Logger.getLogger(RailConsistTransport.class.getName());
    private static final Set<Player> subscribers = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<Player,Boolean>()));
    private static Channel channel;
    private static int channelId;

    private RailConsistTransport() { }

    // Implemented by the queued native communicator during preInit. ModComm's
    // direct socket sender could overtake queued native spawn and layer changes.
    public interface PacketQueue {
        void wurmRailQueueConsist(int channel, ByteBuffer payload);
    }

    static void register() {
        if (channel != null) return;
        try {
            Field id = Channel.class.getDeclaredField("id");
            id.setAccessible(true);
            Channel registered = ModComm.registerChannel(RailConsistFrame.CHANNEL, new IChannelListener() {
                @Override public void onPlayerConnected(Player player) { subscribers.add(player); }
            });
            channelId = id.getInt(registered);
            channel = registered;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOG.log(Level.WARNING,"Could not register synchronized rail playback. Using native movement packets.",error);
        }
    }

    static boolean hasSubscribers() { return channel != null && !subscribers.isEmpty(); }

    static boolean receives(Player player) {
        return channel != null && player.hasLink() && subscribers.contains(player)
                && channel.isActiveForPlayer(player) && player.getCommunicator() instanceof PacketQueue;
    }

    static void send(Player player, long timestamp, List<RailConsistFrame.Pose> poses) {
        if (!receives(player)) return;
        PacketQueue queue = (PacketQueue)player.getCommunicator();
        queue.wurmRailQueueConsist(channelId, RailConsistFrame.encode(timestamp,poses));
    }
}
