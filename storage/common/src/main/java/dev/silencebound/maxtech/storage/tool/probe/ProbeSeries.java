package dev.silencebound.maxtech.storage.tool.probe;

/**
 * The samples a probe's graph shows: a fixed-size window that drops the oldest sample when full. A sample is a load
 * from 0 to 100, or {@link #NO_SIGNAL} when the probed block could not be read (unloaded, no network). Plain arrays, no
 * boxing; the screen reads it every frame.
 */
public final class ProbeSeries {
    /** A sample for a block that could not be read. */
    public static final int NO_SIGNAL = -1;

    private final int[] values;
    private int start;
    private int size;

    /** A window of {@code capacity} samples, at least 1. */
    public ProbeSeries(int capacity) {
        this.values = new int[Math.max(1, capacity)];
    }

    /** Samples a window of {@code seconds} holds at one sample every {@code sampleTicks} ticks, at least 1. */
    public static int capacity(int seconds, int sampleTicks) {
        return Math.max(1, (int) ((seconds * 20L) / Math.max(1, sampleTicks)));
    }

    /** Adds a sample, dropping the oldest when the window is full; a load is clamped to 0 to 100. */
    public void add(int sample) {
        int value = sample < 0 ? NO_SIGNAL : Math.min(100, sample);
        if (size < values.length) {
            values[(start + size) % values.length] = value;
            size++;
        } else {
            values[start] = value;
            start = (start + 1) % values.length;
        }
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return values.length;
    }

    /** Sample {@code index}, 0 the oldest still held. */
    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return values[(start + index) % values.length];
    }

    /** The newest sample, or {@link #NO_SIGNAL} when empty. */
    public int last() {
        return size == 0 ? NO_SIGNAL : get(size - 1);
    }

    /** The highest load in the window; 0 when empty or all samples have no signal. */
    public int peak() {
        int peak = 0;
        for (int i = 0; i < size; i++) {
            peak = Math.max(peak, get(i));
        }
        return peak;
    }
}
