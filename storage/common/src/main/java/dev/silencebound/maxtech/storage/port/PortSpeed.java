package dev.silencebound.maxtech.storage.port;

/**
 * Port speed arithmetic (PRD 7.2, Devices): a tier's base rate times the speed-card multiplier once per card, and the
 * operations per second that rate costs at a given batch size. Pure, so it is unit-tested.
 */
public final class PortSpeed {
    private PortSpeed() {
    }

    /**
     * {@code base} per second with {@code cards} speed cards of {@code multiplier} each, rounded down, at least 1 and
     * at most {@code Integer.MAX_VALUE}.
     */
    public static long rate(long base, int cards, double multiplier) {
        double rate = Math.max(1, base);
        for (int i = 0; i < cards; i++) {
            rate *= multiplier;
        }
        return Math.max(1, (long) Math.min(Integer.MAX_VALUE, Math.floor(rate)));
    }

    /**
     * Operations per second {@code perSecond} needs: one per step, a step being the rate or one batch, whichever is
     * smaller ({@link PortRate#step}).
     */
    public static long operations(long perSecond, long batch) {
        long step = PortRate.step(perSecond, batch);
        return (perSecond + step - 1) / step;
    }
}
