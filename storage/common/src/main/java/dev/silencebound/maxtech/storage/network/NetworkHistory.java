package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.api.resource.StackKey;

/**
 * A network's recorded history (PRD 7.2; a controller's telemetry recorder keeps it): one sample every
 * {@link #sampleTicks()} ticks of a fixed set of {@link Series}, plus the stored amount of each key a reader asked for
 * with {@link #track}, in ring buffers of at most {@link #capacity()} samples. Display panels read it through
 * {@link NetworkState#history()}. Samples are taken on a cadence, never per operation, and are not saved: the history
 * starts again when the controller loads. Server thread only.
 */
public interface NetworkHistory {
    /** What every sample records. */
    enum Series {
        /** Device and player operations during the sample's interval. */
        OPERATIONS,
        /** FE that came into the network's store during the interval ({@link NetworkState#energyReceived()}). */
        ENERGY_IN,
        /** FE operations paid during the interval. */
        ENERGY_OUT,
        /** FE stored at the sample. */
        ENERGY_STORED,
        /** FE the network can store at the sample. */
        ENERGY_CAPACITY,
        /** Items stored at the sample. */
        ITEMS,
        /** mB of fluid stored at the sample. */
        FLUID_MB,
        /** Kinds (keys) stored at the sample. */
        KINDS,
        /** Bytes the storage cells use at the sample. */
        USED_BYTES,
        /** Bytes the storage cells hold at most at the sample. */
        TOTAL_BYTES
    }

    /** Ticks between two samples. */
    int sampleTicks();

    /** Most samples kept; older ones are overwritten. */
    int capacity();

    /** Samples recorded so far, at most {@link #capacity()}. */
    int size();

    /** Game time of the newest sample; {@code Long.MIN_VALUE} before the first. */
    long lastSampleTime();

    /**
     * Copies the recorded samples of {@code series}, oldest first, into {@code out} (the newest ones when {@code out}
     * is shorter) and returns how many it wrote. Allocates nothing.
     */
    int read(Series series, long[] out);

    /**
     * Starts recording {@code key}'s stored amount from the next sample on, or keeps recording it when it is tracked
     * already; returns false when the recorder already tracks its most keys (then nothing changes). A key nobody asks
     * for again (this or {@link #readKey}) for a while is released, so readers need not untrack it.
     */
    boolean track(StackKey key);

    /** Stops recording {@code key} at once and drops its history. */
    void untrack(StackKey key);

    /**
     * Copies {@code key}'s recorded amounts like {@link #read}; samples taken before it was tracked read as -1. Returns
     * 0 for a key nobody tracks. Counts as asking for the key, which keeps it tracked.
     */
    int readKey(StackKey key, long[] out);
}
