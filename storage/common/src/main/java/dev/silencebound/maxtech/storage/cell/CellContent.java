package dev.silencebound.maxtech.storage.cell;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.content.RecipeContext;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import java.util.List;
import java.util.function.LongSupplier;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import org.jspecify.annotations.Nullable;

/**
 * Cells and their parts (PRD 7.2, the cell builder): the item and fluid cell types, their controller chips, memory
 * modules I to III, the five casings with their cell items, and the four add-ons. The small storage cell also has the
 * starter recipes, which build it as an item or a fluid cell from a chip, a memory module and casing parts (M6a); every
 * other cell is built in the cell workbench (M6b).
 */
public final class CellContent {
    private static final RegistryHelper.Registrar<CellType> TYPES = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, CellTypes.REGISTRY_KEY);

    public static final RegistryEntry<CellType, CellType> ITEM_TYPE = TYPES.register("item",
            () -> new CellType(StackKey.Kind.ITEM, StorageServerConfig.CELL_ITEMS_PER_BYTE));
    public static final RegistryEntry<CellType, CellType> FLUID_TYPE = TYPES.register("fluid",
            () -> new CellType(StackKey.Kind.FLUID, StorageServerConfig.CELL_FLUID_MB_PER_BYTE));

    public static final TagKey<Item> ITEM_CONTROLLER_CHIP_TAG = tag("item_controller_chip");
    public static final TagKey<Item> FLUID_CONTROLLER_CHIP_TAG = tag("fluid_controller_chip");
    public static final TagKey<Item> MEMORY_MODULE_1_TAG = tag("memory_module_1");
    public static final TagKey<Item> EMPTY_BUCKETS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "buckets/empty"));

    public static final RegistryEntry<Item, ControllerChipItem> ITEM_CONTROLLER_CHIP = chip("item_controller_chip",
            "Item Controller Chip", ITEM_TYPE.id(), ITEM_CONTROLLER_CHIP_TAG, ConventionTags.CHESTS);
    public static final RegistryEntry<Item, ControllerChipItem> FLUID_CONTROLLER_CHIP = chip("fluid_controller_chip",
            "Fluid Controller Chip", FLUID_TYPE.id(), FLUID_CONTROLLER_CHIP_TAG, EMPTY_BUCKETS);
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_1 = ModContent.CONTENT
            .item("memory_module_1", p -> new MemoryModuleItem(p, StorageServerConfig.MEMORY_1_BYTES))
            .model(flat("memory_module_1")).lang("Memory Module I").tag(MEMORY_MODULE_1_TAG)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("MRM").pattern("WBW")
                    .define('M', MaterialTags.component("basic_memory_processor")).define('R', MaterialTags.REDSTONE)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('B', MaterialTags.component("circuit_board"))
                    .unlockedBy("has_memory_processor", recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();
    /** The starter cell: a small casing with one memory module I. Without a component it is an item cell. */
    public static final RegistryEntry<Item, StorageCellItem> SMALL_STORAGE_CELL = ModContent.CONTENT
            .item("small_storage_cell",
                    p -> new StorageCellItem(p.component(ModDataComponents.CELL.get(), starter(ITEM_TYPE.id())),
                            CellCasing.SMALL))
            .model(flat("small_storage_cell")).lang("Small %s Storage Cell").recipe((recipes, item) -> {
                starterRecipe(recipes, item, ITEM_TYPE.id(), ITEM_CONTROLLER_CHIP_TAG, "small_item_storage_cell");
                starterRecipe(recipes, item, FLUID_TYPE.id(), FLUID_CONTROLLER_CHIP_TAG, "small_fluid_storage_cell");
            }).creativeTab().register();

    public static final TagKey<Item> MEMORY_MODULE_2_TAG = tag("memory_module_2");
    public static final TagKey<Item> MEMORY_MODULE_3_TAG = tag("memory_module_3");
    public static final TagKey<Item> MEMORY_MODULE_4_TAG = tag("memory_module_4");
    public static final TagKey<Item> GOLD_INGOTS = c("ingots/gold");
    public static final TagKey<Item> OBSIDIANS = c("obsidians");
    public static final TagKey<Item> ENDER_PEARLS = c("ender_pearls");

    /** Memory module II: {@code MPM / CBC}, two modules I round an advanced memory processor, tantalum capacitors. */
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_2 = memory(2, "Memory Module II",
            StorageServerConfig.MEMORY_2_BYTES, MEMORY_MODULE_2_TAG, MEMORY_MODULE_1_TAG, "MPM", "CBC");
    /**
     * Memory module III: {@code MPM / SBS / MCM}, four modules II round an advanced memory processor, with niobium
     * superconductor wire and a tantalum capacitor.
     */
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_3 = memory(3, "Memory Module III",
            StorageServerConfig.MEMORY_3_BYTES, MEMORY_MODULE_3_TAG, MEMORY_MODULE_2_TAG, "MPM", "SBS", "MCM");
    /**
     * Memory module IV (M6d): {@code MSM / CPC / MBM}, four modules III round an elite memory processor, with
     * superconductor wire, tantalum capacitors and a board.
     */
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_4 = memoryWith(4, "Memory Module IV",
            StorageServerConfig.MEMORY_4_BYTES, MEMORY_MODULE_4_TAG, MEMORY_MODULE_3_TAG, "elite_memory_processor",
            "MSM", "CPC", "MBM");
    public static final TagKey<Item> MEMORY_MODULE_5_TAG = tag("memory_module_5");
    public static final TagKey<Item> MEMORY_MODULE_6_TAG = tag("memory_module_6");
    /**
     * Memory module V (M6f, 1 MB): {@code LPL / EBE / LPL}, void lattice round elite memory processors, two elite logic
     * processors and a board. No lower module goes in (owner decision F2).
     */
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_5 = memoryWith(5, "Memory Module V",
            StorageServerConfig.MEMORY_5_BYTES, MEMORY_MODULE_5_TAG, MEMORY_MODULE_4_TAG, "elite_memory_processor",
            "LPL", "EBE", "LPL");
    /** Memory module VI (M6f, 16 MB): {@code MLM / LLL / MLM}, four modules V and five void lattices. */
    public static final RegistryEntry<Item, MemoryModuleItem> MEMORY_MODULE_6 = memoryWith(6, "Memory Module VI",
            StorageServerConfig.MEMORY_6_BYTES, MEMORY_MODULE_6_TAG, MEMORY_MODULE_5_TAG, "elite_memory_processor",
            "MLM", "LLL", "MLM");

    public static final RegistryEntry<Item, CellCasingItem> SMALL_CELL_CASING = casing(CellCasing.SMALL,
            "Small Cell Casing", MaterialTags.IRON_INGOT, null);
    public static final RegistryEntry<Item, CellCasingItem> MEDIUM_CELL_CASING = casing(CellCasing.MEDIUM,
            "Medium Cell Casing", GOLD_INGOTS, CellCasing.SMALL);
    public static final RegistryEntry<Item, CellCasingItem> LARGE_CELL_CASING = casing(CellCasing.LARGE,
            "Large Cell Casing", ConventionTags.DIAMOND_GEMS, CellCasing.MEDIUM);
    public static final RegistryEntry<Item, CellCasingItem> BULK_CELL_CASING = casing(CellCasing.BULK,
            "Bulk Cell Casing", OBSIDIANS, CellCasing.MEDIUM);
    public static final RegistryEntry<Item, CellCasingItem> PORTABLE_CELL_CASING = casing(CellCasing.PORTABLE,
            "Portable Cell Casing", ConventionTags.REDSTONE_STORAGE_BLOCKS, CellCasing.SMALL);

    public static final RegistryEntry<Item, StorageCellItem> MEDIUM_STORAGE_CELL = cell(CellCasing.MEDIUM,
            "Medium %s Storage Cell");
    public static final RegistryEntry<Item, StorageCellItem> LARGE_STORAGE_CELL = cell(CellCasing.LARGE,
            "Large %s Storage Cell");
    public static final RegistryEntry<Item, StorageCellItem> BULK_STORAGE_CELL = cell(CellCasing.BULK,
            "Bulk %s Storage Cell");
    public static final RegistryEntry<Item, StorageCellItem> PORTABLE_STORAGE_CELL = cell(CellCasing.PORTABLE,
            "Portable %s Storage Cell");

    public static final RegistryEntry<Item, CellAddonItem> PARTITION_ADDON = addon(CellAddon.PARTITION,
            "Partition Filter Add-on", MaterialTags.LUMEN_QUARTZ, MaterialTags.REDSTONE);
    public static final RegistryEntry<Item, CellAddonItem> VOID_OVERFLOW_ADDON = addon(CellAddon.VOID_OVERFLOW,
            "Void Overflow Add-on", OBSIDIANS, ENDER_PEARLS);
    public static final RegistryEntry<Item, CellAddonItem> READ_ONLY_ADDON = addon(CellAddon.READ_ONLY,
            "Read-Only Lock Add-on", MaterialTags.IRON_INGOT, OBSIDIANS);

    static {
        ModContent.CONTENT.translation(CellTypes.translationKey(ITEM_TYPE.id()), "Item");
        ModContent.CONTENT.translation(CellTypes.translationKey(FLUID_TYPE.id()), "Fluid");
        ModContent.CONTENT.translation(StorageCellItem.BYTES_KEY, "%s / %s bytes used");
        ModContent.CONTENT.translation(StorageCellItem.KINDS_KEY, "%s kinds");
        ModContent.CONTENT.translation(StorageCellItem.KEPT_KEY, "%s unknown entries kept (mod missing)");
        ModContent.CONTENT.translation(StorageCellItem.ID_KEY, "Cell id %s");
        ModContent.CONTENT.translation(StorageCellItem.MEMORY_KEY, "Memory modules: %s / %s");
        ModContent.CONTENT.translation(StorageCellItem.BULK_KEY, "Bulk: one kind only");
        ModContent.CONTENT.translation(StorageCellItem.ADDONS_KEY, "Add-on: %s");
        ModContent.CONTENT.translation(StorageCellItem.FILTER_KEY, "Filter: %s entries");
        ModContent.CONTENT.translation(StorageCellItem.FUZZY_KEY, "Filter: %s entries, fuzzy");
        ModContent.CONTENT.translation(StorageCellItem.DENY_KEY, "Deny: %s entries");
        ModContent.CONTENT.translation(StorageCellItem.DENY_FUZZY_KEY, "Deny: %s entries, fuzzy");
        ModContent.CONTENT.translation(PortableCellItem.ENERGY_KEY, "%s / %s MU");
        ModContent.CONTENT.translation(PortableCellItem.IN_USE_KEY, "This cell is in use elsewhere");
    }

    private CellContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** A new starter cell's data: {@code type} with one memory module I. */
    public static CellData starter(Identifier type) {
        return CellData.of(type, List.of(MEMORY_MODULE_1.get()));
    }

    /** The cell item of {@code casing}. */
    public static StorageCellItem cellItem(CellCasing casing) {
        return switch (casing) {
            case SMALL -> SMALL_STORAGE_CELL.get();
            case MEDIUM -> MEDIUM_STORAGE_CELL.get();
            case LARGE -> LARGE_STORAGE_CELL.get();
            case BULK -> BULK_STORAGE_CELL.get();
            case PORTABLE -> PORTABLE_STORAGE_CELL.get();
        };
    }

    /** The empty casing item of {@code casing}. */
    public static CellCasingItem casingItem(CellCasing casing) {
        return switch (casing) {
            case SMALL -> SMALL_CELL_CASING.get();
            case MEDIUM -> MEDIUM_CELL_CASING.get();
            case LARGE -> LARGE_CELL_CASING.get();
            case BULK -> BULK_CELL_CASING.get();
            case PORTABLE -> PORTABLE_CELL_CASING.get();
        };
    }

    /** The tag of the empty casing item of {@code casing}, which recipes name. */
    public static TagKey<Item> casingTag(CellCasing casing) {
        return tag(casing.id() + "_cell_casing");
    }

    /**
     * Memory module {@code tier} from {@code rows}: {@code M} the module below, {@code P} an advanced memory processor,
     * {@code B} a circuit board, {@code C} a tantalum capacitor, {@code S} superconductor wire, {@code L} a void
     * lattice, {@code E} an elite logic processor.
     */
    private static RegistryEntry<Item, MemoryModuleItem> memory(int tier, String english, LongSupplier bytes,
            TagKey<Item> tag, TagKey<Item> below, String... rows) {
        return memoryWith(tier, english, bytes, tag, below, "advanced_memory_processor", rows);
    }

    /** Memory module {@code tier} as above, with {@code P} the processor component {@code processor}. */
    private static RegistryEntry<Item, MemoryModuleItem> memoryWith(int tier, String english, LongSupplier bytes,
            TagKey<Item> tag, TagKey<Item> below, String processor, String... rows) {
        String name = "memory_module_" + tier;
        return ModContent.CONTENT.item(name, p -> new MemoryModuleItem(p, bytes)).model(flat(name)).lang(english)
                .tag(tag).recipe((recipes, item) -> {
                    ShapedRecipeBuilder recipe = recipes.shaped(RecipeCategory.MISC, item);
                    String keys = String.join("", rows);
                    for (String row : rows) {
                        recipe.pattern(row);
                    }
                    // A key the pattern does not use fails the recipe, so only the used ones are defined.
                    defineIfUsed(recipe, keys, 'M', below);
                    defineIfUsed(recipe, keys, 'P', MaterialTags.component(processor));
                    defineIfUsed(recipe, keys, 'B', MaterialTags.component("circuit_board"));
                    defineIfUsed(recipe, keys, 'C', MaterialTags.component("tantalum_capacitor"));
                    defineIfUsed(recipe, keys, 'S', MaterialTags.component("superconductor_wire"));
                    defineIfUsed(recipe, keys, 'L', MaterialTags.component("void_lattice"));
                    defineIfUsed(recipe, keys, 'E', MaterialTags.component("elite_logic_processor"));
                    // Modules V and VI are void tier: they load only while the void tier is on.
                    recipe.unlockedBy("has_lower_memory", recipes.has(below))
                            .save(tier >= 5
                                    ? StorageModules.gate(recipes.output(), StorageModules.VOID_GEODES)
                                    : recipes.output());
                }).creativeTab().register();
    }

    private static void defineIfUsed(ShapedRecipeBuilder recipe, String keys, char key, TagKey<Item> tag) {
        if (keys.indexOf(key) >= 0) {
            recipe.define(key, tag);
        }
    }

    /**
     * An empty casing: {@code PDP / X X / PPP} for the small one, the casing below in the middle for the others, with
     * casing plates, display glass and {@code extra}.
     */
    private static RegistryEntry<Item, CellCasingItem> casing(CellCasing casing, String english, TagKey<Item> extra,
            @Nullable CellCasing below) {
        String name = casing.id() + "_cell_casing";
        return ModContent.CONTENT.item(name, p -> new CellCasingItem(p, casing)).model(flat(name)).lang(english)
                .tag(casingTag(casing)).recipe((recipes, item) -> {
                    ShapedRecipeBuilder recipe = recipes.shaped(RecipeCategory.MISC, item).pattern("PDP")
                            .pattern(below == null ? "X X" : "XCX").pattern("PPP")
                            .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                            .define('D', MaterialTags.component("display_glass")).define('X', extra)
                            .unlockedBy("has_casing_plate", recipes.has(MaterialTags.SIGNAL_ALLOY_PLATE));
                    if (below != null) {
                        recipe.define('C', casingTag(below));
                    }
                    recipe.save(recipes.output());
                }).creativeTab().register();
    }

    /** The cell item of {@code casing}: built in the cell workbench, so no recipe. A blank item cell by default. */
    private static RegistryEntry<Item, StorageCellItem> cell(CellCasing casing, String english) {
        String name = casing.id() + "_storage_cell";
        return ModContent.CONTENT.item(name, p -> {
            Item.Properties blank = p.component(ModDataComponents.CELL.get(), CellData.of(ITEM_TYPE.id(), List.of()));
            return casing.portable() ? new PortableCellItem(blank) : new StorageCellItem(blank, casing);
        }).model(flat(name)).lang(english).creativeTab().register();
    }

    /** An add-on (shapeless): a basic logic processor and a circuit board with two kind-specific inputs. */
    private static RegistryEntry<Item, CellAddonItem> addon(CellAddon addon, String english, TagKey<Item> first,
            TagKey<Item> second) {
        String name = addon.id() + "_addon";
        return ModContent.CONTENT.item(name, p -> new CellAddonItem(p, addon)).model(flat(name)).lang(english)
                .tag(tag(name))
                .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item)
                        .requires(MaterialTags.component("basic_logic_processor"))
                        .requires(MaterialTags.component("circuit_board")).requires(first).requires(second)
                        .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                        .save(recipes.output()))
                .creativeTab().register();
    }

    private static RegistryEntry<Item, ControllerChipItem> chip(String name, String english, Identifier type,
            TagKey<Item> tag, TagKey<Item> container) {
        ContentRecipe<Item> recipe = (recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item)
                .requires(MaterialTags.component("basic_logic_processor"))
                .requires(MaterialTags.component("basic_io_processor"))
                .requires(MaterialTags.component("circuit_board")).requires(container)
                .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                .save(recipes.output());
        return ModContent.CONTENT.item(name, p -> new ControllerChipItem(p, type)).model(flat(name)).lang(english)
                .tag(tag).recipe(recipe).creativeTab().register();
    }

    /**
     * A shaped starter cell recipe: casing plates, display glass and iron round a memory module I and the chip. The
     * result carries the cell's data, so one item serves every type; the item cell is the item's default.
     */
    private static void starterRecipe(RecipeContext recipes, Item cell, Identifier type, TagKey<Item> chip,
            String name) {
        // The item cell is the item's default, so its recipe needs no component.
        ItemStackTemplate result = type.equals(ITEM_TYPE.id())
                ? new ItemStackTemplate(cell)
                : new ItemStackTemplate(cell,
                        DataComponentPatch.builder().set(ModDataComponents.CELL.get(), starter(type)).build());
        // The vanilla registry stands in for the recipe context's item lookup: every key below is an Ingredient.
        ShapedRecipeBuilder.shaped(BuiltInRegistries.ITEM, RecipeCategory.MISC, result).pattern("PDP").pattern("IMI")
                .pattern("PCP").define('P', recipes.ingredient(MaterialTags.SIGNAL_ALLOY_PLATE))
                .define('D', recipes.ingredient(MaterialTags.component("display_glass")))
                .define('I', recipes.ingredient(MaterialTags.IRON_INGOT))
                .define('M', recipes.ingredient(MEMORY_MODULE_1_TAG)).define('C', recipes.ingredient(chip))
                .unlockedBy("has_memory_module", recipes.has(MEMORY_MODULE_1_TAG))
                .save(recipes.output(), ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name)));
    }

    private static TagKey<Item> c(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path));
    }

    private static TagKey<Item> tag(String name) {
        return TagKey.create(Registries.ITEM, MaxtechStorage.id(name));
    }

    private static ItemModel flat(String name) {
        return ItemModel.flat(MaxtechStorage.id("item/" + name));
    }
}
