package dev.silencebound.maxtech.lib.api.energy;

/**
 * The exchange rate between FE and MaxTech Units: {@code fe} FE are worth {@code mu} MU (Lib's server config
 * {@code energy.feRateFe} and {@code energy.feRateMu}, default 1:1). A ratio of two whole numbers, never a
 * floating-point factor, and both directions round down, so converting one way and back can lose a fraction of a unit
 * but never creates energy. For many small transfers at an uneven rate use a {@link FeConverter}, which carries the
 * fractions over. Immutable; any thread.
 *
 * @param fe
 *            FE side of the ratio, at least 1
 * @param mu
 *            MU side of the ratio, at least 1
 */
public record FeRate(int fe, int mu) {
    /** One FE is one MU, the default. */
    public static final FeRate ONE_TO_ONE = new FeRate(1, 1);

    /** Checks that both sides are at least 1; throws {@link IllegalArgumentException} otherwise. */
    public FeRate {
        if (fe < 1 || mu < 1) {
            throw new IllegalArgumentException("FE rate needs two positive numbers, got " + fe + ":" + mu);
        }
    }

    /** MU worth {@code feAmount} FE, rounded down; 0 for amounts of 0 or less. Saturates instead of overflowing. */
    public long toMu(long feAmount) {
        return feAmount <= 0 ? 0 : EnergyMath.mulDiv(feAmount, mu, fe);
    }

    /** FE worth {@code muAmount} MU, rounded down; 0 for amounts of 0 or less. Saturates instead of overflowing. */
    public long toFe(long muAmount) {
        return muAmount <= 0 ? 0 : EnergyMath.mulDiv(muAmount, fe, mu);
    }

    /** Whether one FE is one MU, so conversion never rounds. */
    public boolean isOneToOne() {
        return fe == mu;
    }

    /*
     * Exact amounts: a "unit" is 1/mu FE, which is also 1/fe MU, so both sides are whole numbers of units and an amount
     * held in units never rounds. Edges and FE handlers keep what they hold in units and round only when they hand out
     * whole FE or MU, always down, keeping the rest.
     */

    /** {@code feAmount} FE in units of {@code 1 / mu} FE; 0 for amounts of 0 or less. Saturates. */
    public long unitsOfFe(long feAmount) {
        return feAmount <= 0 ? 0 : EnergyMath.mul(feAmount, mu);
    }

    /** {@code muAmount} MU in units of {@code 1 / fe} MU; 0 for amounts of 0 or less. Saturates. */
    public long unitsOfMu(long muAmount) {
        return muAmount <= 0 ? 0 : EnergyMath.mul(muAmount, fe);
    }

    /** Whole FE in {@code units}, rounded down; 0 for 0 or less. */
    public long wholeFe(long units) {
        return units <= 0 ? 0 : units / mu;
    }

    /** Whole MU in {@code units}, rounded down; 0 for 0 or less. */
    public long wholeMu(long units) {
        return units <= 0 ? 0 : units / fe;
    }

    /** Fewest whole MU that hold at least {@code units}; 0 for 0 or less. */
    public long muCovering(long units) {
        return units <= 0 ? 0 : (units - 1) / fe + 1;
    }
}
