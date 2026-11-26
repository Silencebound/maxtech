package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.resource.StackKeys;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.NetworkView;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
// PLATFORM-LEAK: a storage bus moves through the mounted inventory's NeoForge transfer handlers inside the caller's
// transaction (PRD 7.2: every move is a transaction against the real handler).
import net.neoforged.neoforge.transfer.ResourceHandler;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.fluid.FluidResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.item.ItemResource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.resource.Resource;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A port in storage mode as one {@link StorageProvider} of its network (B09 item 8, AE2's storage bus): the inventory
 * or tank the port faces, read through and never copied.
 *
 * <ul>
 * <li><b>Store and take</b> go straight to the mounted handler inside the caller's transaction, so the handler's own
 * journal rolls them back with everything else; this provider only logs what moved and tells the network on the root
 * commit, with exactly those amounts. Nothing moves faster or slower than network access: the port's rate does not
 * apply.</li>
 * <li><b>Takes</b> are capped by what the network was told the inventory holds, so the index never goes below zero; an
 * inventory emptied behind the network's back (a hopper) gives only what is really there, and the next look corrects
 * the index.</li>
 * <li><b>Contents</b> are what the last look ({@link #refresh}) saw, filtered by the port's filter; the port looks
 * again on its own cadence ({@link BusCadence}) and soon after every committed move, and reports only the
 * difference.</li>
 * </ul>
 * It is a {@link NetworkView}, so the network's native reads (what a subnet or a bridge to another mod sees) leave it
 * out, like a bridge. A move that comes back into the same port while it is moving (a loop that slipped past the port's
 * check) moves nothing. Server thread only.
 */
public final class PortStorage extends SnapshotJournal<Integer> implements NetworkView {
    private static final byte INSERT = 0;
    private static final byte EXTRACT = 1;

    /** What the provider needs from its port. */
    interface Host {
        /** The mounted item handler, or null when there is none or the port may not use it now. */
        @Nullable
        ResourceHandler<ItemResource> items();

        /** The mounted fluid handler, or null when there is none or the port may not use it now. */
        @Nullable
        ResourceHandler<FluidResource> fluids();

        /** Whether stores and takes may run now: the port is there, on, mounted and not refused. */
        boolean usable();

        BusAccess access();

        PortFilter filter();

        /** The port's priority; read when the network indexes the provider. */
        int priority();

        /** The Storage network whose device the port faces, for loop checks; null for a plain inventory. */
        @Nullable
        NetworkState mountedNetwork();

        /** A move committed: look at the inventory again soon. Called inside the transaction's close; cheap only. */
        void moved();
    }

    private final Host host;
    /** What the network was told the inventory holds: the last look, changed since by committed moves. */
    private Object2LongOpenHashMap<StackKey> seen = new Object2LongOpenHashMap<>();
    /** The next look, swapped with {@link #seen}. */
    private Object2LongOpenHashMap<StackKey> fresh = new Object2LongOpenHashMap<>();
    /** The moves of the open transactions, oldest first; a snapshot is the log's size. */
    private final ByteArrayList logKinds = new ByteArrayList();
    private final ObjectArrayList<StackKey> logKeys = new ObjectArrayList<>();
    private final LongArrayList logAmounts = new LongArrayList();
    /** Per key, what the open transactions took; kept with the log so the cap is O(1). */
    private final Object2LongOpenHashMap<StackKey> logExtracts = new Object2LongOpenHashMap<>();
    private @Nullable Listener listener;
    /** Whether the last look showed the contents to the network (read access, mounted, usable). */
    private boolean visible;
    /** Set while a move runs in the handler, so a move coming back through a loop does nothing. */
    private boolean busy;

    PortStorage(Host host) {
        this.host = host;
    }

    // --- StorageProvider -----------------------------------------------------------------------------------------

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || busy || !host.access().writes() || !host.filter().acceptsKey(key) || !host.usable()) {
            return 0;
        }
        int ask = (int) Math.min(amount, Integer.MAX_VALUE);
        long moved;
        busy = true;
        try {
            if (key.kind() == StackKey.Kind.ITEM) {
                ResourceHandler<ItemResource> handler = host.items();
                moved = handler == null
                        ? 0
                        : PortTransfers.insertStacking(handler, (ItemResource) StackKeys.toResource(key), ask,
                                transaction);
            } else {
                ResourceHandler<FluidResource> handler = host.fluids();
                moved = handler == null
                        ? 0
                        : PortTransfers.insertStacking(handler, (FluidResource) StackKeys.toResource(key), ask,
                                transaction);
            }
        } finally {
            busy = false;
        }
        if (moved > 0) {
            updateSnapshots(transaction);
            log(INSERT, key, moved);
        }
        return moved;
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || busy || !visible || !host.access().reads() || !host.filter().acceptsKey(key)
                || !host.usable()) {
            return 0;
        }
        // Never more than the network was told: a stale look only makes the take smaller until the next one.
        long wanted = Math.min(amount, seen.getLong(key) - logExtracts.getLong(key));
        if (wanted <= 0) {
            return 0;
        }
        int ask = (int) Math.min(wanted, Integer.MAX_VALUE);
        long moved;
        busy = true;
        try {
            if (key.kind() == StackKey.Kind.ITEM) {
                ResourceHandler<ItemResource> handler = host.items();
                moved = handler == null
                        ? 0
                        : handler.extract((ItemResource) StackKeys.toResource(key), ask, transaction);
            } else {
                ResourceHandler<FluidResource> handler = host.fluids();
                moved = handler == null
                        ? 0
                        : handler.extract((FluidResource) StackKeys.toResource(key), ask, transaction);
            }
        } finally {
            busy = false;
        }
        moved = Math.clamp(moved, 0, ask);
        if (moved > 0) {
            updateSnapshots(transaction);
            log(EXTRACT, key, moved);
        }
        return moved;
    }

    @Override
    public long amount(StackKey key) {
        return seen.getLong(key);
    }

    @Override
    public void forEachStored(Visitor visitor) {
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(seen)) {
            visitor.visit(entry.getKey(), entry.getLongValue());
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public int priority() {
        return host.priority();
    }

    /** The Storage network the port mounts through another network's device, so loops through it can be found. */
    @Override
    public @Nullable NetworkState reaches() {
        return host.mountedNetwork();
    }

    private static void checkAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
    }

    private void log(byte kind, StackKey key, long amount) {
        logKinds.add(kind);
        logKeys.add(key);
        logAmounts.add(amount);
        if (kind == EXTRACT) {
            logExtracts.addTo(key, amount);
        }
    }

    private void truncate(int size) {
        if (size == 0) {
            logExtracts.clear();
        } else {
            for (int i = logKeys.size() - 1; i >= size; i--) {
                if (logKinds.getByte(i) == EXTRACT) {
                    StackKey key = logKeys.get(i);
                    long amount = logAmounts.getLong(i);
                    if (logExtracts.addTo(key, -amount) - amount <= 0) {
                        logExtracts.removeLong(key);
                    }
                }
            }
        }
        logKinds.size(size);
        logKeys.size(size);
        logAmounts.size(size);
    }

    private void addSeen(StackKey key, long delta) {
        if (seen.addTo(key, delta) + delta <= 0) {
            seen.removeLong(key);
        }
    }

    // --- Transactions --------------------------------------------------------------------------------------------

    @Override
    protected Integer createSnapshot() {
        return logKeys.size();
    }

    /** The handler rolls its own contents back; only the log's entries after {@code mark} go. */
    @Override
    protected void revertToSnapshot(Integer mark) {
        truncate(mark);
    }

    /**
     * The moves are final: while the contents are shown, the network hears of each with the amount that moved, and
     * {@link #seen} follows; a store into a write-only inventory leaves the network's view (it is not shown).
     */
    @Override
    protected void onRootCommit(Integer originalState) {
        int size = logKeys.size();
        if (size == 0) {
            return;
        }
        if (visible) {
            Listener current = listener;
            for (int i = 0; i < size; i++) {
                StackKey key = logKeys.get(i);
                long delta = logKinds.getByte(i) == INSERT ? logAmounts.getLong(i) : -logAmounts.getLong(i);
                addSeen(key, delta);
                if (current != null) {
                    current.changed(key, delta);
                }
            }
        }
        truncate(0);
        host.moved();
    }

    // --- Looks ---------------------------------------------------------------------------------------------------

    /** Whether no open transaction has moves through this provider; a look waits for that. */
    boolean idle() {
        return logKeys.isEmpty();
    }

    /** Whether the last look showed the contents to the network. */
    boolean visible() {
        return visible;
    }

    /**
     * Looks at {@code items} and {@code fluids} (either may be null) and reports the difference to the last look: what
     * the filter accepts is shown, the rest is not. With both null, or {@code show} false, shows nothing (the inventory
     * is gone, unloaded, refused, write-only, or the port is off). Call only when {@link #idle}, outside any
     * transaction. Returns whether what is shown changed. Allocates only for keys it has not seen before.
     */
    boolean refresh(@Nullable ResourceHandler<ItemResource> items, @Nullable ResourceHandler<FluidResource> fluids,
            boolean show) {
        fresh.clear();
        if (show) {
            PortFilter filter = host.filter();
            if (items != null) {
                collect(items, filter);
            }
            if (fluids != null) {
                collect(fluids, filter);
            }
        }
        visible = show;
        boolean changed = false;
        Listener current = listener;
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(fresh)) {
            long delta = entry.getLongValue() - seen.getLong(entry.getKey());
            if (delta != 0) {
                changed = true;
                if (current != null) {
                    current.changed(entry.getKey(), delta);
                }
            }
        }
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(seen)) {
            if (!fresh.containsKey(entry.getKey())) {
                changed = true;
                if (current != null) {
                    current.changed(entry.getKey(), -entry.getLongValue());
                }
            }
        }
        Object2LongOpenHashMap<StackKey> last = seen;
        seen = fresh;
        fresh = last;
        return changed;
    }

    private <T extends Resource> void collect(ResourceHandler<T> handler, PortFilter filter) {
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            T resource = handler.getResource(index);
            if (resource.isEmpty()) {
                continue;
            }
            long amount = handler.getAmountAsLong(index);
            if (amount > 0 && filter.accepts(resource)) {
                fresh.addTo(StackKeys.of(resource), amount);
            }
        }
    }

    /**
     * Forgets the contents without telling anyone; for a port leaving storage mode, after the network dropped the
     * provider (it already took off what it was told).
     */
    void forget() {
        seen.clear();
        visible = false;
    }

    /**
     * A cheap mark of {@code handler}'s contents: every index's resource and amount mixed into one number, so a look
     * that finds the same mark can skip building the contents. A resource counts by its item or fluid and its component
     * patch, not by {@code Resource.hashCode}, which hashes the whole component map (the item's defaults too) and made
     * idle looks about twice as dear (perf_storage_idle_buses). Allocates nothing of its own.
     */
    static <T extends Resource> long fingerprint(@Nullable ResourceHandler<T> handler, long seed) {
        if (handler == null) {
            return seed * 31;
        }
        long hash = seed ^ handler.size();
        int size = handler.size();
        for (int index = 0; index < size; index++) {
            T resource = handler.getResource(index);
            if (resource.isEmpty()) {
                hash = mix(hash, 0);
                continue;
            }
            hash = mix(hash, kindHash(resource));
            hash = mix(hash, handler.getAmountAsLong(index));
        }
        return hash;
    }

    /** The resource's item or fluid (identity) and component patch; an unknown resource kind by its own hash. */
    private static int kindHash(Resource resource) {
        if (resource instanceof ItemResource item) {
            return 31 * System.identityHashCode(item.getItem()) + item.getComponentsPatch().hashCode();
        }
        if (resource instanceof FluidResource fluid) {
            return 31 * System.identityHashCode(fluid.getFluid()) + fluid.getComponentsPatch().hashCode();
        }
        return resource.hashCode();
    }

    /** One step of a 64-bit mix (the finaliser of SplitMix64 over the running value and the next one). */
    static long mix(long hash, long value) {
        long z = hash * 0x9E3779B97F4A7C15L + value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The amount of {@code key} the network was told about; tests read it. */
    public long shown(StackKey key) {
        return seen.getLong(key);
    }
}
