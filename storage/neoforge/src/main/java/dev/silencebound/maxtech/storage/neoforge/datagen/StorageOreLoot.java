package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.storage.material.DataCrystalContent;
import dev.silencebound.maxtech.storage.material.EmberMaterialContent;
import dev.silencebound.maxtech.storage.material.MaterialContent;
import dev.silencebound.maxtech.storage.material.TierTwoMaterialContent;
import dev.silencebound.maxtech.storage.material.VoidCrystalContent;
import net.minecraft.world.level.block.AmethystClusterBlock;
import java.util.List;
import java.util.Set;
import net.minecraft.advancements.criterion.ItemPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.predicates.MatchTool;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

/**
 * Storage's ore loot: one lumen quartz, raw tantalite or ember quartz with fortune's ore bonus, the ore itself with
 * silk touch, as vanilla's ores; the data and void crystal buds and clusters as amethyst's. Written here rather than
 * declared with the blocks because the fortune and silk touch conditions need the enchantment registry, which only a
 * datagen provider has.
 */
final class StorageOreLoot extends BlockLootSubProvider {
    StorageOreLoot(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.DEFAULT_FLAGS, registries);
    }

    @Override
    protected void generate() {
        for (Block ore : List.of(MaterialContent.LUMEN_QUARTZ_ORE.get(),
                MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.get())) {
            add(ore, createOreDrop(ore, MaterialContent.LUMEN_QUARTZ.get()));
        }
        Block tantalite = TierTwoMaterialContent.DEEPSLATE_TANTALITE_ORE.get();
        add(tantalite, createOreDrop(tantalite, TierTwoMaterialContent.RAW_TANTALITE.get()));
        Block ember = EmberMaterialContent.EMBER_QUARTZ_ORE.get();
        add(ember, createOreDrop(ember, EmberMaterialContent.EMBER_QUARTZ.get()));
        // As amethyst: buds only with silk touch; a cluster drops itself with silk touch, else four shards (with
        // fortune) to a pickaxe and two to anything else.
        crystal(DataCrystalContent.growthStages(), DataCrystalContent.DATA_CRYSTAL.get());
        crystal(VoidCrystalContent.growthStages(), VoidCrystalContent.VOID_CRYSTAL.get());
    }

    /** The buds of {@code stages} drop only with silk touch; the cluster (the last stage) drops {@code shard}s. */
    private void crystal(List<AmethystClusterBlock> stages, Item shard) {
        for (int i = 0; i < stages.size() - 1; i++) {
            dropWhenSilkTouch(stages.get(i));
        }
        HolderLookup.RegistryLookup<Enchantment> enchantments = registries.lookupOrThrow(Registries.ENCHANTMENT);
        HolderLookup.RegistryLookup<Item> items = registries.lookupOrThrow(Registries.ITEM);
        add(stages.getLast(),
                block -> createSilkTouchDispatchTable(block,
                        LootItem.lootTableItem(shard).apply(SetItemCountFunction.setCount(ConstantValue.exactly(4.0F)))
                                .apply(ApplyBonusCount.addOreBonusCount(enchantments.getOrThrow(Enchantments.FORTUNE)))
                                .when(MatchTool.toolMatches(
                                        ItemPredicate.Builder.item().of(items, ItemTags.CLUSTER_MAX_HARVESTABLES)))
                                .otherwise(applyExplosionDecay(block, LootItem.lootTableItem(shard)
                                        .apply(SetItemCountFunction.setCount(ConstantValue.exactly(2.0F)))))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ores();
    }

    private static List<Block> ores() {
        return List.of(MaterialContent.LUMEN_QUARTZ_ORE.get(), MaterialContent.DEEPSLATE_LUMEN_QUARTZ_ORE.get(),
                TierTwoMaterialContent.DEEPSLATE_TANTALITE_ORE.get(), EmberMaterialContent.EMBER_QUARTZ_ORE.get(),
                DataCrystalContent.SMALL_DATA_CRYSTAL_BUD.get(), DataCrystalContent.MEDIUM_DATA_CRYSTAL_BUD.get(),
                DataCrystalContent.LARGE_DATA_CRYSTAL_BUD.get(), DataCrystalContent.DATA_CRYSTAL_CLUSTER.get(),
                VoidCrystalContent.SMALL_VOID_CRYSTAL_BUD.get(), VoidCrystalContent.MEDIUM_VOID_CRYSTAL_BUD.get(),
                VoidCrystalContent.LARGE_VOID_CRYSTAL_BUD.get(), VoidCrystalContent.VOID_CRYSTAL_CLUSTER.get());
    }
}
