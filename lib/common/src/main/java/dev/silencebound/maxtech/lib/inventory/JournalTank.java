package dev.silencebound.maxtech.lib.inventory;

// PLATFORM-LEAK: a machine's tank moves are NeoForge transactions on the tank's journal (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A machine's transactional view of a tank ({@code lib.neoforge.api.inventory.Handlers.MachineTank}) over its
 * {@link TankJournal}: moves go straight to the journal, so they roll back with the caller's transaction and are
 * reported when the root commits. Pure apart from NeoForge's transactions.
 *
 * @param <K>
 *            the key type
 */
public final class JournalTank<K> {
    private final TankJournal<K> journal;

    JournalTank(TankJournal<K> journal) {
        this.journal = journal;
    }

    /** See {@code Handlers.MachineTank#insert}. */
    public long insert(K key, long amount, TransactionContext transaction) {
        return journal.insert(key, amount, transaction);
    }

    /** See {@code Handlers.MachineTank#extract}. */
    public long extract(K key, long amount, TransactionContext transaction) {
        return journal.extract(key, amount, transaction);
    }

    /** See {@code Handlers.MachineTank#insertExactly}. */
    public boolean insertExactly(K key, long amount, TransactionContext transaction) {
        if (amount <= 0) {
            return true;
        }
        try (Transaction nested = Transaction.open(transaction)) {
            if (journal.insert(key, amount, nested) != amount) {
                return false;
            }
            nested.commit();
            return true;
        }
    }

    /** See {@code Handlers.MachineTank#extractExactly}. */
    public boolean extractExactly(K key, long amount, TransactionContext transaction) {
        if (amount <= 0) {
            return true;
        }
        try (Transaction nested = Transaction.open(transaction)) {
            if (journal.extract(key, amount, nested) != amount) {
                return false;
            }
            nested.commit();
            return true;
        }
    }

    @Override
    public String toString() {
        return "MachineTank[" + journal.amount() + " " + journal.key() + "]";
    }
}
