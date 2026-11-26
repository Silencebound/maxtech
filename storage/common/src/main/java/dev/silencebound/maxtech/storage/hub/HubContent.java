package dev.silencebound.maxtech.storage.hub;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The three hubs (M7N item 2, PRD 7.2 Channels with hubs): blocks, items, one block entity type, lang lines and
 * recipes. Each tier is four cables of its tier round that tier's processor: tier 1 a basic logic processor, so it is
 * craftable from the start; tiers 2 and 3 the signal processors of the tier-2 and tier-3 cable recipes, so they open
 * with the same research as those cables (family masks, advanced doping).
 */
public final class HubContent {
    public static final BlockEntry<HubBlock, BlockItem> TIER_1 = hub(HubTier.TIER_1, CableContent.TIER_1_TAG,
            "basic_logic_processor");
    public static final BlockEntry<HubBlock, BlockItem> TIER_2 = hub(HubTier.TIER_2, CableContent.TIER_2_TAG,
            "basic_signal_processor");
    public static final BlockEntry<HubBlock, BlockItem> TIER_3 = hub(HubTier.TIER_3, CableContent.TIER_3_TAG,
            "advanced_signal_processor");

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<HubBlockEntity>> BLOCK_ENTITY = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE).register("storage_hub",
                    () -> new BlockEntityType<>(HubBlockEntity::new, Set.of(TIER_1.get(), TIER_2.get(), TIER_3.get())));

    private HubContent() {
    }

    /** The hub of {@code tier}. */
    public static BlockEntry<HubBlock, BlockItem> hub(HubTier tier) {
        return switch (tier) {
            case TIER_1 -> TIER_1;
            case TIER_2 -> TIER_2;
            case TIER_3 -> TIER_3;
        };
    }

    private static BlockEntry<HubBlock, BlockItem> hub(HubTier tier, TagKey<Item> cables, String processor) {
        String name = "storage_hub_tier_" + tier.level();
        TagKey<Item> processorTag = MaterialTags.component(processor);
        return ModContent.CONTENT.block(name, p -> new HubBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/hub/tier_" + tier.level())))
                .lang("Storage Hub (Tier " + tier.level() + ")").loot(BlockLoot.SELF)
                .tag(BlockTags.MINEABLE_WITH_PICKAXE)
                // Four cables of the tier meet at the processor that shares their channels out.
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern(" C ").pattern("CPC")
                        .pattern(" C ").define('C', cables).define('P', processorTag)
                        .unlockedBy("has_storage_cable", recipes.has(cables)).save(recipes.output()))
                .creativeTab().register();
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
