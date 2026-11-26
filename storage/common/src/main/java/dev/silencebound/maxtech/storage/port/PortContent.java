package dev.silencebound.maxtech.storage.port;

import dev.silencebound.maxtech.storage.crafting.pattern.PatternContent;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.iface.InterfaceCrafting;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * Storage ports of tiers 1 to 3, each as a block and as a cable part, the speed card, the shared block entity type, GUI
 * lines and recipes. Block state models (facing and mode) are written by Storage's model provider; the menu type sits
 * in {@code registry.ModMenus}.
 */
public final class PortContent {
    /** Recipes name the forms by these tags, never the items. */
    public static final TagKey<Item> PORT_TAG = MaterialTags.component("storage_port_tier_1");
    public static final TagKey<Item> PORT_PART_TAG = MaterialTags.component("storage_port_tier_1_part");
    public static final TagKey<Item> PORT_TIER_2_TAG = MaterialTags.component("storage_port_tier_2");
    public static final TagKey<Item> PORT_TIER_2_PART_TAG = MaterialTags.component("storage_port_tier_2_part");
    public static final TagKey<Item> PORT_TIER_3_TAG = MaterialTags.component("storage_port_tier_3");
    public static final TagKey<Item> PORT_TIER_3_PART_TAG = MaterialTags.component("storage_port_tier_3_part");

    public static final BlockEntry<PortBlock, PortItems.PortBlockItem> PORT = block(PortTier.TIER_1, PORT_TAG,
            PORT_PART_TAG,
            // A hopper for the moving part, an I/O processor to drive it, casing plates and wire around them.
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PHP").pattern("WOW")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', Items.HOPPER)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('O', MaterialTags.component("basic_io_processor"))
                    .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()));
    /** The port as a part on a cable's face; Storage's model provider draws its item. */
    public static final RegistryEntry<Item, PartItem> PORT_PART_ITEM = partItem(PortTier.TIER_1, PORT_TAG,
            PORT_PART_TAG);
    public static final PartType PORT_PART = partType(PortTier.TIER_1, PORT_PART_ITEM);

    /*
     * The tiers' recipes start from scratch rather than from the tier below, so no recipe eats a port's filter and
     * cards; the conversion recipes between forms keep them.
     */
    public static final BlockEntry<PortBlock, PortItems.PortBlockItem> PORT_TIER_2 = block(PortTier.TIER_2,
            PORT_TIER_2_TAG, PORT_TIER_2_PART_TAG,
            // Glass fibre and a signal processor carry four times the traffic of tier 1.
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PHP").pattern("SOS")
                    .pattern("PFP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', Items.HOPPER)
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('O', MaterialTags.component("basic_io_processor"))
                    .define('F', MaterialTags.component("glass_fibre"))
                    .unlockedBy("has_signal_processor", recipes.has(MaterialTags.component("basic_signal_processor")))
                    .save(recipes.output()));
    public static final RegistryEntry<Item, PartItem> PORT_TIER_2_PART_ITEM = partItem(PortTier.TIER_2, PORT_TIER_2_TAG,
            PORT_TIER_2_PART_TAG);
    public static final PartType PORT_TIER_2_PART = partType(PortTier.TIER_2, PORT_TIER_2_PART_ITEM);

    public static final BlockEntry<PortBlock, PortItems.PortBlockItem> PORT_TIER_3 = block(PortTier.TIER_3,
            PORT_TIER_3_TAG, PORT_TIER_3_PART_TAG,
            // Tier-2 materials: superconductor wire and advanced processors.
            (recipes,
                    item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PHP").pattern("WOW").pattern("PSP")
                            .define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', Items.HOPPER)
                            .define('W', MaterialTags.component("superconductor_wire"))
                            .define('O', MaterialTags.component("advanced_io_processor"))
                            .define('S', MaterialTags.component("advanced_signal_processor"))
                            .unlockedBy("has_advanced_io_processor",
                                    recipes.has(MaterialTags.component("advanced_io_processor")))
                            .save(recipes.output()));
    public static final RegistryEntry<Item, PartItem> PORT_TIER_3_PART_ITEM = partItem(PortTier.TIER_3, PORT_TIER_3_TAG,
            PORT_TIER_3_PART_TAG);
    public static final PartType PORT_TIER_3_PART = partType(PortTier.TIER_3, PORT_TIER_3_PART_ITEM);

    /** A card in a port's speed slot multiplies its rates ({@code ports.speedCards.*}). */
    public static final RegistryEntry<Item, Item> SPEED_CARD = ModContent.CONTENT.item("speed_card", Item::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/speed_card"))).lang("Speed Card")
            // An oscillator clocks the transfers faster; the processor and plates hold it.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern(" O ").pattern("RSR")
                    .pattern("PPP").define('O', MaterialTags.component("oscillator")).define('R', MaterialTags.REDSTONE)
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .unlockedBy("has_oscillator", recipes.has(MaterialTags.component("oscillator")))
                    .save(recipes.output()))
            .creativeTab().register();

    /**
     * The crafting card (M7 C21): in an export port it requests a craft of a filtered key the network has run out of,
     * at most once per {@code crafting.cardIntervalTicks}. A pattern on a card: blank pattern, logic processor, plates.
     */
    public static final RegistryEntry<Item, Item> CRAFTING_CARD = ModContent.CONTENT.item("crafting_card", Item::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/crafting_card"))).lang("Crafting Card")
            .tag(InterfaceCrafting.CARDS)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern(" B ").pattern("RLR")
                    .pattern("PPP").define('B', PatternContent.BLANK_PATTERN.get()).define('R', MaterialTags.REDSTONE)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .unlockedBy("has_blank_pattern", recipes.has(PatternContent.BLANK_PATTERN.get()))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PortBlockEntity>> PORT_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("storage_port", () -> new BlockEntityType<>(PortBlockEntity::new,
                    Set.of(PORT.get(), PORT_TIER_2.get(), PORT_TIER_3.get())));

    public static final String MODE_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.mode";
    public static final String FILTER_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.filter";
    public static final String BLANK_FILTER_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.filter.blank";
    public static final String CARDS_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.cards";
    public static final String CARDS_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".port.cards";
    public static final String CRAFTING_CARD_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".port.crafting_card";
    public static final String RESEARCH_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.research";
    /** "Access: %s": the storage bus's access button and item tooltip. */
    public static final String ACCESS_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.access";
    /** The storage bus's Jade details: target, access, priority. */
    public static final String BUS_DETAIL_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.bus.detail";
    /** "Order: %s": the export order in the item tooltip and Jade (B09 item 18). */
    public static final String ORDER_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.order";
    /** The mode tab's caption over the export order button. */
    public static final String ORDER_CAPTION_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.order.caption";
    /** A filter slot's tooltip: how it matches ("Matches: %s"). */
    public static final String MATCH_LINE_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.match";
    /** A filter slot's tooltip: how to change the match mode. */
    public static final String MATCH_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.match.hint";
    /** A filter slot's tooltip: its keep amount ("Keeps %s in the target"). */
    public static final String KEEP_LINE_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.keep";
    /** A filter slot's tooltip in export mode: how to change the keep amount. */
    public static final String KEEP_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.keep.hint";
    /** The caption over the keep amount field. */
    public static final String KEEP_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".port.keep.label";

    static {
        ModContent.CONTENT.translation(PortMode.IMPORT.translationKey(), "Import");
        ModContent.CONTENT.translation(PortMode.EXPORT.translationKey(), "Export");
        ModContent.CONTENT.translation(PortMode.STORAGE.translationKey(), "Storage");
        ModContent.CONTENT.translation(BusAccess.READ.translationKey(), "Read");
        ModContent.CONTENT.translation(BusAccess.WRITE.translationKey(), "Write");
        ModContent.CONTENT.translation(BusAccess.BOTH.translationKey(), "Read/Write");
        for (BusStatus status : BusStatus.values()) {
            ModContent.CONTENT.translation(status.translationKey(), status.english());
        }
        ModContent.CONTENT.translation(ACCESS_KEY, "Access: %s");
        ModContent.CONTENT.translation(MatchMode.EXACT.translationKey(), "Exact");
        ModContent.CONTENT.translation(MatchMode.FUZZY.translationKey(), "Fuzzy");
        ModContent.CONTENT.translation(MatchMode.TAG.translationKey(), "Tag");
        ModContent.CONTENT.translation(MatchMode.MOD.translationKey(), "Mod");
        ModContent.CONTENT.translation(PortOrder.FILTER.translationKey(), "Filter order");
        ModContent.CONTENT.translation(PortOrder.ROUND_ROBIN.translationKey(), "Round robin");
        ModContent.CONTENT.translation(PortOrder.FILTER.hintKey(), "Every export starts at the first entry");
        ModContent.CONTENT.translation(PortOrder.ROUND_ROBIN.hintKey(), "Each export starts after the last item moved");
        ModContent.CONTENT.translation(ORDER_KEY, "Order: %s");
        ModContent.CONTENT.translation(ORDER_CAPTION_KEY, "Export order");
        ModContent.CONTENT.translation(MATCH_LINE_KEY, "Matches: %s");
        ModContent.CONTENT.translation(MATCH_HINT_KEY, "Right-click: exact, fuzzy, tag, mod");
        ModContent.CONTENT.translation(KEEP_LINE_KEY, "Keeps %s in the target");
        ModContent.CONTENT.translation(KEEP_HINT_KEY, "Scroll: keep amount (Shift: 64)");
        ModContent.CONTENT.translation(KEEP_LABEL_KEY, "Keep");
        ModContent.CONTENT.translation(BUS_DETAIL_KEY, "Storage bus on %s, %s, priority %s");
        ModContent.CONTENT.translation(MODE_KEY, "Mode: %s");
        ModContent.CONTENT.translation(FILTER_KEY, "Filter");
        ModContent.CONTENT.translation(BLANK_FILTER_KEY, "Empty: imports or mounts everything, exports nothing");
        ModContent.CONTENT.translation(CARDS_LABEL_KEY, "Cards");
        ModContent.CONTENT.translation(CRAFTING_CARD_KEY, "Crafting card");
        ModContent.CONTENT.translation(CARDS_KEY, "Speed cards: %s");
        ModContent.CONTENT.translation(RESEARCH_KEY, "Needs research: %s");
        PortResearchWaits.init();
        PortPayloads.register();
    }

    private PortContent() {
    }

    /** The block form of {@code tier}, with its crafting recipe and the conversion back from the part. */
    private static BlockEntry<PortBlock, PortItems.PortBlockItem> block(PortTier tier, TagKey<Item> tag,
            TagKey<Item> partTag, ContentRecipe<? super PortItems.PortBlockItem> recipe) {
        String name = tier.blockName();
        return ModContent.CONTENT.block(name, p -> new PortBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
                .item(PortItems.PortBlockItem::new).lang("Storage Port (Tier " + tier.number() + ")")
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(tag).recipe(recipe)
                // The part back into the block form, 1:1, with its filter, mode and cards.
                .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                        ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name + "_from_part")),
                        RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(partTag)))
                .creativeTab().register();
    }

    /** The part item of {@code tier}, with the conversion from the block form. */
    private static RegistryEntry<Item, PartItem> partItem(PortTier tier, TagKey<Item> blockTag, TagKey<Item> tag) {
        String name = tier.blockName() + "_part";
        return ModContent.CONTENT.item(name, p -> new PartItem(p, () -> partType(tier)))
                .lang("Storage Port Part (Tier " + tier.number() + ")").tag(tag)
                .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                        ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name)), RecipeCategory.REDSTONE,
                        new ItemStackTemplate(item), recipes.ingredient(blockTag)))
                .creativeTab().register();
    }

    /**
     * The part type of {@code tier}: one box (10 x 10 x 6 px) round the steps of either mode ({@link PortShapes}), the
     * hit shape; the cable model draws the steps of the part's mode, and the cable's arm meets them.
     */
    private static PartType partType(PortTier tier, RegistryEntry<Item, PartItem> item) {
        return PartTypes.register(new PartType(MaxtechStorage.id(tier.blockName()), item,
                List.of(new AABB(3, 3, 0, 13, 13, 3 * PortShapes.STEP_DEPTH)),
                (type, host, face) -> new PortPart(type, host, face, tier))
                .tooltip((stack, lines) -> PortItems.appendTooltip(tier, stack, lines)));
    }

    /** The registered part type of {@code tier}. */
    public static PartType partType(PortTier tier) {
        return switch (tier) {
            case TIER_1 -> PORT_PART;
            case TIER_2 -> PORT_TIER_2_PART;
            case TIER_3 -> PORT_TIER_3_PART;
        };
    }

    /** The block form of {@code tier}. */
    public static BlockEntry<PortBlock, PortItems.PortBlockItem> block(PortTier tier) {
        return switch (tier) {
            case TIER_1 -> PORT;
            case TIER_2 -> PORT_TIER_2;
            case TIER_3 -> PORT_TIER_3;
        };
    }

    /** The part item of {@code tier}. */
    public static RegistryEntry<Item, PartItem> partItem(PortTier tier) {
        return switch (tier) {
            case TIER_1 -> PORT_PART_ITEM;
            case TIER_2 -> PORT_TIER_2_PART_ITEM;
            case TIER_3 -> PORT_TIER_3_PART_ITEM;
        };
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
