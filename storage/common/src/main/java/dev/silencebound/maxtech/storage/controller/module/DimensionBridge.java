package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.StorageNetwork;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.resources.Identifier;
// PLATFORM-LEAK: the surcharge is paid inside the operation's NeoForge transaction, like every Storage transfer.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The dimension bridge (M6f, owner decisions F4 to F6): a network device (M7R item 4) that lets the network's wireless
 * terminals, pocket uplinks and restock belts reach it from other dimensions, and its mail ports and request boards
 * send to players in other dimensions. While it is on a network with a working controller it drains
 * {@code devices.dimensionBridge.drainPerTick} FE from the network ({@link BridgeDrain}); every operation that crosses
 * a dimension pays {@code surchargePerOperation} FE on top of its usual cost, and is refused when the network cannot
 * pay it. The bound network's chunks follow the usual rules: a core or drive in an unloaded chunk is out of reach, a
 * network anchor keeps them loaded. Server thread.
 */
public final class DimensionBridge {
    /**
     * The research unlock that gates placing a bridge. A placeholder until the Void research branch (M6f area A) lists
     * it among its node's unlocks; an id no loaded node lists is unlocked for everyone.
     */
    public static final Identifier UNLOCK = MaxtechStorage.id("dimension_bridge");
    /**
     * Ticks between two drain payments. The FE owed is worked out from game time, so this only sets how often the
     * network's FE visibly drops; it matches the telemetry recorder's default cadence so a settled network with every
     * module stays within its scheduler budget.
     */
    public static final int DRAIN_INTERVAL_TICKS = 200;

    public static final String INFO_KEY = "info." + MaxtechStorage.MOD_ID + ".dimension_bridge";
    public static final String UNPAID_KEY = "info." + MaxtechStorage.MOD_ID + ".dimension_bridge.unpaid";

    private DimensionBridge() {
    }

    /** FE the network pays per tick for each active bridge. */
    public static long drainPerTick() {
        return StorageServerConfig.BRIDGE_DRAIN_PER_TICK.getAsLong();
    }

    /** FE an operation that crosses a dimension costs on top of its usual cost. */
    public static long surcharge() {
        return StorageServerConfig.BRIDGE_SURCHARGE.getAsLong();
    }

    /** Whether {@code network} is online, has a storage controller and a dimension bridge on it. */
    public static boolean bridged(@Nullable NetworkState network) {
        return network != null && network.status() == StorageNetwork.Status.ONLINE
                && network.controllerDevice() instanceof ControllerBlockEntity
                && network.serviceCount(NetworkService.DIMENSION_BRIDGE) > 0;
    }

    /**
     * Pays one crossing's surcharge from {@code network}'s FE inside {@code transaction}; false (nothing paid) when it
     * cannot. A surcharge of 0 is always paid.
     */
    public static boolean pay(NetworkState network, TransactionContext transaction) {
        long cost = surcharge();
        if (cost <= 0) {
            return true;
        }
        FeBuffer buffer = network.energyBuffer();
        return buffer != null && buffer.tryConsume(cost, transaction);
    }

    /** The English texts; called from {@link ExpansionContent}. */
    static void registerTexts() {
        ModContent.CONTENT.translation(INFO_KEY, "Dimension bridge: drains %s MU/t, each crossing costs %s MU");
        ModContent.CONTENT.translation(UNPAID_KEY, "The network could not pay the bridge's last drain");
    }
}
