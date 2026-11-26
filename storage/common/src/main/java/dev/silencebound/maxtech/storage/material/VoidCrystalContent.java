package dev.silencebound.maxtech.storage.material;

import dev.silencebound.maxtech.lib.content.BlockEntry;
import dev.silencebound.maxtech.storage.config.StorageModules;
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
 * Void crystal (PRD 7.2, tier 5 "Endgame"), found in void geodes under the surface of the outer End islands: budding
 * void crystal grows buds into clusters as amethyst does, and the clusters break into void crystal shards. Doping a
 * data crystal wafer with void crystal in the lithograph makes the void lattice (machine step in
 * {@code machine.MachineRecipes}), which memory modules V and VI and the void machine module are built from. Bud and
 * cluster models and the cluster's loot come from Storage's NeoForge datagen ({@code GeodeModels},
 * {@code StorageOreLoot}).
 */
public final class VoidCrystalContent {
    // world.WorldContent.BREAKER_BLACKLIST named again: reading it would load that class now (see DataCrystalContent).
    private static final TagKey<Block> BREAKER_BLACKLIST = TagKey.create(Registries.BLOCK,
            MaxtechStorage.id("breaker_blacklist"));

    public static final RegistryEntry<Item, Item> VOID_CRYSTAL = ModContent.CONTENT.item("void_crystal", Item::new)
            .model(MaterialLooks.tinted(MaterialLooks.Form.GEM, MaterialLooks.VOID)).lang("Void Crystal")
            .tag(MaterialTags.VOID_CRYSTAL).creativeTab().register();

    /** Decorative, from four shards, as the amethyst block; lines void geodes. */
    public static final BlockEntry<AmethystBlock, BlockItem> VOID_CRYSTAL_BLOCK = ModContent.CONTENT
            .block("void_crystal_block", AmethystBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_PURPLE).strength(1.5F).sound(SoundType.AMETHYST)
                    .requiresCorrectToolForDrops())
            .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/void_crystal_block")))
            .lang("Block of Void Crystal").loot(BlockLoot.SELF).tag(BlockTags.MINEABLE_WITH_PICKAXE)
            .recipe((recipes, item) -> recipes.shaped(RecipeCategory.BUILDING_BLOCKS, item).pattern("VV").pattern("VV")
                    .define('V', MaterialTags.VOID_CRYSTAL)
                    .unlockedBy("has_void_crystal", recipes.has(MaterialTags.VOID_CRYSTAL))
                    .save(StorageModules.gate(recipes.output(), StorageModules.VOID_GEODES)))
            .creativeTab().register();
    /** Drops nothing, even with silk touch, and pistons break it, as budding amethyst. */
    public static final BlockEntry<BuddingVoidCrystalBlock, BlockItem> BUDDING_VOID_CRYSTAL = ModContent.CONTENT
            .block("budding_void_crystal", BuddingVoidCrystalBlock::new)
            .properties(p -> p.mapColor(MapColor.COLOR_PURPLE).randomTicks().strength(1.5F).sound(SoundType.AMETHYST)
                    .requiresCorrectToolForDrops().pushReaction(PushReaction.DESTROY))
            .simpleItem().model(BlockModel.cubeAll(MaxtechStorage.id("block/budding_void_crystal")))
            .lang("Budding Void Crystal").loot(BlockLoot.table(LootTable::lootTable))
            .tag(BlockTags.MINEABLE_WITH_PICKAXE).tag(BREAKER_BLACKLIST).creativeTab().register();
    public static final BlockEntry<AmethystClusterBlock, BlockItem> SMALL_VOID_CRYSTAL_BUD = cluster(
            "small_void_crystal_bud", "Small Void Crystal Bud", 3.0F, 8.0F, 1, SoundType.SMALL_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> MEDIUM_VOID_CRYSTAL_BUD = cluster(
            "medium_void_crystal_bud", "Medium Void Crystal Bud", 4.0F, 10.0F, 2, SoundType.MEDIUM_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> LARGE_VOID_CRYSTAL_BUD = cluster(
            "large_void_crystal_bud", "Large Void Crystal Bud", 5.0F, 10.0F, 4, SoundType.LARGE_AMETHYST_BUD);
    public static final BlockEntry<AmethystClusterBlock, BlockItem> VOID_CRYSTAL_CLUSTER = cluster(
            "void_crystal_cluster", "Void Crystal Cluster", 7.0F, 10.0F, 5, SoundType.AMETHYST_CLUSTER);

    /** A data crystal wafer doped with void crystal in the lithograph; the base of memory V and VI and void modules. */
    public static final RegistryEntry<Item, Item> VOID_LATTICE = ModContent.CONTENT.item("void_lattice", Item::new)
            .model(MaterialContent.flat("void_lattice")).lang("Void Lattice")
            .tag(MaterialTags.component("void_lattice")).creativeTab().register();

    static {
        ModContent.CONTENT.itemTag(MaterialTags.GEMS, MaterialTags.VOID_CRYSTAL);
    }

    private VoidCrystalContent() {
    }

    /** Loads the class during mod construction so the entries above are queued before registration runs. */
    public static void init() {
    }

    /** The buds and cluster, in growth order. */
    public static List<AmethystClusterBlock> growthStages() {
        return List.of(SMALL_VOID_CRYSTAL_BUD.get(), MEDIUM_VOID_CRYSTAL_BUD.get(), LARGE_VOID_CRYSTAL_BUD.get(),
                VOID_CRYSTAL_CLUSTER.get());
    }

    /** A bud or the cluster, shaped and lit as amethyst's stage of the same size; models and loot are datagen's. */
    private static BlockEntry<AmethystClusterBlock, BlockItem> cluster(String name, String english, float height,
            float width, int light, SoundType sound) {
        return ModContent.CONTENT.block(name, p -> new AmethystClusterBlock(height, width, p))
                .properties(p -> p.mapColor(MapColor.COLOR_PURPLE).forceSolidOn().noOcclusion().sound(sound)
                        .strength(1.5F).lightLevel(state -> light).pushReaction(PushReaction.DESTROY))
                .simpleItem().lang(english).tag(BlockTags.MINEABLE_WITH_PICKAXE).creativeTab().register();
    }
}
