package dev.silencebound.maxtech.storage.p2p;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * P2P links (PRD 7.2, Devices): per {@link P2PKind} a block {@code p2p_<kind>_link} and a part
 * {@code p2p_<kind>_link_part} that convert 1:1 with their link, one block entity type for all four blocks, the linking
 * tool {@code p2p_linker}, and the texts.
 */
public final class P2PContent {
    public static final Map<P2PKind, BlockEntry<P2PBlock, P2PItems.P2PBlockItem>> BLOCKS = new EnumMap<>(P2PKind.class);
    public static final Map<P2PKind, RegistryEntry<Item, PartItem>> PART_ITEMS = new EnumMap<>(P2PKind.class);
    public static final Map<P2PKind, PartType> PARTS = new EnumMap<>(P2PKind.class);

    static {
        for (P2PKind kind : P2PKind.values()) {
            register(kind);
        }
    }

    public static final RegistryEntry<Item, P2PLinkerItem> LINKER = ModContent.CONTENT
            .item("p2p_linker", P2PLinkerItem::new).model(ItemModel.flat(MaxtechStorage.id("item/p2p_linker")))
            .lang("P2P Linking Tool")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("  D").pattern(" S ")
                    .pattern("P  ").define('D', MaterialTags.component("display_glass"))
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .unlockedBy("has_signal_processor", recipes.has(MaterialTags.component("basic_signal_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<P2PBlockEntity>> BLOCK_ENTITY = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register("p2p_link", () -> {
                Set<Block> blocks = new HashSet<>();
                for (P2PKind kind : P2PKind.values()) {
                    blocks.add(block(kind).get());
                }
                return new BlockEntityType<>(P2PBlockEntity::new, Set.copyOf(blocks));
            });

    private static final String PREFIX = MaxtechStorage.MOD_ID + ".p2p.";
    public static final String DENIED_KEY = "message." + PREFIX + "denied";
    public static final String LINKED_INPUT_KEY = "message." + PREFIX + "linked_input";
    public static final String LINKED_OUTPUT_KEY = "message." + PREFIX + "linked_output";
    public static final String NO_INPUT_KEY = "message." + PREFIX + "no_input";
    public static final String WRONG_KIND_KEY = "message." + PREFIX + "wrong_kind";
    public static final String TOOLTIP_EMPTY_KEY = "tooltip." + PREFIX + "linker_empty";
    public static final String TOOLTIP_HELD_KEY = "tooltip." + PREFIX + "linker_held";
    public static final String TOOLTIP_LINK_KEY = "tooltip." + PREFIX + "link";
    public static final String INFO_LINK_KEY = "info." + PREFIX + "link";
    public static final String INFO_FREQUENCY_KEY = "info." + PREFIX + "frequency";
    public static final String INFO_CONFLICT_KEY = "info." + PREFIX + "conflict";
    public static final String INFO_SIGNAL_KEY = "info." + PREFIX + "signal";

    static {
        ModContent.CONTENT.translation(DENIED_KEY, "The network's owner has not given you access");
        ModContent.CONTENT.translation(LINKED_INPUT_KEY, "%s link input, frequency %s; use the tool on its outputs");
        ModContent.CONTENT.translation(LINKED_OUTPUT_KEY, "%s link output, frequency %s");
        ModContent.CONTENT.translation(NO_INPUT_KEY, "Sneak and use the tool on an input first");
        ModContent.CONTENT.translation(WRONG_KIND_KEY, "The tool holds a %s link, this is a %s link");
        ModContent.CONTENT.translation(TOOLTIP_EMPTY_KEY, "Sneak-use on a link to make it an input");
        ModContent.CONTENT.translation(TOOLTIP_HELD_KEY, "Holds a %s link, frequency %s");
        ModContent.CONTENT.translation(TOOLTIP_LINK_KEY, "%s, frequency %s");
        ModContent.CONTENT.translation(INFO_LINK_KEY, "%s link: %s");
        ModContent.CONTENT.translation(INFO_FREQUENCY_KEY, "Frequency %s");
        ModContent.CONTENT.translation(INFO_CONFLICT_KEY, "Another input holds this frequency");
        ModContent.CONTENT.translation(INFO_SIGNAL_KEY, "Signal %s");
        for (P2PKind kind : P2PKind.values()) {
            ModContent.CONTENT.translation(kind.translationKey(), kind.englishName());
        }
        ModContent.CONTENT.translation(P2PRole.NONE.translationKey(), "not linked");
        ModContent.CONTENT.translation(P2PRole.INPUT.translationKey(), "input");
        ModContent.CONTENT.translation(P2PRole.OUTPUT.translationKey(), "output");
        P2PEndpoint.init();
    }

    private P2PContent() {
    }

    private static void register(P2PKind kind) {
        String name = kind.blockName();
        TagKey<Item> blockTag = MaterialTags.component(name);
        TagKey<Item> partTag = MaterialTags.component(name + "_part");
        BlockEntry<P2PBlock, P2PItems.P2PBlockItem> block = ModContent.CONTENT.block(name, p -> new P2PBlock(p, kind))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
                .item(P2PItems.P2PBlockItem::new).lang("P2P " + kind.englishName() + " Link").loot(BlockLoot.SELF)
                .tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(blockTag)
                // An advanced signal processor drives the link; the middle item says what it carries.
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PXP").pattern("POP")
                        .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('X', ingredient(kind))
                        .define('O', MaterialTags.component("advanced_signal_processor"))
                        .unlockedBy("has_signal_processor",
                                recipes.has(MaterialTags.component("advanced_signal_processor")))
                        .save(StorageModules.gate(recipes.output(), StorageModules.p2p(kind))))
                .recipe((recipes, item) -> ComponentCopyingRecipe.save(
                        StorageModules.gate(recipes.output(), StorageModules.p2p(kind)),
                        ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name + "_from_part")),
                        RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(partTag)))
                .creativeTab().register();
        RegistryEntry<Item, PartItem> partItem = ModContent.CONTENT
                .item(name + "_part", p -> new PartItem(p, () -> P2PContent.part(kind)))
                .lang("P2P " + kind.englishName() + " Link Part").tag(partTag)
                .recipe((recipes, item) -> ComponentCopyingRecipe.save(
                        StorageModules.gate(recipes.output(), StorageModules.p2p(kind)),
                        ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name + "_part")),
                        RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(blockTag)))
                .creativeTab().register();
        // A plate (12 x 12 x 2 px) on the cable's face and a housing (8 x 8 x 2) behind it, as the interface part.
        PartType part = PartTypes.register(new PartType(MaxtechStorage.id(name), partItem,
                List.of(new AABB(2, 2, 0, 14, 14, 2), new AABB(4, 4, 2, 12, 12, 4)),
                (type, host, face) -> new P2PPart(type, host, face, kind)).tooltip(P2PItems::appendTooltip));
        BLOCKS.put(kind, block);
        PART_ITEMS.put(kind, partItem);
        PARTS.put(kind, part);
    }

    /** The block of {@code kind}. */
    public static BlockEntry<P2PBlock, P2PItems.P2PBlockItem> block(P2PKind kind) {
        return Objects.requireNonNull(BLOCKS.get(kind), kind.name());
    }

    /** The part item of {@code kind}. */
    public static RegistryEntry<Item, PartItem> partItem(P2PKind kind) {
        return Objects.requireNonNull(PART_ITEMS.get(kind), kind.name());
    }

    /** The part type of {@code kind}. */
    public static PartType part(P2PKind kind) {
        return Objects.requireNonNull(PARTS.get(kind), kind.name());
    }

    /** The item in the middle of a link's recipe. */
    private static ItemLike ingredient(P2PKind kind) {
        return switch (kind) {
            case ITEM -> Items.HOPPER;
            case FLUID -> Items.BUCKET;
            case ENERGY -> Items.LIGHTNING_ROD;
            case REDSTONE -> Items.REPEATER;
        };
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
