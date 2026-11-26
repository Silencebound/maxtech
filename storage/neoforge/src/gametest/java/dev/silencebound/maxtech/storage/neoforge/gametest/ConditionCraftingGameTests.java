package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.condition.ConditionContent;
import dev.silencebound.maxtech.storage.controller.ControllerContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorDispatch;
import dev.silencebound.maxtech.storage.crafting.job.CraftingDemand;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.condition.ConditionSettings;
import dev.silencebound.maxtech.storage.condition.ConditionSwitchBlockEntity;
import dev.silencebound.maxtech.storage.condition.ConditionType;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlock;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorSettings;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.pattern.GeneratorSource;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Emit to craft (B09 item 14): the condition switch's crafting modes. A controller at x = 1, a test storage device at x
 * = 2 with a CPU above, switches at x = 3 and south of the storage, each with a lamp on top. "While crafting" is on
 * while a job makes its item; "request on signal" offers its item to the planner and stays on until a job's units have
 * arrived, which a fake machine (the test, inserting one unit per tick while the lamp is powered) supplies.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ConditionCraftingGameTests {
    private static final String[] NAMES = {"condition_craft_while_crafting", "condition_craft_request_provides",
            "condition_craft_request_cancel", "condition_craft_request_two_switches",
            "condition_craft_request_saved_and_copied", "condition_craft_request_reload_keeps_arrivals",
            "condition_craft_request_split", "condition_craft_request_removed", "condition_craft_request_two_networks"};
    private static final ItemKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final ItemKey ANDESITE = ItemKey.of(Items.ANDESITE);
    private static final BlockPos FIRST = NetworkGameTests.at(3);
    private static final BlockPos SECOND = NetworkGameTests.at(2).south();

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], ConditionCraftingGameTests::whileCrafting);
        register(NAMES[1], ConditionCraftingGameTests::requestProvides);
        register(NAMES[2], ConditionCraftingGameTests::requestCancel);
        register(NAMES[3], ConditionCraftingGameTests::twoSwitches);
        register(NAMES[4], ConditionCraftingGameTests::savedAndCopied);
        register(NAMES[5], ConditionCraftingGameTests::reloadKeepsArrivals);
        register(NAMES[6], ConditionCraftingGameTests::split);
        register(NAMES[7], ConditionCraftingGameTests::removed);
        register(NAMES[8], ConditionCraftingGameTests::twoNetworks);
    }

    private ConditionCraftingGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event
                .registerEnvironment(MaxtechStorage.id("condition_crafting"), StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 600);
        }
    }

    /** Controller with power, storage and a CPU above it. */
    private static void network(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
    }

    /** A switch at {@code pos} with a lamp on top, set to {@code type} for cobblestone. */
    private static ConditionSwitchBlockEntity placeSwitch(GameTestHelper helper, BlockPos pos, ConditionType type) {
        return placeSwitch(helper, pos, type, COBBLE);
    }

    private static ConditionSwitchBlockEntity placeSwitch(GameTestHelper helper, BlockPos pos, ConditionType type,
            ItemKey item) {
        helper.setBlock(pos, ConditionContent.SWITCH.get());
        helper.setBlock(pos.above(), Blocks.REDSTONE_LAMP);
        ConditionSwitchBlockEntity entity = helper.getBlockEntity(pos, ConditionSwitchBlockEntity.class);
        entity.worker().setSettings(ConditionSettings.DEFAULT.withType(type).withItem(Optional.of(item)));
        return entity;
    }

    private static NetworkState network(GameTestHelper helper, ConditionSwitchBlockEntity entity) {
        NetworkState network = entity.network();
        if (network == null) {
            throw helper.assertionException(Component.literal("the switch is in no network"));
        }
        return network;
    }

    /** Whether the lamp on the switch at {@code pos} gets a redstone signal: what a machine there would see. */
    private static boolean powered(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().hasNeighborSignal(helper.absolutePos(pos.above()));
    }

    private static UUID submit(NetworkState network, StackKey key, long amount) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
        return JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID())).job().orElseThrow().id();
    }

    private static boolean ended(GameTestHelper helper, UUID job) {
        return CraftingJobs.get(helper.getLevel()).get(job) == null;
    }

    /**
     * A fake machine on redstone: each tick it makes one cobblestone into the network for every listed switch whose
     * lamp is powered. Returns the counter of units made.
     */
    private static long[] machine(GameTestHelper helper, ConditionSwitchBlockEntity entity, List<BlockPos> switches) {
        long[] made = new long[1];
        helper.onEachTick(() -> {
            NetworkState network = entity.network();
            for (BlockPos pos : switches) {
                if (network != null && powered(helper, pos) && NetworkGameTests.insert(network, COBBLE, 1) == 1) {
                    made[0]++;
                }
            }
        });
        return made;
    }

    /**
     * A generator provider makes andesite for a job; a switch in "while crafting" mode lights its lamp while the job
     * runs, does not poll while it waits, and goes off once the andesite arrived and the job ended. Andesite, because
     * the other tests' jobs in the same level make cobblestone, and a job for the switch's item wakes it.
     */
    private static void whileCrafting(GameTestHelper helper) {
        network(helper);
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        helper.setBlock(FIRST, GeneratorContent.GENERATOR.get().defaultBlockState()
                .setValue(GeneratorProviderBlock.FACING, Direction.EAST));
        GeneratorProviderBlockEntity generator = helper.getBlockEntity(FIRST, GeneratorProviderBlockEntity.class);
        generator.logic().setSettings(GeneratorSettings.DEFAULT.withOutput(Optional.of(ANDESITE)));
        ConditionSwitchBlockEntity entity = placeSwitch(helper, SECOND, ConditionType.WHILE_CRAFTING, ANDESITE);
        UUID[] id = new UUID[1];
        int[] evaluations = new int[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenIdle(10).thenExecute(() -> {
            helper.assertFalse(powered(helper, SECOND), "on before any job");
            id[0] = submit(network(helper, entity), ANDESITE, 10);
        }).thenWaitUntil(() -> helper.assertTrue(powered(helper, SECOND), "on while the job makes andesite"))
                .thenIdle(20).thenExecute(() -> evaluations[0] = entity.worker().evaluations()).thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().evaluations(), evaluations[0], "no polling while waiting");
                    helper.assertTrue(powered(helper, SECOND), "still on");
                    ChestBlockEntity chest = helper.getBlockEntity(NetworkGameTests.at(4), ChestBlockEntity.class);
                    chest.setItem(0, new ItemStack(Items.ANDESITE, 64));
                    generator.logic().targetChanged();
                }).thenWaitUntil(() -> helper.assertTrue(ended(helper, id[0]), "the job finished"))
                .thenWaitUntil(() -> helper.assertFalse(powered(helper, SECOND), "off once the job is done"))
                .thenSucceed();
    }

    /**
     * The switch offers cobblestone: the plan makes it with a generator step, the switch turns on for the job, the fake
     * machine on its signal makes it, the job finishes and the signal drops. Every unit made is in storage once: none
     * counted twice, none lost.
     */
    private static void requestProvides(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity entity = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        long[] made = machine(helper, entity, List.of(FIRST));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            NetworkState network = network(helper, entity);
            helper.assertTrue(network.patterns().isCraftable(COBBLE), "cobblestone shows as craftable");
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, COBBLE, 10);
            helper.assertTrue(plan.tree().complete(), "nothing missing: " + plan.tree().missing());
            helper.assertValueEqual(plan.tree().steps().getFirst().pattern(), PlanTree.GENERATOR, "made on request");
            helper.assertFalse(powered(helper, FIRST), "on before the job");
            id[0] = submit(network, COBBLE, 10);
        }).thenWaitUntil(() -> helper.assertTrue(ended(helper, id[0]), "the job finished"))
                .thenWaitUntil(() -> helper.assertFalse(powered(helper, FIRST), "off once enough arrived"))
                .thenExecute(() -> {
                    helper.assertTrue(made[0] >= 10, "the machine made what the job needed: " + made[0]);
                    helper.assertValueEqual(entity.worker().requested(), 0L, "nothing owed");
                    helper.assertValueEqual(network(helper, entity).contents().amount(COBBLE), made[0],
                            "every unit made is in storage once");
                }).thenSucceed();
    }

    /** Cancelling the job drops the signal; cobblestone arriving afterwards does not turn it on again. */
    private static void requestCancel(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity entity = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> id[0] = submit(network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertTrue(powered(helper, FIRST), "on for the job")).thenExecute(() -> {
                    helper.assertValueEqual(entity.worker().requested(), 10L, "ten requested");
                    helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancelled");
                    helper.assertValueEqual(entity.worker().requested(), 0L, "the order is withdrawn at once");
                }).thenWaitUntil(() -> helper.assertFalse(powered(helper, FIRST), "off after the cancel"))
                .thenExecute(() -> NetworkGameTests.insert(network(helper, entity), COBBLE, 5)).thenIdle(20)
                .thenExecute(() -> {
                    helper.assertFalse(powered(helper, FIRST), "on again after the cancel");
                    helper.assertValueEqual(network(helper, entity).contents().amount(COBBLE), 5L,
                            "what arrives later stays in storage");
                }).thenSucceed();
    }

    /**
     * Two switches for cobblestone and two jobs: each switch takes one job's order and turns on; with both machines
     * making it, both jobs finish, both signals drop and every unit made is in storage once.
     */
    private static void twoSwitches(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity first = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        ConditionSwitchBlockEntity second = placeSwitch(helper, SECOND, ConditionType.CRAFT_REQUEST);
        // A second CPU, so both jobs run at once.
        helper.setBlock(NetworkGameTests.at(2).north(), CpuContent.CORE_1.get());
        UUID[] ids = new UUID[2];
        long[][] made = new long[1][];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, first);
            network(helper, second);
        }).thenExecute(() -> {
            NetworkState network = network(helper, first);
            ids[0] = submit(network, COBBLE, 6);
            ids[1] = submit(network, COBBLE, 6);
        }).thenWaitUntil(() -> {
            helper.assertTrue(powered(helper, FIRST), "first switch on");
            helper.assertTrue(powered(helper, SECOND), "second switch on");
        }).thenExecute(() -> {
            helper.assertValueEqual(first.worker().requested() + second.worker().requested(), 12L, "both orders");
            made[0] = machine(helper, first, List.of(FIRST, SECOND));
        }).thenWaitUntil(() -> {
            helper.assertTrue(ended(helper, ids[0]), "first job finished");
            helper.assertTrue(ended(helper, ids[1]), "second job finished");
        }).thenWaitUntil(() -> {
            helper.assertFalse(powered(helper, FIRST), "first switch off");
            helper.assertFalse(powered(helper, SECOND), "second switch off");
        }).thenExecute(() -> {
            helper.assertTrue(made[0][0] >= 12, "the machines made what the jobs needed: " + made[0][0]);
            helper.assertValueEqual(network(helper, first).contents().amount(COBBLE), made[0][0],
                    "every unit made is in storage once");
        }).thenSucceed();
    }

    /**
     * The new modes travel by their ids: the dropped block, a blueprint copy onto another switch, and an open order
     * through a save and load (withdrawn again by its job's cancel).
     */
    private static void savedAndCopied(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity entity = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        ConditionSwitchBlockEntity other = placeSwitch(helper, SECOND, ConditionType.ITEM_COUNT);
        UUID job = UUID.randomUUID();
        helper.startSequence().thenWaitUntil(() -> network(helper, entity)).thenExecute(() -> {
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(FIRST), helper.getLevel(),
                    helper.absolutePos(FIRST), entity);
            ConditionSettings carried = drops.getFirst().get(ModDataComponents.CONDITION.get());
            helper.assertTrue(entity.worker().settings().equals(carried), "the drop keeps the mode: " + carried);

            DeviceSettings copy = entity.copyableSettings();
            other.copyableSettings().paste().accept(copy.copy().get());
            helper.assertValueEqual(other.worker().settings().type(), ConditionType.CRAFT_REQUEST, "copied mode");
            helper.assertTrue(other.worker().settings().item().equals(Optional.of(COBBLE)), "copied item");

            helper.assertValueEqual(source(helper, entity).order(job, 7), 7L, "order taken");
            CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
            ConditionSwitchBlockEntity loaded = new ConditionSwitchBlockEntity(entity.getBlockPos(),
                    entity.getBlockState());
            loaded.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(loaded.worker().settings().type(), ConditionType.CRAFT_REQUEST, "loaded mode");
            helper.assertValueEqual(loaded.worker().requested(), 7L, "the order after a reload");
            source(helper, loaded).cancel(job);
            source(helper, entity).cancel(job);
            helper.assertValueEqual(loaded.worker().requested(), 0L, "the cancel withdraws the loaded order");
        }).thenSucceed();
    }

    private static boolean registered(GameTestHelper helper, ConditionSwitchBlockEntity entity) {
        return GeneratorDispatch.registered(helper.getLevel(), source(helper, entity));
    }

    /**
     * Units that arrived but were not settled yet survive a save, and a rebind (what a reload does) counts them: the
     * job needs ten, four arrive just before the save and rebind, six after, and it finishes.
     */
    private static void reloadKeepsArrivals(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity entity = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, entity))
                .thenExecute(() -> id[0] = submit(network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertTrue(powered(helper, FIRST), "on for the job")).thenExecute(() -> {
                    NetworkState network = network(helper, entity);
                    // Arrived in this tick: the switch settles them on its next run, after the save.
                    NetworkGameTests.insert(network, COBBLE, 4);
                    CompoundTag saved = entity.saveWithoutMetadata(helper.getLevel().registryAccess());
                    ConditionSwitchBlockEntity copy = new ConditionSwitchBlockEntity(entity.getBlockPos(),
                            entity.getBlockState());
                    copy.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            helper.getLevel().registryAccess(), saved));
                    helper.assertValueEqual(copy.worker().carried(), 4L, "unsettled arrivals in the save");
                    entity.networkChanged(network);
                }).thenIdle(10).thenExecute(() -> NetworkGameTests.insert(network(helper, entity), COBBLE, 6))
                .thenWaitUntil(() -> helper.assertTrue(ended(helper, id[0]), "the job finished with ten"))
                .thenWaitUntil(() -> helper.assertFalse(powered(helper, FIRST), "off once enough arrived"))
                .thenSucceed();
    }

    /**
     * The switch on its own cable branch is cut off from the job's network with an open order: job ends still reach it,
     * so cancelling the job withdraws the order and drops the signal, and it leaves the dispatch.
     */
    private static void split(GameTestHelper helper) {
        network(helper);
        NetworkGameTests.cables(helper, 3, 3, CableContent.STORAGE_CABLE_TIER_1.type().get());
        BlockPos at = NetworkGameTests.at(4);
        ConditionSwitchBlockEntity entity = placeSwitch(helper, at, ConditionType.CRAFT_REQUEST);
        UUID[] id = new UUID[1];
        NetworkState[] before = new NetworkState[1];
        helper.startSequence().thenWaitUntil(() -> before[0] = network(helper, entity))
                .thenExecute(() -> id[0] = submit(network(helper, entity), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertTrue(powered(helper, at), "on for the job")).thenExecute(() -> {
                    helper.assertTrue(registered(helper, entity), "job ends reach the switch");
                    helper.setBlock(NetworkGameTests.at(3), Blocks.AIR);
                }).thenWaitUntil(() -> helper.assertFalse(entity.network() == before[0], "cut off")).thenExecute(() -> {
                    // The job may already have stopped for want of a source; then its end withdrew the order.
                    if (entity.worker().requested() > 0) {
                        helper.assertTrue(registered(helper, entity), "job ends still reach it after the split");
                    }
                    JobQueue.cancel(helper.getLevel(), id[0]);
                    helper.assertValueEqual(entity.worker().requested(), 0L, "the order is withdrawn");
                    helper.assertFalse(registered(helper, entity), "left the dispatch with its last order");
                }).thenWaitUntil(() -> helper.assertFalse(powered(helper, at), "off after the cancel")).thenSucceed();
    }

    /**
     * The switch holding a job's order is broken: it leaves the dispatch at once, the job orders the units from the
     * other switch for the same item, and finishes with what that one's machine makes.
     */
    private static void removed(GameTestHelper helper) {
        network(helper);
        ConditionSwitchBlockEntity first = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        ConditionSwitchBlockEntity second = placeSwitch(helper, SECOND, ConditionType.CRAFT_REQUEST);
        UUID[] id = new UUID[1];
        long[][] made = new long[1][];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, first);
            network(helper, second);
        }).thenExecute(() -> id[0] = submit(network(helper, first), COBBLE, 10)).thenWaitUntil(() -> helper
                .assertTrue(powered(helper, FIRST) || powered(helper, SECOND), "a switch is on for the job"))
                .thenExecute(() -> {
                    boolean firstHolds = first.worker().requested() > 0;
                    ConditionSwitchBlockEntity holder = firstHolds ? first : second;
                    ConditionSwitchBlockEntity other = firstHolds ? second : first;
                    GeneratorSource gone = source(helper, holder);
                    helper.setBlock(firstHolds ? FIRST : SECOND, Blocks.AIR);
                    helper.assertFalse(GeneratorDispatch.registered(helper.getLevel(), gone),
                            "a broken switch stays in the dispatch");
                    made[0] = machine(helper, other, List.of(firstHolds ? SECOND : FIRST));
                }).thenWaitUntil(() -> helper.assertTrue(ended(helper, id[0]), "the job finished"))
                .thenExecute(() -> helper.assertTrue(made[0][0] >= 10, "the other switch's machine made it"))
                .thenSucceed();
    }

    /**
     * Two networks with a job for cobblestone each. A job on the second counts five units its storage does not hold
     * (they stay to be taken); ten arriving on the first are still all the first job's, so it finishes.
     */
    private static void twoNetworks(GameTestHelper helper) {
        network(helper);
        BlockPos core = NetworkGameTests.at(1).south(4);
        helper.setBlock(core, ControllerContent.CONTROLLER.get());
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2).south(4));
        helper.setBlock(NetworkGameTests.at(2).south(4).above(), CpuContent.CORE_1.get());
        BlockPos otherAt = NetworkGameTests.at(3).south(4);
        ConditionSwitchBlockEntity mine = placeSwitch(helper, FIRST, ConditionType.CRAFT_REQUEST);
        ConditionSwitchBlockEntity theirs = placeSwitch(helper, otherAt, ConditionType.CRAFT_REQUEST);
        UUID[] ids = new UUID[2];
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, mine);
            network(helper, theirs);
            helper.assertFalse(network(helper, mine) == network(helper, theirs), "two networks");
        }).thenExecute(() -> ids[0] = submit(network(helper, mine), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertTrue(powered(helper, FIRST), "first switch on"))
                .thenExecute(() -> ids[1] = submit(network(helper, theirs), COBBLE, 10))
                .thenWaitUntil(() -> helper.assertTrue(powered(helper, otherAt), "second switch on"))
                .thenExecute(() -> {
                    NetworkState other = network(helper, theirs);
                    CraftingDeliveries.delivered(other, COBBLE, 5);
                    helper.assertValueEqual(CraftingDemand.counted(other, COBBLE), 5L, "five counted, not taken");
                    helper.assertValueEqual(CraftingDemand.counted(network(helper, mine), COBBLE), 0L,
                            "none counted on the first network");
                    NetworkGameTests.insert(network(helper, mine), COBBLE, 10);
                }).thenWaitUntil(() -> helper.assertTrue(ended(helper, ids[0]), "the first job finished"))
                .thenExecute(() -> JobQueue.cancel(helper.getLevel(), ids[1])).thenSucceed();
    }

    private static GeneratorSource source(GameTestHelper helper, ConditionSwitchBlockEntity entity) {
        if (entity.patternProvider() instanceof GeneratorSource source) {
            return source;
        }
        throw helper.assertionException(Component.literal("the switch is no generator source"));
    }
}
