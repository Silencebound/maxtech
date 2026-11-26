package dev.silencebound.maxtech.storage.bulk;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.Objects;
// PLATFORM-LEAK: bulk drive changes are journaled in NeoForge's transfer transactions, like a cell's (PRD 7.2).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * A bulk drive's contents (M6f): one item kind and a count, the {@link StorageProvider} its network reads and writes.
 * There are no bytes: inserts are limited only by {@code bulkDrive.maxCount}, read on each insert, so lowering it never
 * deletes anything. The first kind that goes in locks the drive; the lock clears when a root transaction commits with
 * the count at 0. Within a transaction an emptied drive stays locked, so a rollback always finds its kind again.
 *
 * <p>
 * Changes apply at once; a snapshot is the (kind, count) pair before them, pooled, so a transaction allocates nothing
 * after the first few. When the root transaction commits, the difference to the last committed state is reported to the
 * network's listener and the drive hears of it. Fluids are refused. Server thread only.
 */
public final class BulkStore extends SnapshotJournal<BulkStore.State> implements StorageProvider {
    private final Runnable onCommit;
    private @Nullable ItemKey key;
    private long count;
    private int priority;
    private @Nullable ItemKey committedKey;
    private long committedCount;
    private @Nullable Listener listener;
    private final ObjectArrayList<State> pool = new ObjectArrayList<>();
    /** Whether the drive keeps contents it cannot read (a removed mod's item): it takes nothing in meanwhile. */
    private boolean locked;

    BulkStore(Runnable onCommit) {
        this.onCommit = onCommit;
    }

    /** Refuses every insert while {@code locked}; for a drive keeping contents it cannot read. */
    void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** Kind and count before a change; pooled. */
    static final class State {
        @Nullable
        ItemKey key;
        long count;
    }

    /** What fits on top of {@code count} under {@code cap}, at most {@code amount}; never negative. */
    public static long room(long count, long cap, long amount) {
        return Math.max(0, Math.min(amount, cap - count));
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || locked || !(key instanceof ItemKey item) || (this.key != null && !this.key.equals(item))) {
            return 0;
        }
        long moved = room(count, StorageServerConfig.BULK_DRIVE_MAX_COUNT.getAsLong(), amount);
        if (moved > 0) {
            updateSnapshots(transaction);
            this.key = item;
            count += moved;
        }
        return moved;
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        checkAmount(amount);
        if (amount == 0 || this.key == null || !this.key.equals(key)) {
            return 0;
        }
        long moved = Math.min(amount, count);
        if (moved > 0) {
            updateSnapshots(transaction);
            count -= moved;
        }
        return moved;
    }

    private static void checkAmount(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount " + amount);
        }
    }

    @Override
    public long amount(StackKey key) {
        return this.key != null && this.key.equals(key) ? count : 0;
    }

    @Override
    public void forEachStored(Visitor visitor) {
        if (key != null && count > 0) {
            visitor.visit(key, count);
        }
    }

    @Override
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    @Override
    public int priority() {
        return priority;
    }

    /** The kind the drive is locked to; null while it is empty. Committed state outside a transaction. */
    public @Nullable ItemKey key() {
        return key;
    }

    /** Items held. */
    public long count() {
        return count;
    }

    /** Sets the priority; the drive re-indexes its provider afterwards ({@link StorageProvider#priority()}). */
    void setPriority(int priority) {
        this.priority = priority;
    }

    /** Replaces the contents outside any transaction (loading, placing a drive item); reports nothing. */
    void load(@Nullable ItemKey key, long count) {
        boolean empty = key == null || count <= 0;
        this.key = empty ? null : key;
        this.count = empty ? 0 : count;
        committedKey = this.key;
        committedCount = this.count;
    }

    @Override
    protected State createSnapshot() {
        State state = pool.isEmpty() ? new State() : pool.pop();
        state.key = key;
        state.count = count;
        return state;
    }

    @Override
    protected void revertToSnapshot(State snapshot) {
        key = snapshot.key;
        count = snapshot.count;
    }

    @Override
    protected void releaseSnapshot(State snapshot) {
        snapshot.key = null;
        pool.push(snapshot);
    }

    @Override
    protected void onRootCommit(State originalState) {
        if (count == 0) {
            key = null;
        }
        Listener current = listener;
        if (current != null) {
            if (Objects.equals(committedKey, key)) {
                long delta = count - committedCount;
                if (delta != 0 && key != null) {
                    current.changed(key, delta);
                }
            } else {
                // Emptied and refilled with another kind in one transaction.
                if (committedKey != null && committedCount > 0) {
                    current.changed(committedKey, -committedCount);
                }
                if (key != null && count > 0) {
                    current.changed(key, count);
                }
            }
        }
        committedKey = key;
        committedCount = count;
        onCommit.run();
    }
}
