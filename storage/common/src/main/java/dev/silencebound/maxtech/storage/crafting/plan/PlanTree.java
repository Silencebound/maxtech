package dev.silencebound.maxtech.storage.crafting.plan;

import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import java.util.List;
import java.util.Optional;

/**
 * A planned job tree: the steps in pre-order with parent links, so every sub-tree is the contiguous range
 * {@code [i, i + size)}. A v2 split of sub-trees across CPUs hands out such ranges without changing the format.
 * Immutable once built; safe on any thread.
 *
 * @param steps
 *            the nodes, the root first; empty when the plan failed
 * @param used
 *            what the plan needs on hand from the stock it was planned against, per key, at its peak (inputs returned
 *            or credited back later still count while they are out)
 * @param missing
 *            the shortfall per key, summed over the leaves; empty when everything can be made
 * @param failure
 *            why the plan failed as a whole, if it did
 * @param nodes
 *            nodes visited, failed trials included
 */
public record PlanTree<K>(List<Step<K>> steps, Object2LongMap<K> used, Object2LongMap<K> missing,
        Optional<Failure> failure, int nodes) {
    /** A step's pattern id for a node that crafts nothing: it takes from stock or is missing. */
    public static final int NO_PATTERN = -1;
    /**
     * A step's pattern id for a node a generator provider makes (M7C item 11): no inputs, one unit per craft, ordered
     * from the network's generators for the step's key instead of pushed to a pattern provider.
     */
    public static final int GENERATOR = -2;

    /** Whether {@code pattern}, a step's pattern id, makes something: a pattern or a generator. */
    public static boolean crafts(int pattern) {
        return pattern >= 0 || pattern == GENERATOR;
    }

    /** Why a plan failed as a whole. */
    public enum Failure {
        /** The tree has more nodes than {@code crafting.plannerNodeCap}. */
        TOO_MANY_NODES,
        /** Planning took longer than {@code crafting.plannerTimeLimitMs}. */
        TIME_LIMIT,
        /** Planning threw; the server log has the cause. */
        ERROR
    }

    /**
     * One node.
     *
     * @param parent
     *            the parent's index, -1 for the root
     * @param size
     *            nodes in this sub-tree, itself included
     * @param needed
     *            what the parent needs of {@code key}
     * @param stored
     *            what comes from stock
     * @param toCraft
     *            what this node's crafts make for the parent (surplus included for guaranteed outputs)
     * @param missing
     *            what can be neither taken nor crafted; only leaves have it
     * @param crafts
     *            how often the pattern runs
     * @param pattern
     *            the pattern's id, {@link #NO_PATTERN} for a node that crafts nothing, {@link #GENERATOR} for one a
     *            generator provider makes
     */
    public record Step<K>(int parent, int size, K key, long needed, long stored, long toCraft, long missing,
            long crafts, int pattern) {
    }

    static <K> PlanTree<K> of(List<Step<K>> steps, Object2LongMap<K> used, int nodes) {
        Object2LongLinkedOpenHashMap<K> missing = new Object2LongLinkedOpenHashMap<>();
        for (Step<K> step : steps) {
            if (step.missing() > 0) {
                missing.addTo(step.key(), step.missing());
            }
        }
        return new PlanTree<>(steps, Object2LongMaps.unmodifiable(used), Object2LongMaps.unmodifiable(missing),
                Optional.empty(), nodes);
    }

    /** A plan that failed as a whole with {@code failure}. */
    public static <K> PlanTree<K> failed(Failure failure, int nodes) {
        return new PlanTree<>(List.of(), Object2LongMaps.emptyMap(), Object2LongMaps.emptyMap(), Optional.of(failure),
                nodes);
    }

    /** Whether the whole request can be made: no error and nothing missing. */
    public boolean complete() {
        return failure.isEmpty() && missing.isEmpty();
    }
}
