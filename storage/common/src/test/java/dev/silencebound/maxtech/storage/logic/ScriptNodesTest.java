package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Script node types (KubeJS's {@code registerNodeTypes}) with plain Java callbacks standing in for scripts: they run in
 * the graph like built-in nodes, pay their cost, keep memory, read settings while unwired, and a failing one stops its
 * run with nothing carried out. A graph whose script type is gone still loads but does not compile; as text it is
 * refused as an unknown node type.
 */
class ScriptNodesTest {
    private static final Codec<LogicGraph<String>> CODEC = LogicGraph.codec(Codec.STRING);

    @AfterEach
    void forget() {
        ScriptNodes.clearForTests();
    }

    /** {@code value} clamped between {@code low} and {@code high}, each a number input with a setting. */
    private static NodeType clamp() {
        return ScriptNodes.register(ScriptNodeSpec.of("test:clamp").name("Clamp")
                .sentence("{value} kept between {low} and {high}").cost(5).numberInput("value", 0).numberInput("low", 0)
                .numberInput("high", 100).output("result", "number").evaluate(ctx -> ctx.setNumber("result",
                        Math.min(Math.max(ctx.number("value"), ctx.number("low")), ctx.number("high")))));
    }

    private static CompiledGraph<String> countInto(NodeType type, String input) {
        return new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, type, s -> s.withNumber("low", 10).withNumber("high", 50)).wire(1, "count", 2, input)
                .compiled();
    }

    @Test
    void scriptNodeRunsWithItsSettingsAndCost() {
        NodeType clamp = clamp();
        CompiledGraph<String> graph = countInto(clamp, "value");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        context.set(WatchKind.ITEM_COUNT, "a", "", "", 70);
        evaluator.run(context, 0);
        assertEquals(50, evaluator.value(graph.slot(2, "result")), "clamped to the high setting");
        assertEquals(1 + LogicEvaluator.READ_COST + 5, evaluator.lastCost(), "item count, its read, the script's 5");
        context.set(WatchKind.ITEM_COUNT, "a", "", "", 3);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(10, evaluator.value(graph.slot(2, "result")), "clamped to the low setting");
        assertEquals(-1, evaluator.scriptFailedNode());
    }

    @Test
    void yesNoInputReadsItsSettingWhileUnwired() {
        NodeType type = ScriptNodes.register(ScriptNodeSpec.of("test:pass").yesNoInput("on", true)
                .output("out", "yes_no").evaluate(ctx -> ctx.setYes("out", ctx.yes("on"))));
        CompiledGraph<String> on = new TestGraph().node(1, type).compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(on);
        evaluator.run(new FakeContext(), 0);
        assertEquals(1, evaluator.value(on.slot(1, "out")), "the default, yes");
        CompiledGraph<String> off = new TestGraph().node(1, type, s -> s.withFlag("on", false)).compiled();
        evaluator = new LogicEvaluator<>(off);
        evaluator.run(new FakeContext(), 0);
        assertEquals(0, evaluator.value(off.slot(1, "out")), "the setting, no");
    }

    @Test
    void memoryAndEventsWork() {
        NodeType counter = ScriptNodes.register(ScriptNodeSpec.of("test:counter").memory(1).input("up", "event")
                .output("count", "number").output("every_third", "event").evaluate(ctx -> {
                    if (ctx.fired("up")) {
                        long count = ctx.memory(0) + 1;
                        ctx.setMemory(0, count);
                        if (count % 3 == 0) {
                            ctx.fire("every_third");
                        }
                    }
                    ctx.setNumber("count", ctx.memory(0));
                }));
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES).node(2, counter)
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "third")).wire(1, "changed", 2, "up")
                .wire(2, "every_third", 3, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        for (int strength = 1; strength <= 3; strength++) {
            context.set(WatchKind.REDSTONE, "", "", "", strength);
            evaluator.changed(0);
            evaluator.run(context, strength);
        }
        assertEquals(3, evaluator.value(graph.slot(2, "count")));
        assertEquals(List.of("notify third 3"), context.take(), "the third change fired the notice");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(evaluator.memory());
        loaded.run(context, 10);
        assertEquals(3, loaded.value(graph.slot(2, "count")), "memory survives saving");
    }

    @Test
    void failingScriptStopsTheRunWithNothingDone() {
        NodeType broken = ScriptNodes.register(ScriptNodeSpec.of("test:broken").input("go", "event").evaluate(ctx -> {
            if (ctx.fired("go")) {
                throw new IllegalStateException("boom");
            }
        }));
        // The notice is ordered before the script node, so it has emitted when the script fails.
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "x")).node(3, broken).wire(1, "changed", 2, "do")
                .wire(1, "changed", 3, "go").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(-1, evaluator.scriptFailedNode(), "not fired yet");
        context.set(WatchKind.REDSTONE, "", "", "", 5);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(3, evaluator.scriptFailedNode());
        assertEquals("boom", evaluator.scriptFailure());
        assertEquals(List.of(), context.take(), "the notice of the failed run was not sent");
        assertEquals(LogicLang.WHY_SCRIPT_FAILED, evaluator.trace().reason(evaluator.trace().find(3)));
    }

    private static long recurse(long depth) {
        return depth < 0 ? depth : recurse(depth + 1) + 1;
    }

    @Test
    void endlessRecursionPausesInsteadOfCrashing() {
        NodeType deep = ScriptNodes.register(ScriptNodeSpec.of("test:deep").output("out", "number")
                .evaluate(ctx -> ctx.setNumber("out", recurse(0))));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(new TestGraph().node(1, deep).compiled());
        evaluator.run(new FakeContext(), 0);
        assertEquals(1, evaluator.scriptFailedNode());
        assertTrue(evaluator.scriptFailure().contains("stack overflow"), evaluator.scriptFailure());
    }

    @Test
    void wrongPortNameFailsWithItsName() {
        NodeType typo = ScriptNodes.register(
                ScriptNodeSpec.of("test:typo").output("out", "number").evaluate(ctx -> ctx.setNumber("ot", 1)));
        CompiledGraph<String> graph = new TestGraph().node(1, typo).compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(new FakeContext(), 0);
        assertEquals(1, evaluator.scriptFailedNode());
        assertTrue(evaluator.scriptFailure().contains("ot"), evaluator.scriptFailure());
        NodeType wrongType = ScriptNodes.register(
                ScriptNodeSpec.of("test:wrong_type").output("out", "number").evaluate(ctx -> ctx.setYes("out", true)));
        evaluator = new LogicEvaluator<>(new TestGraph().node(1, wrongType).compiled());
        evaluator.run(new FakeContext(), 0);
        assertTrue(evaluator.scriptFailure().contains("number"), evaluator.scriptFailure());
    }

    @Test
    void registerRefusesBadSpecs() {
        assertThrows(IllegalArgumentException.class,
                () -> ScriptNodes.register(ScriptNodeSpec.of("no_namespace").evaluate(ctx -> {
                })));
        assertThrows(IllegalArgumentException.class,
                () -> ScriptNodes.register(ScriptNodeSpec.of("Test:Upper").evaluate(ctx -> {
                })));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodes.register(ScriptNodeSpec.of("test:no_logic")));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodes
                .register(ScriptNodeSpec.of("test:twice").input("a", "number").input("a", "event").evaluate(ctx -> {
                })));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodeSpec.of("test:x").input("a", "item"));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodeSpec.of("test:x").cost(0));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodeSpec.of("test:x").memory(9));
        assertThrows(IllegalArgumentException.class, () -> ScriptNodeSpec.of("test:x").category("act"));
        ScriptNodeSpec many = ScriptNodeSpec.of("test:many").evaluate(ctx -> {
        });
        for (int i = 0; i <= ScriptNodeSpec.MAX_PORTS; i++) {
            many.input("in_" + i, "number");
        }
        assertThrows(IllegalArgumentException.class, () -> ScriptNodes.register(many));
        clamp();
        assertThrows(IllegalArgumentException.class, ScriptNodesTest::clamp, "registered twice");
        ScriptNodes.close();
        assertThrows(IllegalStateException.class,
                () -> ScriptNodes.register(ScriptNodeSpec.of("test:late").evaluate(ctx -> {
                })));
    }

    @Test
    void sentenceAndTextsComeFromTheScript() {
        NodeType clamp = clamp();
        Map<String, String> english = ScriptNodes.english();
        assertEquals("Clamp", english.get(clamp.nameKey()));
        assertEquals("Low", english.get(LogicLang.PREFIX + "node.test:clamp.port.low"));
        String sentence = NodeSentence.of(clamp,
                NodeSettings.<String>empty().withNumber("low", 10).withNumber("high", 20),
                key -> english.getOrDefault(key, key), item -> item);
        assertEquals("0 kept between 10 and 20", sentence);
        assertTrue(NodeTypes.all().contains(clamp), "the palette lists it");
        assertFalse(clamp.common(), "under All, not Common");
    }

    @Test
    void graphTextRoundTripsAScriptNode() {
        NodeType clamp = clamp();
        LogicGraph<String> graph = new TestGraph().node(1, clamp, s -> s.withNumber("high", 7)).build();
        String text = GraphText.write(CODEC, NbtOps.INSTANCE, "Clamp", graph).orElseThrow();
        GraphText.Read<String> read = GraphText.read(text, 16_384, 64, CODEC, NbtOps.INSTANCE);
        assertTrue(read.ok(), read.error());
        assertEquals(clamp, read.graph().orElseThrow().nodes().getFirst().type());
        ScriptNodes.clearForTests();
        GraphText.Read<String> missing = GraphText.read(text, 16_384, 64, CODEC, NbtOps.INSTANCE);
        assertEquals(GraphText.UNKNOWN_NODE, missing.error(), "without the script it is an unknown node type");
    }

    @Test
    void savedGraphWithAMissingScriptStillLoads() {
        NodeType clamp = clamp();
        LogicGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, clamp, s -> s.withNumber("high", 7)).wire(1, "count", 2, "value").build();
        JsonElement saved = CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
        ScriptNodes.clearForTests();
        LogicGraph<String> loaded = CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        LogicNode<String> node = loaded.nodes().get(1);
        assertTrue(node.type().missingScript());
        assertEquals("test:clamp", node.type().id(), "saved again under the same id");
        assertEquals(7, node.settings().number("high").orElseThrow(), "settings kept");
        assertEquals(1, loaded.wires().size(), "wires kept");
        CompileResult<String> result = LogicCompiler.compile(loaded, 64);
        assertTrue(result.paused());
        assertTrue(result.errorsFor(2).stream().anyMatch(error -> error.key().equals(LogicLang.SCRIPT_MISSING)));
        assertEquals(saved, CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow(), "saved unchanged");

        JsonObject unknown = saved.deepCopy().getAsJsonObject();
        JsonArray nodes = unknown.getAsJsonArray("nodes");
        nodes.get(1).getAsJsonObject().addProperty("type", "not_a_type");
        assertTrue(CODEC.parse(JsonOps.INSTANCE, unknown).isError(), "an unknown built-in id still fails");
    }
}
