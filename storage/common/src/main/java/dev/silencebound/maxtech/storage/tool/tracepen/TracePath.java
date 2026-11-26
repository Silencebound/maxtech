package dev.silencebound.maxtech.storage.tool.tracepen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongUnaryOperator;

/**
 * The way data takes between two nodes of one route tree: up from {@code from} to the first node both routes share
 * ({@code meet}), then down to {@code to}. Routes that end at different route roots (two root links of one controller)
 * meet inside the controller's cluster: {@code meet} is then {@link #NO_NODE} and {@link #throughRoot()} is true. Pure
 * logic over a parent function, built on a player's use. Any thread.
 *
 * @param up
 *            {@code from} and the nodes above it, up to but not including {@code meet}; ends with a route root when the
 *            path runs through the root
 * @param down
 *            {@code to} and the nodes above it, likewise, nearest {@code to} first
 * @param meet
 *            the first shared node, or {@link #NO_NODE}
 */
public record TracePath(LongList up, LongList down, long meet) {
    /** "No node", as the network's {@code routeParent} returns at a route's end. */
    public static final long NO_NODE = Long.MIN_VALUE;
    /** Most nodes walked up from either end; a bound against a broken tree, far above any real route. */
    public static final int MAX_NODES = 4096;

    /** Whether the two routes only meet inside the controller's cluster, past their route roots. */
    public boolean throughRoot() {
        return meet == NO_NODE;
    }

    /**
     * Segments on the path: each listed node leads to its parent, except the two route roots of a path through the
     * controller, which meet inside the cluster.
     */
    public int segments() {
        return throughRoot() ? Math.max(0, up.size() - 1) + Math.max(0, down.size() - 1) : up.size() + down.size();
    }

    /**
     * The path from {@code from} to {@code to}, where {@code parent} gives a node's next node towards the root or
     * {@link #NO_NODE} at a route's end. Both ends must be on the tree; either side stops after {@link #MAX_NODES}.
     */
    public static TracePath between(long from, long to, LongUnaryOperator parent) {
        LongOpenHashSet above = new LongOpenHashSet();
        LongArrayList up = new LongArrayList();
        for (long node = from; node != NO_NODE && up.size() < MAX_NODES; node = parent.applyAsLong(node)) {
            above.add(node);
            up.add(node);
        }
        LongArrayList down = new LongArrayList();
        long meet = NO_NODE;
        for (long node = to; node != NO_NODE && down.size() < MAX_NODES; node = parent.applyAsLong(node)) {
            if (above.contains(node)) {
                meet = node;
                break;
            }
            down.add(node);
        }
        if (meet != NO_NODE) {
            up.size(up.indexOf(meet));
        }
        return new TracePath(up, down, meet);
    }
}
