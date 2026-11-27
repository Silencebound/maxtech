package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import java.util.Locale;
import java.util.function.Consumer;
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
 * The budget scheduler's idle cost (PRD 7.2, Performance): a level scheduler holding 500 tasks that never find work.
 * Once they reached the configured maximum backoff, the mean time the level's task scheduler takes per tick must stay
 * under budget, and the tasks must run only as often as their backoff allows. Runs in its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class SchedulerPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String IDLE = "perf_scheduler_idle_tasks";
    private static final int IDLE_TASKS = 500;
    private static final int IDLE_WARMUP_TICKS = 400;
    private static final int IDLE_MEASURED_TICKS = 200;
    /** Mean per tick for 500 sleeping tasks; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 20_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(IDLE, () -> SchedulerPerfGameTests::idleTasks);
    }

    private SchedulerPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechLib.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE,
                                IDLE_WARMUP_TICKS + 2 * IDLE_MEASURED_TICKS + 100, 0, true)));
    }

    private static void idleTasks(GameTestHelper helper) {
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        PerfEnvironment.onTeardown(group::close);
        int[] runs = new int[1];
        for (int i = 0; i < IDLE_TASKS; i++) {
            group.add(new ScheduledTask(TaskPriority.BACKGROUND) {
                @Override
                protected int run(TaskGroup owner, long gameTime) {
                    runs[0]++;
                    return IDLE;
                }
            });
        }
        long[] total = new long[1];
        int[] measured = new int[1];
        int[] runsAtStart = new int[1];
        PerfBudget.TickMeasurement measurement = new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                total[0] = 0;
                measured[0] = 0;
                runsAtStart[0] = runs[0];
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
                helper.assertTrue(measured[0] > IDLE_MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                long mean = total[0] / measured[0];
                int ran = runs[0] - runsAtStart[0];
                LOGGER.info("{}: {} idle tasks, {} runs in {} ticks; mean task tick {} ms (budget {} ms).", IDLE,
                        IDLE_TASKS, ran, IDLE_MEASURED_TICKS, millis(mean), millis(IDLE_BUDGET_NANOS));
                // At the standalone maximum backoff of 100 ticks each task runs about twice in 200 ticks.
                helper.assertTrue(ran <= IDLE_TASKS * 5, ran + " runs: idle tasks are not backing off");
                return mean;
            }
        };
        helper.startSequence().thenIdle(IDLE_WARMUP_TICKS)
                .thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, IDLE_MEASURED_TICKS, IDLE_BUDGET_NANOS,
                        measurement, mean -> "500 idle tasks take " + millis(mean) + " ms per tick, over the "
                                + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
