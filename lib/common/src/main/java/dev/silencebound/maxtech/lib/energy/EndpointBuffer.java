package dev.silencebound.maxtech.lib.energy;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyRole;

/**
 * A {@link FeBuffer} whose energy lives in a {@link EnergyRole#BUFFER buffer} {@link EnergyEndpoint}, so the same MU
 * that operations pay from inside their transactions are what the block's energy network charges (MLE): a host adds
 * {@link #endpoint()} to its network and the pass's flows show up in {@link #stored()}. Every change made through this
 * buffer (an operation's cost, a neighbour's insert, a rollback putting a snapshot back, loading a save) sets the
 * endpoint's stored energy, which settles its flow first and wakes the network. A transaction never spans a pass
 * (passes run at the end of the tick), so a snapshot taken and restored within one tick is exact.
 * <p>
 * Energy above the capacity, which a lowered capacity or a migrated save can leave, is kept here as excess that only
 * drains, as {@link FeBuffer} keeps it; the endpoint holds at most its capacity and the excess is spent first. The
 * buffer's per-operation insert and extract limits are for FE neighbours; the endpoint's per-tick rates for the network
 * are set apart with {@link #setRates}. Server thread only.
 */
public class EndpointBuffer extends FeBuffer {
    private final EnergyEndpoint endpoint;
    /** Energy above the endpoint's capacity; drains first. */
    private long excess;

    /**
     * A buffer of {@code capacity} MU with per-operation limits {@code maxInsert} and {@code maxExtract} for
     * neighbours, whose endpoint takes up to {@code networkInput} and gives up to {@code networkOutput} MU per tick
     * from its network and charges at {@code priority}; {@code onChanged} as for {@link FeBuffer}.
     */
    public EndpointBuffer(long capacity, long maxInsert, long maxExtract, long networkInput, long networkOutput,
            int priority, Runnable onChanged) {
        super(0, maxInsert, maxExtract, onChanged);
        this.endpoint = EnergyEndpoint.buffer(capacity, networkInput, networkOutput, priority);
    }

    /** The endpoint holding this buffer's energy; add it to the block's energy network. */
    public final EnergyEndpoint endpoint() {
        return endpoint;
    }

    /** Sets the endpoint's per-tick rates for the network; a change wakes it. */
    public final void setRates(long networkInput, long networkOutput) {
        if (networkInput != endpoint.maxInput() || networkOutput != endpoint.maxOutput()) {
            endpoint.setBuffer(endpoint.capacity(), networkInput, networkOutput);
        }
    }

    /** Energy held above the capacity, which only drains; 0 normally. */
    public final long excess() {
        return excess;
    }

    @Override
    public long capacity() {
        return endpoint.capacity();
    }

    @Override
    public long getCapacityAsLong() {
        return endpoint.capacity();
    }

    @Override
    public void setCapacity(long capacity) {
        long cap = Math.max(0, capacity);
        if (cap == endpoint.capacity()) {
            return;
        }
        long total = amount();
        endpoint.setBuffer(cap, endpoint.maxInput(), endpoint.maxOutput());
        split(total);
    }

    @Override
    protected long amount() {
        return endpoint.stored() + excess;
    }

    @Override
    protected void setAmount(long amount) {
        split(amount);
    }

    /** Puts {@code amount} in the endpoint up to its capacity and the rest in the excess. */
    private void split(long amount) {
        long total = Math.max(0, amount);
        long cap = endpoint.capacity();
        if (total > cap) {
            excess = total - cap;
            endpoint.setStored(cap);
        } else {
            excess = 0;
            endpoint.setStored(total);
        }
    }
}
