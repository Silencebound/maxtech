package dev.silencebound.maxtech.storage.display.production;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

/**
 * Amounts per key over the last two minute buckets, for the top items in or out of a network: counting a move is one
 * map update, and a key that stops moving drops out within two minutes. At most {@link #MAX_KEYS} keys count per bucket
 * (moves of further keys are not counted until the next bucket), which bounds the memory. Generic over the key so the
 * logic is tested without Minecraft's registries. Server thread only.
 *
 * @param <K>
 *            the key type
 */
public final class KeyRates<K> {
    /** Most keys counted per bucket. */
    public static final int MAX_KEYS = 256;

    private final long bucketTicks;
    private Object2LongOpenHashMap<K> current = new Object2LongOpenHashMap<>();
    private Object2LongOpenHashMap<K> previous = new Object2LongOpenHashMap<>();
    private long index = Long.MIN_VALUE;

    public KeyRates(long bucketTicks) {
        this.bucketTicks = Math.max(1, bucketTicks);
    }

    /** Counts {@code amount} of {@code key} at game time {@code now}. */
    public void add(K key, long amount, long now) {
        advance(now);
        if (current.size() < MAX_KEYS || current.containsKey(key)) {
            current.addTo(key, amount);
        }
    }

    /** The key's rate per minute at {@code now}, the same sliding estimate as {@link RateRing#perMinute}. */
    public long perMinute(K key, long now) {
        advance(now);
        return estimate(current.getLong(key), previous.getLong(key), now);
    }

    /** The rate per minute of all keys together at {@code now}. */
    public long totalPerMinute(long now) {
        advance(now);
        long cur = 0;
        for (long value : current.values()) {
            cur += value;
        }
        long prev = 0;
        for (long value : previous.values()) {
            prev += value;
        }
        return estimate(cur, prev, now);
    }

    /**
     * Writes the {@code keys.length} keys with the highest rate (rates above zero only), the highest first, into
     * {@code keys} and their rates into {@code rates}; returns how many. Reading allocates only iterators.
     */
    public int top(long now, K[] keys, long[] rates) {
        advance(now);
        int found = 0;
        for (Object2LongMap.Entry<K> entry : current.object2LongEntrySet()) {
            found = offer(entry.getKey(), estimate(entry.getLongValue(), previous.getLong(entry.getKey()), now), keys,
                    rates, found);
        }
        for (Object2LongMap.Entry<K> entry : previous.object2LongEntrySet()) {
            if (!current.containsKey(entry.getKey())) {
                found = offer(entry.getKey(), estimate(0, entry.getLongValue(), now), keys, rates, found);
            }
        }
        return found;
    }

    private long estimate(long cur, long prev, long now) {
        long elapsed = Math.floorMod(now, bucketTicks);
        return (cur + prev * (bucketTicks - elapsed) / bucketTicks) * RateRing.MINUTE / bucketTicks;
    }

    /** Inserts into the sorted prefix of the output arrays when the rate is high enough; returns the new count. */
    private int offer(K key, long rate, K[] keys, long[] rates, int found) {
        if (rate <= 0) {
            return found;
        }
        int at = found;
        while (at > 0 && rates[at - 1] < rate) {
            at--;
        }
        if (at >= keys.length) {
            return found;
        }
        for (int i = Math.min(found, keys.length - 1); i > at; i--) {
            keys[i] = keys[i - 1];
            rates[i] = rates[i - 1];
        }
        keys[at] = key;
        rates[at] = rate;
        return Math.min(found + 1, keys.length);
    }

    private void advance(long now) {
        long bucket = Math.floorDiv(now, bucketTicks);
        if (index == Long.MIN_VALUE) {
            index = bucket;
            return;
        }
        if (bucket <= index) {
            return;
        }
        if (bucket == index + 1) {
            Object2LongOpenHashMap<K> old = previous;
            previous = current;
            current = old;
            current.clear();
        } else {
            current.clear();
            previous.clear();
        }
        index = bucket;
    }
}
