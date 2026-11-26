package dev.silencebound.maxtech.storage.tool.zone;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/** The zone painter (M6e area B) and its texts. */
public final class ZonePainterContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String DESCRIPTION_KEY = "tooltip." + PREFIX + "zone_painter";
    public static final String PAINTED_KEY = "message." + PREFIX + "zone_painter.painted";
    public static final String CLEARED_KEY = "message." + PREFIX + "zone_painter.cleared";
    public static final String NOT_CABLE_KEY = "message." + PREFIX + "zone_painter.not_cable";
    public static final String NO_NETWORK_KEY = "message." + PREFIX + "zone_painter.no_network";
    public static final String DENIED_KEY = "message." + PREFIX + "zone_painter.denied";
    public static final String SHOWN_KEY = "message." + PREFIX + "zone_painter.shown";

    // A brush: glowstone paint on a signal alloy handle, with a logic processor that marks the routes.
    public static final RegistryEntry<Item, ZonePainterItem> ZONE_PAINTER = ModContent.CONTENT
            .item("zone_painter", ZonePainterItem::new).model(ItemModel.flat(MaxtechStorage.id("item/zone_painter")))
            .lang("Zone Painter")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern(" GG").pattern(" LG")
                    .pattern("P  ").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('G', MaterialTags.GLOWSTONE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY,
                "Use on a storage cable to paint it as a zone border, again to clear it: a painted cable always"
                        + " starts a zone of its own. Use in the air to see the painted borders near you.");
        ModContent.CONTENT.translation(PAINTED_KEY, "Painted as a zone border; the network rebuilds its zones");
        ModContent.CONTENT.translation(CLEARED_KEY, "Zone border cleared; the network rebuilds its zones");
        ModContent.CONTENT.translation(NOT_CABLE_KEY, "Only storage cables can be painted");
        ModContent.CONTENT.translation(NO_NETWORK_KEY, "This cable is not part of a network yet");
        ModContent.CONTENT.translation(DENIED_KEY, "You need full access to change this network's zones");
        ModContent.CONTENT.translation(SHOWN_KEY, "%s painted zone borders within %s blocks");
    }

    private ZonePainterContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
