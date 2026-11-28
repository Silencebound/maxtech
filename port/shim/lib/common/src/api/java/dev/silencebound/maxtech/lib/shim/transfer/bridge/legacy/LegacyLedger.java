package dev.silencebound.maxtech.lib.shim.transfer.bridge.legacy;

import com.google.common.collect.MapMaker;
import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.SnapshotJournal;
import dev.silencebound.maxtech.lib.shim.transfer.transaction.TransactionContext;
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
 * <p>Mismatches warn once per handler class, then log at debug level.
 *
 * @param <R> the resource type
 */
final class LegacyLedger<R> extends SnapshotJournal<LegacyLedger.State<R>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();
    /** Every commit or rollback mismatch so far; read by tests. */
    static final AtomicInteger MISMATCHES = new AtomicInteger();

    /** A block in a level: the ledger key of a capability found there. Compared by level identity and position. */
    record Location(Object level, long pos) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Location that && that.level == level && that.pos == pos;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(level) * 31 + Long.hashCode(pos);
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
    }

    /**
     * A pending change of one inventory slot counted through one wrapper: positive inserts, negative (deferred)
     * extracts. All parts of one slot have the same resource and the same sign.
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

    /** {@return the pending insert into the inventory slot {@code key} if it is of that resource, else 0} */
    int pendingInsert(int key, R resource) {
        return Math.max(0, pendingDelta(key, resource));
    }

    /** {@return the pending change of the inventory slot {@code key} if it is of that resource, else 0} */
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
        int delta = pendingDelta(key, resource);
        int accepted;
        if (delta >= 0) {
            accepted = Math.min(amount, via.simulateInsert(viaSlot, resource, LegacyMath.add(delta, amount)) - delta);
        } else {
            // Putting back a deferred extraction always fits; anything beyond goes on top of the real slot.
            int back = Math.min(amount, -delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? Math.max(0, via.simulateInsert(viaSlot, resource, rest)) : 0);
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
            // Take back pending inserts of this resource first (they never reached the real slot), then the rest.
            int back = conflicts(key, resource) ? 0 : Math.min(amount, Math.max(0, pendingDelta(key, resource)));
            if (back > 0) {
                add(key, resource, -back, via, viaSlot);
            }
            return back + takeNow(via, viaSlot, resource, amount - back);
        }
        if (conflicts(key, resource)) {
            return 0;
        }
        int delta = pendingDelta(key, resource);
        int accepted;
        if (delta <= 0) {
            accepted = Math.min(amount, via.simulateExtract(viaSlot, resource, LegacyMath.add(-delta, amount)) + delta);
        } else {
            // Taking back a pending insert always works; anything beyond comes from the real slot.
            int back = Math.min(amount, delta);
            int rest = amount - back;
            accepted = back + (rest > 0 ? Math.max(0, via.simulateExtract(viaSlot, resource, rest)) : 0);
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

    /**
     * Adds {@code delta} to the slot's pending change. It first cancels parts of the opposite sign (from any wrapper),
     * so all parts of a slot keep one sign and their order on commit does not matter; the rest is this wrapper's part.
     */
    private void add(int key, R resource, int delta, LegacyTarget<R> via, int viaSlot) {
        int remaining = delta;
        for (int i = 0; i < pending.size() && remaining != 0; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() != key || Integer.signum(entry.delta()) == Integer.signum(remaining)) {
                continue;
            }
            int cancel = Math.min(Math.abs(remaining), Math.abs(entry.delta())) * Integer.signum(remaining);
            remaining -= cancel;
            int left = entry.delta() + cancel;
            if (left == 0) {
                pending.remove(i--);
            } else {
                pending.set(i, new Pending<>(key, resource, left, entry.via(), entry.viaSlot()));
            }
        }
        if (remaining == 0) {
            return;
        }
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key && entry.via() == via && entry.viaSlot() == viaSlot) {
                pending.set(i, new Pending<>(key, resource, entry.delta() + remaining, via, viaSlot));
                return;
            }
        }
        pending.add(new Pending<>(key, resource, remaining, via, viaSlot));
    }
}
