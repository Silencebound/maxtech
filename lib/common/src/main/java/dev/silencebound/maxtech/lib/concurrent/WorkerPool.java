package dev.silencebound.maxtech.lib.concurrent;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.platform.Platform;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A small bounded pool of daemon threads for {@link BackgroundJob}s, and the queue that brings their results back to
 * the main thread. {@link #submit} never blocks: with every thread busy and the queue full it refuses, and the caller
 * tries again later (on its next scheduler run). Results are committed by {@link #drainCommits()}, which the server
 * pool runs at the end of every server tick.
 *
 * <p>
 * The {@linkplain #server() server pool} starts at its first job with {@code workers.threads} threads and shuts down
 * when the server stops; results still in flight then are dropped without callbacks, since the game state they would
 * commit to is gone. Submitting and draining are main thread only.
 */
public final class WorkerPool {
    /** Jobs waiting for a thread at most; a full queue refuses more instead of blocking the tick. */
    public static final int QUEUE_CAPACITY = 256;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable WorkerPool server;

    private final ThreadPoolExecutor executor;
    private final ConcurrentLinkedQueue<Done<?>> done = new ConcurrentLinkedQueue<>();
    /** Results in {@link #done}; {@code ConcurrentLinkedQueue.size()} walks the queue. */
    private final AtomicInteger ready = new AtomicInteger();

    /** A pool of {@code threads} daemon threads named {@code name #n}, with {@link #QUEUE_CAPACITY} queued jobs. */
    public WorkerPool(String name, int threads) {
        int count = Math.max(1, threads);
        AtomicInteger next = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(count, count, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, name + " #" + next.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }

    /** Hooks the server pool's commits and shutdown into the server lifecycle; called once from Lib's init. */
    public static void bootstrap() {
        Platform.get().lifecycle().onServerTickEnd(minecraftServer -> {
            WorkerPool pool = server;
            if (pool != null) {
                pool.drainCommits();
            }
        });
        Platform.get().lifecycle().onServerStopped(minecraftServer -> {
            WorkerPool pool = server;
            server = null;
            if (pool != null) {
                pool.shutdown();
            }
        });
    }

    /** The server's pool, started on first use with {@code workers.threads} threads. Main thread only. */
    public static WorkerPool server() {
        WorkerPool pool = server;
        if (pool == null) {
            pool = new WorkerPool("MaxTech worker", ServerConfig.WORKER_THREADS.getAsInt());
            server = pool;
        }
        return pool;
    }

    /**
     * Queues {@code job} for a worker thread; false when the pool is full or shut down, and then nothing runs. Main
     * thread only.
     */
    public <R> boolean submit(BackgroundJob<R> job) {
        if (executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(() -> run(job));
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    private <R> void run(BackgroundJob<R> job) {
        try {
            done.add(new Done<>(job, job.compute(), null));
        } catch (Exception | StackOverflowError | LinkageError | AssertionError e) {
            done.add(new Done<>(job, null, e));
        }
        ready.incrementAndGet();
    }

    /**
     * Revalidates and commits every result that arrived, then calls {@link BackgroundJob#discarded} for the rest.
     * Returns how many were committed. Main thread only; results arriving while it runs wait for the next call.
     */
    public int drainCommits() {
        int committed = 0;
        for (int n = ready.get(); n > 0; n--) {
            Done<?> result = done.poll();
            if (result == null) {
                break;
            }
            ready.decrementAndGet();
            if (result.apply()) {
                committed++;
            }
        }
        return committed;
    }

    /** Results waiting for {@link #drainCommits()}; any thread. */
    public int completed() {
        return ready.get();
    }

    /** Jobs queued or running, roughly; for diagnostics. */
    public int pending() {
        return executor.getQueue().size() + executor.getActiveCount();
    }

    /** Stops the threads, interrupting running jobs, and drops queued jobs and uncommitted results. */
    public void shutdown() {
        executor.shutdownNow();
        done.clear();
        ready.set(0);
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    private record Done<R>(BackgroundJob<R> job, @Nullable R result, @Nullable Throwable error) {
        boolean apply() {
            R value = result;
            try {
                if (error != null || value == null) {
                    job.discarded(value, error);
                    if (error != null) {
                        LOGGER.warn("Background job {} failed", job, error);
                    }
                    return false;
                }
                if (!job.revalidate(value)) {
                    job.discarded(value, null);
                    return false;
                }
                job.commit(value);
                return true;
            } catch (RuntimeException e) {
                LOGGER.error("Committing background job {} failed", job, e);
                return false;
            }
        }
    }
}
