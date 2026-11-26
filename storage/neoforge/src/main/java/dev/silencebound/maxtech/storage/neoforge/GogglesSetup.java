package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.goggles.GogglesContent;
import dev.silencebound.maxtech.storage.tool.goggles.NetworkGogglesItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Loader glue for the network goggles: their charge as NeoForge's item energy capability, and forgetting a wearer's
 * last overlay on logout.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class GogglesSetup {
    private GogglesSetup() {
    }

    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        NetworkGogglesItem.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Chargers fill the goggles; nothing may draw it out, they spend their charge while showing.
        event.registerItem(Capabilities.Energy.ITEM,
                (stack, access) -> ItemEnergy.chargeOnly(access, ModDataComponents.GOGGLES_ENERGY.get(),
                        NetworkGogglesItem.capacity(), NetworkGogglesItem.maxInput()),
                GogglesContent.NETWORK_GOGGLES.get());
    }
}
