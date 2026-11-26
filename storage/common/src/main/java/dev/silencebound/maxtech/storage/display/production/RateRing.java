package dev.silencebound.maxtech.storage.display.production;

import java.util.Arrays;

/**
 * Amounts counted in fixed time buckets (one minute by default), the memory of one production-stats group: the last
 * {@link #buckets()} buckets in a ring, allocated once. Time only moves forward through {@link #add} and the readers,
 * which first zero the buckets that passed, so nothing runs while nothing happens. The rate is a sliding estimate over
 * the last minute: the bucket now plus the share of the previous one that is still inside the minute. Server thread
 * only.
 */
public final class RateRing {
    /** Ticks in a minute, the unit of every rate. */
    public static final long MINUTE = 1200;

    private final long bucketTicks;
    private final long[] values;
    /** The bucket number (time divided by the bucket length) the head is at; Long.MIN_VALUE before the first use. */
    private long index = Long.MIN_VALUE;

    public RateRing(long bucketTicks, int buckets) {
        this.bucketTicks = Math.max(1, bucketTicks);
        this.values = new long[Math.max(2, buckets)];
    }

    public int buckets() {
        return values.length;
    }

    /** Counts {@code amount} at game time {@code now}. */
    public void add(long amount, long now) {
        advance(now);
        values[slot(index)] += amount;
    }

    /** Amount per minute at {@code now}: the current bucket plus the previous one's share still inside the minute. */
    public long perMinute(long now) {
        advance(now);
        long elapsed = Math.floorMod(now, bucketTicks);
        long current = values[slot(index)];
        long previous = values[slot(index - 1)];
        long inWindow = current + previous * (bucketTicks - elapsed) / bucketTicks;
        return inWindow * MINUTE / bucketTicks;
    }

    /**
     * Copies the finished buckets, oldest first, as amounts per minute into {@code out} (the newest ones when it is
     * short) and returns how many it wrote: at most {@code buckets() - 1}, the bucket now is not finished.
     */
    public int history(long now, long[] out) {
        advance(now);
        int count = Math.min(out.length, values.length - 1);
        for (int i = 0; i < count; i++) {
            out[i] = values[slot(index - count + i)] * MINUTE / bucketTicks;
        }
        return count;
    }

    /** Whether every bucket is zero at {@code now}: the group did nothing for the whole window. */
    public boolean idle(long now) {
        advance(now);
        for (long value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private int slot(long bucket) {
        return (int) Math.floorMod(bucket, (long) values.length);
    }

    /** Moves the head to the bucket of {@code now}, zeroing the ones passed; time going back changes nothing. */
    private void advance(long now) {
        long bucket = Math.floorDiv(now, bucketTicks);
        if (index == Long.MIN_VALUE) {
            index = bucket;
            return;
        }
        if (bucket <= index) {
            return;
        }
        long passed = bucket - index;
        if (passed >= values.length) {
            Arrays.fill(values, 0);
        } else {
            for (long i = 1; i <= passed; i++) {
                values[slot(index + i)] = 0;
            }
        }
        index = bucket;
    }
}
