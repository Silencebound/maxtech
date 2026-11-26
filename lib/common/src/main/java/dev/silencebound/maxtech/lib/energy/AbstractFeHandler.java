package dev.silencebound.maxtech.lib.energy;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it natively (PRD 7.2), the converters bridge it.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The transaction plumbing of an FE handler over one {@code long} amount: preconditions, snapshots before a change,
 * rollback, and a callback once a root transaction committed a change. Snapshots are pooled, so a caller moving FE
 * every tick allocates nothing once the pool is warm. Subclasses say how much may enter or leave right now; room and
 * stock are checked here. Server thread only, like every transfer handler.
 */
public abstract class AbstractFeHandler extends SnapshotJournal<long[]> implements EnergyHandler {
    private final ObjectArrayList<long[]> pool = new ObjectArrayList<>();

    /** FE held now. */
    protected abstract long amount();

    /** Replaces the amount; called inside a transaction after the snapshot was taken, or on rollback. */
    protected abstract void setAmount(long amount);

    /** Most FE one insert may add now, before room is checked; 0 refuses. */
    protected abstract long insertLimit();

    /** Most FE one extract may take now, before stock is checked; 0 refuses. */
    protected abstract long extractLimit();

    /** Runs after a root transaction committed a change from {@code before}; block updates and saving go here. */
    protected abstract void committed(long before);

    @Override
    public long getAmountAsLong() {
        return amount();
    }

    /** FE that still fits; 0 when full or above a lowered capacity. */
    public final long room() {
        return Math.max(0, getCapacityAsLong() - amount());
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        long inserted = Math.min(amount, Math.min(room(), insertLimit()));
        if (inserted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        setAmount(amount() + inserted);
        return (int) inserted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        long extracted = Math.min(amount, Math.min(amount(), extractLimit()));
        if (extracted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        setAmount(amount() - extracted);
        return (int) extracted;
    }

    @Override
    protected final long[] createSnapshot() {
        long[] snapshot = pool.isEmpty() ? new long[1] : pool.pop();
        snapshot[0] = amount();
        return snapshot;
    }

    @Override
    protected final void revertToSnapshot(long[] snapshot) {
        setAmount(snapshot[0]);
    }

    @Override
    protected final void releaseSnapshot(long[] snapshot) {
        pool.push(snapshot);
    }

    @Override
    protected final void onRootCommit(long[] originalState) {
        if (amount() != originalState[0]) {
            committed(originalState[0]);
        }
    }
}
