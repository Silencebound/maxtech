package dev.silencebound.maxtech.storage.priority;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PriorityControlTest {
    private static final int[] SAMPLES = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1_000_000, -999, -1, 0, 1, 999,
            65_535, 65_536, 1_000_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};

    @Test
    void rangeIsTheWholeIntRange() {
        assertEquals(Integer.MIN_VALUE, PriorityControl.MIN);
        assertEquals(Integer.MAX_VALUE, PriorityControl.MAX);
    }

    @Test
    void sixteenBitHalvesRoundTripEveryInt() {
        for (int priority : SAMPLES) {
            int low = PriorityControl.low(priority);
            int high = PriorityControl.high(priority);
            // A data slot syncs a short; the client may read either half sign extended.
            assertEquals(priority, PriorityControl.join(low, high), "plain " + priority);
            assertEquals(priority, PriorityControl.join((short) low, (short) high), "signed " + priority);
        }
    }

    @Test
    void stepsSaturateInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, PriorityControl.add(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, PriorityControl.add(Integer.MAX_VALUE - 5, 100));
        assertEquals(Integer.MIN_VALUE, PriorityControl.add(Integer.MIN_VALUE, -1));
        assertEquals(Integer.MIN_VALUE, PriorityControl.add(Integer.MIN_VALUE + 5, -100));
        assertEquals(1_099, PriorityControl.add(999, 100));
    }
}
