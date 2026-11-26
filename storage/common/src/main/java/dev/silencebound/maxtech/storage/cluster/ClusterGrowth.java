package dev.silencebound.maxtech.storage.cluster;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Where a face-to-face cluster can grow (M7C item 22): the cells touching a cluster block on a face that are not in the
 * cluster and that {@code open} accepts. Controller and CPU clusters both join blocks face to face in any shape, so
 * this is the shape rule; what may stand in a cell (free, no conflict) is the caller's {@code open}, built on the
 * cluster's own code. Pure.
 */
public final class ClusterGrowth {
    private static final Direction[] SIDES = Direction.values();

    private ClusterGrowth() {
    }

    /**
     * Adds to {@code out}, at most {@code max} in all, every cell next to a block of {@code cluster} (positions as
     * {@link BlockPos#asLong()}) that is not in it and that {@code open} accepts, each once, in cluster order.
     */
    public static void frontier(LongList cluster, LongPredicate open, LongList out, int max) {
        LongOpenHashSet seen = new LongOpenHashSet(cluster);
        for (int i = 0; i < cluster.size() && out.size() < max; i++) {
            long pos = cluster.getLong(i);
            for (Direction side : SIDES) {
                long next = BlockPos.offset(pos, side);
                if (seen.add(next) && open.test(next)) {
                    out.add(next);
                    if (out.size() >= max) {
                        return;
                    }
                }
            }
        }
    }
}
