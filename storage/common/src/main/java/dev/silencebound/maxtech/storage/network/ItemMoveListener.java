package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import org.jspecify.annotations.Nullable;

/**
 * Hears committed moves of the keys it listens to, with the device that made them (M7L2: the logic node "items move in
 * or out"). Subscribed per key through {@link NetworkState#listenItemMoves}, so an operation on a key nobody listens to
 * records nothing. Called on the server thread right after the operation's root transaction committed, once per
 * operation (one batch of one key); a simulation or a rolled-back operation is never heard. A listener must not move
 * anything, open a transaction, or listen or unlisten: it marks work, wakes a task and returns.
 */
@FunctionalInterface
public interface ItemMoveListener {
    /**
     * {@code amount} of {@code key} went into the network (positive) or out of it (negative) through {@code device}, or
     * through a player's own operation at a terminal when {@code device} is null.
     */
    void itemsMoved(StackKey key, long amount, @Nullable NetworkDevice device);
}
