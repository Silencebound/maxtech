package dev.silencebound.maxtech.storage.api;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a storage network holds, read-only: the totals of every key over all its providers, with each key interned to an
 * int id (PRD 7.2, Storage data model). Terminals send ids with their counts to clients and resolve each id to its key
 * once.
 *
 * <p>
 * Every method but {@link #latestSnapshot()} is server thread only. Off-thread readers (search, sort, planning) take a
 * {@link #snapshot()} on the server thread and hand it over; a snapshot never changes.
 */
public interface StorageContents {
    /** The network's total of {@code key}; 0 for a key it does not hold. O(1). */
    long amount(StackKey key);

    /** The id {@code key} is interned to, or -1 when the network holds none of it. O(1). */
    int keyId(StackKey key);

    /**
     * The key with {@code id}, or {@code null} for an id not in use. An id whose total dropped to 0 is released after a
     * while and may then name another key, so resolve ids from a snapshot of the same generation, or soon after.
     */
    @Nullable
    StackKey key(int id);

    /** The total of the key with {@code id}; 0 for an id not in use. O(1). */
    long amount(int id);

    /** Distinct keys held now. */
    int size();

    /** Bumped by every change of a total; compare it to see whether anything changed since a snapshot. */
    long generation();

    /**
     * The totals as they are now. Taking one while nothing changed since the last returns the same object and allocates
     * nothing; the first change after a snapshot copies the totals once.
     */
    Snapshot snapshot();

    /**
     * The latest snapshot taken, from any thread; empty before the first, never null. May be older than the live
     * totals.
     */
    Optional<Snapshot> latestSnapshot();

    /**
     * The totals by key id as of {@code generation}, immutable and safe on any thread.
     *
     * @param generation
     *            the {@link #generation()} the totals belong to
     * @param totals
     *            key id to total, every total above 0; read-only
     */
    record Snapshot(long generation, Int2LongMap totals) {
    }
}
