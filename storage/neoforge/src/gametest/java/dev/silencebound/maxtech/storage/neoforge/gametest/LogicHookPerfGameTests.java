package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.Locale;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * The "items move in or out" hook sits on every network operation (M7L2 batch 4). With no graph listening, an operation
 * pays two emptiness checks and nothing else; with a graph listening to another item, one map lookup. Both are timed
 * over 20,000 one-item operations (inserts and extracts, each its own root transaction, through the network's whole
 * path) against the per-operation budget of the other transfer perf tests; the run with a graph listening to the moved
 * item itself is timed too, for the record. Own batch.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicHookPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAME = "perf_logic_item_moves";
    private static final int OPERATIONS = 20_000;
    /** Mean per operation; about 2 microseconds on a desktop, with or without a listener. */
    private static final long BUDGET_NANOS = 10_000;
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey DIRT = ItemKey.of(Items.DIRT);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        StorageTests.function(NAME, StorageTestEnvironment.clearing(LogicHookPerfGameTests::moves));
    }

    private LogicHookPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        StorageTests.test(event,
                event.registerEnvironment(MaxtechStorage.id(NAME), new StorageTestEnvironment("", "", true)), NAME,
                MaxtechStorage.id("empty_42x4x27"), 400);
    }

    private static LogicGraph<LogicKey> listening(StackKey key) {
        return new LogicControllerGameTests.Graph()
                .node(1, NodeTypes.ITEM_MOVES, s -> s.withItem("item", LogicKey.of(key)))
                .node(2, NodeTypes.COUNTER, s -> s).wire(1, "moved", 2, "up").build();
    }

    private static void moves(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lh_perf");
        // Unlimited power: 20,000 operations would drain a controller's buffer, and refused ones cost almost nothing.
        helper.setBlock(NetworkGameTests.at(4), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertValueEqual(NetworkGameTests.insert(network, COBBLE, 1), 1L, "a first cobblestone");
            helper.assertFalse(network.hearsItemMoves(), "nothing listens");
            run(helper, network, 2 * OPERATIONS);
            PerfBudget.measureUnder(helper, NAME + "_none", BUDGET_NANOS, () -> {
                long mean = run(helper, network, OPERATIONS) / OPERATIONS;
                LOGGER.info("{}_none: {} operations, no graph listening; mean {} ms (budget {} ms).", NAME, OPERATIONS,
                        millis(mean), millis(BUDGET_NANOS));
                return mean;
            }, mean -> "an operation with no item-moves listener takes " + millis(mean) + " ms, over the "
                    + millis(BUDGET_NANOS) + " ms budget");
            entity.worker().setGraph(0, "Dirt", listening(DIRT));
        }).thenWaitUntil(() -> helper.assertTrue(
                entity.worker().evaluator(0).map(evaluator -> evaluator.runs() > 0).orElse(false), "not started"))
                .thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    helper.assertTrue(network.hearsItemMoves(), "a graph listens for dirt");
                    runs[0] = entity.worker().runs();
                    PerfBudget.measureUnder(helper, NAME + "_other_key", BUDGET_NANOS, () -> {
                        long mean = run(helper, network, OPERATIONS) / OPERATIONS;
                        LOGGER.info("{}_other_key: {} operations, a graph listening for another item; mean {} ms "
                                + "(budget {} ms).", NAME, OPERATIONS, millis(mean), millis(BUDGET_NANOS));
                        return mean;
                    }, mean -> "an operation with an item-moves listener on another item takes " + millis(mean)
                            + " ms, over the " + millis(BUDGET_NANOS) + " ms budget");
                }).thenIdle(2).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().runs(), runs[0], "graph runs for moves of another item");
                    entity.worker().setGraph(0, "Cobble", listening(COBBLE));
                }).thenIdle(2).thenExecute(() -> {
                    NetworkState network = LogicControllerGameTests.network(helper, entity);
                    long nanos = run(helper, network, OPERATIONS);
                    LOGGER.info("{}_listened: {} operations, a graph listening for the moved item; mean {} ms.", NAME,
                            OPERATIONS, millis(nanos / OPERATIONS));
                })
                .thenWaitUntil(() -> helper.assertTrue(entity.worker().evaluator(0)
                        .map(evaluator -> evaluator.value(evaluator.graph().slot(2, "count")) > 0).orElse(false),
                        "the graph listening for cobblestone heard the moves"))
                .thenSucceed();
    }

    /**
     * {@code count} one-cobblestone operations, inserts and extracts by turns; nanoseconds in all. Fails the test
     * unless every one moved its item.
     */
    private static long run(GameTestHelper helper, NetworkState network, int count) {
        long moved = 0;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            try (Transaction transaction = Transaction.openRoot()) {
                moved += i % 2 == 0 ? network.insert(COBBLE, 1, transaction) : network.extract(COBBLE, 1, transaction);
                transaction.commit();
            }
        }
        long nanos = System.nanoTime() - start;
        helper.assertValueEqual(moved, (long) count, "operations that moved their item");
        return nanos;
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
