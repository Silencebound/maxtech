package dev.silencebound.maxtech.lib.api.energy;

import java.util.Arrays;

/**
 * A set of {@link EnergyEndpoint}s that share energy, formed by the mod that hosts it: Storage makes one per storage
 * network, a cable mod one per conduit graph (splits and merges move endpoints between networks with {@link #remove}
 * and {@link #add}). Made by {@link EnergyScheduler#createNetwork()}, which runs its pass.
 * <p>
 * <b>The pass</b> runs at the end of a tick, and only after something changed: it settles every buffer, then serves
 * priorities from highest to lowest. Each priority first takes from the producers' supply, shared in proportion to
 * demand (consumers' demand, buffers' free input); consumers it leaves short then take from the buffers' output, in the
 * same proportion. Within one priority every endpoint thus gets the same fraction of its demand, to within one MU
 * (linear brownout). Producers and buffers give in proportion to what they offer. What a pass hands out always equals
 * what it draws, so a pass never makes or loses energy.
 * <p>
 * <b>Sleep and wake.</b> After a pass the flows hold every tick and the network does no work: it wakes when a
 * declaration changes, an endpoint is added or removed, the host calls {@link #wake()} (a chunk loaded), or at the tick
 * a buffer will fill or empty far enough to change the result, which the pass predicts from the constant flows. The
 * cost of a pass follows the number of endpoints; a sleeping network costs nothing per tick.
 * <p>
 * <b>Diagnostics</b> such as {@link #producerSupply()} and {@link #lastPassNanos()} are plain fields the pass fills;
 * reading them is free. Server thread only; a pass allocates nothing.
 */
public final class EnergyNetwork {
    private final EnergyScheduler scheduler;
    /** Members sorted by priority, highest first, while {@link #orderDirty} is false; the first {@link #size} used. */
    private EnergyEndpoint[] members = new EnergyEndpoint[8];
    private int size;
    private boolean orderDirty;
    private boolean paused;
    private boolean disposed;
    /** Whether a pass is setting flows, during which listeners must not add or remove endpoints. */
    private boolean inPass;

    /** Whether the network waits in its scheduler's queue for a pass this tick. */
    boolean queued;
    /** Game tick of the next predicted pass, {@link EnergyScheduler#NEVER} when there is none. */
    long wakeTick = EnergyScheduler.NEVER;
    /** Index in the scheduler's network list. */
    int schedulerIndex;

    private long producerSupply;
    private long bufferSupply;
    private long consumerDemand;
    private long bufferDemand;
    private long consumerReceived;
    private long bufferCharge;
    private long bufferDischarge;
    /** Buffers' stored energy settled at the last pass, for {@link #readout()}. */
    private long storedAtPass;
    /** Buffers' capacity at the last pass, for {@link #readout()}. */
    private long capacityAtPass;
    private long passCount;
    private long lastPassTick = -1;
    private long lastPassNanos;
    private long totalPassNanos;

    /** The {@link EnergyEdge}s whose endpoints are in this network; the first {@link #edgeCount} used. */
    private EnergyEdge[] edges = new EnergyEdge[0];
    private int edgeCount;
    /** The per-network export limit the edges' demands were last shared out under; -1 before the first share. */
    long edgeExportLimit = -1;
    /** MU per tick the edges' consumers receive, kept by the edges as their flows change. */
    long edgeExport;
    /** Game tick of {@link #handlerExport}. */
    private long handlerExportTick = -1;
    /** MU sent out this tick by FE handlers of the network's blocks, outside the edges. */
    private long handlerExport;

    EnergyNetwork(EnergyScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** The scheduler that runs this network's passes. */
    public EnergyScheduler scheduler() {
        return scheduler;
    }

    /**
     * Adds {@code endpoint}; the network wakes. Throws {@link IllegalStateException} when the endpoint is in a network
     * already (remove it first) or this network was disposed.
     */
    public void add(EnergyEndpoint endpoint) {
        if (disposed) {
            throw new IllegalStateException("energy network was disposed");
        }
        checkNotInPass();
        if (endpoint.network != null) {
            throw new IllegalStateException("endpoint is in a network already");
        }
        if (size == members.length) {
            EnergyEndpoint[] grown = new EnergyEndpoint[size * 2];
            System.arraycopy(members, 0, grown, 0, size);
            members = grown;
        }
        members[size++] = endpoint;
        endpoint.attach(this, scheduler.now());
        orderDirty = true;
        wake();
    }

    /**
     * Removes {@code endpoint} if it is in this network and returns whether it was; its buffer is settled first and its
     * flow drops to 0. The network wakes.
     */
    @SuppressWarnings("ReferenceEquality") // endpoints are members by identity
    public boolean remove(EnergyEndpoint endpoint) {
        if (endpoint.network != this) {
            return false;
        }
        checkNotInPass();
        for (int i = 0; i < size; i++) {
            if (members[i] == endpoint) {
                System.arraycopy(members, i + 1, members, i, size - i - 1);
                Arrays.fill(members, --size, size + 1, null);
                break;
            }
        }
        endpoint.detach();
        wake();
        return true;
    }

    /** How many endpoints the network has. */
    public int size() {
        return size;
    }

    /**
     * Pauses or resumes the network, for a host whose network reaches into unloaded chunks in {@code pause} mode
     * ({@code networks.unloadedChunkMode}). A paused network settles its buffers, sets every flow to 0 and does not run
     * until resumed.
     */
    public void setPaused(boolean pause) {
        if (pause != paused) {
            paused = pause;
            wake();
        }
    }

    /** Whether the network is paused. */
    public boolean isPaused() {
        return paused;
    }

    /**
     * Asks for a pass at the end of this tick. Endpoints call it themselves when a declaration changes; a host calls it
     * when something outside the declarations changed. Free when a pass is asked for already.
     */
    public void wake() {
        if (!disposed) {
            scheduler.queue(this);
        }
    }

    /**
     * Removes every endpoint (settled, flows 0) and takes the network out of its scheduler, for a host whose network is
     * gone. The network cannot be used again.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        checkNotInPass();
        disposed = true;
        while (size > 0) {
            EnergyEndpoint endpoint = members[--size];
            Arrays.fill(members, size, size + 1, null);
            endpoint.detach();
        }
        scheduler.remove(this);
    }

    /** Whether {@link #dispose()} ran. */
    public boolean isDisposed() {
        return disposed;
    }

    /** Whether the network waits for an event or a predicted tick, doing no work per tick. */
    public boolean isAsleep() {
        return !queued && wakeTick > scheduler.now() + 1;
    }

    /** Game tick of the next predicted pass, or {@link EnergyScheduler#NEVER} when only an event wakes it. */
    public long nextWakeTick() {
        return wakeTick;
    }

    /** Producers' supply per tick at the last pass (what they could give). */
    public long producerSupply() {
        return producerSupply;
    }

    /** Buffers' output per tick at the last pass (what they could give, limited by stored energy and rates). */
    public long bufferSupply() {
        return bufferSupply;
    }

    /** Consumers' demand per tick at the last pass. */
    public long consumerDemand() {
        return consumerDemand;
    }

    /** Buffers' free input per tick at the last pass (what they could take). */
    public long bufferDemand() {
        return bufferDemand;
    }

    /** MU per tick consumers receive since the last pass. */
    public long consumerReceived() {
        return consumerReceived;
    }

    /** MU per tick buffers charge since the last pass (before what they give is taken off). */
    public long bufferCharge() {
        return bufferCharge;
    }

    /** MU per tick buffers give since the last pass. */
    public long bufferDischarge() {
        return bufferDischarge;
    }

    /**
     * The fraction of consumers' demand they receive, 0 to 1; 1 without demand. Below 1 is a brownout: the network as a
     * whole is short.
     */
    public double satisfaction() {
        return consumerDemand == 0 ? 1.0 : (double) consumerReceived / consumerDemand;
    }

    /**
     * The sum of buffers' stored energy now; walks the endpoints, so it is for readouts, not per tick. Saturates
     * instead of overflowing.
     */
    public long bufferStored() {
        long total = 0;
        for (int i = 0; i < size; i++) {
            total = EnergyMath.add(total, members[i].stored());
        }
        return total;
    }

    /** The sum of buffers' capacities; walks the endpoints, so it is for readouts. Saturates instead of overflowing. */
    public long bufferCapacity() {
        long total = 0;
        for (int i = 0; i < size; i++) {
            total = EnergyMath.add(total, members[i].capacity());
        }
        return total;
    }

    /**
     * The network's diagnostics (supply, demand, brownout, stored and capacity, time to full or empty, pass cost) as
     * its last pass left them, with stored energy moved on along the constant flows to the current tick. Reads plain
     * fields only: it walks no endpoints, settles no buffer and never wakes the network, so overlays, sensors and other
     * mods may ask as often as they like. Energy an owner moved outside the pass ({@link EnergyEndpoint#extract},
     * {@link EnergyEndpoint#insert}) shows from the pass it wakes, at the end of that tick. {@link EnergyReadout#NONE}
     * numbers before the first pass and after {@link #dispose()}. Server thread only; allocates the record.
     */
    public EnergyReadout readout() {
        if (disposed || lastPassTick < 0) {
            return EnergyReadout.NONE;
        }
        long net = bufferCharge - bufferDischarge;
        long elapsed = Math.max(0, scheduler.now() - lastPassTick);
        long stored = storedAtPass;
        if (net > 0) {
            stored = Math.min(capacityAtPass, EnergyMath.add(stored, EnergyMath.mul(net, elapsed)));
        } else if (net < 0) {
            stored = Math.max(0, stored - EnergyMath.mul(-net, elapsed));
        }
        return new EnergyReadout(producerSupply, consumerDemand, consumerReceived, bufferCharge, bufferDischarge,
                stored, capacityAtPass, lastPassNanos, passCount, isAsleep(), paused);
    }

    /** MU per tick the network sends to other mods' blocks through its {@link EnergyEdge}s, as of the last pass. */
    public long edgeExport() {
        return edgeExport;
    }

    /** How many {@link EnergyEdge}s are in the network. */
    public int edgeCount() {
        return edgeCount;
    }

    /** How many passes the network ran. */
    public long passCount() {
        return passCount;
    }

    /** Game tick of the last pass, or -1 before the first. */
    public long lastPassTick() {
        return lastPassTick;
    }

    /** Nanoseconds the last pass took: this network's cost per active tick. */
    public long lastPassNanos() {
        return lastPassNanos;
    }

    /** Nanoseconds every pass of this network took together. */
    public long totalPassNanos() {
        return totalPassNanos;
    }

    /** Adds {@code edge} to the edges that share the export limit; its endpoints are added by the edge. */
    void addEdge(EnergyEdge edge) {
        if (edgeCount == edges.length) {
            EnergyEdge[] grown = new EnergyEdge[Math.max(4, edgeCount * 2)];
            System.arraycopy(edges, 0, grown, 0, edgeCount);
            edges = grown;
        }
        edge.networkIndex = edgeCount;
        edges[edgeCount++] = edge;
    }

    /** Removes {@code edge} from the edges and shares the export limit among the others again. */
    @SuppressWarnings("ReferenceEquality") // edges are members by identity
    void removeEdge(EnergyEdge edge) {
        int index = edge.networkIndex;
        if (index < 0 || index >= edgeCount || edges[index] != edge) {
            return;
        }
        EnergyEdge last = edges[--edgeCount];
        edges[index] = last;
        last.networkIndex = index;
        Arrays.fill(edges, edgeCount, edgeCount + 1, null);
        edge.networkIndex = -1;
        if (!disposed && edgeExportLimit >= 0) {
            shareExport(edgeExportLimit);
        }
    }

    /**
     * Sets every edge's export demand from its raw demand: as it is while their sum stays within {@code limit} MU per
     * tick, else scaled down in proportion so the sum is exactly {@code limit} (cumulative rounding).
     */
    void shareExport(long limit) {
        edgeExportLimit = limit;
        long total = 0;
        for (int i = 0; i < edgeCount; i++) {
            total = EnergyMath.add(total, edges[i].rawDemand);
        }
        if (total <= limit) {
            for (int i = 0; i < edgeCount; i++) {
                edges[i].consumer().setDemand(edges[i].rawDemand);
            }
            return;
        }
        long weights = 0;
        long handed = 0;
        for (int i = 0; i < edgeCount; i++) {
            EnergyEdge edge = edges[i];
            weights = EnergyMath.add(weights, edge.rawDemand);
            long upTo = EnergyMath.mulDiv(weights, limit, total);
            edge.consumer().setDemand(upTo - handed);
            handed = upTo;
        }
    }

    /**
     * MU the network's blocks may still send out this tick outside the edges under a per-network {@code limit}: the
     * limit less what the edges send and what was counted this tick.
     */
    long exportLeft(long limit) {
        if (limit == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long used = EnergyMath.add(edgeExport, handlerExportTick == scheduler.now() ? handlerExport : 0);
        return Math.max(0, limit - used);
    }

    /** Counts {@code mu} sent out this tick outside the edges; a negative amount takes back a rolled-back count. */
    void countExport(long mu) {
        long now = scheduler.now();
        if (handlerExportTick != now) {
            handlerExportTick = now;
            handlerExport = 0;
        }
        handlerExport = Math.max(0, handlerExport + mu);
    }

    /** An endpoint's priority changed: the order is re-sorted at the next pass, which this asks for. */
    void priorityChanged() {
        orderDirty = true;
        wake();
    }

    /** The pass at game tick {@code now}; see the class comment. Called by the scheduler. */
    void pass(long now) {
        long start = System.nanoTime();
        if (orderDirty) {
            sortByPriority();
        }
        EnergyEndpoint[] m = members;
        int n = size;
        long supply = 0;
        long buffered = 0;
        long consumersWant = 0;
        long buffersWant = 0;
        long storedSum = 0;
        long capacitySum = 0;
        for (int i = 0; i < n; i++) {
            EnergyEndpoint e = m[i];
            e.settleTo(now);
            e.given = 0;
            e.taken = 0;
            e.want = 0;
            e.offer = 0;
            if (e.role() == EnergyRole.BUFFER) {
                storedSum = EnergyMath.add(storedSum, e.storedSettled());
                capacitySum = EnergyMath.add(capacitySum, e.capacity());
            }
            if (paused) {
                continue;
            }
            switch (e.role()) {
                case PRODUCER -> {
                    e.offer = e.supply();
                    supply += e.offer;
                }
                case CONSUMER -> {
                    e.want = e.demand();
                    consumersWant += e.want;
                }
                case BUFFER -> {
                    long stored = e.storedSettled();
                    e.offer = Math.min(e.maxOutput(), stored);
                    e.want = Math.min(e.maxInput(), e.capacity() - stored);
                    buffered += e.offer;
                    buffersWant += e.want;
                }
            }
        }

        long supplyLeft = supply;
        long bufferedLeft = buffered;
        boolean buffersNeeded = false;
        int i = 0;
        while (i < n) {
            int priority = m[i].priority();
            int end = i;
            long levelWant = 0;
            while (end < n && m[end].priority() == priority) {
                levelWant += m[end].want;
                m[end].supplyReached = supplyLeft > 0;
                end++;
            }
            if (levelWant > 0) {
                long fromSupply = Math.min(supplyLeft, levelWant);
                supplyLeft -= fromSupply;
                long consumersShort = 0;
                long weights = 0;
                long handed = 0;
                for (int k = i; k < end; k++) {
                    EnergyEndpoint e = m[k];
                    if (e.want > 0) {
                        weights += e.want;
                        long upTo = EnergyMath.mulDiv(weights, fromSupply, levelWant);
                        e.given = upTo - handed;
                        handed = upTo;
                        if (e.role() == EnergyRole.CONSUMER) {
                            consumersShort += e.want - e.given;
                        }
                    }
                }
                if (consumersShort > 0) {
                    buffersNeeded = true;
                    long fromBuffers = Math.min(bufferedLeft, consumersShort);
                    if (fromBuffers > 0) {
                        bufferedLeft -= fromBuffers;
                        weights = 0;
                        handed = 0;
                        for (int k = i; k < end; k++) {
                            EnergyEndpoint e = m[k];
                            long missing = e.want - e.given;
                            if (missing > 0 && e.role() == EnergyRole.CONSUMER) {
                                weights += missing;
                                long upTo = EnergyMath.mulDiv(weights, fromBuffers, consumersShort);
                                e.given += upTo - handed;
                                handed = upTo;
                            }
                        }
                    }
                }
            }
            i = end;
        }

        long drawnSupply = supply - supplyLeft;
        long drawnBuffers = buffered - bufferedLeft;
        drawProportionally(m, n, EnergyRole.PRODUCER, drawnSupply, supply);
        drawProportionally(m, n, EnergyRole.BUFFER, drawnBuffers, buffered);

        long received = 0;
        long charged = 0;
        for (int k = 0; k < n; k++) {
            EnergyEndpoint e = m[k];
            if (e.role() == EnergyRole.CONSUMER) {
                received += e.given;
            } else if (e.role() == EnergyRole.BUFFER) {
                charged += e.given;
            }
        }
        long wake = predictWake(m, n, now, buffersNeeded);
        if (consumersWant != consumerDemand || buffersWant != bufferDemand) {
            // Edges hold only what the network can place, so a change in demand has them look again.
            for (int k = 0; k < edgeCount; k++) {
                scheduler.pollEdge(edges[k], 0);
            }
        }
        producerSupply = supply;
        bufferSupply = buffered;
        consumerDemand = consumersWant;
        bufferDemand = buffersWant;
        consumerReceived = received;
        bufferCharge = charged;
        bufferDischarge = drawnBuffers;
        storedAtPass = storedSum;
        capacityAtPass = capacitySum;
        wakeTick = wake;
        passCount++;
        lastPassTick = now;
        // Flows last: listeners may change declarations, which only queue the next pass.
        inPass = true;
        try {
            for (int k = 0; k < n; k++) {
                EnergyEndpoint e = m[k];
                e.setFlow(e.given - e.taken);
            }
        } finally {
            inPass = false;
        }
        lastPassNanos = System.nanoTime() - start;
        totalPassNanos += lastPassNanos;
    }

    /**
     * Splits {@code total} over the endpoints of {@code role} in proportion to their offer (summing to
     * {@code offered}), with cumulative rounding so the parts add up to exactly {@code total}.
     */
    private static void drawProportionally(EnergyEndpoint[] m, int n, EnergyRole role, long total, long offered) {
        if (total <= 0) {
            return;
        }
        long weights = 0;
        long drawn = 0;
        for (int k = 0; k < n; k++) {
            EnergyEndpoint e = m[k];
            if (e.role() == role && e.offer > 0) {
                weights += e.offer;
                long upTo = EnergyMath.mulDiv(weights, total, offered);
                e.taken = upTo - drawn;
                drawn = upTo;
            }
        }
    }

    /**
     * The earliest tick at which a buffer's offer or free input changes with its constant net flow, so the next pass
     * would differ; {@link EnergyScheduler#NEVER} when none does. A buffer's output only matters while consumers are
     * short of producers' supply ({@code buffersNeeded}), its free input only while supply reaches its priority. Stored
     * energy stays within bounds until that tick, since a buffer is never given more than its free input or drawn more
     * than it holds per tick.
     */
    private static long predictWake(EnergyEndpoint[] m, int n, long now, boolean buffersNeeded) {
        long ticks = Long.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            EnergyEndpoint e = m[k];
            if (e.role() != EnergyRole.BUFFER) {
                continue;
            }
            long net = e.given - e.taken;
            if (net == 0) {
                continue;
            }
            long stored = e.storedSettled();
            long room = e.capacity() - stored;
            long untilChange;
            if (net > 0) {
                // Free input min(maxInput, room) shrinks once room drops below maxInput.
                untilChange = room >= e.maxInput() ? (room - e.maxInput()) / net + 1 : 1;
                if (buffersNeeded && stored < e.maxOutput()) {
                    untilChange = 1; // its output grows each tick while below the output rate
                }
            } else {
                long out = -net;
                untilChange = stored >= e.maxOutput() ? (stored - e.maxOutput()) / out + 1 : 1;
                if (room < e.maxInput() && e.supplyReached) {
                    untilChange = 1; // its free input grows each tick and supply would reach it
                }
            }
            ticks = Math.min(ticks, untilChange);
        }
        return ticks == Long.MAX_VALUE ? EnergyScheduler.NEVER : EnergyMath.add(now, ticks);
    }

    private void checkNotInPass() {
        if (inPass) {
            throw new IllegalStateException("endpoints cannot be added or removed from a flow listener");
        }
    }

    /** Stable insertion sort by priority, highest first; near-linear on the mostly sorted member list. */
    private void sortByPriority() {
        EnergyEndpoint[] m = members;
        for (int i = 1; i < size; i++) {
            EnergyEndpoint e = m[i];
            int p = e.priority();
            int j = i - 1;
            while (j >= 0 && m[j].priority() < p) {
                m[j + 1] = m[j];
                j--;
            }
            m[j + 1] = e;
        }
        orderDirty = false;
    }
}
