package dev.silencebound.maxtech.storage.neoforge.gametest;

import dev.silencebound.maxtech.lib.content.ItemDeclaration;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * Storage's materials: every item carries the tags it declares, and the steps a crafting table or furnace makes (the
 * blends, the furnace steps, the kerf back to silicon, the masks and insulated wire, tantalum and niobium ingots) load
 * and give what the line needs; the ores drop what they should. Crafting recipes are matched through the vanilla
 * Crafter's own lookup, so they are automatable.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
public final class MaterialGameTests {
    static {
        StorageTests.function("material_items_tagged", MaterialGameTests::itemsTagged);
        StorageTests.function("material_furnace_steps", MaterialGameTests::furnaceSteps);
        StorageTests.function("material_crafting_steps", MaterialGameTests::craftingSteps);
        StorageTests.function("material_ore_drops_quartz", MaterialGameTests::oreDropsQuartz);
        StorageTests.function("material_tantalite_ore_drops_raw", MaterialGameTests::tantaliteDropsRaw);
        StorageTests.function("material_tier_two_steps", MaterialGameTests::tierTwoSteps);
    }

    private MaterialGameTests() {
    }

    @SubscribeEvent
    static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(MaxtechStorage.id("material"));
        for (String name : List.of("material_items_tagged", "material_furnace_steps", "material_crafting_steps",
                "material_ore_drops_quartz", "material_tantalite_ore_drops_raw", "material_tier_two_steps")) {
            StorageTests.test(event, environment, name, StorageTests.SMALL, 20);
        }
    }

    /** Every declared item is in every tag its chain names, so recipes reading those tags find it. */
    private static void itemsTagged(GameTestHelper helper) {
        int checked = 0;
        for (ItemDeclaration<?> declaration : ModContent.CONTENT.items()) {
            Item item = declaration.entry().get();
            for (TagKey<Item> tag : declaration.tags()) {
                helper.assertTrue(item.builtInRegistryHolder().is(tag), item + " in " + tag.location());
                checked++;
            }
        }
        helper.assertTrue(checked > 0, "no tags checked");
        helper.succeed();
    }

    private static void furnaceSteps(GameTestHelper helper) {
        smelts(helper, MaterialContent.LUMEN_QUARTZ_ORE.item().get(), MaterialContent.LUMEN_QUARTZ.get());
        smelts(helper, MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.item().get(), MaterialContent.LUMEN_QUARTZ.get());
        smelts(helper, MaterialContent.SILICON_BLEND.get(), MaterialContent.SILICON.get());
        smelts(helper, MaterialContent.SILICON_KERF.get(), MaterialContent.SILICON.get());
        smelts(helper, MaterialContent.SIGNAL_ALLOY_BLEND.get(), MaterialContent.SIGNAL_ALLOY_INGOT.get());
        smelts(helper, MaterialContent.DISPLAY_GLASS_BLEND.get(), MaterialContent.DISPLAY_GLASS.get());
        helper.succeed();
    }

    private static void craftingSteps(GameTestHelper helper) {
        crafts(helper, 2, 1, List.of(stack(MaterialContent.LUMEN_QUARTZ.get()), new ItemStack(Items.CHARCOAL)),
                MaterialContent.SILICON_BLEND.get(), 1);
        crafts(helper, 2, 2,
                List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT),
                        stack(MaterialContent.LUMEN_QUARTZ_DUST.get()), stack(MaterialContent.LUMEN_QUARTZ_DUST.get())),
                MaterialContent.SIGNAL_ALLOY_BLEND.get(), 2);
        crafts(helper, 2, 1, List.of(stack(MaterialContent.LUMEN_QUARTZ_DUST.get()), new ItemStack(Items.GLASS)),
                MaterialContent.DISPLAY_GLASS_BLEND.get(), 1);
        ItemStack wire = stack(MaterialContent.COPPER_WIRE.get());
        crafts(helper, 3, 1, List.of(wire, wire.copy(), new ItemStack(Items.DRIED_KELP)),
                MaterialContent.INSULATED_COPPER_WIRE.get(), 2);
        crafts(helper, 3, 1, List.of(wire.copy(), wire.copy(), new ItemStack(Items.GRAY_WOOL)),
                MaterialContent.INSULATED_COPPER_WIRE.get(), 2);
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        crafts(helper, 3, 3,
                List.of(iron, redstone, iron.copy(), redstone.copy(), stack(MaterialContent.DISPLAY_GLASS.get()),
                        redstone.copy(), iron.copy(), redstone.copy(), iron.copy()),
                MaterialContent.LOGIC_MASK.get(), 1);
        helper.succeed();
    }

    /** Mined with an iron pickaxe, the ore gives lumen quartz, not itself. */
    private static void oreDropsQuartz(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        for (Block ore : List.of(MaterialContent.LUMEN_QUARTZ_ORE.get(),
                MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.get())) {
            BlockState state = ore.defaultBlockState();
            List<ItemStack> drops = Block.getDrops(state, level, pos, null, null, new ItemStack(Items.IRON_PICKAXE));
            helper.assertValueEqual(drops.size(), 1, ore + " drop stacks");
            helper.assertTrue(drops.getFirst().is(MaterialContent.LUMEN_QUARTZ.get()), ore + " drops lumen quartz");
            helper.assertTrue(state.requiresCorrectToolForDrops(), ore + " needs a pickaxe");
        }
        helper.succeed();
    }

    /**
     * Tantalite needs an iron pickaxe and gives raw tantalite, or itself with silk touch; it exists only as a deepslate
     * ore.
     */
    private static void tantaliteDropsRaw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        Block ore = TierTwoMaterialContent.DEEPSLATE_TANTALITE_ORE.get();
        BlockState state = ore.defaultBlockState();
        helper.assertTrue(state.requiresCorrectToolForDrops() && state.is(BlockTags.NEEDS_IRON_TOOL),
                "tantalite needs an iron pickaxe");
        helper.assertTrue(state.is(MaterialTags.ORES_IN_DEEPSLATE), "tantalite is a deepslate ore");
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, null, new ItemStack(Items.IRON_PICKAXE));
        helper.assertValueEqual(drops.size(), 1, "drop stacks");
        helper.assertTrue(drops.getFirst().is(TierTwoMaterialContent.RAW_TANTALITE.get()), "drops raw tantalite");
        ItemStack silk = new ItemStack(Items.IRON_PICKAXE);
        silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH),
                1);
        List<ItemStack> silkDrops = Block.getDrops(state, level, pos, null, null, silk);
        helper.assertTrue(silkDrops.size() == 1 && silkDrops.getFirst().is(ore.asItem()), "silk touch keeps the ore");
        helper.succeed();
    }

    /** Tantalum and niobium dust smelt to their ingots; the family masks are crafted from their own parts. */
    private static void tierTwoSteps(GameTestHelper helper) {
        smelts(helper, TierTwoMaterialContent.TANTALUM_DUST.get(), TierTwoMaterialContent.TANTALUM_INGOT.get());
        smelts(helper, TierTwoMaterialContent.NIOBIUM_DUST.get(), TierTwoMaterialContent.NIOBIUM_INGOT.get());
        ServerLevel level = helper.getLevel();
        for (Item dust : List.of(TierTwoMaterialContent.TANTALUM_DUST.get(),
                TierTwoMaterialContent.NIOBIUM_DUST.get())) {
            helper.assertTrue(level.recipeAccess()
                    .getRecipeFor(RecipeType.BLASTING, new SingleRecipeInput(new ItemStack(dust)), level).isPresent(),
                    "a blast furnace smelts " + dust);
        }
        mask(helper, stack(MaterialContent.SILICON.get()), TierTwoMaterialContent.MEMORY_MASK.get());
        mask(helper, stack(MaterialContent.OSCILLATOR.get()), TierTwoMaterialContent.SIGNAL_MASK.get());
        mask(helper, stack(MaterialContent.CASING_PLATE.get()), TierTwoMaterialContent.IO_MASK.get());
        helper.succeed();
    }

    /** The mask frame (iron corners, redstone top and bottom, display glass) with {@code part} on both sides. */
    private static void mask(GameTestHelper helper, ItemStack part, Item mask) {
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        crafts(helper, 3, 3, List.of(iron, redstone, iron.copy(), part, stack(MaterialContent.DISPLAY_GLASS.get()),
                part.copy(), iron.copy(), redstone.copy(), iron.copy()), mask, 1);
    }

    private static void smelts(GameTestHelper helper, Item input, Item output) {
        ServerLevel level = helper.getLevel();
        Optional<RecipeHolder<SmeltingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.SMELTING,
                new SingleRecipeInput(new ItemStack(input)), level);
        helper.assertTrue(recipe.isPresent(), "a furnace smelts " + input);
        helper.assertTrue(recipe.orElseThrow().value().assemble(new SingleRecipeInput(new ItemStack(input))).is(output),
                input + " smelts to " + output);
    }

    private static void crafts(GameTestHelper helper, int width, int height, List<ItemStack> grid, Item output,
            int count) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(width, height, new ArrayList<>(grid));
        Optional<RecipeHolder<CraftingRecipe>> recipe = CrafterBlock.getPotentialResults(level, input);
        helper.assertTrue(recipe.isPresent(), "a crafting recipe makes " + output + " from " + grid);
        ItemStack result = recipe.orElseThrow().value().assemble(input);
        helper.assertTrue(result.is(output), "crafted " + result + ", wanted " + output);
        helper.assertValueEqual(result.getCount(), count, output + " count");
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(item);
    }
}
