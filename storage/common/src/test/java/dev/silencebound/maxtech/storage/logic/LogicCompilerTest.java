package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogicCompilerTest {
    private static GraphError only(CompileResult<String> result) {
        assertTrue(result.paused(), "the graph should not compile");
        assertTrue(result.graph().isEmpty(), "a paused graph has nothing to run");
        assertEquals(1, result.errors().size(), () -> "one error expected: " + result.errors());
        return result.errors().getFirst();
    }

    @Test
    void validGraphCompiles() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COMPARE).wire(1, "count", 2, "value").compile();
        assertFalse(result.paused(), () -> result.errors().toString());
        assertEquals(2, result.graph().orElseThrow().size());
        assertEquals(result.source(), result.graph().orElseThrow().source());
    }

    @Test
    void tooManyNodesIsAGraphError() {
        TestGraph graph = new TestGraph();
        for (int i = 0; i < 4; i++) {
            graph.node(i, NodeTypes.CONSTANT);
        }
        GraphError error = only(LogicCompiler.compile(graph.build(), 3));
        assertEquals(new GraphError(GraphError.GRAPH, "", LogicLang.TOO_MANY_NODES, "3"), error);
        assertFalse(LogicCompiler.compile(graph.build(), 4).paused(), "exactly the limit is fine");
    }

    @Test
    void unconnectedRequiredInputNamesThePortInPlainWords() {
        GraphError error = only(new TestGraph().node(7, NodeTypes.COMPARE).compile());
        assertEquals(7, error.node());
        assertEquals("value", error.port());
        assertEquals(PortType.NUMBER.connectKey(), error.key());
        assertEquals("Connect a number here", LogicLang.english().get(error.key()));
    }

    @Test
    void missingItemAsksForOneUnlessWired() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.ITEM_COUNT).compile());
        assertEquals("item", error.port());
        assertEquals(PortType.ITEM.connectKey(), error.key());
        assertEquals("Pick an item or connect one here", LogicLang.english().get(error.key()));

        CompileResult<String> wired = new TestGraph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "item").withItem("item", "iron"))
                .node(2, NodeTypes.ITEM_COUNT).wire(1, "value", 2, "item").compile();
        assertFalse(wired.paused(), () -> wired.errors().toString());
        Watch<String> watch = wired.graph().orElseThrow().watches().getFirst();
        assertEquals("iron", watch.item().orElseThrow(), "the wired constant's item is watched");
    }

    @Test
    void emptyRequiredTextMustBeFilledIn() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.NOTIFY, s -> s.withText("text", "  "))
                .node(2, NodeTypes.POWER_CHANGES).wire(2, "changed", 1, "do").compile());
        assertEquals("text", error.port());
        assertEquals(LogicLang.FILL_IN, error.key());
    }

    @Test
    void wireOfWrongTypeIsRejected() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.POWER_CHANGES).node(2, NodeTypes.COMPARE)
                .wire(1, "powered", 2, "value").compile());
        assertEquals(2, error.node());
        assertEquals(PortType.NUMBER.wrongTypeKey(), error.key());
    }

    @Test
    void twoWiresIntoOneInputAreRejected() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.CONSTANT).node(2, NodeTypes.CONSTANT)
                .node(3, NodeTypes.COMPARE).wire(1, "value", 3, "value").wire(2, "value", 3, "value").compile());
        assertEquals(LogicLang.TWO_WIRES, error.key());
    }

    @Test
    void brokenWiresAreReported() {
        GraphError missing = only(new TestGraph().node(1, NodeTypes.CONSTANT).node(2, NodeTypes.COMPARE)
                .wire(1, "value", 2, "value").wire(1, "value", 9, "value").compile());
        assertEquals(new GraphError(1, "", LogicLang.MISSING_NODE, ""), missing);
        GraphError port = only(new TestGraph().node(1, NodeTypes.CONSTANT).node(2, NodeTypes.COMPARE)
                .wire(1, "value", 2, "value").wire(1, "nope", 2, "value").compile());
        assertEquals(LogicLang.UNKNOWN_PORT, port.key());
    }

    @Test
    void duplicateIdsAreRejected() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.CONSTANT).node(1, NodeTypes.CONSTANT).compile());
        assertEquals(LogicLang.DUPLICATE_NODE, error.key());
    }

    @Test
    void loopWithoutMemoryIsRejectedOnEveryNodeInIt() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.NOT).node(2, NodeTypes.NOT)
                .node(3, NodeTypes.NOT).wire(1, "result", 2, "value").wire(2, "result", 1, "value")
                .wire(2, "result", 3, "value").compile();
        assertTrue(result.paused());
        assertEquals(List.of(new GraphError(1, "", LogicLang.LOOP, ""), new GraphError(2, "", LogicLang.LOOP, "")),
                result.errors(), "the node fed by the loop is not part of it");
    }

    @Test
    void selfLoopWithoutMemoryIsRejected() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.NOT).wire(1, "result", 1, "value").compile());
        assertEquals(LogicLang.LOOP, error.key());
    }

    @Test
    void loopThroughARememberNodeCompiles() {
        // A counter that resets itself at 3: counter -> compare -> counter.reset.
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.POWER_CHANGES).node(2, NodeTypes.COUNTER)
                .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 2)).wire(1, "changed", 2, "up")
                .wire(2, "count", 3, "value").wire(3, "turned_yes", 2, "reset").compile();
        assertFalse(result.paused(), () -> result.errors().toString());
    }

    @Test
    void movingNeedsALabelOnOneSide() {
        GraphError error = only(new TestGraph().node(1, NodeTypes.POWER_CHANGES)
                .node(2, NodeTypes.MOVE_ITEMS, s -> s.withItem("item", "cobble")).wire(1, "changed", 2, "do")
                .compile());
        assertEquals(LogicLang.MOVE_NOWHERE, error.key());
        assertFalse(new TestGraph().node(1, NodeTypes.POWER_CHANGES)
                .node(2, NodeTypes.MOVE_ITEMS, s -> s.withItem("item", "cobble").withText("to", "chest"))
                .wire(1, "changed", 2, "do").compile().paused());
    }

    @Test
    void nodesAreOrderedAfterTheirFeedersWhateverTheListOrder() {
        CompiledGraph<String> graph = new TestGraph().node(3, NodeTypes.NOT).node(2, NodeTypes.NOT)
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no")).wire(2, "result", 3, "value")
                .wire(1, "value", 2, "value").compiled();
        assertEquals(1, graph.nodes[0].id);
        assertEquals(2, graph.nodes[1].id);
        assertEquals(3, graph.nodes[2].id);
    }

    @Test
    void nodesWatchingTheSameThingShareAWatch() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "cobble"))
                .node(3, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble").withText("label", "chest"))
                .node(4, NodeTypes.POWER_CHANGES).compiled();
        assertEquals(3, graph.watches().size());
        assertEquals(WatchKind.ITEM_COUNT, graph.watches().get(0).kind());
        assertEquals("chest", graph.watches().get(1).label());
        assertEquals(WatchKind.POWER, graph.watches().get(2).kind());
    }

    @Test
    void switchBackIsKeptOnTheRightSideOfTheThreshold() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "a").withNumber("threshold", 100).withNumber("switch_back", 150))
                .node(2, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "a").withText("mode", "below").withNumber("threshold", 100)
                                .withNumber("switch_back", 50))
                .node(3, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "a").withNumber("threshold", 100)).compiled();
        assertEquals(100, graph.nodes[0].switchBack, "above: switching back above the limit is clamped to it");
        assertEquals(100, graph.nodes[1].switchBack, "below: switching back below the limit is clamped to it");
        assertEquals(100, graph.nodes[2].switchBack, "unset: the limit itself");
    }
}
