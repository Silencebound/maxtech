package dev.silencebound.maxtech.lib.api.schedule;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The tasks of one owner (a storage network, say) with that owner's budget per tick: at most {@code budgetNanos} of
 * time and {@code budgetOps} operations. Each tick the group runs its due tasks, player queue first; once either budget
 * is spent, the rest wait for the next tick in the order they were queued. So an owner with too much work slows down,
 * never the server. Every pass runs at least one task, so a budget too small for one task still makes progress.
 *
 * <p>
 * Groups belong to one {@link TaskScheduler} (one per level) and live until {@link #close()} or until the server stops.
 * Server thread only; a pass allocates nothing.
 */
public final class TaskGroup {
    private final TaskScheduler scheduler;
    private final LongSupplier budgetNanos;
    private final IntSupplier budgetOps;
    private final TaskQueue player = new TaskQueue();
    private final TaskQueue background = new TaskQueue();
    private final ReferenceOpenHashSet<ScheduledTask> members = new ReferenceOpenHashSet<>();
    private int opsLeft;
    boolean active;
    private boolean closed;
    private long lastPassNanos = -1;
    private int lastPassRuns;
    private int deferred;
    private long overBudgetPasses;
    private long runs;

    TaskGroup(TaskScheduler scheduler, LongSupplier budgetNanos, IntSupplier budgetOps) {
        this.scheduler = scheduler;
        this.budgetNanos = budgetNanos;
        this.budgetOps = budgetOps;
    }

    /** Adds {@code task}, taking it out of any other group, and queues it for the next pass. */
    public void add(ScheduledTask task) {
        if (closed) {
            throw new IllegalStateException("Group is closed");
        }
        TaskGroup previous = task.group;
        if (previous == this) {
            task.wake();
            return;
        }
        if (previous != null) {
            previous.remove(task);
        }
        task.group = this;
        if (task.spreadKey == 0) {
            task.spreadKey = scheduler.nextSpreadKey();
        }
        members.add(task);
        task.wake();
    }

    /**
     * Takes {@code task} out of this group; does nothing if it is not in it. Queue and timer entries are dropped when
     * they come up.
     */
    public void remove(ScheduledTask task) {
        if (task.group != this) {
            return;
        }
        task.group = null;
        task.wakeAt = -1;
        task.idleRuns = 0;
        members.remove(task);
    }

    /** Removes every task and the group itself from its scheduler; adding to it afterwards throws. */
    public void close() {
        closed = true;
        for (ScheduledTask task : members) {
            task.group = null;
            task.wakeAt = -1;
            task.idleRuns = 0;
        }
        members.clear();
        drop(player);
        drop(background);
    }

    /**
     * Empties a closed group's queue. A task that moved to another group while queued here never got into that group's
     * queue (its flag said it was queued), so it is queued there now; this group's own members were already cut loose
     * by {@link #close()}.
     */
    private static void drop(TaskQueue queue) {
        while (!queue.isEmpty()) {
            ScheduledTask task = queue.dequeue();
            task.queued = false;
            TaskGroup owner = task.group;
            if (owner != null) {
                owner.enqueue(task);
            }
        }
    }

    /** Whether {@link #close()} was called; a closed group takes no tasks. */
    public boolean isClosed() {
        return closed;
    }

    /** Tasks in the group, queued, sleeping or waiting. */
    public int size() {
        return members.size();
    }

    /** Tasks queued for the next pass. */
    public int queued() {
        return player.size() + background.size();
    }

    /** Operations still allowed in the running pass; a task sizes its batch with it. 0 outside a pass. */
    public int remainingOps() {
        return Math.max(0, opsLeft);
    }

    /** Charges {@code ops} operations beyond the one every run costs; the pass stops once none are left. */
    public void spendOps(int ops) {
        if (ops > 0) {
            opsLeft -= ops;
        }
    }

    /** Nanoseconds the latest pass took; -1 before the first. For the owner's diagnostics. */
    public long lastPassNanos() {
        return lastPassNanos;
    }

    /** Tasks the latest pass ran. */
    public int lastPassRuns() {
        return lastPassRuns;
    }

    /** Tasks the latest pass left for the next tick because the budget ran out. */
    public int deferred() {
        return deferred;
    }

    /** Passes so far that ran out of budget; tells an owner it is throttled. */
    public long overBudgetPasses() {
        return overBudgetPasses;
    }

    /** Task runs so far. */
    public long runs() {
        return runs;
    }

    void enqueue(ScheduledTask task) {
        if (task.queued || closed) {
            return;
        }
        task.queued = true;
        (task.priority() == TaskPriority.PLAYER ? player : background).enqueue(task);
        if (!active) {
            active = true;
            scheduler.activate(this);
        }
    }

    boolean hasQueued() {
        return !player.isEmpty() || !background.isEmpty();
    }

    /** Runs due tasks within the budget; tasks queued during the pass (re-queued or woken) wait for the next one. */
    void pass(long gameTime) {
        LongSupplier clock = scheduler.clock;
        long start = clock.getAsLong();
        long deadline = start + Math.max(0, budgetNanos.getAsLong());
        opsLeft = Math.max(1, budgetOps.getAsInt());
        int playerDue = player.size();
        int backgroundDue = background.size();
        int ran = 0;
        boolean out = false;
        for (int i = 0; i < playerDue; i++) {
            if (ran > 0 && (opsLeft <= 0 || clock.getAsLong() >= deadline)) {
                out = true;
                break;
            }
            if (runOne(player.dequeue(), gameTime)) {
                ran++;
            }
        }
        if (!out) {
            for (int i = 0; i < backgroundDue; i++) {
                if (ran > 0 && (opsLeft <= 0 || clock.getAsLong() >= deadline)) {
                    out = true;
                    break;
                }
                if (runOne(background.dequeue(), gameTime)) {
                    ran++;
                }
            }
        }
        deferred = out ? queued() : 0;
        if (out) {
            overBudgetPasses++;
        }
        opsLeft = 0;
        lastPassRuns = ran;
        runs += ran;
        lastPassNanos = clock.getAsLong() - start;
    }

    /** Returns whether the task ran; a stale entry (removed or moved task) is dropped instead. */
    private boolean runOne(ScheduledTask task, long gameTime) {
        task.queued = false;
        TaskGroup owner = task.group;
        if (owner != this) {
            if (owner != null) {
                // Moved to another group while queued here; it never got into that group's queue.
                owner.enqueue(task);
            }
            return false;
        }
        opsLeft--;
        TaskScheduler.RunListener listener = TaskScheduler.runListener;
        int next;
        if (listener == null) {
            next = task.run(this, gameTime);
        } else {
            LongSupplier clock = scheduler.clock;
            long start = clock.getAsLong();
            next = task.run(this, gameTime);
            listener.ran(this, task, clock.getAsLong() - start);
        }
        if (task.group != this) {
            return true;
        }
        if (next < ScheduledTask.WAIT) {
            remove(task);
            return true;
        }
        if (task.queued) {
            // Woken by an event during its own run: the event wins over the sleep it asked for.
            return true;
        }
        if (next >= ScheduledTask.NEXT_TICK) {
            task.idleRuns = 0;
            if (next == ScheduledTask.NEXT_TICK) {
                enqueue(task);
            } else {
                scheduler.sleep(task, gameTime + next);
            }
        } else if (next == ScheduledTask.IDLE) {
            int backoff = scheduler.backoff(task.idleRuns);
            if (task.idleRuns < 30) {
                task.idleRuns++;
            }
            scheduler.sleep(task, gameTime + scheduler.spread(task, backoff));
        } else {
            // WAIT: no timer; only wake() brings it back.
            task.wakeAt = -1;
        }
        return true;
    }
}
