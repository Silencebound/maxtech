package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeSettings;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicEvents;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.TeamBudget;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * Logic Controller performance (M7L areas B and G, PRD 8.1): an idle controller with eight graphs, and one with 20
 * graphs on every kind of event next to an idle sensor, makes its task run not once over 100 ticks, and 104 graphs on a
 * busy network, each wanting to run every tick, stay within the team's checks budget while the scheduler's pass stays
 * short. Each runs in its own batch with timing on; run them alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicControllerPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String IDLE = "perf_logic_idle_controller";
    private static final String STRESS = "perf_logic_100_graphs";
    private static final String IDLE_20 = "perf_logic_idle_20_graphs";
    /** Graphs of the 20-graph idle test; the controller holds 8 by default, so its batch raises the limit. */
    private static final int IDLE_GRAPHS = 20;
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    /** Logic controllers in the stress test; eight graphs each. */
    private static final int CONTROLLERS = 13;
    private static final int STRESS_TICKS = 100;
    /** Mean scheduler pass of the network under the stress, all its devices included. */
    private static final long PASS_BUDGET_NANOS = 2_000_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(IDLE, () -> StorageTestEnvironment.clearing(LogicControllerPerfGameTests::idle));
        FUNCTIONS.register(STRESS, () -> StorageTestEnvironment.clearing(LogicControllerPerfGameTests::stress));
        FUNCTIONS.register(IDLE_20, () -> StorageTestEnvironment.clearing(LogicControllerPerfGameTests::idleTwenty));
    }

    private LogicControllerPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        register(event, IDLE, 300);
        register(event, STRESS, 400 + STRESS_TICKS);
        register(event, IDLE_20, 400, new StorageTestEnvironment("logic.maxGraphs", "" + IDLE_GRAPHS, true));
    }

    private static void register(RegisterGameTestsEvent event, String name, int maxTicks) {
        register(event, name, maxTicks, StorageTestEnvironment.roomy("", "", true));
    }

    private static void register(RegisterGameTestsEvent event, String name, int maxTicks,
            StorageTestEnvironment environment) {
        Identifier id = MaxtechStorage.id(name);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(event.registerEnvironment(id, environment), STRUCTURE, maxTicks, 0, true)));
    }

    /** Eight graphs: six item crossings, power changes and redstone changes into the controller. */
    private static List<LogicGraph<LogicKey>> eightGraphs() {
        List<LogicGraph<LogicKey>> graphs = new ArrayList<>();
        Item[] items = {Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.SAND, Items.GRAVEL, Items.OAK_LOG};
        for (Item item : items) {
            graphs.add(LogicControllerGameTests.crossesNotify(ItemKey.of(item), 100, "full"));
        }
        graphs.add(new LogicControllerGameTests.Graph().node(1, NodeTypes.POWER_CHANGES, s -> s)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "power")).wire(1, "changed", 2, "do").build());
        graphs.add(new LogicControllerGameTests.Graph().node(1, NodeTypes.REDSTONE_CHANGES, s -> s)
                .node(2, NodeTypes.NOTIFY, s -> s.withText("text", "lever")).wire(1, "changed", 2, "do").build());
        return graphs;
    }

    /**
     * A controller with eight graphs settles; over the next 100 ticks its task does not run at all and no graph runs,
     * while an item none of them watches comes and goes.
     */
    private static void idle(GameTestHelper helper) {
        helper.assertFalse(LogicEvents.listening(), "a LogicEvents listener is left from another test");
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_idle");
        long[] before = new long[2];
        int[] tick = new int[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            List<LogicGraph<LogicKey>> graphs = eightGraphs();
            for (int i = 0; i < graphs.size(); i++) {
                entity.worker().setGraph(i, "Graph " + i, graphs.get(i));
            }
        }).thenWaitUntil(() -> {
            for (int i = 0; i < 8; i++) {
                helper.assertTrue(entity.worker().evaluator(i).orElseThrow().runs() > 0, "graph " + i);
            }
        }).thenIdle(20).thenExecute(() -> {
            before[0] = entity.worker().taskRuns();
            before[1] = entity.worker().runs();
        }).thenExecuteFor(100, () -> {
            // An item no graph watches: the network is busy, the controller hears nothing.
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            if (tick[0]++ % 2 == 0) {
                NetworkGameTests.insert(network, ItemKey.of(Items.CLAY_BALL), 3);
            } else {
                extract(network, ItemKey.of(Items.CLAY_BALL), 3);
            }
        }).thenExecute(() -> {
            long taskRuns = entity.worker().taskRuns() - before[0];
            LOGGER.info("{}: {} task runs, {} graph runs in 100 idle ticks.", IDLE, taskRuns,
                    entity.worker().runs() - before[1]);
            helper.assertValueEqual(taskRuns, 0L, "task runs while idle");
            helper.assertValueEqual(entity.worker().runs(), before[1], "graph runs while idle");
        }).thenSucceed();
    }

    /**
     * Twelve graphs on the triggers the eight above leave out, so 20 together cover every event node: a crafting job,
     * labelled devices, full storage, link load, a sensor crossing, a tag count (a contents watcher), a labelled count,
     * redstone into a labelled device, and memory and delay nodes that wait for an event. The latch drives a switch on
     * "lamp", a second sensor: its state is false, so the first run turns its target off. Pointed at "eye", it turned
     * the watched sensor off before or after its first read, depending on which task the scheduler ran first.
     */
    private static List<LogicGraph<LogicKey>> twelveMoreGraphs() {
        List<LogicGraph<LogicKey>> graphs = new ArrayList<>();
        graphs.add(counting(NodeTypes.JOB_FINISHED, "finished", s -> s));
        graphs.add(counting(NodeTypes.LABEL_DEVICES, "added", s -> s.withText("label", "pumps")));
        graphs.add(counting(NodeTypes.STORAGE_FULL, "filled", s -> s));
        graphs.add(counting(NodeTypes.LINK_OVERLOADED, "overloaded", s -> s));
        graphs.add(counting(NodeTypes.SENSOR_CROSSES, "crossed",
                s -> s.withText("label", "eye").withText("value", "items.total").withNumber("threshold", 100)));
        graphs.add(counting(NodeTypes.COUNT_CROSSES, "crossed",
                s -> s.withItem("item", LogicKey.tag(ItemTags.LOGS)).withNumber("threshold", 100)));
        graphs.add(counting(NodeTypes.COUNT_CROSSES, "crossed",
                s -> s.withItem("item", LogicKey.of(COBBLE)).withText("label", "bank").withNumber("threshold", 100)));
        graphs.add(counting(NodeTypes.REDSTONE_CHANGES, "changed", s -> s.withText("label", "eye")));
        graphs.add(new LogicControllerGameTests.Graph().node(1, NodeTypes.POWER_CHANGES, s -> s)
                .node(2, NodeTypes.DELAY, s -> s.withNumber("ticks", 40))
                .node(3, NodeTypes.NOTIFY, s -> s.withText("text", "late")).wire(1, "changed", 2, "start")
                .wire(2, "done", 3, "do").build());
        graphs.add(
                new LogicControllerGameTests.Graph()
                        .node(1, NodeTypes.COUNT_CROSSES,
                                s -> s.withItem("item", LogicKey.of(ItemKey.of(Items.DIRT))).withNumber("threshold",
                                        100))
                        .node(2, NodeTypes.LATCH, s -> s)
                        .node(3, NodeTypes.DEVICE_SWITCH, s -> s.withText("label", "lamp")).wire(1, "crossed", 2, "set")
                        .wire(2, "state", 3, "on").build());
        graphs.add(
                new LogicControllerGameTests.Graph()
                        .node(1, NodeTypes.COUNT_CROSSES,
                                s -> s.withItem("item", LogicKey.of(ItemKey.of(Items.SAND))).withNumber("threshold",
                                        100))
                        .node(2, NodeTypes.SENSOR_VALUE,
                                s -> s.withText("label", "eye").withText("value", "items.total"))
                        .node(3, NodeTypes.HOLD, s -> s).wire(2, "value", 3, "value").wire(1, "crossed", 3, "store")
                        .build());
        graphs.add(
                new LogicControllerGameTests.Graph()
                        .node(1, NodeTypes.COUNT_CROSSES,
                                s -> s.withItem("item", LogicKey.of(ItemKey.of(Items.GRAVEL))).withNumber("threshold",
                                        100))
                        .node(2, NodeTypes.ITEM_COUNT, s -> s.withItem("item", LogicKey.of(COBBLE)))
                        .node(3, NodeTypes.COMPARE, s -> s.withNumber("threshold", 10)).node(4, NodeTypes.IF, s -> s)
                        .node(5, NodeTypes.REQUEST_CRAFT, s -> s.withItem("item", LogicKey.of(ItemKey.of(Items.STICK))))
                        .wire(2, "count", 3, "value").wire(1, "crossed", 4, "when").wire(3, "result", 4, "condition")
                        .wire(4, "then", 5, "do").build());
        return graphs;
    }

    private static LogicGraph<LogicKey> counting(NodeType trigger, String output,
            UnaryOperator<NodeSettings<LogicKey>> settings) {
        return new LogicControllerGameTests.Graph().node(1, trigger, settings).node(2, NodeTypes.COUNTER, s -> s)
                .wire(1, output, 2, "up").build();
    }

    /**
     * One controller holding 20 graphs (eight above plus {@link #twelveMoreGraphs}) next to a sensor "eye" on a chest,
     * on a network that is busy with an item none of them counts. Once settled, over 100 ticks the controller's task
     * does not run, no graph runs and the sensor, still on, does not read. A second sensor "lamp" is what the switch
     * graph turns off.
     */
    private static void idleTwenty(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lp_idle_20");
        // The test device at x 2 is storage: "bank" for the labelled count.
        helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class)
                .setLabel(DeviceLabel.of("bank", null));
        BlockPos sensorPos = NetworkGameTests.at(4);
        helper.setBlock(sensorPos,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.SOUTH));
        SensorBlockEntity sensor = helper.getBlockEntity(sensorPos, SensorBlockEntity.class);
        sensor.setLabel(DeviceLabel.of("eye", null));
        helper.setBlock(sensorPos.south(), Blocks.CHEST);
        ChestBlockEntity chest = helper.getBlockEntity(sensorPos.south(), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        chest.setChanged();
        BlockPos lampPos = NetworkGameTests.at(5);
        helper.setBlock(lampPos,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.SOUTH));
        SensorBlockEntity lamp = helper.getBlockEntity(lampPos, SensorBlockEntity.class);
        lamp.setLabel(DeviceLabel.of("lamp", null));
        List<LogicGraph<LogicKey>> graphs = new ArrayList<>(eightGraphs());
        graphs.addAll(twelveMoreGraphs());
        long[] before = new long[3];
        int[] tick = new int[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            helper.assertValueEqual(graphs.size(), IDLE_GRAPHS, "graphs");
            for (int i = 0; i < graphs.size(); i++) {
                entity.worker().setGraph(i, "Graph " + i, graphs.get(i));
            }
        }).thenWaitUntil(() -> {
            for (int i = 0; i < IDLE_GRAPHS; i++) {
                helper.assertTrue(entity.worker().errors(i).isEmpty(), "graph " + i + ": " + entity.worker().errors(i));
                helper.assertTrue(entity.worker().evaluator(i).orElseThrow().runs() > 0, "graph " + i);
            }
            helper.assertTrue(sensor.sensor().value("items.total") == 5, "the sensor has not read its chest");
            helper.assertTrue(lamp.deviceLink().switchedOff(), "the switch graph has not turned the lamp off");
        }).thenIdle(20).thenExecute(() -> {
            before[0] = entity.worker().taskRuns();
            before[1] = entity.worker().runs();
            before[2] = sensor.sensor().reads();
        }).thenExecuteFor(100, () -> {
            // An item no graph counts, in no counted tag: the network is busy, the controller hears nothing.
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            if (tick[0]++ % 2 == 0) {
                NetworkGameTests.insert(network, ItemKey.of(Items.CLAY_BALL), 3);
            } else {
                extract(network, ItemKey.of(Items.CLAY_BALL), 3);
            }
        }).thenExecute(() -> {
            long taskRuns = entity.worker().taskRuns() - before[0];
            LOGGER.info("{}: {} task runs, {} graph runs, {} sensor reads in 100 idle ticks.", IDLE_20, taskRuns,
                    entity.worker().runs() - before[1], sensor.sensor().reads() - before[2]);
            helper.assertValueEqual(taskRuns, 0L, "task runs while idle");
            helper.assertValueEqual(entity.worker().runs(), before[1], "graph runs while idle");
            helper.assertValueEqual((long) sensor.sensor().reads(), before[2], "sensor reads while idle");
            // Switched off, the sensor would not read whatever happened, and the check above would prove nothing.
            helper.assertFalse(sensor.deviceLink().stopped(), "the watched sensor is off");
        }).thenSucceed();
    }

    /**
     * Thirteen controllers with eight cobblestone-crossing graphs each (104 graphs) on one network of one owner;
     * cobblestone swings across the limit every tick for 100 ticks, so every graph wants to run every tick. The team is
     * charged at most its budget per second (plus one run's overspend), graphs wait and run again in later seconds, and
     * the network's scheduler pass stays short.
     */
    private static void stress(GameTestHelper helper) {
        // Measured as without a script listener: every batch's teardown removes one (StorageTestEnvironment).
        helper.assertFalse(LogicEvents.listening(), "a LogicEvents listener is left from another test");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        NetworkGameTests.power(helper, core, 100_000_000);
        OwnerRecord owner = OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lp_stress"));
        List<LogicControllerBlockEntity> controllers = new ArrayList<>();
        for (int i = 0; i < CONTROLLERS; i++) {
            BlockPos pos = NetworkGameTests.at(3 + i);
            helper.setBlock(pos, LogicContent.CONTROLLER.get());
            LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, pos);
            entity.setOwner(owner);
            controllers.add(entity);
        }
        UUID team = TeamBudget.team(helper.getLevel(), owner);
        long[] start = new long[2];
        long[] passes = new long[2];
        long[] waited = new long[1];
        int[] tick = new int[1];
        java.util.Set<Long> secondsWithRuns = new java.util.HashSet<>();
        long[] lastRuns = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            for (LogicControllerBlockEntity entity : controllers) {
                LogicControllerGameTests.network(helper, entity);
            }
        }).thenExecute(() -> {
            for (LogicControllerBlockEntity entity : controllers) {
                for (int g = 0; g < 8; g++) {
                    entity.worker().setGraph(g, "Cobble " + g,
                            LogicControllerGameTests.crossesNotify(COBBLE, 10, "full"));
                }
            }
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, controllers.getFirst()), COBBLE, 5);
        }).thenIdle(40).thenWaitUntil(PerfBudget.measureTicks(helper, STRESS, STRESS_TICKS, PASS_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    start[0] = TeamBudget.total(team);
                    start[1] = helper.getLevel().getGameTime();
                    passes[0] = 0;
                    passes[1] = 0;
                    waited[0] = 0;
                    tick[0] = 0;
                    secondsWithRuns.clear();
                    lastRuns[0] = 0;
                }, () -> {
                    NetworkState network = LogicControllerGameTests.network(helper, controllers.getFirst());
                    if (tick[0]++ % 2 == 0) {
                        NetworkGameTests.insert(network, COBBLE, 10);
                    } else {
                        extract(network, COBBLE, 10);
                    }
                    long nanos = network.scheduler().lastPassNanos();
                    if (nanos >= 0) {
                        passes[0] += nanos;
                        passes[1]++;
                    }
                    long runs = 0;
                    for (LogicControllerBlockEntity entity : controllers) {
                        runs += entity.worker().runs();
                        waited[0] += entity.worker().waiting();
                    }
                    if (runs > lastRuns[0]) {
                        secondsWithRuns.add(helper.getLevel().getGameTime() / TeamBudget.SECOND);
                    }
                    lastRuns[0] = runs;
                }, () -> {
                    long charged = TeamBudget.total(team) - start[0];
                    long seconds = (helper.getLevel().getGameTime() - start[1]) / TeamBudget.SECOND + 1;
                    long allowed = TeamBudget.limit() * (seconds + 1);
                    long mean = passes[1] == 0 ? 0 : passes[0] / passes[1];
                    LOGGER.info(
                            "{}: {} checks over {} seconds (budget {} per second), {} graph-ticks waited, mean pass {} ms.",
                            STRESS, charged, seconds, TeamBudget.limit(), waited[0],
                            String.format(Locale.ROOT, "%.3f", mean / 1_000_000.0));
                    helper.assertTrue(charged <= allowed, "charged " + charged + " checks, over " + allowed);
                    helper.assertTrue(waited[0] > 0, "no graph waited for the budget");
                    helper.assertTrue(secondsWithRuns.size() >= 4, "graphs ran in only " + secondsWithRuns.size()
                            + " seconds; they should resume each second");
                    helper.assertTrue(passes[1] > 50, "only " + passes[1] + " passes timed");
                    return mean;
                }), mean -> "the network's scheduler pass takes " + mean + " ns under 104 busy graphs")).thenSucceed();
    }

    private static long extract(NetworkState network, StackKey key, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            long moved = network.extract(key, amount, transaction);
            transaction.commit();
            return moved;
        }
    }
}
