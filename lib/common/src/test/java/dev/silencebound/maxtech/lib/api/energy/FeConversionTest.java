package dev.silencebound.maxtech.lib.api.energy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** FE conversion: floor rounding both ways, carried remainders, and that no round trip creates energy. */
class FeConversionTest {
    private static final int RUNS = 2_000;

    @Test
    void roundsDownBothWays() {
        FeRate rate = new FeRate(3, 2);
        assertEquals(6, rate.toMu(10), "10 FE at 3:2 are 6.67 MU");
        assertEquals(15, rate.toFe(10), "10 MU at 3:2 are 15 FE");
        assertEquals(0, rate.toMu(1));
        assertEquals(0, rate.toMu(-5));
        assertEquals(Long.MAX_VALUE, new FeRate(1, 1000).toMu(Long.MAX_VALUE), "saturates");
        assertTrue(FeRate.ONE_TO_ONE.isOneToOne());
        assertThrows(IllegalArgumentException.class, () -> new FeRate(0, 1));
    }

    @Test
    void roundTripsNeverGain() {
        Random random = new Random(26_1_2L);
        for (int run = 0; run < RUNS; run++) {
            FeRate rate = randomRate(random);
            long amount = randomAmount(random);
            long backFe = rate.toFe(rate.toMu(amount));
            long backMu = rate.toMu(rate.toFe(amount));
            assertTrue(backFe <= amount, rate + ": " + amount + " FE came back as " + backFe);
            assertTrue(backMu <= amount, rate + ": " + amount + " MU came back as " + backMu);
            assertEquals(exactFloor(amount, rate.mu(), rate.fe()), rate.toMu(amount), rate + " toMu " + amount);
        }
    }

    @Test
    void converterCarriesFractionsAndNeverCreatesEnergy() {
        Random random = new Random(42);
        for (int run = 0; run < RUNS / 10; run++) {
            FeRate rate = randomRate(random);
            FeConverter in = new FeConverter(rate);
            FeConverter out = new FeConverter(rate);
            long feIn = 0;
            long muMade = 0;
            long feBack = 0;
            for (int step = 0; step < 200; step++) {
                long amount = random.nextInt(50);
                long simulated = in.simulateFeToMu(amount);
                long mu = in.feToMu(amount);
                assertEquals(simulated, mu, "simulate matches");
                feIn += amount;
                muMade += mu;
                // The carried remainder makes the stream as exact as one big conversion.
                assertEquals(rate.toMu(feIn), muMade, rate + " after " + feIn + " FE");
                feBack += out.muToFe(mu);
                assertTrue(feBack <= feIn, rate + ": " + feIn + " FE in, " + feBack + " FE back");
            }
        }
    }

    @Test
    void remaindersRestoreAndDropOnRateChange() {
        FeConverter converter = new FeConverter(new FeRate(3, 1));
        assertEquals(0, converter.feToMu(2));
        long toMu = converter.toMuRemainder();
        assertEquals(2, toMu);
        assertEquals(1, converter.feToMu(1), "the carried 2 FE and 1 more make one MU");
        converter.restoreRemainders(toMu, 0);
        assertEquals(1, converter.feToMu(1), "restored remainder counts again");
        converter.restoreRemainders(99, -1);
        assertEquals(0, converter.toMuRemainder(), "invalid remainder dropped");
        converter.feToMu(2);
        converter.setRate(new FeRate(2, 1));
        assertEquals(0, converter.toMuRemainder(), "a new rate drops the remainder");
        FeConverter oneToOne = new FeConverter(FeRate.ONE_TO_ONE);
        assertEquals(7, oneToOne.feToMu(7));
        assertEquals(7, oneToOne.muToFe(7));
        assertEquals(0, oneToOne.toMuRemainder() + oneToOne.toFeRemainder());
    }

    private static FeRate randomRate(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> FeRate.ONE_TO_ONE;
            case 1 -> new FeRate(1 + random.nextInt(10), 1 + random.nextInt(10));
            case 2 -> new FeRate(1 + random.nextInt(1_000_000), 1 + random.nextInt(1_000_000));
            default -> new FeRate(1_000_000, 1 + random.nextInt(7));
        };
    }

    private static long randomAmount(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> random.nextInt(100);
            case 1 -> random.nextLong(1L << 40);
            default -> random.nextLong(Long.MAX_VALUE);
        };
    }

    private static long exactFloor(long amount, long multiply, long divide) {
        BigInteger exact = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(multiply))
                .divide(BigInteger.valueOf(divide));
        return exact.bitLength() < Long.SIZE ? exact.longValue() : Long.MAX_VALUE;
    }
}
