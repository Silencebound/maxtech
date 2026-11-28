package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.google.common.collect.MapMaker;
import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The transaction state of one real 1.21.1 inventory, shared by every wrapper over it: two cables, two sides or two
 * capability caches on one block all get the same ledger ({@link Registry}), so they never count the same free space
 * or the same items twice. A ledger is found by the real container behind NeoForge's vanilla wrappers, else by the
 * block's {@link Location} (level and position, all sides together), else by the handler object.
 *
 * <ul>
 * <li><b>Insertion is simulated and deferred.</b> Moves into one inventory slot are combined (over every wrapper and
 * side) and simulated as one move against the real handler, and applied when the root transaction commits, each part
 * through the wrapper and side that counted it. Any number of slots may have pending moves; one slot holds one
 * resource at a time, so a second resource is refused there until the commit.
 * <li><b>Extraction</b> is real and immediate where the handler can be put back exactly ({@link
 * LegacyTarget#extractsImmediately()}, item handlers with {@code setStackInSlot}): the amount is held by the ledger,
 * and a rollback restores it into its slot, up to the slot limit. Nothing is counted that was not physically taken, so
 * it cannot duplicate. Elsewhere (fluids, energy, other item handlers) extraction is simulated and deferred like
 * insertion. A real extraction and its restore run the handler's change callbacks (setChanged) also for transactions
 * that end up aborted, such as dry runs.
 * <li><b>On commit each part is re-checked by applying it for real.</b> If the handler changed in between and no
 * longer takes a counted insert, the rest goes into any other slot and then to {@link LegacyTarget#overflow} (items
 * drop at the block; fluid and energy cannot and are lost). If a deferred extraction gives less than counted, the
 * difference was already handed on and is duplicated. Both can only happen when the handler changes between the
 * simulation and the commit of the same transaction, or when its simulation does not match its real move.
 * </ul>
 *
 * <p>Inserts and deferred extracts through different wrappers are combined only with parts of the same sign; one
 * side's deferred extract is never credited to another side's insert (or the reverse), since sides can reach
 * different tanks.
 *
 * <p>Lib's own moves skip the ledger where they can: its ports ({@code KindMoves}, through {@code ForeignKinds}) and
 * {@code EnergyHandlerUtil.move} at a root move through fluid, energy and non-modifiable item wrappers foreign side
 * last ({@link LegacyDirect}), counting only what the handler really moved. The ledger remains for every other caller
 * of the capability.
 *
 * <p>Mismatches warn once per handler class, then log at debug level.
 *
 * @param <R> the resource type
 */
final class LegacyLedger<R> extends SnapshotJournal<LegacyLedger.State<R>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();
    /** Every commit or rollback mismatch so far; read by tests. */
    static final AtomicInteger MISMATCHES = new AtomicInteger();

    /**
     * A block in a level: the ledger key of a capability found there. Compared by level identity and position; the
     * level is held weakly, so a ledger key never keeps an unloaded level alive ({@link Registry#forget} drops its
     * keys when the level unloads).
     */
    static final class Location {
        private final WeakReference<Object> level;
        private final int levelHash;
        private final long pos;

        Location(Object level, long pos) {
            this.level = new WeakReference<>(level);
            this.levelHash = System.identityHashCode(level);
            this.pos = pos;
        }

        /** {@return whether this key is in {@code level}; false once the level is gone} */
        boolean in(Object level) {
            return this.level.get() == level;
        }

        boolean levelGone() {
            return level.get() == null;
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof Location that) || that.pos != pos || that.levelHash != levelHash) {
                return false;
            }
            Object mine = level.get();
            return mine != null && mine == that.level.get();
        }

        @Override
        public int hashCode() {
            return levelHash * 31 + Long.hashCode(pos);
        }

        @Override
        public String toString() {
            return "Location{" + level.get() + " @ " + pos + "}";
        }
    }

    /**
     * One ledger per real inventory: objects by identity with weak keys, {@link Location}s by value. Values are weak:
     * wrappers keep their ledger alive, and an unused ledger goes away with its entry.
     */
    static final class Registry<R> {
        private final Map<Object, LegacyLedger<R>> byObject = new MapMaker().weakKeys().weakValues().makeMap();
        private final Map<Object, LegacyLedger<R>> byLocation = new MapMaker().weakValues().makeMap();

        LegacyLedger<R> of(Object inventory) {
            var map = inventory instanceof Location ? byLocation : byObject;
            return map.computeIfAbsent(inventory, unused -> new LegacyLedger<>());
        }

        /** Drops the keys of {@code level} (and of levels already collected); wrappers that hold a ledger keep it. */
        void forget(Object level) {
            byLocation.keySet().removeIf(key -> key instanceof Location at && (at.in(level) || at.levelGone()));
        }

        /** {@return the number of block keys; for tests} */
        int locations() {
            return byLocation.size();
        }
    }

    /**
     * A pending change of one inventory slot counted through one wrapper and slot ({@code via}, {@code viaSlot}):
     * positive inserts, negative (deferred) extracts. All parts of one slot have the same resource. Parts of different
     * wrappers are kept apart and may have different signs: an insert through one side is never credited against an
     * extract through another, since the sides can reach different tanks or have different rules.
     */
    record Pending<R>(int key, R resource, int delta, LegacyTarget<R> via, int viaSlot) {
    }

    /** An amount extracted for real in this transaction, to restore on rollback. */
    record Held<R>(LegacyTarget<R> via, int viaSlot, R resource, int amount) {
    }

    record State<R>(List<Pending<R>> pending, int held) {
    }

    private List<Pending<R>> pending = new ArrayList<>();
    private final List<Held<R>> held = new ArrayList<>();

    /** {@return the pending inserts into the inventory slot {@code key} (all sides) if of that resource, else 0} */
    int pendingInsert(int key, R resource) {
        return sumOf(key, resource, 1);
    }

    /** {@return whether any slot has a pending change in the current transaction} */
    boolean hasPending() {
        return !pending.isEmpty();
    }

    /**
     * {@return the net pending change of the inventory slot {@code key} over all sides if it is of that resource, else
     * 0}; what the slot will show after the commit, for reading only
     */
    int pendingDelta(int key, R resource) {
        long total = 0;
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key) {
                if (!entry.resource().equals(resource)) {
                    return 0;
                }
                total += entry.delta();
            }
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, total));
    }

    /** {@return the resource pending for the inventory slot {@code key}, or null} */
    @Nullable R pendingResource(int key) {
        for (int i = 0, n = pending.size(); i < n; i++) {
            if (pending.get(i).key() == key) {
                return pending.get(i).resource();
            }
        }
        return null;
    }

    int insert(LegacyTarget<R> via, int viaSlot, int key, R resource, int amount, TransactionContext transaction) {
        if (amount <= 0 || conflicts(key, resource)) {
            return 0;
        }
        // Putting back this side's own deferred extraction always fits; the rest is simulated on top of every side's
        // pending inserts. Other sides' deferred extractions are not credited (they may drain another tank).
        int own = partOf(key, via, viaSlot);
        int back = own < 0 ? Math.min(amount, -own) : 0;
        int rest = amount - back;
        int accepted = back;
        if (rest > 0) {
            int inserts = sumOf(key, resource, 1);
            accepted += Math.max(0, Math.min(rest,
                    via.simulateInsert(viaSlot, resource, LegacyMath.add(inserts, rest)) - inserts));
        }
        if (accepted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        add(key, resource, accepted, via, viaSlot);
        return accepted;
    }

    int extract(LegacyTarget<R> via, int viaSlot, int key, R resource, int amount, TransactionContext transaction) {
        if (amount <= 0) {
            return 0;
        }
        if (via.extractsImmediately()) {
            updateSnapshots(transaction);
            // Take back this side's pending inserts of this resource first (they never reached the real slot), then
            // the rest.
            int back = conflicts(key, resource) ? 0 : Math.min(amount, Math.max(0, partOf(key, via, viaSlot)));
            if (back > 0) {
                add(key, resource, -back, via, viaSlot);
            }
            return back + takeNow(via, viaSlot, resource, amount - back);
        }
        if (conflicts(key, resource)) {
            return 0;
        }
        // Taking back this side's own pending insert always works; the rest is simulated on top of every side's
        // deferred extractions. Other sides' pending inserts are not credited (they may fill another tank).
        int own = partOf(key, via, viaSlot);
        int back = own > 0 ? Math.min(amount, own) : 0;
        int rest = amount - back;
        int accepted = back;
        if (rest > 0) {
            int extracts = sumOf(key, resource, -1);
            accepted += Math.max(0, Math.min(rest,
                    via.simulateExtract(viaSlot, resource, LegacyMath.add(extracts, rest)) - extracts));
        }
        if (accepted <= 0) {
            return 0;
        }
        updateSnapshots(transaction);
        add(key, resource, -accepted, via, viaSlot);
        return accepted;
    }

    private int takeNow(LegacyTarget<R> via, int viaSlot, R resource, int amount) {
        int taken = amount > 0 ? via.extract(viaSlot, resource, amount) : 0;
        if (taken > 0) {
            held.add(new Held<>(via, viaSlot, resource, taken));
        }
        return taken;
    }

    @Override
    protected State<R> createSnapshot() {
        return new State<>(new ArrayList<>(pending), held.size());
    }

    @Override
    protected void revertToSnapshot(State<R> snapshot) {
        for (int i = held.size() - 1; i >= snapshot.held(); i--) {
            Held<R> entry = held.remove(i);
            int left = entry.amount() - entry.via().restore(entry.viaSlot(), entry.resource(), entry.amount());
            if (left > 0) {
                put(entry.via(), entry.viaSlot(), entry.resource(), left, "rollback");
            }
        }
        pending = snapshot.pending();
    }

    @Override
    protected void onRootCommit(State<R> originalState) {
        List<Pending<R>> apply = pending;
        pending = new ArrayList<>();
        held.clear();
        for (Pending<R> entry : apply) {
            if (entry.delta() > 0) {
                put(entry.via(), entry.viaSlot(), entry.resource(), entry.delta(), "commit");
            } else if (entry.delta() < 0) {
                int wanted = -entry.delta();
                int missing = wanted - entry.via().extract(entry.viaSlot(), entry.resource(), wanted);
                if (missing > 0) {
                    mismatch(entry.via(), "gave " + missing + " x " + entry.resource()
                            + " less on commit than simulated; that amount was duplicated");
                }
            }
        }
    }

    /** Puts a counted amount into the real handler: its slot, then any slot, then overflow. */
    private static <R> void put(LegacyTarget<R> via, int slot, R resource, int amount, String when) {
        int left = amount - via.insert(slot, resource, amount);
        if (left > 0) {
            left -= via.insertAnywhere(resource, left);
        }
        if (left > 0) {
            mismatch(via, "did not take " + left + " x " + resource + " on " + when + "; sent to overflow");
            via.overflow(resource, left);
        }
    }

    private static void mismatch(LegacyTarget<?> via, String what) {
        MISMATCHES.incrementAndGet();
        Object handler = via.handler();
        if (WARNED.add(handler.getClass())) {
            LOGGER.warn("{} ({}) {}. Its simulation does not match its real moves; further cases are logged at debug "
                    + "level.", handler, handler.getClass().getName(), what);
        } else {
            LOGGER.debug("{} {}", handler, what);
        }
    }

    /** {@return whether the slot has pending parts of another resource} */
    private boolean conflicts(int key, R resource) {
        R current = pendingResource(key);
        return current != null && !current.equals(resource);
    }

    /** {@return this side's pending change of the slot {@code key}} */
    private int partOf(int key, LegacyTarget<R> via, int viaSlot) {
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key && entry.via() == via && entry.viaSlot() == viaSlot) {
                return entry.delta();
            }
        }
        return 0;
    }

    /** {@return the size of the slot's pending parts of sign {@code sign} (all sides) if of that resource, else 0} */
    private int sumOf(int key, R resource, int sign) {
        long total = 0;
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key && Integer.signum(entry.delta()) == sign) {
                if (!entry.resource().equals(resource)) {
                    return 0;
                }
                total += Math.abs((long) entry.delta());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Adds {@code delta} to this side's part of the slot's pending change; a part that reaches 0 is removed. Other
     * sides' parts are left alone, so each part is applied through the side that counted it.
     */
    private void add(int key, R resource, int delta, LegacyTarget<R> via, int viaSlot) {
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key && entry.via() == via && entry.viaSlot() == viaSlot) {
                int sum = entry.delta() + delta;
                if (sum == 0) {
                    pending.remove(i);
                } else {
                    pending.set(i, new Pending<>(key, resource, sum, via, viaSlot));
                }
                return;
            }
        }
        pending.add(new Pending<>(key, resource, delta, via, viaSlot));
    }
}
