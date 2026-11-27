package dev.silencebound.maxtech.farms.hub;

/**
 * The lab hub's fuel generator as plain numbers: one burning fuel item turns into MU at a fixed rate per burn tick,
 * credited in steps instead of every tick. A step pays for the ticks since the last one, at most the burn left and at
 * most what fits; ticks that did not fit are not burned, so a full hub pauses its fuel instead of wasting it. Time
 * spent paused (or unloaded) never counts: {@link #pause()} makes the next step start the clock again. Not thread-safe.
 */
public final class FuelBurner {
    private int burnLeft;
    private int burnTotal;
    /** Game tick the burn was paid up to; {@code Long.MIN_VALUE} while paused. */
    private long paidTo = Long.MIN_VALUE;

    /** Ticks the current fuel item still burns; 0 when nothing burns. */
    public int burnLeft() {
        return burnLeft;
    }

    /** Ticks the current fuel item burned in total; 0 when nothing burns. */
    public int burnTotal() {
        return burnLeft > 0 ? burnTotal : 0;
    }

    /** Whether a fuel item is burning. */
    public boolean isBurning() {
        return burnLeft > 0;
    }

    /** Starts burning a fuel item of {@code ticks} burn time at game tick {@code now}; nothing for 0 or less. */
    public void start(int ticks, long now) {
        if (ticks > 0) {
            burnLeft = ticks;
            burnTotal = ticks;
            paidTo = now;
        }
    }

    /** Stops counting time until the next {@link #step}, which then only restarts the clock. */
    public void pause() {
        paidTo = Long.MIN_VALUE;
    }

    /**
     * Pays the burn from the last step up to {@code now}: returns the MU made, at most {@code room} and in whole burn
     * ticks of {@code muPerTick} each. Ticks that would not fit are dropped from the elapsed time, not burned.
     */
    public long step(long now, long room, long muPerTick) {
        if (burnLeft <= 0 || muPerTick <= 0) {
            return 0;
        }
        if (paidTo == Long.MIN_VALUE || now <= paidTo) {
            paidTo = Math.max(paidTo, now);
            return 0;
        }
        long ticks = Math.min(now - paidTo, burnLeft);
        long fit = Math.max(0, room) / muPerTick;
        if (fit < ticks) {
            ticks = fit;
        }
        paidTo = now;
        burnLeft -= (int) ticks;
        return ticks * muPerTick;
    }

    /** Restores a saved burn; the clock starts at the next step, so time while unloaded never burns. */
    public void load(int left, int total) {
        burnLeft = Math.max(0, left);
        burnTotal = Math.max(burnLeft, total);
        paidTo = Long.MIN_VALUE;
    }
}
