package dev.silencebound.maxtech.storage.crafting.pattern;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.Direction;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The blank and the encoded pattern (M7 C2) and the pattern tooltip's texts. The data component sits in
 * {@code registry.ModDataComponents}.
 */
public final class PatternContent {
    /** A pattern with nothing on it; the pattern terminal writes it. */
    public static final RegistryEntry<Item, Item> BLANK_PATTERN = ModContent.CONTENT.item("blank_pattern", Item::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/blank_pattern"))).lang("Blank Pattern")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item, 4).pattern("GLG").pattern("LML")
                    .pattern("PPP").define('G', Items.GLASS_PANE).define('L', MaterialTags.LUMEN_QUARTZ)
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .unlockedBy("has_basic_memory_processor",
                            recipes.has(MaterialTags.component("basic_memory_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    /** A pattern carrying a recipe; never in the creative tab, since it is empty without its component. */
    public static final RegistryEntry<Item, PatternItem> ENCODED_PATTERN = ModContent.CONTENT
            .item("encoded_pattern", p -> new PatternItem(p.stacksTo(1)))
            .model(ItemModel.flat(MaxtechStorage.id("item/encoded_pattern"))).lang("Encoded Pattern").register();

    public static final String INVALID_KEY = key("invalid");
    public static final String CRAFTING_KEY = key("crafting");
    public static final String PROCESSING_KEY = key("processing");
    public static final String STONECUTTING_KEY = key("stonecutting");
    public static final String SMITHING_KEY = key("smithing");
    public static final String MAKES_KEY = key("makes");
    public static final String TAKES_KEY = key("takes");
    public static final String CHANCE_KEY = key("chance");
    public static final String BYPRODUCT_KEY = key("byproduct");
    public static final String FACE_KEY = key("face");
    public static final String RETURNED_KEY = key("returned");
    public static final String PRIORITY_KEY = key("priority");
    public static final String UNREADABLE_KEY = key("unreadable");

    static {
        ModContent.CONTENT.translation(INVALID_KEY, "Carries no pattern");
        ModContent.CONTENT.translation(UNREADABLE_KEY, "Unusable: names an unknown entry (mod missing)");
        ModContent.CONTENT.translation(CRAFTING_KEY, "Crafting pattern");
        ModContent.CONTENT.translation(PROCESSING_KEY, "Processing pattern");
        ModContent.CONTENT.translation(STONECUTTING_KEY, "Stonecutting pattern");
        ModContent.CONTENT.translation(SMITHING_KEY, "Smithing pattern");
        ModContent.CONTENT.translation(MAKES_KEY, "Makes:");
        ModContent.CONTENT.translation(TAKES_KEY, "Takes:");
        ModContent.CONTENT.translation(CHANCE_KEY, " (%s%% chance)");
        ModContent.CONTENT.translation(BYPRODUCT_KEY, " (byproduct)");
        ModContent.CONTENT.translation(FACE_KEY, " into %s");
        ModContent.CONTENT.translation(RETURNED_KEY, " (returned)");
        ModContent.CONTENT.translation(PRIORITY_KEY, "Priority: %s");
        for (Direction face : Direction.values()) {
            ModContent.CONTENT.translation(faceKey(face), switch (face) {
                case DOWN -> "the bottom";
                case UP -> "the top";
                case NORTH -> "the north side";
                case SOUTH -> "the south side";
                case WEST -> "the west side";
                case EAST -> "the east side";
            });
        }
    }

    private PatternContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** The lang key naming {@code face} in a pattern. */
    public static String faceKey(Direction face) {
        return key("face." + face.getSerializedName());
    }

    private static String key(String name) {
        return "tooltip." + MaxtechStorage.MOD_ID + ".pattern." + name;
    }
}
