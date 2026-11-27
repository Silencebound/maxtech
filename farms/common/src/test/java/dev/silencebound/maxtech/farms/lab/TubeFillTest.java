package dev.silencebound.maxtech.farms.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Tubes show the hub tank's level in five steps, with a margin around each boundary. */
class TubeFillTest {
    private static final long CAPACITY = 16_000;
    private static final long MARGIN = CAPACITY / TubeFill.MARGIN_DIVISOR;

    @Test
    void emptyOnlyWhenEmpty() {
        assertEquals(0, TubeFill.step(0, CAPACITY, 3));
        assertEquals(1, TubeFill.step(1, CAPACITY, 0));
        assertEquals(1, TubeFill.step(1, CAPACITY, 2));
    }

    @Test
    void nearestQuarterFromEmpty() {
        assertEquals(1, TubeFill.step(CAPACITY / 4, CAPACITY, 0));
        assertEquals(2, TubeFill.step(CAPACITY / 2, CAPACITY, 0));
        assertEquals(3, TubeFill.step(CAPACITY * 3 / 4, CAPACITY, 0));
        assertEquals(4, TubeFill.step(CAPACITY, CAPACITY, 0));
    }

    @Test
    void stepsUpOnlyPastTheMargin() {
        long boundary = CAPACITY * 3 / 8;
        assertEquals(1, TubeFill.step(boundary, CAPACITY, 1));
        assertEquals(1, TubeFill.step(boundary + MARGIN - 1, CAPACITY, 1));
        assertEquals(2, TubeFill.step(boundary + MARGIN, CAPACITY, 1));
    }

    @Test
    void stepsDownOnlyPastTheMargin() {
        long boundary = CAPACITY * 5 / 8;
        assertEquals(3, TubeFill.step(boundary, CAPACITY, 3));
        assertEquals(3, TubeFill.step(boundary - MARGIN, CAPACITY, 3));
        assertEquals(2, TubeFill.step(boundary - MARGIN - 1, CAPACITY, 3));
    }

    @Test
    void hoveringAtABoundaryKeepsTheStep() {
        long boundary = CAPACITY * 3 / 8;
        for (int shown = 1; shown <= 2; shown++) {
            for (long amount = boundary - MARGIN / 2; amount <= boundary + MARGIN / 2; amount++) {
                assertEquals(shown, TubeFill.step(amount, CAPACITY, shown));
            }
        }
    }

    @Test
    void bigJumpsGoStraightThere() {
        assertEquals(4, TubeFill.step(CAPACITY, CAPACITY, 1));
        assertEquals(1, TubeFill.step(10, CAPACITY, 4));
    }

    @Test
    void fullTankIsFull() {
        assertEquals(4, TubeFill.step(CAPACITY, CAPACITY, 3));
        assertEquals(4, TubeFill.step(CAPACITY - MARGIN, CAPACITY, 3));
    }
}
