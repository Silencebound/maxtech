package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Rules with parameters (M7L2 row 19): a copy's values going into the template's graph and the errors naming a missing
 * or changed parameter, the compile checks of parameters, call branches and run-rule arguments, the cross-rule check of
 * the parameters a call names, calls running once each with their own values (also from a For each), old graphs running
 * as before, the card of a copy showing its values, and no allocation per run.
 */
class ParametersTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    /** "Keep {item} above {amount}", as the built-in template. */
    private static LogicGraph<String> keepAbove() {
        return BuiltInTemplates.keepAbove("stick");
    }

    /**
     * "When another rule runs this: if the item's count is below the amount, craft 5 of it", with an item and an amount
     * parameter.
     */
    private static TestGraph callable() {
        return new TestGraph().node(1, NodeTypes.RUN_BY_RULE)
                .node(2, NodeTypes.PARAMETER,
                        s -> s.withText("name", "Item").withText("kind", "item").withItem("item", "stick"))
                .node(3, NodeTypes.PARAMETER,
                        s -> s.withText("name", "amount").withText("kind", "number").withNumber("value", 100))
                .node(4, NodeTypes.ITEM_COUNT).node(5, NodeTypes.COMPARE, s -> s.withText("mode", "below"))
                .node(6, NodeTypes.IF).node(7, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 5))
                .wire(2, "value", 4, "item").wire(4, "count", 5, "value").wire(3, "value", 5, "threshold")
                .wire(1, "ran", 6, "when").wire(5, "result", 6, "condition").wire(6, "then", 7, "do")
                .wire(2, "value", 7, "item");
    }

    private static List<String> keys(CompileResult<String> result) {
        List<String> keys = new ArrayList<>();
        for (GraphError error : result.errors()) {
            keys.add(error.node() + " " + error.port() + " " + error.key() + " " + error.arg());
        }
        return keys;
    }

    // ---- copies with values

    @Test
    void aCopysValuesGoIntoTheTemplate() {
        Parameters.Applied<String> applied = Parameters.apply(keepAbove(),
                Map.of("item", new SettingValue.Item<>("cobble"), "amount", new SettingValue.Num<>(500)));
        assertTrue(applied.errors().isEmpty(), applied.errors().toString());
        CompiledGraph<String> graph = LogicCompiler.compile(applied.graph(), 64).graph().orElseThrow();
        // The copy watches its own item, like a rule built with it.
        assertEquals("cobble", graph.watches().getFirst().item().orElseThrow());
        assertFalse(graph.callable(), "no Run by another rule node: an ordinary rule");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("cobble", 499).count("stick", 0);
        evaluator.run(context, 0);
        // The batch keeps the template's default.
        assertEquals(List.of("craft cobble 32"), context.take());
        // The template itself, with its defaults, keeps sticks above 64.
        LogicEvaluator<String> template = new LogicEvaluator<>(LogicCompiler.compile(keepAbove(), 64).graph().get());
        template.run(context, 0);
        assertEquals(List.of("craft stick 32"), context.take());
    }

    @Test
    void aMissingOrChangedParameterIsAnErrorNamingIt() {
        Parameters.Applied<String> applied = Parameters.apply(keepAbove(),
                Map.of("limit", new SettingValue.Num<>(5), "amount", new SettingValue.Flag<>(true)));
        assertEquals(List.of(new GraphError(2, "value", LogicLang.PARAM_KIND, "amount"),
                new GraphError(GraphError.GRAPH, "", LogicLang.PARAM_UNKNOWN, "limit")), applied.errors());
        // Numbers are clamped to the value range; names match without case or spaces.
        Parameters.Applied<String> clamped = Parameters.apply(keepAbove(),
                Map.of(Parameters.key(" Amount "), new SettingValue.Num<>(Long.MAX_VALUE)));
        assertEquals(SettingSpec.MAX_NUMBER, Parameters.find(clamped.graph(), "amount").orElseThrow().value()
                .map(value -> ((SettingValue.Num<String>) value).value()).orElseThrow());
    }

    @Test
    void parametersListTheirDefaults() {
        List<Parameters.Param<String>> params = Parameters.of(keepAbove());
        assertEquals(List.of("item", "amount", "batch"), params.stream().map(Parameters.Param::key).toList());
        assertEquals(Map.of("item", new SettingValue.Item<>("stick"), "amount", new SettingValue.Num<>(64L), "batch",
                new SettingValue.Num<>(32L)), Parameters.values(keepAbove()));
    }

    // ---- compile checks

    @Test
    void parametersAreChecked() {
        CompileResult<String> result = new TestGraph()
                .node(1, NodeTypes.PARAMETER, s -> s.withText("name", "a").withText("kind", "number"))
                .node(2, NodeTypes.PARAMETER, s -> s.withText("name", " A").withText("kind", "number"))
                .node(3, NodeTypes.PARAMETER, s -> s.withText("kind", "number"))
                .node(4, NodeTypes.PARAMETER, s -> s.withText("name", "b").withText("kind", "item")).compile();
        assertEquals(List.of("3 name " + LogicLang.FILL_IN + " ", "4 item " + LogicLang.FILL_IN + " ",
                "2 name " + LogicLang.PARAM_DUPLICATE + " "), keys(result));
    }

    @Test
    void aCallBranchHoldsOnlyWhatRunsOncePerCall() {
        assertTrue(callable().compile().graph().orElseThrow().callable());
        CompileResult<String> latch = callable().node(8, NodeTypes.LATCH).wire(5, "turned_yes", 8, "set").compile();
        assertEquals(List.of("8  " + LogicLang.CALL_NOT_HERE + " "), keys(latch));
        CompileResult<String> two = callable().node(9, NodeTypes.RUN_BY_RULE).compile();
        assertEquals(List.of("9  " + LogicLang.CALL_ONE_ENTRY + " "), keys(two));
    }

    @Test
    void runRuleArgumentsNeedTheirParameters() {
        CompileResult<String> unnamed = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", "keep").withItem("item", "cobble"))
                .node(3, NodeTypes.CONSTANT, s -> s.withNumber("value", 5)).wire(1, "started", 2, "do")
                .wire(3, "value", 2, "amount").compile();
        assertEquals(List.of("2 item_param " + LogicLang.FILL_IN + " ", "2 amount_param " + LogicLang.FILL_IN + " "),
                keys(unnamed));
        CompileResult<String> noItem = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", "keep").withText("item_param", "item"))
                .wire(1, "started", 2, "do").compile();
        assertEquals(List.of("2 item " + PortType.ITEM.connectKey() + " "), keys(noItem));
    }

    @Test
    void callsMustNameParametersTheRuleHas() {
        LogicGraph<String> caller = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", "keep").withItem("item", "cobble")
                        .withText("item_param", "item").withText("amount_param", "Limit"))
                .wire(1, "started", 2, "do").build();
        List<List<GraphError>> errors = RuleCalls.check(List.of(caller, callable().build()), List.of("caller", "keep"),
                new boolean[]{true, true});
        assertEquals(List.of(new GraphError(2, "amount_param", LogicLang.PARAM_UNKNOWN, "limit")), errors.get(0));
        assertTrue(errors.get(1).isEmpty());
        // A parameter of another kind: the item fills a number.
        LogicGraph<String> wrongKind = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.RUN_RULE,
                        s -> s.withText("rule", "keep").withItem("item", "cobble").withText("item_param", "amount"))
                .wire(1, "started", 2, "do").build();
        assertEquals(List.of(new GraphError(2, "item_param", LogicLang.PARAM_KIND, "amount")), RuleCalls
                .check(List.of(wrongKind, callable().build()), List.of("caller", "keep"), new boolean[]{true, true})
                .get(0));
    }

    // ---- calls

    @Test
    void eachCallRunsWithItsOwnValues() {
        CompiledGraph<String> graph = callable().compiled();
        assertTrue(graph.watches().stream().noneMatch(watch -> watch.kind() == WatchKind.ITEM_COUNT),
                "the call branch's reads are not watched: " + graph.watches());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("gold", 10).count("iron", 2_000).count("stick", 0);
        evaluator.run(context, 0);
        assertEquals(List.of(), context.take(), "nothing runs without a call");
        int item = graph.param("item");
        int amount = graph.param("amount");
        assertTrue(evaluator.call(item, "gold", amount, 50));
        assertTrue(evaluator.call(item, "iron", amount, 50));
        // Iron is under this call's amount; a call without arguments takes the defaults (stick, 100).
        assertTrue(evaluator.call(item, "iron", amount, 3_000));
        assertTrue(evaluator.call(-1, null, -1, 0));
        assertTrue(evaluator.pending(1));
        evaluator.run(context, 1);
        assertEquals(List.of("craft gold 5", "craft iron 5", "craft stick 5"), context.take());
        assertEquals(0, evaluator.callsWaiting());
        // The parameters are back at their own values after the calls.
        assertEquals(100, evaluator.value(graph.slot(3, "value")));
        evaluator.run(context, 2);
        assertEquals(List.of(), context.take(), "a call runs once");
    }

    @Test
    void callsPastTheItemLimitWaitForTheNextRun() {
        CompiledGraph<String> graph = callable().compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        context.maxEach = 2;
        for (String item : List.of("a", "b", "c")) {
            evaluator.call(graph.param("item"), item, -1, 0);
        }
        evaluator.run(context, 0);
        assertEquals(List.of("craft a 5", "craft b 5"), context.take());
        assertTrue(evaluator.wantsRerun());
        evaluator.run(context, 1);
        assertEquals(List.of("craft c 5"), context.take());
    }

    @Test
    void aForEachPassesItsItemToTheCall() {
        LogicGraph<String> caller = new TestGraph().node(1, NodeTypes.GRAPH_START).node(2, NodeTypes.FOR_EACH,
                s -> s.withText("source", "list").withItem(ForEach.item(1), "gold").withItem(ForEach.item(2), "iron"))
                .node(3, NodeTypes.CONSTANT, s -> s.withNumber("value", 40))
                .node(4, NodeTypes.RUN_RULE,
                        s -> s.withText("rule", "keep").withText("item_param", "item").withText("amount_param",
                                "amount"))
                .wire(1, "started", 2, "do").wire(2, "each", 4, "do").wire(2, "item", 4, "item")
                .wire(3, "value", 4, "amount").build();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(
                LogicCompiler.compile(caller, 64).graph().orElseThrow());
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("run keep item=gold amount=40", "run keep item=iron amount=40"), context.take());
    }

    @Test
    void graphsWithoutParametersRunAsBefore() {
        // A Run by another rule node without parameters: a plain event, its branch free to remember.
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.RUN_BY_RULE).node(2, NodeTypes.COUNTER)
                .wire(1, "ran", 2, "up").compiled();
        assertFalse(graph.callable());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        assertFalse(evaluator.call(-1, null, -1, 0), "not callable with arguments");
    }

    // ---- words

    @Test
    void aCopysCardShowsItsValues() {
        LogicGraph<String> copy = Parameters
                .apply(keepAbove(),
                        Map.of("item", new SettingValue.Item<>("cobble"), "amount", new SettingValue.Num<>(500)))
                .graph();
        RuleCard card = RuleCards.build(copy, LANG, item -> item);
        assertFalse(card.custom(), RuleCardsTest.text(card).toString());
        assertEquals(List.of("IF the count of cobble in the network is below 500", "THEN craft 32 cobble"),
                RuleCardsTest.text(card));
        // The values are fields of the parameter nodes, so editing them on the card edits the parameters.
        List<String> fields = new ArrayList<>();
        for (RuleCard.Line line : card.lines()) {
            for (RuleCard.Field field : line.fields()) {
                fields.add(field.node() + " " + field.setting());
            }
        }
        assertTrue(fields.containsAll(List.of("1 item", "2 value", "3 value")), fields.toString());
        assertEquals("the number parameter amount",
                NodeSentence
                        .of(NodeTypes.PARAMETER, NodeSettings.<String>empty().withText("name", "amount"),
                                key -> ENGLISH.getOrDefault(key, key), item -> item)
                        .toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void theTemplateIsShipped() {
        LogicGraph<String> shipped = Objects.requireNonNull(BuiltInTemplates.all(id -> id).get("keep_item_above"))
                .graph();
        assertTrue(Parameters.any(shipped));
        assertTrue(LogicCompiler.compile(shipped, 64).graph().isPresent());
        // Its parameters take values the way a copy or the template form gives them.
        Parameters.Applied<String> applied = Parameters.apply(shipped, Map.of("item", new SettingValue.Item<>("cobble"),
                "amount", new SettingValue.Num<>(500), "batch", new SettingValue.Num<>(8)));
        assertEquals(List.of(), applied.errors());
        CompiledGraph<String> graph = LogicCompiler.compile(applied.graph(), 64).graph().orElseThrow();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("cobble", 10);
        evaluator.run(context, 0);
        assertEquals(List.of("craft cobble 8"), context.take());
    }

    // ---- cost

    @Test
    void callsAllocateNothingOnceWarm() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions
                .assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = callable().compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        long[] crafts = new long[1];
        FakeContext context = new FakeContext() {
            @Override
            public void requestCraft(String item, long amount) {
                // Counted without allocating; the fake's action log would allocate.
                crafts[0]++;
            }

            @Override
            public long readEach(WatchKind kind, String item, String label, long number) {
                return "iron".equals(item) ? 2_000 : 10;
            }
        };
        int item = graph.param("item");
        int amount = graph.param("amount");
        for (int i = 0; i < 40_000; i++) {
            if (i == 20_000) {
                crafts[0] = 0;
                long before = threads.getCurrentThreadAllocatedBytes();
                for (int j = 0; j < 20_000; j++) {
                    evaluator.call(item, "gold", amount, 50);
                    evaluator.call(item, "iron", amount, 50);
                    evaluator.run(context, j);
                }
                long allocated = threads.getCurrentThreadAllocatedBytes() - before;
                // Every run served both calls: gold (10 of 50) asked once, iron (2,000) not; nothing left waiting.
                assertEquals(20_000, crafts[0], "the calls were served");
                assertEquals(0, evaluator.callsWaiting());
                assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
                return;
            }
            evaluator.call(item, "gold", amount, 50);
            evaluator.call(item, "iron", amount, 50);
            evaluator.run(context, i);
        }
    }
}
