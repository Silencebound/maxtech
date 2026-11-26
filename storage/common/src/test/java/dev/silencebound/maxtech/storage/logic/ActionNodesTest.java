package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.editor.SettingRules;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

/**
 * The action node types of batch 3: cancel craft, pulse redstone, write to display and set port filter. Each sends its
 * action through the context at the end of a run; the pulse switches off through one scheduled wake and remembers when
 * across a save.
 */
class ActionNodesTest {
    /** A redstone-changes node (1) into {@code type} (2) at its "do" input. */
    private static CompiledGraph<String> onRedstone(NodeType type, UnaryOperator<NodeSettings<String>> settings) {
        return new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES).node(2, type, settings).wire(1, "changed", 2, "do")
                .compiled();
    }

    /** Changes the controller's redstone, so the redstone-changes node fires on the next run. */
    private static void flip(LogicEvaluator<String> evaluator, FakeContext context) {
        String key = FakeContext.key(WatchKind.REDSTONE, "", "", "");
        context.values.put(key, context.values.getOrDefault(key, 0L) == 0 ? 15L : 0L);
        evaluator.changed(0);
    }

    /** A first run, which only looks (change events fire on a change after the first look). */
    private static LogicEvaluator<String> started(CompiledGraph<String> graph, FakeContext context, long time) {
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, time);
        context.take();
        return evaluator;
    }

    // ---- cancel craft

    @Test
    void cancelCraftSendsItsItemAndScope() {
        FakeContext context = new FakeContext();
        LogicEvaluator<String> all = started(onRedstone(NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", "stick")),
                context, 0);
        flip(all, context);
        all.run(context, 1);
        assertEquals(List.of("cancel stick all"), context.take());
        LogicEvaluator<String> own = started(
                onRedstone(NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", "stick").withText("scope", "own")), context,
                0);
        flip(own, context);
        own.run(context, 1);
        assertEquals(List.of("cancel stick own"), context.take());
        own.run(context, 2);
        assertEquals(List.of(), context.take(), "only when it fires");
    }

    @Test
    void cancelCraftNeedsAnItem() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.CANCEL_CRAFT).wire(1, "changed", 2, "do").compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> "item".equals(error.port())), result.errors()::toString);
    }

    // ---- pulse redstone

    @Test
    void pulseTurnsOnThenOffThroughOneWake() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = onRedstone(NodeTypes.PULSE_REDSTONE,
                s -> s.withText("label", "lamp").withNumber("ticks", 30).withNumber("strength", 9));
        LogicEvaluator<String> evaluator = started(graph, context, 100);
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "no wake before a pulse");
        flip(evaluator, context);
        evaluator.run(context, 110);
        assertEquals(List.of("pulse lamp 9"), context.take());
        assertEquals(140, evaluator.nextWake(), "off once its ticks have passed");
        assertEquals(140, evaluator.due(graph.position(2)), "the live view sees when it goes off");
        assertFalse(evaluator.pending(139));
        evaluator.run(context, 140);
        assertEquals(List.of("pulse lamp 9 off"), context.take());
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake());
        assertEquals(0, evaluator.due(graph.position(2)));
    }

    @Test
    void aPulseWhileOnStartsAgainOrIsIgnored() {
        FakeContext context = new FakeContext();
        LogicEvaluator<String> restart = started(
                onRedstone(NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "lamp").withNumber("ticks", 20)), context,
                0);
        flip(restart, context);
        restart.run(context, 10);
        flip(restart, context);
        restart.run(context, 25);
        assertEquals(List.of("pulse lamp 15", "pulse lamp 15"), context.take());
        assertEquals(45, restart.nextWake(), "restarted: off 20 ticks after the second pulse");

        LogicEvaluator<String> ignore = started(
                onRedstone(NodeTypes.PULSE_REDSTONE,
                        s -> s.withText("label", "lamp").withNumber("ticks", 20).withText("again", "ignore")),
                context, 0);
        flip(ignore, context);
        ignore.run(context, 10);
        flip(ignore, context);
        ignore.run(context, 25);
        assertEquals(List.of("pulse lamp 15"), context.take(), "the second pulse is ignored");
        assertEquals(30, ignore.nextWake(), "off 20 ticks after the first");
        assertEquals(LogicLang.WHY_PULSE_ON, ignore.trace().reason(0));
        ignore.run(context, 30);
        assertEquals(List.of("pulse lamp 15 off"), context.take());
    }

    @Test
    void aPulseDueInTheSameRunAsANewOneEndsOn() {
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = started(
                onRedstone(NodeTypes.PULSE_REDSTONE,
                        s -> s.withText("label", "lamp").withNumber("ticks", 20).withText("again", "ignore")),
                context, 0);
        flip(evaluator, context);
        evaluator.run(context, 10);
        context.take();
        // Off is due at 30 and a new pulse comes in the same run: the switch ends on, for a new 20 ticks.
        flip(evaluator, context);
        evaluator.run(context, 30);
        assertEquals(List.of("pulse lamp 15"), context.take(), "one action, the last value of the run");
        assertEquals(50, evaluator.nextWake());
    }

    @Test
    void aPulseRunningAtASaveStillGoesOff() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = onRedstone(NodeTypes.PULSE_REDSTONE,
                s -> s.withText("label", "lamp").withNumber("ticks", 40));
        LogicEvaluator<String> before = started(graph, context, 0);
        flip(before, context);
        before.run(context, 10);
        context.take();
        LogicMemory saved = LogicMemory.CODEC
                .parse(JsonOps.INSTANCE, LogicMemory.CODEC.encodeStart(JsonOps.INSTANCE, before.memory()).getOrThrow())
                .getOrThrow();
        LogicEvaluator<String> after = new LogicEvaluator<>(graph);
        after.load(saved);
        assertEquals(50, after.nextWake(), "the end of the pulse was saved");
        after.run(context, 20);
        assertEquals(List.of(), context.take(), "still on after the load: nothing sent before the end");
        after.run(context, 50);
        assertEquals(List.of("pulse lamp 15 off"), context.take());
    }

    @Test
    void pulseStrengthCanComeFromAWire() {
        FakeContext context = new FakeContext().count("a", 4);
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(3, NodeTypes.PULSE_REDSTONE, s -> s.withText("label", "lamp")).wire(1, "changed", 3, "do")
                .wire(2, "count", 3, "strength").compiled();
        LogicEvaluator<String> evaluator = started(graph, context, 0);
        flip(evaluator, context);
        evaluator.run(context, 5);
        assertEquals(List.of("pulse lamp 4"), context.take());
    }

    // ---- write to display

    @Test
    void writeDisplayFillsInItsInputs() {
        assertEquals("Cobble: 12 of 64 {count:stone}",
                LogicContext.fillValues("Cobble: {a} of {b} {count:stone}", 12, 64));
        assertEquals("no braces", LogicContext.fillValues("no braces", 1, 2));
        assertEquals("{c} -5", LogicContext.fillValues("{c} {a}", -5, 0));
    }

    @Test
    void writeDisplayWithoutAnEventWritesWhenItsValueChanges() {
        FakeContext context = new FakeContext().count("cobble", 12);
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "Cobble: {a}"))
                .wire(1, "count", 2, "a").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(List.of("display board Cobble: 12"), context.take(), "written on the first run");
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "nothing changed, nothing written");
        context.count("cobble", 30);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(List.of("display board Cobble: 30"), context.take());
    }

    @Test
    void writeDisplayWithAnEventWritesOnlyThen() {
        FakeContext context = new FakeContext().count("cobble", 12);
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(3, NodeTypes.WRITE_DISPLAY,
                        s -> s.withText("label", "board").withText("text", "{a}/{b}").withNumber("b", 100))
                .wire(1, "changed", 3, "do").wire(2, "count", 3, "a").compiled();
        LogicEvaluator<String> evaluator = started(graph, context, 0);
        context.count("cobble", 30);
        evaluator.changed(1);
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "a value change alone writes nothing");
        flip(evaluator, context);
        evaluator.run(context, 2);
        assertEquals(List.of("display board 30/100"), context.take());
    }

    @Test
    void twoWritesToOneLabelInARunSendTheLast() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "first"))
                .node(3, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "second"))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").compiled();
        LogicEvaluator<String> evaluator = started(graph, context, 0);
        flip(evaluator, context);
        evaluator.run(context, 1);
        assertEquals(List.of("display board second"), context.take());
    }

    // ---- set port filter

    @Test
    void setFilterSendsSlotItemAndMode() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_FILTER,
                        s -> s.withText("label", "out").withNumber("slot", 3).withItem("item", "iron"))
                .node(3, NodeTypes.SET_FILTER,
                        s -> s.withText("label", "out").withNumber("slot", 4).withText("port_mode", "export"))
                .node(4, NodeTypes.SET_FILTER,
                        s -> s.withText("label", "out").withNumber("slot", 0).withText("port_mode", "import"))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").wire(1, "changed", 4, "do").compiled();
        LogicEvaluator<String> evaluator = started(graph, context, 0);
        flip(evaluator, context);
        evaluator.run(context, 1);
        assertEquals(List.of("filter out iron 3 0", "filter out null 4 2", "filter out null 0 1"), context.take(),
                "different slots on one label are each sent; no item clears a slot");
    }

    @Test
    void setFilterMustChangeSomething() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_FILTER, s -> s.withText("label", "out").withNumber("slot", 0))
                .wire(1, "changed", 2, "do").compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> LogicLang.FILTER_NOTHING.equals(error.key())),
                result.errors()::toString);
    }

    @Test
    void setFilterShowsItsItemOnlyForASlot() {
        SettingSpec item = NodeTypes.SET_FILTER.setting("item").orElseThrow();
        assertTrue(SettingRules.shows(NodeTypes.SET_FILTER, NodeSettings.<String>empty(), item), "slot 1 by default");
        assertFalse(SettingRules.shows(NodeTypes.SET_FILTER, NodeSettings.<String>empty().withNumber("slot", 0), item));
    }

    // ---- shared

    @Test
    void theActionsAreNotCommonAndCostTwo() {
        for (NodeType type : List.of(NodeTypes.CANCEL_CRAFT, NodeTypes.PULSE_REDSTONE, NodeTypes.WRITE_DISPLAY,
                NodeTypes.SET_FILTER)) {
            assertFalse(type.common(), type + " is not in the Common set");
            assertEquals(2, type.cost(), type + " costs as other actions");
            assertEquals(NodeCategory.ACT, type.category());
        }
    }

    @Test
    void sentencesReadPlainly() {
        Map<String, String> english = LogicLang.english();
        UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        assertEquals("cancel the crafts of stick (Only this graph's)", NodeSentence.of(NodeTypes.CANCEL_CRAFT,
                NodeSettings.<String>empty().withItem("item", "stick").withText("scope", "own"), lang, item -> item));
        assertEquals("pulse redstone 15 from lamp for 10 ticks", NodeSentence.of(NodeTypes.PULSE_REDSTONE,
                NodeSettings.<String>empty().withText("label", "lamp"), lang, item -> item));
        assertEquals("show \"Cobble: {a}\" on board",
                NodeSentence.of(NodeTypes.WRITE_DISPLAY,
                        NodeSettings.<String>empty().withText("label", "board").withText("text", "Cobble: {a}"), lang,
                        item -> item));
        assertEquals("set filter slot 2 of out to iron, port mode Export",
                NodeSentence.of(
                        NodeTypes.SET_FILTER, NodeSettings.<String>empty().withText("label", "out")
                                .withNumber("slot", 2).withItem("item", "iron").withText("port_mode", "export"),
                        lang, item -> item));
    }

    @Test
    void graphsWithTheActionsSurviveTheTextFormat() {
        Codec<LogicGraph<String>> codec = LogicGraph.codec(Codec.STRING);
        LogicGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.CANCEL_CRAFT, s -> s.withItem("item", "minecraft:stick").withText("scope", "own"))
                .node(3, NodeTypes.PULSE_REDSTONE,
                        s -> s.withText("label", "lamp").withNumber("ticks", 7).withText("again", "ignore"))
                .node(4, NodeTypes.WRITE_DISPLAY, s -> s.withText("label", "board").withText("text", "<red>{a}</red>"))
                .node(5, NodeTypes.SET_FILTER,
                        s -> s.withText("label", "out").withNumber("slot", 9).withItem("item", "minecraft:iron_ingot")
                                .withText("port_mode", "export"))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").wire(1, "changed", 5, "do").build();
        String text = GraphText.write(codec, NbtOps.INSTANCE, "Actions", graph).orElseThrow();
        GraphText.Read<String> read = GraphText.read(text, 16_384, 64, codec, NbtOps.INSTANCE);
        assertTrue(read.ok(), read.error());
        assertEquals(graph, read.graph().orElseThrow());
        assertFalse(LogicCompiler.compile(read.graph().orElseThrow(), 64).paused(), "and still compiles");
    }
}
