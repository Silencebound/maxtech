package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellInventory;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockContent;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockEntity;
import dev.silencebound.maxtech.storage.storageblock.StorageBlockTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * 400 storage blocks on one network, each holding its own kind (PRD 8.1): idle they cost nothing (no ticker, no
 * scheduled task), and an insert and an extract of a kind one of them holds go straight to it. The mean time per
 * operation must stay within budget. Runs in its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class StorageBlockPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "perf_storage_storage_blocks";
    private static final int COLUMNS = 40;
    private static final int ROWS = 10;
    private static final int OPERATIONS = 2000;
    /** Mean per operation, insert and extract together, with the network's whole path. */
    private static final long BUDGET_NANOS = 20_000;
    private static final long POWER = 1_000_000;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(StorageBlockPerfGameTests::blocks));
    }

    private StorageBlockPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), StorageTestEnvironment.roomy("", "", true)), NAME,
                MaxtechStorage.id("empty_42x4x27"), 400);
    }

    private static void blocks(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        List<StorageBlockEntity> blocks = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                blocks.add(StorageBlockGameTests.block(helper, new BlockPos(1 + column, 1, 2 + row),
                        StorageBlockTier.ONE));
            }
        }
        List<StackKey> kinds = StorageBlockGameTests.kinds(blocks.size());
        StackKey held = kinds.get(blocks.size() / 2);
        helper.startSequence().thenWaitUntil(() -> {
            for (StorageBlockEntity block : blocks) {
                if (block.network() == null || block.cell() == null) {
                    throw helper.assertionException(Component.literal("a storage block is in no network"));
                }
            }
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
            NetworkGameTests.power(helper, controller, POWER);
            for (int i = 0; i < blocks.size(); i++) {
                StorageBlockEntity block = blocks.get(i);
                BlockState state = block.getBlockState();
                helper.assertTrue(state.getTicker(helper.getLevel(), StorageBlockContent.BLOCK_ENTITY.get()) == null,
                        "a storage block ticks");
                helper.assertValueEqual(block.scheduledTasks(), 0, "tasks of an idle storage block");
                CellInventory cell = block.cell();
                if (cell == null) {
                    throw helper.assertionException(Component.literal("a storage block has no cell"));
                }
                try (Transaction transaction = Transaction.openRoot()) {
                    cell.insert(kinds.get(i), 1, transaction);
                    transaction.commit();
                }
            }
            helper.assertValueEqual(network.contents().amount(held), 1L, "indexed held kind");
            run(helper, controller, network, held, OPERATIONS);
            PerfBudget.measureUnder(helper, NAME, BUDGET_NANOS, () -> {
                long before = network.contents().amount(held);
                long[] times = run(helper, controller, network, held, OPERATIONS);
                helper.assertValueEqual(network.contents().amount(held), before, "held kind after the run");
                long mean = (times[0] + times[1]) / (2L * OPERATIONS);
                LOGGER.info("{}: {} blocks, {} inserts {} ms, {} extracts {} ms; mean {} ms (budget {} ms).", NAME,
                        blocks.size(), OPERATIONS, millis(times[0]), OPERATIONS, millis(times[1]), millis(mean),
                        millis(BUDGET_NANOS));
                return mean;
            }, mean -> "an operation with 400 storage blocks takes " + millis(mean) + " ms on average, over the "
                    + millis(BUDGET_NANOS) + " ms budget");
        }).thenSucceed();
    }

    /**
     * {@code count} one-item inserts of the held kind, then {@code count} one-item extracts, the controller topped up
     * before each; nanoseconds.
     */
    private static long[] run(GameTestHelper helper, BlockPos controller, NetworkState network, StackKey held,
            int count) {
        long[] times = new long[2];
        NetworkGameTests.power(helper, controller, POWER);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (NetworkGameTests.insert(network, held, 1) != 1) {
                throw new IllegalStateException("insert " + i + " was refused with " + network.storedEnergy() + " FE");
            }
        }
        times[0] = System.nanoTime() - start;
        NetworkGameTests.power(helper, controller, POWER);
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            try (Transaction transaction = Transaction.openRoot()) {
                if (network.extract(held, 1, transaction) != 1) {
                    throw new IllegalStateException(
                            "extract " + i + " moved nothing with " + network.storedEnergy() + " FE");
                }
                transaction.commit();
            }
        }
        times[1] = System.nanoTime() - start;
        return times;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
