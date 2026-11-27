package dev.silencebound.maxtech.farms.neoforge;

import dev.silencebound.maxtech.farms.MaxtechFarms;
import dev.silencebound.maxtech.farms.registry.FarmsContent;
import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.energy.EnergyNetworks;
import net.minecraft.core.Direction;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.jspecify.annotations.Nullable;

/**
 * Loader glue for the lab hub and the fluid port: the hub's tank, items and FE input as NeoForge capabilities on every
 * side, its MU store for Lib's energy diagnostics, the port's insert-only fluid input, and the creative tab. The
 * handlers all come from the blocks' own storage, so this is the only place that names NeoForge's capabilities for
 * them.
 */
@EventBusSubscriber(modid = MaxtechFarms.MOD_ID)
final class LabHubSetup {
    private static final BlockCapability<EnergyEndpoint, @Nullable Direction> ENDPOINT = BlockCapability
            .createSided(EnergyNetworks.ENDPOINT_CAPABILITY, EnergyEndpoint.class);

    private LabHubSetup() {
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FarmsContent.LAB_HUB_ENTITY.get(),
                (hub, side) -> hub.storage().tank());
        event.registerBlockEntity(Capabilities.Item.BLOCK, FarmsContent.LAB_HUB_ENTITY.get(),
                (hub, side) -> hub.storage().itemHandler());
        event.registerBlockEntity(Capabilities.Energy.BLOCK, FarmsContent.LAB_HUB_ENTITY.get(),
                (hub, side) -> hub.feHandler());
        event.registerBlockEntity(ENDPOINT, FarmsContent.LAB_HUB_ENTITY.get(), (hub, side) -> hub.energy());
        event.registerBlockEntity(Capabilities.Fluid.BLOCK, FarmsContent.FLUID_PORT_ENTITY.get(),
                (port, side) -> port.fluidHandler());
    }

    @SubscribeEvent
    static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(FarmsContent.LAB_HUB_ITEM.get());
            event.accept(FarmsContent.FLUID_PORT_ITEM.get());
        }
    }
}
