package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestPorts;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * The idle cost of transfer ports (PRD 8.1): 200 ports, each pushing a full output slot into a full chest. Once they
 * backed off to the scheduler's maximum, the mean time the level's task scheduler takes per tick must stay under budget
 * and the ports must run only as often as their backoff allows. Runs in its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_42x4x27");
    private static final String IDLE = "perf_transfer_idle";
    private static final int PORTS = 200;
    private static final int MEASURED_TICKS = 200;
    /** Mean scheduler time per tick for 200 blocked ports at full backoff; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(IDLE, () -> TransferPerfGameTests::idle);
    }

    private TransferPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechLib.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE,
                                warmupTicks() + 2 * MEASURED_TICKS + 200, 0, true)));
    }

    /** Long enough for every port to reach the maximum backoff: the doubling delays sum to about twice it. */
    private static int warmupTicks() {
        return 2 * 1200 + 100;
    }

    private static void idle(GameTestHelper helper) {
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        PerfEnvironment.onTeardown(group::close);
        PortSettings push = PortSettings.EMPTY.withMode(ResourceKinds.ITEM, Direction.EAST, PortFaceMode.PUSH);
        TestPorts.PortEntity[] ports = new TestPorts.PortEntity[PORTS];
        for (int i = 0; i < PORTS; i++) {
            BlockPos pos = new BlockPos((i % 14) * 3, 0, i / 14);
            helper.setBlock(pos.east(), Blocks.CHEST);
            ChestBlockEntity chest = helper.getBlockEntity(pos.east(), ChestBlockEntity.class);
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            ports[i] = TestPorts.place(helper.getLevel(), helper.absolutePos(pos), group, Block.UPDATE_ALL);
            ports[i].output.set(0, new ItemStack(Items.STONE, 64));
            ports[i].port.setSettings(push);
        }
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        long[] total = new long[1];
        int[] measured = new int[1];
        long[] runsAtStart = new long[1];
        PerfBudget.TickMeasurement measurement = new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                total[0] = 0;
                measured[0] = 0;
                runsAtStart[0] = group.runs();
            }

            @Override
            public void tick() {
                long nanos = LevelTasks.lastTickNanos(helper.getLevel());
                if (nanos >= 0) {
                    total[0] += nanos;
                    measured[0]++;
                }
            }

            @Override
            public long finish() {
                helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                long mean = total[0] / measured[0];
                long ran = group.runs() - runsAtStart[0];
                LOGGER.info("{}: {} blocked ports, {} runs in {} ticks; mean task tick {} ms (budget {} ms).", IDLE,
                        PORTS, ran, MEASURED_TICKS, millis(mean), millis(IDLE_BUDGET_NANOS));
                long allowed = (long) PORTS * (MEASURED_TICKS / (maxBackoff - maxBackoff / 8) + 1);
                helper.assertTrue(ran <= allowed, ran + " runs, at most " + allowed + ": ports are not backing off");
                return mean;
            }
        };
        helper.startSequence().thenIdle(2 * maxBackoff + 100).thenExecute(() -> {
            for (TestPorts.PortEntity port : ports) {
                helper.assertValueEqual(port.port.status(ResourceKinds.ITEM), PortStatus.BLOCKED, "status");
            }
        }).thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, IDLE_BUDGET_NANOS, measurement,
                mean -> PORTS + " blocked ports take " + millis(mean) + " ms per tick, over the "
                        + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenExecute(() -> {
                    for (TestPorts.PortEntity port : ports) {
                        helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 64, "stone in a port");
                    }
                }).thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
