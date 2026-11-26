package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Labels naming another network (M7L2 row 13): how a label splits, which nodes may name another network, what the
 * compiler refuses, the uses a compiled graph lists, the craft's network reaching the context, and the card sentence.
 */
class NetworkRefTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    @Test
    void labelsSplitAtTheLastMark() {
        assertEquals("North Base", NetworkRef.network("intake@North Base"));
        assertEquals("intake", NetworkRef.device("intake@North Base"));
        assertEquals("North Base", NetworkRef.network(" @ North Base "));
        assertEquals("", NetworkRef.device("@North Base"));
        assertEquals("", NetworkRef.network("intake"));
        assertEquals("intake", NetworkRef.device("intake"));
        // A label ending in @ names the own network, so a device label holding an @ can still be named.
        assertEquals("", NetworkRef.network("a@b@"));
        assertEquals("a@b", NetworkRef.device("a@b@"));
        assertTrue(NetworkRef.remote("x@B"));
        assertFalse(NetworkRef.remote("x@"));
        assertEquals("north base", NetworkRef.key(" North Base "));
        assertEquals("intake@North Base", NetworkRef.of("intake", "North Base"));
        assertEquals("intake", NetworkRef.of("intake", ""));
    }

    @Test
    void readsAndActionsOnAnotherNetworkCompileAndAreListed() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "iron").withText("label", "@North Base").withNumber("threshold", 10))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamps@north base"))
                .node(3, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "iron").withText("network", "@North Base"))
                .node(4, NodeTypes.MOVE_ITEMS, s -> s.withItem("item", "iron").withText("to", "intake@North Base"))
                .wire(1, "past", 2, "on").wire(1, "crossed", 3, "do").wire(1, "crossed", 4, "do").compiled();
        assertEquals(List.of(new CompiledGraph.NetworkUse(1, "label", "North Base"),
                new CompiledGraph.NetworkUse(2, "label", "north base"),
                new CompiledGraph.NetworkUse(3, "network", "North Base"),
                new CompiledGraph.NetworkUse(4, "to", "North Base")), graph.networks());
        Watch<String> watch = graph.watches().getFirst();
        assertEquals("@North Base", watch.label(), "the watch keeps the label; the controller splits it");
    }

    @Test
    void theCraftTakesItsNetwork() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.REQUEST_CRAFT,
                        s -> s.withItem("item", "iron").withNumber("amount", 4).withText("network", "@North Base"))
                .wire(1, "started", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("craft iron 4 @North Base"), context.actions);
    }

    @Test
    void nodesThatStayOnTheirNetworkAreRefused() {
        CompileResult<String> result = new TestGraph()
                .node(1, NodeTypes.REDSTONE_CHANGES, s -> s.withText("label", "lever@North Base")).compile();
        assertEquals(List.of(new GraphError(1, "label", LogicLang.NET_NOT_HERE, "North Base")), result.errors());
    }

    @Test
    void aCraftNamesOnlyANetwork() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "iron").withText("network", "smelters"))
                .wire(1, "started", 2, "do").compile();
        assertEquals(List.of(new GraphError(2, "network", LogicLang.NET_NETWORK_ONLY, "smelters")), result.errors());
    }

    @Test
    void aMoveStaysOnOneNetwork() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.MOVE_ITEMS,
                        s -> s.withItem("item", "iron").withText("from", "out@North Base").withText("to", "intake"))
                .wire(1, "started", 2, "do").compile();
        assertEquals(List.of(new GraphError(2, "to", LogicLang.NET_MOVE_SIDES, "North Base")), result.errors());
        assertTrue(
                new TestGraph().node(1, NodeTypes.GRAPH_START)
                        .node(2, NodeTypes.MOVE_ITEMS,
                                s -> s.withItem("item", "iron").withText("from", "@North Base").withText("to",
                                        "intake@NORTH BASE"))
                        .wire(1, "started", 2, "do").compile().errors().isEmpty(),
                "one network in any case");
    }

    @Test
    void cardsNameTheNetwork() {
        RuleCard card = RuleCards
                .build(new TestGraph()
                        .node(1, NodeTypes.COUNT_CROSSES,
                                s -> s.withItem("item", "iron").withText("label", "@North Base").withNumber("threshold",
                                        10))
                        .node(2, NodeTypes.REQUEST_CRAFT,
                                s -> s.withItem("item", "iron").withNumber("amount", 8).withText("network",
                                        "@North Base"))
                        .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamps@North Base"))
                        .wire(1, "crossed", 2, "do").wire(1, "past", 3, "on").build(), LANG, item -> item);
        List<String> text = RuleCardsTest.text(card);
        assertTrue(text.contains("WHEN the count of iron in North Base goes above 10"), text.toString());
        assertTrue(text.contains("THEN craft 8 iron on North Base"), text.toString());
        assertTrue(String.join("\n", text).contains("lamps on North Base"), text.toString());
    }
}
