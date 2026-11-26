package dev.silencebound.maxtech.storage.controller.card;

import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The controller's upgrade cards (M7R item 2): one item per {@link CardKind}, with the recipe its expansion block had
 * (casing plates round three processors and two of the module's own parts), and the texts of the card slots. Loaded
 * from {@code ControllerContent.init()}, so the cards follow the controller in the creative tab.
 */
public final class CardContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".controller.cards.";
    public static final String BATCH_TOOLTIP_KEY = "tooltip." + PREFIX + "batch";
    public static final String BATCH_CAP_KEY = "tooltip." + PREFIX + "batch_cap";
    public static final String EFFICIENCY_TOOLTIP_KEY = "tooltip." + PREFIX + "efficiency";
    public static final String EFFICIENCY_CAP_KEY = "tooltip." + PREFIX + "efficiency_cap";
    public static final String SURGE_TOOLTIP_KEY = "tooltip." + PREFIX + "surge";
    public static final String SURGE_CAP_KEY = "tooltip." + PREFIX + "surge_cap";
    public static final String INSTALL_TOOLTIP_KEY = "tooltip." + PREFIX + "install";
    /** The controller screen's Cards tab. */
    public static final String TAB_KEY = "gui." + PREFIX + "tab";
    public static final String SLOTS_KEY = "gui." + PREFIX + "slots";
    public static final String NO_RESEARCH_KEY = "gui." + PREFIX + "no_research";
    public static final String LOCKED_SLOT_KEY = "gui." + PREFIX + "locked_slot";
    public static final String EMPTY_SLOT_KEY = "gui." + PREFIX + "empty_slot";
    public static final String NOT_ALLOWED_KEY = "gui." + PREFIX + "not_allowed";
    public static final String BATCH_LINE_KEY = "gui." + PREFIX + "batch_line";
    public static final String EFFICIENCY_LINE_KEY = "gui." + PREFIX + "efficiency_line";
    public static final String SURGE_LINE_KEY = "gui." + PREFIX + "surge_line";
    public static final String SURGE_OFF_KEY = "gui." + PREFIX + "surge_off";
    /** The Network tab's section and its rows. */
    public static final String HEADER_KEY = "gui." + PREFIX + "header";
    public static final String ROW_KEY = "gui." + PREFIX + "row";
    public static final String NONE_KEY = "gui." + PREFIX + "none";
    /** The controller's info line. */
    public static final String INFO_KEY = "info." + PREFIX + "installed";

    // The I/O processor sizes operations; oscillators clock the larger batches.
    public static final RegistryEntry<Item, UpgradeCardItem> BATCH_AMPLIFIER = card(CardKind.BATCH_AMPLIFIER,
            "batch_amplifier_card", "Batch Amplifier Card", "basic_io_processor", "oscillator");
    // An advanced logic processor plans the moves; superconductor wire loses less on the way.
    public static final RegistryEntry<Item, UpgradeCardItem> EFFICIENCY_CORE = card(CardKind.EFFICIENCY_CORE,
            "efficiency_core_card", "Efficiency Core Card", "advanced_logic_processor", "superconductor_wire");
    // An advanced I/O processor gates the operations; tantalum capacitors smooth the supply.
    public static final RegistryEntry<Item, UpgradeCardItem> SURGE_REGULATOR = card(CardKind.SURGE_REGULATOR,
            "surge_regulator_card", "Surge Regulator Card", "advanced_io_processor", "tantalum_capacitor");

    /** Every card, in {@link CardKind} order. */
    public static final List<RegistryEntry<Item, UpgradeCardItem>> ALL = List.of(BATCH_AMPLIFIER, EFFICIENCY_CORE,
            SURGE_REGULATOR);

    static {
        ModContent.CONTENT.translation(BATCH_TOOLTIP_KEY, "+%s%% items or mB per device operation");
        ModContent.CONTENT.translation(BATCH_CAP_KEY, "Up to %s cards count: +%s%% in all");
        ModContent.CONTENT.translation(EFFICIENCY_TOOLTIP_KEY, "-%s%% MU for the network's operations");
        ModContent.CONTENT.translation(EFFICIENCY_CAP_KEY, "Up to %s cards count: -%s%% in all");
        ModContent.CONTENT.translation(SURGE_TOOLTIP_KEY, "Below %s%% MU, automation waits and players go first");
        ModContent.CONTENT.translation(SURGE_CAP_KEY, "Up to %s card counts");
        ModContent.CONTENT.translation(INSTALL_TOOLTIP_KEY, "Goes in a slot on the controller's Cards tab");
        ModContent.CONTENT.translation(TAB_KEY, "Cards");
        ModContent.CONTENT.translation(SLOTS_KEY, "Card slots: %s of %s in use");
        ModContent.CONTENT.translation(NO_RESEARCH_KEY, "The owner's team needs the research Upgrade Cards");
        ModContent.CONTENT.translation(LOCKED_SLOT_KEY, "Locked: more controller blocks open more slots");
        ModContent.CONTENT.translation(EMPTY_SLOT_KEY, "Batch amplifier, efficiency core or surge regulator card");
        ModContent.CONTENT.translation(NOT_ALLOWED_KEY, "Only players who may configure the network change cards");
        ModContent.CONTENT.translation(BATCH_LINE_KEY, "Device operations: %s%% of the normal size");
        ModContent.CONTENT.translation(EFFICIENCY_LINE_KEY, "Operations cost %s%% of their MU");
        ModContent.CONTENT.translation(SURGE_LINE_KEY, "Below %s%% MU, automation waits and players go first");
        ModContent.CONTENT.translation(SURGE_OFF_KEY, "No surge regulator: automation may use the last MU");
        ModContent.CONTENT.translation(HEADER_KEY, "Upgrade cards");
        ModContent.CONTENT.translation(ROW_KEY, "%s x %s");
        ModContent.CONTENT.translation(NONE_KEY, "None installed");
        ModContent.CONTENT.translation(INFO_KEY, "Cards: %s in %s slots");
    }

    private CardContent() {
    }

    /** The card item of {@code kind}. */
    public static Item item(CardKind kind) {
        return switch (kind) {
            case BATCH_AMPLIFIER -> BATCH_AMPLIFIER.get();
            case EFFICIENCY_CORE -> EFFICIENCY_CORE.get();
            case SURGE_REGULATOR -> SURGE_REGULATOR.get();
        };
    }

    /** The kind of card {@code stack} is, or null for anything else. */
    public static @Nullable CardKind kind(ItemStack stack) {
        return stack.getItem() instanceof UpgradeCardItem card ? card.kind() : null;
    }

    /** A card {@code name}: {@code PXP / YXY / PXP}, the recipe of the expansion block it replaced. */
    private static RegistryEntry<Item, UpgradeCardItem> card(CardKind kind, String name, String english,
            String processor, String part) {
        return ModContent.CONTENT.item(name, p -> new UpgradeCardItem(p, kind))
                .model(ItemModel.flat(MaxtechStorage.id("item/" + name))).lang(english)
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PXP").pattern("YXY")
                        .pattern("PXP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                        .define('X', MaterialTags.component(processor)).define('Y', MaterialTags.component(part))
                        .unlockedBy("has_" + processor, recipes.has(MaterialTags.component(processor)))
                        .save(recipes.output()))
                .creativeTab().register();
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
