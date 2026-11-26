package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;

/**
 * Follows one network for the systems that count what networks do (research tasks and discovery, PRD 7.2), from the
 * network's own change stream, so nothing ever recounts. One observer per network, made by the factory given to
 * {@link NetworkState#observeNetworks}; it lives as long as its network. Every call is on the server thread and must be
 * cheap: {@link #keyChanged} runs on every committed change.
 */
public interface NetworkObserver {
    /**
     * {@code key}'s total over the network changed by {@code delta} to {@code total}, committed. Providers joining and
     * leaving (merges, splits, drives loading) report their contents this way too, so a running sum over the deltas
     * always equals the network's contents.
     */
    void keyChanged(StackKey key, long total, long delta);

    /** A device joined or left, or the network's status or power changed. */
    void devicesChanged();

    /** A device of the network moved {@code amount} of {@code key} between the network and an inventory, committed. */
    void transferred(StackKey key, long amount);

    /**
     * A cell in one of the network's drives is now {@code percent} (0 to 100, rounded down) full after a committed
     * change.
     */
    void cellFilled(int percent);
}
