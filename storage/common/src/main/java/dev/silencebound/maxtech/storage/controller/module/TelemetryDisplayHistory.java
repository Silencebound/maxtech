package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.display.DisplayHistory;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Feeds display screens their past samples from the network's telemetry recorder (area D's {@link DisplayHistory}). The
 * recorder samples every {@code sampleTicks}; a screen asks for samples {@code ticksPerSample} apart, so each asked
 * point takes the newest recorded sample at or before it, and points older than the history are left out. Rates are
 * turned into the screen's units (operations per second, FE per tick). An item count is only recorded once a screen
 * asks for it: the first ask starts tracking the key (until the recorder's key limit), so later asks find its history;
 * a screen players can see keeps asking ({@link #watched}), and a key no screen asks for within
 * {@link TelemetryRecorder#KEY_IDLE_SAMPLES} samples is released. Tag counts have no history. Server thread.
 */
public final class TelemetryDisplayHistory implements DisplayHistory {
    public static final TelemetryDisplayHistory INSTANCE = new TelemetryDisplayHistory();

    /** Scratch buffers reused across calls (server thread only), grown to the largest recorder seen. */
    private long[] values = new long[0];
    private long[] extra = new long[0];

    private TelemetryDisplayHistory() {
    }

    /**
     * Keeps a watched screen's item tracked: asks for the key and wants the next call within half the idle window, so
     * the key outlives any stretch in which the count does not change.
     */
    @Override
    public int watched(NetworkState network, DisplaySettings settings) {
        NetworkHistory history = network.history();
        if (history == null || settings.source() != DisplaySourceKind.ITEM_COUNT) {
            return 0;
        }
        StackKey key = settings.item().orElse(null);
        if (key == null || !history.track(key)) {
            return 0;
        }
        return history.sampleTicks() * (TelemetryRecorder.KEY_IDLE_SAMPLES / 2);
    }

    @Override
    public void fill(NetworkState network, DisplaySettings settings, int samples, int ticksPerSample, LongList out) {
        NetworkHistory history = network.history();
        if (history == null || history.size() == 0 || samples <= 0) {
            return;
        }
        if (values.length < history.capacity()) {
            values = new long[history.capacity()];
            extra = new long[history.capacity()];
        }
        long[] values = this.values;
        long[] extra = this.extra;
        int count = 0;
        switch (settings.source()) {
            case ITEM_COUNT -> {
                StackKey key = settings.item().orElse(null);
                if (key == null) {
                    return;
                }
                count = history.readKey(key, values);
                if (count == 0) {
                    history.track(key);
                    return;
                }
            }
            case THROUGHPUT -> count = history.read(NetworkHistory.Series.OPERATIONS, values);
            case POWER_STORED -> count = history.read(NetworkHistory.Series.ENERGY_STORED, values);
            case POWER_IN -> count = history.read(NetworkHistory.Series.ENERGY_IN, values);
            case POWER_OUT -> count = history.read(NetworkHistory.Series.ENERGY_OUT, values);
            case BYTES_USED -> count = history.read(NetworkHistory.Series.USED_BYTES, values);
            case BYTES_FREE -> {
                count = history.read(NetworkHistory.Series.USED_BYTES, values);
                history.read(NetworkHistory.Series.TOTAL_BYTES, extra);
            }
            // Tags, notices, text, charts and lists keep no telemetry series.
            default -> {
                return;
            }
        }
        int sampleTicks = history.sampleTicks();
        int step = Math.max(1, ticksPerSample);
        for (int i = 0; i < samples; i++) {
            // The asked point i lies (samples - 1 - i) steps before the newest sample.
            long back = (long) (samples - 1 - i) * step / sampleTicks;
            int index = (int) (count - 1 - back);
            if (index < 0) {
                continue;
            }
            long value = values[index];
            if (value < 0) {
                continue;
            }
            out.add(switch (settings.source()) {
                case THROUGHPUT -> value * 20 / sampleTicks;
                case POWER_IN, POWER_OUT -> value / sampleTicks;
                case BYTES_FREE -> extra[index] - value;
                default -> value;
            });
        }
    }
}
