package dev.silencebound.maxtech.farms.hub;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import java.util.ArrayDeque;
import java.util.function.Supplier;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; other mods charge the hub through it.
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The lab hub's MU store as NeoForge's FE capability, at Lib's FE rate, so other mods' generators and cables charge it.
 * Insert only: the hub spends its MU itself. FE that would not make a whole MU is refused, never kept, so no rate
 * creates energy. Every insert is journaled in the caller's transaction and undone on abort; snapshots are pooled, so a
 * transfer allocates nothing once warm. Server thread only.
 */
public final class HubEnergyHandler implements EnergyHandler {
    private final EnergyEndpoint store;
    private final Supplier<FeRate> rate;
    private final Runnable onCommit;
    private final Journal journal = new Journal();

    /** FE into {@code store} at {@code rate}; {@code onCommit} runs once a root transaction that changed it commits. */
    public HubEnergyHandler(EnergyEndpoint store, Supplier<FeRate> rate, Runnable onCommit) {
        this.store = store;
        this.rate = rate;
        this.onCommit = onCommit;
    }

    @Override
    public long getAmountAsLong() {
        return rate.get().toFe(store.stored());
    }

    @Override
    public long getCapacityAsLong() {
        return rate.get().toFe(store.capacity());
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        FeRate current = rate.get();
        long mu = Math.min(current.toMu(amount), store.capacity() - store.stored());
        if (mu <= 0) {
            return 0;
        }
        // The FE those MU are worth, rounded up: never more than offered, since mu came from rounding down.
        long fe = (current.unitsOfMu(mu) + current.mu() - 1) / current.mu();
        journal.updateSnapshots(transaction);
        store.insert(mu);
        return (int) fe;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return 0;
    }

    /** The store's level before a transaction changed it, in a pooled one-element array. */
    private final class Journal extends SnapshotJournal<long[]> {
        private final ArrayDeque<long[]> pool = new ArrayDeque<>();

        @Override
        protected long[] createSnapshot() {
            long[] snapshot = pool.isEmpty() ? new long[1] : pool.pop();
            snapshot[0] = store.stored();
            return snapshot;
        }

        @Override
        protected void revertToSnapshot(long[] snapshot) {
            store.setStored(snapshot[0]);
        }

        @Override
        protected void releaseSnapshot(long[] snapshot) {
            pool.push(snapshot);
        }

        @Override
        protected void onRootCommit(long[] originalState) {
            onCommit.run();
        }
    }
}
