package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.controller.module.AlarmSettings;
import dev.silencebound.maxtech.storage.controller.module.ExpansionContent;
import dev.silencebound.maxtech.storage.research.ResearchContent;
import dev.silencebound.maxtech.storage.research.ResearchTerminalBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Block;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkService;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Controller performance (PRD 8.1): finding a 33-block multiblock controller, and a 10,000-node network routed from a
 * nine-block controller over ten root links, both its full route rebuild and its idle tick (load display cadence
 * included). Each runs in its own batch with timing on; run them alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ControllerPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILL = "perf_controller_cluster_fill";
    private static final String ROUTES = "perf_controller_10k_root_links";
    private static final String MODULES = "perf_controller_all_modules_10k";
    /**
     * Scheduler runs the settled all-modules network may make in 100 ticks, not counting a telemetry sample and the
     * alarm panel's look that follows it ({@link #SAMPLE_FOLLOW_UP_TICKS}).
     */
    private static final int SETTLED_RUNS = 2;
    /**
     * Ticks from a telemetry sample in which runs are not counted as settled-network runs: the sample, the alarm
     * panel's look one tick later and the alarm module and bridge drain it wakes the tick after (about three runs). The
     * recorder samples every 200 ticks, so of two back-to-back 100-tick windows (a {@link PerfBudget} retry) one always
     * holds a sample.
     */
    private static final int SAMPLE_FOLLOW_UP_TICKS = 5;
    /** Median of one flood fill over 33 controller blocks: about 200 block entity reads. */
    private static final long FILL_BUDGET_NANOS = 500_000;
    /**
     * Median full route rebuild of 10,010 nodes over ten root links, joins and zones included; Lib's bare route tree
     * over 10,000 nodes has 8 ms.
     */
    private static final long REBUILD_BUDGET_NANOS = 25_000_000;
    /** Mean tick of the settled network, as the idle-device budget. */
    private static final long IDLE_BUDGET_NANOS = 20_000;
    private static final int SIDE = 100;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(FILL, () -> StorageTestEnvironment.clearing(ControllerPerfGameTests::clusterFill));
        FUNCTIONS.register(ROUTES, () -> StorageTestEnvironment.clearing(ControllerPerfGameTests::tenThousandNodes));
        FUNCTIONS.register(MODULES, () -> StorageTestEnvironment.clearing(ControllerPerfGameTests::allModules));
    }

    private ControllerPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        register(event, FILL, MaxtechStorage.id("empty_42x4x27"), 200);
        // 600 ticks, plus room for a retry of both measurements (PerfBudget).
        register(event, ROUTES, MaxtechStorage.id("empty_100x2x101"), 800);
        register(event, MODULES, MaxtechStorage.id("empty_100x2x101"), 800);
    }

    private static void register(RegisterGameTestsEvent event, String name, Identifier structure, int maxTicks) {
        Identifier id = MaxtechStorage.id(name);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new StorageTestEnvironment("", "", true)),
                                structure, maxTicks, 0, true)));
    }

    /**
     * A controller block under a 4 x 8 slab of 32 more (unowned, so the full size cap): the multiblock's fill, timed 50
     * times.
     */
    private static void clusterFill(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 4; z++) {
                helper.setBlock(new BlockPos(2 + x, 2, 3 + z), ControllerContent.CONTROLLER.get());
            }
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper
                        .assertValueEqual(NetworkGameTests.controllerEntity(helper, core).blockCount(), 33, "blocks"))
                .thenExecute(() -> PerfBudget.measureUnder(helper, FILL, FILL_BUDGET_NANOS, () -> {
                    ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
                    long[] nanos = new long[50];
                    for (int i = 0; i < nanos.length; i++) {
                        long start = System.nanoTime();
                        controller.detectNow();
                        nanos[i] = System.nanoTime() - start;
                    }
                    helper.assertValueEqual(controller.blockCount(), 33, "blocks after the fills");
                    return median(FILL, nanos, FILL_BUDGET_NANOS, "fill over 33 controller blocks");
                }, median -> failure("fill over 33 controller blocks", median, FILL_BUDGET_NANOS))).thenSucceed();
    }

    /**
     * A 100 x 100 layer of tier-1 cables with a nine-block controller along one edge, all ten links in use: five full
     * rebuilds are timed through the graph's tick, then 100 settled ticks.
     */
    private static void tenThousandNodes(GameTestHelper helper) {
        BlockPos core = new BlockPos(45, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int i = 1; i <= 8; i++) {
            helper.setBlock(core.east(i), ControllerContent.CONTROLLER.get());
        }
        cable(helper, core.west());
        for (int x = 0; x < SIDE; x++) {
            for (int z = 1; z <= SIDE; z++) {
                cable(helper, new BlockPos(x, 1, z));
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] idle = new long[2];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.nodeCount(), SIDE * SIDE + 10, "nodes");
            helper.assertValueEqual(network.rootLinksUsed(), 10, "root links");
        }).thenExecute(() -> NetworkGameTests.power(helper, core, 10_000)).thenIdle(20)
                .thenWaitUntil(rebuilds(helper, core, graph, ROUTES, "full rebuild of 10,010 nodes"))
                .thenExecute(() -> helper.assertValueEqual(NetworkGameTests.state(helper, core).rootLinksUsed(), 10,
                        "root links kept"))
                .thenIdle(10).thenWaitUntil(PerfBudget.measureTicks(helper, ROUTES + "_idle", 100, IDLE_BUDGET_NANOS,
                        new PerfBudget.TickMeasurement() {
                            @Override
                            public void start() {
                                idle[0] = 0;
                                idle[1] = 0;
                            }

                            @Override
                            public void tick() {
                                long nanos = graph.lastTickNanos();
                                if (nanos >= 0) {
                                    idle[0] += nanos;
                                    idle[1]++;
                                }
                            }

                            @Override
                            public long finish() {
                                helper.assertTrue(idle[1] > 50, "only " + idle[1] + " ticks timed");
                                long mean = idle[0] / idle[1];
                                LOGGER.info("{}_idle: mean tick {} ms (budget {} ms).", ROUTES, millis(mean),
                                        millis(IDLE_BUDGET_NANOS));
                                return mean;
                            }
                        }, mean -> "the settled 10,010-node network takes " + millis(mean) + " ms per tick, over "
                                + millis(IDLE_BUDGET_NANOS) + " ms"))
                .thenSucceed();
    }

    /**
     * The 10,000-cable layer of {@link #tenThousandNodes} (its edge row tier 4), with a nine-block controller along the
     * edge, two research terminals (channel-free members) after it, and then 24 network devices on the layer's edge:
     * one of each kind that was a module (M7R item 4; wireless access mounts fill the rest), and a controller alarm
     * rule for each of the first conditions. Five full rebuilds are timed, then 100 settled ticks: the graph tick stays
     * under the idle budget and the network's scheduler runs at most {@link #SETTLED_RUNS} times outside a telemetry
     * sample and what it wakes, so no module works per tick without an event. The sample is left out because a retry's
     * window, right after the first, always holds one.
     */
    private static void allModules(GameTestHelper helper) {
        BlockPos core = new BlockPos(45, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int i = 1; i <= 8; i++) {
            helper.setBlock(core.east(i), ControllerContent.CONTROLLER.get());
        }
        // The modules that filled the rest are built in or network devices now (M7R items 3 and 4): one device of each
        // kind and wireless access mounts keep the count, on the layer's edge past the terminals, so they join
        // through the cables (the edge row is tier 4, so all 24 get a channel); the controller runs a rule of each
        // condition below.
        List<Block> devices = new ArrayList<>();
        for (var entry : ExpansionContent.DEVICES) {
            devices.add(entry.get());
        }
        while (devices.size() < 24) {
            devices.add(ExpansionContent.WIRELESS_ACCESS_MOUNT.get());
        }
        for (int i = 0; i < devices.size(); i++) {
            helper.setBlock(core.east(11 + i), devices.get(i));
        }
        helper.setBlock(core.east(9), ResearchContent.TERMINAL.get());
        helper.setBlock(core.east(10), ResearchContent.TERMINAL.get());
        AlarmSettings.Type[] types = AlarmSettings.Type.values();
        cable(helper, core.west());
        for (int x = 0; x < SIDE; x++) {
            ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 1)),
                    CableContent.STORAGE_CABLE_TIER_4.type().get(), null);
            for (int z = 2; z <= SIDE; z++) {
                cable(helper, new BlockPos(x, 1, z));
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] idle = new long[2];
        // Runs counted, the scheduler's total at the last tick, runs in the ticks after a telemetry sample.
        long[] runs = new long[3];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            helper.assertValueEqual(controller.blockCount(), 9, "blocks");
            helper.assertValueEqual(controller.members().size(), 0, "nothing attaches (M7R item 6)");
            for (int i = 9; i <= 10; i++) {
                helper.assertTrue(
                        helper.getBlockEntity(core.east(i), ResearchTerminalBlockEntity.class).network() == network,
                        "the terminal at east " + i + " on the network");
            }
            int serving = 0;
            for (NetworkService service : NetworkService.values()) {
                serving += network.serviceCount(service);
            }
            helper.assertValueEqual(serving, 24, "network devices");
            helper.assertTrue(!controller.overCapacity(), "within the size cap");
            // Ten to the cables, and one to the terminal touching the last controller block: a channel-free device
            // still takes a root link (only status displays did not, until they were removed on 2026-10-18).
            helper.assertValueEqual(network.rootLinksUsed(), 11, "root links");
            helper.assertTrue(network.history() != null, "telemetry");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 10_000);
            ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
            for (int i = 0; i < StorageServerConfig.ALARM_RULES.getAsInt() && i < types.length; i++) {
                helper.assertTrue(
                        controller.recorders().addRule(new AlarmSettings(types[i], types[i].defaultThreshold())),
                        "rule " + i);
            }
        }).thenIdle(20)
                .thenWaitUntil(rebuilds(helper, core, graph, MODULES, "full rebuild of 10,035 nodes with every module"))
                .thenIdle(10).thenWaitUntil(PerfBudget.measureTicks(helper, MODULES + "_idle", 100, IDLE_BUDGET_NANOS,
                        new PerfBudget.TickMeasurement() {
                            @Override
                            public void start() {
                                idle[0] = 0;
                                idle[1] = 0;
                                runs[0] = 0;
                                runs[1] = NetworkGameTests.state(helper, core).scheduler().runs();
                                runs[2] = 0;
                            }

                            @Override
                            public void tick() {
                                countRuns(helper, core, runs);
                                long nanos = graph.lastTickNanos();
                                if (nanos >= 0) {
                                    idle[0] += nanos;
                                    idle[1]++;
                                }
                            }

                            @Override
                            public long finish() {
                                countRuns(helper, core, runs);
                                NetworkState network = NetworkGameTests.state(helper, core);
                                long ran = runs[0];
                                helper.assertTrue(ran <= SETTLED_RUNS, ran + " scheduler runs in 100 settled ticks ("
                                        + runs[2] + " more right after a telemetry sample)");
                                // The tasks do run when due: the recorder took its first sample (none would mean a
                                // stuck task).
                                NetworkHistory history = network.history();
                                helper.assertTrue(history != null && history.size() >= 1, "telemetry sampled");
                                helper.assertTrue(idle[1] > 50, "only " + idle[1] + " ticks timed");
                                long mean = idle[0] / idle[1];
                                LOGGER.info(
                                        "{}_idle: mean tick {} ms (budget {} ms), {} scheduler runs, {} after a sample.",
                                        MODULES, millis(mean), millis(IDLE_BUDGET_NANOS), ran, runs[2]);
                                return mean;
                            }
                        },
                        mean -> "the settled network with every module takes " + millis(mean) + " ms per tick, over "
                                + millis(IDLE_BUDGET_NANOS) + " ms"))
                // The anchor holds chunk tickets: let them go before the area is cleared without side effects. Each
                // broken primary hands over to the next block, which would anchor again on its next tick.
                .thenExecute(() -> {
                    for (int i = 0; i <= 8; i++) {
                        helper.destroyBlock(core.east(i));
                    }
                }).thenSucceed();
    }

    /**
     * Adds the network's scheduler runs since the last call to {@code runs[0]}, or to {@code runs[2]} within
     * {@link #SAMPLE_FOLLOW_UP_TICKS} of a telemetry sample; {@code runs[1]} holds the total of the last call.
     */
    private static void countRuns(GameTestHelper helper, BlockPos core, long[] runs) {
        NetworkState network = NetworkGameTests.state(helper, core);
        long total = network.scheduler().runs();
        NetworkHistory history = network.history();
        long sinceSample = history == null
                ? Long.MAX_VALUE
                : helper.getLevel().getGameTime() - history.lastSampleTime();
        if (sinceSample >= 0 && sinceSample < SAMPLE_FOLLOW_UP_TICKS) {
            runs[2] += total - runs[1];
        } else {
            runs[0] += total - runs[1];
        }
        runs[1] = total;
    }

    /**
     * Five full rebuilds timed through the graph's tick: every tenth tick asks for a rebuild, and over the next two
     * ticks the dearer graph tick is kept, so the reading does not depend on whether the test runs before or after the
     * level's tick. The median goes against {@link #REBUILD_BUDGET_NANOS}.
     */
    private static Runnable rebuilds(GameTestHelper helper, BlockPos core, NetworkGraph graph, String name,
            String what) {
        long[] rebuilds = new long[5];
        int[] round = new int[1];
        return PerfBudget.measureTicks(helper, name, 50, REBUILD_BUDGET_NANOS, new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                Arrays.fill(rebuilds, 0);
                round[0] = 0;
            }

            @Override
            public void tick() {
                NetworkState network = NetworkGameTests.state(helper, core);
                int tick = round[0]++;
                if (tick % 10 == 0) {
                    network.clusterChanged();
                } else if (tick % 10 <= 2) {
                    rebuilds[tick / 10] = Math.max(rebuilds[tick / 10], graph.lastTickNanos());
                }
            }

            @Override
            public long finish() {
                return median(name, rebuilds, REBUILD_BUDGET_NANOS, what);
            }
        }, median -> failure(what, median, REBUILD_BUDGET_NANOS));
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }

    private static long median(String name, long[] nanos, long budget, String what) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long median = sorted[sorted.length / 2];
        LOGGER.info("{}: {} median {} ms, max {} ms (budget {} ms).", name, what, millis(median),
                millis(sorted[sorted.length - 1]), millis(budget));
        return median;
    }

    private static String failure(String what, long median, long budget) {
        return what + " takes " + millis(median) + " ms, over the " + millis(budget) + " ms budget";
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
