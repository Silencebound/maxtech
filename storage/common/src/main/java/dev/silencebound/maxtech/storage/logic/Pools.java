package dev.silencebound.maxtech.storage.logic;

import dev.silencebound.maxtech.storage.crafting.provider.DispatchSplit;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;

/**
 * Machine pools (M7L2 row 17): the ports and interfaces sharing a label are one pool of machines, and a send shares
 * items out over its idle members by a strategy. Pure: the controller gathers the members, decides which are idle (a
 * sensor value on each member's machine against a condition) and moves the items; this class packs the values the nodes
 * read and plans a send, reusing the crafting dispatcher's split ({@link DispatchSplit}) for "spread evenly" and "least
 * full". Members are numbered in the controller's order from 0.
 */
public final class Pools {
    /** Strategy: everything to the first idle member, the rest to the next ones when it takes less. */
    public static final int FIRST_IDLE = 0;
    /** Strategy: everything to the next idle member after the one served last; the turn is saved. */
    public static final int ROUND_ROBIN = 1;
    /** Strategy: shares that level what the idle members hold of the item, the emptiest first. */
    public static final int LEAST_FULL = 2;
    /** Strategy: the amount split evenly over the idle members; the odd items go round in turn. */
    public static final int SPREAD_EVENLY = 3;

    /** Idle condition: the sensor value equals the limit. */
    public static final int IDLE_EQUAL = 0;
    /** Idle condition: the sensor value is below the limit. */
    public static final int IDLE_BELOW = 1;
    /** Idle condition: the sensor value is above the limit. */
    public static final int IDLE_ABOVE = 2;
    /** Largest idle limit either way, so a limit and its condition pack into one long. */
    public static final long MAX_IDLE_LIMIT = 1L << 40;

    private Pools() {
    }

    /** The idle condition {@code compare} ({@code IDLE_*}) with {@code limit}, as one number (a watch's number). */
    public static long packIdle(int compare, long limit) {
        return Math.clamp(limit, -MAX_IDLE_LIMIT, MAX_IDLE_LIMIT) * 4 + Math.clamp(compare, IDLE_EQUAL, IDLE_ABOVE);
    }

    /** The condition of a packed idle condition: {@link #IDLE_EQUAL}, {@link #IDLE_BELOW} or {@link #IDLE_ABOVE}. */
    public static int idleCompare(long packed) {
        return (int) Math.floorMod(packed, 4L);
    }

    /** The limit of a packed idle condition. */
    public static long idleLimit(long packed) {
        return Math.floorDiv(packed, 4L);
    }

    /** Whether a member whose sensor reads {@code value} is idle by the packed condition {@code idle}. */
    public static boolean idle(long value, long idle) {
        long limit = idleLimit(idle);
        return switch (idleCompare(idle)) {
            case IDLE_BELOW -> value < limit;
            case IDLE_ABOVE -> value > limit;
            default -> value == limit;
        };
    }

    /** A pool status watch's value: {@code members} and how many of them are {@code idle}. */
    public static long packStatus(int members, int idle) {
        return (long) Math.max(0, members) << 32 | Math.clamp(idle, 0, Math.max(0, members));
    }

    /** The members of a packed pool status. */
    public static int members(long status) {
        return (int) (status >>> 32);
    }

    /** The idle members of a packed pool status. */
    public static int idleMembers(long status) {
        return (int) (status & 0xFFFF_FFFFL);
    }

    /**
     * Where the next round-robin or spread-evenly send starts after one that served {@code served} first (-1: none):
     * the member after it. The other strategies keep {@code cursor}.
     */
    public static int nextCursor(int strategy, int served, int members, int cursor) {
        if ((strategy != ROUND_ROBIN && strategy != SPREAD_EVENLY) || served < 0 || members <= 0) {
            return cursor;
        }
        return (served + 1) % members;
    }

    /**
     * Plans sends over a pool, reusing its arrays: one per controller, grown when a pool is larger than any before, so
     * planning allocates nothing in the steady state. Server thread only.
     */
    public static final class Planner {
        private int[] order = new int[8];
        private long[] share = new long[8];
        private int[] load = new int[8];
        private int[] split = new int[8];
        private int count;

        /**
         * Plans a send of {@code amount} over {@code members} members by {@code strategy}. {@code candidate[i]} says
         * whether member i may take items (idle and allowed); {@code held[i]} what it holds of the item (only "least
         * full" reads it); {@code cursor} where round robin and spread evenly start. Afterwards {@link #count()}
         * candidates are in the order to serve them ({@link #order}), each with its {@link #share}; a member that takes
         * less than its share leaves the rest for the others in that order.
         */
        public void plan(int strategy, long amount, int members, boolean[] candidate, long[] held, int cursor) {
            grow(members);
            java.util.Arrays.fill(share, 0, members, 0);
            count = 0;
            int start = members <= 0 ? 0 : Math.floorMod(cursor, members);
            boolean rotate = strategy == ROUND_ROBIN || strategy == SPREAD_EVENLY;
            for (int j = 0; j < members; j++) {
                int i = rotate ? (start + j) % members : j;
                if (candidate[i]) {
                    order[count++] = i;
                }
            }
            if (count == 0 || amount <= 0) {
                return;
            }
            int total = (int) Math.min(amount, Integer.MAX_VALUE);
            switch (strategy) {
                case SPREAD_EVENLY -> {
                    DispatchSplit.split(DispatchStrategy.ROUND_ROBIN, total, count, load, null, split);
                    for (int j = 0; j < count; j++) {
                        share[order[j]] = split[j];
                    }
                }
                case LEAST_FULL -> {
                    // Emptiest first (ties in member order), then shares that level the amounts held.
                    for (int j = 1; j < count; j++) {
                        int moving = order[j];
                        int k = j - 1;
                        while (k >= 0 && held[order[k]] > held[moving]) {
                            order[k + 1] = order[k];
                            k--;
                        }
                        order[k + 1] = moving;
                    }
                    for (int j = 0; j < count; j++) {
                        load[j] = (int) Math.clamp(held[order[j]], 0, Integer.MAX_VALUE / 2);
                    }
                    DispatchSplit.split(DispatchStrategy.LOWEST_LOAD, total, count, load, null, split);
                    for (int j = 0; j < count; j++) {
                        share[order[j]] = split[j];
                    }
                }
                default -> share[order[0]] = total;
            }
        }

        /** How many members the last plan may serve. */
        public int count() {
            return count;
        }

        /** The member served {@code j}-th (0 to {@link #count()} - 1). */
        public int order(int j) {
            return order[j];
        }

        /** What the last plan gives member {@code member} at first; 0 for one it does not serve. */
        public long share(int member) {
            return share[member];
        }

        private void grow(int members) {
            if (members > order.length) {
                int size = Math.max(members, order.length * 2);
                order = new int[size];
                share = new long[size];
                load = new int[size];
                split = new int[size];
            }
        }
    }

    /**
     * Which members of a pool were idle at the last look, one bit each, for the turned-idle event. A look over a
     * different number of members than the last starts over: member numbers moved, so nothing counts as turned idle.
     * Allocates only when the member count changes.
     */
    public static final class IdleBits {
        private long[] bits = new long[0];
        private int members = -1;

        /**
         * Starts a look over {@code members} members. Returns whether the last look was over as many, so turns can be
         * counted; false starts over with every bit clear.
         */
        public boolean begin(int members) {
            if (members == this.members) {
                return true;
            }
            this.members = members;
            bits = new long[(Math.max(0, members) + 63) >>> 6];
            return false;
        }

        /** Records member {@code i} as idle or not; returns whether it turned idle since the last look. */
        public boolean set(int i, boolean idle) {
            long bit = 1L << (i & 63);
            boolean was = (bits[i >>> 6] & bit) != 0;
            bits[i >>> 6] = idle ? bits[i >>> 6] | bit : bits[i >>> 6] & ~bit;
            return idle && !was;
        }
    }
}
