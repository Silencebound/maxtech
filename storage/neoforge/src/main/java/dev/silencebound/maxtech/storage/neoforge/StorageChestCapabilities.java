package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.storagechest.StorageChestContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Exposes the storage chest's slots on every side, so hoppers and pipes fill and empty it like a chest. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class StorageChestCapabilities {
    private StorageChestCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, StorageChestContent.BLOCK_ENTITY.get(),
                (chest, side) -> chest.items());
    }
}
