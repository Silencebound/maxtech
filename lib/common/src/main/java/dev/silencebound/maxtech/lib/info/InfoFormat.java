package dev.silencebound.maxtech.lib.info;

/**
 * Number formatting for info lines. Lines are built on the server and sent as translatable components, so numbers go in
 * as plain strings grouped by thousands with a comma, whatever the client's language.
 */
public final class InfoFormat {
    private static final String[] UNITS = {"k", "M", "G", "T", "P", "E"};

    private InfoFormat() {
    }

    /**
     * {@code value} short, for overlays: as is below 1000, else in thousands, millions and so on with one decimal below
     * 100 of the unit: 999 is {@code "999"}, 1234 is {@code "1.2k"}, 12000 is {@code "12k"}, 123456 is {@code "123k"},
     * 999950 is {@code "1M"}. Negative values keep their sign.
     */
    public static String compact(long value) {
        if (value < 0) {
            // Long.MIN_VALUE has no positive counterpart; one less changes nothing at that scale.
            return "-" + compact(value == Long.MIN_VALUE ? Long.MAX_VALUE : -value);
        }
        if (value < 1000) {
            return Long.toString(value);
        }
        double scaled = value;
        int unit = -1;
        do {
            scaled /= 1000;
            unit++;
        } while (Math.round(scaled * 10) >= 10_000 && unit < UNITS.length - 1);
        long tenths = Math.round(scaled * 10);
        String digits;
        if (tenths >= 1000) {
            digits = Long.toString(Math.round(scaled));
        } else if (tenths % 10 == 0) {
            digits = Long.toString(tenths / 10);
        } else {
            digits = tenths / 10 + "." + tenths % 10;
        }
        return digits + UNITS[unit];
    }

    /**
     * {@code value} of {@code max} as a whole percentage without the sign, clamped to 0 to 100, and never 100 while
     * {@code value} is below {@code max} nor 0 while it is above 0: {@code "64"}. 0 when {@code max} is 0 or less.
     */
    public static String percent(long value, long max) {
        if (max <= 0 || value <= 0) {
            return "0";
        }
        if (value >= max) {
            return "100";
        }
        long percent = Math.round(value * 100.0 / max);
        return Long.toString(Math.clamp(percent, 1, 99));
    }

    /** {@code 1234567} as {@code "1,234,567"}; negative values keep their sign. */
    public static String number(long value) {
        String digits = Long.toString(value);
        int start = value < 0 ? 1 : 0;
        int length = digits.length() - start;
        if (length <= 3) {
            return digits;
        }
        StringBuilder out = new StringBuilder(digits.length() + (length - 1) / 3);
        out.append(digits, 0, start);
        int firstGroup = length % 3 == 0 ? 3 : length % 3;
        out.append(digits, start, start + firstGroup);
        for (int i = start + firstGroup; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return out.toString();
    }

    /**
     * {@code ticks} as seconds at 20 ticks per second, exact and without trailing zeros: 200 is {@code "10"}, 50 is
     * {@code "2.5"}, 1 is {@code "0.05"}; whole seconds are grouped like {@link #number}. Throws
     * {@link IllegalArgumentException} for negative ticks.
     */
    public static String seconds(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Ticks must not be negative, was " + ticks);
        }
        String whole = number(ticks / 20);
        long hundredths = (ticks % 20) * 5;
        if (hundredths == 0) {
            return whole;
        }
        if (hundredths % 10 == 0) {
            return whole + "." + hundredths / 10;
        }
        return whole + (hundredths < 10 ? ".0" : ".") + hundredths;
    }

    /**
     * {@code value} rounded to two decimals, without trailing zeros and grouped like {@link #number}: 32.0 is
     * {@code "32"}, 12.5 is {@code "12.5"}, 1234.567 is {@code "1,234.57"}. Throws {@link IllegalArgumentException} for
     * negative values and for values that are not finite.
     */
    public static String decimal(double value) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Value must be finite and not negative, was " + value);
        }
        long hundredths = Math.round(value * 100);
        String whole = number(hundredths / 100);
        long fraction = hundredths % 100;
        if (fraction == 0) {
            return whole;
        }
        if (fraction % 10 == 0) {
            return whole + "." + fraction / 10;
        }
        return whole + (fraction < 10 ? ".0" : ".") + fraction;
    }
}
