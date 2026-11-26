package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.content.ModelLayer;
import dev.silencebound.maxtech.lib.content.RecipeContext;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Storage's own materials and the components made from them (PRD 7.2, "Materials, machines and progression"): lumen
 * quartz and its ores, the silicon line, the metal line, the crystal basics and the basic processors of the four
 * families. Recipes declared here are the ones a crafting table or furnace makes, so the vanilla Crafter and furnaces
 * automate them; the machine recipes are data in {@code machine}. Furnace steps with two inputs go through a blend
 * crafted first, since a furnace takes one item.
 */
public final class MaterialContent {
    private static final float ORE_XP = 0.2F;
    static final float BLEND_XP = 0.1F;
    private static final int SMELTING_TICKS = 200;
    private static final int BLASTING_TICKS = 100;

    public static final BlockEntry<DropExperienceBlock, BlockItem> LUMEN_QUARTZ_ORE = ore("lumen_quartz_ore",
            "Lumen Quartz Ore", false);
    public static final BlockEntry<DropExperienceBlock, BlockItem> DEEPSLATE_LUMEN_QUARTZ_ORE = ore(
            "deepslate_lumen_quartz_ore", "Deepslate Lumen Quartz Ore", true);

    public static final RegistryEntry<Item, Item> LUMEN_QUARTZ = ModContent.CONTENT.item("lumen_quartz", Item::new)
            .model(MaterialLooks.tinted(MaterialLooks.Form.GEM, MaterialLooks.LUMEN)).lang("Lumen Quartz")
            .tag(MaterialTags.LUMEN_QUARTZ).recipe((recipes, item) -> {
                Ingredient ores = recipes.ingredient(MaterialTags.LUMEN_QUARTZ_ORES);
                cook(recipes, ores, MaterialTags.LUMEN_QUARTZ_ORES, item, ORE_XP, "lumen_quartz_from_ore");
            }).creativeTab().register();
    public static final RegistryEntry<Item, Item> LUMEN_QUARTZ_DUST = material("lumen_quartz_dust", "Lumen Quartz Dust",
            MaterialTags.LUMEN_QUARTZ_DUST, MaterialLooks.tinted(MaterialLooks.Form.DUST, MaterialLooks.LUMEN));

    public static final RegistryEntry<Item, Item> SILICON_BLEND = component("silicon_blend", "Silicon Blend",
            (recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(MaterialTags.LUMEN_QUARTZ)
                    .requires(MaterialTags.COALS).unlockedBy("has_lumen_quartz", recipes.has(MaterialTags.LUMEN_QUARTZ))
                    .save(recipes.output()));
    public static final RegistryEntry<Item, Item> SILICON = ModContent.CONTENT.item("silicon", Item::new)
            .model(flat("silicon")).lang("Silicon").tag(MaterialTags.SILICON).recipe((recipes, item) -> {
                TagKey<Item> blend = MaterialTags.component("silicon_blend");
                cook(recipes, recipes.ingredient(blend), blend, item, BLEND_XP, "silicon");
                // Kerf from slicing goes back into the furnace, so slicing loses nothing in the long run.
                SimpleCookingRecipeBuilder
                        .smelting(recipes.ingredient(MaterialTags.SILICON_DUST), RecipeCategory.MISC,
                                CookingBookCategory.MISC, item, BLEND_XP, SMELTING_TICKS)
                        .unlockedBy("has_kerf", recipes.has(MaterialTags.SILICON_DUST))
                        .save(recipes.output(), key("silicon_from_kerf"));
            }).creativeTab().register();
    public static final RegistryEntry<Item, Item> SILICON_KERF = material("silicon_kerf", "Silicon Kerf",
            MaterialTags.SILICON_DUST, MaterialLooks.tinted(MaterialLooks.Form.DUST, MaterialLooks.SILICON));
    public static final RegistryEntry<Item, Item> SILICON_WAFER = component("silicon_wafer", "Silicon Wafer");
    public static final RegistryEntry<Item, Item> DOPED_WAFER = component("doped_wafer", "Doped Wafer");
    /** Reusable: print recipes take it as a catalyst. The start node of research, so it stays crafted. */
    public static final RegistryEntry<Item, Item> LOGIC_MASK = ModContent.CONTENT
            .item("logic_mask", p -> new Item(p.stacksTo(1))).model(flat("logic_mask")).lang("Logic Mask")
            .tag(MaterialTags.component("logic_mask"))
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("IRI").pattern("RDR")
                    .pattern("IRI").define('I', MaterialTags.IRON_INGOT).define('R', MaterialTags.REDSTONE)
                    .define('D', MaterialTags.component("display_glass"))
                    .unlockedBy("has_display_glass", recipes.has(MaterialTags.component("display_glass")))
                    .save(recipes.output()))
            .creativeTab().register();
    public static final RegistryEntry<Item, Item> LOGIC_DIE = component("logic_die", "Logic Die");

    public static final RegistryEntry<Item, Item> COPPER_WIRE = component("copper_wire", "Copper Wire",
            MaterialTags.COPPER_WIRE);
    public static final RegistryEntry<Item, Item> INSULATED_COPPER_WIRE = component("insulated_copper_wire",
            "Insulated Copper Wire", (recipes, item) -> {
                recipes.shapeless(RecipeCategory.MISC, item, 2).requires(MaterialTags.COPPER_WIRE)
                        .requires(MaterialTags.COPPER_WIRE).requires(Items.DRIED_KELP)
                        .unlockedBy("has_copper_wire", recipes.has(MaterialTags.COPPER_WIRE)).save(recipes.output());
                recipes.shapeless(RecipeCategory.MISC, item, 2).requires(MaterialTags.COPPER_WIRE)
                        .requires(MaterialTags.COPPER_WIRE).requires(ItemTags.WOOL)
                        .unlockedBy("has_copper_wire", recipes.has(MaterialTags.COPPER_WIRE))
                        .save(recipes.output(), key("insulated_copper_wire_from_wool"));
            });
    public static final RegistryEntry<Item, Item> SIGNAL_ALLOY_BLEND = component("signal_alloy_blend",
            "Signal Alloy Blend",
            (recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item, 2).requires(MaterialTags.IRON_INGOT)
                    .requires(MaterialTags.COPPER_INGOT).requires(MaterialTags.LUMEN_QUARTZ_DUST)
                    .requires(MaterialTags.LUMEN_QUARTZ_DUST)
                    .unlockedBy("has_lumen_quartz_dust", recipes.has(MaterialTags.LUMEN_QUARTZ_DUST))
                    .save(recipes.output()));
    public static final RegistryEntry<Item, Item> SIGNAL_ALLOY_INGOT = ModContent.CONTENT
            .item("signal_alloy_ingot", Item::new)
            .model(MaterialLooks.tinted(MaterialLooks.Form.INGOT, MaterialLooks.SIGNAL)).lang("Signal Alloy Ingot")
            .tag(MaterialTags.SIGNAL_ALLOY_INGOT).recipe((recipes, item) -> {
                TagKey<Item> blend = MaterialTags.component("signal_alloy_blend");
                cook(recipes, recipes.ingredient(blend), blend, item, BLEND_XP, "signal_alloy_ingot");
            }).creativeTab().register();
    public static final RegistryEntry<Item, Item> CASING_PLATE = component("casing_plate", "Casing Plate",
            MaterialTags.SIGNAL_ALLOY_PLATE);
    public static final RegistryEntry<Item, Item> GLASS_FIBRE = component("glass_fibre", "Glass Fibre");
    public static final RegistryEntry<Item, Item> CIRCUIT_BOARD = component("circuit_board", "Circuit Board");

    public static final RegistryEntry<Item, Item> OSCILLATOR = component("oscillator", "Oscillator");
    public static final RegistryEntry<Item, Item> DISPLAY_GLASS_BLEND = component("display_glass_blend",
            "Display Glass Blend",
            (recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(MaterialTags.LUMEN_QUARTZ_DUST)
                    .requires(MaterialTags.GLASS_BLOCKS)
                    .unlockedBy("has_lumen_quartz_dust", recipes.has(MaterialTags.LUMEN_QUARTZ_DUST))
                    .save(recipes.output()));
    public static final RegistryEntry<Item, Item> DISPLAY_GLASS = component("display_glass", "Display Glass",
            (recipes, item) -> {
                TagKey<Item> blend = MaterialTags.component("display_glass_blend");
                cook(recipes, recipes.ingredient(blend), blend, item, BLEND_XP, "display_glass");
            });

    public static final RegistryEntry<Item, Item> BASIC_LOGIC_PROCESSOR = component("basic_logic_processor",
            "Basic Logic Processor");
    public static final RegistryEntry<Item, Item> BASIC_MEMORY_PROCESSOR = component("basic_memory_processor",
            "Basic Memory Processor");
    public static final RegistryEntry<Item, Item> BASIC_SIGNAL_PROCESSOR = component("basic_signal_processor",
            "Basic Signal Processor");
    public static final RegistryEntry<Item, Item> BASIC_IO_PROCESSOR = component("basic_io_processor",
            "Basic I/O Processor");

    static {
        ModContent.CONTENT.itemTag(MaterialTags.ORES, MaterialTags.LUMEN_QUARTZ_ORES);
        ModContent.CONTENT.itemTag(MaterialTags.GEMS, MaterialTags.LUMEN_QUARTZ);
        ModContent.CONTENT.itemTag(MaterialTags.DUSTS, MaterialTags.LUMEN_QUARTZ_DUST, MaterialTags.SILICON_DUST);
        ModContent.CONTENT.itemTag(MaterialTags.INGOTS, MaterialTags.SIGNAL_ALLOY_INGOT);
        ModContent.CONTENT.itemTag(MaterialTags.PLATES, MaterialTags.SIGNAL_ALLOY_PLATE);
        ModContent.CONTENT.itemTag(MaterialTags.WIRES, MaterialTags.COPPER_WIRE);
    }

    private MaterialContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    private static BlockEntry<DropExperienceBlock, BlockItem> ore(String name, String english, boolean deepslate) {
        // Vanilla's nether quartz ore gives 2 to 5 experience; lumen quartz is commoner, so a little less.
        Function<BlockBehaviour.Properties, DropExperienceBlock> factory = p -> new DropExperienceBlock(
                UniformInt.of(1, 3), p);
        return ModContent.CONTENT.block(name, factory).properties(p -> deepslate
                ? p.requiresCorrectToolForDrops().mapColor(MapColor.DEEPSLATE).strength(4.5F, 3.0F)
                        .sound(SoundType.DEEPSLATE)
                : p.requiresCorrectToolForDrops().mapColor(MapColor.STONE).strength(3.0F, 3.0F).sound(SoundType.STONE))
                .simpleItem()
                .model(BlockModel.layeredCube(List.of(
                        ModelLayer
                                .plain(Identifier.withDefaultNamespace(deepslate ? "block/deepslate" : "block/stone")),
                        ModelLayer.plain(MaxtechStorage.id("block/lumen_quartz_ore_overlay")))))
                .lang(english)
                // Loot is the neoforge datagen's StorageOreLoot: fortune needs the enchantment registry.
                .tag(BlockTags.MINEABLE_WITH_PICKAXE).tag(BlockTags.NEEDS_STONE_TOOL)
                .tag(MaterialTags.LUMEN_QUARTZ_ORE_BLOCKS).tag(MaterialTags.ORE_BLOCKS)
                .tag(deepslate ? MaterialTags.ORES_IN_DEEPSLATE : MaterialTags.ORES_IN_STONE)
                .itemTag(MaterialTags.LUMEN_QUARTZ_ORES)
                .itemTag(deepslate ? MaterialTags.ORES_IN_DEEPSLATE_ITEMS : MaterialTags.ORES_IN_STONE_ITEMS)
                .creativeTab().register();
    }

    private static RegistryEntry<Item, Item> component(String name, String english) {
        return component(name, english, MaterialTags.component(name));
    }

    private static RegistryEntry<Item, Item> component(String name, String english, TagKey<Item> tag) {
        return material(name, english, tag, flat(name));
    }

    /** An item without a recipe of its own, drawn with {@code model}. */
    static RegistryEntry<Item, Item> material(String name, String english, TagKey<Item> tag, ItemModel model) {
        return ModContent.CONTENT.item(name, Item::new).model(model).lang(english).tag(tag).creativeTab().register();
    }

    private static RegistryEntry<Item, Item> component(String name, String english, ContentRecipe<Item> recipe) {
        return ModContent.CONTENT.item(name, Item::new).model(flat(name)).lang(english)
                .tag(MaterialTags.component(name)).recipe(recipe).creativeTab().register();
    }

    static ItemModel flat(String name) {
        return ItemModel.flat(MaxtechStorage.id("item/" + name));
    }

    /** Smelting and blasting {@code ingredient} (the items of {@code unlock}) into {@code result}. */
    static void cook(RecipeContext recipes, Ingredient ingredient, TagKey<Item> unlock, Item result, float experience,
            String name) {
        SimpleCookingRecipeBuilder
                .smelting(ingredient, RecipeCategory.MISC, CookingBookCategory.MISC, result, experience, SMELTING_TICKS)
                .unlockedBy("has_input", recipes.has(unlock)).save(recipes.output(), key(name + "_smelting"));
        SimpleCookingRecipeBuilder
                .blasting(ingredient, RecipeCategory.MISC, CookingBookCategory.MISC, result, experience, BLASTING_TICKS)
                .unlockedBy("has_input", recipes.has(unlock)).save(recipes.output(), key(name + "_blasting"));
    }

    static ResourceKey<Recipe<?>> key(String name) {
        return ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name));
    }
}
