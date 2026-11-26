package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.api.recipe.ItemOutput;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import dev.silencebound.maxtech.storage.machine.ModuleContent;
import dev.silencebound.maxtech.storage.machine.ProcessingMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineBlockEntity;
import dev.silencebound.maxtech.storage.machine.StorageMachineRecipe;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The tier-2 line (M6b area A): refining's niobium chance, the chain from raw tantalite to an advanced processor
 * through real machines, the precision module's yield and the module slot's refusal of hoppers.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class TierTwoMachineGameTests {
    private static final BlockPos LITHOGRAPH = new BlockPos(1, 1, 1);
    private static final BlockPos FABRICATOR = new BlockPos(3, 1, 1);
    private static final BlockPos ASSEMBLER = new BlockPos(5, 1, 1);
    private static final BlockPos MACHINE = new BlockPos(2, 1, 1);
    private static final BlockPos INPUT_HOPPER = new BlockPos(1, 1, 1);
    private static final BlockPos OUTPUT_HOPPER = new BlockPos(2, 0, 1);
    private static final BlockPos CHEST = new BlockPos(3, 0, 1);
    private static final int ROLLS = 20_000;

    static {
        StorageTests.function("machine_refine_niobium_chance", TierTwoMachineGameTests::refineChance);
        StorageTests.function("machine_tier_two_recipes_name_research", TierTwoMachineGameTests::recipesNameResearch);
        StorageTests.function("machine_chain_to_advanced_processor", TierTwoMachineGameTests::chain);
        StorageTests.function("machine_precision_module_doubles_yield", TierTwoMachineGameTests::precision);
        StorageTests.function("machine_module_slot_refuses_hopper", TierTwoMachineGameTests::moduleSlotRefusesHopper);
    }

    private TierTwoMachineGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("tier_two"));
        StorageTests.test(event, environment, "machine_refine_niobium_chance", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "machine_tier_two_recipes_name_research", StorageTests.SMALL, 20);
        StorageTests.test(event, environment, "machine_chain_to_advanced_processor", StorageTests.SMALL, 3000);
        StorageTests.test(event, environment, "machine_precision_module_doubles_yield", StorageTests.SMALL, 1500);
        StorageTests.test(event, environment, "machine_module_slot_refuses_hopper", StorageTests.SMALL, 200);
    }

    /**
     * The loaded refine recipe makes tantalum dust every run and niobium dust a quarter of the time: its chance is
     * recipe data, rolled the machine's way with a seeded random so the bound never flakes.
     */
    private static void refineChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StorageMachineRecipe refine = null;
        for (RecipeHolder<StorageMachineRecipe> holder : level.recipeAccess().recipeMap()
                .byType(MachineContent.recipeType(MachineKind.FABRICATOR))) {
            if (holder.id().identifier().equals(MaxtechStorage.id("fabricator/tantalum_dust"))) {
                refine = holder.value();
            }
        }
        helper.assertTrue(refine != null, "the refine recipe loaded");
        StorageMachineRecipe recipe = java.util.Objects.requireNonNull(refine);
        helper.assertValueEqual(recipe.mode().orElse(""), "refine", "refine mode");
        ItemOutput tantalum = output(helper, recipe, TierTwoMaterialContent.TANTALUM_DUST.get());
        ItemOutput niobium = output(helper, recipe, TierTwoMaterialContent.NIOBIUM_DUST.get());
        helper.assertValueEqual(niobium.chance(), ItemOutput.ALWAYS / 4, "niobium chance");
        RandomSource random = RandomSource.create(6_502L);
        int tantalumRuns = 0;
        int niobiumRuns = 0;
        for (int i = 0; i < ROLLS; i++) {
            if (ProcessingMachineBlockEntity.rolls(tantalum, random)) {
                tantalumRuns++;
            }
            if (ProcessingMachineBlockEntity.rolls(niobium, random)) {
                niobiumRuns++;
            }
        }
        helper.assertValueEqual(tantalumRuns, ROLLS, "tantalum dust every run");
        // 25 % of 20,000 is 5,000 with a standard deviation of about 61; 4,700..5,300 is about five of them.
        helper.assertTrue(niobiumRuns >= 4_700 && niobiumRuns <= 5_300, "niobium in " + niobiumRuns + " of " + ROLLS);
        helper.succeed();
    }

    /**
     * The loaded recipes carry the research gates area C checks: family masks gate printing with them, advanced
     * processors gate glowstone doping, capacitors and advanced assembly; refining and basic steps need none.
     */
    private static void recipesNameResearch(GameTestHelper helper) {
        research(helper, MachineKind.LITHOGRAPH, "memory_die", ResearchGates.MEMORY_MASK);
        research(helper, MachineKind.LITHOGRAPH, "advanced_signal_die", ResearchGates.SIGNAL_MASK);
        research(helper, MachineKind.LITHOGRAPH, "io_die", ResearchGates.IO_MASK);
        research(helper, MachineKind.LITHOGRAPH, "advanced_doped_wafer", ResearchGates.ADVANCED_PROCESSORS);
        research(helper, MachineKind.LITHOGRAPH, "advanced_logic_die", ResearchGates.ADVANCED_PROCESSORS);
        research(helper, MachineKind.FABRICATOR, "tantalum_capacitor", ResearchGates.ADVANCED_PROCESSORS);
        research(helper, MachineKind.ASSEMBLER, "advanced_io_processor", ResearchGates.ADVANCED_PROCESSORS);
        research(helper, MachineKind.LITHOGRAPH, "logic_die", null);
        research(helper, MachineKind.FABRICATOR, "tantalum_dust", null);
        research(helper, MachineKind.ASSEMBLER, "basic_memory_processor", null);
        helper.succeed();
    }

    private static void research(GameTestHelper helper, MachineKind kind, String name, @Nullable Identifier gate) {
        Identifier id = MaxtechStorage.id(kind.id() + "/" + name);
        for (RecipeHolder<StorageMachineRecipe> holder : helper.getLevel().recipeAccess().recipeMap()
                .byType(MachineContent.recipeType(kind))) {
            if (holder.id().identifier().equals(id)) {
                helper.assertValueEqual(holder.value().research(), Optional.ofNullable(gate), id + " research");
                return;
            }
        }
        throw helper.assertionException("no recipe " + id);
    }

    private static ItemOutput output(GameTestHelper helper, StorageMachineRecipe recipe, Item item) {
        for (ItemOutput output : recipe.outputs()) {
            if (output.item().create().is(item)) {
                return output;
            }
        }
        throw helper.assertionException("no " + item + " output in " + recipe.outputs());
    }

    /**
     * Raw tantalite to an advanced signal processor through real machines and loaded recipes: refine, smelt, draw and
     * press capacitors, dope with glowstone, print with the crafted signal mask, assemble. The basic parts (wafers,
     * oscillators, boards, insulated wire) come from {@code machine_chain_to_logic_processor}'s line and are handed in.
     */
    private static void chain(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(FABRICATOR, MachineContent.block(MachineKind.FABRICATOR));
        helper.setBlock(ASSEMBLER, MachineContent.block(MachineKind.ASSEMBLER));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        ProcessingMachineBlockEntity fabricator = MachineChainGameTests.machine(helper, FABRICATOR);
        ProcessingMachineBlockEntity assembler = MachineChainGameTests.machine(helper, ASSEMBLER);
        List<ItemStack> bag = new ArrayList<>();
        helper.startSequence()
                .thenExecute(() -> MachineChainGameTests.feed(fabricator, "refine",
                        new ItemStack(TierTwoMaterialContent.RAW_TANTALITE.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, fabricator,
                        TierTwoMaterialContent.TANTALUM_DUST.get(), 1, bag))
                .thenExecute(() -> {
                    // A niobium dust, if this run rolled one, is not needed here.
                    fabricator.outputs().set(0, ItemResource.EMPTY, 0);
                    fabricator.outputs().set(1, ItemResource.EMPTY, 0);
                    bag.add(MachineChainGameTests.smelt(helper,
                            MachineChainGameTests.take(bag, TierTwoMaterialContent.TANTALUM_DUST.get())));
                    MachineChainGameTests.feed(fabricator, "draw", new ItemStack(Items.COPPER_INGOT));
                })
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, fabricator,
                        MaterialContent.COPPER_WIRE.get(), 3, bag))
                .thenExecute(() -> MachineChainGameTests.feed(fabricator, "press",
                        MachineChainGameTests.take(bag, TierTwoMaterialContent.TANTALUM_INGOT.get()),
                        MachineChainGameTests.take(bag, MaterialContent.COPPER_WIRE.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, fabricator,
                        TierTwoMaterialContent.TANTALUM_CAPACITOR.get(), 2, bag))
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "dope",
                        new ItemStack(MaterialContent.SILICON_WAFER.get()), new ItemStack(Items.GLOWSTONE_DUST)))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        TierTwoMaterialContent.ADVANCED_DOPED_WAFER.get(), 1, bag))
                .thenExecute(() -> {
                    ItemStack iron = new ItemStack(Items.IRON_INGOT);
                    ItemStack redstone = new ItemStack(Items.REDSTONE);
                    ItemStack oscillator = new ItemStack(MaterialContent.OSCILLATOR.get());
                    ItemStack mask = MachineChainGameTests.craft(helper, 3, 3, iron, redstone, iron.copy(), oscillator,
                            new ItemStack(MaterialContent.DISPLAY_GLASS.get()), oscillator.copy(), iron.copy(),
                            redstone.copy(), iron.copy());
                    helper.assertTrue(mask.is(TierTwoMaterialContent.SIGNAL_MASK.get()), "crafted the signal mask");
                    MachineChainGameTests.feed(lithograph, "print",
                            MachineChainGameTests.take(bag, TierTwoMaterialContent.ADVANCED_DOPED_WAFER.get()), mask);
                })
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        TierTwoMaterialContent.ADVANCED_SIGNAL_DIE.get(), 2, bag))
                .thenExecute(() -> MachineChainGameTests.feed(assembler, "",
                        MachineChainGameTests.take(bag, TierTwoMaterialContent.ADVANCED_SIGNAL_DIE.get()),
                        new ItemStack(MaterialContent.INSULATED_COPPER_WIRE.get(), 2),
                        new ItemStack(MaterialContent.OSCILLATOR.get()),
                        MachineChainGameTests.take(bag, TierTwoMaterialContent.TANTALUM_CAPACITOR.get()),
                        new ItemStack(MaterialContent.CIRCUIT_BOARD.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, assembler,
                        TierTwoMaterialContent.ADVANCED_SIGNAL_PROCESSOR.get(), 1, bag))
                .thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    /**
     * With a precision module the lithograph prints 4 dies per wafer instead of 2 (the config's yield of 2), while
     * slicing makes its usual 3 wafers; the module fits only the lithograph.
     */
    private static void precision(GameTestHelper helper) {
        helper.setBlock(LITHOGRAPH, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(FABRICATOR, MachineContent.block(MachineKind.FABRICATOR));
        ProcessingMachineBlockEntity lithograph = MachineChainGameTests.machine(helper, LITHOGRAPH);
        ProcessingMachineBlockEntity fabricator = MachineChainGameTests.machine(helper, FABRICATOR);
        ItemStack module = new ItemStack(ModuleContent.PRECISION_MODULE.get());
        helper.assertValueEqual(insert(fabricator.modules(), module), 0, "the fabricator refuses a precision module");
        helper.assertValueEqual(insert(lithograph.modules(), module), 1, "the lithograph takes a precision module");
        List<ItemStack> bag = new ArrayList<>();
        Item die = MaterialContent.LOGIC_DIE.get();
        helper.startSequence()
                .thenExecute(() -> MachineChainGameTests.feed(lithograph, "print",
                        new ItemStack(MaterialContent.DOPED_WAFER.get()),
                        new ItemStack(MaterialContent.LOGIC_MASK.get())))
                .thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph, die, 1, bag)).thenExecute(() -> {
                    helper.assertValueEqual(count(bag, die), 4, "dies printed with the module");
                    MachineChainGameTests.feed(lithograph, "slice", new ItemStack(MaterialContent.SILICON.get()));
                }).thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph,
                        MaterialContent.SILICON_WAFER.get(), 1, bag))
                .thenExecute(() -> {
                    helper.assertValueEqual(count(bag, MaterialContent.SILICON_WAFER.get()), 3,
                            "slicing ignores the module");
                    lithograph.modules().set(0, ItemResource.EMPTY, 0);
                    bag.clear();
                    MachineChainGameTests.feed(lithograph, "print", new ItemStack(MaterialContent.DOPED_WAFER.get()));
                }).thenWaitUntil(() -> MachineChainGameTests.collect(helper, lithograph, die, 1, bag))
                .thenExecute(() -> helper.assertValueEqual(count(bag, die), 2, "dies printed without the module"))
                .thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    /**
     * A hopper feeding a lithograph cannot put a module anywhere (the module slot is not on any side, the inputs refuse
     * modules), and a hopper under it cannot take the fitted module out.
     */
    private static void moduleSlotRefusesHopper(GameTestHelper helper) {
        helper.setBlock(MACHINE, MachineContent.block(MachineKind.LITHOGRAPH));
        helper.setBlock(INPUT_HOPPER, MachineGameTests.hopper(Direction.EAST));
        helper.setBlock(OUTPUT_HOPPER, MachineGameTests.hopper(Direction.EAST));
        helper.setBlock(CHEST, Blocks.CHEST);
        StorageMachineBlockEntity machine = helper.getBlockEntity(MACHINE, StorageMachineBlockEntity.class);
        Item module = ModuleContent.PRECISION_MODULE.get();
        helper.assertValueEqual(insert(machine.modules(), new ItemStack(module)), 1, "fitted by hand");
        helper.getBlockEntity(INPUT_HOPPER, HopperBlockEntity.class).setItem(0, new ItemStack(module));
        helper.assertValueEqual(insert(machine.items(), new ItemStack(module)), 0, "the sides refuse a module");
        helper.startSequence().thenIdle(60).thenExecute(() -> {
            Container feed = helper.getBlockEntity(INPUT_HOPPER, HopperBlockEntity.class);
            Container below = helper.getBlockEntity(OUTPUT_HOPPER, HopperBlockEntity.class);
            Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
            helper.assertValueEqual(feed.countItem(module), 1, "the module stays in the feeding hopper");
            helper.assertTrue(machine.modules().stack(0).is(module), "the fitted module stays");
            helper.assertTrue(ResourceHandlerUtil.isEmpty(machine.inputs()), "no module in the inputs");
            helper.assertTrue(ResourceHandlerUtil.isEmpty(machine.catalysts()), "no module in the mask slot");
            helper.assertValueEqual(below.countItem(module) + chest.countItem(module), 0, "nothing pulled the module");
        }).thenExecute(() -> MachineGameTests.clear(helper)).thenSucceed();
    }

    static int insert(net.neoforged.neoforge.transfer.ResourceHandler<ItemResource> handler, ItemStack stack) {
        try (Transaction transaction = Transaction.openRoot()) {
            int moved = ResourceHandlerUtil.insertStacking(handler, ItemResource.of(stack), stack.getCount(),
                    transaction);
            transaction.commit();
            return moved;
        }
    }

    private static int count(List<ItemStack> bag, Item item) {
        int total = 0;
        for (ItemStack stack : bag) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
