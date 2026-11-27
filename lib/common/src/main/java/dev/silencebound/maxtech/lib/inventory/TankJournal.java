package dev.silencebound.maxtech.lib.inventory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
// PLATFORM-LEAK: tank contents are NeoForge transaction state (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The contents of one tank as transaction state: a key (null when empty) and an amount, up to the capacity unless a
 * save held more (then it takes nothing until drained below the capacity). Pure apart from NeoForge's transactions,
 * which run without a game, so JUnit tests it directly. Changes inside a transaction are journaled and reported once
 * the root commits; {@link #set} reports at once. Server thread only.
 *
 * @param <K>
 *            the key type, compared with {@code equals}
 */
final class TankJournal<K> extends SnapshotJournal<TankJournal.State<K>> {
    private final long capacity;
    private final Predicate<? super K> filter;
    private final Runnable onChange;
    private @Nullable K key;
    private Optional<K> resource = Optional.empty();
    private long amount;
    private boolean blocked;

    TankJournal(long capacity, Predicate<? super K> filter, Runnable onChange) {
        this.capacity = capacity;
        this.filter = filter;
        this.onChange = onChange;
    }

    /** A snapshot of the contents. */
    record State<K>(@Nullable K key, long amount) {
    }

    Optional<K> resource() {
        return resource;
    }

    @Nullable
    K key() {
        return key;
    }

    long amount() {
        return amount;
    }

    long capacity() {
        return capacity;
    }

    boolean accepts(K candidate) {
        return filter.test(candidate);
    }

    /** While blocked (contents that did not load), the tank takes nothing. */
    void block(boolean value) {
        blocked = value;
    }

    boolean blocked() {
        return blocked;
    }

    long insert(K candidate, long wanted, TransactionContext transaction) {
        if (wanted <= 0 || blocked || (key != null && !key.equals(candidate)) || !filter.test(candidate)) {
            return 0;
        }
        long moved = Math.min(wanted, capacity - amount);
        if (moved <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        change(key == null ? candidate : key, amount + moved);
        return moved;
    }

    long extract(K candidate, long wanted, TransactionContext transaction) {
        if (wanted <= 0 || key == null || !key.equals(candidate)) {
            return 0;
        }
        long moved = Math.min(wanted, amount);
        if (moved <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        change(key, amount - moved);
        return moved;
    }

    /**
     * Replaces the contents outside any transaction and reports it; a null key or amount 0 empties the tank. Throws
     * {@link IllegalStateException} while a transaction is open on the thread.
     */
    void set(@Nullable K newKey, long newAmount) {
        NoTransaction.check("Setting a tank");
        if (newAmount < 0) {
            throw new IllegalArgumentException("Negative amount " + newAmount);
        }
        change(newKey, newAmount);
        onChange.run();
    }

    /** Replaces the contents without reporting it (loading). */
    void load(@Nullable K newKey, long newAmount) {
        change(newKey, newAmount);
    }

    private void change(@Nullable K newKey, long newAmount) {
        K next = newAmount == 0 ? null : newKey;
        long nextAmount = next == null ? 0 : newAmount;
        if (!Objects.equals(next, key)) {
            resource = Optional.ofNullable(next);
        }
        key = next;
        amount = nextAmount;
    }

    @Override
    protected State<K> createSnapshot() {
        return new State<>(key, amount);
    }

    @Override
    protected void revertToSnapshot(State<K> snapshot) {
        change(snapshot.key(), snapshot.amount());
    }

    @Override
    protected void onRootCommit(State<K> originalState) {
        if (originalState.amount() != amount || !Objects.equals(originalState.key(), key)) {
            onChange.run();
        }
    }
}
