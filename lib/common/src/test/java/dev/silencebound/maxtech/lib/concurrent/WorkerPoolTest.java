package dev.silencebound.maxtech.lib.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Jobs run off the calling thread and commit only through {@link WorkerPool#drainCommits()}. */
class WorkerPoolTest {
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** Records what happened to it; computes the sum of a snapshot's values. */
    private static final class SumJob implements BackgroundJob<Long> {
        final CowSnapshot.Snapshot<Int2LongOpenHashMap> input;
        final CowSnapshot<Int2LongOpenHashMap> live;
        final List<String> events;
        volatile @Nullable Thread computedOn;

        SumJob(CowSnapshot<Int2LongOpenHashMap> live, List<String> events) {
            this.live = live;
            this.input = live.snapshot();
            this.events = events;
        }

        @Override
        public Long compute() {
            computedOn = Thread.currentThread();
            long sum = 0;
            for (long value : input.value().values()) {
                sum += value;
            }
            return sum;
        }

        @Override
        public boolean revalidate(Long result) {
            return live.generation() == input.generation();
        }

        @Override
        public void commit(Long result) {
            events.add("commit " + result);
        }

        @Override
        public void discarded(@Nullable Long result, @Nullable Throwable error) {
            events.add("discard " + result + " " + (error == null ? "" : error.getMessage()));
        }
    }

    @Test
    void commitsOnlyWhenDrainedAndStillValid() {
        WorkerPool pool = new WorkerPool("test worker", 2);
        try {
            CowSnapshot<Int2LongOpenHashMap> totals = CowSnapshot.int2Long();
            totals.write().put(1, 5);
            totals.write().put(2, 7);
            List<String> events = new ArrayList<>();
            SumJob valid = new SumJob(totals, events);
            assertTrue(pool.submit(valid));
            awaitDone(pool, 1);
            assertTrue(events.isEmpty(), "nothing commits before the main thread drains");
            assertEquals(1, pool.drainCommits());
            assertEquals(List.of("commit 12"), events);
            assertNotEquals(Thread.currentThread(), valid.computedOn, "compute ran on a worker");

            events.clear();
            SumJob stale = new SumJob(totals, events);
            assertTrue(pool.submit(stale));
            awaitDone(pool, 1);
            totals.write().put(3, 1);
            assertEquals(0, pool.drainCommits());
            assertEquals(List.of("discard 12 "), events, "a result computed on an old generation is discarded");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void failuresAreDiscardedOnTheMainThread() {
        WorkerPool pool = new WorkerPool("test worker", 1);
        try {
            List<String> events = new ArrayList<>();
            assertTrue(pool.submit(new BackgroundJob<String>() {
                @Override
                public String compute() {
                    throw new IllegalStateException("boom");
                }

                @Override
                public void commit(String result) {
                    events.add("commit");
                }

                @Override
                public void discarded(@Nullable String result, @Nullable Throwable error) {
                    events.add("discard " + result + " " + (error == null ? null : error.getMessage()));
                }
            }));
            awaitDone(pool, 1);
            assertEquals(0, pool.drainCommits());
            assertEquals(List.of("discard null boom"), events);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void fullPoolRefusesInsteadOfBlocking() throws InterruptedException {
        WorkerPool pool = new WorkerPool("test worker", 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            BackgroundJob<Boolean> blocker = new BackgroundJob<>() {
                @Override
                public Boolean compute() throws InterruptedException {
                    return release.await(10, TimeUnit.SECONDS);
                }

                @Override
                public void commit(Boolean result) {
                }
            };
            int accepted = 0;
            long start = System.nanoTime();
            while (pool.submit(blocker)) {
                accepted++;
                assertTrue(accepted <= WorkerPool.QUEUE_CAPACITY + 1, "the queue is bounded");
            }
            assertTrue(System.nanoTime() - start < TIMEOUT_NANOS, "submit never blocked");
            assertTrue(accepted >= WorkerPool.QUEUE_CAPACITY, "the queue took " + accepted);
            release.countDown();
            awaitDone(pool, accepted);
            assertEquals(accepted, pool.drainCommits());
        } finally {
            release.countDown();
            pool.shutdown();
        }
        assertFalse(pool.submit(new BackgroundJob<String>() {
            @Override
            public String compute() {
                return "";
            }

            @Override
            public void commit(String result) {
            }
        }), "a shut-down pool refuses");
    }

    /** Waits until {@code count} jobs have finished computing; fails after the timeout. */
    private static void awaitDone(WorkerPool pool, int count) {
        BooleanSupplier finished = () -> pool.completed() >= count;
        long start = System.nanoTime();
        while (!finished.getAsBoolean()) {
            assertTrue(System.nanoTime() - start < TIMEOUT_NANOS, "jobs did not finish");
            Thread.onSpinWait();
        }
    }
}
