package dev.silencebound.maxtech.lib.transfer;

import dev.silencebound.maxtech.lib.api.energy.FeRate;

/**
 * The part of one MU an energy port holds between FE and MU, counted exactly in units of the rate ({@code 1 / mu} FE,
 * which is {@code 1 / fe} MU; see {@link FeRate}), so FE that leave or enter the port's MU buffers are never rounded
 * away: what is not a whole MU yet stays here and counts in the next operation, in either direction. One remainder per
 * energy port, shared by pushing and pulling, always below one MU ({@code rate.fe()} units). Moves are carried out by
 * the caller in this order: ask {@link #feOut} or {@link #feIn} for the most FE, pick an amount up to that, then
 * {@link #takeOut} or {@link #putIn} says how many MU to take from or put into the buffers and keeps the rest. A
 * different rate drops the remainder, which was counted at the old one (less than one MU). Not thread-safe.
 */
final class EnergyRemainder {
    private FeRate rate = FeRate.ONE_TO_ONE;
    /** Units held; {@code 0 <= held < rate.fe()}. */
    private long held;

    /** The rate the remainder is counted at. */
    FeRate rate() {
        return rate;
    }

    /** Switches to {@code newRate}; a different rate drops the remainder. */
    void use(FeRate newRate) {
        if (!newRate.equals(rate)) {
            rate = newRate;
            held = 0;
        }
    }

    /** The units held now, for journals and saving. */
    long held() {
        return held;
    }

    /** Puts back units read from {@link #held()} at the current rate; a value out of range becomes 0. */
    void restore(long units) {
        held = units >= 0 && units < rate.fe() ? units : 0;
    }

    /** Most whole FE that can leave buffers holding {@code stockMu} MU, with the remainder. */
    long feOut(long stockMu) {
        return rate.wholeFe(add(rate.unitsOfMu(stockMu), held));
    }

    /**
     * Gives {@code fe} FE out ({@code fe <= feOut(stock)}): returns the MU to take from the buffers, the fewest that
     * cover it with the remainder, and keeps the rest of the last MU.
     */
    long takeOut(long fe) {
        if (fe <= 0) {
            return 0;
        }
        long need = rate.unitsOfFe(fe);
        long mu = need > held ? rate.muCovering(need - held) : 0;
        held = held + rate.unitsOfMu(mu) - need;
        return mu;
    }

    /** Most whole FE that can enter buffers with {@code roomMu} MU of room, with the remainder. */
    long feIn(long roomMu) {
        if (roomMu < 0) {
            return 0;
        }
        return rate.wholeFe(add(rate.unitsOfMu(roomMu), rate.fe() - 1 - held));
    }

    /**
     * Takes {@code fe} FE in ({@code fe <= feIn(room)}): returns the whole MU to put into the buffers and keeps the
     * rest as the remainder.
     */
    long putIn(long fe) {
        if (fe <= 0) {
            return 0;
        }
        long units = held + rate.unitsOfFe(fe);
        held = units % rate.fe();
        return units / rate.fe();
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
