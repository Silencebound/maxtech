package dev.silencebound.maxtech.storage.crafting.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Order lists (M7C item 6): several roots planned as one tree against one stock. */
class OrderPlanTest {
    private static final Map<String, List<Planner.Pattern<String>>> PATTERNS = Map.of("planks",
            List.of(new Planner.Pattern<>(0, "planks", 4, 1f, List.of(new Planner.Input<>(List.of("log"), 1, false)),
                    List.of())),
            "stick", List.of(new Planner.Pattern<>(1, "stick", 4, 1f,
                    List.of(new Planner.Input<>(List.of("planks"), 2, false)), List.of())));

    private static PlanTree<String> plan(long logs, List<String> keys, long[] amounts) {
        Object2LongOpenHashMap<String> stock = new Object2LongOpenHashMap<>();
        stock.put("log", logs);
        return new Planner<>(stock, k -> PATTERNS.getOrDefault(k, List.of()),
                new Planner.Limits(1_000, Long.MAX_VALUE, 4, System::nanoTime)).plan(keys, amounts, true);
    }

    @Test
    void rootsShareTheStock() {
        // 8 planks need 2 logs, 8 sticks need 4 planks, so 1 log more: 3 in all.
        PlanTree<String> enough = plan(3, List.of("planks", "stick"), new long[]{8, 8});
        assertTrue(enough.complete(), "complete: " + enough.missing());
        assertEquals(3, enough.used().getLong("log"));
        PlanTree<String> short1 = plan(2, List.of("planks", "stick"), new long[]{8, 8});
        assertFalse(short1.complete());
        assertEquals(1, short1.missing().getLong("log"));
    }

    @Test
    void oneRootPerRequestInOrder() {
        PlanTree<String> tree = plan(3, List.of("planks", "stick"), new long[]{8, 8});
        int roots = 0;
        int at = 0;
        while (at < tree.steps().size()) {
            PlanTree.Step<String> root = tree.steps().get(at);
            assertEquals(-1, root.parent());
            assertEquals(roots == 0 ? "planks" : "stick", root.key());
            assertEquals(0, root.stored(), "each root crafts its whole amount");
            roots++;
            at += root.size();
        }
        assertEquals(2, roots);
    }

    @Test
    void oneKeyOneAmount() {
        assertThrows(IllegalArgumentException.class, () -> plan(1, List.of("planks"), new long[]{1, 2}));
        assertThrows(IllegalArgumentException.class, () -> plan(1, List.of(), new long[0]));
    }
}
