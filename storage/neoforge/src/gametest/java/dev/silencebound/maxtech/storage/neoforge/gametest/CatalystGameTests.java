package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.job.JobState;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternReturns;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.plan.PlanTree;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Catalysts and byproducts (M7C item 3) through real providers and a test machine: a byproduct that comes out after the
 * main output is still pulled into storage, the planner needs a catalyst the recipe also lists as an output only once,
 * and full jobs return a catalyst that comes out of the machine and one that stays in it. A powered controller at x =
 * 1, a test storage device at x = 2 with a CPU above it, the provider at x = 3 facing east and the machine at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CatalystGameTests {
    private static final String[] NAMES = {"catalyst_byproduct_pulled", "catalyst_planned_once",
            "catalyst_job_through_output", "catalyst_job_kept_in_machine", "catalyst_detection",
            "catalyst_eaten_by_machine", "catalyst_returns_saved", "catalyst_chance_byproduct_not_kept",
            "catalyst_shared_machine"};
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);
    private static final StackKey SHEARS = ItemKey.of(Items.SHEARS);
    private static final StackKey COBBLE = ItemKey.of(Items.COBBLESTONE);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CatalystTestContent.init();
        register(NAMES[0], CatalystGameTests::byproductPulled);
        register(NAMES[1], CatalystGameTests::plannedOnce);
        register(NAMES[2], CatalystGameTests::throughOutput);
        register(NAMES[3], CatalystGameTests::keptInMachine);
        register(NAMES[4], CatalystGameTests::detection);
        register(NAMES[5], CatalystGameTests::eatenByMachine);
        register(NAMES[6], CatalystGameTests::returnsSaved);
        register(NAMES[7], CatalystGameTests::chanceByproductNotKept);
        register(NAMES[8], CatalystGameTests::sharedMachine);
    }

    private CatalystGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("catalyst"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 1200);
        }
    }

    private static PatternInput in(StackKey key, boolean returned) {
        return new PatternInput(new PatternIngredient.Exact(key), 1, Optional.empty(), returned);
    }

    private static PatternOutput out(StackKey key) {
        return new PatternOutput(key, 1, 1f);
    }

    private static CraftingPattern processing(List<PatternInput> inputs, List<PatternOutput> outputs) {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING, inputs, outputs, Optional.empty(),
                Optional.empty());
    }

    /** Iron from raw iron with shears the recipe lists on both sides, as a recipe viewer shows a catalyst. */
    private static CraftingPattern listedCatalyst() {
        return processing(List.of(in(RAW_IRON, false), in(SHEARS, false)), List.of(out(IRON), out(SHEARS)));
    }

    /** Controller, storage device, a CPU, the provider with {@code pattern} and the test machine. */
    private static PatternProviderBlockEntity setUp(GameTestHelper helper, CraftingPattern pattern) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        helper.setBlock(NetworkGameTests.at(4), CatalystTestContent.ITEM_MACHINE.get());
        BlockPos pos = NetworkGameTests.at(3);
        helper.setBlock(pos, ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING,
                Direction.EAST));
        PatternProviderBlockEntity provider = helper.getBlockEntity(pos, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(pattern));
        return provider;
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider) {
        NetworkState network = provider.network();
        if (network == null || network.patterns().providerCount() == 0) {
            throw helper.assertionException(Component.literal("the provider is in no network"));
        }
        return network;
    }

    private static CatalystTestContent.ItemMachine machine(GameTestHelper helper) {
        return helper.getBlockEntity(NetworkGameTests.at(4), CatalystTestContent.ItemMachine.class);
    }

    private static JobState state(GameTestHelper helper, UUID id) {
        CraftingJob job = CraftingJobs.get(helper.getLevel()).get(id);
        return job == null ? JobState.DONE : job.state();
    }

    private static UUID submit(GameTestHelper helper, NetworkState network, StackKey key, long amount) {
        CraftingPlanner.Result plan = CraftingPlanner.planNow(network, key, amount);
        helper.assertTrue(plan.tree().complete(), "complete plan: " + plan.tree().missing());
        JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                JobQueue.Request.player(UUID.randomUUID()));
        return submission.job()
                .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                .id();
    }

    /**
     * Two crafts making iron and cobblestone: the machine puts the main output in an earlier slot than the byproduct,
     * so the batch ends during the pull pass, and the cobblestone must still go to storage.
     */
    private static void byproductPulled(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper,
                processing(List.of(in(RAW_IRON, false)), List.of(out(IRON), out(COBBLE))));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            Object2LongOpenHashMap<StackKey> inputs = new Object2LongOpenHashMap<>();
            inputs.put(RAW_IRON, 2);
            CraftingPattern pattern = provider.logic().patterns().getFirst();
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider), pattern, 2, inputs,
                    DispatchStrategy.FASTEST), 2, "two crafts handed over");
            machine(helper).process(Items.RAW_IRON, null, true, Items.IRON_INGOT, Items.COBBLESTONE);
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 2L, "iron in storage");
            helper.assertValueEqual(network.contents().amount(COBBLE), 2L, "the byproduct in storage");
            helper.assertTrue(machine(helper).empty(), "the machine is empty");
        }).thenSucceed();
    }

    /**
     * Eight iron need the listed catalyst once: the plan takes one pair of shears and is complete.
     */
    private static void plannedOnce(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, listedCatalyst());
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            NetworkGameTests.insert(network, RAW_IRON, 8);
            NetworkGameTests.insert(network, SHEARS, 1);
            PlanTree<StackKey> tree = CraftingPlanner.planNow(network, IRON, 8).tree();
            helper.assertTrue(tree.complete(), "complete with one pair: " + tree.missing());
            helper.assertValueEqual(tree.used().getLong(SHEARS), 1L, "the shears once");
            helper.assertValueEqual(tree.used().getLong(RAW_IRON), 8L, "raw iron per craft");
        }).thenSucceed();
    }

    /**
     * A full job of three iron with one pair of shears that the machine uses up and hands back with each craft: the job
     * sends one craft at a time, takes the shears back each time and ends with the shears in storage.
     */
    private static void throughOutput(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, listedCatalyst());
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            NetworkGameTests.insert(network, RAW_IRON, 3);
            NetworkGameTests.insert(network, SHEARS, 1);
            id[0] = submit(helper, network, IRON, 3);
        }).thenWaitUntil(() -> {
            machine(helper).process(Items.RAW_IRON, Items.SHEARS, true, Items.IRON_INGOT, Items.SHEARS);
            helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "job done");
        }).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 3L, "three iron");
            helper.assertValueEqual(network.contents().amount(SHEARS), 1L, "the shears back");
            helper.assertValueEqual(network.contents().amount(RAW_IRON), 0L, "raw iron used");
            helper.assertTrue(machine(helper).empty(), "the machine is empty");
        }).thenSucceed();
    }

    /**
     * A full job of three iron with shears marked returned that stay in the machine, as a mould does: after each craft
     * the provider takes them back from the face they went into, and they end in storage.
     */
    private static void keptInMachine(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper,
                processing(List.of(in(RAW_IRON, false), in(SHEARS, true)), List.of(out(IRON))));
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            NetworkGameTests.insert(network, RAW_IRON, 3);
            NetworkGameTests.insert(network, SHEARS, 1);
            id[0] = submit(helper, network, IRON, 3);
        }).thenWaitUntil(() -> {
            machine(helper).process(Items.RAW_IRON, Items.SHEARS, false, Items.IRON_INGOT, null);
            helper.assertValueEqual(state(helper, id[0]), JobState.DONE, "job done");
        }).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 3L, "three iron");
            helper.assertValueEqual(network.contents().amount(SHEARS), 1L, "the shears back");
            helper.assertTrue(machine(helper).empty(), "the machine is empty");
            helper.assertTrue(Objects.requireNonNull(network).contents().amount(RAW_IRON) == 0, "raw iron used");
        }).thenSucceed();
    }

    /**
     * Which inputs count as returned: marked ones; an exact input listed as a guaranteed byproduct of the same key in
     * at least its amount; not a tag input that accepts another byproduct, a chance or smaller byproduct, nor in a
     * crafting pattern.
     */
    private static void detection(GameTestHelper helper) {
        PatternInput tagged = new PatternInput(new PatternIngredient.ItemTag(ItemTags.LOGS), 1, Optional.empty(),
                false);
        CraftingPattern tag = processing(List.of(in(RAW_IRON, false), tagged),
                List.of(out(IRON), out(ItemKey.of(Items.BIRCH_LOG))));
        CraftingPattern chance = processing(List.of(in(RAW_IRON, false), in(SHEARS, false)),
                List.of(out(IRON), new PatternOutput(SHEARS, 1, 0.5f)));
        CraftingPattern smaller = processing(
                List.of(in(RAW_IRON, false),
                        new PatternInput(new PatternIngredient.Exact(SHEARS), 2, Optional.empty(), false)),
                List.of(out(IRON), out(SHEARS)));
        CraftingPattern crafting = new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(in(RAW_IRON, false), in(SHEARS, false)), List.of(out(IRON), out(SHEARS)), Optional.empty(),
                Optional.empty());
        CraftingPattern marked = processing(List.of(in(RAW_IRON, false), in(SHEARS, true)), List.of(out(IRON)));
        helper.startSequence().thenExecute(() -> {
            helper.assertTrue(PatternReturns.returned(listedCatalyst(), 1), "listed catalyst");
            helper.assertValueEqual(PatternReturns.catalystOutput(listedCatalyst(), 1), 1, "its output");
            helper.assertFalse(PatternReturns.returned(listedCatalyst(), 0), "raw iron is used up");
            helper.assertFalse(PatternReturns.returned(tag, 1), "a tag input is no catalyst");
            helper.assertFalse(PatternReturns.returned(chance, 1), "a chance byproduct");
            helper.assertFalse(PatternReturns.returned(smaller, 1), "a smaller byproduct");
            helper.assertFalse(PatternReturns.returned(crafting, 1), "a crafting pattern");
            helper.assertTrue(PatternReturns.returned(marked, 1), "marked returned");
            helper.assertTrue(PatternReturns.any(marked) && !PatternReturns.any(tag), "any");
        }).thenSucceed();
    }

    /**
     * The machine uses up the listed catalyst instead of handing it back: the job waits a while for it, then stops
     * partial; nothing is made out of thin air.
     */
    private static void eatenByMachine(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper, listedCatalyst());
        UUID[] id = new UUID[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            NetworkGameTests.insert(network, RAW_IRON, 2);
            NetworkGameTests.insert(network, SHEARS, 1);
            id[0] = submit(helper, network, IRON, 2);
        }).thenWaitUntil(() -> {
            machine(helper).process(Items.RAW_IRON, Items.SHEARS, true, Items.IRON_INGOT, null);
            helper.assertValueEqual(state(helper, id[0]), JobState.PARTIAL, "stopped partial");
        }).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 1L, "one iron");
            helper.assertValueEqual(network.contents().amount(SHEARS), 0L, "the shears are gone");
            helper.assertValueEqual(machine(helper).count(Items.SHEARS), 0, "none in the machine");
        }).thenSucceed();
    }

    /** A returned input still owed survives saving and loading the provider. */
    private static void returnsSaved(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper,
                processing(List.of(in(RAW_IRON, false), in(SHEARS, true)), List.of(out(IRON))));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            Object2LongOpenHashMap<StackKey> inputs = new Object2LongOpenHashMap<>();
            inputs.put(RAW_IRON, 1);
            inputs.put(SHEARS, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider),
                    provider.logic().patterns().getFirst(), 1, inputs, DispatchStrategy.FASTEST), 1, "one craft in");
            helper.assertValueEqual(provider.logic().returnOwed(SHEARS), 1L, "the shears owed");
            CompoundTag saved = provider.saveWithoutMetadata(helper.getLevel().registryAccess());
            PatternProviderBlockEntity copy = new PatternProviderBlockEntity(provider.getBlockPos(),
                    provider.getBlockState());
            copy.loadWithComponents(
                    TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertValueEqual(copy.logic().returnOwed(SHEARS), 1L, "owed after a reload");
        }).thenSucceed();
    }

    /**
     * A chance byproduct that did not come is not waited for: cobblestone put into the machine after the batch is left
     * there.
     */
    private static void chanceByproductNotKept(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper,
                processing(List.of(in(RAW_IRON, false)), List.of(out(IRON), new PatternOutput(COBBLE, 1, 0.5f))));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            Object2LongOpenHashMap<StackKey> inputs = new Object2LongOpenHashMap<>();
            inputs.put(RAW_IRON, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider),
                    provider.logic().patterns().getFirst(), 1, inputs, DispatchStrategy.FASTEST), 1, "one craft in");
            machine(helper).process(Items.RAW_IRON, null, true, Items.IRON_INGOT, null);
        }).thenWaitUntil(
                () -> helper.assertValueEqual(network(helper, provider).contents().amount(IRON), 1L, "the iron in"))
                .thenExecute(() -> {
                    for (int i = 0; i < 3; i++) {
                        machine(helper).addSecond(Items.COBBLESTONE);
                    }
                }).thenIdle(80).thenExecute(() -> {
                    helper.assertValueEqual(machine(helper).count(Items.COBBLESTONE), 3, "left in the machine");
                    helper.assertValueEqual(network(helper, provider).contents().amount(COBBLE), 0L, "none taken");
                }).thenSucceed();
    }

    /**
     * Five cobblestone from elsewhere sit in the machine's byproduct slot: two crafts take their two and leave the
     * five, also after the batch.
     */
    private static void sharedMachine(GameTestHelper helper) {
        PatternProviderBlockEntity provider = setUp(helper,
                processing(List.of(in(RAW_IRON, false)), List.of(out(IRON), out(COBBLE))));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            for (int i = 0; i < 5; i++) {
                machine(helper).addSecond(Items.COBBLESTONE);
            }
            Object2LongOpenHashMap<StackKey> inputs = new Object2LongOpenHashMap<>();
            inputs.put(RAW_IRON, 2);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider),
                    provider.logic().patterns().getFirst(), 2, inputs, DispatchStrategy.FASTEST), 2, "two crafts in");
            machine(helper).process(Items.RAW_IRON, null, true, Items.IRON_INGOT, Items.COBBLESTONE);
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 2L, "two iron");
            helper.assertValueEqual(network.contents().amount(COBBLE), 2L, "the batch's two cobblestone");
        }).thenIdle(80).thenExecute(() -> {
            helper.assertValueEqual(machine(helper).count(Items.COBBLESTONE), 5, "the other five left");
            helper.assertValueEqual(network(helper, provider).contents().amount(COBBLE), 2L, "still two");
        }).thenSucceed();
    }
}
