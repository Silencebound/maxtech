package dev.silencebound.maxtech.lib.api.schedule;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The first-in, first-out queue of a {@link TaskGroup}: a ring buffer that doubles when full and never shrinks.
 * fastutil's {@code ObjectArrayFIFOQueue} halves its array whenever it drains to a quarter, so a group whose few due
 * tasks fill and empty the queue each tick allocated a new array on most ticks. A group's queue holds each of its tasks
 * at most once, plus stale entries until they come up, so the kept array is bounded by the group's peak size. Server
 * thread only.
 */
final class TaskQueue {
    private @Nullable ScheduledTask[] tasks = new ScheduledTask[16];
    private int head;
    private int size;

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void enqueue(ScheduledTask task) {
        if (size == tasks.length) {
            grow();
        }
        tasks[(head + size) & (tasks.length - 1)] = task;
        size++;
    }

    /** Takes the oldest task; the queue must not be empty. */
    ScheduledTask dequeue() {
        if (size == 0) {
            throw new IllegalStateException("Queue is empty");
        }
        ScheduledTask task = tasks[head];
        tasks[head] = null;
        head = (head + 1) & (tasks.length - 1);
        size--;
        if (task == null) {
            throw new IllegalStateException("Queue slot is empty");
        }
        return task;
    }

    private void grow() {
        @Nullable
        ScheduledTask[] grown = Arrays.copyOf(tasks, tasks.length * 2);
        // The wrapped part [0, head) moves behind the old end, so the order stays head .. head + size.
        System.arraycopy(tasks, 0, grown, tasks.length, head);
        Arrays.fill(grown, 0, head, null);
        tasks = grown;
    }
}
