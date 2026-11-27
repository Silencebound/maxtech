package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.ioport.IoPortBlockEntity;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Sixteen busy IO ports (B09 item 11, PRD 8.1): each unloads a cell of four kinds into the network's storage at its
 * rate. None ticks; the network's task group pass, divided by the batches the ports moved in it, must stay within the
 * budget per batch, and every item stays accounted for. The network's own scheduler budget decides how many batches run
 * per tick. Runs in its own batch.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class IoPortPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "perf_storage_io_ports";
    private static final int PORTS = 16;
    private static final int STORES = 4;
    private static final long PER_KIND = 4_000;
    private static final List<StackKey> KINDS = List.of(ItemKey.of(Items.COBBLESTONE), ItemKey.of(Items.STONE),
            ItemKey.of(Items.DIRT), ItemKey.of(Items.SAND));
    private static final int TICKS = 10;
    /**
     * Batches the ports move before the measurement, so the JIT has compiled their path (cold, a batch took 0.5 ms).
     * Counted in batches, not ticks: a fast machine moves up to 64 a tick, and the cells (4,000 batches together) must
     * not run dry before the measurement and its retry (at most 64 x 21 batches) end.
     */
    private static final long WARMUP_BATCHES = 800;
    /**
     * Mean per moved batch: the port's run, its root transaction, the network insert and the cell extract (measured
     * about 17 us after the warm-up, on a busy machine).
     */
    private static final long BUDGET_NANOS = 50_000;
    private static final long POWER = 1_000_000;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(IoPortPerfGameTests::ports));
    }

    private IoPortPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), StorageTestEnvironment.roomy("", "", false)), NAME,
                MaxtechStorage.id("empty_42x4x27"), 400);
    }

    private static void ports(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        List<TestStorage.TestDevice> stores = new ArrayList<>(STORES);
        for (int i = 0; i < STORES; i++) {
            stores.add(NetworkGameTests.device(helper, NetworkGameTests.at(2 + i)));
        }
        List<IoPortBlockEntity> ports = new ArrayList<>(PORTS);
        for (int i = 0; i < PORTS; i++) {
            BlockPos pos = NetworkGameTests.at(2 + STORES + i);
            helper.setBlock(pos, IoPortContent.IO_PORT.get());
            ports.add(helper.getBlockEntity(pos, IoPortBlockEntity.class));
        }
        long total = PORTS * PER_KIND * KINDS.size();
        long[] movedAtStart = new long[1];
        long[] nanos = new long[1];
        long[] runsLastTick = new long[1];
        PerfBudget.TickMeasurement measurement = new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                nanos[0] = 0;
                movedAtStart[0] = moved(ports);
                runsLastTick[0] = NetworkGameTests.state(helper, controller).scheduler().runs();
                NetworkGameTests.power(helper, controller, POWER);
            }

            @Override
            public void tick() {
                // Topped up every tick, outside the timed pass, so no port waits for energy.
                NetworkGameTests.power(helper, controller, POWER);
                TaskGroup group = NetworkGameTests.state(helper, controller).scheduler();
                long runs = group.runs();
                if (runs != runsLastTick[0] && group.lastPassNanos() > 0) {
                    nanos[0] += group.lastPassNanos();
                }
                runsLastTick[0] = runs;
            }

            @Override
            public long finish() {
                long batches = (moved(ports) - movedAtStart[0]) / StorageServerConfig.BATCH_ITEMS.getAsInt();
                // The network's scheduler budget, not the ports, caps the batches per tick; each port must still work.
                helper.assertTrue(batches >= TICKS, "only " + batches + " batches moved: the ports idled");
                assertAccounted(helper, stores, ports, total);
                long mean = nanos[0] / Math.max(1, batches);
                LOGGER.info("{}: {} ports, {} batches in {} ticks, {} ms; mean {} ms per batch (budget {} ms).", NAME,
                        PORTS, batches, TICKS, millis(nanos[0]), millis(mean), millis(BUDGET_NANOS));
                return mean;
            }
        };
        helper.startSequence().thenWaitUntil(() -> {
            for (IoPortBlockEntity port : ports) {
                if (port.network() == null) {
                    throw helper.assertionException(Component.literal("an IO port is in no network"));
                }
            }
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, POWER);
            for (IoPortBlockEntity port : ports) {
                BlockState state = port.getBlockState();
                helper.assertTrue(state.getTicker(helper.getLevel(), IoPortContent.BLOCK_ENTITY.get()) == null,
                        "an IO port ticks");
                helper.assertTrue(port.insertCell(CellGameTests.cell(CellContent.ITEM_TYPE.id(), 2)),
                        "the cell did not go in");
                CellInventory cell = port.cell(0);
                if (cell == null) {
                    throw helper.assertionException(Component.literal("no cell mounted in an IO port"));
                }
                for (StackKey kind : KINDS) {
                    helper.assertValueEqual(CellGameTests.insert(cell, kind, PER_KIND), PER_KIND, "filled " + kind);
                }
            }
        }).thenWaitUntil(() -> {
            NetworkGameTests.power(helper, controller, POWER);
            helper.assertTrue(moved(ports) >= WARMUP_BATCHES * StorageServerConfig.BATCH_ITEMS.getAsInt(),
                    "still warming up");
        }).thenWaitUntil(
                PerfBudget.measureTicks(helper, NAME, TICKS, BUDGET_NANOS, measurement, mean -> "16 busy IO ports take "
                        + millis(mean) + " ms per batch, over the " + millis(BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static long moved(List<IoPortBlockEntity> ports) {
        long moved = 0;
        for (IoPortBlockEntity port : ports) {
            moved += port.movedTotal();
        }
        return moved;
    }

    /** Every item is in a port's cell or in the network's stores. */
    private static void assertAccounted(GameTestHelper helper, List<TestStorage.TestDevice> stores,
            List<IoPortBlockEntity> ports, long total) {
        long sum = 0;
        for (StackKey kind : KINDS) {
            for (TestStorage.TestDevice store : stores) {
                sum += store.provider.amount(kind);
            }
            for (IoPortBlockEntity port : ports) {
                CellInventory cell = port.cell(0);
                if (cell != null) {
                    sum += cell.amount(kind);
                }
            }
        }
        helper.assertValueEqual(sum, total, "items in the stores and the ports' cells");
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
