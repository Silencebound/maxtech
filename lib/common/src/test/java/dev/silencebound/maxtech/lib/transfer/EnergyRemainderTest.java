package dev.silencebound.maxtech.lib.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link EnergyRemainder}: energy counted in units ({@code stored MU * fe + remainder + outside FE * mu}) never changes
 * over many operations at uneven rates, in either direction or mixed, and the remainder stays below one MU.
 */
class EnergyRemainderTest {
    private static final FeRate[] RATES = {FeRate.ONE_TO_ONE, new FeRate(3, 7), new FeRate(7, 3), new FeRate(1, 1000),
            new FeRate(1000, 1), new FeRate(999_983, 999_979)};

    @Test
    void pushesThreeFeForSevenMuOneByOne() {
        EnergyRemainder remainder = new EnergyRemainder();
        remainder.use(new FeRate(3, 7));
        assertEquals(3, remainder.takeOut(1));
        assertEquals(2, remainder.takeOut(1));
        assertEquals(2, remainder.takeOut(1));
        assertEquals(0, remainder.held(), "3 FE are exactly 7 MU");
    }

    @Test
    void pullsSevenFeForThreeMuOneByOne() {
        EnergyRemainder remainder = new EnergyRemainder();
        remainder.use(new FeRate(7, 3));
        long mu = 0;
        for (int i = 0; i < 7; i++) {
            mu += remainder.putIn(1);
        }
        assertEquals(3, mu, "7 FE are exactly 3 MU");
        assertEquals(0, remainder.held());
    }

    @Test
    void manySmallPushesDoNotDrift() {
        for (FeRate rate : RATES) {
            EnergyRemainder remainder = new EnergyRemainder();
            remainder.use(rate);
            long stored = 10_000_000;
            long outside = 0;
            for (int i = 0; i < 100_000 && remainder.feOut(stored) > 0; i++) {
                stored -= remainder.takeOut(1);
                outside++;
            }
            assertTrue(stored >= 0, rate + ": stored below 0");
            assertEquals(10_000_000L * rate.fe(), stored * rate.fe() + remainder.held() + outside * rate.mu(),
                    rate + ": units after pushes");
        }
    }

    @Test
    void manySmallPullsDoNotDrift() {
        for (FeRate rate : RATES) {
            EnergyRemainder remainder = new EnergyRemainder();
            remainder.use(rate);
            long capacity = 10_000_000;
            long stored = 0;
            long outside = 100_000;
            while (outside > 0 && remainder.feIn(capacity - stored) > 0) {
                stored += remainder.putIn(1);
                outside--;
            }
            assertTrue(stored <= capacity, rate + ": stored above capacity");
            assertEquals(100_000L * rate.mu(), stored * rate.fe() + remainder.held() + outside * rate.mu(),
                    rate + ": units after pulls");
        }
    }

    /** Random pushes and pulls up to what fits: totals in units never change, stock and room are kept exactly. */
    @Test
    void mixedDirectionsKeepEveryUnit() {
        Random random = new Random(18);
        for (FeRate rate : RATES) {
            EnergyRemainder remainder = new EnergyRemainder();
            remainder.use(rate);
            long capacity = 50_000;
            long stored = 20_000;
            long outside = 30_000;
            long total = stored * rate.fe() + outside * rate.mu();
            for (int i = 0; i < 200_000; i++) {
                if (random.nextBoolean()) {
                    long fe = Math.min(remainder.feOut(stored), 1 + random.nextInt(50));
                    long mu = remainder.takeOut(fe);
                    assertTrue(mu <= stored, rate + ": took more MU than stored");
                    stored -= mu;
                    outside += fe;
                } else {
                    long fe = Math.min(Math.min(remainder.feIn(capacity - stored), outside), 1 + random.nextInt(50));
                    long mu = remainder.putIn(fe);
                    stored += mu;
                    outside -= fe;
                    assertTrue(stored <= capacity, rate + ": put in more MU than room");
                }
                assertTrue(remainder.held() >= 0 && remainder.held() < rate.fe(), rate + ": remainder out of range");
                assertEquals(total, stored * rate.fe() + remainder.held() + outside * rate.mu(), rate + ": units");
            }
        }
    }

    @Test
    void aNewRateDropsTheRemainder() {
        EnergyRemainder remainder = new EnergyRemainder();
        remainder.use(new FeRate(3, 7));
        remainder.takeOut(1);
        assertEquals(2, remainder.held());
        remainder.use(new FeRate(3, 7));
        assertEquals(2, remainder.held(), "same rate");
        remainder.use(new FeRate(3, 5));
        assertEquals(0, remainder.held(), "another rate");
        remainder.restore(3);
        assertEquals(0, remainder.held(), "out of range");
    }
}
