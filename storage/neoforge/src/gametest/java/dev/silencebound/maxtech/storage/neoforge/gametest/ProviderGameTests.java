package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.resource.FluidKey;
import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.api.crafting.CraftingPattern;
import dev.silencebound.maxtech.storage.api.crafting.PatternIngredient;
import dev.silencebound.maxtech.storage.api.crafting.PatternInput;
import dev.silencebound.maxtech.storage.api.crafting.PatternOutput;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.crafting.assembler.AssemblerContent;
import dev.silencebound.maxtech.storage.crafting.assembler.CraftingAssemblerBlockEntity;
import dev.silencebound.maxtech.storage.crafting.pattern.PatternItem;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDeliveries;
import dev.silencebound.maxtech.storage.crafting.provider.CraftingDispatcher;
import dev.silencebound.maxtech.storage.crafting.provider.DispatchStrategy;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlock;
import dev.silencebound.maxtech.storage.crafting.provider.PatternProviderBlockEntity;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderContent;
import dev.silencebound.maxtech.storage.iface.InterfaceBlockEntity;
import dev.silencebound.maxtech.storage.iface.InterfaceContent;
import dev.silencebound.maxtech.storage.network.NetworkState;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The pattern provider and the crafting assembler (M7c area B) against real machines, driven through
 * {@link CraftingDispatcher} as the executor will: furnace faces and the pull from its bottom, brewing stand faces, a
 * fluid machine's tanks, assembler crafting with remainders, blocking mode, the strategy split, a machine emptied
 * behind the network's back, and interface deliveries. A powered controller at x = 1, a test storage device at x = 2,
 * the provider at x = 3 facing east and its machine at x = 4.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class ProviderGameTests {
    private static final String[] NAMES = {"provider_furnace_faces_and_pull", "provider_brewing_stand_faces",
            "provider_fluid_machine", "provider_assembler_remainders", "provider_blocking_mode",
            "provider_strategy_split", "provider_no_dupe_emptied_machine", "provider_interface_delivery",
            "provider_item_keeps_patterns", "provider_assembler_energy", "provider_switched_off_by_logic",
            "provider_outputs_one_operation"};
    private static final StackKey RAW_IRON = ItemKey.of(Items.RAW_IRON);
    private static final StackKey COAL = ItemKey.of(Items.COAL);
    private static final StackKey IRON = ItemKey.of(Items.IRON_INGOT);

    static {
        TestStorage.init();
        StorageTestEnvironment.init();
        ProviderTestContent.init();
        StorageTests.function(NAMES[0], StorageTestEnvironment.clearing(ProviderGameTests::furnace));
        StorageTests.function(NAMES[1], StorageTestEnvironment.clearing(ProviderGameTests::brewing));
        StorageTests.function(NAMES[2], StorageTestEnvironment.clearing(ProviderGameTests::fluidMachine));
        StorageTests.function(NAMES[3], StorageTestEnvironment.clearing(ProviderGameTests::assembler));
        StorageTests.function(NAMES[4], StorageTestEnvironment.clearing(ProviderGameTests::blocking));
        StorageTests.function(NAMES[5], StorageTestEnvironment.clearing(ProviderGameTests::strategySplit));
        StorageTests.function(NAMES[6], StorageTestEnvironment.clearing(ProviderGameTests::noDupe));
        StorageTests.function(NAMES[7], StorageTestEnvironment.clearing(ProviderGameTests::interfaceDelivery));
        StorageTests.function(NAMES[8], StorageTestEnvironment.clearing(ProviderGameTests::itemKeepsPatterns));
        StorageTests.function(NAMES[9], StorageTestEnvironment.clearing(ProviderGameTests::assemblerEnergy));
        StorageTests.function(NAMES[10], StorageTestEnvironment.clearing(ProviderGameTests::switchedOff));
        StorageTests.function(NAMES[11], StorageTestEnvironment.clearing(ProviderGameTests::outputsOneOperation));
    }

    private ProviderGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("provider"),
                StorageTestEnvironment.plain());
        for (String name : NAMES) {
            StorageTests.test(event, environment, name, MaxtechStorage.id("empty_16x12x16"), 700);
        }
    }

    private static PatternInput in(StackKey key, long amount) {
        return new PatternInput(new PatternIngredient.Exact(key), amount, Optional.empty(), false);
    }

    private static PatternInput in(StackKey key, long amount, Direction face) {
        return new PatternInput(new PatternIngredient.Exact(key), amount, Optional.of(face), false);
    }

    private static CraftingPattern processing(List<PatternInput> inputs, StackKey output, long amount) {
        return new CraftingPattern(CraftingPattern.Kind.PROCESSING, inputs,
                List.of(new PatternOutput(output, amount, 1f)), Optional.empty(), Optional.empty());
    }

    /** Raw iron and coal into a furnace; the faces come from the furnace rule at dispatch. */
    private static CraftingPattern smelting() {
        return processing(List.of(in(RAW_IRON, 1), in(COAL, 1)), IRON, 1);
    }

    private static Object2LongOpenHashMap<StackKey> inputs(Object... keysAndAmounts) {
        Object2LongOpenHashMap<StackKey> map = new Object2LongOpenHashMap<>();
        for (int i = 0; i < keysAndAmounts.length; i += 2) {
            map.addTo((StackKey) keysAndAmounts[i], ((Number) keysAndAmounts[i + 1]).longValue());
        }
        return map;
    }

    /** Controller, storage device and a provider at {@code x} facing {@code facing}, holding {@code patterns}. */
    private static PatternProviderBlockEntity provider(GameTestHelper helper, BlockPos pos, Direction facing,
            List<CraftingPattern> patterns) {
        helper.setBlock(pos,
                ProviderContent.PROVIDER.get().defaultBlockState().setValue(PatternProviderBlock.FACING, facing));
        PatternProviderBlockEntity provider = helper.getBlockEntity(pos, PatternProviderBlockEntity.class);
        for (int i = 0; i < patterns.size(); i++) {
            provider.logic().slots().setItem(i, PatternItem.encode(patterns.get(i)));
        }
        return provider;
    }

    private static PatternProviderBlockEntity setUp(GameTestHelper helper, List<CraftingPattern> patterns) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        return provider(helper, NetworkGameTests.at(3), Direction.EAST, patterns);
    }

    private static NetworkState network(GameTestHelper helper, PatternProviderBlockEntity provider) {
        NetworkState network = provider.network();
        helper.assertTrue(network != null, "the provider is in a network");
        return Objects.requireNonNull(network);
    }

    private static <T extends BlockEntity> T machine(GameTestHelper helper, Class<T> type) {
        return helper.getBlockEntity(NetworkGameTests.at(4), type);
    }

    /**
     * Raw iron goes in on top and coal into the fuel slot from the side; the ingot is pulled from the bottom into the
     * network and reported to the delivery listeners.
     */
    private static void furnace(GameTestHelper helper) {
        CraftingPattern pattern = smelting();
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        long[] delivered = new long[1];
        CraftingDeliveries.Listener listener = new CraftingDeliveries.Listener() {
            @Override
            public boolean expects(NetworkState network, StackKey key) {
                return false;
            }

            @Override
            public void delivered(NetworkState network, StackKey key, long amount) {
                if (network == provider.network() && key.equals(IRON)) {
                    delivered[0] += amount;
                }
            }
        };
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            CraftingDeliveries.listen(listener);
            Object2LongOpenHashMap<StackKey> inputs = inputs(RAW_IRON, 1, COAL, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider), pattern, 1, inputs,
                    DispatchStrategy.FASTEST), 1, "one craft handed over");
            helper.assertTrue(inputs.isEmpty(), "the inputs left the caller");
            FurnaceBlockEntity furnace = machine(helper, FurnaceBlockEntity.class);
            helper.assertTrue(furnace.getItem(0).is(Items.RAW_IRON), "raw iron on top");
            helper.assertTrue(furnace.getItem(1).is(Items.COAL), "coal in the fuel slot");
            helper.assertValueEqual(provider.logic().inFlight(), 1, "one craft in flight");
        }).thenWaitUntil(() -> helper.assertValueEqual(network(helper, provider).contents().amount(IRON), 1L,
                "the ingot pulled into the network")).thenExecute(() -> {
                    CraftingDeliveries.unlisten(listener);
                    helper.assertValueEqual(delivered[0], 1L, "the ingot reported");
                    helper.assertValueEqual(provider.logic().inFlight(), 0, "nothing in flight");
                    helper.assertTrue(machine(helper, FurnaceBlockEntity.class).getItem(2).isEmpty(),
                            "the output slot emptied");
                }).thenSucceed();
    }

    /** The ingredient goes in on top, bottles and blaze powder from the side. */
    private static void brewing(GameTestHelper helper) {
        StackKey water = ItemKey.of(PotionContents.createItemStack(Items.POTION, Potions.WATER));
        StackKey awkward = ItemKey.of(PotionContents.createItemStack(Items.POTION, Potions.AWKWARD));
        StackKey wart = ItemKey.of(Items.NETHER_WART);
        StackKey powder = ItemKey.of(Items.BLAZE_POWDER);
        CraftingPattern pattern = processing(List.of(in(wart, 1), in(water, 3), in(powder, 1)), awkward, 3);
        helper.setBlock(NetworkGameTests.at(4), Blocks.BREWING_STAND);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network(helper, provider), pattern, 1,
                            inputs(wart, 1, water, 3, powder, 1), DispatchStrategy.FASTEST),
                    1, "one craft handed over");
            BrewingStandBlockEntity stand = machine(helper, BrewingStandBlockEntity.class);
            for (int i = 0; i < 3; i++) {
                helper.assertTrue(stand.getItem(i).is(Items.POTION), "a water bottle in slot " + i);
            }
            helper.assertTrue(stand.getItem(3).is(Items.NETHER_WART), "the wart on top");
            helper.assertTrue(stand.getItem(4).is(Items.BLAZE_POWDER), "blaze powder as fuel");
        }).thenSucceed();
    }

    /** Water goes into the top tank; the lava comes back from the side the provider touches. */
    private static void fluidMachine(GameTestHelper helper) {
        StackKey water = FluidKey.of(Fluids.WATER);
        StackKey lava = FluidKey.of(Fluids.LAVA);
        CraftingPattern pattern = processing(List.of(in(water, 1000, Direction.UP)), lava, 1000);
        helper.setBlock(NetworkGameTests.at(4), ProviderTestContent.FLUID_MACHINE.get());
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider), pattern, 2,
                    inputs(water, 2000), DispatchStrategy.FASTEST), 2, "two crafts handed over");
            ProviderTestContent.FluidMachine machine = machine(helper, ProviderTestContent.FluidMachine.class);
            helper.assertValueEqual(machine.input.getAmountAsInt(0), 2000, "water in the top tank");
            machine.process();
        }).thenWaitUntil(() -> helper.assertValueEqual(network(helper, provider).contents().amount(lava), 2000L,
                "the lava pulled into the network")).thenSucceed();
    }

    /** A cake from its recipe id: the grid is laid out from the recipe, and the three buckets come back. */
    private static void assembler(GameTestHelper helper) {
        StackKey milk = ItemKey.of(Items.MILK_BUCKET);
        StackKey sugar = ItemKey.of(Items.SUGAR);
        StackKey egg = ItemKey.of(Items.EGG);
        StackKey wheat = ItemKey.of(Items.WHEAT);
        List<PatternInput> cells = new ArrayList<>();
        for (StackKey key : List.of(milk, milk, milk, sugar, egg, sugar, wheat, wheat, wheat)) {
            cells.add(in(key, 1));
        }
        CraftingPattern cake = new CraftingPattern(CraftingPattern.Kind.CRAFTING, cells,
                List.of(new PatternOutput(ItemKey.of(Items.CAKE), 1, 1f)),
                Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace("cake")));
        helper.setBlock(NetworkGameTests.at(4), AssemblerContent.ASSEMBLER.get());
        PatternProviderBlockEntity provider = setUp(helper, List.of(cake));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertTrue(provider.logic().canAccept(cake), "the assembler takes crafting patterns");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, cake, 1,
                    inputs(milk, 2, sugar, 2, egg, 1, wheat, 3), DispatchStrategy.FASTEST), 0,
                    "short of milk: nothing handed over");
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, cake, 2,
                    inputs(milk, 6, sugar, 4, egg, 2, wheat, 6), DispatchStrategy.FASTEST), 2, "two cakes");
        }).thenWaitUntil(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.CAKE)), 2L, "two cakes stored");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.BUCKET)), 6L, "six buckets back");
        }).thenSucceed();
    }

    /**
     * Each craft costs {@code crafting.assemblerEnergyPerCraft} FE from the network in the batch's transaction; a
     * network that cannot pay for the whole batch gets a smaller one (the dispatcher halves a refused share), and with
     * no FE left nothing is taken or crafted.
     */
    private static void assemblerEnergy(GameTestHelper helper) {
        StackKey log = ItemKey.of(Items.OAK_LOG);
        CraftingPattern planks = new CraftingPattern(CraftingPattern.Kind.CRAFTING, List.of(in(log, 1)),
                List.of(new PatternOutput(ItemKey.of(Items.OAK_PLANKS), 4, 1f)),
                Optional.of(Identifier.withDefaultNamespace("crafting")),
                Optional.of(Identifier.withDefaultNamespace("oak_planks")));
        long cost = StorageServerConfig.CRAFTING_ASSEMBLER_ENERGY_PER_CRAFT.getAsLong();
        helper.assertTrue(cost > 0, "the default costs FE");
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 3 * cost + cost / 2);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        PatternProviderBlockEntity provider = provider(helper, NetworkGameTests.at(3), Direction.EAST, List.of(planks));
        helper.setBlock(NetworkGameTests.at(4), AssemblerContent.ASSEMBLER.get());
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            CraftingAssemblerBlockEntity assembler = helper.getBlockEntity(NetworkGameTests.at(4),
                    CraftingAssemblerBlockEntity.class);
            long before = network.storedEnergy();
            Object2LongOpenHashMap<StackKey> logs = inputs(log, 8);
            int sent = CraftingDispatcher.dispatch(network, planks, 8, logs, DispatchStrategy.FASTEST);
            helper.assertTrue(sent >= 1 && sent < 8, "a smaller batch than asked, got " + sent);
            helper.assertValueEqual(before - network.storedEnergy(), sent * cost, "FE drops by crafts x cost");
            helper.assertValueEqual(logs.getLong(log), (long) (8 - sent), "only crafted inputs taken");
            helper.assertValueEqual(planksIn(assembler), 4 * sent, "one result per paid craft");
            int total = sent;
            long left = network.storedEnergy();
            while (left >= cost) {
                int more = CraftingDispatcher.dispatch(network, planks, 1, inputs(log, 1), DispatchStrategy.FASTEST);
                helper.assertValueEqual(more, 1, "one craft is paid for while the FE lasts");
                total += more;
                left -= cost;
            }
            helper.assertValueEqual(network.storedEnergy(), left, "exactly the crafts were paid");
            Object2LongOpenHashMap<StackKey> unchanged = inputs(log, 4);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, planks, 4, unchanged, DispatchStrategy.FASTEST), 0,
                    "no FE for one craft: nothing handed over");
            helper.assertValueEqual(unchanged.getLong(log), 4L, "no input taken without FE");
            helper.assertValueEqual(planksIn(assembler), 4 * total, "nothing crafted without FE");
            helper.assertValueEqual(network.storedEnergy(), left, "nothing paid for a refusal");
        }).thenSucceed();
    }

    private static int planksIn(CraftingAssemblerBlockEntity assembler) {
        int count = 0;
        for (ItemStack stack : assembler.contents()) {
            if (stack.is(Items.OAK_PLANKS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Blocking holds a second batch while the furnace still holds the first's input; off, it goes; the setting is kept
     * in the item.
     */
    private static void blocking(GameTestHelper helper) {
        CraftingPattern pattern = processing(List.of(in(RAW_IRON, 1)), IRON, 1);
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertTrue(provider.logic().blocking(), "blocking by default");
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 1, inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 1,
                    "the first batch");
            helper.assertTrue(!provider.logic().canAccept(pattern), "blocked while the furnace holds raw iron");
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 1, inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 0,
                    "the second waits");
            provider.logic().setBlocking(false);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 1, inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 1,
                    "non-blocking takes it");
            helper.assertValueEqual(machine(helper, FurnaceBlockEntity.class).getItem(0).getCount(), 2,
                    "both in the furnace");
            helper.assertTrue(
                    Boolean.TRUE.equals(
                            provider.collectComponents().get(ModDataComponents.PATTERN_PROVIDER_NON_BLOCKING.get())),
                    "the setting goes into the item");
        }).thenSucceed();
    }

    /**
     * Turned off by logic (M7L area E), a provider accepts no batch and dispatch sends it nothing, the raw iron stays
     * with the caller; turned on again it takes the batch at once.
     */
    private static void switchedOff(GameTestHelper helper) {
        CraftingPattern pattern = processing(List.of(in(RAW_IRON, 1)), IRON, 1);
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertTrue(provider.logic().canAccept(pattern), "on, it accepts");
            provider.setSwitchedOff(true);
            helper.assertTrue(!provider.logic().canAccept(pattern), "off, it accepts");
            Object2LongOpenHashMap<StackKey> held = inputs(RAW_IRON, 1);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, pattern, 1, held, DispatchStrategy.FASTEST), 0,
                    "dispatched while off");
            helper.assertValueEqual(held.getLong(RAW_IRON), 1L, "the input stays with the caller");
            helper.assertTrue(machine(helper, FurnaceBlockEntity.class).getItem(0).isEmpty(), "furnace while off");
            provider.setSwitchedOff(false);
            helper.assertValueEqual(CraftingDispatcher.dispatch(network, pattern, 1, held, DispatchStrategy.FASTEST), 1,
                    "dispatched when on again");
            helper.assertValueEqual(machine(helper, FurnaceBlockEntity.class).getItem(0).getCount(), 1, "furnace");
        }).thenSucceed();
    }

    /**
     * Two providers with a furnace each: round robin splits evenly, fastest gives the busier one less, and the provider
     * sent work longest ago goes first.
     */
    private static void strategySplit(GameTestHelper helper) {
        CraftingPattern pattern = processing(List.of(in(RAW_IRON, 1)), IRON, 1);
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        PatternProviderBlockEntity first = provider(helper, NetworkGameTests.at(3), Direction.SOUTH, List.of(pattern));
        PatternProviderBlockEntity second = provider(helper, NetworkGameTests.at(4), Direction.SOUTH, List.of(pattern));
        BlockPos firstFurnace = NetworkGameTests.at(3).south();
        BlockPos secondFurnace = NetworkGameTests.at(4).south();
        helper.setBlock(firstFurnace, Blocks.FURNACE);
        helper.setBlock(secondFurnace, Blocks.FURNACE);
        first.logic().setBlocking(false);
        second.logic().setBlocking(false);
        helper.startSequence().thenWaitUntil(() -> {
            network(helper, first);
            helper.assertValueEqual(network(helper, first).patterns().patternsFor(IRON).size(), 2, "both registered");
        }).thenExecute(() -> {
            NetworkState network = network(helper, first);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 4, inputs(RAW_IRON, 4), DispatchStrategy.ROUND_ROBIN),
                    4, "four crafts");
            helper.assertValueEqual(first.logic().inFlight(), 2, "round robin: two to the first");
            helper.assertValueEqual(second.logic().inFlight(), 2, "round robin: two to the second");
            first.logic().clearPending();
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 4, inputs(RAW_IRON, 4), DispatchStrategy.FASTEST), 4,
                    "four more");
            helper.assertValueEqual(first.logic().inFlight(), 3, "fastest: the idle one takes three");
            helper.assertValueEqual(second.logic().inFlight(), 3, "fastest: the busy one takes one");
            int inFurnaces = helper.getBlockEntity(firstFurnace, FurnaceBlockEntity.class).getItem(0).getCount()
                    + helper.getBlockEntity(secondFurnace, FurnaceBlockEntity.class).getItem(0).getCount();
            helper.assertValueEqual(inFurnaces, 8, "every raw iron in a furnace");
        }).thenSucceed();
    }

    /**
     * The furnace is emptied by hand after the push: the provider pulls nothing, the network gains nothing, and the
     * batch is given up once its time has run out.
     */
    private static void noDupe(GameTestHelper helper) {
        CraftingPattern pattern = smelting();
        helper.setBlock(NetworkGameTests.at(4), Blocks.FURNACE);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            helper.assertValueEqual(CraftingDispatcher.dispatch(network(helper, provider), pattern, 1,
                    inputs(RAW_IRON, 1, COAL, 1), DispatchStrategy.FASTEST), 1, "pushed");
            FurnaceBlockEntity furnace = machine(helper, FurnaceBlockEntity.class);
            furnace.setItem(0, ItemStack.EMPTY);
            furnace.setItem(1, ItemStack.EMPTY);
            // A stale ingot the provider never asked for stays where it is.
            furnace.setItem(2, new ItemStack(Items.GOLD_INGOT));
        }).thenIdle(260).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(network.contents().amount(IRON), 0L, "no ingot from nowhere");
            helper.assertValueEqual(network.contents().amount(ItemKey.of(Items.GOLD_INGOT)), 0L,
                    "an unexpected output stays in the machine");
            helper.assertValueEqual(provider.logic().inFlight(), 1, "still waiting within its time");
        }).thenWaitUntil(() -> helper.assertValueEqual(provider.logic().inFlight(), 0, "the batch was given up"))
                .thenExecute(() -> helper.assertValueEqual(network(helper, provider).contents().amount(IRON), 0L,
                        "still no ingot"))
                .thenSucceed();
    }

    /**
     * A pattern with two outputs into a barrel standing in for the machine: two ingots and three nuggets in two slots
     * come out in one operation (4 FE), not one per slot (M7E follow-up); both are credited, the raw iron left in the
     * barrel stays, and nothing is in flight afterwards.
     */
    private static void outputsOneOperation(GameTestHelper helper) {
        StackKey nugget = ItemKey.of(Items.GOLD_NUGGET);
        CraftingPattern pattern = new CraftingPattern(CraftingPattern.Kind.PROCESSING, List.of(in(RAW_IRON, 1)),
                List.of(new PatternOutput(IRON, 2, 1f), new PatternOutput(nugget, 3, 1f)), Optional.empty(),
                Optional.empty());
        helper.setBlock(NetworkGameTests.at(4), Blocks.BARREL);
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        long[] before = new long[2];
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            NetworkState network = network(helper, provider);
            helper.assertValueEqual(
                    CraftingDispatcher.dispatch(network, pattern, 1, inputs(RAW_IRON, 1), DispatchStrategy.FASTEST), 1,
                    "pushed");
            before[0] = network.operations();
            before[1] = network.storedEnergy();
            BarrelBlockEntity barrel = machine(helper, BarrelBlockEntity.class);
            barrel.setItem(20, new ItemStack(Items.IRON_INGOT, 2));
            barrel.setItem(21, new ItemStack(Items.GOLD_NUGGET, 3));
        }).thenWaitUntil(() -> helper.assertValueEqual(provider.logic().inFlight(), 0, "nothing in flight"))
                .thenExecute(() -> {
                    NetworkState network = network(helper, provider);
                    helper.assertValueEqual(network.contents().amount(IRON), 2L, "ingots in the network");
                    helper.assertValueEqual(network.contents().amount(nugget), 3L, "nuggets in the network");
                    helper.assertValueEqual(network.contents().amount(RAW_IRON), 0L, "raw iron in the network");
                    helper.assertValueEqual(network.operations() - before[0], 1L, "operations to pull both");
                    helper.assertValueEqual(before[1] - network.storedEnergy(), 4L, "FE to pull both");
                }).thenSucceed();
    }

    /** An insert into an interface of a key a job waits for is reported once its transaction commits. */
    private static void interfaceDelivery(GameTestHelper helper) {
        BlockPos core = NetworkGameTests.controller(helper, 1);
        NetworkGameTests.power(helper, core, 1_000_000);
        NetworkGameTests.device(helper, NetworkGameTests.at(2));
        helper.setBlock(NetworkGameTests.at(3), InterfaceContent.INTERFACE.get());
        InterfaceBlockEntity iface = helper.getBlockEntity(NetworkGameTests.at(3), InterfaceBlockEntity.class);
        long[] delivered = new long[1];
        CraftingDeliveries.Listener listener = new CraftingDeliveries.Listener() {
            @Override
            public boolean expects(NetworkState network, StackKey key) {
                return network == iface.network() && key.equals(IRON);
            }

            @Override
            public void delivered(NetworkState network, StackKey key, long amount) {
                delivered[0] += amount;
            }
        };
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(iface.network() != null, "in a network"))
                .thenExecute(() -> {
                    CraftingDeliveries.listen(listener);
                    try {
                        int slot = iface.worker().itemHandler().size() - 1;
                        try (Transaction simulation = Transaction.openRoot()) {
                            iface.worker().itemHandler().insert(slot, ItemResource.of(Items.IRON_INGOT), 2, simulation);
                        }
                        helper.assertValueEqual(delivered[0], 0L, "a simulation reports nothing");
                        try (Transaction transaction = Transaction.openRoot()) {
                            iface.worker().itemHandler().insert(slot, ItemResource.of(Items.IRON_INGOT), 2,
                                    transaction);
                            iface.worker().itemHandler().insert(slot, ItemResource.of(Items.GOLD_INGOT), 1,
                                    transaction);
                            transaction.commit();
                        }
                        helper.assertValueEqual(delivered[0], 2L, "two ingots reported, the gold not expected");
                    } finally {
                        CraftingDeliveries.unlisten(listener);
                    }
                }).thenSucceed();
    }

    /** The provider's patterns go into its item and come back when it is placed again. */
    private static void itemKeepsPatterns(GameTestHelper helper) {
        CraftingPattern pattern = smelting();
        PatternProviderBlockEntity provider = setUp(helper, List.of(pattern));
        helper.startSequence().thenWaitUntil(() -> network(helper, provider)).thenExecute(() -> {
            helper.assertTrue(network(helper, provider).patterns().isCraftable(IRON), "registered");
            ItemStack drop = new ItemStack(ProviderContent.PROVIDER.item().get());
            drop.applyComponents(provider.collectComponents());
            helper.assertTrue(drop.get(DataComponents.CONTAINER) != null, "the patterns are in the item");
            BlockPos other = NetworkGameTests.at(5);
            helper.setBlock(other, ProviderContent.PROVIDER.get());
            PatternProviderBlockEntity placed = helper.getBlockEntity(other, PatternProviderBlockEntity.class);
            placed.applyComponents(drop.getComponents(), DataComponentPatch.EMPTY);
            helper.assertTrue(pattern.equals(PatternItem.pattern(placed.logic().slots().getItem(0))),
                    "the pattern came back");
            provider.logic().slots().setItem(0, ItemStack.EMPTY);
            helper.assertTrue(!network(helper, provider).patterns().isCraftable(IRON),
                    "taking the pattern out drops it from the network");
        }).thenSucceed();
    }
}
