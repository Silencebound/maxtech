package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * 200 idle interfaces (PRD 7.2, Performance; PRD 8.1): a 20 by 10 grid of interface blocks on a cable floor, each
 * holding its 4 cobblestone and with one slot waiting for dirt the network never holds. They sleep without a timer and
 * listen only to the key they wait for, so while the network's cobblestone changes every tick (a player insert) the
 * interfaces do not run, the mean time per tick of the level's task scheduler plus the storage network's tick stays
 * under budget, and so does the insert. Own batch, timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class InterfacePerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String IDLE = "perf_storage_idle_interfaces";
    private static final int COLUMNS = 20;
    private static final int ROWS = 10;
    private static final int WARMUP_TICKS = 100;
    private static final int MEASURED_TICKS = 200;
    /** Mean scheduler and network time per tick for 200 sleeping interfaces; ports get 0.03 ms for 500 idle ones. */
    private static final long IDLE_BUDGET_NANOS = 20_000;
    /** One player insert of a key all 200 interfaces stock; none listens to it while its slot is full. */
    private static final long INSERT_BUDGET_NANOS = 100_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(IDLE, () -> StorageTestEnvironment.clearing(InterfacePerfGameTests::idleInterfaces));
    }

    private InterfacePerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                // Two measurements (idle tick, insert), each possibly retried.
                                STRUCTURE, WARMUP_TICKS + 4 * (MEASURED_TICKS + 1) + 300, 0, true)));
    }

    private static void idleInterfaces(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        List<InterfaceBlockEntity> interfaces = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                BlockPos floor = new BlockPos(2 + column, 1, 1 + row);
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(floor),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
                helper.setBlock(floor.above(), InterfaceContent.INTERFACE.get());
                InterfaceBlockEntity iface = helper.getBlockEntity(floor.above(), InterfaceBlockEntity.class);
                iface.worker().config().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
                iface.worker().config().setItem(1, new ItemStack(Items.DIRT, 4));
                // Stocked up front: 800 refill operations would queue on the tier-1 floor for half a minute.
                try (Transaction transaction = Transaction.openRoot()) {
                    iface.worker().itemStock().insert(0, ItemResource.of(Items.COBBLESTONE), 4, transaction);
                    transaction.commit();
                }
                interfaces.add(iface);
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 2, "devices in the network");
        }).thenExecute(() -> drive.provider.setDirect(ItemKey.of(Items.COBBLESTONE), 50_000)).thenIdle(WARMUP_TICKS)
                .thenExecute(() -> {
                    for (InterfaceBlockEntity iface : interfaces) {
                        helper.assertValueEqual(iface.worker().itemStock().getAmountAsLong(0), 4L,
                                "an interface's stock");
                    }
                })
                .thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, IDLE_BUDGET_NANOS,
                        measurement(helper, controller, interfaces, graph, false),
                        mean -> "200 idle interfaces take " + millis(mean) + " ms per tick, over the "
                                + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenWaitUntil(
                        PerfBudget
                                .measureTicks(helper, IDLE + "_insert", MEASURED_TICKS, INSERT_BUDGET_NANOS,
                                        measurement(helper, controller, interfaces, graph, true),
                                        insert -> "an insert of a key 200 idle interfaces stock takes " + millis(insert)
                                                + " ms, over the " + millis(INSERT_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /**
     * {@link #MEASURED_TICKS} idle ticks while a key every interface stocks but none waits for is stored once per tick;
     * returns the mean insert time ({@code insert}) or the mean tick.
     */
    private static PerfBudget.TickMeasurement measurement(GameTestHelper helper, BlockPos controller,
            List<InterfaceBlockEntity> interfaces, NetworkGraph graph, boolean insert) {
        long[] total = new long[1];
        long[] inserts = new long[1];
        int[] measured = new int[1];
        long[] runsAtStart = new long[1];
        return PerfBudget.TickMeasurement.of(() -> {
            total[0] = 0;
            inserts[0] = 0;
            measured[0] = 0;
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.listenedKeyCount(), 1, "keys listened to (dirt only)");
            runsAtStart[0] = NetworkGameTests.taskRuns(network);
        }, () -> {
            // A busy key every interface stocks but none waits for; timed apart from the ticks.
            NetworkState state = NetworkGameTests.state(helper, controller);
            long start = System.nanoTime();
            long stored = NetworkGameTests.insert(state, ItemKey.of(Items.COBBLESTONE), 1);
            inserts[0] += System.nanoTime() - start;
            helper.assertValueEqual(stored, 1L, "cobblestone stored");
            long tasks = LevelTasks.lastTickNanos(helper.getLevel());
            long network = graph.lastTickNanos();
            if (tasks >= 0 && network >= 0) {
                total[0] += tasks + network;
                measured[0]++;
            }
        }, () -> {
            helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
            long mean = total[0] / measured[0];
            long insertMean = inserts[0] / MEASURED_TICKS;
            long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, controller)) - runsAtStart[0];
            LOGGER.info(
                    "{}: {} idle interfaces, {} task runs in {} ticks; mean tick {} ms (budget {} ms); one insert"
                            + " of their stocked key {} ms (budget {} ms).",
                    IDLE, interfaces.size(), ran, MEASURED_TICKS, millis(mean), millis(IDLE_BUDGET_NANOS),
                    millis(insertMean), millis(INSERT_BUDGET_NANOS));
            helper.assertTrue(ran <= 10, ran + " runs: idle interfaces are not sleeping");
            return insert ? insertMean : mean;
        });
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
