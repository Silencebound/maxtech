package dev.silencebound.maxtech.storage.network;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
// PLATFORM-LEAK: the tally is journaled in NeoForge's transfer transactions, like the FE it counts.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A network's running FE totals, received and spent, journaled in the transaction that moves the FE: a rolled-back
 * transfer counts nothing, so the totals only ever hold committed FE. It also keeps this tick's operation costs before
 * and after efficiency cores, so the discount applies to the tick's sum ({@link OperationCost#tickCharge}); a
 * rolled-back operation takes its share back out. Snapshots are pooled; a counted operation allocates nothing once the
 * pool is warm. Server thread only.
 */
final class EnergyTally extends SnapshotJournal<long[]> {
    private final ObjectArrayList<long[]> pool = new ObjectArrayList<>();
    private long received;
    private long spent;
    /** Game time the per-tick sums below belong to. */
    private long tick = Long.MIN_VALUE;
    private long rawThisTick;
    private long paidThisTick;

    void receive(long fe, TransactionContext transaction) {
        if (fe > 0) {
            updateSnapshots(transaction);
            received += fe;
        }
    }

    /**
     * What an operation costing {@code raw} FE before efficiency cores is charged at {@code percent} in tick
     * {@code now}; see {@link OperationCost#tickCharge}. Changes nothing: pass the answer to {@link #spend}.
     */
    long charge(long raw, int percent, long now) {
        return now == tick
                ? OperationCost.tickCharge(rawThisTick, paidThisTick, raw, percent)
                : OperationCost.tickCharge(0, 0, raw, percent);
    }

    /** Counts {@code fe} charged for an operation costing {@code raw} before the discount, in tick {@code now}. */
    void spend(long fe, long raw, long now, TransactionContext transaction) {
        if (fe <= 0 && raw <= 0) {
            return;
        }
        updateSnapshots(transaction);
        if (now != tick) {
            tick = now;
            rawThisTick = 0;
            paidThisTick = 0;
        }
        rawThisTick += Math.max(0, raw);
        paidThisTick += Math.max(0, fe);
        spent += Math.max(0, fe);
    }

    long received() {
        return received;
    }

    long spent() {
        return spent;
    }

    @Override
    protected long[] createSnapshot() {
        long[] snapshot = pool.isEmpty() ? new long[5] : pool.pop();
        snapshot[0] = received;
        snapshot[1] = spent;
        snapshot[2] = tick;
        snapshot[3] = rawThisTick;
        snapshot[4] = paidThisTick;
        return snapshot;
    }

    @Override
    protected void revertToSnapshot(long[] snapshot) {
        received = snapshot[0];
        spent = snapshot[1];
        tick = snapshot[2];
        rawThisTick = snapshot[3];
        paidThisTick = snapshot[4];
    }

    @Override
    protected void releaseSnapshot(long[] snapshot) {
        pool.push(snapshot);
    }
}
