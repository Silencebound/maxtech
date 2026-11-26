package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.lib.content.BlockLoot;
import dev.silencebound.maxtech.lib.content.BlockModel;
import dev.silencebound.maxtech.lib.platform.RegistryEntry;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * Deep data crystal (PRD 7.2, tier 5 "Big Storage"), found in deep data geodes in deepslate: budding data crystal grows
 * buds into clusters as amethyst does, and the clusters break into data crystal shards. A shard slices into crystal
 * wafers that ember doping and the four family masks turn into elite dies, assembled with superconductor wire into the
 * elite processors of all four families. The machine steps are data in {@code machine.MachineRecipes}; the bud and
 * cluster models and the cluster's loot are written by Storage's NeoForge datagen ({@code GeodeModels},
 * {@code StorageOreLoot}).
 */
public final class DataCrystalContent {
    // research.ResearchContent.PROCESSORS and world.WorldContent.BREAKER_BLACKLIST, named again here: reading those
    // fields would load their classes now and move their entries in the registration and creative tab order.
    private static final TagKey<Item> PROCESSORS = TagKey.create(Registries.ITEM, MaxtechStorage.id("processors"));
    private static final TagKey<Block> BREAKER_BLACKLIST = TagKey.create(Registries.BLOCK,
            MaxtechStorage.id("breaker_blacklist"));

    public static final RegistryEntry<Item, Item> DATA_CRYSTAL = ModContent.CONTENT.item("data_crystal", Item::new)
            .model(MaterialLooks.tinted(MaterialLooks.Form.GEM, MaterialLooks.DATA)).lang("Data Crystal")
            .tag(MaterialTags.DATA_CRYSTAL).creativeTab().register();

    /** Decorative, from four shards, as the amethyst block. */
    public static final BlockEntry<AmethystBlock, BlockItem> DATA_CRYSTAL_BLOCK = ModContent.CONTENT
            .block("data_crystal_block", AmethystBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).strength(1.5F).sound(SoundType.AMETHYST)
                    .requiresCorrectToolForDrops())
            .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/data_crystal_block")))
            .lang("Block of Data Crystal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.BUILDING_BLOCKS, item).pattern("DD").pattern("DD")
                    .define('D', MaterialTags.DATA_CRYSTAL)
                    .unlockedBy("has_data_crystal", recipes.has(MaterialTags.DATA_CRYSTAL)).save(recipes.output()))
            .creativeTab().register();
    /** Drops nothing, even with silk touch, and pistons break it, as budding amethyst. */
    public static final BlockEntry<BuddingDataCrystalBlock, BlockItem> BUDDING_DATA_CRYSTAL = ModContent.CONTENT
            .block("budding_data_crystal", BuddingDataCrystalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_CYAN).randomTicks().strength(1.5F).sound(SoundType.AMETHYST)
                    .requiresCorrectToolForDrops().pushReaction(PushReaction.DESTROY))
            .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/budding_data_crystal")))
            .lang("Budding Data Crystal").loot(BlockLoot.table(LootTable::lootTable))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).tag(BREAKER_BLACKLIST).creativeTab().register();
    public static final BlockEntry<AmethystClusterBlock, BlockItem> SMALL_DATA_CRYSTAL_BUD = cluster(
            "small_data_crystal_bud", "Small Data Crystal Bud", 3.0F, 8.0F, 1, SoundType.SMALL_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> MEDIUM_DATA_CRYSTAL_BUD = cluster(
            "medium_data_crystal_bud", "Medium Data Crystal Bud", 4.0F, 10.0F, 2, SoundType.MEDIUM_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> LARGE_DATA_CRYSTAL_BUD = cluster(
            "large_data_crystal_bud", "Large Data Crystal Bud", 5.0F, 10.0F, 4, SoundType.LARGE_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> DATA_CRYSTAL_CLUSTER = cluster(
            "data_crystal_cluster", "Data Crystal Cluster", 7.0F, 10.0F, 5, SoundType.AMETHYST_CLUSTER);

    public static final RegistryEntry<Item, Item> DATA_CRYSTAL_WAFER = component("data_crystal_wafer",
            "Data Crystal Wafer");
    public static final RegistryEntry<Item, Item> EMBER_DOPED_WAFER = component("ember_doped_wafer",
            "Ember-Doped Wafer");
    public static final RegistryEntry<Item, Item> ELITE_LOGIC_DIE = component("elite_logic_die", "Elite Logic Die");
    public static final RegistryEntry<Item, Item> ELITE_MEMORY_DIE = component("elite_memory_die", "Elite Memory Die");
    public static final RegistryEntry<Item, Item> ELITE_SIGNAL_DIE = component("elite_signal_die", "Elite Signal Die");
    public static final RegistryEntry<Item, Item> ELITE_IO_DIE = component("elite_io_die", "Elite I/O Die");
    public static final RegistryEntry<Item, Item> ELITE_LOGIC_PROCESSOR = component("elite_logic_processor",
            "Elite Logic Processor");
    public static final RegistryEntry<Item, Item> ELITE_MEMORY_PROCESSOR = component("elite_memory_processor",
            "Elite Memory Processor");
    public static final RegistryEntry<Item, Item> ELITE_SIGNAL_PROCESSOR = component("elite_signal_processor",
            "Elite Signal Processor");
    public static final RegistryEntry<Item, Item> ELITE_IO_PROCESSOR = component("elite_io_processor",
            "Elite I/O Processor");

    static {
        ModContent.CONTENT.itemTag(MaterialTags.GEMS, MaterialTags.DATA_CRYSTAL);
        ModContent.CONTENT.itemTag(PROCESSORS, MaterialTags.component("elite_logic_processor"),
                MaterialTags.component("elite_memory_processor"), MaterialTags.component("elite_signal_processor"),
                MaterialTags.component("elite_io_processor"));
    }

    private DataCrystalContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** The buds and clusters, in growth order. */
    public static List<AmethystClusterBlock> growthStages() {
        return List.of(SMALL_DATA_CRYSTAL_BUD.get(), MEDIUM_DATA_CRYSTAL_BUD.get(), LARGE_DATA_CRYSTAL_BUD.get(),
                DATA_CRYSTAL_CLUSTER.get());
    }

    /**
     * A bud or the cluster, shaped and lit as amethyst's stage of the same size. No model or loot here: the facing
     * cross model and the silk-touch and pickaxe loot are the NeoForge datagen's.
     */
    private static BlockEntry<AmethystClusterBlock, BlockItem> cluster(String name, String english, float height,
            float width, int light, SoundType sound) {
        return ModContent.CONTENT.block(name, p -> new AmethystClusterBlock(height, width, p))
                .properties(p -> p.mapColor(MapColor.COLOR_CYAN).forceSolidOn().noOcclusion().sound(sound)
                        .strength(1.5F).lightLevel(state -> light).pushReaction(PushReaction.DESTROY))
                .simpleItem().lang(english).tag(BlockTags.MINEABLE_WITH_PICKAXE).creativeTab().register();
    }

    private static RegistryEntry<Item, Item> component(String name, String english) {
        return ModContent.CONTENT.item(name, Item::new).model(MaterialContent.flat(name)).lang(english)
                .tag(MaterialTags.component(name)).creativeTab().register();
    }
}
