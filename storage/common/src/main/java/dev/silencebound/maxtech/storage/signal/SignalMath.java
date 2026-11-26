package dev.silencebound.maxtech.storage.signal;

/**
 * The numbers of antenna links and wireless coverage, kept pure so JUnit can test them; callers pass the config values.
 * A range of {@link #UNLIMITED} reaches the whole dimension.
 */
public final class SignalMath {
    /** The range of a tier-3 antenna: no limit within its dimension. */
    public static final int UNLIMITED = 0;
    /** Antenna tiers run from 1 to this. */
    public static final int MAX_TIER = 3;

    private SignalMath() {
    }

    /**
     * The range of a link between antennas of tiers {@code a} and {@code b}: the lower tier's, {@code range1} for tier
     * 1, {@code range2} for tier 2 and {@link #UNLIMITED} for tier 3.
     */
    public static int linkRange(int a, int b, int range1, int range2) {
        return switch (Math.min(a, b)) {
            case 1 -> range1;
            case 2 -> range2;
            default -> UNLIMITED;
        };
    }

    /** Whether two blocks {@code distanceSqr} apart (squared) are within {@code range}. */
    public static boolean inRange(double distanceSqr, int range) {
        return range == UNLIMITED || distanceSqr <= (double) range * range;
    }

    /** Channels of a link between antennas of tiers {@code a} and {@code b}: the lower tier's. */
    public static long linkWidth(int a, int b, long width1, long width2, long width3) {
        return switch (Math.min(a, b)) {
            case 1 -> width1;
            case 2 -> width2;
            default -> width3;
        };
    }

    /**
     * Blocks a wireless terminal reaches with {@code mounts} wireless access mounts: none without one, {@code base}
     * with one, {@code step} more for each further one, at most {@code max}.
     */
    public static int wirelessRange(int mounts, int base, int step, int max) {
        if (mounts <= 0) {
            return 0;
        }
        long range = base + (long) step * (mounts - 1);
        return (int) Math.max(0, Math.min(max, range));
    }
}
