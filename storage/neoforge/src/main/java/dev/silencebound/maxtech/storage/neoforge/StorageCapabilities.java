package dev.silencebound.maxtech.storage.neoforge;

import dev.silencebound.maxtech.lib.api.energy.EnergyEndpoint;
import dev.silencebound.maxtech.lib.api.info.BlockInfoProvider;
import dev.silencebound.maxtech.lib.energy.EnergyLookups;
import dev.silencebound.maxtech.lib.info.InfoLookups;
import dev.silencebound.maxtech.lib.neoforge.platform.NeoForgeBlockLookup;
import dev.silencebound.maxtech.storage.MaxtechStorage;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlock;
import dev.silencebound.maxtech.storage.network.StorageDeviceBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jspecify.annotations.Nullable;

/**
 * Every {@link StorageDeviceBlock} of Storage (and of its GameTests) gets an FE input on every side, which feeds its
 * network in MU at Lib's FE rate, Lib's energy endpoint capability where the device owns an endpoint (diagnostics:
 * Jade, the scanner, the analyzer, the probe and sensors), and Lib's block info capability. Blocks are all registered
 * when the event fires, so devices of later areas are covered without registering anything themselves. Storage cables
 * get their info through Lib's conduit info.
 */
@EventBusSubscriber(modid = MaxtechStorage.MOD_ID)
final class StorageCapabilities {
    private StorageCapabilities() {
    }

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        List<Block> devices = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof StorageDeviceBlock) {
                devices.add(block);
            }
        }
        if (devices.isEmpty()) {
            return;
        }
        Block[] blocks = devices.toArray(Block[]::new);
        event.registerBlock(Capabilities.Energy.BLOCK,
                (level, pos, state, blockEntity,
                        side) -> blockEntity instanceof StorageDeviceBlockEntity device ? device.feHandler() : null,
                blocks);
        BlockCapability<EnergyEndpoint, @Nullable Direction> endpoint = NeoForgeBlockLookup.of(EnergyLookups.BLOCK)
                .capability();
        event.registerBlock(endpoint, (level, pos, state, blockEntity,
                side) -> blockEntity instanceof StorageDeviceBlockEntity device ? device.energyEndpoint() : null,
                blocks);
        BlockCapability<BlockInfoProvider, @Nullable Direction> info = NeoForgeBlockLookup.of(InfoLookups.BLOCK)
                .capability();
        event.registerBlock(info, (level, pos, state, blockEntity,
                side) -> blockEntity instanceof StorageDeviceBlockEntity device ? device : null, blocks);
    }
}
