package dev.silencebound.maxtech.storage.tool.setup;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;

/**
 * The setup blueprint (M7C item 21): the item, its components and texts, and the ghost payload. The components live
 * here rather than in {@code ModDataComponents}, as the blueprint card's do.
 */
public final class SetupContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".setup_blueprint.";
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /** The copied group. Synced, for the tooltip; capped by {@link SetupBlueprint#MAX_ENTRIES}. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<SetupBlueprint>> BLUEPRINT = COMPONENTS
            .register("setup_blueprint", () -> DataComponentType.<SetupBlueprint>builder()
                    .persistent(SetupBlueprint.CODEC).networkSynchronized(SetupBlueprint.STREAM_CODEC).build());
    /** The first corner while selecting. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> CORNER = COMPONENTS
            .register("setup_corner", () -> DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC).build());
    /** How the next paste is turned; absent means not turned. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Rotation>> ROTATION = COMPONENTS
            .register("setup_rotation", () -> DataComponentType.<Rotation>builder().persistent(Rotation.CODEC)
                    .networkSynchronized(Rotation.STREAM_CODEC).build());

    public static final RegistryEntry<Item, SetupBlueprintItem> ITEM = ModContent.CONTENT
            .item("setup_blueprint", SetupBlueprintItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/setup_blueprint"))).lang("Setup Blueprint")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PMP").pattern(" L ")
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final String CORNER_KEY = "message." + PREFIX + "corner";
    public static final String COPIED_KEY = "message." + PREFIX + "copied";
    public static final String COPIED_PARTLY_KEY = "message." + PREFIX + "copied_partly";
    public static final String TOO_LARGE_KEY = "message." + PREFIX + "too_large";
    public static final String TOO_MANY_KEY = "message." + PREFIX + "too_many";
    public static final String TOO_BIG_KEY = "message." + PREFIX + "too_big";
    public static final String UNLOADED_KEY = "message." + PREFIX + "unloaded";
    public static final String EMPTY_KEY = "message." + PREFIX + "empty";
    public static final String CANCELLED_KEY = "message." + PREFIX + "cancelled";
    public static final String PASTED_KEY = "message." + PREFIX + "pasted";
    public static final String REMOVED_KEY = "message." + PREFIX + "removed";
    public static final String ROTATED_KEY = "message." + PREFIX + "rotated";
    public static final String APPLIED_KEY = "message." + PREFIX + "applied";
    public static final String DENIED_KEY = "message." + PREFIX + "denied";
    public static final String FINISHED_KEY = "message." + PREFIX + "finished";
    public static final String BUILD_STARTED_KEY = "message." + PREFIX + "build_started";
    public static final String BUILD_NO_TERMINAL_KEY = "message." + PREFIX + "build_no_terminal";
    public static final String BUILD_DENIED_KEY = "message." + PREFIX + "build_denied";
    public static final String BUILD_OTHER_LEVEL_KEY = "message." + PREFIX + "build_other_level";
    public static final String BUILD_STOPPED_KEY = "message." + PREFIX + "build_stopped";
    public static final String BUILT_KEY = "message." + PREFIX + "built";
    public static final String BUILD_MISSING_KEY = "message." + PREFIX + "build_missing";
    public static final String BUILD_ITEM_KEY = "message." + PREFIX + "build_item";
    public static final String BUILD_MORE_KEY = "message." + PREFIX + "build_more";
    public static final String BUILD_BLOCKED_KEY = "message." + PREFIX + "build_blocked";
    public static final String TOOLTIP_EMPTY_KEY = "tooltip." + PREFIX + "empty";
    public static final String TOOLTIP_CORNER_KEY = "tooltip." + PREFIX + "corner";
    public static final String TOOLTIP_HELD_KEY = "tooltip." + PREFIX + "held";
    public static final String TOOLTIP_TURNED_KEY = "tooltip." + PREFIX + "turned";
    public static final String TOOLTIP_USE_KEY = "tooltip." + PREFIX + "use";
    public static final String TOOLTIP_BUILD_KEY = "tooltip." + PREFIX + "build";

    static {
        ModContent.CONTENT.translation(CORNER_KEY, "First corner set; use on the opposite corner to copy");
        ModContent.CONTENT.translation(COPIED_KEY, "Copied %s device blocks");
        ModContent.CONTENT.translation(COPIED_PARTLY_KEY,
                "Copied %s device blocks; %s without settings, you lack access to them");
        ModContent.CONTENT.translation(TOO_LARGE_KEY, "Too large: at most %s blocks along each side");
        ModContent.CONTENT.translation(TOO_MANY_KEY, "Too many devices: at most %s");
        ModContent.CONTENT.translation(TOO_BIG_KEY,
                "Too much setup data to copy (over %s KiB); copy a smaller area or fewer filters");
        ModContent.CONTENT.translation(UNLOADED_KEY, "Part of the area is not loaded");
        ModContent.CONTENT.translation(EMPTY_KEY, "No MaxTech devices between the corners");
        ModContent.CONTENT.translation(CANCELLED_KEY, "Selection cancelled");
        ModContent.CONTENT.translation(PASTED_KEY, "Ghost placed; build into it to set the devices up");
        ModContent.CONTENT.translation(REMOVED_KEY, "Ghost removed");
        ModContent.CONTENT.translation(ROTATED_KEY, "Turned %s degrees");
        ModContent.CONTENT.translation(APPLIED_KEY, "Set up from the blueprint");
        ModContent.CONTENT.translation(DENIED_KEY, "Placed, but you lack access to set it up");
        ModContent.CONTENT.translation(FINISHED_KEY, "Blueprint finished");
        ModContent.CONTENT.translation(BUILD_STARTED_KEY, "Building the ghost from the network");
        ModContent.CONTENT.translation(BUILD_NO_TERMINAL_KEY,
                "Carry a wireless terminal that reaches its network to build from it");
        ModContent.CONTENT.translation(BUILD_DENIED_KEY, "You may not take items out of that network");
        ModContent.CONTENT.translation(BUILD_OTHER_LEVEL_KEY, "The ghost is in another dimension");
        ModContent.CONTENT.translation(BUILD_STOPPED_KEY,
                "Building stopped: the terminal no longer reaches its network");
        ModContent.CONTENT.translation(BUILT_KEY, "Built %s blocks and parts.");
        ModContent.CONTENT.translation(BUILD_MISSING_KEY, "Not in storage: %s.");
        ModContent.CONTENT.translation(BUILD_ITEM_KEY, "%s x %s");
        ModContent.CONTENT.translation(BUILD_MORE_KEY, " and %s more");
        ModContent.CONTENT.translation(BUILD_BLOCKED_KEY, "%s places refused (blocked or protected).");
        ModContent.CONTENT.translation(TOOLTIP_EMPTY_KEY, "Use on two corners to copy the devices between them");
        ModContent.CONTENT.translation(TOOLTIP_CORNER_KEY, "First corner at %s %s %s");
        ModContent.CONTENT.translation(TOOLTIP_HELD_KEY, "Holds %s device blocks, %s x %s x %s");
        ModContent.CONTENT.translation(TOOLTIP_TURNED_KEY, "Turned %s degrees");
        ModContent.CONTENT.translation(TOOLTIP_USE_KEY,
                "Use on a block to place a ghost; sneak-use in the air to turn it, sneak-use on a ghost to remove it, "
                        + "sneak-use elsewhere to copy anew");
        ModContent.CONTENT.translation(TOOLTIP_BUILD_KEY,
                "Use on a ghost to build it from the network of a carried wireless terminal");
        SetupGhosts.register();
    }

    private SetupContent() {
    }

    /** Loads the class, which registers everything above. */
    public static void init() {
    }
}
