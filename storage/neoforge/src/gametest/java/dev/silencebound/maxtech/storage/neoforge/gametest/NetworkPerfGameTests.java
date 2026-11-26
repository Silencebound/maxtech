package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.ArrayList;
import java.util.List;
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
 * 500 idle storage devices (PRD 7.2, Performance: the idle-port budget): a controller and a 25 by 20 block of devices
 * that touch each other, each with a scheduled task that never finds work. Once their backoff has grown, the mean time
 * per tick of the level's task scheduler plus the storage network's tick must stay under budget, the tasks must run
 * only as often as their backoff allows, and the settled network must not rebuild its routes. Runs in its own batch
 * with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String IDLE = "perf_storage_idle_devices";
    private static final int COLUMNS = 25;
    private static final int ROWS = 20;
    private static final int WARMUP_TICKS = 400;
    private static final int MEASURED_TICKS = 200;
    /** Mean per tick for 500 idle devices, as for Lib's 500 idle tasks; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(IDLE, () -> StorageTestEnvironment.clearing(NetworkPerfGameTests::idleDevices));
    }

    private NetworkPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, WARMUP_TICKS + 2 * (MEASURED_TICKS + 1) + 200, 0, true)));
    }

    private static void idleDevices(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        int[] runs = new int[1];
        StorageTask idle = (transfer, gameTime) -> {
            runs[0]++;
            return StorageTask.IDLE;
        };
        List<TestStorage.TestDevice> devices = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(2 + column, 1, 1 + row));
                device.schedule(idle);
                devices.add(device);
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] total = new long[1];
        int[] measured = new int[1];
        int[] runsAtStart = new int[1];
        int[] rebuildsAtStart = new int[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 1, "devices in the network");
        }).thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    total[0] = 0;
                    measured[0] = 0;
                    runsAtStart[0] = runs[0];
                    rebuildsAtStart[0] = NetworkGameTests.state(helper, controller).rebuilds();
                }, () -> {
                    long tasks = LevelTasks.lastTickNanos(helper.getLevel());
                    long network = graph.lastTickNanos();
                    if (tasks >= 0 && network >= 0) {
                        total[0] += tasks + network;
                        measured[0]++;
                    }
                }, () -> {
                    helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                    long mean = total[0] / measured[0];
                    int ran = runs[0] - runsAtStart[0];
                    LOGGER.info("{}: {} idle devices, {} task runs in {} ticks; mean tick {} ms (budget {} ms).", IDLE,
                            devices.size(), ran, MEASURED_TICKS, millis(mean), millis(BUDGET_NANOS));
                    // At the standalone maximum backoff of 100 ticks each task runs about twice in 200 ticks.
                    helper.assertTrue(ran <= devices.size() * 5, ran + " runs: idle tasks are not backing off");
                    helper.assertValueEqual(NetworkGameTests.state(helper, controller).rebuilds(), rebuildsAtStart[0],
                            "route rebuilds while idle");
                    return mean;
                }), mean -> "500 idle storage devices take " + millis(mean) + " ms per tick, over the "
                        + millis(BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
