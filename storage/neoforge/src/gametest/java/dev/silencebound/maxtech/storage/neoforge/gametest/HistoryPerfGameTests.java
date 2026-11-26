package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 32 history chart screens (the default series limit of one network) recording every 20 ticks, the shortest interval,
 * with nobody watching (M7L2 history charts). The level's task scheduler plus the storage network's tick is measured
 * first with the same screens showing stored power (asleep), then recording; the difference is the recording cost and
 * must stay within its budget. Every screen must record at its interval and none may send anything. Runs in its own
 * batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class HistoryPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String NAME = "perf_history_series";
    private static final int COLUMNS = 8;
    private static final int ROWS = 4;
    private static final int INTERVAL = 20;
    private static final int WARMUP_TICKS = 200;
    private static final int MEASURED_TICKS = 200;
    /**
     * What 32 recorders (1.6 samples per tick) may add to the mean tick over the same screens sleeping: a fraction of
     * the idle-device budget, since a sample is one read and one array store.
     */
    private static final long BUDGET_NANOS = 8_000;
    /** The baseline is only measured to subtract it; this catches a broken set-up, not a slow machine. */
    private static final long BASELINE_BUDGET_NANOS = 1_000_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(NAME, () -> StorageTestEnvironment.clearing(HistoryPerfGameTests::recordingScreens));
    }

    private HistoryPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(NAME);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, 2 * WARMUP_TICKS + 4 * (MEASURED_TICKS + 1) + 200, 0, true)));
    }

    private static void recordingScreens(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        List<DisplayPanelBlockEntity> panels = new ArrayList<>(COLUMNS * ROWS);
        // Baseline: the same screens showing stored power, which sleep while nobody sees them.
        for (int x = 0; x < COLUMNS; x++) {
            for (int z = 0; z < ROWS; z++) {
                Direction facing = (x + z) % 2 == 0 ? Direction.UP : Direction.DOWN;
                DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, new BlockPos(2 + x, 1, 1 + z), facing);
                panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.POWER_STORED));
                panels.add(panel);
            }
        }
        String[] values = {"count:minecraft:cobblestone", "energy", "items", "bytes"};
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] total = new long[1];
        int[] measured = new int[1];
        int[] samplesAtStart = new int[1];
        long[] baseline = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 2, "devices in the network");
            for (DisplayPanelBlockEntity panel : panels) {
                helper.assertTrue(panel.worker().isStarted(), "a screen is not running");
            }
        }).thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper, NAME + "_baseline", MEASURED_TICKS,
                BASELINE_BUDGET_NANOS, PerfBudget.TickMeasurement.of(() -> {
                    total[0] = 0;
                    measured[0] = 0;
                }, () -> tick(helper, graph, total, measured), () -> {
                    helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                    baseline[0] = total[0] / measured[0];
                    return baseline[0];
                }), mean -> "the baseline network takes " + millis(mean) + " ms per tick")).thenExecute(() -> {
                    for (int i = 0; i < panels.size(); i++) {
                        panels.get(i).applyToWall(HistoryChartGameTests.chart(values[i % values.length]));
                    }
                }).thenWaitUntil(() -> {
                    for (DisplayPanelBlockEntity panel : panels) {
                        helper.assertTrue(panel.worker().chart().samples() > 0, "a screen has not recorded");
                    }
                }).thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper, NAME, MEASURED_TICKS,
                        BUDGET_NANOS, PerfBudget.TickMeasurement.of(() -> {
                            total[0] = 0;
                            measured[0] = 0;
                            samplesAtStart[0] = samples(panels);
                        }, () -> tick(helper, graph, total, measured), () -> {
                            helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                            int taken = samples(panels) - samplesAtStart[0];
                            int expected = panels.size() * MEASURED_TICKS / INTERVAL;
                            helper.assertTrue(taken >= expected * 8 / 10 && taken <= expected * 12 / 10 + panels.size(),
                                    taken + " samples in " + MEASURED_TICKS + " ticks, expected about " + expected);
                            int sends = 0;
                            for (DisplayPanelBlockEntity panel : panels) {
                                sends += panel.worker().sends() + panel.worker().chartSends();
                            }
                            helper.assertValueEqual(sends, 0, "payloads sent by unseen screens");
                            long mean = total[0] / measured[0];
                            long recording = Math.max(0, mean - baseline[0]);
                            LOGGER.info(
                                    "{}: {} recording screens, {} samples in {} ticks; mean tick {} ms, baseline {} ms,"
                                            + " recording {} ms (budget {} ms).",
                                    NAME, panels.size(), taken, MEASURED_TICKS, millis(mean), millis(baseline[0]),
                                    millis(recording), millis(BUDGET_NANOS));
                            return recording;
                        }), recording -> panels.size() + " recording screens add " + millis(recording)
                                + " ms per tick, over the " + millis(BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /** Adds the level scheduler's and the storage network's time of the last tick. */
    private static void tick(GameTestHelper helper, NetworkGraph graph, long[] total, int[] measured) {
        long tasks = LevelTasks.lastTickNanos(helper.getLevel());
        long network = graph.lastTickNanos();
        if (tasks >= 0 && network >= 0) {
            total[0] += tasks + network;
            measured[0]++;
        }
    }

    private static int samples(List<DisplayPanelBlockEntity> panels) {
        int out = 0;
        for (DisplayPanelBlockEntity panel : panels) {
            out += panel.worker().chart().samples();
        }
        return out;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
