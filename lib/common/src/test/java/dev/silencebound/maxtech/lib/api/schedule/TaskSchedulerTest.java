package dev.silencebound.maxtech.lib.api.schedule;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Budgets, priorities, backoff and wake-ups, on a fake clock and game time. */
class TaskSchedulerTest {
    /** Nanoseconds; tasks advance it to simulate their cost. */
    private long now;
    private long gameTime;
    private final List<String> log = new ArrayList<>();

    private TaskScheduler scheduler(int maxBackoff) {
        TaskScheduler scheduler = new TaskScheduler(() -> now, () -> maxBackoff);
        scheduler.spread = false;
        return scheduler;
    }

    private void tick(TaskScheduler scheduler) {
        scheduler.tick(gameTime);
        gameTime++;
    }

    /** Logs its name and game time, costs {@code nanos}, and returns {@code result} each run. */
    private class Task extends ScheduledTask {
        final String name;
        final long nanos;
        int result;
        int ops;
        final LongArrayList ranAt = new LongArrayList();

        Task(String name, TaskPriority priority, long nanos, int result) {
            super(priority);
            this.name = name;
            this.nanos = nanos;
            this.result = result;
        }

        @Override
        protected int run(TaskGroup group, long time) {
            log.add(name);
            ranAt.add(time);
            now += nanos;
            group.spendOps(ops);
            return result;
        }
    }

    @Test
    void operationBudgetDefersTheRestInOrder() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 3);
        for (int i = 0; i < 7; i++) {
            group.add(new Task("t" + i, TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        }
        tick(scheduler);
        assertEquals(List.of("t0", "t1", "t2"), log);
        assertEquals(4, group.deferred());
        assertEquals(1, group.overBudgetPasses());
        tick(scheduler);
        tick(scheduler);
        assertEquals(List.of("t0", "t1", "t2", "t3", "t4", "t5", "t6"), log);
        assertEquals(0, group.deferred());
        assertEquals(0, scheduler.activeGroups(), "nothing queued, nothing active");
    }

    /** A run listener gets every run with its clock time; once cleared, runs and their results are unchanged. */
    @Test
    void runListenerTimesRunsWithoutChangingThem() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 1000);
        group.add(new Task("slow", TaskPriority.BACKGROUND, 250, ScheduledTask.NEXT_TICK));
        List<String> heard = new ArrayList<>();
        try {
            TaskScheduler.setRunListener((owner, task, nanos) -> heard.add(((Task) task).name + " " + nanos));
            tick(scheduler);
            tick(scheduler);
        } finally {
            TaskScheduler.setRunListener(null);
        }
        tick(scheduler);
        assertEquals(List.of("slow 250", "slow 250"), heard, "listened runs");
        assertEquals(List.of("slow", "slow", "slow"), log, "runs with and without the listener");
        assertEquals(3, group.runs());
    }

    @Test
    void extraOperationsCountAgainstTheBudget() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 10);
        Task big = new Task("big", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT);
        big.ops = 9;
        group.add(big);
        group.add(new Task("small", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        tick(scheduler);
        assertEquals(List.of("big"), log, "the batch used the tick's operations");
        tick(scheduler);
        assertEquals(List.of("big", "small"), log);
    }

    @Test
    void timeBudgetDefersButAlwaysRunsOne() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 100L, () -> 1000);
        group.add(new Task("slow", TaskPriority.BACKGROUND, 500, ScheduledTask.WAIT));
        group.add(new Task("next", TaskPriority.BACKGROUND, 10, ScheduledTask.WAIT));
        tick(scheduler);
        assertEquals(List.of("slow"), log, "one task over budget still runs; the rest waits");
        assertTrue(group.lastPassNanos() >= 500);
        tick(scheduler);
        assertEquals(List.of("slow", "next"), log);
    }

    @Test
    void groupsHaveSeparateBudgets() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup busy = scheduler.newGroup(() -> 1_000_000_000L, () -> 1);
        TaskGroup quiet = scheduler.newGroup(() -> 1_000_000_000L, () -> 1);
        busy.add(new Task("b0", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        busy.add(new Task("b1", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        quiet.add(new Task("q0", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        tick(scheduler);
        assertEquals(List.of("b0", "q0"), log, "a busy owner does not take another's budget");
    }

    @Test
    void playerTasksGoFirst() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 2);
        group.add(new Task("port", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        group.add(new Task("bus", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT));
        group.add(new Task("click", TaskPriority.PLAYER, 0, ScheduledTask.WAIT));
        tick(scheduler);
        assertEquals(List.of("click", "port"), log);
    }

    @Test
    void idleTasksBackOffExponentiallyUpToTheCap() {
        TaskScheduler scheduler = scheduler(8);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task idle = new Task("idle", TaskPriority.BACKGROUND, 0, ScheduledTask.IDLE);
        group.add(idle);
        for (int i = 0; i < 60; i++) {
            tick(scheduler);
        }
        // Runs at 0, then waits 1, 2, 4, 8, 8 ...
        assertEquals(LongArrayList.wrap(new long[]{0, 1, 3, 7, 15, 23, 31, 39, 47, 55}), idle.ranAt);
    }

    @Test
    void wakeResetsBackoffAndDropsTheTimer() {
        TaskScheduler scheduler = scheduler(64);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task idle = new Task("idle", TaskPriority.BACKGROUND, 0, ScheduledTask.IDLE);
        group.add(idle);
        for (int i = 0; i < 20; i++) {
            tick(scheduler);
        }
        assertEquals(LongArrayList.wrap(new long[]{0, 1, 3, 7, 15}), idle.ranAt);
        idle.wake();
        assertEquals(-1, idle.wakeAt());
        assertTrue(idle.isQueued());
        tick(scheduler);
        assertEquals(20, idle.ranAt.getLong(5), "woken at once");
        for (int i = 0; i < 40; i++) {
            tick(scheduler);
        }
        // After the wake the backoff starts at 1 again; the old timer (due at 31) fires nothing.
        assertEquals(LongArrayList.wrap(new long[]{0, 1, 3, 7, 15, 20, 21, 23, 27, 35, 51}), idle.ranAt);
    }

    @Test
    void wakeDuringItsOwnRunWinsOverTheSleep() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        int[] wakes = {2};
        Task task = new Task("self", TaskPriority.BACKGROUND, 0, ScheduledTask.IDLE) {
            @Override
            protected int run(TaskGroup owner, long time) {
                int result = super.run(owner, time);
                if (wakes[0]-- > 0) {
                    wake();
                }
                return result;
            }
        };
        group.add(task);
        for (int i = 0; i < 60; i++) {
            tick(scheduler);
        }
        // Woken by its own runs at ticks 0 and 1, so those idle results start no backoff: it starts at tick 2.
        assertEquals(LongArrayList.wrap(new long[]{0, 1, 2, 3, 5, 9, 17, 33}), task.ranAt);
    }

    @Test
    void cadenceWaitAndDone() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task cadence = new Task("cadence", TaskPriority.BACKGROUND, 0, 5);
        Task waiting = new Task("waiting", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT);
        Task done = new Task("done", TaskPriority.BACKGROUND, 0, ScheduledTask.DONE);
        Task busy = new Task("busy", TaskPriority.BACKGROUND, 0, ScheduledTask.NEXT_TICK);
        group.add(cadence);
        group.add(waiting);
        group.add(done);
        group.add(busy);
        for (int i = 0; i < 12; i++) {
            tick(scheduler);
        }
        assertEquals(LongArrayList.wrap(new long[]{0, 5, 10}), cadence.ranAt);
        assertEquals(1, waiting.ranAt.size(), "WAIT sleeps until woken");
        assertEquals(1, done.ranAt.size());
        assertNull(done.group(), "DONE leaves the group");
        assertEquals(12, busy.ranAt.size(), "NEXT_TICK runs every tick");
        assertEquals(3, group.size());
        waiting.wake();
        tick(scheduler);
        assertEquals(2, waiting.ranAt.size());
    }

    @Test
    void timersFarOutWaitWholeTurns() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task far = new Task("far", TaskPriority.BACKGROUND, 0, TaskScheduler.WHEEL_SIZE + 3);
        group.add(far);
        for (int i = 0; i < 2 * TaskScheduler.WHEEL_SIZE + 10; i++) {
            tick(scheduler);
        }
        long period = TaskScheduler.WHEEL_SIZE + 3;
        assertEquals(LongArrayList.wrap(new long[]{0, period, 2 * period}), far.ranAt);
    }

    @Test
    void skippedTicksCatchUp() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task cadence = new Task("cadence", TaskPriority.BACKGROUND, 0, 3);
        group.add(cadence);
        tick(scheduler);
        gameTime += 10;
        tick(scheduler);
        assertEquals(LongArrayList.wrap(new long[]{0, 11}), cadence.ranAt, "a timer passed while not ticking fires");
    }

    @Test
    void removeMoveAndClose() {
        TaskScheduler scheduler = scheduler(100);
        TaskGroup first = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        TaskGroup second = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task removed = new Task("removed", TaskPriority.BACKGROUND, 0, ScheduledTask.NEXT_TICK);
        Task moved = new Task("moved", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT);
        first.add(removed);
        first.add(moved);
        first.remove(removed);
        second.add(moved);
        assertSame(second, moved.group());
        tick(scheduler);
        assertEquals(List.of("moved"), log, "a removed task does not run; a moved one runs once");
        Task sleeper = new Task("sleeper", TaskPriority.BACKGROUND, 0, ScheduledTask.IDLE);
        second.add(sleeper);
        tick(scheduler);
        second.close();
        assertNull(sleeper.group(), "close empties the group, sleeping tasks included");
        assertNull(moved.group());
        assertThrows(IllegalStateException.class, () -> second.add(sleeper));
        for (int i = 0; i < 10; i++) {
            tick(scheduler);
        }
        assertEquals(List.of("moved", "sleeper"), log);
        assertFalse(sleeper.isQueued());
        assertEquals(0, scheduler.timers(), "stale timers are dropped when their slot comes up");
    }

    @Test
    void taskMovedWhileQueuedRunsInItsNewGroupWhenTheOldOneCloses() {
        // A storage network merge: a device's queued task moves to the surviving network, then the old one closes.
        TaskScheduler scheduler = scheduler(100);
        TaskGroup old = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        TaskGroup survivor = scheduler.newGroup(() -> 1_000_000_000L, () -> 100);
        Task task = new Task("task", TaskPriority.BACKGROUND, 0, ScheduledTask.WAIT);
        old.add(task);
        survivor.add(task);
        old.close();
        tick(scheduler);
        assertEquals(List.of("task"), log, "runs once in the surviving group");
        assertSame(survivor, task.group());
    }

    @Test
    void sameTickBackoffsSpreadDeterministicallyWithinTheBound() {
        long[] first = idleWakeTicks();
        assertArrayEquals(first, idleWakeTicks(), "same creation order gives the same offsets");
        int period = 64;
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        long sum = 0;
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        for (long wake : first) {
            lo = Math.min(lo, wake);
            hi = Math.max(hi, wake);
            sum += wake;
            distinct.add(wake);
        }
        assertTrue(hi <= period + period / 8, "never later than period + bound: " + hi);
        assertTrue(lo >= period - period / 8, "never earlier than period - bound: " + lo);
        assertTrue(distinct.size() >= 8, "spread over several ticks: " + distinct.size());
        double mean = (double) sum / first.length;
        assertTrue(Math.abs(mean - period) <= period / 16.0, "mean stays near the period: " + mean);
    }

    /** Offsets of the backoff period 64 for 200 tasks that first join a group on the same tick. */
    private long[] idleWakeTicks() {
        TaskScheduler scheduler = new TaskScheduler(() -> now, () -> 64);
        TaskGroup group = scheduler.newGroup(() -> 1_000_000_000L, () -> 1000);
        long[] wakes = new long[200];
        for (int i = 0; i < wakes.length; i++) {
            Task task = new Task("t" + i, TaskPriority.BACKGROUND, 0, ScheduledTask.IDLE);
            group.add(task);
            wakes[i] = scheduler.spread(task, 64);
        }
        return wakes;
    }
}
