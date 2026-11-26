package dev.silencebound.maxtech.storage.crafting.overview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Pattern conflicts for the pattern overview (M7C item 2): how many different patterns make each pattern's main output.
 * Equal patterns (one pattern held by several providers) count once. Called when a list arrives, never per frame. Pure;
 * any thread.
 */
public final class PatternConflicts {
    private PatternConflicts() {
    }

    /**
     * For each of {@code patterns}, how many different patterns in the list make the same output; 1 means no conflict.
     *
     * @param output
     *            a pattern's main output
     */
    public static <P, K> int[] counts(List<P> patterns, Function<P, K> output) {
        Map<K, List<P>> distinct = new HashMap<>();
        for (P pattern : patterns) {
            List<P> same = distinct.computeIfAbsent(output.apply(pattern), key -> new ArrayList<>());
            if (!same.contains(pattern)) {
                same.add(pattern);
            }
        }
        int[] counts = new int[patterns.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = Objects.requireNonNull(distinct.get(output.apply(patterns.get(i)))).size();
        }
        return counts;
    }
}
