package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.editor.SettingRules;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Step sequences (M7L2 row 16): moving on, jumps, reset, time limits and their order, one step per run, memory through
 * a save, the step-is check and the card's STEPS block. The controller side is in the GameTests.
 */
class SequenceNodesTest {
    private static final int SEQ = 1;
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    /** A sequence (fill, wait, empty) and a counter on each "entered" event (21 to 23) and on "time ran out" (30). */
    private static TestGraph sequence(UnaryOperator<NodeSettings<String>> settings) {
        TestGraph graph = new TestGraph().node(SEQ, NodeTypes.SEQUENCE, s -> settings
                .apply(s.withText("name_1", "fill").withText("name_2", "Wait").withText("name_3", "empty")));
        for (int k = 1; k <= 3; k++) {
            graph.node(20 + k, NodeTypes.COUNTER).wire(SEQ, Sequences.entered(k), 20 + k, "up");
        }
        return graph.node(30, NodeTypes.COUNTER).wire(SEQ, Sequences.TIME_UP, 30, "up");
    }

    /** A device state read (node {@code id}) on label {@code label} into input {@code input} of the sequence. */
    private static TestGraph when(TestGraph graph, int id, String label, String input) {
        return graph.node(id, NodeTypes.DEVICE_STATE, s -> s.withText("label", label)).wire(id, "on", SEQ, input);
    }

    private static int watch(CompiledGraph<String> graph, String label) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.label().equals(label)) {
                return watch.index();
            }
        }
        throw new AssertionError("no watch on " + label);
    }

    private static void set(LogicEvaluator<String> evaluator, FakeContext context, String label, boolean on) {
        context.set(WatchKind.DEVICE, "", label, "", on ? 1 : 0);
        evaluator.changed(watch(evaluator.graph(), label));
    }

    private static long step(LogicEvaluator<String> evaluator) {
        return evaluator.step(evaluator.graph().position(SEQ));
    }

    private static long entered(LogicEvaluator<String> evaluator, int k) {
        return evaluator.value(evaluator.graph().slot(20 + k, "count"));
    }

    private static LogicMemory saved(LogicEvaluator<String> evaluator) {
        return LogicMemory.CODEC.parse(JsonOps.INSTANCE,
                LogicMemory.CODEC.encodeStart(JsonOps.INSTANCE, evaluator.memory()).getOrThrow()).getOrThrow();
    }

    // ---- moving on

    @Test
    void itStartsAtTheFirstStepAndGoesOnWhenItsConditionIsYes() {
        TestGraph graph = sequence(s -> s);
        when(graph, 11, "a", Sequences.next(1));
        when(graph, 12, "b", Sequences.next(2));
        when(graph, 13, "c", Sequences.next(3));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(1, step(evaluator), "the first run enters step 1");
        assertEquals(1, entered(evaluator, 1));
        assertEquals(1, evaluator.value(evaluator.graph().slot(SEQ, "step")));
        evaluator.run(context, 1);
        assertEquals(1, entered(evaluator, 1), "a look again enters nothing");
        set(evaluator, context, "b", true);
        evaluator.run(context, 2);
        assertEquals(1, step(evaluator), "only the current step's condition moves it");
        set(evaluator, context, "a", true);
        evaluator.run(context, 3);
        assertEquals(2, step(evaluator));
        assertEquals(1, entered(evaluator, 2));
        assertTrue(evaluator.wantsRerun(), "the new step's own condition is looked at next run");
        evaluator.run(context, 4);
        assertEquals(3, step(evaluator), "step 2's condition was already yes: one step per run");
        set(evaluator, context, "b", false);
        set(evaluator, context, "c", true);
        evaluator.run(context, 5);
        assertEquals(1, step(evaluator), "after the last step comes the first");
        assertEquals(2, entered(evaluator, 1));
        assertEquals(0, evaluator.value(evaluator.graph().slot(30, "count")), "no time limit ran out");
    }

    @Test
    void aGoToInputBeatsGoingOnAndHoldsTheStep() {
        TestGraph graph = sequence(s -> s);
        when(graph, 11, "a", Sequences.next(1));
        when(graph, 13, "c", Sequences.next(3));
        when(graph, 14, "g2", Sequences.go(2));
        when(graph, 15, "g3", Sequences.go(3));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        set(evaluator, context, "a", true);
        set(evaluator, context, "g3", true);
        evaluator.run(context, 1);
        assertEquals(3, step(evaluator), "a go-to beats the step's own condition");
        set(evaluator, context, "c", true);
        evaluator.run(context, 2);
        evaluator.run(context, 3);
        assertEquals(3, step(evaluator), "held there while the go-to is yes");
        assertEquals(1, entered(evaluator, 3), "held, not left and entered again");
        set(evaluator, context, "g2", true);
        evaluator.run(context, 4);
        assertEquals(2, step(evaluator), "two go-tos at once: the lower step wins");
        set(evaluator, context, "g2", false);
        set(evaluator, context, "g3", false);
        set(evaluator, context, "c", false);
        evaluator.run(context, 5);
        assertEquals(2, step(evaluator), "released, step 2 waits for its own condition");
    }

    @Test
    void resetBeatsEverythingAndEntersTheFirstStepAgain() {
        TestGraph graph = sequence(s -> s).node(40, NodeTypes.REDSTONE_CHANGES).wire(40, "changed", SEQ,
                Sequences.RESET);
        when(graph, 14, "g2", Sequences.go(2));
        CompiledGraph<String> compiled = graph.compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(compiled);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(1, entered(evaluator, 1));
        context.set(WatchKind.REDSTONE, "", "", "", 3);
        evaluator.changed(watch(compiled, ""));
        evaluator.run(context, 1);
        assertEquals(2, entered(evaluator, 1), "reset enters step 1 again although it is there");
        set(evaluator, context, "g2", true);
        context.set(WatchKind.REDSTONE, "", "", "", 5);
        evaluator.changed(watch(compiled, ""));
        evaluator.run(context, 2);
        assertEquals(1, step(evaluator), "reset beats a go-to in the same run");
        assertEquals(3, entered(evaluator, 1));
        evaluator.run(context, 3);
        assertEquals(2, step(evaluator), "the go-to pulls it there on the next run");
    }

    @Test
    void goOnInputsThatStayYesGoRoundOnceThenWaitForAChange() {
        TestGraph graph = sequence(s -> s);
        when(graph, 11, "a", Sequences.next(1));
        when(graph, 12, "b", Sequences.next(2));
        when(graph, 13, "c", Sequences.next(3));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext().set(WatchKind.DEVICE, "", "a", "", 1)
                .set(WatchKind.DEVICE, "", "b", "", 1).set(WatchKind.DEVICE, "", "c", "", 1);
        // The controller's loop: a run in each tick the evaluator has work.
        int runs = 0;
        for (long tick = 0; tick < 200; tick++) {
            if (evaluator.pending(tick)) {
                evaluator.run(context, tick);
                runs++;
            }
        }
        assertTrue(runs <= 5, "always-yes inputs ran the graph " + runs + " times in 200 ticks");
        assertEquals(1, step(evaluator), "one round, back at step 1, then waiting");
        assertEquals(2, entered(evaluator, 1), "entered at the start and after the round");
        assertEquals(1, entered(evaluator, 2));
        set(evaluator, context, "a", false);
        evaluator.run(context, 200);
        assertEquals(1, step(evaluator), "turning no moves nothing");
        set(evaluator, context, "a", true);
        evaluator.run(context, 201);
        assertEquals(2, step(evaluator), "turning yes again counts");
    }

    // ---- time limits

    @Test
    void aTimeLimitMovesOnThroughOneWakeAndFiresTimeRanOut() {
        TestGraph graph = sequence(s -> s.withNumber("timeout_1", 2).withNumber("timeout_2", 1));
        when(graph, 11, "a", Sequences.next(1));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        evaluator.run(context, 100);
        assertEquals(140, evaluator.nextWake(), "step 1's limit, 2 s from entering it");
        assertTrue(evaluator.wantsRerun(), "a new step is looked at once more");
        evaluator.run(context, 101);
        assertFalse(evaluator.pending(139), "then nothing until the limit");
        evaluator.run(context, 140);
        assertEquals(2, step(evaluator));
        assertEquals(1, evaluator.value(evaluator.graph().slot(30, "count")), "time ran out");
        assertEquals(160, evaluator.nextWake(), "step 2's own limit counts from entering it");
        evaluator.run(context, 141);
        evaluator.run(context, 160);
        assertEquals(3, step(evaluator));
        assertEquals(2, evaluator.value(evaluator.graph().slot(30, "count")));
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "step 3 has no limit: no wake");
    }

    @Test
    void aConditionYesWhenTheTimeRunsOutWinsOverTheLimit() {
        TestGraph graph = sequence(s -> s.withNumber("timeout_1", 2));
        when(graph, 11, "a", Sequences.next(1));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        set(evaluator, context, "a", true);
        evaluator.run(context, 40);
        assertEquals(2, step(evaluator));
        assertEquals(0, evaluator.value(evaluator.graph().slot(30, "count")), "moved by its condition, not by time");
    }

    @Test
    void aHeldStepWaitsPastItsLimitAndMovesOnOnceReleased() {
        TestGraph graph = sequence(s -> s.withNumber("timeout_1", 1));
        when(graph, 14, "g1", Sequences.go(1));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        FakeContext context = new FakeContext();
        context.set(WatchKind.DEVICE, "", "g1", "", 1);
        evaluator.run(context, 0);
        evaluator.run(context, 20);
        assertEquals(1, step(evaluator), "held at step 1 past its limit");
        set(evaluator, context, "g1", false);
        evaluator.run(context, 30);
        assertEquals(2, step(evaluator), "released after the limit: on at once");
        assertEquals(1, evaluator.value(evaluator.graph().slot(30, "count")));
    }

    // ---- memory

    @Test
    void theStepAndItsDueTimeSurviveASaveWithoutEnteringAgain() {
        TestGraph graph = sequence(s -> s.withNumber("timeout_2", 5));
        when(graph, 11, "a", Sequences.next(1));
        CompiledGraph<String> compiled = graph.compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(compiled);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        set(evaluator, context, "a", true);
        evaluator.run(context, 10);
        assertEquals(2, step(evaluator));
        LogicMemory memory = saved(evaluator);

        LogicEvaluator<String> loaded = new LogicEvaluator<>(compiled);
        loaded.load(memory);
        assertEquals(110, loaded.nextWake(), "the due time is back before the first run");
        context.set(WatchKind.DEVICE, "", "a", "", 0);
        loaded.run(context, 20);
        assertEquals(2, step(loaded));
        assertEquals(1, entered(loaded, 2), "a load enters nothing again (the counter kept its 1)");
        assertEquals(2, loaded.value(compiled.slot(SEQ, "step")), "the step output shows the saved step");
        loaded.run(context, 110);
        assertEquals(3, step(loaded), "the saved limit still moves it on");
    }

    @Test
    void aShorterSequenceDropsItsLaterPortsAndRestartsAStepPastTheEnd() {
        // Saved at step 4 of 4, then edited down to 3 steps.
        TestGraph four = sequence(s -> s.withNumber("steps", 4));
        when(four, 11, "a", Sequences.next(1));
        CompiledGraph<String> long4 = four.compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(long4);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        evaluator.load(new LogicMemory(List.of(new LogicMemory.Entry(SEQ, "sequence", List.of(4L, 0L, 0L, 0L)))));
        LogicMemory memory = saved(evaluator);

        LogicEvaluator<String> shorter = new LogicEvaluator<>(sequence(s -> s).compiled());
        shorter.load(memory);
        shorter.run(context, 1);
        assertEquals(1, step(shorter), "a step past the end starts again at step 1");

        CompileResult<String> stale = sequence(s -> s).node(12, NodeTypes.DEVICE_STATE, s -> s.withText("label", "d"))
                .wire(12, "on", SEQ, Sequences.next(4)).compile();
        assertTrue(stale.paused());
        assertEquals(LogicLang.UNKNOWN_PORT, stale.errors().getFirst().key(), "step 4's port is gone");
        assertEquals(5, NodeTypes.SEQUENCE.outputs(NodeSettings.empty()).size(), "step, time ran out, 3 entered");
        assertEquals(1 + 2 * 16, NodeTypes.SEQUENCE.inputs(NodeSettings.<String>empty().withNumber("steps", 99)).size(),
                "at most 16 steps");
    }

    // ---- step is

    @Test
    void stepIsNamesAStepByNameOrNumber() {
        for (String which : List.of("wait", " WAIT ", "2")) {
            TestGraph graph = sequence(s -> s).node(50, NodeTypes.STEP_IS, s -> s.withText("which", which)).wire(SEQ,
                    "step", 50, "step");
            when(graph, 11, "a", Sequences.next(1));
            CompiledGraph<String> compiled = graph.compiled();
            LogicEvaluator<String> evaluator = new LogicEvaluator<>(compiled);
            FakeContext context = new FakeContext();
            evaluator.run(context, 0);
            assertEquals(0, evaluator.value(compiled.slot(50, "result")), which);
            set(evaluator, context, "a", true);
            evaluator.run(context, 1);
            assertEquals(1, evaluator.value(compiled.slot(50, "result")), which);
        }
        CompileResult<String> unknown = sequence(s -> s).node(50, NodeTypes.STEP_IS, s -> s.withText("which", "clean"))
                .wire(SEQ, "step", 50, "step").compile();
        assertTrue(unknown.paused());
        assertEquals(LogicLang.NO_SUCH_STEP, unknown.errors().getFirst().key());
        assertEquals("clean", unknown.errors().getFirst().arg());
        assertTrue(sequence(s -> s).node(50, NodeTypes.STEP_IS, s -> s.withText("which", "4"))
                .wire(SEQ, "step", 50, "step").compile().paused(), "no step 4 in three");
    }

    // ---- settings panel and card

    @Test
    void thePanelShowsTheNamesAndLimitsOfTheStepsItHas() {
        NodeSettings<String> settings = NodeSettings.<String>empty().withNumber("steps", 2);
        NodeType type = NodeTypes.SEQUENCE;
        assertTrue(SettingRules.shows(type, settings, type.setting("name_2").orElseThrow()));
        assertTrue(SettingRules.shows(type, settings, type.setting("timeout_2").orElseThrow()));
        assertFalse(SettingRules.shows(type, settings, type.setting("name_3").orElseThrow()));
        assertFalse(SettingRules.shows(type, settings, type.setting("timeout_16").orElseThrow()));
    }

    /** The batch template's shape: fill until "tank" is full, wait 30 s, empty until it is empty. */
    static TestGraph batch() {
        return new TestGraph()
                .node(SEQ, NodeTypes.SEQUENCE,
                        s -> s.withText("name_1", "fill").withText("name_2", "wait").withText("name_3", "empty")
                                .withNumber("timeout_2", 30))
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "Water").withText("label", "tank"))
                .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 999))
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 1))
                .wire(2, "count", 3, "value").wire(2, "count", 4, "value").wire(3, "result", SEQ, Sequences.next(1))
                .wire(4, "result", SEQ, Sequences.next(3)).node(5, NodeTypes.STEP_IS, s -> s.withText("which", "fill"))
                .wire(SEQ, "step", 5, "step").node(6, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "inlet"))
                .wire(5, "result", 6, "on").node(7, NodeTypes.STEP_IS, s -> s.withText("which", "empty"))
                .wire(SEQ, "step", 7, "step").node(8, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "outlet"))
                .wire(7, "result", 8, "on").node(9, NodeTypes.NOTIFY, s -> s.withText("text", "Batch done"))
                .wire(SEQ, Sequences.entered(3), 9, "do");
    }

    @Test
    void theCardShowsTheStepsWithWhatEachDoes() {
        RuleCard card = RuleCards.build(batch().build(), LANG, item -> item);
        assertEquals(
                List.of("LET \"tank\": the count of Water in tank", "STEPS Step sequence: 3 steps in turn",
                        "1. fill: go on when \"tank\" is above 999", "·   while here: switch inlet on",
                        "2. wait: go on after 30 s", "3. empty: go on when \"tank\" is below 1",
                        "·   while here: switch outlet on", "·   on entering: notify \"Batch done\""),
                RuleCardsTest.text(card));
        assertFalse(card.custom(), "a sequence with its actions is a plain card");
        RuleCard.Line fill = card.lines().get(2);
        assertEquals(RuleCard.Live.STEP, fill.live());
        assertEquals("step:1", fill.liveEntry());
        assertTrue(fill.fields().stream().anyMatch(field -> field.setting().equals("name_1")),
                "the step's name can be edited on the card");
        assertTrue(card.lines().get(4).fields().stream().anyMatch(field -> field.setting().equals("timeout_2")),
                "and its time limit");
    }

    @Test
    void theBatchTemplateReadsAsSteps() {
        LogicGraph<String> graph = java.util.Objects.requireNonNull(
                dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates.all(id -> id).get("batch_process"))
                .graph();
        assertFalse(LogicCompiler.compile(graph, 64).paused());
        RuleCard card = RuleCards.build(graph, LANG, item -> item);
        assertFalse(card.custom(), RuleCardsTest.text(card).toString());
        assertEquals(List.of("LET \"batch\": the count of minecraft:cobblestone in batch",
                "STEPS Step sequence: 3 steps in turn", "1. fill: go on when \"batch\" is above 63",
                "·   while here: switch inlet on", "2. wait: go on after 30 s",
                "3. empty: go on when \"batch\" is below 1", "·   while here: switch outlet on",
                "·   on entering: notify \"Batch ready, emptying it\""), RuleCardsTest.text(card));
    }

    @Test
    void fewerStepsOnTheCardDropTheWiresOfTheStepsThatGo() {
        LogicGraph<String> graph = batch().build();
        LogicGraph<String> two = CardEdits.set(graph, SEQ, "steps", new SettingValue.Num<>(2)).graph().orElseThrow();
        assertTrue(two.wires().stream().noneMatch(wire -> wire.to() == SEQ && wire.input().equals("next_3")));
        assertTrue(two.wires().stream().noneMatch(wire -> wire.from() == SEQ && wire.output().equals("entered_3")));
        CompileResult<String> result = LogicCompiler.compile(two, 64);
        assertTrue(result.errors().stream().noneMatch(error -> error.key().equals(LogicLang.UNKNOWN_PORT)),
                result.errors().toString());
    }

    @Test
    void runsAllocateNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        TestGraph graph = sequence(s -> s.withNumber("timeout_2", 1));
        when(graph, 11, "a", Sequences.next(1));
        // Step 3 goes on with the same read, so every run moves: 1 by its condition, 2 by time, 3 by its condition.
        graph.wire(11, "on", SEQ, Sequences.next(3));
        graph.node(50, NodeTypes.STEP_IS, s -> s.withText("which", "empty")).wire(SEQ, "step", 50, "step");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph.compiled());
        // Reads answer yes without building map keys, so only the evaluator is measured.
        FakeContext context = new FakeContext() {
            @Override
            public long read(Watch<String> watch) {
                return 1;
            }
        };
        int a = watch(evaluator.graph(), "a");
        for (int i = 0; i < 2000; i++) {
            evaluator.changed(a);
            evaluator.run(context, i * 30L);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 2000; i < 22000; i++) {
            evaluator.changed(a);
            evaluator.run(context, i * 30L);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }
}
