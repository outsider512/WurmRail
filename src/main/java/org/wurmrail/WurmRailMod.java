package org.wurmrail;

import com.wurmonline.server.creatures.Communicator;
import java.util.Properties;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.ItemTemplatesCreatedListener;
import org.gotti.wurmunlimited.modloader.interfaces.MessagePolicy;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerMessageListener;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.ServerPollListener;
import org.gotti.wurmunlimited.modloader.interfaces.ServerShutdownListener;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.modsupport.actions.ModActions;
import org.gotti.wurmunlimited.modsupport.vehicles.ModVehicleBehaviours;

/**
 * Discovery-safe entry point. The loader constructs this class BEFORE ModComm
 * modifies Player and Communicator. Keep gameplay bytecode out of this class:
 * even an uncalled getPlayer() -> Creature conversion can load Player during
 * JVM verification and freeze it before those patches run.
 */
public final class WurmRailMod implements WurmServerMod, Configurable, PreInitable,
        ItemTemplatesCreatedListener, ServerStartedListener, ServerPollListener,
        ServerShutdownListener, PlayerMessageListener {
    private final Properties configuration = new Properties();
    private volatile WurmServerMod runtime;

    @Override public void configure(Properties properties) {
        configuration.clear();
        configuration.putAll(properties);
    }

    @Override public void preInit() {
        org.wurmrail.shared.CoalVeinTiles.install(org.gotti.wurmunlimited.modloader.classhooks.HookManager.getInstance().getClassPool());
        CoalVeinHooks.init();
        // Register hooks without loading gameplay bytecode while other mods
        // and the loader still need to patch the server's classes.
        ModActions.init();
        ModVehicleBehaviours.init();
        RailModelHooks.init();
        RailVegetationHooks.init();
        BulkHopperHooks.init();
        LogBunkHooks.init();
        LogCarHooks.init();
        LiquidTankHooks.init();
        TrestleWalkingServerHooks.install();
    }

    @Override public void onItemTemplatesCreated() {
        WurmServerMod current = runtime;
        if (current == null) {
            // This game-startup event follows the loader's patching phases and
            // server hook installation. The separate factory is verified now.
            current = RuntimeFactory.create(configuration);
            runtime = current;
        }
        ((ItemTemplatesCreatedListener)current).onItemTemplatesCreated();
    }

    @Override public void onServerStarted() {
        WurmServerMod current = runtime;
        if (current != null) ((ServerStartedListener)current).onServerStarted();
    }

    @Override public void onServerPoll() {
        WurmServerMod current = runtime;
        if (current != null) ((ServerPollListener)current).onServerPoll();
    }

    @Override public void onServerShutdown() {
        WurmServerMod current = runtime;
        if (current != null) ((ServerShutdownListener)current).onServerShutdown();
    }

    @Override public boolean onPlayerMessage(Communicator communicator, String message) {
        WurmServerMod current = runtime;
        return current != null && ((PlayerMessageListener)current).onPlayerMessage(communicator,message);
    }

    @Override public MessagePolicy onPlayerMessage(Communicator communicator, String message, String title) {
        WurmServerMod current = runtime;
        return current == null ? MessagePolicy.PASS
                : ((PlayerMessageListener)current).onPlayerMessage(communicator,message,title);
    }

    private static final class RuntimeFactory {
        private static WurmServerMod create(Properties configuration) {
            WurmRailRuntime created = new WurmRailRuntime();
            created.configure(configuration);
            created.init();
            return created;
        }
    }
}
