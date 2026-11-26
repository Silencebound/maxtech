package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The load shedding nodes (M7L2 row 21): the level as a read, its change as an event, and the power priority action.
 */
class ShedNodesTest {
    @Test
    void levelReadsAndChangeFiresOncePerChange() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.SHEDDING_CHANGES).node(2, NodeTypes.COUNTER)
                .node(3, NodeTypes.SHED_LEVEL).wire(1, "changed", 2, "up").compiled();
        assertEquals(1, graph.watches().size(), "both nodes share one watch");
        assertEquals(WatchKind.SHED_LEVEL, graph.watches().get(0).kind());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "nothing on the first look");
        assertEquals(0, evaluator.value(graph.slot(3, "shedding")));

        context.set(WatchKind.SHED_LEVEL, "", "", "", 2);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "fired on the change");
        assertEquals(2, evaluator.value(graph.slot(1, "level")));
        assertEquals(2, evaluator.value(graph.slot(3, "level")));
        assertEquals(1, evaluator.value(graph.slot(3, "shedding")));

        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "no second fire for the same level");

        context.set(WatchKind.SHED_LEVEL, "", "", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 3);
        assertEquals(2, evaluator.value(graph.slot(2, "count")), "fired when the power came back");
        assertEquals(0, evaluator.value(graph.slot(3, "shedding")));
    }

    @Test
    void setPowerPriorityActsOnTheEventWithTheSettingClamped() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.SHEDDING_CHANGES)
                .node(2, NodeTypes.SET_POWER_PRIORITY, s -> s.withText("label", "lamps").withNumber("priority", 4))
                .node(3, NodeTypes.CONSTANT, s -> s.withNumber("value", 9))
                .node(4, NodeTypes.SET_POWER_PRIORITY, s -> s.withText("label", "pumps")).wire(1, "changed", 2, "do")
                .wire(1, "changed", 4, "do").wire(3, "value", 4, "priority").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of(), context.actions, "no event, no action");
        context.set(WatchKind.SHED_LEVEL, "", "", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("power lamps 4", "power pumps 5"), context.actions.stream().sorted().toList(),
                "the setting, and a wired 9 kept at 5");
    }
}
