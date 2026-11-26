package dev.silencebound.maxtech.storage.crafting.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class CpuConnectionsTest {
    private static final LongPredicate LOADED = CpuConnectionsTest::loaded;
    private static final long CORE_A = BlockPos.asLong(0, 0, 0);
    private static final long CORE_B = BlockPos.asLong(-1, 0, 0);
    private static final long PART = BlockPos.asLong(1, 0, 0);

    private static boolean loaded(long key) {
        return true;
    }

    private static int bits(Direction... sides) {
        int mask = 0;
        for (Direction side : sides) {
            mask |= CpuConnections.bit(side);
        }
        return mask;
    }

    @Test
    void sameClusterConnects() {
        Long2LongOpenHashMap clusters = new Long2LongOpenHashMap();
        clusters.put(CORE_A, CORE_A);
        clusters.put(PART, CORE_A);
        clusters.put(BlockPos.asLong(1, 1, 0), CORE_A);
        assertEquals(bits(Direction.EAST), CpuConnections.mask(CORE_A, clusters, LOADED, 0));
        assertEquals(bits(Direction.WEST, Direction.UP), CpuConnections.mask(PART, clusters, LOADED, 0));
    }

    @Test
    void anotherClusterDoesNotConnect() {
        // Two cores side by side are two CPUs.
        Long2LongOpenHashMap clusters = new Long2LongOpenHashMap();
        clusters.put(CORE_A, CORE_A);
        clusters.put(PART, CORE_A);
        clusters.put(CORE_B, CORE_B);
        assertEquals(bits(Direction.EAST), CpuConnections.mask(CORE_A, clusters, LOADED, 0));
        assertEquals(0, CpuConnections.mask(CORE_B, clusters, LOADED, bits(Direction.EAST)));
    }

    @Test
    void looseBlocksConnectToNothing() {
        // Parts without a core (or of a conflicted cluster) are in no formed cluster, so their old bits go.
        Long2LongOpenHashMap clusters = new Long2LongOpenHashMap();
        assertEquals(0, CpuConnections.mask(PART, clusters, LOADED, bits(Direction.WEST, Direction.UP)));
        clusters.put(CORE_A, CORE_A);
        assertEquals(0, CpuConnections.mask(PART, clusters, LOADED, 0));
        assertEquals(0, CpuConnections.mask(CORE_A, clusters, LOADED, bits(Direction.EAST)));
    }

    @Test
    void unloadedSidesKeepTheirBits() {
        Long2LongOpenHashMap clusters = new Long2LongOpenHashMap();
        clusters.put(PART, CORE_A);
        long north = BlockPos.offset(PART, Direction.NORTH);
        LongPredicate loaded = key -> key != north;
        int current = bits(Direction.NORTH, Direction.SOUTH);
        assertEquals(bits(Direction.NORTH), CpuConnections.mask(PART, clusters, loaded, current));
    }
}
