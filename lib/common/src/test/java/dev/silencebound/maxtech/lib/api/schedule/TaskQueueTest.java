package dev.silencebound.maxtech.lib.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

/** The group queue keeps first-in, first-out order across wrap-around and growth. */
class TaskQueueTest {
    private static ScheduledTask task() {
        return new ScheduledTask(TaskPriority.BACKGROUND) {
            @Override
            protected int run(TaskGroup group, long gameTime) {
                return DONE;
            }
        };
    }

    @Test
    void keepsOrderWhileWrappingAndGrowing() {
        TaskQueue queue = new TaskQueue();
        ArrayDeque<ScheduledTask> expected = new ArrayDeque<>();
        // Uneven fills and drains move the head around the ring and grow it while wrapped.
        for (int round = 1; round <= 40; round++) {
            for (int i = 0; i < round * 3; i++) {
                ScheduledTask task = task();
                queue.enqueue(task);
                expected.add(task);
            }
            for (int i = 0; i < round * 2; i++) {
                assertSame(expected.remove(), queue.dequeue());
            }
            assertEquals(expected.size(), queue.size());
        }
        while (!expected.isEmpty()) {
            assertSame(expected.remove(), queue.dequeue());
        }
        assertTrue(queue.isEmpty());
        assertThrows(IllegalStateException.class, queue::dequeue);
    }
}
