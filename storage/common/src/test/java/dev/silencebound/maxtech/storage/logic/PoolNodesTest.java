package dev.silencebound.maxtech.storage.logic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import dev.silencebound.maxtech.storage.logic.template.BuiltInTemplates;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Machine pools (M7L2 row 17): the planner's strategies, the packed idle condition and status, the send, status and
 * turned-idle nodes against a fake context, their sentences, card lines and card edits, and the template.
 */
class PoolNodesTest {
    private static final Map<String, String> ENGLISH = LogicLang.english();
    private static final RuleCards.Lang LANG = key -> ENGLISH.getOrDefault(key, key);

    private static String text(String key) {
        return ENGLISH.getOrDefault(key, key);
    }

    // ---- the planner

    private static long[] plan(int strategy, long amount, boolean[] candidate, long[] held, int cursor) {
        Pools.Planner planner = new Pools.Planner();
        planner.plan(strategy, amount, candidate.length, candidate, held, cursor);
        long[] shares = new long[candidate.length];
        for (int i = 0; i < shares.length; i++) {
            shares[i] = planner.share(i);
        }
        return shares;
    }

    private static int[] order(int strategy, boolean[] candidate, long[] held, int cursor) {
        Pools.Planner planner = new Pools.Planner();
        planner.plan(strategy, 1, candidate.length, candidate, held, cursor);
        int[] order = new int[planner.count()];
        for (int j = 0; j < order.length; j++) {
            order[j] = planner.order(j);
        }
        return order;
    }

    @Test
    void firstIdleSendsEverythingToTheFirstIdleMember() {
        boolean[] idle = {false, true, false, true};
        assertArrayEquals(new long[]{0, 16, 0, 0}, plan(Pools.FIRST_IDLE, 16, idle, new long[4], 3),
                "the cursor does not matter");
        assertArrayEquals(new int[]{1, 3}, order(Pools.FIRST_IDLE, idle, new long[4], 3),
                "the rest goes to the next idle member in member order");
    }

    @Test
    void roundRobinStartsAtTheCursorAndSkipsBusyMembers() {
        boolean[] idle = {true, false, true, true};
        assertArrayEquals(new long[]{0, 0, 8, 0}, plan(Pools.ROUND_ROBIN, 8, idle, new long[4], 1),
                "member 1 is busy, so the turn passes to member 2");
        assertArrayEquals(new int[]{2, 3, 0}, order(Pools.ROUND_ROBIN, idle, new long[4], 1));
        assertArrayEquals(new long[]{8, 0, 0, 0}, plan(Pools.ROUND_ROBIN, 8, idle, new long[4], 4), "wraps around");
        assertEquals(3, Pools.nextCursor(Pools.ROUND_ROBIN, 2, 4, 1), "next after the one served");
        assertEquals(0, Pools.nextCursor(Pools.ROUND_ROBIN, 3, 4, 1), "wraps");
        assertEquals(1, Pools.nextCursor(Pools.ROUND_ROBIN, -1, 4, 1), "nothing served keeps the turn");
        assertEquals(1, Pools.nextCursor(Pools.FIRST_IDLE, 2, 4, 1), "first idle keeps no turn");
        assertEquals(1, Pools.nextCursor(Pools.LEAST_FULL, 2, 4, 1), "least full keeps no turn");
    }

    @Test
    void spreadEvenlySplitsOverIdleMembersAndTheOddItemsTakeTurns() {
        boolean[] idle = {true, true, false, true};
        assertArrayEquals(new long[]{4, 4, 0, 4}, plan(Pools.SPREAD_EVENLY, 12, idle, new long[4], 0));
        assertArrayEquals(new long[]{4, 3, 0, 3}, plan(Pools.SPREAD_EVENLY, 10, idle, new long[4], 0));
        assertArrayEquals(new long[]{3, 4, 0, 3}, plan(Pools.SPREAD_EVENLY, 10, idle, new long[4], 1),
                "starting at member 1, it gets the odd item");
        assertArrayEquals(new long[]{1, 1, 0, 0}, plan(Pools.SPREAD_EVENLY, 2, idle, new long[4], 0),
                "fewer items than members");
    }

    @Test
    void leastFullLevelsWhatTheMembersHold() {
        boolean[] idle = {true, true, true};
        assertArrayEquals(new long[]{0, 7, 2}, plan(Pools.LEAST_FULL, 9, idle, new long[]{10, 0, 5}, 0),
                "the emptiest gets most, until all hold about the same");
        assertArrayEquals(new int[]{1, 2, 0}, order(Pools.LEAST_FULL, idle, new long[]{10, 0, 5}, 0), "emptiest first");
        assertArrayEquals(new long[]{0, 3, 0},
                plan(Pools.LEAST_FULL, 3, new boolean[]{true, true, false}, new long[]{4, 0, 0}, 0),
                "a busy member gets nothing, however empty");
    }

    @Test
    void noCandidatesPlanNothing() {
        Pools.Planner planner = new Pools.Planner();
        planner.plan(Pools.SPREAD_EVENLY, 64, 3, new boolean[]{false, false, false}, new long[3], 0);
        assertEquals(0, planner.count());
        planner.plan(Pools.FIRST_IDLE, 64, 0, new boolean[0], new long[0], 5);
        assertEquals(0, planner.count(), "an empty pool");
    }

    @Test
    void plannerGrowsForLargePools() {
        boolean[] idle = new boolean[40];
        java.util.Arrays.fill(idle, true);
        long[] shares = plan(Pools.SPREAD_EVENLY, 80, idle, new long[40], 0);
        for (long share : shares) {
            assertEquals(2, share);
        }
    }

    @Test
    void idleConditionsAndStatusPack() {
        long lit = Pools.packIdle(Pools.IDLE_EQUAL, 0);
        assertTrue(Pools.idle(0, lit));
        assertFalse(Pools.idle(1, lit));
        long low = Pools.packIdle(Pools.IDLE_BELOW, -3);
        assertEquals(Pools.IDLE_BELOW, Pools.idleCompare(low));
        assertEquals(-3, Pools.idleLimit(low));
        assertTrue(Pools.idle(-4, low));
        assertFalse(Pools.idle(-3, low));
        long high = Pools.packIdle(Pools.IDLE_ABOVE, 7);
        assertTrue(Pools.idle(8, high));
        assertFalse(Pools.idle(7, high));
        assertEquals(Pools.MAX_IDLE_LIMIT, Pools.idleLimit(Pools.packIdle(Pools.IDLE_EQUAL, Long.MAX_VALUE)));
        long status = Pools.packStatus(4, 3);
        assertEquals(4, Pools.members(status));
        assertEquals(3, Pools.idleMembers(status));
        assertEquals(2, Pools.idleMembers(Pools.packStatus(2, 5)), "never more idle than members");
    }

    @Test
    void idleBitsCountTurnsAndStartOverWhenTheMemberCountChanges() {
        Pools.IdleBits bits = new Pools.IdleBits();
        assertFalse(bits.begin(3), "the first look starts over");
        bits.set(0, false);
        bits.set(1, true);
        bits.set(2, true);
        assertTrue(bits.begin(3));
        assertTrue(bits.set(0, true), "member 0 turned idle");
        assertFalse(bits.set(1, true), "member 1 stayed idle");
        assertFalse(bits.set(2, false));
        assertFalse(bits.begin(2), "two members now: their numbers moved, so it starts over");
        bits.set(0, true);
        bits.set(1, true);
        assertTrue(bits.begin(2));
        assertFalse(bits.set(0, true), "already idle at the fresh look");
        assertFalse(bits.begin(70), "grows past one word");
        assertTrue(bits.set(69, true));
    }

    // ---- the nodes

    private static TestGraph send(String strategy) {
        return new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.MOVE_TO_POOL,
                        s -> s.withText("label", "furnaces").withItem("item", "iron").withNumber("amount", 12)
                                .withText("strategy", strategy).withText("idle_value", "state.lit"))
                .wire(1, "started", 2, "do");
    }

    @Test
    void sendPassesItsSettingsAndKeepsTheTurnInMemory() {
        CompiledGraph<String> graph = send("round_robin").compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of("pool iron furnaces 12 strategy 1 idle state.lit 0 0 at 0"), context.take());
        evaluator.restart();
        evaluator.run(context, 1);
        assertEquals(List.of("pool iron furnaces 12 strategy 1 idle state.lit 0 0 at 1"), context.take(),
                "the turn the context gave back");

        // The turn is saved with the graph's memory, unlike other actions' memory.
        LogicMemory saved = LogicMemory.CODEC.parse(JsonOps.INSTANCE,
                LogicMemory.CODEC.encodeStart(JsonOps.INSTANCE, evaluator.memory()).getOrThrow()).getOrThrow();
        LogicEvaluator<String> loaded = new LogicEvaluator<>(graph);
        loaded.load(saved);
        loaded.run(context, 2);
        assertEquals(List.of("pool iron furnaces 12 strategy 1 idle state.lit 0 0 at 2"), context.take());
    }

    @Test
    void sendWithoutAnIdleValueAndWithAWiredAmount() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.GRAPH_START)
                .node(2, NodeTypes.CONSTANT, s -> s.withNumber("value", 5))
                .node(3, NodeTypes.MOVE_TO_POOL,
                        s -> s.withText("label", "presses").withItem("item", "plate").withText("strategy", "least_full")
                                .withText("idle_when", "above").withNumber("idle_limit", 3))
                .wire(1, "started", 3, "do").wire(2, "value", 3, "amount").compiled();
        FakeContext context = new FakeContext();
        new LogicEvaluator<>(graph).run(context, 0);
        assertEquals(List.of("pool plate presses 5 strategy 2 idle  2 3 at 0"), context.take());
    }

    @Test
    void sendNeedsALabelAndAnItem() {
        CompileResult<String> result = new TestGraph().node(1, NodeTypes.MOVE_TO_POOL).compile();
        assertTrue(result.errors().stream().anyMatch(error -> "item".equals(error.port())), result.errors()::toString);
        assertTrue(result.errors().stream().anyMatch(error -> "label".equals(error.port())), result.errors()::toString);
    }

    @Test
    void turnedIdleNeedsAnIdleValue() {
        CompileResult<String> result = new TestGraph()
                .node(1, NodeTypes.POOL_MEMBER_IDLE, s -> s.withText("label", "furnaces")).compile();
        assertTrue(result.errors().stream().anyMatch(error -> "idle_value".equals(error.port())),
                result.errors()::toString);
    }

    @Test
    void statusReadsMembersIdleAndBusy() {
        CompiledGraph<String> graph = new TestGraph().node(1, NodeTypes.POOL_STATUS,
                s -> s.withText("label", "furnaces").withText("idle_value", "state.lit")).compiled();
        assertEquals(1, graph.watches().size());
        Watch<String> watch = graph.watches().getFirst();
        assertEquals(WatchKind.POOL, watch.kind());
        assertEquals("state.lit", watch.name());
        assertEquals(Pools.packIdle(Pools.IDLE_EQUAL, 0), watch.number());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext().set(WatchKind.POOL, "", "furnaces", "state.lit",
                Pools.packStatus(4, 1));
        evaluator.run(context, 0);
        assertEquals(4, evaluator.value(graph.slot(1, "members")));
        assertEquals(1, evaluator.value(graph.slot(1, "idle")));
        assertEquals(3, evaluator.value(graph.slot(1, "busy")));
        assertEquals(1, evaluator.value(graph.slot(1, "any_idle")));
        context.set(WatchKind.POOL, "", "furnaces", "state.lit", Pools.packStatus(4, 0));
        evaluator.changed(0);
        evaluator.run(context, 1);
        assertEquals(0, evaluator.value(graph.slot(1, "any_idle")));
        assertEquals(4, evaluator.value(graph.slot(1, "busy")));
    }

    @Test
    void conditionsDifferMakeTwoWatches() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.POOL_STATUS,
                        s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                .node(2, NodeTypes.POOL_STATUS,
                        s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                .node(3, NodeTypes.POOL_STATUS,
                        s -> s.withText("label", "furnaces").withText("idle_value", "comparator")
                                .withText("idle_when", "below").withNumber("idle_limit", 2))
                .compiled();
        assertEquals(2, graph.watches().size(), "equal conditions share a watch");
    }

    @Test
    void turnedIdleFiresWithHowMany() {
        CompiledGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.POOL_MEMBER_IDLE,
                        s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "idle")).wire(1, "turned_idle", 2, "do").compiled();
        Watch<String> watch = graph.watches().getFirst();
        assertEquals(WatchKind.POOL_IDLE, watch.kind());
        assertTrue(watch.kind().signal());
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        FakeContext context = new FakeContext();
        evaluator.run(context, 0);
        assertEquals(List.of(), context.take(), "nothing turned idle yet");
        evaluator.signal(0, 1);
        evaluator.signal(0, 2);
        evaluator.run(context, 1);
        assertEquals(List.of("notify idle 3"), context.take());
        assertEquals(3, evaluator.value(graph.slot(1, "count")));
    }

    @Test
    void runsAllocateNothing() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) bean;
        org.junit.jupiter.api.Assumptions
                .assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        CompiledGraph<String> graph = send("spread_evenly")
                .node(3, NodeTypes.POOL_STATUS, s -> s.withText("label", "furnaces"))
                .node(4, NodeTypes.POOL_MEMBER_IDLE,
                        s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                .compiled();
        LogicEvaluator<String> evaluator = new LogicEvaluator<>(graph);
        long[] value = new long[1];
        Pools.Planner planner = new Pools.Planner();
        boolean[] idle = {true, false, true, true};
        long[] held = new long[4];
        LogicContext<String> context = new FakeContext() {
            @Override
            public long read(Watch<String> watch) {
                return Pools.packStatus(4, (int) (value[0] % 5));
            }

            @Override
            public long sendToPool(String item, String label, long amount, int strategy, String idleValue,
                    long idleCondition, long cursor) {
                planner.plan(strategy, amount, 4, idle, held, (int) cursor);
                return Pools.nextCursor(strategy, planner.order(0), 4, (int) cursor);
            }
        };
        for (int i = 0; i < 20_000; i++) {
            value[0] = i;
            evaluator.restart();
            evaluator.signal(1, 1);
            evaluator.run(context, i);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 20_000; i < 40_000; i++) {
            value[0] = i;
            evaluator.restart();
            evaluator.signal(1, 1);
            evaluator.run(context, i);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 4096, "run allocated " + allocated + " bytes over 20000 runs");
    }

    // ---- words

    @Test
    void sentencesReadPlainly() {
        NodeSettings<String> pool = NodeSettings.<String>empty().withText("label", "furnaces").withItem("item", "iron")
                .withNumber("amount", 16);
        assertEquals("send 16 iron to the idle members of furnaces (First idle), idle while state.lit is Equal to 0",
                NodeSentence.of(NodeTypes.MOVE_TO_POOL, pool.withText("idle_value", "state.lit"), PoolNodesTest::text,
                        item -> item));
        assertEquals("send 16 iron to the members of furnaces (Round robin); idle check: {idle_value}", NodeSentence
                .of(NodeTypes.MOVE_TO_POOL, pool.withText("strategy", "round_robin"), PoolNodesTest::text, item -> item)
                .replace("Idle value", "{idle_value}"));
        assertEquals("the members of furnaces, idle while comparator is Below 1",
                NodeSentence.of(NodeTypes.POOL_STATUS,
                        NodeSettings.<String>empty().withText("label", "furnaces").withText("idle_value", "comparator")
                                .withText("idle_when", "below").withNumber("idle_limit", 1),
                        PoolNodesTest::text, item -> item));
        assertEquals("when a member of furnaces turns idle (state.lit is Equal to 0)",
                NodeSentence.of(NodeTypes.POOL_MEMBER_IDLE,
                        NodeSettings.<String>empty().withText("label", "furnaces").withText("idle_value", "state.lit"),
                        PoolNodesTest::text, item -> item));
    }

    @Test
    void cardsReadThePoolNodes() {
        LogicGraph<String> graph = new TestGraph()
                .node(1, NodeTypes.POOL_MEMBER_IDLE,
                        s -> s.withText("label", "furnaces").withText("idle_value", "state.lit"))
                .node(2, NodeTypes.MOVE_TO_POOL,
                        s -> s.withText("label", "furnaces").withItem("item", "iron").withNumber("amount", 8)
                                .withText("idle_value", "state.lit"))
                .wire(1, "turned_idle", 2, "do").node(3, NodeTypes.POOL_STATUS, s -> s.withText("label", "furnaces"))
                .node(4, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamp")).wire(3, "any_idle", 4, "on")
                .build();
        List<String> lines = RuleCardsTest.text(RuleCards.build(graph, LANG, item -> item));
        assertTrue(lines.contains("WHEN a member of furnaces turns idle (state.lit is equal to 0)"), lines::toString);
        assertTrue(lines.contains(
                "THEN send 8 iron to the idle members of furnaces (first idle), idle while state.lit is equal to 0"),
                lines::toString);
        assertTrue(lines.stream().anyMatch(line -> line.contains("switch lamp on while a member of furnaces is idle")),
                lines::toString);
    }

    @Test
    void cardEditsOfferAndChangeThePoolNodes() {
        assertTrue(CardEdits.choices(CardEdits.Add.TRIGGER).contains(NodeTypes.POOL_MEMBER_IDLE));
        assertTrue(CardEdits.choices(CardEdits.Add.CONDITION).contains(NodeTypes.POOL_STATUS));
        assertTrue(CardEdits.choices(CardEdits.Add.ACTION).contains(NodeTypes.MOVE_TO_POOL));
        LogicGraph<String> graph = send("first_idle").build();
        CardEdits.Result<String> strategy = CardEdits.set(graph, 2, "strategy",
                new SettingValue.Text<>("spread_evenly"));
        assertEquals("", strategy.refusal());
        CardEdits.Result<String> amount = CardEdits.set(strategy.graph().orElseThrow(), 2, "amount",
                new SettingValue.Num<>(0));
        CardEdits.Result<String> when = CardEdits.set(amount.graph().orElseThrow(), 2, "idle_when",
                new SettingValue.Text<>("above"));
        CardEdits.Result<String> limit = CardEdits.set(when.graph().orElseThrow(), 2, "idle_limit",
                new SettingValue.Num<>(3));
        LogicNode<String> node = java.util.Objects.requireNonNull(limit.graph().orElseThrow().node(2));
        assertEquals(Optional.of("spread_evenly"), node.settings().text("strategy"));
        assertEquals(1L, node.settings().number("amount").orElseThrow(), "clamped to at least one");
        assertEquals(Optional.of("above"), node.settings().text("idle_when"));
        assertEquals(3L, node.settings().number("idle_limit").orElseThrow());
        assertEquals(CardEdits.REFUSED_VALUE,
                CardEdits.set(graph, 2, "strategy", new SettingValue.Text<>("fastest")).refusal());
    }

    @Test
    void templateFeedsAFurnaceArray() {
        LogicGraph<String> graph = java.util.Objects
                .requireNonNull(BuiltInTemplates.all(id -> id).get("feed_furnace_array")).graph();
        CompileResult<String> result = LogicCompiler.compile(graph, 64);
        assertFalse(result.paused(), result.errors()::toString);
        List<String> lines = RuleCardsTest.text(RuleCards.build(graph, LANG, item -> item));
        assertTrue(lines.stream().anyMatch(line -> line.contains(
                "send 8 minecraft:raw_iron to the idle members of furnaces (first idle), idle while state.lit")),
                lines::toString);
        assertTrue(lines.stream().anyMatch(line -> line.contains("(spread evenly)")), lines::toString);
    }
}
