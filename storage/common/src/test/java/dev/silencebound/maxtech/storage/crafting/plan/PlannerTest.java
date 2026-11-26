package dev.silencebound.maxtech.storage.crafting.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlannerTest {
    private final Object2LongOpenHashMap<String> stock = new Object2LongOpenHashMap<>();
    private final Map<String, List<Planner.Pattern<String>>> patterns = new HashMap<>();
    private int nextId;

    private static Planner.Input<String> in(long amount, String... candidates) {
        return new Planner.Input<>(List.of(candidates), amount, false);
    }

    private void pattern(String output, long amount, float chance, List<Planner.Remainder<String>> remainders,
            List<Planner.Input<String>> inputs) {
        patterns.computeIfAbsent(output, k -> new ArrayList<>())
                .add(new Planner.Pattern<>(nextId++, output, amount, chance, inputs, remainders));
    }

    private void pattern(String output, long amount, List<Planner.Input<String>> inputs) {
        pattern(output, amount, 1f, List.of(), inputs);
    }

    private PlanTree<String> plan(String key, long amount) {
        return plan(key, amount, 100_000, 4);
    }

    private PlanTree<String> plan(String key, long amount, int nodeCap, int chanceCap) {
        return new Planner<>(stock, k -> patterns.getOrDefault(k, List.of()),
                new Planner.Limits(nodeCap, Long.MAX_VALUE, chanceCap, System::nanoTime)).plan(key, amount);
    }

    private static PlanTree.Step<String> step(PlanTree<String> tree, String key) {
        for (PlanTree.Step<String> step : tree.steps()) {
            if (step.key().equals(key)) {
                return step;
            }
        }
        throw new AssertionError("no step for " + key);
    }

    @Test
    void takesStockFirstThenCrafts() {
        stock.put("planks", 2);
        stock.put("log", 10);
        pattern("planks", 4, List.of(in(1, "log")));
        PlanTree<String> tree = plan("planks", 10);
        assertTrue(tree.complete());
        PlanTree.Step<String> root = tree.steps().getFirst();
        assertEquals(2, root.stored());
        assertEquals(2, root.crafts(), "8 more planks need two crafts");
        assertEquals(8, root.toCraft());
        assertEquals(2, step(tree, "log").stored());
        assertEquals(2, tree.used().getLong("log"));
    }

    @Test
    void craftRootCraftsTheWholeAmount() {
        stock.put("planks", 2);
        stock.put("log", 10);
        pattern("planks", 4, List.of(in(1, "log")));
        PlanTree<String> tree = new Planner<>(stock, k -> patterns.getOrDefault(k, List.of()),
                new Planner.Limits(100_000, Long.MAX_VALUE, 4, System::nanoTime)).plan("planks", 8, true);
        PlanTree.Step<String> root = tree.steps().getFirst();
        assertEquals(0, root.stored(), "the stored planks stay where they are");
        assertEquals(2, root.crafts());
        assertEquals(8, root.toCraft());
        assertEquals(2, tree.used().getLong("log"), "deeper nodes still take from stock");
    }

    @Test
    void tagPicksTheMostPlentifulKey() {
        stock.put("oak", 3);
        stock.put("birch", 50);
        pattern("stick", 4, List.of(in(2, "oak", "birch")));
        PlanTree<String> tree = plan("stick", 4);
        assertEquals("birch", tree.steps().get(1).key());
        assertTrue(tree.complete());
    }

    @Test
    void tagFallsBackToACraftableCandidate() {
        stock.put("ore", 5);
        pattern("ingot", 1, List.of(in(1, "ore")));
        pattern("plate", 1, List.of(in(1, "rare_ingot", "ingot")));
        PlanTree<String> tree = plan("plate", 2);
        assertEquals("ingot", tree.steps().get(1).key(), "the craftable candidate when none is stored");
        assertTrue(tree.complete());
    }

    @Test
    void loopsRunOnlyFromStock() {
        pattern("a", 1, List.of(in(1, "b")));
        pattern("b", 1, List.of(in(1, "a")));
        PlanTree<String> empty = plan("a", 1);
        assertFalse(empty.complete());
        assertEquals(1, empty.missing().getLong("a"), "a loop with nothing stored ends in a shortfall");
        assertEquals(3, empty.steps().size());

        stock.put("a", 1);
        PlanTree<String> stocked = plan("a", 2);
        assertEquals(1, stocked.steps().getFirst().stored());
        assertEquals(1, stocked.missing().getLong("a"), "the loop's input was used up by the root");
    }

    @Test
    void returnedInputsCountOnce() {
        stock.put("hammer", 1);
        stock.put("ingot", 10);
        pattern("plate", 1, 1f, List.of(), List.of(new Planner.Input<>(List.of("hammer"), 1, true), in(1, "ingot")));
        PlanTree<String> tree = plan("plate", 10);
        assertTrue(tree.complete());
        assertEquals(1, step(tree, "hammer").needed());
        assertEquals(1, tree.used().getLong("hammer"), "the hammer is needed on hand while it is out");
    }

    @Test
    void usedCountsThePeakNotTheNetTake() {
        // A returned hammer nets to zero; if the plan reported net takes, losing the hammer before the job starts
        // would pass revalidation and the job would stall.
        stock.put("hammer", 1);
        stock.put("ingot", 2);
        pattern("plate", 1, 1f, List.of(), List.of(new Planner.Input<>(List.of("hammer"), 1, true), in(1, "ingot")));
        PlanTree<String> tree = plan("plate", 2);
        assertTrue(tree.complete());
        assertEquals(1, tree.used().getLong("hammer"));
        stock.put("hammer", 0);
        boolean stillThere = true;
        for (var entry : tree.used().object2LongEntrySet()) {
            stillThere &= stock.getLong(entry.getKey()) >= entry.getLongValue();
        }
        assertFalse(stillThere, "revalidation sees the hammer is gone");
    }

    @Test
    void remaindersComeBackForLaterSteps() {
        stock.put("water_bucket", 1);
        pattern("mud", 1, 1f, List.of(new Planner.Remainder<>("bucket", 1)), List.of(in(1, "water_bucket")));
        pattern("brick", 1, List.of(in(1, "mud"), in(1, "bucket")));
        PlanTree<String> tree = plan("brick", 1);
        assertTrue(tree.complete(), () -> "missing " + tree.missing());
        assertEquals(1, step(tree, "bucket").stored(), "the mud's bucket");
        assertFalse(tree.used().containsKey("bucket"));
    }

    @Test
    void surplusOfTheMainOutputIsReused() {
        stock.put("log", 1);
        pattern("planks", 4, List.of(in(1, "log")));
        pattern("pair", 1, List.of(in(1, "planks"), in(1, "planks")));
        PlanTree<String> tree = plan("pair", 1);
        assertTrue(tree.complete(), () -> "missing " + tree.missing());
        assertEquals(1, tree.used().getLong("log"), "one craft of planks covers both inputs");
        assertEquals(4, tree.steps().get(1).toCraft());
        assertEquals(1, tree.steps().get(3).stored(), "the second input from the first craft's surplus");
    }

    @Test
    void chanceRepeatsUpToTheCap() {
        stock.put("gravel", 1000);
        pattern("flint", 1, 0.25f, List.of(), List.of(in(1, "gravel")));
        PlanTree<String> tree = plan("flint", 10);
        assertEquals(40, tree.steps().getFirst().crafts(), "expected crafts for a 25% chance");
        PlanTree<String> capped = plan("flint", 10, 1000, 2);
        assertEquals(20, capped.steps().getFirst().crafts(), "capped at twice the guaranteed crafts");
    }

    @Test
    void partialReportNamesTheMissingLeaves() {
        stock.put("ore", 3);
        pattern("ingot", 1, List.of(in(1, "ore"), in(1, "coal")));
        PlanTree<String> tree = plan("ingot", 5);
        assertFalse(tree.complete());
        assertTrue(tree.failure().isEmpty());
        assertEquals(2, tree.missing().getLong("ore"));
        assertEquals(5, tree.missing().getLong("coal"));
    }

    @Test
    void secondPatternIsUsedWhenTheFirstFallsShort() {
        stock.put("sand", 4);
        pattern("glass", 1, List.of(in(1, "quartz")));
        pattern("glass", 1, List.of(in(1, "sand")));
        PlanTree<String> tree = plan("glass", 4);
        assertTrue(tree.complete());
        assertEquals("sand", tree.steps().get(1).key());
        assertEquals(2, tree.steps().size(), "the failed trial left no steps");
    }

    @Test
    void nodeCapFailsTheWholePlan() {
        pattern("a", 1, List.of(in(1, "b"), in(1, "c")));
        pattern("b", 1, List.of(in(1, "c"), in(1, "d")));
        PlanTree<String> tree = plan("a", 1, 3, 4);
        assertEquals(Optional.of(PlanTree.Failure.TOO_MANY_NODES), tree.failure());
        assertTrue(tree.steps().isEmpty());
    }

    @Test
    void timeLimitFailsTheWholePlan() {
        for (int i = 0; i < 600; i++) {
            pattern("k" + i, 1, List.of(in(1, "k" + (i + 1))));
        }
        long[] now = {0};
        PlanTree<String> tree = new Planner<>(stock, k -> patterns.getOrDefault(k, List.of()),
                new Planner.Limits(100_000, 10, 4, () -> now[0] += 100)).plan("k0", 1);
        assertEquals(Optional.of(PlanTree.Failure.TIME_LIMIT), tree.failure());
    }

    @Test
    void subTreesAreContiguous() {
        stock.put("ore", 100);
        pattern("ingot", 1, List.of(in(1, "ore")));
        pattern("plate", 1, List.of(in(2, "ingot")));
        pattern("gear", 1, List.of(in(2, "plate"), in(1, "ingot")));
        PlanTree<String> tree = plan("gear", 1);
        List<PlanTree.Step<String>> steps = tree.steps();
        assertEquals(steps.size(), steps.getFirst().size());
        for (int i = 0; i < steps.size(); i++) {
            PlanTree.Step<String> step = steps.get(i);
            for (int j = i + 1; j < i + step.size(); j++) {
                int ancestor = steps.get(j).parent();
                while (ancestor > i) {
                    ancestor = steps.get(ancestor).parent();
                }
                assertEquals(i, ancestor, "step " + j + " lies in the sub-tree of " + i);
            }
        }
    }
}
