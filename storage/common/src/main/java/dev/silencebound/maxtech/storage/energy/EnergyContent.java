package dev.silencebound.maxtech.storage.energy;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The three energy cells: blocks, items, one block entity type, lang lines and crafting recipes. Their block states
 * (one model per charge level) are written by Storage's model provider. Recipes name the lower tier by item tag, so a
 * tier-2 cell is made from four tier-1 cells; the FE in the cells used up is lost, like any crafting ingredient's data.
 */
public final class EnergyContent {
    public static final TagKey<Item> TIER_1_TAG = MaterialTags.component("energy_cell_tier_1");
    public static final TagKey<Item> TIER_2_TAG = MaterialTags.component("energy_cell_tier_2");
    public static final TagKey<Item> TIER_3_TAG = MaterialTags.component("energy_cell_tier_3");
    private static final TagKey<Item> REDSTONE_BLOCKS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "storage_blocks/redstone"));

    public static final BlockEntry<EnergyCellBlock, EnergyCellItem> TIER_1 = cell(EnergyCellTier.TIER_1, TIER_1_TAG,
            // Redstone blocks hold the charge, a logic processor meters it, signal alloy plates case it.
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PRP").pattern("RLR")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('R', REDSTONE_BLOCKS)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()));
    public static final BlockEntry<EnergyCellBlock, EnergyCellItem> TIER_2 = cell(EnergyCellTier.TIER_2, TIER_2_TAG,
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("ECE").pattern("CAC")
                    .pattern("ECE").define('E', TIER_1_TAG).define('C', MaterialTags.component("tantalum_capacitor"))
                    .define('A', MaterialTags.component("advanced_logic_processor"))
                    .unlockedBy("has_energy_cell", recipes.has(TIER_1_TAG)).save(recipes.output()));
    public static final BlockEntry<EnergyCellBlock, EnergyCellItem> TIER_3 = cell(EnergyCellTier.TIER_3, TIER_3_TAG,
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("ESE").pattern("SAS")
                    .pattern("ESE").define('E', TIER_2_TAG).define('S', MaterialTags.component("superconductor_wire"))
                    .define('A', MaterialTags.component("advanced_memory_processor"))
                    .unlockedBy("has_energy_cell", recipes.has(TIER_2_TAG)).save(recipes.output()));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<EnergyCellBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("energy_cell", () -> new BlockEntityType<>(EnergyCellBlockEntity::new,
                    Set.of(TIER_1.get(), TIER_2.get(), TIER_3.get())));

    public static final String STORED_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".energy_cell.stored";
    public static final String NEEDS_RESEARCH_KEY = "message." + MaxtechStorage.MOD_ID + ".needs_research";

    static {
        ModContent.CONTENT.translation(STORED_KEY, "Stored: %s / %s MU");
        ModContent.CONTENT.translation(NEEDS_RESEARCH_KEY, "Needs research: %s");
    }

    private EnergyContent() {
    }

    private static BlockEntry<EnergyCellBlock, EnergyCellItem> cell(EnergyCellTier tier, TagKey<Item> tag,
            ContentRecipe<? super EnergyCellItem> recipe) {
        String roman = "I".repeat(tier.level());
        return ModContent.CONTENT.block("energy_cell_tier_" + tier.level(), p -> new EnergyCellBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .item(EnergyCellItem::new).lang("Energy Cell " + roman).loot(BlockLoot.SELF)
                .tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(tag).recipe(recipe).creativeTab().register();
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
