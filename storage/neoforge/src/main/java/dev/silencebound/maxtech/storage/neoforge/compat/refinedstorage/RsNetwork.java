package dev.silencebound.maxtech.storage.neoforge.compat.refinedstorage;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.storage.root.RootStorageListener;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.bridge.ExportedStorage;
import dev.silencebound.maxtech.storage.bridge.ForeignNetwork;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A Refined Storage network as one bridge sees it, reached through an RS node container in front of the bridge (a cable
 * or any other RS block). Reads, inserts and extracts go through the network's root storage (its
 * {@link StorageNetworkComponent}), which spans every disk, external storage and mount of the network, with RS's
 * simulate/execute actions; the bridge's {@code BridgeStorage} maps them onto Storage's transactions. The bridge joins
 * no RS network itself and uses no RS energy, but it needs the network to be powered ({@link #powered}): without power
 * RS counts as offline, as for RS's own devices. Power is checked on every call, so a take or store is always right;
 * the bridge's cached view catches up on the next refresh (RS signals no power changes).
 *
 * <p>
 * Changes: a listener on the root storage's list, which RS calls for every change of the network's contents. Changes an
 * export pushes into the list are left out ({@link RsExport#pushing()}), since the bridge does not show those.
 */
final class RsNetwork implements ForeignNetwork {
    private final InWorldNetworkNodeContainer container;
    private final Network network;
    private final StorageNetworkComponent root;
    private @Nullable RootStorageListener listener;
    private @Nullable RsExport export;

    RsNetwork(InWorldNetworkNodeContainer container, Network network) {
        this.container = container;
        this.network = network;
        this.root = network.getComponent(StorageNetworkComponent.class);
    }

    @Override
    public Object identity() {
        return network;
    }

    @Override
    public Component name() {
        return Component.translatable(RefinedStorageCompat.NAME);
    }

    /** The node in front is still there and still in the same network object (merges and splits replace it). */
    @Override
    @SuppressWarnings("ReferenceEquality") // RS replaces the network object on merges and splits; identity is the point
    public boolean connected() {
        return !container.isRemoved() && container.getNode().getNetwork() == network;
    }

    /**
     * Whether {@code network} is powered the way RS's own devices check it: RS energy is off in its config, or the
     * network has stored energy (a device using none needs more than 0).
     */
    static boolean powered(Network network) {
        return !RefinedStorageApi.INSTANCE.isEnergyRequired()
                || network.getComponent(EnergyNetworkComponent.class).getStored() > 0;
    }

    /**
     * What the root storage lists, less what exports mounted in this network put there (RS lists those from its cache
     * even while the guard blanks them).
     */
    @Override
    public void forEachStored(StorageProvider.Visitor visitor) {
        if (!powered(network)) {
            return;
        }
        for (ResourceAmount entry : root.getAll()) {
            StackKey key = RsKeys.key(entry.resource());
            if (key != null) {
                long amount = entry.amount() - RsExport.mountedAmount(root, entry.resource());
                if (amount > 0) {
                    visitor.visit(key, amount);
                }
            }
        }
    }

    @Override
    public long insert(StackKey key, long amount, boolean simulate) {
        if (!powered(network)) {
            return 0;
        }
        return RsKeys.clamp(root.insert(RsKeys.resource(key), amount, action(simulate), RsKeys.ACTOR), amount);
    }

    @Override
    public long extract(StackKey key, long amount, boolean simulate) {
        if (!powered(network)) {
            return 0;
        }
        return RsKeys.clamp(root.extract(RsKeys.resource(key), amount, action(simulate), RsKeys.ACTOR), amount);
    }

    private static Action action(boolean simulate) {
        return simulate ? Action.SIMULATE : Action.EXECUTE;
    }

    @Override
    public boolean setChangeListener(@Nullable Runnable signal) {
        RootStorageListener old = listener;
        if (old != null) {
            root.removeListener(old);
            listener = null;
        }
        if (signal != null) {
            RootStorageListener now = change -> {
                if (!RsExport.pushing()) {
                    signal.run();
                }
            };
            listener = now;
            root.addListener(now);
        }
        return true;
    }

    @Override
    public void export(@Nullable ExportedStorage storage) {
        RsExport old = export;
        if (old != null) {
            export = null;
            old.unmount();
        }
        if (storage != null) {
            RsExport now = new RsExport(storage, root, network);
            export = now;
            now.mount();
        }
    }
}
