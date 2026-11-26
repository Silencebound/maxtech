package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.tool.layer.CableLayerItem;
import dev.silencebound.maxtech.storage.tool.layer.LayerContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loader glue for the cable layer: its charge as NeoForge's item energy capability. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class LayerSetup {
    private LayerSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Chargers fill the layer; nothing may draw it out, it spends its charge per cable laid.
        event.registerItem(Capabilities.Energy.ITEM, (stack, access) -> ItemEnergy.chargeOnly(access,
                LayerContent.ENERGY.get(), CableLayerItem.capacity(), CableLayerItem.maxInput()),
                LayerContent.CABLE_LAYER.get());
    }
}
