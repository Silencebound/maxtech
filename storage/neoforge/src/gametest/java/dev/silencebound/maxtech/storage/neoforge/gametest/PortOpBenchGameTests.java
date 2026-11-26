package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.PortMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Times a port operation on a busy network (M7E item D): {@link #PAIRS} tier-3 import ports with two speed cards each
 * take cobblestone from barrels the bench refills every tick, and as many export ports put it into barrels the bench
 * empties every tick, so the network's contents stay level and every port works every tick. After {@link #WARM_TICKS}
 * it sums the network task group's pass time and the operations over {@link #TICKS} ticks and logs nanoseconds per
 * operation (overall, and the fastest and median of its windows, since other work on the machine only ever adds time),
 * operations per tick and the passes that ran out of budget. Not a test: it asserts only that operations ran, and is
 * registered only with {@code MAXTECH_BENCH=1} in the environment, so normal suites and CI never see it. Rerun with
 * {@code MAXTECH_BENCH=1 ./gradlew :storage:neoforge:runGameTestServer -Pgametest='maxtech_storage:bench_port_*'}.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PortOpBenchGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = "1".equals(System.getenv("MAXTECH_BENCH"));
    private static final String NAME = "bench_port_operations";
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final int PAIRS = 24;
    private static final int WARM_TICKS = 400;
    private static final int WINDOWS = 20;
    private static final int WINDOW_TICKS = 1000;
    private static final int TICKS = WINDOWS * WINDOW_TICKS;

    static {
        if (ENABLED) {
            TestStorage.init();
            StorageTestEnvironment.init();
            StorageTests.function(NAME, StorageTestEnvironment.clearing(PortOpBenchGameTests::bench));
        }
    }

    private PortOpBenchGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        if (!ENABLED) {
            return;
        }
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id(NAME),
                StorageTestEnvironment.roomy("", "", false));
        StorageTests.test(event, environment, NAME, STRUCTURE, WARM_TICKS + TICKS + 400);
    }

    private static void bench(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        TestStorage.TestDevice drive = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        drive.provider.setDirect(COBBLE, TestStorage.DEVICE_CAPACITY / 2);
        NetworkGameTests.cables(helper, 2, 1 + PAIRS, CableContent.STORAGE_CABLE_TIER_4.type().get());
        List<BarrelBlockEntity> sources = new ArrayList<>(PAIRS);
        List<BarrelBlockEntity> targets = new ArrayList<>(PAIRS);
        for (int i = 0; i < PAIRS; i++) {
            BlockPos cable = NetworkGameTests.at(2 + i);
            sources.add(port(helper, cable.above(), Direction.UP, PortMode.IMPORT));
            targets.add(port(helper, cable.south(), Direction.SOUTH, PortMode.EXPORT));
        }
        FeBuffer buffer = NetworkGameTests.controllerEntity(helper, controller).buffer();
        long[] nanos = new long[1];
        long[] windowNanos = new long[WINDOWS];
        long[] windowOps = new long[WINDOWS + 1];
        long[] lastRuns = new long[1];
        long[] startOps = new long[1];
        long[] startOver = new long[1];
        int[] tick = new int[1];
        helper.onEachTick(() -> {
            buffer.load(buffer.capacity());
            for (BarrelBlockEntity source : sources) {
                for (int slot = 0; slot < source.getContainerSize(); slot++) {
                    source.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                }
            }
            for (BarrelBlockEntity target : targets) {
                target.clearContent();
            }
            NetworkState network = NetworkGameTests.network(helper, controller);
            if (network == null) {
                return;
            }
            TaskGroup group = network.scheduler();
            int now = tick[0]++;
            if (now >= WARM_TICKS && (now - WARM_TICKS) % WINDOW_TICKS == 0 && now <= WARM_TICKS + TICKS) {
                windowOps[(now - WARM_TICKS) / WINDOW_TICKS] = network.operations();
            }
            if (now == WARM_TICKS) {
                startOps[0] = network.operations();
                startOver[0] = group.overBudgetPasses();
                lastRuns[0] = group.runs();
            } else if (now > WARM_TICKS && now <= WARM_TICKS + TICKS) {
                long runs = group.runs();
                if (runs != lastRuns[0]) {
                    long pass = Math.max(0, group.lastPassNanos());
                    nanos[0] += pass;
                    windowNanos[Math.min(WINDOWS - 1, (now - WARM_TICKS - 1) / WINDOW_TICKS)] += pass;
                }
                lastRuns[0] = runs;
            }
        });
        helper.startSequence().thenIdle(WARM_TICKS + TICKS + 2).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            long ops = network.operations() - startOps[0];
            helper.assertTrue(ops > 0, "no operations ran");
            long[] perOp = new long[WINDOWS];
            for (int w = 0; w < WINDOWS; w++) {
                perOp[w] = windowNanos[w] / Math.max(1, windowOps[w + 1] - windowOps[w]);
            }
            Arrays.sort(perOp);
            LOGGER.info(
                    "{}: {} ports, {} operations in {} ticks ({} a tick), {} ns per operation (fastest {}-tick window"
                            + " {}, median {}), {} ms a tick, {} passes over budget",
                    NAME, 2 * PAIRS, ops, TICKS, String.format(Locale.ROOT, "%.1f", ops / (double) TICKS),
                    String.format(Locale.ROOT, "%.0f", nanos[0] / (double) ops), WINDOW_TICKS, perOp[0],
                    perOp[WINDOWS / 2], String.format(Locale.ROOT, "%.4f", nanos[0] / 1_000_000.0 / TICKS),
                    network.scheduler().overBudgetPasses() - startOver[0]);
        }).thenSucceed();
    }

    /** A tier-3 port with two speed cards at {@code pos} facing {@code facing} into a barrel; returns the barrel. */
    private static BarrelBlockEntity port(GameTestHelper helper, BlockPos pos, Direction facing, PortMode mode) {
        helper.setBlock(pos, PortContent.PORT_TIER_3.get().defaultBlockState().setValue(PortBlock.FACING, facing)
                .setValue(PortBlock.MODE, mode));
        PortBlockEntity port = helper.getBlockEntity(pos, PortBlockEntity.class);
        port.worker().cards().setItem(0, new ItemStack(PortContent.SPEED_CARD.get()));
        port.worker().cards().setItem(1, new ItemStack(PortContent.SPEED_CARD.get()));
        if (mode == PortMode.EXPORT) {
            port.filter().setItem(0, new ItemStack(Items.COBBLESTONE));
        }
        BlockPos barrel = pos.relative(facing);
        helper.setBlock(barrel, Blocks.BARREL);
        return helper.getBlockEntity(barrel, BarrelBlockEntity.class);
    }
}
