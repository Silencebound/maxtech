package dev.silencebound.maxtech.storage.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class ValueRingTest {
    private static final int MINUTE = 1200;

    /** A sample is taken only when one is due; asking early changes nothing. */
    @Test
    void recordsOnlyWhenDue() {
        ValueRing ring = new ValueRing(MINUTE);
        assertTrue(ring.record(0, 5));
        assertFalse(ring.record(10, 6));
        assertFalse(ring.record(MINUTE - 1, 7));
        assertEquals(1, ring.size());
        assertEquals(1, ring.ticksUntilDue(MINUTE - 1));
        assertTrue(ring.record(MINUTE, 8));
        assertEquals(2, ring.size());
        assertEquals(8, ring.latest());
    }

    /** Whole intervals without a sample become gaps, never made-up values. */
    @Test
    void missedIntervalsAreGaps() {
        ValueRing ring = new ValueRing(MINUTE);
        ring.record(0, 1);
        ring.record(MINUTE * 4L, 2);
        assertEquals(5, ring.size());
        assertEquals(1, ring.fineAt(0));
        for (int i = 1; i < 4; i++) {
            assertEquals(ValueRing.NO_VALUE, ring.fineAt(i));
        }
        assertEquals(2, ring.fineAt(4));
    }

    /** The fine ring keeps the newest {@link ValueRing#CAPACITY} samples, oldest first. */
    @Test
    void keepsTheNewest() {
        ValueRing ring = new ValueRing(20);
        for (int i = 0; i < ValueRing.CAPACITY + 60; i++) {
            ring.record(i * 20L, i);
        }
        assertEquals(ValueRing.CAPACITY, ring.size());
        assertEquals(60, ring.fineAt(0));
        assertEquals(ValueRing.CAPACITY + 59, ring.latest());
    }

    /** Each coarse point is the rounded mean of {@link ValueRing#COARSE_FACTOR} fine samples, gaps left out. */
    @Test
    void coarsePointsAreMeans() {
        ValueRing ring = new ValueRing(20);
        for (int i = 1; i <= 12; i++) {
            ring.record(i * 20L, i);
        }
        assertEquals(2, ring.coarseSize());
        assertEquals(4, ring.coarseAt(0));
        assertEquals(10, ring.coarseAt(1));
        // Six samples with two gaps: the mean of the four read ones.
        ValueRing gappy = new ValueRing(20);
        gappy.record(0, 10);
        gappy.record(60, 20);
        gappy.record(80, 30);
        gappy.record(100, 40);
        assertEquals(1, gappy.coarseSize());
        assertEquals(25, gappy.coarseAt(0));
    }

    /** A one-hour window at one sample a minute is 60 points, one per sample, newest last. */
    @Test
    void chartFromTheFineRing() {
        ValueRing ring = new ValueRing(MINUTE);
        for (int i = 0; i < ValueRing.CAPACITY; i++) {
            ring.record((long) i * MINUTE, i);
        }
        long[] out = new long[60];
        assertEquals(60, ring.chart(72_000, 60, out));
        assertEquals(180, out[0]);
        assertEquals(239, out[59]);
    }

    /** 24 hours at one sample a minute come from the coarse ring, four coarse points per chart point. */
    @Test
    void chartFromTheCoarseRingForLongWindows() {
        ValueRing ring = new ValueRing(MINUTE);
        for (int i = 0; i < 24 * 60; i++) {
            ring.record((long) i * MINUTE, i);
        }
        assertEquals(ValueRing.CAPACITY, ring.coarseSize());
        long[] out = new long[60];
        assertEquals(60, ring.chart(24 * 72_000L, 60, out));
        long expected = 0;
        for (int k = 0; k < 4; k++) {
            expected += ring.coarseAt(ValueRing.CAPACITY - 1 - k);
        }
        assertEquals(Math.round(expected / 4.0), out[59]);
        // The fine ring holds only four hours; the oldest point needs the coarse one.
        assertNotEquals(ValueRing.NO_VALUE, out[0]);
        assertTrue(out[0] < out[59]);
    }

    /** Gaps and the time before the history read as no value; a long interval gives fewer points, not gaps. */
    @Test
    void chartLeavesGapsOpen() {
        ValueRing ring = new ValueRing(MINUTE);
        ring.record(0, 3);
        ring.record(MINUTE * 10L, 4);
        long[] out = new long[60];
        assertEquals(60, ring.chart(72_000, 60, out));
        assertEquals(4, out[59]);
        assertEquals(3, out[49]);
        for (int i = 50; i < 59; i++) {
            assertEquals(ValueRing.NO_VALUE, out[i]);
        }
        assertEquals(ValueRing.NO_VALUE, out[0]);
        ValueRing slow = new ValueRing(6000);
        slow.record(0, 1);
        assertEquals(12, slow.chart(72_000, 60, out));
    }

    /** The codec keeps both rings, the time and the coarse point being built, so recording goes on after a load. */
    @Test
    void codecRoundTrip() {
        ValueRing ring = new ValueRing(20);
        for (int i = 0; i < 300; i++) {
            ring.record(i * 20L, i % 7 == 0 ? ValueRing.NO_VALUE : i);
        }
        JsonElement json = ValueRing.CODEC.encodeStart(JsonOps.INSTANCE, ring).getOrThrow();
        ValueRing loaded = ValueRing.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(ring.interval(), loaded.interval());
        assertEquals(ring.lastTime(), loaded.lastTime());
        assertEquals(ring.size(), loaded.size());
        assertEquals(ring.coarseSize(), loaded.coarseSize());
        for (int i = 0; i < ring.size(); i++) {
            assertEquals(ring.fineAt(i), loaded.fineAt(i));
        }
        for (int i = 0; i < ring.coarseSize(); i++) {
            assertEquals(ring.coarseAt(i), loaded.coarseAt(i));
        }
        assertFalse(loaded.record(ring.lastTime() + 1, 1));
        for (int i = 300; i < 306; i++) {
            ring.record(i * 20L, i);
            loaded.record(i * 20L, i);
        }
        assertEquals(ring.coarseAt(ring.coarseSize() - 1), loaded.coarseAt(loaded.coarseSize() - 1));
    }
}
