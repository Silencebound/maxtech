package dev.silencebound.maxtech.storage.signal;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.terminal.TerminalContent;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * Signal content (M6d, storage/PRD.md 7.2): the three antenna tiers, their block entity type, the wireless terminal,
 * and the texts of pairing and wireless use. The antenna mount and wireless access mount are controller expansions in
 * {@code controller.module.ExpansionContent}. Loaded after the P2P links, whose linking tool pairs antennas.
 */
public final class SignalContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    public static final String UNPAIRED_KEY = "info." + PREFIX + "antenna.unpaired";
    public static final String PAIRED_KEY = "info." + PREFIX + "antenna.paired";
    public static final String PAIRED_MOUNT_KEY = "info." + PREFIX + "antenna.paired_mount";
    public static final String DENIED_KEY = "message." + PREFIX + "antenna.denied";
    public static final String NEEDS_RESEARCH_KEY = "message." + PREFIX + "signal.needs_research";
    public static final String PICKED_KEY = "message." + PREFIX + "antenna.picked";
    public static final String CLEARED_KEY = "message." + PREFIX + "antenna.cleared";
    public static final String SAME_KEY = "message." + PREFIX + "antenna.same";
    public static final String GONE_KEY = "message." + PREFIX + "antenna.gone";
    public static final String TWO_MOUNTS_KEY = "message." + PREFIX + "antenna.two_mounts";
    public static final String OUT_OF_RANGE_KEY = "message." + PREFIX + "antenna.out_of_range";
    public static final String PAIRED_MESSAGE_KEY = "message." + PREFIX + "antenna.paired";
    public static final String WIRELESS_DISABLED_KEY = "message." + PREFIX + "wireless.disabled";
    public static final String WIRELESS_UNBOUND_KEY = "message." + PREFIX + "wireless.unbound";
    public static final String WIRELESS_DIMENSION_KEY = "message." + PREFIX + "wireless.other_dimension";
    public static final String WIRELESS_NO_NETWORK_KEY = "message." + PREFIX + "wireless.no_network";
    public static final String WIRELESS_OUT_OF_RANGE_KEY = "message." + PREFIX + "wireless.out_of_range";
    public static final String WIRELESS_BOUND_KEY = "message." + PREFIX + "wireless.bound";
    public static final String WIRELESS_TOOLTIP_KEY = "tooltip." + PREFIX + "wireless.bound";
    public static final String WIRELESS_TOOLTIP_UNBOUND_KEY = "tooltip." + PREFIX + "wireless.unbound";
    public static final String WIRELESS_RANGE_KEY = "info." + PREFIX + "controller.wireless_range";
    public static final String WIRELESS_NO_POWER_KEY = "message." + PREFIX + "wireless.no_power";
    public static final String WIRELESS_ENERGY_TOOLTIP_KEY = "tooltip." + PREFIX + "wireless.energy";

    /** Recipes name antennas by these tags, never the items. */
    public static final TagKey<Item> ANTENNA_TIER_1_TAG = MaterialTags.component("antenna_tier_1");
    public static final TagKey<Item> ANTENNA_TIER_2_TAG = MaterialTags.component("antenna_tier_2");

    // A lightning rod as the mast, an oscillator for the carrier, a signal processor to route over it.
    public static final BlockEntry<AntennaBlock, AntennaItem> ANTENNA_TIER_1 = antenna(1, "Antenna",
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PRP").pattern("OSO")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('R', Items.LIGHTNING_ROD)
                    .define('O', MaterialTags.component("oscillator"))
                    .define('S', MaterialTags.component("basic_signal_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_oscillator", recipes.has(MaterialTags.component("oscillator")))
                    .save(recipes.output()));
    // The tier below, with an advanced signal processor and superconductor wire for the longer link.
    public static final BlockEntry<AntennaBlock, AntennaItem> ANTENNA_TIER_2 = antenna(2, "Antenna (Tier 2)",
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PSP").pattern("WAW")
                    .pattern("PSP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('A', ANTENNA_TIER_1_TAG)
                    .define('S', MaterialTags.component("advanced_signal_processor"))
                    .define('W', MaterialTags.component("superconductor_wire"))
                    .unlockedBy("has_antenna", recipes.has(ANTENNA_TIER_1_TAG)).save(recipes.output()));
    // The tier below, with elite signal processors and data crystal to reach across the dimension.
    public static final BlockEntry<AntennaBlock, AntennaItem> ANTENNA_TIER_3 = antenna(3, "Antenna (Tier 3)",
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PSP").pattern("CAC")
                    .pattern("PSP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('A', ANTENNA_TIER_2_TAG)
                    .define('S', MaterialTags.component("elite_signal_processor"))
                    .define('C', MaterialTags.DATA_CRYSTAL).unlockedBy("has_antenna", recipes.has(ANTENNA_TIER_2_TAG))
                    .save(recipes.output()));

    /** The antenna tiers, tier 1 first. */
    public static final List<BlockEntry<AntennaBlock, AntennaItem>> ANTENNAS = List.of(ANTENNA_TIER_1, ANTENNA_TIER_2,
            ANTENNA_TIER_3);

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<AntennaBlockEntity>> ANTENNA_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("antenna", () -> new BlockEntityType<>(AntennaBlockEntity::new,
                    Set.of(ANTENNA_TIER_1.get(), ANTENNA_TIER_2.get(), ANTENNA_TIER_3.get())));

    // A terminal made portable: an advanced I/O processor for the link and an oscillator for the carrier.
    public static final RegistryEntry<Item, WirelessTerminalItem> WIRELESS_TERMINAL = ModContent.CONTENT
            .item("wireless_terminal", WirelessTerminalItem::new)
            .model(ItemModel.flat(MaxtechStorage.id("item/wireless_terminal"))).lang("Wireless Terminal")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("POP").pattern("PTP")
                    .pattern("PIP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('O', MaterialTags.component("oscillator")).define('T', TerminalContent.TERMINAL_TAG)
                    .define('I', MaterialTags.component("advanced_io_processor"))
                    .unlockedBy("has_terminal", recipes.has(TerminalContent.TERMINAL_TAG))
                    .save(StorageModules.gate(recipes.output(), StorageModules.WIRELESS)))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(UNPAIRED_KEY,
                "Not paired; use the linking tool here, then on the antenna or mount to pair with");
        ModContent.CONTENT.translation(PAIRED_KEY, "Paired with the antenna at %s %s %s");
        ModContent.CONTENT.translation(PAIRED_MOUNT_KEY, "Paired with the antenna mount at %s %s %s");
        ModContent.CONTENT.translation(DENIED_KEY, "You need full access to pair this");
        ModContent.CONTENT.translation(NEEDS_RESEARCH_KEY, "Your team needs the research %s for this");
        ModContent.CONTENT.translation(PICKED_KEY,
                "Picked %s %s %s; use the tool on the antenna or mount to pair it with");
        ModContent.CONTENT.translation(CLEARED_KEY, "Pick dropped");
        ModContent.CONTENT.translation(SAME_KEY, "An antenna cannot pair with itself");
        ModContent.CONTENT.translation(GONE_KEY, "The picked antenna is gone or not loaded");
        ModContent.CONTENT.translation(TWO_MOUNTS_KEY, "An antenna mount pairs with an antenna, not another mount");
        ModContent.CONTENT.translation(OUT_OF_RANGE_KEY, "Too far apart for the lower antenna's tier");
        ModContent.CONTENT.translation(PAIRED_MESSAGE_KEY, "Paired %s %s %s with %s %s %s");
        ModContent.CONTENT.translation(WIRELESS_DISABLED_KEY, "Wireless terminals are turned off on this server");
        ModContent.CONTENT.translation(WIRELESS_UNBOUND_KEY,
                "Not bound; sneak-use the wireless terminal on a storage controller");
        ModContent.CONTENT.translation(WIRELESS_DIMENSION_KEY,
                "The bound network is in another dimension and has no dimension bridge");
        ModContent.CONTENT.translation(WIRELESS_NO_NETWORK_KEY,
                "The bound controller is gone, unloaded or in no network");
        ModContent.CONTENT.translation(WIRELESS_OUT_OF_RANGE_KEY, "Out of the network's wireless range");
        ModContent.CONTENT.translation(WIRELESS_BOUND_KEY, "Bound to the storage controller at %s %s %s");
        ModContent.CONTENT.translation(WIRELESS_TOOLTIP_KEY, "Bound to the controller at %s %s %s in %s");
        ModContent.CONTENT.translation(WIRELESS_TOOLTIP_UNBOUND_KEY, "Not bound to a network");
        ModContent.CONTENT.translation(WIRELESS_RANGE_KEY, "Wireless range: %s blocks");
        ModContent.CONTENT.translation(WIRELESS_NO_POWER_KEY, "The wireless terminal is out of power; charge it");
        ModContent.CONTENT.translation(WIRELESS_ENERGY_TOOLTIP_KEY, "Energy: %s / %s MU");
    }

    private SignalContent() {
    }

    private static BlockEntry<AntennaBlock, AntennaItem> antenna(int tier, String english,
            ContentRecipe<? super AntennaItem> recipe) {
        return ModContent.CONTENT.block("antenna_tier_" + tier, p -> new AntennaBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .item((block, p) -> new AntennaItem(block, p))
                .model(BlockModel.cubeAll(MaxtechStorage.id("block/antenna/tier_" + tier))).lang(english)
                .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .itemTag(MaterialTags.component("antenna_tier_" + tier)).recipe(recipe).creativeTab().register();
    }

    /**
     * Loads the class during mod construction so the entries above are queued before registration runs, and registers
     * the pick-from-network payload.
     */
    public static void init() {
        NetworkPick.register();
    }
}
