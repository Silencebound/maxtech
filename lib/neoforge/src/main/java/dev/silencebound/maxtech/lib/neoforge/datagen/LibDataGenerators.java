package dev.silencebound.maxtech.lib.neoforge.datagen;

import dev.silencebound.maxtech.lib.MaxtechLib;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/** Registers Lib's data providers for {@code runData}; output lands in {@code lib/common/src/generated}. */
@EventBusSubscriber(modid = MaxtechLib.MOD_ID, value = Dist.CLIENT)
final class LibDataGenerators {
    private LibDataGenerators() {
    }

    @SubscribeEvent
    static void gatherData(GatherDataEvent.Client event) {
        event.createProvider(LibLanguageProvider::new);
        event.createProvider(LibItemTagsProvider::new);
        event.createProvider(LibModelProvider::new);
        event.createProvider(LibRecipeProvider.Runner::new);
    }
}
