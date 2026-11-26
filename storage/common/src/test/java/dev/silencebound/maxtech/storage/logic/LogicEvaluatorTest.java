package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogicEvaluatorTest {
    private static int watch(CompiledGraph<String> graph, WatchKind kind, String item) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == kind && watch.item().orElse("").equals(item)) {
                return watch.index();
            }
        }
        throw new AssertionError("No watch " + kind + " " + item);
    }

    @Test
    void onlyNodesDownstreamOfAChangeRunAndCostIsCounted() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10))
                .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "a"))
                .node(4, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "dirt"))
                .node(5, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10))
                .node(6, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "b")).wire(1, "count", 2, "value")
                .wire(2, "result", 3, "on").wire(4, "count", 5, "value").wire(5, "result", 6, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("cobble", 5).count("dirt", 5);

        evaluator.run(context, 0);
        assertEquals(2, context.reads, "the first run reads every watch");
        assertEquals(2 * LogicEvaluator.READ_COST + 2 * (1 + 1 + 2), evaluator.lastCost());
        assertEquals(graph.maxCost(), evaluator.lastCost(), "the first run runs everything");
        assertEquals(List.of("switch a false", "switch b false"), context.take(), "the first run sets the devices");
        assertFalse(evaluator.pending(0), "nothing changed since");

        context.count("cobble", 6);
        evaluator.changed(watch(graph, WatchKind.ITEM_COUNT, "cobble"));
        assertTrue(evaluator.pending(0));
        evaluator.run(context, 1);
        assertEquals(3, context.reads, "only the changed watch is read again");
        assertEquals(LogicEvaluator.READ_COST + 1 + 1, evaluator.lastCost(), "count and compare, nothing else");
        assertEquals(List.of(), context.take());
        RunTrace trace = evaluator.trace();
        assertEquals(1, trace.size());
        assertEquals(2, trace.node(0), "the compare stopped the run");
        assertEquals(LogicLang.WHY_STILL, trace.reason(0));
        assertEquals(6, trace.first(0));
        assertEquals(10, trace.second(0));

        context.count("cobble", 11);
        evaluator.changed(watch(graph, WatchKind.ITEM_COUNT, "cobble"));
        evaluator.run(context, 2);
        assertEquals(LogicEvaluator.READ_COST + 1 + 1 + 2, evaluator.lastCost());
        assertEquals(List.of("switch a true"), context.take());
    }

    @Test
    void crossingWithSwitchBackDoesNotFlicker() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "cobble").withText("mode", "below").withNumber("threshold", 100)
                                .withNumber("switch_back", 200))
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "cobble").withNumber("amount", 64))
                .wire(1, "crossed", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        int crafts = 0;
        long[] counts = {150, 99, 120, 99, 150, 199, 210, 150, 90, 95};
        boolean[] expected = {false, true, false, false, false, false, false, false, true, false};
        for (int i = 0; i < counts.length; i++) {
            context.count("cobble", counts[i]);
            evaluator.changed(0);
            evaluator.run(context, i);
            List<String> actions = context.take();
            assertEquals(expected[i] ? List.of("craft cobble 64") : List.of(), actions, "at count " + counts[i]);
            crafts += actions.size();
        }
        assertEquals(2, crafts, "one request per real crossing");
    }

    @Test
    void compareAboveHoldsUntilTheSwitchBackValue() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 100).withNumber("switch_back", 80))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "on"))
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "off")).wire(1, "count", 2, "value")
                .wire(2, "turned_yes", 3, "do").wire(2, "turned_no", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        long[] counts = {50, 101, 90, 99, 101, 81, 80, 95, 100, 101};
        String[] expected = {"", "on", "", "", "", "", "off", "", "", "on"};
        for (int i = 0; i < counts.length; i++) {
            context.count("a", counts[i]);
            evaluator.changed(0);
            evaluator.run(context, i);
            assertEquals(expected[i].isEmpty() ? List.of() : List.of("notify " + expected[i] + " 3"), context.take(),
                    "at count " + counts[i]);
        }
    }

    @Test
    void compareFiresTurnedYesAndTurnedNoOnlyOnChange() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.COMPARE,
                        s -> s.withText("mode", "between").withNumber("threshold", 20).withNumber("upper", 10))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "in"))
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "out")).wire(1, "count", 2, "value")
                .wire(2, "turned_yes", 3, "do").wire(2, "turned_no", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        long[] counts = {5, 15, 10, 21, 25, 20};
        String[] expected = {"", "notify in 3", "", "notify out 3", "", "notify in 3"};
        for (int i = 0; i < counts.length; i++) {
            context.count("a", counts[i]);
            evaluator.changed(0);
            evaluator.run(context, i);
            assertEquals(expected[i].isEmpty() ? List.of() : List.of(expected[i]), context.take(),
                    "at count " + counts[i] + " (bounds swapped by the compiler: 10 to 20)");
        }
    }

    @Test
    void latchSetsAndResetWins() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "b")).node(3, NodeTypes.LATCH)
                .node(4, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "finished", 3, "set")
                .wire(2, "finished", 3, "reset").wire(3, "state", 4, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        int a = watch(graph, WatchKind.JOB_FINISHED, "a");
        int b = watch(graph, WatchKind.JOB_FINISHED, "b");
        evaluator.run(context, 0);
        assertEquals(List.of("redstone lamp 0"), context.take());
        evaluator.signal(a);
        evaluator.run(context, 1);
        assertEquals(List.of("redstone lamp 15"), context.take());
        evaluator.signal(a);
        evaluator.run(context, 2);
        assertEquals(List.of(), context.take(), "set again: no change, no action");
        evaluator.signal(a);
        evaluator.signal(b);
        evaluator.run(context, 3);
        assertEquals(List.of("redstone lamp 0"), context.take(), "set and reset together: reset wins");
        evaluator.run(context, 4);
        assertEquals(List.of(), context.take(), "a signal fires once");
    }

    @Test
    void counterCountsAndALoopThroughItStepsOncePerRun() {
        // The counter resets itself when it reaches 3, through a compare: the reset reaches it one run later.
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED)
                .node(2, NodeTypes.COUNTER, s -> s.withNumber("step", 1))
                .node(3, NodeTypes.COMPARE, s -> s.withText("mode", "equal").withNumber("threshold", 3))
                .wire(1, "finished", 2, "up").wire(2, "count", 3, "value").wire(3, "turned_yes", 2, "reset").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        int count = graph.slot(2, "count");
        evaluator.run(context, 0);
        for (int i = 1; i <= 3; i++) {
            evaluator.signal(0);
            evaluator.run(context, i);
            assertEquals(i, evaluator.value(count));
        }
        assertTrue(evaluator.wantsRerun(), "the reset travels the loop to the next run");
        assertTrue(evaluator.pending(3));
        evaluator.run(context, 4);
        assertEquals(0, evaluator.value(count), "reset one run later");
        // The compare changed again (back to no), so the counter is looked at once more; nothing changes there.
        evaluator.run(context, 5);
        assertEquals(0, evaluator.value(count));
        assertFalse(evaluator.wantsRerun());
        assertFalse(evaluator.pending(6), "the loop settled");
    }

    @Test
    void duplicateActionsInOneRunAreDropped() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED)
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "gear").withNumber("amount", 1))
                .node(3, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "gear").withNumber("amount", 5))
                .node(4, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "plate")).wire(1, "finished", 2, "do")
                .wire(1, "finished", 3, "do").wire(1, "finished", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        evaluator.signal(0);
        evaluator.run(context, 1);
        assertEquals(List.of("craft gear 1", "craft plate 1"), context.take());
        assertEquals(2, evaluator.lastActions());
        assertEquals(3, evaluator.trace().node(0));
        assertEquals(LogicLang.WHY_ALREADY_DONE, evaluator.trace().reason(0));

        context.runningCrafts.add("gear");
        evaluator.signal(0);
        evaluator.run(context, 2);
        assertEquals(List.of("craft plate 1"), context.take(), "no second craft while one is running");
        assertEquals(LogicLang.WHY_CRAFT_RUNNING, evaluator.trace().reason(evaluator.trace().find(2)));
        assertEquals(1 + 3 * (2 + LogicEvaluator.READ_COST), evaluator.lastCost(), "each craft check is a read");
    }

    @Test
    void settingActionsInOneRunKeepTheLastValue() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no").withFlag("yes", true))
                .node(2, NodeTypes.NOT).node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "door"))
                .node(4, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "door")).wire(1, "value", 3, "on")
                .wire(1, "value", 2, "value").wire(2, "result", 4, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("switch door false"), context.take(), "one action per device and run, the last wins");
    }

    @Test
    void whyTraceNamesTheIfThatStopped() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED)
                .node(2, NodeTypes.DEVICE_STATE, s -> s.withText("label", "door")).node(3, NodeTypes.IF)
                .node(4, NodeTypes.NOTIFY, s -> s.withText("text", "done").withFlag("chat", true))
                .wire(1, "finished", 3, "when").wire(2, "on", 3, "condition").wire(3, "then", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        evaluator.signal(watch(graph, WatchKind.JOB_FINISHED, ""));
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take());
        assertEquals(1, evaluator.trace().size());
        assertEquals(3, evaluator.trace().node(0));
        assertEquals(LogicLang.WHY_IF_NO, evaluator.trace().reason(0));

        context.set(WatchKind.DEVICE, "", "door", "", 1);
        evaluator.changed(watch(graph, WatchKind.DEVICE, ""));
        evaluator.signal(watch(graph, WatchKind.JOB_FINISHED, ""));
        evaluator.run(context, 2);
        int channels = LogicContext.CHANNEL_LOG | LogicContext.CHANNEL_TOAST | LogicContext.CHANNEL_CHAT;
        assertEquals(List.of("notify done " + channels), context.take());
        assertEquals(0, evaluator.trace().size());
    }

    @Test
    void onlyChangesThatCrossALimitMatter() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "a").withNumber("threshold", 100).withNumber("switch_back", 80))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "full")).wire(1, "crossed", 2, "do").compiled();
        int w = watch(graph, WatchKind.ITEM_COUNT, "a");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        assertTrue(evaluator.matters(w, 5), "before the first look everything matters");
        FakeContext context = new FakeContext().count("a", 5);
        evaluator.run(context, 0);
        assertFalse(evaluator.matters(w, 50), "still below");
        assertTrue(evaluator.matters(w, 101), "crosses the limit");
        context.count("a", 101);
        evaluator.changed(w);
        evaluator.run(context, 1);
        assertFalse(evaluator.matters(w, 90), "above the switch-back value");
        assertTrue(evaluator.matters(w, 80), "switches back");

        CompiledGraph<String> reading = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "a").withNumber("threshold", 100))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10)).wire(1, "count", 2, "value").compiled();
        LogicEvaluator<String> values = new LogicEvaluator<>(reading);
        values.run(context, 0);
        assertTrue(values.matters(watch(reading, WatchKind.ITEM_COUNT, "a"), 102), "the count output is read");
    }

    @Test
    void memorySurvivesSaveAndLoadWithoutFiringAgain() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES,
                        s -> s.withItem("item", "cobble").withText("mode", "below").withNumber("threshold", 100))
                .node(2, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", "cobble")).node(3, NodeTypes.JOB_FINISHED)
                .node(4, NodeTypes.COUNTER).node(5, NodeTypes.DELAY, s -> s.withNumber("ticks", 50))
                .node(6, NodeTypes.NOTIFY, s -> s.withText("text", "late")).wire(1, "crossed", 2, "do")
                .wire(3, "finished", 4, "up").wire(3, "finished", 5, "start").wire(5, "done", 6, "do").compiled();
        FakeContext context = new FakeContext().count("cobble", 50);
        LogicEvaluator<String> first = new LogicEvaluator<>(graph);
        first.run(context, 0);
        assertEquals(List.of("craft cobble 1"), context.take());
        first.signal(watch(graph, WatchKind.JOB_FINISHED, ""));
        first.run(context, 10);
        assertEquals(60, first.nextWake());
        LogicMemory saved = LogicMemory.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, LogicMemory.CODEC
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, first.memory()).getOrThrow())
                .getOrThrow();

        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved);
        assertEquals(60, loaded.nextWake(), "the waiting delay is known before the first run");
        loaded.run(context, 20);
        assertEquals(List.of(), context.take(), "the crossing is remembered, no second craft");
        assertEquals(1, loaded.value(graph.slot(4, "count")), "the counter kept its count");
        assertTrue(loaded.pending(60));
        loaded.run(context, 60);
        assertEquals(List.of("notify late 3"), context.take(), "the delay finished after the load");

        LogicEvaluator<String> fresh = new LogicEvaluator<>(graph);
        fresh.run(context, 20);
        assertEquals(List.of("craft cobble 1"), context.take(), "without memory the crossing fires again");
    }

    @Test
    void settingActionsAreSentAgainAfterALoad() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.DEVICE_STATE, s -> s.withText("label", "a"))
                .node(2, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "b")).wire(1, "on", 2, "on").compiled();
        FakeContext context = new FakeContext().set(WatchKind.DEVICE, "", "a", "", 1);
        LogicEvaluator<String> first = new LogicEvaluator<>(graph);
        first.run(context, 0);
        assertEquals(List.of("switch b true"), context.take());
        first.everythingChanged();
        first.run(context, 1);
        assertEquals(List.of(), context.take(), "unchanged input: nothing sent");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(first.memory());
        loaded.run(context, 2);
        assertEquals(List.of("switch b true"), context.take(), "the device may have changed while unloaded");
    }

    @Test
    void aLoadedGraphSendsTheSameActionsAsBeforeTheSave() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10))
                .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "b"))
                .node(4, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "a").withNumber("threshold", 10))
                .node(5, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "c")).node(6, NodeTypes.POWER_CHANGES)
                .node(7, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "d")).wire(1, "count", 2, "value")
                .wire(2, "result", 3, "on").wire(4, "past", 5, "on").wire(6, "powered", 7, "on").compiled();
        FakeContext context = new FakeContext().count("a", 20).set(WatchKind.POWER, "", "", "", 1);
        LogicEvaluator<String> first = new LogicEvaluator<>(graph);
        first.run(context, 0);
        List<String> before = context.take();
        assertEquals(List.of("switch b true", "switch c true", "switch d true"), before.stream().sorted().toList());
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(LogicMemory.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, LogicMemory.CODEC
                        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, first.memory()).getOrThrow())
                .getOrThrow());
        loaded.run(context, 1);
        assertEquals(before, context.take(), "remembered states drive the outputs after a load");
        assertEquals(1, loaded.value(graph.slot(2, "result")));
        assertEquals(1, loaded.value(graph.slot(4, "past")));
        assertEquals(1, loaded.value(graph.slot(6, "powered")));
    }

    @Test
    void anEventThroughALoopIsNeverLostWhenItsSourceRunsFirst() {
        // Counter 5 counts the "then" events of if 4, which sits in a loop through two remembering nodes (hold 2
        // and the counter). If 9 is fed by a longer chain and feeds the counter, so the counter runs after if 4:
        // a second event can arrive before the counter took the first.
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED).node(2, NodeTypes.HOLD)
                .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", -1)).node(4, NodeTypes.IF)
                .node(5, NodeTypes.COUNTER).node(6, NodeTypes.CONSTANT, s -> s.withText("kind", "yes_no"))
                .node(7, NodeTypes.NOT).node(8, NodeTypes.NOT).node(10, NodeTypes.NOT).node(11, NodeTypes.NOT)
                .node(9, NodeTypes.IF).wire(1, "finished", 2, "store").wire(5, "count", 2, "value")
                .wire(2, "value", 3, "value").wire(3, "result", 4, "condition").wire(1, "finished", 4, "when")
                .wire(4, "then", 5, "up").wire(6, "value", 7, "value").wire(7, "result", 8, "value")
                .wire(8, "result", 10, "value").wire(10, "result", 11, "value").wire(1, "finished", 9, "when")
                .wire(11, "result", 9, "condition").wire(9, "otherwise", 5, "reset").compiled();
        int ifPosition = -1;
        int counterPosition = -1;
        for (int pos = 0; pos < graph.size(); pos++) {
            ifPosition = graph.nodes[pos].id == 4 ? pos : ifPosition;
            counterPosition = graph.nodes[pos].id == 5 ? pos : counterPosition;
        }
        assertTrue(ifPosition < counterPosition, "the setup needs the source ordered first");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        for (int tick = 1; tick <= 3; tick++) {
            evaluator.signal(0);
            evaluator.run(context, tick);
        }
        for (int tick = 4; evaluator.pending(tick) && tick < 20; tick++) {
            evaluator.run(context, tick);
        }
        assertEquals(3, evaluator.value(graph.slot(5, "count")), "three jobs, three counts");
    }

    @Test
    void memoryOfAChangedNodeIsDropped() {
        CompiledGraph<String> latch = new TestGraph().node(1, NodeTypes.LATCH).node(2, NodeTypes.JOB_FINISHED)
                .wire(2, "finished", 1, "set").compiled();
        LogicMemory saved = new LogicMemory(List.of(new LogicMemory.Entry(1, "counter", List.of(7L)),
                new LogicMemory.Entry(9, "latch", List.of(1L))));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(latch);
        evaluator.load(saved);
        evaluator.run(new FakeContext(), 0);
        assertEquals(0, evaluator.value(latch.slot(1, "state")));
    }

    @Test
    void delayWakesOnceAndIgnoresStartsWhileWaiting() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED)
                .node(2, NodeTypes.DELAY, s -> s.withNumber("ticks", 20))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "ping").withFlag("log", false))
                .wire(1, "finished", 2, "start").wire(2, "done", 3, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake());
        evaluator.signal(0);
        evaluator.run(context, 100);
        assertEquals(120, evaluator.nextWake());
        assertFalse(evaluator.pending(119), "no polling before the wake");
        evaluator.signal(0);
        evaluator.run(context, 110);
        assertEquals(LogicLang.WHY_DELAY_WAITING, evaluator.trace().reason(0));
        assertEquals(120, evaluator.nextWake(), "a start while waiting does not restart");
        assertTrue(evaluator.pending(120));
        evaluator.run(context, 120);
        assertEquals(List.of("notify ping " + LogicContext.CHANNEL_TOAST), context.take());
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake());
        assertFalse(evaluator.pending(1000));
    }

    @Test
    void changeEventsDoNotFireOnTheFirstLook() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.POWER_CHANGES).node(2, NodeTypes.COUNTER)
                .wire(1, "changed", 2, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.POWER, "", "", "", 1);
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")));
        context.set(WatchKind.POWER, "", "", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(0, evaluator.value(graph.slot(1, "powered")));
    }

    /**
     * Area E: a labelled device joining fires "added" once, leaving fires "removed" once; the first look fires none.
     */
    @Test
    void labelDevicesFireOnAddAndRemoveOnly() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.LABEL_DEVICES, s -> s.withText("label", "pumps")).node(2, NodeTypes.COUNTER)
                .node(3, NodeTypes.COUNTER).wire(1, "added", 2, "up").wire(1, "removed", 3, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.DEVICE_COUNT, "", "pumps", "", 2);
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "first look");
        assertEquals(2, evaluator.value(graph.slot(1, "devices")));
        context.set(WatchKind.DEVICE_COUNT, "", "pumps", "", 3);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "added");
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "same count: nothing");
        assertEquals(0, evaluator.value(graph.slot(3, "count")));
        context.set(WatchKind.DEVICE_COUNT, "", "pumps", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 3);
        assertEquals(1, evaluator.value(graph.slot(3, "count")), "removed");
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
    }

    /** Area E: a cell turning full fires; one filling while another stays full fires again; emptying fires nothing. */
    @Test
    void storageFullFiresWhenACellFills() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.STORAGE_FULL).node(2, NodeTypes.COUNTER)
                .wire(1, "filled", 2, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "nothing full");
        context.set(WatchKind.FULL, "", "", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(1, evaluator.value(graph.slot(1, "any_full")));
        context.set(WatchKind.FULL, "", "", "", 2);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(2, evaluator.value(graph.slot(2, "count")), "a second cell filled");
        context.set(WatchKind.FULL, "", "", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 3);
        assertEquals(2, evaluator.value(graph.slot(2, "count")), "emptying fires nothing");
        assertEquals(0, evaluator.value(graph.slot(1, "any_full")));
        context.set(WatchKind.FULL, "", "", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 4);
        // A loaded graph remembers the count: the same full cell doesn't fire again.
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(evaluator.memory());
        loaded.run(context, 5);
        assertEquals(3, loaded.value(graph.slot(2, "count")), "no second fire after a load");
    }

    /** Area E: link load crosses its limit with a switch-back value, and {@code past} says while it is over. */
    @Test
    void linkOverloadedCrossesWithSwitchBack() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.LINK_OVERLOADED, s -> s.withNumber("threshold", 80).withNumber("switch_back", 50))
                .node(2, NodeTypes.COUNTER).wire(1, "overloaded", 2, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.LOAD, "", "", "", 40);
        evaluator.run(context, 0);
        assertFalse(evaluator.past(0));
        assertFalse(evaluator.matters(0, 70), "below the limit changes nothing");
        context.set(WatchKind.LOAD, "", "", "", 90);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertTrue(evaluator.past(0));
        context.set(WatchKind.LOAD, "", "", "", 60);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertTrue(evaluator.past(0), "60 is above the switch-back value");
        context.set(WatchKind.LOAD, "", "", "", 50);
        evaluator.changed(0);
        evaluator.run(context, 3);
        assertFalse(evaluator.past(0));
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
    }

    /** Area E: the context can add a why line for the action it is carrying out, and only then. */
    @Test
    void contextAddsWhyForTheActingNode() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.POWER_CHANGES)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "hi")).wire(1, "changed", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        int[] acting = {-2};
        FakeContext context = new FakeContext() {
            @Override
            public void notify(String text, int channels) {
                super.notify(text, channels);
                acting[0] = evaluator.actingNode();
                evaluator.why(LogicLang.WHY_NO_TARGET, 0, 0);
            }
        };
        context.set(WatchKind.POWER, "", "", "", 1);
        evaluator.run(context, 0);
        context.set(WatchKind.POWER, "", "", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(2, acting[0]);
        assertEquals(LogicLang.WHY_NO_TARGET, evaluator.trace().reason(evaluator.trace().find(2)));
        assertEquals(-1, evaluator.actingNode());
        int lines = evaluator.trace().size();
        evaluator.why(LogicLang.WHY_NO_TARGET, 0, 0);
        assertEquals(lines, evaluator.trace().size(), "ignored outside the action calls");
    }

    @Test
    void runAllocatesNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.COUNT_CROSSES, s -> s.withItem("item", "a").withNumber("threshold", 50))
                .node(2, NodeTypes.LATCH).node(3, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp"))
                .node(4, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(5, NodeTypes.COMPARE, s -> s.withNumber("threshold", 75)).wire(1, "crossed", 2, "set")
                .wire(5, "turned_yes", 2, "reset").wire(2, "state", 3, "on").wire(4, "count", 5, "value").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        CountingContext context = new CountingContext();
        for (int i = 0; i < 20_000; i++) {
            context.value = i % 100;
            evaluator.changed(0);
            evaluator.run(context, i);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) {
            context.value = i % 100;
            evaluator.changed(0);
            evaluator.run(context, i);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(context.actions > 0, "the graph acted");
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }

    /** A context that only counts, so the allocation test measures the evaluator alone. */
    private static final class CountingContext implements LogicContext<String> {
        long value;
        int actions;

        @Override
        public long read(Watch<String> watch) {
            return value;
        }

        @Override
        public boolean craftRunning(String item) {
            return false;
        }

        @Override
        public void switchDevice(String label, boolean on) {
            actions++;
        }

        @Override
        public void setPriority(String label, long priority) {
            actions++;
        }

        @Override
        public void redstone(String label, int strength) {
            actions++;
        }

        @Override
        public void requestCraft(String item, long amount) {
            actions++;
        }

        @Override
        public void moveItems(String item, String from, String to, long amount) {
            actions++;
        }

        @Override
        public void notify(String text, int channels) {
            actions++;
        }

        @Override
        public void pulse(String label, int strength, boolean on) {
            actions++;
        }

        @Override
        public void cancelCraft(String item, boolean ownOnly) {
            actions++;
        }

        @Override
        public void writeDisplay(String label, String text, long a, long b) {
            actions++;
        }

        @Override
        public void setFilter(String label, @org.jspecify.annotations.Nullable String item, int slot, int portMode) {
            actions++;
        }

        @Override
        public void setVariable(String name, long value, boolean yesNo) {
            actions++;
        }

        @Override
        public void runRule(String rule) {
            actions++;
        }

        @Override
        public void switchStockRule(String label, @org.jspecify.annotations.Nullable String item, boolean on) {
            actions++;
        }

        @Override
        public void setStockAmount(String label, String item, int field, long amount) {
            actions++;
        }

        @Override
        public long sendToPool(String item, String label, long amount, int strategy, String idleValue, long idle,
                long cursor) {
            actions++;
            return cursor;
        }
    }
}
