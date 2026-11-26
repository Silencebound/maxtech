package dev.silencebound.maxtech.storage.terminal.filter;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellFilter;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

/**
 * The view filter (B09 item 24, like AE2's view cells and Refined Storage's grid filter): an item that carries a
 * {@link CellFilter} and, placed in a terminal's filter slots, limits what the terminal's grid shows. Its filter
 * component, its GUI's menu type, its payloads and its texts.
 */
public final class ViewFilterContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    private static final String GUI = "gui." + PREFIX + "view_filter.";
    public static final String ENTRIES_KEY = "tooltip." + PREFIX + "view_filter.entries";
    public static final String SHOWS_KEY = "tooltip." + PREFIX + "view_filter.shows";
    public static final String HIDES_KEY = "tooltip." + PREFIX + "view_filter.hides";
    public static final String BLANK_KEY = "tooltip." + PREFIX + "view_filter.blank";
    public static final String SHOW_KEY = GUI + "show";
    public static final String HIDE_KEY = GUI + "hide";
    public static final String MODE_HINT_KEY = GUI + "mode_hint";
    public static final String ENTRY_HINT_KEY = GUI + "entry_hint";
    public static final String SLOTS_KEY = GUI + "slots";
    public static final String SLOTS_HINT_KEY = GUI + "slots_hint";

    /** The filter a view filter carries; synced, so the client's terminal screen can match against it. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<CellFilter>> FILTER = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE)
            .register("view_filter", () -> DataComponentType.<CellFilter>builder().persistent(CellFilter.CODEC)
                    .networkSynchronized(CellFilter.STREAM_CODEC).build());

    /**
     * The view filters a wireless terminal or portable cell carries in its filter strip (#322); saved, and synced as
     * NBT like any component without a stream codec.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<CarriedFilters>> CARRIED = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE).register("view_filters",
                    () -> DataComponentType.<CarriedFilters>builder().persistent(CarriedFilters.CODEC).build());

    /** The view filter's GUI, opened from the main hand. */
    public static final RegistryEntry<MenuType<?>, MenuType<ViewFilterMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("view_filter", () -> Platform.get().menus().menuType(ViewFilterMenu::fromNetwork));

    // Display glass to look through, a logic processor to sort and lumen quartz, as for the other filter parts.
    public static final RegistryEntry<Item, ViewFilterItem> VIEW_FILTER = ModContent.CONTENT
            .item("view_filter", ViewFilterItem::new).model(ItemModel.flat(MaxtechStorage.id("item/view_filter")))
            .lang("View Filter")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern(" G ").pattern("QLQ")
                    .define('G', MaterialTags.component("display_glass")).define('Q', MaterialTags.LUMEN_QUARTZ)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(ENTRIES_KEY, "%s of %s entries");
        ModContent.CONTENT.translation(SHOWS_KEY, "Shows only what it lists");
        ModContent.CONTENT.translation(HIDES_KEY, "Hides what it lists");
        ModContent.CONTENT.translation(BLANK_KEY, "Blank: use it in hand to set it up");
        ModContent.CONTENT.translation(SHOW_KEY, "Show listed");
        ModContent.CONTENT.translation(HIDE_KEY, "Hide listed");
        ModContent.CONTENT.translation(MODE_HINT_KEY,
                "Show listed: the terminal shows only what the list matches. Hide listed: it shows everything else.");
        ModContent.CONTENT.translation(ENTRY_HINT_KEY,
                "Click with an item or bucket to add it; right-click an entry to cycle its tags; click to clear");
        ModContent.CONTENT.translation(SLOTS_KEY, "View filters");
        ModContent.CONTENT.translation(SLOTS_HINT_KEY,
                "Put view filters here to limit what the grid shows; with several, an entry shows if any filter shows it");
    }

    private ViewFilterContent() {
    }

    /** Loads the class during mod construction so the entries above are queued, and registers the GUI's payload. */
    public static void init() {
        ViewFilterMenu.registerPayloads();
    }
}
