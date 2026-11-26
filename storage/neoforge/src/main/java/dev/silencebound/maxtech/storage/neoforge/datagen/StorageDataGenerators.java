package dev.silencebound.maxtech.storage.neoforge.datagen;

import dev.silencebound.maxtech.lib.neoforge.ContentEnabledCondition;
import dev.silencebound.maxtech.storage.config.StorageModules;
import dev.silencebound.maxtech.lib.neoforge.datagen.ContentDatagen;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.registry.ModContent;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.data.loot.LootTableProvider;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Registers Storage's data providers for {@code runData}; output lands in {@code storage/common/src/generated}. Models,
 * names, tags, loot and base recipes come from the declarations in {@link ModContent}; the ores' loot, the machine
 * models and recipes and the worldgen entries are written here.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID, value = Dist.CLIENT)
final class StorageDataGenerators {
    private StorageDataGenerators() {
    }

    @SubscribeEvent
    static void gatherData(GatherDataEvent.Client event) {
        // Module recipes carry the content toggle's condition, a NeoForge type the common declarations cannot name.
        StorageModules.installGate((output, toggle) -> output.withConditions(new ContentEnabledCondition(toggle)));
        ContentDatagen.providers(ModContent.CONTENT).language(StorageLanguageProvider::new)
                .models(StorageModelProvider::new).blockTags(StorageBlockTagsProvider::new)
                .loot(new LootTableProvider.SubProviderEntry(StorageOreLoot::new, LootContextParamSets.BLOCK))
                .register(event);
        event.createProvider(StorageMachineRecipeProvider.Runner::new);
        event.createDatapackRegistryObjects(
                ResearchSourceDatagen.add(ResearchDatagen.add(StorageWorldgenData.registries())));
        event.createProvider(ResearchDatagen.LootModifiers::new);
        event.createProvider(ResearchSourceDatagen.TradeTags::new);
        event.createProvider(ResearchSourceDatagen.LootModifiers::new);
        event.createProvider(StorageWorldgenTags.Biomes::new);
        event.createProvider(StorageWorldgenTags.DimensionTypes::new);
        event.createProvider(StorageAdvancements::provider);
        event.createProvider(SensorReaderDatagen::new);
        event.createProvider(LogicTemplateDatagen::new);
        event.createProvider(CuriosSlotDatagen.Entities::new);
        event.createProvider(CuriosSlotDatagen.Tags::new);
    }
}
