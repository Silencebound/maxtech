package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.storage.network.NetworkDevice;

/** A P2P end in either form, the block or the part on a cable. */
public interface P2PDevice extends NetworkDevice {
    /** The end's link and transfer logic. */
    P2PEndpoint endpoint();

    @Override
    default boolean claimBlocked() {
        return endpoint().claimBlocked();
    }
}
