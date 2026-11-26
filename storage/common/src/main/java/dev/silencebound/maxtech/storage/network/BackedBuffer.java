package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.energy.FeBuffer;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.Transaction;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A subnet's FE store with its parent network behind it (owner decision 2026-10-04): it pays from the subnet's own
 * buffer first and from the parent's store for the rest, all or nothing, and reports both together. FE put in goes into
 * the subnet's own buffer only.
 *
 * <p>
 * A parent that is itself backed is asked in turn. While a backed store is being asked (its total read or a payment
 * made through it), it counts as empty to anyone who reaches it again, so in a loop of subnets each store counts and
 * pays once. One instance per network, re-bound on each call. Server thread only.
 */
final class BackedBuffer extends FeBuffer {
    /** Where the parent's store comes from; {@link NetworkState} is one. */
    interface Source {
        /** The store that pays for this source's operations, or null for none. */
        @Nullable
        FeBuffer energyBuffer();
    }

    private FeBuffer primary = this;
    private @Nullable Source parent;
    /** Set while this store is being asked, so a loop of subnets that reaches it again sees nothing. */
    private boolean busy;

    BackedBuffer() {
        super(0, 0, 0, BackedBuffer::nothing);
    }

    private static void nothing() {
    }

    /** Binds to {@code primary} with {@code parent}'s store behind it; returns this. */
    BackedBuffer bind(FeBuffer primary, Source parent) {
        this.primary = primary;
        this.parent = parent;
        return this;
    }

    /** The parent's store; call only while {@link #busy}. */
    private @Nullable FeBuffer backup() {
        Source current = parent;
        return current == null ? null : current.energyBuffer();
    }

    @Override
    public long stored() {
        if (busy) {
            return 0;
        }
        busy = true;
        try {
            FeBuffer backup = backup();
            return primary.stored() + (backup == null ? 0 : backup.stored());
        } finally {
            busy = false;
        }
    }

    @Override
    public long capacity() {
        if (busy) {
            return 0;
        }
        busy = true;
        try {
            FeBuffer backup = backup();
            return primary.capacity() + (backup == null ? 0 : backup.capacity());
        } finally {
            busy = false;
        }
    }

    @Override
    protected long amount() {
        return stored();
    }

    @Override
    public long getCapacityAsLong() {
        return capacity();
    }

    @Override
    protected void setAmount(long amount) {
        throw new IllegalStateException("A backed store has no amount of its own");
    }

    /**
     * Pays {@code cost} in a transaction nested in {@code transaction}: the own share and the parent's share commit
     * together or not at all, whatever the caller does with its transaction afterwards.
     */
    @Override
    public boolean tryConsume(long cost, TransactionContext transaction) {
        if (cost < 0) {
            throw new IllegalArgumentException("Negative cost " + cost);
        }
        if (cost == 0) {
            return true;
        }
        if (busy) {
            return false;
        }
        busy = true;
        try {
            long own = Math.min(primary.stored(), cost);
            FeBuffer backup = own == cost ? null : backup();
            if (own < cost && (backup == null || backup.stored() < cost - own)) {
                return false;
            }
            try (Transaction nested = Transaction.open(transaction)) {
                if (own > 0 && !primary.tryConsume(own, nested)) {
                    return false;
                }
                if (backup != null && !backup.tryConsume(cost - own, nested)) {
                    return false;
                }
                nested.commit();
                return true;
            }
        } finally {
            busy = false;
        }
    }

    /**
     * Pays {@code cost} outside a transaction, all or nothing: the parent's share first, which may fail, then the own
     * share, which cannot, since it is at most what the own store holds.
     */
    @Override
    public boolean tryConsume(long cost) {
        if (cost < 0) {
            throw new IllegalArgumentException("Negative cost " + cost);
        }
        if (cost == 0) {
            return true;
        }
        if (busy) {
            return false;
        }
        busy = true;
        try {
            long own = Math.min(primary.stored(), cost);
            if (own < cost) {
                FeBuffer backup = backup();
                if (backup == null || backup.stored() < cost - own || !backup.tryConsume(cost - own)) {
                    return false;
                }
            }
            return own == 0 || primary.tryConsume(own);
        } finally {
            busy = false;
        }
    }

    @Override
    public long receive(long offered) {
        return primary.receive(offered);
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        return primary.insert(amount, transaction);
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        return 0;
    }

    @Override
    protected long insertLimit() {
        return 0;
    }

    @Override
    protected long extractLimit() {
        return 0;
    }

    @Override
    public EnergyHandler inputOnly() {
        return primary.inputOnly();
    }

    @Override
    public EnergyHandler outputOnly() {
        return primary.outputOnly();
    }
}
