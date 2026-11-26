package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuConnections;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuPart;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * The crafting CPU's connected textures (B09 item 28): the block states that tell the client model which faces touch
 * the same formed cluster, after placing and breaking cluster blocks. Only blocks of one formed cluster connect; a core
 * next to another core, loose parts and a conflicted cluster connect to nothing. Also the other core's conflict when a
 * core is placed or broken, clusters saved before the connections existed, and the cost of breaking a full cluster.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CpuConnectionGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CLUSTER = "cpu_connect_cluster";
    private static final String CONFLICT = "cpu_connect_core_conflict";
    private static final String LOAD = "cpu_connect_old_cluster_on_load";
    private static final String PERF = "perf_storage_cpu_mass_break";
    /** One part broken off a 64-part cluster (the default part limit), mean. */
    private static final long BREAK_BUDGET_NANOS = 500_000;

    static {
        StorageTestEnvironment.init();
        register(CLUSTER, CpuConnectionGameTests::cluster);
        register(CONFLICT, CpuConnectionGameTests::coreConflict);
        register(LOAD, CpuConnectionGameTests::oldClusterOnLoad);
        register(PERF, CpuConnectionGameTests::massBreak);
    }

    private CpuConnectionGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(MaxtechStorage.id("cpu_connections"),
                StorageTestEnvironment.plain());
        for (String name : new String[]{CLUSTER, CONFLICT, LOAD}) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 200);
        }
        StorageTests.test(event, event.registerEnvironment(MaxtechStorage.id(PERF), StorageTestEnvironment.plain()),
                PERF, MaxtechStorage.id("empty_16x12x16"), 200);
    }

    private static int bits(Direction... sides) {
        int mask = 0;
        for (Direction side : sides) {
            mask |= CpuConnections.bit(side);
        }
        return mask;
    }

    private static void expect(GameTestHelper helper, BlockPos pos, int mask, String what) {
        helper.assertValueEqual(CpuConnections.mask(helper.getBlockState(pos)), mask, what);
    }

    /**
     * A core with a storage block and a lane in a row, a cache on the lane: each touches its cluster neighbours. A
     * second core beside the first, and a loose part, stay alone. Breaking the storage block cuts the lane and cache
     * off: they are loose and connect to nothing, not even each other. A core touching the lane's group makes it a
     * conflict, which connects nothing.
     */
    private static void cluster(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        BlockPos storage = core.east();
        BlockPos lane = core.east(2);
        BlockPos cache = lane.above();
        BlockPos other = core.west();
        BlockPos loose = core.north(3);
        helper.setBlock(core, CpuContent.CORE_1.get());
        helper.setBlock(storage, CpuContent.STORAGE.get());
        helper.setBlock(lane, CpuContent.LANE.get());
        helper.setBlock(cache, CpuContent.CACHE.get());
        helper.setBlock(other, CpuContent.CORE_2.get());
        helper.setBlock(loose, CpuContent.STORAGE.get());
        helper.startSequence().thenWaitUntil(() -> {
            expect(helper, core, bits(Direction.EAST), "core");
            expect(helper, storage, bits(Direction.WEST, Direction.EAST), "storage");
            expect(helper, lane, bits(Direction.WEST, Direction.UP), "lane");
            expect(helper, cache, bits(Direction.DOWN), "cache");
            expect(helper, other, 0, "a core beside another core");
            expect(helper, loose, 0, "a part without a core");
        }).thenExecute(() -> helper.destroyBlock(storage)).thenWaitUntil(() -> {
            expect(helper, core, 0, "core alone");
            expect(helper, lane, 0, "a loose lane, though it touches the cache");
            expect(helper, cache, 0, "a loose cache");
        }).thenExecute(() -> {
            helper.setBlock(storage, CpuContent.STORAGE.get());
            helper.setBlock(lane.east(), CpuContent.CORE_3.get());
        }).thenWaitUntil(() -> {
            expect(helper, core, 0, "conflicted core");
            expect(helper, storage, 0, "conflicted storage");
            expect(helper, lane, 0, "conflicted lane");
            expect(helper, lane.east(), 0, "second core");
        }).thenSucceed();
    }

    private static CpuCoreBlockEntity cpu(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockEntity(pos, CpuCoreBlockEntity.class);
    }

    /**
     * Placing a second core that reaches the first one's parts makes both conflicted at once, and breaking it ends the
     * first one's conflict; the connections follow.
     */
    private static void coreConflict(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        BlockPos storage = core.east();
        BlockPos second = core.east(2);
        helper.setBlock(core, CpuContent.CORE_1.get());
        helper.setBlock(storage, CpuContent.STORAGE.get());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertFalse(cpu(helper, core).conflict(), "one core");
            expect(helper, core, bits(Direction.EAST), "core");
        }).thenExecute(() -> {
            helper.setBlock(second, CpuContent.CORE_2.get());
            // At once, from the placement itself: the new core's scheduled tick would recount a tick later.
            helper.assertTrue(cpu(helper, core).conflict(), "the first core sees the second at once");
        }).thenWaitUntil(() -> {
            expect(helper, core, 0, "conflicted core");
            expect(helper, storage, 0, "conflicted storage");
        }).thenExecute(() -> {
            helper.destroyBlock(second);
            helper.assertFalse(cpu(helper, core).conflict(), "the conflict ends with the second core at once");
        }).thenWaitUntil(() -> {
            expect(helper, core, bits(Direction.EAST), "core again");
            expect(helper, storage, bits(Direction.WEST), "storage again");
        }).thenSucceed();
    }

    /**
     * A cluster whose blocks were placed without their placement hooks, as a cluster saved before connected textures
     * existed: the core's load connects it.
     */
    private static void oldClusterOnLoad(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 2, 5);
        BlockPos storage = core.east();
        BlockPos lane = core.above();
        int quiet = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;
        helper.getLevel().setBlock(helper.absolutePos(storage), CpuContent.STORAGE.get().defaultBlockState(), quiet);
        helper.getLevel().setBlock(helper.absolutePos(lane), CpuContent.LANE.get().defaultBlockState(), quiet);
        helper.getLevel().setBlock(helper.absolutePos(core), CpuContent.CORE_1.get().defaultBlockState(), quiet);
        helper.startSequence().thenWaitUntil(() -> {
            expect(helper, core, bits(Direction.EAST, Direction.UP), "core");
            expect(helper, storage, bits(Direction.WEST), "storage");
            expect(helper, lane, bits(Direction.DOWN), "lane");
        }).thenSucceed();
    }

    /**
     * A core and 64 parts in a 4 x 4 x 4 cube, broken one by one from the far corner, so every break walks and scans
     * what is left: mean per break under {@link #BREAK_BUDGET_NANOS}.
     */
    private static void massBreak(GameTestHelper helper) {
        BlockPos core = new BlockPos(3, 2, 3);
        List<BlockPos> parts = new ArrayList<>();
        for (int x = 1; x <= 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    parts.add(core.offset(x, y, z));
                }
            }
        }
        helper.setBlock(core, CpuContent.CORE_1.get());
        helper.startSequence().thenIdle(2)
                .thenExecute(() -> PerfBudget.measureUnder(helper, PERF, BREAK_BUDGET_NANOS, () -> {
                    for (BlockPos part : parts) {
                        helper.setBlock(part, CpuContent.STORAGE.get());
                    }
                    helper.assertValueEqual(cpu(helper, core).count(CpuPart.STORAGE), parts.size(), "counted");
                    long total = 0;
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        long start = System.nanoTime();
                        helper.setBlock(parts.get(i), Blocks.AIR);
                        total += System.nanoTime() - start;
                    }
                    helper.assertValueEqual(cpu(helper, core).count(CpuPart.STORAGE), 0, "all broken");
                    long mean = total / parts.size();
                    LOGGER.info("{}: mean break {} ms (budget {} ms).", PERF, mean / 1e6, BREAK_BUDGET_NANOS / 1e6);
                    return mean;
                }, mean -> "a part break took " + mean / 1e6 + " ms")).thenSucceed();
    }
}
