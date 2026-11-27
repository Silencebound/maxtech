package dev.silencebound.maxtech.farms.lab;

import dev.silencebound.maxtech.lib.api.conduit.ConduitContents;

/**
 * The fill step lab tubes show for the hub tank's level: 0 only when the tank is empty, else 1 to
 * {@link ConduitContents#FULL} by quarters, rounded to the nearest. A step changes only once the level is a margin
 * ({@link #MARGIN_DIVISOR}th of the capacity) past the boundary, so a tank hovering at a boundary does not redraw every
 * tube of the lab each time it moves. Pure.
 */
final class TubeFill {
    /** The margin past a boundary is the capacity divided by this: half a step's eighth either side. */
    static final int MARGIN_DIVISOR = 32;

    private TubeFill() {
    }

    /**
     * The step to show for {@code amount} of {@code capacity}, coming from the {@code shown} step.
     *
     * @param shown
     *            the step the tubes show now, 0 to {@link ConduitContents#FULL}
     */
    static int step(long amount, long capacity, int shown) {
        if (amount <= 0) {
            return 0;
        }
        if (capacity <= 0) {
            return ConduitContents.FULL;
        }
        int up = nominal(amount, capacity, -1);
        if (shown < up) {
            return up;
        }
        int down = nominal(amount, capacity, 1);
        return Math.min(shown, down);
    }

    /**
     * The step for {@code amount} moved by the margin ({@code side} -1 below, 1 above): 1, plus one for each boundary
     * at 3/8, 5/8 and 7/8 of the capacity it reaches.
     */
    private static int nominal(long amount, long capacity, int side) {
        // Compared in 32nds of the capacity: boundary k sits at (2k - 1) * 4 / 32, the margin is 1 / 32.
        long scaled = amount * MARGIN_DIVISOR + side * capacity;
        int step = 1;
        for (int k = 2; k <= ConduitContents.FULL; k++) {
            if (scaled >= (2L * k - 1) * 4 * capacity) {
                step = k;
            }
        }
        return step;
    }
}
