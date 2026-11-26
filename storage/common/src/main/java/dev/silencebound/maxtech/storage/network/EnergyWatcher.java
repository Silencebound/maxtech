package dev.silencebound.maxtech.storage.network;

/**
 * Hears a network's stored energy (a condition switch on power): called from the network's tick when the stored FE
 * differs from the last tick's, so only committed values. It must return after a cheap check and only wake a task.
 * Server thread.
 */
@FunctionalInterface
public interface EnergyWatcher {
    /** The network now stores {@code stored} of {@code capacity} FE. */
    void energyChanged(long stored, long capacity);
}
