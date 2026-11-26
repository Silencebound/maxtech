package dev.silencebound.maxtech.storage.storageblock;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.Platform;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.cell.CellCasing;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellType;
import dev.silencebound.maxtech.storage.cell.CellTypes;
import dev.silencebound.maxtech.storage.material.MaterialTags;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;

/**
 * Storage blocks (B09 item 27): the universal cell type they use, the three tiers with their items, the block entity
 * type, the item component, the menu and the lang lines. Each tier is crafted from four memory modules (I, III, IV)
 * round a cell casing (small, medium, large) with casing plates. The component and menu use this area's own registrars,
 * so the shared {@code ModDataComponents} and {@code ModMenus} stay untouched. No loot tables: the blocks drop
 * themselves with their cell id in code.
 */
public final class StorageBlockContent {
    /** Items and fluids in one cell; registered like every cell type (storage/CLAUDE.md). */
    public static final RegistryEntry<CellType, CellType> UNIVERSAL = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, CellTypes.REGISTRY_KEY)
            .register("universal", () -> new UniversalCellType());

    /** A storage block item's cell id, priority and stats. Synced for its tooltip. */
    public static final RegistryEntry<DataComponentType<?>, DataComponentType<StorageBlockData>> DATA = Platform.get()
            .registry().registrar(MaxtechStorage.MOD_ID, Registries.DATA_COMPONENT_TYPE)
            .register("storage_block", () -> DataComponentType.<StorageBlockData>builder()
                    .persistent(StorageBlockData.CODEC).networkSynchronized(StorageBlockData.STREAM_CODEC).build());

    /** The block's screen: bytes, kinds and the priority tab. */
    public static final RegistryEntry<MenuType<?>, MenuType<StorageBlockMenu>> MENU = Platform.get().registry()
            .registrar(MaxtechStorage.MOD_ID, Registries.MENU)
            .register("storage_block", () -> Platform.get().menus().menuType(StorageBlockMenu::fromNetwork));

    public static final BlockEntry<StorageBlock, StorageBlockItem> STORAGE_BLOCK_1 = tier(StorageBlockTier.ONE,
            CellContent.MEMORY_MODULE_1_TAG, CellCasing.SMALL);
    public static final BlockEntry<StorageBlock, StorageBlockItem> STORAGE_BLOCK_2 = tier(StorageBlockTier.TWO,
            CellContent.MEMORY_MODULE_3_TAG, CellCasing.MEDIUM);
    public static final BlockEntry<StorageBlock, StorageBlockItem> STORAGE_BLOCK_3 = tier(StorageBlockTier.THREE,
            CellContent.MEMORY_MODULE_4_TAG, CellCasing.LARGE);

    public static final RegistryEntry<BlockEntityType<?>, BlockEntityType<StorageBlockEntity>> BLOCK_ENTITY = Platform
            .get().registry().registrar(MaxtechStorage.MOD_ID, Registries.BLOCK_ENTITY_TYPE)
            .register("storage_block", () -> new BlockEntityType<>(StorageBlockEntity::new,
                    Set.of(STORAGE_BLOCK_1.get(), STORAGE_BLOCK_2.get(), STORAGE_BLOCK_3.get())));

    static {
        ModContent.CONTENT.translation(CellTypes.translationKey(UNIVERSAL.id()), "Universal");
        ModContent.CONTENT.translation(StorageBlockTexts.BYTES, "%s / %s bytes used");
        ModContent.CONTENT.translation(StorageBlockTexts.KINDS, "%s kinds");
        ModContent.CONTENT.translation(StorageBlockTexts.KEPT, "%s unknown entries kept (mod missing)");
        ModContent.CONTENT.translation(StorageBlockTexts.PRIORITY, "Priority: %s");
        ModContent.CONTENT.translation(StorageBlockTexts.HOLDS, "Items and fluids; keeps them when broken");
        ModContent.CONTENT.translation(StorageBlockTexts.INACTIVE,
                "Inactive: its cell is in use elsewhere or unreadable");
    }

    private StorageBlockContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** The block of {@code tier}. */
    public static BlockEntry<StorageBlock, StorageBlockItem> block(StorageBlockTier tier) {
        return switch (tier) {
            case ONE -> STORAGE_BLOCK_1;
            case TWO -> STORAGE_BLOCK_2;
            case THREE -> STORAGE_BLOCK_3;
        };
    }

    /** A tier: {@code PMP / MCM / PMP}, casing plates, four memory modules and a cell casing. */
    private static BlockEntry<StorageBlock, StorageBlockItem> tier(StorageBlockTier tier, TagKey<Item> module,
            CellCasing casing) {
        String roman = switch (tier) {
            case ONE -> "I";
            case TWO -> "II";
            case THREE -> "III";
        };
        return ModContent.CONTENT.block("storage_block_tier_" + tier.level(), p -> new StorageBlock(tier, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(3.0F, 6.0F).sound(SoundType.METAL))
                .item(StorageBlockItem::new)
                .model(BlockModel.orientable(MaxtechStorage.id("block/storage_block/front_tier_" + tier.level()),
                        MaxtechStorage.id("block/storage_block/side"), MaxtechStorage.id("block/storage_block/side")))
                .lang("Storage Block " + roman).tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .recipe((recipes, item) -> recipes.shaped(RecipeCategory.MISC, item).pattern("PMP").pattern("MCM")
                        .pattern("PMP").define('P', MaterialTags.SIGNAL_ALLOY_PLATE).define('M', module)
                        .define('C', CellContent.casingTag(casing)).unlockedBy("has_memory_module", recipes.has(module))
                        .save(recipes.output()))
                .creativeTab().register();
    }
}
