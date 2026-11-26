package dev.silencebound.maxtech.lib.schedule;

import org.jspecify.annotations.Nullable;

/**
 * One unit of recurring work in a {@link TaskGroup}: a storage port, a bus, a rule check. Nothing that owns a task
 * ticks on its own; the group runs it when it is due, inside the group's budget, and the task says from its result when
 * it wants to run next. Work woken by an event (an inventory change, a player click) calls {@link #wake()}.
 *
 * <p>
 * The state fields are the scheduler's; a task belongs to at most one group at a time. Server thread only.
 */
public abstract class ScheduledTask {
    /** Run again next tick (there is more to do now). */
    public static final int NEXT_TICK = 1;
    /** Nothing to do: sleep, retrying after a delay that doubles with each idle run up to the configured maximum. */
    public static final int IDLE = 0;
    /** Nothing to do until an event calls {@link #wake()}; no timer. */
    public static final int WAIT = -1;
    /** Finished: leave the group. */
    public static final int DONE = -2;

    private final TaskPriority priority;
    @Nullable
    TaskGroup group;
    boolean queued;
    /** Game time of the pending timer, or -1 for none; a wheel entry whose time no longer matches is stale. */
    long wakeAt = -1;
    /** Idle runs in a row; sets the backoff. */
    int idleRuns;
    /** Fixed per-task value that spreads backoff wake-ups; 0 until the task first joins a group. */
    int spreadKey;

    protected ScheduledTask(TaskPriority priority) {
        this.priority = priority;
    }

    public final TaskPriority priority() {
        return priority;
    }

    /**
     * Does the work and returns when to run next: {@link #NEXT_TICK}, a delay in ticks above 1 (a cadence),
     * {@link #IDLE} (backoff), {@link #WAIT} or {@link #DONE}. Anything else below {@link #DONE} counts as
     * {@code DONE}. Work beyond one operation should be charged with {@link TaskGroup#spendOps}, and a batch can be
     * sized with {@link TaskGroup#remainingOps()}. Must not block.
     */
    protected abstract int run(TaskGroup group, long gameTime);

    /**
     * Wakes the task for the next run of its group, the same tick if the group has not run yet; resets the backoff and
     * drops a pending timer. Does nothing for a task in no group. Cheap enough to call on every change event.
     */
    public final void wake() {
        TaskGroup current = group;
        if (current != null) {
            idleRuns = 0;
            wakeAt = -1;
            current.enqueue(this);
        }
    }

    /** The group the task is in, or {@code null}. */
    public final @Nullable TaskGroup group() {
        return group;
    }

    /** Whether it waits to run in its group's next pass. */
    public final boolean isQueued() {
        return queued && group != null;
    }

    /** Game time of its timer, or -1 when it has none (queued, waiting for an event, or in no group). */
    public final long wakeAt() {
        return wakeAt;
    }
}
