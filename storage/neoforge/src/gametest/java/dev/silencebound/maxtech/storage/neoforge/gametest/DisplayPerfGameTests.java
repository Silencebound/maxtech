package dev.silencebound.maxtech.storage.neoforge.gametest;

import com.mojang.logging.LogUtils;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.schedule.LevelTasks;
import dev.silencebound.maxtech.lib.neoforge.perf.PerfBudget;
import dev.silencebound.maxtech.lib.api.network.NetworkGraph;
import dev.silencebound.maxtech.lib.api.network.NetworkManager;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.creative.CreativeEnergyContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.network.StorageTask;
import dev.silencebound.maxtech.storage.network.NetworkState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.slf4j.Logger;

/**
 * 100 display screens on a busy network with nobody watching (M6c area D): a 10 x 10 grid of single panels (facing up
 * and down by turns, so none merge) counting cobblestone, the planks tag or stored power, while a device stores a
 * cobblestone and a plank every tick. Unseen screens must send nothing, and the level's task scheduler plus the storage
 * network's tick must stay under budget: the per-key listeners and the tag watchers cost one lookup per change and wake
 * nothing. Runs in its own batch with timing on.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class DisplayPerfGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final String NAME = "perf_display_unseen_screens";
    private static final int SIDE = 10;
    /**
     * Long enough for the JIT to compile the store and listener path: after 100 ticks the window measured mostly
     * interpreted code (about 0.05 ms against about 0.006 ms once compiled).
     */
    private static final int WARMUP_TICKS = 2000;
    private static final int MEASURED_TICKS = 200;
    /**
     * Mean per tick for the busy device's two stores and 100 unseen screens hearing them; the same order as the idle
     * device budget, since unseen screens must add next to nothing.
     */
    private static final long BUDGET_NANOS = 30_000;
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);

    private static final RegistryHelper.Registrar<Consumer<GameTestHelper>> FUNCTIONS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.TEST_FUNCTION);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        FUNCTIONS.register(NAME, () -> StorageTestEnvironment.clearing(DisplayPerfGameTests::unseenScreens));
    }

    private DisplayPerfGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Identifier id = MaxtechStorage.id(NAME);
        event.registerTest(id,
                new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, id),
                        new TestData<>(event.registerEnvironment(id, StorageTestEnvironment.roomy("", "", true)),
                                STRUCTURE, WARMUP_TICKS + 2 * (MEASURED_TICKS + 1) + 200, 0, true)));
    }

    private static void unseenScreens(GameTestHelper helper) {
        helper.setBlock(new BlockPos(1, 1, 1), CreativeEnergyContent.CREATIVE_ENERGY_SOURCE.get());
        TestStorage.TestDevice busy = NetworkGameTests.device(helper, new BlockPos(1, 1, 2));
        List<DisplayPanelBlockEntity> panels = new ArrayList<>(SIDE * SIDE);
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                Direction facing = (x + z) % 2 == 0 ? Direction.UP : Direction.DOWN;
                DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, new BlockPos(2 + x, 1, 1 + z), facing);
                DisplaySettings settings = switch (panels.size() % 4) {
                    case 0, 1 -> DisplaySettings.DEFAULT.withSource(DisplaySourceKind.ITEM_COUNT).withItem(COBBLE);
                    case 2 -> DisplaySettings.DEFAULT.withSource(DisplaySourceKind.TAG_COUNT).withTag(ItemTags.PLANKS);
                    default -> DisplaySettings.DEFAULT.withSource(DisplaySourceKind.POWER_STORED);
                };
                panel.applyToWall(settings);
                panels.add(panel);
            }
        }
        int[] stores = new int[1];
        StorageTask work = (transfer, gameTime) -> {
            try (Transaction transaction = Transaction.openRoot()) {
                // Counts only what went in: a stopped network refuses every store and would measure nothing.
                stores[0] += (int) transfer.insert(stores[0] % 2 == 0 ? COBBLE : PLANKS, 1, transaction);
                transaction.commit();
            }
            return StorageTask.NEXT_TICK;
        };
        NetworkGraph graph = NetworkManager.get(helper.getLevel()).graph(StorageNetworks.TYPE);
        long[] total = new long[1];
        int[] measured = new int[1];
        int[] storesAtStart = new int[1];
        long[] runsAtStart = new long[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = NetworkGameTests.net(helper, busy);
            helper.assertValueEqual(network.deviceCount(), SIDE * SIDE + 2, "devices in the network");
            for (DisplayPanelBlockEntity panel : panels) {
                helper.assertTrue(panel.worker().isStarted(), "a screen is not running");
            }
        }).thenExecute(() -> busy.schedule(work)).thenIdle(WARMUP_TICKS).thenWaitUntil(PerfBudget.measureTicks(helper,
                NAME, MEASURED_TICKS, BUDGET_NANOS, PerfBudget.TickMeasurement.of(() -> {
                    total[0] = 0;
                    measured[0] = 0;
                    storesAtStart[0] = stores[0];
                    runsAtStart[0] = NetworkGameTests.taskRuns(NetworkGameTests.net(helper, busy));
                }, () -> {
                    long tasks = LevelTasks.lastTickNanos(helper.getLevel());
                    long network = graph.lastTickNanos();
                    if (tasks >= 0 && network >= 0) {
                        total[0] += tasks + network;
                        measured[0]++;
                    }
                }, () -> {
                    helper.assertTrue(measured[0] > MEASURED_TICKS / 2, "only " + measured[0] + " ticks timed");
                    helper.assertTrue(stores[0] - storesAtStart[0] >= MEASURED_TICKS / 2,
                            "the network was not busy: " + (stores[0] - storesAtStart[0]) + " stores");
                    int sends = 0;
                    for (DisplayPanelBlockEntity panel : panels) {
                        sends += panel.worker().sends();
                    }
                    helper.assertValueEqual(sends, 0, "updates sent by unseen screens");
                    // Only the busy task runs: unseen screens sleep whatever changes.
                    long runs = NetworkGameTests.taskRuns(NetworkGameTests.net(helper, busy)) - runsAtStart[0];
                    helper.assertTrue(runs <= stores[0] - storesAtStart[0] + 2,
                            runs + " task runs for " + (stores[0] - storesAtStart[0]) + " stores: unseen screens run");
                    long mean = total[0] / measured[0];
                    LOGGER.info("{}: {} unseen screens, {} stores in {} ticks; mean tick {} ms (budget {} ms).", NAME,
                            panels.size(), stores[0] - storesAtStart[0], MEASURED_TICKS, millis(mean),
                            millis(BUDGET_NANOS));
                    return mean;
                }), mean -> "100 unseen screens on a busy network take " + millis(mean) + " ms per tick, over the "
                        + millis(BUDGET_NANOS) + " ms budget"))
                .thenSucceed();
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.4f", nanos / 1_000_000.0);
    }
}
