package dev.silencebound.maxtech.storage.network;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * A storage cable that carries devices of its own: the parts on its faces. Its devices sit on the cable's node, join
 * its network with it and hold channels along its route. Implemented by the cable's saved state; the network logic
 * finds it when the cable's node is added. Server thread only.
 */
public interface DeviceHost {
    /** How many devices the cable carries now. */
    int deviceCount();

    /** The device at {@code index}, from 0 to {@link #deviceCount()} exclusive. */
    NetworkDevice device(int index);

    /** Whether a part covers the cable's {@code side}: the cable links on that side to nothing but the part. */
    boolean occupies(Direction side);

    /**
     * Whether the cable links on {@code side} to nothing but the part there: true for a covered face unless the part
     * sits in the cable's line and passes the link on now (a linked toggle bus). Read by the connectivity on topology
     * changes only.
     */
    default boolean cutsLink(Direction side) {
        return occupies(side);
    }

    /**
     * The cable's node was added to its graph (placed, or its chunk loaded): the devices start their work. Called on
     * every add; devices start once per instance.
     */
    void nodeAdded(ServerLevel level);
}
