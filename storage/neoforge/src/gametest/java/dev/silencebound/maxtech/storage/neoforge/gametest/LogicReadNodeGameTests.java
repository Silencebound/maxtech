package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.api.security.OwnerRecord;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.drive.DriveBlockEntity;
import dev.silencebound.maxtech.storage.logic.LogicEvaluator;
import dev.silencebound.maxtech.storage.logic.LogicGraph;
import dev.silencebound.maxtech.storage.logic.NodeType;
import dev.silencebound.maxtech.storage.logic.NodeTypes;
import dev.silencebound.maxtech.storage.logic.controller.LogicContent;
import dev.silencebound.maxtech.storage.logic.controller.LogicControllerBlockEntity;
import dev.silencebound.maxtech.storage.logic.controller.LogicKey;
import dev.silencebound.maxtech.storage.network.DeviceLabel;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.subnet.SubnetBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The read node types of batch 2 in a real controller: each value follows the network when it changes (energy drawn,
 * items or fluid stored, a crafting job submitted), and a graph that reads it runs once for that change, not on every
 * tick and not for changes it does not read. A label no device carries reads 0.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class LogicReadNodeGameTests {
    private static final Identifier STRUCTURE = MaxtechStorage.id("empty_42x4x27");
    private static final List<String> NAMES = new ArrayList<>();
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STICK = ItemKey.of(Items.STICK);
    private static final StackKey WATER = FluidKey.of(Fluids.WATER);
    /** More than {@code condition.minIntervalTicks} (10 in both presets), so a sampled value has been looked at. */
    private static final int SETTLE = 25;

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register("logic_read_energy_follows_the_store", LogicReadNodeGameTests::energy);
        register("logic_read_free_space_follows_inserts", LogicReadNodeGameTests::freeSpace);
        register("logic_read_fluid_amount_and_tag", LogicReadNodeGameTests::fluid);
        register("logic_read_count_by_tag", LogicReadNodeGameTests::countByTag);
        register("logic_read_craft_status_follows_jobs", LogicReadNodeGameTests::craftStatus);
        register("logic_read_craft_status_linked_cpu", LogicReadNodeGameTests::craftStatusLinkedCpu);
    }

    private LogicReadNodeGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        NAMES.add(name);
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("logic_read"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, STRUCTURE, 300);
        }
    }

    private static LogicEvaluator<LogicKey> evaluator(GameTestHelper helper, LogicControllerBlockEntity entity,
            int graph) {
        return entity.worker().evaluator(graph)
                .orElseThrow(() -> helper.assertionException(Component.literal("graph " + graph + " is paused")));
    }

    /** Output {@code output} of node 1 of graph {@code graph}. */
    private static long out(GameTestHelper helper, LogicControllerBlockEntity entity, int graph, String output) {
        LogicEvaluator<LogicKey> evaluator = evaluator(helper, entity, graph);
        return evaluator.value(evaluator.graph().slot(1, output));
    }

    private static void ran(GameTestHelper helper, LogicControllerBlockEntity entity, int graphs) {
        for (int g = 0; g < graphs; g++) {
            helper.assertTrue(evaluator(helper, entity, g).runs() > 0, "graph " + g + " has not run yet");
        }
    }

    /** A powered controller at x 1, a drive with one cell of {@code cellType} at x 2, a logic controller at x 3. */
    private static LogicControllerBlockEntity driveSetUp(GameTestHelper helper, String owner, Identifier cellType) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        DriveBlockEntity drive = CellGameTests.drive(helper, NetworkGameTests.at(2));
        drive.insertCell(CellGameTests.cell(cellType, 1));
        helper.setBlock(NetworkGameTests.at(3), LogicContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(3));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), owner)));
        return entity;
    }

    /**
     * Drawing FE from the controller's store shows in the stored, capacity and percent outputs within the sampling
     * interval, for one run of the graph. The controller labelled "core" reads the same; a label no device carries
     * reads 0 and its graph does not run again.
     */
    private static void energy(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lr_energy");
        BlockPos core = NetworkGameTests.at(1);
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            NetworkGameTests.controllerEntity(helper, core).setLabel(DeviceLabel.of("core", null));
            entity.worker().setGraph(0, "Net", graph(NodeTypes.ENERGY, ""));
            entity.worker().setGraph(1, "Core", graph(NodeTypes.ENERGY, "core"));
            entity.worker().setGraph(2, "Missing", graph(NodeTypes.ENERGY, "bank"));
        }).thenWaitUntil(() -> ran(helper, entity, 3)).thenIdle(SETTLE).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertValueEqual(out(helper, entity, 0, "stored"), network.storedEnergy(), "stored at the start");
            helper.assertValueEqual(out(helper, entity, 0, "capacity"), network.energyCapacity(), "capacity");
            helper.assertValueEqual(out(helper, entity, 1, "stored"), network.storedEnergy(), "the labelled core");
            helper.assertValueEqual(out(helper, entity, 2, "stored"), 0L, "a label no device carries");
            helper.assertValueEqual(out(helper, entity, 2, "percent"), 0L, "no capacity, no percent");
            runs[0] = entity.worker().runs();
            long capacity = network.energyCapacity();
            // Down to a quarter of the capacity in one go.
            helper.assertTrue(NetworkGameTests.controllerEntity(helper, core).buffer()
                    .tryConsume(network.storedEnergy() - capacity / 4), "drew FE");
        }).thenIdle(SETTLE).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertValueEqual(out(helper, entity, 0, "stored"), network.storedEnergy(), "stored after drawing");
            helper.assertValueEqual(out(helper, entity, 0, "percent"), 25L, "percent full");
            helper.assertValueEqual(out(helper, entity, 1, "percent"), 25L, "the labelled core");
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 2, "one run each for the two that changed");
        }).thenSucceed();
    }

    /** Storing items in a cell lowers the free bytes and raises the percent, for one run. */
    private static void freeSpace(GameTestHelper helper) {
        LogicControllerBlockEntity entity = driveSetUp(helper, "lr_space", CellContent.ITEM_TYPE.id());
        long[] runs = new long[2];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Space", graph(NodeTypes.FREE_SPACE, ""));
            entity.worker().setGraph(1, "Missing", graph(NodeTypes.FREE_SPACE, "nothing"));
        }).thenWaitUntil(() -> ran(helper, entity, 2)).thenWaitUntil(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(network.totalBytes() > 0, "the cell is mounted");
            helper.assertValueEqual(out(helper, entity, 0, "total_bytes"), network.totalBytes(), "total bytes");
            helper.assertValueEqual(out(helper, entity, 0, "free_bytes"), network.totalBytes(), "an empty cell");
        }).thenIdle(SETTLE).thenExecute(() -> {
            runs[0] = entity.worker().runs();
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 640);
        }).thenIdle(SETTLE).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            helper.assertTrue(network.usedBytes() > 0, "the cobble went into the cell");
            helper.assertValueEqual(out(helper, entity, 0, "free_bytes"), network.totalBytes() - network.usedBytes(),
                    "free bytes after the insert");
            helper.assertValueEqual(out(helper, entity, 0, "percent"), network.usedBytes() * 100 / network.totalBytes(),
                    "percent full");
            helper.assertValueEqual(out(helper, entity, 1, "total_bytes"), 0L, "a label no device carries");
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "one run for the insert");
        }).thenSucceed();
    }

    /** Water stored in a fluid cell shows in a fluid amount by fluid and by the water tag, one run each. */
    private static void fluid(GameTestHelper helper) {
        LogicControllerBlockEntity entity = driveSetUp(helper, "lr_fluid", CellContent.FLUID_TYPE.id());
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Water", graph(NodeTypes.FLUID_AMOUNT, "fluid", LogicKey.of(WATER)));
            entity.worker().setGraph(1, "Tag",
                    graph(NodeTypes.FLUID_AMOUNT, "fluid", LogicKey.fluidTag(FluidTags.WATER)));
        }).thenWaitUntil(() -> ran(helper, entity, 2)).thenIdle(3).thenExecute(() -> {
            runs[0] = entity.worker().runs();
            helper.assertValueEqual(
                    NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), WATER, 3_000), 3_000L,
                    "water stored");
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(out(helper, entity, 0, "amount_mb"), 3_000L, "by fluid");
            helper.assertValueEqual(out(helper, entity, 1, "amount_mb"), 3_000L, "by tag");
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 2, "one run each");
        }).thenSucceed();
    }

    /**
     * Logs of two kinds add up in a count by the logs tag, for one run; cobblestone, outside the tag, costs none. A tag
     * with no items in the network reads 0.
     */
    private static void countByTag(GameTestHelper helper) {
        LogicControllerBlockEntity entity = LogicControllerGameTests.setUp(helper, "lr_tag");
        long[] runs = new long[1];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Logs", graph(NodeTypes.COUNT_BY_TAG, "tag", LogicKey.tag(ItemTags.LOGS)));
            entity.worker().setGraph(1, "Wool", graph(NodeTypes.COUNT_BY_TAG, "tag", LogicKey.tag(ItemTags.WOOL)));
        }).thenWaitUntil(() -> ran(helper, entity, 2)).thenIdle(3).thenExecute(() -> {
            runs[0] = entity.worker().runs();
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            NetworkGameTests.insert(network, ItemKey.of(Items.OAK_LOG), 5);
            NetworkGameTests.insert(network, ItemKey.of(Items.BIRCH_LOG), 3);
        }).thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(out(helper, entity, 0, "count"), 8L, "both kinds of log");
            helper.assertValueEqual(out(helper, entity, 1, "count"), 0L, "a tag with no items in the network");
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "one run for both inserts");
            NetworkGameTests.insert(LogicControllerGameTests.network(helper, entity), COBBLE, 10);
        }).thenIdle(3).thenExecute(
                () -> helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "no run for items outside the tag"))
                .thenSucceed();
    }

    /**
     * A job for sticks taken by the only CPU shows as running with no free CPU, for one run (submitting and starting it
     * happen in one tick); a second job waits; cancelling both frees the CPU again.
     */
    private static void craftStatus(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), CraftingTestContent.HOST.get());
        CraftingTestContent.Host host = helper.getBlockEntity(NetworkGameTests.at(3), CraftingTestContent.Host.class);
        host.setPatterns(List.of(crafting(Items.STICK, 4, Items.OAK_PLANKS, 2)));
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(5), LogicContent.CONTROLLER.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(5));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lr_craft")));
        long[] runs = new long[1];
        UUID[] job = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> LogicControllerGameTests.network(helper, entity)).thenExecute(() -> {
            entity.worker().setGraph(0, "Sticks", graph(NodeTypes.CRAFT_STATUS, "item", LogicKey.of(STICK)));
        }).thenWaitUntil(() -> {
            ran(helper, entity, 1);
            helper.assertValueEqual(out(helper, entity, 0, "free_cpus"), 1L, "the CPU is free");
            helper.assertValueEqual(out(helper, entity, 0, "running"), 0L, "nothing running");
        }).thenIdle(3).thenExecute(() -> {
            NetworkState network = LogicControllerGameTests.network(helper, entity);
            NetworkGameTests.insert(network, PLANKS, 8);
            runs[0] = entity.worker().runs();
            job[0] = submitSticks(helper, network);
        }).thenIdle(5).thenExecute(() -> {
            helper.assertValueEqual(out(helper, entity, 0, "running"), 1L, "the job runs");
            helper.assertValueEqual(out(helper, entity, 0, "waiting"), 0L, "none waits");
            helper.assertValueEqual(out(helper, entity, 0, "free_cpus"), 0L, "the CPU is busy");
            helper.assertValueEqual(entity.worker().runs(), runs[0] + 1, "one run for submitting and starting");
            job[1] = submitSticks(helper, LogicControllerGameTests.network(helper, entity));
        }).thenIdle(3).thenExecute(() -> {
            helper.assertValueEqual(out(helper, entity, 0, "waiting"), 1L, "the second job waits for the CPU");
            helper.assertValueEqual(out(helper, entity, 0, "running"), 1L, "the first still runs");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), job[1]), "cancelled the waiting one");
            helper.assertTrue(JobQueue.cancel(helper.getLevel(), job[0]), "cancelled the running one");
        }).thenWaitUntil(() -> {
            helper.assertValueEqual(out(helper, entity, 0, "waiting"), 0L, "none waits after the cancels");
            helper.assertValueEqual(out(helper, entity, 0, "running"), 0L, "nothing running after the cancel");
            helper.assertValueEqual(out(helper, entity, 0, "free_cpus"), 1L, "the CPU is free again");
        }).thenSucceed();
    }

    /** Plans eight sticks now and queues them for a player; fails the test on a refusal. */
    private static UUID submitSticks(GameTestHelper helper, NetworkState network) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STICK, 8);
        Optional<CraftingJob> submitted = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID())).job();
        return submitted.orElseThrow(() -> helper.assertionException(Component.literal("job refused"))).id();
    }

    /**
     * A CPU on a subnet that shares with its parent can take the parent's jobs, so a crafting status on the parent
     * counts it as free (as the job queue would use it).
     */
    private static void craftStatusLinkedCpu(GameTestHelper helper) {
        BlockPos controller = NetworkGameTests.controller(helper, 1);
        helper.setBlock(NetworkGameTests.at(2), LogicContent.CONTROLLER.get());
        NetworkGameTests.device(helper, new BlockPos(2, 1, 2));
        SubnetBlockEntity subnet = SubnetGameTests.subnet(helper, NetworkGameTests.at(3), Direction.WEST);
        helper.setBlock(NetworkGameTests.at(4), CpuContent.CORE_1.get());
        LogicControllerBlockEntity entity = LogicControllerGameTests.entity(helper, NetworkGameTests.at(2));
        entity.setOwner(OwnerRecord.of(NetworkGameTests.player(helper.getLevel(), "lr_linked")));
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState parent = NetworkGameTests.state(helper, controller);
            helper.assertTrue(subnet.parent() == parent, "uplink in the parent");
        }).thenExecute(() -> {
            NetworkGameTests.power(helper, controller, 1_000_000);
            subnet.buffer().receive(1_000_000);
            entity.worker().setGraph(0, "Any", graph(NodeTypes.CRAFT_STATUS, ""));
        }).thenWaitUntil(() -> {
            ran(helper, entity, 1);
            helper.assertValueEqual(out(helper, entity, 0, "free_cpus"), 1L, "the subnet's CPU");
        }).thenSucceed();
    }

    private static CraftingPattern crafting(Item output, int count, Item input, long amount) {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(ItemKey.of(input)), amount, Optional.empty(),
                        false)),
                List.of(new PatternOutput(ItemKey.of(output), count, 1f)), Optional.empty(), Optional.empty());
    }

    private static LogicGraph<LogicKey> graph(NodeType type, String label) {
        return new LogicControllerGameTests.Graph().node(1, type, s -> s.withText("label", label)).build();
    }

    private static LogicGraph<LogicKey> graph(NodeType type, String setting, LogicKey key) {
        return new LogicControllerGameTests.Graph().node(1, type, s -> s.withItem(setting, key)).build();
    }
}
