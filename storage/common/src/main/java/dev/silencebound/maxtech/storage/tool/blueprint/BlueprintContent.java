package dev.silencebound.maxtech.storage.tool.blueprint;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;

/**
 * The blueprint card (M6e area A): the item, its {@code maxtech_storage:blueprint} component, the names of the device
 * kinds it copies, and its texts. The component is registered here rather than in {@code ModDataComponents}, so this
 * area adds no line to that shared class.
 */
public final class BlueprintContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".blueprint.";

    /** What a card holds. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Blueprint>> BLUEPRINT = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE)
            .register("blueprint", () -> DataComponentType.<Blueprint>builder().persistent(Blueprint.CODEC)
                    .networkSynchronized(Blueprint.STREAM_CODEC).build());

    public static final RegistryEntry<Item, BlueprintCardItem> CARD = ModContent.CONTENT
            .item("blueprint_card", BlueprintCardItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/blueprint_card"))).lang("Blueprint Card")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PLP")
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_logic_processor",
                            recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final String COPIED_KEY = "message." + PREFIX + "copied";
    public static final String PASTED_KEY = "message." + PREFIX + "pasted";
    public static final String PASTED_ROW_KEY = "message." + PREFIX + "pasted_row";
    public static final String PASTED_ROW_DENIED_KEY = "message." + PREFIX + "pasted_row_denied";
    public static final String WRONG_KIND_KEY = "message." + PREFIX + "wrong_kind";
    public static final String DENIED_KEY = "message." + PREFIX + "denied";
    public static final String CLEARED_KEY = "message." + PREFIX + "cleared";
    public static final String TOOLTIP_EMPTY_KEY = "tooltip." + PREFIX + "empty";
    public static final String TOOLTIP_HELD_KEY = "tooltip." + PREFIX + "held";
    public static final String TOOLTIP_USE_KEY = "tooltip." + PREFIX + "use";

    /** Device kinds with settings to copy, by path, and their names. */
    private static final String[][] KINDS = {{"port", "Port"}, {"interface", "Interface"},
            {"condition_switch", "Condition Switch"}, {"display", "Display"}, {"collector", "Collector"},
            {"placer_port", "Placer Port"}, {"breaker_port", "Breaker Port"}};

    static {
        ModContent.CONTENT.translation(COPIED_KEY, "Copied the %s settings");
        ModContent.CONTENT.translation(PASTED_KEY, "Pasted the %s settings");
        ModContent.CONTENT.translation(PASTED_ROW_KEY, "Pasted onto %s devices");
        ModContent.CONTENT.translation(PASTED_ROW_DENIED_KEY,
                "Pasted onto %s devices; %s skipped, you lack access to them");
        ModContent.CONTENT.translation(WRONG_KIND_KEY, "The card holds %s settings, this is a %s");
        ModContent.CONTENT.translation(DENIED_KEY, "The network's owner has not given you access");
        ModContent.CONTENT.translation(CLEARED_KEY, "The card is blank again");
        ModContent.CONTENT.translation(TOOLTIP_EMPTY_KEY, "Use on a device to copy its settings");
        ModContent.CONTENT.translation(TOOLTIP_HELD_KEY, "Holds %s settings");
        ModContent.CONTENT.translation(TOOLTIP_USE_KEY,
                "Use on the same kind to paste, sneak-use to paste along a row; sneak-use in the air to clear");
        for (String[] kind : KINDS) {
            ModContent.CONTENT.translation(DeviceSettings.kindKey(DeviceSettings.kind(kind[0])), kind[1]);
        }
    }

    private BlueprintContent() {
    }

    /** Loads the class, which registers everything above. */
    public static void init() {
    }
}
