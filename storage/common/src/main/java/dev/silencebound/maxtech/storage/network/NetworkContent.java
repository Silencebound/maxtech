package dev.silencebound.maxtech.storage.network;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.analyzer.AnalyzerTexts;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/**
 * The network area's items: the network analyzer, a tinted spyglass placeholder until it has a texture of its own,
 * crafted from area 5's tier-1 parts.
 */
public final class NetworkContent {
    public static final RegistryEntry<Item, AnalyzerItem> NETWORK_ANALYZER = ModContent.CONTENT
            .item("network_analyzer", p -> new AnalyzerItem(p.stacksTo(1)))
            .model(ItemModel.flat(MaxtechStorage.id("item/network_analyzer"))).lang("Network Analyzer")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern(" D ").pattern("PLP")
                    .pattern("PWP").define('D', MaterialTags.component("display_glass"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor")).define('W', MaterialTags.COPPER_WIRE)
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(AnalyzerItem.DESCRIPTION_KEY,
                "Hold to read the storage block you look at; use on a block for the full report and its route,"
                        + " sneak-use for the network around you. Use in the air with a processor or storage cell in"
                        + " the other hand to analyse it for research points.");
    }

    private NetworkContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and the info lines declared. */
    public static void init() {
        NetworkInfo.init();
        Glance.init();
        PowerPriority.init();
        AnalyzerTexts.init();
        NetworkCommand.init();
        NetworkProfiler.init();
        LinkDwell.init();
    }
}
