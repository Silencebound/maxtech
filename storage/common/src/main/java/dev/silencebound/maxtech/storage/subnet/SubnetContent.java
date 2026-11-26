package dev.silencebound.maxtech.storage.subnet;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The subnet controller (M6d): block, item, block entity type and lang lines. The model is a column with the plain
 * casing on its sides and the subnet face (MTX final pass) on its ends, turned so one end faces the parent network. No
 * loot table: the block drops itself with its settings in code.
 */
public final class SubnetContent {
    public static final BlockEntry<SubnetBlock, SubnetItem> SUBNET = ModContent.CONTENT
            .block("subnet_controller", SubnetBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(SubnetItem::new)
            .model(BlockModel.facingColumn(MaxtechStorage.id("block/storage_controller_side"),
                    MaxtechStorage.id("block/subnet_controller_end")))
            .lang("Subnet Controller").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            // Logic and I/O processors split the network; the oscillator times its own routes.
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.REDSTONE, item).pattern("PLP").pattern("OCO")
                    .pattern("PIP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('L', MaterialTags.component("advanced_logic_processor"))
                    .define('I', MaterialTags.component("advanced_io_processor"))
                    .define('O', MaterialTags.component("oscillator"))
                    .define('C', MaterialTags.component("tantalum_capacitor"))
                    .unlockedBy("has_logic_processor", recipes.has(MaterialTags.component("advanced_logic_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<SubnetBlockEntity>> SUBNET_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("subnet_controller", () -> new BlockEntityType<>(SubnetBlockEntity::new, Set.of(SUBNET.get())));

    static {
        ModContent.CONTENT.translation(SubnetTexts.INFO_MODE, "Subnet: %s, priority %s, filter %s");
        ModContent.CONTENT.translation(SubnetTexts.INFO_PARENT, "Parent network: %s devices");
        ModContent.CONTENT.translation(SubnetTexts.INFO_NO_PARENT, "No parent network in front");
        ModContent.CONTENT.translation(SubnetTexts.INFO_SAME_NETWORK,
                "The subnet touches its parent elsewhere: one network, no boundary");
        ModContent.CONTENT.translation(SubnetTexts.TOOLTIP_MODE, "%s, priority %s, %s MU");
        ModContent.CONTENT.translation(SubnetTexts.GUI_MODE, "Mode: %s");
        ModContent.CONTENT.translation(SubnetTexts.GUI_FILTER, "Filter (empty: everything)");
        ModContent.CONTENT.translation(SubnetMode.MERGED.translationKey(), "Merged");
        ModContent.CONTENT.translation(SubnetMode.SHARES_UP.translationKey(), "Shares up");
        ModContent.CONTENT.translation(SubnetMode.CLIENT.translationKey(), "Client");
        ModContent.CONTENT.translation(SubnetMode.ISOLATED.translationKey(), "Isolated");
        ModContent.CONTENT.translation(SubnetTexts.help(SubnetMode.MERGED), "Both sides see and store in each other");
        ModContent.CONTENT.translation(SubnetTexts.help(SubnetMode.SHARES_UP), "The parent sees and takes from this");
        ModContent.CONTENT.translation(SubnetTexts.help(SubnetMode.CLIENT), "This pulls from the parent");
        ModContent.CONTENT.translation(SubnetTexts.help(SubnetMode.ISOLATED), "Neither side sees the other");
    }

    private SubnetContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
