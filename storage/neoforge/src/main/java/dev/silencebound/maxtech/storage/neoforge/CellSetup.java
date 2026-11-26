package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.energy.ItemEnergy;
import dev.silencebound.maxtech.storage.cell.CellContent;
import dev.silencebound.maxtech.storage.cell.CellTypes;
import dev.silencebound.maxtech.storage.cell.PortableCellItem;
import dev.silencebound.maxtech.storage.config.StorageServerConfig;
import dev.silencebound.maxtech.storage.registry.ModDataComponents;
import dev.silencebound.maxtech.storage.drive.DriveContent;
import dev.silencebound.maxtech.storage.ioport.IoPortContent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * Loader glue for cells and drives: creates the cell type registry, which common code reads back from the root
 * registry, exposes each drive's cell slots and each IO port's cell slots as their item capability on every side, and
 * gives the portable cell's battery NeoForge's item energy capability.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class CellSetup {
    private CellSetup() {
    }

    @SubscribeEvent
    static void createRegistries(NewRegistryEvent event) {
        // Synced: cells name their type by id, so client and server must agree on the types.
        event.create(new RegistryBuilder<>(CellTypes.REGISTRY_KEY).sync(true));
    }

    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.Item.BLOCK, DriveContent.DRIVE_BLOCK_ENTITY.get(),
                (drive, side) -> drive.cells());
        // The IO port: cells go into its input slots and come out of its output slots, on every side.
        event.registerBlockEntity(Capabilities.Item.BLOCK, IoPortContent.BLOCK_ENTITY.get(),
                (port, side) -> port.automation());
        // The portable cell's battery takes FE from any charger; nothing may draw it out.
        event.registerItem(Capabilities.Energy.ITEM,
                (stack, access) -> ItemEnergy.chargeOnly(access, ModDataComponents.PORTABLE_ENERGY.get(),
                        PortableCellItem.capacity(),
                        (int) StorageServerConfig.loadedOr(() -> StorageServerConfig.PORTABLE_MAX_INPUT.getAsInt(), 0)),
                CellContent.PORTABLE_STORAGE_CELL.get());
    }
}
