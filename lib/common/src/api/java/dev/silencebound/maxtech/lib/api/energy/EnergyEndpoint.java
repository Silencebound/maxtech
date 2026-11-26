package dev.silencebound.maxtech.lib.api.energy;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One block's part in an {@link EnergyNetwork}: a {@link EnergyRole#PRODUCER producer}, {@link EnergyRole#CONSUMER
 * consumer} or {@link EnergyRole#BUFFER buffer}. The block owns it, keeps it for its lifetime and adds it to the
 * network its host forms. Endpoints declare, they do not push or pull: a producer declares its supply per tick, a
 * consumer its demand per tick, a buffer its capacity and rates. A declaration that changes wakes the network, which
 * works out every endpoint's {@link #flow()} at the end of the tick; the flow then holds every tick until the next
 * pass, which runs only after a change. Declarations are clamped to {@code 0..}{@link #MAX_RATE}.
 * <p>
 * Energy lives in blocks: a buffer's stored energy is kept here, saved by its owner ({@link #stored()},
 * {@link #setStored}) and moved by the flow. While its network sleeps the level is settled lazily: reading it applies
 * the flow of every tick since the last read in one step, so a sleeping network gives the same result as one that ran
 * every tick. Producers and consumers hold no energy here; their owners act on {@link #drawn()} and {@link #received()}
 * (or {@link #suppliedFraction()}: a machine at 0.5 runs at half speed).
 * <p>
 * Server thread only; nothing here allocates.
 */
public final class EnergyEndpoint {
    /** Largest supply, demand or buffer rate per tick an endpoint can declare: 2^40 MU, about 1.1 trillion. */
    public static final long MAX_RATE = 1L << 40;

    /** Told when a pass changed an endpoint's flow. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code endpoint}'s {@link EnergyEndpoint#flow()} changed in the pass that is running; called on the server
         * thread at the end of the tick, while the pass sets the flows. May change declarations, which wakes the
         * network for the next tick, but must not add or remove endpoints (that throws {@link IllegalStateException}).
         */
        void onFlowChanged(EnergyEndpoint endpoint);
    }

    private final EnergyRole role;
    private int priority = EnergyPriority.DEFAULT;
    private long supply;
    private long demand;
    private long capacity;
    private long stored;
    private long maxInput;
    private long maxOutput;
    /** Game tick up to which {@link #stored} includes the flow. */
    private long settledTick;
    private long flow;
    private @Nullable Listener listener;

    @Nullable
    EnergyNetwork network;
    /** Pass scratch: what this endpoint wants to take this pass (consumer demand, buffer intake room). */
    long want;
    /** Pass scratch: what this endpoint offers this pass (producer supply, buffer output). */
    long offer;
    /** Pass scratch: what it is given. */
    long given;
    /** Pass scratch: what is taken from it. */
    long taken;
    /** Pass scratch: whether producers had supply left when the pass reached this endpoint's priority. */
    boolean supplyReached;

    private EnergyEndpoint(EnergyRole role) {
        this.role = role;
    }

    /** A producer with no supply yet; declare it with {@link #setSupply}. */
    public static EnergyEndpoint producer() {
        return new EnergyEndpoint(EnergyRole.PRODUCER);
    }

    /** A consumer at {@code priority} with no demand yet; declare it with {@link #setDemand}. */
    public static EnergyEndpoint consumer(int priority) {
        EnergyEndpoint endpoint = new EnergyEndpoint(EnergyRole.CONSUMER);
        endpoint.priority = priority;
        return endpoint;
    }

    /**
     * An empty buffer of {@code capacity} MU that takes up to {@code maxInput} and gives up to {@code maxOutput} MU per
     * tick, charging at {@code priority} ({@link EnergyPriority#LEFTOVERS} for leftovers only).
     */
    public static EnergyEndpoint buffer(long capacity, long maxInput, long maxOutput, int priority) {
        EnergyEndpoint endpoint = new EnergyEndpoint(EnergyRole.BUFFER);
        endpoint.capacity = Math.max(0, capacity);
        endpoint.maxInput = rate(maxInput);
        endpoint.maxOutput = rate(maxOutput);
        endpoint.priority = priority;
        return endpoint;
    }

    /** This endpoint's role. */
    public EnergyRole role() {
        return role;
    }

    /** The network this endpoint is in, or empty while it is in none. */
    public Optional<EnergyNetwork> network() {
        return Optional.ofNullable(network);
    }

    /** Sets the listener told about flow changes, or none with {@code null}; one per endpoint. */
    public void setListener(@Nullable Listener newListener) {
        listener = newListener;
    }

    /** The priority: higher is served first; see {@link EnergyPriority}. Producers ignore it. */
    public int priority() {
        return priority;
    }

    /** Sets the priority; a change wakes the network. */
    public void setPriority(int newPriority) {
        if (newPriority != priority) {
            settle();
            priority = newPriority;
            if (network != null) {
                network.priorityChanged();
            }
        }
    }

    /** The declared supply per tick; 0 unless this is a producer. */
    public long supply() {
        return supply;
    }

    /**
     * Declares the supply per tick of a producer; a change wakes the network. Throws {@link IllegalStateException} on
     * another role.
     */
    public void setSupply(long perTick) {
        check(EnergyRole.PRODUCER);
        long clamped = rate(perTick);
        if (clamped != supply) {
            supply = clamped;
            changed();
        }
    }

    /** The declared demand per tick; 0 unless this is a consumer. */
    public long demand() {
        return demand;
    }

    /**
     * Declares the demand per tick of a consumer; a change wakes the network. Throws {@link IllegalStateException} on
     * another role.
     */
    public void setDemand(long perTick) {
        check(EnergyRole.CONSUMER);
        long clamped = rate(perTick);
        if (clamped != demand) {
            demand = clamped;
            changed();
        }
    }

    /** A buffer's capacity in MU; 0 for other roles. */
    public long capacity() {
        return capacity;
    }

    /** A buffer's input rate per tick; 0 for other roles. */
    public long maxInput() {
        return maxInput;
    }

    /** A buffer's output rate per tick; 0 for other roles. */
    public long maxOutput() {
        return maxOutput;
    }

    /**
     * Changes a buffer's capacity and rates (an upgrade); stored energy above the new capacity is cut off. A change
     * wakes the network. Throws {@link IllegalStateException} on another role.
     */
    public void setBuffer(long newCapacity, long newMaxInput, long newMaxOutput) {
        check(EnergyRole.BUFFER);
        settle();
        long cap = Math.max(0, newCapacity);
        long in = rate(newMaxInput);
        long out = rate(newMaxOutput);
        if (cap != capacity || in != maxInput || out != maxOutput) {
            capacity = cap;
            maxInput = in;
            maxOutput = out;
            stored = Math.min(stored, capacity);
            changed();
        }
    }

    /**
     * A buffer's stored energy now, with the flow since the last read applied; 0 for other roles. For GUIs, saving and
     * journals.
     */
    public long stored() {
        settle();
        return stored;
    }

    /**
     * Sets a buffer's stored energy (loading a save, a transaction putting back a journaled value), clamped to
     * {@code 0..capacity}; a change wakes the network. Throws {@link IllegalStateException} on another role.
     */
    public void setStored(long amount) {
        check(EnergyRole.BUFFER);
        settle();
        long clamped = Math.clamp(amount, 0, capacity);
        if (clamped != stored) {
            stored = clamped;
            changed();
        }
    }

    /**
     * Takes up to {@code max} MU out of a buffer for its owner's own use (an operation's cost, an FE neighbour) and
     * returns what was taken; wakes the network when anything was. Not limited by the output rate, which applies to the
     * network only. 0 for other roles or a {@code max} below 1.
     */
    public long extract(long max) {
        if (role != EnergyRole.BUFFER || max <= 0) {
            return 0;
        }
        settle();
        long taken = Math.min(max, stored);
        if (taken > 0) {
            stored -= taken;
            changed();
        }
        return taken;
    }

    /**
     * Puts up to {@code max} MU into a buffer from outside the network (an FE neighbour) and returns what fit; wakes
     * the network when anything did. Not limited by the input rate. 0 for other roles or a {@code max} below 1.
     */
    public long insert(long max) {
        if (role != EnergyRole.BUFFER || max <= 0) {
            return 0;
        }
        settle();
        long added = Math.min(max, capacity - stored);
        if (added > 0) {
            stored += added;
            changed();
        }
        return added;
    }

    /**
     * MU that may still leave this endpoint's network this tick for other mods' blocks outside its {@link EnergyEdge}s
     * (an FE handler of this block being drained), under a per-network limit of {@code networkLimit} MU per tick
     * ({@link EdgeSettings#networkLimit()}): the limit less what the edges send and what {@link #countExport} counted
     * this tick. {@link Long#MAX_VALUE} outside a network or without a limit.
     */
    public long exportLeft(long networkLimit) {
        EnergyNetwork current = network;
        return current == null ? Long.MAX_VALUE : current.exportLeft(networkLimit);
    }

    /**
     * Counts {@code mu} sent out of this endpoint's network this tick outside its edges, against the per-network export
     * limit; a negative amount takes a count back (a rolled-back transaction). Nothing outside a network.
     */
    public void countExport(long mu) {
        EnergyNetwork current = network;
        if (current != null) {
            current.countExport(mu);
        }
    }

    /**
     * MU per tick this endpoint gets from the network, set by the latest pass: positive when it receives, negative when
     * it gives (a producer drawn on, a buffer discharging), the net of both for a buffer. 0 outside a network.
     */
    public long flow() {
        return flow;
    }

    /** MU per tick this endpoint receives: {@link #flow()} when positive, else 0. */
    public long received() {
        return Math.max(0, flow);
    }

    /** MU per tick drawn from this endpoint: minus {@link #flow()} when negative, else 0. */
    public long drawn() {
        return Math.max(0, -flow);
    }

    /**
     * The fraction of a consumer's demand it receives, 0 to 1; 1 for a consumer without demand and for other roles.
     * Brownout is linear: a machine runs at this fraction of its speed.
     */
    public double suppliedFraction() {
        if (role != EnergyRole.CONSUMER || demand == 0) {
            return 1.0;
        }
        return (double) Math.max(0, flow) / demand;
    }

    /** The stored energy as last settled, for a pass that settled it already. */
    long storedSettled() {
        return stored;
    }

    /** Applies the flow of the ticks since the last settle to a buffer's stored energy. */
    void settle() {
        EnergyNetwork current = network;
        if (current == null) {
            return;
        }
        settleTo(current.scheduler().now());
    }

    /** {@link #settle()} up to game tick {@code now}. */
    void settleTo(long now) {
        long elapsed = now - settledTick;
        if (elapsed > 0 && flow != 0 && role == EnergyRole.BUFFER) {
            if (flow > 0) {
                stored = Math.min(capacity, EnergyMath.add(stored, EnergyMath.mul(flow, elapsed)));
            } else {
                stored = Math.max(0, stored - EnergyMath.mul(-flow, elapsed));
            }
        }
        if (elapsed > 0 || now < settledTick) {
            settledTick = now;
        }
    }

    /** Sets the flow from a pass (after {@link #settleTo}) and tells the listener when it changed. */
    void setFlow(long newFlow) {
        if (newFlow != flow) {
            flow = newFlow;
            Listener current = listener;
            if (current != null) {
                current.onFlowChanged(this);
            }
        }
    }

    /** Attaches this endpoint to {@code target} at game tick {@code now}. */
    void attach(EnergyNetwork target, long now) {
        network = target;
        settledTick = now;
        flow = 0;
    }

    /** Detaches this endpoint after a final settle; its flow drops to 0, and the listener hears of it. */
    void detach() {
        settle();
        network = null;
        setFlow(0);
    }

    private void changed() {
        EnergyNetwork current = network;
        if (current != null) {
            current.wake();
        }
    }

    private void check(EnergyRole expected) {
        if (role != expected) {
            throw new IllegalStateException("a " + role + " endpoint has no " + expected + " declaration");
        }
    }

    private static long rate(long perTick) {
        return Math.clamp(perTick, 0, MAX_RATE);
    }
}
