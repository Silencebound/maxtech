package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.save.RetiredIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Loader glue for {@link RetiredIds}: adds each retired id as a registry alias of its replacement, for blocks and
 * items, on every start (aliases are not saved).
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class RetiredIdSetup {
    private RetiredIdSetup() {
    }

    @SubscribeEvent
    static void register(RegisterEvent event) {
        if (event.getRegistryKey().equals(Registries.BLOCK)) {
            RetiredIds.BLOCKS_AND_ITEMS.forEach(BuiltInRegistries.BLOCK::addAlias);
        } else if (event.getRegistryKey().equals(Registries.ITEM)) {
            RetiredIds.BLOCKS_AND_ITEMS.forEach(BuiltInRegistries.ITEM::addAlias);
        }
    }
}
