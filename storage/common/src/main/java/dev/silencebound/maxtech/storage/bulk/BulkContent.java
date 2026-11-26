package dev.silencebound.maxtech.storage.bulk;

import com.mojang.serialization.Codec;
import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.lib.platform.RegistryHelper;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import dev.silencebound.maxtech.storage.research.ResearchGates;
import dev.silencebound.maxtech.storage.save.KeptValue;
import dev.silencebound.maxtech.storage.tool.blueprint.DeviceSettings;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * The bulk drive (M6f area B): block, item, block entity type, its two item components, its menu and lang lines. The
 * components and menu use this area's own registrars, so the shared {@code ModDataComponents} and {@code ModMenus} stay
 * untouched. No loot table: the block drops itself with its contents and priority in code.
 */
public final class BulkContent {
    /** The research unlock that gates placing a bulk drive, from the Void branch. */
    public static final Identifier UNLOCK = ResearchGates.BULK_DRIVE;

    private static final RegistryHelper.Registrar<DataComponentType<?>> COMPONENTS = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /**
     * A bulk drive item's contents, kept as saved when their item no longer loads (a removed mod). Synced for its
     * tooltip.
     */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<KeptValue<BulkContents>>> CONTENTS = COMPONENTS
            .register("bulk_contents", () -> DataComponentType.<KeptValue<BulkContents>>builder()
                    .persistent(BulkContents.KEPT_CODEC).networkSynchronized(BulkContents.KEPT_STREAM_CODEC).build());
    /** A bulk drive item's priority, left out at 0. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<Integer>> PRIORITY = COMPONENTS.register(
            "bulk_priority",
            () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.intRange(BulkDriveBlockEntity.MIN_PRIORITY, BulkDriveBlockEntity.MAX_PRIORITY))
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    /** The drive's screen: its contents and the priority tab. */
    public static final RegistryEntry<MenuType<?>, MenuType<BulkDriveMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("bulk_drive", () -> Platform.get().menus().menuType(BulkDriveMenu::fromNetwork));

    // A drive and the largest memory, held to one kind by elite memory processors.
    public static final BlockEntry<BulkDriveBlock, BulkDriveItem> BULK_DRIVE = ModContent.CONTENT
            .block("bulk_drive", BulkDriveBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
            .item(BulkDriveItem::new)
            .model(BlockModel.orientable(MaxtechStorage.id("block/bulk_drive_front"),
                    MaxtechStorage.id("block/storage_controller_side"),
                    MaxtechStorage.id("block/storage_controller_side")))
            .lang("Bulk Drive").tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PEP").pattern("MDM")
                    .pattern("PEP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE)
                    .define('E', MaterialTags.component("elite_memory_processor"))
                    .define('M', CellContent.MEMORY_MODULE_4_TAG).define('D', DriveContent.DRIVE.get())
                    .unlockedBy("has_memory_module_4", recipes.has(CellContent.MEMORY_MODULE_4_TAG))
                    .save(recipes.output()))
            .creativeTab().register();

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<BulkDriveBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("bulk_drive", () -> new BlockEntityType<>(BulkDriveBlockEntity::new, Set.of(BULK_DRIVE.get())));

    static {
        ModContent.CONTENT.translation(BulkTexts.HOLDS, "Holds %s %s");
        ModContent.CONTENT.translation(BulkTexts.EMPTY, "Empty");
        ModContent.CONTENT.translation(BulkTexts.PRIORITY, "Priority: %s");
        ModContent.CONTENT.translation(BulkTexts.CAPACITY, "Up to %s items of one kind");
        ModContent.CONTENT.translation(BulkTexts.NEEDS_RESEARCH, "Your team needs the research %s to place this");
        ModContent.CONTENT.translation(DeviceSettings.kindKey(BulkDriveBlockEntity.SETTINGS_KIND), "Bulk Drive");
        ModContent.CONTENT.translation(BulkTexts.HELP, "Sneak-use with an empty hand to take a stack");
    }

    private BulkContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }
}
