package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Bundled redstone channels in logic (M7L2 row 22): the channel setting on redstone out, pulse, redstone changes and
 * redstone strength replaces the label; reads become a channel watch by number, writes a channel action.
 */
class RedstoneChannelNodesTest {
    private static final int RED = 14;
    private static final int BLUE = 11;

    @Test
    void redstoneOutToAChannelNeedsNoLabel() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", 7))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("channel", "red")).wire(1, "value", 2, "strength")
                .compile();
        assertEquals(List.of(), result.errors(), "a channel replaces the required label");
        FakeContext context = new FakeContext();
        new LogicEvaluator<>(result.graph().orElseThrow()).run(context, 0);
        assertEquals(List.of("channel " + RED + " 7"), context.take());
    }

    @Test
    void withoutChannelTheLabelIsStillRequired() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.REDSTONE_OUT).compile();
        assertTrue(result.paused(), "no label and no channel");
    }

    @Test
    void labelAndChannelWritesAreSeparateActions() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", 5))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp"))
                .node(3, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp").withText("channel", "blue"))
                .wire(1, "value", 2, "strength").wire(1, "value", 3, "strength").compiled();
        new LogicEvaluator<>(graph).run(context, 0);
        assertEquals(List.of("redstone lamp 5", "channel " + BLUE + " 5"), context.take());
    }

    @Test
    void readsWatchTheChannelByNumber() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("channel", "red"))
                .node(2, NodeTypes.REDSTONE_CHANGES, s -> s.withText("channel", "red"))
                .node(3, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("channel", "blue"))
                .node(4, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("label", "door")).compiled();
        List<Watch<String>> watches = graph.watches();
        assertEquals(3, watches.size(), "the two red reads share a watch");
        assertEquals(WatchKind.CHANNEL, watches.get(0).kind());
        assertEquals(RED, watches.get(0).number());
        assertEquals("", watches.get(0).label());
        assertEquals(BLUE, watches.get(1).number());
        assertEquals(WatchKind.REDSTONE, watches.get(2).kind());

        FakeContext context = new FakeContext().set(WatchKind.CHANNEL, "", "", "", 9);
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(9, evaluator.value(graph.slot(1, "strength")));
        assertEquals(1, evaluator.value(graph.slot(1, "powered")));
        assertEquals(9, evaluator.value(graph.slot(2, "strength")));
    }

    @Test
    void pulseOnAChannelTurnsOffAfterItsTicks() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.PULSE_REDSTONE,
                        s -> s.withText("channel", "red").withNumber("ticks", 5).withNumber("strength", 6))
                .wire(1, "changed", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        context.take();
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("pulse channel " + RED + " 6"), context.take());
        assertEquals(0, evaluator.pulsing().size(), "a channel pulse has no label to end");
        assertEquals(1 << RED, evaluator.pulsingChannels());
        evaluator.run(context, 6);
        assertEquals(List.of("pulse channel " + RED + " 6 off"), context.take());
        assertEquals(0, evaluator.pulsingChannels());
    }

    @Test
    void channelIdsFollowDyeColourIds() {
        assertEquals("white", NodeTypes.channelId(0));
        assertEquals("red", NodeTypes.channelId(RED));
        assertEquals("black", NodeTypes.channelId(15));
        assertEquals(-1, NodeTypes.channel(NodeTypes.REDSTONE_OUT, NodeSettings.<String>empty()));
        assertEquals(-1, NodeTypes.channel(NodeTypes.NOTIFY, NodeSettings.<String>empty().withText("channel", "red")));
        assertFalse(NodeTypes.byId("redstone_out").orElseThrow().setting("channel").isEmpty());
    }

    @Test
    void sentencesNameTheChannel() {
        Map<String, String> english = LogicLang.english();
        UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        assertEquals("set the red channel to 7 while On", NodeSentence.of(NodeTypes.REDSTONE_OUT,
                NodeSettings.<String>empty().withText("channel", "red").withNumber("strength", 7), lang, item -> item));
        assertEquals("the value of the blue channel", NodeSentence.of(NodeTypes.REDSTONE_STRENGTH,
                NodeSettings.<String>empty().withText("channel", "blue"), lang, item -> item));
        assertEquals("when the light blue channel changes", NodeSentence.of(NodeTypes.REDSTONE_CHANGES,
                NodeSettings.<String>empty().withText("channel", "light_blue"), lang, item -> item));
        assertEquals("pulse the red channel at 15 for 10 ticks", NodeSentence.of(NodeTypes.PULSE_REDSTONE,
                NodeSettings.<String>empty().withText("channel", "red"), lang, item -> item));
        // Without a channel the label sentence stays as it was.
        assertEquals("send redstone 7 from lamp while On", NodeSentence.of(NodeTypes.REDSTONE_OUT,
                NodeSettings.<String>empty().withText("label", "lamp").withNumber("strength", 7), lang, item -> item));
    }
}
