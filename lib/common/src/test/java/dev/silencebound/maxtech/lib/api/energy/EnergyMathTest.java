package dev.silencebound.maxtech.lib.api.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The allocation-free 128-bit {@code mulDiv} against exact BigInteger arithmetic. */
class EnergyMathTest {
    @Test
    void mulDivMatchesExactArithmetic() {
        Random random = new Random(26_1_2L);
        for (int run = 0; run < 100_000; run++) {
            long a = sample(random);
            long b = sample(random);
            long c = 1 + random.nextLong(sample(random) == 0 ? 1 : Long.MAX_VALUE);
            assertEquals(exact(a, b, c), EnergyMath.mulDiv(a, b, c), a + " * " + b + " / " + c);
        }
        assertEquals(Long.MAX_VALUE, EnergyMath.mulDiv(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, EnergyMath.mulDiv(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, EnergyMath.mulDiv(Long.MAX_VALUE, 2, 1), "saturates");
    }

    private static long sample(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> random.nextInt(1_000);
            case 1 -> random.nextLong(1L << 40);
            default -> random.nextLong(Long.MAX_VALUE);
        };
    }

    private static long exact(long a, long b, long c) {
        BigInteger result = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c));
        return result.bitLength() < Long.SIZE ? result.longValue() : Long.MAX_VALUE;
    }
}
