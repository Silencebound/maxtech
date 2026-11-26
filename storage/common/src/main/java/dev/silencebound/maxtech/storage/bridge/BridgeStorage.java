package dev.silencebound.maxtech.storage.bridge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkView;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
// PLATFORM-LEAK: every network transfer runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A bridge's foreign network as one {@link StorageProvider} of the bridge's Storage network (M7b, B2): read-through,
 * with take and store passing through to the foreign network inside NeoForge transactions. It is a {@link NetworkView},
 * so the Storage network's own (native) storage, which is what the bridge exports, never includes it.
 *
 * <p>
 * The foreign network moves in simulate/modulate steps, not transactions, so a move is made safe by doing its
 * irreversible half on the side where a shortfall can only leave the bridge holding more than it promised:
 * <ul>
 * <li><b>Store</b> simulates when asked and inserts for real once, when the root transaction commits. What the real
 * insert does not take stays in the bridge's own <i>held</i> store, which is part of this provider's contents (the
 * network still sees and can take it) and is offered to the foreign network again on the bridge's next pass. The
 * network is told about exactly what was promised, so nothing is lost or counted twice.</li>
 * <li><b>Take</b> extracts for real when asked and counts only what came out; a rolled-back take puts it back, and what
 * the foreign network does not take back goes into the held store. So the caller never gets more than really left the
 * foreign network.</li>
 * </ul>
 * Both are capped by the bridge's rate (its port tier's items or mB per second) and, for takes, by what the network's
 * index was told this provider holds, so the index never goes below zero.
 *
 * <p>
 * The foreign contents are read in full ({@link #refresh}) when the foreign network signals a change or on the slow
 * poll, never per tick; the difference to the last read is reported to the index. While the loop guard is held
 * ({@link BridgeGuard}), this provider reports nothing new and moves nothing. Server thread only.
 */
public final class BridgeStorage extends SnapshotJournal<Integer> implements NetworkView {
    private static final byte INSERT = 0;
    private static final byte EXTRACT = 1;
    private static final byte HELD = 2;

    /** One held key and amount, for saving. */
    record Held(StackKey key, long amount) {
        static final Codec<Held> CODEC = RecordCodecBuilder
                .create(i -> i.group(StackKey.CODEC.fieldOf("key").forGetter(Held::key),
                        Codec.LONG.fieldOf("amount").forGetter(Held::amount)).apply(i, Held::new));
    }

    /** What the storage needs from its bridge. */
    interface Host {
        /**
         * The foreign network this provider shows and moves through, or null while there is none, this bridge is on
         * standby or out of a network.
         */
        @Nullable
        ForeignNetwork mounted();

        /** The foreign network found in front, mounted or not; for putting back a rolled-back take. */
        @Nullable
        ForeignNetwork found();

        /** What the bridge's rate allows now for {@code key}'s kind: items, or mB of fluid. */
        long allowance(StackKey key);

        /** Takes {@code amount} of {@code key}'s kind off the bridge's rate. */
        void spend(StackKey key, long amount);

        /** The held store changed: save, and offer it to the foreign network on the next pass. */
        void heldChanged();

        /**
         * No open transaction has moves through the provider any more (its root committed or the moves rolled back):
         * what waited for that ({@link #idle}) may run. Called inside the transaction's close, so only cheap work.
         */
        void settled();
    }

    private final Host host;
    /** The foreign contents as last read and since changed by this provider's committed moves. */
    private Object2LongOpenHashMap<StackKey> seen = new Object2LongOpenHashMap<>();
    /** The next read, swapped with {@link #seen}. */
    private Object2LongOpenHashMap<StackKey> fresh = new Object2LongOpenHashMap<>();
    /** What the foreign network did not take or take back; part of this provider's contents. */
    private final Object2LongOpenHashMap<StackKey> held = new Object2LongOpenHashMap<>();
    /** The moves of the open transactions, oldest first; a snapshot is the log's size. */
    private final ObjectArrayList<StackKey> logKeys = new ObjectArrayList<>();
    private final LongArrayList logAmounts = new LongArrayList();
    private final ByteArrayList logKinds = new ByteArrayList();
    /**
     * Running sums over the log, kept with it so a move reads them in O(1) instead of scanning the log: the amount of
     * items and of fluid all moves used of the rate, and per key the stores and real takes. The log stays what commit
     * and rollback replay; these only follow it.
     */
    private long logItems;
    private long logFluid;
    private final Object2LongOpenHashMap<StackKey> logInserts = new Object2LongOpenHashMap<>();
    private final Object2LongOpenHashMap<StackKey> logExtracts = new Object2LongOpenHashMap<>();
    private final StorageProvider.Visitor collect = this::collect;
    private @Nullable Listener listener;
    /** Whether {@link #seen} is part of this provider's contents (a foreign network is mounted). */
    private boolean showing;

    BridgeStorage(Host host) {
        this.host = host;
    }

    // --- StorageProvider -----------------------------------------------------------------------------------------

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        ForeignNetwork foreign = host.mounted();
        if (amount == 0 || foreign == null || BridgeGuard.held() || !foreign.connected()) {
            return 0;
        }
        long wanted = Math.min(amount, room(key));
        if (wanted <= 0) {
            return 0;
        }
        // The forecast covers this transaction's earlier stores of the key too, since none is real yet.
        long earlier = logInserts.getLong(key);
        long accepted;
        BridgeGuard.enter();
        try {
            accepted = foreign.insert(key, earlier + wanted, true) - earlier;
        } finally {
            BridgeGuard.exit();
        }
        accepted = Math.clamp(accepted, 0, wanted);
        if (accepted > 0) {
            updateSnapshots(transaction);
            log(INSERT, key, accepted);
        }
        return accepted;
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || BridgeGuard.held()) {
            return 0;
        }
        long wanted = Math.min(amount, room(key));
        if (wanted <= 0) {
            return 0;
        }
        long fromHeld = Math.min(wanted, held.getLong(key));
        if (fromHeld > 0) {
            updateSnapshots(transaction);
            addHeld(key, -fromHeld);
            log(HELD, key, fromHeld);
        }
        ForeignNetwork foreign = host.mounted();
        // Never more than the index was told: a stale read only makes the take smaller until the next refresh.
        long rest = showing ? Math.min(wanted - fromHeld, seen.getLong(key) - logExtracts.getLong(key)) : 0;
        if (rest <= 0 || foreign == null || !foreign.connected()) {
            return fromHeld;
        }
        long got;
        BridgeGuard.enter();
        try {
            got = foreign.extract(key, rest, false);
        } finally {
            BridgeGuard.exit();
        }
        if (got > rest) {
            // More than asked came out: keep the surplus held. It left the foreign network, so the total stays.
            long surplus = got - rest;
            addSeen(key, -surplus);
            addHeld(key, surplus);
            host.heldChanged();
            got = rest;
        }
        if (got > 0) {
            updateSnapshots(transaction);
            log(EXTRACT, key, got);
        }
        return fromHeld + Math.max(0, got);
    }

    @Override
    public long amount(StackKey key) {
        return (showing ? seen.getLong(key) : 0) + held.getLong(key);
    }

    @Override
    public void forEachStored(Visitor visitor) {
        if (showing) {
            for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(seen)) {
                visitor.visit(entry.getKey(), entry.getLongValue() + held.getLong(entry.getKey()));
            }
        }
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(held)) {
            if (!showing || !seen.containsKey(entry.getKey())) {
                visitor.visit(entry.getKey(), entry.getLongValue());
            }
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    private static void checkAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
    }

    /** What the rate still allows for {@code key}'s kind in the open transactions. */
    private long room(StackKey key) {
        return host.allowance(key) - (key.kind() == StackKey.Kind.FLUID ? logFluid : logItems);
    }

    private void addUsed(StackKey key, long amount) {
        if (key.kind() == StackKey.Kind.FLUID) {
            logFluid += amount;
        } else {
            logItems += amount;
        }
    }

    private void log(byte kind, StackKey key, long amount) {
        logKinds.add(kind);
        logKeys.add(key);
        logAmounts.add(amount);
        addUsed(key, amount);
        if (kind == INSERT) {
            logInserts.addTo(key, amount);
        } else if (kind == EXTRACT) {
            logExtracts.addTo(key, amount);
        }
    }

    /** Drops the log's entries from {@code size} on, and their part of the running sums. */
    private void truncate(int size) {
        if (size == 0) {
            logItems = 0;
            logFluid = 0;
            logInserts.clear();
            logExtracts.clear();
        } else {
            for (int i = logKeys.size() - 1; i >= size; i--) {
                byte kind = logKinds.getByte(i);
                StackKey key = logKeys.get(i);
                long amount = logAmounts.getLong(i);
                addUsed(key, -amount);
                if (kind == INSERT) {
                    unlog(logInserts, key, amount);
                } else if (kind == EXTRACT) {
                    unlog(logExtracts, key, amount);
                }
            }
        }
        logKinds.size(size);
        logKeys.size(size);
        logAmounts.size(size);
    }

    /** Takes {@code amount} off {@code key}'s running sum, dropping the key at zero so the map stays small. */
    private static void unlog(Object2LongOpenHashMap<StackKey> sums, StackKey key, long amount) {
        if (sums.addTo(key, -amount) - amount <= 0) {
            sums.removeLong(key);
        }
    }

    private void addHeld(StackKey key, long delta) {
        if (held.addTo(key, delta) + delta <= 0) {
            held.removeLong(key);
        }
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

    /** Undoes the moves after {@code mark}, newest first: held takes go back, real takes go back to the network. */
    @Override
    protected void revertToSnapshot(Integer mark) {
        boolean heldChanged = false;
        for (int i = logKeys.size() - 1; i >= mark; i--) {
            byte kind = logKinds.getByte(i);
            StackKey key = logKeys.get(i);
            long amount = logAmounts.getLong(i);
            if (kind == HELD) {
                addHeld(key, amount);
            } else if (kind == EXTRACT) {
                long back = putBack(key, amount);
                if (back < amount) {
                    // The foreign network lost what it did not take back; the bridge holds it, so the total stays.
                    addSeen(key, -(amount - back));
                    addHeld(key, amount - back);
                    heldChanged = true;
                }
            }
        }
        truncate(mark);
        if (heldChanged) {
            host.heldChanged();
        }
        if (mark == 0) {
            host.settled();
        }
    }

    private long putBack(StackKey key, long amount) {
        ForeignNetwork foreign = host.found();
        if (foreign == null || !foreign.connected()) {
            return 0;
        }
        BridgeGuard.enter();
        try {
            return Math.clamp(foreign.insert(key, amount, false), 0, amount);
        } finally {
            BridgeGuard.exit();
        }
    }

    /**
     * The moves are final: stores go into the foreign network now (what it does not take is held), the rate is charged
     * and the index hears of every move with the amount promised in the transaction.
     */
    @Override
    protected void onRootCommit(Integer originalState) {
        boolean heldChanged = false;
        int size = logKeys.size();
        for (int i = 0; i < size; i++) {
            byte kind = logKinds.getByte(i);
            StackKey key = logKeys.get(i);
            long amount = logAmounts.getLong(i);
            long delta;
            if (kind == INSERT) {
                long real = store(key, amount);
                if (real > 0) {
                    addSeen(key, real);
                }
                if (real < amount) {
                    addHeld(key, amount - real);
                    heldChanged = true;
                }
                delta = amount;
            } else {
                if (kind == EXTRACT) {
                    addSeen(key, -amount);
                } else {
                    heldChanged = true;
                }
                delta = -amount;
            }
            host.spend(key, amount);
            Listener current = listener;
            if (current != null) {
                current.changed(key, delta);
            }
        }
        truncate(0);
        if (heldChanged) {
            host.heldChanged();
        }
        host.settled();
    }

    /** The real insert of a committed store; 0 when the foreign network went away meanwhile. */
    private long store(StackKey key, long amount) {
        ForeignNetwork foreign = host.found();
        if (foreign == null || !foreign.connected()) {
            return 0;
        }
        BridgeGuard.enter();
        try {
            return Math.clamp(foreign.insert(key, amount, false), 0, amount);
        } finally {
            BridgeGuard.exit();
        }
    }

    // --- Refresh and held store ----------------------------------------------------------------------------------

    /** Whether no transaction has moves through this provider open; refresh and draining wait for that. */
    boolean idle() {
        return logKeys.isEmpty();
    }

    /**
     * Reads {@code foreign}'s contents again and reports the difference to the last read; with {@code null}, shows no
     * foreign contents any more. Call only when {@link #idle}, with no transaction open. Changing whether foreign
     * contents are shown at all is up to the caller ({@link #show}).
     */
    void refresh(@Nullable ForeignNetwork foreign) {
        fresh.clear();
        if (foreign != null && foreign.connected()) {
            BridgeGuard.enter();
            try {
                foreign.forEachStored(collect);
            } finally {
                BridgeGuard.exit();
            }
        }
        Listener current = showing ? listener : null;
        if (current != null) {
            for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(fresh)) {
                long delta = entry.getLongValue() - seen.getLong(entry.getKey());
                if (delta != 0) {
                    current.changed(entry.getKey(), delta);
                }
            }
            for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(seen)) {
                if (!fresh.containsKey(entry.getKey())) {
                    current.changed(entry.getKey(), -entry.getLongValue());
                }
            }
        }
        Object2LongOpenHashMap<StackKey> last = seen;
        seen = fresh;
        fresh = last;
    }

    private void collect(StackKey key, long amount) {
        if (amount > 0) {
            fresh.addTo(key, amount);
        }
    }

    /**
     * Sets whether the foreign contents are part of this provider. The caller makes the network index the provider
     * again afterwards, since the index cannot be told a whole new view by deltas.
     */
    void show(boolean showing) {
        this.showing = showing;
    }

    boolean showing() {
        return showing;
    }

    /**
     * Offers the held store to {@code foreign}, outside any transaction: what it takes moves from held to the foreign
     * contents, so the total stays and the index hears nothing. Returns whether anything is still held.
     */
    boolean drain(ForeignNetwork foreign) {
        if (held.isEmpty() || !foreign.connected()) {
            return !held.isEmpty();
        }
        // A copy, since the loop changes the held store; this runs rarely, only after a shortfall.
        List<Held> copy = held();
        boolean changed = false;
        for (Held entry : copy) {
            long moved;
            BridgeGuard.enter();
            try {
                moved = Math.clamp(foreign.insert(entry.key(), entry.amount(), false), 0, entry.amount());
            } finally {
                BridgeGuard.exit();
            }
            if (moved > 0) {
                addHeld(entry.key(), -moved);
                if (showing) {
                    addSeen(entry.key(), moved);
                } else {
                    // Not shown: the moved part leaves this provider's contents.
                    Listener current = listener;
                    if (current != null) {
                        current.changed(entry.key(), -moved);
                    }
                }
                changed = true;
            }
        }
        if (changed) {
            host.heldChanged();
        }
        return !held.isEmpty();
    }

    /** The held store, for saving and drops. */
    List<Held> held() {
        List<Held> list = new ArrayList<>(held.size());
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(held)) {
            list.add(new Held(entry.getKey(), entry.getLongValue()));
        }
        return list;
    }

    /** Empties the held store, once it went into the bridge's drops or was dropped itself. */
    void clearHeld() {
        held.clear();
    }

    /** Replaces the held store with saved entries; the caller has the network index the provider again. */
    void loadHeld(List<Held> entries) {
        held.clear();
        for (Held entry : entries) {
            if (entry.amount() > 0) {
                held.addTo(entry.key(), entry.amount());
            }
        }
    }

    /** The foreign amount of {@code key} as last read and since changed by committed moves, shown or not; for tests. */
    public long lastRead(StackKey key) {
        return seen.getLong(key);
    }

    /** How many units the bridge holds; for info lines and tests. */
    public long heldTotal() {
        long sum = 0;
        for (Object2LongMap.Entry<StackKey> entry : Object2LongMaps.fastIterable(held)) {
            sum += entry.getLongValue();
        }
        return sum;
    }
}
