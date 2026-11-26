package dev.silencebound.maxtech.lib.api.energy;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A block outside MaxTech energy (another mod's FE machine, generator or battery) taking part in an
 * {@link EnergyNetwork}: one {@link #producer() producer} for what it gives and one {@link #consumer() consumer} for
 * what it takes, both in the network's pass like any other endpoint (priorities, linear brownout). Lib's FE edge
 * reaches the neighbour through NeoForge's energy capability; a subclass supplies the four neighbour calls and the
 * settings, this class does the accounting.
 * <p>
 * <b>No energy is created.</b> The edge holds what it moves in exact units of the {@link FeRate}
 * ({@link FeRate#unitsOfFe 1/mu FE}), so no rounding happens until it hands out whole FE or MU, and then always down
 * with the rest kept. What the network draws from the producer comes out of energy the edge took from the neighbour
 * beforehand: the producer's supply is only what the edge holds, so a draw is always covered. The edge takes no more
 * than the network can place in one pass (its consumers' demand and its buffers' free input, rounded up to whole FE),
 * so little builds up in it; a network whose demand changes has its edges look again at the end of the next tick. What
 * the consumer receives is held until the neighbour takes it; what the neighbour does not take stays in the edge and is
 * offered back to the network.
 * <p>
 * <b>Ticking.</b> FE has no change signal, so the edge works like this: while its two flows differ it runs at the end
 * of every tick (before the passes), settling the flows, giving the neighbour what it received, refilling what was
 * drawn and declaring what the neighbour can give and take now; a change in either declaration wakes the network. While
 * they are equal (both 0, or a two-way neighbour taking back its own supply, which cancels out) it sleeps and looks at
 * the neighbour again only every {@link EdgeSettings#pollTicks()} ticks, or at once after {@link #neighbourChanged()}
 * (Lib's FE edge calls it when its capability cache is invalidated). An edge outside a network does nothing.
 * <p>
 * <b>Export.</b> The consumer's demand is the neighbour's room, limited by {@link EdgeSettings#exportPerEdge()}; the
 * demands of all edges of one network are scaled down together to {@link EdgeSettings#exportPerNetwork()}, and with
 * export switched off the demand is 0 and nothing is given to the neighbour. Energy coming in is never limited.
 * <p>
 * Server thread only. Ticking allocates nothing; its cost follows the number of edges that flow, not the network.
 */
public abstract class EnergyEdge {
    /** Most FE asked of the neighbour in one call: FE handlers take {@code int}s. */
    protected static final long MAX_FE_PER_CALL = Integer.MAX_VALUE;

    private final EnergyScheduler scheduler;
    private final EnergyEndpoint producer = EnergyEndpoint.producer();
    private final EnergyEndpoint consumer = EnergyEndpoint.consumer(EnergyPriority.DEFAULT);
    private @Nullable EnergyNetwork network;
    private boolean closed;
    private FeRate rate = FeRate.ONE_TO_ONE;
    /** Energy the edge holds, in units of {@link #rate}; below 0 only after an overdraw, which is then repaid. */
    private long balance;
    /** The part of {@link #balance} the consumer received and the neighbour has not taken yet; 0 or more. */
    private long pending;
    /** MU per tick the producer gives and the consumer gets, as last settled. */
    private long importFlow;
    private long exportFlow;
    private long settledTick;
    /** Export demand before the network's share: the neighbour's room limited per edge. */
    long rawDemand;

    /** Index in the scheduler's edge list, -1 outside it. */
    int schedulerIndex = -1;
    /** Whether the flows differ, so the edge runs every tick. */
    boolean active;
    /** Game tick of the next look at an idle neighbour, {@link EnergyScheduler#NEVER} for none. */
    long nextPoll = EnergyScheduler.NEVER;
    /** Index in the network's edge list, -1 outside a network. */
    int networkIndex = -1;

    /** An edge run by {@code scheduler} (the neighbour's level's, {@link EnergyNetworks#of}), in no network yet. */
    protected EnergyEdge(EnergyScheduler scheduler) {
        this.scheduler = scheduler;
        this.settledTick = scheduler.now();
        EnergyEndpoint.Listener listener = endpoint -> flowChanged();
        producer.setListener(listener);
        consumer.setListener(listener);
        scheduler.addEdge(this);
    }

    /**
     * FE the neighbour would take now, up to {@code max}, without moving anything; 0 when it is gone or takes none.
     * Called at the end of a tick with no transaction open.
     */
    protected abstract long simulateInsert(long max);

    /** Gives the neighbour up to {@code max} FE and returns what it took; 0 when it is gone. No transaction open. */
    protected abstract long insert(long max);

    /** FE the neighbour would give now, up to {@code max}, without moving anything; 0 when it is gone or gives none. */
    protected abstract long simulateExtract(long max);

    /**
     * Takes up to {@code max} FE from the neighbour and returns what it gave; 0 when it is gone. No transaction open.
     */
    protected abstract long extract(long max);

    /** The settings to use now; read once per run of the edge. */
    protected abstract EdgeSettings settings();

    /** What the network draws from the neighbour; add it to the network with {@link #join}, not by hand. */
    public final EnergyEndpoint producer() {
        return producer;
    }

    /** What the network sends the neighbour; add it to the network with {@link #join}, not by hand. */
    public final EnergyEndpoint consumer() {
        return consumer;
    }

    /** The network the edge is in, or empty. */
    public final Optional<EnergyNetwork> network() {
        return Optional.ofNullable(network);
    }

    /** The scheduler that runs the edge. */
    public final EnergyScheduler scheduler() {
        return scheduler;
    }

    /** Whether {@link #close()} ran. */
    public final boolean isClosed() {
        return closed;
    }

    /** The priority at which the neighbour is sent energy (its consumer's); producers have none. */
    public final int exportPriority() {
        return consumer.priority();
    }

    /** Sets the priority at which the neighbour is sent energy, such as {@link EnergyPriority#LEFTOVERS}. */
    public final void setExportPriority(int priority) {
        consumer.setPriority(priority);
    }

    /** MU per tick the network draws from the neighbour, as of the last pass. */
    public final long importing() {
        return importFlow;
    }

    /** MU per tick the network sends the neighbour, as of the last pass. */
    public final long exporting() {
        return exportFlow;
    }

    /**
     * Whole FE the edge holds now: drawn from the neighbour and not yet passed on, or received and not yet taken; at
     * most about one pass of the network's demand. {@link #close()} gives it back to the neighbour where it can, but
     * when the neighbour unloads with the host it cannot: a host must save what {@code close()} returns and hand it to
     * {@link #restoreHeldFe} when it loads again, or that energy is lost. A fraction of one FE is not counted.
     */
    public final long heldFe() {
        return rate.wholeFe(balance);
    }

    /** Puts back FE saved from {@link #heldFe()}, replacing what the edge holds; offered to the network as supply. */
    public final void restoreHeldFe(long fe) {
        rate = settings().rate();
        balance = rate.unitsOfFe(fe);
        pending = 0;
        if (network != null) {
            scheduler.pollEdge(this, 0);
        }
    }

    /**
     * Adds the edge's endpoints to {@code target}, leaving its current network first, and looks at the neighbour at the
     * end of this tick. Throws {@link IllegalStateException} when the edge is closed or the network disposed.
     */
    @SuppressWarnings("ReferenceEquality") // networks are compared by identity
    public final void join(EnergyNetwork target) {
        if (closed) {
            throw new IllegalStateException("energy edge was closed");
        }
        if (target == network) {
            return;
        }
        leave();
        settle(scheduler.now());
        consumer.setDemand(0);
        producer.setSupply(rate.wholeMu(balance));
        rawDemand = 0;
        target.add(producer);
        target.add(consumer);
        network = target;
        target.addEdge(this);
        scheduler.pollEdge(this, 0);
    }

    /**
     * Takes the edge's endpoints out of its network, settling the flows up to now; nothing when it is in none. Must not
     * be called from a flow listener.
     */
    public final void leave() {
        EnergyNetwork current = network;
        if (current == null) {
            return;
        }
        if (!current.isDisposed()) {
            // Removing settles the flows through the listener while network is still set.
            current.remove(producer);
            current.remove(consumer);
        }
        current.removeEdge(this);
        network = null;
        importFlow = 0;
        exportFlow = 0;
        nextPoll = EnergyScheduler.NEVER;
        scheduler.setEdgeActive(this, false);
    }

    /**
     * Leaves the network, gives the neighbour back what the edge holds as far as it takes it, and stops the edge for
     * good. Returns the whole FE still held (the neighbour is gone or full); a host may save it ({@link #heldFe()}).
     */
    public final long close() {
        if (closed) {
            return heldFe();
        }
        leave();
        long fe = Math.min(rate.wholeFe(balance), MAX_FE_PER_CALL);
        if (fe > 0) {
            long given = clamp(insert(fe), fe);
            balance -= rate.unitsOfFe(given);
        }
        pending = 0;
        closed = true;
        scheduler.removeEdge(this);
        return heldFe();
    }

    /**
     * The neighbour may have changed (placed, removed, its chunk loaded or unloaded): the edge looks at it at the end
     * of the current tick. Safe to call from a capability invalidation listener: it touches neither the level nor the
     * neighbour.
     */
    public final void neighbourChanged() {
        if (network != null && !closed) {
            scheduler.pollEdge(this, 0);
        }
    }

    /** One run at the end of tick {@code now}; see the class comment. Called by the scheduler. */
    final void tick(long now) {
        settle(now);
        EnergyNetwork current = network;
        if (current != null && current.isDisposed()) {
            leave();
            return;
        }
        if (current == null || closed) {
            nextPoll = EnergyScheduler.NEVER;
            return;
        }
        EdgeSettings settings = settings();
        useRate(settings.rate());
        long edgeLimit = settings.edgeLimit();
        long room = 0;
        if (edgeLimit > 0) {
            // The room before this tick's delivery is the demand for the next: what the neighbour used since.
            room = clamp(simulateInsert(MAX_FE_PER_CALL), MAX_FE_PER_CALL);
            long fe = Math.min(rate.wholeFe(pending), MAX_FE_PER_CALL);
            if (fe > 0) {
                long units = rate.unitsOfFe(clamp(insert(fe), fe));
                pending -= units;
                balance -= units;
            }
        }
        long offer = clamp(simulateExtract(MAX_FE_PER_CALL), MAX_FE_PER_CALL);
        // Hold what the network can place in one pass (its demand and free buffer input at the last pass, less this
        // edge's own export demand), at most one call's worth, so the next draw is covered but nothing builds up in the
        // edge; repays an overdraw first.
        long placeable = Math.max(0,
                EnergyMath.add(current.consumerDemand(), current.bufferDemand()) - consumer.demand());
        long target = Math.min(offer, rate.wholeFe(EnergyMath.add(rate.unitsOfMu(placeable), rate.mu() - 1L)));
        long want = target - Math.floorDiv(balance - pending, rate.mu());
        if (offer > 0 && want > 0) {
            long ask = Math.min(want, MAX_FE_PER_CALL);
            balance += rate.unitsOfFe(clamp(extract(ask), ask));
        }
        producer.setSupply(rate.wholeMu(balance));
        long demand = Math.min(EnergyMath.mulDiv(room, rate.mu(), rate.fe()), edgeLimit);
        if (demand != rawDemand || settings.networkLimit() != current.edgeExportLimit) {
            rawDemand = demand;
            current.shareExport(settings.networkLimit());
        }
        nextPoll = EnergyMath.add(now, settings.pollTicks());
    }

    /** A pass changed a flow: settle at the old flows, then take the new ones. */
    private void flowChanged() {
        settle(scheduler.now());
        long newImport = producer.drawn();
        long newExport = consumer.received();
        EnergyNetwork current = network;
        if (current != null) {
            current.edgeExport += newExport - exportFlow;
        }
        importFlow = newImport;
        exportFlow = newExport;
        // Equal flows (a two-way neighbour fed its own supply) cancel out in settle: nothing to do every tick.
        scheduler.setEdgeActive(this, newImport != newExport);
    }

    /**
     * Applies the flows of the ticks since the last settle: what the consumer received is held for the neighbour, what
     * the producer gave is paid from it first, then from what was drawn from the neighbour.
     */
    private void settle(long now) {
        long elapsed = now - settledTick;
        if (elapsed <= 0) {
            if (elapsed < 0) {
                settledTick = now;
            }
            return;
        }
        settledTick = now;
        if (exportFlow > 0) {
            long units = rate.unitsOfMu(EnergyMath.mul(exportFlow, elapsed));
            balance = EnergyMath.add(balance, units);
            pending = EnergyMath.add(pending, units);
        }
        if (importFlow > 0) {
            long units = rate.unitsOfMu(EnergyMath.mul(importFlow, elapsed));
            pending -= Math.min(pending, units);
            balance -= units;
        }
    }

    /** Switches to {@code newRate}, keeping what is held in whole FE (a fraction of one FE is lost). */
    private void useRate(FeRate newRate) {
        if (newRate.equals(rate)) {
            return;
        }
        long held = balance >= 0
                ? newRate.unitsOfFe(rate.wholeFe(balance))
                : -newRate.unitsOfFe((-balance - 1) / rate.mu() + 1); // an overdraw rounds up, never away
        pending = Math.min(newRate.unitsOfFe(rate.wholeFe(pending)), Math.max(0, held));
        balance = held;
        rate = newRate;
    }

    /** {@code moved} within {@code 0..asked}: a neighbour reporting more than it was asked does not count. */
    private static long clamp(long moved, long asked) {
        return Math.clamp(moved, 0, asked);
    }
}
