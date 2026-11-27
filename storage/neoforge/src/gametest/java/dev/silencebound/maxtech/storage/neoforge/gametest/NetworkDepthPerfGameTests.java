package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.slf4j.Logger;

/**
 * One cable change on a 10,000-node network (PRD 7.2, Performance; M6d): the 100 x 100 layer of tier-1 cables under a
 * nine-block controller, as in {@code perf_controller_10k_root_links}. Five times, a cable inside the layer is broken
 * and the dearest graph tick of the next three is kept (Lib's split check and the route patch), then the cable is
 * placed back. No change may cause a full rebuild. Runs in its own batch with timing on; run it alone.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class NetworkDepthPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "perf_network_cable_change_10k";
    /**
     * Median dearest tick after one cable removal: Lib's split check plus the route patch (a memoised walk over the
     * 10,010 nodes and a search over the detached ones). A full rebuild of the same network takes 12 to 17 ms.
     */
    private static final long CHANGE_BUDGET_NANOS = 6_000_000;
    private static final int SIDE = 100;
    private static final int ROUNDS = 5;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(NAME, () -> StorageTestEnvironment.clearing(NetworkDepthPerfGameTests::cableChange));
    }

    private NetworkDepthPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(NAME);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, new StorageTestEnvironment("", "", true)),
                                MaxtechStorage.id("empty_100x2x101"), 600 + ROUNDS * 10 + 11, 0, true)));
    }

    private static void cableChange(GameTestHelper helper) {
        BlockPos core = new BlockPos(45, 1, 0);
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
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] changes = new long[ROUNDS];
        int[] round = new int[1];
        int[] rebuilds = new int[2];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, core);
            helper.assertValueEqual(network.nodeCount(), SIDE * SIDE + 10, "nodes");
            helper.assertValueEqual(network.rootLinksUsed(), 10, "root links");
        }).thenIdle(20).thenWaitUntil(PerfBudget.measureTicks(helper, NAME, ROUNDS * 10 + 10, CHANGE_BUDGET_NANOS,
                PerfBudget.TickMeasurement.of(() -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    rebuilds[0] = network.rebuilds();
                    rebuilds[1] = network.patches();
                    Arrays.fill(changes, 0);
                    round[0] = 0;
                }, () -> {
                    // Every tenth tick break a cable near the controller (many routes run through it), keep the dearest
                    // of
                    // the next three graph ticks, and on the fifth place it back. The last ten ticks let the network
                    // settle.
                    int tick = round[0]++;
                    if (tick >= ROUNDS * 10) {
                        return;
                    }
                    int r = tick / 10;
                    BlockPos pos = new BlockPos(40 + r * 3, 1, 3);
                    if (tick % 10 == 0) {
                        helper.destroyBlock(pos);
                    } else if (tick % 10 <= 3) {
                        changes[r] = Math.max(changes[r], graph.lastTickNanos());
                    } else if (tick % 10 == 5) {
                        cable(helper, pos);
                    }
                }, () -> {
                    NetworkState network = NetworkGameTests.state(helper, core);
                    helper.assertValueEqual(network.nodeCount(), SIDE * SIDE + 10, "nodes after the changes");
                    helper.assertValueEqual(network.rebuilds(), rebuilds[0], "full rebuilds during the changes");
                    helper.assertTrue(network.patches() >= rebuilds[1] + ROUNDS,
                            (network.patches() - rebuilds[1]) + " patches for " + ROUNDS + " removals");
                    long[] sorted = changes.clone();
                    Arrays.sort(sorted);
                    long median = sorted[sorted.length / 2];
                    LOGGER.info("{}: one cable change median {} ms, max {} ms (budget {} ms).", NAME, millis(median),
                            millis(sorted[sorted.length - 1]), millis(CHANGE_BUDGET_NANOS));
                    return median;
                }), median -> "one cable change takes " + millis(median) + " ms, over the "
                        + millis(CHANGE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static void cable(GameTestHelper helper, BlockPos pos) {
        ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(pos),
                CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
