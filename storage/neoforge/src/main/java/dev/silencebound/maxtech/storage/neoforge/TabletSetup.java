package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletContent;
import dev.silencebound.maxtech.storage.tool.tablet.CraftingTabletItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loader glue for the crafting tablet: its charge as NeoForge's item energy capability. */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class TabletSetup {
    private TabletSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Chargers fill the tablet; nothing may draw it out, it spends its charge per operation itself.
        event.registerItem(Capabilities.Energy.ITEM,
                (stack, access) -> ItemEnergy.chargeOnly(access, ModDataComponents.WIRELESS_ENERGY.get(),
                        CraftingTabletItem.capacity(), CraftingTabletItem.maxInput()),
                CraftingTabletContent.TABLET.get());
    }
}
