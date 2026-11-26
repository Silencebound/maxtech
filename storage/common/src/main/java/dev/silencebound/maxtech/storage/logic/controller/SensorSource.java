package dev.silencebound.maxtech.storage.logic.controller;

import dev.silencebound.maxtech.storage.network.NetworkDevice;

/**
 * A network device whose values logic graphs can read by name: the sensor (M7L area D) implements it on its block and
 * part ({@code SensorDevice}). The controller reads a value when a graph runs and listens to the sensor's reads; it
 * hands a graph a value only when it changed, so nothing polls here. Server thread only.
 */
public interface SensorSource extends NetworkDevice {
    /** The value {@code name} as last read (the sensor's value id); 0 for a name the sensor does not know. */
    long logicValue(String name);

    /**
     * Whether the value {@code name} was there at the last read; false for a name the sensor does not know or a pack
     * reader value whose path was missing, which the editor's why line then says. True by default.
     */
    default boolean logicValueFound(String name) {
        return true;
    }

    /**
     * The block the sensor reads, as {@link net.minecraft.core.BlockPos#asLong()}; {@link Long#MIN_VALUE} when it reads
     * none. A machine pool (M7L2) takes a member's idle value from the pool's sensor reading that member's machine.
     */
    default long logicTarget() {
        return Long.MIN_VALUE;
    }

    /**
     * Calls {@code listener} after any of the sensor's values changed; adding the same listener twice adds it once. The
     * listener only marks work.
     */
    void listenLogic(Runnable listener);

    /** Stops calling {@code listener}; nothing happens if it was not listening. */
    void unlistenLogic(Runnable listener);
}
