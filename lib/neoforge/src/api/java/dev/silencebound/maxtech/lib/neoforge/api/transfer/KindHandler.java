package dev.silencebound.maxtech.lib.neoforge.api.transfer;

import java.util.Optional;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * One block's storage of one resource kind as a transfer port sees it, from a {@link KindBridge}: indices holding a
 * resource and an amount in the kind's unit, and inserting and extracting by key. Amounts are {@code long}; a handler
 * whose own amounts are smaller saturates them.
 * <p>
 * A {@link #transactional()} handler journals its changes in the given transaction, like NeoForge's
 * {@code ResourceHandler}. One that is not (a mod with simulate/execute calls, such as Mekanism's chemicals) changes at
 * once and ignores the transaction; Lib's ports never move between two such handlers, and touch one such side last:
 * they simulate it, move on the transactional side in a nested transaction, carry out the foreign side, and keep only
 * what it took before committing. The foreign execute must be the last step before the root commit: nothing that can
 * fail or roll back may run between it and the commit, since it cannot be undone. Server thread only.
 *
 * @param <K>
 *            the key type of the kind
 */
@ApiStatus.Experimental
public interface KindHandler<K> {
    /** Number of indices. */
    int size();

    /** The resource at {@code index}, or empty when the index is empty or the handler holds something unknown there. */
    Optional<K> resourceAt(int index);

    /** The amount at {@code index}; 0 when empty. */
    long amountAt(int index);

    /** Inserts up to {@code amount} of {@code key} wherever the handler places it; returns what went in. */
    long insert(K key, long amount, TransactionContext transaction);

    /** Extracts up to {@code amount} of {@code key} from wherever the handler holds it; returns what came out. */
    long extract(K key, long amount, TransactionContext transaction);

    /** Whether {@link #insert} and {@link #extract} roll back with the transaction. */
    boolean transactional();

    /**
     * What {@link #insert} would take, changing nothing. The default inserts in a nested transaction and rolls it back;
     * a handler that is not {@link #transactional()} must override it.
     */
    default long simulateInsert(K key, long amount, TransactionContext transaction) {
        try (Transaction nested = Transaction.open(transaction)) {
            return insert(key, amount, nested);
        }
    }

    /** What {@link #extract} would give, changing nothing; like {@link #simulateInsert}. */
    default long simulateExtract(K key, long amount, TransactionContext transaction) {
        try (Transaction nested = Transaction.open(transaction)) {
            return extract(key, amount, nested);
        }
    }
}
