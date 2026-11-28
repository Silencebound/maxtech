package dev.silencebound.maxtech.lib.platform;

// PLATFORM-LEAK: port moves are NeoForge transfer transactions (ADR-0002, ADR-0018).
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * One block's storage of one resource kind as Lib's transfer ports see it: on NeoForge a {@code KindHandler} from the
 * kind's {@code KindBridge}, with the same contract (indices with a key and an amount, insert and extract by key, and
 * whether changes roll back with the transaction). A handler that is not {@link #transactional()} changes at once; its
 * simulations change nothing. Server thread only.
 *
 * @param <K>
 *            the key type of the kind
 */
public interface KindStorage<K> {
    /** Number of indices. */
    int size();

    /** The key at {@code index}, or null when it is empty or holds something unknown. */
    @Nullable
    K resourceAt(int index);

    /** The amount at {@code index}; 0 when empty. */
    long amountAt(int index);

    /** Inserts up to {@code amount} of {@code key}; returns what went in. */
    long insert(K key, long amount, TransactionContext transaction);

    /** Extracts up to {@code amount} of {@code key}; returns what came out. */
    long extract(K key, long amount, TransactionContext transaction);

    /** Whether {@link #insert} and {@link #extract} roll back with the transaction. */
    boolean transactional();

    /** What {@link #insert} would take, changing nothing. */
    long simulateInsert(K key, long amount, TransactionContext transaction);

    /** What {@link #extract} would give, changing nothing. */
    long simulateExtract(K key, long amount, TransactionContext transaction);
}
