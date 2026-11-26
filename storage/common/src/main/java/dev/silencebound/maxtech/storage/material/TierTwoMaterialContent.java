package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ModelLayer;
import dev.silencebound.maxtech.lib.content.RecipeContext;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

/**
 * Storage's tier-2 materials (PRD 7.2, tier 2 "Base"): tantalite ore, which the fabricator refines into tantalum and a
 * niobium byproduct, the tantalum capacitor and niobium superconductor wire, the memory, signal and I/O masks with
 * their dies, and the advanced (glowstone-doped) dies and processors of all four families. Crafting and furnace recipes
 * are declared here; the machine steps are data in {@code machine.MachineRecipes}. Everything here is craftable;
 * research gates the machine runs that use the family masks and advanced doping (the recipes' {@code research}).
 */
public final class TierTwoMaterialContent {
    private static final float INGOT_XP = 0.7F;

    /** Deepslate only: tantalite generates below y 0 (M6b decision), so there is no stone variant. */
    public static final BlockEntry<DropExperienceBlock, BlockItem> DEEPSLATE_TANTALITE_ORE = ModContent.CONTENT
            .block("deepslate_tantalite_ore", p -> new DropExperienceBlock(ConstantInt.of(0), p))
            .properties(p -> p.requiresCorrectToolForDrops().mapColor(MapColor.DEEPSLATE).strength(4.5F, 3.0F)
                    .sound(SoundType.DEEPSLATE))
            .simpleItem()
            .model(BlockModel.layeredCube(List.of(ModelLayer.plain(Identifier.withDefaultNamespace("block/deepslate")),
                    ModelLayer.plain(MaxtechStorage.id("block/tantalite_ore_overlay")))))
            .lang("Deepslate Tantalite Ore")
            // Loot is the neoforge datagen's StorageOreLoot: raw tantalite with fortune, the ore with silk touch.
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).tag(BlockTags.NEEDS_IRON_TOOL).tag(MaterialTags.TANTALITE_ORE_BLOCKS)
            .tag(MaterialTags.ORE_BLOCKS).tag(MaterialTags.ORES_IN_DEEPSLATE).itemTag(MaterialTags.TANTALITE_ORES)
            .itemTag(MaterialTags.ORES_IN_DEEPSLATE_ITEMS).creativeTab().register();
    public static final RegistryEntry<Item, Item> RAW_TANTALITE = MaterialContent.material("raw_tantalite",
            "Raw Tantalite", MaterialTags.RAW_TANTALITE,
            MaterialLooks.tinted(MaterialLooks.Form.RAW, MaterialLooks.ROCK));
    public static final RegistryEntry<Item, Item> TANTALUM_DUST = MaterialContent.material("tantalum_dust",
            "Tantalum Dust", MaterialTags.TANTALUM_DUST,
            MaterialLooks.tinted(MaterialLooks.Form.DUST, MaterialLooks.TANTALUM));
    public static final RegistryEntry<Item, Item> NIOBIUM_DUST = MaterialContent.material("niobium_dust",
            "Niobium Dust", MaterialTags.NIOBIUM_DUST,
            MaterialLooks.tinted(MaterialLooks.Form.DUST, MaterialLooks.NIOBIUM));
    public static final RegistryEntry<Item, Item> TANTALUM_INGOT = ingot("tantalum_ingot", "Tantalum Ingot",
            MaterialTags.TANTALUM_INGOT, MaterialTags.TANTALUM_DUST, MaterialLooks.TANTALUM);
    public static final RegistryEntry<Item, Item> NIOBIUM_INGOT = ingot("niobium_ingot", "Niobium Ingot",
            MaterialTags.NIOBIUM_INGOT, MaterialTags.NIOBIUM_DUST, MaterialLooks.NIOBIUM);
    public static final RegistryEntry<Item, Item> TANTALUM_CAPACITOR = component("tantalum_capacitor",
            "Tantalum Capacitor");
    /** Drawn from niobium; no recipe uses it yet, later tiers' cables and memory will. */
    public static final RegistryEntry<Item, Item> SUPERCONDUCTOR_WIRE = component("superconductor_wire",
            "Superconductor Wire");

    public static final RegistryEntry<Item, Item> MEMORY_MASK = mask("memory_mask", "Memory Mask", 'S',
            MaterialTags.SILICON);
    public static final RegistryEntry<Item, Item> SIGNAL_MASK = mask("signal_mask", "Signal Mask", 'O',
            MaterialTags.component("oscillator"));
    public static final RegistryEntry<Item, Item> IO_MASK = mask("io_mask", "I/O Mask", 'P',
            MaterialTags.SIGNAL_ALLOY_PLATE);
    public static final RegistryEntry<Item, Item> MEMORY_DIE = component("memory_die", "Memory Die");
    public static final RegistryEntry<Item, Item> SIGNAL_DIE = component("signal_die", "Signal Die");
    public static final RegistryEntry<Item, Item> IO_DIE = component("io_die", "I/O Die");

    public static final RegistryEntry<Item, Item> ADVANCED_DOPED_WAFER = component("advanced_doped_wafer",
            "Advanced Doped Wafer");
    public static final RegistryEntry<Item, Item> ADVANCED_LOGIC_DIE = component("advanced_logic_die",
            "Advanced Logic Die");
    public static final RegistryEntry<Item, Item> ADVANCED_MEMORY_DIE = component("advanced_memory_die",
            "Advanced Memory Die");
    public static final RegistryEntry<Item, Item> ADVANCED_SIGNAL_DIE = component("advanced_signal_die",
            "Advanced Signal Die");
    public static final RegistryEntry<Item, Item> ADVANCED_IO_DIE = component("advanced_io_die", "Advanced I/O Die");
    public static final RegistryEntry<Item, Item> ADVANCED_LOGIC_PROCESSOR = component("advanced_logic_processor",
            "Advanced Logic Processor");
    public static final RegistryEntry<Item, Item> ADVANCED_MEMORY_PROCESSOR = component("advanced_memory_processor",
            "Advanced Memory Processor");
    public static final RegistryEntry<Item, Item> ADVANCED_SIGNAL_PROCESSOR = component("advanced_signal_processor",
            "Advanced Signal Processor");
    public static final RegistryEntry<Item, Item> ADVANCED_IO_PROCESSOR = component("advanced_io_processor",
            "Advanced I/O Processor");

    static {
        ModContent.CONTENT.itemTag(MaterialTags.ORES, MaterialTags.TANTALITE_ORES);
        ModContent.CONTENT.itemTag(MaterialTags.RAW_MATERIALS, MaterialTags.RAW_TANTALITE);
        ModContent.CONTENT.itemTag(MaterialTags.DUSTS, MaterialTags.TANTALUM_DUST, MaterialTags.NIOBIUM_DUST);
        ModContent.CONTENT.itemTag(MaterialTags.INGOTS, MaterialTags.TANTALUM_INGOT, MaterialTags.NIOBIUM_INGOT);
    }

    private TierTwoMaterialContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    private static RegistryEntry<Item, Item> item(String name, String english, TagKey<Item> tag) {
        return ModContent.CONTENT.item(name, Item::new).model(MaterialContent.flat(name)).lang(english).tag(tag)
                .creativeTab().register();
    }

    private static RegistryEntry<Item, Item> component(String name, String english) {
        return item(name, english, MaterialTags.component(name));
    }

    /** An ingot smelted or blasted from its dust, the furnace half of the refine line. */
    private static RegistryEntry<Item, Item> ingot(String name, String english, TagKey<Item> tag, TagKey<Item> dust,
            int tint) {
        return ModContent.CONTENT.item(name, Item::new).model(MaterialLooks.tinted(MaterialLooks.Form.INGOT, tint))
                .lang(english).tag(tag).recipe((recipes, item) -> MaterialContent.cook(recipes,
                        recipes.ingredient(dust), dust, item, INGOT_XP, name))
                .creativeTab().register();
    }

    /**
     * A family mask: the logic mask's frame (iron, redstone, display glass) with the family's own part on the sides, so
     * the four masks never share a recipe. One per stack, as the logic mask.
     */
    private static RegistryEntry<Item, Item> mask(String name, String english, char key, TagKey<Item> part) {
        return ModContent.CONTENT.item(name, p -> new Item(p.stacksTo(1))).model(MaterialContent.flat(name))
                .lang(english).tag(MaterialTags.component(name))
                .recipe((recipes, item) -> maskRecipe(recipes, item, key, part)).creativeTab().register();
    }

    private static void maskRecipe(RecipeContext recipes, Item item, char key, TagKey<Item> part) {
        TagKey<Item> glass = MaterialTags.component("display_glass");
        recipes.shaped(RecipeCategory.MISC, item).pattern("IRI").pattern(key + "D" + key).pattern("IRI")
                .define('I', MaterialTags.IRON_INGOT).define('R', MaterialTags.REDSTONE).define('D', glass)
                .define(key, part).unlockedBy("has_display_glass", recipes.has(glass)).save(recipes.output());
    }
}
