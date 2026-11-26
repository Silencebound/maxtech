package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.port.BusStatus;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * 50 idle storage buses (B09 item 8; PRD 8.1): a 10 by 5 grid of ports in storage mode on a cable floor, each facing up
 * into a full double chest of 54 different stacks. Once their looks have backed off, the time per tick of the network's
 * task group plus the storage network's tick, as the median of {@link #WINDOWS} window means, must stay under budget,
 * and the buses must run about once per window. Runs in its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StorageBusPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String IDLE = "perf_storage_idle_buses";
    private static final int COLUMNS = 10;
    private static final int ROWS = 5;
    private static final int WARMUP_TICKS = 400;
    /** Measured windows, each {@code ports.storageBus.maxScanTicks} long; see {@link PortPerfGameTests}. */
    private static final int WINDOWS = 7;
    /** Upper bound of {@code ports.storageBus.maxScanTicks} in either preset, for the test's timeout. */
    private static final int MAX_WINDOW_TICKS = 200;
    /** Mean per tick for 50 idle buses on double chests; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(IDLE, () -> StorageTestEnvironment.clearing(StorageBusPerfGameTests::idleBuses));
    }

    private StorageBusPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, WARMUP_TICKS + 2 * (WINDOWS * MAX_WINDOW_TICKS + 1) + 200, 0, true)));
    }

    private static void idleBuses(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1000);
        List<Item> kinds = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (kinds.size() < 54 && item.getDefaultMaxStackSize() == 64 && !item.equals(Blocks.AIR.asItem())) {
                kinds.add(item);
            }
        }
        List<PortBlockEntity> buses = new ArrayList<>(COLUMNS * ROWS);
        for (int x = 2; x < 2 + 2 * COLUMNS; x++) {
            for (int row = 0; row < ROWS; row++) {
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 1 + row)),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
            }
        }
        for (int column = 0; column < COLUMNS; column++) {
            int x = 2 + 2 * column;
            for (int row = 0; row < ROWS; row++) {
                int z = 1 + row;
                BlockState left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                        .setValue(ChestBlock.TYPE, ChestType.LEFT);
                helper.setBlock(new BlockPos(x, 3, z), left);
                helper.setBlock(new BlockPos(x + 1, 3, z), left.setValue(ChestBlock.TYPE, ChestType.RIGHT));
                ChestBlockEntity first = helper.getBlockEntity(new BlockPos(x, 3, z), ChestBlockEntity.class);
                ChestBlockEntity second = helper.getBlockEntity(new BlockPos(x + 1, 3, z), ChestBlockEntity.class);
                for (int slot = 0; slot < 27; slot++) {
                    first.setItem(slot, new ItemStack(kinds.get(slot), 64));
                    second.setItem(slot, new ItemStack(kinds.get(27 + slot), 64));
                }
                BlockPos at = new BlockPos(x, 2, z);
                helper.setBlock(at, PortContent.PORT.get().defaultBlockState().setValue(PortBlock.FACING, Direction.UP)
                        .setValue(PortBlock.MODE, PortMode.STORAGE));
                buses.add(helper.getBlockEntity(at, PortBlockEntity.class));
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        int window = StorageServerConfig.STORAGE_BUS_MAX_SCAN_TICKS.getAsInt();
        long[] windowNanos = new long[WINDOWS];
        int[] windowTicks = new int[WINDOWS];
        int[] tick = new int[1];
        long[] runsAtStart = new long[1];
        long[] runsLastTick = new long[1];
        int[] rebuildsAtStart = new int[1];
        PerfBudget.TickMeasurement measurement = new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                Arrays.fill(windowNanos, 0);
                Arrays.fill(windowTicks, 0);
                tick[0] = 0;
                NetworkState network = NetworkGameTests.state(helper, controller);
                runsAtStart[0] = NetworkGameTests.taskRuns(network);
                runsLastTick[0] = runsAtStart[0];
                rebuildsAtStart[0] = network.rebuilds();
            }

            @Override
            public void tick() {
                TaskGroup group = NetworkGameTests.state(helper, controller).scheduler();
                long runs = group.runs();
                long tasks = runs != runsLastTick[0] ? group.lastPassNanos() : 0;
                runsLastTick[0] = runs;
                long network = graph.lastTickNanos();
                int w = Math.min(WINDOWS - 1, tick[0]++ / window);
                if (tasks >= 0 && network >= 0) {
                    windowNanos[w] += tasks + network;
                    windowTicks[w]++;
                }
            }

            @Override
            public long finish() {
                long[] means = new long[WINDOWS];
                for (int w = 0; w < WINDOWS; w++) {
                    helper.assertTrue(windowTicks[w] > window / 2,
                            "only " + windowTicks[w] + " ticks timed in window " + w);
                    means[w] = windowNanos[w] / windowTicks[w];
                }
                long[] sorted = means.clone();
                Arrays.sort(sorted);
                long median = sorted[WINDOWS / 2];
                NetworkState network = NetworkGameTests.state(helper, controller);
                long ran = NetworkGameTests.taskRuns(network) - runsAtStart[0];
                LOGGER.info(
                        "{}: {} idle buses, {} task runs in {} ticks; mean tick per {}-tick window {} ms, median {} ms"
                                + " (budget {} ms).",
                        IDLE, buses.size(), ran, WINDOWS * window, window, millisList(means), millis(median),
                        millis(IDLE_BUDGET_NANOS));
                // At the longest wait each bus looks about once per window.
                helper.assertTrue(ran <= buses.size() * 3L * WINDOWS, ran + " runs: idle buses are not backing off");
                helper.assertValueEqual(network.rebuilds(), rebuildsAtStart[0], "route rebuilds while idle");
                return median;
            }
        };
        long expected = 64L * COLUMNS * ROWS;
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 1, "devices in the network");
            for (PortBlockEntity bus : buses) {
                helper.assertValueEqual(bus.worker().busStatus(), BusStatus.MOUNTED, "a bus is not mounted");
            }
            helper.assertValueEqual(network.contents().amount(ItemKey.of(kinds.get(0))), expected,
                    "the first kind over all chests");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(kinds.get(53))), expected,
                    "the last kind over all double chests");
        }).thenIdle(WARMUP_TICKS)
                .thenWaitUntil(
                        PerfBudget
                                .measureTicks(helper, IDLE, WINDOWS * window, IDLE_BUDGET_NANOS, measurement,
                                        median -> "50 idle storage buses take " + millis(median)
                                                + " ms per tick, over the " + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millisList(long[] nanos) {
        StringBuilder out = new StringBuilder();
        for (long n : nanos) {
            out.append(out.isEmpty() ? "" : " ").append(millis(n));
        }
        return out.toString();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
