package dev.silencebound.maxtech.storage.crafting.provider;

import org.jspecify.annotations.Nullable;

/**
 * The share of a batch each target gets under a {@link DispatchStrategy}. Pure arithmetic on arrays, so it is unit
 * tested on its own; {@link CraftingDispatcher} feeds it the providers' loads and durations. Targets are given in the
 * order round robin should serve them (longest since its last batch first).
 */
public final class DispatchSplit {
    private DispatchSplit() {
    }

    /**
     * Splits {@code crafts} over {@code load.length} targets.
     *
     * @param load
     *            crafts each target has in flight, at least 0
     * @param ticksPerCraft
     *            estimated ticks one craft takes at each target, at least 1 (only {@link DispatchStrategy#FASTEST}
     *            reads it)
     * @return crafts per target, summing to {@code crafts}; all zero when there are no targets or no crafts
     */
    public static int[] split(DispatchStrategy strategy, int crafts, int[] load, int[] ticksPerCraft) {
        int[] share = new int[load.length];
        split(strategy, crafts, load.length, load, ticksPerCraft, share);
        return share;
    }

    /**
     * {@link #split(DispatchStrategy, int, int[], int[])} over the first {@code n} targets of the arrays, writing the
     * shares into {@code share} (at least {@code n} long) and allocating nothing; logic's machine pools (M7L2) share
     * out a send this way. {@code ticksPerCraft} may be null unless the strategy is {@link DispatchStrategy#FASTEST}.
     */
    public static void split(DispatchStrategy strategy, int crafts, int n, int[] load, int @Nullable [] ticksPerCraft,
            int[] share) {
        java.util.Arrays.fill(share, 0, n, 0);
        if (n == 0 || crafts <= 0) {
            return;
        }
        switch (strategy) {
            case ROUND_ROBIN -> {
                int base = crafts / n;
                int extra = crafts % n;
                for (int i = 0; i < n; i++) {
                    share[i] = base + (i < extra ? 1 : 0);
                }
            }
            case LOWEST_LOAD -> level(crafts, n, load, null, share);
            case FASTEST -> level(crafts, n, load, ticksPerCraft, share);
        }
    }

    /**
     * Water-filling: finds the smallest finish time {@code T} at which the targets together can take {@code crafts},
     * each target i finishing its load plus its share by {@code T} ({@code (load + share) * ticks <= T}), then hands
     * out the shares; crafts left over by rounding go to the targets that finish soonest with one more, earliest first.
     */
    private static void level(int crafts, int n, int[] load, int @Nullable [] ticks, int[] share) {
        long low = 0;
        long high = 0;
        for (int i = 0; i < n; i++) {
            high = Math.max(high, (load[i] + (long) crafts) * tick(ticks, i));
        }
        while (low < high) {
            long mid = low + (high - low) / 2;
            if (capacity(mid, n, load, ticks) >= crafts) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        // Everything that finishes strictly before T, then the rest one at a time to whoever is done soonest.
        long given = 0;
        for (int i = 0; i < n; i++) {
            long fits = Math.max(0, (low - 1) / tick(ticks, i) - load[i]);
            share[i] = (int) Math.min(fits, crafts - given);
            given += share[i];
        }
        while (given < crafts) {
            int best = 0;
            long bestFinish = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                long finish = (load[i] + share[i] + 1L) * tick(ticks, i);
                if (finish < bestFinish) {
                    bestFinish = finish;
                    best = i;
                }
            }
            share[best]++;
            given++;
        }
    }

    private static long capacity(long time, int n, int[] load, int @Nullable [] ticks) {
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.max(0, time / tick(ticks, i) - load[i]);
        }
        return total;
    }

    private static long tick(int @Nullable [] ticks, int i) {
        return ticks == null ? 1 : Math.max(1, ticks[i]);
    }
}
