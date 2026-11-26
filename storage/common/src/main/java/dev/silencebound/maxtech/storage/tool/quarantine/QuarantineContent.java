package dev.silencebound.maxtech.storage.tool.quarantine;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The quarantine tag and its texts. */
public final class QuarantineContent {
    public static final RegistryEntry<Item, QuarantineTagItem> QUARANTINE_TAG = ModContent.CONTENT
            .item("quarantine_tag", QuarantineTagItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/quarantine_tag"))).lang("Quarantine Tag")
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.TOOLS, item).requires(Items.PAPER)
                    .requires(Items.STRING).requires(MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(QuarantineTagItem.DESCRIPTION_KEY,
                "Use on a storage network you own (or as an operator) to freeze it: nothing moves, nothing is lost."
                        + " Use again to lift it.");
        ModContent.CONTENT.translation(QuarantineTagItem.ON_KEY, "Network quarantined: all operations stopped");
        ModContent.CONTENT.translation(QuarantineTagItem.OFF_KEY, "Quarantine lifted: the network runs again");
        ModContent.CONTENT.translation(QuarantineTagItem.DENIED_KEY,
                "Only the network's owner or an operator can quarantine it");
    }

    private QuarantineContent() {
    }

    /** Loads the class during mod construction so the entries above are queued and the texts declared. */
    public static void init() {
    }
}
