package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Analog redstone (M7L2 chunk 6): redstone out and pulse take a strength 0 to 15 from a setting or a number input,
 * clamped; a yes/no input still gives the strength setting or 0; "redstone strength" reads 0 to 15.
 */
class AnalogRedstoneTest {
    /** A number constant (1) into the strength input of redstone out (2), labelled "lamp". */
    private static LogicEvaluator<String> strengthFrom(long number) {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.CONSTANT, s -> s.withNumber("value", number))
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "value", 2, "strength")
                .compiled();
        return new LogicEvaluator<>(graph);
    }

    @Test
    void numberInputIsClampedTo0To15() {
        long[] inputs = {7, 0, 15, 16, 40, -1, -3, SettingSpec.MAX_NUMBER, -SettingSpec.MAX_NUMBER};
        int[] expected = {7, 0, 15, 15, 15, 0, 0, 15, 0};
        for (int i = 0; i < inputs.length; i++) {
            FakeContext context = new FakeContext();
            strengthFrom(inputs[i]).run(context, 0);
            assertEquals(List.of("redstone lamp " + expected[i]), context.take(), "input " + inputs[i]);
        }
    }

    @Test
    void sameStrengthAgainSendsNothing() {
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = strengthFrom(9);
        evaluator.run(context, 0);
        assertEquals(List.of("redstone lamp 9"), context.take());
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "an unchanged strength is not sent again");
    }

    @Test
    void pulseStrengthInputIsClamped() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.CONSTANT, s -> s.withNumber("value", 99))
                .node(3, NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "lamp")).wire(1, "changed", 3, "do")
                .wire(2, "value", 3, "strength").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        context.take();
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("pulse lamp 15"), context.take());
    }

    /**
     * A graph saved before analog redstone: a yes/no into "on" and no strength setting. Yes gives 15 and no gives 0, as
     * before.
     */
    @Test
    void oldYesNoGraphStillGives15Or0() {
        String saved = """
                {"version": 1,
                 "nodes": [
                   {"id": 1, "type": "constant", "settings": {"kind": {"kind": "text", "value": "yes_no"},
                     "yes": {"kind": "flag", "value": true}}},
                   {"id": 2, "type": "redstone_out", "x": 96,
                     "settings": {"label": {"kind": "text", "value": "lamp"}}}],
                 "wires": [{"from": 1, "out": "value", "to": 2, "in": "on"}]}
                """;
        Codec<LogicGraph<String>> codec = LogicGraph.codec(Codec.STRING);
        LogicGraph<String> graph = codec.parse(JsonOps.INSTANCE, JsonParser.parseString(saved)).getOrThrow();
        FakeContext context = new FakeContext();
        new LogicEvaluator<>(LogicCompiler.compile(graph, 64).graph().orElseThrow()).run(context, 0);
        assertEquals(List.of("redstone lamp 15"), context.take(), "yes");

        LogicGraph<String> off = codec
                .parse(JsonOps.INSTANCE, JsonParser.parseString(saved.replace("\"value\": true", "\"value\": false")))
                .getOrThrow();
        new LogicEvaluator<>(LogicCompiler.compile(off, 64).graph().orElseThrow()).run(context, 0);
        assertEquals(List.of("redstone lamp 0"), context.take(), "no");
    }

    @Test
    void yesNoTogetherWithAStrengthSetting() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp").withNumber("strength", 6))
                .node(3, NodeTypes.COMPARE).wire(1, "strength", 3, "value").wire(3, "result", 2, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("redstone lamp 0"), context.take());
        context.set(WatchKind.REDSTONE, "", "", "", 4);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("redstone lamp 6"), context.take(), "yes gives the strength setting");
    }

    @Test
    void redstoneStrengthReadsAClampedSignal() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.REDSTONE_STRENGTH, s -> s.withText("label", "door")).compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        int strength = graph.slot(1, "strength");
        int powered = graph.slot(1, "powered");
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(strength));
        assertEquals(0, evaluator.value(powered));
        long[] inputs = {7, 15, 20, -2, 1};
        long[] expected = {7, 15, 15, 0, 1};
        for (int i = 0; i < inputs.length; i++) {
            context.set(WatchKind.REDSTONE, "", "door", "", inputs[i]);
            evaluator.changed(0);
            evaluator.run(context, i + 1);
            assertEquals(expected[i], evaluator.value(strength), "signal " + inputs[i]);
            assertEquals(expected[i] > 0 ? 1 : 0, evaluator.value(powered), "powered at " + inputs[i]);
        }
        assertEquals(List.of(), context.take(), "a read sends nothing");
    }

    @Test
    void sentences() {
        Map<String, String> english = LogicLang.english();
        UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        assertEquals("the redstone strength into door", NodeSentence.of(NodeTypes.REDSTONE_STRENGTH,
                NodeSettings.<String>empty().withText("label", "door"), lang, item -> item));
        assertEquals("send redstone 7 from lamp while On", NodeSentence.of(NodeTypes.REDSTONE_OUT,
                NodeSettings.<String>empty().withText("label", "lamp").withNumber("strength", 7), lang, item -> item));
    }
}
