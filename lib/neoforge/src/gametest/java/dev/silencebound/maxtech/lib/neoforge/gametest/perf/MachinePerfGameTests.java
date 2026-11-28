package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.inventory.FluidTank;
import dev.silencebound.maxtech.lib.api.inventory.ItemSlots;
import dev.silencebound.maxtech.lib.api.machine.MachineProcess;
import dev.silencebound.maxtech.lib.api.machine.MachineStatus;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskSchedulers;
import dev.silencebound.maxtech.lib.neoforge.gametest.MachineProcessGameTests;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
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
import org.slf4j.Logger;

/**
 * Idle machines cost nothing per tick (PRD 8.1): 500 machine processes, half empty and half holding an input no recipe
 * matches, sleep after their first step. Once settled, none of them runs in the measured ticks and the level's task
 * scheduler stays under budget. Runs in its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class MachinePerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String IDLE = "perf_machine_idle";
    private static final int MACHINES = 500;
    private static final int SETTLE_LIMIT_TICKS = 200;
    private static final int MEASURED_TICKS = 200;
    /** Mean level task tick with 500 sleeping machines; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(IDLE, () -> MachinePerfGameTests::idle);
    }

    private MachinePerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechLib.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE,
                                SETTLE_LIMIT_TICKS + 2 * MEASURED_TICKS + 100, 0, true)));
    }

    private static void idle(GameTestHelper helper) {
        TaskGroup group = TaskSchedulers.newGroup(helper.getLevel());
        MachineProcess[] processes = new MachineProcess[MACHINES];
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        for (int i = 0; i < MACHINES; i++) {
            ItemSlots slots = ItemSlots.builder(4).input(0, 2).output(2, 4).build(slot -> {
            });
            if (i % 2 == 1) {
                slots.set(0, new ItemStack(Items.DIRT));
            }
            processes[i] = MachineProcess.builder(MachineProcessGameTests.TYPE).items(slots, 0, 2)
                    .inputTank(FluidTank.create(1000, () -> {
                    })).outputTank(FluidTank.create(1000, () -> {
                    })).tasks(level -> group).build();
            processes[i].start(helper.getLevel(), pos);
        }
        PerfEnvironment.onTeardown(() -> {
            for (MachineProcess process : processes) {
                process.stop();
            }
            group.close();
        });
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
                LOGGER.info("{}: {} idle machines, {} runs in {} ticks; mean task tick {} ms (budget {} ms).", IDLE,
                        MACHINES, ran, MEASURED_TICKS, millis(mean), millis(IDLE_BUDGET_NANOS));
                helper.assertValueEqual(ran, 0L, "runs of idle machines");
                return mean;
            }
        };
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(group.queued(), 0, "queued machines");
            for (int i = 0; i < MACHINES; i++) {
                MachineStatus expected = i % 2 == 1 ? MachineStatus.NO_RECIPE : MachineStatus.IDLE;
                helper.assertValueEqual(processes[i].status(), expected, "machine " + i);
            }
        }).thenIdle(10)
                .thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, IDLE_BUDGET_NANOS, measurement,
                        mean -> "500 idle machines take " + millis(mean) + " ms per tick, over the "
                                + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
