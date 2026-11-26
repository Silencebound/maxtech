package dev.silencebound.maxtech.storage.tool.layer;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The cable layer (M6f area D, decision F8), its components and its texts. */
public final class LayerContent {
    private static final String PREFIX = MaxtechStorage.MOD_ID + ".";
    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);
    public static final String DESCRIPTION_KEY = "tooltip." + PREFIX + "cable_layer";
    public static final String ENERGY_KEY = "tooltip." + PREFIX + "cable_layer.energy";
    public static final String CABLE_KEY = "tooltip." + PREFIX + "cable_layer.cable";
    public static final String START_KEY = "tooltip." + PREFIX + "cable_layer.start";
    public static final String START_SET_KEY = "message." + PREFIX + "cable_layer.start_set";
    public static final String START_CLEARED_KEY = "message." + PREFIX + "cable_layer.start_cleared";
    public static final String CABLE_SET_KEY = "message." + PREFIX + "cable_layer.cable_set";
    public static final String LAID_KEY = "message." + PREFIX + "cable_layer.laid";
    public static final String BLOCKED_KEY = "message." + PREFIX + "cable_layer.blocked";
    public static final String TOO_LONG_KEY = "message." + PREFIX + "cable_layer.too_long";
    public static final String NO_NETWORK_KEY = "message." + PREFIX + "cable_layer.no_network";
    public static final String NO_POWER_KEY = "message." + PREFIX + "cable_layer.no_power";
    public static final String NO_CABLE_KEY = "message." + PREFIX + "cable_layer.no_cable";
    public static final String OTHER_LEVEL_KEY = "message." + PREFIX + "cable_layer.other_level";

    /** FE in a cable layer. Synced for its charge bar and tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> ENERGY = component(
            "layer_energy",
            DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** The start of the next run, set by the first use on a block. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<GlobalPos>> START = component(
            "layer_start", DataComponentType.<GlobalPos>builder().persistent(GlobalPos.CODEC)
                    .networkSynchronized(GlobalPos.STREAM_CODEC));
    /** The storage cable item the layer lays; tier 1 plain when never set. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Item>> CABLE = component("layer_cable",
            DataComponentType.<Item>builder().persistent(BuiltInRegistries.ITEM.byNameCodec())
                    .networkSynchronized(ByteBufCodecs.registry(Registries.ITEM)));
    /** The colour laid cables get; uncoloured when absent. Synced for the tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<DyeColor>> COLOUR = component(
            "layer_colour", DataComponentType.<DyeColor>builder().persistent(DyeColor.CODEC)
                    .networkSynchronized(DyeColor.STREAM_CODEC));

    // A piston pushes the cable out, an I/O processor pulls it from the network; a tier-1 cable shows what it lays.
    public static final RegistryEntry<Item, CableLayerItem> CABLE_LAYER = ModContent.CONTENT
            .item("cable_layer", CableLayerItem::new).model(ItemModel.flat(MaxtechStorage.id("item/cable_layer")))
            .lang("Cable Layer")
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.TOOLS, item).pattern("PCP").pattern("SIS")
                    .pattern("PLP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('C', CableContent.TIER_1_TAG)
                    .define('S', Items.PISTON).define('I', MaterialTags.component("advanced_io_processor"))
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_storage_cable", recipes.has(CableContent.TIER_1_TAG))
                    .save(StorageModules.gate(recipes.output(), StorageModules.WIRELESS)))
            .creativeTab().register();

    static {
        ModContent.CONTENT.translation(DESCRIPTION_KEY,
                "Use on two blocks to lay storage cable between them, taken from the bound network. Sneak-use on a"
                        + " controller to bind, on a storage cable to pick its kind and colour; sneak-use in the air"
                        + " drops the start. Charge in any FE charger.");
        ModContent.CONTENT.translation(ENERGY_KEY, "Energy: %s / %s MU");
        ModContent.CONTENT.translation(CABLE_KEY, "Lays: %s");
        ModContent.CONTENT.translation(START_KEY, "Start: %s, %s, %s");
        ModContent.CONTENT.translation(START_SET_KEY, "Start set at %s, %s, %s; use on the end block");
        ModContent.CONTENT.translation(START_CLEARED_KEY, "Start dropped");
        ModContent.CONTENT.translation(CABLE_SET_KEY, "Now laying %s");
        ModContent.CONTENT.translation(LAID_KEY, "Laid %s cables");
        ModContent.CONTENT.translation(BLOCKED_KEY, "Laid %s cables; stopped at %s, %s, %s");
        ModContent.CONTENT.translation(TOO_LONG_KEY, "That run is %s blocks; the layer lays at most %s");
        ModContent.CONTENT.translation(NO_NETWORK_KEY,
                "No network in reach: bind the layer to a controller and stay in its wireless range");
        ModContent.CONTENT.translation(NO_POWER_KEY, "Laid %s cables; the cable layer is out of power, charge it");
        ModContent.CONTENT.translation(NO_CABLE_KEY, "Laid %s cables; the network has no more %s");
        ModContent.CONTENT.translation(OTHER_LEVEL_KEY, "The start is in another dimension; start again here");
    }

    private LayerContent() {
    }

    private static <T> RegistryEntry<DataComponentType<?>, DataComponentType<T>> component(String name,
            DataComponentType.Builder<T> builder) {
        return COMPONENTS.register(name, builder::build);
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
