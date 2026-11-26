package dev.silencebound.maxtech.lib.platform;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Listeners registered here run on the thread that fires the event: the server thread for all but common setup. */
public interface LifecycleEvents {
    /** Runs after registration, possibly in parallel with other mods' setup. */
    void onCommonSetup(String modId, Runnable action);

    void onServerStarting(Consumer<MinecraftServer> action);

    void onServerStopped(Consumer<MinecraftServer> action);

    /** Runs at the end of every server tick, after every level ticked; main-thread commits of background jobs. */
    void onServerTickEnd(Consumer<MinecraftServer> action);

    /** Runs at the end of every server level tick. Network logic hangs off this instead of block entity tickers. */
    void onServerLevelTickEnd(Consumer<ServerLevel> action);

    /**
     * Runs whenever a position of a server level notifies its neighbours: after a block is placed, broken or changes
     * state, but also for redstone propagation and diode updates with no change, and sometimes several times per
     * change. It fires very often, so the action must return at once when it has nothing to do (multiblocks look the
     * position up in a primitive index) and must tolerate repeats. A {@code setBlock} without neighbour updates does
     * not fire it. It also runs when another mod cancelled the notification, since the block change still happened. The
     * position may be reused by the caller; copy it before keeping it.
     */
    void onBlockChanged(BiConsumer<ServerLevel, BlockPos> action);

    /**
     * Runs when a chunk of a server level has finished loading to full status, before its full-status future completes:
     * {@code getChunkNow} may still return {@code null} inside the action, so queue work for the end of the level tick.
     * It does not run when a chunk that stayed in memory returns to full status (see {@link #onChunkLevelChanged}).
     */
    void onChunkLoaded(ChunkListener action);

    /**
     * Runs whenever the ticket level of a server level's chunk changes (lower is more loaded: 31 entity ticking, 32
     * block ticking, 33 full, above that not accessible). It fires often, sometimes twice for one change, and before
     * the chunk reaches its new status, so the action must return at once when it has nothing to do and should queue
     * its work. A chunk that drops just below full status stays in memory with its block entities and fires only this
     * when it comes back, never {@link #onChunkLoaded}.
     */
    void onChunkLevelChanged(ChunkLevelListener action);

    /** Receives a chunk position packed by {@code ChunkPos.pack}. */
    @FunctionalInterface
    interface ChunkListener {
        void accept(ServerLevel level, long chunkPos);
    }

    /** Receives a chunk position packed by {@code ChunkPos.pack} and its old and new ticket levels. */
    @FunctionalInterface
    interface ChunkLevelListener {
        void accept(ServerLevel level, long chunkPos, int oldLevel, int newLevel);
    }
}
