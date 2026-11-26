package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.history.ChartValue;
import dev.silencebound.maxtech.storage.history.HistoryClaims;
import dev.silencebound.maxtech.storage.history.ValueRing;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.Optional;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The recording side of a {@link DisplaySourceKind#HISTORY} screen (M7L2 history charts): one {@link ValueRing} of the
 * chart's value, filled by a task on the network's scheduler that wakes once per interval (never a ticker), whether or
 * not anyone looks. Each sample first claims the screen's one series on the network ({@link HistoryClaims}); a screen
 * past {@code history.maxSeriesPerNetwork} records nothing and says so. The ring is saved with the screen together with
 * the reference and interval it records, and starts again when either changes. Server thread.
 */
public final class ChartRecorder implements StorageTask, HistoryClaims.Claimant {
    /** The chart's state, sent to viewers in place of a maximum: recording. */
    public static final int RECORDING = 0;
    /** Not recording: the network's series limit is reached. */
    public static final int LIMIT = 1;
    /** Not recording: no readable value is set. */
    public static final int UNSET = 2;

    private static final String SAVE_KEY = "chart_history";
    private static final String SAVE_VALUE_KEY = "chart_history_value";

    private final ScreenWorker.Host host;
    private final Runnable recorded;
    private @Nullable ValueRing ring;
    /** The reference the ring records, as typed. */
    private String ringValue = "";
    /** The settings the parsed value was made for, and the value. */
    private @Nullable ChartSettings parsedFor;
    private @Nullable ChartValue value;
    private @Nullable HistoryClaims claimedOn;
    private int state = UNSET;
    private int samples;

    /** A recorder for {@code host}'s chart; {@code recorded} runs after each sample. */
    ChartRecorder(ScreenWorker.Host host, Runnable recorded) {
        this.host = host;
        this.recorded = recorded;
    }

    /** A chart keeps recording while load shedding pauses its display; only the screen stops updating (M7L2). */
    @Override
    public boolean runsWhileShed() {
        return true;
    }

    @Override
    public int run(StorageTransfer transfer, long gameTime) {
        if (host.isRemoved()) {
            release();
            return DONE;
        }
        DisplaySettings settings = host.settings();
        ChartValue current = settings.source() == DisplaySourceKind.HISTORY ? value(settings.chart()) : null;
        if (current == null) {
            release();
            setState(UNSET);
            // Woken by a settings change.
            return WAIT;
        }
        int interval = interval(settings.chart());
        ValueRing record = ring(settings.chart().value(), interval);
        NetworkState network = transfer.network();
        if (!claim(network)) {
            setState(LIMIT);
            return interval;
        }
        setState(RECORDING);
        if (!record.due(gameTime)) {
            return record.ticksUntilDue(gameTime);
        }
        record.record(gameTime, current.read(network));
        samples++;
        host.recordChanged();
        recorded.run();
        return interval;
    }

    /** Viewers hear of a new state at once. */
    private void setState(int next) {
        if (next != state) {
            state = next;
            recorded.run();
        }
    }

    /** The interval samples are taken at: the chart's, but never below {@code history.minIntervalTicks}. */
    static int interval(ChartSettings chart) {
        return Math.max(chart.intervalTicks(), StorageServerConfig.HISTORY_MIN_INTERVAL_TICKS.getAsInt());
    }

    // Settings are replaced, never changed, so identity says whether the value was parsed for these.
    @SuppressWarnings("ReferenceEquality")
    private @Nullable ChartValue value(ChartSettings chart) {
        if (chart != parsedFor) {
            parsedFor = chart;
            Optional<ChartValue> parsed = ChartValue.parse(chart.value());
            value = parsed.orElse(null);
        }
        return value;
    }

    /** The ring for {@code reference} at {@code interval}; a new one when either changed. */
    private ValueRing ring(String reference, int interval) {
        ValueRing current = ring;
        if (current == null || current.interval() != interval || !ringValue.equals(reference)) {
            current = new ValueRing(interval);
            ring = current;
            ringValue = reference;
            host.recordChanged();
        }
        return current;
    }

    private boolean claim(NetworkState network) {
        HistoryClaims claims = network.historyClaims();
        HistoryClaims previous = claimedOn;
        if (previous != null && previous != claims) {
            previous.release(this);
        }
        claimedOn = claims;
        return claims.claim(this, 1, StorageServerConfig.HISTORY_MAX_SERIES.getAsInt()) == 1;
    }

    /**
     * Drops the record from memory, for a panel that stopped being its wall's anchor: the new anchor records the wall
     * from now on, and a non-anchor saves no record, so the old ring would only hold memory.
     */
    void clear() {
        release();
        ring = null;
        ringValue = "";
        parsedFor = null;
        value = null;
        state = UNSET;
    }

    /** Gives back the screen's series, if it holds one. */
    void release() {
        HistoryClaims current = claimedOn;
        if (current != null) {
            current.release(this);
            claimedOn = null;
        }
    }

    // The claims are compared by identity: each network has its own.
    @SuppressWarnings("ReferenceEquality")
    @Override
    public boolean stillClaims(HistoryClaims claims) {
        return !host.isRemoved() && claimedOn == claims && host.settings().source() == DisplaySourceKind.HISTORY;
    }

    /** {@link #RECORDING}, {@link #LIMIT} or {@link #UNSET}. */
    public int state() {
        return state;
    }

    /** The recorded ring, or null before the first sample of the current value. */
    public @Nullable ValueRing ring() {
        return ring;
    }

    /** Samples taken since load; for tests. */
    public int samples() {
        return samples;
    }

    /**
     * Writes the chart of the last {@code chart.windowTicks()} into {@code out} (at most {@code out.length} points) and
     * returns the number of points; 0 without a record.
     */
    int chart(ChartSettings chart, long[] out) {
        ValueRing current = ring;
        if (current == null || current.size() == 0 || !ringValue.equals(chart.value())) {
            return 0;
        }
        return current.chart(chart.windowTicks(), out.length, out);
    }

    /** Saves the ring with the reference it records; nothing without one. */
    void save(ValueOutput output) {
        ValueRing current = ring;
        if (current != null && current.size() > 0) {
            output.store(SAVE_KEY, ValueRing.CODEC, current);
            output.putString(SAVE_VALUE_KEY, ringValue);
        }
    }

    /** Loads the ring {@link #save} wrote; a ring for another value or interval is dropped on the next sample. */
    void load(ValueInput input) {
        ring = input.read(SAVE_KEY, ValueRing.CODEC).orElse(null);
        ringValue = input.getStringOr(SAVE_VALUE_KEY, "");
        parsedFor = null;
    }
}
