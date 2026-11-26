package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.controller.module.TelemetryDisplayHistory;
import dev.silencebound.maxtech.storage.controller.module.TelemetryRecorder;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.silencebound.maxtech.storage.network.NetworkHistory;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The controller's telemetry history (M6c2 area G's telemetry recorder, built in since M7R item 3): every controller
 * samples its network on a cadence (one sample per {@code sampleTicks}, nothing in between) into fixed ring buffers
 * that display panels read through {@link NetworkState#history()}; with {@code controller.recorders.telemetry} off
 * there is no history and no sampling task ({@code ControllerRecorderGameTests}).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ControllerTelemetryGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final String RECORDS = "controller_telemetry_records";
    private static final String BOUNDED = "controller_telemetry_memory_bounded";

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(RECORDS, StorageTestEnvironment.clearing(ControllerTelemetryGameTests::records));
        StorageTests.function(BOUNDED, StorageTestEnvironment.clearing(ControllerTelemetryGameTests::bounded));
    }

    private ControllerTelemetryGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> fast = event.registerEnvironment(
                MaxtechStorage.id("controller_telemetry_10"),
                new StorageTestEnvironment("controller.recorders.telemetrySampleTicks", "10", false));
        StorageTests.test(event, fast, RECORDS, STRUCTURE, 600);
        StorageTests.test(event, fast, BOUNDED, STRUCTURE, 200);
    }

    /**
     * A recorder samples every 10 ticks: five samples in 50 ticks and no task runs between them. Two player inserts of
     * 64 items show in operations, FE out (4 each) and the item count, FE fed in shows as FE in, and a tracked key
     * reads -1 before it was tracked and its amount after. No expansion block is needed.
     */
    private static void records(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        TestStorage.TestDevice device = NetworkGameTests.device(helper, core.north());
        int[] sizes = new int[2];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertTrue(device.network() == network, "network formed");
            helper.assertTrue(network.history() != null && history(helper, core).size() >= 1, "first sample");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, core, 5_000);
            // FE through a device's input counts as received; the test power above does not.
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertValueEqual(device.energyInput().insert(800, transaction), 800, "FE in");
                transaction.commit();
            }
            helper.assertTrue(history(helper, core).track(COBBLE), "tracked");
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 64), 64L, "insert");
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 64), 64L, "insert");
            sizes[0] = history(helper, core).size();
        }).thenIdle(50).thenExecute(() -> {
            NetworkHistory history = history(helper, core);
            sizes[1] = history.size();
            int taken = sizes[1] - sizes[0];
            helper.assertTrue(taken >= 4 && taken <= 6, taken + " samples in 50 ticks at one per 10");
            long[] values = new long[history.capacity()];
            int count = history.read(NetworkHistory.Series.OPERATIONS, values);
            helper.assertValueEqual(sum(values, count), 2L, "operations");
            count = history.read(NetworkHistory.Series.ENERGY_OUT, values);
            helper.assertValueEqual(sum(values, count), 8L, "FE paid");
            count = history.read(NetworkHistory.Series.ENERGY_IN, values);
            helper.assertValueEqual(sum(values, count), 800L, "FE fed in");
            count = history.read(NetworkHistory.Series.ITEMS, values);
            helper.assertValueEqual(values[count - 1], 128L, "items now");
            count = history.readKey(COBBLE, values);
            helper.assertValueEqual(values[count - 1], 128L, "tracked key now");
            helper.assertValueEqual(values[0], -1L, "before it was tracked");
            // Display screens get the same history: the newest stored-FE point is the newest sample.
            NetworkState network = NetworkGameTests.state(helper, core);
            LongArrayList seeded = new LongArrayList();
            TelemetryDisplayHistory.INSTANCE.fill(network, DisplaySettings.DEFAULT, 4, 10, seeded);
            count = history.read(NetworkHistory.Series.ENERGY_STORED, values);
            helper.assertValueEqual(seeded.size(), 4, "points for the screen");
            helper.assertValueEqual(seeded.getLong(3), values[count - 1], "newest point");
            helper.assertValueEqual(seeded.getLong(0), values[count - 4], "oldest point, oldest first");
        }).thenSucceed();
    }

    /**
     * A recorder of 8 samples and 2 keys keeps exactly that: 20 samples leave the newest 8, a third key is refused
     * until one is untracked, and an untracked key reads nothing.
     */
    private static void bounded(GameTestHelper helper) {
        BlockPos core = new BlockPos(5, 1, 5);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.startSequence().thenWaitUntil(() -> NetworkGameTests.state(helper, core)).thenExecute(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            TelemetryRecorder recorder = new TelemetryRecorder(1, 8, 2);
            helper.assertValueEqual(recorder.bufferLongs(), (long) (NetworkHistory.Series.values().length + 2) * 8,
                    "longs held");
            StackKey dirt = ItemKey.of(Items.DIRT);
            StackKey stone = ItemKey.of(Items.STONE);
            helper.assertTrue(recorder.track(COBBLE) && recorder.track(dirt), "two keys");
            helper.assertTrue(!recorder.track(stone), "a third key is refused");
            for (int i = 0; i < 20; i++) {
                NetworkGameTests.power(helper, core, 1);
                recorder.sample(network, i);
            }
            long[] values = new long[16];
            helper.assertValueEqual(recorder.size(), 8, "samples kept");
            helper.assertValueEqual(recorder.read(NetworkHistory.Series.ENERGY_IN, values), 8, "read back");
            long[] stored = new long[16];
            recorder.read(NetworkHistory.Series.ENERGY_STORED, stored);
            helper.assertValueEqual(stored[7] - stored[0], 7L, "the newest eight, oldest first");
            helper.assertValueEqual(recorder.lastSampleTime(), 19L, "last sample");
            recorder.untrack(dirt);
            helper.assertValueEqual(recorder.readKey(dirt, values), 0, "untracked key");
            helper.assertTrue(recorder.track(stone), "room again");
            helper.assertValueEqual(recorder.trackedKeys(), 2, "two keys");
        }).thenSucceed();
    }

    private static NetworkHistory history(GameTestHelper helper, BlockPos core) {
        ControllerBlockEntity controller = NetworkGameTests.controllerEntity(helper, core);
        @Nullable
        NetworkHistory history = controller.history();
        if (history == null) {
            throw helper.assertionException(Component.literal("no telemetry history"));
        }
        return history;
    }

    private static long sum(long[] values, int count) {
        long total = 0;
        for (int i = 0; i < count; i++) {
            total += values[i];
        }
        return total;
    }
}
