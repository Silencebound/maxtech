package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.QueueBackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.stock.BackgroundCrafting;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.crafting.stock.StockRule;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.logic.LogicContext;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.Pools;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.logic.controller.LogicWorker;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.LogicMover;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import dev.silencebound.maxtech.storage.research.Research;
import dev.silencebound.maxtech.storage.research.ResearchTeam;
import dev.silencebound.maxtech.storage.sensor.SensorBlock;
import dev.silencebound.maxtech.storage.sensor.SensorBlockEntity;
import dev.silencebound.maxtech.storage.sensor.SensorContent;
import dev.silencebound.maxtech.storage.sensor.SensorWorker;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.slf4j.Logger;

/**
 * Times the logic actions and the sensor's saved-data read against a real level (M7L2 chunk 5), next to the JUnit
 * {@code LogicCostBenchmark}. Not a test: it asserts only that the set-up works, and is registered only with
 * {@code MAXTECH_BENCH=1} in the environment, so normal suites and CI never see it. Rerun with
 * {@code MAXTECH_BENCH=1 ./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:bench_*'}; results
 * go to the log and to {@code logic-bench/} in the run directory.
 *
 * <p>
 * Each action is called directly on the controller's {@link LogicWorker} (the {@link LogicContext} a graph's run acts
 * through), on the server thread, one call per sample: the label lookup, the device's own work and whatever events it
 * sends. Moves call the labelled port's {@link LogicMover} directly, once per tick with the level's clock (the worker's
 * own clock stands still outside a run, and the port's rate allows about one item a tick). Craft requests time the
 * submission on the server thread; planning runs on the worker pool and is timed apart with
 * {@link CraftingPlanner#planNow}. The actions added after the first measurement (cancel craft, pulses, channel writes,
 * write to display, set filter, power priority, stock rule edits, shared variables, run another rule) are timed the
 * same way.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicCostBenchGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = "1".equals(System.getenv("MAXTECH_BENCH"));
    private static final String NAME = "bench_logic_actions";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final int WARM = 500;
    private static final int ITERS = 3000;
    private static final int MOVE_WARM_TICKS = 3000;
    private static final int MOVE_TICKS = 2000;

    static {
        if (ENABLED) {
            TestStorage.init();
            StorageTestEnvironment.init();
            StorageTests.function(NAME, StorageTestEnvironment.clearing(LogicCostBenchGameTests::bench));
        }
    }

    private LogicCostBenchGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        if (!ENABLED) {
            return;
        }
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("bench"),
                StorageTestEnvironment.plain());
        StorageTests.test(event, environment, NAME, STRUCTURE, 9000);
    }

    private record Stat(String name, int calls, double median, double p95, double allocBytes) {
    }

    private static Stat perTick(String name, Moves moves) {
        long[] ns = new long[moves.ns.size()];
        for (int i = 0; i < ns.length; i++) {
            ns[i] = moves.ns.get(i);
        }
        Arrays.sort(ns);
        return new Stat(name, ns.length, ns[ns.length / 2], ns[ns.length * 95 / 100], (double) moves.alloc / ns.length);
    }

    /** The sensor ignores the transfer it is handed; anything else would be a bug in this bench. */
    private record NoTransfer(NetworkState network) implements StorageTransfer {
        @Override
        public long batchLimit(StackKey.Kind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean canOperate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long insert(StackKey key, long amount, TransactionContext transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long extract(StackKey key, long amount, TransactionContext transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long beginOperation(StackKey.Kind kind) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean finishOperation(StackKey.Kind kind, long moved, TransactionContext transaction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int retryTicks() {
            throw new UnsupportedOperationException();
        }
    }

    private static long allocated() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return threads instanceof com.sun.management.ThreadMXBean sun ? sun.getCurrentThreadAllocatedBytes() : -1;
    }

    /** Times {@code op} once per sample after {@code warm} untimed calls; the int is the call's number. */
    private static Stat time(String name, int warm, int iters, IntConsumer op) {
        return time(name, warm, iters, i -> {
        }, op);
    }

    /** As {@link #time(String, int, int, IntConsumer)}, with {@code before} run untimed ahead of every call. */
    private static Stat time(String name, int warm, int iters, IntConsumer before, IntConsumer op) {
        for (int i = 0; i < warm; i++) {
            before.accept(i);
            op.accept(i);
        }
        long[] ns = new long[iters];
        long a0 = allocated();
        for (int i = 0; i < iters; i++) {
            before.accept(warm + i);
            long start = System.nanoTime();
            op.accept(warm + i);
            ns[i] = System.nanoTime() - start;
        }
        long a1 = allocated();
        Arrays.sort(ns);
        return new Stat(name, iters, ns[iters / 2], ns[iters * 95 / 100],
                a0 < 0 ? Double.NaN : (double) (a1 - a0) / iters);
    }

    private static PatternInput in(Item item, long amount) {
        return new PatternInput(new PatternIngredient.Exact(ItemKey.of(item)), amount, Optional.empty(), false);
    }

    private static SensorBlockEntity sensor(GameTestHelper helper, BlockPos pos, OwnerRecord owner) {
        helper.setBlock(pos,
                SensorContent.SENSOR.get().defaultBlockState().setValue(SensorBlock.FACING, Direction.SOUTH));
        SensorBlockEntity sensor = helper.getBlockEntity(pos, SensorBlockEntity.class);
        sensor.setOwner(owner);
        return sensor;
    }

    /**
     * Row at z 1: core controller (x 1), test storage (x 2), logic controller (x 3), port "box" (x 4) into a chest (x
     * 5). Above: condition switch "lamp" in logic mode (x 1), drive "bank" (x 2), a sensor on a furnace (x 3) and one
     * on a chest (x 4), both facing south. South: pattern provider (x 1) facing a furnace, a crafting CPU (x 2), a
     * stock keeper "keeper" (x 3). North of the logic controller: a display panel "panel".
     */
    private static void bench(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "bench_owner");
        ServerPlayer player = NetworkGameTests.player(helper.getLevel(), "bench_player");
        OwnerRecord owner = OwnerRecord.of(player);
        ResearchTeam team = Research.team(player, true);
        Research.addPoints(helper.getLevel().getServer(), team, 1000);
        Research.spendPoints(helper.getLevel().getServer(), team, MaxtechStorage.id("condition_switch"));

        BlockPos portPos = NetworkGameTests.at(4);
        helper.setBlock(portPos, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.EAST)
                .setValue(PortBlock.MODE, PortMode.EXPORT));
        PortBlockEntity port = helper.getBlockEntity(portPos, PortBlockEntity.class);
        port.setLabel(DeviceLabel.of("box", null));
        helper.setBlock(NetworkGameTests.at(5), Blocks.CHEST);

        BlockPos switchPos = NetworkGameTests.at(1).above();
        helper.setBlock(switchPos, ConditionContent.SWITCH.get());
        ConditionSwitchBlockEntity lamp = helper.getBlockEntity(switchPos, ConditionSwitchBlockEntity.class);
        lamp.setOwner(owner);
        lamp.worker().setSettings(ConditionSettings.DEFAULT.withType(ConditionType.LOGIC));
        lamp.setLabel(DeviceLabel.of("lamp", null));

        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2).above());
        drive.setLabel(DeviceLabel.of("bank", null));

        SensorWorker furnaceSensor = sensor(helper, NetworkGameTests.at(3).above(), owner).sensor();
        helper.setBlock(NetworkGameTests.at(3).above().south(), Blocks.FURNACE);
        SensorWorker chestSensor = sensor(helper, NetworkGameTests.at(4).above(), owner).sensor();
        helper.setBlock(NetworkGameTests.at(4).above().south(), Blocks.CHEST);

        CraftingPattern smelt = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(in(Items.RAW_IRON, 1), in(Items.COAL, 1)), List.of(new PatternOutput(IRON, 1, 1f)),
                Optional.empty(), Optional.empty());
        BlockPos providerPos = NetworkGameTests.at(1).south();
        helper.setBlock(providerPos, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.SOUTH));
        helper.getBlockEntity(providerPos, PatternProviderBlockEntity.class).logic().slots().setItem(0,
                PatternItem.encode(smelt));
        helper.setBlock(providerPos.south(), Blocks.FURNACE);
        helper.setBlock(NetworkGameTests.at(2).south(), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(3).south(), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3).south(),
                StockKeeperBlockEntity.class);
        keeper.setOwner(owner);
        keeper.setLabel(DeviceLabel.of("keeper", null));
        // Keeps 0, so the keeper never asks for a craft while its rule is edited.
        keeper.worker().setRule(0, StockRule.EMPTY.withKey(Optional.of(COBBLE)));
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3).north(), Direction.NORTH);
        panel.setOwner(owner);
        panel.setLabel(DeviceLabel.of("panel", null));
        // A rule the run-another-rule action calls: one "Run by another rule" node.
        entity.worker().setGraph(0, "callee", new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.RUN_BY_RULE, s -> s).node(2, NodeTypes.CONSTANT, s -> s).build());

        List<Stat> results = new ArrayList<>();
        Moves moves = new Moves();
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(network.patterns().isCraftable(IRON), "pattern not on the network yet");
            helper.assertTrue(
                    network.labels().has("box") && network.labels().has("lamp") && network.labels().has("bank")
                            && network.labels().has("keeper") && network.labels().has("panel"),
                    "labels not on the network yet");
            helper.assertTrue(furnaceSensor.dataReads() > 0, "no saved-data read yet");
            helper.assertTrue(chestSensor.reads() > 0, "no chest read yet");
        }).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            NetworkGameTests.insert(network, COBBLE, 1_000);
            NetworkGameTests.insert(network, ItemKey.of(Items.RAW_IRON), 100);
            NetworkGameTests.insert(network, ItemKey.of(Items.COAL), 100);
            NetworkGameTests.power(helper, NetworkGameTests.at(1), 1_000_000);
        }).thenIdle(40).thenWaitUntil(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            LogicMover mover = (LogicMover) network.labels().devices("box").getFirst();
            helper.assertTrue(mover.logicMove(COBBLE, 1, true, helper.getLevel().getGameTime()) > 0,
                    "the port moves nothing yet");
        }).thenExecute(() -> run(helper, entity, furnaceSensor, chestSensor, results))
                // Moves one per tick: the port's rate allows one batch per tick, so moves
                // repeated within a tick would time the refusal instead.
                .thenExecuteFor(MOVE_WARM_TICKS + MOVE_TICKS, () -> moveOnce(helper, entity, moves))
                .thenExecute(() -> more(helper, entity, panel, results))
                .thenExecute(() -> finish(helper, results, moves)).thenSucceed();
    }

    /**
     * One timed move of a cobblestone (a tier 1 port moves 16 a second), into the chest on even ticks and back on odd
     * ones.
     */
    private static void moveOnce(GameTestHelper helper, LogicControllerBlockEntity entity, Moves moves) {
        NetworkState network = LogicControllerGameTests.network(helper, entity);
        LogicMover mover = (LogicMover) network.labels().devices("box").getFirst();
        long gameTime = helper.getLevel().getGameTime();
        // Topped up untimed: the moves' energy would drain the buffer.
        NetworkGameTests.power(helper, NetworkGameTests.at(1), 10_000);
        long a0 = allocated();
        long start = System.nanoTime();
        long moved = mover.logicMove(COBBLE, 1, gameTime % 2 == 0, gameTime);
        long ns = System.nanoTime() - start;
        long a1 = allocated();
        moves.calls++;
        if (moves.calls > MOVE_WARM_TICKS && moved > 0) {
            moves.ns.add(ns);
            moves.alloc += a1 - a0;
            moves.moved += moved;
        }
    }

    /** The actions added after the first measurement, each timed in a tight loop like the first ones. */
    private static void more(GameTestHelper helper, LogicControllerBlockEntity entity, DisplayPanelBlockEntity panel,
            List<Stat> stats) {
        NetworkState network = LogicControllerGameTests.network(helper, entity);
        LogicWorker worker = entity.worker();
        LogicKey cobble = LogicKey.of(COBBLE);
        LogicKey iron = LogicKey.of(IRON);
        stats.add(time("pulse_redstone (condition switch, on then off)", WARM, ITERS,
                i -> worker.pulse("lamp", 1 + i % 15, i % 2 == 0)));
        stats.add(time("redstone_out to a channel", WARM, ITERS, i -> worker.channel(3, i % 16)));
        stats.add(
                time("pulse_redstone to a channel", WARM, ITERS, i -> worker.pulseChannel(4, 1 + i % 15, i % 2 == 0)));
        stats.add(time("set_power_priority (display)", WARM, ITERS, i -> worker.setPowerPriority("panel", i % 10)));
        stats.add(time("write_display (throttled: kept for the next write)", WARM, ITERS,
                i -> worker.writeDisplay("panel", "Count {a}", i, 0)));
        stats.add(time("write_display: the display's own write (showText)", WARM, ITERS,
                i -> panel.showText("Count " + i)));
        stats.add(time("set_filter (port, slot 1)", WARM, ITERS,
                i -> worker.setFilter("box", i % 2 == 0 ? cobble : iron, 1, LogicContext.PORT_KEEP)));
        worker.setFilter("box", null, 1, LogicContext.PORT_KEEP);
        stats.add(time("stock_rule_switch (keeper)", WARM, ITERS,
                i -> worker.switchStockRule("keeper", cobble, i % 2 == 0)));
        worker.switchStockRule("keeper", cobble, false);
        stats.add(time("stock_rule_set (keeper, restart amount)", WARM, ITERS,
                i -> worker.setStockAmount("keeper", cobble, LogicContext.STOCK_RESTART, i % 10)));
        // The worker's clock moves only inside a run, so after the first send the port's rate refuses: this times the
        // pool's lookup, idle check and plan plus a refused move; a real send adds one move per member that takes.
        stats.add(time("move_to_pool: plan and a refused move (one port)", WARM, ITERS,
                i -> worker.sendToPool(cobble, "box", 1, Pools.ROUND_ROBIN, "", 0, i)));
        stats.add(time("set_variable", WARM, ITERS, i -> worker.setVariable("bench", i, false)));
        stats.add(time("run_rule (a rule with a run-by-rule node)", WARM, ITERS, i -> worker.runRule("callee")));
        BackgroundCrafting.Request request = new BackgroundCrafting.Request(IRON, 1, 0, 0, entity.ownerRecord());
        stats.add(time("cancel_craft (one job requested just before)", 20, 200,
                i -> QueueBackgroundCrafting.INSTANCE.submit(network, request, outcome -> {
                }), i -> worker.cancelCraft(iron, false)));
    }

    /** The timed moves that moved something. */
    private static final class Moves {
        final List<Long> ns = new ArrayList<>();
        int calls;
        long moved;
        long alloc;
    }

    private static void run(GameTestHelper helper, LogicControllerBlockEntity entity, SensorWorker furnaceSensor,
            SensorWorker chestSensor, List<Stat> stats) {
        NetworkState network = LogicControllerGameTests.network(helper, entity);
        LogicWorker worker = entity.worker();
        long now = helper.getLevel().getGameTime();
        int interval = Math.max(20, StorageServerConfig.SENSOR_SAVED_DATA_INTERVAL.getAsInt());
        NoTransfer transfer = new NoTransfer(network);
        int[] taken = new int[1];
        int dataReadsBefore = furnaceSensor.dataReads();

        stats.add(time("set_priority (drive)", WARM, ITERS, i -> worker.setPriority("bank", i % 10)));
        stats.add(time("redstone_out (condition switch)", WARM, ITERS, i -> worker.redstone("lamp", i % 16)));
        stats.add(time("notify (log)", WARM, ITERS, i -> worker.notify("bench", LogicContext.CHANNEL_LOG)));
        // Ends on: a switched-off port moves nothing, and the moves come after.
        stats.add(time("device_switch (port)", WARM, ITERS, i -> worker.switchDevice("box", i % 2 == 1)));
        stats.add(time("sensor read plain (chest)", WARM, ITERS, i -> chestSensor.run(transfer, now + 20L * (i + 1))));
        stats.add(time("sensor read with saved data (furnace)", WARM / 5, ITERS / 3,
                i -> furnaceSensor.run(transfer, now + (long) interval * (i + 1))));
        int dataReads = furnaceSensor.dataReads() - dataReadsBefore;
        BackgroundCrafting.Request request = new BackgroundCrafting.Request(IRON, 1, 0, 0, entity.ownerRecord());
        stats.add(time("request_craft submit (real queue)", 20, 200, i -> {
            if (QueueBackgroundCrafting.INSTANCE.submit(network, request, outcome -> {
            })) {
                taken[0]++;
            }
        }));
        stats.add(time("craft plan off thread (planNow)", 50, 500, i -> CraftingPlanner.planNow(network, IRON, 1)));
        helper.assertTrue(taken[0] > 0, "no craft request taken");
        helper.assertTrue(dataReads >= ITERS / 3, "saved-data reads: " + dataReads);
    }

    private static void finish(GameTestHelper helper, List<Stat> stats, Moves moves) {
        helper.assertTrue(moves.ns.size() >= MOVE_TICKS / 2, "moves that moved: " + moves.ns.size());
        stats.add(perTick("move_items (port; 1 item to or from a chest; one per tick)", moves));
        StringBuilder out = new StringBuilder();
        out.append("# jvm=").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.vm.version")).append(" cpus=")
                .append(Runtime.getRuntime().availableProcessors()).append(" moved=").append(moves.moved).append('\n');
        out.append("action,calls,medianNs,p95Ns,allocBytes\n");
        for (Stat stat : stats) {
            out.append(String.format(Locale.ROOT, "%s,%d,%.0f,%.0f,%.0f%n", stat.name(), stat.calls(), stat.median(),
                    stat.p95(), stat.allocBytes()));
        }
        LOGGER.info("Logic action benchmark\n{}", out);
        try {
            Path dir = Path.of("logic-bench");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("gametest-" + System.currentTimeMillis() + ".csv"), out.toString(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write the logic action benchmark", e);
        }
    }
}
