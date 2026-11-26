package dev.silencebound.maxtech.storage.crafting.request;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sums a plan's or a job's per-step numbers per key, for the crafting screens' tile grid: one tile per key, in the
 * order the keys first appear (the planner's pre-order puts the request first). Four counts per key whose meaning is
 * the caller's: for a plan, taken from storage / to craft / 0 / missing; for a running job, held in the CPU / in flight
 * / still to send / waiting for. Pure; not thread safe.
 */
public final class TileSums<K> {
    /** One key's sums. */
    public record Sum<K>(K key, long stored, long crafting, long scheduled, long missing) {
        /** Whether every count is zero. */
        public boolean empty() {
            return stored == 0 && crafting == 0 && scheduled == 0 && missing == 0;
        }
    }

    private final Map<K, long[]> sums = new LinkedHashMap<>();

    /** Adds one step's (or one source's) counts for {@code key}; negative counts count as zero. */
    public void add(K key, long stored, long crafting, long scheduled, long missing) {
        long[] sum = sums.computeIfAbsent(key, k -> new long[4]);
        sum[0] = saturatedAdd(sum[0], stored);
        sum[1] = saturatedAdd(sum[1], crafting);
        sum[2] = saturatedAdd(sum[2], scheduled);
        sum[3] = saturatedAdd(sum[3], missing);
    }

    private static long saturatedAdd(long sum, long value) {
        if (value <= 0) {
            return sum;
        }
        long next = sum + value;
        return next < sum ? Long.MAX_VALUE : next;
    }

    /** The sums, at most {@code limit}, leaving out keys whose counts are all zero. */
    public List<Sum<K>> result(int limit) {
        List<Sum<K>> out = new ArrayList<>(Math.min(limit, sums.size()));
        for (Map.Entry<K, long[]> entry : sums.entrySet()) {
            if (out.size() >= limit) {
                break;
            }
            long[] sum = entry.getValue();
            Sum<K> tile = new Sum<>(entry.getKey(), sum[0], sum[1], sum[2], sum[3]);
            if (!tile.empty()) {
                out.add(tile);
            }
        }
        return out;
    }

    /**
     * Main output a running step still has to send to its provider: what it crafts less what the crafts it sent make
     * (the step's output per craft, rounded up), never below zero.
     */
    public static long scheduled(long toCraft, long crafts, long sent) {
        if (crafts <= 0 || toCraft <= 0) {
            return 0;
        }
        long perCraft = Math.max(1, Math.ceilDiv(toCraft, crafts));
        if (sent <= 0) {
            return toCraft;
        }
        if (sent >= crafts) {
            return 0;
        }
        return Math.max(0, toCraft - sent * perCraft);
    }
}
