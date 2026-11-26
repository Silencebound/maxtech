package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.crafting.job.CraftLimits;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * What the network needs from its controller device: the FE store that powers the network while it is the controller in
 * charge, a hook to show the network's state, and the stats of its cluster (PRD 7.2, Controller): how many root links
 * the network may route through, which cluster blocks carry them, how many devices it may hold and how large a device
 * operation may be. Implemented by the controller core's block entity. Server thread only.
 */
public interface NetworkController {
    /** The controller's FE store; the network's energy while this controller is in charge. */
    FeBuffer buffer();

    /**
     * When this controller was placed (game time), saved with it: of several controllers in one network the lowest is
     * in charge (then the lowest position), the others are on standby (M7N item 3). Must not change while the block
     * stays; read when controllers join or leave.
     */
    long placedAt();

    /**
     * Whether this is a subnet controller: of several controllers in one network, controller cores come first, so a
     * subnet controller never puts a core on standby.
     */
    default boolean isSubnetController() {
        return false;
    }

    /**
     * The network's status or power may have changed; the controller updates its look if needed. Called on joins,
     * rebuilds, status changes and committed energy changes, so it must return after a cheap check when nothing
     * changed.
     */
    void refreshStatus();

    /**
     * How many links from the cluster to cables and devices outside it the network routes through at most; links beyond
     * this carry no routes. A storage controller allows every face of its active blocks ({@link Integer#MAX_VALUE}, M7R
     * item 1). Read when routes are rebuilt.
     */
    int rootLinks();

    /**
     * The positions of the cluster blocks that carry root links: a storage controller's active blocks. The list must
     * not change while the network holds it; the controller calls {@link NetworkState#clusterChanged()} after replacing
     * it.
     */
    LongList exitNodes();

    /**
     * Most devices the network may hold besides its controller and the cluster; more are refused until room frees. A
     * storage controller sets none since M7R item 1 (channels limit networks); a subnet controller keeps its own.
     */
    default int maxDevices() {
        return Integer.MAX_VALUE;
    }

    /** The batch size of a device operation, in percent of {@code network.batch.*}; 100 or more. */
    int batchPercent();

    /**
     * What an operation's FE cost is reduced to, in percent (efficiency cores): 1 to 100, 100 without a reduction. Read
     * per operation, so it must be a cached value.
     */
    default int energyPercent() {
        return 100;
    }

    /**
     * Below this percent of the network's FE capacity, automation operations are held back so the rest stays for player
     * operations (surge regulators): 0 to 100, 0 without a regulator. Read per operation, so it must be a cached value.
     */
    default int surgeReservePercent() {
        return 0;
    }

    /**
     * The network's limits for automated crafting jobs (M7L2 chunk 20); {@link CraftLimits#DEFAULT} follows the config.
     * Read when the job queue assigns CPUs and when automation submits, so it must be a cached value.
     */
    default CraftLimits craftLimits() {
        return CraftLimits.DEFAULT;
    }

    /**
     * What {@code player} may do with the network under the controller's security rules, or null when the network has
     * no security module (owner security then decides). Server thread; called on every access check, so it must not
     * search.
     */
    default @Nullable AccessLevel securityLevel(Player player) {
        return null;
    }

    /**
     * The network that pays for this network's operations when its own store cannot (a subnet's parent), or null for
     * none. Read per operation, so it must not search. Server thread.
     */
    default @Nullable NetworkState energyParent() {
        return null;
    }

    /**
     * The network's player-visible name (M7L2 row 13; unique on the server, {@link NetworkNames}); empty before the
     * controller has one. Server thread.
     */
    default String networkName() {
        return "";
    }

    /** The history a telemetry recorder in the cluster keeps, or null without one. Server thread. */
    default @Nullable NetworkHistory history() {
        return null;
    }

    /**
     * Every few seconds while the network ticks: the controller may update how its cluster shows its load. Must return
     * quickly; it may read {@link NetworkState#exitLoad(long)} and other cached values.
     */
    void refreshLoad(NetworkState network);

    /**
     * Void overflow in the network destroyed {@code amount} of {@code key} (M7C item 20); the controller keeps it in
     * its recovery bin for a while. Called after the commit, on the server thread, possibly every tick: no more than a
     * map update. Does nothing by default.
     */
    default void voided(StackKey key, long amount) {
    }

    /**
     * Whether load shedding is armed on this controller's network (M7L2): its FE has once been charged past the highest
     * resume level since the controller was placed. Saved with the controller, so a reload does not start shedding
     * again from a cold start.
     */
    boolean sheddingArmed();

    /** Arms load shedding ({@link #sheddingArmed()}) and saves the controller. Server thread. */
    void armShedding();
}
