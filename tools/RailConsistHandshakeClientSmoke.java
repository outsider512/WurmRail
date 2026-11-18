import com.wurmonline.client.WurmClientBase;
import com.wurmonline.client.comm.SimpleServerConnectionClass;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.cell.CellRenderable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Random;
import org.gotti.wurmunlimited.modcomm.ModCommHandler;
import org.wurmrail.client.RailConsistPlayback;
import org.wurmrail.shared.RailConsistFrame;

/** The game loader advertises and receives consist frames on each connection. */
public final class RailConsistHandshakeClientSmoke {
    private static final long CAR_ID = 274;

    public static void run() throws Exception {
        World originalWorld = CellRenderable.world;
        Field clientObject = WurmClientBase.class.getDeclaredField("clientObject");
        clientObject.setAccessible(true);
        Object originalClient = clientObject.get(null);
        try {
            World world = RailVehicleTerrainClientSmoke.setupWorld(0, 0, 5, 0, 0, -6);
            Object client = allocate(WurmClientBase.class);
            set(world, "client", client);
            set(client, "world", world);
            clientObject.set(null, client);

            connect(client, 19);
            receive(19, 5_000_000L, 52);
            checkPose(52);
            connect(client, 31);
            check(RailConsistPlayback.sample(CAR_ID, 0, System.nanoTime()) == null,
                    "a new handshake clears the preceding world's pose history");
            receive(31, 17, 48);
            checkPose(48);
        } finally {
            RailConsistPlayback.clear();
            clientObject.set(null, originalClient);
            CellRenderable.world = originalWorld;
        }
        System.out.println("PASS native ModComm handshake, rail frame receipt and fresh clock on reconnect");
    }

    private static void connect(Object client, int channelId) throws Exception {
        Object connection = allocate(SimpleServerConnectionClass.class);
        Class<?> socketType = Class.forName(SimpleServerConnectionClass.class.getName() + "$SocketConnection2");
        Object socket = allocate(socketType);
        ByteBuffer outgoing = ByteBuffer.allocate(128);
        set(socket, "writeBufferTmp", outgoing);
        set(socket, "writeBuffer_w", ByteBuffer.allocate(256));
        set(socket, "encryptRandom", new Random(0));
        set(connection, "connection", socket);
        set(client, "serverConnection", connection);

        ModCommHandler.startHandshake();
        // Native flush copies and encrypts the socket output, leaving the
        // application payload in the original buffer for this socket-free test.
        check(outgoing.position() > 0, "native launcher sent its channel advertisement");
        DataInputStream request = new DataInputStream(new ByteArrayInputStream(outgoing.array(), 0, outgoing.limit()));
        check(request.readByte() == -100 && request.readByte() == 2 && request.readByte() == 1,
                "native advertisement uses the launcher handshake envelope");
        int channels = request.readInt();
        int railChannels = 0;
        for (int i = 0; i < channels; i++) {
            if (RailConsistFrame.CHANNEL.equals(request.readUTF())) railChannels++;
        }
        check(railChannels == 1, "the game's launcher advertises exactly one consist channel");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream response = new DataOutputStream(bytes)) {
            // Packet opcode was consumed by the native command dispatcher.
            response.writeByte(2);
            response.writeByte(1);
            response.writeInt(1);
            response.writeInt(channelId);
            response.writeUTF(RailConsistFrame.CHANNEL);
        }
        ModCommHandler.handlePacket(ByteBuffer.wrap(bytes.toByteArray()));
    }

    private static void receive(int channelId, long serverTime, float x) {
        ByteBuffer frame = RailConsistFrame.encode(serverTime,
                Collections.singletonList(new RailConsistFrame.Pose(CAR_ID, x, 44, 5.25f, 81, 0)));
        ByteBuffer message = ByteBuffer.allocate(1 + Integer.BYTES + frame.remaining());
        message.put((byte) 1).putInt(channelId).put(frame);
        message.flip();
        ModCommHandler.handlePacket(message);
    }

    private static void checkPose(float x) {
        float[] pose = RailConsistPlayback.sample(CAR_ID, 0, System.nanoTime());
        check(pose != null && pose[0] == x && pose[1] == 44 && pose[2] == 5.25f && pose[3] == 81,
                "the actual game-loader handler delivers the complete pose from its negotiated channel");
    }

    private static Object allocate(Class<?> type) throws Exception {
        Method allocate = RailVehicleTerrainClientSmoke.class.getDeclaredMethod("allocate", Class.class);
        allocate.setAccessible(true);
        return allocate.invoke(null, type);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        RailVehicleTerrainClientSmoke.set(target, field, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
