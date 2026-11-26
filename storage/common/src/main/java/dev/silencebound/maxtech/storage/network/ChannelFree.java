package dev.silencebound.maxtech.storage.network;

/**
 * A device that is a network member without a channel (owner 2026-10-17, like AE2's energy cells): energy cells, the
 * creative energy source, the fuel generator, display panels and parts, the network anchor and security module,
 * research terminals, sensors, condition switches (blocks and parts), toggle buses and crafting CPUs. It joins whenever
 * it is in the network's graph (cables, unloaded-chunk mode and anchors decide that), needs no route, never counts
 * against cable, hub, root-link or ad-hoc channels or the device limits, and is never offline for channels or refused.
 * Its front line, if it has one, shows only online or offline. Read by {@link NetworkState} on topology changes only.
 */
public interface ChannelFree {
    /**
     * Whether this device holds no channel; true unless a class shares this interface across kinds that differ (the
     * service devices). Must stay the same for the device's whole life.
     */
    default boolean channelFree() {
        return true;
    }

    /** Whether {@code device} holds no channel: a {@link ChannelFree} that says so. */
    static boolean of(Object device) {
        return device instanceof ChannelFree free && free.channelFree();
    }
}
