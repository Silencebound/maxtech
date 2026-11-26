package dev.silencebound.maxtech.storage.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AmountFormatTest {
    @Test
    void fullIsExactUpToSixDigits() {
        assertEquals("0", AmountFormat.full(-3));
        assertEquals("1999", AmountFormat.full(1999));
        assertEquals("999999", AmountFormat.full(999_999));
        assertEquals("1.0M", AmountFormat.full(1_000_000));
    }

    @Test
    void compactRoundsDownAndStaysShort() {
        assertEquals("0", AmountFormat.compact(0));
        assertEquals("999", AmountFormat.compact(999));
        assertEquals("1.0k", AmountFormat.compact(1000));
        assertEquals("1.9k", AmountFormat.compact(1999));
        assertEquals("12k", AmountFormat.compact(12_999));
        assertEquals("999k", AmountFormat.compact(999_999));
        assertEquals("1.0M", AmountFormat.compact(1_000_000));
        assertEquals("2.1G", AmountFormat.compact(2_147_483_647));
        assertEquals("9.2E", AmountFormat.compact(Long.MAX_VALUE));
        for (long amount = 1; amount > 0 && amount < Long.MAX_VALUE / 7; amount = amount * 7 + 3) {
            assertTrue(AmountFormat.compact(amount).length() <= 4, amount + " gives " + AmountFormat.compact(amount));
        }
    }

    @Test
    void fluidsShowMillibucketsBelowABucket() {
        assertEquals("250mB", AmountFormat.fluid(250));
        assertEquals("1B", AmountFormat.fluid(1999));
        assertEquals("1.5kB", AmountFormat.fluid(1_500_000));
    }
}
