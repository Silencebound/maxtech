package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.LogicLang;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.RunTrace;
import dev.silencebound.maxtech.storage.logic.controller.LogicAction;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.NetworkNotices;
import dev.silencebound.maxtech.storage.network.NetworkState;
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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The extra node types of batch 1 in a real controller: a timer fires at its due tick through one scheduled wake and
 * the controller does no work between; graph start fires when a controller is loaded and when a graph is switched on;
 * toggle, statistics, edge and cooldown keep their memory through saving and loading.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicExtraNodeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_extra_timer_fires_on_time", LogicExtraNodeGameTests::timer);
        register("logic_extra_graph_start_on_load", LogicExtraNodeGameTests::graphStart);
        register("logic_extra_memory_saved_and_loaded", LogicExtraNodeGameTests::memory);
    }

    private LogicExtraNodeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_extra"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity) {
        return entity.worker().evaluator(0)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph 0 is paused")));
    }

    private static List<String> described(List<LogicAction> actions) {
        List<String> out = new ArrayList<>();
        for (LogicAction action : actions) {
            out.add(action.describe());
        }
        return out;
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }

    /**
     * A one-second timer posts to the network's log: three notices exactly 20 ticks apart, each at the tick the timer
     * was due, and between two of them the controller's task ran once (no polling while it waits).
     */
    private static void timer(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lx_timer");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.TIMER, s -> s.withNumber("interval", 1))
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "tick").withFlag("toast", false))
                .wire(1, "fired", 2, "do").build();
        long[] due = new long[2];
        long[] taskRuns = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity))
                .thenExecute(() -> entity.worker().setGraph(0, "Timer", graph))
                .thenWaitUntil(() -> helper.assertTrue(evaluator(helper, entity).runs() > 0, "not started"))
                .thenExecute(
                        () -> due[0] = evaluator(helper, entity).due(evaluator(helper, entity).graph().position(1)))
                .thenWaitUntil(() -> helper.assertValueEqual(notices(helper, entity).size(), 2, "notices"))
                .thenExecute(() -> {
                    taskRuns[0] = entity.worker().taskRuns();
                    due[1] = evaluator(helper, entity).due(evaluator(helper, entity).graph().position(1));
                }).thenWaitUntil(() -> helper.assertValueEqual(notices(helper, entity).size(), 3, "notices"))
                .thenExecute(() -> {
                    List<NetworkNotices.Notice> notices = notices(helper, entity);
                    helper.assertValueEqual(notices.get(0).gameTime(), due[0], "the first fired at its due tick");
                    helper.assertValueEqual(notices.get(1).gameTime(), due[0] + 20, "a second later");
                    helper.assertValueEqual(notices.get(2).gameTime(), due[1], "the third at its due tick");
                    helper.assertValueEqual(notices.get(2).gameTime(), due[0] + 40, "two seconds after the first");
                    helper.assertValueEqual(entity.worker().taskRuns() - taskRuns[0], 1L,
                            "task runs while the timer waited");
                }).thenSucceed();
    }

    private static List<NetworkNotices.Notice> notices(GameTestHelper helper, LogicControllerBlockEntity entity) {
        List<NetworkNotices.Notice> tick = new ArrayList<>();
        for (NetworkNotices.Notice notice : LogicControllerGameTests.network(helper, entity).notices().lines()) {
            if (notice.text().equals("tick")) {
                tick.add(notice);
            }
        }
        return tick;
    }

    /**
     * "Graph starts" sends a notice when the graph is saved; switching the graph off and on sends one more; a
     * controller loaded from the saved block entity sends one again; a recheck while it runs sends none.
     */
    private static void graphStart(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "lx_start");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph().node(1, NodeTypes.GRAPH_START, s -> s)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "started")).wire(1, "started", 2, "do").build();
        BlockPos pos = NetworkGameTests.at(3);
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, first))
                .thenExecute(() -> first.worker().setGraph(0, "Start", graph))
                .thenWaitUntil(() -> helper.assertValueEqual(described(first.worker().actions()),
                        List.of("notify \"started\" 3"), "on save"))
                .thenExecute(() -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, first), COBBLE, 5))
                .thenIdle(5).thenExecute(() -> {
                    helper.assertValueEqual(first.worker().actions().size(), 1, "a network change is no start");
                    first.worker().setEnabled(0, false);
                    first.worker().setEnabled(0, true);
                }).thenWaitUntil(() -> helper.assertValueEqual(first.worker().actions().size(), 2, "switched on"))
                .thenExecute(() -> {
                    saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, pos).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        described(LogicControllerGameTests.entity(helper, pos).worker().actions()),
                        List.of("notify \"started\" 3"), "on load"))
                .thenIdle(10)
                .thenExecute(() -> helper.assertValueEqual(
                        LogicControllerGameTests.entity(helper, pos).worker().actions().size(), 1, "once per load"))
                .thenSucceed();
    }

    /**
     * Cobblestone over 10 (an edge on a compare) flips a toggle and passes a one-minute cooldown to a notice; a
     * statistics node samples the count. After saving and loading: the edge does not fire again, a second crossing
     * flips the toggle back (it remembered yes), the cooldown still blocks (it remembered when it last passed, and the
     * why line says so), and the statistics average the samples from before the save too.
     */
    private static void memory(GameTestHelper helper) {
        LogicControllerBlockEntity first = LogicControllerGameTests.setUp(helper, "lx_memory");
        LogicGraph<LogicKey> graph = new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                .node(2, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10)).node(3, NodeTypes.EDGE, s -> s)
                .node(4, NodeTypes.TOGGLE, s -> s).node(5, NodeTypes.COOLDOWN, s -> s.withNumber("seconds", 60))
                .node(6, NodeTypes.NOTIFY, s -> s.withText("text", "cooled")).node(7, NodeTypes.STATS, s -> s)
                .wire(1, "count", 2, "value").wire(2, "result", 3, "value").wire(3, "turned_yes", 4, "flip")
                .wire(3, "turned_yes", 5, "in").wire(5, "passed", 6, "do").wire(1, "count", 7, "value").build();
        BlockPos pos = NetworkGameTests.at(3);
        CompoundTag[] saved = new CompoundTag[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, first))
                .thenExecute(() -> first.worker().setGraph(0, "Memory", graph))
                .thenWaitUntil(() -> helper.assertTrue(evaluator(helper, first).runs() > 0, "not started"))
                .thenExecute(() -> NetworkGameTests.insert(LogicControllerGameTests.network(helper, first), COBBLE, 20))
                .thenWaitUntil(() -> helper.assertValueEqual(described(first.worker().actions()),
                        List.of("notify \"cooled\" 3"), "the first crossing"))
                .thenExecute(() -> {
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, first);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(4, "state")), 1L, "toggle");
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(7, "average")), 10L, "0 and 20");
                    saved[0] = first.saveWithoutMetadata(helper.getLevel().registryAccess());
                    helper.setBlock(pos, Blocks.AIR);
                    helper.setBlock(pos, LogicContent.CONTROLLER.get());
                    LogicControllerGameTests.entity(helper, pos).loadCustomOnly(TagValueInput
                            .create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved[0]));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        evaluator(helper, LogicControllerGameTests.entity(helper, pos)).runs() > 0, "not loaded"))
                .thenIdle(3).thenExecute(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    helper.assertValueEqual(described(loaded.worker().actions()), List.of(), "actions after the load");
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, loaded);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(4, "state")), 1L,
                            "the toggle kept yes");
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(7, "average")), 10L,
                            "the statistics kept their samples");
                    extract(LogicControllerGameTests.network(helper, loaded), COBBLE, 15);
                }).thenIdle(3)
                .thenExecute(() -> NetworkGameTests.insert(
                        LogicControllerGameTests.network(helper, LogicControllerGameTests.entity(helper, pos)), COBBLE,
                        15))
                .thenIdle(3).thenExecute(() -> {
                    LogicControllerBlockEntity loaded = LogicControllerGameTests.entity(helper, pos);
                    LogicEvaluator<LogicKey> evaluator = evaluator(helper, loaded);
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(4, "state")), 0L,
                            "the second crossing flipped the remembered yes");
                    helper.assertValueEqual(described(loaded.worker().actions()), List.of(),
                            "the cooldown still blocks");
                    RunTrace trace = evaluator.trace();
                    int at = trace.find(5);
                    helper.assertTrue(at >= 0 && trace.reason(at).equals(LogicLang.WHY_COOLDOWN),
                            "the why line names the cooldown");
                    helper.assertValueEqual(evaluator.value(evaluator.graph().slot(7, "average")), 11L,
                            "0, 20, 5 and 20");
                }).thenSucceed();
    }
}
