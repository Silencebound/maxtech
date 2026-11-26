package dev.silencebound.maxtech.storage.crafting.plan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The crafting planner (M7 C7, C8, C24 to C27): turns a request for {@code amount} of {@code key} into a job tree, from
 * a stock snapshot and the network's patterns. Generic over the key type so it is unit-tested without a game; in game
 * {@code K} is a {@code StackKey} ({@link CraftingPlanner}).
 *
 * <p>
 * Each node first takes what the (virtual) stock holds, then crafts the rest with the first pattern that plans without
 * shortfall (or the first pattern at all, so the report names what is missing). A pattern input lists candidate keys (a
 * tag's matching keys); the one the stock holds most of wins, else the first craftable one. A key already on the path
 * from the root is never crafted again, so loops run only from what is in stock. Only the main output counts; surplus
 * of a guaranteed output goes back to the virtual stock for later nodes. A chance output repeats its step until the
 * expected amount came out, at most {@code chanceRepeatCap} times the guaranteed crafts. Returned inputs are needed
 * once per step and come back afterwards; crafting remainders come back per craft. Every node counts against the node
 * cap, and the clock is checked every 256 nodes.
 *
 * <p>
 * Not thread-safe; one instance plans one request, on any thread.
 */
public final class Planner<K> {
    private static final int CLOCK_EVERY = 256;

    /** What the planner may spend. */
    public record Limits(int nodeCap, long deadlineNanos, int chanceRepeatCap, LongSupplier clock) {
    }

    /** One input of a pattern, per craft. */
    public record Input<K>(List<K> candidates, long amount, boolean returned) {
    }

    /** One remainder per craft: what comes back from an input (a bucket from a water bucket). */
    public record Remainder<K>(K key, long amount) {
    }

    /**
     * A pattern as the planner sees it.
     *
     * @param id
     *            the caller's index of the pattern, kept in the steps
     */
    public record Pattern<K>(int id, K output, long amount, float chance, List<Input<K>> inputs,
            List<Remainder<K>> remainders) {
    }

    /** Thrown when a limit is hit; the plan fails as a whole. */
    public static final class LimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final PlanTree.Failure failure;

        LimitException(PlanTree.Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        public PlanTree.Failure failure() {
            return failure;
        }
    }

    private final Object2LongMap<K> stock;
    private final Function<K, List<Pattern<K>>> patterns;
    private final Limits limits;
    /** Change of each key's virtual stock: credited minus taken. */
    private final Object2LongOpenHashMap<K> delta = new Object2LongOpenHashMap<>();
    /** Undo journal for {@link #delta}: key, previous value, as pairs. */
    private final ObjectArrayList<K> journalKeys = new ObjectArrayList<>();
    private final LongArrayList journalValues = new LongArrayList();
    /**
     * Deepest drawdown of each key below its stock so far: what the plan needs on hand at its peak. Net takes would
     * understate it, since returned inputs, remainders and surplus credit back what an earlier step took.
     */
    private final Object2LongOpenHashMap<K> peak = new Object2LongOpenHashMap<>();
    /** Undo journal for {@link #peak}, as for {@link #delta}. */
    private final ObjectArrayList<K> peakKeys = new ObjectArrayList<>();
    private final LongArrayList peakValues = new LongArrayList();
    private final ObjectArrayList<PlanTree.Step<K>> steps = new ObjectArrayList<>();
    private final ObjectOpenHashSet<K> path = new ObjectOpenHashSet<>();
    private int nodes;
    /** Whether the running plan's root crafts everything; see {@link #plan(Object, long, boolean)}. */
    private boolean craftRoot;

    /**
     * @param stock
     *            the stock snapshot; read only
     * @param patterns
     *            the patterns making a key as their main output, in preference order; empty for none
     */
    public Planner(Object2LongMap<K> stock, Function<K, List<Pattern<K>>> patterns, Limits limits) {
        this.stock = stock;
        this.patterns = patterns;
        this.limits = limits;
    }

    /** Plans {@code amount} of {@code key}; a hit limit gives a failed tree with no steps. */
    public PlanTree<K> plan(K key, long amount) {
        return plan(key, amount, false);
    }

    /**
     * Plans {@code amount} of {@code key}; with {@code craftRoot} the root crafts the whole amount instead of taking
     * what the stock holds of {@code key} first (background crafts that top a stock up, M7 C9 and C21). Deeper nodes
     * take from stock as usual.
     */
    public PlanTree<K> plan(K key, long amount, boolean craftRoot) {
        return plan(List.of(key), new long[]{amount}, craftRoot);
    }

    /**
     * Plans several requests as one tree (M7C item 6, an order list): {@code amounts[i]} of {@code keys.get(i)} for
     * each {@code i}, in order, against one virtual stock, so stock an earlier request takes is gone for a later one.
     * The tree has one root per request, each followed by its sub-tree. {@code craftRoot} applies to every root.
     */
    public PlanTree<K> plan(List<K> keys, long[] amounts, boolean craftRoot) {
        if (keys.isEmpty() || keys.size() != amounts.length) {
            throw new IllegalArgumentException("one amount per key, at least one key");
        }
        this.craftRoot = craftRoot;
        try {
            for (int i = 0; i < keys.size(); i++) {
                node(keys.get(i), amounts[i], -1);
            }
        } catch (LimitException e) {
            return PlanTree.failed(e.failure(), nodes);
        }
        Object2LongOpenHashMap<K> used = new Object2LongOpenHashMap<>();
        for (var entries = peak.object2LongEntrySet().fastIterator(); entries.hasNext();) {
            Object2LongMap.Entry<K> entry = entries.next();
            if (entry.getLongValue() > 0) {
                used.put(entry.getKey(), entry.getLongValue());
            }
        }
        return PlanTree.of(List.copyOf(steps), used, nodes);
    }

    private long available(K key) {
        return stock.getLong(key) + delta.getLong(key);
    }

    private void change(K key, long by) {
        journalKeys.add(key);
        journalValues.add(delta.getLong(key));
        long drawdown = -delta.addTo(key, by) - by;
        if (drawdown > peak.getLong(key)) {
            peakKeys.add(key);
            peakValues.add(peak.getLong(key));
            peak.put(key, drawdown);
        }
    }

    private void rollback(int journalMark, int peakMark, int stepMark) {
        for (int i = peakKeys.size() - 1; i >= peakMark; i--) {
            peak.put(peakKeys.get(i), peakValues.getLong(i));
        }
        peakKeys.size(peakMark);
        peakValues.size(peakMark);
        for (int i = journalKeys.size() - 1; i >= journalMark; i--) {
            delta.put(journalKeys.get(i), journalValues.getLong(i));
        }
        journalKeys.size(journalMark);
        journalValues.size(journalMark);
        steps.size(stepMark);
    }

    private void count() {
        nodes++;
        if (nodes > limits.nodeCap()) {
            throw new LimitException(PlanTree.Failure.TOO_MANY_NODES);
        }
        if (nodes % CLOCK_EVERY == 0 && limits.clock().getAsLong() > limits.deadlineNanos()) {
            throw new LimitException(PlanTree.Failure.TIME_LIMIT);
        }
    }

    /** Plans one node and its sub-tree; returns the missing count of the sub-tree. */
    private long node(K key, long needed, int parent) {
        count();
        int index = steps.size();
        long stored = parent == -1 && craftRoot ? 0 : Math.max(0, Math.min(needed, available(key)));
        if (stored > 0) {
            change(key, -stored);
        }
        long rest = needed - stored;
        steps.add(new PlanTree.Step<>(parent, 1, key, needed, stored, 0, 0, 0, -1));
        if (rest == 0) {
            return 0;
        }
        List<Pattern<K>> options = path.contains(key) ? List.of() : patterns.apply(key);
        if (options.isEmpty()) {
            steps.set(index, new PlanTree.Step<>(parent, 1, key, needed, stored, 0, rest, 0, -1));
            return rest;
        }
        path.add(key);
        long missing = -1;
        for (int i = 0; i < options.size() && missing != 0; i++) {
            int journalMark = journalKeys.size();
            int peakMark = peakKeys.size();
            missing = craft(options.get(i), key, needed, stored, rest, parent, index);
            if (missing != 0 && options.size() > 1) {
                // Try the next pattern; the last one is planned again below if none plans in full.
                rollback(journalMark, peakMark, index + 1);
                if (i == options.size() - 1) {
                    missing = craft(options.getFirst(), key, needed, stored, rest, parent, index);
                    break;
                }
            }
        }
        path.remove(key);
        return missing;
    }

    private long craft(Pattern<K> pattern, K key, long needed, long stored, long rest, int parent, int index) {
        long guaranteed = ceilDiv(rest, pattern.amount());
        long crafts = guaranteed;
        boolean chance = pattern.chance() < 1f;
        if (chance) {
            long expected = (long) Math.ceil(rest / (pattern.amount() * (double) pattern.chance()));
            crafts = Math.min(expected, saturatedMultiply(guaranteed, limits.chanceRepeatCap()));
        }
        long missing = 0;
        for (Input<K> input : pattern.inputs()) {
            long total = input.returned() ? input.amount() : saturatedMultiply(input.amount(), crafts);
            K pick = pick(input.candidates());
            missing += node(pick, total, index);
            if (input.returned()) {
                change(pick, input.amount());
            }
        }
        for (Remainder<K> remainder : pattern.remainders()) {
            change(remainder.key(), saturatedMultiply(remainder.amount(), crafts));
        }
        long made = chance ? rest : saturatedMultiply(crafts, pattern.amount());
        if (made > rest) {
            change(key, made - rest);
        }
        steps.set(index,
                new PlanTree.Step<>(parent, steps.size() - index, key, needed, stored, made, 0, crafts, pattern.id()));
        return missing;
    }

    /** The candidate the stock holds most of, else the first with a pattern, else the first. */
    private K pick(List<K> candidates) {
        K best = candidates.getFirst();
        long most = 0;
        for (int i = 0; i < candidates.size(); i++) {
            long amount = available(candidates.get(i));
            if (amount > most) {
                most = amount;
                best = candidates.get(i);
            }
        }
        if (most > 0) {
            return best;
        }
        for (int i = 0; i < candidates.size(); i++) {
            if (!path.contains(candidates.get(i)) && !patterns.apply(candidates.get(i)).isEmpty()) {
                return candidates.get(i);
            }
        }
        return best;
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }

    private static long saturatedMultiply(long a, long b) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        return (high == 0 && low >= 0) ? low : Long.MAX_VALUE;
    }
}
