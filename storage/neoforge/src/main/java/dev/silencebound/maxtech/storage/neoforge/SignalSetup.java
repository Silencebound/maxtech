package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.signal.SignalContent;
import dev.silencebound.maxtech.storage.signal.WirelessTerminalItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loader glue for the signal area: the wireless terminal's charge as NeoForge's item energy capability. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class SignalSetup {
    private SignalSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Chargers fill the terminal; nothing may draw it out, it spends its charge per operation itself.
        event.registerItem(Capabilities.Energy.ITEM,
                (stack, access) -> ItemEnergy.chargeOnly(access, ModDataComponents.WIRELESS_ENERGY.get(),
                        WirelessTerminalItem.capacity(), WirelessTerminalItem.maxInput()),
                SignalContent.WIRELESS_TERMINAL.get());
    }
}
