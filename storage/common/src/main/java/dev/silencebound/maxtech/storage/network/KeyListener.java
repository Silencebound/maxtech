package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;

/**
 * Hears about one key's total in a device's network (PRD 7.2, Performance: listeners subscribe by key). A device
 * subscribes through {@link DeviceLink#listen}; the network's index looks the changed key up once per committed change
 * and calls only the listeners of that key, so a network with no listeners pays one empty-map check per change.
 *
 * <p>
 * Called on the server thread right after a provider's root transaction committed, so a listener must not move
 * anything, open a transaction, or listen or unlisten: it wakes a task ({@link DeviceLink#wake}) and returns.
 */
@FunctionalInterface
public interface KeyListener {
    /**
     * {@code key}'s total in the device's network is now {@code total}: after a committed change (a key appearing for
     * the first time included, and providers joining or leaving), or once for each listened key when the device joins a
     * network (0 when the network holds none).
     */
    void keyChanged(StackKey key, long total);
}
