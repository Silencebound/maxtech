package dev.silencebound.maxtech.lib.api.energy;

import java.util.OptionalLong;

/**
 * What an {@link EnergyNetwork} reports about itself, from {@link EnergyNetwork#readout()}: the numbers its last pass
 * worked out, kept as plain fields while the network sleeps, with stored energy moved on along the constant flows to
 * the current tick. Amounts are MU, rates MU per tick, costs nanoseconds. Immutable; making one walks nothing, so
 * overlays may ask for it as often as they draw.
 * <p>
 * Before a network's first pass every number is 0 and its fraction 1.
 *
 * @param supply
 *            producers' supply per tick at the last pass (what generators could give)
 * @param demand
 *            consumers' demand per tick at the last pass (what machines asked for)
 * @param received
 *            MU per tick consumers receive; below {@code demand} is a brownout
 * @param bufferCharge
 *            MU per tick buffers take in
 * @param bufferDischarge
 *            MU per tick buffers give
 * @param stored
 *            buffers' stored energy now, settled along the flows of the last pass
 * @param capacity
 *            buffers' capacity at the last pass
 * @param lastPassNanos
 *            nanoseconds the last pass took: the network's cost on a tick it runs, 0 for every tick it sleeps
 * @param passCount
 *            passes the network ran
 * @param asleep
 *            whether the network waits for a change, doing no work per tick
 * @param paused
 *            whether the host paused it (unloaded chunks with the pause mode)
 */
public record EnergyReadout(long supply, long demand, long received, long bufferCharge, long bufferDischarge,
        long stored, long capacity, long lastPassNanos, long passCount, boolean asleep, boolean paused) {
    /** A network that never ran a pass, or none at all. */
    public static final EnergyReadout NONE = new EnergyReadout(0, 0, 0, 0, 0, 0, 0, 0, 0, true, false);

    /**
     * The fraction of consumers' demand they receive, 0 to 1; 1 without demand. Brownout is linear: every machine of a
     * short priority runs at its own fraction, and this is the network's as a whole.
     */
    public double suppliedFraction() {
        return suppliedFraction(received, demand);
    }

    /** Whether consumers receive less than they ask for. */
    public boolean brownout() {
        return received < demand;
    }

    /** MU per tick buffers gain (positive) or lose (negative). */
    public long netBufferFlow() {
        return bufferCharge - bufferDischarge;
    }

    /** Ticks until the buffers are full at the current flows; empty when they do not gain. 0 when full. */
    public OptionalLong ticksToFull() {
        return ticksToFull(stored, capacity, netBufferFlow());
    }

    /** Ticks until the buffers are empty at the current flows; empty when they do not lose. 0 when empty. */
    public OptionalLong ticksToEmpty() {
        return ticksToEmpty(stored, netBufferFlow());
    }

    /**
     * {@code received / demand}, clamped to 0..1; 1 when {@code demand} is 0 or below (nothing asked is everything
     * served).
     */
    public static double suppliedFraction(long received, long demand) {
        if (demand <= 0) {
            return 1.0;
        }
        if (received >= demand) {
            return 1.0;
        }
        return received <= 0 ? 0.0 : (double) received / demand;
    }

    /**
     * Ticks until {@code stored} reaches {@code capacity} at {@code net} MU per tick, rounded up (the tick it is full);
     * 0 when it is full already, empty when {@code net} is 0 or below or there is no capacity. Never overflows.
     */
    public static OptionalLong ticksToFull(long stored, long capacity, long net) {
        if (capacity <= 0) {
            return OptionalLong.empty();
        }
        if (stored >= capacity) {
            return OptionalLong.of(0);
        }
        if (net <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ceilDiv(capacity - Math.max(0, stored), net));
    }

    /**
     * Ticks until {@code stored} reaches 0 at {@code net} MU per tick, rounded up; 0 when it is empty already, empty
     * when {@code net} is 0 or above. Never overflows.
     */
    public static OptionalLong ticksToEmpty(long stored, long net) {
        if (stored <= 0) {
            return OptionalLong.of(0);
        }
        if (net >= 0) {
            return OptionalLong.empty();
        }
        // -Long.MIN_VALUE overflows; a loss that large empties any store in one tick.
        long loss = net == Long.MIN_VALUE ? Long.MAX_VALUE : -net;
        return OptionalLong.of(ceilDiv(stored, loss));
    }

    /** {@code ceil(a / b)} for {@code a >= 0, b > 0}, without the {@code a + b - 1} overflow. */
    private static long ceilDiv(long a, long b) {
        long quotient = a / b;
        return a % b == 0 ? quotient : quotient + 1;
    }
}
