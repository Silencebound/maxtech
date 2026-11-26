package dev.silencebound.maxtech.storage.controller.module;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkState;

/**
 * A controller's telemetry history (PRD 7.2, Controller; M6c2 telemetry recorder, built into the controller since M7R
 * item 3): fixed-size ring buffers ({@link HistoryRings}), one per {@link NetworkHistory.Series} and one per tracked
 * key, filled by {@link #sample} on the controller's cadence. Memory is bounded by the config at construction:
 * ({@code series} + {@code maxKeys}) x {@code capacity} longs, allocated once. Sampling walks the tracked keys and the
 * network's providers (for the byte counts), nothing else. Server thread only.
 */
public final class TelemetryRecorder implements NetworkHistory {
    /**
     * Samples after which a key nobody asked for is released (10 minutes at the default 200-tick interval). Display
     * screens ask when a player starts seeing them, so a key stays while players keep looking within this window; a
     * shorter window frees slots for new panels sooner, a longer one keeps more history for screens looked at rarely.
     */
    public static final int KEY_IDLE_SAMPLES = 60;
    private static final int SERIES = Series.values().length;

    private final int sampleTicks;
    private final HistoryRings<StackKey> rings;
    private long lastTime = Long.MIN_VALUE;
    private long taken;
    private long lastOperations = -1;
    private long lastSpent;
    private long lastReceived;

    public TelemetryRecorder(int sampleTicks, int capacity, int maxKeys) {
        this.sampleTicks = Math.max(1, sampleTicks);
        this.rings = new HistoryRings<>(SERIES, capacity, maxKeys, KEY_IDLE_SAMPLES);
    }

    /** Whether this recorder was made with these numbers; a config change makes a new one. */
    public boolean matches(int sampleTicks, int capacity, int maxKeys) {
        return this.sampleTicks == Math.max(1, sampleTicks) && rings.capacity() == Math.max(1, capacity)
                && rings.maxKeys() == Math.max(0, maxKeys);
    }

    /** The longs the buffers hold, for the memory bound; fixed at construction. */
    public long bufferLongs() {
        return (long) (rings.seriesCount() + rings.maxKeys()) * rings.capacity();
    }

    /**
     * Records one sample of {@code network} at {@code gameTime}: the interval's operations and FE in and out (0 for the
     * first sample, which has no interval), and the stored FE, items, fluid, kinds, bytes and tracked keys now. Keys
     * nobody asked for in {@link #KEY_IDLE_SAMPLES} samples are released first.
     */
    public void sample(NetworkState network, long gameTime) {
        long operations = network.operations();
        long spent = network.energySpent();
        long received = network.energyReceived();
        long stored = network.storedEnergy();
        boolean first = lastOperations < 0 || operations < lastOperations || spent < lastSpent
                || received < lastReceived;
        // A merge or split starts the network's running totals again; that interval reads 0.
        long ops = first ? 0 : operations - lastOperations;
        long out = first ? 0 : spent - lastSpent;
        long in = first ? 0 : received - lastReceived;
        lastOperations = operations;
        lastSpent = spent;
        lastReceived = received;
        rings.begin();
        rings.put(index(Series.OPERATIONS), ops);
        rings.put(index(Series.ENERGY_IN), in);
        rings.put(index(Series.ENERGY_OUT), out);
        rings.put(index(Series.ENERGY_STORED), stored);
        rings.put(index(Series.ENERGY_CAPACITY), network.energyCapacity());
        rings.put(index(Series.ITEMS), network.storedItems());
        rings.put(index(Series.FLUID_MB), network.storedFluidMb());
        rings.put(index(Series.KINDS), network.contents().size());
        rings.put(index(Series.USED_BYTES), network.usedBytes());
        rings.put(index(Series.TOTAL_BYTES), network.totalBytes());
        for (int slot = 0; slot < rings.maxKeys(); slot++) {
            StackKey key = rings.keyAt(slot);
            if (key != null) {
                rings.putKey(slot, network.contents().amount(key));
            }
        }
        rings.advance();
        lastTime = gameTime;
        taken++;
    }

    /** Samples taken since the recorder was made; tests tell the sampling runs from other scheduler runs with it. */
    public long samplesTaken() {
        return taken;
    }

    @Override
    public int sampleTicks() {
        return sampleTicks;
    }

    @Override
    public int capacity() {
        return rings.capacity();
    }

    @Override
    public int size() {
        return rings.size();
    }

    @Override
    public long lastSampleTime() {
        return lastTime;
    }

    @Override
    public int read(Series series, long[] out) {
        return rings.read(index(series), out);
    }

    @Override
    public boolean track(StackKey key) {
        return rings.track(key);
    }

    @Override
    public void untrack(StackKey key) {
        rings.untrack(key);
    }

    @Override
    public int readKey(StackKey key, long[] out) {
        return rings.readKey(key, out);
    }

    /** The ring of {@code series}. */
    private static int index(Series series) {
        return switch (series) {
            case OPERATIONS -> 0;
            case ENERGY_IN -> 1;
            case ENERGY_OUT -> 2;
            case ENERGY_STORED -> 3;
            case ENERGY_CAPACITY -> 4;
            case ITEMS -> 5;
            case FLUID_MB -> 6;
            case KINDS -> 7;
            case USED_BYTES -> 8;
            case TOTAL_BYTES -> 9;
        };
    }

    /** Tracked keys now. */
    public int trackedKeys() {
        return rings.trackedKeys();
    }
}
