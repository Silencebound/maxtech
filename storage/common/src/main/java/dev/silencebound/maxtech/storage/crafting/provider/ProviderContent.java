package dev.silencebound.maxtech.storage.crafting.provider;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import net.minecraft.resources.Identifier;
import java.util.List;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
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
 * The pattern provider (M7 C2): block, part, block entity type, GUI texts and recipes, which convert the two forms 1:1
 * with their patterns and setting. The menu type sits in {@code registry.ModMenus}.
 */
public final class ProviderContent {
    public static final TagKey<Item> PROVIDER_TAG = MaterialTags.component("pattern_provider");
    public static final TagKey<Item> PROVIDER_PART_TAG = MaterialTags.component("pattern_provider_part");

    public static final BlockEntry<PatternProviderBlock, ProviderItems.ProviderBlockItem> PROVIDER = ModContent.CONTENT
            .block("pattern_provider", PatternProviderBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(ProviderItems.ProviderBlockItem::new).lang("Pattern Provider").loot(BlockLoot.SELF)
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(PROVIDER_TAG)
            // A crafting table for the patterns, an I/O processor to drive the machine, memory to hold them.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PMP").pattern("OCO")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('M', MaterialTags.component("basic_memory_processor"))
                    .define('O', MaterialTags.component("basic_io_processor")).define('C', Items.CRAFTING_TABLE)
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_basic_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("pattern_provider_from_part")),
                    RecipeCategory.MISC, new ItemStackTemplate(item), recipes.ingredient(PROVIDER_PART_TAG)))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<PatternProviderBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("pattern_provider",
                    () -> new BlockEntityType<>(PatternProviderBlockEntity::new, Set.of(PROVIDER.get())));

    /** The provider as a part on a cable's face, working on the block beyond it. */
    public static final RegistryEntry<Item, PartItem> PROVIDER_PART_ITEM = ModContent.CONTENT
            .item("pattern_provider_part", p -> new PartItem(p, () -> ProviderContent.PROVIDER_PART))
            .lang("Pattern Provider Part").tag(PROVIDER_PART_TAG)
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("pattern_provider_part")),
                    RecipeCategory.MISC, new ItemStackTemplate(item), recipes.ingredient(PROVIDER_TAG)))
            .creativeTab().register();

    /** A plate (12 x 12 x 2 px) on the cable's face and a housing (8 x 8 x 2) behind it, as the interface part. */
    public static final PartType PROVIDER_PART = PartTypes
            .register(new PartType(MaxtechStorage.id("pattern_provider"), PROVIDER_PART_ITEM,
                    List.of(new AABB(2, 2, 0, 14, 14, 2), new AABB(4, 4, 2, 12, 12, 4)), PatternProviderPart::new)
                    .tooltip(ProviderItems::appendTooltip));

    public static final String PATTERNS_KEY = key("patterns");
    public static final String BLOCKING_ON_KEY = key("blocking.on");
    public static final String BLOCKING_OFF_KEY = key("blocking.off");
    public static final String BLOCKING_HINT_KEY = key("blocking.hint");
    public static final String LOCKED_KEY = key("locked");
    public static final String IN_FLIGHT_KEY = key("in_flight");
    public static final String TOOLTIP_PATTERNS_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".pattern_provider.patterns";
    public static final String TOOLTIP_NON_BLOCKING_KEY = "tooltip." + MaxtechStorage.MOD_ID
            + ".pattern_provider.non_blocking";

    /** Caption above the lock mode button in the mode tab (B09 item 26). */
    public static final String LOCK_CAPTION_KEY = key("lock.caption");
    /** The mode tab's line, the Jade glance and the label while a lock holds batches: the reason. */
    public static final String LOCKED_NOW_KEY = key("lock.locked");
    /** The lock mode in Jade's details and the item tooltip. */
    public static final String LOCK_MODE_KEY = key("lock.mode");
    /** The blueprint card's kind for providers (block and part) and for the crafter. */
    public static final Identifier SETTINGS_KIND = DeviceSettings.kind("pattern_provider");
    public static final Identifier CRAFTER_SETTINGS_KIND = DeviceSettings.kind("crafter");

    static {
        LockMode.values();
        CraftingDeliveries.listen(ResultLocks.INSTANCE);
        ModContent.CONTENT.translation(LOCK_CAPTION_KEY, "Lock");
        ModContent.CONTENT.translation(LOCKED_NOW_KEY, "Locked: %s");
        ModContent.CONTENT.translation(LOCK_MODE_KEY, "Lock: %s");
        ModContent.CONTENT.translation(DeviceSettings.kindKey(SETTINGS_KIND), "Pattern Provider");
        ModContent.CONTENT.translation(DeviceSettings.kindKey(CRAFTER_SETTINGS_KIND), "Crafter");
        ModContent.CONTENT.translation(PATTERNS_KEY, "Patterns");
        ModContent.CONTENT.translation(BLOCKING_ON_KEY, "Blocking: on");
        ModContent.CONTENT.translation(BLOCKING_OFF_KEY, "Blocking: off");
        ModContent.CONTENT.translation(BLOCKING_HINT_KEY,
                "Blocking holds processing batches while the machine still holds inputs or a batch is running");
        ModContent.CONTENT.translation(LOCKED_KEY, "More slots open with research");
        ModContent.CONTENT.translation(IN_FLIGHT_KEY, "Crafts running: %s");
        ModContent.CONTENT.translation(TOOLTIP_PATTERNS_KEY, "Holds %s patterns");
        ModContent.CONTENT.translation(TOOLTIP_NON_BLOCKING_KEY, "Blocking off");
    }

    private ProviderContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    static DataComponentType<Boolean> nonBlockingType() {
        return ModDataComponents.PATTERN_PROVIDER_NON_BLOCKING.get();
    }

    static DataComponentType<LockMode> lockType() {
        return ModDataComponents.PATTERN_PROVIDER_LOCK.get();
    }

    private static String key(String name) {
        return "gui." + MaxtechStorage.MOD_ID + ".pattern_provider." + name;
    }
}
