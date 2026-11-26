package dev.silencebound.maxtech.storage.port;

/**
 * How much a port may move of one kind (items, or mB of fluid): a credit that grows by the port's rate per second, up
 * to one second's worth, and shrinks by what the port moved. The port moves once a full step has built up, a step being
 * its rate per second or one batch, whichever is smaller, so it moves its rate in as few operations as the batch size
 * allows (PRD 7.2, Performance: batch transfers). The credit is brought up to date from the game time when read, so a
 * sleeping port costs nothing. Server thread only; pure arithmetic, so it is unit-tested.
 */
public final class PortRate {
    static final int TICKS_PER_SECOND = 20;

    /** Credit in twentieths of a unit, so a rate below 20 per second still grows every tick. */
    private long credit = -1;
    private long stamp;

    /** The amount one operation waits for and moves: the rate per second, but at most one batch, at least 1. */
    public static long step(long perSecond, long batch) {
        return Math.max(1, Math.min(perSecond, batch));
    }

    /**
     * What may move at {@code gameTime}, whole units. A new port starts with a full second; a game time before the last
     * read (a clock set back) adds nothing.
     */
    public long available(long gameTime, long perSecond) {
        long cap = Math.max(1, perSecond) * TICKS_PER_SECOND;
        if (credit < 0) {
            credit = cap;
        } else if (gameTime > stamp) {
            long ticks = gameTime - stamp;
            // Saturates instead of overflowing after a long sleep.
            credit = ticks >= TICKS_PER_SECOND ? cap : Math.min(cap, credit + ticks * Math.max(1, perSecond));
        }
        credit = Math.min(credit, cap);
        stamp = Math.max(stamp, gameTime);
        return credit / TICKS_PER_SECOND;
    }

    /** Takes {@code units} that moved off the credit; call after {@link #available} on the same tick. */
    public void spend(long units) {
        credit = Math.max(0, credit - units * TICKS_PER_SECOND);
    }

    /** Ticks until {@code step} units are available again, at least 1. */
    public int ticksUntil(long step, long perSecond) {
        long missing = step * TICKS_PER_SECOND - Math.max(0, credit);
        if (missing <= 0) {
            return 1;
        }
        long rate = Math.max(1, perSecond);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (missing + rate - 1) / rate));
    }
}
