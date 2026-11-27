package dev.silencebound.maxtech.lib.api.schedule;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.ApiStatus;
import java.util.function.LongSupplier;

/**
 * The budgeted work queue of one level (PRD 7.2, Performance): {@link TaskGroup}s with their own budgets, a hashed
 * timer wheel for sleeping tasks, and exponential backoff for idle ones. Lib owns one per level
 * ({@link TaskSchedulers}) and runs it each level tick, so storage devices, like machines, never tick on their own.
 *
 * <p>
 * Sleeping costs nothing per tick but the wheel slot of the current tick: a task's timer sits in the slot of its due
 * time and is checked only when that slot comes up, once per {@link #WHEEL_SIZE} ticks. A woken task's old timer stays
 * in its slot and is dropped when found stale. Server thread only; a tick allocates nothing once the lists have grown.
 */
public final class TaskScheduler {
    /** Slots of the timer wheel; a power of two. Timers further out wait for whole turns. */
    static final int WHEEL_SIZE = 256;
    private static final int MASK = WHEEL_SIZE - 1;
    /** Backoff periods from this many ticks get a per-task offset. */
    static final int SPREAD_MIN = 16;
    /** Whether backoff wake-ups are spread; tests turn it off to assert exact periods. */
    boolean spread = true;
    private int spreadCounter;

    final LongSupplier clock;
    private final IntSupplier maxBackoffTicks;
    private final ObjectArrayList<ScheduledTask>[] wheel;
    private final ObjectArrayList<TaskGroup> active = new ObjectArrayList<>();
    private long lastTick = Long.MIN_VALUE;
    private int timers;

    /**
     * A scheduler timing its groups with {@code clock} (nanoseconds; {@code System::nanoTime} in game) and capping
     * backoff at {@code maxBackoffTicks}, read on each use. For Lib and tests; addons get groups from
     * {@link TaskSchedulers#newGroup}.
     */
    @ApiStatus.Internal
    @SuppressWarnings("unchecked") // generic array creation; every element is set right below
    public TaskScheduler(LongSupplier clock, IntSupplier maxBackoffTicks) {
        this.clock = clock;
        this.maxBackoffTicks = maxBackoffTicks;
        this.wheel = new ObjectArrayList[WHEEL_SIZE];
        for (int i = 0; i < WHEEL_SIZE; i++) {
            wheel[i] = new ObjectArrayList<>();
        }
    }

    /**
     * A new group with the given budgets per pass, read on each pass: {@code budgetNanos} of time (0 or less runs one
     * task per pass) and {@code budgetOps} operations (at least 1).
     */
    public TaskGroup newGroup(LongSupplier budgetNanos, IntSupplier budgetOps) {
        return new TaskGroup(this, budgetNanos, budgetOps);
    }

    /** Groups with queued tasks. */
    public int activeGroups() {
        return active.size();
    }

    /** Timer entries in the wheel, stale ones included until their slot comes up; for tests. */
    @ApiStatus.Internal
    public int timers() {
        return timers;
    }

    /** Backoff in ticks after {@code idleRuns} idle runs in a row: 1, 2, 4 ... up to the configured maximum. */
    int backoff(int idleRuns) {
        int max = Math.max(1, maxBackoffTicks.getAsInt());
        return idleRuns >= 30 ? max : Math.min(max, 1 << idleRuns);
    }

    /**
     * Backoff of {@code period} ticks moved by a fixed offset of the task, in {@code [-period/8, period/8]} (about), so
     * tasks that went idle on the same tick do not all wake together. The offsets average out, so the mean cost is
     * unchanged, and a wake is never later than {@code period + period/8}. Periods under {@link #SPREAD_MIN} stay
     * exact: they are short enough not to burst.
     */
    int spread(ScheduledTask task, int period) {
        if (!spread || period < SPREAD_MIN) {
            return period;
        }
        return period - period / 8 + (task.spreadKey >>> 1) % (period / 4 + 1);
    }

    /** A nonzero, well-mixed key for the next task, deterministic in the order tasks first join a group. */
    int nextSpreadKey() {
        int h = ++spreadCounter * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h == 0 ? 1 : h;
    }

    void activate(TaskGroup group) {
        active.add(group);
    }

    void sleep(ScheduledTask task, long dueTick) {
        task.wakeAt = dueTick;
        wheel[(int) (dueTick & MASK)].add(task);
        timers++;
    }

    /**
     * Wakes the tasks due by {@code gameTime} and runs every group with queued tasks. Ticks skipped since the last call
     * (a level that stopped ticking) are caught up, at most one wheel turn's worth, which covers every slot. Lib calls
     * it once per level tick; anyone else calling it would run every group again and spend their budgets.
     */
    @ApiStatus.Internal
    public void tick(long gameTime) {
        if (timers > 0) {
            long from = lastTick == Long.MIN_VALUE || gameTime - lastTick > WHEEL_SIZE || gameTime < lastTick
                    ? gameTime - WHEEL_SIZE + 1
                    : lastTick + 1;
            for (long t = from; t <= gameTime; t++) {
                fire((int) (t & MASK), gameTime);
            }
        }
        lastTick = gameTime;
        for (int i = 0; i < active.size(); i++) {
            TaskGroup group = active.get(i);
            if (!group.isClosed() && group.hasQueued()) {
                group.pass(gameTime);
            }
        }
        int kept = 0;
        for (int i = 0; i < active.size(); i++) {
            TaskGroup group = active.get(i);
            if (!group.isClosed() && group.hasQueued()) {
                active.set(kept++, group);
            } else {
                group.active = false;
            }
        }
        active.size(kept);
    }

    /** Queues the due tasks of one slot and drops its stale entries; future turns stay. */
    private void fire(int slot, long now) {
        ObjectArrayList<ScheduledTask> entries = wheel[slot];
        // Backwards, so removing an entry by moving the last one into its place skips nothing.
        for (int i = entries.size() - 1; i >= 0; i--) {
            ScheduledTask task = entries.get(i);
            long due = task.wakeAt;
            TaskGroup group = task.group;
            if (group != null && due >= 0 && (due & MASK) == slot) {
                if (due > now) {
                    continue;
                }
                task.wakeAt = -1;
                group.enqueue(task);
            }
            int last = entries.size() - 1;
            entries.set(i, entries.get(last));
            entries.remove(last);
            timers--;
        }
    }
}
