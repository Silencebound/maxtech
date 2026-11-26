package dev.silencebound.maxtech.storage.tool.belt;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

/** The restock belt (M6e), its marks component, its menu type and its texts. */
public final class BeltContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String FULL_KEY = "message." + PREFIX + "belt.full";
    public static final String MARKED_TOOLTIP_KEY = "tooltip." + PREFIX + "belt.marked";
    public static final String HINT_KEY = "gui." + PREFIX + "belt.hint";
    public static final String HINT_COUNT_KEY = "gui." + PREFIX + "belt.hint_count";

    private static final TagKey<Item> LEATHERS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "leathers"));
    private static final TagKey<Item> CHESTS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "chests"));

    /** The slots a restock belt keeps filled. Synced for its GUI and tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<BeltMarks>> MARKS = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE)
            .register("belt_marks", () -> DataComponentType.<BeltMarks>builder().persistent(BeltMarks.CODEC)
                    .networkSynchronized(BeltMarks.STREAM_CODEC).build());

    /** The belt's slot marking GUI. */
    public static final RegistryEntry<MenuType<?>, MenuType<BeltMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("restock_belt", () -> Platform.get().menus().menuType(BeltMenu::fromNetwork));

    // Leather to wear, a chest's worth of pouches, an oscillator and an I/O processor for the link.
    public static final RegistryEntry<Item, RestockBeltItem> RESTOCK_BELT = ModContent.CONTENT
            .item("restock_belt", RestockBeltItem::new).model(ItemModel.flat(MaxtechStorage.id("item/restock_belt")))
            .lang("Restock Belt")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("LCL").pattern("OIO")
                    .pattern("LPL").define('L', LEATHERS).define('C', CHESTS)
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('O', MaterialTags.component("oscillator"))
                    .define('I', MaterialTags.component("advanced_io_processor"))
                    .unlockedBy("has_oscillator", recipes.has(MaterialTags.component("oscillator")))
                    .save(StorageModules.gate(recipes.output(), StorageModules.WIRELESS)))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(FULL_KEY, "The belt marks %s slots at most");
        ModContent.CONTENT.translation(MARKED_TOOLTIP_KEY, "Keeps %s slots stocked");
        ModContent.CONTENT.translation(HINT_KEY, "Left-click marks a slot, right-click clears it");
        ModContent.CONTENT.translation(HINT_COUNT_KEY, "Scroll over a mark to change its count (shift: 16)");
    }

    private BeltContent() {
    }

    /**
     * Loads the class during mod construction so the entries above are queued before registration runs, and registers
     * the belts' refill pass.
     */
    public static void init() {
        BeltRestock.init();
    }
}
