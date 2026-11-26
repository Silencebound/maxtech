package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

/**
 * For each (M7L2 row 18): going through a tag, a list and a stock keeper's items, one pass per item with nothing
 * leaking from one item to the next, the run's item limit and the team's checks, the compile checks of the branch, the
 * order a For each runs in, its why line, card and sentences, the template, the text round trip and no allocation per
 * run.
 */
class ForEachNodesTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    private static String text(String key) {
        return ENGLISH.getOrDefault(key, key);
    }

    /** "When the graph starts, for each item of {@code source}: if its count is below 1,000, craft 64 of it." */
    private static TestGraph keepStocked(java.util.function.UnaryOperator<NodeSettings<String>> source) {
        return new TestGraph().node(1, NodeTypes.GRAPH_START).node(2, NodeTypes.FOR_EACH, source)
                .node(3, NodeTypes.ITEM_COUNT)
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 1_000))
                .node(5, NodeTypes.IF).node(6, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 64))
                .wire(1, "started", 2, "do").wire(2, "item", 3, "item").wire(3, "count", 4, "value")
                .wire(2, "each", 5, "when").wire(4, "result", 5, "condition").wire(5, "then", 6, "do")
                .wire(2, "item", 6, "item");
    }

    private static TestGraph oresGraph() {
        return keepStocked(s -> s.withText("source", "tag").withItem("tag", "#ores"));
    }

    private static FakeContext ores() {
        FakeContext context = new FakeContext().count("gold", 10).count("iron", 2_000).count("copper", 999);
        context.tags.put("#ores", List.of("gold", "iron", "copper"));
        return context;
    }

    private static long out(LogicEvaluator<String> evaluator, int node, String output) {
        return evaluator.value(evaluator.graph().slot(node, output));
    }

    /** The why line's entries as "reason a b". */
    private static List<String> why(LogicEvaluator<String> evaluator) {
        List<String> lines = new ArrayList<>();
        RunTrace trace = evaluator.trace();
        for (int i = 0; i < trace.size(); i++) {
            lines.add(trace.reason(i) + " " + trace.first(i) + " " + trace.second(i));
        }
        return lines;
    }

    // ---- going through the items

    @Test
    void aTagCraftsOnlyTheItemsUnderTheLimit() {
        CompiledGraph<String> graph = oresGraph().compiled();
        assertTrue(graph.watches().isEmpty(), "the branch's reads are not watched: " + graph.watches());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = ores();
        evaluator.run(context, 0);
        // Gold is under the limit before iron, which is not: iron's "no" must not undo gold's "then", nor gold's "then"
        // count for iron.
        assertEquals(List.of("craft gold 64", "craft copper 64"), context.take());
        assertEquals(3, out(evaluator, 2, "checked"));
        assertEquals(List.of(LogicLang.WHY_EACH + " 3 2"), why(evaluator), "one line for every pass");
        assertEquals(2, evaluator.lastActions());
        // Graph start, the For each and its tag; per item the count (with its read), compare, if and craft; and the
        // check for a running craft of gold and copper.
        int expected = 1 + 1 + LogicEvaluator.READ_COST + 3 * (1 + LogicEvaluator.READ_COST + 1 + 1 + 2)
                + 2 * LogicEvaluator.READ_COST;
        assertEquals(expected, evaluator.lastCost(), "each pass charges the nodes it runs");
    }

    @Test
    void aCompareFiresForEveryItemAlsoWhenItsCountIsTheLast() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "tag").withItem("tag", "#ores"))
                .node(3, NodeTypes.ITEM_COUNT)
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 100))
                .node(5, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 2)).wire(1, "started", 2, "do")
                .wire(2, "item", 3, "item").wire(3, "count", 4, "value").wire(4, "turned_yes", 5, "do")
                .wire(2, "item", 5, "item").compiled();
        FakeContext context = new FakeContext().count("gold", 10).count("iron", 10).count("copper", 500);
        context.tags.put("#ores", List.of("gold", "iron", "copper"));
        new LogicEvaluator<>(graph).run(context, 0);
        assertEquals(List.of("craft gold 2", "craft iron 2"), context.take());
    }

    @Test
    void aCraftRunningIsCheckedPerItem() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(oresGraph().compiled());
        FakeContext context = ores();
        context.runningCrafts.add("gold");
        evaluator.run(context, 0);
        assertEquals(List.of("craft copper 64"), context.take());
    }

    @Test
    void aListGoesThroughItsItemsOnceEachWithTagsStandingForTheirItems() {
        CompiledGraph<String> graph = keepStocked(s -> s.withText("source", "list").withNumber("items", 4)
                .withItem(ForEach.item(1), "gold").withItem(ForEach.item(2), "#ores").withItem(ForEach.item(4), "sand")
                .withItem(ForEach.item(5), "past the list")).compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = ores();
        evaluator.run(context, 0);
        assertEquals(List.of("craft gold 64", "craft copper 64", "craft sand 64"), context.take(),
                "gold once, though the tag holds it too; the fifth entry is past the list's size");
        assertEquals(4, out(evaluator, 2, "checked"), "gold, iron, copper, sand");
    }

    @Test
    void aStockKeeperGivesTheItemsOfItsRules() {
        CompiledGraph<String> graph = keepStocked(s -> s.withText("source", "stock_keeper").withText("label", "k"))
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = ores();
        context.keepers.put("k", List.of("iron", "copper", "iron"));
        evaluator.run(context, 0);
        assertEquals(List.of("craft copper 64"), context.take());
        assertEquals(2, out(evaluator, 2, "checked"), "iron counts once");
    }

    @Test
    void theStockStatusOfEachItemIsReadForIt() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "stock_keeper").withText("label", "k"))
                .node(3, NodeTypes.STOCK_STATUS, s -> s.withText("label", "k")).node(4, NodeTypes.IF)
                .node(5, NodeTypes.MOVE_ITEMS, s -> s.withText("to", "out").withNumber("amount", 8))
                .wire(1, "started", 2, "do").wire(2, "item", 3, "item").wire(2, "each", 4, "when")
                .wire(3, "below", 4, "condition").wire(4, "otherwise", 5, "do").wire(2, "item", 5, "item").compiled();
        FakeContext context = new FakeContext().count("iron", 50).count("gold", 50)
                .stock("iron", "k", WatchKind.STOCK_KEEP, 100).stock("iron", "k", WatchKind.STOCK_FLAGS, 1)
                .stock("gold", "k", WatchKind.STOCK_KEEP, 10).stock("gold", "k", WatchKind.STOCK_FLAGS, 1);
        context.keepers.put("k", List.of("iron", "gold"));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(List.of("move gold >out 8"), context.take(), "iron is below its keep, gold is not");
    }

    @Test
    void theRunsItemLimitStopsAndSaysSo() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(oresGraph().compiled());
        FakeContext context = ores();
        context.maxEach = 2;
        evaluator.run(context, 0);
        assertEquals(List.of("craft gold 64"), context.take(), "copper, the third item, is past the limit");
        assertEquals(2, out(evaluator, 2, "checked"));
        assertEquals(List.of(LogicLang.WHY_EACH + " 2 1", LogicLang.WHY_EACH_CAP + " 2 2"), why(evaluator));
    }

    @Test
    void theLimitCountsOverEveryForEachOfTheRun() {
        TestGraph graph = oresGraph()
                .node(7, NodeTypes.FOR_EACH, s -> s.withText("source", "tag").withItem("tag", "#ores"))
                .node(8, NodeTypes.NOTIFY, s -> s.withText("text", "seen")).wire(1, "started", 7, "do")
                .wire(7, "each", 8, "do");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = ores();
        context.maxEach = 4;
        evaluator.run(context, 0);
        assertEquals(3, out(evaluator, 2, "checked"));
        assertEquals(1, out(evaluator, 7, "checked"), "one item left for the second For each");
    }

    @Test
    void theTeamsChecksStopTheRun() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(oresGraph().compiled());
        FakeContext context = ores();
        // The For each and its tag cost 3; the first pass (gold, with its craft) 9 more.
        context.checksLeft = 10;
        evaluator.run(context, 0);
        assertEquals(List.of("craft gold 64"), context.take());
        assertEquals(1, out(evaluator, 2, "checked"));
        assertTrue(why(evaluator).contains(LogicLang.WHY_EACH_BUDGET + " 1 0"), why(evaluator).toString());
    }

    @Test
    void doneFiresOnceAfterEveryItem() {
        CompiledGraph<String> graph = oresGraph().node(7, NodeTypes.NOTIFY, s -> s.withText("text", "all checked"))
                .wire(2, "done", 7, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = ores();
        evaluator.run(context, 0);
        assertEquals(List.of("notify all checked 3", "craft gold 64", "craft copper 64"), context.take(),
                "the notice once, outside the passes");
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "nothing without the trigger");
    }

    @Test
    void anOutsideValueTheBranchReadsIsOfThisRun() {
        // The outside check comes after the For each in the graph's own order; it must still run first.
        TestGraph graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "list").withItem(ForEach.item(1), "gold"))
                .node(3, NodeTypes.AND).node(4, NodeTypes.IF)
                .node(5, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 1))
                .node(6, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "power"))
                .node(7, NodeTypes.COMPARE, s -> s.withNumber("threshold", 5))
                .node(8, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no")).wire(1, "started", 2, "do")
                .wire(8, "value", 3, "a").wire(7, "result", 3, "b").wire(2, "each", 4, "when")
                .wire(3, "result", 4, "condition").wire(4, "then", 5, "do").wire(2, "item", 5, "item")
                .wire(6, "count", 7, "value");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext().count("power", 10);
        evaluator.run(context, 0);
        assertEquals(List.of("craft gold 1"), context.take());
    }

    @Test
    void aBranchOutsideATriggerDoesNotRun() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(oresGraph().compiled());
        FakeContext context = ores();
        evaluator.run(context, 0);
        context.take();
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "a recheck is no trigger");
        assertEquals(List.of(), why(evaluator), "no pass ran");
        assertEquals(3, out(evaluator, 2, "checked"), "still the last run's count");
    }

    // ---- compile checks

    private static List<String> errors(TestGraph graph) {
        List<String> errors = new ArrayList<>();
        for (GraphError error : graph.compile().errors()) {
            errors.add(error.node() + " " + error.key() + (error.port().isEmpty() ? "" : " " + error.port()));
        }
        return errors;
    }

    @Test
    void theBranchTakesOnlyNodesThatKeepNoMemory() {
        TestGraph graph = oresGraph().node(7, NodeTypes.LATCH).wire(2, "each", 7, "set");
        assertEquals(List.of("7 " + LogicLang.EACH_NOT_HERE), errors(graph));
        TestGraph crossing = oresGraph().node(7, NodeTypes.COUNT_CROSSES).wire(2, "item", 7, "item");
        assertEquals(List.of("7 " + LogicLang.EACH_NOT_HERE), errors(crossing));
    }

    @Test
    void aForEachCannotSitInAnothersBranch() {
        TestGraph graph = oresGraph()
                .node(7, NodeTypes.FOR_EACH, s -> s.withText("source", "tag").withItem("tag", "#ores"))
                .wire(5, "then", 7, "do");
        assertEquals(List.of("7 " + LogicLang.EACH_NESTED), errors(graph));
    }

    @Test
    void aNodeCannotBeInTheBranchAndAfterDone() {
        TestGraph graph = oresGraph().node(7, NodeTypes.OR)
                .node(8, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no")).node(9, NodeTypes.COMPARE)
                .wire(4, "result", 7, "a").wire(2, "checked", 9, "value").wire(9, "result", 7, "b");
        List<String> errors = errors(graph);
        assertTrue(errors.contains("7 " + LogicLang.EACH_MIXED), errors.toString());
    }

    @Test
    void eachSourceNeedsWhatItGoesThrough() {
        assertEquals(List.of("2 " + LogicLang.FILL_IN + " tag"), errors(keepStocked(s -> s.withText("source", "tag"))));
        assertEquals(List.of("2 " + LogicLang.FILL_IN + " " + ForEach.item(1)), errors(keepStocked(s -> s
                .withText("source", "list").withNumber("items", 1).withItem(ForEach.item(2), "beyond the size"))));
        assertEquals(List.of("2 " + LogicLang.FILL_IN + " label"),
                errors(keepStocked(s -> s.withText("source", "stock_keeper"))));
    }

    @Test
    void oldGraphsKeepTheirWatches() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "item").withItem("item", "cobble"))
                .node(2, NodeTypes.ITEM_COUNT).node(3, NodeTypes.COMPARE).wire(1, "value", 2, "item")
                .wire(2, "count", 3, "value").compiled();
        assertEquals(1, graph.watches().size());
        assertEquals("cobble", graph.watches().getFirst().item().orElseThrow(), "an item from a constant is fixed");
    }

    // ---- words

    @Test
    void theTemplateReadsAsACard() {
        LogicGraph<String> template = Objects
                .requireNonNull(BuiltInTemplates.all(id -> id).get("keep_every_ore_stocked")).graph();
        RuleCard card = RuleCards.build(template, LANG, item -> item);
        assertFalse(card.custom(), RuleCardsTest.text(card).toString());
        assertEquals(List.of("WHEN every 30 seconds", "FOR EACH item in #c:ores", "IF its count is below 1,000",
                "THEN craft 64 of it"), RuleCardsTest.text(card));
        assertTrue(LogicCompiler.compile(template, 64).graph().isPresent(), "the template compiles");
    }

    @Test
    void listsStockKeepersAndDoneReadOnTheCard() {
        TestGraph list = keepStocked(s -> s.withText("source", "list").withNumber("items", 5)
                .withItem(ForEach.item(1), "gold").withItem(ForEach.item(2), "iron").withItem(ForEach.item(3), "tin")
                .withItem(ForEach.item(5), "zinc")).node(7, NodeTypes.NOTIFY, s -> s.withText("text", "done"))
                .wire(2, "done", 7, "do");
        assertEquals(List.of("WHEN the graph starts", "FOR EACH item in the list gold, iron, tin and 1 more",
                "THEN if its count is below 1,000: craft 64 of it", "AND once every item is checked, notify \"done\""),
                RuleCardsTest.text(RuleCards.build(list.build(), LANG, item -> item)));
        TestGraph keeper = keepStocked(s -> s.withText("source", "stock_keeper").withText("label", "k"));
        assertEquals("FOR EACH item stock keeper \"k\" has a rule for",
                RuleCardsTest.text(RuleCards.build(keeper.build(), LANG, item -> item)).get(1));
    }

    @Test
    void sentencesReadPlainly() {
        assertEquals("for each item of A list", NodeSentence.of(NodeTypes.FOR_EACH,
                NodeSettings.<String>empty().withText("source", "list"), ForEachNodesTest::text, item -> item));
        assertEquals("its count",
                text(NodeTypes.eachSentenceKey(NodeTypes.ITEM_COUNT, NodeSettings.empty()).orElseThrow()));
        assertEquals("its count in {label}",
                text(NodeTypes
                        .eachSentenceKey(NodeTypes.ITEM_COUNT, NodeSettings.<String>empty().withText("label", "chest"))
                        .orElseThrow()));
        assertTrue(NodeTypes.eachSentenceKey(NodeTypes.NOTIFY, NodeSettings.empty()).isEmpty());
    }

    // ---- saving

    @Test
    void theItemWiresSurviveTheTextRoundTrip() {
        Codec<LogicGraph<String>> codec = LogicGraph.codec(Codec.STRING);
        LogicGraph<String> graph = keepStocked(
                s -> s.withText("source", "list").withItem(ForEach.item(1), "gold").withItem(ForEach.item(2), "#ores"))
                .build();
        String text = GraphText.write(codec, NbtOps.INSTANCE, "Ores", graph).orElseThrow();
        GraphText.Read<String> read = GraphText.read(text, 16_384, 64, codec, NbtOps.INSTANCE);
        assertTrue(read.ok(), read.error());
        assertEquals(graph, read.graph().orElseThrow());
        assertTrue(LogicCompiler.compile(read.graph().orElseThrow(), 64).graph().isPresent());
    }

    // ---- cost

    @Test
    void aRunAllocatesNothing() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions
                .assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(keepStocked(
                s -> s.withText("source", "list").withItem(ForEach.item(1), "gold").withItem(ForEach.item(2), "#ores"))
                .compiled());
        FakeContext context = new FakeContext() {
            @Override
            public void requestCraft(String item, long amount) {
                // Kept out of the measure: the fake's action log allocates.
            }

            @Override
            public long readEach(WatchKind kind, String item, String label, long number) {
                return "iron".equals(item) ? 2_000 : 10;
            }
        };
        context.count("gold", 10).count("iron", 2_000).count("copper", 999);
        context.tags.put("#ores", List.of("gold", "iron", "copper"));
        for (int i = 0; i < 20_000; i++) {
            evaluator.restart();
            evaluator.run(context, i);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 20_000; i < 40_000; i++) {
            evaluator.restart();
            evaluator.run(context, i);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }
}
