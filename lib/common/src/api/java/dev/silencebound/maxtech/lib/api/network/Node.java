package dev.silencebound.maxtech.lib.api.network;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jspecify.annotations.Nullable;

/** A graph vertex. Edges are stored on both ends; conduits have at most six, so lists beat sets here. */
final class Node {
    final long pos;
    Network network;
    final LongArrayList links = new LongArrayList(6);
    /** Targets of connections into unloaded chunks; null for the common case of none. */
    @Nullable
    LongArrayList unloaded;
    boolean anchor;

    Node(long pos, Network network) {
        this.pos = pos;
        this.network = network;
    }

    boolean hasUnloaded(long target) {
        LongArrayList list = unloaded;
        return list != null && list.contains(target);
    }
}
