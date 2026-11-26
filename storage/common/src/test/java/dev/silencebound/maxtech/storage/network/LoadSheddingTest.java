package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The load shedding level as a pure function (M7L2 row 21): thresholds 25/15/5 % for priorities 5/4/3, priority 2 off,
 * a 10 % resume margin, on a 10,000 FE network. Hysteresis, the band the level holds in, and the arming level.
 */
class LoadSheddingTest {
    private static final int[] THRESHOLDS = {0, 0, 0, 5, 15, 25};
    private static final int MARGIN = 10;
    private static final long CAPACITY = 10_000;
    private static final int NONE = LoadShedding.NONE;

    private static int level(int current, long stored) {
        return LoadShedding.pausedFrom(THRESHOLDS, MARGIN, current, stored, CAPACITY);
    }

    @Test
    void fallingPausesLowestFirst() {
        assertEquals(NONE, level(NONE, 2_500), "at 25 % nothing pauses");
        assertEquals(5, level(NONE, 2_499), "below 25 % priority 5");
        assertEquals(5, level(5, 1_500), "at 15 % still only 5");
        assertEquals(4, level(5, 1_499), "below 15 % priority 4");
        assertEquals(3, level(4, 499), "below 5 % priority 3");
        assertEquals(3, level(3, 0), "priority 2 is off: 0 FE pauses 3 to 5 only");
    }

    @Test
    void risingResumesOnlyAtThresholdPlusMargin() {
        assertEquals(3, level(3, 1_499), "priority 3 waits for 15 %");
        assertEquals(4, level(3, 1_500), "at 15 % priority 3 resumes, 4 waits for 25 %");
        assertEquals(4, level(4, 2_499), "4 still paused below 25 %");
        assertEquals(5, level(4, 2_500), "at 25 % priority 4 resumes, 5 waits for 35 %");
        assertEquals(5, level(5, 3_499), "5 still paused below 35 %");
        assertEquals(NONE, level(5, 3_500), "at 35 % everything runs");
        assertEquals(NONE, level(NONE, 3_000), "not paused at 30 %, so 30 % pauses nothing");
    }

    @Test
    void bandMatchesTheLevel() {
        assertEquals(2_500, LoadShedding.bandLow(THRESHOLDS, NONE, CAPACITY), "nothing paused: down to 25 %");
        assertEquals(Long.MAX_VALUE, LoadShedding.bandHigh(THRESHOLDS, MARGIN, NONE, CAPACITY), "no top");
        assertEquals(1_500, LoadShedding.bandLow(THRESHOLDS, 5, CAPACITY), "5 paused: down to 15 %");
        assertEquals(3_500, LoadShedding.bandHigh(THRESHOLDS, MARGIN, 5, CAPACITY), "5 paused: up to 35 %");
        assertEquals(Long.MIN_VALUE, LoadShedding.bandLow(THRESHOLDS, 3, CAPACITY), "3 paused: no bottom");
        assertEquals(1_500, LoadShedding.bandHigh(THRESHOLDS, MARGIN, 3, CAPACITY), "3 paused: up to 15 %");
        // Every value inside a band keeps its level.
        for (int current : new int[]{NONE, 5, 4, 3}) {
            long low = Math.max(0, LoadShedding.bandLow(THRESHOLDS, current, CAPACITY));
            long high = Math.min(CAPACITY + 1, LoadShedding.bandHigh(THRESHOLDS, MARGIN, current, CAPACITY));
            for (long stored = low; stored < high; stored += 7) {
                assertEquals(current, level(current, stored), "level " + current + " at " + stored);
            }
        }
    }

    @Test
    void armsAtTheHighestResumeLevel() {
        assertEquals(35, LoadShedding.armPercent(THRESHOLDS, MARGIN));
        assertEquals(0, LoadShedding.armPercent(new int[6], MARGIN), "every threshold off: never arms");
        assertEquals(100, LoadShedding.armPercent(new int[]{0, 0, 0, 0, 0, 95}, MARGIN), "capped at 100 %");
    }
}
