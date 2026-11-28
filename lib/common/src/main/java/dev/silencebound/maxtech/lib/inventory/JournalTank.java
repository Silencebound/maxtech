package dev.silencebound.maxtech.lib.inventory;

import dev.silencebound.maxtech.lib.api.inventory.MachineAccess;
// PLATFORM-LEAK: a machine's tank moves are NeoForge transactions on the tank's journal (ADR-0002).
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * {@link MachineAccess.Tank} over a tank's {@link TankJournal}: moves go straight to the journal, so they roll back
 * with the caller's transaction and are reported when the root commits. Pure apart from NeoForge's transactions.
 *
 * @param <K>
 *            the key type
 */
final class JournalTank<K> implements MachineAccess.Tank<K> {
    private final TankJournal<K> journal;

    JournalTank(TankJournal<K> journal) {
        this.journal = journal;
    }

    @Override
    public long insert(K key, long amount, TransactionContext transaction) {
        return journal.insert(key, amount, transaction);
    }

    @Override
    public long extract(K key, long amount, TransactionContext transaction) {
        return journal.extract(key, amount, transaction);
    }

    @Override
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

    @Override
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
