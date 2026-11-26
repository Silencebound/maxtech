package dev.silencebound.maxtech.storage.tool.uplink;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The pocket uplink (M6e), its settings component and its texts. */
public final class UplinkContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String ON_KEY = "message." + PREFIX + "uplink.on";
    public static final String OFF_KEY = "message." + PREFIX + "uplink.off";
    public static final String FILTER_ADDED_KEY = "message." + PREFIX + "uplink.filter_added";
    public static final String FILTER_REMOVED_KEY = "message." + PREFIX + "uplink.filter_removed";
    public static final String FILTER_FULL_KEY = "message." + PREFIX + "uplink.filter_full";
    public static final String FILTER_CLEARED_KEY = "message." + PREFIX + "uplink.filter_cleared";
    public static final String FILTER_ANY_KEY = "tooltip." + PREFIX + "uplink.filter_any";
    public static final String FILTER_KEY = "tooltip." + PREFIX + "uplink.filter";

    /** A pocket uplink's overflow switch and filter. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<UplinkSettings>> SETTINGS = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE)
            .register("uplink_settings", () -> DataComponentType.<UplinkSettings>builder()
                    .persistent(UplinkSettings.CODEC).networkSynchronized(UplinkSettings.STREAM_CODEC).build());

    // A hopper to catch what the inventory cannot hold, an oscillator for the carrier, an I/O processor for the link.
    public static final RegistryEntry<Item, PocketUplinkItem> POCKET_UPLINK = ModContent.CONTENT
            .item("pocket_uplink", PocketUplinkItem::new).model(ItemModel.flat(MaxtechStorage.id("item/pocket_uplink")))
            .lang("Pocket Uplink")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PHP").pattern("OIO")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', Items.HOPPER)
                    .define('O', MaterialTags.component("oscillator"))
                    .define('I', MaterialTags.component("advanced_io_processor"))
                    .unlockedBy("has_oscillator", recipes.has(MaterialTags.component("oscillator")))
                    .save(StorageModules.gate(recipes.output(), StorageModules.WIRELESS)))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(ON_KEY, "Pickup overflow on");
        ModContent.CONTENT.translation(OFF_KEY, "Pickup overflow off");
        ModContent.CONTENT.translation(FILTER_ADDED_KEY, "%s added to the filter");
        ModContent.CONTENT.translation(FILTER_REMOVED_KEY, "%s taken off the filter");
        ModContent.CONTENT.translation(FILTER_FULL_KEY, "The filter holds %s items at most");
        ModContent.CONTENT.translation(FILTER_CLEARED_KEY, "Filter cleared; every item overflows");
        ModContent.CONTENT.translation(FILTER_ANY_KEY, "Overflows every item");
        ModContent.CONTENT.translation(FILTER_KEY, "Overflows only:");
    }

    private UplinkContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
