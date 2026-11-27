package dev.silencebound.maxtech.farms.neoforge.datagen;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import java.util.List;
import net.minecraft.core.HolderLookup;
import java.util.Set;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Registers Farms' data providers for {@code runData}; output lands in {@code farms/common/src/generated}. */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID, value = Dist.CLIENT)
final class FarmsDataGenerators {
    private FarmsDataGenerators() {
    }

    @SubscribeEvent
    static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(FarmsLanguageProvider::new);
        event.createProvider(FarmsModelProvider::new);
        event.createProvider(FarmsBlockTagsProvider::new);
        event.createProvider(FarmsRecipeProvider.Runner::new);
        event.createProvider((output, registries) -> new LootTableProvider(output, Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(BlockLoot::new, LootContextParamSets.BLOCK)),
                registries));
    }

    /**
     * Blocks drop themselves; the hub adds what it holds in its own {@code getDrops}. The hub has no explosion
     * condition, so like a shulker box an explosion never destroys it or what it holds.
     */
    private static final class BlockLoot extends BlockLootSubProvider {
        BlockLoot(HolderLookup.Provider registries) {
            super(Set.of(), FeatureFlags.DEFAULT_FLAGS, registries);
        }

        @Override
        protected void generate() {
            Block hub = FarmsContent.LAB_HUB.get();
            add(hub, LootTable.lootTable()
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1)).add(LootItem.lootTableItem(hub))));
            dropSelf(FarmsContent.FLUID_PORT.get());
        }

        @Override
        protected Iterable<Block> getKnownBlocks() {
            return List.of(FarmsContent.LAB_HUB.get(), FarmsContent.FLUID_PORT.get());
        }
    }
}
