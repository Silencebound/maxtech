package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.api.energy.EdgeSettings;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.FeRate;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
// PLATFORM-LEAK: FE is NeoForge's energy transfer type; Storage's blocks show it to other mods (MLE).
import net.neoforged.neoforge.transfer.TransferPreconditions;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * What other mods see of a Storage block's or item's energy: an FE handler over the block's MU handler (its store, or
 * its network's input), converted at Lib's configured {@link FeRate} ({@code energy.feRateFe}/{@code feRateMu}, default
 * 1:1). Every move goes through the MU handler inside the caller's transaction, so Storage's own journals and running
 * totals see it exactly as they see a move in MU; this view only converts.
 * <p>
 * <b>Nothing is created.</b> Both directions round down. The fraction of one MU that a conversion leaves (FE taken in
 * that is not a whole MU yet, or MU taken out that is not a whole FE yet) is held here in units of the rate
 * ({@link FeRate#unitsOfFe}) and used in the next move, so a stream of small inserts at an uneven rate is as exact as
 * one large one. The holder saves it ({@link #save}) and a save migrated from FE carries the remainder of its
 * conversion in it; when the rate changes the held amount is kept in whole FE. Changes to it are journaled.
 * <p>
 * <b>Export.</b> Extracting is energy leaving MaxTech for another mod's block, so it follows Lib's export settings
 * ({@code energy.exportEnabled}, {@code exportPerEdge} per tick for this view, {@code exportPerNetwork} together with
 * the network of {@code exportEndpoint}'s endpoint). Inserting is never limited here. Server thread only;
 * allocation-free once the snapshot pool is warm.
 */
public final class FeView extends SnapshotJournal<long[]> implements EnergyHandler {
    /** Saved key of the held units. */
    public static final String HELD = "fe_held";
    /** Saved keys of the rate the held units are counted at. */
    public static final String HELD_RATE_FE = "fe_held_rate_fe";
    public static final String HELD_RATE_MU = "fe_held_rate_mu";

    private static final int SNAP_HELD = 0;
    private static final int SNAP_EXPORT_TICK = 1;
    private static final int SNAP_EXPORTED = 2;

    private final EnergyHandler store;
    private final boolean in;
    private final boolean out;
    private final boolean device;
    private final Supplier<@Nullable EnergyEndpoint> exportEndpoint;
    private final LongSupplier clock;
    private final Supplier<EdgeSettings> settings;
    private final Runnable onHeldChanged;
    private final ObjectArrayList<long[]> pool = new ObjectArrayList<>();
    private FeRate rate = FeRate.ONE_TO_ONE;
    /** Units of {@link #rate} this view holds for the store. */
    private long held;
    private long exportTick = -1;
    /** MU sent out through this view in {@link #exportTick}. */
    private long exported;

    private FeView(EnergyHandler store, boolean in, boolean out, boolean device,
            Supplier<@Nullable EnergyEndpoint> exportEndpoint, LongSupplier clock, Supplier<EdgeSettings> settings,
            Runnable onHeldChanged) {
        this.store = store;
        this.settings = settings;
        this.in = in;
        this.out = out;
        this.device = device;
        this.exportEndpoint = exportEndpoint;
        this.clock = clock;
        this.onHeldChanged = onHeldChanged;
    }

    /**
     * A view that only takes FE into {@code store} (MU), for a machine or an item; {@code onHeldChanged} runs when the
     * held fraction changed.
     */
    public static FeView input(EnergyHandler store, Runnable onHeldChanged) {
        return new FeView(store, true, false, false, FeView::none, FeView::noClock, ServerConfig::edgeSettings,
                onHeldChanged);
    }

    /** {@link #input} for a storage network device, whose store is its network's ({@link #isDevice()}). */
    public static FeView deviceInput(EnergyHandler store, Runnable onHeldChanged) {
        return new FeView(store, true, false, true, FeView::none, FeView::noClock, ServerConfig::edgeSettings,
                onHeldChanged);
    }

    /**
     * A view that takes FE in ({@code in}) and gives it out ({@code out}) under the export settings; exports count
     * against the per-network limit of {@code exportEndpoint}'s network (null outside one) and the per-edge limit per
     * game tick of {@code clock}.
     */
    public static FeView of(EnergyHandler store, boolean in, boolean out, boolean device,
            Supplier<@Nullable EnergyEndpoint> exportEndpoint, LongSupplier clock, Runnable onHeldChanged) {
        return new FeView(store, in, out, device, exportEndpoint, clock, ServerConfig::edgeSettings, onHeldChanged);
    }

    /** {@link #of} with its own settings instead of Lib's config; for tests. */
    static FeView withSettings(EnergyHandler store, boolean in, boolean out,
            Supplier<@Nullable EnergyEndpoint> exportEndpoint, LongSupplier clock, Supplier<EdgeSettings> settings) {
        return new FeView(store, in, out, false, exportEndpoint, clock, settings, FeView::nothing);
    }

    private static void nothing() {
    }

    /** The MU handler this view converts for. */
    public EnergyHandler store() {
        return store;
    }

    /**
     * Whether this is a storage network device's view, which the fuel generator feeds through the energy network rather
     * than through the view.
     */
    public boolean isDevice() {
        return device;
    }

    private static @Nullable EnergyEndpoint none() {
        return null;
    }

    private static long noClock() {
        return 0;
    }

    @Override
    public long getAmountAsLong() {
        return settings.get().rate().toFe(store.getAmountAsLong());
    }

    @Override
    public long getCapacityAsLong() {
        return settings.get().rate().toFe(store.getCapacityAsLong());
    }

    @Override
    public int insert(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (!in || amount == 0) {
            return 0;
        }
        useRate(settings.get().rate(), transaction);
        long units = held + rate.unitsOfFe(amount);
        long want = Math.min(rate.wholeMu(units), Integer.MAX_VALUE);
        int moved = want > 0 ? store.insert((int) want, transaction) : 0;
        long used = rate.unitsOfMu(moved);
        long accepted;
        long next;
        if (moved == want) {
            accepted = amount;
            next = units - used;
        } else {
            // The store took less: take only the FE that pays for what went in, the fraction of the last FE held.
            long need = used - held;
            accepted = need <= 0 ? 0 : Math.min(amount, (need - 1) / rate.mu() + 1);
            next = held + rate.unitsOfFe(accepted) - used;
        }
        setHeld(next, transaction);
        return (int) accepted;
    }

    @Override
    public int extract(int amount, TransactionContext transaction) {
        TransferPreconditions.checkNonNegative(amount);
        if (!out || amount == 0) {
            return 0;
        }
        EdgeSettings current = settings.get();
        if (!current.exportEnabled()) {
            return 0;
        }
        useRate(current.rate(), transaction);
        long now = clock.getAsLong();
        EnergyEndpoint endpoint = exportEndpoint.get();
        long budget = Math.min(current.edgeLimit() - (exportTick == now ? exported : 0),
                endpoint == null ? Long.MAX_VALUE : endpoint.exportLeft(current.networkLimit()));
        long stock = Math.min(store.getAmountAsLong(), Math.max(0, budget));
        long give = Math.min(amount, rate.wholeFe(saturatedAdd(rate.unitsOfMu(stock), held)));
        if (give <= 0) {
            return 0;
        }
        long need = rate.unitsOfFe(give);
        long mu = need > held ? Math.min(rate.muCovering(need - held), Integer.MAX_VALUE) : 0;
        updateSnapshots(transaction);
        int taken = mu > 0 ? store.extract((int) mu, transaction) : 0;
        long available = held + rate.unitsOfMu(taken);
        if (taken < mu) {
            // The store's own limit: give the whole FE that what it gave pays for.
            give = Math.min(give, rate.wholeFe(available));
            need = rate.unitsOfFe(give);
        }
        held = available - need;
        if (exportTick != now) {
            exportTick = now;
            exported = 0;
        }
        exported += taken;
        if (endpoint != null) {
            endpoint.countExport(taken);
        }
        return (int) give;
    }

    /** The held units, counted at {@link #heldRate()}; for saving and tests. */
    public long held() {
        return held;
    }

    /** The rate {@link #held()} is counted at. */
    public FeRate heldRate() {
        return rate;
    }

    /** Puts back held units counted at {@code at}, outside any transaction (loading). */
    public void restoreHeld(long units, FeRate at) {
        rate = at;
        held = Math.max(0, units);
    }

    /** Saves the held units and their rate; nothing when none are held. */
    public void save(ValueOutput output) {
        if (held > 0) {
            output.putLong(HELD, held);
            output.putInt(HELD_RATE_FE, rate.fe());
            output.putInt(HELD_RATE_MU, rate.mu());
        }
    }

    /** Loads what {@link #save} wrote, or nothing held. */
    public void load(ValueInput input) {
        long units = input.getLongOr(HELD, 0L);
        int fe = Math.max(1, input.getIntOr(HELD_RATE_FE, 1));
        int mu = Math.max(1, input.getIntOr(HELD_RATE_MU, 1));
        restoreHeld(units, new FeRate(fe, mu));
    }

    /** Switches to {@code newRate}, keeping the held amount in whole FE (a fraction of one FE is lost). */
    private void useRate(FeRate newRate, TransactionContext transaction) {
        if (!newRate.equals(rate)) {
            long fe = rate.wholeFe(held);
            updateSnapshots(transaction);
            rate = newRate;
            held = newRate.unitsOfFe(fe);
        }
    }

    private void setHeld(long next, TransactionContext transaction) {
        if (next != held) {
            updateSnapshots(transaction);
            held = next;
        }
    }

    @Override
    protected long[] createSnapshot() {
        long[] snapshot = pool.isEmpty() ? new long[3] : pool.pop();
        snapshot[SNAP_HELD] = held;
        snapshot[SNAP_EXPORT_TICK] = exportTick;
        snapshot[SNAP_EXPORTED] = exported;
        return snapshot;
    }

    @Override
    protected void revertToSnapshot(long[] snapshot) {
        // The network's export count is shared by several views and edges, so it is taken back by difference.
        long counted = snapshot[SNAP_EXPORT_TICK] == exportTick ? exported - snapshot[SNAP_EXPORTED] : exported;
        EnergyEndpoint endpoint = exportEndpoint.get();
        if (endpoint != null && counted != 0) {
            endpoint.countExport(-counted);
        }
        held = snapshot[SNAP_HELD];
        exportTick = snapshot[SNAP_EXPORT_TICK];
        exported = snapshot[SNAP_EXPORTED];
    }

    @Override
    protected void releaseSnapshot(long[] snapshot) {
        pool.push(snapshot);
    }

    @Override
    protected void onRootCommit(long[] originalState) {
        if (held != originalState[SNAP_HELD]) {
            onHeldChanged.run();
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
