package dev.silencebound.maxtech.storage.bridge;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.port.PortTier;
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
 * The network bridge (M7b) in the three port tiers: blocks, items, the shared block entity type, recipes and info
 * lines. The model is a column whose ends (front and back) carry the bridge face, turned to the block's facing; plain
 * placeholder art.
 */
public final class BridgeContent {
    public static final BlockEntry<BridgeBlock, BlockItem> BRIDGE_TIER_1 = block(PortTier.TIER_1,
            MaterialTags.component("insulated_copper_wire"), MaterialTags.component("basic_signal_processor"),
            MaterialTags.component("basic_io_processor"));
    public static final BlockEntry<BridgeBlock, BlockItem> BRIDGE_TIER_2 = block(PortTier.TIER_2,
            MaterialTags.component("glass_fibre"), MaterialTags.component("basic_signal_processor"),
            MaterialTags.component("basic_io_processor"));
    public static final BlockEntry<BridgeBlock, BlockItem> BRIDGE_TIER_3 = block(PortTier.TIER_3,
            MaterialTags.component("superconductor_wire"), MaterialTags.component("advanced_signal_processor"),
            MaterialTags.component("advanced_io_processor"));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<BridgeBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("network_bridge", () -> new BlockEntityType<>(BridgeBlockEntity::new,
                    Set.of(BRIDGE_TIER_1.get(), BRIDGE_TIER_2.get(), BRIDGE_TIER_3.get())));

    public static final String INFO_NONE = "info." + MaxtechStorage.MOD_ID + ".bridge.none";
    public static final String INFO_MOUNTED = "info." + MaxtechStorage.MOD_ID + ".bridge.mounted";
    public static final String INFO_STANDBY = "info." + MaxtechStorage.MOD_ID + ".bridge.standby";
    public static final String INFO_RATE = "info." + MaxtechStorage.MOD_ID + ".bridge.rate";
    public static final String INFO_HELD = "info." + MaxtechStorage.MOD_ID + ".bridge.held";

    static {
        ModContent.CONTENT.translation(INFO_NONE, "No AE2 or Refined Storage network in front");
        ModContent.CONTENT.translation(INFO_MOUNTED, "Bridged to: %s");
        ModContent.CONTENT.translation(INFO_STANDBY, "Standby: another bridge of this network links %s");
        ModContent.CONTENT.translation(INFO_RATE, "Rate: %s items/s, %s mB/s");
        ModContent.CONTENT.translation(INFO_HELD, "Held for the other network: %s");
    }

    private BridgeContent() {
    }

    /**
     * The bridge of {@code tier}: signal alloy plates around two signal processors (one per network), an I/O processor
     * and the tier's link material, from scratch like the ports' recipes.
     */
    private static BlockEntry<BridgeBlock, BlockItem> block(PortTier tier, TagKey<Item> link, TagKey<Item> signal,
            TagKey<Item> io) {
        String name = "network_bridge_tier_" + tier.number();
        return ModContent.CONTENT.block(name, p -> new BridgeBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
                .simpleItem()
                .model(BlockModel.facingColumn(MaxtechStorage.id("block/port/side"),
                        MaxtechStorage.id("block/bridge/face_tier_" + tier.number())))
                .lang("Network Bridge (Tier " + tier.number() + ")").loot(BlockLoot.SELF)
                .tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PSP").pattern("LOL")
                        .pattern("PSP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('S', signal)
                        .define('L', link).define('O', io).unlockedBy("has_signal_processor", recipes.has(signal))
                        .save(recipes.output()))
                .creativeTab().register();
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
