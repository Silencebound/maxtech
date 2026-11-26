package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.neoforge.platform.NeoForgeBlockLookup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.machine.MachineContent;
import dev.silencebound.maxtech.storage.machine.MachineKind;
import net.minecraft.core.Direction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/**
 * Exposes every Storage machine's slots and energy (in FE at Lib's rate) on all six sides, so hoppers, pipes and FE
 * cables work anywhere, and the generator's energy endpoint for diagnostics.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class StorageMachineCapabilities {
    private StorageMachineCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        BlockCapability<EnergyEndpoint, @Nullable Direction> endpoint = NeoForgeBlockLookup.of(EnergyLookups.BLOCK)
                .capability();
        for (MachineKind kind : MachineKind.values()) {
            event.registerBlockEntity(endpoint, MachineContent.blockEntityType(kind),
                    (machine, side) -> machine.energyEndpoint());
            event.registerBlockEntity(Capabilities.Item.BLOCK, MachineContent.blockEntityType(kind),
                    (machine, side) -> machine.items());
            event.registerBlockEntity(Capabilities.Energy.BLOCK, MachineContent.blockEntityType(kind),
                    (machine, side) -> machine.feHandler());
        }
    }
}
