package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
// PLATFORM-LEAK: every network transfer runs in NeoForge's transfer transaction (PRD 7.2, cache is a view).
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
// PLATFORM-LEAK: see above.
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * The {@link StorageTransfer} of one device: owned by its {@link DeviceLink}, bound to the device's network each time
 * it is handed out. Each device has its own, so a device operating inside another device's run (a port exporting into
 * an interface on the same network) never rebinds the outer device's transfer. Server thread only.
 */
final class LinkTransfer implements StorageTransfer {
    private final DeviceLink link;
    private @Nullable NetworkState network;
    /** The last operation since binding was refused for lack of energy. */
    boolean energyShort;
    /** The last operation since binding was held back by a surge regulator; the task sleeps until energy returns. */
    boolean throttled;
    /**
     * Whether the operations are a player's (a task with {@link StorageTask#playerPriority()}): they are never held
     * back by a surge regulator.
     */
    boolean player;
    /**
     * The kind of the multi-key operation open now ({@link #beginOperation}), or null: its moves pay nothing of their
     * own, {@link #finishOperation} pays for all of them at once.
     */
    StackKey.@Nullable Kind open;
    /** Multi-key operations paid in a transaction that is still open; counted once its root commits. */
    private final OperationCounter counter = new OperationCounter();

    LinkTransfer(DeviceLink link) {
        this.link = link;
    }

    /** Binds to {@code state} for automation or a player's task and forgets the last refusal; returns this. */
    LinkTransfer bind(NetworkState state, boolean forPlayer) {
        network = state;
        energyShort = false;
        throttled = false;
        player = forPlayer;
        // A rebind ends an operation left open: its moves then pay one by one, and finishing it pays nothing.
        open = null;
        return this;
    }

    /**
     * Counts one operation for {@code state} when the root of {@code transaction} commits, and not at all when it rolls
     * back: a multi-key operation is paid before its caller knows whether everything commits.
     */
    void countOnCommit(NetworkState state, TransactionContext transaction) {
        counter.add(state, transaction);
    }

    DeviceLink link() {
        return link;
    }

    NetworkDevice device() {
        return link.device;
    }

    private NetworkState bound() {
        NetworkState current = network;
        if (current == null) {
            throw new IllegalStateException("Transfer not bound to a network");
        }
        return current;
    }

    @Override
    public NetworkState network() {
        return bound();
    }

    @Override
    public long batchLimit(StackKey.Kind kind) {
        return bound().batchLimit(kind);
    }

    @Override
    public boolean canOperate() {
        return retryTicks() == 0;
    }

    @Override
    public long insert(StackKey key, long amount, TransactionContext transaction) {
        return bound().operate(true, key, amount, transaction, this);
    }

    @Override
    public long extract(StackKey key, long amount, TransactionContext transaction) {
        return bound().operate(false, key, amount, transaction, this);
    }

    @Override
    public long beginOperation(StackKey.Kind kind) {
        return bound().beginOperation(kind, this);
    }

    @Override
    public boolean finishOperation(StackKey.Kind kind, long moved, TransactionContext transaction) {
        return bound().finishOperation(kind, moved, transaction, this);
    }

    @Override
    public int retryTicks() {
        return bound().retryTicks(this);
    }

    /**
     * Pending operation counts as transaction-aware state: a rolled-back level puts the count back, the root commit
     * hands what is left to the network. The snapshots are small {@code Integer}s from the boxing cache (a root holds a
     * handful of operations at most), so nothing is allocated per operation.
     */
    private static final class OperationCounter extends SnapshotJournal<Integer> {
        private @Nullable NetworkState network;
        private int pending;

        void add(NetworkState state, TransactionContext transaction) {
            updateSnapshots(transaction);
            network = state;
            pending++;
        }

        @Override
        protected Integer createSnapshot() {
            return pending;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            pending = snapshot;
        }

        @Override
        protected void onRootCommit(Integer originalState) {
            NetworkState state = network;
            int count = pending;
            pending = 0;
            network = null;
            if (state != null && count > 0) {
                state.countOperations(count);
            }
        }
    }
}
