package dev.silencebound.maxtech.lib.concurrent;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * A main-thread value (usually a primitive map, such as a network's totals) with copy-on-write snapshots for readers on
 * other threads (PRD 7.2: terminal search, planning). {@link #snapshot()} hands out the current value itself and marks
 * it shared; the next {@link #write()} copies it first, so a snapshot never changes after it was taken. Writes while
 * nothing is shared, and snapshots while nothing was written, cost nothing; the one copy per write burst is the price
 * of isolation.
 *
 * <p>
 * Every write bumps the {@link #generation()}. A job computed on a snapshot keeps its generation and compares it at
 * commit time to see whether the data moved on ({@link BackgroundJob#revalidate}). {@link #write()} and
 * {@link #snapshot()} are main thread only; {@link Snapshot} values are immutable by contract and safe on any thread
 * once handed over (through {@link WorkerPool} or {@link #latest()}).
 */
public final class CowSnapshot<T> {
    /** A value as of {@code generation}; never written again, so readers must not change it either. */
    public record Snapshot<T>(long generation, T value) {
    }

    private final UnaryOperator<T> copier;
    private T current;
    private boolean shared;
    private long generation;
    private @Nullable Snapshot<T> last;
    private volatile @Nullable Snapshot<T> published;

    /** Holds {@code initial}; {@code copier} makes an independent copy for the first write after a snapshot. */
    public CowSnapshot(T initial, UnaryOperator<T> copier) {
        this.current = initial;
        this.copier = copier;
    }

    /** A holder for an {@code int -> long} map (key id to count), starting empty. */
    public static CowSnapshot<Int2LongOpenHashMap> int2Long() {
        return new CowSnapshot<>(new Int2LongOpenHashMap(), Int2LongOpenHashMap::new);
    }

    /**
     * The value to change now; copies it first if a snapshot holds it. Call it once per change (or per batch of
     * changes) and do not keep the result past the batch, since the next call after a snapshot returns a new object.
     */
    public T write() {
        if (shared) {
            current = copier.apply(current);
            shared = false;
        }
        generation++;
        return current;
    }

    /** The current value for reading on the main thread; never hand it to another thread. */
    public T read() {
        return current;
    }

    /** Bumped by every {@link #write()}; starts at 0. */
    public long generation() {
        return generation;
    }

    /**
     * The current value as an immutable snapshot, and publishes it for {@link #latest()}. Snapshots of one generation
     * are the same object, so asking each tick allocates nothing while nothing changed.
     */
    public Snapshot<T> snapshot() {
        Snapshot<T> snapshot = last;
        if (snapshot == null || snapshot.generation() != generation) {
            snapshot = new Snapshot<>(generation, current);
            last = snapshot;
            published = snapshot;
        }
        shared = true;
        return snapshot;
    }

    /** The latest snapshot taken, from any thread; {@code null} before the first. May be older than the live value. */
    public @Nullable Snapshot<T> latest() {
        return published;
    }
}
