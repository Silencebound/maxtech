package dev.silencebound.maxtech.storage.controller.module;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The ring buffers behind a {@link TelemetryRecorder}: one ring per series and one per tracked key, all
 * {@code capacity} samples long and allocated once. Generic over the key so the ring and key-slot logic can be tested
 * without Minecraft's registries. Server thread only.
 *
 * <p>
 * A key stays tracked while readers keep asking for it ({@link #track} or {@link #readKey}); one nobody asked for in
 * {@code idleSamples} samples is released when the next sample starts, so the key slots never fill up for good.
 *
 * @param <K>
 *            the key type
 */
final class HistoryRings<K> {
    private final int capacity;
    private final int idleSamples;
    private final long[][] series;
    private final long[][] keyData;
    private final @Nullable Object[] keys;
    /** The sample count when each slot's key was last asked for. */
    private final long[] lastAsked;
    private final Object2IntOpenHashMap<K> slots = new Object2IntOpenHashMap<>();
    /** Where the next sample goes. */
    private int head;
    private int size;
    /** Samples recorded since construction. */
    private long samples;

    HistoryRings(int seriesCount, int capacity, int maxKeys, int idleSamples) {
        this.capacity = Math.max(1, capacity);
        this.idleSamples = Math.max(1, idleSamples);
        series = new long[seriesCount][this.capacity];
        int keyCount = Math.max(0, maxKeys);
        keyData = new long[keyCount][this.capacity];
        keys = new Object[keyCount];
        lastAsked = new long[keyCount];
        slots.defaultReturnValue(-1);
    }

    int capacity() {
        return capacity;
    }

    int maxKeys() {
        return keys.length;
    }

    int seriesCount() {
        return series.length;
    }

    int size() {
        return size;
    }

    int trackedKeys() {
        return slots.size();
    }

    /** Starts a sample: releases the keys nobody asked for in the idle window. */
    void begin() {
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != null && samples - lastAsked[slot] >= idleSamples) {
                release(slot);
            }
        }
    }

    /** Sets {@code series}'s value of the sample being recorded. */
    void put(int seriesIndex, long value) {
        series[seriesIndex][head] = value;
    }

    /** The key in {@code slot}, or null for a free slot; for filling the sample's key values. */
    @SuppressWarnings("unchecked")
    @Nullable
    K keyAt(int slot) {
        return (K) keys[slot];
    }

    /** Sets the tracked key value in {@code slot} of the sample being recorded. */
    void putKey(int slot, long value) {
        keyData[slot][head] = value;
    }

    /** Ends the sample: it becomes the newest. */
    void advance() {
        head = (head + 1) % capacity;
        size = Math.min(capacity, size + 1);
        samples++;
    }

    /** Copies {@code seriesIndex}'s samples oldest first into {@code out}; see {@link #copy}. */
    int read(int seriesIndex, long[] out) {
        return copy(series[seriesIndex], out);
    }

    /**
     * Tracks {@code key} from the next sample on, or keeps it tracked; false when every slot is taken. Samples before
     * it read as -1.
     */
    boolean track(K key) {
        int slot = slots.getInt(key);
        if (slot >= 0) {
            lastAsked[slot] = samples;
            return true;
        }
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == null) {
                keys[i] = key;
                lastAsked[i] = samples;
                Arrays.fill(keyData[i], -1);
                slots.put(key, i);
                return true;
            }
        }
        return false;
    }

    /** Releases {@code key} at once. */
    void untrack(K key) {
        int slot = slots.getInt(key);
        if (slot >= 0) {
            release(slot);
        }
    }

    /** Copies {@code key}'s samples like {@link #read} and counts as asking for it; 0 for an untracked key. */
    int readKey(K key, long[] out) {
        int slot = slots.getInt(key);
        if (slot < 0) {
            return 0;
        }
        lastAsked[slot] = samples;
        return copy(keyData[slot], out);
    }

    private void release(int slot) {
        Object key = keys[slot];
        keys[slot] = null;
        slots.removeInt(key);
    }

    /** Copies the ring {@code values} oldest first into {@code out}, the newest ones when it is short. */
    private int copy(long[] values, long[] out) {
        int count = Math.min(size, out.length);
        int start = Math.floorMod(head - count, capacity);
        for (int i = 0; i < count; i++) {
            out[i] = values[(start + i) % capacity];
        }
        return count;
    }
}
