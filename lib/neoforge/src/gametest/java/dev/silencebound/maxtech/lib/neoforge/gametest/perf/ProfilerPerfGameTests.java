package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.profile.LibProfiler;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
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
 * What {@code /maxtech_lib profile} costs while no session runs: a level scheduler running 5,000 tasks every tick, with
 * the profiler off, must stay within the budget measured for the same scene before the profiler's run hook existed. The
 * hook is one static read per task run, so this guards against it growing into more. Runs in its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ProfilerPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String OFF = "perf_profiler_off_task_runs";
    private static final int TASKS = 5_000;
    private static final int WARMUP_TICKS = 100;
    private static final int MEASURED_TICKS = 200;
    /**
     * Mean level scheduler tick for 5,000 runs of a trivial task with the profiler off; before the run hook the scene
     * measured 71 to 84 µs on an M3 Pro (median 72 µs over three runs; 69 to 82 µs with the hook, 2026-11-28).
     */
    private static final long BUDGET_NANOS = 90_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(OFF, () -> ProfilerPerfGameTests::offTaskRuns);
    }

    private ProfilerPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechLib.id(OFF);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), STRUCTURE,
                                WARMUP_TICKS + 2 * MEASURED_TICKS + 100, 0, true)));
    }

    private static void offTaskRuns(GameTestHelper helper) {
        helper.assertFalse(LibProfiler.isRunning(), "a profile is running");
        // Budgets far above the scene, so every task runs every tick.
        TaskGroup group = LevelTasks.of(helper.getLevel()).newGroup(() -> 1_000_000_000L, () -> Integer.MAX_VALUE);
        PerfEnvironment.onTeardown(group::close);
        long[] runs = new long[1];
        for (int i = 0; i < TASKS; i++) {
            group.add(new ScheduledTask(TaskPriority.BACKGROUND) {
                @Override
                protected int run(TaskGroup owner, long gameTime) {
                    runs[0]++;
                    return NEXT_TICK;
                }
            });
        }
        long[] total = new long[1];
        int[] measured = new int[1];
        long[] runsAtStart = new long[1];
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
                helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                helper.assertFalse(LibProfiler.isRunning(), "a profile started while measuring");
                long mean = total[0] / measured[0];
                long ran = runs[0] - runsAtStart[0];
                LOGGER.info("{}: {} tasks, {} runs in {} ticks; mean task tick {} ms (budget {} ms).", OFF, TASKS, ran,
                        measured[0], millis(mean), millis(BUDGET_NANOS));
                helper.assertTrue(ran >= (long) TASKS * (MEASURED_TICKS - 2),
                        ran + " runs: the tasks did not all run every tick");
                return mean;
            }
        };
        helper.startSequence().thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper, OFF, MEASURED_TICKS,
                BUDGET_NANOS, measurement, mean -> TASKS + " task runs take " + millis(mean)
                        + " ms per tick with the profiler off, over" + " the " + millis(BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
