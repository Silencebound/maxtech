package dev.silencebound.maxtech.storage.api;

import org.jspecify.annotations.Nullable;

/**
 * A block that is part of a storage network: the controller, a drive, a terminal, a port. Implemented by the block
 * entity. A device is a node of the network like a cable: it links to the storage cables and devices it touches, and
 * holds one channel along its route to the network's root (PRD 7.2, Channels with hubs).
 *
 * <p>
 * Methods are called on the server thread, never per tick.
 */
public interface StorageDevice {
    /** What a device does in its network; the network treats {@link #CONTROLLER} specially, the rest only counts. */
    enum Role {
        /** Powers the network, roots its routes and zones and keeps its stats; one per network. */
        CONTROLLER,
        /** Holds storage: a drive. */
        STORAGE,
        /** Shows the network to players: a terminal. Player clicks are never rate-limited. */
        ACCESS,
        /** Moves things in and out on a cadence: a port. Its tier sets its speed. */
        TRANSFER,
        /** Anything else. */
        OTHER
    }

    /** The device's role; fixed for the device's life. */
    Role role();

    /**
     * The most operations per second this device makes, for diagnostics and planning; 0 for devices that move nothing
     * on their own (controller, drive, terminal). It limits and reserves nothing: since channels (M7N) a device's own
     * rate is all that limits its transfers. Named {@code bandwidthDemand()} before Storage 0.4.0.
     */
    default int operationsPerSecond() {
        return 0;
    }

    /**
     * The device joined {@code network}, or left its network with {@code null}. Called once per change, after the
     * network has indexed the device's storage (on joining) or dropped it (on leaving). Work the device scheduled moves
     * with it, so most devices do nothing here.
     */
    default void networkChanged(@Nullable StorageNetwork network) {
    }
}
