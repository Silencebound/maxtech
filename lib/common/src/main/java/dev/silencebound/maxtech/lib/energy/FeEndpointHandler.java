package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyRole;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; MaxTech blocks expose it to other mods (MLE).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * A MaxTech energy buffer as an FE handler, for the FE capability of a MaxTech block: other mods' cables and machines
 * insert FE into its {@link EnergyEndpoint buffer endpoint} and extract FE from it, converted at the configured
 * {@link FeRate}. Inserting is always allowed; extracting follows the export settings (switched off, limited per tick
 * for this handler and per network together with the network's edges).
 * <p>
 * <b>Nothing is created.</b> The handler keeps the fraction of one MU that a conversion leaves (FE taken in that is not
 * a whole MU yet, or MU taken out that is not a whole FE yet) and uses it in the next operation; it is not saved, so at
 * most one MU is lost when the block unloads. A move changes the buffer at once and is journaled by its amount: a
 * rollback takes back exactly what this handler put in or gives back what it took, so it can lose energy (when the
 * owner filled or drained the buffer inside the same transaction) but never create any. Per-operation limits are the
 * buffer's input and output rates in FE.
 * <p>
 * Server thread only; allocation-free once the snapshot pool is warm.
 */
public final class FeEndpointHandler extends SnapshotJournal<long[]> implements EnergyHandler {
    private static final int HELD = 0;
    private static final int APPLIED = 1;
    private static final int EXPORT_TICK = 2;
    private static final int EXPORTED = 3;

    private final EnergyEndpoint buffer;
    private final LongSupplier clock;
    private final Supplier<EdgeSettings> settings;
    private final Runnable onChanged;
    private final ObjectArrayList<long[]> pool = new ObjectArrayList<>();
    private FeRate rate = FeRate.ONE_TO_ONE;
    /** Units of {@link #rate} held between FE and MU; always below one MU ({@code rate.fe()} units). */
    private long held;
    /** MU this handler moved into the buffer (negative: out of it) in the open root transaction. */
    private long applied;
    private long exportTick = -1;
    /** MU this handler sent out in {@link #exportTick}. */
    private long exported;

    /**
     * An FE view of {@code buffer} (a {@link EnergyRole#BUFFER buffer} endpoint) with settings from Lib's server
     * config; {@code clock} is the level's game time, {@code onChanged} runs after a root commit changed the buffer
     * (mark the block entity changed there).
     */
    public FeEndpointHandler(EnergyEndpoint buffer, LongSupplier clock, Runnable onChanged) {
        this(buffer, clock, ServerConfig::edgeSettings, onChanged);
    }

    /** As {@link #FeEndpointHandler(EnergyEndpoint, LongSupplier, Runnable)} with its own settings (tests). */
    public FeEndpointHandler(EnergyEndpoint buffer, LongSupplier clock, Supplier<EdgeSettings> settings,
            Runnable onChanged) {
        if (buffer.role() != EnergyRole.BUFFER) {
            throw new IllegalArgumentException("an FE handler needs a buffer endpoint, not a " + buffer.role());
        }
        this.buffer = buffer;
        this.clock = clock;
        this.settings = settings;
        this.onChanged = onChanged;
    }

    /** The endpoint this handler converts for. */
    public EnergyEndpoint buffer() {
        return buffer;
    }

    @Override
    public long getAmountAsLong() {
        FeRate current = settings.get().rate();
        return current.wholeFe(saturatedAdd(current.unitsOfMu(buffer.stored()), held(current)));
    }

    @Override
    public long getCapacityAsLong() {
        FeRate current = settings.get().rate();
        return current.wholeFe(current.unitsOfMu(buffer.capacity()));
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (amount == 0) {
            return 0;
        }
        useRate(settings.get().rate());
        long room = buffer.capacity() - buffer.stored();
        if (room < 0) {
            return 0;
        }
        // FE whose MU fit the room once the held fraction is added; the fraction of the last MU stays held.
        long fit = rate.wholeFe(saturatedAdd(rate.unitsOfMu(room), rate.fe() - 1 - held));
        long accepted = Math.min(amount, Math.min(fit, perOperation(buffer.maxInput())));
        if (accepted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        long units = held + rate.unitsOfFe(accepted);
        applied += buffer.insert(rate.wholeMu(units));
        held = units % rate.fe();
        return (int) accepted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        EdgeSettings current = settings.get();
        if (amount == 0 || !current.exportEnabled()) {
            return 0;
        }
        useRate(current.rate());
        long now = clock.getAsLong();
        long stock = buffer.stored();
        long budget = Math.min(current.edgeLimit() - (exportTick == now ? exported : 0),
                buffer.exportLeft(current.networkLimit()));
        long give = Math.min(amount, perOperation(buffer.maxOutput()));
        give = Math.min(give, rate.wholeFe(saturatedAdd(rate.unitsOfMu(stock), held)));
        give = Math.min(give, rate.wholeFe(saturatedAdd(rate.unitsOfMu(Math.max(0, budget)), held)));
        if (give <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        long need = rate.unitsOfFe(give);
        long mu = need > held ? rate.muCovering(need - held) : 0;
        applied -= buffer.extract(mu);
        held = held + rate.unitsOfMu(mu) - need;
        if (exportTick != now) {
            exportTick = now;
            exported = 0;
        }
        exported += mu;
        buffer.countExport(mu);
        return (int) give;
    }

    /**
     * {@code mu} per operation in whole FE, rounded down, but at least 1 FE when {@code mu} is not 0: where one FE is
     * worth more than the buffer's rate (1 FE = 7/3 MU against a rate of 2 MU), rounding down would give 0 and the
     * buffer could never be filled or drained through FE. So one operation may exceed the buffer's input or output rate
     * by up to 1 FE; the buffer's room and stock and the export limits still bound it exactly.
     */
    private long perOperation(long mu) {
        if (mu <= 0) {
            return 0;
        }
        return Math.max(1, rate.wholeFe(rate.unitsOfMu(mu)));
    }

    /** {@link #held} at {@code current}; a fraction counted at another rate does not count. */
    private long held(FeRate current) {
        return current.equals(rate) ? held : 0;
    }

    /** Switches to {@code newRate}; the held fraction of one MU, counted at the old rate, is dropped. */
    private void useRate(FeRate newRate) {
        if (!newRate.equals(rate)) {
            rate = newRate;
            held = 0;
        }
    }

    @Override
    protected long[] createSnapshot() {
        long[] snapshot = pool.isEmpty() ? new long[4] : pool.pop();
        snapshot[HELD] = held;
        snapshot[APPLIED] = applied;
        snapshot[EXPORT_TICK] = exportTick;
        snapshot[EXPORTED] = exported;
        return snapshot;
    }

    @Override
    protected void revertToSnapshot(long[] snapshot) {
        // The network's count is a sum shared by several handlers, so it is taken back by difference, not reset.
        long counted = snapshot[EXPORT_TICK] == exportTick ? exported - snapshot[EXPORTED] : exported;
        buffer.countExport(-counted);
        long undo = applied - snapshot[APPLIED];
        if (undo > 0) {
            buffer.extract(undo);
        } else if (undo < 0) {
            buffer.insert(-undo);
        }
        held = snapshot[HELD];
        applied = snapshot[APPLIED];
        exportTick = snapshot[EXPORT_TICK];
        exported = snapshot[EXPORTED];
    }

    @Override
    protected void releaseSnapshot(long[] snapshot) {
        pool.push(snapshot);
    }

    @Override
    protected void onRootCommit(long[] originalState) {
        long change = applied;
        applied = 0;
        if (change != 0) {
            onChanged.run();
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
