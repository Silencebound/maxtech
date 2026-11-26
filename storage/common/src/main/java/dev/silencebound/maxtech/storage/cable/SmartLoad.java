package dev.silencebound.maxtech.storage.cable;

/**
 * How a smart cable shows load (M6d): the share of its route segment's channels in use, cut into four buckets. Only a
 * change of bucket reaches clients, so a busy network re-meshes a smart cable at most once per {@link #INTERVAL} ticks,
 * and a steady one never. Pure functions, any thread.
 */
public final class SmartLoad {
    /** Ticks between two reads of a network's smart cables; drawing only, so not a config value. */
    public static final int INTERVAL = 40;
    /** No operation in the last second: the line keeps the cable's own colour. */
    public static final int IDLE = 0;
    /** Under half the segment's channels in use. */
    public static final int LOW = 1;
    /** Half to 90 % in use. */
    public static final int BUSY = 2;
    /** 90 % or more in use. */
    public static final int SATURATED = 3;

    /** The line's colours for {@link #LOW}, {@link #BUSY} and {@link #SATURATED}: green, amber, red. */
    private static final int[] COLOURS = {0, 0x4FD06A, 0xE8B530, 0xE0473A};

    private SmartLoad() {
    }

    /** The bucket of a segment {@code percent} (0 to 100) busy. */
    public static int bucket(int percent) {
        if (percent <= 0) {
            return IDLE;
        }
        if (percent < 50) {
            return LOW;
        }
        return percent < 90 ? BUSY : SATURATED;
    }

    /**
     * The line's RGB for {@code bucket}, or {@code idle} (the cable's own colour) for {@link #IDLE} or a bucket out of
     * range.
     */
    public static int colour(int bucket, int idle) {
        return bucket > IDLE && bucket < COLOURS.length ? COLOURS[bucket] : idle;
    }

    /**
     * The lane brightness ({@link CableTierMarks#lineRgb}'s level, 1 to 4) a smart cable shows for {@code bucket}
     * instead of its tier's: dim while idle, brightest when saturated; a bucket out of range reads as idle.
     */
    public static int brightness(int bucket) {
        return bucket > IDLE && bucket < COLOURS.length ? bucket + 1 : 1;
    }
}
