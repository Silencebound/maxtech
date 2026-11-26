package dev.silencebound.maxtech.storage.bridge;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.StorageProvider;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Another mod's storage network as a bridge sees it (M7b): what it holds, insert and extract in the simulate/modulate
 * style AE2 and Refined Storage use, a change signal, and a hook to mount the bridge's own network into it. Compat code
 * ({@code compat.ae2}, {@code compat.refinedstorage}) implements it and hands it out through {@link ForeignNetworks};
 * the bridge wraps it in a transaction-aware {@link StorageProvider} ({@link BridgeStorage}), so the rest of Storage
 * never sees the difference.
 *
 * <p>
 * The bridge calls every method on the server thread, with the loop guard held ({@link BridgeGuard}): while it is, the
 * {@link ExportedStorage} mounted into this network reports nothing and accepts nothing, so an implementation may list,
 * insert into and extract from all its mounted storages as usual without seeing a Storage network twice.
 */
public interface ForeignNetwork {
    /**
     * What identifies the foreign network: two bridges whose foreign networks have equal identities ({@code equals})
     * reach the same network, and only one of them per Storage network mounts it. Stable while {@link #connected()} is
     * true; AE2's grid or RS's network object, for example.
     */
    Object identity();

    /** The foreign network's kind for the bridge's info lines, such as "Applied Energistics 2 network". */
    Component name();

    /**
     * Whether the foreign side is still there; once false the bridge stops using this object and looks for a network in
     * front of it again. Cheap: the bridge calls it before every operation.
     */
    boolean connected();

    /**
     * Visits what the foreign network holds, each key once and above 0. What the mounted {@link ExportedStorage} shows
     * is left out by itself, since it reports nothing while the guard is held. Called when the network changed or on
     * the slow poll, never per tick.
     */
    void forEachStored(StorageProvider.Visitor visitor);

    /**
     * Inserts up to {@code amount} of {@code key} (above 0) and returns how much went in, 0 to {@code amount}. With
     * {@code simulate} true nothing changes and the answer is a forecast, which the real insert may fall short of.
     */
    long insert(StackKey key, long amount, boolean simulate);

    /** Extracts up to {@code amount} of {@code key} (above 0) and returns how much came out; see {@link #insert}. */
    long extract(StackKey key, long amount, boolean simulate);

    /**
     * Installs the bridge's change signal, or removes it with {@code null}. Call it on the server thread whenever what
     * {@link #forEachStored} reports may have changed; the bridge reads the contents again on its next pass, at most
     * once per {@code bridges.refreshTicks}. Returns false when the foreign network cannot report changes; the bridge
     * then reads it every {@code bridges.pollIntervalTicks} instead.
     */
    boolean setChangeListener(@Nullable Runnable listener);

    /**
     * Mounts {@code storage}, the bridge's Storage network, into the foreign network so its terminals see and use it,
     * or removes the mount with {@code null}. A bridge mounts at most one at a time and removes it when it unloads, is
     * broken, or another bridge of the same Storage network already links the two networks. A finder hands each bridge
     * its own object ({@link ForeignNetworks.Finder}), so {@code null} removes that bridge's mount only.
     */
    void export(@Nullable ExportedStorage storage);
}
