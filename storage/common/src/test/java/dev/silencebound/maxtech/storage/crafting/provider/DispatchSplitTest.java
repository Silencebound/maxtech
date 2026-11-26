package dev.silencebound.maxtech.storage.crafting.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DispatchSplitTest {
    private static int[] split(DispatchStrategy strategy, int crafts, int[] load, int[] ticks) {
        int[] share = DispatchSplit.split(strategy, crafts, load, ticks);
        assertEquals(crafts, Arrays.stream(share).sum(), "every craft is handed out");
        return share;
    }

    @Test
    void roundRobinSplitsEvenlyFromTheFirst() {
        assertArrayEquals(new int[]{4, 3, 3}, split(DispatchStrategy.ROUND_ROBIN, 10, new int[3], new int[]{1, 1, 1}));
        assertArrayEquals(new int[]{1, 1, 0}, split(DispatchStrategy.ROUND_ROBIN, 2, new int[]{9, 0, 0}, new int[3]));
    }

    @Test
    void lowestLoadFillsTheEmptiestFirst() {
        assertArrayEquals(new int[]{0, 4}, split(DispatchStrategy.LOWEST_LOAD, 4, new int[]{6, 0}, new int[]{1, 1}));
        assertArrayEquals(new int[]{2, 6}, split(DispatchStrategy.LOWEST_LOAD, 8, new int[]{4, 0}, new int[]{1, 1}));
    }

    @Test
    void fastestWeighsTheDuration() {
        // A target twice as fast takes twice the share.
        assertArrayEquals(new int[]{4, 8}, split(DispatchStrategy.FASTEST, 12, new int[2], new int[]{200, 100}));
        // Load counts in ticks: both are busy until tick 200, and the faster one finishes three more by 500.
        assertArrayEquals(new int[]{1, 3}, split(DispatchStrategy.FASTEST, 4, new int[]{1, 2}, new int[]{200, 100}));
    }

    @Test
    void nothingToSplit() {
        assertArrayEquals(new int[0], DispatchSplit.split(DispatchStrategy.FASTEST, 5, new int[0], new int[0]));
        assertArrayEquals(new int[2], DispatchSplit.split(DispatchStrategy.FASTEST, 0, new int[2], new int[]{1, 1}));
    }

    @Test
    void largeBatchesStayExact() {
        int[] share = split(DispatchStrategy.FASTEST, 1_000_000, new int[]{0, 0, 0}, new int[]{1, 2, 4});
        assertArrayEquals(new int[]{571_429, 285_714, 142_857}, share);
    }
}
