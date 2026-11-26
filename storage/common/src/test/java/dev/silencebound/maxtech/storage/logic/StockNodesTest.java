package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The stock keeper node types (M7L2 row 15): the status read, the short event, the rule switch and the amount setter,
 * against a fake context; their sentences, card lines and card edits.
 */
class StockNodesTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    private static long out(LogicEvaluator<String> evaluator, CompiledGraph<String> graph, int node, String output) {
        return evaluator.value(graph.slot(node, output));
    }

    private static Watch<String> stockWatch(CompiledGraph<String> graph, long value) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == WatchKind.STOCK && watch.number() == value) {
                return watch;
            }
        }
        throw new AssertionError("No stock watch " + value);
    }

    private static TestGraph status() {
        return new TestGraph().node(1, NodeTypes.STOCK_STATUS,
                s -> s.withItem("item", "cobble").withText("label", "keeper"));
    }

    @Test
    void statusReadsTheRuleTheCountAndTheFlags() {
        CompiledGraph<String> graph = status().compiled();
        assertEquals(5, graph.watches().size(), "keep, restart, batch, state and the count");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().stock("cobble", "keeper", WatchKind.STOCK_KEEP, 100)
                .stock("cobble", "keeper", WatchKind.STOCK_RESTART, 40)
                .stock("cobble", "keeper", WatchKind.STOCK_BATCH, 32).stock("cobble", "keeper", WatchKind.STOCK_FLAGS,
                        WatchKind.STOCK_FOUND | WatchKind.STOCK_ON | WatchKind.STOCK_RUNNING)
                .count("cobble", 30);
        evaluator.run(context, 0);
        assertEquals(100, out(evaluator, graph, 1, "keep"));
        assertEquals(40, out(evaluator, graph, 1, "restart"));
        assertEquals(32, out(evaluator, graph, 1, "batch"));
        assertEquals(30, out(evaluator, graph, 1, "count"));
        assertEquals(1, out(evaluator, graph, 1, "below"), "30 is below the restart threshold of 40");
        assertEquals(1, out(evaluator, graph, 1, "crafting"));
        assertEquals(1, out(evaluator, graph, 1, "on"));

        context.count("cobble", 40).stock("cobble", "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_FOUND);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(0, out(evaluator, graph, 1, "below"), "40 is not below 40");
        assertEquals(0, out(evaluator, graph, 1, "crafting"));
        assertEquals(0, out(evaluator, graph, 1, "on"), "switched off");
    }

    @Test
    void statusWithoutARestartThresholdIsBelowUnderKeepAndWithoutARuleNever() {
        CompiledGraph<String> graph = status().compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().stock("cobble", "keeper", WatchKind.STOCK_KEEP, 64)
                .stock("cobble", "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_FOUND).count("cobble", 63);
        evaluator.run(context, 0);
        assertEquals(1, out(evaluator, graph, 1, "below"), "a rule that only keeps tops up below keep");
        context.stock("cobble", "keeper", WatchKind.STOCK_FLAGS, 0).stock("cobble", "keeper", WatchKind.STOCK_KEEP, 0);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(0, out(evaluator, graph, 1, "below"), "no rule: never below");
    }

    @Test
    void statusNeedsAnItemAndALabel() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.STOCK_STATUS).compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> "item".equals(error.port())), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(error -> "label".equals(error.port())), result.errors()::toString);
    }

    @Test
    void twoStatusNodesShareTheirWatchesAndAShortNodeSharesTheStateWatch() {
        CompiledGraph<String> graph = status()
                .node(2, NodeTypes.STOCK_STATUS, s -> s.withItem("item", "cobble").withText("label", "keeper"))
                .node(3, NodeTypes.STOCK_SHORT, s -> s.withItem("item", "cobble").withText("label", "keeper"))
                .node(4, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble")).compiled();
        assertEquals(5, graph.watches().size(), "one subscription per value, shared");
    }

    @Test
    void shortFiresOncePerChangeAndRecoveredAfterIt() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.STOCK_SHORT, s -> s.withText("label", "keeper"))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "short")).wire(1, "went_short", 2, "do")
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "back")).wire(1, "recovered", 3, "do").compiled();
        assertEquals(Optional.empty(), stockWatch(graph, WatchKind.STOCK_FLAGS).item(), "any rule");
        int flags = stockWatch(graph, WatchKind.STOCK_FLAGS).index();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of(), context.take(), "not short on the first look: nothing");
        context.stock("", "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_FOUND | WatchKind.STOCK_SHORT);
        evaluator.changed(flags);
        evaluator.run(context, 1);
        assertEquals(List.of("notify short 3"), context.take());
        assertEquals(1, out(evaluator, graph, 1, "short"));
        // Still short, with another bit changing: no second event.
        context.stock("", "keeper", WatchKind.STOCK_FLAGS,
                WatchKind.STOCK_FOUND | WatchKind.STOCK_SHORT | WatchKind.STOCK_FILLING);
        evaluator.changed(flags);
        evaluator.run(context, 2);
        assertEquals(List.of(), context.take(), "fires once per change");
        context.stock("", "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_FOUND);
        evaluator.changed(flags);
        evaluator.run(context, 3);
        assertEquals(List.of("notify back 3"), context.take());
    }

    @Test
    void shortRemembersItsStateThroughASave() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.STOCK_SHORT, s -> s.withText("label", "keeper"))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "short")).wire(1, "went_short", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().stock("", "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_SHORT);
        evaluator.run(context, 0);
        assertEquals(List.of("notify short 3"), context.take(), "short on the first look fires");
        LogicMemory memory = evaluator.memory();
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(memory);
        loaded.run(context, 1);
        assertEquals(List.of(), context.take(), "a loaded graph does not fire again");
    }

    @Test
    void switchActsOnChangesOnly() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.STOCK_STATUS, s -> s.withItem("item", "cobble").withText("label", "keeper"))
                .node(2, NodeTypes.STOCK_RULE_SWITCH, s -> s.withText("label", "keeper").withItem("item", "cobble"))
                .wire(1, "below", 2, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().stock("cobble", "keeper", WatchKind.STOCK_KEEP, 10).stock("cobble",
                "keeper", WatchKind.STOCK_FLAGS, WatchKind.STOCK_FOUND);
        evaluator.run(context, 0);
        assertEquals(List.of("stock keeper cobble true"), context.take());
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "unchanged: sent once");
        context.count("cobble", 20);
        evaluator.everythingChanged();
        evaluator.run(context, 2);
        assertEquals(List.of("stock keeper cobble false"), context.take());
    }

    @Test
    void switchWithoutAnItemSwitchesEveryRule() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", false))
                .node(2, NodeTypes.STOCK_RULE_SWITCH, s -> s.withText("label", "keeper")).wire(1, "value", 2, "on")
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("stock keeper null false"), context.take());
    }

    @Test
    void setSendsItsAmountWhenFiredAndTwoFieldsOfOneRuleBothGo() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", "keeper").withItem("item", "cobble").withNumber("amount", 500))
                .node(3, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", "keeper").withItem("item", "cobble").withText("field", "restart_below")
                                .withNumber("amount", 100))
                .node(4, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", "keeper").withItem("item", "cobble").withNumber("amount", 600))
                .wire(1, "started", 2, "do").wire(1, "started", 3, "do").wire(1, "started", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        List<String> actions = context.take();
        assertEquals(2, actions.size(), actions::toString);
        assertTrue(actions.contains("stock keeper cobble field 0 600"), "the later keep replaces: " + actions);
        assertTrue(actions.contains("stock keeper cobble field 1 100"), actions::toString);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "a wired when sets only when it fires");
    }

    @Test
    void setUnwiredFollowsItsAmountAndNeverSendsBelowZero() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.SENSOR_VALUE, s -> s.withText("label", "tank").withText("value", "level"))
                .node(2, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", "keeper").withItem("item", "cobble").withText("field", "batch_size"))
                .wire(1, "value", 2, "amount").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.SENSOR, "", "tank", "level", 16);
        evaluator.run(context, 0);
        assertEquals(List.of("stock keeper cobble field 2 16"), context.take());
        context.set(WatchKind.SENSOR, "", "tank", "level", -5);
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of("stock keeper cobble field 2 0"), context.take());
    }

    @Test
    void setNeedsAnItem() {
        CompileResult<String> result = new TestGraph()
                .node(1, NodeTypes.STOCK_RULE_SET, s -> s.withText("label", "keeper")).compile();
        assertTrue(result.errors().stream().anyMatch(error -> "item".equals(error.port())), result.errors()::toString);
    }

    @Test
    void sentencesReadPlainly() {
        java.util.function.UnaryOperator<String> lang = key -> ENGLISH.getOrDefault(key, key);
        NodeSettings<String> rule = NodeSettings.<String>empty().withItem("item", "cobble").withText("label", "keeper");
        assertEquals("the stock rule for cobble in keeper",
                NodeSentence.of(NodeTypes.STOCK_STATUS, rule, lang, item -> item));
        assertEquals("when the stock rule for cobble in keeper runs short",
                NodeSentence.of(NodeTypes.STOCK_SHORT, rule, lang, item -> item));
        assertEquals("switch the stock rule for cobble in keeper on while On",
                NodeSentence.of(NodeTypes.STOCK_RULE_SWITCH, rule, lang, item -> item));
        assertEquals("set Restart below of the stock rule for cobble in keeper to 32",
                NodeSentence.of(NodeTypes.STOCK_RULE_SET,
                        rule.withText("field", "restart_below").withNumber("amount", 32), lang, item -> item));
    }

    @Test
    void cardsReadTheStockNodes() {
        LogicGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.STOCK_SHORT, s -> s.withItem("item", "cobble").withText("label", "keeper"))
                .node(2, NodeTypes.STOCK_RULE_SET,
                        s -> s.withText("label", "keeper").withItem("item", "cobble").withNumber("amount", 32))
                .wire(1, "went_short", 2, "do")
                .node(3, NodeTypes.STOCK_STATUS, s -> s.withItem("item", "cobble").withText("label", "keeper"))
                .node(4, NodeTypes.STOCK_RULE_SWITCH, s -> s.withText("label", "keeper").withItem("item", "dirt"))
                .wire(3, "below", 4, "on").build();
        List<String> lines = RuleCardsTest.text(RuleCards.build(graph, LANG, item -> item));
        assertTrue(lines.contains("WHEN the stock rule for cobble in keeper runs short"), lines::toString);
        assertTrue(lines.contains("THEN set keep of the stock rule for cobble in keeper to 32"), lines::toString);
        assertTrue(lines.stream().anyMatch(line -> line.contains(
                "switch the stock rule for dirt in keeper on while cobble in keeper is below its restart amount")),
                lines::toString);
    }

    @Test
    void cardEditsOfferAndChangeTheStockNodes() {
        assertTrue(CardEdits.choices(CardEdits.Add.TRIGGER).contains(NodeTypes.STOCK_SHORT));
        assertTrue(CardEdits.choices(CardEdits.Add.CONDITION).contains(NodeTypes.STOCK_STATUS));
        assertTrue(CardEdits.choices(CardEdits.Add.ACTION).contains(NodeTypes.STOCK_RULE_SWITCH));
        assertTrue(CardEdits.choices(CardEdits.Add.ACTION).contains(NodeTypes.STOCK_RULE_SET));
        LogicGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.STOCK_RULE_SET, s -> s.withText("label", "keeper").withItem("item", "cobble"))
                .build();
        CardEdits.Result<String> field = CardEdits.set(graph, 1, "field", new SettingValue.Text<>("batch_size"));
        assertEquals("", field.refusal());
        CardEdits.Result<String> amount = CardEdits.set(field.graph().orElseThrow(), 1, "amount",
                new SettingValue.Num<>(-3));
        LogicNode<String> node = java.util.Objects.requireNonNull(amount.graph().orElseThrow().node(1));
        assertEquals(Optional.of("batch_size"), node.settings().text("field"));
        assertEquals(0L, node.settings().number("amount").orElseThrow(), "clamped to the setting's range");
        assertEquals(CardEdits.REFUSED_VALUE,
                CardEdits.set(graph, 1, "field", new SettingValue.Text<>("priority")).refusal());
    }

    @Test
    void runsAllocateNothing() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions
                .assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = status().node(2, NodeTypes.STOCK_SHORT, s -> s.withText("label", "keeper"))
                .node(3, NodeTypes.STOCK_RULE_SWITCH, s -> s.withText("label", "keeper")).wire(1, "below", 3, "on")
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        long[] value = new long[1];
        LogicContext<String> context = new FakeContext() {
            @Override
            public long read(Watch<String> watch) {
                return value[0] * (watch.index() + 1);
            }

            @Override
            public void switchStockRule(String label, @org.jspecify.annotations.Nullable String item, boolean on) {
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
