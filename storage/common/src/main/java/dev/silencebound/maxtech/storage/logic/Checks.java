package dev.silencebound.maxtech.storage.logic;

/**
 * The threshold checks shared by compare and crossing nodes, with a switch-back value (hysteresis): a condition that
 * became true stays true until the value passes the switch-back value, so a count swinging around the limit does not
 * flicker. States are 0 (not checked yet), {@link #NO} and {@link #YES}, as kept in node memory.
 */
final class Checks {
    /** Mode indices, in the order of the mode choices. */
    static final int ABOVE = 0;
    static final int BELOW = 1;
    static final int BETWEEN = 2;
    static final int EQUAL = 3;

    static final long NO = 1;
    static final long YES = 2;

    private Checks() {
    }

    /**
     * The new state for {@code value} given the previous state. Above: turns yes over {@code threshold} and back to no
     * at or under {@code switchBack} (at most the threshold). Below: turns yes under the threshold and back to no at or
     * over the switch-back value (at least the threshold). Between (inclusive bounds) and equal have no switch-back.
     */
    static long state(int mode, long value, long threshold, long switchBack, long upper, long previous) {
        boolean met = switch (mode) {
            case ABOVE -> previous == YES ? value > switchBack : value > threshold;
            case BELOW -> previous == YES ? value < switchBack : value < threshold;
            case BETWEEN -> value >= threshold && value <= upper;
            default -> value == threshold;
        };
        return met ? YES : NO;
    }
}
