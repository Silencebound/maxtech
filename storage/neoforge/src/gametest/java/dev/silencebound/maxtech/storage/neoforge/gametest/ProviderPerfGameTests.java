package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * 200 idle pattern providers (PRD 8.1): a 20 by 10 grid on a cable floor, each holding a pattern and facing a furnace
 * above it. With no batch in flight their output tasks sleep without a timer, so the scheduler barely runs them and the
 * mean time per tick of the level's task scheduler plus the storage network's tick stays under budget. Own batch.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ProviderPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String IDLE = "perf_storage_idle_providers";
    private static final int COLUMNS = 20;
    private static final int ROWS = 10;
    private static final int WARMUP_TICKS = 100;
    private static final int MEASURED_TICKS = 200;
    /** Mean scheduler and network time per tick, as for 200 idle interfaces. */
    private static final long IDLE_BUDGET_NANOS = 20_000;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(IDLE, StorageTestEnvironment.clearing(ProviderPerfGameTests::idleProviders));
    }

    private ProviderPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(IDLE);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, WARMUP_TICKS + 2 * (MEASURED_TICKS + 1) + 300, 0, true)));
    }

    private static void idleProviders(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 100_000);
        NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        CraftingPattern pattern = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(Items.RAW_IRON)), 1, Optional.empty(),
                        false)),
                List.of(new PatternOutput(ItemKey.of(Items.IRON_INGOT), 1, 1f)), Optional.empty(), Optional.empty());
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                BlockPos floor = new BlockPos(2 + column, 1, 1 + row);
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(floor),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
                helper.setBlock(floor.above(), ProviderContent.PROVIDER.get().defaultBlockState()
                        .setValue(PatternProviderBlock.FACING, Direction.UP));
                helper.setBlock(floor.above(2), Blocks.FURNACE);
                helper.getBlockEntity(floor.above(), PatternProviderBlockEntity.class).logic().slots().setItem(0,
                        PatternItem.encode(pattern));
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] total = new long[1];
        int[] measured = new int[1];
        long[] runsAtStart = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + 2, "devices in the network");
            helper.assertValueEqual(network.patterns().providerCount(), COLUMNS * ROWS, "providers registered");
        }).thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper, IDLE, MEASURED_TICKS, IDLE_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    total[0] = 0;
                    measured[0] = 0;
                    runsAtStart[0] = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, controller));
                }, () -> {
                    long tasks = LevelTasks.lastTickNanos(helper.getLevel());
                    long network = graph.lastTickNanos();
                    if (tasks >= 0 && network >= 0) {
                        total[0] += tasks + network;
                        measured[0]++;
                    }
                }, () -> {
                    helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                    long mean = total[0] / measured[0];
                    long ran = NetworkGameTests.taskRuns(NetworkGameTests.state(helper, controller)) - runsAtStart[0];
                    LOGGER.info("{}: {} idle providers, {} task runs in {} ticks, {} ms per tick (budget {} ms).", IDLE,
                            COLUMNS * ROWS, ran, MEASURED_TICKS, mean / 1e6, IDLE_BUDGET_NANOS / 1e6);
                    helper.assertTrue(ran <= 10, ran + " runs: idle providers are not sleeping");
                    return mean;
                }), mean -> "200 idle providers take " + mean / 1e6 + " ms per tick")).thenSucceed();
    }
}
