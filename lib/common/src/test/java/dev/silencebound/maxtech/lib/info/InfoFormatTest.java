package dev.silencebound.maxtech.lib.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InfoFormatTest {
    @Test
    void groupsThousands() {
        assertEquals("0", InfoFormat.number(0));
        assertEquals("999", InfoFormat.number(999));
        assertEquals("1,000", InfoFormat.number(1000));
        assertEquals("12,345", InfoFormat.number(12345));
        assertEquals("123,456", InfoFormat.number(123456));
        assertEquals("1,234,567", InfoFormat.number(1234567));
        assertEquals("9,223,372,036,854,775,807", InfoFormat.number(Long.MAX_VALUE));
    }

    @Test
    void compactNumbersAreShort() {
        assertEquals("0", InfoFormat.compact(0));
        assertEquals("999", InfoFormat.compact(999));
        assertEquals("1k", InfoFormat.compact(1000));
        assertEquals("1.2k", InfoFormat.compact(1234));
        assertEquals("12k", InfoFormat.compact(12_000));
        assertEquals("12.3k", InfoFormat.compact(12_345));
        assertEquals("100k", InfoFormat.compact(99_960));
        assertEquals("123k", InfoFormat.compact(123_456));
        assertEquals("1M", InfoFormat.compact(999_950));
        assertEquals("1.5M", InfoFormat.compact(1_500_000));
        assertEquals("4G", InfoFormat.compact(4_000_000_000L));
        assertEquals("9.2E", InfoFormat.compact(Long.MAX_VALUE));
        assertEquals("-1.2k", InfoFormat.compact(-1234));
        assertEquals("-9.2E", InfoFormat.compact(Long.MIN_VALUE));
    }

    @Test
    void percentNeverRoundsToTheEnds() {
        assertEquals("0", InfoFormat.percent(0, 100));
        assertEquals("0", InfoFormat.percent(5, 0));
        assertEquals("64", InfoFormat.percent(64, 100));
        assertEquals("1", InfoFormat.percent(1, 1000));
        assertEquals("99", InfoFormat.percent(999, 1000));
        assertEquals("100", InfoFormat.percent(1000, 1000));
        assertEquals("100", InfoFormat.percent(2000, 1000));
    }

    @Test
    void keepsTheSign() {
        assertEquals("-5", InfoFormat.number(-5));
        assertEquals("-1,000", InfoFormat.number(-1000));
        assertEquals("-9,223,372,036,854,775,808", InfoFormat.number(Long.MIN_VALUE));
    }

    @Test
    void secondsAreExact() {
        assertEquals("0", InfoFormat.seconds(0));
        assertEquals("0.05", InfoFormat.seconds(1));
        assertEquals("0.15", InfoFormat.seconds(3));
        assertEquals("0.5", InfoFormat.seconds(10));
        assertEquals("2.5", InfoFormat.seconds(50));
        assertEquals("10", InfoFormat.seconds(200));
        assertEquals("12.95", InfoFormat.seconds(259));
        assertEquals("1,000", InfoFormat.seconds(20_000));
    }

    @Test
    void negativeSecondsThrow() {
        assertThrows(IllegalArgumentException.class, () -> InfoFormat.seconds(-1));
    }

    @Test
    void decimalsRoundToHundredths() {
        assertEquals("32", InfoFormat.decimal(32.0));
        assertEquals("12.5", InfoFormat.decimal(12.5));
        assertEquals("0.05", InfoFormat.decimal(0.05));
        assertEquals("1,234.57", InfoFormat.decimal(1234.567));
        assertEquals("32,000", InfoFormat.decimal(32 * 1000.0));
        assertEquals("0", InfoFormat.decimal(0.001));
    }

    @Test
    void negativeOrInfiniteDecimalsThrow() {
        assertThrows(IllegalArgumentException.class, () -> InfoFormat.decimal(-0.5));
        assertThrows(IllegalArgumentException.class, () -> InfoFormat.decimal(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> InfoFormat.decimal(Double.POSITIVE_INFINITY));
    }
}
