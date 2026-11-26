package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Hears every operation that moved something into or out of a network, once the transaction it was part of committed at
 * the root (a simulation or a rolled-back operation is never heard). Devices joining or leaving are not moves. A ledger
 * module records these. Server thread.
 */
public interface MoveWatcher {
    /**
     * {@code amount} of {@code key} went in (positive) or came out (negative) at game time {@code gameTime}, for
     * {@code player}, or for automation when null.
     */
    void moved(StackKey key, long amount, @Nullable UUID player, long gameTime);
}
