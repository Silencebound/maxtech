package dev.silencebound.maxtech.storage.togglebus;

import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cable.CableContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * The toggle bus (B09 item 19, AE2's toggle bus and RS's relay): a cable part that links or cuts the cable's line on
 * its face by redstone, and the inverted one that links without a signal. Both are parts only, with no block form, and
 * both use no channel. Their items are drawn by Storage's part item models, their look on a cable by the cable model.
 */
public final class ToggleBusContent {
    public static final TagKey<Item> TOGGLE_BUS_TAG = MaterialTags.component("toggle_bus");
    public static final TagKey<Item> INVERTED_TAG = MaterialTags.component("inverted_toggle_bus");
    private static final TagKey<Item> REDSTONE_DUSTS = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("c", "dusts/redstone"));

    /** "Linked" or "Open" at a glance, with the switch icon. */
    static final String LINKED_KEY = "info.maxtech_storage.toggle_bus.linked";
    static final String OPEN_KEY = "info.maxtech_storage.toggle_bus.open";
    static final String NORMAL_KEY = "info.maxtech_storage.toggle_bus.normal";
    static final String INVERTED_KEY = "info.maxtech_storage.toggle_bus.inverted";
    static final String WAITING_KEY = "info.maxtech_storage.toggle_bus.waiting";

    // A lever switches the line, redstone carries the signal, a tier-1 cable is the piece of line it sits in.
    public static final RegistryEntry<Item, PartItem> TOGGLE_BUS_ITEM = ModContent.CONTENT
            .item("toggle_bus", p -> new PartItem(p, () -> ToggleBusContent.TOGGLE_BUS)).lang("Toggle Bus")
            .tag(TOGGLE_BUS_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.REDSTONE, item)
                    .requires(CableContent.TIER_1_TAG).requires(REDSTONE_DUSTS).requires(Items.LEVER)
                    .unlockedBy("has_storage_cable", recipes.has(CableContent.TIER_1_TAG)).save(recipes.output()))
            .creativeTab().register();

    // A redstone torch inverts the signal, as it does in vanilla.
    public static final RegistryEntry<Item, PartItem> INVERTED_ITEM = ModContent.CONTENT
            .item("inverted_toggle_bus", p -> new PartItem(p, () -> ToggleBusContent.INVERTED))
            .lang("Inverted Toggle Bus").tag(INVERTED_TAG)
            .recipe((recipes, item) -> recipes.shapeless(RecipeCategory.REDSTONE, item).requires(TOGGLE_BUS_TAG)
                    .requires(Items.REDSTONE_TORCH).unlockedBy("has_toggle_bus", recipes.has(TOGGLE_BUS_TAG))
                    .save(recipes.output()))
            .creativeTab().register();

    /**
     * A 6 x 6 px collar around the cable from the block face 4 px in; the neighbouring cable's arm meets it at the
     * block face, so the line looks unbroken with the bus in it.
     */
    private static final List<AABB> COLLAR = List.of(new AABB(5, 5, 0, 11, 11, 4));

    public static final PartType TOGGLE_BUS = PartTypes.register(new PartType(MaxtechStorage.id("toggle_bus"),
            TOGGLE_BUS_ITEM, COLLAR, (type, host, face) -> new ToggleBusPart(type, host, face, false)).inLine());
    public static final PartType INVERTED = PartTypes.register(new PartType(MaxtechStorage.id("inverted_toggle_bus"),
            INVERTED_ITEM, COLLAR, (type, host, face) -> new ToggleBusPart(type, host, face, true)).inLine());

    static {
        ModContent.CONTENT.translation(LINKED_KEY, "Linked");
        ModContent.CONTENT.translation(OPEN_KEY, "Open");
        ModContent.CONTENT.translation(NORMAL_KEY, "Links while powered");
        ModContent.CONTENT.translation(INVERTED_KEY, "Inverted: links while unpowered");
        ModContent.CONTENT.translation(WAITING_KEY, "Signal changed; switches in %s ticks");
    }

    private ToggleBusContent() {
    }

    /** Loads the class during mod construction so the entries above are queued. */
    public static void init() {
        ToggleBusWaits.init();
    }
}
