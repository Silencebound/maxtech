package dev.silencebound.maxtech.lib.energy;

// PLATFORM-LEAK: FE is NeoForge's energy transfer type (PRD 7.2).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * An energy store for a MaxTech block (Storage's controller, devices and machines): a {@code long} amount in the
 * owner's unit (MU since MLE) with a capacity and per-operation insert and extract limits, transaction-aware through
 * {@link AbstractFeHandler}. Devices pay per operation with {@link #tryConsume(long, TransactionContext)} inside the
 * transaction that moves their items, so a rollback refunds the energy too. The handler methods move the store's own
 * unit; a block shows FE to other mods through a converting view, never this store directly.
 *
 * <p>
 * Hand {@link #inputOnly()} or {@link #outputOnly()} to the capability for sides that should only take or only give;
 * both are views of this one buffer and its journal. A capacity lowered below the amount keeps the excess, which only
 * drains. Limits and capacity are plain fields: an owner whose config changes sets them again. Server thread only;
 * allocation-free once the snapshot pool is warm.
 */
public class FeBuffer extends AbstractFeHandler {
    private final Runnable onChanged;
    private final EnergyHandler inputOnly = new View(true, false);
    private final EnergyHandler outputOnly = new View(false, true);
    private long amount;
    private long capacity;
    private long maxInsert;
    private long maxExtract;

    /**
     * A buffer of {@code capacity} FE taking at most {@code maxInsert} and giving at most {@code maxExtract} per
     * operation; {@code onChanged} runs once per committed change (mark the block entity changed there).
     */
    public FeBuffer(long capacity, long maxInsert, long maxExtract, Runnable onChanged) {
        this.onChanged = onChanged;
        // Not through setCapacity: a subclass's override would run before its own fields are set.
        this.capacity = Math.max(0, capacity);
        setLimits(maxInsert, maxExtract);
    }

    /** Energy held now; what the owner saves. */
    public long stored() {
        return amount();
    }

    public long capacity() {
        return capacity;
    }

    /** Sets a new capacity (at least 0); a lower one keeps the excess until it drains. */
    public void setCapacity(long capacity) {
        this.capacity = Math.max(0, capacity);
    }

    /**
     * Runs the owner's change callback, for energy that moved without a call on this buffer (an energy network's flow
     * into an endpoint-backed buffer), so the owner saves it.
     */
    public final void changed() {
        onChanged.run();
    }

    /** The per-operation insert limit. */
    protected final long maxInsert() {
        return maxInsert;
    }

    /** The per-operation extract limit. */
    protected final long maxExtract() {
        return maxExtract;
    }

    /** Sets the per-operation limits (negative counts as 0). */
    public void setLimits(long maxInsert, long maxExtract) {
        this.maxInsert = Math.max(0, maxInsert);
        this.maxExtract = Math.max(0, maxExtract);
    }

    /**
     * Restores a saved amount without firing {@code onChanged}; outside any transaction (load time). Negative counts as
     * 0; an amount above the capacity is kept.
     */
    public void load(long stored) {
        setAmount(Math.max(0, stored));
    }

    /**
     * Takes exactly {@code cost} FE, or nothing when less is stored, as part of {@code transaction}: a device pays for
     * an operation in the transaction that does it. Ignores the extract limit, which is for neighbours pulling FE out.
     * Returns whether it was paid; a cost of 0 always is.
     */
    public boolean tryConsume(long cost, TransactionContext transaction) {
        if (cost < 0) {
            throw new IllegalArgumentException("Negative cost " + cost);
        }
        if (cost == 0) {
            return true;
        }
        long held = amount();
        if (held < cost) {
            return false;
        }
        updateSnapshots(transaction);
        setAmount(held - cost);
        return true;
    }

    /**
     * Takes exactly {@code cost} FE, or nothing when less is stored, outside any transaction, and fires
     * {@code onChanged} when it took some. For a device that pays for work no transaction covers; must not be called
     * while a transaction on this buffer is open.
     */
    public boolean tryConsume(long cost) {
        if (cost < 0) {
            throw new IllegalArgumentException("Negative cost " + cost);
        }
        long held = amount();
        if (held < cost) {
            return false;
        }
        if (cost > 0) {
            setAmount(held - cost);
            onChanged.run();
        }
        return true;
    }

    /**
     * Adds up to {@code offered} FE outside any transaction, ignoring the insert limit (an owner's own generator), and
     * fires {@code onChanged} when it added some. Returns what was added.
     */
    public long receive(long offered) {
        long added = Math.min(Math.max(0, offered), room());
        if (added > 0) {
            setAmount(amount() + added);
            onChanged.run();
        }
        return added;
    }

    /** A view that only takes FE; for input sides. */
    public EnergyHandler inputOnly() {
        return inputOnly;
    }

    /** A view that only gives FE; for output sides. */
    public EnergyHandler outputOnly() {
        return outputOnly;
    }

    @Override
    public long getCapacityAsLong() {
        return capacity;
    }

    @Override
    protected long amount() {
        return amount;
    }

    @Override
    protected void setAmount(long amount) {
        this.amount = amount;
    }

    @Override
    protected long insertLimit() {
        return maxInsert;
    }

    @Override
    protected long extractLimit() {
        return maxExtract;
    }

    @Override
    protected void committed(long before) {
        onChanged.run();
    }

    /** Shares the buffer's amount and journal; only the direction differs. */
    private final class View implements EnergyHandler {
        private final boolean in;
        private final boolean out;

        View(boolean in, boolean out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public long getAmountAsLong() {
            return FeBuffer.this.getAmountAsLong();
        }

        @Override
        public long getCapacityAsLong() {
            return FeBuffer.this.getCapacityAsLong();
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return in ? FeBuffer.this.insert(amount, transaction) : 0;
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            return out ? FeBuffer.this.extract(amount, transaction) : 0;
        }
    }
}
