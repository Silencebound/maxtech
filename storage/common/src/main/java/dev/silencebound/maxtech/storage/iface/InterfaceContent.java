package dev.silencebound.maxtech.storage.iface;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.part.ComponentCopyingRecipe;
import dev.silencebound.maxtech.storage.part.PartItem;
import dev.silencebound.maxtech.storage.part.PartType;
import dev.silencebound.maxtech.storage.part.PartTypes;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import java.util.Set;
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
 * The interface (PRD 7.2, Devices): the full block, its part form, the block entity type, the GUI's texts and the
 * recipes, which convert the two forms 1:1 with their settings and stock. The menu type sits in
 * {@code registry.ModMenus}.
 */
public final class InterfaceContent {
    /** Recipes name the two forms by these tags, never the items. */
    public static final TagKey<Item> INTERFACE_TAG = MaterialTags.component("storage_interface");
    public static final TagKey<Item> INTERFACE_PART_TAG = MaterialTags.component("storage_interface_part");

    public static final BlockEntry<InterfaceBlock, InterfaceItems.InterfaceBlockItem> INTERFACE = ModContent.CONTENT
            .block("storage_interface", InterfaceBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(2.0F, 6.0F).sound(SoundType.METAL))
            .item(InterfaceItems.InterfaceBlockItem::new)
            .model(BlockModel.cubeAll(MaxtechStorage.id("block/interface/face"))).lang("Storage Interface")
            .loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE).itemTag(INTERFACE_TAG)
            // A chest for the stock, a hopper for the moving part, I/O processors to drive both sides.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PHP").pattern("OCO")
                    .pattern("PWP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('H', Items.HOPPER)
                    .define('C', ConventionTags.CHESTS).define('O', MaterialTags.component("basic_io_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire"))
                    .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            // The part back into the block form, 1:1, with its settings and stock.
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("storage_interface_from_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(INTERFACE_PART_TAG)))
            .creativeTab().register();

    /** The interface as a part on a cable's face; Storage's model provider draws its item. */
    public static final RegistryEntry<Item, PartItem> INTERFACE_PART_ITEM = ModContent.CONTENT
            .item("storage_interface_part", p -> new PartItem(p, () -> InterfaceContent.INTERFACE_PART))
            .lang("Storage Interface Part").tag(INTERFACE_PART_TAG)
            .recipe((recipes, item) -> ComponentCopyingRecipe.save(recipes.output(),
                    ResourceKey.create(Registries.RECIPE, MaxtechStorage.id("storage_interface_part")),
                    RecipeCategory.REDSTONE, new ItemStackTemplate(item), recipes.ingredient(INTERFACE_TAG)))
            .creativeTab().register();

    /** A plate (12 x 12 x 2 px) on the cable's face and a housing (8 x 8 x 2) behind it. */
    public static final PartType INTERFACE_PART = PartTypes
            .register(new PartType(MaxtechStorage.id("storage_interface"), INTERFACE_PART_ITEM,
                    List.of(new AABB(2, 2, 0, 14, 14, 2), new AABB(4, 4, 2, 12, 12, 4)), InterfacePart::new)
                    .tooltip(InterfaceItems::appendTooltip));

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<InterfaceBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("storage_interface",
                    () -> new BlockEntityType<>(InterfaceBlockEntity::new, Set.of(INTERFACE.get())));

    public static final String STOCK_KEY = "gui." + MaxtechStorage.MOD_ID + ".interface.stock";
    public static final String FLUIDS_KEY = "gui." + MaxtechStorage.MOD_ID + ".interface.fluids";
    public static final String HINT_KEY = "gui." + MaxtechStorage.MOD_ID + ".interface.hint";
    public static final String FLUID_AMOUNT_KEY = "gui." + MaxtechStorage.MOD_ID + ".interface.fluid_amount";
    public static final String TOOLTIP_SLOTS_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".interface.slots";
    public static final String TOOLTIP_FLUID_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".interface.fluid";
    public static final String TOOLTIP_CARD_KEY = "tooltip." + MaxtechStorage.MOD_ID + ".interface.card";
    public static final String CARD_KEY = "gui." + MaxtechStorage.MOD_ID + ".interface.card";

    static {
        ModContent.CONTENT.translation(STOCK_KEY, "Stock");
        ModContent.CONTENT.translation(FLUIDS_KEY, "Fluid stock");
        ModContent.CONTENT.translation(HINT_KEY,
                "Click with a stack to keep that many, right-click to add one, click empty-handed to clear");
        ModContent.CONTENT.translation(FLUID_AMOUNT_KEY, "%s / %s mB");
        ModContent.CONTENT.translation(TOOLTIP_SLOTS_KEY, "Stock slots set: %s");
        ModContent.CONTENT.translation(TOOLTIP_FLUID_KEY, "Holds %s mB of fluid");
        ModContent.CONTENT.translation(TOOLTIP_CARD_KEY, "Card: %s");
        ModContent.CONTENT.translation(CARD_KEY, "Card");
        // Empty unless a card joins it (the crafting card); written so the tag always exists.
        ModContent.CONTENT.itemTag(InterfaceCrafting.CARDS);
        InterfaceResearchWaits.init();
    }

    private InterfaceContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
