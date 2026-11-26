package dev.silencebound.maxtech.storage.research;

/**
 * The research numbers a preset and the pack decide (PRD 7.2, Research): task targets, point costs and the points per
 * skip token scale with the preset, kind counts with the pack's item count up to a cap. Pure math, any thread.
 */
public final class ResearchScaling {
    private ResearchScaling() {
    }

    /** The multiplier of {@code preset}: {@code relaxed} or {@code expert} from config, 1 otherwise. */
    public static double scale(ResearchPreset preset, double relaxed, double expert) {
        return switch (preset) {
            case RELAXED -> relaxed;
            case EXPERT -> expert;
            case OFF, STANDARD -> 1.0;
        };
    }

    /** A count target scaled by {@code scale}, rounded up and at least 1. */
    public static long count(long base, double scale) {
        return Math.max(1L, ceil(base * scale));
    }

    /**
     * The base kind count for a pack of {@code packItems} items: {@code fraction} of them rounded up, at least 1 and at
     * most {@code max} (no cap when {@code max} is 0 or less). The preset scales the result afterwards.
     */
    public static long kinds(double fraction, long max, int packItems) {
        long kinds = Math.max(1L, ceil(fraction * packItems));
        return max > 0 ? Math.min(kinds, max) : kinds;
    }

    /** A fill percentage scaled by {@code scale}, between 1 and 100. */
    public static long percent(long base, double scale) {
        return Math.min(100L, count(base, scale));
    }

    /** A point cost scaled by {@code scale}, rounded up; 0 stays 0. */
    public static long cost(long base, double scale) {
        return base <= 0 ? 0 : count(base, scale);
    }

    private static long ceil(double value) {
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(value);
    }
}
