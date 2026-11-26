package dev.silencebound.maxtech.storage.bridge;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageProvider;
// PLATFORM-LEAK: moves into the Storage network run in NeoForge's transfer transaction like every network move.
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * A bridge's Storage network as the foreign network sees it (M7b, both directions): the network's own storage (drives,
 * cells; never what it shows of other networks through views or bridges), in the simulate/modulate style AE2 and
 * Refined Storage use. Compat code mounts it as a storage of the foreign network ({@link ForeignNetwork#export}).
 *
 * <p>
 * It reports nothing and accepts nothing while the loop guard is held ({@link BridgeGuard}), that is while any bridge
 * reads or moves through a foreign network, while the bridge is in no network or not mounted (standby, or a foreign
 * side that kept it after {@link ForeignNetwork#export} removed it), and while a transaction of someone else is open on
 * the thread (the move cannot join it). Moves are capped by the bridge's rate, which the bridge's own takes and stores
 * share. Server thread only.
 */
public final class ExportedStorage {
    private final BridgeBlockEntity bridge;
    private final StorageProvider.Listener forward = this::forward;
    private StorageProvider.@Nullable Listener listener;
    private @Nullable NetworkState watching;

    ExportedStorage(BridgeBlockEntity bridge) {
        this.bridge = bridge;
    }

    /** The network to show, or null when it must show nothing now (also once the bridge took its mount down). */
    private @Nullable NetworkState network() {
        return BridgeGuard.held() || !bridge.isMounted() ? null : bridge.network();
    }

    /** How much of {@code key} the Storage network's own storage holds; 0 while it shows nothing. */
    public long amount(StackKey key) {
        NetworkState network = network();
        return network == null ? 0 : network.nativeAmount(key);
    }

    /** Visits what the Storage network's own storage holds, each key once; nothing while it shows nothing. */
    public void forEachStored(StorageProvider.Visitor visitor) {
        NetworkState network = network();
        if (network != null) {
            network.forEachNative(visitor);
        }
    }

    /**
     * Stores up to {@code amount} of {@code key} in the Storage network's own storage and returns how much went in;
     * with {@code simulate} nothing changes. The network's drives decide where it goes, as for any insert.
     */
    public long insert(StackKey key, long amount, boolean simulate) {
        return move(key, amount, simulate, true);
    }

    /** Takes up to {@code amount} of {@code key} from the Storage network's own storage; see {@link #insert}. */
    public long extract(StackKey key, long amount, boolean simulate) {
        return move(key, amount, simulate, false);
    }

    private long move(StackKey key, long amount, boolean simulate, boolean in) {
        NetworkState network = network();
        if (amount <= 0 || network == null || !canOpenRoot()) {
            return 0;
        }
        long wanted = Math.min(amount, bridge.allowance(key));
        if (wanted <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = in
                    ? network.nativeInsert(key, wanted, transaction)
                    : network.nativeExtract(key, wanted, transaction);
            if (!simulate && moved > 0) {
                transaction.commit();
                bridge.spend(key, moved);
            }
            return moved;
        }
    }

    /** No transaction is open, or one is only closing: a root transaction can be opened (see storage-facts.md). */
    private static boolean canOpenRoot() {
        Transaction.Lifecycle lifecycle = Transaction.getLifecycle();
        return lifecycle == Transaction.Lifecycle.NONE || lifecycle == Transaction.Lifecycle.ROOT_CLOSING;
    }

    /**
     * Installs the foreign side's listener, or removes it with {@code null}: it hears every committed change of the
     * Storage network's own storage, with the delta, on the server thread. Follows the bridge into another network.
     */
    public void setListener(StorageProvider.@Nullable Listener listener) {
        this.listener = listener;
        networkChanged();
    }

    /** The bridge's network changed (or the listener did): listen to the new one. */
    void networkChanged() {
        NetworkState target = listener == null ? null : bridge.network();
        if (target != watching) {
            if (watching != null) {
                watching.unlistenNative(forward);
            }
            if (target != null) {
                target.listenNative(forward);
            }
            watching = target;
        }
    }

    private void forward(StackKey key, long delta) {
        StorageProvider.Listener current = listener;
        if (current != null) {
            current.changed(key, delta);
        }
    }
}
