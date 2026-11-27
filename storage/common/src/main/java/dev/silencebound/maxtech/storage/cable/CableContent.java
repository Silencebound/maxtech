package dev.silencebound.maxtech.storage.cable;

import dev.silencebound.maxtech.lib.api.conduit.ConduitLook;
import dev.silencebound.maxtech.lib.conduit.ConduitItem;
import dev.silencebound.maxtech.lib.content.ConduitBuilder;
import dev.silencebound.maxtech.lib.content.ConduitEntry;
import dev.silencebound.maxtech.lib.content.ContentRecipe;
import dev.silencebound.maxtech.lib.content.ItemModel;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.network.StorageNetworks;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * The storage cables (PRD 7.2, Network): four channel tiers (M6a tiers 1 and 2, M6d tiers 3 and 4), all
 * {@link #THICKNESS} wide, each in the five {@link CableLook}s (glass retired: registered, not crafted or listed), each
 * in 16 colours plus uncoloured through Lib's conduit dyeing. A tier shows as the brightness of the one lane inside the
 * cable ({@link CableTierMarks}), not as width, so a tier upgrade never changes a cable's hit shape. Storage's client
 * draws them with its own cable model; the item is a 3D piece of straight cable whose model the neoforge datagen builds
 * from the same geometry ({@code CableItemModels}). Every look of a tier carries the tier's tag, so recipes take any of
 * them.
 */
public final class CableContent {
    /**
     * The lane's base cyan, a shade darker and more saturated per tier; a dye pulls it towards the dye, and
     * {@link CableTierMarks#lineRgb} sets its brightness per tier.
     */
    public static final int TIER_1_COLOUR = 0x5AA5AD;
    public static final int TIER_2_COLOUR = 0x3F8794;
    public static final int TIER_3_COLOUR = 0x2F7384;
    public static final int TIER_4_COLOUR = 0x245E70;
    /** Every tier's width: 4 px, as a fraction of a block. */
    public static final float THICKNESS = 4.0F / 16.0F;

    /** Recipes name cables by these tags, never the items. */
    public static final TagKey<Item> TIER_1_TAG = MaterialTags.component("storage_cable_tier_1");
    public static final TagKey<Item> TIER_2_TAG = MaterialTags.component("storage_cable_tier_2");
    public static final TagKey<Item> TIER_3_TAG = MaterialTags.component("storage_cable_tier_3");
    public static final TagKey<Item> TIER_4_TAG = MaterialTags.component("storage_cable_tier_4");

    // Glass fibre carries the signal, insulated wire around it; lumen quartz dust tunes it.
    public static final ConduitEntry<StorageCableConduitType> STORAGE_CABLE_TIER_1 = plain(1, TIER_1_COLOUR, TIER_1_TAG,
            (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item, 8).pattern("WWW").pattern("FDF")
                    .pattern("WWW").define('W', MaterialTags.component("insulated_copper_wire"))
                    .define('F', MaterialTags.component("glass_fibre")).define('D', MaterialTags.LUMEN_QUARTZ_DUST)
                    .unlockedBy("has_glass_fibre", recipes.has(MaterialTags.component("glass_fibre")))
                    .save(recipes.output()));
    // Four tier-1 cables bundled round a signal alloy plate and a signal processor.
    public static final ConduitEntry<StorageCableConduitType> STORAGE_CABLE_TIER_2 = plain(2, TIER_2_COLOUR, TIER_2_TAG,
            bundle(TIER_1_TAG, MaterialTags.SIGNAL_ALLOY_PLATE, MaterialTags.component("basic_signal_processor")));
    // M6d's ember quartz and elite processors are not in yet; tantalum and the advanced signal processor stand in.
    public static final ConduitEntry<StorageCableConduitType> STORAGE_CABLE_TIER_3 = plain(3, TIER_3_COLOUR, TIER_3_TAG,
            bundle(TIER_2_TAG, MaterialTags.TANTALUM_INGOT, MaterialTags.component("advanced_signal_processor")));
    public static final ConduitEntry<StorageCableConduitType> STORAGE_CABLE_TIER_4 = plain(4, TIER_4_COLOUR, TIER_4_TAG,
            bundle(TIER_3_TAG, MaterialTags.NIOBIUM_INGOT, MaterialTags.component("advanced_io_processor")));

    public static final List<ConduitEntry<StorageCableConduitType>> COVERED = List.of(
            covered(1, TIER_1_COLOUR, TIER_1_TAG), covered(2, TIER_2_COLOUR, TIER_2_TAG),
            covered(3, TIER_3_COLOUR, TIER_3_TAG), covered(4, TIER_4_COLOUR, TIER_4_TAG));
    public static final List<ConduitEntry<StorageCableConduitType>> SMART = List.of(smart(1, TIER_1_COLOUR, TIER_1_TAG),
            smart(2, TIER_2_COLOUR, TIER_2_TAG), smart(3, TIER_3_COLOUR, TIER_3_TAG),
            smart(4, TIER_4_COLOUR, TIER_4_TAG));

    public static final List<ConduitEntry<StorageCableConduitType>> GLASS = List.of(glass(1, TIER_1_COLOUR, TIER_1_TAG),
            glass(2, TIER_2_COLOUR, TIER_2_TAG), glass(3, TIER_3_COLOUR, TIER_3_TAG),
            glass(4, TIER_4_COLOUR, TIER_4_TAG));
    public static final List<ConduitEntry<StorageCableConduitType>> FIBER = List.of(fiber(1, TIER_1_COLOUR, TIER_1_TAG),
            fiber(2, TIER_2_COLOUR, TIER_2_TAG), fiber(3, TIER_3_COLOUR, TIER_3_TAG),
            fiber(4, TIER_4_COLOUR, TIER_4_TAG));

    private CableContent() {
    }

    /** The cable of {@code look} and {@code tier} (1 to 4). */
    public static ConduitEntry<StorageCableConduitType> cable(CableLook look, int tier) {
        return switch (look) {
            case PLAIN -> switch (tier) {
                case 1 -> STORAGE_CABLE_TIER_1;
                case 2 -> STORAGE_CABLE_TIER_2;
                case 3 -> STORAGE_CABLE_TIER_3;
                default -> STORAGE_CABLE_TIER_4;
            };
            case COVERED -> COVERED.get(tier - 1);
            case SMART -> SMART.get(tier - 1);
            case GLASS -> GLASS.get(tier - 1);
            case FIBER -> FIBER.get(tier - 1);
        };
    }

    /** Four cables of the tier below round two {@code plate}s and a {@code processor} give four. */
    private static ContentRecipe<? super ConduitItem> bundle(TagKey<Item> below, TagKey<Item> plate,
            TagKey<Item> processor) {
        return (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item, 4).pattern("CPC").pattern("CSC")
                .pattern("CPC").define('C', below).define('P', plate).define('S', processor)
                .unlockedBy("has_storage_cable", recipes.has(below)).save(recipes.output());
    }

    private static ConduitEntry<StorageCableConduitType> plain(int tier, int colour, TagKey<Item> tag,
            ContentRecipe<? super ConduitItem> recipe) {
        return register(CableLook.PLAIN, tier, colour, tag, recipe);
    }

    // Eight cables round a wool block: the sleeve.
    private static ConduitEntry<StorageCableConduitType> covered(int tier, int colour, TagKey<Item> tag) {
        return register(CableLook.COVERED, tier, colour, tag, ring(tag, ItemTags.WOOL));
    }

    // Eight cables round glowstone dust: the load lights.
    private static ConduitEntry<StorageCableConduitType> smart(int tier, int colour, TagKey<Item> tag) {
        return register(CableLook.SMART, tier, colour, tag, ring(tag, MaterialTags.GLOWSTONE));
    }

    // Retired look: no recipe and not listed, kept so placed and stored glass cables keep working.
    private static ConduitEntry<StorageCableConduitType> glass(int tier, int colour, TagKey<Item> tag) {
        return register(CableLook.GLASS, tier, colour, tag, null);
    }

    // Eight cables round glass fibre: the line carries pulses.
    private static ConduitEntry<StorageCableConduitType> fiber(int tier, int colour, TagKey<Item> tag) {
        return register(CableLook.FIBER, tier, colour, tag, ring(tag, MaterialTags.component("glass_fibre")));
    }

    private static ContentRecipe<? super ConduitItem> ring(TagKey<Item> cables, TagKey<Item> centre) {
        return (recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item, 8).pattern("CCC").pattern("CXC")
                .pattern("CCC").define('C', cables).define('X', centre)
                .unlockedBy("has_storage_cable", recipes.has(cables)).save(recipes.output());
    }

    /** Registers a cable; one without a {@code recipe} (a retired look) is left out of the creative tab too. */
    private static ConduitEntry<StorageCableConduitType> register(CableLook look, int tier, int colour,
            TagKey<Item> tag, @Nullable ContentRecipe<? super ConduitItem> recipe) {
        String name = look.name(tier);
        ConduitBuilder<StorageCableConduitType> builder = ModContent.CONTENT
                .conduit(name,
                        item -> new StorageCableConduitType(StorageNetworks.ID, tier, look, colour, THICKNESS, item))
                .model(ItemModel.existing(MaxtechStorage.id("item/" + name))).look(ConduitLook.wire())
                .block(StorageCableBlock::new).lang(look.english(tier)).tag(tag);
        if (recipe != null) {
            builder.recipe(recipe).creativeTab();
        }
        return builder.register();
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
        StorageNetworks.init();
    }
}
