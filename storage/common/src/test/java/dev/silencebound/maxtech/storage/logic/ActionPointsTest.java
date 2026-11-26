package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cost model of M7L2 chunk 5: checks for nodes and action points for actions are counted apart; every action type
 * costs at least one point and nothing else costs any; a run's points are those of the actions it sent; For each passes
 * and queued rule calls stop when the team's action points run out, as they do for checks.
 */
class ActionPointsTest {
    private static List<String> why(LogicEvaluator<String> evaluator) {
        List<String> lines = new ArrayList<>();
        RunTrace trace = evaluator.trace();
        for (int i = 0; i < trace.size(); i++) {
            lines.add(trace.reason(i) + " " + trace.first(i) + " " + trace.second(i));
        }
        return lines;
    }

    // ---- the cost table

    @Test
    void everyActionTypeCostsPointsAndNoOtherTypeDoes() {
        for (NodeType type : NodeTypes.all()) {
            if (type.op().acts()) {
                assertTrue(type.actionPoints() >= 1, type.id() + " acts but costs no action points");
            } else {
                assertEquals(0, type.actionPoints(), type.id() + " does not act but costs action points");
            }
        }
    }

    @Test
    void theWeightsFollowTheBenchmark() {
        // 1 point is about 1 microsecond (cost-benchmark.md): a move 12 µs, a craft request 4, a switch 1.5, a notice
        // to the log 0.3.
        assertEquals(12, NodeTypes.MOVE_ITEMS.actionPoints());
        assertEquals(13, NodeTypes.MOVE_TO_POOL.actionPoints());
        assertEquals(4, NodeTypes.REQUEST_CRAFT.actionPoints());
        assertEquals(2, NodeTypes.DEVICE_SWITCH.actionPoints());
        assertEquals(2, NodeTypes.REDSTONE_OUT.actionPoints());
        assertEquals(1, NodeTypes.NOTIFY.actionPoints());
        assertEquals(1, NodeTypes.SET_PRIORITY.actionPoints());
        // The two remember nodes the benchmark found undercosted, in checks.
        assertEquals(8, NodeTypes.STATS.cost());
        assertEquals(3, NodeTypes.CHANGE_RATE.cost());
    }

    // ---- counting a run

    @Test
    void aRunCostsThePointsOfTheActionsItSentApartFromItsChecks() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.MOVE_ITEMS, s -> s.withItem("item", "iron").withText("to", "box"))
                .node(3, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank")).wire(1, "started", 2, "do")
                .wire(1, "started", 3, "do");
        CompiledGraph<String> compiled = graph.compiled();
        assertTrue(compiled.acts());
        assertEquals(12 + 1, compiled.maxActionPoints(), "every action node once");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(compiled);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(2, context.take().size());
        assertEquals(12 + 1, evaluator.lastActionPoints());
        assertEquals(NodeTypes.GRAPH_START.cost() + NodeTypes.MOVE_ITEMS.cost() + NodeTypes.SET_PRIORITY.cost(),
                evaluator.lastCost(), "checks count the nodes only");
        evaluator.run(context, 1);
        assertEquals(0, evaluator.lastActionPoints(), "a run that sends nothing costs no points");
    }

    @Test
    void aGraphWithoutActionsNeedsNoPoints() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "iron"))
                .compiled();
        assertEquals(false, graph.acts());
        assertEquals(0, graph.maxActionPoints());
    }

    @Test
    void aSettingSentTwiceInARunCostsOnce() {
        TestGraph graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 1))
                .node(3, NodeTypes.SET_PRIORITY, s -> s.withText("label", "bank").withNumber("priority", 2))
                .wire(1, "started", 2, "do").wire(1, "started", 3, "do");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(1, context.take().size(), "the second replaces the first");
        assertEquals(1, evaluator.lastActionPoints());
    }

    // ---- For each and rule calls stop on points

    /** "When the graph starts, for each ore: craft 64 of it" (4 points a pass). */
    private static TestGraph craftEachOre() {
        return new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.FOR_EACH, s -> s.withText("source", "tag").withItem("tag", "#ores"))
                .node(3, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 64)).wire(1, "started", 2, "do")
                .wire(2, "each", 3, "do").wire(2, "item", 3, "item");
    }

    @Test
    void aForEachStopsWhenTheTeamsActionPointsRunOut() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(craftEachOre().compiled());
        FakeContext context = new FakeContext();
        context.tags.put("#ores", List.of("gold", "iron", "copper"));
        // Two passes' crafts (4 each) reach 8; the third pass does not start.
        context.actionPointsLeft = 8;
        evaluator.run(context, 0);
        assertEquals(List.of("craft gold 64", "craft iron 64"), context.take());
        assertEquals(8, evaluator.lastActionPoints());
        assertTrue(why(evaluator).contains(LogicLang.WHY_EACH_POINTS + " 2 0"), why(evaluator).toString());
    }

    @Test
    void aForEachWithPointsLeftGoesThroughEveryItem() {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(craftEachOre().compiled());
        FakeContext context = new FakeContext();
        context.tags.put("#ores", List.of("gold", "iron", "copper"));
        context.actionPointsLeft = 9;
        evaluator.run(context, 0);
        assertEquals(3, context.take().size(), "the third pass starts with 1 point left and overspends");
        assertEquals(12, evaluator.lastActionPoints());
    }

    @Test
    void queuedCallsPastTheTeamsActionPointsWaitForTheNextRun() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.RUN_BY_RULE)
                .node(2, NodeTypes.PARAMETER,
                        s -> s.withText("name", "item").withText("kind", "item").withItem("item", "stick"))
                .node(3, NodeTypes.REQUEST_CRAFT, s -> s.withNumber("amount", 5)).wire(1, "ran", 3, "do")
                .wire(2, "value", 3, "item").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        context.actionPointsLeft = 4;
        for (String item : List.of("a", "b", "c")) {
            evaluator.call(graph.param("item"), item, -1, 0);
        }
        evaluator.run(context, 0);
        assertEquals(List.of("craft a 5"), context.take());
        assertTrue(why(evaluator).contains(LogicLang.WHY_EACH_POINTS + " 1 0"), why(evaluator).toString());
        assertTrue(evaluator.wantsRerun());
        context.actionPointsLeft = Long.MAX_VALUE;
        evaluator.run(context, 1);
        assertEquals(List.of("craft b 5", "craft c 5"), context.take());
    }
}
