package dev.silencebound.maxtech.lib.api.energy;

/**
 * Overflow-safe arithmetic on non-negative MU amounts; results saturate at {@link Long#MAX_VALUE}, never wrap. Nothing
 * here allocates.
 */
final class EnergyMath {
    private EnergyMath() {
    }

    /**
     * {@code floor(a * b / c)} for {@code a, b >= 0} and {@code c > 0}, saturating at {@link Long#MAX_VALUE}. Exact:
     * when {@code a * b} does not fit in a long, the 128-bit product is divided bit by bit (64 steps, no allocation).
     */
    static long mulDiv(long a, long b, long c) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        if (high == 0 && low >= 0) {
            return low / c;
        }
        if (high >= c) {
            return Long.MAX_VALUE; // the quotient needs more than 64 bits
        }
        // Shift-subtract division of high:low by c. The remainder stays below c < 2^63, so shifting it left once
        // fits in 64 unsigned bits.
        long remainder = high;
        long quotient = 0;
        for (int bit = 63; bit >= 0; bit--) {
            remainder = (remainder << 1) | ((low >>> bit) & 1);
            quotient <<= 1;
            if (Long.compareUnsigned(remainder, c) >= 0) {
                remainder -= c;
                quotient |= 1;
            }
        }
        return quotient < 0 ? Long.MAX_VALUE : quotient;
    }

    /** {@code a * b} for {@code a, b >= 0}, saturating at {@link Long#MAX_VALUE}. */
    static long mul(long a, long b) {
        long low = a * b;
        return Math.multiplyHigh(a, b) == 0 && low >= 0 ? low : Long.MAX_VALUE;
    }

    /** {@code a + b} for {@code a, b >= 0}, saturating at {@link Long#MAX_VALUE}. */
    static long add(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
