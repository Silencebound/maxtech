package dev.silencebound.maxtech.storage.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationCostTest {
    @Test
    void energyIsPerOperationPlusPerUnit() {
        assertEquals(2 + 64, OperationCost.energyCost(false, 64, 2, 1, 250));
        assertEquals(2 + 4, OperationCost.energyCost(true, 1_000, 2, 1, 250), "1,000 mB is four units");
        assertEquals(2 + 3, OperationCost.energyCost(true, 501, 2, 1, 250), "a started unit counts");
        assertEquals(0, OperationCost.energyCost(false, 0, 2, 1, 250), "nothing moved, nothing paid");
        assertEquals(10, OperationCost.energyCost(false, 8, 2, 1, 250));
    }

    @Test
    void withoutUnitCostAnOperationCostsTheSameWhateverItMoves() {
        // The presets since M7E: 4 FE an operation, nothing per item or mB.
        assertEquals(4, OperationCost.energyCost(false, 1, 4, 0, 250));
        assertEquals(4, OperationCost.energyCost(false, 64, 4, 0, 250));
        assertEquals(4, OperationCost.energyCost(true, 16_000, 4, 0, 250));
        assertEquals(0, OperationCost.energyCost(true, 0, 4, 0, 250), "nothing moved, nothing paid");
        assertEquals(Long.MAX_VALUE, OperationCost.affordable(true, 4, 4, 0, 250), "a paid operation moves a batch");
        assertEquals(0, OperationCost.affordable(false, 3, 4, 0, 250), "the operation unpaid");
        assertEquals(2, OperationCost.discounted(OperationCost.energyCost(false, 64, 4, 0, 250), 50),
                "efficiency cores");
        assertEquals(Long.MAX_VALUE, OperationCost.affordable(false, 2, 4, 0, 250, 50), "a discounted operation paid");
        assertEquals(0, OperationCost.affordable(false, 1, 4, 0, 250, 50), "a discounted operation unpaid");
    }

    @Test
    void affordableIsWhatTheBufferPaysFor() {
        assertEquals(18, OperationCost.affordable(false, 20, 2, 1, 250));
        assertEquals(9, OperationCost.affordable(false, 20, 2, 2, 250), "two FE per item");
        assertEquals(4_000, OperationCost.affordable(true, 20, 2, 4, 1_000), "whole fluid units only");
        assertEquals(1, OperationCost.affordable(false, 3, 2, 1, 250), "exactly one item");
        assertEquals(0, OperationCost.affordable(false, 2, 2, 1, 250), "the operation but no item");
        assertEquals(Long.MAX_VALUE, OperationCost.affordable(false, 2, 2, 0, 250), "free units");
        assertEquals(0, OperationCost.affordable(false, 1, 2, 0, 250), "the operation unpaid");
        for (long stored = 0; stored < 200; stored++) {
            long most = OperationCost.affordable(true, stored, 2, 3, 250);
            if (most > 0) {
                assertTrue(OperationCost.energyCost(true, most, 2, 3, 250) <= stored, "pays for what it allows");
                assertTrue(OperationCost.energyCost(true, most + 1, 2, 3, 250) > stored, "allows all it can pay");
            }
        }
    }

    @Test
    void discountRoundsUpAndNeverReachesZero() {
        assertEquals(66, OperationCost.discounted(66, 100));
        assertEquals(60, OperationCost.discounted(66, 90), "59.4 rounds up");
        assertEquals(40, OperationCost.discounted(66, 60), "39.6 rounds up");
        assertEquals(1, OperationCost.discounted(1, 1));
        assertEquals(0, OperationCost.discounted(0, 60), "nothing stays nothing");
        for (long stored = 0; stored < 300; stored++) {
            long most = OperationCost.affordable(false, stored, 2, 1, 250, 60);
            if (most > 0) {
                assertTrue(OperationCost.discounted(OperationCost.energyCost(false, most, 2, 1, 250), 60) <= stored,
                        "pays for what it allows at " + stored);
                assertTrue(OperationCost.discounted(OperationCost.energyCost(false, most + 1, 2, 1, 250), 60) > stored,
                        "allows all it can pay at " + stored);
            }
        }
        assertEquals(Long.MAX_VALUE - 2, OperationCost.affordable(false, Long.MAX_VALUE / 2, 2, 1, 250, 60),
                "huge stores saturate instead of overflowing");
    }

    @Test
    void belowPercentAvoidsOverflow() {
        assertTrue(OperationCost.belowPercent(999, 10_000, 10));
        assertTrue(!OperationCost.belowPercent(1_000, 10_000, 10));
        assertTrue(!OperationCost.belowPercent(0, 10_000, 0), "no regulator");
        assertTrue(!OperationCost.belowPercent(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 10), "creative store");
        assertTrue(OperationCost.belowPercent(0, 5, 50), "small capacities round down");
    }

    @Test
    void efficiencyDiscountsTheTicksSum() {
        // Ten 4 FE operations in one tick at -10 %: 36 in all, where rounding each up kept 40.
        for (int percent : new int[]{90, 80, 70, 60}) {
            long raw = 0;
            long paid = 0;
            for (int op = 0; op < 10; op++) {
                long charge = OperationCost.tickCharge(raw, paid, 4, percent);
                assertTrue(charge >= 0 && charge <= 4, "charge " + charge);
                raw += 4;
                paid += charge;
                assertEquals((raw * percent + 99) / 100, paid, "the tick's total is rounded up once");
            }
            assertEquals(40L * percent / 100, paid, "ten operations at " + percent + " %");
        }
        assertEquals(4, OperationCost.tickCharge(0, 0, 4, 100), "no cores");
        assertEquals(0, OperationCost.tickCharge(8, 8, 0, 90), "nothing moved");
        assertEquals(4, OperationCost.tickCharge(0, 0, 4, 90), "the first operation of a tick rounds up");
        assertEquals(0, OperationCost.tickCharge(4, 8, 4, 60), "an earlier overcharge is never paid back");
    }
}
