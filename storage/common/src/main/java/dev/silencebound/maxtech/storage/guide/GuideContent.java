package dev.silencebound.maxtech.storage.guide;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The Storage manual, the book that opens Storage's guide. The pages live under
 * {@code assets/maxtech_storage/guides/maxtech_storage/guide/}; the neoforge client declares the GuideME guide.
 */
public final class GuideContent {
    /** Storage's guide id. */
    public static final Identifier GUIDE = MaxtechStorage.id("guide");
    /** The manual's tooltip line. */
    public static final String DESCRIPTION_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".storage_manual";

    /** A book and a lumen quartz, shapeless, so a player can make it as soon as the first ore is mined. */
    public static final RegistryEntry<Item, StorageManualItem> STORAGE_MANUAL = ModContent.CONTENT
            .item("storage_manual", StorageManualItem::new).properties(p -> p.stacksTo(1))
            .model(ItemModel.flat(MaxtechStorage.id("item/storage_manual"))).lang("Storage Manual")
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.TOOLS, item).requires(Items.BOOK)
                    .requires(recipes.ingredient(MaterialTags.LUMEN_QUARTZ))
                    .unlockedBy("has_lumen_quartz", recipes.has(MaterialTags.LUMEN_QUARTZ)).save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY, "Cables, drives, cells, terminals and research");
    }

    private GuideContent() {
    }

    /** Loads the class during mod construction so the manual is queued. */
    public static void init() {
    }
}
