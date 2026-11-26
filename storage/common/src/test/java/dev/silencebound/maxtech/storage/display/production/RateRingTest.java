package dev.silencebound.maxtech.storage.display.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The per-minute rate math of the production stats: minute buckets, the sliding estimate, history and top lists. */
class RateRingTest {
    private static final long M = RateRing.MINUTE;

    @Test
    void rateInTheFirstMinuteIsWhatWasCounted() {
        RateRing ring = new RateRing(M, 16);
        ring.add(100, 10);
        ring.add(50, 500);
        assertEquals(150, ring.perMinute(600));
    }

    @Test
    void thePreviousMinuteFadesOutOverTheNextOne() {
        RateRing ring = new RateRing(M, 16);
        ring.add(600, 100);
        assertEquals(600, ring.perMinute(M), "just after the roll the old minute counts whole");
        assertEquals(300, ring.perMinute(M + M / 2), "halfway it counts half");
        assertEquals(0, ring.perMinute(2 * M), "a minute later it is gone");
    }

    @Test
    void aSteadyFlowReadsTheSameAtAnyPointInTheMinute() {
        RateRing ring = new RateRing(M, 16);
        for (long t = 0; t < 5 * M; t += 20) {
            ring.add(10, t);
        }
        // 10 items every 20 ticks is 600 a minute.
        assertEquals(600, ring.perMinute(5 * M), "ten items every 20 ticks is 600 a minute");
    }

    @Test
    void historyListsFinishedMinutesOldestFirst() {
        RateRing ring = new RateRing(M, 16);
        ring.add(10, 0);
        ring.add(20, M);
        ring.add(30, 2 * M);
        long[] out = new long[15];
        assertEquals(15, ring.history(2 * M + 5, out));
        assertEquals(0, out[0]);
        assertEquals(10, out[13]);
        assertEquals(20, out[14], "the newest finished minute is last");
    }

    @Test
    void aGapLongerThanTheRingClearsEverything() {
        RateRing ring = new RateRing(M, 4);
        ring.add(99, 0);
        assertFalse(ring.idle(M));
        assertTrue(ring.idle(10 * M));
        assertEquals(0, ring.perMinute(10 * M));
    }

    @Test
    void timeGoingBackDoesNotLoseTheCount() {
        RateRing ring = new RateRing(M, 16);
        ring.add(60, 5 * M);
        ring.add(60, 5 * M - 100);
        assertEquals(120, ring.perMinute(5 * M));
    }

    @Test
    void historyOutputShorterThanTheRingKeepsTheNewest() {
        RateRing ring = new RateRing(M, 16);
        for (int minute = 0; minute < 6; minute++) {
            ring.add(minute + 1, minute * M);
        }
        long[] out = new long[3];
        assertEquals(3, ring.history(5 * M + 1, out));
        assertArrayEquals(new long[]{3, 4, 5}, out);
    }

    @Test
    void keyRatesRankTheBusiestKeysAndForgetQuietOnes() {
        KeyRates<String> rates = new KeyRates<>(M);
        rates.add("cobble", 900, 10);
        rates.add("sand", 300, 20);
        rates.add("dirt", 600, 30);
        String[] keys = new String[2];
        long[] values = new long[2];
        assertEquals(2, rates.top(100, keys, values));
        assertArrayEquals(new String[]{"cobble", "dirt"}, keys);
        assertArrayEquals(new long[]{900, 600}, values);
        assertEquals(1800, rates.totalPerMinute(100));
        // Two minutes without a move: nothing left.
        assertEquals(0, rates.top(3 * M, keys, values));
        assertEquals(0, rates.totalPerMinute(3 * M));
    }

    @Test
    void keyRatesStopCountingNewKeysPastTheCap() {
        KeyRates<Integer> rates = new KeyRates<>(M);
        for (int i = 0; i < KeyRates.MAX_KEYS + 10; i++) {
            rates.add(i, 5, 10);
        }
        rates.add(0, 5, 20);
        assertEquals(10, rates.perMinute(0, 30));
        assertEquals(0, rates.perMinute(KeyRates.MAX_KEYS + 5, 30));
    }
}
