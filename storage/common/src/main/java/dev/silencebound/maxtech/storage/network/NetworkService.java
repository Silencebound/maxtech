package dev.silencebound.maxtech.storage.network;

/**
 * A service a standalone device gives its whole network (PRD 7.2, Controller; M7R item 4): placed anywhere on the
 * network, found through cables like every device, using one channel (none for security and anchor). The network counts
 * its members per service on joins and leaves only ({@link NetworkState#serviceCount}), so readers never search.
 */
public enum NetworkService {
    /** The controller's access rules apply to the network while at least one security module is a member. */
    SECURITY,
    /** The controller keeps the network's chunks loaded while at least one network anchor is a member. */
    ANCHOR,
    /** Wireless terminals reach the network within range of each wireless access mount and the network's antennas. */
    WIRELESS_ACCESS,
    /** An antenna mount links the network to the far antenna it is paired with. */
    ANTENNA_MOUNT,
    /** Pocket devices reach the network from other dimensions while a dimension bridge is a member. */
    DIMENSION_BRIDGE
}
