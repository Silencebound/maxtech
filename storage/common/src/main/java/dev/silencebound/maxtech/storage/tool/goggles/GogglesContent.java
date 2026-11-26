package dev.silencebound.maxtech.storage.tool.goggles;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/** The network goggles (M6e area B) and their texts. */
public final class GogglesContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String DESCRIPTION_KEY = "tooltip." + PREFIX + "network_goggles";
    public static final String ENERGY_KEY = "tooltip." + PREFIX + "network_goggles.energy";
    public static final String NO_POWER_KEY = "message." + PREFIX + "network_goggles.no_power";

    // Two display-glass lenses on a signal alloy frame, with a logic processor to read the network.
    public static final RegistryEntry<Item, NetworkGogglesItem> NETWORK_GOGGLES = ModContent.CONTENT
            .item("network_goggles", NetworkGogglesItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/network_goggles"))).lang("Network Goggles")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PLP").pattern("D D")
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('D', MaterialTags.component("display_glass"))
                    .unlockedBy("has_display_glass", recipes.has(MaterialTags.component("display_glass")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY,
                "Wear to see the storage network you look at: zone, channel use and route in the action bar, and the"
                        + " network around you through walls. Charge in any FE charger.");
        ModContent.CONTENT.translation(ENERGY_KEY, "Energy: %s / %s MU");
        ModContent.CONTENT.translation(NO_POWER_KEY, "The network goggles are out of power; charge them");
        ModContent.CONTENT.translation(GogglesView.READOUT_KEY,
                "Network #%s, zone %s: segment %s%% of %s channels, %s segments to the root");
        ModContent.CONTENT.translation(GogglesView.READOUT_ROOT_KEY, "Network #%s, zone %s: route root");
        ModContent.CONTENT.translation(GogglesView.BORDER_KEY, ", painted zone border");
    }

    private GogglesContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
