package dev.silencebound.maxtech.storage.crafting.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

/**
 * The order in which the planner tries the patterns making one item (M7C item 2): highest priority first, the network's
 * order among equal priorities, and each pattern once even when several providers hold it (the copy with the highest
 * priority stays). The planner takes the first pattern that plans without shortfall, so a lower priority pattern runs
 * only when the higher ones lack materials. Called once per key per plan, never per tick. Pure; any thread.
 */
public final class PatternOrder {
    private PatternOrder() {
    }

    /**
     * {@code patterns} in planning order; a new list.
     *
     * @param priority
     *            a pattern's priority
     * @param same
     *            whether two entries are the same pattern
     */
    public static <P> List<P> order(List<P> patterns, ToIntFunction<P> priority, BiPredicate<P, P> same) {
        List<P> sorted = new ArrayList<>(patterns);
        // List.sort is stable, so equal priorities keep the network's order.
        sorted.sort((a, b) -> Integer.compare(priority.applyAsInt(b), priority.applyAsInt(a)));
        List<P> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            P candidate = sorted.get(i);
            boolean listed = false;
            for (int j = 0; j < out.size() && !listed; j++) {
                listed = same.test(out.get(j), candidate);
            }
            if (!listed) {
                out.add(candidate);
            }
        }
        return out;
    }
}
