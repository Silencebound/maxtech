package dev.silencebound.maxtech.storage.crafting.crafter;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.provider.ProviderItems;
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
 * The crafter (B09 item 29): block, block entity type, info texts and recipe. Its model comes from Storage's datagen
 * ({@code CrafterModels}); its screen is the pattern provider's ({@code registry.ModMenus}).
 */
public final class CrafterContent {
    /** Crafting tables by NeoForge's common tag. */
    private static final TagKey<Item> CRAFTING_TABLES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "player_workstations/crafting_tables"));

    public static final BlockEntry<CrafterBlock, ProviderItems.ProviderBlockItem> CRAFTER = ModContent.CONTENT
            .block("crafter", CrafterBlock::new)
            // Glass in front: neighbours keep their faces, and light reaches the item inside.
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL).noOcclusion())
            .item(ProviderItems.ProviderBlockItem::new).lang("Crafter").loot(BlockLoot.SELF)
            .tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // A crafting table behind glass, a memory processor for its patterns, an I/O processor for the network.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PGP").pattern("MCO")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('G', MaterialTags.component("display_glass"))
                    .define('M', MaterialTags.component("basic_memory_processor")).define('C', CRAFTING_TABLES)
                    .define('O', MaterialTags.component("basic_io_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CrafterBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("crafter", () -> new BlockEntityType<>(CrafterBlockEntity::new, Set.of(CRAFTER.get())));

    /** "Crafting: Stone Slab", at a glance while it crafts. */
    public static final String INFO_CRAFTING = info("crafting");
    /** "Patterns: 3 / 9". */
    public static final String INFO_PATTERNS = info("patterns");
    /** "Held up: the machine is full". */
    public static final String INFO_STUCK = info("stuck");

    static {
        ModContent.CONTENT.translation(INFO_CRAFTING, "Crafting: %s");
        ModContent.CONTENT.translation(INFO_PATTERNS, "Patterns: %s / %s");
        ModContent.CONTENT.translation(INFO_STUCK, "Held up: %s");
    }

    private CrafterContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    private static String info(String name) {
        return "info." + MaxtechStorage.MOD_ID + ".crafter." + name;
    }
}
