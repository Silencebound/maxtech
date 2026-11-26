package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorContent;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlock;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.generator.GeneratorSettings;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.crafting.request.CraftingSession;
import dev.silencebound.maxtech.storage.crafting.request.RequestPayloads;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Craft by redstone (M7C item 11): a generator provider set to cobblestone faces a chest that stands in for the machine
 * (the test fills it once the signal is on). A plan step for cobblestone turns the signal on, collects exactly what the
 * job needs and turns it off; nothing arriving names the generator as stuck in the job view; storage that takes only
 * part keeps the rest in the machine, never more and never lost. A controller at x = 1, a test storage device at x = 2
 * with a CPU above, the generator at x = 3 facing east and the chest at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class GeneratorGameTests {
    private static final String[] NAMES = {"generator_plan_step_collects", "generator_nothing_arrived",
            "generator_full_storage_no_dupe", "generator_switched_off_and_removed", "generator_cancel_mid_run",
            "generator_save_load_order", "generator_mixed_plan", "generator_drops_ended_orders",
            "generator_priority_pattern_wins", "generator_priority_generator_wins", "generator_priority_tie",
            "generator_priority_saved"};
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey COAL = ItemKey.of(Items.COAL);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        register(NAMES[0], GeneratorGameTests::planStepCollects);
        register(NAMES[1], GeneratorGameTests::nothingArrived);
        register(NAMES[2], GeneratorGameTests::fullStorageNoDupe);
        register(NAMES[3], GeneratorGameTests::switchedOffAndRemoved);
        register(NAMES[4], GeneratorGameTests::cancelMidRun);
        register(NAMES[5], GeneratorGameTests::saveLoadOrder);
        register(NAMES[6], GeneratorGameTests::mixedPlan);
        register(NAMES[7], GeneratorGameTests::dropsEndedOrders);
        register(NAMES[8], helper -> priorityOrder(helper, 5, 0, false));
        register(NAMES[9], helper -> priorityOrder(helper, 5, 9, true));
        register(NAMES[10], helper -> priorityOrder(helper, 5, 5, true));
        register(NAMES[11], GeneratorGameTests::prioritySaved);
    }

    private GeneratorGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("generator"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 600);
        }
    }

    /** Controller with FE, storage, a CPU when asked, the generator set to cobblestone and the empty chest. */
    private static GeneratorProviderBlockEntity setUp(GameTestHelper helper, boolean cpu) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        if (cpu) {
            helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        }
        helper.setBlock(NetworkGameTests.at(4), Blocks.CHEST);
        helper.setBlock(NetworkGameTests.at(3), GeneratorContent.GENERATOR.get().defaultBlockState()
                .setValue(GeneratorProviderBlock.FACING, Direction.EAST));
        GeneratorProviderBlockEntity generator = helper.getBlockEntity(NetworkGameTests.at(3),
                GeneratorProviderBlockEntity.class);
        generator.logic().setSettings(GeneratorSettings.DEFAULT.withOutput(Optional.of(COBBLE)));
        return generator;
    }

    private static NetworkState network(GameTestHelper helper, GeneratorProviderBlockEntity generator) {
        NetworkState network = generator.network();
        if (network == null || !network.patterns().isCraftable(COBBLE)) {
            throw helper.assertionException(Component.literal("the generator is in no network"));
        }
        return network;
    }

    private static TestStorage.TestDevice storage(GameTestHelper helper) {
        return helper.getBlockEntity(NetworkGameTests.at(2), TestStorage.TestDevice.class);
    }

    private static ChestBlockEntity chest(GameTestHelper helper) {
        return helper.getBlockEntity(NetworkGameTests.at(4), ChestBlockEntity.class);
    }

    /** Whether the chest gets a redstone signal from any side; only the generator gives one. */
    private static boolean signal(GameTestHelper helper) {
        return helper.getLevel().hasNeighborSignal(helper.absolutePos(NetworkGameTests.at(4)));
    }

    private static int cobble(ChestBlockEntity chest) {
        int count = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(Items.COBBLESTONE)) {
                count += chest.getItem(i).getCount();
            }
        }
        return count;
    }

    private static UUID submit(NetworkState network, long amount) {
        return submit(network, COBBLE, amount);
    }

    private static UUID submit(NetworkState network, StackKey key, long amount) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
        return JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID())).job().orElseThrow().id();
    }

    /**
     * The plan makes cobblestone with a generator step and nothing missing; the job turns the signal on, the "machine"
     * makes a stack, the generator takes exactly the ten the job needs and turns the signal off, and the job finishes
     * with them in storage.
     */
    private static void planStepCollects(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, generator)).thenExecute(() -> {
            NetworkState network = network(helper, generator);
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, COBBLE, 10);
            helper.assertTrue(plan.tree().complete(), "nothing missing: " + plan.tree().missing());
            PlanTree.Step<StackKey> root = plan.tree().steps().getFirst();
            helper.assertValueEqual(root.pattern(), PlanTree.GENERATOR, "made by the generator");
            helper.assertValueEqual(root.crafts(), 10L, "ten units");
            helper.assertFalse(signal(helper), "signal off before the job");
            id[0] = submit(network, 10);
        }).thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the job")).thenExecute(() -> {
            helper.assertValueEqual(generator.logic().remaining(), 10L, "ten owed");
            chest(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        }).thenWaitUntil(
                () -> helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(id[0]) == null, "the job finished"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, generator);
                    helper.assertValueEqual(cobble(chest(helper)), 54, "exactly ten taken from the machine");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "ten in storage");
                    helper.assertValueEqual(generator.logic().remaining(), 0L, "nothing owed");
                    helper.assertTrue(generator.logic().ticksPerThousand() > 0, "a rate was measured");
                }).thenWaitUntil(() -> helper.assertFalse(signal(helper), "signal off once enough arrived"))
                .thenSucceed();
    }

    /** The machine makes nothing: after the timeout the job view names the generator, nothing arrived. */
    private static void nothingArrived(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, generator))
                .thenExecute(() -> id[0] = submit(network(helper, generator), 4))
                .thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the job")).thenWaitUntil(() -> {
                    NetworkState network = network(helper, generator);
                    CraftingJob job = Objects.requireNonNull(CraftingJobs.get(helper.getLevel()).get(id[0]));
                    List<RequestPayloads.Stuck> stuck = CraftingSession.stuck(network, job,
                            JobQueue.progress(helper.getLevel(), id[0]));
                    helper.assertValueEqual(stuck.size(), 1, "one stuck generator");
                    helper.assertValueEqual(stuck.getFirst().reason(), StuckReason.NOTHING_ARRIVED, "why");
                    helper.assertValueEqual(stuck.getFirst().pos(), helper.absolutePos(NetworkGameTests.at(3)),
                            "where");
                    helper.assertValueEqual(stuck.getFirst().key(), COBBLE, "the held-up step");
                }).thenExecute(() -> JobQueue.cancel(helper.getLevel(), id[0])).thenSucceed();
    }

    /**
     * Storage with room for three: the generator moves three, the rest stays in the machine, it says the output is
     * blocked and keeps the signal on; with room again it collects the other seven and switches off. Machine plus
     * storage always hold the stack the machine made.
     */
    private static void fullStorageNoDupe(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, false);
        helper.startSequence().thenWaitUntil(() -> network(helper, generator)).thenExecute(() -> {
            // Storage with room for exactly three more, filled behind the network's back (no FE spent).
            storage(helper).provider.setDirect(STONE, TestStorage.DEVICE_CAPACITY - 3);
            chest(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            helper.assertValueEqual(generator.logic().order(UUID.randomUUID(), 10), 10L, "order taken");
        }).thenWaitUntil(() -> helper.assertTrue(generator.logic().stuck() == StuckReason.OUTPUT_BLOCKED,
                "output blocked: " + generator.logic().stuck())).thenExecute(() -> {
                    NetworkState network = network(helper, generator);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 3L, "three stored");
                    helper.assertValueEqual(cobble(chest(helper)), 61, "the rest stays in the machine");
                    helper.assertValueEqual(generator.logic().remaining(), 7L, "seven owed");
                    helper.assertTrue(signal(helper), "still on");
                    storage(helper).provider.setDirect(STONE, TestStorage.DEVICE_CAPACITY - 100);
                    generator.logic().targetChanged();
                }).thenWaitUntil(() -> helper.assertValueEqual(generator.logic().remaining(), 0L, "all arrived"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, generator);
                    helper.assertValueEqual(network.contents().amount(COBBLE), 10L, "ten stored");
                    helper.assertValueEqual(cobble(chest(helper)), 54, "exactly ten taken");
                    helper.assertTrue(generator.logic().stuck() == null, "no longer stuck");
                }).thenWaitUntil(() -> helper.assertFalse(signal(helper), "off once filled")).thenSucceed();
    }

    /**
     * Strength 7 reaches the machine; logic turning the generator off takes the signal away until it is on again; the
     * generator broken mid-job makes the job stop partial instead of waiting forever; its item keeps the settings.
     */
    private static void switchedOffAndRemoved(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        generator.logic().setSettings(generator.logic().settings().withStrength(7));
        BlockPos at = helper.absolutePos(NetworkGameTests.at(3));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, generator))
                .thenExecute(() -> id[0] = submit(network(helper, generator), 5))
                .thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the job")).thenExecute(() -> {
                    helper.assertValueEqual(helper.getLevel().getSignal(at, Direction.WEST), 7, "strength 7");
                    helper.assertValueEqual(helper.getLevel().getSignal(at, Direction.UP), 0, "only the machine");
                    GeneratorSettings kept = generator.collectComponents()
                            .getOrDefault(ModDataComponents.GENERATOR_SETTINGS.get(), GeneratorSettings.DEFAULT);
                    helper.assertValueEqual(kept, generator.logic().settings(), "the item keeps the settings");
                    generator.setSwitchedOff(true, null);
                }).thenWaitUntil(() -> helper.assertFalse(signal(helper), "off while switched off"))
                .thenExecute(() -> generator.setSwitchedOff(false, null))
                .thenWaitUntil(() -> helper.assertTrue(signal(helper), "on again"))
                .thenExecute(() -> helper.setBlock(NetworkGameTests.at(3), Blocks.AIR))
                .thenWaitUntil(() -> helper.assertValueEqual(
                        Objects.requireNonNull(CraftingJobs.get(helper.getLevel()).get(id[0])).state(),
                        JobState.PARTIAL, "the job stops partial without a generator"))
                .thenExecute(() -> JobQueue.cancel(helper.getLevel(), id[0])).thenSucceed();
    }

    /**
     * Cancelling a running job withdraws its order at once: the signal is off in the same tick, nothing is owed, and a
     * stack the machine makes afterwards stays in it.
     */
    private static void cancelMidRun(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, generator))
                .thenExecute(() -> id[0] = submit(network(helper, generator), 10))
                .thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the job")).thenExecute(() -> {
                    helper.assertValueEqual(generator.logic().owed(id[0]), 10L, "ten owed to the job");
                    helper.assertTrue(JobQueue.cancel(helper.getLevel(), id[0]), "cancelled");
                    helper.assertFalse(signal(helper), "signal off at once");
                    helper.assertValueEqual(generator.logic().remaining(), 0L, "nothing owed");
                    chest(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    generator.logic().targetChanged();
                }).thenIdle(40).thenExecute(() -> {
                    helper.assertValueEqual(cobble(chest(helper)), 64, "nothing pulled after the cancel");
                    helper.assertFalse(signal(helper), "still off");
                }).thenSucceed();
    }

    /**
     * An open order survives a save and load of the generator: the copy owes the same job the same amount, and a cancel
     * of that job withdraws it.
     */
    private static void saveLoadOrder(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, false);
        UUID job = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        helper.startSequence().thenWaitUntil(() -> network(helper, generator)).thenExecute(() -> {
            helper.assertValueEqual(generator.logic().order(job, 10), 10L, "order taken");
            helper.assertValueEqual(generator.logic().order(other, 4), 4L, "second order taken");
            CompoundTag saved = generator.saveWithoutMetadata(helper.getLevel().registryAccess());
            GeneratorProviderBlockEntity copy = new GeneratorProviderBlockEntity(generator.getBlockPos(),
                    generator.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(copy.logic().owed(job), 10L, "the job's order after a reload");
            helper.assertValueEqual(copy.logic().owed(other), 4L, "the other order after a reload");
            helper.assertValueEqual(copy.logic().remaining(), 14L, "everything owed");
            helper.assertValueEqual(copy.logic().settings(), generator.logic().settings(), "settings after a reload");
            copy.logic().cancel(job);
            helper.assertValueEqual(copy.logic().remaining(), 4L, "the cancelled job's order is gone");
        }).thenSucceed();
    }

    /**
     * One job with a generator step and a real pattern: stone smelted in a furnace from cobblestone the generator
     * collects. The plan uses both, and the job finishes with exactly the two stone it asked for.
     */
    private static void mixedPlan(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        BlockPos providerAt = NetworkGameTests.at(2).north();
        helper.setBlock(providerAt.north(), Blocks.FURNACE);
        helper.setBlock(providerAt, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerAt, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0,
                PatternItem.encode(new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(in(COBBLE), in(COAL)),
                        List.of(new PatternOutput(STONE, 1, 1f)), Optional.empty(), Optional.empty())));
        // Two real cobblestone patterns, one with a higher priority than the generator: the priority order runs for
        // the key with the generator in it, and the gravel pattern, short of gravel, falls back to the generator.
        ItemStack fromGravel = PatternItem.encode(cobbleFrom(ItemKey.of(Items.GRAVEL)));
        PatternItem.setPriority(fromGravel, 9);
        provider.logic().slots().setItem(1, fromGravel);
        provider.logic().slots().setItem(2, PatternItem.encode(cobbleFrom(ItemKey.of(Items.ANDESITE))));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> {
            NetworkState network = network(helper, generator);
            helper.assertTrue(network.patterns().isCraftable(STONE), "the stone pattern is on the network");
        }).thenExecute(() -> {
            NetworkState network = network(helper, generator);
            helper.assertValueEqual(NetworkGameTests.insert(network, COAL, 2), 2L, "coal stored");
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, STONE, 2);
            helper.assertTrue(plan.tree().complete(), "nothing missing: " + plan.tree().missing());
            boolean generated = false;
            boolean patterned = false;
            for (PlanTree.Step<StackKey> step : plan.tree().steps()) {
                generated |= step.pattern() == PlanTree.GENERATOR && step.key().equals(COBBLE);
                patterned |= step.pattern() >= 0 && step.key().equals(STONE);
            }
            helper.assertTrue(generated && patterned, "a generator step and a pattern step");
            id[0] = submit(network, STONE, 2);
        }).thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the cobblestone"))
                .thenExecute(() -> chest(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 64)))
                .thenWaitUntil(() -> helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(id[0]) == null,
                        "the job finished"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, generator);
                    helper.assertValueEqual(network.contents().amount(STONE), 2L, "two stone stored");
                    helper.assertValueEqual(cobble(chest(helper)), 62, "two cobblestone taken");
                    helper.assertValueEqual(network.contents().amount(COBBLE), 0L, "no cobblestone left over");
                }).thenWaitUntil(() -> helper.assertFalse(signal(helper), "signal off")).thenSucceed();
    }

    /**
     * A generator starting again (a chunk reload) keeps the order of a running job but drops one whose job is gone, as
     * when the job ended while the generator was unloaded and its cancel never reached it.
     */
    private static void dropsEndedOrders(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, true);
        UUID[] id = new UUID[1];
        UUID gone = UUID.randomUUID();
        helper.startSequence().thenWaitUntil(() -> network(helper, generator))
                .thenExecute(() -> id[0] = submit(network(helper, generator), 10))
                .thenWaitUntil(() -> helper.assertTrue(signal(helper), "signal on for the job")).thenExecute(() -> {
                    helper.assertValueEqual(generator.logic().order(gone, 5), 5L, "an order for an ended job");
                    generator.logic().start(helper.getLevel());
                    helper.assertValueEqual(generator.logic().owed(gone), 0L, "the ended job's order is dropped");
                    helper.assertValueEqual(generator.logic().owed(id[0]), 10L, "the running job's order stays");
                    helper.assertValueEqual(generator.logic().remaining(), 10L, "only the running job is owed");
                }).thenExecute(() -> JobQueue.cancel(helper.getLevel(), id[0])).thenSucceed();
    }

    /**
     * A generator and a real cobblestone pattern (from gravel, which is stored) for the same output, with the given
     * priorities (owner decision 2026-10-10): the higher one makes the cobblestone, and on a tie the generator does.
     */
    private static void priorityOrder(GameTestHelper helper, int patternPriority, int generatorPriority,
            boolean generatorWins) {
        GeneratorProviderBlockEntity generator = setUp(helper, false);
        generator.setPriority(generatorPriority);
        BlockPos providerAt = NetworkGameTests.at(2).north();
        helper.setBlock(providerAt.north(), Blocks.CHEST);
        helper.setBlock(providerAt, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        PatternProviderBlockEntity provider = helper.getBlockEntity(providerAt, PatternProviderBlockEntity.class);
        ItemStack fromGravel = PatternItem.encode(cobbleFrom(ItemKey.of(Items.GRAVEL)));
        PatternItem.setPriority(fromGravel, patternPriority);
        provider.logic().slots().setItem(0, fromGravel);
        helper.startSequence()
                .thenWaitUntil(
                        () -> helper.assertFalse(network(helper, generator).patterns().patternsFor(COBBLE).isEmpty(),
                                "the pattern is on the network"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, generator);
                    helper.assertValueEqual(NetworkGameTests.insert(network, ItemKey.of(Items.GRAVEL), 4), 4L,
                            "gravel stored");
                    CraftingPlanner.Result plan = CraftingPlanner.planNow(network, COBBLE, 4);
                    helper.assertTrue(plan.tree().complete(), "nothing missing: " + plan.tree().missing());
                    int root = plan.tree().steps().getFirst().pattern();
                    if (generatorWins) {
                        helper.assertValueEqual(root, PlanTree.GENERATOR, "the generator makes it");
                    } else {
                        helper.assertTrue(root >= 0, "the pattern makes it, not " + root);
                    }
                }).thenSucceed();
    }

    /** The priority is kept by a save and load of the block and in the item it drops. */
    private static void prioritySaved(GameTestHelper helper) {
        GeneratorProviderBlockEntity generator = setUp(helper, false);
        generator.setPriority(-42);
        CompoundTag saved = generator.saveWithoutMetadata(helper.getLevel().registryAccess());
        GeneratorProviderBlockEntity copy = new GeneratorProviderBlockEntity(generator.getBlockPos(),
                generator.getBlockState());
        copy.loadWithComponents(
                TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        helper.assertValueEqual(copy.priority(), -42, "priority after a reload");
        GeneratorSettings kept = generator.collectComponents().getOrDefault(ModDataComponents.GENERATOR_SETTINGS.get(),
                GeneratorSettings.DEFAULT);
        helper.assertValueEqual(kept.priority(), -42, "priority in the dropped item");
        generator.setPriority(5_000);
        helper.assertValueEqual(generator.priority(), 999, "clamped to the shared range");
        helper.succeed();
    }

    private static CraftingPattern cobbleFrom(StackKey input) {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(in(input)),
                List.of(new PatternOutput(COBBLE, 1, 1f)), Optional.empty(), Optional.empty());
    }

    private static PatternInput in(StackKey key) {
        return new PatternInput(new PatternIngredient.Exact(key), 1, Optional.empty(), false);
    }
}
