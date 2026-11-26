package dev.silencebound.maxtech.storage.terminal;

import dev.silencebound.maxtech.storage.terminal.export.ExportOutputs;

import dev.silencebound.maxtech.lib.api.resource.StackKey;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.sync.SyncedListType;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.crafting.request.RequestContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * The item terminal (block, item, block entity type), the synced list kind every terminal-style GUI uses, and the GUI's
 * texts. Its block state and panel model are written by Storage's model provider ({@code TerminalModels}).
 */
public final class TerminalContent {
    /** The contents list of every terminal-style GUI: key ids with their counts, keys sent once each. */
    public static final SyncedListType<StackKey> LIST = SyncedListType.register(MaxtechStorage.MOD_ID,
            MaxtechStorage.id("terminal_contents"), StackKey.STREAM_CODEC);

    public static final String DENIED_KEY = "message.maxtech_storage.terminal.denied";
    public static final String SEARCH_KEY = key("search");
    public static final String SEARCH_HELP_KEY = key("search.help");
    public static final String VIEW_KEY = key("view");
    public static final String VIEW_ALL_KEY = key("view.all");
    public static final String VIEW_ITEMS_KEY = key("view.items");
    public static final String VIEW_FLUIDS_KEY = key("view.fluids");
    public static final String VIEW_HINT_KEY = key("view.hint");
    public static final String SIZE_KEY = key("size");
    public static final String SIZE_SMALL_KEY = key("size.small");
    public static final String SIZE_TALL_KEY = key("size.tall");
    public static final String SIZE_FILL_KEY = key("size.fill");
    public static final String SORT_KEY = key("sort");
    public static final String SORT_COUNT_KEY = key("sort.count");
    public static final String SORT_NAME_KEY = key("sort.name");
    public static final String SORT_MOD_KEY = key("sort.mod");
    public static final String ASCENDING_KEY = key("ascending");
    public static final String DESCENDING_KEY = key("descending");
    public static final String STORED_KEY = key("stored");
    public static final String FLUID_STORED_KEY = key("fluid_stored");
    public static final String FLUID_HINT_KEY = key("fluid_hint");
    public static final String SUMMARY_KEY = key("summary");
    public static final String ENERGY_KEY = key("energy");
    public static final String LINK_KEY = key("link");
    public static final String KINDS_KEY = key("kinds");
    public static final String SLOTS_KEY = key("slots");
    public static final String PIN_HINT_KEY = key("pin_hint");
    public static final String PINNED_KEY = key("pinned");
    public static final String TAB_ADD_KEY = key("tab.add");
    public static final String TAB_KEY = key("tab");
    public static final String KEY_CATEGORY = "key.category." + MaxtechStorage.MOD_ID + ".terminal";
    public static final String DEPOSIT_KEY = "key." + MaxtechStorage.MOD_ID + ".terminal.deposit";
    public static final String RESTOCK_KEY = "key." + MaxtechStorage.MOD_ID + ".terminal.restock";
    public static final String LOCATE_KEY = "key." + MaxtechStorage.MOD_ID + ".terminal.locate";
    public static final String LOCATE_HINT_KEY = key("locate_hint");

    /** Recipes name the two forms by these tags, never the items. */
    public static final TagKey<Item> TERMINAL_TAG = MaterialTags.component("storage_terminal");
    public static final TagKey<Item> TERMINAL_PART_TAG = MaterialTags.component("storage_terminal_part");

    public static final BlockEntry<TerminalBlock, BlockItem> TERMINAL = ModContent.CONTENT
            .block("storage_terminal", TerminalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL)).simpleItem()
            .lang("Storage Terminal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(TERMINAL_TAG)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("WIW")
                    .pattern("PLP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('I', MaterialTags.component("basic_io_processor"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            // The part back into the block form, 1:1.
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(TERMINAL_PART_TAG)
                    .unlockedBy("has_terminal_part", recipes.has(TERMINAL_PART_TAG)).save(recipes.output(),
                            ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("storage_terminal_from_part"))))
            .creativeTab().register();

    /**
     * The terminal as a part on a cable's face (no item model in the chain: Storage's model provider draws its panel).
     */
    public static final RegistryEntry<Item, PartItem> TERMINAL_PART_ITEM = ModContent.CONTENT
            .item("storage_terminal_part", p -> new PartItem(p, () -> TerminalContent.TERMINAL_PART))
            .lang("Storage Terminal Part").tag(TERMINAL_PART_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.MISC, item).requires(TERMINAL_TAG)
                    .unlockedBy("has_terminal", recipes.has(TERMINAL_TAG)).save(recipes.output()))
            .creativeTab().register();

    /** The panel of the block's screen, 12 x 12 so that parts on neighbouring faces never meet, 2 px deep. */
    public static final PartType TERMINAL_PART = PartTypes.register(new PartType(MaxtechStorage.id("storage_terminal"),
            TERMINAL_PART_ITEM, List.of(new AABB(2, 2, 0, 14, 14, 2)), TerminalPart::new));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<TerminalBlockEntity>> TERMINAL_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register(
                    "storage_terminal", () -> new BlockEntityType<>(TerminalBlockEntity::new, Set.of(TERMINAL.get())));

    /** The network log tab (M7L): its title, the line with no log, and the hint under it. */
    public static final String LOG_TAB_KEY = key("log.tab");
    public static final String LOG_EMPTY_KEY = key("log.empty");
    public static final String LOG_HINT_KEY = key("log.hint");

    static {
        ModContent.CONTENT.translation(LOG_TAB_KEY, "Network log");
        ModContent.CONTENT.translation(LOG_EMPTY_KEY, "Nothing logged yet");
        ModContent.CONTENT.translation(LOG_HINT_KEY, "Notices from logic graphs, newest at the bottom");
        ModContent.CONTENT.translation(DENIED_KEY, "The network's owner has not given you access");
        ModContent.CONTENT.translation(SEARCH_KEY, "Search");
        ModContent.CONTENT.translation(SEARCH_HELP_KEY,
                "Words match the name; @mod the mod, #tag a tag, $text the tooltip. All words must match.");
        ModContent.CONTENT.translation(VIEW_KEY, "Show: %s");
        ModContent.CONTENT.translation(VIEW_ALL_KEY, "Items and fluids");
        ModContent.CONTENT.translation(VIEW_ITEMS_KEY, "Items");
        ModContent.CONTENT.translation(VIEW_FLUIDS_KEY, "Fluids");
        ModContent.CONTENT.translation(VIEW_HINT_KEY, "%s. Click to switch between all, items and fluids.");
        ModContent.CONTENT.translation(SIZE_KEY, "Size: %s. Click for the next size, right-click for the one before.");
        ModContent.CONTENT.translation(SIZE_SMALL_KEY, "Small");
        ModContent.CONTENT.translation(SIZE_TALL_KEY, "Tall");
        ModContent.CONTENT.translation(SIZE_FILL_KEY, "Fill the window");
        ModContent.CONTENT.translation(SORT_KEY, "Sort: %s");
        ModContent.CONTENT.translation(SORT_COUNT_KEY, "Count");
        ModContent.CONTENT.translation(SORT_NAME_KEY, "Name");
        ModContent.CONTENT.translation(SORT_MOD_KEY, "Mod");
        ModContent.CONTENT.translation(ASCENDING_KEY, "Ascending");
        ModContent.CONTENT.translation(DESCENDING_KEY, "Descending");
        ModContent.CONTENT.translation(STORED_KEY, "Stored: %s");
        ModContent.CONTENT.translation(FLUID_STORED_KEY, "Stored: %s mB");
        ModContent.CONTENT.translation(FLUID_HINT_KEY, "Click with a fluid container to fill it");
        ModContent.CONTENT.translation(SUMMARY_KEY, "%s channels, %s MU");
        ModContent.CONTENT.translation(ENERGY_KEY, "Energy: %s / %s MU");
        ModContent.CONTENT.translation(LINK_KEY, "Link: %s channels, %s%% in use");
        ModContent.CONTENT.translation(KINDS_KEY, "Kinds stored: %s");
        ModContent.CONTENT.translation(SLOTS_KEY, "%s / %s slots");
        ModContent.CONTENT.translation(PIN_HINT_KEY, "Shift-right-click to pin or unpin");
        ModContent.CONTENT.translation(PINNED_KEY, "Pinned");
        ModContent.CONTENT.translation(TAB_ADD_KEY, "Save the search as a tab");
        ModContent.CONTENT.translation(TAB_KEY, "Tab \"%s\": click to search, right-click to remove");
        ModContent.CONTENT.translation(KEY_CATEGORY, "MaxTech Storage terminal");
        ModContent.CONTENT.translation(DEPOSIT_KEY, "Deposit items already stored");
        ModContent.CONTENT.translation(RESTOCK_KEY, "Restock the hotbar");
        ModContent.CONTENT.translation(LOCATE_KEY, "Locate the item under the mouse");
        ModContent.CONTENT.translation(LOCATE_HINT_KEY, "%s: show where it is stored");
        for (TerminalStatus status : TerminalStatus.values()) {
            ModContent.CONTENT.translation(status.translationKey(), switch (status) {
                case NO_NETWORK -> "No network";
                case ONLINE -> "Online";
                case NO_POWER -> "No power";
                case STOPPED -> "Network stopped";
                case LOCAL -> "Local storage";
            });
        }
    }

    private TerminalContent() {
    }

    /**
     * Loads the class during mod construction so the entries above are queued before registration runs, with the
     * crafting request payloads and the network log tab's. JEI's "+" on the terminal is Storage's own handler (it also
     * requests crafting), so the menu is not registered with Lib's crafting grid transfer.
     */
    public static void init() {
        RequestContent.init();
        NetworkLogPayloads.init();
        ItemLocator.init();
        ExportOutputs.init();
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".terminal." + name;
    }
}
