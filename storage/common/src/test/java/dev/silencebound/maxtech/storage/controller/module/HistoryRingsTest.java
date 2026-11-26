package dev.silencebound.maxtech.storage.controller.module;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The telemetry recorder's rings and key slots, without a network (keys are strings here). */
class HistoryRingsTest {
    /** Records one sample: series 0 gets {@code value}, every tracked key {@code value * 10}. */
    private static void sample(HistoryRings<String> rings, long value) {
        rings.begin();
        rings.put(0, value);
        for (int slot = 0; slot < rings.maxKeys(); slot++) {
            if (rings.keyAt(slot) != null) {
                rings.putKey(slot, value * 10);
            }
        }
        rings.advance();
    }

    @Test
    void readsOldestFirstAcrossTheWrap() {
        HistoryRings<String> rings = new HistoryRings<>(1, 4, 0, 100);
        long[] out = new long[8];
        assertEquals(0, rings.read(0, out), "empty");
        for (long v = 1; v <= 3; v++) {
            sample(rings, v);
        }
        assertEquals(3, rings.read(0, out));
        assertArrayEquals(new long[]{1, 2, 3}, Arrays.copyOf(out, 3), "before the wrap");
        for (long v = 4; v <= 10; v++) {
            sample(rings, v);
        }
        assertEquals(4, rings.size(), "bounded by the capacity");
        assertEquals(4, rings.read(0, out));
        assertArrayEquals(new long[]{7, 8, 9, 10}, Arrays.copyOf(out, 4), "the newest four after the wrap");
        long[] shortOut = new long[2];
        assertEquals(2, rings.read(0, shortOut));
        assertArrayEquals(new long[]{9, 10}, shortOut, "a short buffer gets the newest");
    }

    @Test
    void trackedKeysReadFromTheirFirstSample() {
        HistoryRings<String> rings = new HistoryRings<>(1, 4, 2, 100);
        long[] out = new long[4];
        sample(rings, 1);
        assertEquals(0, rings.readKey("iron", out), "untracked");
        assertTrue(rings.track("iron"));
        sample(rings, 2);
        sample(rings, 3);
        assertEquals(3, rings.readKey("iron", out));
        assertArrayEquals(new long[]{-1, 20, 30}, Arrays.copyOf(out, 3), "-1 before it was tracked");
        for (long v = 4; v <= 6; v++) {
            sample(rings, v);
        }
        assertEquals(4, rings.readKey("iron", out));
        assertArrayEquals(new long[]{30, 40, 50, 60}, out, "wrapped");
    }

    @Test
    void slotsFillUpAndFreeOnUntrack() {
        HistoryRings<String> rings = new HistoryRings<>(1, 4, 2, 100);
        long[] out = new long[4];
        assertTrue(rings.track("iron") && rings.track("gold"));
        assertTrue(rings.track("iron"), "tracking again is fine");
        assertEquals(2, rings.trackedKeys(), "one slot per key");
        assertFalse(rings.track("copper"), "full");
        sample(rings, 1);
        rings.untrack("gold");
        assertEquals(0, rings.readKey("gold", out), "untracked key reads nothing");
        assertTrue(rings.track("copper"), "room again");
        sample(rings, 2);
        assertEquals(2, rings.readKey("copper", out));
        assertArrayEquals(new long[]{-1, 20}, Arrays.copyOf(out, 2), "a reused slot starts empty");
    }

    /** A watched screen asks again every half window (its keep-alive call); its key outlives many idle windows. */
    @Test
    void keysAskedPeriodicallyStayTracked() {
        HistoryRings<String> rings = new HistoryRings<>(1, 8, 2, 4);
        long[] out = new long[8];
        assertTrue(rings.track("iron"));
        for (int i = 0; i < 40; i++) {
            if (i % 2 == 0) {
                assertTrue(rings.track("iron"), "kept at sample " + i);
            }
            sample(rings, i);
        }
        assertEquals(1, rings.trackedKeys(), "still tracked after ten idle windows");
        assertEquals(8, rings.readKey("iron", out));
        assertArrayEquals(new long[]{320, 330, 340, 350, 360, 370, 380, 390}, out, "recorded without a gap");
    }

    @Test
    void keysNobodyAsksForAreReleased() {
        HistoryRings<String> rings = new HistoryRings<>(1, 8, 2, 3);
        long[] out = new long[8];
        assertTrue(rings.track("iron") && rings.track("gold"));
        assertFalse(rings.track("copper"), "full");
        for (int i = 0; i < 2; i++) {
            sample(rings, i);
            rings.readKey("gold", out);
        }
        sample(rings, 2);
        assertEquals(2, rings.trackedKeys(), "kept within the window");
        sample(rings, 3);
        assertEquals(1, rings.trackedKeys(), "iron released after three samples unasked");
        assertEquals(0, rings.readKey("iron", out));
        assertTrue(rings.readKey("gold", out) > 0, "reading keeps gold");
        assertTrue(rings.track("copper"), "a new key gets the freed slot");
        for (int i = 4; i < 8; i++) {
            sample(rings, i);
        }
        assertEquals(0, rings.trackedKeys(), "all released once nobody asks");
    }
}
