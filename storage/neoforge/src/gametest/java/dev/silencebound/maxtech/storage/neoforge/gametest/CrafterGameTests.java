package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerCore;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingAssemblerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.cpu.CpuContent;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterBlock;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterBlockEntity;
import dev.silencebound.maxtech.storage.crafting.crafter.CrafterContent;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJob;
import dev.silencebound.maxtech.storage.crafting.job.CraftingJobs;
import dev.silencebound.maxtech.storage.crafting.job.JobQueue;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.plan.CraftingPlanner;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderLogic;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.crafting.provider.StuckReason;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * The crafter (B09 item 29): jobs whose crafting, stonecutting and smithing steps it crafts from its own patterns, the
 * recipe-changed refusal, breaking it with patterns and results inside, the pattern priority deciding between it and a
 * provider with an assembler for the same pattern, and the synced item display on the crafter and the assembler. A
 * controller at x = 1, a storage device at x = 2 with a CPU above it and the crafter at x = 3; the provider sits north
 * of the storage device facing north, its assembler beyond it.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class CrafterGameTests {
    private static final String[] NAMES = {"crafter_crafting_job", "crafter_stonecutting_job", "crafter_smithing_job",
            "crafter_recipe_changed", "crafter_break_keeps_contents", "crafter_priority", "crafter_display",
            "crafter_priority_remainder", "crafter_priority_tie", "crafter_returns_leftovers",
            "crafter_leftovers_back_off"};
    private static final StackKey LOG = ItemKey.of(Items.OAK_LOG);
    private static final StackKey PLANKS = ItemKey.of(Items.OAK_PLANKS);
    private static final StackKey STONE = ItemKey.of(Items.STONE);
    private static final StackKey SLAB = ItemKey.of(Items.STONE_SLAB);
    private static final StackKey TEMPLATE = ItemKey.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
    private static final StackKey DIAMOND_SWORD = ItemKey.of(Items.DIAMOND_SWORD);
    private static final StackKey NETHERITE = ItemKey.of(Items.NETHERITE_INGOT);
    private static final StackKey NETHERITE_SWORD = ItemKey.of(Items.NETHERITE_SWORD);
    private static final BlockPos CRAFTER = NetworkGameTests.at(3);
    private static final BlockPos PROVIDER = NetworkGameTests.at(2).north();
    private static final BlockPos ASSEMBLER = NetworkGameTests.at(2).north(2);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        CraftingTestContent.init();
        register(NAMES[0], helper -> job(helper, planks(), PLANKS, 8, List.of(LOG), 2));
        register(NAMES[1],
                helper -> job(helper, PatternTableGameTests.stonecuttingPattern(), SLAB, 6, List.of(STONE), 3));
        register(NAMES[2], helper -> job(helper, PatternTableGameTests.smithingPattern(), NETHERITE_SWORD, 1,
                List.of(TEMPLATE, DIAMOND_SWORD, NETHERITE), 1));
        register(NAMES[3], CrafterGameTests::recipeChanged);
        register(NAMES[4], CrafterGameTests::breakKeepsContents);
        register(NAMES[5], CrafterGameTests::priority);
        register(NAMES[6], CrafterGameTests::display);
        register(NAMES[7], CrafterGameTests::priorityRemainder);
        register(NAMES[8], CrafterGameTests::priorityTie);
        register(NAMES[9], CrafterGameTests::returnsLeftovers);
        register(NAMES[10], CrafterGameTests::leftoversBackOff);
    }

    private CrafterGameTests() {
    }

    private static void register(String name, Consumer<GameTestHelper> test) {
        StorageTests.function(name, StorageTestEnvironment.clearing(test));
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("crafter"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            // The back-off test waits through the doubling retries up to the cap and then once more.
            int timeout = name.equals("crafter_leftovers_back_off") ? 900 : 400;
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), timeout);
        }
    }

    /** One oak log to four oak planks. */
    private static CraftingPattern planks() {
        return new CraftingPattern(CraftingPattern.Kind.CRAFTING,
                List.of(new PatternInput(new PatternIngredient.Exact(LOG), 1, Optional.empty(), false)),
                List.of(new PatternOutput(PLANKS, 4, 1f)), Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace("oak_planks")));
    }

    /** Controller, powered, the storage device and the crafter facing south, holding {@code patterns}. */
    private static CrafterBlockEntity setUp(GameTestHelper helper, List<CraftingPattern> patterns) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(CRAFTER,
                CrafterContent.CRAFTER.get().defaultBlockState().setValue(CrafterBlock.FACING, Direction.SOUTH));
        CrafterBlockEntity crafter = helper.getBlockEntity(CRAFTER, CrafterBlockEntity.class);
        for (int i = 0; i < patterns.size(); i++) {
            crafter.logic().slots().setItem(i, PatternItem.encode(patterns.get(i)));
        }
        return crafter;
    }

    private static NetworkState network(GameTestHelper helper, CrafterBlockEntity crafter, StackKey output) {
        NetworkState network = crafter.network();
        if (network == null || !network.patterns().isCraftable(output)) {
            throw helper.assertionException(Component.literal("the crafter's pattern is not on the network"));
        }
        return network;
    }

    private static Object2LongOpenHashMap<StackKey> inputs(StackKey key, long amount) {
        Object2LongOpenHashMap<StackKey> map = new Object2LongOpenHashMap<>();
        map.put(key, amount);
        return map;
    }

    private static int count(List<ItemStack> stacks, StackKey key) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (ItemKey.of(stack).equals(key)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * A job for {@code count} of {@code output}: the crafter crafts it from exactly the {@code crafts} sets of
     * {@code inputs} in storage with its own pattern, no provider or assembler on the network; the job ends with the
     * outputs stored, the inputs used up once, the buffer empty and the MU paid per craft.
     */
    private static void job(GameTestHelper helper, CraftingPattern pattern, StackKey output, long count,
            List<StackKey> inputs, long crafts) {
        CrafterBlockEntity crafter = setUp(helper, List.of(pattern));
        helper.setBlock(NetworkGameTests.at(2).above(), CpuContent.CORE_1.get());
        UUID[] id = new UUID[1];
        long[] energy = new long[1];
        helper.startSequence().thenWaitUntil(() -> network(helper, crafter, output)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, output);
            for (StackKey input : inputs) {
                NetworkGameTests.insert(network, input, crafts);
            }
            CraftingPlanner.Result plan = CraftingPlanner.planNow(network, output, count);
            helper.assertTrue(plan.tree().complete(), "the plan is complete");
            energy[0] = network.storedEnergy();
            JobQueue.Submission submission = JobQueue.submit(network, network.nodes().iterator().nextLong(), plan,
                    JobQueue.Request.player(UUID.randomUUID()));
            id[0] = submission.job()
                    .orElseThrow(() -> helper.assertionException(Component.literal("refused: " + submission.refusal())))
                    .id();
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, crafter, output);
            helper.assertTrue(CraftingJobs.get(helper.getLevel()).get(id[0]) == null, "the job finished");
            helper.assertValueEqual(network.contents().amount(output), count, "the outputs stored");
            for (StackKey input : inputs) {
                helper.assertValueEqual(network.contents().amount(input), 0L, input + " used up");
            }
            helper.assertTrue(crafter.core().contents().isEmpty(), "the buffer emptied into the network");
        }).thenExecute(() -> {
            NetworkState network = network(helper, crafter, output);
            long paid = energy[0] - network.storedEnergy();
            long perCraft = StorageServerConfig.CRAFTING_CRAFTER_ENERGY_PER_CRAFT.getAsLong();
            helper.assertTrue(perCraft > 0, "a craft costs MU by default");
            helper.assertTrue(paid >= crafts * perCraft, "the crafts were paid, " + paid);
        }).thenSucceed();
    }

    /**
     * A stonecutting pattern whose recipe gives another count (as after a datapack change) is refused whole: nothing
     * crafted, nothing taken, the crafter names the reason; the real pattern still runs. A processing pattern never
     * goes into a crafter's slot.
     */
    private static void recipeChanged(GameTestHelper helper) {
        CraftingPattern real = PatternTableGameTests.stonecuttingPattern();
        CraftingPattern wrongCount = new CraftingPattern(CraftingPattern.Kind.CRAFTING, real.inputs(),
                List.of(new PatternOutput(SLAB, 3, 1f)), real.recipeType(), real.recipe());
        CrafterBlockEntity crafter = setUp(helper, List.of(wrongCount, real));
        helper.startSequence().thenWaitUntil(() -> network(helper, crafter, SLAB)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, SLAB);
            Object2LongOpenHashMap<StackKey> stone = inputs(STONE, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, wrongCount, 1, stone), 0,
                    "a pattern whose recipe makes another count");
            helper.assertValueEqual(stone.getLong(STONE), 1L, "the input stays with the job");
            helper.assertTrue(crafter.logic().stuck() == StuckReason.RECIPE_CHANGED, "the reason is named");
            helper.assertTrue(crafter.core().contents().isEmpty(), "nothing crafted");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, real, 1, stone), 1, "the real pattern runs");
            helper.assertTrue(crafter.logic().stuck() == null, "no longer stuck");
            CraftingPattern smelting = new CraftingPattern(CraftingPattern.Kind.PROCESSING,
                    List.of(new PatternInput(new PatternIngredient.Exact(LOG), 1, Optional.empty(), false)),
                    List.of(new PatternOutput(ItemKey.of(Items.CHARCOAL), 1, 1f)),
                    Optional.of(Identifier.withDefaultNamespace("smelting")), Optional.empty());
            helper.assertFalse(crafter.logic().slots().canPlaceItem(2, PatternItem.encode(smelting)),
                    "a processing pattern does not go in");
            helper.assertTrue(crafter.logic().slots().canPlaceItem(2, PatternItem.encode(planks())),
                    "a crafting pattern does");
        }).thenSucceed();
    }

    /**
     * Broken with two patterns and a crafted batch still in its buffer (crafted straight through its core, so no output
     * task has pulled it yet), the crafter drops one item holding both; placed again, it has them back. The batch's
     * inputs were used once: nothing of them is in the item.
     */
    private static void breakKeepsContents(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks(), PatternTableGameTests.stonecuttingPattern()));
        helper.startSequence().thenWaitUntil(() -> network(helper, crafter, PLANKS)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            helper.assertTrue(
                    crafter.core().craft(helper.getLevel(), planks(), 2, inputs(LOG, 2), network, crafter.deviceLink()),
                    "crafted into the buffer");
            helper.assertValueEqual(count(crafter.core().contents(), PLANKS), 8, "eight planks wait");
            BlockPos absolute = helper.absolutePos(CRAFTER);
            List<ItemStack> drops = Block.getDrops(helper.getBlockState(CRAFTER), helper.getLevel(), absolute, crafter);
            helper.assertValueEqual(drops.size(), 1, "one item drops");
            ItemStack drop = drops.getFirst();
            helper.assertTrue(drop.is(CrafterContent.CRAFTER.get().asItem()), "the crafter itself");
            ItemContainerContents patterns = drop.get(DataComponents.CONTAINER);
            helper.assertValueEqual(patterns == null ? 0L : patterns.nonEmptyItemCopyStream().count(), 2L,
                    "both patterns in the item");
            ItemContainerContents buffer = drop.get(ModDataComponents.CRAFTER_BUFFER.get());
            helper.assertValueEqual(buffer == null ? 0 : count(buffer.nonEmptyItemCopyStream().toList(), PLANKS), 8,
                    "the planks in the item");
            helper.assertValueEqual(buffer == null ? 0 : count(buffer.nonEmptyItemCopyStream().toList(), LOG), 0,
                    "no input kept");
            BlockPos again = NetworkGameTests.at(6);
            helper.setBlock(again, CrafterContent.CRAFTER.get());
            CrafterBlockEntity placed = helper.getBlockEntity(again, CrafterBlockEntity.class);
            placed.applyComponentsFromItemStack(drop);
            helper.assertValueEqual(count(placed.core().contents(), PLANKS), 8, "the planks are back");
            helper.assertTrue(PatternItem.pattern(placed.logic().slots().getItem(0)) != null
                    && PatternItem.pattern(placed.logic().slots().getItem(1)) != null, "the patterns are back");
        }).thenSucceed();
    }

    /**
     * The same planks pattern in the crafter and in a provider with an assembler: the higher pattern priority gets
     * every batch while it accepts; turned around, the other one gets them.
     */
    private static void priority(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks()));
        helper.setBlock(PROVIDER, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        helper.setBlock(ASSEMBLER, AssemblerContent.ASSEMBLER.get());
        PatternProviderBlockEntity provider = helper.getBlockEntity(PROVIDER, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(planks()));
        crafter.logic().setPriority(0, 5);
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, crafter, PLANKS);
            helper.assertTrue(provider.network() == crafter.network(), "both on one network");
        }).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(ASSEMBLER,
                    CraftingAssemblerBlockEntity.class);
            for (int i = 0; i < 3; i++) {
                helper.assertValueEqual(
                        CraftingDispatcher.dispatch(network, planks(), 2, inputs(LOG, 2), DispatchStrategy.ROUND_ROBIN),
                        2, "every batch handed over");
            }
            helper.assertTrue(assembler.contents().isEmpty(), "the lower priority got nothing");
            helper.assertTrue(crafter.logic().lastDispatch() > 0, "the crafter got the batches");
            crafter.logic().setPriority(0, -5);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, planks(), 2, inputs(LOG, 2), DispatchStrategy.ROUND_ROBIN), 2,
                    "handed over again");
            helper.assertValueEqual(count(assembler.contents(), PLANKS), 8, "now the assembler crafts");
        }).thenSucceed();
    }

    /**
     * The crafter (priority 5) and the provider with the assembler (priority 0) hold the same pattern. A step larger
     * than the crafter's buffer takes is not cut short: what the crafter refuses falls through to the lower priority in
     * the same call.
     */
    private static void priorityRemainder(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks()));
        PatternProviderBlockEntity provider = providerWithAssembler(helper);
        crafter.logic().setPriority(0, 5);
        helper.startSequence().thenWaitUntil(() -> bothOnNetwork(helper, crafter, provider)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(ASSEMBLER,
                    CraftingAssemblerBlockEntity.class);
            // 200 crafts are 800 planks; one nine-slot buffer holds 576, so the crafter takes 100 (halved) at most.
            Object2LongOpenHashMap<StackKey> logs = inputs(LOG, 200);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, planks(), 200, logs, DispatchStrategy.ROUND_ROBIN), 200,
                    "the whole step is handed over");
            int crafted = count(crafter.core().contents(), PLANKS);
            int assembled = count(assembler.contents(), PLANKS);
            helper.assertTrue(crafted > 0 && crafted < 800, "the crafter took what fits, " + crafted);
            helper.assertValueEqual(crafted + assembled, 800, "the rest went to the lower priority");
            helper.assertValueEqual(logs.getLong(LOG), 0L, "every log used once");
        }).thenSucceed();
    }

    /** At equal priority the crafter and the provider with the assembler share a step, as round robin says. */
    private static void priorityTie(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks()));
        PatternProviderBlockEntity provider = providerWithAssembler(helper);
        helper.startSequence().thenWaitUntil(() -> bothOnNetwork(helper, crafter, provider)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(ASSEMBLER,
                    CraftingAssemblerBlockEntity.class);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, planks(), 4, inputs(LOG, 4), DispatchStrategy.ROUND_ROBIN), 4,
                    "the step is handed over");
            helper.assertValueEqual(count(crafter.core().contents(), PLANKS), 8, "half to the crafter");
            helper.assertValueEqual(count(assembler.contents(), PLANKS), 8, "half to the assembler");
        }).thenSucceed();
    }

    /**
     * Results in the crafter's buffer without a batch in flight (the network had no room when they were made) go to the
     * network on the next wake of its output task, without waiting for another batch.
     */
    private static void returnsLeftovers(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks()));
        helper.startSequence().thenWaitUntil(() -> network(helper, crafter, PLANKS)).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            helper.assertTrue(
                    crafter.core().craft(helper.getLevel(), planks(), 2, inputs(LOG, 2), network, crafter.deviceLink()),
                    "crafted straight into the buffer");
            crafter.deviceLink().wake(crafter.logic().task());
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            helper.assertValueEqual(network.contents().amount(PLANKS), 8L, "the planks reached the network");
            helper.assertTrue(crafter.core().isEmpty(), "the buffer is empty");
        }).thenSucceed();
    }

    /**
     * A crafter on a network without storage holds results nobody takes: its output task retries with a delay that
     * doubles from the pull interval on each run that moves nothing, up to {@link ProviderLogic#MAX_LEFTOVER_TICKS}.
     * Once a storage device joins, the results reach the network within the cap and the delay resets.
     */
    private static void leftoversBackOff(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        BlockPos at = NetworkGameTests.at(2);
        helper.setBlock(at,
                CrafterContent.CRAFTER.get().defaultBlockState().setValue(CrafterBlock.FACING, Direction.SOUTH));
        CrafterBlockEntity crafter = helper.getBlockEntity(at, CrafterBlockEntity.class);
        List<Integer> delays = new ArrayList<>();
        long[] joined = new long[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(crafter.network() != null, "on a network"))
                .thenExecute(() -> {
                    NetworkState network = crafter.network();
                    helper.assertTrue(network != null && crafter.core().craft(helper.getLevel(), planks(), 2,
                            inputs(LOG, 2), network, crafter.deviceLink()), "crafted into the buffer");
                    crafter.deviceLink().wake(crafter.logic().task());
                }).thenWaitUntil(() -> {
                    int delay = crafter.logic().leftoverDelay();
                    if (delay > 0 && (delays.isEmpty() || delays.getLast() != delay)) {
                        delays.add(delay);
                    }
                    helper.assertValueEqual(delay, ProviderLogic.MAX_LEFTOVER_TICKS, "the delay reached the cap");
                }).thenExecute(() -> {
                    helper.assertTrue(delays.size() >= 3, "several retries, " + delays);
                    for (int i = 1; i < delays.size(); i++) {
                        helper.assertTrue(delays.get(i) > delays.get(i - 1), "each retry waits longer, " + delays);
                    }
                    helper.assertValueEqual(count(crafter.core().contents(), PLANKS), 8, "the planks still wait");
                    NetworkGameTests.device(helper, NetworkGameTests.at(3));
                    joined[0] = helper.getTick();
                }).thenWaitUntil(() -> {
                    NetworkState network = crafter.network();
                    helper.assertTrue(network != null && network.contents().amount(PLANKS) == 8L,
                            "the planks reached the network");
                }).thenExecute(() -> {
                    helper.assertTrue(helper.getTick() - joined[0] <= ProviderLogic.MAX_LEFTOVER_TICKS + 5,
                            "picked up within the cap, after " + (helper.getTick() - joined[0]));
                    helper.assertTrue(crafter.core().isEmpty(), "the buffer is empty");
                    helper.assertValueEqual(crafter.logic().leftoverDelay(), 0, "the delay reset");
                }).thenSucceed();
    }

    private static PatternProviderBlockEntity providerWithAssembler(GameTestHelper helper) {
        helper.setBlock(PROVIDER, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        helper.setBlock(ASSEMBLER, AssemblerContent.ASSEMBLER.get());
        PatternProviderBlockEntity provider = helper.getBlockEntity(PROVIDER, PatternProviderBlockEntity.class);
        provider.logic().slots().setItem(0, PatternItem.encode(planks()));
        return provider;
    }

    private static void bothOnNetwork(GameTestHelper helper, CrafterBlockEntity crafter,
            PatternProviderBlockEntity provider) {
        network(helper, crafter, PLANKS);
        helper.assertTrue(provider.network() == crafter.network(), "both on one network");
    }

    /**
     * The item display: a batch sets the crafting item on the crafter and the assembler, the client update carries it,
     * and once {@link AssemblerCore#DISPLAY_TICKS} pass without a batch both clear, the update too.
     */
    private static void display(GameTestHelper helper) {
        CrafterBlockEntity crafter = setUp(helper, List.of(planks()));
        helper.setBlock(PROVIDER, ProviderContent.PROVIDER.get().defaultBlockState()
                .setValue(PatternProviderBlock.FACING, Direction.NORTH));
        helper.setBlock(ASSEMBLER, AssemblerContent.ASSEMBLER.get());
        PatternProviderBlockEntity provider = helper.getBlockEntity(PROVIDER, PatternProviderBlockEntity.class);
        CraftingPattern slab = PatternTableGameTests.stonecuttingPattern();
        provider.logic().slots().setItem(0, PatternItem.encode(slab));
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, crafter, PLANKS);
            network(helper, crafter, SLAB);
        }).thenExecute(() -> {
            NetworkState network = network(helper, crafter, PLANKS);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(ASSEMBLER,
                    CraftingAssemblerBlockEntity.class);
            helper.assertTrue(crafter.craftingItem().isEmpty(), "the crafter shows nothing while idle");
            helper.assertTrue(
                    helper.getBlockState(CRAFTER).isRedstoneConductor(helper.getLevel(), helper.absolutePos(CRAFTER)),
                    "the see-through crafter still conducts redstone");
            helper.assertTrue(helper.getBlockState(ASSEMBLER).isRedstoneConductor(helper.getLevel(),
                    helper.absolutePos(ASSEMBLER)), "the see-through assembler still conducts redstone");
            helper.assertTrue(assembler.craftingItem().isEmpty(), "the assembler shows nothing while idle");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, planks(), 1, inputs(LOG, 1)), 1, "planks");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, slab, 1, inputs(STONE, 1)), 1, "a slab");
            helper.assertTrue(crafter.craftingItem().is(Items.OAK_PLANKS), "the crafter shows the planks");
            helper.assertTrue(assembler.craftingItem().is(Items.STONE_SLAB), "the assembler shows the slab");
            helper.assertTrue(update(helper, crafter).contains("crafting"), "the crafter's update carries it");
            helper.assertTrue(update(helper, assembler).contains("crafting"), "the assembler's update carries it");
        }).thenIdle(AssemblerCore.DISPLAY_TICKS + 1).thenExecute(() -> {
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(ASSEMBLER,
                    CraftingAssemblerBlockEntity.class);
            helper.assertTrue(crafter.craftingItem().isEmpty(), "the crafter clears once idle");
            helper.assertTrue(assembler.craftingItem().isEmpty(), "the assembler clears once idle");
            helper.assertFalse(update(helper, crafter).contains("crafting"), "the crafter's update clears it");
            helper.assertFalse(update(helper, assembler).contains("crafting"), "the assembler's update clears it");
        }).thenSucceed();
    }

    private static CompoundTag update(GameTestHelper helper,
            net.minecraft.world.level.block.entity.BlockEntity entity) {
        return entity.getUpdateTag(helper.getLevel().registryAccess());
    }
}
