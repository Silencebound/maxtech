package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.logic.remote.LogicRemoteItem;
import dev.silencebound.maxtech.storage.logic.remote.RemoteContent;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loader glue for the logic remote: its charge as NeoForge's item energy capability. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class RemoteSetup {
    private RemoteSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Chargers fill the remote; nothing may draw it out, it spends its charge per pause or resume itself.
        event.registerItem(Capabilities.Energy.ITEM, (stack, access) -> ItemEnergy.chargeOnly(access,
                ModDataComponents.WIRELESS_ENERGY.get(), LogicRemoteItem.capacity(), LogicRemoteItem.maxInput()),
                RemoteContent.REMOTE.get());
    }
}
