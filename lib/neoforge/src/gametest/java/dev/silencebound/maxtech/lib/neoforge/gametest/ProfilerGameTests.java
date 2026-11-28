package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.brigadier.tree.CommandNode;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.schedule.TaskPriority;
import dev.silencebound.maxtech.lib.profile.LibProfiler;
import dev.silencebound.maxtech.lib.profile.ProfileCommand;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * {@code /maxtech_lib profile}'s profiler in a running server: the same tasks run on the same ticks with it on and off,
 * and its report lists a known task class with the runs it made while profiling. Runs in its own batch, since a session
 * times every scheduler on the server.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class ProfilerGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_1x1x1");
    private static final String SAME = "profiler_keeps_behaviour_and_lists_task";
    /** Ticks per phase; a multiple of the cadence task's period. */
    private static final int PHASE_TICKS = 12;
    private static final int CADENCE = 3;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(SAME, () -> ProfilerGameTests::keepsBehaviourAndListsTask);
    }

    private ProfilerGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechLib.id(SAME);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(event.registerEnvironment(id), STRUCTURE, 4 * PHASE_TICKS + 40, 0, true)));
    }

    /** Logs the game time of each run and asks to run again after {@code period} ticks (1: every tick). */
    static final class DemoTask extends ScheduledTask {
        final LongArrayList ranAt = new LongArrayList();
        private final int period;

        DemoTask(int period) {
            super(TaskPriority.BACKGROUND);
            this.period = period;
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            ranAt.add(gameTime);
            return period;
        }
    }

    private static void keepsBehaviourAndListsTask(GameTestHelper helper) {
        helper.assertFalse(LibProfiler.isRunning(), "a profile was running before the test");
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        DemoTask everyTick = new DemoTask(ScheduledTask.NEXT_TICK);
        DemoTask cadence = new DemoTask(CADENCE);
        group.add(everyTick);
        group.add(cadence);
        MinecraftServer server = helper.getLevel().getServer();
        CommandNode<CommandSourceStack> lib = server.getCommands().getDispatcher().getRoot()
                .getChild(MaxtechLib.MOD_ID);
        helper.assertTrue(lib != null && lib.getChild("profile") != null, "/maxtech_lib profile is not registered");
        long[] phase = new long[4];
        @SuppressWarnings("unchecked")
        Optional<LibProfiler.Report>[] report = new Optional[]{Optional.empty()};
        helper.startSequence().thenIdle(2).thenExecute(() -> phase[0] = helper.getLevel().getGameTime())
                .thenIdle(PHASE_TICKS).thenExecute(() -> {
                    phase[1] = helper.getLevel().getGameTime();
                    helper.assertTrue(LibProfiler.start(server, 1000, done -> {
                    }), "the profiler did not start");
                    helper.assertFalse(LibProfiler.start(server, 1000, done -> {
                    }), "a second profile started");
                    phase[2] = helper.getLevel().getGameTime();
                }).thenIdle(PHASE_TICKS).thenExecute(() -> {
                    phase[3] = helper.getLevel().getGameTime();
                    report[0] = LibProfiler.stop(server);
                    group.close();
                    helper.assertFalse(LibProfiler.isRunning(), "the profile still runs after stop");
                    helper.assertTrue(report[0].isPresent(), "stop gave no report");
                    LibProfiler.Report done = report[0].orElseThrow();

                    // Same runs on the same ticks, relative to each phase's start.
                    LongArrayList off = relative(everyTick.ranAt, phase[0], phase[1]);
                    LongArrayList on = relative(everyTick.ranAt, phase[2], phase[3]);
                    helper.assertValueEqual(on, off, "every-tick runs with the profiler on and off");
                    helper.assertValueEqual(phase[3] - phase[2], phase[1] - phase[0], "phase lengths");
                    helper.assertValueEqual((long) off.size(), phase[1] - phase[0], "every-tick runs per phase");
                    for (long[] window : new long[][]{{phase[0], phase[1]}, {phase[2], phase[3]}}) {
                        LongArrayList gaps = gaps(cadence.ranAt, window[0], window[1]);
                        helper.assertTrue(
                                gaps.size() >= PHASE_TICKS / CADENCE - 1
                                        && gaps.longStream().allMatch(gap -> gap == CADENCE),
                                "cadence runs " + cadence.ranAt + " are not every " + CADENCE + " ticks");
                    }

                    String name = "ProfilerGameTests.DemoTask";
                    Optional<LibProfiler.KindRow> row = done.kind(name);
                    helper.assertTrue(row.isPresent(), "the report does not list " + name + ": " + done.kinds());
                    long runsOn = (long) on.size() + relative(cadence.ranAt, phase[2], phase[3]).size();
                    row.ifPresent(kind -> {
                        helper.assertValueEqual(kind.runs(), runsOn, "profiled runs of " + name);
                        helper.assertValueEqual(kind.owner(), MaxtechLib.MOD_ID, "owner of " + name);
                        helper.assertValueEqual(kind.kind(), LibProfiler.Kind.TASK, "kind of " + name);
                    });
                    helper.assertTrue(done.runs() >= runsOn, "total runs " + done.runs() + " below " + runsOn);
                    helper.assertTrue(done.schedulerNanos() > 0, "no scheduler time was sampled");
                    List<Component> lines = ProfileCommand.lines(done);
                    helper.assertTrue(lines.stream().anyMatch(line -> line.getString().contains(name)),
                            "the chat report does not name " + name);
                    helper.assertFalse(lines.stream().anyMatch(line -> line.getString().contains("commands.")),
                            "the chat report has a missing lang key");
                }).thenSucceed();
    }

    /** Ticks between consecutive runs in {@code [from, to)}; a cadence task gives its period each time. */
    private static LongArrayList gaps(LongArrayList times, long from, long to) {
        LongArrayList runs = relative(times, from, to);
        LongArrayList out = new LongArrayList();
        for (int i = 1; i < runs.size(); i++) {
            out.add(runs.getLong(i) - runs.getLong(i - 1));
        }
        return out;
    }

    /** Runs in game times {@code [from, to)}, as offsets from {@code from}. */
    private static LongArrayList relative(LongArrayList times, long from, long to) {
        LongArrayList out = new LongArrayList();
        for (int i = 0; i < times.size(); i++) {
            long time = times.getLong(i);
            if (time >= from && time < to) {
                out.add(time - from);
            }
        }
        return out;
    }
}
