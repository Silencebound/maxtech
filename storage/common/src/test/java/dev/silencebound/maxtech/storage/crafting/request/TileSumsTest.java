package dev.silencebound.maxtech.storage.crafting.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TileSumsTest {
    /** Sticks from planks from logs, with planks needed twice: one tile per key, in first-seen order. */
    @Test
    void sumsPerKeyInFirstSeenOrder() {
        TileSums<String> sums = new TileSums<>();
        sums.add("stick", 0, 8, 0, 0);
        sums.add("planks", 2, 2, 0, 0);
        sums.add("log", 0, 0, 0, 1);
        sums.add("planks", 1, 3, 0, 0);
        List<TileSums.Sum<String>> tiles = sums.result(10);
        assertEquals(List.of(new TileSums.Sum<>("stick", 0, 8, 0, 0), new TileSums.Sum<>("planks", 3, 5, 0, 0),
                new TileSums.Sum<>("log", 0, 0, 0, 1)), tiles);
    }

    @Test
    void dropsEmptyKeysAndNegativeCounts() {
        TileSums<String> sums = new TileSums<>();
        sums.add("done", 0, 0, 0, 0);
        sums.add("odd", -5, -1, -1, -1);
        sums.add("held", 4, 0, 0, 0);
        assertEquals(List.of(new TileSums.Sum<>("held", 4, 0, 0, 0)), sums.result(10));
    }

    @Test
    void limitsAndSaturates() {
        TileSums<Integer> sums = new TileSums<>();
        for (int i = 0; i < 5; i++) {
            sums.add(i, 1, 0, 0, 0);
        }
        assertEquals(3, sums.result(3).size());
        TileSums<String> big = new TileSums<>();
        big.add("a", Long.MAX_VALUE, 0, 0, 0);
        big.add("a", Long.MAX_VALUE, 0, 0, 0);
        assertEquals(Long.MAX_VALUE, big.result(1).getFirst().stored());
        assertTrue(new TileSums.Sum<>("x", 0, 0, 0, 0).empty());
    }

    /** Four crafts of 4 planks each (16 to craft): what is still to send drops by 4 per craft sent. */
    @Test
    void scheduledFollowsSentCrafts() {
        assertEquals(16, TileSums.scheduled(16, 4, 0));
        assertEquals(8, TileSums.scheduled(16, 4, 2));
        assertEquals(0, TileSums.scheduled(16, 4, 4));
        assertEquals(0, TileSums.scheduled(16, 4, 9));
        // 10 to craft in 3 crafts of 4: the last craft makes more than is left, never more than the total.
        assertEquals(10, TileSums.scheduled(10, 3, 0));
        assertEquals(2, TileSums.scheduled(10, 3, 2));
        assertEquals(0, TileSums.scheduled(0, 0, 0));
        assertEquals(0, TileSums.scheduled(5, 0, 0));
    }
}
