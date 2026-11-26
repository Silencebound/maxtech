package dev.silencebound.maxtech.storage.machine;

/**
 * What a speed or efficiency module does to a run, as plain arithmetic: the run's time and its FE in all, and how the
 * FE spreads over the run's ticks so that they add up exactly. Allocation-free; machines call it every working tick.
 */
public final class ModuleMath {
    private static final int PERCENT = 100;

    private ModuleMath() {
    }

    /**
     * Ticks a run of {@code baseDuration} takes when the machine runs {@code speedBonusPercent} per cent faster: 100
     * halves it. Rounded up and at least one tick.
     */
    public static int duration(int baseDuration, int speedBonusPercent) {
        long scaled = ((long) baseDuration * PERCENT + PERCENT + speedBonusPercent - 1) / (PERCENT + speedBonusPercent);
        return (int) Math.max(1, scaled);
    }

    /**
     * FE a whole run costs: the recipe's {@code energyPerTick} over {@code baseDuration} ticks, times
     * {@code energyPercent} per cent (250 for +150 %, 60 for -40 %), rounded up.
     */
    public static long runEnergy(int energyPerTick, int baseDuration, int energyPercent) {
        long base = (long) energyPerTick * baseDuration;
        return (base * energyPercent + PERCENT - 1) / PERCENT;
    }

    /**
     * FE the run pays on tick {@code tick} (0 for the first) of {@code duration}, so that the ticks of a run add up to
     * {@code runEnergy} exactly; a tick at or past the end pays what the last one does.
     */
    public static long energyForTick(long runEnergy, int duration, int tick) {
        int at = Math.min(Math.max(tick, 0), duration - 1);
        return runEnergy * (at + 1) / duration - runEnergy * at / duration;
    }
}
