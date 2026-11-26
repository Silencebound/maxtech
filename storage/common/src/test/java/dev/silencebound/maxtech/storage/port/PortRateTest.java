package dev.silencebound.maxtech.storage.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortRateTest {
    @Test
    void stepIsTheRateCappedAtABatch() {
        assertEquals(16, PortRate.step(16, 64), "tier 1 moves its second's worth at once");
        assertEquals(64, PortRate.step(256, 64), "tier 3 moves four batches a second");
        assertEquals(1000, PortRate.step(1000, 1000));
        assertEquals(1, PortRate.step(0, 64), "at least one");
    }

    @Test
    void aNewPortStartsWithOneSecond() {
        PortRate rate = new PortRate();
        assertEquals(16, rate.available(100, 16));
    }

    @Test
    void creditRefillsPerTickUpToOneSecond() {
        PortRate rate = new PortRate();
        rate.available(0, 16);
        rate.spend(16);
        assertEquals(0, rate.available(0, 16));
        assertEquals(20, rate.ticksUntil(16, 16), "a full step after one second");
        assertEquals(8, rate.available(10, 16), "half a second, half the rate");
        assertEquals(16, rate.available(1_000, 16), "never more than one second's worth");
    }

    @Test
    void slowRatesStillGrowEveryTick() {
        PortRate rate = new PortRate();
        rate.available(0, 3);
        rate.spend(3);
        assertEquals(0, rate.available(6, 3), "18 twentieths are not a whole item yet");
        assertEquals(1, rate.available(7, 3));
        assertEquals(13, rate.ticksUntil(3, 3), "39 twentieths short at 3 a tick");
    }

    @Test
    void steadyStateMatchesTheRate() {
        PortRate rate = new PortRate();
        long moved = 0;
        long time = 0;
        rate.available(time, 16);
        // Drain the start credit, then run as the port does: wait for a step, move it.
        rate.spend(16);
        while (time < 20 * 60) {
            time += rate.ticksUntil(PortRate.step(16, 64), 16);
            long available = rate.available(time, 16);
            assertTrue(available >= 16, "woke with a full step");
            rate.spend(16);
            moved += 16;
        }
        assertEquals(16 * 60, moved, "a minute at 16 per second");
    }

    @Test
    void aClockSetBackAddsNothing() {
        PortRate rate = new PortRate();
        rate.available(100, 16);
        rate.spend(16);
        assertEquals(0, rate.available(50, 16));
        assertEquals(0, rate.available(100, 16), "the clock catching up again adds nothing either");
    }

    @Test
    void spendingNeverGoesNegative() {
        PortRate rate = new PortRate();
        rate.available(0, 16);
        rate.spend(1_000);
        assertEquals(0, rate.available(0, 16));
        assertEquals(1, rate.available(2, 16), "two ticks of credit from zero");
    }
}
