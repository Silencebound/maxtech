package dev.silencebound.maxtech.storage.drive;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;

/**
 * The few largest amounts out of many, for a drive's overlay icons: offer every (key, amount) once, with amounts
 * already summed per key, and the lists keep the largest {@code limit}, largest first, ties in offer order. Pure; not
 * thread-safe.
 */
final class LargestKinds {
    private LargestKinds() {
    }

    /**
     * Offers {@code key} with {@code amount} to {@code keys} and {@code amounts} (parallel lists, sorted by amount,
     * largest first, at most {@code limit} long). Each key must be offered once.
     */
    static <K> void offer(List<K> keys, LongArrayList amounts, int limit, K key, long amount) {
        int at = keys.size();
        while (at > 0 && amounts.getLong(at - 1) < amount) {
            at--;
        }
        if (at >= limit) {
            return;
        }
        keys.add(at, key);
        amounts.add(at, amount);
        if (keys.size() > limit) {
            keys.removeLast();
            amounts.removeLong(amounts.size() - 1);
        }
    }
}
