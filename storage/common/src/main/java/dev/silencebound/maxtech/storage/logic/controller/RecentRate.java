package dev.silencebound.maxtech.storage.logic.controller;

/**
 * Checks (or action points) per second one rule paid recently, for the admin overview (M7L2 chunk 23). Game time is cut
 * into windows of {@link #WINDOW_TICKS}; the rate is the checks of the window before this one and of this one so far,
 * over the time they cover, so it looks back between 10 and 20 seconds. Updated where a run is charged, with a few long
 * fields and no allocation; read only when an admin asks. Server thread.
 */
public final class RecentRate {
    /** Ticks per window (10 seconds). */
    public static final int WINDOW_TICKS = 200;
    private static final double TICKS_PER_SECOND = 20.0;

    private long window = Long.MIN_VALUE;
    private long checks;
    private long previous;

    /** Counts {@code cost} checks paid at {@code gameTime}. */
    public void add(int cost, long gameTime) {
        long now = gameTime / WINDOW_TICKS;
        if (now != window) {
            previous = now == window + 1 ? checks : 0;
            window = now;
            checks = 0;
        }
        checks += cost;
    }

    /** The checks per second over the last window and this one so far, as of {@code gameTime}; 0 when idle since. */
    public double perSecond(long gameTime) {
        long now = gameTime / WINDOW_TICKS;
        long counted;
        if (now == window) {
            counted = previous + checks;
        } else if (now == window + 1) {
            counted = checks;
        } else {
            return 0;
        }
        long span = WINDOW_TICKS + gameTime % WINDOW_TICKS;
        return counted * TICKS_PER_SECOND / span;
    }
}
