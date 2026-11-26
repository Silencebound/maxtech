package dev.silencebound.maxtech.storage.terminal;

/**
 * Short amount labels for terminal cells: at most four characters for items, so a count fits under a 16-pixel icon.
 * Rounds down, so a cell never shows more than is stored.
 */
public final class AmountFormat {
    private static final char[] UNITS = {'k', 'M', 'G', 'T', 'P', 'E'};

    private AmountFormat() {
    }

    /** {@code 999}, {@code 1.2k}, {@code 12k}, {@code 123k}, {@code 1.2M} ... */
    public static String compact(long amount) {
        if (amount < 1000) {
            return Long.toString(Math.max(0, amount));
        }
        long scaled = amount;
        int unit = -1;
        // Tenths of the unit, so 1,250 becomes 12 tenths of a thousand.
        long tenths = 0;
        while (scaled >= 1000 && unit < UNITS.length - 1) {
            tenths = scaled / 100;
            scaled /= 1000;
            unit++;
        }
        if (scaled < 10) {
            return scaled + "." + tenths % 10 + UNITS[unit];
        }
        return scaled + String.valueOf(UNITS[unit]);
    }

    /**
     * The exact count while it has at most six digits ({@code 123456}), then {@link #compact}; for the full amount
     * format, which draws labels in half-size text.
     */
    public static String full(long amount) {
        return amount < 1_000_000 ? Long.toString(Math.max(0, amount)) : compact(amount);
    }

    /** A fluid amount in mB: {@code 250mB} below a bucket, then whole buckets ({@code 12B}, {@code 1.5kB}). */
    public static String fluid(long millibuckets) {
        if (millibuckets < 1000) {
            return Math.max(0, millibuckets) + "mB";
        }
        return compact(millibuckets / 1000) + "B";
    }
}
