package dev.silencebound.maxtech.farms.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A fuel item gives exactly its burn time in MU, pauses instead of wasting when full, and never burns while paused. */
class FuelBurnerTest {
    private static final long RATE = 20;

    @Test
    void burnsWholeFuelInSteps() {
        FuelBurner burner = new FuelBurner();
        burner.start(100, 0);
        long made = 0;
        for (long now = 20; now <= 120; now += 20) {
            made += burner.step(now, Long.MAX_VALUE, RATE);
        }
        assertEquals(100 * RATE, made);
        assertFalse(burner.isBurning());
        assertEquals(0, burner.step(200, Long.MAX_VALUE, RATE));
    }

    @Test
    void fullBufferPausesInsteadOfWasting() {
        FuelBurner burner = new FuelBurner();
        burner.start(100, 0);
        // Room for 5 ticks only; the other 15 elapsed ticks are not burned.
        assertEquals(5 * RATE, burner.step(20, 5 * RATE + RATE - 1, RATE));
        assertEquals(95, burner.burnLeft());
        assertEquals(0, burner.step(40, 0, RATE));
        assertEquals(95, burner.burnLeft());
    }

    @Test
    void pausedTimeNeverBurns() {
        FuelBurner burner = new FuelBurner();
        burner.start(100, 0);
        burner.step(10, Long.MAX_VALUE, RATE);
        burner.pause();
        assertEquals(0, burner.step(1_000, Long.MAX_VALUE, RATE));
        assertEquals(90, burner.burnLeft());
        assertEquals(10 * RATE, burner.step(1_010, Long.MAX_VALUE, RATE));
    }

    @Test
    void loadedBurnRestartsTheClock() {
        FuelBurner burner = new FuelBurner();
        burner.load(40, 1600);
        assertTrue(burner.isBurning());
        assertEquals(1600, burner.burnTotal());
        assertEquals(0, burner.step(5_000, Long.MAX_VALUE, RATE));
        assertEquals(40 * RATE, burner.step(5_100, Long.MAX_VALUE, RATE));
    }
}
