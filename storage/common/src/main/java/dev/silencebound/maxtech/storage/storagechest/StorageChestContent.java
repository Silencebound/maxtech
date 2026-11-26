package dev.silencebound.maxtech.storage.storagechest;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The storage chest, tier 0 of the ladder (PRD 7.2): a chest with search and sort for the first minutes, crafted from a
 * chest, lumen quartz, copper and display glass. It drops through code with its contents, so it has no loot table.
 */
public final class StorageChestContent {
    public static final BlockEntry<StorageChestBlock, StorageChestItem> STORAGE_CHEST = ModContent.CONTENT
            .block("storage_chest", StorageChestBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).strength(2.5F, 6.0F).sound(SoundType.COPPER))
            .item(StorageChestItem::new)
            .model(BlockModel.orientable(MaxtechStorage.id("block/storage_chest_front"),
                    MaxtechStorage.id("block/storage_chest_side"), MaxtechStorage.id("block/storage_chest_top")))
            .lang("Storage Chest").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("QDQ").pattern("CXC")
                    .pattern("QCQ").define('Q', MaterialTags.LUMEN_QUARTZ)
                    .define('D', MaterialTags.component("display_glass")).define('C', MaterialTags.COPPER_INGOT)
                    .define('X', ConventionTags.CHESTS)
                    .unlockedBy("has_lumen_quartz", recipes.has(MaterialTags.LUMEN_QUARTZ)).save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StorageChestBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register("storage_chest",
                    () -> new BlockEntityType<>(StorageChestBlockEntity::new, Set.of(STORAGE_CHEST.get())));

    static {
        ModContent.CONTENT.translation(StorageChestItem.HOLDS_KEY, "Holds items in %s of %s slots");
    }

    private StorageChestContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
