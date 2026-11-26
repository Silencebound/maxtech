package dev.silencebound.maxtech.storage.ioport;

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
 * The IO port (B09 item 11): block, item, block entity type and menu. The model is the drive's casing with the IO
 * port's front. No loot table: the block drops itself with its cells, cards and settings in code.
 */
public final class IoPortContent {
    private static final String PREFIX = "gui." + MaxtechStorage.MOD_ID + ".io_port.";
    /** The overlay's glance: "Unload, 3 cells waiting". */
    public static final String GLANCE_KEY = "info." + MaxtechStorage.MOD_ID + ".io_port.glance";
    /** The overlay's details: when cells move on, the speed and the cells in the output. */
    public static final String DETAIL_KEY = "info." + MaxtechStorage.MOD_ID + ".io_port.detail";
    public static final String INPUT_KEY = PREFIX + "input";
    public static final String OUTPUT_KEY = PREFIX + "output";
    public static final String MODE_HINT_KEY = PREFIX + "mode_hint";
    public static final String OUTPUT_HINT_KEY = PREFIX + "output_hint";
    public static final String SPEED_KEY = PREFIX + "speed";
    /** The caption above the output setting in the mode tab. */
    public static final String OUTPUT_CAPTION_KEY = PREFIX + "output_caption";

    public static final BlockEntry<IoPortBlock, IoPortItem> IO_PORT = ModContent.CONTENT
            .block("io_port", IoPortBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(IoPortItem::new)
            .model(BlockModel.orientable(MaxtechStorage.id("block/io_port_front"),
                    MaxtechStorage.id("block/storage_controller_side"),
                    MaxtechStorage.id("block/storage_controller_side")))
            .lang("IO Port").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PIP").pattern("CWC")
                    .pattern("PLP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('I', MaterialTags.component("basic_io_processor"))
                    .define('W', MaterialTags.component("insulated_copper_wire")).define('C', ConventionTags.CHESTS)
                    .define('L', MaterialTags.component("basic_logic_processor"))
                    .unlockedBy("has_io_processor", recipes.has(MaterialTags.component("basic_io_processor")))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<IoPortBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("io_port", () -> new BlockEntityType<>(IoPortBlockEntity::new, Set.of(IO_PORT.get())));

    /** The IO port's screen: input and output cells, speed cards, mode and output setting. */
    public static final RegistryEntry<MenuType<?>, MenuType<IoPortMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("io_port", () -> Platform.get().menus().menuType(IoPortMenu::fromNetwork));

    static {
        ModContent.CONTENT.translation(IoPortMode.UNLOAD.translationKey(), "Unload");
        ModContent.CONTENT.translation(IoPortMode.LOAD.translationKey(), "Load");
        ModContent.CONTENT.translation(IoPortOutput.EMPTY.translationKey(), "When empty");
        ModContent.CONTENT.translation(IoPortOutput.FULL.translationKey(), "When full");
        ModContent.CONTENT.translation(IoPortOutput.WORK_DONE.translationKey(), "When done");
        ModContent.CONTENT.translation(INPUT_KEY, "In");
        ModContent.CONTENT.translation(OUTPUT_KEY, "Out");
        ModContent.CONTENT.translation(MODE_HINT_KEY, "Unload empties cells into the network; load fills them from it");
        ModContent.CONTENT.translation(OUTPUT_HINT_KEY,
                "When a cell moves to the output: as soon as it is empty or full, or once nothing more moves");
        ModContent.CONTENT.translation(SPEED_KEY, "%s ops/t");
        ModContent.CONTENT.translation(OUTPUT_CAPTION_KEY, "Cells move out");
        ModContent.CONTENT.translation(GLANCE_KEY, "%s, %s cells waiting");
        ModContent.CONTENT.translation(DETAIL_KEY, "Moves cells out: %s; %s operations per tick; %s cells done");
    }

    private IoPortContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
