package dev.silencebound.maxtech.storage.logic.controller;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.history.HistoryClaims;
import dev.silencebound.maxtech.storage.history.ValueRing;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The recorded history of a Logic Controller's pinned values (M7L2 history charts), for the sparklines in the rule
 * detail of its screen. One {@link ValueRing} per pinned value, in rule order, up to
 * {@code history.pinSeriesPerController}, each claimed against the network's {@code history.maxSeriesPerNetwork}.
 * Sampled every {@code history.pinIntervalTicks} (at least {@code history.minIntervalTicks}) by a task on the network's
 * scheduler that sleeps in between; a pinned value that is not known at a sample is a gap. A series follows its rule's
 * row, name and node: renaming or moving the rule, or unpinning the node, starts its history again. Saved with the
 * controller (not in its item). Server thread.
 */
public final class PinHistory implements StorageTask, HistoryClaims.Claimant {
    /** Points of a sparkline: the whole fine ring (four hours at the default interval) in 40 steps. */
    public static final int SPARK_POINTS = 40;

    /** One recorded pinned value: the rule's row and name and the pinned node's id. */
    record Series(int graph, String rule, int node, ValueRing ring) {
        static final Codec<Series> CODEC = RecordCodecBuilder
                .create(instance -> instance.group(Codec.INT.fieldOf("graph").forGetter(Series::graph),
                        Codec.STRING.fieldOf("rule").forGetter(Series::rule),
                        Codec.INT.fieldOf("node").forGetter(Series::node),
                        ValueRing.CODEC.fieldOf("ring").forGetter(Series::ring)).apply(instance, Series::new));
    }

    /** At most this many series are saved or loaded, whatever the config says. */
    private static final int HARD_MAX = 64;
    static final Codec<List<Series>> LIST_CODEC = Series.CODEC.listOf(0, HARD_MAX);

    /** What the history belongs to. */
    interface Host {
        LogicWorker worker();

        boolean isRemoved();

        /** The history changed and should be saved. */
        void historyChanged();
    }

    private final Host host;
    private final List<Series> series = new ArrayList<>();
    /** The pinned values wanted at the last sample, as graph and node id pairs (scratch, reused). */
    private final IntArrayList wanted = new IntArrayList();
    /** Each rule's pinned node ids as primitives, rebuilt only when the worker's revision moved. */
    private int[][] pinIds = new int[0][];
    private long pinsRevision = Long.MIN_VALUE;
    private @Nullable HistoryClaims claimedOn;
    private long revision;
    private int samples;
    private boolean limited;

    PinHistory(Host host) {
        this.host = host;
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (host.isRemoved()) {
            release();
            return DONE;
        }
        int interval = Math.max(StorageServerConfig.HISTORY_PIN_INTERVAL_TICKS.getAsInt(),
                StorageServerConfig.HISTORY_MIN_INTERVAL_TICKS.getAsInt());
        LogicWorker worker = host.worker();
        refreshPins(worker);
        collectWanted();
        int count = wanted.size() / 2;
        NetworkState network = transfer.network();
        int granted = count == 0 ? 0 : claim(network, count);
        if (count == 0) {
            release();
        }
        limited = granted < count;
        if (reconcile(worker, granted, interval)) {
            revision++;
            host.historyChanged();
        }
        if (series.isEmpty()) {
            // Nothing pinned: sleep until the rules change (the controller wakes this task then). Pinned values past
            // the network's limit have no event when a series frees up, so they look again once per interval.
            return count == 0 ? WAIT : interval;
        }
        // A series added since the last sample is due at once; the others keep their own interval.
        boolean recorded = false;
        int wait = interval;
        for (int s = 0; s < series.size(); s++) {
            Series entry = series.get(s);
            ValueRing ring = entry.ring();
            if (ring.due(gameTime)) {
                int pin = pinIndex(entry.graph(), entry.node());
                ring.record(gameTime, pin < 0 ? ValueRing.NO_VALUE : worker.pinValue(entry.graph(), pin));
                recorded = true;
            } else {
                wait = Math.min(wait, ring.ticksUntilDue(gameTime));
            }
        }
        if (recorded) {
            samples++;
            revision++;
            host.historyChanged();
        }
        return wait;
    }

    /** The pinned values in rule order, up to {@code history.pinSeriesPerController}, into {@link #wanted}. */
    private void collectWanted() {
        wanted.clear();
        int max = Math.min(HARD_MAX, StorageServerConfig.HISTORY_PIN_SERIES.getAsInt());
        for (int g = 0; g < pinIds.length && wanted.size() / 2 < max; g++) {
            int[] pins = pinIds[g];
            for (int i = 0; i < pins.length && wanted.size() / 2 < max; i++) {
                wanted.add(g);
                wanted.add(pins[i]);
            }
        }
    }

    /**
     * Copies the rules' pinned node ids into {@link #pinIds} when the rules changed since the last copy (a save, new
     * pins, a removal or a load all move {@link LogicWorker#revision()}); otherwise nothing, so a sample reads no boxed
     * list.
     */
    private void refreshPins(LogicWorker worker) {
        if (worker.revision() == pinsRevision && pinIds.length == worker.size()) {
            return;
        }
        pinsRevision = worker.revision();
        int[][] next = new int[worker.size()][];
        for (int g = 0; g < next.length; g++) {
            List<Integer> pins = worker.pins(g);
            int count = Math.min(pins.size(), worker.pinCount(g));
            next[g] = new int[count];
            for (int i = 0; i < count; i++) {
                next[g][i] = pins.get(i);
            }
        }
        pinIds = next;
    }

    /** Keeps the series of the first {@code granted} wanted values, new ones empty; true when anything changed. */
    private boolean reconcile(LogicWorker worker, int granted, int interval) {
        boolean same = series.size() == granted;
        for (int k = 0; same && k < granted; k++) {
            Series entry = series.get(k);
            same = entry.graph() == wanted.getInt(k * 2) && entry.node() == wanted.getInt(k * 2 + 1)
                    && entry.rule().equals(worker.name(entry.graph())) && entry.ring().interval() == interval;
        }
        if (same) {
            return false;
        }
        List<Series> next = new ArrayList<>(granted);
        for (int k = 0; k < granted; k++) {
            int graph = wanted.getInt(k * 2);
            int node = wanted.getInt(k * 2 + 1);
            String rule = worker.name(graph);
            Series kept = null;
            for (int s = 0; s < series.size() && kept == null; s++) {
                Series entry = series.get(s);
                if (entry.graph() == graph && entry.node() == node && entry.rule().equals(rule)
                        && entry.ring().interval() == interval) {
                    kept = entry;
                }
            }
            next.add(kept != null ? kept : new Series(graph, rule, node, new ValueRing(interval)));
        }
        series.clear();
        series.addAll(next);
        return true;
    }

    /** Where node {@code node} is among graph {@code graph}'s pins, or -1. */
    private int pinIndex(int graph, int node) {
        if (graph >= pinIds.length) {
            return -1;
        }
        int[] pins = pinIds[graph];
        for (int i = 0; i < pins.length; i++) {
            if (pins[i] == node) {
                return i;
            }
        }
        return -1;
    }

    private int claim(NetworkState network, int count) {
        HistoryClaims claims = network.historyClaims();
        HistoryClaims previous = claimedOn;
        if (previous != null && previous != claims) {
            previous.release(this);
        }
        claimedOn = claims;
        return claims.claim(this, count, StorageServerConfig.HISTORY_MAX_SERIES.getAsInt());
    }

    /** Gives back the claimed series. */
    void release() {
        HistoryClaims current = claimedOn;
        if (current != null) {
            current.release(this);
            claimedOn = null;
        }
    }

    // Each network has its own claims, compared by identity.
    @SuppressWarnings("ReferenceEquality")
    @Override
    public boolean stillClaims(HistoryClaims claims) {
        return !host.isRemoved() && claimedOn == claims;
    }

    /**
     * Writes the sparkline of graph {@code graph}'s pinned value {@code pin} into {@code out} (at most its length,
     * oldest first, gaps as {@link ValueRing#NO_VALUE}) and returns the number of points; 0 when it has no history.
     */
    public int sparkline(int graph, int pin, long[] out) {
        refreshPins(host.worker());
        if (graph < 0 || graph >= pinIds.length || pin < 0 || pin >= pinIds[graph].length) {
            return 0;
        }
        int node = pinIds[graph][pin];
        for (int s = 0; s < series.size(); s++) {
            Series entry = series.get(s);
            if (entry.graph() == graph && entry.node() == node && entry.ring().size() > 0) {
                return entry.ring().chart(entry.ring().fineSpan(), out.length, out);
            }
        }
        return 0;
    }

    /** Changes on every sample and every change of the recorded set; the screen compares it. */
    public long revision() {
        return revision;
    }

    /** Samples taken since load; for tests. */
    public int samples() {
        return samples;
    }

    /** Series recorded now. */
    public int seriesCount() {
        return series.size();
    }

    /** Whether some pinned values are not recorded because a limit was reached. */
    public boolean limited() {
        return limited;
    }

    /** The series for saving. */
    List<Series> saved() {
        return List.copyOf(series);
    }

    /** Takes saved series; the next sample keeps those that still match a pinned value. */
    void load(List<Series> saved) {
        series.clear();
        series.addAll(saved);
        revision++;
    }
}
