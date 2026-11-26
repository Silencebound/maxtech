package dev.silencebound.maxtech.storage.tool.wrench;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.tool.ToolTags;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/**
 * Storage's wrench (M6e area A) and its texts. It joins {@code maxtech_lib:acts_as/wrench}, so every block that takes a
 * wrench takes it, but not {@code c:tools/wrench}: it is no crafting tool.
 */
public final class WrenchContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".wrench";

    public static final RegistryEntry<Item, StorageWrenchItem> WRENCH = ModContent.CONTENT
            .item("storage_wrench", StorageWrenchItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/storage_wrench"))).lang("Storage Wrench")
            .tag(ToolTags.ACTS_AS_WRENCH)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("P P").pattern(" L ")
                    .pattern(" I ").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor")).define('I', MaterialTags.IRON_INGOT)
                    .unlockedBy("has_signal_alloy_plate", recipes.has(MaterialTags.SIGNAL_ALLOY_PLATE))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final String DESCRIPTION_KEY = "tooltip." + PREFIX;
    public static final String DENIED_KEY = "message." + PREFIX + ".denied";

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY,
                "Sneak-use: pick up a device with its contents and settings, or take a part off a cable");
        ModContent.CONTENT.translation(DENIED_KEY, "You may not take this device");
    }

    private WrenchContent() {
    }

    /** Loads the class, which registers everything above. */
    public static void init() {
    }
}
