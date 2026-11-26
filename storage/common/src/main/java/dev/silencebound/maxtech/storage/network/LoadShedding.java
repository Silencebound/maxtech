package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * One network's load shedding (M7L2 row 21): its {@link NetworkDevice#sheddable()} members in one list per
 * {@link PowerPriority}, and the priority from which they are paused now. A priority pauses below its threshold and
 * resumes at its threshold plus the margin, so devices do not flicker around it. Thresholds and the margin come from
 * {@code loadShedding.*}.
 *
 * <p>
 * Cold start: nothing sheds until the network is armed, which happens once its FE has been charged past the highest
 * resume level; the network keeps that with its controller, so a reload does not start shedding again. A network that
 * never got FE never sheds.
 *
 * <p>
 * Cost: after each change of level the network works out the band of stored FE in which the level holds
 * ({@link #holds}); while the FE stays in it, a check is two comparisons and visits no device. Only when it leaves the
 * band are the thresholds read again, and only the lists of the priorities that start or stop pausing are walked.
 * Server thread only.
 */
public final class LoadShedding {
    /** {@link #pausedFrom()} while nothing is paused. */
    public static final int NONE = PowerPriority.MAX + 1;

    /** Members by priority, index 1 to 5; each device's {@link DeviceLink#shedSlot} is its index in its list. */
    private final ObjectArrayList<DeviceLink>[] byPriority;
    /** Thresholds in percent by priority, read from config on each slow-path update; index 0 and 1 stay 0. */
    private final int[] thresholds = new int[NONE];
    private int members;
    private int pausedFrom = NONE;
    /** Devices paused or resumed since the network was made; tests check that a steady network visits none. */
    private long visits;
    /** Slow-path updates (the FE left the band); tests check that a steady network makes none. */
    private long updates;
    /** The band of stored FE the current level holds in, for {@link #bandCapacity} and {@link #bandLimitless}. */
    private long bandLow = Long.MIN_VALUE;
    private long bandHigh = Long.MIN_VALUE;
    private long bandCapacity = -1;
    private boolean bandLimitless;

    @SuppressWarnings("unchecked") // an array of one generic list type
    LoadShedding() {
        byPriority = new ObjectArrayList[PowerPriority.MAX + 1];
        for (int p = PowerPriority.MIN; p <= PowerPriority.MAX; p++) {
            byPriority[p] = new ObjectArrayList<>(0);
        }
    }

    /** Whether the network has sheddable members, so its tick should look at the energy. */
    boolean active() {
        return members > 0;
    }

    /** The lowest power priority paused now, {@link #NONE} while nothing is. Higher numbers are paused too. */
    public int pausedFrom() {
        return pausedFrom;
    }

    /**
     * The shed level: how many priorities are paused, 0 (none) to 4 (priorities 2 to 5). What logic graphs and the
     * controller's Network tab show.
     */
    public int level() {
        return NONE - pausedFrom;
    }

    /** Devices paused or resumed so far. */
    public long visits() {
        return visits;
    }

    /** Times the FE left the band and the level was worked out again; for tests. */
    public long updates() {
        return updates;
    }

    /** Sheddable members of priority {@code priority}; for diagnostics and tests. */
    public int count(int priority) {
        return byPriority[PowerPriority.clamp(priority)].size();
    }

    /** Forgets the band, so the next check works the level out again (devices or the controller changed). */
    void invalidate() {
        bandCapacity = -1;
    }

    /** Whether the level holds at {@code stored} of {@code capacity} FE: two comparisons, no device visited. */
    boolean holds(long stored, long capacity, boolean limitless) {
        return capacity == bandCapacity && limitless == bandLimitless && stored >= bandLow && stored < bandHigh;
    }

    /** {@code link}'s device joined the network: it is listed and paused if its priority is shed now. */
    void add(DeviceLink link) {
        if (!link.device.sheddable() || link.shedSlot >= 0) {
            return;
        }
        ObjectArrayList<DeviceLink> list = byPriority[link.powerPriority()];
        link.shedSlot = list.size();
        list.add(link);
        members++;
        link.setPowerPaused(link.powerPriority() >= pausedFrom);
    }

    /** {@code link}'s device left the network: it is no longer listed and no longer paused by it. */
    void remove(DeviceLink link) {
        int slot = link.shedSlot;
        if (slot < 0) {
            return;
        }
        unlist(link, link.powerPriority(), slot);
        link.shedSlot = -1;
        members--;
        link.setPowerPaused(false);
    }

    /**
     * {@code link}'s priority changed from {@code from} to its current one: it moves list and is paused or resumed by
     * the current level.
     */
    void moved(DeviceLink link, int from) {
        int slot = link.shedSlot;
        if (slot < 0) {
            return;
        }
        unlist(link, from, slot);
        ObjectArrayList<DeviceLink> next = byPriority[link.powerPriority()];
        link.shedSlot = next.size();
        next.add(link);
        link.setPowerPaused(link.powerPriority() >= pausedFrom);
    }

    private void unlist(DeviceLink link, int priority, int slot) {
        ObjectArrayList<DeviceLink> list = byPriority[priority];
        DeviceLink last = list.pop();
        if (last != link) {
            list.set(slot, last);
            last.shedSlot = slot;
        }
    }

    /**
     * Whether a network not yet armed is armed at {@code stored} of {@code capacity} FE: charged to the highest resume
     * level of any threshold that is on. A network with every threshold off never arms (and never sheds).
     */
    boolean arms(long stored, long capacity) {
        readThresholds();
        int arm = armPercent(thresholds, StorageServerConfig.SHED_RESUME_MARGIN.getAsInt());
        return arm > 0 && capacity > 0 && stored >= thresholdFe(capacity, arm);
    }

    /**
     * Works the level out at {@code stored} of {@code capacity} FE, pauses or resumes the devices whose priorities
     * crossed, and keeps the band the new level holds in. Sheds nothing while {@code limitless}, without capacity or
     * before the network is {@code armed}. Returns whether the level changed.
     */
    boolean update(long stored, long capacity, boolean limitless, boolean armed) {
        updates++;
        readThresholds();
        int margin = StorageServerConfig.SHED_RESUME_MARGIN.getAsInt();
        bandCapacity = capacity;
        bandLimitless = limitless;
        int next;
        if (limitless || capacity <= 0) {
            next = NONE;
            bandLow = Long.MIN_VALUE;
            bandHigh = Long.MAX_VALUE;
        } else if (!armed) {
            next = NONE;
            bandLow = Long.MIN_VALUE;
            int arm = armPercent(thresholds, margin);
            bandHigh = arm > 0 ? thresholdFe(capacity, arm) : Long.MAX_VALUE;
        } else {
            next = pausedFrom(thresholds, margin, pausedFrom, stored, capacity);
            bandLow = bandLow(thresholds, next, capacity);
            bandHigh = bandHigh(thresholds, margin, next, capacity);
        }
        int previous = pausedFrom;
        if (next == previous) {
            return false;
        }
        pausedFrom = next;
        // A lower number pauses more: walk only the priorities between the two levels.
        int low = Math.min(next, previous);
        int high = Math.max(next, previous);
        boolean pause = next < previous;
        for (int p = low; p < high && p <= PowerPriority.MAX; p++) {
            ObjectArrayList<DeviceLink> list = byPriority[p];
            for (int i = 0; i < list.size(); i++) {
                visits++;
                list.get(i).setPowerPaused(pause);
            }
        }
        return true;
    }

    private void readThresholds() {
        thresholds[PowerPriority.HIGH] = StorageServerConfig.SHED_HIGH_PERCENT.getAsInt();
        thresholds[PowerPriority.NORMAL] = StorageServerConfig.SHED_NORMAL_PERCENT.getAsInt();
        thresholds[PowerPriority.LOW] = StorageServerConfig.SHED_LOW_PERCENT.getAsInt();
        thresholds[PowerPriority.OPTIONAL] = StorageServerConfig.SHED_OPTIONAL_PERCENT.getAsInt();
    }

    /**
     * The lowest priority to pause at {@code stored} of {@code capacity} FE when {@code current} is paused now:
     * {@code thresholds[p]} in percent per priority (0 is off; 0 and 1 are never read), {@code margin} the resume
     * margin. A priority not paused pauses below its threshold; a paused one stays paused until its threshold plus the
     * margin. Essential devices never pause. O(priorities).
     */
    static int pausedFrom(int[] thresholds, int margin, int current, long stored, long capacity) {
        for (int p = PowerPriority.HIGH; p <= PowerPriority.MAX; p++) {
            int threshold = thresholds[p];
            if (threshold <= 0) {
                continue;
            }
            if (stored < thresholdFe(capacity, p >= current ? resume(threshold, margin) : threshold)) {
                return p;
            }
        }
        return NONE;
    }

    /**
     * The FE below which {@code pausedFrom} no longer holds: the highest threshold of a priority not paused now; the
     * level changes once the FE drops under it. {@link Long#MIN_VALUE} when none.
     */
    static long bandLow(int[] thresholds, int pausedFrom, long capacity) {
        long low = Long.MIN_VALUE;
        for (int p = PowerPriority.HIGH; p < pausedFrom && p <= PowerPriority.MAX; p++) {
            if (thresholds[p] > 0) {
                low = Math.max(low, thresholdFe(capacity, thresholds[p]));
            }
        }
        return low;
    }

    /**
     * The FE from which {@code pausedFrom} no longer holds: the lowest resume level of a paused priority.
     * {@link Long#MAX_VALUE} when none is paused.
     */
    static long bandHigh(int[] thresholds, int margin, int pausedFrom, long capacity) {
        long high = Long.MAX_VALUE;
        for (int p = Math.max(PowerPriority.HIGH, pausedFrom); p <= PowerPriority.MAX; p++) {
            if (thresholds[p] > 0) {
                high = Math.min(high, thresholdFe(capacity, resume(thresholds[p], margin)));
            }
        }
        return high;
    }

    /** The highest resume level in percent of any threshold that is on; 0 when all are off. */
    static int armPercent(int[] thresholds, int margin) {
        int arm = 0;
        for (int p = PowerPriority.HIGH; p <= PowerPriority.MAX; p++) {
            if (thresholds[p] > 0) {
                arm = Math.max(arm, resume(thresholds[p], margin));
            }
        }
        return arm;
    }

    private static int resume(int threshold, int margin) {
        return Math.min(100, threshold + margin);
    }

    /** {@code percent} (0 to 100) of {@code capacity}, rounded as {@link OperationCost#belowPercent} rounds it. */
    static long thresholdFe(long capacity, int percent) {
        long p = Math.clamp(percent, 0, 100);
        return capacity / 100 * p + capacity % 100 * p / 100;
    }
}
