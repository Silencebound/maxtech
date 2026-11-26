package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.controller.ControllerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuCoreBlockEntity;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperBlockEntity;
import dev.silencebound.maxtech.storage.crafting.stock.StockKeeperContent;
import dev.silencebound.maxtech.storage.display.DisplayPanelBlockEntity;
import dev.silencebound.maxtech.storage.display.DisplaySettings;
import dev.silencebound.maxtech.storage.display.DisplaySourceKind;
import dev.silencebound.maxtech.storage.display.ScreenRows;
import dev.silencebound.maxtech.storage.display.production.ProductionFeed;
import dev.silencebound.maxtech.storage.display.production.ProductionStats;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.port.PortBlockEntity;
import dev.silencebound.maxtech.storage.port.PortContent;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Production stats (M7C item 18): the network's own move stream feeds the groups (a CPU, a stock keeper, a labelled
 * port pool) and the top items in and out, a real craft shows up on its CPU, and a display's feed builds the rows
 * without subscribing to anything of its own.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ProductionStatsGameTests {
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final CraftingPattern STICKS = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
            List.of(new PatternInput(new PatternIngredient.Exact(PLANKS), 2, Optional.empty(), false)),
            List.of(new PatternOutput(STICK, 4, 1f)), Optional.empty(), Optional.empty());
    private static final String[] NAMES = {"production_stats_real_craft", "production_stats_groups",
            "production_stats_display_rows"};

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(ProductionStatsGameTests::realCraft));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(ProductionStatsGameTests::groups));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(ProductionStatsGameTests::displayRows));
    }

    private ProductionStatsGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("production_stats"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 400);
        }
    }

    /** A powered controller at x 1 and a test device at x 2; the network forms a tick later (see {@link #net}). */
    private static ControllerBlockEntity setUp(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        return NetworkGameTests.controllerEntity(helper, core);
    }

    private static NetworkState net(GameTestHelper helper, ControllerBlockEntity controller) {
        NetworkState network = controller.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the controller is in no network"));
        }
        return network;
    }

    private static ProductionStats.Line line(List<ProductionStats.Line> lines, ProductionStats.Kind kind) {
        for (ProductionStats.Line line : lines) {
            if (line.kind() == kind) {
                return line;
            }
        }
        return MISSING;
    }

    private static final ProductionStats.Line MISSING = new ProductionStats.Line(ProductionStats.Kind.CPU, "", 0, -1,
            new LongArrayList());

    private static ProductionFeed feed(GameTestHelper helper, DisplayPanelBlockEntity panel) {
        if (panel.worker().feed() instanceof ProductionFeed feed) {
            return feed;
        }
        throw helper.assertionException(Component.literal("the panel has no production feed"));
    }

    /**
     * A real craft: a player's insert of planks counts as coming in, the CPU taking the planks out for the job shows as
     * a CPU line with a rate and as planks going out, and the stats were made by the first look, not by polling.
     */
    private static void realCraft(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper);
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class).setPatterns(List.of(STICKS));
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        ProductionStats[] made = new ProductionStats[1];
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertValueEqual(JobQueue.freeCpus(net(helper, controller)), 1, "free CPUs"))
                .thenIdle(30).thenExecute(() -> {
                    NetworkState network = net(helper, controller);
                    made[0] = ProductionStats.of(network);
                    helper.assertTrue(network.hearsItemMoves(), "the stats listen to the network's moves");
                    helper.assertTrue(ProductionStats.of(network) == made[0], "one stats object per network");
                    NetworkGameTests.insert(network, PLANKS, 40);
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 4);
                    JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(),
                            plan, JobQueue.Request.player(UUID.randomUUID()));
                    helper.assertTrue(submission.job().isPresent(), "refused: " + submission.refusal());
                }).thenWaitUntil(() -> helper.assertValueEqual(JobQueue.freeCpus(net(helper, controller)), 0,
                        "the CPU runs the job"))
                .thenIdle(30).thenExecute(() -> {
                    // As a provider pulling the sticks out of a machine: stored, then reported.
                    NetworkState network = net(helper, controller);
                    NetworkGameTests.insert(network, STICK, 4);
                    CraftingDeliveries.delivered(network, STICK, 4);
                }).thenWaitUntil(() -> {
                    long now = helper.getLevel().getGameTime();
                    ProductionStats.Line cpu = line(made[0].groups(now), ProductionStats.Kind.CPU);
                    helper.assertTrue(cpu.perMinute() > 0, "the CPU moved items");
                }).thenExecute(() -> {
                    long now = helper.getLevel().getGameTime();
                    helper.assertTrue(made[0].topIn(now).stream().anyMatch(top -> top.item().equals(PLANKS)),
                            "planks came in");
                    helper.assertTrue(made[0].topOut(now).stream().anyMatch(top -> top.item().equals(PLANKS)),
                            "the CPU took planks out");
                    helper.assertTrue(made[0].totalIn(now) > 0 && made[0].totalOut(now) > 0, "totals count");
                }).thenSucceed();
    }

    /**
     * Moves are attributed to the device that made them: a CPU, a stock keeper, and the ports sharing a label whatever
     * its case (one pool); an unlabelled port, a player's own move and a fluid make no group, and only items count.
     */
    private static void groups(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper);
        helper.setBlock(NetworkGameTests.at(3), StockKeeperContent.KEEPER.get());
        StockKeeperBlockEntity keeper = helper.getBlockEntity(NetworkGameTests.at(3), StockKeeperBlockEntity.class);
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        CpuCoreBlockEntity cpu = helper.getBlockEntity(NetworkGameTests.at(4), CpuCoreBlockEntity.class);
        helper.setBlock(NetworkGameTests.at(5), PortContent.PORT.get().defaultBlockState());
        PortBlockEntity first = helper.getBlockEntity(NetworkGameTests.at(5), PortBlockEntity.class);
        helper.setBlock(NetworkGameTests.at(6), PortContent.PORT.get().defaultBlockState());
        PortBlockEntity second = helper.getBlockEntity(NetworkGameTests.at(6), PortBlockEntity.class);
        helper.setBlock(NetworkGameTests.at(7), PortContent.PORT.get().defaultBlockState());
        PortBlockEntity plain = helper.getBlockEntity(NetworkGameTests.at(7), PortBlockEntity.class);
        first.setLabel(DeviceLabel.of("Smelters", null));
        second.setLabel(DeviceLabel.of("smelters", null));
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            NetworkState network = net(helper, controller);
            ProductionStats stats = ProductionStats.of(network);
            stats.crafted(cpu.nodePos(), 600);
            stats.stocked(keeper.nodePos(), 300);
            stats.itemsMoved(STICK, 120, first);
            stats.itemsMoved(STICK, -60, second);
            stats.itemsMoved(STICK, 999, plain);
            stats.itemsMoved(STICK, 50, null);
            stats.itemsMoved(FluidKey.of(Fluids.WATER), 1000, first);
            long now = helper.getLevel().getGameTime();
            List<ProductionStats.Line> lines = stats.groups(now);
            helper.assertValueEqual(lines.size(), 3, "a CPU, a keeper and one pool");
            ProductionStats.Line pool = line(lines, ProductionStats.Kind.POOL);
            helper.assertTrue(pool.label().equals("Smelters"), "the pool is named by its label");
            helper.assertTrue(pool.perMinute() >= 150 && pool.perMinute() <= 180, "pool rate " + pool.perMinute());
            helper.assertTrue(line(lines, ProductionStats.Kind.CPU).perMinute() >= 550, "CPU rate");
            helper.assertTrue(line(lines, ProductionStats.Kind.STOCK_KEEPER).perMinute() >= 275, "keeper rate");
            helper.assertTrue(lines.getFirst().kind() == ProductionStats.Kind.CPU, "the busiest group comes first");
            helper.assertTrue(stats.topIn(now).getFirst().item().equals(STICK), "sticks come in");
            helper.assertValueEqual(stats.topIn(now).size(), 1, "only sticks come in: fluids do not count");
            helper.assertTrue(stats.topOut(now).stream().anyMatch(top -> top.item().equals(STICK)), "sticks go out");
        }).thenSucceed();
    }

    /**
     * A screen set to the production source builds a CPU row with its history and the top lists with item icons, and
     * the same rows twice are equal (so an unchanged screen sends nothing).
     */
    private static void displayRows(GameTestHelper helper) {
        ControllerBlockEntity controller = setUp(helper);
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        CpuCoreBlockEntity cpu = helper.getBlockEntity(NetworkGameTests.at(4), CpuCoreBlockEntity.class);
        DisplayPanelBlockEntity panel = DisplayGameTests.panel(helper, NetworkGameTests.at(3), Direction.NORTH);
        panel.applyToWall(DisplaySettings.DEFAULT.withSource(DisplaySourceKind.PRODUCTION));
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            helper.assertTrue(panel.worker().feed() instanceof ProductionFeed, "the panel has a production feed");
            NetworkState network = net(helper, controller);
            ProductionStats.of(network).crafted(cpu.nodePos(), 240);
            ProductionStats.of(network).itemsMoved(COBBLE, -120, null);
            ScreenRows rows = feed(helper, panel).rows(network, helper.getLevel().getGameTime());
            helper.assertTrue(rows.rows().size() >= 3, "a CPU, a header and an item: " + rows.rows().size());
            helper.assertTrue(rows.rows().getFirst().tone() == ScreenRows.GOOD, "the CPU works");
            helper.assertTrue(rows.rows().getFirst().icon().isEmpty(), "a group has no icon");
            helper.assertTrue(rows.rows().stream().anyMatch(row -> row.icon().isPresent()), "an item row has an icon");
            helper.assertTrue(rows.equals(feed(helper, panel).rows(network, helper.getLevel().getGameTime())),
                    "unchanged rows are equal");
            helper.assertValueEqual(feed(helper, panel).refreshTicks(), ProductionFeed.REFRESH_TICKS,
                    "re-read by time only while seen");
        }).thenSucceed();
    }
}
