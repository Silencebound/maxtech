package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The task board nodes (M7C item 15): what a "task" node watches and reads, and what "set task" sends. */
class TaskNodesTest {
    @Test
    void aTaskNodeWatchesItsBoardAndTaskAndSplitsDoneFromProgress() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.TASK_STATE,
                s -> s.withText("board", " Base ").withText("task", "Furnaces").withText("board_scope", "network"))
                .compiled();
        assertEquals(1, graph.watches().size());
        Watch<String> watch = graph.watches().getFirst();
        assertEquals(WatchKind.TASK, watch.kind());
        assertEquals("n\nBase\nFurnaces", watch.name());
        FakeContext context = new FakeContext().set(WatchKind.TASK, "", "", watch.name(), (5L << 1) | 1);
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(1, evaluator.value(graph.slot(1, "done")));
        assertEquals(5, evaluator.value(graph.slot(1, "progress")));
        context.set(WatchKind.TASK, "", "", watch.name(), 7L << 1);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(0, evaluator.value(graph.slot(1, "done")));
        assertEquals(7, evaluator.value(graph.slot(1, "progress")));
    }

    @Test
    void setTaskSendsOnItsEventAndDefaultsToDone() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_TASK, s -> s.withText("board", "Base").withText("task", "Furnaces"))
                .wire(1, "changed", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(List.of(), context.take(), "waits for its event");
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("task t|Base|Furnaces true"), context.take(), "the team's board by default");
    }

    @Test
    void aTaskNodeNeedsATask() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.TASK_STATE).compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> "task".equals(error.port())), result.errors()::toString);
    }
}
