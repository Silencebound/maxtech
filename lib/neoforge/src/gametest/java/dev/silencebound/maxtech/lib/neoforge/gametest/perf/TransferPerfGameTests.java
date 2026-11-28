package dev.silencebound.maxtech.lib.neoforge.gametest.perf;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.api.resource.ResourceKinds;
import dev.silencebound.maxtech.lib.api.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.api.transfer.PortFaceMode;
import dev.silencebound.maxtech.lib.api.transfer.PortSettings;
import dev.silencebound.maxtech.lib.api.transfer.PortStatus;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.neoforge.gametest.LibGameTests;
import dev.silencebound.maxtech.lib.neoforge.gametest.TestPorts;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The cost of transfer ports that cannot move (PRD 8.1), 200 ports each: pushing a full output slot into a full chest
 * once they backed off to the scheduler's maximum; beside a chunk below full status, half of them with something to
 * push; and blocked while their buffers report a change every tick. The mean time the level's task scheduler takes per
 * tick must stay under budget, and the ports must run only as often as backoff or the minimum interval allow. Each runs
 * in its own batch.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class TransferPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_42x4x27");
    private static final String IDLE = "perf_transfer_idle";
    private static final String UNLOADED = "perf_transfer_unloaded_neighbour";
    private static final String STORM = "perf_transfer_wake_storm";
    private static final int PORTS = 200;
    private static final int MEASURED_TICKS = 200;
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** Mean scheduler time per tick for 200 blocked ports at full backoff; PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 20_000;
    /**
     * Mean scheduler time per tick for 200 ports beside a chunk below full status, 100 of them with stone to push at
     * full backoff and 100 with nothing to push, which must not run at all.
     */
    private static final long UNLOADED_BUDGET_NANOS = 20_000;
    /**
     * Mean scheduler time per tick for 200 ports blocked by full chests whose buffers report a change every tick, so
     * each runs once per {@code transfer.minIntervalTicks}.
     */
    private static final long STORM_BUDGET_NANOS = 60_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        PerfEnvironment.init();
        FUNCTIONS.register(IDLE, () -> TransferPerfGameTests::idle);
        FUNCTIONS.register(UNLOADED, () -> TransferPerfGameTests::unloadedNeighbour);
        FUNCTIONS.register(STORM, () -> TransferPerfGameTests::wakeStorm);
    }

    private TransferPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        int measuring = 2 * (MEASURED_TICKS + 1) + 200;
        register(event, IDLE, STRUCTURE, warmupTicks() + measuring);
        register(event, UNLOADED, LibGameTests.EMPTY_STRUCTURE, warmupTicks() + measuring + 100);
        register(event, STORM, STRUCTURE, 100 + measuring);
    }

    private static void register(RegisterGameTestsEvent event, String name, Identifier structure, int maxTicks) {
        Identifier id = MaxtechLib.id(name);
        event.registerTest(id, new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                new TestData<>(event.registerEnvironment(id, new PerfEnvironment()), structure, maxTicks, 0, true)));
    }

    /**
     * Long enough for every port to reach the maximum backoff: the doubling delays sum to about twice it. Tests are
     * registered before a world's config may be loaded, so this falls back to the highest value the config allows.
     */
    private static int warmupTicks() {
        return 2 * ServerConfig.loadedOr(ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS, 1200) + 100;
    }

    /** Places 200 ports in the structure, each pushing a full output slot east into a full chest. */
    private static TestPorts.PortEntity[] blockedPorts(GameTestHelper helper, TaskGroup group) {
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
        return ports;
    }

    private static void idle(GameTestHelper helper) {
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        PerfEnvironment.onTeardown(group::close);
        TestPorts.PortEntity[] ports = blockedPorts(helper, group);
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        Timing timing = new Timing(helper, IDLE, group, null, IDLE_BUDGET_NANOS, () -> {
        });
        helper.startSequence().thenIdle(2 * maxBackoff + 100).thenExecute(() -> {
            for (TestPorts.PortEntity port : ports) {
                helper.assertValueEqual(port.port.status(ResourceKinds.ITEM), PortStatus.BLOCKED, "status");
            }
        }).thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, IDLE_BUDGET_NANOS, timing.with(ran -> {
            long allowed = (long) PORTS * (MEASURED_TICKS / (maxBackoff - maxBackoff / 8) + 1);
            helper.assertTrue(ran <= allowed, ran + " runs, at most " + allowed + ": ports are not backing off");
        }), mean -> PORTS + " blocked ports take " + millis(mean) + " ms per tick, over the "
                + millis(IDLE_BUDGET_NANOS) + " ms budget")).thenExecute(() -> {
                    for (TestPorts.PortEntity port : ports) {
                        helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 64, "stone in a port");
                    }
                }).thenSucceed();
    }

    /**
     * Ports in a border chunk pushing east into the next chunk, which is below full status: those with stone poll with
     * backoff, those with nothing to push wait for an event and never run. Far from the test grid, with a forced anchor
     * two chunks west.
     */
    private static void unloadedNeighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int anchorX = 6600;
        int chunkZ = 6600;
        int portChunkX = anchorX + 2;
        int unloadedChunkX = anchorX + 3;
        int y = helper.absolutePos(BlockPos.ZERO).getY();
        TaskGroup working = LevelTasks.newGroup(level);
        TaskGroup empty = LevelTasks.newGroup(level);
        BlockPos[] positions = new BlockPos[PORTS];
        for (int i = 0; i < PORTS; i++) {
            positions[i] = new BlockPos(portChunkX * 16 + 15, y + i / 16, chunkZ * 16 + i % 16);
        }
        PerfEnvironment.onTeardown(() -> {
            working.close();
            empty.close();
            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
            }
            level.setChunkForced(anchorX, chunkZ, false);
        });
        TestPorts.PortEntity[] ports = new TestPorts.PortEntity[PORTS];
        int maxBackoff = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        PortSettings push = PortSettings.EMPTY.withMode(ResourceKinds.ITEM, Direction.EAST, PortFaceMode.PUSH);
        Timing timing = new Timing(helper, UNLOADED, working, empty, UNLOADED_BUDGET_NANOS, () -> {
        });
        helper.startSequence().thenExecute(() -> {
            level.setChunkForced(anchorX, chunkZ, true);
            level.getChunk(portChunkX, chunkZ);
        }).thenWaitUntil(() -> {
            helper.assertTrue(level.getChunkSource().getChunkNow(portChunkX, chunkZ) != null, "port chunk not full");
            helper.assertTrue(level.getChunkSource().getChunkNow(unloadedChunkX, chunkZ) == null,
                    "the neighbour chunk is at full status");
        }).thenExecute(() -> {
            for (int i = 0; i < PORTS; i++) {
                boolean work = i % 2 == 0;
                ports[i] = TestPorts.place(level, positions[i], work ? working : empty, QUIET);
                if (work) {
                    ports[i].output.set(0, new ItemStack(Items.STONE, 64));
                }
                ports[i].port.setSettings(push);
            }
        }).thenIdle(2 * maxBackoff + 100).thenWaitUntil(
                PerfBudget.measureTicks(helper, UNLOADED, MEASURED_TICKS, UNLOADED_BUDGET_NANOS, timing.with(ran -> {
                    long allowed = (long) (PORTS / 2) * (MEASURED_TICKS / (maxBackoff - maxBackoff / 8) + 1);
                    helper.assertTrue(ran <= allowed, ran + " runs of ports with stone, at most " + allowed);
                    helper.assertValueEqual(timing.otherRuns(), 0L, "runs of ports with nothing to push");
                }), mean -> PORTS + " ports beside an unloaded chunk take " + millis(mean) + " ms per tick, over the "
                        + millis(UNLOADED_BUDGET_NANOS) + " ms budget"))
                .thenExecute(() -> {
                    helper.assertTrue(level.getChunkSource().getChunkNow(unloadedChunkX, chunkZ) == null,
                            "a port loaded the neighbour chunk");
                    for (int i = 0; i < PORTS; i += 2) {
                        helper.assertValueEqual(TestPorts.count(ports[i].output, Items.STONE), 64, "stone in a port");
                    }
                }).thenSucceed();
    }

    /**
     * Blocked ports whose buffers report a change every tick, as a busy machine's would: each still runs only once per
     * minimum interval, not every tick.
     */
    private static void wakeStorm(GameTestHelper helper) {
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        PerfEnvironment.onTeardown(group::close);
        TestPorts.PortEntity[] ports = blockedPorts(helper, group);
        int interval = Math.max(1, ServerConfig.TRANSFER_MIN_INTERVAL_TICKS.getAsInt());
        Timing timing = new Timing(helper, STORM, group, null, STORM_BUDGET_NANOS, () -> {
            for (TestPorts.PortEntity port : ports) {
                port.port.bufferChanged();
            }
        });
        helper.startSequence().thenIdle(50).thenWaitUntil(
                PerfBudget.measureTicks(helper, STORM, MEASURED_TICKS, STORM_BUDGET_NANOS, timing.with(ran -> {
                    long allowed = (long) PORTS * (MEASURED_TICKS / interval + 1);
                    helper.assertTrue(ran <= allowed, ran + " runs, at most " + allowed
                            + ": events run ports more often than the minimum interval");
                    long expected = (long) PORTS * (MEASURED_TICKS / interval) / 2;
                    helper.assertTrue(ran >= expected,
                            ran + " runs, at least " + expected + ": the events did not wake the ports");
                }), mean -> PORTS + " blocked ports woken every tick take " + millis(mean) + " ms per tick, over the "
                        + millis(STORM_BUDGET_NANOS) + " ms budget"))
                .thenExecute(() -> {
                    for (TestPorts.PortEntity port : ports) {
                        helper.assertValueEqual(TestPorts.count(port.output, Items.STONE), 64, "stone in a port");
                    }
                }).thenSucceed();
    }

    /**
     * Sums the level's task scheduler time over the measured ticks, counts the runs of the ports' group (and of a
     * second group, if any), logs runs per port, and returns the mean tick.
     */
    private static final class Timing {
        private final GameTestHelper helper;
        private final String test;
        private final TaskGroup group;
        private final TaskGroup other;
        private final long budget;
        private final Runnable eachTick;
        private long total;
        private int measured;
        private long runsAtStart;
        private long otherAtStart;

        Timing(GameTestHelper helper, String test, TaskGroup group, @Nullable TaskGroup other, long budget,
                Runnable eachTick) {
            this.helper = helper;
            this.test = test;
            this.group = group;
            this.other = other == null ? group : other;
            this.budget = budget;
            this.eachTick = eachTick;
        }

        long otherRuns() {
            return other.runs() - otherAtStart;
        }

        PerfBudget.TickMeasurement with(LongConsumer check) {
            return new PerfBudget.TickMeasurement() {
                @Override
                public void start() {
                    total = 0;
                    measured = 0;
                    runsAtStart = group.runs();
                    otherAtStart = other.runs();
                }

                @Override
                public void tick() {
                    eachTick.run();
                    long nanos = LevelTasks.lastTickNanos(helper.getLevel());
                    if (nanos >= 0) {
                        total += nanos;
                        measured++;
                    }
                }

                @Override
                public long finish() {
                    helper.assertTrue(measured > MEASURED_TICKS / 2, "only " + measured + " ticks timed");
                    long mean = total / measured;
                    long ran = group.runs() - runsAtStart;
                    LOGGER.info("{}: {} ports, {} runs in {} ticks ({} per port); mean task tick {} ms (budget {} ms).",
                            test, PORTS, ran, MEASURED_TICKS, String.format(Locale.ROOT, "%.2f", ran / (double) PORTS),
                            millis(mean), millis(budget));
                    check.accept(ran);
                    return mean;
                }
            };
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
