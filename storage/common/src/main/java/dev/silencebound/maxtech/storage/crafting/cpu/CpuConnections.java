package dev.silencebound.maxtech.storage.crafting.cpu;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Which faces of a crafting CPU block touch a block of the same formed cluster (B09 item 28), kept in six boolean block
 * state properties so the client's connected-texture model draws the frame only on the cluster's outer edges. A formed
 * cluster is a core's {@link CpuCluster#scan} without a conflict: the core and its counted parts. Two clusters side by
 * side, a conflicted cluster and parts without a core connect to nothing.
 *
 * <p>
 * Block states rather than model data because the part blocks have no block entity: the states are saved with the
 * chunk, synced by vanilla and make the client rebuild the chunk sections around a changed block. They change only when
 * a CPU block is placed or broken ({@link #refresh}), never per tick. A connection towards an unloaded block is left as
 * it was, so a cluster across a chunk border keeps its look while one side is unloaded. Server thread, but
 * {@link #mask} and {@link #withMask} are pure.
 */
public final class CpuConnections {
    private static final Direction[] SIDES = Direction.values();
    /**
     * Flags for a state change: tell clients only; no neighbour or shape updates, no {@code onPlace}, no block entity
     * side effects (the block's shape and the core's block entity stay as they are).
     */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    private CpuConnections() {
    }

    /**
     * The properties, in a holder of their own so the pure mask logic loads without vanilla's block state classes (unit
     * tests run without bootstrapped registries).
     */
    private static final class Properties {
        /** By {@link Direction#get3DDataValue()}: down, up, north, south, west, east. */
        static final BooleanProperty[] ALL = {BlockStateProperties.DOWN, BlockStateProperties.UP,
                BlockStateProperties.NORTH, BlockStateProperties.SOUTH, BlockStateProperties.WEST,
                BlockStateProperties.EAST};

        private Properties() {
        }
    }

    /** The six properties, for {@code createBlockStateDefinition}. */
    public static BooleanProperty[] properties() {
        return Properties.ALL.clone();
    }

    /** The bit of {@code side} in a connection mask. */
    public static int bit(Direction side) {
        return 1 << side.get3DDataValue();
    }

    /** The connection mask of a CPU block's state, by {@link #bit}; 0 for any other block. */
    public static int mask(BlockState state) {
        if (!state.hasProperty(Properties.ALL[0])) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < Properties.ALL.length; i++) {
            if (state.getValue(Properties.ALL[i])) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /** {@code state} with the connections of {@code mask}. */
    public static BlockState withMask(BlockState state, int mask) {
        BlockState result = state;
        for (int i = 0; i < Properties.ALL.length; i++) {
            result = result.setValue(Properties.ALL[i], (mask & (1 << i)) != 0);
        }
        return result;
    }

    /**
     * The connection mask the block at {@code pos} should have: a side is connected when the block there belongs to the
     * same formed cluster ({@code clusters} maps every block of a formed cluster to its core's position). A side whose
     * block is not {@code loaded} keeps its bit from {@code current}. Pure.
     */
    public static int mask(long pos, Long2LongMap clusters, LongPredicate loaded, int current) {
        boolean member = clusters.containsKey(pos);
        long core = clusters.get(pos);
        int mask = 0;
        for (Direction side : SIDES) {
            long next = BlockPos.offset(pos, side);
            int bit = bit(side);
            if (!loaded.test(next)) {
                mask |= current & bit;
            } else if (member && clusters.containsKey(next) && clusters.get(next) == core) {
                mask |= bit;
            }
        }
        return mask;
    }

    /**
     * A CPU block at {@code origin} was placed or broken: every core whose cluster may have changed counts it again
     * ({@link CpuCoreBlockEntity#recount}; one walk and one scan per core per change serve both the counts and the
     * looks) and every CPU block in those clusters (the parts reachable from {@code origin} over parts, up to
     * {@code maxParts} per side, and the cores they touch) gets its connections again. Writes only states that change;
     * the block at {@code origin} only when {@code writeOrigin} (a block being placed must not change its own state, so
     * it {@link #settle}s on a scheduled tick instead). Server thread.
     */
    public static void refresh(ServerLevel level, BlockPos origin, int maxParts, boolean writeOrigin) {
        LongOpenHashSet region = new LongOpenHashSet();
        LongArrayList cores = new LongArrayList();
        walk(level, origin, maxParts, region, cores);
        Long2LongOpenHashMap clusters = new Long2LongOpenHashMap();
        LongArrayList members = new LongArrayList();
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        for (int i = 0; i < cores.size(); i++) {
            long core = cores.getLong(i);
            members.clear();
            // A core being placed has no block entity yet; its own onLoad counts it.
            CpuCluster.Scan scan = level.getBlockEntity(target.set(core)) instanceof CpuCoreBlockEntity entity
                    ? entity.recount(members)
                    : CpuCluster.scan(level, target.immutable(), maxParts, members);
            if (scan.conflict()) {
                continue;
            }
            clusters.put(core, core);
            for (int m = 0; m < members.size(); m++) {
                long member = members.getLong(m);
                clusters.put(member, core);
                region.add(member);
            }
        }
        if (!writeOrigin) {
            region.remove(origin.asLong());
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        LongPredicate loaded = key -> level.isLoaded(at.set(key));
        for (LongIterator it = region.iterator(); it.hasNext();) {
            long key = it.nextLong();
            BlockState state = level.getBlockState(target.set(key));
            int current = mask(state);
            int wanted = mask(key, clusters, loaded, current);
            if (wanted != current && state.hasProperty(Properties.ALL[0])) {
                level.setBlock(target.immutable(), withMask(state, wanted), FLAGS);
            }
        }
    }

    /**
     * Gives the block at {@code pos} the connections its loaded neighbours hold towards it: after a {@link #refresh}
     * that skipped it, they are its own. Six reads; server thread.
     */
    public static void settle(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(Properties.ALL[0])) {
            return;
        }
        int current = mask(state);
        int wanted = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction side : SIDES) {
            next.setWithOffset(pos, side);
            if (!level.isLoaded(next)) {
                wanted |= current & bit(side);
            } else if ((mask(level.getBlockState(next)) & bit(side.getOpposite())) != 0) {
                wanted |= bit(side);
            }
        }
        if (wanted != current) {
            level.setBlock(pos, withMask(state, wanted), FLAGS);
        }
    }

    /**
     * Collects into {@code region} the CPU blocks at and around {@code origin} reachable over parts (cores end the
     * walk) and into {@code cores} the cores among them.
     */
    private static void walk(ServerLevel level, BlockPos origin, int maxParts, LongOpenHashSet region,
            LongArrayList cores) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        long start = origin.asLong();
        seen.add(start);
        Block self = level.getBlockState(origin).getBlock();
        if (self instanceof CpuPartBlock || self instanceof CpuCoreBlock) {
            region.add(start);
        }
        if (self instanceof CpuCoreBlock) {
            cores.add(start);
        }
        queue.enqueue(start);
        int parts = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        // Six sides of the origin may each lead into a cluster of up to maxParts parts.
        int limit = maxParts * SIDES.length + 1;
        while (!queue.isEmpty() && parts <= limit) {
            long at = queue.dequeueLong();
            for (Direction side : SIDES) {
                next.set(at).move(side);
                long key = next.asLong();
                if (!seen.add(key) || !level.isLoaded(next)) {
                    continue;
                }
                Block block = level.getBlockState(next).getBlock();
                if (block instanceof CpuPartBlock) {
                    parts++;
                    region.add(key);
                    queue.enqueue(key);
                } else if (block instanceof CpuCoreBlock) {
                    region.add(key);
                    cores.add(key);
                }
            }
        }
    }
}
