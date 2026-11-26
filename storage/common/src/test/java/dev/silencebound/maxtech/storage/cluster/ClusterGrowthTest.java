package dev.silencebound.maxtech.storage.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClusterGrowthTest {
    private static long at(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @Test
    void singleBlockHasSixNeighbours() {
        LongArrayList out = new LongArrayList();
        ClusterGrowth.frontier(LongArrayList.of(at(0, 0, 0)), cell -> true, out, 100);
        assertEquals(6, out.size());
        assertTrue(out.contains(at(1, 0, 0)));
        assertTrue(out.contains(at(0, -1, 0)));
    }

    @Test
    void sharedNeighboursCountOnceAndClusterCellsNever() {
        LongArrayList out = new LongArrayList();
        // Two blocks side by side: 6 + 6 faces, minus the two facing each other, so 10 cells.
        ClusterGrowth.frontier(LongArrayList.of(at(0, 0, 0), at(1, 0, 0)), cell -> true, out, 100);
        assertEquals(10, out.size());
        assertEquals(10, new LongOpenHashSet(out).size());
        assertFalse(out.contains(at(0, 0, 0)));
        assertFalse(out.contains(at(1, 0, 0)));
    }

    @Test
    void closedCellsAndTheCapAreRespected() {
        LongArrayList out = new LongArrayList();
        long blocked = at(0, 1, 0);
        ClusterGrowth.frontier(LongArrayList.of(at(0, 0, 0)), cell -> cell != blocked, out, 100);
        assertEquals(5, out.size());
        assertFalse(out.contains(blocked));

        LongArrayList capped = new LongArrayList();
        ClusterGrowth.frontier(LongArrayList.of(at(0, 0, 0), at(5, 0, 0)), cell -> true, capped, 4);
        assertEquals(4, capped.size());
    }
}
