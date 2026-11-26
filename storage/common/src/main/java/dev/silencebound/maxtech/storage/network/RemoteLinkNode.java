package dev.silencebound.maxtech.storage.network;

import it.unimi.dsi.fastutil.longs.LongList;

/**
 * A storage device that also links to nodes it does not touch (M6d antennas): {@link StorageConnectivity} adds
 * {@link #remoteLinks} to the six sides it reads, and the link is one segment of the route tree whose channels are
 * {@link #remoteLinkWidth}. Implemented by a {@link StorageDeviceBlockEntity}. A link whose far end's chunk is unloaded
 * is a cut edge like any other, so {@code networks.unloadedChunkMode} applies. When the answer changes, the device
 * invalidates its own node and the far end's. Server thread only.
 */
public interface RemoteLinkNode {
    /**
     * Adds the positions, in the device's own level, that it links to without touching them. Only ends that link back
     * belong here (both ends report each other); a far end in an unloaded chunk is reported as last known, so the graph
     * keeps a cut edge to it. Read only when the node is added or invalidated.
     */
    void remoteLinks(LongList out);

    /**
     * Channels of the link to {@code partner}, one of the positions {@link #remoteLinks} reported; 0 makes the link
     * carry no routes. Read when routes are built.
     */
    long remoteLinkWidth(long partner);
}
