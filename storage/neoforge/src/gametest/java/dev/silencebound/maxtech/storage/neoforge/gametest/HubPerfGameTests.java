package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.hub.HubContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * Hub recount locality (M7N item 2, PRD 7.2 Channels with hubs: channels are counted per hub branch on topology
 * changes). A cable change behind a hub at the end of a 900-cable trunk walks only the hub's branch: no full count, no
 * rebuild, fewer route segments than twice the branch's routes up to the hub (a walk to the controller would cost more
 * than that for a single device), the trunk's channels stay as they were, and the graph tick after a change stays in
 * the route patch budget. Runs in its own batch with timing on; run it alone.
 */
// Networks are compared by identity.
@SuppressWarnings("ReferenceEquality")
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class HubPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_100x2x101");
    private static final String CHANGE = "perf_hubs_branch_recount";
    /** As {@code perf_network_cable_change_10k}: the route patch's budget. */
    private static final long CHANGE_BUDGET_NANOS = 6_000_000;
    private static final int SIDE = 100;
    private static final int TRUNK_ROWS = 9;
    private static final int ROUNDS = 5;
    /** The hub, past the trunk's last row, and the 20 x 20 plane of cables behind it. */
    private static final BlockPos HUB = new BlockPos(SIDE - 1, 1, 2 * TRUNK_ROWS);
    private static final int PLANE_X = SIDE - 20;
    private static final int PLANE_Z = 2 * TRUNK_ROWS + 1;
    private static final int OUTSIDE_DEVICES = 10;
    private static final String PLACE = "perf_hubs_place_remove_10k";
    /**
     * Placing or removing a hub rebuilds the network once, so its budget is the full rebuild's of a 10,000-node network
     * ({@code perf_controller_10k_root_links}); a cable change's patch budget does not apply to a rebuild.
     */
    private static final long REBUILD_BUDGET_NANOS = 25_000_000;
    /** Ticks between two hub changes: the rebuild interval, a patch before it and a margin. */
    private static final int PLACE_PERIOD = 20;
    /** Graph ticks after a hub change that may carry its rebuild. */
    private static final int PLACE_WINDOW = 8;
    private static final int FIRST_COLUMN = 45;
    private static final int LAST_COLUMN = 53;
    private static final int[] DEVICE_ROWS = {15, 30, 45, 60, 75, 90};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(CHANGE, StorageTestEnvironment.clearing(HubPerfGameTests::branchRecount));
        StorageTests.function(PLACE, StorageTestEnvironment.clearing(HubPerfGameTests::placeRemove));
    }

    private HubPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(CHANGE), new StorageTestEnvironment("", "", true)), CHANGE,
                STRUCTURE, 600 + ROUNDS * 10 + 11);
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(PLACE), new StorageTestEnvironment("", "", true)), PLACE,
                STRUCTURE, 600 + 2 * ROUNDS * PLACE_PERIOD * 2 + 11);
    }

    /**
     * The 100 x 100 layer of {@code perf_channels_cable_change_10k} (tier-3 cables, a nine-block controller, 54 devices
     * under nine columns). Five times a cable in a device column is replaced by a tier-3 hub, and later the hub by a
     * cable again. Each change rebuilds the network exactly once and counts channels once, every device keeps its
     * channel, and the dearest graph tick after a change stays in the 10,000-node rebuild budget.
     */
    private static void placeRemove(GameTestHelper helper) {
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
        int devices = 0;
        for (int x = FIRST_COLUMN; x <= LAST_COLUMN; x++) {
            for (int z : DEVICE_ROWS) {
                NetworkGameTests.device(helper, new BlockPos(x, 0, z));
                devices++;
            }
        }
        int deviceCount = devices;
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        int changes = 2 * ROUNDS;
        long[] nanos = new long[changes];
        int[] rebuilds = new int[changes];
        int[] counts = new int[changes];
        int[] before = new int[2];
        int[] round = new int[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.rootLinksUsed(), 10, "root links");
            helper.assertValueEqual(network.channelsHeld(), deviceCount, "devices holding channels");
        }).thenIdle(20).thenWaitUntil(PerfBudget.measureTicks(helper, PLACE, changes * PLACE_PERIOD + 10,
                REBUILD_BUDGET_NANOS, PerfBudget.TickMeasurement.of(() -> {
                    Arrays.fill(nanos, 0);
                    Arrays.fill(rebuilds, 0);
                    Arrays.fill(counts, 0);
                    round[0] = 0;
                }, () -> {
                    // Every PLACE_PERIOD ticks a change: even ones put a hub in place of a cable, odd ones take it
                    // out again. Keep the dearest graph tick of the window after it and the rebuilds it caused.
                    int tick = round[0]++;
                    if (tick >= changes * PLACE_PERIOD) {
                        return;
                    }
                    int c = tick / PLACE_PERIOD;
                    int at = tick % PLACE_PERIOD;
                    BlockPos pos = new BlockPos(FIRST_COLUMN + 1 + 2 * ((c / 2) % 4), 1, 80);
                    NetworkState network = NetworkGameTests.network(helper, core);
                    if (at == 0) {
                        if (network != null) {
                            before[0] = network.rebuilds();
                            before[1] = network.channelCounts();
                        }
                        helper.destroyBlock(pos);
                        if (c % 2 == 0) {
                            helper.setBlock(pos, HubContent.TIER_3.get());
                        } else {
                            cable(helper, pos);
                        }
                    } else if (at <= PLACE_WINDOW) {
                        nanos[c] = Math.max(nanos[c], graph.lastTickNanos());
                    } else if (at == PLACE_PERIOD - 1 && network != null) {
                        rebuilds[c] = network.rebuilds() - before[0];
                        counts[c] = network.channelCounts() - before[1];
                    }
                }, () -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    for (int c = 0; c < changes; c++) {
                        String what = (c % 2 == 0 ? "placing" : "removing") + " hub " + (c / 2);
                        helper.assertValueEqual(rebuilds[c], 1, "rebuilds for " + what);
                        helper.assertValueEqual(counts[c], 1, "channel counts for " + what);
                    }
                    helper.assertValueEqual(network.channelsHeld(), deviceCount, "devices holding channels after");
                    long[] sorted = nanos.clone();
                    Arrays.sort(sorted);
                    long median = sorted[sorted.length / 2];
                    LOGGER.info("{}: placing or removing a hub median {} ms, max {} ms (budget {} ms).", PLACE,
                            millis(median), millis(sorted[sorted.length - 1]), millis(REBUILD_BUDGET_NANOS));
                    return median;
                }), median -> "placing or removing a hub takes " + millis(median) + " ms, over the "
                        + millis(REBUILD_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /**
     * A controller, a snake of tier-3 cables nine rows long (about 900 cables) with ten devices under its first row, a
     * tier-3 hub at its end and a 20 x 20 plane of tier-3 cables behind the hub with 20 devices set into its far rows.
     * Five times the cable a branch device routes through is broken (a patch reroutes it over the plane) and later put
     * back.
     */
    private static void branchRecount(GameTestHelper helper) {
        BlockPos core = new BlockPos(0, 1, 0);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        for (int row = 0; row < TRUNK_ROWS; row++) {
            int z = 1 + 2 * row;
            for (int x = 0; x < SIDE; x++) {
                cable(helper, new BlockPos(x, 1, z));
            }
            if (row < TRUNK_ROWS - 1) {
                cable(helper, new BlockPos(row % 2 == 0 ? SIDE - 1 : 0, 1, z + 1));
            }
        }
        List<TestStorage.TestDevice> outside = new ArrayList<>();
        for (int i = 0; i < OUTSIDE_DEVICES; i++) {
            outside.add(NetworkGameTests.device(helper, new BlockPos(10 + 2 * i, 0, 1)));
        }
        helper.setBlock(HUB, HubContent.TIER_3.get());
        List<BlockPos> spots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            spots.add(new BlockPos(PLANE_X + 1 + 2 * i, 1, PLANE_Z + 16));
            spots.add(new BlockPos(PLANE_X + 1 + 2 * i, 1, PLANE_Z + 18));
        }
        for (int x = PLANE_X; x < SIDE; x++) {
            for (int z = PLANE_Z; z < PLANE_Z + 20; z++) {
                BlockPos pos = new BlockPos(x, 1, z);
                if (!spots.contains(pos)) {
                    cable(helper, pos);
                }
            }
        }
        List<TestStorage.TestDevice> branch = new ArrayList<>();
        for (BlockPos pos : spots) {
            branch.add(NetworkGameTests.device(helper, pos));
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long hub = helper.absolutePos(HUB).asLong();
        long trunkStart = helper.absolutePos(new BlockPos(0, 1, 1)).asLong();
        long[] changes = new long[ROUNDS];
        long[] steps = new long[ROUNDS];
        long[] stepsBefore = new long[1];
        long[] branchFull = new long[1];
        long[] broken = new long[ROUNDS];
        int[] round = new int[1];
        int[] before = new int[3];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            for (TestStorage.TestDevice device : outside) {
                helper.assertTrue(device.network() == network, "a device on the trunk is offline");
            }
            helper.assertValueEqual(network.channelsHeld(), OUTSIDE_DEVICES + branch.size(), "devices online");
            helper.assertValueEqual(network.hubChannels(hub), branch.size(), "devices behind the hub");
            helper.assertValueEqual(network.segmentChannels(trunkStart), OUTSIDE_DEVICES + 1,
                    "channels at the trunk's start: the outside devices and the hub");
        }).thenIdle(20).thenWaitUntil(PerfBudget.measureTicks(helper, CHANGE, ROUNDS * 10 + 10, CHANGE_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    before[0] = network.rebuilds();
                    before[1] = network.patches();
                    before[2] = network.channelCounts();
                    branchFull[0] = branchRoutes(network, branch, hub);
                    Arrays.fill(changes, 0);
                    Arrays.fill(steps, 0);
                    round[0] = 0;
                }, () -> {
                    // Every tenth tick break the cable a branch device routes through, keep the dearest of the next
                    // three graph ticks and the route steps the change walked, and on the fifth put the cable back.
                    int tick = round[0]++;
                    if (tick >= ROUNDS * 10) {
                        return;
                    }
                    int r = tick / 10;
                    NetworkState network = NetworkGameTests.network(helper, core);
                    if (tick % 10 == 0) {
                        if (network == null) {
                            return;
                        }
                        long parent = network.routeParent(branch.get(r * 4).nodePos());
                        broken[r] = parent;
                        stepsBefore[0] = network.channelSteps();
                        if (parent != Long.MIN_VALUE) {
                            helper.getLevel().destroyBlock(BlockPos.of(parent), false);
                        }
                    } else if (tick % 10 <= 4) {
                        if (tick % 10 <= 3) {
                            changes[r] = Math.max(changes[r], graph.lastTickNanos());
                        }
                        if (network != null) {
                            steps[r] = network.channelSteps() - stepsBefore[0];
                        }
                    } else if (tick % 10 == 5 && broken[r] != Long.MIN_VALUE) {
                        ConduitBlock.placeConduit(helper.getLevel(), BlockPos.of(broken[r]),
                                CableContent.STORAGE_CABLE_TIER_3.type().get(), null);
                    }
                }, () -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.rebuilds(), before[0], "full rebuilds during the changes");
                    helper.assertValueEqual(network.channelCounts(), before[2], "full channel counts");
                    helper.assertTrue(network.patches() >= before[1] + ROUNDS,
                            (network.patches() - before[1]) + " patches for " + ROUNDS + " removals");
                    helper.assertValueEqual(network.channelsHeld(), OUTSIDE_DEVICES + branch.size(),
                            "devices online after");
                    helper.assertValueEqual(network.hubChannels(hub), branch.size(), "devices behind the hub after");
                    helper.assertValueEqual(network.segmentChannels(trunkStart), OUTSIDE_DEVICES + 1,
                            "channels at the trunk's start after");
                    long most = 0;
                    for (int r = 0; r < ROUNDS; r++) {
                        helper.assertTrue(broken[r] != Long.MIN_VALUE, "round " + r + " found no route to break");
                        helper.assertTrue(steps[r] > 0, "round " + r + " rerouted no device");
                        most = Math.max(most, steps[r]);
                    }
                    helper.assertTrue(most <= 2 * branchFull[0], "a change walked " + most
                            + " route segments, the branch's routes up to the hub are " + branchFull[0]);
                    long[] sorted = changes.clone();
                    Arrays.sort(sorted);
                    long median = sorted[sorted.length / 2];
                    LOGGER.info(
                            "{}: one change behind the hub median {} ms, max {} ms (budget {} ms); route segments "
                                    + "walked {} at most, {} for the branch up to the hub.",
                            CHANGE, millis(median), millis(sorted[sorted.length - 1]), millis(CHANGE_BUDGET_NANOS),
                            most, branchFull[0]);
                    return median;
                }), median -> "one change behind the hub takes " + millis(median) + " ms, over the "
                        + millis(CHANGE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /** Route segments from every branch device up to the hub, plus the hub's own count. Test bookkeeping. */
    private static long branchRoutes(NetworkState network, List<TestStorage.TestDevice> branch, long hub) {
        long total = 0;
        for (TestStorage.TestDevice device : branch) {
            for (long node = device.nodePos(); node != hub && network.routeParent(node) != Long.MIN_VALUE;) {
                node = network.routeParent(node);
                total++;
            }
            total++;
        }
        return total;
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_3.type().get(), null);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
