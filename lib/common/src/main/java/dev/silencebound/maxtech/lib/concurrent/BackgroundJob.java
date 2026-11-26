package dev.silencebound.maxtech.lib.concurrent;

import org.jspecify.annotations.Nullable;

/**
 * Work that runs off the main thread on a {@link WorkerPool} and lands back on it: {@link #compute()} reads only
 * immutable inputs (snapshots, copies made at submit time), then the main thread checks the result against the live
 * state with {@link #revalidate} and applies it with {@link #commit}. The planner and terminal searches work this way
 * (PRD 7.2), so the tick never waits for them.
 */
public interface BackgroundJob<R> {
    /** Worker thread. Must not touch the level, block entities or any other live game state. */
    R compute() throws Exception;

    /**
     * Main thread: whether {@code result} still fits the live state (for example, the snapshot's generation is still
     * current, or the keys it reserved are still there). False discards it.
     */
    default boolean revalidate(R result) {
        return true;
    }

    /** Main thread: applies a result that passed {@link #revalidate}. */
    void commit(R result);

    /**
     * Main thread: the result was not committed, because {@link #revalidate} said no ({@code error} is null) or
     * {@link #compute()} threw ({@code result} is null). A job that must happen submits itself again here.
     */
    default void discarded(@Nullable R result, @Nullable Throwable error) {
    }
}
