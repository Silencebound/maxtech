package dev.silencebound.maxtech.storage.drive;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.content.ConventionTags;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The storage drive: block, item and block entity type. The block state model is the controller's casing with a bay of
 * eight slot recesses in front; Storage's client adds the cells with their lights from
 * {@link DriveBlockEntity#CELL_BANDS}. No loot table: the block drops itself with its cells in code.
 */
public final class DriveContent {
    public static final BlockEntry<DriveBlock, DriveItem> DRIVE = ModContent.CONTENT
            .block("storage_drive", DriveBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(DriveItem::new)
            .model(BlockModel.orientable(MaxtechStorage.id("block/drive/front"),
                    MaxtechStorage.id("block/storage_controller_side"),
                    MaxtechStorage.id("block/storage_controller_side")))
            .lang("Storage Drive").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PIP").pattern("WCW")
                    .pattern("PLP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('I', MaterialTags.component("basic_io_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire")).define('C', ConventionTags.CHESTS)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<DriveBlockEntity>> DRIVE_BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("storage_drive", () -> new BlockEntityType<>(DriveBlockEntity::new, Set.of(DRIVE.get())));

    /** The drive's screen: its cell slots and the priority tab. */
    public static final RegistryEntry<MenuType<?>, MenuType<DriveMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("storage_drive", () -> Platform.get().menus().menuType(DriveMenu::fromNetwork));

    static {
        ModContent.CONTENT.translation(DriveItem.CELLS_KEY, "Holds %s cells");
        ModContent.CONTENT.translation(DriveMenu.HELP_KEY, "Sneak-use empty-handed: last cell out");
        ModContent.CONTENT.translation(DriveMenu.EMPTY_KEY, "Empty into network");
        ModContent.CONTENT.translation(DriveMenu.FILL_KEY, "Fill from network");
        ModContent.CONTENT.translation(DriveMenu.FILL_HINT_KEY, "A partitioned cell takes only what its filter allows");
        ModContent.CONTENT.translation(DriveMenu.RUNNING_KEY, "Running, cell %s%% full. Click to stop");
        ModContent.CONTENT.translation(DriveMenu.NO_CELL_KEY, "No cell in this slot");
        ModContent.CONTENT.translation(DriveMenu.EMPTY_DONE_KEY, "Finished: the cell is empty");
        ModContent.CONTENT.translation(DriveMenu.EMPTY_BLOCKED_KEY, "Stopped: the network has no room for the rest");
        ModContent.CONTENT.translation(DriveMenu.FILL_DONE_KEY, "Finished: the cell is full");
        ModContent.CONTENT.translation(DriveMenu.FILL_BLOCKED_KEY, "Stopped: nothing more in the network fits");
    }

    private DriveContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
