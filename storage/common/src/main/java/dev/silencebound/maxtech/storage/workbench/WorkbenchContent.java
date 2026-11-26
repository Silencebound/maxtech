package dev.silencebound.maxtech.storage.workbench;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The cell workbench (PRD 7.2, the cell builder): a full block crafted from casing plates, display glass, a basic logic
 * processor and a crafting table. It drops itself; the cell in it drops beside it.
 */
public final class WorkbenchContent {
    public static final TagKey<Item> CRAFTING_TABLES = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "player_workstations/crafting_tables"));

    public static final BlockEntry<CellWorkbenchBlock, BlockItem> CELL_WORKBENCH = ModContent.CONTENT
            .block("cell_workbench", CellWorkbenchBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_GRAY).strength(2.5F, 6.0F).sound(SoundType.COPPER)).simpleItem()
            .model(BlockModel.orientable(MaxtechStorage.id("block/cell_workbench_front"),
                    MaxtechStorage.id("block/cell_workbench_side"), MaxtechStorage.id("block/cell_workbench_top")))
            .lang("Cell Workbench").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PDP").pattern("LCL")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass"))
                    .define('L', MaterialTags.component("basic_logic_processor")).define('C', CRAFTING_TABLES)
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("basic_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<CellWorkbenchBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register("cell_workbench",
                    () -> new BlockEntityType<>(CellWorkbenchBlockEntity::new, Set.of(CELL_WORKBENCH.get())));

    private static final String GUI = "gui." + MaxtechStorage.MOD_ID + ".cell_workbench.";
    public static final String CASING_KEY = GUI + "casing";
    public static final String MEMORY_KEY = GUI + "memory";
    public static final String ADDONS_KEY = GUI + "addons";
    public static final String FILTER_KEY = GUI + "filter";
    public static final String EMPTY_KEY = GUI + "empty";
    public static final String CHIP_FIRST_KEY = GUI + "chip_first";
    public static final String BYTES_KEY = GUI + "bytes";
    public static final String KINDS_KEY = GUI + "kinds";
    public static final String KEPT_KEY = GUI + "kept";
    public static final String BULK_KEY = GUI + "bulk";
    public static final String FUZZY_KEY = GUI + "fuzzy";
    public static final String EXACT_KEY = GUI + "exact";
    public static final String ALLOW_KEY = GUI + "allow";
    public static final String DENY_KEY = GUI + "deny";
    public static final String ALLOW_HINT_KEY = GUI + "allow_hint";
    public static final String DENY_HINT_KEY = GUI + "deny_hint";
    public static final String TAG_KEY = GUI + "tag";
    public static final String FILTER_HINT_KEY = GUI + "filter_hint";
    public static final String CLEAR_UNKNOWN_KEY = GUI + "clear_unknown";
    public static final String CLEAR_UNKNOWN_HINT_KEY = GUI + "clear_unknown_hint";
    public static final String NOTICE_RESEARCH_KEY = GUI + "notice.research";
    public static final String NOTICE_NO_FIT_KEY = GUI + "notice.no_fit";
    public static final String NOTICE_IN_USE_KEY = GUI + "notice.in_use";
    public static final String NOTICE_NO_ROOM_KEY = GUI + "notice.no_room";

    static {
        ModContent.CONTENT.translation(CASING_KEY, "Casing");
        ModContent.CONTENT.translation(MEMORY_KEY, "Memory");
        ModContent.CONTENT.translation(ADDONS_KEY, "Add-ons");
        ModContent.CONTENT.translation(FILTER_KEY, "Filter");
        ModContent.CONTENT.translation(EMPTY_KEY, "Put a cell casing in");
        ModContent.CONTENT.translation(CHIP_FIRST_KEY, "Fit a controller chip");
        ModContent.CONTENT.translation(BYTES_KEY, "%s / %s B");
        ModContent.CONTENT.translation(KINDS_KEY, "%s kinds");
        ModContent.CONTENT.translation(KEPT_KEY, "%s unknown kept");
        ModContent.CONTENT.translation(BULK_KEY, "Bulk: one kind");
        ModContent.CONTENT.translation(FUZZY_KEY, "Fuzzy");
        ModContent.CONTENT.translation(EXACT_KEY, "Exact");
        ModContent.CONTENT.translation(ALLOW_KEY, "Allow listed");
        ModContent.CONTENT.translation(DENY_KEY, "Deny listed");
        ModContent.CONTENT.translation(ALLOW_HINT_KEY, "Allow listed: the cell takes only the listed items and tags");
        ModContent.CONTENT.translation(DENY_HINT_KEY,
                "Deny listed: it takes everything else; void overflow never destroys a listed item");
        ModContent.CONTENT.translation(TAG_KEY, "Tag #%s");
        ModContent.CONTENT.translation(FILTER_HINT_KEY,
                "Click with an item to add it; right-click an entry to cycle its tags; click to clear");
        ModContent.CONTENT.translation(CLEAR_UNKNOWN_KEY, "Clear unknown");
        ModContent.CONTENT.translation(CLEAR_UNKNOWN_HINT_KEY, "Remove %s unknown entries");
        ModContent.CONTENT.translation(NOTICE_RESEARCH_KEY, "Research needed");
        ModContent.CONTENT.translation(NOTICE_NO_FIT_KEY, "Contents would not fit");
        ModContent.CONTENT.translation(NOTICE_IN_USE_KEY, "Cell is in a drive");
        ModContent.CONTENT.translation(NOTICE_NO_ROOM_KEY, "Inventory full");
    }

    private WorkbenchContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
