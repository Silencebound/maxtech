package dev.silencebound.maxtech.lib.api.energy;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Runs the passes of a level's {@link EnergyNetwork}s: {@link #tick()} at the end of every tick runs the networks that
 * were woken or reached their predicted wake tick, and returns at once when none did, so sleeping networks cost
 * nothing. Lib makes one per server level and ticks it ({@link EnergyNetworks#of}); tests make their own with a clock.
 * Server thread only; ticking allocates nothing.
 */
public final class EnergyScheduler {
    /** A wake tick that never comes: the network waits for an event. */
    public static final long NEVER = Long.MAX_VALUE;

    private final LongSupplier clock;
    private EnergyNetwork[] networks = new EnergyNetwork[4];
    private int count;
    private EnergyNetwork[] queue = new EnergyNetwork[4];
    private int queued;
    /** No network's wake tick is earlier; may be earlier than every one, which costs one scan. */
    private long earliestWake = NEVER;
    private EnergyEdge[] edges = new EnergyEdge[0];
    private int edgeCount;
    /** Edges whose two flows differ, which run every tick. */
    private int activeEdges;
    /** No idle edge's next look is earlier; may be earlier than every one, which costs one scan. */
    private long earliestPoll = NEVER;

    /** A scheduler whose game time comes from {@code clock} (the level's game time). */
    public EnergyScheduler(LongSupplier clock) {
        this.clock = clock;
    }

    /** The current game tick, from the clock. */
    public long now() {
        return clock.getAsLong();
    }

    /** A new, empty network run by this scheduler. */
    public EnergyNetwork createNetwork() {
        EnergyNetwork network = new EnergyNetwork(this);
        if (count == networks.length) {
            networks = grow(networks);
        }
        network.schedulerIndex = count;
        networks[count++] = network;
        return network;
    }

    /** How many networks this scheduler runs. */
    public int networkCount() {
        return count;
    }

    /** How many {@link EnergyEdge}s this scheduler runs, open ones in a network or not. */
    public int edgeCount() {
        return edgeCount;
    }

    /** How many edges have flows that differ and run every tick; the others sleep. */
    public int activeEdgeCount() {
        return activeEdges;
    }

    /**
     * Runs the edges that flow or are due to look at their neighbour, then the pass of every network that was woken
     * this tick or reached its predicted wake tick. Call once at the end of each tick; Lib does for its level
     * schedulers. A network woken during this call (by a flow listener) runs at the next call.
     */
    public void tick() {
        long now = clock.getAsLong();
        if (activeEdges > 0 || now >= earliestPoll) {
            tickEdges(now);
        }
        if (queued == 0 && now < earliestWake) {
            return;
        }
        if (now >= earliestWake) {
            long earliest = NEVER;
            for (int i = 0; i < count; i++) {
                EnergyNetwork network = networks[i];
                if (network.wakeTick <= now) {
                    queue(network);
                } else {
                    earliest = Math.min(earliest, network.wakeTick);
                }
            }
            earliestWake = earliest;
        }
        int due = queued;
        for (int i = 0; i < due; i++) {
            EnergyNetwork network = queue[i];
            network.queued = false;
            if (!network.isDisposed()) {
                network.pass(now);
                earliestWake = Math.min(earliestWake, network.wakeTick);
            }
        }
        // Networks queued by listeners during the passes move to the front for the next tick.
        int later = queued - due;
        System.arraycopy(queue, due, queue, 0, later);
        Arrays.fill(queue, later, queued, null);
        queued = later;
    }

    /** Runs every edge that flows or is due, and finds the earliest next look of the idle ones. */
    private void tickEdges(long now) {
        long earliest = NEVER;
        for (int i = 0; i < edgeCount; i++) {
            EnergyEdge edge = edges[i];
            if (edge.active || edge.nextPoll <= now) {
                edge.tick(now);
            }
            if (!edge.active) {
                earliest = Math.min(earliest, edge.nextPoll);
            }
        }
        earliestPoll = earliest;
    }

    /** Adds a new edge; it sleeps until it joins a network. */
    void addEdge(EnergyEdge edge) {
        if (edgeCount == edges.length) {
            EnergyEdge[] grown = new EnergyEdge[Math.max(4, edgeCount * 2)];
            System.arraycopy(edges, 0, grown, 0, edgeCount);
            edges = grown;
        }
        edge.schedulerIndex = edgeCount;
        edges[edgeCount++] = edge;
    }

    /** Takes a closed edge out of the list. */
    @SuppressWarnings("ReferenceEquality") // edges are members by identity
    void removeEdge(EnergyEdge edge) {
        int index = edge.schedulerIndex;
        if (index < 0 || index >= edgeCount || edges[index] != edge) {
            return;
        }
        setEdgeActive(edge, false);
        EnergyEdge last = edges[--edgeCount];
        edges[index] = last;
        last.schedulerIndex = index;
        Arrays.fill(edges, edgeCount, edgeCount + 1, null);
        edge.schedulerIndex = -1;
    }

    /** Marks {@code edge} as flowing (runs every tick) or idle (runs when its next look is due). */
    void setEdgeActive(EnergyEdge edge, boolean active) {
        if (edge.active != active) {
            edge.active = active;
            activeEdges += active ? 1 : -1;
            if (!active) {
                earliestPoll = Math.min(earliestPoll, edge.nextPoll);
            }
        }
    }

    /** Has {@code edge} look at its neighbour at the first {@link #tick()} at or after game tick {@code tick}. */
    void pollEdge(EnergyEdge edge, long tick) {
        edge.nextPoll = Math.min(edge.nextPoll, tick);
        earliestPoll = Math.min(earliestPoll, tick);
    }

    /** Queues {@code network} for a pass at the next {@link #tick()}; nothing when it is queued already. */
    void queue(EnergyNetwork network) {
        if (network.queued) {
            return;
        }
        if (queued == queue.length) {
            queue = grow(queue);
        }
        network.queued = true;
        queue[queued++] = network;
    }

    /** Takes a disposed network out of the list; a queued entry is skipped when its turn comes. */
    void remove(EnergyNetwork network) {
        int index = network.schedulerIndex;
        if (index < 0 || index >= count || networks[index] != network) {
            return;
        }
        EnergyNetwork last = networks[--count];
        networks[index] = last;
        last.schedulerIndex = index;
        Arrays.fill(networks, count, count + 1, null);
        network.schedulerIndex = -1;
    }

    private static EnergyNetwork[] grow(EnergyNetwork[] array) {
        EnergyNetwork[] grown = new EnergyNetwork[array.length * 2];
        System.arraycopy(array, 0, grown, 0, array.length);
        return grown;
    }
}
