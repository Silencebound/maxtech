package dev.silencebound.maxtech.lib.api.energy;

/**
 * Converts a stream of transfers between FE and MU at a {@link FeRate}, carrying the fraction of a unit that rounding
 * cut off over to the next transfer, so many small transfers at an uneven rate lose less than one unit in total instead
 * of up to one per transfer. Never creates energy: the MU handed out never exceed the FE taken in at the rate, and the
 * other way round. One converter per edge (such as one FE neighbour); the two directions keep separate remainders.
 * <p>
 * The remainders are plain state: a caller inside a transfer transaction journals them with {@link #toMuRemainder()}
 * and {@link #toFeRemainder()} and puts them back with {@link #restoreRemainders} on abort. They are not meant to be
 * saved; dropping them loses less than one unit per direction. Not thread-safe; use it from one thread.
 */
public final class FeConverter {
    private FeRate rate;
    /** FE-to-MU fraction carried over, in units of {@code 1 / rate.fe()} MU; always below {@code rate.fe()}. */
    private long toMuRemainder;
    /** MU-to-FE fraction carried over, in units of {@code 1 / rate.mu()} FE; always below {@code rate.mu()}. */
    private long toFeRemainder;

    /** A converter at {@code rate} with no remainders. */
    public FeConverter(FeRate rate) {
        this.rate = rate;
    }

    /** The current rate. */
    public FeRate rate() {
        return rate;
    }

    /** Switches to {@code newRate}; a different rate drops both remainders, as they were counted at the old one. */
    public void setRate(FeRate newRate) {
        if (!newRate.equals(rate)) {
            rate = newRate;
            toMuRemainder = 0;
            toFeRemainder = 0;
        }
    }

    /** Converts {@code feAmount} FE that came in to MU, rounding down and carrying the rest; 0 for amounts below 1. */
    public long feToMu(long feAmount) {
        if (feAmount <= 0) {
            return 0;
        }
        long partial = feAmount % rate.fe() * rate.mu() + toMuRemainder;
        toMuRemainder = partial % rate.fe();
        return whole(feAmount, rate.fe(), rate.mu(), partial);
    }

    /** What {@link #feToMu} would return now, without changing the remainder. */
    public long simulateFeToMu(long feAmount) {
        if (feAmount <= 0) {
            return 0;
        }
        return whole(feAmount, rate.fe(), rate.mu(), feAmount % rate.fe() * rate.mu() + toMuRemainder);
    }

    /** Converts {@code muAmount} MU that go out to FE, rounding down and carrying the rest; 0 for amounts below 1. */
    public long muToFe(long muAmount) {
        if (muAmount <= 0) {
            return 0;
        }
        long partial = muAmount % rate.mu() * rate.fe() + toFeRemainder;
        toFeRemainder = partial % rate.mu();
        return whole(muAmount, rate.mu(), rate.fe(), partial);
    }

    /** What {@link #muToFe} would return now, without changing the remainder. */
    public long simulateMuToFe(long muAmount) {
        if (muAmount <= 0) {
            return 0;
        }
        return whole(muAmount, rate.mu(), rate.fe(), muAmount % rate.mu() * rate.fe() + toFeRemainder);
    }

    /** The FE-to-MU fraction carried over, in units of {@code 1 / rate().fe()} MU; for journaling. */
    public long toMuRemainder() {
        return toMuRemainder;
    }

    /** The MU-to-FE fraction carried over, in units of {@code 1 / rate().mu()} FE; for journaling. */
    public long toFeRemainder() {
        return toFeRemainder;
    }

    /**
     * Puts back remainders read from {@link #toMuRemainder()} and {@link #toFeRemainder()} at the current rate, such as
     * when a transaction aborts. A value outside the valid range becomes 0, which can only lose a fraction.
     */
    public void restoreRemainders(long toMu, long toFe) {
        toMuRemainder = toMu >= 0 && toMu < rate.fe() ? toMu : 0;
        toFeRemainder = toFe >= 0 && toFe < rate.mu() ? toFe : 0;
    }

    /**
     * {@code floor(amount * to / from)} with the carried fraction: the whole part {@code (amount / from) * to}
     * (saturating) plus {@code partial / from}, where {@code partial} is the rest of the amount times {@code to} plus
     * the remainder. Every product stays below {@code 2^63}, since {@code partial < from * to + from}.
     */
    private static long whole(long amount, int from, int to, long partial) {
        return EnergyMath.add(EnergyMath.mul(amount / from, to), partial / from);
    }
}
