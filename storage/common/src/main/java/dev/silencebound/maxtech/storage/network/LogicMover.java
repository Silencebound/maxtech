package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;

/**
 * A device a logic graph's move action goes through (M7L): a port or an interface. The device does the moving itself,
 * through its own handler, rate, access and claims, in transfer transactions that count only what really moved, so a
 * move never dupes or loses anything. Server thread only.
 */
public interface LogicMover {
    /**
     * Moves up to {@code amount} of {@code key} between the network's storage and the device's side: into the device's
     * side when {@code intoDevice}, out of it into the network otherwise. Capped by the device's rate (a port's rate
     * credit, one batch per operation). Returns what moved, 0 when the device is off, in no network, faces nothing that
     * holds or takes the key, or the network refused. Must be called outside any transaction.
     */
    long logicMove(StackKey key, long amount, boolean intoDevice, long gameTime);

    /**
     * The block whose machine the device serves, as {@link net.minecraft.core.BlockPos#asLong()}: a port's faced block.
     * {@link Long#MIN_VALUE} for a device that faces no one block (an interface serves its neighbours). Logic's machine
     * pools (M7L2) match a member's machine with the sensor watching it by this, and number members by position.
     */
    default long logicTarget() {
        return Long.MIN_VALUE;
    }

    /**
     * How much of {@code key} the device's side holds now: what the faced inventory holds for a port, the stock for an
     * interface; 0 when it can't tell. Logic's "least full" pool strategy reads it when a send is planned, never per
     * tick.
     */
    default long logicHeld(StackKey key) {
        return 0;
    }
}
