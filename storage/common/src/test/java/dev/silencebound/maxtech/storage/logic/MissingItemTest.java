package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The crafting-job-failed node's "missing item" output (owner decision 2026-10-09): nodes it feeds take the run's
 * missing item, a notice's {item} names it, the why line lists every missing item, and only nodes that can take an item
 * while they run may read it.
 */
class MissingItemTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    /** Craft failed (1) into request craft 16 (2), a notice (3) and an item count (4), item from "missing item". */
    private static TestGraph refill() {
        return new TestGraph().node(1, NodeTypes.CRAFT_FAILED, s -> s.withItem("item", "torch"))
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 16))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "Short of {item}")).node(4, NodeTypes.ITEM_COUNT)
                .wire(1, "failed", 2, "do").wire(1, "missing_item", 2, "item").wire(1, "failed", 3, "do")
                .wire(1, "missing_item", 4, "item");
    }

    private static int failWatch(CompiledGraph<String> graph) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == WatchKind.CRAFT_FAILED) {
                return watch.index();
            }
        }
        throw new AssertionError("no craft failed watch");
    }

    @Test
    void missingItemFeedsCraftCountAndNotice() {
        CompiledGraph<String> graph = refill().compiled();
        assertEquals(List.of(WatchKind.CRAFT_FAILED), kinds(graph), "the fed nodes watch nothing of their own");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("coal", 5);
        evaluator.run(context, 0);
        assertEquals(List.of(), context.actions, "nothing before a job fails");

        evaluator.signal(failWatch(graph), 12, "coal", "12 coal, 3 stick");
        evaluator.run(context, 1);
        assertEquals(
                List.of("craft coal 16",
                        "notify Short of coal " + (LogicContext.CHANNEL_LOG | LogicContext.CHANNEL_TOAST)),
                context.actions);
        assertEquals(12, evaluator.value(graph.slot(1, "missing")), "the first item's amount");
        assertEquals(5, evaluator.value(graph.slot(4, "count")), "the missing item's count, read when it fired");
        RunTrace trace = evaluator.trace();
        int line = trace.find(1);
        assertTrue(line >= 0, "a why line on the failed node");
        assertEquals(LogicLang.WHY_MISSING, trace.reason(line));
        assertEquals("12 coal, 3 stick", trace.text(line), "every missing item listed");
    }

    @Test
    void secondFailureBeforeARunKeepsTheFirstItem() {
        CompiledGraph<String> graph = refill().compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        evaluator.signal(failWatch(graph), 2, "coal", "2 coal");
        evaluator.signal(failWatch(graph), 4, "stick", "4 stick");
        evaluator.run(context, 1);
        assertEquals("craft coal 16", context.actions.getFirst());
        context.actions.clear();
        // A later run that is no failure takes no item: the craft has none to ask for.
        evaluator.everythingChanged();
        evaluator.run(context, 2);
        assertEquals(List.of(), context.actions);
    }

    @Test
    void onlyNodesThatTakeAnItemWhileTheyRunMayReadIt() {
        TestGraph crossing = new TestGraph().node(1, NodeTypes.CRAFT_FAILED)
                .node(2, NodeTypes.COUNT_CROSSES, s -> s.withNumber("threshold", 5)).wire(1, "missing_item", 2, "item");
        List<String> errors = new ArrayList<>();
        for (GraphError error : crossing.compile().errors()) {
            errors.add(error.node() + " " + error.key() + " " + error.port());
        }
        assertEquals(List.of("2 " + LogicLang.MISSING_ITEM_NOT_HERE + " item"), errors);
    }

    @Test
    void cardReadsTheMissingItem() {
        RuleCard card = RuleCards.build(refill().build(), LANG, item -> item);
        List<String> text = RuleCardsTest.text(card);
        assertTrue(text.contains("THEN craft 16 of the missing item"), text.toString());
        assertTrue(text.contains("WHEN a crafting job for torch fails for lack of an item"), text.toString());
    }

    private static List<WatchKind> kinds(CompiledGraph<String> graph) {
        List<WatchKind> kinds = new ArrayList<>();
        for (Watch<String> watch : graph.watches()) {
            kinds.add(watch.kind());
        }
        return kinds;
    }
}
