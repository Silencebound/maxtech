package dev.silencebound.maxtech.lib.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import dev.silencebound.maxtech.lib.MaxtechLib;
import dev.silencebound.maxtech.lib.concurrent.BackgroundJob;
import dev.silencebound.maxtech.lib.concurrent.CowSnapshot;
import dev.silencebound.maxtech.lib.concurrent.WorkerPool;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.energy.FeLookups;
import dev.silencebound.maxtech.lib.energy.FeTransfer;
import dev.silencebound.maxtech.lib.platform.CachedBlockLookup;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.schedule.ScheduledTask;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.lib.schedule.TaskPriority;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * Lib's runtime pieces in a running server: FE buffers through NeoForge's energy capability, budgeted task groups in a
 * level's scheduler, and background jobs that commit on the server thread.
 */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID)
public final class RuntimeGameTests {
    private static final Identifier STRUCTURE = MaxtechLib.id("empty_7x2x3");
    private static final String[] NAMES = {"fe_buffer_capability", "scheduler_budget_in_level",
            "scheduler_wakes_on_event", "worker_pool_commits_on_server_thread"};

    private static final RegistryHelper.Registrar<Block> BLOCKS = Platform.get().registry().registrar(MaxtechLib.MOD_ID,
            Registries.BLOCK);
    static final RegistryEntry<Block, FeBlock> FE_BLOCK = BLOCKS.register("test_fe_buffer",
            id -> new FeBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id))));
    private static final RegistryHelper.Registrar<BlockEntityType<?>> BLOCK_ENTITIES = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.BLOCK_ENTITY_TYPE);
    private static final RegistryEntry<BlockEntityType<?>, BlockEntityType<FeEntity>> FE_BLOCK_ENTITY = BLOCK_ENTITIES
            .register("test_fe_buffer", () -> new BlockEntityType<>(FeEntity::new, Set.of(FE_BLOCK.get())));
    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechLib.MOD_ID, Registries.TEST_FUNCTION);

    static {
        FUNCTIONS.register(NAMES[0], () -> RuntimeGameTests::feBufferCapability);
        FUNCTIONS.register(NAMES[1], () -> RuntimeGameTests::schedulerBudgetInLevel);
        FUNCTIONS.register(NAMES[2], () -> RuntimeGameTests::schedulerWakesOnEvent);
        FUNCTIONS.register(NAMES[3], () -> RuntimeGameTests::workerPoolCommits);
    }

    private RuntimeGameTests() {
    }

    /** Up: input only. Down: output only. Other sides: both. */
    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FE_BLOCK_ENTITY.get(),
                (entity, side) -> side == Direction.UP
                        ? entity.buffer.inputOnly()
                        : side == Direction.DOWN ? entity.buffer.outputOnly() : entity.buffer);
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechLib.id("runtime"));
        for (String name : NAMES) {
            event.registerTest(MaxtechLib.id(name),
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, MaxtechLib.id(name)),
                            new TestData<>(environment, STRUCTURE, 100, 0, true)));
        }
    }

    /**
     * Two FE blocks found through {@link FeLookups#BLOCK}, by plain and cached lookup, each side giving the view its
     * capability registered; FE moves between them with {@link FeTransfer} only where the sides allow it.
     */
    private static void feBufferCapability(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), FE_BLOCK.get());
        helper.setBlock(new BlockPos(3, 1, 1), FE_BLOCK.get());
        FeEntity source = helper.getBlockEntity(new BlockPos(1, 1, 1), FeEntity.class);
        FeEntity target = helper.getBlockEntity(new BlockPos(3, 1, 1), FeEntity.class);
        source.buffer.load(5000);
        BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos targetPos = helper.absolutePos(new BlockPos(3, 1, 1));
        EnergyHandler sourceSide = FeLookups.BLOCK.find(level, sourcePos, Direction.EAST);
        CachedBlockLookup<EnergyHandler> targetTop = FeLookups.BLOCK.cache(level, targetPos, Direction.UP);
        EnergyHandler targetBottom = FeLookups.BLOCK.find(level, targetPos, Direction.DOWN);
        helper.assertTrue(sourceSide != null && targetTop.find() != null && targetBottom != null,
                "the FE buffers are not found through FeLookups");
        helper.assertTrue(FeLookups.BLOCK.find(level, helper.absolutePos(new BlockPos(2, 1, 1)), null) == null,
                "air has an FE handler");
        EnergyHandler top = targetTop.find();
        if (sourceSide == null || top == null || targetBottom == null) {
            return;
        }
        helper.assertValueEqual(FeTransfer.move(sourceSide, top, 1000), 1000L, "FE moved into the input side");
        helper.assertValueEqual(FeTransfer.move(sourceSide, targetBottom, 1000), 0L, "FE moved into the output side");
        helper.assertValueEqual(FeTransfer.move(top, sourceSide, 1000), 0L, "FE taken out of the input side");
        helper.assertValueEqual(FeTransfer.move(targetBottom, sourceSide, 300), 300L, "FE taken from the output side");
        helper.assertValueEqual(source.buffer.stored(), 4300L, "source FE");
        helper.assertValueEqual(target.buffer.stored(), 700L, "target FE");
        helper.assertTrue(source.changes > 0 && target.changes > 0, "committed moves fired no change");
        int before = target.changes;
        try (Transaction tx = Transaction.openRoot()) {
            helper.assertTrue(target.buffer.tryConsume(700, tx), "the target could not pay 700 FE");
        }
        helper.assertValueEqual(target.buffer.stored(), 700L, "FE after a rolled-back payment");
        helper.assertValueEqual(target.changes, before, "changes fired by a rollback");
        helper.succeed();
    }

    /**
     * A group with a budget of three operations in the level's scheduler runs ten busy tasks three per tick, all of
     * them within four ticks, and a group with the configured budgets runs as well. Both groups are closed at the end,
     * so nothing keeps running into later batches.
     */
    private static void schedulerBudgetInLevel(GameTestHelper helper) {
        TaskGroup tight = LevelTasks.of(helper.getLevel()).newGroup(() -> 1_000_000_000L, () -> 3);
        TaskGroup configured = LevelTasks.newGroup(helper.getLevel());
        IntArrayList perTick = new IntArrayList();
        int[] runs = new int[1];
        int[] configuredRuns = new int[1];
        for (int i = 0; i < 10; i++) {
            tight.add(new CountingTask(runs, ScheduledTask.WAIT));
        }
        configured.add(new CountingTask(configuredRuns, ScheduledTask.NEXT_TICK));
        int[] last = new int[1];
        helper.startSequence().thenExecuteFor(5, () -> {
            perTick.add(runs[0] - last[0]);
            last[0] = runs[0];
        }).thenExecute(() -> {
            tight.close();
            configured.close();
            helper.assertValueEqual(runs[0], 10, "tasks run");
            for (int i = 0; i < perTick.size(); i++) {
                helper.assertTrue(perTick.getInt(i) <= 3, "a tick ran " + perTick.getInt(i)
                        + " tasks over a budget of 3" + " (per tick: " + perTick + ")");
            }
            helper.assertTrue(tight.overBudgetPasses() > 0, "the tight group never ran out of budget");
            helper.assertTrue(configuredRuns[0] >= 4, "the configured group ran " + configuredRuns[0] + " times");
        }).thenSucceed();
    }

    /** A waiting task does not run again until woken; woken, it runs in the next scheduler pass. */
    private static void schedulerWakesOnEvent(GameTestHelper helper) {
        TaskGroup group = LevelTasks.newGroup(helper.getLevel());
        int[] runs = new int[1];
        CountingTask task = new CountingTask(runs, ScheduledTask.WAIT);
        group.add(task);
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(runs[0], 1, "runs before the wake");
            helper.assertTrue(task.wakeAt() < 0 && !task.isQueued(), "a waiting task has a timer or is queued");
            task.wake();
        }).thenIdle(1).thenExecute(() -> {
            group.close();
            helper.assertValueEqual(runs[0], 2, "runs after the wake");
        }).thenSucceed();
    }

    /**
     * A job sums a snapshot on a worker thread and commits on the server thread at the end of a server tick; a job
     * whose snapshot went stale is discarded there instead.
     */
    private static void workerPoolCommits(GameTestHelper helper) {
        Thread serverThread = Thread.currentThread();
        CowSnapshot<Int2LongOpenHashMap> totals = CowSnapshot.int2Long();
        totals.write().put(1, 30);
        totals.write().put(2, 12);
        List<String> events = new ArrayList<>();
        AtomicReference<@Nullable Thread> computedOn = new AtomicReference<>();
        CowSnapshot.Snapshot<Int2LongOpenHashMap> snapshot = totals.snapshot();
        BackgroundJob<Long> sum = new BackgroundJob<>() {
            @Override
            public Long compute() {
                computedOn.set(Thread.currentThread());
                long total = 0;
                for (long value : snapshot.value().values()) {
                    total += value;
                }
                return total;
            }

            @Override
            public boolean revalidate(Long result) {
                return totals.generation() == snapshot.generation();
            }

            @Override
            public void commit(Long result) {
                events.add((Objects.equals(Thread.currentThread(), serverThread) ? "server " : "other ") + result);
            }

            @Override
            public void discarded(@Nullable Long result, @Nullable Throwable error) {
                events.add("discarded " + result);
            }
        };
        helper.assertTrue(WorkerPool.server().submit(sum), "the server pool refused a job");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!events.isEmpty(), "no commit yet"))
                .thenExecute(() -> {
                    helper.assertValueEqual(events, List.of("server 42"), "events");
                    helper.assertTrue(!Objects.equals(computedOn.get(), serverThread),
                            "the job ran on the server thread");
                    events.clear();
                    helper.assertTrue(WorkerPool.server().submit(sum), "the server pool refused a job");
                    totals.write().put(3, 1);
                }).thenWaitUntil(() -> helper.assertTrue(!events.isEmpty(), "no result yet"))
                .thenExecute(() -> helper.assertValueEqual(events, List.of("discarded 42"), "events")).thenSucceed();
    }

    /** Counts its runs into a shared slot and returns a fixed result. */
    private static final class CountingTask extends ScheduledTask {
        private final int[] runs;
        private final int result;

        CountingTask(int[] runs, int result) {
            super(TaskPriority.BACKGROUND);
            this.runs = runs;
            this.result = result;
        }

        @Override
        protected int run(TaskGroup group, long gameTime) {
            runs[0]++;
            return result;
        }
    }

    static final class FeBlock extends Block implements EntityBlock {
        static final MapCodec<FeBlock> CODEC = simpleCodec(FeBlock::new);

        FeBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected MapCodec<? extends Block> codec() {
            return CODEC;
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new FeEntity(pos, state);
        }
    }

    /** 10,000 FE, 1,000 per operation each way; not saved. */
    static final class FeEntity extends BlockEntity {
        final FeBuffer buffer = new FeBuffer(10_000, 1000, 1000, this::changed);
        int changes;

        FeEntity(BlockPos pos, BlockState state) {
            super(FE_BLOCK_ENTITY.get(), pos, state);
        }

        private void changed() {
            changes++;
            setChanged();
        }
    }
}
