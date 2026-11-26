package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The read node types of batch 2: fluid amount, energy, free storage, crafting status and count by tag. Each reads its
 * values from watches; an empty network, a label no device carries and a tag with nothing in it all read 0 (the context
 * returns 0 for them), and nothing divides by zero.
 */
class ReadNodesTest {
    private static Watch<String> watch(CompiledGraph<String> graph, WatchKind kind) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == kind) {
                return watch;
            }
        }
        throw new AssertionError("No watch " + kind);
    }

    private static long out(LogicEvaluator<String> evaluator, CompiledGraph<String> graph, int node, String output) {
        return evaluator.value(graph.slot(node, output));
    }

    // ---- fluid amount and count by tag: the item count's watch

    @Test
    void fluidAmountReadsItsFluidThroughTheItemCountWatch() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.FLUID_AMOUNT, s -> s.withItem("fluid", "water").withText("label", "tanks"))
                .compiled();
        Watch<String> watch = watch(graph, WatchKind.ITEM_COUNT);
        assertEquals(Optional.of("water"), watch.item());
        assertEquals("tanks", watch.label());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, out(evaluator, graph, 1, "amount_mb"), "a label no device carries reads 0");
        context.set(WatchKind.ITEM_COUNT, "water", "tanks", "", 4_000);
        evaluator.changed(watch.index());
        evaluator.run(context, 1);
        assertEquals(4_000, out(evaluator, graph, 1, "amount_mb"));
    }

    @Test
    void fluidAmountTakesAFluidFromAConstant() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "item").withItem("item", "#c:water"))
                .node(2, NodeTypes.FLUID_AMOUNT).wire(1, "value", 2, "fluid").compiled();
        assertEquals(Optional.of("#c:water"), watch(graph, WatchKind.ITEM_COUNT).item());
    }

    @Test
    void fluidAmountNeedsAFluid() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.FLUID_AMOUNT).compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> "fluid".equals(error.port())), result.errors()::toString);
    }

    @Test
    void countByTagReadsItsTagAndATagWithNoItemsReadsZero() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.COUNT_BY_TAG, s -> s.withItem("tag", "#logs"))
                .node(2, NodeTypes.COUNT_BY_TAG, s -> s.withItem("tag", "#empty")).compiled();
        assertEquals(2, graph.watches().size());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("#logs", 96);
        evaluator.run(context, 0);
        assertEquals(96, out(evaluator, graph, 1, "count"));
        assertEquals(0, out(evaluator, graph, 2, "count"), "a tag with no items in the network");
    }

    @Test
    void countByTagSharesTheWatchOfAnItemCountOfTheSameTag() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.COUNT_BY_TAG, s -> s.withItem("tag", "#logs"))
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "#logs")).compiled();
        assertEquals(1, graph.watches().size(), "one subscription for both");
    }

    // ---- energy

    @Test
    void energyReadsStoredCapacityAndPercent() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ENERGY).compiled();
        assertEquals(2, graph.watches().size(), "stored and capacity");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, out(evaluator, graph, 1, "stored"), "an empty network");
        assertEquals(0, out(evaluator, graph, 1, "percent"), "no capacity is 0 percent, not a division by zero");
        context.set(WatchKind.ENERGY, "", "", "", 250).set(WatchKind.ENERGY_CAPACITY, "", "", "", 1_000);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(250, out(evaluator, graph, 1, "stored"));
        assertEquals(1_000, out(evaluator, graph, 1, "capacity"));
        assertEquals(25, out(evaluator, graph, 1, "percent"));
    }

    @Test
    void energyOfAMissingLabelReadsZero() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ENERGY, s -> s.withText("label", "bank"))
                .compiled();
        assertEquals("bank", watch(graph, WatchKind.ENERGY_CAPACITY).label(), "the capacity follows the label");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.ENERGY, "", "", "", 500);
        evaluator.run(context, 0);
        assertEquals(0, out(evaluator, graph, 1, "stored"), "the network's energy is not the label's");
        assertEquals(0, out(evaluator, graph, 1, "percent"));
    }

    @Test
    void energyChangeRerunsOnlyTheEnergyNode() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ENERGY).node(2, NodeTypes.CRAFT_STATUS)
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        context.reads = 0;
        context.set(WatchKind.ENERGY, "", "", "", 10);
        evaluator.changed(watch(graph, WatchKind.ENERGY).index());
        evaluator.run(context, 1);
        assertEquals(1, context.reads, "only the stored energy is read again");
        assertEquals(LogicEvaluator.READ_COST + NodeTypes.ENERGY.cost(), evaluator.lastCost(), "one read and one node");
    }

    @Test
    void percentIsExactAndNeverOverflows() {
        assertEquals(0, LogicEvaluator.percent(0, 0));
        assertEquals(0, LogicEvaluator.percent(5, 0));
        assertEquals(33, LogicEvaluator.percent(1, 3));
        assertEquals(99, LogicEvaluator.percent(999, 1_000));
        assertEquals(100, LogicEvaluator.percent(1_000, 1_000));
        assertEquals(100, LogicEvaluator.percent(2_000, 1_000), "never past full");
        long max = SettingSpec.MAX_NUMBER;
        assertEquals(50, LogicEvaluator.percent(max / 2, max));
        assertEquals(99, LogicEvaluator.percent(max - 1, max), "not full until it is");
        assertEquals(50, LogicEvaluator.percent(Long.MAX_VALUE / 2, Long.MAX_VALUE));
    }

    // ---- free storage

    @Test
    void freeSpaceReadsFreeTotalAndPercent() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.FREE_SPACE, s -> s.withText("label", "disks"))
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, out(evaluator, graph, 1, "free_bytes"), "no cells, or a label no device carries");
        assertEquals(0, out(evaluator, graph, 1, "percent"));
        context.set(WatchKind.BYTES_USED, "", "disks", "", 300).set(WatchKind.BYTES_TOTAL, "", "disks", "", 1_200);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(900, out(evaluator, graph, 1, "free_bytes"));
        assertEquals(1_200, out(evaluator, graph, 1, "total_bytes"));
        assertEquals(25, out(evaluator, graph, 1, "percent"));
        // Read a moment apart, used may briefly exceed the total: free never goes below 0.
        context.set(WatchKind.BYTES_USED, "", "disks", "", 1_500);
        evaluator.changed(watch(graph, WatchKind.BYTES_USED).index());
        evaluator.run(context, 2);
        assertEquals(0, out(evaluator, graph, 1, "free_bytes"));
        assertEquals(100, out(evaluator, graph, 1, "percent"));
    }

    // ---- crafting status

    @Test
    void craftStatusUnpacksRunningWaitingAndFreeCpus() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.CRAFT_STATUS, s -> s.withItem("item", "torch"))
                .node(2, NodeTypes.CRAFT_STATUS).compiled();
        assertEquals(Optional.of("torch"), watch(graph, WatchKind.CRAFT_JOBS).item());
        assertEquals(3, graph.watches().size(), "jobs for the torch, jobs for any item, one shared CPU watch");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, out(evaluator, graph, 1, "running"), "no jobs on an empty network");
        assertEquals(0, out(evaluator, graph, 1, "free_cpus"));
        context.set(WatchKind.CRAFT_JOBS, "torch", "", "", WatchKind.packCraft(3, true))
                .set(WatchKind.CRAFT_JOBS, "", "", "", WatchKind.packCraft(5, false))
                .set(WatchKind.CRAFT_CPUS, "", "", "", 2);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(1, out(evaluator, graph, 1, "running"));
        assertEquals(3, out(evaluator, graph, 1, "waiting"));
        assertEquals(2, out(evaluator, graph, 1, "free_cpus"));
        assertEquals(0, out(evaluator, graph, 2, "running"));
        assertEquals(5, out(evaluator, graph, 2, "waiting"));
        assertEquals(2, out(evaluator, graph, 2, "free_cpus"));
        assertEquals(0, out(evaluator, graph, 2, "auto_cpus"), "none packed for automation");
        context.set(WatchKind.CRAFT_CPUS, "", "", "", WatchKind.packCpus(3, 1));
        evaluator.everythingChanged();
        evaluator.run(context, 2);
        assertEquals(3, out(evaluator, graph, 1, "free_cpus"));
        assertEquals(1, out(evaluator, graph, 1, "auto_cpus"), "two of the three are kept for players");
    }

    @Test
    void craftStatusPackingKeepsLargeCounts() {
        assertEquals(1, WatchKind.packCraft(0, true));
        assertEquals((long) Integer.MAX_VALUE * 2, WatchKind.packCraft(Long.MAX_VALUE, false), "clamped, not wrapped");
        assertEquals(0, WatchKind.packCraft(-4, false));
    }

    // ---- shared

    @Test
    void sentencesNameTheirSettings() {
        java.util.Map<String, String> english = LogicLang.english();
        java.util.function.UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        assertEquals("the amount of water in tanks",
                NodeSentence.of(NodeTypes.FLUID_AMOUNT,
                        NodeSettings.<String>empty().withItem("fluid", "water").withText("label", "tanks"), lang,
                        item -> item));
        assertEquals("the count of #logs in Label", NodeSentence.of(NodeTypes.COUNT_BY_TAG,
                NodeSettings.<String>empty().withItem("tag", "#logs"), lang, item -> item));
        assertEquals("whether torch is being crafted", NodeSentence.of(NodeTypes.CRAFT_STATUS,
                NodeSettings.<String>empty().withItem("item", "torch"), lang, item -> item));
        assertEquals("the energy stored in bank", NodeSentence.of(NodeTypes.ENERGY,
                NodeSettings.<String>empty().withText("label", "bank"), lang, item -> item));
        assertEquals("the free bytes in Label",
                NodeSentence.of(NodeTypes.FREE_SPACE, NodeSettings.<String>empty(), lang, item -> item));
    }

    @Test
    void readsAreNotCommon() {
        for (NodeType type : List.of(NodeTypes.FLUID_AMOUNT, NodeTypes.ENERGY, NodeTypes.FREE_SPACE,
                NodeTypes.CRAFT_STATUS, NodeTypes.COUNT_BY_TAG)) {
            assertEquals(NodeCategory.READ, type.category(), type.id());
            assertTrue(!type.common(), type.id() + " stays out of the Common palette");
        }
    }

    @Test
    void runsOfTheReadNodesAllocateNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ENERGY).node(2, NodeTypes.FREE_SPACE)
                .node(3, NodeTypes.CRAFT_STATUS).node(4, NodeTypes.FLUID_AMOUNT, s -> s.withItem("fluid", "water"))
                .node(5, NodeTypes.COUNT_BY_TAG, s -> s.withItem("tag", "#logs")).compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        long[] value = new long[1];
        LogicContext<String> context = new FakeContext() {
            @Override
            public long read(Watch<String> watch) {
                return value[0] * (watch.index() + 1);
            }
        };
        for (int i = 0; i < 20_000; i++) {
            value[0] = i % 100;
            evaluator.everythingChanged();
            evaluator.run(context, i);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 20_000; i < 40_000; i++) {
            value[0] = i % 100;
            evaluator.everythingChanged();
            evaluator.run(context, i);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }
}
