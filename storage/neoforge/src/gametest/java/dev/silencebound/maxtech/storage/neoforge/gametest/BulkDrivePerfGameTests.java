package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.bulk.BulkContent;
import dev.silencebound.maxtech.storage.bulk.BulkDriveBlockEntity;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * 400 bulk drives on one network, each locked to its own item (PRD 8.1): idle they cost nothing (no ticker, no
 * scheduled task), an insert of a kind one of them holds goes straight to it, and an insert of a kind none takes walks
 * the providers once with a constant-time refusal each. The mean time per operation must stay within budget. Runs in
 * its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class BulkDrivePerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "perf_storage_bulk_drives";
    private static final int COLUMNS = 40;
    private static final int ROWS = 10;
    private static final int OPERATIONS = 2000;
    /** Mean per operation, held kind and refused kind together, with the network's whole path. */
    private static final long BUDGET_NANOS = 20_000;
    private static final long POWER = 1_000_000;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(BulkDrivePerfGameTests::drives));
    }

    private BulkDrivePerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), StorageTestEnvironment.roomy("", "", true)), NAME,
                MaxtechStorage.id("empty_42x4x27"), 400);
    }

    private static void drives(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        List<BulkDriveBlockEntity> drives = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                drives.add(BulkDriveGameTests.drive(helper, new BlockPos(1 + column, 1, 2 + row)));
            }
        }
        List<StackKey> kinds = kinds(drives.size());
        StackKey held = kinds.get(drives.size() / 2);
        StackKey refused = ItemKey.of(Items.BARRIER);
        helper.startSequence().thenWaitUntil(() -> {
            for (BulkDriveBlockEntity drive : drives) {
                if (drive.network() == null) {
                    throw helper.assertionException(Component.literal("a bulk drive is in no network"));
                }
            }
        }).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, NetworkGameTests.at(1));
            NetworkGameTests.power(helper, controller, POWER);
            for (int i = 0; i < drives.size(); i++) {
                BulkDriveBlockEntity drive = drives.get(i);
                BlockState state = drive.getBlockState();
                helper.assertTrue(state.getTicker(helper.getLevel(), BulkContent.BLOCK_ENTITY.get()) == null,
                        "a bulk drive ticks");
                helper.assertValueEqual(drive.scheduledTasks(), 0, "tasks of an idle bulk drive");
                try (Transaction transaction = Transaction.openRoot()) {
                    drive.store().insert(kinds.get(i), 1, transaction);
                    transaction.commit();
                }
            }
            helper.assertValueEqual(network.contents().amount(held), 1L, "indexed held kind");
            run(network, held, refused, OPERATIONS);
            PerfBudget.measureUnder(helper, NAME, BUDGET_NANOS, () -> {
                long before = network.contents().amount(held);
                NetworkGameTests.power(helper, controller, POWER);
                long[] times = run(network, held, refused, OPERATIONS);
                helper.assertValueEqual(network.contents().amount(held), before + OPERATIONS,
                        "held kind after the run");
                long mean = (times[0] + times[1]) / (2L * OPERATIONS);
                LOGGER.info(
                        "{}: {} drives, {} inserts of a held kind {} ms, {} refused inserts {} ms; mean {} ms "
                                + "(budget {} ms).",
                        NAME, drives.size(), OPERATIONS, millis(times[0]), OPERATIONS, millis(times[1]), millis(mean),
                        millis(BUDGET_NANOS));
                return mean;
            }, mean -> "an operation with 400 bulk drives takes " + millis(mean) + " ms on average, over the "
                    + millis(BUDGET_NANOS) + " ms budget");
        }).thenSucceed();
    }

    /** {@code count} distinct items, none of them air or a bulk drive. */
    @SuppressWarnings("ReferenceEquality") // items are registry singletons
    private static List<StackKey> kinds(int count) {
        List<StackKey> kinds = new ArrayList<>(count);
        for (Item item : BuiltInRegistries.ITEM) {
            if (kinds.size() == count) {
                break;
            }
            if (item != Items.AIR && item != Items.BARRIER && item != BulkContent.BULK_DRIVE.item().get()) {
                kinds.add(ItemKey.of(item));
            }
        }
        return kinds;
    }

    /** {@code count} one-item inserts of the held kind, then {@code count} of a kind no drive takes; nanoseconds. */
    private static long[] run(NetworkState network, StackKey held, StackKey refused, int count) {
        long[] times = new long[2];
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (NetworkGameTests.insert(network, held, 1) != 1) {
                throw new IllegalStateException("insert " + i + " was refused with " + network.storedEnergy() + " FE");
            }
        }
        times[0] = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            if (NetworkGameTests.insert(network, refused, 1) != 0) {
                throw new IllegalStateException("a kind no bulk drive holds went in");
            }
        }
        times[1] = System.nanoTime() - start;
        return times;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
