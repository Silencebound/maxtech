package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.editor.SettingRules;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

/**
 * The hook node types of batch 4: item moves, crafting failed, players nearby, terminals opened, shared variables,
 * rules running each other (with the loop check) and wait until. The evaluator side; the controller's hooks are
 * GameTests.
 */
class HookNodesTest {
    private static int watch(CompiledGraph<String> graph, WatchKind kind) {
        for (Watch<String> watch : graph.watches()) {
            if (watch.kind() == kind) {
                return watch.index();
            }
        }
        throw new AssertionError("no " + kind + " watch");
    }

    /** {@code type} (1) into a counter (2) at "up" through its output {@code output}. */
    private static CompiledGraph<String> counted(NodeType type, String output,
            UnaryOperator<NodeSettings<String>> settings) {
        return new TestGraph().node(1, type, settings).node(2, NodeTypes.COUNTER).wire(1, output, 2, "up").compiled();
    }

    // ---- item moves, craft failed, terminal, run by rule

    @Test
    void itemMovesFiresOncePerRunWithTheAmountsAddedUp() {
        CompiledGraph<String> graph = counted(NodeTypes.ITEM_MOVES, "moved", s -> s.withItem("item", "cobble"));
        int moves = watch(graph, WatchKind.ITEM_MOVES);
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "no move, no event on the first run");
        evaluator.signal(moves, 64);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(64, evaluator.value(graph.slot(1, "amount")));
        evaluator.signal(moves, 10);
        evaluator.signal(moves, 5);
        assertTrue(evaluator.pending(2));
        evaluator.run(context, 2);
        assertEquals(2, evaluator.value(graph.slot(2, "count")), "two moves before one run fire once");
        assertEquals(15, evaluator.value(graph.slot(1, "amount")), "with both amounts");
        evaluator.run(context, 3);
        assertEquals(2, evaluator.value(graph.slot(2, "count")), "nothing new, nothing fired");
        assertEquals(0, context.reads, "a signal is never read from the context");
    }

    @Test
    void itemMovesWatchesKeepTheirDirection() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.ITEM_MOVES, s -> s.withItem("item", "cobble").withText("direction", "into"))
                .node(2, NodeTypes.ITEM_MOVES, s -> s.withItem("item", "cobble").withText("direction", "out_of"))
                .node(3, NodeTypes.ITEM_MOVES, s -> s.withText("label", "intake")).compiled();
        assertEquals(3, graph.watches().size(), "two directions of one item are two watches");
        assertEquals(1, graph.watches().get(0).number());
        assertEquals(2, graph.watches().get(1).number());
        assertEquals(0, graph.watches().get(2).number(), "both ways by default");
        assertTrue(graph.watches().get(2).item().isEmpty(), "any item");
        assertEquals("intake", graph.watches().get(2).label());
    }

    @Test
    void craftFailedFiresWithTheMissingAmount() {
        CompiledGraph<String> graph = counted(NodeTypes.CRAFT_FAILED, "failed", s -> s.withItem("item", "stick"));
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        evaluator.signal(watch(graph, WatchKind.CRAFT_FAILED), 6);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")));
        assertEquals(6, evaluator.value(graph.slot(1, "missing")));
    }

    @Test
    void terminalAndRunByRuleFireOnTheirSignal() {
        for (NodeType type : List.of(NodeTypes.PLAYER_USES_TERMINAL, NodeTypes.RUN_BY_RULE)) {
            String output = type == NodeTypes.RUN_BY_RULE ? "ran" : "opened";
            CompiledGraph<String> graph = counted(type, output, s -> s);
            LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
            FakeContext context = new FakeContext();
            evaluator.run(context, 0);
            assertEquals(0, evaluator.value(graph.slot(2, "count")), type + " fires nothing on a recheck");
            evaluator.signal(
                    watch(graph, type == NodeTypes.RUN_BY_RULE ? WatchKind.RULE_CALLED : WatchKind.TERMINAL_OPENED));
            evaluator.run(context, 1);
            assertEquals(1, evaluator.value(graph.slot(2, "count")), type.id());
            evaluator.everythingChanged();
            evaluator.run(context, 2);
            assertEquals(1, evaluator.value(graph.slot(2, "count")), type + ": a recheck is no event");
        }
    }

    // ---- players nearby

    @Test
    void playerNearbyFiresWhenOneComesOrLeavesButNotOnTheFirstLook() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.PLAYER_NEARBY, s -> s.withText("label", "door").withNumber("radius", 5))
                .node(2, NodeTypes.COUNTER).node(3, NodeTypes.COUNTER).wire(1, "came", 2, "up").wire(1, "left", 3, "up")
                .compiled();
        Watch<String> watch = graph.watches().getFirst();
        assertEquals(WatchKind.PLAYERS_NEAR, watch.kind());
        assertEquals(5, watch.number(), "the radius travels with the watch");
        FakeContext context = new FakeContext().set(WatchKind.PLAYERS_NEAR, "", "door", "", 1);
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(0, evaluator.value(graph.slot(2, "count")), "already near at the first look: no event");
        assertEquals(1, evaluator.value(graph.slot(1, "near")));
        context.set(WatchKind.PLAYERS_NEAR, "", "door", "", 2);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(1, evaluator.value(graph.slot(2, "count")), "a second player came");
        context.set(WatchKind.PLAYERS_NEAR, "", "door", "", 0);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(1, evaluator.value(graph.slot(3, "count")), "both left: one event");
        assertEquals(0, evaluator.value(graph.slot(1, "near")));
        assertEquals(0, evaluator.value(graph.slot(1, "players")));
    }

    // ---- shared variables

    @Test
    void variableNamesIgnoreCaseAndSpaces() {
        assertEquals("mode", LogicCompiler.variableName("  Mode "));
        assertEquals("a".repeat(SettingSpec.MAX_LABEL), LogicCompiler.variableName("A".repeat(40)));
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "Mode"))
                .node(2, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "mode ")).compiled();
        assertEquals(1, graph.watches().size(), "one variable, one watch");
        assertEquals("mode", graph.watches().getFirst().name());
    }

    @Test
    void readingAVariableGivesItsNumberAndYesNo() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "mode")).compiled();
        FakeContext context = new FakeContext().set(WatchKind.VARIABLE, "", "", "mode", 3);
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(3, evaluator.value(graph.slot(1, "value")));
        assertEquals(1, evaluator.value(graph.slot(1, "yes")));
        context.set(WatchKind.VARIABLE, "", "", "mode", 0);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(0, evaluator.value(graph.slot(1, "yes")));
    }

    @Test
    void settingAVariableWritesWhenItsValueChangesOrOnItsEvent() {
        FakeContext context = new FakeContext().count("cobble", 12);
        CompiledGraph<String> onChange = new TestGraph()
                .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "cobble"))
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", " Stock")).wire(1, "count", 2, "value")
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(onChange);
        evaluator.run(context, 0);
        assertEquals(List.of("variable stock 12"), context.take(), "written on the first run, by its key");
        evaluator.run(context, 1);
        assertEquals(List.of(), context.take(), "nothing changed, nothing written");
        context.count("cobble", 20);
        evaluator.changed(0);
        evaluator.run(context, 2);
        assertEquals(List.of("variable stock 20"), context.take());

        CompiledGraph<String> onEvent = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "open").withText("kind", "yes_no"))
                .node(3, NodeTypes.REDSTONE_CHANGES).wire(1, "changed", 2, "do").compiled();
        LogicEvaluator<String> second = new LogicEvaluator<>(onEvent);
        second.run(context, 0);
        assertEquals(List.of(), context.take(), "waits for its event");
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        second.changed(0);
        second.run(context, 1);
        assertEquals(List.of("variable open 1 yes/no"), context.take(), "unwired yes is yes");
    }

    @Test
    void twoWritesToOneVariableInARunSendTheLastAndOtherNamesStay() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "a").withNumber("value", 1))
                .node(3, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "a").withNumber("value", 2))
                .node(4, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "b").withNumber("value", 3))
                .wire(1, "changed", 2, "do").wire(1, "changed", 3, "do").wire(1, "changed", 4, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("variable a 2", "variable b 3"), context.take());
    }

    @Test
    void aVariableNeedsAName() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.SHARED_VARIABLE).compile();
        assertTrue(result.paused());
        assertTrue(result.errors().stream().anyMatch(error -> "name".equals(error.port())), result.errors()::toString);
    }

    @Test
    void theVariableValueSettingShowsOnlyForNumbers() {
        SettingSpec value = NodeTypes.SET_SHARED_VARIABLE.setting("value").orElseThrow();
        assertTrue(SettingRules.shows(NodeTypes.SET_SHARED_VARIABLE, NodeSettings.<String>empty(), value));
        assertFalse(SettingRules.shows(NodeTypes.SET_SHARED_VARIABLE,
                NodeSettings.<String>empty().withText("kind", "yes_no"), value));
    }

    // ---- run another rule and the loop check

    @Test
    void runRuleSendsItsRuleOncePerEvent() {
        FakeContext context = new FakeContext();
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.RUN_RULE, s -> s.withText("rule", " Doors ")).wire(1, "changed", 2, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        assertEquals(List.of(), context.take());
        context.set(WatchKind.REDSTONE, "", "", "", 15);
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(List.of("run Doors"), context.take());
    }

    @Test
    void aRuleIsNamedByRowNumberOrByName() {
        List<String> names = List.of("Doors", "Lights", "12");
        assertEquals(0, RuleCalls.target("1", names.size(), names::get));
        assertEquals(2, RuleCalls.target("3", names.size(), names::get));
        assertEquals(-1, RuleCalls.target("4", names.size(), names::get), "past the last row");
        assertEquals(-1, RuleCalls.target("0", names.size(), names::get));
        assertEquals(-1, RuleCalls.target("99999999999", names.size(), names::get), "a long number names none");
        assertEquals(1, RuleCalls.target(" lights ", names.size(), names::get), "names ignore case and spaces");
        assertEquals(-1, RuleCalls.target("12", names.size(), names::get), "digits are always a row");
        assertEquals(-1, RuleCalls.target("pumps", names.size(), names::get));
        assertEquals(-1, RuleCalls.target("  ", names.size(), names::get));
    }

    @Test
    void loopsAreFoundAndChainsAreNot() {
        assertArrayEquals(new int[]{-1, -1, -1}, RuleCalls.loops(new int[][]{{1}, {2}, {}}), "a chain");
        int[] two = RuleCalls.loops(new int[][]{{1}, {0}, {0}});
        assertTrue(two[0] >= 0 && two[0] == two[1], "0 and 1 run each other");
        assertEquals(-1, two[2], "2 runs into the loop but is not on it");
        assertTrue(RuleCalls.loops(new int[][]{{0}})[0] >= 0, "a rule running itself");
        int[] three = RuleCalls.loops(new int[][]{{1}, {2}, {0, -1, 7}});
        assertTrue(three[0] >= 0 && three[0] == three[1] && three[1] == three[2], "a circle of three");
    }

    private static LogicGraph<String> runs(String... rules) {
        TestGraph graph = new TestGraph().node(1, NodeTypes.RUN_BY_RULE);
        for (int i = 0; i < rules.length; i++) {
            String rule = rules[i];
            graph.node(2 + i, NodeTypes.RUN_RULE, s -> s.withText("rule", rule)).wire(1, "ran", 2 + i, "do");
        }
        return graph.build();
    }

    @Test
    void theLoopCheckMarksTheRunRuleNodesOfACircle() {
        List<LogicGraph<String>> graphs = List.of(runs("B"), runs("3", "Lamp"), runs("a"), runs());
        List<String> names = List.of("A", "B", "C", "Lamp");
        List<List<GraphError>> errors = RuleCalls.check(graphs, names, new boolean[]{true, true, true, true});
        assertEquals(1, errors.get(0).size(), "A runs B");
        assertEquals(LogicLang.RULE_LOOP, errors.get(0).getFirst().key());
        assertEquals(2, errors.get(0).getFirst().node());
        assertEquals("rule", errors.get(0).getFirst().port());
        assertEquals(1, errors.get(1).size(), "B's call of C is on the circle, its call of Lamp is not");
        assertEquals(2, errors.get(1).getFirst().node());
        assertEquals(1, errors.get(2).size());
        assertEquals(List.of(), errors.get(3), "Lamp runs nothing");

        List<List<GraphError>> broken = RuleCalls.check(graphs, names, new boolean[]{true, true, false, true});
        assertTrue(broken.stream().allMatch(List::isEmpty), "a graph that does not compile runs nothing: no circle");
        List<List<GraphError>> renamed = RuleCalls.check(graphs, List.of("Alpha", "B", "C", "Lamp"),
                new boolean[]{true, true, true, true});
        assertTrue(renamed.stream().allMatch(List::isEmpty), "C runs \"a\", which is no name any more");
        assertEquals(List.of(), RuleCalls.check(List.of(runs("1")), List.of("Self"), new boolean[]{false}).getFirst());
        assertEquals(1, RuleCalls.check(List.of(runs("1")), List.of("Self"), new boolean[]{true}).getFirst().size(),
                "a rule running itself");
    }

    // ---- wait until

    /** A redstone-changes node (1) fires into wait-until (3) held by a compare on an item count (2). */
    private static CompiledGraph<String> waiting(UnaryOperator<NodeSettings<String>> settings) {
        return new TestGraph().node(1, NodeTypes.REDSTONE_CHANGES)
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", "fuel"))
                .node(4, NodeTypes.COMPARE, s -> s.withNumber("threshold", 0)).node(3, NodeTypes.WAIT_UNTIL, settings)
                .node(5, NodeTypes.COUNTER).node(6, NodeTypes.COUNTER).wire(1, "changed", 3, "in")
                .wire(2, "count", 4, "value").wire(4, "result", 3, "condition").wire(3, "passed", 5, "up")
                .wire(3, "timed_out", 6, "up").compiled();
    }

    private static void flip(LogicEvaluator<String> evaluator, FakeContext context) {
        String key = FakeContext.key(WatchKind.REDSTONE, "", "", "");
        context.values.put(key, context.values.getOrDefault(key, 0L) == 0 ? 15L : 0L);
        evaluator.changed(0);
    }

    private static void fuel(LogicEvaluator<String> evaluator, FakeContext context, long count) {
        context.count("fuel", count);
        evaluator.changed(1);
    }

    @Test
    void waitUntilHoldsEventsAndLetsThemThroughOnePerRun() {
        CompiledGraph<String> graph = waiting(s -> s.withNumber("timeout", 0));
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        flip(evaluator, context);
        evaluator.run(context, 1);
        flip(evaluator, context);
        evaluator.run(context, 2);
        assertEquals(2, evaluator.value(graph.slot(3, "held")), "two held while there is no fuel");
        assertEquals(0, evaluator.value(graph.slot(5, "count")));
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake(), "no timeout, no wake");
        fuel(evaluator, context, 5);
        evaluator.run(context, 3);
        assertEquals(1, evaluator.value(graph.slot(5, "count")), "the oldest leaves");
        assertTrue(evaluator.wantsRerun(), "and the next one next tick");
        evaluator.run(context, 4);
        assertEquals(2, evaluator.value(graph.slot(5, "count")));
        assertEquals(0, evaluator.value(graph.slot(3, "held")));
        assertFalse(evaluator.wantsRerun());
        flip(evaluator, context);
        evaluator.run(context, 5);
        assertEquals(3, evaluator.value(graph.slot(5, "count")), "with fuel an event passes at once");
        assertEquals(0, evaluator.value(graph.slot(3, "held")));
    }

    @Test
    void waitUntilGivesUpAfterItsTimeout() {
        CompiledGraph<String> graph = waiting(s -> s.withNumber("timeout", 2));
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        flip(evaluator, context);
        evaluator.run(context, 10);
        assertEquals(50, evaluator.nextWake(), "one wake at the timeout, 40 ticks on");
        flip(evaluator, context);
        evaluator.run(context, 20);
        assertEquals(50, evaluator.nextWake(), "the earliest timeout");
        assertFalse(evaluator.pending(49));
        evaluator.run(context, 50);
        assertEquals(1, evaluator.value(graph.slot(6, "count")), "the first gave up");
        assertEquals(1, evaluator.value(graph.slot(3, "held")));
        assertEquals(60, evaluator.nextWake());
        evaluator.run(context, 60);
        assertEquals(2, evaluator.value(graph.slot(6, "count")));
        assertEquals(0, evaluator.value(graph.slot(5, "count")), "nothing was let through");
        assertEquals(LogicEvaluator.NO_WAKE, evaluator.nextWake());
    }

    @Test
    void waitUntilDropsEventsPastItsMost() {
        CompiledGraph<String> graph = waiting(s -> s.withNumber("hold", 2).withNumber("timeout", 0));
        FakeContext context = new FakeContext();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        evaluator.run(context, 0);
        for (int i = 1; i <= 3; i++) {
            flip(evaluator, context);
            evaluator.run(context, i);
        }
        assertEquals(2, evaluator.value(graph.slot(3, "held")));
        assertEquals(LogicLang.WHY_WAIT_FULL, evaluator.trace().reason(evaluator.trace().find(3)));
        assertEquals(2, evaluator.trace().first(evaluator.trace().find(3)));
    }

    @Test
    void heldEventsAndTheirTimeoutsSurviveASave() {
        CompiledGraph<String> graph = waiting(s -> s.withNumber("timeout", 5));
        FakeContext context = new FakeContext();
        LogicEvaluator<String> before = new LogicEvaluator<>(graph);
        before.run(context, 0);
        flip(before, context);
        before.run(context, 10);
        flip(before, context);
        before.run(context, 30);
        LogicMemory saved = LogicMemory.CODEC
                .parse(JsonOps.INSTANCE, LogicMemory.CODEC.encodeStart(JsonOps.INSTANCE, before.memory()).getOrThrow())
                .getOrThrow();
        LogicEvaluator<String> after = new LogicEvaluator<>(graph);
        after.load(saved);
        assertEquals(110, after.nextWake(), "the first timeout was saved");
        after.run(context, 40);
        assertEquals(2, after.value(graph.slot(3, "held")), "both still held after the load");
        fuel(after, context, 1);
        after.run(context, 41);
        assertEquals(1, after.value(graph.slot(5, "count")));
        after.run(context, 42);
        assertEquals(2, after.value(graph.slot(5, "count")), "both let through");
    }

    // ---- shared

    @Test
    void theHooksAreNotCommonAndCostAsTheirKind() {
        for (NodeType type : List.of(NodeTypes.ITEM_MOVES, NodeTypes.CRAFT_FAILED, NodeTypes.PLAYER_NEARBY,
                NodeTypes.PLAYER_USES_TERMINAL, NodeTypes.RUN_BY_RULE, NodeTypes.SHARED_VARIABLE,
                NodeTypes.SET_SHARED_VARIABLE, NodeTypes.RUN_RULE, NodeTypes.WAIT_UNTIL)) {
            assertFalse(type.common(), type + " is not in the Common set");
            assertEquals(type.op().acts() ? 2 : 1, type.cost(), type.id());
        }
    }

    @Test
    void sentencesReadPlainly() {
        Map<String, String> english = LogicLang.english();
        UnaryOperator<String> lang = key -> english.getOrDefault(key, key);
        assertEquals("when cobble moves Into the network through intake",
                NodeSentence.of(NodeTypes.ITEM_MOVES, NodeSettings.<String>empty().withItem("item", "cobble")
                        .withText("label", "intake").withText("direction", "into"), lang, item -> item));
        assertEquals("when a player comes within 8 blocks of door", NodeSentence.of(NodeTypes.PLAYER_NEARBY,
                NodeSettings.<String>empty().withText("label", "door"), lang, item -> item));
        assertEquals("set shared value mode (Yes/no)", NodeSentence.of(NodeTypes.SET_SHARED_VARIABLE,
                NodeSettings.<String>empty().withText("name", "mode").withText("kind", "yes_no"), lang, item -> item));
        assertEquals("run rule Doors", NodeSentence.of(NodeTypes.RUN_RULE,
                NodeSettings.<String>empty().withText("rule", "Doors"), lang, item -> item));
        assertEquals("hold Event until Condition, giving up after 60 seconds",
                NodeSentence.of(NodeTypes.WAIT_UNTIL, NodeSettings.<String>empty(), lang, item -> item));
    }

    @Test
    void graphsWithTheHooksSurviveTheTextFormat() {
        Codec<LogicGraph<String>> codec = LogicGraph.codec(Codec.STRING);
        LogicGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.ITEM_MOVES,
                        s -> s.withItem("item", "minecraft:cobblestone").withText("direction", "out_of"))
                .node(2, NodeTypes.PLAYER_NEARBY, s -> s.withText("label", "door").withNumber("radius", 3))
                .node(3, NodeTypes.WAIT_UNTIL, s -> s.withNumber("timeout", 9).withNumber("hold", 3))
                .node(4, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "mode").withText("kind", "yes_no"))
                .node(5, NodeTypes.RUN_RULE, s -> s.withText("rule", "2")).node(6, NodeTypes.RUN_BY_RULE)
                .node(7, NodeTypes.CRAFT_FAILED).node(8, NodeTypes.PLAYER_USES_TERMINAL)
                .node(9, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "mode")).wire(1, "moved", 3, "in")
                .wire(2, "near", 3, "condition").wire(3, "passed", 5, "do").wire(9, "yes", 4, "yes").build();
        String text = GraphText.write(codec, NbtOps.INSTANCE, "Hooks", graph).orElseThrow();
        GraphText.Read<String> read = GraphText.read(text, 16_384, 64, codec, NbtOps.INSTANCE);
        assertTrue(read.ok(), read.error());
        assertEquals(graph, read.graph().orElseThrow());
        assertFalse(LogicCompiler.compile(read.graph().orElseThrow(), 64).paused(), "and still compiles");
    }

    @Test
    void runsOfTheHookNodesAllocateNothing() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.ITEM_MOVES).node(2, NodeTypes.PLAYER_NEARBY)
                .node(3, NodeTypes.WAIT_UNTIL, s -> s.withNumber("timeout", 1))
                .node(4, NodeTypes.SET_SHARED_VARIABLE, s -> s.withText("name", "n"))
                .node(5, NodeTypes.SHARED_VARIABLE, s -> s.withText("name", "n"))
                .node(6, NodeTypes.RUN_RULE, s -> s.withText("rule", "2")).wire(1, "moved", 3, "in")
                .wire(2, "near", 3, "condition").wire(1, "amount", 4, "value").wire(3, "passed", 6, "do").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        int moves = watch(graph, WatchKind.ITEM_MOVES);
        int players = watch(graph, WatchKind.PLAYERS_NEAR);
        FakeCounting counting = new FakeCounting();
        for (int round = 0; round < 2; round++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = round * 20_000; i < (round + 1) * 20_000; i++) {
                counting.value = i % 3;
                evaluator.signal(moves, i % 50);
                evaluator.changed(players);
                evaluator.run(counting, i);
            }
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            if (round == 1) {
                assertTrue(counting.acted > 0, "the graph acted");
                assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
            }
        }
    }

    /** A context that only counts, so the allocation test measures the evaluator alone. */
    private static final class FakeCounting extends FakeContext {
        long value;
        int acted;

        @Override
        public long read(Watch<String> watch) {
            return value;
        }

        @Override
        public void setVariable(String name, long value, boolean yesNo) {
            acted++;
        }

        @Override
        public void runRule(String rule) {
            acted++;
        }
    }
}
