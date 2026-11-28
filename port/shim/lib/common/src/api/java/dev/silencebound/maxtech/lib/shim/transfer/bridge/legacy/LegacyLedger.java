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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The transaction state of one real 1.21.1 inventory, shared by every wrapper over it: two cables, two sides or two
 * capability caches on one block all get the same ledger ({@link Registry}), so they never count the same free space
 * or the same items twice.
 *
 * <ul>
 * <li><b>Insertion is simulated and deferred.</b> Moves into one inventory slot are combined and simulated as one
 * move against the real handler, and applied when the root transaction commits. Any number of slots may have pending
 * moves; one slot holds one resource at a time, so a second resource is refused there until the commit.
 * <li><b>Extraction</b> is real and immediate where the handler can be put back exactly ({@link
 * LegacyTarget#extractsImmediately()}, item handlers with {@code setStackInSlot}): the amount is held by the ledger,
 * and a rollback restores it into its slot. Nothing is counted that was not physically taken, so it cannot duplicate.
 * Elsewhere (fluids, energy, other item handlers) extraction is simulated and deferred like insertion.
 * <li><b>On commit each slot is re-checked by applying it for real.</b> If the handler changed in between and no
 * longer takes a counted insert, the rest goes into any other slot and then to {@link LegacyTarget#overflow} (items
 * drop at the block), so nothing is lost. If a deferred extraction gives less than counted, the difference was already
 * handed on and is logged; this is the one case that can duplicate, and only with a handler that changed between the
 * simulation and the commit of the same transaction.
 * </ul>
 *
 * <p>Mismatches warn once per handler class, then log at debug level.
 *
 * @param <R> the resource type
 */
final class LegacyLedger<R> extends SnapshotJournal<LegacyLedger.State<R>> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * One ledger per real inventory object, weak on both sides: wrappers keep their ledger alive, and the ledger keeps
     * its handlers alive only while it is.
     */
    static final class Registry<R> {
        private final Map<Object, LegacyLedger<R>> ledgers = new MapMaker().weakKeys().weakValues().makeMap();

        LegacyLedger<R> of(Object inventory) {
            return ledgers.computeIfAbsent(inventory, unused -> new LegacyLedger<>());
        }
    }

    /** A pending change of one inventory slot: positive inserts, negative (deferred) extracts. */
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
        Pending<R> entry = find(key);
        return entry != null && entry.resource().equals(resource) ? Math.max(0, entry.delta()) : 0;
    }

    /** {@return the pending change of the inventory slot {@code key} if it is of that resource, else 0} */
    int pendingDelta(int key, R resource) {
        Pending<R> entry = find(key);
        return entry != null && entry.resource().equals(resource) ? entry.delta() : 0;
    }

    /** {@return the resource pending for the inventory slot {@code key}, or null} */
    @Nullable R pendingResource(int key) {
        Pending<R> entry = find(key);
        return entry == null ? null : entry.resource();
    }

    int insert(LegacyTarget<R> via, int viaSlot, int key, R resource, int amount, TransactionContext transaction) {
        if (amount <= 0) {
            return 0;
        }
        Pending<R> entry = find(key);
        if (entry != null && !entry.resource().equals(resource)) {
            return 0;
        }
        int delta = entry == null ? 0 : entry.delta();
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
        set(entry, key, resource, delta + accepted, via, viaSlot);
        return accepted;
    }

    int extract(LegacyTarget<R> via, int viaSlot, int key, R resource, int amount, TransactionContext transaction) {
        if (amount <= 0) {
            return 0;
        }
        Pending<R> entry = find(key);
        if (entry != null && !entry.resource().equals(resource)) {
            // The slot has a pending insert of something else; the real slot may still hold this resource.
            if (!via.extractsImmediately()) {
                return 0;
            }
            updateSnapshots(transaction);
            return takeNow(via, viaSlot, resource, amount);
        }
        int delta = entry == null ? 0 : entry.delta();
        if (via.extractsImmediately()) {
            updateSnapshots(transaction);
            int back = Math.min(amount, Math.max(0, delta));
            if (back > 0) {
                set(entry, key, resource, delta - back, via, viaSlot);
            }
            return back + takeNow(via, viaSlot, resource, amount - back);
        }
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
        set(entry, key, resource, delta - accepted, via, viaSlot);
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
        Object handler = via.handler();
        if (WARNED.add(handler.getClass())) {
            LOGGER.warn("{} ({}) {}. Its simulation does not match its real moves; further cases are logged at debug "
                    + "level.", handler, handler.getClass().getName(), what);
        } else {
            LOGGER.debug("{} {}", handler, what);
        }
    }

    private @Nullable Pending<R> find(int key) {
        for (int i = 0, n = pending.size(); i < n; i++) {
            Pending<R> entry = pending.get(i);
            if (entry.key() == key) {
                return entry;
            }
        }
        return null;
    }

    private void set(@Nullable Pending<R> old, int key, R resource, int delta, LegacyTarget<R> via, int viaSlot) {
        int index = old == null ? -1 : pending.indexOf(old);
        if (delta == 0) {
            if (index >= 0) {
                pending.remove(index);
            }
            return;
        }
        Pending<R> entry = old == null ? new Pending<>(key, resource, delta, via, viaSlot)
                : new Pending<>(key, resource, delta, old.via(), old.viaSlot());
        if (index >= 0) {
            pending.set(index, entry);
        } else {
            pending.add(entry);
        }
    }
}
