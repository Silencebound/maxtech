package dev.silencebound.maxtech.storage.tool.usage;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The usage report book and its report's texts. */
public final class UsageContent {
    public static final RegistryEntry<Item, UsageReportItem> USAGE_REPORT_BOOK = ModContent.CONTENT
            .item("usage_report_book", UsageReportItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/usage_report_book"))).lang("Usage Report Book")
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.TOOLS, item).requires(Items.WRITABLE_BOOK)
                    .requires(MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(UsageReportItem.DESCRIPTION_KEY,
                "Use for a report on your team's storage networks in this dimension, or on a network block for that"
                        + " network: devices, limits, bytes and who placed what.");
        ModContent.CONTENT.translation(UsageReportItem.NO_ACCESS_KEY, "You have no access to this network");
        ModContent.CONTENT.translation(UsageBook.NAME, "Storage Usage Report");
        ModContent.CONTENT.translation(UsageBook.HEADING, "Storage usage");
        ModContent.CONTENT.translation(UsageBook.FOR, "For %s");
        ModContent.CONTENT.translation(UsageBook.NETWORKS, "Networks: %s");
        ModContent.CONTENT.translation(UsageBook.DEVICES, "Team devices: %s of %s");
        ModContent.CONTENT.translation(UsageBook.ZONES, "Team zones: %s of %s");
        ModContent.CONTENT.translation(UsageBook.BYTES, "Team bytes: %s of %s");
        ModContent.CONTENT.translation(UsageBook.NO_CAP, "no cap");
        ModContent.CONTENT.translation(UsageBook.REFUSED_HINT,
                "A network turned devices away. Its page says whether the controller or the team cap stopped them.");
        ModContent.CONTENT.translation(UsageBook.NETWORK, "Network #%s");
        ModContent.CONTENT.translation(UsageBook.AT, "Root at %s");
        ModContent.CONTENT.translation(UsageBook.NO_ROOT, "No route root");
        ModContent.CONTENT.translation(UsageBook.OWNER, "Owner: %s");
        ModContent.CONTENT.translation(UsageBook.LIMIT, "Devices: %s of %s");
        ModContent.CONTENT.translation(UsageBook.NO_LIMIT, "no limit");
        ModContent.CONTENT.translation(UsageBook.ROLES, "Storage %s, terminals %s, ports %s, other %s");
        ModContent.CONTENT.translation(UsageBook.REFUSED_CONTROLLER,
                "Turned away: %s (the device limit of a network without a controller, or of a subnet)");
        ModContent.CONTENT.translation(UsageBook.REFUSED_TEAM, "Turned away: %s (the team's device cap)");
        ModContent.CONTENT.translation(UsageBook.CELL_BYTES, "Bytes: %s of %s");
        ModContent.CONTENT.translation(UsageBook.NETWORK_ZONES, "Zones: %s");
        ModContent.CONTENT.translation(UsageBook.OFFLINE, "Cells offline: %s");
        ModContent.CONTENT.translation(UsageBook.OWNERS, "Devices by owner");
        ModContent.CONTENT.translation(UsageBook.OWNER_ROW, "%s: %s");
    }

    private UsageContent() {
    }

    /** Loads the class during mod construction so the entries above are queued and the texts declared. */
    public static void init() {
    }
}
