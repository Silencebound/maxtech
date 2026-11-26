package dev.silencebound.maxtech.storage.research.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The observer's count with the shipped defaults: a point per 20 runs, at most 200 a day. */
class ObserverTallyTest {
    private static final int RUNS = 20;
    private static final int CAP = 200;

    @Test
    void aPointEveryTwentyRuns() {
        ObserverTally tally = new ObserverTally();
        int points = 0;
        for (int i = 0; i < 19; i++) {
            points += tally.record(0, RUNS, CAP);
        }
        assertEquals(0, points);
        assertEquals(1, tally.record(0, RUNS, CAP));
        assertEquals(0, tally.runs());
        for (int i = 0; i < 39; i++) {
            points += tally.record(0, RUNS, CAP);
        }
        assertEquals(1, points);
        assertEquals(2, tally.pointsToday());
    }

    @Test
    void theCapHoldsUntilTheDayChanges() {
        ObserverTally tally = new ObserverTally();
        int points = 0;
        for (int i = 0; i < RUNS * (CAP + 5); i++) {
            points += tally.record(3, RUNS, CAP);
        }
        assertEquals(CAP, points);
        assertEquals(CAP, tally.pointsToday());
        for (int i = 0; i < RUNS - 1; i++) {
            tally.record(4, RUNS, CAP);
        }
        assertEquals(1, tally.record(4, RUNS, CAP));
        assertEquals(1, tally.pointsToday());
        assertEquals(4, tally.day());
    }

    @Test
    void aZeroCapGivesNothingAndRunsPerPointIsAtLeastOne() {
        ObserverTally tally = new ObserverTally();
        assertEquals(0, tally.record(0, 1, 0));
        assertEquals(1, tally.record(0, 0, 5));
    }
}
