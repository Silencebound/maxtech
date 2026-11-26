package dev.silencebound.maxtech.storage.crafting.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternOrderTest {
    /** A pattern as "name:copy"; two entries with the same name are the same pattern held twice. */
    private static List<String> order(List<String> patterns, Map<String, Integer> priorities) {
        return PatternOrder.order(patterns, p -> priorities.getOrDefault(p, 0), (a, b) -> name(a).equals(name(b)));
    }

    private static String name(String pattern) {
        int colon = pattern.indexOf(':');
        return colon < 0 ? pattern : pattern.substring(0, colon);
    }

    @Test
    void highestPriorityFirst() {
        assertEquals(List.of("c", "a", "b"), order(List.of("a", "b", "c"), Map.of("c", 5)));
        assertEquals(List.of("b", "c", "a"), order(List.of("a", "b", "c"), Map.of("a", -1, "b", 3, "c", 2)));
    }

    @Test
    void equalPrioritiesKeepTheNetworkOrder() {
        assertEquals(List.of("a", "b", "c"), order(List.of("a", "b", "c"), Map.of()));
    }

    @Test
    void eachPatternOnceWithItsHighestPriority() {
        // The same pattern in two providers: the copy with priority 4 stays, ahead of b.
        assertEquals(List.of("a:2", "b"), order(List.of("a:1", "b", "a:2"), Map.of("a:2", 4, "b", 1)));
        assertEquals(List.of("a:1", "b"), order(List.of("a:1", "a:2", "b"), Map.of()));
    }

    private static PlanTree<String> plan(Map<String, Long> stock, Map<String, List<Planner.Pattern<String>>> patterns,
            String key, long amount) {
        Object2LongOpenHashMap<String> snapshot = new Object2LongOpenHashMap<>();
        stock.forEach(snapshot::put);
        return new Planner<>(snapshot, k -> patterns.getOrDefault(k, List.of()),
                new Planner.Limits(1_000, Long.MAX_VALUE, 4, System::nanoTime)).plan(key, amount);
    }

    /** The planner falls back to the next pattern in this order when the first lacks materials. */
    @Test
    void plannerTakesTheFirstThatPlansInFull() {
        Map<String, Long> stock = Map.of("oak", 64L, "birch", 64L);
        Planner.Pattern<String> fromOak = new Planner.Pattern<>(0, "stick", 4, 1f,
                List.of(new Planner.Input<>(List.of("oak"), 2, false)), List.of());
        Planner.Pattern<String> fromBirch = new Planner.Pattern<>(1, "stick", 4, 1f,
                List.of(new Planner.Input<>(List.of("birch"), 2, false)), List.of());
        Map<Integer, Integer> priority = Map.of(1, 5);
        List<Planner.Pattern<String>> ordered = PatternOrder.order(List.of(fromOak, fromBirch),
                p -> priority.getOrDefault(p.id(), 0), (a, b) -> a.id() == b.id());
        assertEquals(1, plan(stock, Map.of("stick", ordered), "stick", 8).steps().getFirst().pattern());
        assertEquals(0, plan(Map.of("oak", 64L), Map.of("stick", ordered), "stick", 8).steps().getFirst().pattern());
    }
}
