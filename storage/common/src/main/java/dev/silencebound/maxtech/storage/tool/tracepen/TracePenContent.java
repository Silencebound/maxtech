package dev.silencebound.maxtech.storage.tool.tracepen;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/** The trace pen (M6e area B) and its texts. */
public final class TracePenContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String DESCRIPTION_KEY = "tooltip." + PREFIX + "trace_pen";
    public static final String START_TOOLTIP_KEY = "tooltip." + PREFIX + "trace_pen.start";
    public static final String STARTED_KEY = "message." + PREFIX + "trace_pen.started";
    public static final String CLEARED_KEY = "message." + PREFIX + "trace_pen.cleared";
    public static final String NOT_STORAGE_KEY = "message." + PREFIX + "trace_pen.not_storage";
    public static final String OTHER_NETWORK_KEY = "message." + PREFIX + "trace_pen.other_network";
    public static final String NO_ROUTE_KEY = "message." + PREFIX + "trace_pen.no_route";
    public static final String DENIED_KEY = "message." + PREFIX + "trace_pen.denied";
    public static final String TRACE_KEY = "info." + PREFIX + "trace_pen.trace";
    public static final String SLOWEST_KEY = "info." + PREFIX + "trace_pen.slowest";
    public static final String BUSIEST_KEY = "info." + PREFIX + "trace_pen.busiest";
    public static final String THROUGH_ROOT_KEY = "info." + PREFIX + "trace_pen.through_root";
    public static final String BORDERS_KEY = "info." + PREFIX + "trace_pen.borders";

    // A signal alloy stylus with a copper wire to a logic processor that reads the routes.
    public static final RegistryEntry<Item, TracePenItem> TRACE_PEN = ModContent.CONTENT
            .item("trace_pen", TracePenItem::new).model(ItemModel.flat(MaxtechStorage.id("item/trace_pen")))
            .lang("Trace Pen")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("  L").pattern(" W ")
                    .pattern("P  ").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('W', MaterialTags.COPPER_WIRE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY,
                "Sneak-use on a storage cable or device to mark the start, then use on another block of the same"
                        + " network to trace the route between them. Sneak-use in the air to drop the start.");
        ModContent.CONTENT.translation(START_TOOLTIP_KEY, "Traces from %s %s %s");
        ModContent.CONTENT.translation(STARTED_KEY, "Trace start marked at %s %s %s; use the pen on the end");
        ModContent.CONTENT.translation(CLEARED_KEY, "Trace start dropped");
        ModContent.CONTENT.translation(NOT_STORAGE_KEY, "Not part of a storage network");
        ModContent.CONTENT.translation(OTHER_NETWORK_KEY, "Not on the same network as the start");
        ModContent.CONTENT.translation(NO_ROUTE_KEY, "No route there yet");
        ModContent.CONTENT.translation(DENIED_KEY, "You have no access to this network");
        ModContent.CONTENT.translation(TRACE_KEY, "Trace from %s to %s: %s segments");
        ModContent.CONTENT.translation(SLOWEST_KEY, "Fewest channels: %s at %s");
        ModContent.CONTENT.translation(BUSIEST_KEY, "Fullest segment: %s%% of %s channels at %s");
        ModContent.CONTENT.translation(THROUGH_ROOT_KEY, "The route runs through the controller");
        ModContent.CONTENT.translation(BORDERS_KEY, "Zone borders crossed: %s");
    }

    private TracePenContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
