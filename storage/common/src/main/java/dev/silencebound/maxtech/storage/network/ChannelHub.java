package dev.silencebound.maxtech.storage.network;

/**
 * A hub (M7N item 2, PRD 7.2 Channels with hubs): a device node whose budget the devices routed through it share
 * instead of the links beyond it. The hub holds one channel on its own route while any device counts against it, never
 * counts towards the controller's device limit and never splits a network. Its network reads {@link #hubCapacity()}
 * when the hub joins and at each rebuild. Server thread.
 */
public interface ChannelHub extends NetworkDevice {
    /** Channels the devices behind this hub share; at least 1. */
    int hubCapacity();
}
