package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.conduit.ConduitBlock;
import dev.silencebound.maxtech.lib.config.ServerConfig;
import dev.silencebound.maxtech.lib.energy.FeBuffer;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.network.NetworkGraph;
import dev.silencebound.maxtech.lib.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.lib.schedule.TaskGroup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.port.PortBlock;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import dev.silencebound.maxtech.storage.port.MatchMode;
import dev.silencebound.maxtech.storage.port.PortFilter;
import dev.silencebound.maxtech.storage.port.PortMode;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * Port and storage budgets (PRD 7.2, Performance; PRD 8.1). 500 idle ports: a 25 by 20 grid of import ports on a cable
 * floor, each facing up into an empty barrel; once their backoff has grown, the time per tick of the network's task
 * group plus the storage network's tick, as the median of {@link #WINDOWS} window means, must stay under budget, for
 * tier-1 ports and for tier-3 ports with two speed cards each (M6c), and for ports with fuzzy, tag and mod filter
 * entries (B09 items 18 and 22): half import ports facing barrels full of items no entry matches, half export ports
 * with keep amounts facing full barrels, on a network of 1,000 kinds. A burst insert: a million items stored by player
 * inserts of one batch each into ten drives must stay under a total budget. Each runs in its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class PortPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String IDLE = "perf_storage_idle_ports";
    private static final String BURST = "perf_storage_burst_insert";
    private static final String IDLE_TIER_3 = "perf_storage_idle_tier_3_ports";
    private static final String FILTERED = "perf_storage_filtered_ports";
    /** Kinds the filtered ports' network holds. */
    private static final int FILTERED_KINDS = 1000;
    private static final int COLUMNS = 25;
    private static final int ROWS = 20;
    private static final int WARMUP_TICKS = 400;
    /**
     * Measured windows, each {@code scheduler.maxBackoffTicks} long. Backed-off ports run in a burst once per maximum
     * backoff (they were placed on the same tick), so a median per tick would skip their work entirely, while the plain
     * mean over two bursts was pushed over budget by one GC pause or OS hiccup in a burst tick (issue #350: 0.0306 ms
     * against a steady 0.008 to 0.010 ms). Each window holds one full backoff cycle, so its mean counts every port's
     * run once; the median of the window means drops a window hit by a hiccup without dropping the ports' work.
     */
    private static final int WINDOWS = 7;
    /** Upper bound of {@code scheduler.maxBackoffTicks} in either preset, for the test's timeout. */
    private static final int MAX_WINDOW_TICKS = 200;
    /** Mean per tick for 500 idle ports (measured about 0.009 ms); PRD 8.1 gives 500 idle machines 0.1 ms. */
    private static final long IDLE_BUDGET_NANOS = 30_000;
    private static final long BURST_ITEMS = 1_000_000;
    private static final int DRIVES = 10;
    private static final int KINDS = 16;
    /** The whole burst of a million items (measured about 80 ms, test drives included). */
    private static final long BURST_BUDGET_NANOS = 250_000_000;

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(IDLE,
                () -> StorageTestEnvironment.clearing(helper -> idlePorts(helper, IDLE, Variant.TIER_1)));
        FUNCTIONS.register(IDLE_TIER_3,
                () -> StorageTestEnvironment.clearing(helper -> idlePorts(helper, IDLE_TIER_3, Variant.TIER_3)));
        FUNCTIONS.register(FILTERED,
                () -> StorageTestEnvironment.clearing(helper -> idlePorts(helper, FILTERED, Variant.FILTERED)));
        FUNCTIONS.register(BURST, () -> StorageTestEnvironment.clearing(PortPerfGameTests::burstInsert));
    }

    private PortPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        for (String name : List.of(IDLE, BURST, IDLE_TIER_3, FILTERED)) {
            Identifier id = MaxtechStorage.id(name);
            event.registerTest(id,
                    new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                            new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                    STRUCTURE, WARMUP_TICKS + 2 * (WINDOWS * MAX_WINDOW_TICKS + 1) + 200, 0, true)));
        }
    }

    /** The idle port tests' setups. */
    private enum Variant {
        /** Tier-1 import ports facing empty barrels. */
        TIER_1,
        /** Tier-3 import ports with two speed cards each, facing empty barrels. */
        TIER_3,
        /**
         * Tier-1 ports with fuzzy, tag and mod entries: import ports facing barrels of cobblestone no entry matches,
         * export ports with keep amounts facing barrels full of cobblestone, on a network of many kinds.
         */
        FILTERED
    }

    private static void idlePorts(GameTestHelper helper, String name, Variant variant) {
        boolean fast = variant == Variant.TIER_3;
        boolean filtered = variant == Variant.FILTERED;
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, controller, 1000);
        if (filtered) {
            TestStorage.TestDevice drive = NetworkGameTests.device(helper, controller.south());
            int found = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                if (found == FILTERED_KINDS) {
                    break;
                }
                if (!item.equals(Items.AIR) && !item.equals(Items.COBBLESTONE)) {
                    drive.provider.setDirect(ItemKey.of(item), 64);
                    found++;
                }
            }
        }
        List<PortBlockEntity> ports = new ArrayList<>(COLUMNS * ROWS);
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                BlockPos floor = new BlockPos(2 + column, 1, 1 + row);
                ConduitBlock.placeConduit(helper.getLevel(), helper.absolutePos(floor),
                        CableContent.STORAGE_CABLE_TIER_1.type().get(), null);
                PortBlock block = fast ? PortContent.PORT_TIER_3.get() : PortContent.PORT.get();
                helper.setBlock(floor.above(), block.defaultBlockState().setValue(PortBlock.FACING, Direction.UP));
                helper.setBlock(floor.above(2), Blocks.BARREL);
                PortBlockEntity port = helper.getBlockEntity(floor.above(), PortBlockEntity.class);
                if (filtered) {
                    filter(helper, port, floor.above(2), (row + column) % 2 == 0);
                }
                if (fast) {
                    port.worker().cards().setItem(0, new ItemStack(PortContent.SPEED_CARD.get()));
                    port.worker().cards().setItem(1, new ItemStack(PortContent.SPEED_CARD.get()));
                }
                ports.add(port);
            }
        }
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        int window = ServerConfig.SCHEDULER_MAX_BACKOFF_TICKS.getAsInt();
        long[] windowNanos = new long[WINDOWS];
        int[] windowTicks = new int[WINDOWS];
        int[] tick = new int[1];
        long[] runsAtStart = new long[1];
        long[] runsLastTick = new long[1];
        int[] rebuildsAtStart = new int[1];
        PerfBudget.TickMeasurement measurement = new PerfBudget.TickMeasurement() {
            @Override
            public void start() {
                Arrays.fill(windowNanos, 0);
                Arrays.fill(windowTicks, 0);
                tick[0] = 0;
                NetworkState network = NetworkGameTests.state(helper, controller);
                runsAtStart[0] = NetworkGameTests.taskRuns(network);
                runsLastTick[0] = runsAtStart[0];
                rebuildsAtStart[0] = network.rebuilds();
            }

            @Override
            public void tick() {
                TaskGroup group = NetworkGameTests.state(helper, controller).scheduler();
                // This network's group, not the level's whole task tick: copies run by -Pgametest.repeat share the
                // level. The group passes only on ticks with queued tasks, and lastPassNanos keeps the last pass's
                // time. The timer wheel's firing (queueing due tasks) is outside the group and not timed here; it is
                // O(timers) in lib and measured level-wide by SchedulerPerfGameTests.
                long runs = group.runs();
                long tasks = runs != runsLastTick[0] ? group.lastPassNanos() : 0;
                runsLastTick[0] = runs;
                long network = graph.lastTickNanos();
                int w = Math.min(WINDOWS - 1, tick[0]++ / window);
                if (tasks >= 0 && network >= 0) {
                    windowNanos[w] += tasks + network;
                    windowTicks[w]++;
                }
            }

            @Override
            public long finish() {
                long[] means = new long[WINDOWS];
                for (int w = 0; w < WINDOWS; w++) {
                    helper.assertTrue(windowTicks[w] > window / 2,
                            "only " + windowTicks[w] + " ticks timed in window " + w);
                    means[w] = windowNanos[w] / windowTicks[w];
                }
                long[] sorted = means.clone();
                Arrays.sort(sorted);
                long median = sorted[WINDOWS / 2];
                NetworkState network = NetworkGameTests.state(helper, controller);
                long ran = NetworkGameTests.taskRuns(network) - runsAtStart[0];
                LOGGER.info(
                        "{}: {} idle ports, {} task runs in {} ticks; mean tick per {}-tick window {} ms, median {} ms"
                                + " (budget {} ms).",
                        name, ports.size(), ran, WINDOWS * window, window, millisList(means), millis(median),
                        millis(IDLE_BUDGET_NANOS));
                // At the maximum backoff each port runs about once per window.
                helper.assertTrue(ran <= ports.size() * 3L * WINDOWS, ran + " runs: idle ports are not backing off");
                helper.assertValueEqual(network.rebuilds(), rebuildsAtStart[0], "route rebuilds while idle");
                return median;
            }
        };
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), COLUMNS * ROWS + (filtered ? 2 : 1),
                    "devices in the network");
        }).thenIdle(WARMUP_TICKS)
                .thenWaitUntil(PerfBudget.measureTicks(helper, name, WINDOWS * window, IDLE_BUDGET_NANOS, measurement,
                        median -> "500 idle ports take " + millis(median) + " ms per tick, over the "
                                + millis(IDLE_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /**
     * Sets up one filtered port: an import port with fuzzy, tag, mod and exact entries facing a barrel of 27 stacks of
     * cobblestone (which none matches), or an export port with the same kinds of entries, keep amounts on two, facing a
     * barrel full of cobblestone, so every run reads the barrel, finds no room and backs off.
     */
    private static void filter(GameTestHelper helper, PortBlockEntity port, BlockPos barrel, boolean importing) {
        BarrelBlockEntity inventory = helper.getBlockEntity(barrel, BarrelBlockEntity.class);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        }
        PortFilter filter = port.filter();
        filter.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        filter.setMatch(0, MatchMode.FUZZY, null);
        filter.setItem(1, new ItemStack(Items.OAK_LOG));
        filter.setMatch(1, MatchMode.TAG, Identifier.withDefaultNamespace("logs"));
        filter.setItem(2, new ItemStack(Items.OAK_PLANKS));
        filter.setMatch(2, MatchMode.TAG, Identifier.withDefaultNamespace("planks"));
        // Import: Storage's mod, which the barrel's cobblestone is not from; export: Minecraft's, every network kind.
        filter.setItem(3, importing ? new ItemStack(PortContent.SPEED_CARD.get()) : new ItemStack(Items.STONE));
        filter.setMatch(3, MatchMode.MOD, null);
        filter.setItem(4, new ItemStack(Items.DIRT));
        if (!importing) {
            port.setMode(PortMode.EXPORT);
            filter.setKeep(1, 32);
            filter.setKeep(3, 64);
        }
    }

    /**
     * A million items in batches of 64 over 16 kinds into ten drives of 100,000: only the inserts are timed; the
     * controller's buffer is topped up between them, outside the timing.
     */
    private static void burstInsert(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        List<TestStorage.TestDevice> drives = new ArrayList<>(DRIVES);
        for (int i = 0; i < DRIVES; i++) {
            drives.add(NetworkGameTests.device(helper, NetworkGameTests.at(2 + i)));
        }
        StackKey[] keys = new StackKey[KINDS];
        int found = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (found == KINDS) {
                break;
            }
            if (!item.equals(Items.AIR)) {
                keys[found++] = ItemKey.of(item);
            }
        }
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.state(helper, controller);
            helper.assertValueEqual(network.deviceCount(), DRIVES + 1, "devices in the network");
        }).thenExecute(
                () -> PerfBudget.measureUnder(helper, BURST, BURST_BUDGET_NANOS,
                        () -> burst(helper, controller, drives, keys), nanos -> "a burst of a million items took "
                                + millis(nanos) + " ms, over the " + millis(BURST_BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    /** One timed burst; empties the drives again afterwards, untimed, so a retry starts from the same state. */
    private static long burst(GameTestHelper helper, BlockPos controller, List<TestStorage.TestDevice> drives,
            StackKey[] keys) {
        NetworkState network = NetworkGameTests.state(helper, controller);
        FeBuffer buffer = NetworkGameTests.controllerEntity(helper, controller).buffer();
        long nanos = 0;
        long stored = 0;
        int operations = 0;
        while (stored < BURST_ITEMS) {
            buffer.load(buffer.capacity());
            StackKey key = keys[operations % KINDS];
            long start = System.nanoTime();
            long moved;
            try (Transaction transaction = Transaction.openRoot()) {
                moved = network.insert(key, Math.min(64, BURST_ITEMS - stored), transaction);
                transaction.commit();
            }
            nanos += System.nanoTime() - start;
            helper.assertTrue(moved > 0, "the drives filled up at " + stored);
            stored += moved;
            operations++;
        }
        long indexed = 0;
        for (StackKey key : keys) {
            indexed += network.contents().amount(key);
        }
        helper.assertValueEqual(indexed, BURST_ITEMS, "indexed items");
        long held = 0;
        for (TestStorage.TestDevice drive : drives) {
            for (StackKey key : keys) {
                held += drive.provider.amount(key);
            }
        }
        helper.assertValueEqual(held, BURST_ITEMS, "items in the drives");
        LOGGER.info("{}: {} items in {} inserts took {} ms (budget {} ms).", BURST, stored, operations, millis(nanos),
                millis(BURST_BUDGET_NANOS));
        for (StackKey key : keys) {
            long left = network.contents().amount(key);
            while (left > 0) {
                buffer.load(buffer.capacity());
                long taken;
                try (Transaction transaction = Transaction.openRoot()) {
                    taken = network.extract(key, left, transaction);
                    transaction.commit();
                }
                helper.assertTrue(taken > 0, "could not empty the drives of " + key);
                left -= taken;
            }
        }
        return nanos;
    }

    private static String millisList(long[] nanos) {
        StringBuilder out = new StringBuilder();
        for (long n : nanos) {
            out.append(out.isEmpty() ? "" : " ").append(millis(n));
        }
        return out.toString();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
