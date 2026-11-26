package dev.silencebound.maxtech.storage.display;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.text.RichText;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.display.material.MaterialTexts;
import dev.silencebound.maxtech.storage.display.production.ProductionTexts;
import dev.silencebound.maxtech.storage.display.task.TaskBoardSync;
import dev.silencebound.maxtech.storage.display.task.TaskBoards;
import dev.silencebound.maxtech.storage.display.task.TaskTexts;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * Display panels (M6c area D): the panel block, its part form, the block entity type, the screen payloads, the research
 * gate of the graph modes and the GUI lines. The block's models are written by Storage's model provider
 * ({@code DisplayModels}).
 */
public final class DisplayContent {
    /** Recipes name the two forms by these tags, never the items. */
    public static final TagKey<Item> PANEL_TAG = MaterialTags.component("display_panel");
    public static final TagKey<Item> PANEL_PART_TAG = MaterialTags.component("display_panel_part");
    /** The unlock the line and bar graph modes need (research node {@code holo_displays}). */
    public static final Identifier HOLO_GRAPHS = MaxtechStorage.id("holo_graphs");

    public static final BlockEntry<DisplayPanelBlock, DisplayItems.PanelItem> PANEL = ModContent.CONTENT
            .block("display_panel", DisplayPanelBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(1.5F, 6.0F).sound(SoundType.METAL).noOcclusion())
            .item(DisplayItems.PanelItem::new).lang("Display Panel").loot(BlockLoot.SELF)
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(PANEL_TAG)
            // Display glass for the screen, a signal processor to drive it, casing plates and wire around them.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item, 2).pattern("PGP").pattern("GSG")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('G', MaterialTags.component("display_glass"))
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_display_glass", recipes.has(MaterialTags.component("display_glass")))
                    .save(recipes.output()))
            // The part back into the block form, 1:1, with its settings.
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("display_panel_from_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(PANEL_PART_TAG)))
            .creativeTab().register();

    /** The panel as a part on a cable's face; Storage's model provider draws its item. */
    public static final RegistryEntry<Item, PartItem> PANEL_PART_ITEM = ModContent.CONTENT
            .item("display_panel_part", p -> new PartItem(p, () -> DisplayContent.PANEL_PART))
            .lang("Display Panel Part").tag(PANEL_PART_TAG)
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("display_panel_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(PANEL_TAG)))
            .creativeTab().register();

    /** A 12 x 12 x 2 px screen, as the terminal part, so parts on neighbouring faces never meet. */
    public static final PartType PANEL_PART = PartTypes
            .register(new PartType(MaxtechStorage.id("display_panel"), PANEL_PART_ITEM,
                    List.of(new AABB(2, 2, 0, 14, 14, 2)), DisplayPart::new).tooltip(DisplayItems::appendTooltip));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<DisplayPanelBlockEntity>> PANEL_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("display_panel", () -> new BlockEntityType<>(DisplayPanelBlockEntity::new, Set.of(PANEL.get())));

    /** Tooltip: what the screen shows ({@code %s} source, {@code %s} mode). */
    public static final String SHOWS_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".display.shows";
    /** GUI and screen lines. */
    public static final String WINDOW_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.window";
    public static final String ACCENT_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.accent";
    public static final String NOTHING_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.nothing";
    public static final String GRAPHS_LOCKED_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.graphs_locked";
    public static final String OFFLINE_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.offline";
    public static final String STOPPED_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.stopped";
    /** Settings GUI: what a screen showing logic notices shows. */
    public static final String NOTICES_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.notices_hint";
    /** On the screen: no notice from the last {@link DisplayView#NOTICE_TICKS} ticks. */
    public static final String NO_NOTICES_KEY = "gui." + MaxtechStorage.MOD_ID + ".display.no_notices";
    /** History chart settings and screen texts (M7L2). */
    public static final String CHART_VALUE_HINT_KEY = chart("value_hint");
    public static final String CHART_VALUE_TIP_KEY = chart("value_tip");
    public static final String CHART_BAD_VALUE_KEY = chart("bad_value");
    public static final String CHART_INTERVAL_KEY = chart("interval");
    public static final String CHART_WINDOW_KEY = chart("window");
    public static final String CHART_SECONDS_KEY = chart("seconds");
    public static final String CHART_MINUTES_KEY = chart("minutes");
    public static final String CHART_LIMIT_KEY = chart("limit");
    public static final String CHART_UNSET_KEY = chart("unset");
    public static final String CHART_WAITING_KEY = chart("waiting");
    public static final String CHART_MIN_KEY = chart("min");
    public static final String CHART_MAX_KEY = chart("max");
    /** Settings GUI: what a screen showing its own text shows, and the button to the text editor. */
    public static final String TEXT_HINT_KEY = text("hint");
    public static final String EDIT_TEXT_KEY = text("edit");
    /** The text editor's lines, buttons and tooltips. */
    public static final String EDITOR_TITLE_KEY = text("title");
    public static final String SAVE_KEY = text("save");
    public static final String CANCEL_KEY = text("cancel");
    public static final String BOLD_KEY = text("bold");
    public static final String ITALIC_KEY = text("italic");
    public static final String CLOSE_TAG_KEY = text("close_tag");
    public static final String SMALL_KEY = text("small");
    public static final String NORMAL_KEY = text("normal");
    public static final String BIG_KEY = text("big");
    public static final String LEFT_KEY = text("left");
    public static final String CENTRE_KEY = text("centre");
    public static final String RIGHT_KEY = text("right");
    public static final String HELD_ICON_KEY = text("held_icon");
    public static final String HELD_ICON_TIP_KEY = text("held_icon_tip");
    public static final String SEARCH_HINT_KEY = text("search_hint");
    public static final String ITEM_COUNT_KEY = text("item_count");
    public static final String ITEM_COUNT_TIP_KEY = text("item_count_tip");
    public static final String TAG_COUNT_KEY = text("tag_count");
    public static final String TAG_COUNT_TIP_KEY = text("tag_count_tip");
    public static final String SENSOR_KEY = text("sensor");
    public static final String SENSOR_TIP_KEY = text("sensor_tip");
    public static final String MARKUP_TIP_KEY = text("markup_tip");
    public static final String LENGTH_KEY = text("length");

    /** A list screen's ages after a row's note ({@link ScreenRows.Row#time()}). */
    public static final String AGE_NOW_KEY = rows("now");
    public static final String AGE_MINUTES_KEY = rows("minutes");
    public static final String AGE_HOURS_KEY = rows("hours");
    public static final String AGE_DAYS_KEY = rows("days");
    /** A row's note followed by its age. */
    public static final String NOTE_AGE_KEY = rows("note_age");

    /** The lang key of a list screen line. */
    private static String rows(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".display.rows." + name;
    }

    /** The lang key of a history chart line. */
    private static String chart(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".display.chart." + name;
    }

    /** The lang key of a text editor line. */
    private static String text(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".display.text." + name;
    }

    /** The lang key of the name of text colour {@code name} ({@link RichText#COLOUR_NAMES}). */
    public static String colourKey(String name) {
        return text("colour." + name);
    }

    static {
        for (DisplayMode mode : DisplayMode.values()) {
            ModContent.CONTENT.translation(mode.translationKey(), switch (mode) {
                case VALUE -> "Value";
                case LINE -> "Line graph";
                case BAR -> "Bar graph";
            });
        }
        for (DisplaySourceKind source : DisplaySourceKind.values()) {
            ModContent.CONTENT.translation(source.translationKey(), switch (source) {
                case ITEM_COUNT -> "Item count";
                case TAG_COUNT -> "Tag count";
                case THROUGHPUT -> "Throughput";
                case POWER_STORED -> "Power stored";
                case POWER_IN -> "Power in";
                case POWER_OUT -> "Power out";
                case BYTES_USED -> "Bytes used";
                case BYTES_FREE -> "Bytes free";
                case LOGIC_NOTICES -> "Logic notices";
                case TEXT -> "Text";
                case HISTORY -> "History chart";
                case TASK_BOARD -> "Task board";
                case ALARMS -> "Alarm panel";
                case MATERIAL_LIST -> "Build material list";
                case PRODUCTION -> "Production stats";
            });
        }
        ModContent.CONTENT.translation(DisplaySourceKind.HISTORY.unitKey(), "%s");
        ModContent.CONTENT.translation(DisplaySourceKind.TASK_BOARD.unitKey(), "%s");
        ModContent.CONTENT.translation(DisplaySourceKind.ALARMS.unitKey(), "%s");
        ModContent.CONTENT.translation(DisplaySourceKind.PRODUCTION.unitKey(), "%s");
        ModContent.CONTENT.translation(CHART_VALUE_HINT_KEY, "energy, count:iron_ingot, sensor:label:value ...");
        ModContent.CONTENT.translation(CHART_VALUE_TIP_KEY,
                "What to record: energy, bytes, items, fluid, count:<item>, fluid:<fluid>,"
                        + " sensor:<label>:<value id>, variable:<controller label>:<name> or"
                        + " pin:<controller label>:<rule>:<n>. Enter saves.");
        ModContent.CONTENT.translation(CHART_BAD_VALUE_KEY, "Cannot record \"%s\"");
        ModContent.CONTENT.translation(CHART_INTERVAL_KEY, "Every %s");
        ModContent.CONTENT.translation(CHART_WINDOW_KEY, "Window: %s h");
        ModContent.CONTENT.translation(CHART_SECONDS_KEY, "%s s");
        ModContent.CONTENT.translation(CHART_MINUTES_KEY, "%s min");
        ModContent.CONTENT.translation(CHART_LIMIT_KEY, "History limit reached");
        ModContent.CONTENT.translation(CHART_UNSET_KEY, "Set a value to record");
        ModContent.CONTENT.translation(CHART_WAITING_KEY, "Waiting for the first sample");
        ModContent.CONTENT.translation(CHART_MIN_KEY, "min %s");
        ModContent.CONTENT.translation(CHART_MAX_KEY, "max %s");
        ModContent.CONTENT.translation(TEXT_HINT_KEY, "Your own text");
        ModContent.CONTENT.translation(EDIT_TEXT_KEY, "Edit text");
        ModContent.CONTENT.translation(EDITOR_TITLE_KEY, "Display Text");
        ModContent.CONTENT.translation(SAVE_KEY, "Save");
        ModContent.CONTENT.translation(CANCEL_KEY, "Cancel");
        ModContent.CONTENT.translation(BOLD_KEY, "Bold");
        ModContent.CONTENT.translation(ITALIC_KEY, "Italic");
        ModContent.CONTENT.translation(CLOSE_TAG_KEY, "Close the last open tag");
        ModContent.CONTENT.translation(SMALL_KEY, "Small");
        ModContent.CONTENT.translation(NORMAL_KEY, "Normal");
        ModContent.CONTENT.translation(BIG_KEY, "Big");
        ModContent.CONTENT.translation(LEFT_KEY, "Left");
        ModContent.CONTENT.translation(CENTRE_KEY, "Centre");
        ModContent.CONTENT.translation(RIGHT_KEY, "Right");
        ModContent.CONTENT.translation(HELD_ICON_KEY, "Held item");
        ModContent.CONTENT.translation(HELD_ICON_TIP_KEY, "Insert the icon of the item in your main hand");
        ModContent.CONTENT.translation(SEARCH_HINT_KEY, "Search icons");
        ModContent.CONTENT.translation(ITEM_COUNT_KEY, "Item count");
        ModContent.CONTENT.translation(ITEM_COUNT_TIP_KEY,
                "Insert a live count of the item in the settings slot (or stone, to change)");
        ModContent.CONTENT.translation(TAG_COUNT_KEY, "Tag count");
        ModContent.CONTENT.translation(TAG_COUNT_TIP_KEY,
                "Insert a live count of the tag picked in the settings (or a tag to change)");
        ModContent.CONTENT.translation(SENSOR_KEY, "Sensor value");
        ModContent.CONTENT.translation(SENSOR_TIP_KEY,
                "Insert a live sensor value: replace \"label\" with a sensor's label and \"redstone\" with a value id"
                        + " such as comparator, energy.stored, items.total or item.minecraft:iron_ingot");
        ModContent.CONTENT.translation(MARKUP_TIP_KEY,
                "Tags: <red>, <b>, <i>, </> closes; per line <big>, <small>, <center>, <right>; icons [item:id];"
                        + " values {count:id}, {count:#tag}, {sensor:label:value}. The guide has the details.");
        ModContent.CONTENT.translation(LENGTH_KEY, "%s / %s");
        ModContent.CONTENT.translation(DisplayText.TOO_LONG_KEY, "Too long: at most %s characters and 24 lines");
        ModContent.CONTENT.translation(DisplayText.NO_SUCH_ITEM_KEY, "No such item: %s");
        ModContent.CONTENT.translation(DisplayText.BAD_COUNT_KEY, "Cannot count \"%s\": use an item id or #tag");
        ModContent.CONTENT.translation(DisplayText.BAD_SENSOR_KEY, "Cannot read sensor \"%s\": use label:value_id");
        for (int i = 0; i < RichText.COLOUR_NAMES.size(); i++) {
            String name = RichText.COLOUR_NAMES.get(i);
            ModContent.CONTENT.translation(colourKey(name), switch (name) {
                case "black" -> "Black";
                case "dark_blue" -> "Dark blue";
                case "dark_green" -> "Dark green";
                case "dark_aqua" -> "Dark aqua";
                case "dark_red" -> "Dark red";
                case "dark_purple" -> "Dark purple";
                case "gold" -> "Gold";
                case "gray" -> "Grey";
                case "dark_gray" -> "Dark grey";
                case "blue" -> "Blue";
                case "green" -> "Green";
                case "aqua" -> "Aqua";
                case "red" -> "Red";
                case "light_purple" -> "Light purple";
                case "yellow" -> "Yellow";
                default -> "White";
            });
        }
        ModContent.CONTENT.translation(DisplaySourceKind.LOGIC_NOTICES.unitKey(), "%s notices");
        ModContent.CONTENT.translation(NOTICES_HINT_KEY, "From logic notify nodes");
        ModContent.CONTENT.translation(NO_NOTICES_KEY, "No recent notices");
        ModContent.CONTENT.translation(DisplaySourceKind.ITEM_COUNT.unitKey(), "%s");
        ModContent.CONTENT.translation(DisplaySourceKind.THROUGHPUT.unitKey(), "%s ops/s");
        ModContent.CONTENT.translation(DisplaySourceKind.POWER_STORED.unitKey(), "%s MU");
        ModContent.CONTENT.translation(DisplaySourceKind.POWER_IN.unitKey(), "%s MU/t");
        ModContent.CONTENT.translation(DisplaySourceKind.BYTES_USED.unitKey(), "%s B");
        ModContent.CONTENT.translation(SHOWS_KEY, "Shows: %s (%s)");
        ModContent.CONTENT.translation(WINDOW_KEY, "Window: %s s");
        ModContent.CONTENT.translation(ACCENT_KEY, "Colour");
        ModContent.CONTENT.translation(NOTHING_KEY, "Nothing set");
        ModContent.CONTENT.translation(GRAPHS_LOCKED_KEY, "Graphs need research");
        ModContent.CONTENT.translation(AGE_NOW_KEY, "now");
        ModContent.CONTENT.translation(AGE_MINUTES_KEY, "%s min");
        ModContent.CONTENT.translation(AGE_HOURS_KEY, "%s h");
        ModContent.CONTENT.translation(AGE_DAYS_KEY, "%s d");
        ModContent.CONTENT.translation(NOTE_AGE_KEY, "%s · %s");
        ModContent.CONTENT.translation(OFFLINE_KEY, "OFFLINE");
        ModContent.CONTENT.translation(STOPPED_KEY, "NO POWER");
        DisplaySync.register();
        TaskTexts.init();
        ProductionTexts.init();
        MaterialTexts.init();
        TaskBoards.init();
        TaskBoardSync.register();
        Platform.get().lifecycle().onServerStopped(server -> {
            DisplayViewers.clear();
            DisplayTextSave.clear();
            DisplayChartSave.clear();
            DisplayListSave.clear();
        });
    }

    private DisplayContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
