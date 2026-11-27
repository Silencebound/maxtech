package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageTransfer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * Channel counting at scale (M7N, PRD 7.2 Channels with hubs: counted on topology changes per zone, never per
 * transfer). A cable change on a 10,000-node network recounts only the devices whose route it moved, never the whole
 * network, and fits the same budget as the route patch alone; and 10,000 operations of a device 1,000 segments from the
 * controller walk no route at all. Each runs in its own batch with timing on; run them alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ChannelPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_100x2x101");
    private static final String CHANGE = "perf_channels_cable_change_10k";
    private static final String TRANSFER = "perf_channels_transfer_no_route_work";
    /** As {@code perf_network_cable_change_10k}: the route patch's budget, which the channel recount must fit in. */
    private static final long CHANGE_BUDGET_NANOS = 6_000_000;
    /**
     * 10,000 one-item operations of a device at the end of a 1,000-node route, each in its own root transaction: about
     * 40 ms on a quiet machine. Whether an operation walks the route is checked exactly, by the network's count of
     * route segments walked; this budget bounds what the operations cost.
     */
    private static final long TRANSFER_BUDGET_NANOS = 150_000_000;
    private static final int OPERATIONS = 10_000;
    private static final int SIDE = 100;
    private static final int ROUNDS = 5;
    /** Columns of the plane that lead straight to a root link; devices sit under them. */
    private static final int FIRST_COLUMN = 45;
    private static final int LAST_COLUMN = 53;
    private static final int[] DEVICE_ROWS = {15, 30, 45, 60, 75, 90};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(CHANGE, StorageTestEnvironment.clearing(ChannelPerfGameTests::cableChange));
        StorageTests.function(TRANSFER, StorageTestEnvironment.clearing(ChannelPerfGameTests::transferNoRouteWork));
    }

    private ChannelPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(CHANGE), new StorageTestEnvironment("", "", true)), CHANGE,
                STRUCTURE, 600 + ROUNDS * 10 + 11);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(TRANSFER), new StorageTestEnvironment("", "", true)),
                TRANSFER, STRUCTURE, 600);
    }

    /**
     * The 100 x 100 layer of {@code perf_network_cable_change_10k}, in tier-3 cables, under a nine-block controller
     * (ten root links), with 54 devices under the nine columns that run straight to a root link. Five times a cable in
     * one of those columns, above its last device, is broken (a patch reroutes the devices below it) and later put
     * back. No change counts channels in full, each walks fewer route segments than a quarter of a full count, every
     * device keeps its channel, and the dearest graph tick after a change stays in the patch budget. Measured once: a
     * retry would repeat the cuts on a network the first attempt already patched, where the route checks no longer
     * hold.
     */
    private static void cableChange(GameTestHelper helper) {
        BlockPos core = new BlockPos(FIRST_COLUMN, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int i = 1; i <= 8; i++) {
            helper.setBlock(core.east(i), ControllerContent.CONTROLLER.get());
        }
        cable(helper, core.west());
        for (int x = 0; x < SIDE; x++) {
            for (int z = 1; z <= SIDE; z++) {
                cable(helper, new BlockPos(x, 1, z));
            }
        }
        List<TestStorage.TestDevice> devices = new ArrayList<>();
        for (int x = FIRST_COLUMN; x <= LAST_COLUMN; x++) {
            for (int z : DEVICE_ROWS) {
                devices.add(NetworkGameTests.device(helper, new BlockPos(x, 0, z)));
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] changes = new long[ROUNDS];
        long[] steps = new long[ROUNDS];
        long[] stepsBefore = new long[1];
        long[] fullCount = new long[1];
        int[] round = new int[1];
        int[] before = new int[3];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.rootLinksUsed(), 10, "root links");
            helper.assertValueEqual(network.channelsHeld(), devices.size(), "devices holding channels");
        }).thenIdle(20).thenWaitUntil(PerfBudget.measureTicksOnce(helper, CHANGE, ROUNDS * 10 + 10, CHANGE_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    before[0] = network.rebuilds();
                    before[1] = network.patches();
                    before[2] = network.channelCounts();
                    fullCount[0] = fullCount(network, devices);
                    Arrays.fill(changes, 0);
                    Arrays.fill(steps, 0);
                    round[0] = 0;
                }, () -> {
                    // Every tenth tick break a cable in a device column, keep the dearest of the next three graph
                    // ticks and the route steps the change walked, and on the fifth place the cable back.
                    int tick = round[0]++;
                    if (tick >= ROUNDS * 10) {
                        return;
                    }
                    int r = tick / 10;
                    BlockPos pos = new BlockPos(FIRST_COLUMN + 1 + 2 * (r % 4) + r / 4, 1, 80);
                    NetworkState network = NetworkGameTests.network(helper, core);
                    if (tick % 10 == 0) {
                        stepsBefore[0] = network == null ? 0 : network.channelSteps();
                        helper.destroyBlock(pos);
                    } else if (tick % 10 <= 4) {
                        if (tick % 10 <= 3) {
                            changes[r] = Math.max(changes[r], graph.lastTickNanos());
                        }
                        if (network != null) {
                            steps[r] = network.channelSteps() - stepsBefore[0];
                        }
                    } else if (tick % 10 == 5) {
                        cable(helper, pos);
                    }
                }, () -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.rebuilds(), before[0], "full rebuilds during the changes");
                    helper.assertValueEqual(network.channelCounts(), before[2], "full channel counts");
                    helper.assertTrue(network.patches() >= before[1] + ROUNDS,
                            (network.patches() - before[1]) + " patches for " + ROUNDS + " removals");
                    helper.assertValueEqual(network.channelsHeld(), devices.size(), "devices holding channels after");
                    long most = 0;
                    for (long walked : steps) {
                        helper.assertTrue(walked > 0, "a change rerouted no device");
                        most = Math.max(most, walked);
                    }
                    helper.assertTrue(most * 4 < fullCount[0],
                            "a change walked " + most + " route segments, a full count walks " + fullCount[0]);
                    long[] sorted = changes.clone();
                    Arrays.sort(sorted);
                    long median = sorted[sorted.length / 2];
                    LOGGER.info(
                            "{}: one cable change median {} ms, max {} ms (budget {} ms); route segments walked "
                                    + "{} at most, {} for a full count.",
                            CHANGE, millis(median), millis(sorted[sorted.length - 1]), millis(CHANGE_BUDGET_NANOS),
                            most, fullCount[0]);
                    return median;
                }), median -> "one cable change takes " + millis(median) + " ms, over the "
                        + millis(CHANGE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /** Route segments a full count walks: every device's route to the root. Test bookkeeping, not measured. */
    private static long fullCount(NetworkState network, List<TestStorage.TestDevice> devices) {
        long total = 0;
        for (TestStorage.TestDevice device : devices) {
            for (long node = device.nodePos(); network.routeParent(node) != Long.MIN_VALUE;) {
                node = network.routeParent(node);
                total++;
            }
        }
        return total;
    }

    /**
     * A device at the end of a snake of about 1,000 tier-1 cables, powered by a creative source beside the controller:
     * 10,000 operations (one item in, one out, alternately) all pass, walk no route segment and change no channel
     * count, and take less than the budget.
     */
    private static void transferNoRouteWork(GameTestHelper helper) {
        BlockPos core = new BlockPos(0, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        helper.setBlock(core.above(), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        BlockPos end = BlockPos.ZERO;
        for (int row = 0; row < 10; row++) {
            int z = 1 + 2 * row;
            for (int x = 0; x < SIDE; x++) {
                cable(helper, new BlockPos(x, 1, z));
            }
            if (row < 9) {
                // The connector at the far end of each row, alternating sides, so the route snakes.
                cable(helper, new BlockPos(row % 2 == 0 ? SIDE - 1 : 0, 1, z + 1));
            }
            end = new BlockPos(row % 2 == 0 ? SIDE - 1 : 0, 0, z);
        }
        TestStorage.TestDevice device = NetworkGameTests.device(helper, end);
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            helper.assertTrue(network.isPowered(), "network unpowered");
            helper.assertTrue(depth(network, device.nodePos()) >= 900, "route too short");
        }).thenIdle(10).thenExecute(() -> {
            NetworkState network = NetworkGameTests.net(helper, device);
            long steps = network.channelSteps();
            long version = network.channelVersion();
            int rebuilds = network.rebuilds();
            PerfBudget.measureUnder(helper, TRANSFER, TRANSFER_BUDGET_NANOS, () -> {
                StorageTransfer transfer = device.deviceLink().transfer();
                if (transfer == null) {
                    throw helper.assertionException(Component.literal("no transfer"));
                }
                int moved = 0;
                long started = System.nanoTime();
                for (int i = 0; i < OPERATIONS; i++) {
                    try (Transaction transaction = Transaction.openRoot()) {
                        moved += (int) (i % 2 == 0
                                ? transfer.insert(COBBLE, 1, transaction)
                                : transfer.extract(COBBLE, 1, transaction));
                        transaction.commit();
                    }
                }
                long took = System.nanoTime() - started;
                helper.assertValueEqual(moved, OPERATIONS, "operations that moved an item");
                LOGGER.info("{}: {} operations on a {}-segment route in {} ms (budget {} ms).", TRANSFER, OPERATIONS,
                        depth(network, device.nodePos()), millis(took), millis(TRANSFER_BUDGET_NANOS));
                return took;
            }, took -> OPERATIONS + " operations take " + millis(took) + " ms, over the "
                    + millis(TRANSFER_BUDGET_NANOS) + " ms budget");
            helper.assertValueEqual(network.channelSteps(), steps, "route segments walked by the operations");
            helper.assertValueEqual(network.channelVersion(), version, "channel counts changed by the operations");
            helper.assertValueEqual(network.rebuilds(), rebuilds, "rebuilds during the operations");
        }).thenSucceed();
    }

    private static int depth(NetworkState network, long pos) {
        int depth = 0;
        for (long node = pos; network.routeParent(node) != Long.MIN_VALUE;) {
            node = network.routeParent(node);
            depth++;
        }
        return depth;
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_3.type().get(), null);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
