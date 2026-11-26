package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyPriority;
import dev.silencebound.maxtech.lib.energy.EndpointBuffer;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.LongSupplier;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage runs on it (PRD 7.2).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The energy cells of one network as one pool: their stored MU and capacity as running totals, so reading the network's
 * energy is O(1) however many cells it has. Every change of a cell's amount, committed or inside an open transaction
 * (and on rollback), reaches the totals at once through {@link Store}, so the totals are always exact.
 * <p>
 * Each cell is also a buffer endpoint of its storage network's MaxTech energy network (MLE), charged at
 * {@link EnergyPriority#LEFTOVERS leftovers} priority by the network's pass. Between two passes a cell's energy changes
 * linearly with its flow (the pass wakes before any buffer fills or empties), so the pool keeps the sum of the cells'
 * flows beside the stored total as of a game tick and adds {@code flows * elapsed ticks} when read; a flow change,
 * which the pass reports per cell, moves that tick on first. The clock is the energy scheduler's ({@link #bind}).
 *
 * <p>
 * Filling and draining walk the cells from a cursor like a water level: cells below it are full, cells above it empty,
 * so in steady use a fill or a drain touches one or two cells. A cell charged or drained from outside the network only
 * makes the next walk skip it. Fill and drain return at once when the pool is full or empty, so a generator pushing
 * into a network whose cells are all full costs O(1). Server thread only.
 */
public final class EnergyCells {
    private final ObjectArrayList<Store> cells = new ObjectArrayList<>();
    /** Cells whose energy the network's flow changes every tick: their blocks are told each tick ({@link #tick}). */
    private final ObjectArrayList<Store> flowing = new ObjectArrayList<>(0);
    /** Cells with edges to other mods' blocks, whose held energy is saved with them ({@link Store#setEdged}). */
    private final ObjectArrayList<Store> edged = new ObjectArrayList<>(0);
    private final Combined combined = new Combined();
    /** The cells' energy at {@link #baseTick}, with every direct change since. */
    private long base;
    /** Game tick up to which {@link #base} counts the flows. */
    private long baseTick;
    /**
     * The sum of the cells' flows from the energy network, MU per tick; never negative (cells give the network none).
     */
    private long flowSum;
    /** MU the flows put into the cells up to {@link #baseTick}. */
    private long charged;
    private LongSupplier clock = EnergyCells::noClock;
    private long capacity;
    private int cursor;
    /** Cells looked at by fills and drains since the pool was made; tests check it stays O(1) per call. */
    private long visits;

    /**
     * Moves the flows on by {@code gameTime}, the clock of the energy scheduler the cells' endpoints are in; without it
     * the pool counts no time, which is right while no cell is in an energy network.
     */
    void bind(LongSupplier gameTime) {
        rebase();
        clock = gameTime;
        baseTick = gameTime.getAsLong();
    }

    /** Adds {@code store} to the pool (a cell joined), taking it from any pool it was in. */
    void add(Store store) {
        EnergyCells previous = store.pool;
        if (previous == this) {
            return;
        }
        if (previous != null) {
            previous.remove(store);
        }
        rebase();
        cells.add(store);
        store.pool = this;
        base += store.stored();
        flowSum += store.lastFlow;
        capacity += store.capacity();
        if (store.lastFlow != 0) {
            flowing.add(store);
        }
        if (store.edged) {
            edged.add(store);
        }
    }

    /** Takes {@code store} out of the pool (a cell left); nothing happens if it is not in it. */
    void remove(Store store) {
        if (store.pool != this) {
            return;
        }
        rebase();
        cells.remove(store);
        store.pool = null;
        base -= store.stored();
        flowSum -= store.lastFlow;
        capacity -= store.capacity();
        flowing.remove(store);
        edged.remove(store);
        if (cursor >= cells.size()) {
            cursor = Math.max(0, cells.size() - 1);
        }
    }

    private static long noClock() {
        return 0;
    }

    /** Moves {@link #base} on to now along the flows. */
    private void rebase() {
        long now = clock.getAsLong();
        if (flowSum != 0 && now > baseTick) {
            base += flowSum * (now - baseTick);
            charged += flowSum * (now - baseTick);
        }
        baseTick = now;
    }

    /** A pass changed {@code store}'s flow to {@code flow}, after settling it at the old one. */
    private void flowChanged(Store store, long flow) {
        rebase();
        flowSum += flow - store.lastFlow;
        if (store.lastFlow == 0 && flow != 0) {
            flowing.add(store);
        } else if (store.lastFlow != 0 && flow == 0) {
            flowing.remove(store);
        }
    }

    /**
     * Once per tick from the network's tick: cells whose energy the flow changes, and cells whose edges move energy
     * (their saved energy includes what the edges hold), tell their block ({@link Store.Ticker}), so the chunk is saved
     * with the energy it holds and the gauge follows. Costs a size check while nothing flows.
     */
    void tick() {
        for (int i = 0; i < flowing.size(); i++) {
            Store store = flowing.get(i);
            Store.Ticker ticker = store.ticker;
            if (ticker != null) {
                ticker.flowTick();
            }
        }
        for (int i = 0; i < edged.size(); i++) {
            Store store = edged.get(i);
            Store.Ticker ticker = store.ticker;
            if (store.lastFlow == 0 && ticker != null && ticker.edgesActive()) {
                ticker.flowTick();
            }
        }
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Cells in the pool. */
    public int size() {
        return cells.size();
    }

    /** MU in every cell together, with the flows up to now. O(1). */
    public long stored() {
        if (flowSum == 0) {
            return base;
        }
        long elapsed = clock.getAsLong() - baseTick;
        return elapsed > 0 ? base + flowSum * elapsed : base;
    }

    /** MU the energy network's flows put into the cells while they were in this pool, up to now. O(1). */
    long charged() {
        long elapsed = clock.getAsLong() - baseTick;
        return flowSum != 0 && elapsed > 0 ? charged + flowSum * elapsed : charged;
    }

    /** Cells fills and drains have looked at so far; for perf tests and diagnostics. */
    public long visits() {
        return visits;
    }

    /** What every cell together can hold. */
    public long capacity() {
        return capacity;
    }

    /**
     * {@code primary} with the cells behind it, as one buffer: it reports both together, pays from {@code primary}
     * first and the cells after, and takes FE into {@code primary} first and the cells after. One instance per pool,
     * re-bound on each call.
     */
    FeBuffer over(FeBuffer primary) {
        combined.primary = primary;
        return combined;
    }

    /** Takes up to {@code amount} FE from the cells, ignoring their output limits; returns what it took. */
    private long drain(long amount, @Nullable TransactionContext transaction) {
        int n = cells.size();
        if (n == 0 || stored() <= 0 || amount <= 0) {
            return 0;
        }
        long left = amount;
        int i = Math.min(cursor, n - 1);
        for (int visited = 0; visited < n && left > 0; visited++) {
            visits++;
            Store cell = cells.get(i);
            long take = Math.min(cell.stored(), left);
            if (take > 0 && (transaction == null ? cell.tryConsume(take) : cell.tryConsume(take, transaction))) {
                left -= take;
            }
            if (left > 0) {
                i = i == 0 ? n - 1 : i - 1;
            }
        }
        cursor = i;
        return amount - left;
    }

    /** Puts up to {@code amount} FE into the cells, within each cell's input limit; returns what went in. */
    private long fill(long amount, TransactionContext transaction) {
        int n = cells.size();
        if (n == 0 || stored() >= capacity || amount <= 0) {
            return 0;
        }
        long left = amount;
        int i = Math.min(cursor, n - 1);
        for (int visited = 0; visited < n && left > 0; visited++) {
            visits++;
            left -= cells.get(i).insert((int) Math.min(left, Integer.MAX_VALUE), transaction);
            if (left > 0) {
                i = i == n - 1 ? 0 : i + 1;
            }
        }
        cursor = i;
        return amount - left;
    }

    /** Adds up to {@code amount} FE to the cells outside any transaction, ignoring input limits. */
    private long receive(long amount) {
        int n = cells.size();
        long left = amount;
        int i = Math.min(cursor, Math.max(0, n - 1));
        for (int visited = 0; visited < n && left > 0 && stored() < capacity; visited++) {
            visits++;
            left -= cells.get(i).receive(left);
            if (left > 0) {
                i = i == n - 1 ? 0 : i + 1;
            }
        }
        cursor = i;
        return amount - left;
    }

    /**
     * An energy cell's MU store: an {@link EndpointBuffer} whose every change of amount or capacity is added to the
     * pool it is in at once, inside transactions too, and whose flow changes from the energy network move the pool's
     * flow sum, so the pool's totals stay exact. Neighbours use it through the cell's FE capability within its limits;
     * operations drain it without the output limit. Its endpoint takes up to the transfer limit per tick from the
     * network at leftovers priority and gives the network nothing.
     */
    public static class Store extends EndpointBuffer {
        /** What a cell's block does each tick while its energy moves without a direct change. */
        public interface Ticker {
            /** The energy changed this tick: mark the block changed, update its look. */
            void flowTick();

            /** Whether the cell's edges move energy now. */
            boolean edgesActive();
        }

        private @Nullable EnergyCells pool;
        /** The endpoint's flow as the pool last counted it. */
        private long lastFlow;
        private @Nullable Ticker ticker;
        private boolean edged;

        /** See {@link FeBuffer#FeBuffer}; the endpoint takes up to {@code maxInsert} per tick. */
        public Store(long capacity, long maxInsert, long maxExtract, Runnable onChanged) {
            super(capacity, maxInsert, maxExtract, maxInsert, 0, EnergyPriority.LEFTOVERS, onChanged);
            endpoint().setListener(this::flowChanged);
        }

        private void flowChanged(EnergyEndpoint endpoint) {
            long flow = endpoint.flow();
            EnergyCells current = pool;
            if (current != null) {
                current.flowChanged(this, flow);
            }
            lastFlow = flow;
        }

        /** Sets what the cell's block does each tick while the network moves its energy. */
        public void setTicker(Ticker newTicker) {
            ticker = newTicker;
        }

        /**
         * Whether the cell has edges to other mods' blocks; the pool then asks it each tick whether they move energy.
         */
        public void setEdged(boolean on) {
            if (on == edged) {
                return;
            }
            edged = on;
            EnergyCells current = pool;
            if (current != null) {
                if (on) {
                    current.edged.add(this);
                } else {
                    current.edged.remove(this);
                }
            }
        }

        /** Sets the per-operation limits for neighbours and the endpoint's input rate to {@code limit}. */
        public void setTransfer(long limit) {
            setLimits(limit, limit);
            setRates(limit, 0);
        }

        private void counted(long delta) {
            EnergyCells current = pool;
            if (current != null) {
                current.base += delta;
            }
        }

        @Override
        protected void setAmount(long amount) {
            long before = amount();
            super.setAmount(amount);
            counted(amount() - before);
        }

        @Override
        public void setCapacity(long capacity) {
            long before = capacity();
            long storedBefore = amount();
            super.setCapacity(capacity);
            EnergyCells current = pool;
            if (current != null) {
                current.capacity += capacity() - before;
            }
            counted(amount() - storedBefore);
        }
    }

    /** The network's buffer and the pool as one store; see {@link #over}. */
    private final class Combined extends FeBuffer {
        private final EnergyHandler input = new Input();
        private FeBuffer primary = this;

        Combined() {
            super(0, 0, 0, Combined::nothing);
        }

        private static void nothing() {
        }

        @Override
        public long stored() {
            return primary.stored() + EnergyCells.this.stored();
        }

        @Override
        public long capacity() {
            return primary.capacity() + EnergyCells.this.capacity;
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
            throw new IllegalStateException("The network's combined store has no amount of its own");
        }

        @Override
        public boolean tryConsume(long cost, TransactionContext transaction) {
            if (cost < 0) {
                throw new IllegalArgumentException("Negative cost " + cost);
            }
            if (cost == 0) {
                return true;
            }
            if (stored() < cost) {
                return false;
            }
            long fromPrimary = Math.min(primary.stored(), cost);
            if (fromPrimary > 0 && !primary.tryConsume(fromPrimary, transaction)) {
                return false;
            }
            // The totals are exact, so the cells hold the rest; a shortfall would only leave the caller's
            // transaction to roll back.
            return drain(cost - fromPrimary, transaction) == cost - fromPrimary;
        }

        @Override
        public boolean tryConsume(long cost) {
            if (cost < 0) {
                throw new IllegalArgumentException("Negative cost " + cost);
            }
            if (stored() < cost) {
                return false;
            }
            long fromPrimary = Math.min(primary.stored(), cost);
            if (fromPrimary > 0 && !primary.tryConsume(fromPrimary)) {
                return false;
            }
            return drain(cost - fromPrimary, null) == cost - fromPrimary;
        }

        @Override
        public long receive(long offered) {
            long added = primary.receive(offered);
            return added + EnergyCells.this.receive(Math.max(0, offered - added));
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            int inserted = primary.insert(amount, transaction);
            return inserted + (int) fill(amount - inserted, transaction);
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
            return input;
        }

        @Override
        public EnergyHandler outputOnly() {
            return input;
        }

        /** Takes FE into the primary buffer, then the cells; gives none. */
        private final class Input implements EnergyHandler {
            @Override
            public long getAmountAsLong() {
                return stored();
            }

            @Override
            public long getCapacityAsLong() {
                return capacity();
            }

            @Override
            public int insert(int amount, TransactionContext transaction) {
                return Combined.this.insert(amount, transaction);
            }

            @Override
            public int extract(int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonNegative(amount);
                return 0;
            }
        }
    }
}
