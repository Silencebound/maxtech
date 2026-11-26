package dev.silencebound.maxtech.storage.crafting.cpu;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

/**
 * Finds a crafting CPU's cluster (M7 C3): the part blocks touching its core or each other, face to face, in any shape,
 * in loaded chunks. A fill runs only when a core loads or a CPU block is placed or broken
 * ({@link CpuConnections#refresh}), never per tick, and visits at most {@code crafting.cpu.maxParts} parts. Two cores
 * reaching the same parts are a conflict and both count nothing. Server thread.
 */
public final class CpuCluster {
    private static final Direction[] SIDES = Direction.values();

    /** What a fill found: parts per kind, and whether another core touches the cluster (a conflict). */
    public record Scan(int storage, int lanes, int caches, boolean conflict) {
        /** Parts of {@code part}. */
        public int count(CpuPart part) {
            return switch (part) {
                case STORAGE -> storage;
                case LANE -> lanes;
                case CACHE -> caches;
            };
        }
    }

    private CpuCluster() {
    }

    /** Fills from the core at {@code core} over part blocks, up to {@code maxParts}. */
    public static Scan scan(ServerLevel level, BlockPos core, int maxParts) {
        return scan(level, core, maxParts, null);
    }

    /**
     * {@link #scan(ServerLevel, BlockPos, int)}, adding each counted part's position to {@code members} when given (the
     * cluster preview, M7C item 22).
     */
    public static Scan scan(ServerLevel level, BlockPos core, int maxParts, @Nullable LongList members) {
        int storage = 0;
        int lanes = 0;
        int caches = 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long self = core.asLong();
        seen.add(self);
        queue.enqueue(self);
        boolean conflict = false;
        int parts = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && parts < maxParts) {
            long at = queue.dequeueLong();
            for (Direction side : SIDES) {
                next.set(at).move(side);
                long key = next.asLong();
                if (!seen.add(key) || !level.isLoaded(next)) {
                    continue;
                }
                Block block = level.getBlockState(next).getBlock();
                if (block instanceof CpuPartBlock part) {
                    if (parts < maxParts) {
                        switch (part.part()) {
                            case STORAGE -> storage++;
                            case LANE -> lanes++;
                            case CACHE -> caches++;
                        }
                        parts++;
                        queue.enqueue(key);
                        if (members != null) {
                            members.add(key);
                        }
                    }
                } else if (at != self && block instanceof CpuCoreBlock) {
                    // Only a core reached over parts shares them; two cores side by side are two CPUs.
                    conflict = true;
                }
            }
        }
        return new Scan(storage, lanes, caches, conflict);
    }

    /**
     * Whether a part placed at {@code cell}, next to the cluster of the core at {@code core} whose counted parts are
     * {@code cluster}, would join that cluster without a conflict: no other core touches the cell (the fill would reach
     * it over the new part) and no part outside the cluster does (it may belong to another core). The cluster preview's
     * rule for where a cluster can grow; it does not check that the cell is free. Server thread.
     */
    public static boolean canGrowInto(ServerLevel level, long cell, long core, LongSet cluster) {
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction side : SIDES) {
            next.set(cell).move(side);
            long key = next.asLong();
            if (key == core || cluster.contains(key) || !level.isLoaded(next)) {
                continue;
            }
            Block block = level.getBlockState(next).getBlock();
            if (block instanceof CpuCoreBlock || block instanceof CpuPartBlock) {
                return false;
            }
        }
        return true;
    }
}
