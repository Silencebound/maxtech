package dev.silencebound.maxtech.storage.network;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The root links of a controller network (PRD 7.2, Controller): the links from the cluster blocks that carry them (the
 * active blocks of a multiblock controller, M7R item 1) to nodes outside the cluster, of which the routes may use only
 * as many as the controller allows (a storage controller: all of them). {@link NetworkState} offers every candidate at
 * a rebuild, {@link #enable enables} the widest, then by cluster block and side, and asks {@link #width} for each edge
 * while it builds the tree from its virtual root. Topology only; pure, so the tree building is unit-tested. Server
 * thread only.
 */
final class RootLinks {
    private static final Direction[] SIDES = Direction.values();

    /** Enabled links by outside node: bits of the sides (seen from that node) towards cluster blocks. */
    private final Long2IntOpenHashMap enabled = new Long2IntOpenHashMap();
    private final LongArrayList outside = new LongArrayList();
    private final LongArrayList inside = new LongArrayList();
    private final IntArrayList sides = new IntArrayList();
    private final LongArrayList widths = new LongArrayList();
    private int used;

    /** Forgets every candidate and enabled link; at the start of a rebuild. */
    void clear() {
        enabled.clear();
        outside.clear();
        inside.clear();
        sides.clear();
        widths.clear();
        used = 0;
    }

    /**
     * Offers the link from the cluster block at {@code clusterPos} to the outside node at {@code outsidePos}, which
     * touch, with its channels {@code width} (positive).
     */
    void offer(long clusterPos, long outsidePos, long width) {
        int bit = sideBit(outsidePos, clusterPos);
        if (bit == 0 || width <= 0) {
            return;
        }
        outside.add(outsidePos);
        inside.add(clusterPos);
        sides.add(Integer.numberOfTrailingZeros(bit));
        widths.add(width);
    }

    /**
     * Enables at most {@code allowed} of the offered links, widest first, then by the lower cluster block position and
     * side, so the choice does not depend on the order they were offered in. Returns how many it enabled.
     */
    int enable(int allowed) {
        int[] order = new int[outside.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        // Rebuilds only, so a comparator is fine here.
        IntArrays.quickSort(order, (a, b) -> {
            int byWidth = Long.compare(widths.getLong(b), widths.getLong(a));
            if (byWidth != 0) {
                return byWidth;
            }
            int byNode = Long.compare(inside.getLong(a), inside.getLong(b));
            return byNode != 0 ? byNode : Integer.compare(sides.getInt(a), sides.getInt(b));
        });
        used = Math.min(order.length, Math.max(0, allowed));
        for (int i = 0; i < used; i++) {
            int c = order[i];
            long node = outside.getLong(c);
            enabled.put(node, enabled.get(node) | (1 << sides.getInt(c)));
        }
        return used;
    }

    /** Links offered since the last {@link #clear}: every link from a block carrying root links to an outside node. */
    int offered() {
        return outside.size();
    }

    /** Links enabled by the last {@link #enable}. */
    int used() {
        return used;
    }

    /** Whether the link from the outside node {@code outsidePos} to the cluster block {@code clusterPos} is enabled. */
    boolean allows(long outsidePos, long clusterPos) {
        return (enabled.get(outsidePos) & sideBit(outsidePos, clusterPos)) != 0;
    }

    /** Whether {@code pos} is the outside node of an enabled link. */
    boolean isOutside(long pos) {
        return enabled.containsKey(pos);
    }

    /** The outside nodes of the enabled links. */
    LongIterator outsideNodes() {
        return enabled.keySet().iterator();
    }

    /**
     * Width of an edge of a controller network's tree: unlimited from {@code superRoot}, 0 between two cluster blocks
     * and on a cluster block's link that is not enabled, else {@code base}.
     */
    long width(long from, long to, long superRoot, boolean fromCluster, boolean toCluster, long base) {
        if (from == superRoot || to == superRoot) {
            return Long.MAX_VALUE;
        }
        if (fromCluster || toCluster) {
            if (fromCluster == toCluster) {
                return 0;
            }
            return fromCluster ? (allows(to, from) ? base : 0) : (allows(from, to) ? base : 0);
        }
        return base;
    }

    /** The bit of the side of {@code from} that {@code to} touches; 0 when they do not touch. */
    static int sideBit(long from, long to) {
        for (Direction side : SIDES) {
            if (BlockPos.offset(from, side) == to) {
                return 1 << side.get3DDataValue();
            }
        }
        return 0;
    }
}
