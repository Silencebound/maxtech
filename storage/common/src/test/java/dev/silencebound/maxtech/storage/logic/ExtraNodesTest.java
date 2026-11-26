package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.serialization.JsonOps;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The extra node types of batch 1: events, reads, math and checks, memory and cooldown. */
class ExtraNodesTest {
    private static int watch(CompiledGraph<String> graph, WatchKind kind) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == kind) {
                return watch.index();
            }
        }
        throw new AssertionError("No watch " + kind);
    }

    /** Through a JSON round trip, as the controller saves it. */
    private static LogicMemory saved(LogicEvaluator<String> evaluator) {
        return LogicMemory.CODEC.parse(JsonOps.INSTANCE,
                LogicMemory.CODEC.encodeStart(JsonOps.INSTANCE, evaluator.memory()).getOrThrow()).getOrThrow();
    }

    private static CompiledGraph<String> countInto(NodeType type, String input,
            java.util.function.UnaryOperator<NodeSettings<String>> settings) {
        return new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a")).node(2, type, settings)
                .wire(1, "count", 2, input).compiled();
    }

    // ---- events

    @Test
    void timerFiresEveryIntervalThroughOneWake() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.TIMER, s -> s.withNumber("interval", 2))
                .node(2, NodeTypes.COUNTER).wire(1, "fired", 2, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 100);
        assertEquals(140, evaluator.nextWake(), "the first one fires an interval after the start");
        assertFalse(evaluator.pending(139), "nothing to do before it is due");
        assertTrue(evaluator.pending(140));
        evaluator.run(context, 140);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(180, evaluator.nextWake());
        evaluator.run(context, 180);
        assertEquals(2, evaluator.value(graph.slot(2, "count")));
        // A late run (the budget, an unloaded network) fires once and counts on from then.
        evaluator.run(context, 500);
        assertEquals(3, evaluator.value(graph.slot(2, "count")), "no burst to catch up");
        assertEquals(540, evaluator.nextWake());
        assertEquals(540, evaluator.due(graph.position(1)), "the live view sees the due tick");
    }

    @Test
    void timerSleepsWhileOff() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.DEVICE_STATE, s -> s.withText("label", "a"))
                .node(2, NodeTypes.TIMER, s -> s.withNumber("interval", 1)).node(3, NodeTypes.COUNTER)
                .wire(1, "on", 2, "on").wire(2, "fired", 3, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "off: no wake at all");
        context.set(WatchKind.DEVICE, "", "a", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 50);
        assertEquals(70, evaluator.nextWake(), "on: one interval from now");
        context.set(WatchKind.DEVICE, "", "a", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 60);
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "off again");
        assertEquals(0, evaluator.value(graph.slot(3, "count")));
    }

    @Test
    void graphStartFiresOnceOnEachStart() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "hello")).node(3, NodeTypes.POWER_CHANGES)
                .wire(1, "started", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("notify hello 3"), context.take(), "a new graph starts");
        evaluator.everythingChanged();
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "a recheck (the network back online) is no start");
        evaluator.restart();
        evaluator.run(context, 2);
        assertEquals(List.of("notify hello 3"), context.take(), "switched on again");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved(evaluator));
        loaded.run(context, 3);
        assertEquals(List.of("notify hello 3"), context.take(), "loaded with the world");
    }

    @Test
    void dayAndNightFireOnFlipsOnly() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.DAYTIME_CHANGES).node(2, NodeTypes.COUNTER)
                .node(3, NodeTypes.COUNTER).node(4, NodeTypes.DAYTIME).wire(1, "day_began", 2, "up")
                .wire(1, "night_began", 3, "up").compiled();
        assertEquals(1, graph.watches().size(), "both nodes share one watch");
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.DAYTIME, "", "", "", WatchKind.packDaytime(1000, true));
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "the first look fires nothing");
        assertEquals(1, evaluator.value(graph.slot(4, "day")));
        assertEquals(1000, evaluator.value(graph.slot(4, "time")));
        context.set(WatchKind.DAYTIME, "", "", "", WatchKind.packDaytime(13000, false));
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(3, "count")), "night began");
        assertEquals(0, evaluator.value(graph.slot(1, "day")));
        context.set(WatchKind.DAYTIME, "", "", "", WatchKind.packDaytime(14000, false));
        evaluator.run(context, 2);
        assertEquals(14000, evaluator.value(graph.slot(4, "time")), "the time is read afresh on every run");
        assertEquals(1, evaluator.value(graph.slot(3, "count")), "no second night");
        context.set(WatchKind.DAYTIME, "", "", "", WatchKind.packDaytime(10, true));
        evaluator.changed(0);
        evaluator.run(context, 3);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "day began");
    }

    @Test
    void weatherChangesFireOnChangeAndReadOut() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.WEATHER_CHANGES).node(2, NodeTypes.COUNTER)
                .node(3, NodeTypes.WEATHER).wire(1, "changed", 2, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")));
        assertEquals(0, evaluator.value(graph.slot(3, "raining")));
        context.set(WatchKind.WEATHER, "", "", "", 2);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(2, evaluator.value(graph.slot(1, "weather")));
        assertEquals(1, evaluator.value(graph.slot(3, "raining")));
    }

    // ---- check

    private static long math(String operation, long a, long b, List<String> why) {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.MATH, s -> s.withText("operation", operation).withNumber("a", a).withNumber("b", b))
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(new FakeContext(), 0);
        for (int i = 0; i < evaluator.trace().size(); i++) {
            why.add(evaluator.trace().reason(i));
        }
        return evaluator.value(graph.slot(1, "result"));
    }

    @Test
    void mathOperations() {
        List<String> why = new java.util.ArrayList<>();
        assertEquals(12, math("add", 7, 5, why));
        assertEquals(2, math("subtract", 7, 5, why));
        assertEquals(35, math("multiply", 7, 5, why));
        assertEquals(-3, math("divide", -7, 2, why), "division drops the remainder");
        assertEquals(5, math("min", 7, 5, why));
        assertEquals(7, math("max", 7, 5, why));
        assertEquals(1, math("modulo", -7, 2, why), "the remainder is never negative for a positive divisor");
        assertEquals(List.of(), why);
        long max = SettingSpec.MAX_NUMBER;
        assertEquals(max, math("multiply", max, max, why), "kept within the number range");
        assertEquals(-max, math("multiply", max, -3, why));
        assertEquals(max, math("add", max, max, why));
    }

    @Test
    void divideByZeroGivesZeroAndAWarning() {
        for (String operation : List.of("divide", "modulo")) {
            List<String> why = new java.util.ArrayList<>();
            assertEquals(0, math(operation, 9, 0, why), operation);
            assertEquals(List.of(LogicLang.WHY_DIVIDE_ZERO), why, operation);
        }
    }

    @Test
    void mathReadsItsWires() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.MATH, s -> s.withText("operation", "multiply").withNumber("b", 3))
                .wire(1, "count", 2, "a").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("a", 4);
        evaluator.run(context, 0);
        assertEquals(12, evaluator.value(graph.slot(2, "result")));
        context.count("a", 5);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(15, evaluator.value(graph.slot(2, "result")));
    }

    @Test
    void betweenWithInclusiveToggles() {
        long[] counts = {9, 10, 15, 20, 21};
        boolean[][] expected = {{false, true, true, true, false}, {false, false, true, false, false}};
        for (int mode = 0; mode < 2; mode++) {
            boolean inclusive = mode == 0;
            // Bounds given the wrong way round are put right.
            CompiledGraph<String> graph = countInto(NodeTypes.BETWEEN, "value", s -> s.withNumber("low", 20)
                    .withNumber("high", 10).withFlag("include_low", inclusive).withFlag("include_high", inclusive));
            LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
            FakeContext context = new FakeContext();
            for (int i = 0; i < counts.length; i++) {
                context.count("a", counts[i]);
                evaluator.changed(0);
                evaluator.run(context, i);
                assertEquals(expected[mode][i] ? 1 : 0, evaluator.value(graph.slot(2, "result")),
                        "inclusive " + inclusive + " at " + counts[i]);
            }
        }
    }

    @Test
    void selectAndXor() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.DEVICE_STATE, s -> s.withText("label", "a"))
                .node(2, NodeTypes.DEVICE_STATE, s -> s.withText("label", "b"))
                .node(3, NodeTypes.SELECT, s -> s.withNumber("a", 64).withNumber("b", 8)).node(4, NodeTypes.XOR)
                .wire(1, "on", 3, "choose").wire(1, "on", 4, "a").wire(2, "on", 4, "b").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        boolean[][] states = {{false, false}, {true, false}, {false, true}, {true, true}};
        long[] select = {8, 64, 8, 64};
        long[] xor = {0, 1, 1, 0};
        for (int i = 0; i < states.length; i++) {
            context.set(WatchKind.DEVICE, "", "a", "", states[i][0] ? 1 : 0);
            context.set(WatchKind.DEVICE, "", "b", "", states[i][1] ? 1 : 0);
            evaluator.everythingChanged();
            evaluator.run(context, i);
            assertEquals(select[i], evaluator.value(graph.slot(3, "value")), "select " + i);
            assertEquals(xor[i], evaluator.value(graph.slot(4, "result")), "xor " + i);
        }
    }

    // ---- remember

    @Test
    void toggleFlipsAndResetWins() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "b")).node(3, NodeTypes.TOGGLE)
                .wire(1, "finished", 3, "flip").wire(2, "finished", 3, "reset").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        int state = graph.slot(3, "state");
        assertEquals(0, evaluator.value(state));
        evaluator.signal(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(state));
        evaluator.signal(0);
        evaluator.run(context, 2);
        assertEquals(0, evaluator.value(state));
        evaluator.signal(0);
        evaluator.run(context, 3);
        evaluator.signal(0);
        evaluator.signal(1);
        evaluator.run(context, 4);
        assertEquals(0, evaluator.value(state), "reset wins over a flip in the same run");
    }

    @Test
    void edgeFiresOnChangesButNotOnTheFirstSample() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.DEVICE_STATE, s -> s.withText("label", "a"))
                .node(2, NodeTypes.EDGE).node(3, NodeTypes.COUNTER).node(4, NodeTypes.COUNTER).wire(1, "on", 2, "value")
                .wire(2, "turned_yes", 3, "up").wire(2, "turned_no", 4, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.DEVICE, "", "a", "", 1);
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(3, "count")), "already yes on the first sample: no event");
        context.set(WatchKind.DEVICE, "", "a", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(4, "count")), "turned no");
        context.set(WatchKind.DEVICE, "", "a", "", 1);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(1, evaluator.value(graph.slot(3, "count")), "turned yes");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved(evaluator));
        loaded.run(context, 3);
        assertEquals(1, loaded.value(graph.slot(3, "count")), "no second turned yes after the load");
        assertEquals(1, loaded.value(graph.slot(4, "count")), "no turned no after the load");
    }

    @Test
    void statsOverTheLastSamples() {
        CompiledGraph<String> graph = countInto(NodeTypes.STATS, "value", s -> s.withNumber("samples", 3));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        long[] counts = {10, 20, 60, 2};
        long[][] expected = {{10, 10, 10}, {15, 10, 20}, {30, 10, 60}, {27, 2, 60}};
        for (int i = 0; i < counts.length; i++) {
            context.count("a", counts[i]);
            evaluator.changed(0);
            evaluator.run(context, i);
            assertEquals(expected[i][0], evaluator.value(graph.slot(2, "average")), "average at " + i);
            assertEquals(expected[i][1], evaluator.value(graph.slot(2, "min")), "lowest at " + i);
            assertEquals(expected[i][2], evaluator.value(graph.slot(2, "max")), "highest at " + i);
        }
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "a sample window never wakes");
    }

    @Test
    void statsAverageOfHugeNumbersDoesNotOverflow() {
        CompiledGraph<String> graph = countInto(NodeTypes.STATS, "value", s -> s.withNumber("samples", 32));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        long big = SettingSpec.MAX_NUMBER;
        for (int i = 0; i < 40; i++) {
            context.count("a", big - i % 2);
            evaluator.changed(0);
            evaluator.run(context, i);
        }
        long average = evaluator.value(graph.slot(2, "average"));
        assertTrue(average >= big - 1 && average <= big, "average " + average);
    }

    @Test
    void statsSecondsWindowEmptiesToTheValueItself() {
        CompiledGraph<String> graph = countInto(NodeTypes.STATS, "value",
                s -> s.withText("window", "seconds").withNumber("seconds", 2));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("a", 100);
        evaluator.run(context, 0);
        context.count("a", 50);
        evaluator.changed(0);
        evaluator.run(context, 10);
        assertEquals(75, evaluator.value(graph.slot(2, "average")));
        assertEquals(40, evaluator.nextWake(), "one wake when the oldest sample leaves the window");
        assertFalse(evaluator.pending(39));
        evaluator.run(context, 40);
        assertEquals(50, evaluator.value(graph.slot(2, "average")), "only the newer sample is left");
        assertEquals(50, evaluator.nextWake());
        evaluator.run(context, 50);
        assertEquals(50, evaluator.value(graph.slot(2, "average")), "an empty window shows the value now");
        assertEquals(50, evaluator.value(graph.slot(2, "min")));
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "nothing left to expire");
    }

    @Test
    void statsWithoutAnySampleShowTheValue() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.JOB_FINISHED, s -> s.withItem("item", "x")).node(3, NodeTypes.STATS)
                .wire(1, "count", 3, "value").wire(2, "finished", 3, "sample").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("a", 7);
        evaluator.run(context, 0);
        assertEquals(7, evaluator.value(graph.slot(3, "average")), "no sample yet: the value now");
        context.count("a", 9);
        evaluator.changed(watch(graph, WatchKind.ITEM_COUNT));
        evaluator.signal(watch(graph, WatchKind.JOB_FINISHED));
        evaluator.run(context, 1);
        context.count("a", 3);
        evaluator.changed(watch(graph, WatchKind.ITEM_COUNT));
        evaluator.run(context, 2);
        assertEquals(9, evaluator.value(graph.slot(3, "max")), "a wired sample input samples only when it fires");
        assertEquals(9, evaluator.value(graph.slot(3, "min")));
    }

    @Test
    void changeRatePerSecond() {
        CompiledGraph<String> graph = countInto(NodeTypes.CHANGE_RATE, "value", s -> s);
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("a", 100);
        evaluator.run(context, 0);
        int rate = graph.slot(2, "rate");
        assertEquals(0, evaluator.value(rate), "one sample has no rate yet");
        context.count("a", 140);
        evaluator.changed(0);
        evaluator.run(context, 40);
        assertEquals(20, evaluator.value(rate), "40 more in 2 seconds");
        context.count("a", 130);
        evaluator.changed(0);
        evaluator.run(context, 45);
        assertEquals(-40, evaluator.value(rate), "10 fewer in a quarter second");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved(evaluator));
        loaded.run(context, 60);
        assertEquals(-40, loaded.value(rate), "the rate and last sample survive a load");
        context.count("a", 160);
        loaded.changed(0);
        loaded.run(context, 65);
        assertEquals(30, loaded.value(rate), "30 more in the second since the sample before the load");
    }

    // ---- time

    @Test
    void cooldownLetsOneThroughPerIntervalAndShowsTheRest() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.JOB_FINISHED)
                .node(2, NodeTypes.COOLDOWN, s -> s.withNumber("seconds", 1)).node(3, NodeTypes.COUNTER)
                .wire(1, "finished", 2, "in").wire(2, "passed", 3, "up").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        long[] times = {0, 5, 19, 20, 39, 40};
        long[] counts = {1, 1, 1, 2, 2, 3};
        for (int i = 0; i < times.length; i++) {
            evaluator.signal(0);
            evaluator.run(context, times[i]);
            assertEquals(counts[i], evaluator.value(graph.slot(3, "count")), "at tick " + times[i]);
        }
        evaluator.signal(0);
        evaluator.run(context, 45);
        assertEquals(LogicLang.WHY_COOLDOWN, evaluator.trace().reason(0));
        assertEquals(15, evaluator.trace().first(0), "ticks left");
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved(evaluator));
        loaded.run(context, 46);
        loaded.signal(0);
        loaded.run(context, 50);
        assertEquals(3, loaded.value(graph.slot(3, "count")), "still cooling down after a load");
        loaded.signal(0);
        loaded.run(context, 60);
        assertEquals(4, loaded.value(graph.slot(3, "count")), "through again at the boundary");
    }

    // ---- memory and allocation

    @Test
    void newNodesKeepMemoryThroughASave() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.TIMER, s -> s.withNumber("interval", 5))
                .node(2, NodeTypes.TOGGLE).node(3, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(4, NodeTypes.STATS).wire(1, "fired", 2, "flip").wire(3, "count", 4, "value").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().count("a", 10);
        evaluator.run(context, 0);
        context.count("a", 30);
        evaluator.changed(watch(graph, WatchKind.ITEM_COUNT));
        evaluator.run(context, 100);
        assertEquals(1, evaluator.value(graph.slot(2, "state")));
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved(evaluator));
        assertEquals(200, loaded.nextWake(), "the timer's due tick is known before the first run");
        loaded.run(context, 150);
        assertEquals(1, loaded.value(graph.slot(2, "state")), "the toggle kept its state");
        assertEquals(20, loaded.value(graph.slot(4, "average")), "the statistics kept their samples");
    }

    @Test
    void oldSavesLoadUnchanged() {
        // Memory as a save before batch 1 wrote it: entries of at most 16 longs keyed by node and type.
        String json = "[{\"node\":4,\"type\":\"counter\",\"values\":[3]},{\"node\":5,\"type\":\"delay\",\"values\":[60]}]";
        LogicMemory memory = LogicMemory.CODEC.parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json))
                .getOrThrow();
        CompiledGraph<String> graph = new TestGraph().node(3, NodeTypes.JOB_FINISHED).node(4, NodeTypes.COUNTER)
                .node(5, NodeTypes.DELAY, s -> s.withNumber("ticks", 50)).wire(3, "finished", 4, "up")
                .wire(3, "finished", 5, "start").compiled();
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(memory);
        assertEquals(60, loaded.nextWake());
        loaded.run(new FakeContext(), 10);
        assertEquals(3, loaded.value(graph.slot(4, "count")));
    }

    @Test
    void runsOfTheNewNodesAllocateNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "a"))
                .node(2, NodeTypes.STATS, s -> s.withText("window", "seconds").withNumber("seconds", 1))
                .node(3, NodeTypes.CHANGE_RATE).node(4, NodeTypes.MATH, s -> s.withText("operation", "divide"))
                .node(5, NodeTypes.BETWEEN, s -> s.withNumber("high", 50)).node(6, NodeTypes.EDGE)
                .node(7, NodeTypes.TOGGLE).node(8, NodeTypes.COOLDOWN, s -> s.withNumber("seconds", 1))
                .node(9, NodeTypes.TIMER, s -> s.withNumber("interval", 1)).node(10, NodeTypes.DAYTIME)
                .node(11, NodeTypes.REDSTONE_OUT, s -> s.withText("label", "lamp")).wire(1, "count", 2, "value")
                .wire(1, "count", 3, "value").wire(2, "average", 4, "a").wire(3, "rate", 4, "b")
                .wire(4, "result", 5, "value").wire(5, "result", 6, "value").wire(6, "turned_yes", 8, "in")
                .wire(8, "passed", 7, "flip").wire(9, "fired", 7, "reset").wire(7, "state", 11, "on").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        int count = watch(graph, WatchKind.ITEM_COUNT);
        CountingContext counting = new CountingContext();
        for (int i = 0; i < 20_000; i++) {
            counting.value = i % 100;
            evaluator.changed(count);
            evaluator.run(counting, i);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 20_000; i < 40_000; i++) {
            counting.value = i % 100;
            evaluator.changed(count);
            evaluator.run(counting, i);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(counting.actions > 0, "the graph acted");
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }

    /** A context that only counts, so the allocation test measures the evaluator alone. */
    private static final class CountingContext implements LogicContext<String> {
        long value;
        int actions;

        @Override
        public long read(Watch<String> watch) {
            return watch.kind() == WatchKind.DAYTIME ? WatchKind.packDaytime(value * 100, value < 50) : value;
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
