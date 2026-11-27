package dev.silencebound.maxtech.lib.api.network;

import it.unimi.dsi.fastutil.longs.LongList;
import org.jetbrains.annotations.ApiStatus;

/**
 * The world as one network type sees it; {@code ConduitConnectivity} implements it for conduit types. The graph asks
 * only when a node is added or invalidated, never per tick. Server thread only.
 * <p>
 * Experimental (Lib 0.14.0) until Farms, its first addon user, settles it.
 */
@ApiStatus.Experimental
public interface NodeConnectivity {
    /**
     * Appends the packed positions ({@link net.minecraft.core.BlockPos#asLong()}) of the nodes of this type that the
     * node at {@code pos} connects to. May include positions in unloaded chunks, as long as the answer comes from state
     * stored at {@code pos} (cables keep their connection state) rather than from loading the neighbour.
     * <p>
     * Must be symmetric: if A lists B, B lists A. When a connection changes, invalidate both ends.
     */
    void connections(long pos, LongList out);

    /**
     * Whether the chunk holding {@code pos} is fully present: on the server, {@code getChunkNow(x, z) != null}. Not
     * {@code Level.isLoaded}, which turns true as soon as the chunk is ticketed at full level, while it may still be
     * loading and its block entities have not re-added their nodes.
     */
    boolean isLoaded(long pos);
}
