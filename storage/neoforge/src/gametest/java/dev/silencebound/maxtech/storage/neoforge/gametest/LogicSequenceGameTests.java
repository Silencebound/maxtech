package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Sequences;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Step sequences (M7L2 row 16) in a real controller: a sequence drives three labelled condition switches through fill,
 * wait and empty; it keeps its step and its time limit through saving and loading; a time limit moves it on at its due
 * tick with no work in between; and in simulate mode it moves on its copied memory while the switches stay as they
 * were. The row is the logic controller layout ({@link LogicControllerGameTests#setUp}): core at x 1, test drive at 2,
 * logic controller at 3, then the switches "inlet", "heater" and "outlet" at 4, 5 and 6.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicSequenceGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String[] LABELS = {"inlet", "heater", "outlet"};
    /** The sequence's node id in {@link #batch}. */
    private static final int SEQ = 1;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_sequence_drives_three_devices", LogicSequenceGameTests::drives);
        register("logic_sequence_survives_save_and_load", LogicSequenceGameTests::saved);
        register("logic_sequence_time_limit_fires", LogicSequenceGameTests::timeLimit);
        register("logic_sequence_simulate_leaves_world", LogicSequenceGameTests::simulate);
        register("perf_logic_sequences_idle", LogicSequenceGameTests::idle);
    }

    private LogicSequenceGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("logic_sequence"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 400);
        }
    }

    // ---- helpers

    /** The controller row plus the three switches in logic mode, owned and researched by the controller's owner. */
    private static Setup setUp(GameTestHelper helper, String name) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, name);
        ServerPlayer owner = NetworkGameTests.player(helper.getLevel(), name + "_owner");
        entity.setOwner(OwnerRecord.of(owner));
        ResearchTeam team = Research.team(owner, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));
        ConditionSwitchBlockEntity[] switches = new ConditionSwitchBlockEntity[LABELS.length];
        for (int i = 0; i < LABELS.length; i++) {
            BlockPos pos = NetworkGameTests.at(4 + i);
            helper.setBlock(pos, ConditionContent.SWITCH.get());
            ConditionSwitchBlockEntity device = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
            device.setOwner(OwnerRecord.of(owner));
            device.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
            device.setLabel(DeviceLabel.of(LABELS[i], null));
            switches[i] = device;
        }
        return new Setup(entity, List.of(switches));
    }

    private record Setup(LogicControllerBlockEntity controller, List<ConditionSwitchBlockEntity> switches) {
    }

    /**
     * Fill (on to "wait" once the network holds 10 cobblestone), wait (on after {@code waitSeconds}, 0 for never),
     * empty (back to "fill" once no cobblestone is left); while at each step its switch gives 15, and entering "wait"
     * posts a notice.
     */
    private static LogicGraph<LogicKey> batch(long waitSeconds) {
        LogicControllerGameTests.Graph graph = new LogicControllerGameTests.Graph()
                .node(SEQ, NodeTypes.SEQUENCE,
                        s -> s.withText("name_1", "fill").withText("name_2", "wait")
                                .withNumber("timeout_2", waitSeconds).withText("name_3", "empty"))
                .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 9))
                .node(4, NodeTypes.COMPARE, s -> s.withText("mode", "below").withNumber("threshold", 1))
                .wire(2, "count", 3, "value").wire(2, "count", 4, "value").wire(3, "result", SEQ, Sequences.next(1))
                .wire(4, "result", SEQ, Sequences.next(3))
                .node(5, NodeTypes.NOTIFY, s -> s.withText("text", "waiting").withFlag("toast", false))
                .wire(SEQ, Sequences.entered(2), 5, "do");
        String[] steps = {"fill", "wait", "empty"};
        for (int i = 0; i < steps.length; i++) {
            int check = 10 + i;
            int out = 20 + i;
            String which = steps[i];
            String label = LABELS[i];
            graph.node(check, NodeTypes.STEP_IS, s -> s.withText("which", which)).wire(SEQ, "step", check, "step")
                    .node(out, NodeTypes.REDSTONE_OUT, s -> s.withText("label", label))
                    .wire(check, "result", out, "on");
        }
        return graph.build();
    }

    /** Only the switch of step {@code step} (1 to 3) gives a signal. */
    private static void only(GameTestHelper helper, Setup setup, int step) {
        for (int i = 0; i < LABELS.length; i++) {
            helper.assertValueEqual(setup.switches().get(i).worker().strength(), i + 1 == step ? 15 : 0, LABELS[i]);
        }
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        if (entity.worker().size() == 0) {
            throw helper.assertionException(Component.literal("no graph yet"));
        }
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static long step(GameTestHelper helper, LogicControllerBlockEntity entity) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity);
        return evaluator.step(evaluator.graph().position(SEQ));
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    private static int notices(GameTestHelper helper, LogicControllerBlockEntity entity, String text) {
        int count = 0;
        for (NetworkNotices.Notice notice : LogicControllerGameTests.network(helper, entity).notices().lines()) {
            if (notice.text().equals(text)) {
                count++;
            }
        }
        return count;
    }

    // ---- tests

    /**
     * The sequence starts at "fill" (inlet on), goes to "wait" when 10 cobblestone are in (heater on, one notice),
     * after a second to "empty" (outlet on), and back to "fill" once the cobblestone is taken out.
     */
    private static void drives(GameTestHelper helper) {
        Setup setup = setUp(helper, "ls_drive");
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Batch", batch(1)))
                .thenWaitUntil(() -> only(helper, setup, 1))
                .thenExecute(
                        () -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> only(helper, setup, 2))
                .thenExecute(() -> helper.assertValueEqual(notices(helper, entity, "waiting"), 1, "entered wait"))
                .thenWaitUntil(() -> only(helper, setup, 3))
                .thenExecute(() -> extract(LogicControllerGameTests.network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> only(helper, setup, 1)).thenIdle(5)
                .thenExecute(() -> helper.assertValueEqual(notices(helper, entity, "waiting"), 1, "wait entered once"))
                .thenSucceed();
    }

    /**
     * Saved while at "wait" with a 3-second limit: the loaded controller is still at "wait" (heater on), posts no
     * second "waiting" notice, and moves to "empty" at the saved due tick, not 3 seconds after the load.
     */
    private static void saved(GameTestHelper helper) {
        Setup setup = setUp(helper, "ls_save");
        LogicControllerBlockEntity first = setup.controller();
        BlockPos pos = NetworkGameTests.at(3);
        CompoundTag[] saved = new CompoundTag[1];
        long[] due = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, first))
                .thenExecute(() -> first.worker().setGraph(0, "Batch", batch(3)))
                .thenWaitUntil(() -> only(helper, setup, 1))
                .thenExecute(() -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, first), COBBLE, 10))
                .thenWaitUntil(() -> only(helper, setup, 2)).thenIdle(10).thenExecute(() -> {
                    due[0] = evaluator(helper, first).nextWake();
                    saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, pos).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        evaluator(helper, LogicControllerGameTests.entity(helper, pos)).runs() > 0, "not loaded"))
                .thenExecute(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    helper.assertValueEqual(step(helper, loaded), 2L, "still at wait");
                    helper.assertValueEqual(evaluator(helper, loaded).nextWake(), due[0], "the saved due tick");
                    helper.assertTrue(helper.getLevel().getGameTime() < due[0], "loaded before it was due");
                    only(helper, setup, 2);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(step(helper, LogicControllerGameTests.entity(helper, pos)),
                        3L, "on to empty"))
                .thenExecute(() -> {
                    helper.assertTrue(helper.getLevel().getGameTime() <= due[0] + 1,
                            "moved on at the saved due tick, not a full limit after the load");
                    helper.assertValueEqual(notices(helper, LogicControllerGameTests.entity(helper, pos), "waiting"), 1,
                            "the load entered nothing");
                }).thenWaitUntil(() -> only(helper, setup, 3)).thenSucceed();
    }

    /**
     * A "fill" step with a one-second limit and no condition: "time ran out" posts a notice at the due tick, the
     * sequence is at step 2, and the controller's task ran only for that between entering and the limit.
     */
    private static void timeLimit(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ls_time");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(SEQ, NodeTypes.SEQUENCE, s -> s.withNumber("steps", 2).withNumber("timeout_1", 1))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "late").withFlag("toast", false))
                .wire(SEQ, Sequences.TIME_UP, 2, "do").build();
        long[] due = new long[1];
        long[] taskRuns = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Late", graph))
                .thenWaitUntil(() -> helper.assertValueEqual(step(helper, entity), 1L, "started")).thenIdle(2)
                .thenExecute(() -> {
                    due[0] = evaluator(helper, entity).nextWake();
                    taskRuns[0] = entity.worker().taskRuns();
                }).thenWaitUntil(() -> helper.assertValueEqual(notices(helper, entity, "late"), 1, "time ran out"))
                .thenExecute(() -> {
                    helper.assertValueEqual(step(helper, entity), 2L, "on to step 2");
                    NetworkNotices.Notice notice = null;
                    for (NetworkNotices.Notice line : LogicControllerGameTests.network(helper, entity).notices()
                            .lines()) {
                        if (line.text().equals("late")) {
                            notice = line;
                        }
                    }
                    if (notice == null) {
                        throw helper.assertionException(Component.literal("no notice"));
                    }
                    helper.assertValueEqual(notice.gameTime(), due[0], "at the due tick");
                    helper.assertTrue(entity.worker().taskRuns() - taskRuns[0] <= 2,
                            "no work while waiting: " + (entity.worker().taskRuns() - taskRuns[0]) + " task runs");
                }).thenSucceed();
    }

    /**
     * Eight sequences (the controller's default limit of graphs), each at a step that waits for 1,000 cobblestone or
     * ten minutes, with "step is" switches: while the network is busy with an item none of them counts, the
     * controller's task does not run and no graph runs for 100 ticks. A waiting step costs nothing until its condition
     * changes or its time is due.
     */
    private static void idle(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "ls_idle");
        int graphs = 8;
        long[] before = new long[2];
        int[] tick = new int[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            for (int i = 0; i < graphs; i++) {
                LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                        .node(SEQ, NodeTypes.SEQUENCE, s -> s.withNumber("timeout_1", 600))
                        .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                        .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 999))
                        .node(4, NodeTypes.STEP_IS, s -> s.withText("which", "2"))
                        .node(5, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "pump")).wire(2, "count", 3, "value")
                        .wire(3, "result", SEQ, Sequences.next(1)).wire(SEQ, "step", 4, "step")
                        .wire(4, "result", 5, "on").build();
                entity.worker().setGraph(i, "Sequence " + i, graph);
            }
        }).thenWaitUntil(() -> {
            for (int i = 0; i < graphs; i++) {
                helper.assertTrue(entity.worker().errors(i).isEmpty(), "graph " + i + ": " + entity.worker().errors(i));
                LogicEvaluator<LogicKey> evaluator = entity.worker().evaluator(i).orElseThrow();
                helper.assertValueEqual(evaluator.step(evaluator.graph().position(SEQ)), 1L, "graph " + i + " step");
            }
        }).thenIdle(20).thenExecute(() -> {
            before[0] = entity.worker().taskRuns();
            before[1] = entity.worker().runs();
        }).thenExecuteFor(100, () -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            if (tick[0]++ % 2 == 0) {
                NetworkGameTests.insert(network, ItemKey.of(Items.CLAY_BALL), 3);
            } else {
                extract(network, ItemKey.of(Items.CLAY_BALL), 3);
            }
        }).thenExecute(() -> {
            helper.assertValueEqual(entity.worker().taskRuns() - before[0], 0L, "task runs while idle");
            helper.assertValueEqual(entity.worker().runs() - before[1], 0L, "graph runs while idle");
        }).thenSucceed();
    }

    /**
     * Simulating, the sequence moves to "wait" on the copied memory when cobblestone comes in, recording its redstone
     * as "would", while the inlet stays on and the heater off; leaving simulate gives back the real step, "fill".
     */
    private static void simulate(GameTestHelper helper) {
        Setup setup = setUp(helper, "ls_sim");
        LogicControllerBlockEntity entity = setup.controller();
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Batch", batch(0)))
                .thenWaitUntil(() -> only(helper, setup, 1)).thenExecute(() -> entity.worker().setSimulate(0, true))
                .thenWaitUntil(() -> helper.assertTrue(evaluator(helper, entity).runs() > 0, "simulating"))
                .thenExecute(
                        () -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertValueEqual(step(helper, entity), 2L, "simulated step")).thenIdle(5)
                .thenExecute(() -> {
                    only(helper, setup, 1);
                    helper.assertTrue(entity.worker().actions().stream().anyMatch(
                            a -> a.kind() == LogicAction.Kind.REDSTONE && a.simulated() && a.label().equals("heater")),
                            "the heater's redstone was recorded");
                    helper.assertTrue(
                            entity.worker().actions().stream()
                                    .noneMatch(a -> a.label().equals("heater") && a.value() > 0 && !a.simulated()),
                            "the heater was never really turned on");
                    helper.assertValueEqual(notices(helper, entity, "waiting"), 0, "no real notice");
                    extract(LogicControllerGameTests.network(helper, entity), COBBLE, 10);
                }).thenIdle(3).thenExecute(() -> entity.worker().setSimulate(0, false))
                .thenWaitUntil(() -> helper.assertTrue(evaluator(helper, entity).runs() > 0, "real again")).thenIdle(3)
                .thenExecute(() -> {
                    helper.assertValueEqual(step(helper, entity), 1L, "the real step came back");
                    only(helper, setup, 1);
                }).thenSucceed();
    }
}
