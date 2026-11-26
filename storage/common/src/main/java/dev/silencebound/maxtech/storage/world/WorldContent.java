package dev.silencebound.maxtech.storage.world;

import dev.silencebound.maxtech.lib.api.resource.ItemKey;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ConventionTags;
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
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The world devices (PRD 7.2, Devices): the network chest, collector, placer and breaker, each as a full block and as a
 * part on a storage cable's face, converting 1:1 with their settings. Block models are written by Storage's model
 * provider; the settings menu type sits in {@code registry.ModMenus}.
 */
public final class WorldContent {
    /** Recipes name the two forms by these tags, never the items. */
    public static final TagKey<Item> NETWORK_CHEST_TAG = MaterialTags.component("network_chest");
    public static final TagKey<Item> NETWORK_CHEST_PART_TAG = MaterialTags.component("network_chest_part");

    /** A part's plate (12 x 12 x 2 px) and housing (8 x 8 x 4), as the port part's. */
    private static final List<AABB> PART_BOXES = List.of(new AABB(2, 2, 0, 14, 14, 2), new AABB(4, 4, 2, 12, 12, 6));

    public static final BlockEntry<NetworkChestBlock, WorldBlockItem> NETWORK_CHEST = ModContent.CONTENT
            .block("network_chest", NetworkChestBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.5F, 6.0F).sound(SoundType.METAL))
            .item((block, properties) -> new WorldBlockItem(block, properties, WorldKind.NETWORK_CHEST))
            .model(BlockModel.orientable(MaxtechStorage.id("block/network_chest_front"),
                    MaxtechStorage.id("block/network_chest_side"), MaxtechStorage.id("block/network_chest_top")))
            .lang("Network Chest").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(NETWORK_CHEST_TAG)
            // A chest behind display glass, an I/O processor to answer for the network, casing plates around them.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PDP").pattern("OXO")
                    .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('D', MaterialTags.component("display_glass")).define('X', ConventionTags.CHESTS)
                    .define('O', MaterialTags.component("basic_io_processor"))
                    .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            .recipe(fromPart("network_chest_from_part", NETWORK_CHEST_PART_TAG)).creativeTab().register();

    public static final RegistryEntry<Item, PartItem> NETWORK_CHEST_PART_ITEM = ModContent.CONTENT
            .item("network_chest_part", p -> new PartItem(p, () -> WorldContent.NETWORK_CHEST_PART))
            .lang("Network Chest Part").tag(NETWORK_CHEST_PART_TAG)
            .recipe(fromPart("network_chest_part", NETWORK_CHEST_TAG)).creativeTab().register();

    public static final PartType NETWORK_CHEST_PART = PartTypes.register(
            new PartType(MaxtechStorage.id("network_chest"), NETWORK_CHEST_PART_ITEM, PART_BOXES, NetworkChestPart::new)
                    .tooltip(tooltip(WorldKind.NETWORK_CHEST)));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<NetworkChestBlockEntity>> NETWORK_CHEST_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register("network_chest",
                    () -> new BlockEntityType<>(NetworkChestBlockEntity::new, Set.of(NETWORK_CHEST.get())));

    /** Blocks a breaker never breaks, whatever their hardness. */
    public static final TagKey<Block> BREAKER_BLACKLIST = TagKey.create(Registries.BLOCK,
            MaxtechStorage.id("breaker_blacklist"));
    /** Blocks a placer never places; empty by default, for packs. */
    public static final TagKey<Block> PLACER_BLACKLIST = TagKey.create(Registries.BLOCK,
            MaxtechStorage.id("placer_blacklist"));
    public static final TagKey<Item> COLLECTOR_TAG = MaterialTags.component("collector");
    public static final TagKey<Item> COLLECTOR_PART_TAG = MaterialTags.component("collector_part");
    public static final TagKey<Item> PLACER_TAG = MaterialTags.component("placer_port");
    public static final TagKey<Item> PLACER_PART_TAG = MaterialTags.component("placer_port_part");
    public static final TagKey<Item> BREAKER_TAG = MaterialTags.component("breaker_port");
    public static final TagKey<Item> BREAKER_PART_TAG = MaterialTags.component("breaker_port_part");

    // A hopper's intake around lumen quartz, the radius it reaches over.
    public static final BlockEntry<WorldDeviceBlock, WorldBlockItem> COLLECTOR = device(WorldKind.COLLECTOR,
            "Collector", COLLECTOR_TAG, COLLECTOR_PART_TAG,
            deviceRecipe(Items.HOPPER, MaterialTags.LUMEN_QUARTZ, StorageModules.COLLECTOR));
    // A piston to push the block out, a logic processor to choose it.
    public static final BlockEntry<WorldDeviceBlock, WorldBlockItem> PLACER = device(WorldKind.PLACER, "Placer Port",
            PLACER_TAG, PLACER_PART_TAG,
            deviceRecipe(Items.PISTON, MaterialTags.component("basic_logic_processor"), StorageModules.PLACER_BREAKER));
    // An iron pickaxe for the breaking, a logic processor to drive it.
    public static final BlockEntry<WorldDeviceBlock, WorldBlockItem> BREAKER = device(WorldKind.BREAKER, "Breaker Port",
            BREAKER_TAG, BREAKER_PART_TAG, deviceRecipe(Items.IRON_PICKAXE,
                    MaterialTags.component("basic_logic_processor"), StorageModules.PLACER_BREAKER));

    public static final RegistryEntry<Item, PartItem> COLLECTOR_PART_ITEM = partItem(WorldKind.COLLECTOR,
            "Collector Part", COLLECTOR_PART_TAG, COLLECTOR_TAG, () -> WorldContent.COLLECTOR_PART);
    public static final RegistryEntry<Item, PartItem> PLACER_PART_ITEM = partItem(WorldKind.PLACER, "Placer Port Part",
            PLACER_PART_TAG, PLACER_TAG, () -> WorldContent.PLACER_PART);
    public static final RegistryEntry<Item, PartItem> BREAKER_PART_ITEM = partItem(WorldKind.BREAKER,
            "Breaker Port Part", BREAKER_PART_TAG, BREAKER_TAG, () -> WorldContent.BREAKER_PART);

    public static final PartType COLLECTOR_PART = partType(WorldKind.COLLECTOR, COLLECTOR_PART_ITEM);
    public static final PartType PLACER_PART = partType(WorldKind.PLACER, PLACER_PART_ITEM);
    public static final PartType BREAKER_PART = partType(WorldKind.BREAKER, BREAKER_PART_ITEM);

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<WorldDeviceBlockEntity>> DEVICE_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("world_device", () -> new BlockEntityType<>(WorldDeviceBlockEntity::new,
                    Set.of(COLLECTOR.get(), PLACER.get(), BREAKER.get())));

    public static final String FILTER_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.filter";
    public static final String MODE_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.mode";
    public static final String TOOL_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.tool";
    /** The placer's action setting's caption in the mode tab (B09 item 25). */
    public static final String ACTION_CAPTION_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.action";
    /** Jade and the scanner: "Action: Drop stacks". */
    public static final String ACTION_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.action_label";
    public static final String CONTENTS_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.contents";
    public static final String TAG_HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.tag_hint";
    public static final String MATCHES_TAG_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.matches_tag";
    public static final String NEEDS_RESEARCH_LABEL_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.needs_research";
    public static final String BLANK_CHEST_KEY = "gui." + MaxtechStorage.MOD_ID + ".world.blank.network_chest";

    static {
        ModContent.CONTENT.translation(WorldKind.NETWORK_CHEST.modeKey(0), "3 rows");
        ModContent.CONTENT.translation(WorldKind.NETWORK_CHEST.modeKey(1), "6 rows");
        ModContent.CONTENT.translation(WorldKind.COLLECTOR.modeKey(0), "Always");
        ModContent.CONTENT.translation(WorldKind.PLACER.modeKey(0), "Pulse");
        ModContent.CONTENT.translation(WorldKind.PLACER.modeKey(1), "Continuous");
        ModContent.CONTENT.translation(WorldKind.PLACER.modeHintKey(0), "Acts once for each redstone pulse");
        ModContent.CONTENT.translation(WorldKind.PLACER.modeHintKey(1), "Acts on every cadence while it can");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionKey(0), "Place blocks");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionKey(1), "Drop stacks");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionKey(2), "Drop single items");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionHintKey(0), "Places the filtered block in front");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionHintKey(1),
                "Drops a stack of the filtered item in front, like a dropper");
        ModContent.CONTENT.translation(WorldKind.PLACER.actionHintKey(2),
                "Drops one filtered item in front, like a dropper");
        ModContent.CONTENT.translation(ACTION_CAPTION_KEY, "Action");
        ModContent.CONTENT.translation(ACTION_LABEL_KEY, "Action: %s");
        ModContent.CONTENT.translation(WorldSettings.ACTION_KEY, "Action: %s");
        ModContent.CONTENT.translation(FILTER_LABEL_KEY, "Filter");
        ModContent.CONTENT.translation(MODE_LABEL_KEY, "Mode: %s");
        ModContent.CONTENT.translation(TOOL_LABEL_KEY, "Tool");
        ModContent.CONTENT.translation(CONTENTS_KEY, "Open");
        ModContent.CONTENT.translation(TAG_HINT_KEY, "Right-click with an empty hand to match a tag");
        ModContent.CONTENT.translation(MATCHES_TAG_KEY, "Matches #%s");
        ModContent.CONTENT.translation(NEEDS_RESEARCH_LABEL_KEY, "Needs research: %s");
        ModContent.CONTENT.translation(BLANK_CHEST_KEY, "Empty: shows nothing, takes anything in");
        ModContent.CONTENT.translation(WorldSettings.FILTER_KEY, "Filter: %s");
        ModContent.CONTENT.translation(WorldSettings.MODE_KEY, "Mode: %s");
        ModContent.CONTENT.translation(WorldSettings.TOOL_KEY, "Tool: %s");
        ModContent.CONTENT.translation(NetworkChestMenu.NEEDS_RESEARCH_KEY, "Needs research: %s");
        ModContent.CONTENT.translation(NetworkChestMenu.NO_ACCESS_KEY,
                "No network, or its owner has not given you access");
    }

    private WorldContent() {
    }

    /**
     * A collector, placer or breaker block named after its kind, dropping itself with its settings. The collector's
     * model is a plain cube; the placer's and breaker's facing models come from Storage's model provider.
     */
    private static BlockEntry<WorldDeviceBlock, WorldBlockItem> device(WorldKind kind, String lang, TagKey<Item> tag,
            TagKey<Item> partTag, ContentRecipe<Item> recipe) {
        String name = kind.getSerializedName();
        var chain = ModContent.CONTENT.block(name, properties -> new WorldDeviceBlock(kind, properties))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.5F, 6.0F).sound(SoundType.METAL))
                .item((block, properties) -> new WorldBlockItem(block, properties, kind)).lang(lang)
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(tag).recipe(recipe)
                .recipe(fromPart(name + "_from_part", partTag, StorageModules.of(kind))).creativeTab();
        if (kind == WorldKind.COLLECTOR) {
            chain = chain.model(BlockModel.cubeAll(MaxtechStorage.id("block/world/collector")));
        }
        return chain.register();
    }

    /** Casing plates around {@code top}, two I/O processors and {@code middle}. */
    private static ContentRecipe<Item> deviceRecipe(ItemLike top, TagKey<Item> middle, StorageModules.Module module) {
        TagKey<Item> io = MaterialTags.component("basic_io_processor");
        return (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PHP").pattern("OMO")
                .pattern("PPP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', top).define('M', middle)
                .define('O', io).unlockedBy("has_io_processor", recipes.has(io))
                .save(StorageModules.gate(recipes.output(), module));
    }

    private static RegistryEntry<Item, PartItem> partItem(WorldKind kind, String lang, TagKey<Item> tag,
            TagKey<Item> blockTag, Supplier<PartType> type) {
        String name = kind.getSerializedName() + "_part";
        return ModContent.CONTENT.item(name, p -> new PartItem(p, type)).lang(lang).tag(tag)
                .recipe(fromPart(name, blockTag, StorageModules.of(kind))).creativeTab().register();
    }

    private static PartType partType(WorldKind kind, Supplier<? extends Item> item) {
        return PartTypes.register(new PartType(MaxtechStorage.id(kind.getSerializedName()), item, PART_BOXES,
                (type, host, face) -> new WorldDevicePart(type, host, face, kind)).tooltip(tooltip(kind)));
    }

    /** The 1:1 conversion keeping the settings, from the other form's tag. */
    private static ContentRecipe<Item> fromPart(String name, TagKey<Item> from) {
        return fromPart(name, from, null);
    }

    /** As {@link #fromPart(String, TagKey)}, loading only while {@code module} (when not null) is on. */
    private static ContentRecipe<Item> fromPart(String name, TagKey<Item> from,
            StorageModules.@Nullable Module module) {
        return (recipes, item) -> ComponentCopyingRecipe.save(
                module == null ? recipes.output() : StorageModules.gate(recipes.output(), module),
                ResourceKey.create(Registries.RECIPE, MaxtechStorage.id(name)), RecipeCategory.REDSTONE,
                new ItemStackTemplate(item), recipes.ingredient(from));
    }

    private static BiConsumer<ItemStack, Consumer<Component>> tooltip(WorldKind kind) {
        return (stack, lines) -> WorldSettings.appendTooltip(stack, kind, lines);
    }

    /** Whether {@code key} is a block item whose block is not in {@link WorldContent#PLACER_BLACKLIST}. */
    // builtInRegistryHolder is deprecated for registry-agnostic code; blocks always have one.
    @SuppressWarnings("deprecation")
    public static boolean placeable(ItemKey key) {
        return key.item() instanceof BlockItem block
                && !block.getBlock().builtInRegistryHolder().is(WorldContent.PLACER_BLACKLIST);
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
        WorldGate.init();
        CollectorIndex.init();
    }

    /** A world device's block item, with its settings summary in the tooltip. */
    public static final class WorldBlockItem extends BlockItem {
        private final WorldKind kind;

        public WorldBlockItem(Block block, Properties properties, WorldKind kind) {
            super(block, properties);
            this.kind = kind;
        }

        @Override
        public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                Consumer<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, context, display, lines, flag);
            WorldSettings.appendTooltip(stack, kind, lines);
        }
    }
}
