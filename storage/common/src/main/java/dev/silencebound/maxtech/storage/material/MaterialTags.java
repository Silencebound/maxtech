package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The item tags Storage's recipes name. Materials use {@code c:} tags (other mods' silicon or copper wire work too);
 * components without a convention folder get a tag of the same name in Storage's namespace, so recipes never name an
 * item directly.
 */
public final class MaterialTags {
    public static final TagKey<Item> ORES = c("ores");
    public static final TagKey<Item> LUMEN_QUARTZ_ORES = c("ores/lumen_quartz");
    public static final TagKey<Block> LUMEN_QUARTZ_ORE_BLOCKS = cBlock("ores/lumen_quartz");
    public static final TagKey<Block> ORE_BLOCKS = cBlock("ores");
    public static final TagKey<Block> ORES_IN_STONE = cBlock("ores_in_ground/stone");
    public static final TagKey<Block> ORES_IN_DEEPSLATE = cBlock("ores_in_ground/deepslate");
    public static final TagKey<Item> ORES_IN_STONE_ITEMS = c("ores_in_ground/stone");
    public static final TagKey<Item> ORES_IN_DEEPSLATE_ITEMS = c("ores_in_ground/deepslate");
    public static final TagKey<Item> GEMS = c("gems");
    public static final TagKey<Item> LUMEN_QUARTZ = c("gems/lumen_quartz");
    public static final TagKey<Item> DUSTS = c("dusts");
    public static final TagKey<Item> LUMEN_QUARTZ_DUST = c("dusts/lumen_quartz");
    /** Silicon dust; Storage's is the kerf left by slicing. */
    public static final TagKey<Item> SILICON_DUST = c("dusts/silicon");
    public static final TagKey<Item> SILICON = c("silicon");
    public static final TagKey<Item> INGOTS = c("ingots");
    public static final TagKey<Item> SIGNAL_ALLOY_INGOT = c("ingots/signal_alloy");
    public static final TagKey<Item> PLATES = c("plates");
    public static final TagKey<Item> SIGNAL_ALLOY_PLATE = c("plates/signal_alloy");
    public static final TagKey<Item> WIRES = c("wires");
    public static final TagKey<Item> COPPER_WIRE = c("wires/copper");
    public static final TagKey<Item> TANTALITE_ORES = c("ores/tantalite");
    public static final TagKey<Block> TANTALITE_ORE_BLOCKS = cBlock("ores/tantalite");
    public static final TagKey<Item> RAW_MATERIALS = c("raw_materials");
    public static final TagKey<Item> RAW_TANTALITE = c("raw_materials/tantalite");
    public static final TagKey<Item> TANTALUM_DUST = c("dusts/tantalum");
    public static final TagKey<Item> NIOBIUM_DUST = c("dusts/niobium");
    public static final TagKey<Item> TANTALUM_INGOT = c("ingots/tantalum");
    public static final TagKey<Item> NIOBIUM_INGOT = c("ingots/niobium");
    public static final TagKey<Item> EMBER_QUARTZ_ORES = c("ores/ember_quartz");
    public static final TagKey<Block> EMBER_QUARTZ_ORE_BLOCKS = cBlock("ores/ember_quartz");
    public static final TagKey<Block> ORES_IN_NETHERRACK = cBlock("ores_in_ground/netherrack");
    public static final TagKey<Item> ORES_IN_NETHERRACK_ITEMS = c("ores_in_ground/netherrack");
    public static final TagKey<Item> EMBER_QUARTZ = c("gems/ember_quartz");
    public static final TagKey<Item> DATA_CRYSTAL = c("gems/data_crystal");
    public static final TagKey<Item> VOID_CRYSTAL = c("gems/void_crystal");

    public static final TagKey<Item> IRON_INGOT = c("ingots/iron");
    public static final TagKey<Item> COPPER_INGOT = c("ingots/copper");
    public static final TagKey<Item> IRON_NUGGET = c("nuggets/iron");
    public static final TagKey<Item> SLIME_BALLS = c("slime_balls");
    public static final TagKey<Item> GLASS_BLOCKS = ConventionTags.GLASS_BLOCKS;
    public static final TagKey<Item> REDSTONE = ConventionTags.REDSTONE_DUSTS;
    public static final TagKey<Item> GLOWSTONE = c("dusts/glowstone");
    public static final TagKey<Item> COALS = ItemTags.COALS;

    private MaterialTags() {
    }

    /** The tag of one of Storage's components, named like the item: {@code maxtech_storage:<name>}. */
    public static TagKey<Item> component(String name) {
        return storage(name);
    }

    private static TagKey<Item> c(String path) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", path));
    }

    private static TagKey<Block> cBlock(String path) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", path));
    }

    private static TagKey<Item> storage(String path) {
        return TagKey.create(Registries.ITEM, MaxtechStorage.id(path));
    }
}
