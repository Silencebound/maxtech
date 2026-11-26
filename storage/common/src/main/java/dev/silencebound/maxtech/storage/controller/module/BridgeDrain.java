package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;

/**
 * A dimension bridge's constant FE drain: a task on its network's scheduler that, every
 * {@link DimensionBridge#DRAIN_INTERVAL_TICKS}, takes the FE owed since the last payment ({@code drainPerTick} per tick
 * of game time) from the network. It runs only while the bridge is on a network with a working controller (M7R item 4);
 * otherwise it waits until the bridge's network or that network's controller changes and owes nothing for the time
 * between. A network that cannot pay in full gives what it has, so the surcharge of the next crossing fails until FE
 * comes back. Server thread.
 */
final class BridgeDrain implements StorageTask {
    private final ServiceDeviceBlockEntity bridge;
    /** Game time paid up to; negative while not draining. */
    private long paidUntil = -1;
    private boolean unpaid;
    private long drained;

    BridgeDrain(ServiceDeviceBlockEntity bridge) {
        this.bridge = bridge;
    }

    /** Schedules the task (tasks belong to the block entity instance). */
    void start() {
        bridge.schedule(this);
    }

    /** The bridge's network or that network's controller changed: look again on the next pass. */
    void wake() {
        bridge.wake(this);
    }

    /** Whether the last payment fell short. */
    boolean unpaid() {
        return unpaid;
    }

    /** FE drained since the block entity was made; for tests. */
    long drained() {
        return drained;
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (!bridge.active()) {
            paidUntil = -1;
            unpaid = false;
            return WAIT;
        }
        if (paidUntil < 0 || gameTime < paidUntil) {
            paidUntil = gameTime;
            return DimensionBridge.DRAIN_INTERVAL_TICKS;
        }
        long ticks = gameTime - paidUntil;
        long perTick = DimensionBridge.drainPerTick();
        long owed = perTick > 0 && ticks > Long.MAX_VALUE / perTick ? Long.MAX_VALUE : ticks * perTick;
        paidUntil = gameTime;
        if (owed > 0) {
            FeBuffer buffer = transfer.network().energyBuffer();
            if (buffer != null && buffer.tryConsume(owed)) {
                drained += owed;
                unpaid = false;
            } else {
                if (buffer != null) {
                    long rest = buffer.stored();
                    if (rest > 0 && buffer.tryConsume(rest)) {
                        drained += rest;
                    }
                }
                unpaid = true;
            }
        }
        return DimensionBridge.DRAIN_INTERVAL_TICKS;
    }
}
